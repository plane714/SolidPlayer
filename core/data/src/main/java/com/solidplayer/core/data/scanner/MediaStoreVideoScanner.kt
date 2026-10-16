package com.solidplayer.core.data.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MediaStore 索引到的一个视频。 */
data class MediaStoreVideo(
    val path: String,
    val contentUri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val dateAdded: Long,
    val durationMs: Long,
)

/**
 * 查系统媒体库索引。
 *
 * 这条路的价值有两个：
 * 1. **Android 10 兜底** —— 那一个版本拿不到「所有文件访问」，`File` 读不到共享目录，
 *    只能靠 MediaStore；
 * 2. **元信息便宜** —— 时长、入库时间都在库里，不用为每个文件打开一次容器去探。
 *
 * 所以它在磁盘遍历成立时也不白白浪费：用来给遍历到的文件补时长与入库时间（按绝对路径对齐）。
 */
class MediaStoreVideoScanner(private val resolver: ContentResolver) {

    suspend fun scan(): List<MediaStoreVideo> = withContext(Dispatchers.IO) {
        val collection = videoCollection()
        val projection = buildProjection()
        val selection = buildSelection()

        val out = ArrayList<MediaStoreVideo>(512)
        val cursor = runCatching {
            resolver.query(collection, projection, selection, null, SORT_ORDER)
        }.getOrNull() ?: return@withContext emptyList()

        cursor.use {
            val idIndex = it.getColumnIndex(MediaStore.Video.Media._ID)
            val nameIndex = it.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            val dataIndex = it.getColumnIndex(MediaStore.Video.Media.DATA)
            val sizeIndex = it.getColumnIndex(MediaStore.Video.Media.SIZE)
            val modifiedIndex = it.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
            val addedIndex = it.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            val durationIndex = it.getColumnIndex(MediaStore.Video.Media.DURATION)
            val relativeIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
            } else {
                -1
            }

            while (it.moveToNext()) {
                val id = if (idIndex >= 0) it.getLong(idIndex) else continue
                val displayName = if (nameIndex >= 0) it.getString(nameIndex).orEmpty() else continue
                if (displayName.isEmpty() || !VideoExtensions.isVideo(displayName)) continue

                val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else 0L
                if (size <= 0L) continue

                val path = resolvePath(it, dataIndex, relativeIndex, displayName) ?: continue

                out += MediaStoreVideo(
                    path = path,
                    contentUri = ContentUris.withAppendedId(collection, id),
                    displayName = displayName,
                    sizeBytes = size,
                    // MediaStore 的时间戳是**秒**，全项目统一用毫秒
                    lastModified = secondsToMillis(it, modifiedIndex),
                    dateAdded = secondsToMillis(it, addedIndex),
                    durationMs = if (durationIndex >= 0 && !it.isNull(durationIndex)) {
                        it.getLong(durationIndex)
                    } else {
                        0L
                    },
                )
            }
        }
        out
    }

    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun buildProjection(): Array<String> {
        val base = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            base += MediaStore.Video.Media.RELATIVE_PATH
        }
        return base.toTypedArray()
    }

    private fun buildSelection(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.SIZE} > 0 AND ${MediaStore.Video.Media.IS_PENDING} = 0"
        } else {
            "${MediaStore.Video.Media.SIZE} > 0"
        }

    /**
     * 绝对路径。
     *
     * `DATA` 列在 API 29+ 已标记废弃，但绝大多数设备仍会返回真实路径；
     * 拿不到时用 `RELATIVE_PATH + DISPLAY_NAME` 拼回来（拼出来的是 `/storage/emulated/0/...`
     * 形式，对主卷是对的，对 SD 卡只能靠 `DATA`）。
     */
    private fun resolvePath(
        cursor: Cursor,
        dataIndex: Int,
        relativeIndex: Int,
        displayName: String,
    ): String? {
        if (dataIndex >= 0 && !cursor.isNull(dataIndex)) {
            cursor.getString(dataIndex)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (relativeIndex < 0) return null
        val relative = cursor.getString(relativeIndex)?.takeIf { it.isNotBlank() } ?: return null
        val root = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }
            .getOrNull()
            ?: return null
        return "$root/${relative.trimStart('/')}$displayName"
    }

    private fun secondsToMillis(cursor: Cursor, index: Int): Long {
        if (index < 0 || cursor.isNull(index)) return 0L
        return cursor.getLong(index) * 1000L
    }

    private companion object {
        const val SORT_ORDER = "${MediaStore.Video.Media.DATE_ADDED} DESC"
    }
}
