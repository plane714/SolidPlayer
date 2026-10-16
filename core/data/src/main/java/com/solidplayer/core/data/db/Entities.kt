package com.solidplayer.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 实体（文档 §7.1）。
 *
 * 文档里给了完整的建表 DDL 作为「设计意图的表达」，并明确 **Room Entity 才是唯一事实来源**。
 * 这里按 Room 惯例使用 camelCase 属性名（Room 默认列名即属性名），
 * 与 DDL 的 snake_case 是同一份结构的两种写法。
 *
 * `exportSchema = true` 已开启，schema JSON 输出到 `core/data/schemas/`，纳入版本管理以便写 Migration。
 */

/** 媒体文件表：核心表，一个文件对应一个 episodeId */
@Entity(
    tableName = "media_file",
    indices = [
        // UNIQUE(uri)：一个文件只能有一行，扫描时靠它做去重
        Index(value = ["uri"], unique = true),
        Index("fileHash"),
        Index("episodeId"),
        Index("documentId"),
        // 媒体库首页按目录聚合（GROUP BY folderPath），必须走索引
        Index("folderPath"),
    ],
    // 约束 B：episodeId 持久化在这里，第二次打开同一个文件不再走网络识别
)
data class MediaFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** content:// URI */
    val uri: String,
    /** DocumentsContract 的 documentId，用于扫描去重 */
    val documentId: String? = null,
    /** 文件名（含扩展名） */
    val displayName: String,
    val relativePath: String? = null,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val durationMs: Long = 0L,
    /** 前 16MB 的 MD5 */
    val fileHash: String? = null,
    val parsedTitle: String? = null,
    val parsedSeason: Int? = null,
    /** 字符串，兼容 C1 / S1 / O1 */
    val parsedEpisode: String? = null,
    val animeId: Long? = null,
    /** ★ 识别结果：弹幕库编号 */
    val episodeId: Long? = null,
    /** none / hash / filename / manual */
    val matchType: String = MatchType.NONE,
    val confidence: Float = 0f,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,

    // ── DB v2：自动发现（T3 媒体库首页）新增 ──────────────────────────
    //
    // 这六列的存在理由：媒体库要**按目录聚合**，而目录是扫描出来的、不是用户配的，
    // 所以必须跟着文件一起落库；否则每次进首页都要重扫一遍全机才能算出「N 个视频」。
    // v1 里 SAF 扫描的旧行这些列全为 null，**不会**出现在新的文件夹页面上（详见 MIGRATION_1_2）。

    /** 所在目录的绝对路径，媒体库按它聚合 */
    val folderPath: String? = null,
    /** 所在目录的显示名（存储根第一层用「内部存储」） */
    val folderName: String? = null,
    /** 文件进入设备的时间（毫秒）；首页红角标按它判「NEW」 */
    @ColumnInfo(defaultValue = "0")
    val dateAdded: Long = 0L,
    /** 同目录旁挂字幕的格式，逗号分隔，如 `SRT,SSA` */
    val subtitleFormats: String? = null,
    /** 来源：`disk` / `mediastore` / `saf` */
    val source: String? = null,
    /**
     * 最近一次被扫描命中的时刻；扫完把没更新的行删掉，就等于同步了「文件已删除」。
     *
     * `defaultValue = "0"` 是**必须**的：`ALTER TABLE … ADD COLUMN … NOT NULL` 在非空表上
     * 没有默认值就直接报错，所以 `MIGRATION_1_2` 里必须带 `DEFAULT 0`；
     * 实体这边同步标上，导出 schema 才与迁移出来的表结构严格一致（否则 Room 的
     * 运行时校验虽然能过 —— 它只在实体侧声明了默认值时才比对 —— 但 schema 文件会与迁移对不上）。
     */
    @ColumnInfo(defaultValue = "0")
    val lastSeenAt: Long = 0L,

    // ── DB v3：NEW 角标的「已看过」标记 ──────────────────────────────
    //
    // 「NEW」原来是纯时间窗口（`dateAdded` 7 天内），用户点开看过的视频仍然一直挂着 NEW，
    // 文件夹角标也不会自己减下去 —— 那个数字就失去了「还有多少没看」的含义。
    // v3 加这一列把「入库时间新」与「用户还没点开过」两件事分开：
    // NEW = 7 天窗口内 **且** lastViewedAt = 0（判定口径唯一收敛在 [isNew]）。

    /**
     * 用户最近一次**打开该文件**（进入播放页）的时刻；`0` = 从未打开过。
     *
     * `defaultValue = "0"` 与 `dateAdded` / `lastSeenAt` 同理：`ALTER TABLE … ADD COLUMN … NOT NULL`
     * 在非空表上必须带默认值，实体侧同步标上导出 schema 才与迁移出来的表结构一致。
     */
    @ColumnInfo(defaultValue = "0")
    val lastViewedAt: Long = 0L,
)

/**
 * 「NEW」角标的**唯一判定口径**。
 *
 * 两个条件缺一不可：
 * - `dateAdded >= newSince`：入库时间落在 7 天窗口内（[com.solidplayer.core.data.repository.VideoLibraryRepository.NEW_WINDOW_MS]）；
 * - `lastViewedAt == 0L`：用户还没点开过这个文件。
 *
 * `dateAdded` 为 0 的行（v1 的 SAF 旧行、磁盘遍历拿不到入库时间的行）**不算 NEW** ——
 * 0 不会 >= 一个正数窗口下界，这个退化情形天然被挡住，不需要额外判断。
 */
fun MediaFileEntity.isNew(newSince: Long): Boolean =
    lastViewedAt == 0L && dateAdded >= newSince

object MatchType {
    const val NONE = "none"
    const val HASH = "hash"
    const val FILENAME = "filename"

    /** 手动指定优先级最高，重扫不得覆盖 */
    const val MANUAL = "manual"
}

/** 作品表 */
@Entity(tableName = "anime")
data class AnimeEntity(
    /** 来自 API */
    @PrimaryKey val animeId: Long,
    val title: String,
    val type: String? = null,
    val imageUrl: String? = null,
    val rating: Double = 0.0,
    val isOnAir: Boolean = false,
    val updatedAt: Long = 0L,
)

/** 剧集表 */
@Entity(
    tableName = "episode",
    indices = [Index("animeId")],
    foreignKeys = [
        ForeignKey(
            entity = AnimeEntity::class,
            parentColumns = ["animeId"],
            childColumns = ["animeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpisodeEntity(
    @PrimaryKey val episodeId: Long,
    val animeId: Long,
    val episodeTitle: String? = null,
    /** 短标题，可含字母 */
    val episodeNumber: String? = null,
    val airDate: String? = null,
)

/**
 * 弹幕缓存索引（原始弹幕另存为文件，**不入库**）。
 * 一条弹幕库动辄上万条记录，进 SQLite 会让媒体库查询变慢（文档 §7.2）。
 */
@Entity(tableName = "danmaku_cache")
data class DanmakuCacheEntity(
    @PrimaryKey val episodeId: Long,
    val source: String = "dandanplay",
    val withRelated: Boolean = true,
    val chConvert: Int = 0,
    /** 弹幕条数，0 表示空缓存（空结果也要缓存，且 TTL 更长） */
    val count: Int = 0,
    /** 原始 JSON 落盘路径 */
    val filePath: String,
    val fetchedAt: Long,
    val etag: String? = null,
)

/** 播放历史 */
@Entity(
    tableName = "play_history",
    foreignKeys = [
        ForeignKey(
            entity = MediaFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaFileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlayHistoryEntity(
    @PrimaryKey val mediaFileId: Long,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 每集的弹幕时轴偏移（只有这项跟内容绑定，所以放 DB；样式偏好放 DataStore） */
    val danmakuOffsetMs: Long = 0L,
    val updatedAt: Long = 0L,
)
