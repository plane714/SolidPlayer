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
import androidx.compose.foundation.layout.Box
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 抽帧位置：第 1 秒。很多视频首帧是黑场，取第 0 帧的缩略图常常是一片黑。 */
private const val FRAME_AT_US = 1_000_000L

/**
 * 视频缩略图缓存。
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

    /** 取过且拿不到的 uri：记下来，别再试。 */
    private val failed = HashSet<String>()

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
 * 优先 `ContentResolver.loadThumbnail`（API 29+）：系统维护好的缩略图库，
 * 命中时是纯文件读取，比解一遍视频容器快一个数量级 —— 但它**只认 `content://`**，
 * 磁盘遍历分支持有的是 `file://`，那种只能退回去抽帧。
 *
 * ⚠️ 两条分支交出来的图**必须都接近显示尺寸**：缓存的容量是按像素字节数算的
 * （对象内部的 `MAX_BYTES` 是 24MB），混进一张 1080p 的整帧就相当于占掉 30 多个名额。
 * `loadThumbnail` 自己会按 `Size` 缩，抽帧那条要在 [frameAt] 里自己缩。
 */
suspend fun loadVideoThumbnail(
    context: Context,
    uriString: String,
    widthPx: Int,
    heightPx: Int,
): Bitmap? = withContext(Dispatchers.IO) {
    if (uriString.isBlank() || widthPx <= 0 || heightPx <= 0) return@withContext null
    VideoThumbnailCache.get(uriString)?.let { return@withContext it }
    if (VideoThumbnailCache.isKnownFailure(uriString)) return@withContext null

    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
    val bitmap = decodeGate.withPermit {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme != "file") {
                context.contentResolver.loadThumbnail(uri, Size(widthPx, heightPx), null)
            } else {
                frameAt(context, uri, widthPx, heightPx)
            }
        }.getOrNull()
    }

    if (bitmap == null) {
        VideoThumbnailCache.markFailed(uriString)
        return@withContext null
    }
    // 两条分支都统一在这里再收一次口：`loadThumbnail` 通常已按 `Size` 缩好，但不同 ROM 的
    // 实现、以及请求尺寸小于系统缩略图下限时都可能给出更大的图 ——
    // 「进缓存的图必须接近显示尺寸」这条不变量不能在两个分支里各写一遍。
    // 已经够小的图会原样返回，不产生额外拷贝。
    val cached = downscale(bitmap, widthPx, heightPx)
    VideoThumbnailCache.put(uriString, cached)
    cached
}

private fun frameAt(context: Context, uri: Uri, widthPx: Int, heightPx: Int): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (uri.scheme == "file") {
            retriever.setDataSource(uri.path)
        } else {
            retriever.setDataSource(context, uri)
        }
        // 取第 1 秒：很多视频首帧是黑场，缩略图会是一片黑
        val frame = retriever.getFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
            ?: return null
        // `getFrameAtTime` 交出来的是**整张关键帧**（1080p 一张 ≈ 8MB，4K 更多），
        // 直接缓存会让 24MB 的 LRU 只装得下两三条 —— 缩到显示尺寸再交出去。
        downscale(frame, widthPx, heightPx)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * 等比缩到不超过 [widthPx] × [heightPx]。
 *
 * 用**等比**而不是撑满：竖屏视频撑进 16:9 的盒子会被压扁，而这里没有编解码信息
 * 可以拿来算裁切（裁切交给上层的 `ContentScale.Crop` 做，它至少是按比例裁）。
 */
private fun downscale(source: Bitmap, widthPx: Int, heightPx: Int): Bitmap {
    val scale = minOf(widthPx.toFloat() / source.width, heightPx.toFloat() / source.height)
    if (scale >= 1f) return source
    val target = Bitmap.createScaledBitmap(
        source,
        (source.width * scale).roundToInt().coerceAtLeast(1),
        (source.height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
    // 原图是几十 MB 的整帧，缩完立刻放掉，别等 GC
    if (target !== source) source.recycle()
    return target
}

/**
 * 列表里的缩略图。拿不到就退化成一块深色底 + 胶片图标，**不留白**——
 * 留白会让整列行高看起来参差不齐。
 */
@Composable
fun VideoThumbnail(
    uri: String,
    modifier: Modifier = Modifier,
    widthPx: Int = 320,
    heightPx: Int = 180,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf(VideoThumbnailCache.get(uri)) }

    LaunchedEffect(uri) {
        if (bitmap == null) {
            bitmap = loadVideoThumbnail(context, uri, widthPx, heightPx)
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
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
