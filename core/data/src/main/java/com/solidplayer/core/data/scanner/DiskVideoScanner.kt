package com.solidplayer.core.data.scanner

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** 磁盘遍历发现的一个视频（还没合并 MediaStore 元信息）。 */
data class DiskVideoFile(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val subtitleFormats: List<String>,
)

/**
 * 直接遍历共享存储找视频。
 *
 * 走 `java.io.File` 而不是 MediaStore，是为了拿到「所有文件访问」权限后的**完整覆盖**：
 * 隐藏目录、带 `.nomedia` 的目录、系统还没索引到的新拷贝文件，MediaStore 都可能漏掉。
 *
 * 只在 [canTraverse] 成立时调用（见 [VideoScanner]）：
 * - Android 11+（API 30）：需要 `MANAGE_EXTERNAL_STORAGE`；
 * - Android 9 及以下（API ≤ 28）：需要 `READ_EXTERNAL_STORAGE`；
 * - **Android 10（API 29）拿不到该权限**，分区存储强制生效，`File` 读不到共享目录，
 *   这条分支会整体退化成 MediaStore 索引（这就是那一个版本需要单独兜底的原因）。
 */
@Singleton
class DiskVideoScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun scan(onFolder: (String) -> Unit = {}): List<DiskVideoFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<DiskVideoFile>(512)
        val roots = StorageRoots(context).roots()
        for (root in roots) {
            walk(root, out, onFolder)
            if (out.size >= MAX_FILES) break
        }
        out.sortBy { it.path }
        out
    }

    private suspend fun walk(dir: File, out: MutableList<DiskVideoFile>, onFolder: (String) -> Unit) {
        coroutineContext.ensureActive()
        if (out.size >= MAX_FILES) return

        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        if (children.isEmpty()) return

        // 先扫一遍本层：视频要拿同层的字幕文件算角标，所以不能边走边出结果
        val subtitleBases = ArrayList<Pair<String, String>>(8)
        val directories = ArrayList<File>(8)

        for (child in children) {
            val name = child.name
            if (name.startsWith(".")) continue

            if (child.isDirectory) {
                // Android/data 与 Android/obb 是各应用的私有区，里面全是播放不了的缓存、量极大；
                // Android/media 则是正常的共享媒体区（微信 / WhatsApp 下载的视频就落在这里），必须保留。
                // 所以只掐掉这两个目录，而不是整棵 Android 树。
                val isAppPrivate = child.parentFile?.name == ANDROID_DIR && name in APP_PRIVATE_DIRS
                if (isAppPrivate) continue
                directories += child
                continue
            }

            val ext = name.substringAfterLast('.', "").lowercase()
            when {
                ext in SUBTITLE_EXTENSIONS -> subtitleBases += name.substringBeforeLast('.') to ext
                ext in VideoExtensions.SUPPORTED -> Unit // 下一轮统一处理
            }
        }

        for (child in children) {
            if (out.size >= MAX_FILES) return
            val name = child.name
            if (name.startsWith(".") || child.isDirectory) continue
            if (!VideoExtensions.isVideo(name)) continue

            val size = child.length()
            if (size <= 0L) continue

            out += DiskVideoFile(
                path = child.absolutePath,
                displayName = name,
                sizeBytes = size,
                lastModified = child.lastModified(),
                subtitleFormats = matchSubtitles(name.substringBeforeLast('.'), subtitleBases),
            )
        }

        for (sub in directories) {
            if (out.size >= MAX_FILES) return
            onFolder(sub.absolutePath)
            walk(sub, out, onFolder)
        }
    }

    /**
     * 旁挂字幕的匹配规则：`xxx.mkv` 对应 `xxx.srt`，也对应 `xxx.zh.srt` / `xxx.chs.ass`。
     * 即「字幕文件去掉扩展名之后，等于视频名，或比视频名多一段 `.语言` 后缀」。
     */
    private fun matchSubtitles(videoBase: String, subtitleBases: List<Pair<String, String>>): List<String> {
        if (subtitleBases.isEmpty()) return emptyList()
        val prefix = "$videoBase."
        return subtitleBases
            .asSequence()
            .filter { (base, _) -> base == videoBase || base.startsWith(prefix) }
            .map { (_, ext) -> ext.uppercase() }
            .distinct()
            .sorted()
            .toList()
    }

    private companion object {
        /** 目录深度与文件数上限：防止误扫到奇怪的深层目录时卡死 */
        const val MAX_FILES = 20_000

        const val ANDROID_DIR = "Android"
        val APP_PRIVATE_DIRS = setOf("data", "obb")
        val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "sub", "idx", "vtt", "txt")
    }
}
