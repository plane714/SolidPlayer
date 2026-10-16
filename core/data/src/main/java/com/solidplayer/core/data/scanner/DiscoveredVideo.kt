package com.solidplayer.core.data.scanner

/**
 * 自动发现到的一个视频文件。
 *
 * 与 [MediaFileEntry]（SAF 目录扫描）的区别：
 * - 这个模型**带所在目录信息**（`folderPath` / `folderName`），媒体库首页要按目录聚合；
 * - 它由「磁盘遍历」与「MediaStore 索引」两条来源合并而来，所以带 [source] 标记。
 *
 * `path` 是绝对路径，同时作为**跨次扫描的稳定去重键**写入 `media_file.documentId`。
 * 之所以不用 `uri` 去重：MediaStore 索引到的文件用 `content://media/...`，
 * 没被索引到的只能用 `file://...`，同一文件在两次扫描里可能来自不同分支。
 *
 * @param uri       交给 Media3 播放 / `ContentResolver` 算哈希 / `loadThumbnail` 取缩略图
 * @param path      绝对路径（稳定键）
 * @param folderPath 父目录绝对路径，媒体库按它聚合
 * @param folderName 父目录名（存储根直接放在第一层的文件，名字取「内部存储」/卷名）
 * @param dateAdded 文件进入设备的时间：MediaStore 有 `DATE_ADDED` 就用它，
 *                  否则退化为 `lastModified`。首页的「NEW」角标按它判定
 * @param durationMs 时长，取不到为 0（磁盘遍历分支要等真正播放/识别时再探）
 * @param subtitleFormats 同目录下的旁挂字幕格式（`SRT` / `ASS` / `TXT`…），按字母序去重
 */
data class DiscoveredVideo(
    val uri: String,
    val path: String,
    val displayName: String,
    val folderPath: String,
    val folderName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val dateAdded: Long,
    val durationMs: Long,
    val subtitleFormats: List<String>,
    val source: String,
)

/** [DiscoveredVideo.source] 的取值。 */
object VideoSource {
    /** 磁盘遍历发现（只有在「所有文件访问」/ 旧系统读权限下才可能） */
    const val DISK = "disk"

    /** 只来自 MediaStore 索引（分区存储下无法遍历目录时的唯一来源） */
    const val MEDIASTORE = "mediastore"
}
