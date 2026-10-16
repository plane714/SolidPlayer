package com.solidplayer.core.data.scanner

import android.net.Uri

/**
 * 扫描到的本地视频条目。
 *
 * 全部走 SAF / `ContentResolver`，**没有真实文件路径** ——
 * Android 10+ 拿到的 `content://` URI 无法转成 `File`（文档 §12.3）。
 */
data class MediaFileEntry(
    /** 可直接交给 Media3 播放，也可交给 ContentResolver 计算哈希 */
    val uri: Uri,
    /** DocumentsContract 的 documentId，用于跨次扫描去重 */
    val documentId: String,
    /** 文件名（含扩展名） */
    val displayName: String,
    /** 相对路径，用于分组展示 */
    val relativePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    val uriString: String get() = uri.toString()
}
