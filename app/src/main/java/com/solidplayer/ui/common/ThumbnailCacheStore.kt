package com.solidplayer.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频缩略图概况（设置页展示 / 清除用）。
 *
 * `entries` 是落盘的图片张数，`bytes` 是这些图片的总占用 —— 用户想清缓存时关心的
 * 就是「这些东西占了我多少存储」。
 */
data class ThumbnailCacheStats(
    val entries: Int = 0,
    val bytes: Long = 0L,
) {
    val isEmpty: Boolean get() = entries == 0 && bytes == 0L
}

/**
 * 缩略图落盘缓存。
 *
 * 为什么要有它：文件列表每次进出都会重新抽帧，而抽帧（尤其是 `file://` 分支下
 * `MediaMetadataRetriever` 解容器）很慢；只靠内存 LRU 的话，进程一退或缓存被挤掉，
 * 下次进目录又得全部重来。落盘之后，第二次进入目录是纯文件读取。
 *
 * 和弹幕缓存（[com.solidplayer.core.data.repository.DanmakuCacheStore]）不同，缩略图
 * 是**纯派生数据**，随时可以重新生成，所以放在 `cacheDir` 而不是 `filesDir`：
 * 系统存储紧张时可以自行回收，不会误删用户数据。
 *
 * 文件名用 [cacheKey] 的 MD5，避免 URI 里的 `:` `/` `?` 等字符直接做文件名（Windows /
 * 某些 ROM 上会出问题）；键里已经带了尺寸，因此同一视频的不同显示尺寸互不覆盖。
 */
@Singleton
class ThumbnailCacheStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val directory: File
        get() = File(context.cacheDir, CACHE_DIRNAME)

    private val writes = AtomicInteger(0)

    /** 读一张缩略图。文件不存在 / 解码失败都返回 null，由调用方回退到重新抽帧。 */
    suspend fun read(cacheKey: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = fileFor(cacheKey)
        if (!file.isFile) return@withContext null
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /** 把一张缩略图写盘。失败无所谓（下次再抽一遍），所以只返回是否写成功。 */
    suspend fun write(cacheKey: String, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val target = fileFor(cacheKey)
            // 先写临时文件再改名：避免进程被杀时留下半张图，下次 decode 出花屏
            val tmp = File(directory, target.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            // 每写若干张才做一次容量检查，避免每张都遍历一遍目录（长列表滑动时的 O(n²)）
            if (writes.incrementAndGet() % TRIM_CHECK_INTERVAL == 0) trimToMaxBytes()
            true
        }.getOrDefault(false)
    }

    /** 清空全部缩略图，返回删掉的张数。 */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        val files = runCatching { directory.listFiles() }.getOrNull() ?: return@withContext 0
        var removed = 0
        for (file in files) {
            if (file.isFile && runCatching { file.delete() }.getOrDefault(false)) removed++
        }
        removed
    }

    /** 缓存概况：张数 + 落盘占用。 */
    suspend fun stats(): ThumbnailCacheStats = withContext(Dispatchers.IO) {
        val files = runCatching { directory.listFiles() }.getOrNull()
            ?: return@withContext ThumbnailCacheStats()
        var entries = 0
        var bytes = 0L
        for (file in files) {
            if (!file.isFile) continue
            entries++
            bytes += runCatching { file.length() }.getOrDefault(0L)
        }
        ThumbnailCacheStats(entries = entries, bytes = bytes)
    }

    private fun fileFor(cacheKey: String): File = File(directory, md5(cacheKey) + ".jpg")

    /** 超过容量上限时按「最久未修改」逐个删，直到回到上限以内。 */
    private fun trimToMaxBytes() {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES) break
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= length
        }
    }

    private companion object {
        const val CACHE_DIRNAME = "thumbnails"
        const val THUMB_QUALITY = 90

        /** 128MB 上限：一张 372×210 的 JPEG 约 15~30KB，够放几千张。 */
        const val MAX_BYTES = 128L * 1024L * 1024L

        /** 每写这么多张检查一次容量，兼顾及时性与性能。 */
        const val TRIM_CHECK_INTERVAL = 64

        fun md5(text: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            val builder = StringBuilder(digest.size * 2)
            for (byte in digest) builder.append("%02x".format(byte))
            return builder.toString()
        }
    }
}

/**
 * 从 Compose 里拿 [ThumbnailCacheStore] 的入口。
 *
 * 缩略图组件是纯 Composable、没有 ViewModel，用 Hilt 的 EntryPoint 取单例比把 store
 * 顺着五层参数一路传下去干净；设置页那边则直接走构造注入。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThumbnailCacheStoreEntryPoint {
    fun thumbnailCacheStore(): ThumbnailCacheStore
}

@Composable
fun rememberThumbnailCacheStore(): ThumbnailCacheStore {
    val context = LocalContext.current
    return remember(context) {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            ThumbnailCacheStoreEntryPoint::class.java,
        ).thumbnailCacheStore()
    }
}
