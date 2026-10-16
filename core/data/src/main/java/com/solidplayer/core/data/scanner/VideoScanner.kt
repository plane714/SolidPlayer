package com.solidplayer.core.data.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动发现全机视频（媒体库首页的数据源）。
 *
 * 两条来源**合并**：
 * - 磁盘遍历（[DiskVideoScanner]）—— 覆盖最全，包括隐藏目录与系统没索引到的文件，
 *   但拿不到时长/入库时间，也拿不到能取缩略图的 `content://`；
 * - MediaStore 索引（[MediaStoreVideoScanner]）—— 元信息齐全，但会漏掉 `.nomedia` 之类。
 *
 * 合并策略：**以磁盘遍历为准，用 MediaStore 按绝对路径对齐补元信息**；
 * MediaStore 有、遍历没覆盖到的再补一遍（分区存储下遍历不可用时它就变成唯一来源）。
 */
@Singleton
class VideoScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val disk: DiskVideoScanner,
    private val mediaStore: MediaStoreVideoScanner,
) {

    /**
     * 当前是否有能力直接遍历共享存储。
     *
     * - API 30+：必须有「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`）。
     *   `targetSdk` 已经远高于 30，分区存储是强制的，只给读权限也用 `File` 读不到共享目录。
     * - API ≤ 28：没有分区存储，`READ_EXTERNAL_STORAGE` 就够。
     * - API 29：`MANAGE_EXTERNAL_STORAGE` 尚不存在，又已经强制分区存储 —— 无解，只能退化。
     */
    fun canTraverse(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> hasReadPermission()
        else -> false
    }

    private fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun scan(onFolder: (String) -> Unit = {}): List<DiscoveredVideo> = withContext(Dispatchers.IO) {
        val rootSet = StorageRoots(context).roots().map { it.absolutePath }.toSet()
        val indexed = mediaStore.scan().associateBy { it.path }
        val merged = LinkedHashMap<String, DiscoveredVideo>()
        val traversable = canTraverse()

        if (traversable) {
            for (file in disk.scan(onFolder)) {
                val hit = indexed[file.path]
                val (folderPath, folderName) = VideoFolders.folderOf(file.path, rootSet)
                merged[file.path] = DiscoveredVideo(
                    // 索引里有就用 content:// —— 缩略图只能靠它（loadThumbnail 不认 file://）
                    uri = hit?.contentUri?.toString() ?: Uri.fromFile(File(file.path)).toString(),
                    path = file.path,
                    displayName = file.displayName,
                    folderPath = folderPath,
                    folderName = folderName,
                    sizeBytes = file.sizeBytes,
                    lastModified = file.lastModified,
                    // MediaStore 的 DATE_ADDED 才是真正的「进入设备的时间」，
                    // 拿不到时退化成文件修改时间
                    dateAdded = hit?.dateAdded?.takeIf { it > 0L } ?: file.lastModified,
                    durationMs = hit?.durationMs ?: 0L,
                    subtitleFormats = file.subtitleFormats,
                    source = VideoSource.DISK,
                )
            }
        }

        for (video in indexed.values) {
            if (merged.containsKey(video.path)) continue
            if (isAppPrivate(video.path)) continue
            val (folderPath, folderName) = VideoFolders.folderOf(video.path, rootSet)
            merged[video.path] = DiscoveredVideo(
                uri = video.contentUri.toString(),
                path = video.path,
                displayName = video.displayName,
                folderPath = folderPath,
                folderName = folderName,
                sizeBytes = video.sizeBytes,
                lastModified = video.lastModified,
                dateAdded = video.dateAdded.takeIf { it > 0L } ?: video.lastModified,
                durationMs = video.durationMs,
                // 分区存储下读不到同目录的字幕文件，这项只能是空的
                subtitleFormats = emptyList(),
                source = VideoSource.MEDIASTORE,
            )
        }

        merged.values.sortedWith(compareBy({ it.folderPath.lowercase() }, { it.displayName.lowercase() }))
    }

    /** `Android/data` 与 `Android/obb` 里的东西是应用私有缓存，不进媒体库。 */
    private fun isAppPrivate(path: String): Boolean =
        path.contains("/Android/data/") || path.contains("/Android/obb/")
}
