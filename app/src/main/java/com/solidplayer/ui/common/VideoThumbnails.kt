package com.solidplayer.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** 抽帧位置：第 1 秒。很多视频首帧是黑场，取第 0 帧的缩略图常常是一片黑。 */
private const val FRAME_AT_US = 1_000_000L

/**
 * 缩略图缓存键：`uri@宽x高`。
 *
 * 必须带上尺寸：缩略图是**按显示尺寸**抽的，同一个视频在列表（小图）和别处（大图）
 * 用同一把键的话，先缓存的小图会被后一次请求直接命中，结果就是「越大越糊」。
 */
internal fun thumbnailCacheKey(uriString: String, widthPx: Int, heightPx: Int): String =
    "$uriString@${widthPx}x$heightPx"

/**
 * 视频缩略图的**内存** LRU 缓存（磁盘那层见 [ThumbnailCacheStore]）。
 *
 * 为什么要自己写一个：项目是**离线构建**，Gradle 缓存里没有 Coil / Glide，加依赖直接构建失败；
 * 而这里需要的也只是「给一个 uri 要一张小图」，平台 API 就够了。
 *
 * 三件事必须做，否则长列表会卡：
 * 1. **内存 LRU**，按像素字节数计量（张数上限对 1080p 帧毫无意义）；
 * 2. **失败记忆** —— 损坏的视频每次重试都要打开一次容器，滑动时会被反复触发；
 * 3. **按显示尺寸抽帧**，不要把 4K 关键帧整张塞进 Image。
 */
object VideoThumbnailCache {

    private const val MAX_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 取过且拿不到的 uri：记下来，别再试。并发抽帧下用并发集合，避免 HashSet 并发写坏。 */
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun get(key: String): Bitmap? = cache.get(key)

    fun isKnownFailure(key: String): Boolean = key in failed

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun markFailed(key: String) {
        failed += key
    }

    fun clear() {
        cache.evictAll()
        failed.clear()
    }
}

/**
 * 抽帧的并发闸门：**同时最多 2 路**。
 *
 * 为什么不让 `Dispatchers.IO` 全速跑：那里有 64 个线程，快速滑动一个目录时会一口气
 * 并起十几路 `MediaMetadataRetriever`，每一路的中间产物都是一整张关键帧。
 * 除了峰值内存，更直接的后果是**刚写进 LRU 的缩略图立刻被新来的挤掉** ——
 * 表现就是「每次进目录，缩略图都要重新加载一遍」。缩略图是后台预取，慢一点没关系，
 * 挤爆缓存才是真的会掉帧。
 */
private val decodeGate = Semaphore(2)

/**
 * 抽一帧当缩略图。
 *
 * 取值顺序：内存 LRU → 磁盘缓存 → 实际解码，解码后同时回填内存与磁盘。
 *
 * 优先 `ContentResolver.loadThumbnail`（API 29+）：系统维护好的缩略图库，
 * 命中时是纯文件读取，比解一遍视频容器快一个数量级 —— 但它**只认 `content://`**，
 * 磁盘遍历分支持有的是 `file://`，那种只能退回去抽帧。
 *
 * ⚠️ 两条分支交出来的图都会被 [coverCrop] 裁成**恰好等于显示盒子**的尺寸：
 * 缓存的容量是按像素字节数算的（对象内部的 `MAX_BYTES` 是 24MB），混进一张 1080p 的
 * 整帧就相当于占掉 30 多个名额，必须在这里收口。
 */
suspend fun loadVideoThumbnail(
    context: Context,
    store: ThumbnailCacheStore,
    uriString: String,
    widthPx: Int,
    heightPx: Int,
): Bitmap? = withContext(Dispatchers.IO) {
    if (uriString.isBlank() || widthPx <= 0 || heightPx <= 0) return@withContext null
    val key = thumbnailCacheKey(uriString, widthPx, heightPx)
    VideoThumbnailCache.get(key)?.let { return@withContext it }
    if (VideoThumbnailCache.isKnownFailure(key)) return@withContext null

    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
    val bitmap = decodeGate.withPermit {
        // 磁盘缓存命中：纯文件读取，比重新解一遍视频容器快得多。
        store.read(key) ?: runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme != "file") {
                // 请求一个「至少两倍盒子」的正方形，而不是精确的 width×height：
                // `loadThumbnail` 按**等比内接**生成，若直接要 372×210，竖屏视频只能拿到
                // 118×210 这种「宽不够」的小图，再被 Crop 拉大就发虚。给足余量后由下面的
                // [coverCrop] 自己裁成盒子尺寸，横竖屏都能拿到足够像素。
                val edge = maxOf(widthPx, heightPx) * 2
                context.contentResolver.loadThumbnail(uri, Size(edge, edge), null)
            } else {
                frameAt(context, uri)
            }
        }.getOrNull()?.let { decoded ->
            // 统一收口：裁成「恰好等于显示盒子」的图。这样上层的 ContentScale.Crop 不再需要
            // 放大，既清晰又把每张图的字节数固定在一个可预期的量级（LRU 才好按字节算容量）。
            val cropped = coverCrop(decoded, widthPx, heightPx)
            store.write(key, cropped)
            cropped
        }
    }

    if (bitmap == null) {
        VideoThumbnailCache.markFailed(key)
        return@withContext null
    }
    VideoThumbnailCache.put(key, bitmap)
    bitmap
}

private fun frameAt(context: Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (uri.scheme == "file") {
            retriever.setDataSource(uri.path)
        } else {
            retriever.setDataSource(context, uri)
        }
        // 取第 1 秒：很多视频首帧是黑场，缩略图会是一片黑
        retriever.getFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * 把整帧裁成**恰好** [widthPx] × [heightPx]。
 *
 * 先等比缩放到「刚好盖满」盒子（取 max 比例），再从中心裁掉多余的一条边。
 * 这样得到的图与列表里 `ContentScale.Crop` 的最终显示效果一致，但由我们提前裁好，
 * 显示时不再发生二次放大 —— 竖屏视频塞进 16:9 盒子也不会糊。
 *
 * 注意缩放用的中间图可能是几十 MB 的整帧，裁完就回收，别等 GC。
 */
private fun coverCrop(source: Bitmap, widthPx: Int, heightPx: Int): Bitmap {
    val scale = maxOf(
        widthPx.toFloat() / source.width,
        heightPx.toFloat() / source.height,
    )
    val scaledWidth = (source.width * scale).roundToInt().coerceAtLeast(widthPx)
    val scaledHeight = (source.height * scale).roundToInt().coerceAtLeast(heightPx)
    val scaled = if (scaledWidth == source.width && scaledHeight == source.height) {
        source
    } else {
        Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
    }
    val left = ((scaledWidth - widthPx) / 2).coerceAtLeast(0)
    val top = ((scaledHeight - heightPx) / 2).coerceAtLeast(0)
    val cropped = Bitmap.createBitmap(scaled, left, top, widthPx, heightPx)
    if (cropped !== scaled) scaled.recycle()
    if (source !== scaled && source !== cropped) source.recycle()
    return cropped
}

/**
 * 列表里的缩略图。拿不到就退化成一块深色底 + 胶片图标，**不留白**——
 * 留白会让整列行高看起来参差不齐。
 *
 * 抽帧尺寸**按组件实际占用的像素来**（[BoxWithConstraints] + 屏幕密度），不再写死
 * 320×180：高密度屏上一个 124dp×70dp 的盒子有 400 多像素宽，按 320 抽出来的图放大后
 * 必然发虚。按显示尺寸抽帧，既清晰又不会浪费内存。
 */
@Composable
fun VideoThumbnail(
    uri: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val store = rememberThumbnailCacheStore()

    BoxWithConstraints(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val heightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val key = thumbnailCacheKey(uri, widthPx, heightPx)
        var bitmap by remember(key) { mutableStateOf(VideoThumbnailCache.get(key)) }

        LaunchedEffect(key) {
            if (bitmap == null) {
                bitmap = loadVideoThumbnail(context, store, uri, widthPx, heightPx)
            }
        }

        val image = bitmap
        if (image == null) {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.45f),
            )
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
