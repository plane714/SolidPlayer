package com.solidplayer.core.data.db

import androidx.room.Embedded

/**
 * 媒体库列表投影：文件本体 + 从 `anime` / `episode` 表 join 出来的标题。
 *
 * 为什么要投影而不是让 UI 自己查两次：媒体库要按「番剧 → 集」分组展示，
 * 而标题分散在三张表里；一次 join 拿到，UI 层只做分组，不碰 SQL。
 *
 * `animeTitle` / `episodeTitle` 为 null = 该文件还没识别出对应记录（`LEFT JOIN`）。
 */
data class MediaFileDisplay(
    @Embedded val file: MediaFileEntity,
    val animeTitle: String? = null,
    val episodeTitle: String? = null,
    val episodeNumber: String? = null,
)

/**
 * 媒体库首页的一个「文件夹」（按 `media_file.folderPath` 聚合出来的一行）。
 *
 * `newCount` 是在 SQL 里用 `dateAdded >= :newSince` 数出来的 —— 判定窗口（7 天）
 * 由调用方传进来。阈值会在时间上「过期」，但列表本来就会随数据库变更重发，
 * 且这个窗口只影响角标颜色，不值得为它再摆一套定时刷新。
 */
data class VideoFolderSummary(
    val folderPath: String,
    val folderName: String? = null,
    /** 该目录下的视频总数 */
    val videoCount: Int = 0,
    /** 其中属于「NEW」窗口的数量 */
    val newCount: Int = 0,
    /** 目录里最近一次改动时间，用于排序/展示 */
    val latestModified: Long = 0L,
)
