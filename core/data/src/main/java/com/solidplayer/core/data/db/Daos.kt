package com.solidplayer.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaFileDao {

    @Query("SELECT * FROM media_file ORDER BY relativePath ASC, displayName ASC")
    fun observeAll(): Flow<List<MediaFileEntity>>

    @Query("SELECT * FROM media_file WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): MediaFileEntity?

    @Query("SELECT * FROM media_file WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): MediaFileEntity?

    /** 按稳定键（自动发现时存的是绝对路径）反查，用于换源时保住已有识别结果。 */
    @Query("SELECT * FROM media_file WHERE documentId = :documentId LIMIT 1")
    suspend fun findByDocumentId(documentId: String): MediaFileEntity?

    /** 按 id 批量取回（P2「仅重试失败项」用）。 */
    @Query("SELECT * FROM media_file WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<MediaFileEntity>

    /** 批量识别时用 hash 反查已有记录，避免重复请求（文档 §7.2） */
    @Query("SELECT * FROM media_file WHERE fileHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): MediaFileEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: MediaFileEntity): Long

    /**
     * 仅刷新扫描得到的文件元信息。
     * **刻意不动 `fileHash` / `durationMs` / `episodeId`** —— 那几项要么有成本、
     * 要么是持久化的识别结果（约束 B），不能被一次重扫抹掉。
     */
    @Query(
        """
        UPDATE media_file SET
            displayName = :displayName,
            relativePath = :relativePath,
            sizeBytes = :sizeBytes,
            lastModified = :lastModified,
            updatedAt = :now
        WHERE uri = :uri
        """,
    )
    suspend fun updateScannedMeta(
        uri: String,
        displayName: String,
        relativePath: String?,
        sizeBytes: Long,
        lastModified: Long,
        now: Long,
    )

    @Query(
        """
        UPDATE media_file SET
            displayName = :displayName,
            relativePath = :relativePath,
            sizeBytes = :sizeBytes,
            lastModified = :lastModified,
            durationMs = :durationMs,
            fileHash = :fileHash,
            updatedAt = :now
        WHERE uri = :uri
        """,
    )
    suspend fun updateFileMeta(
        uri: String,
        displayName: String,
        relativePath: String?,
        sizeBytes: Long,
        lastModified: Long,
        durationMs: Long,
        fileHash: String?,
        now: Long,
    )

    /**
     * 写入识别结果。
     * `matchType != 'manual'` 的条件保证 **手动指定的结果不会被重扫覆盖**（文档 §7.2）。
     *
     * @return 受影响行数，0 表示被 manual 保护挡下
     */
    @Query(
        """
        UPDATE media_file SET
            animeId = :animeId,
            episodeId = :episodeId,
            matchType = :matchType,
            confidence = :confidence,
            updatedAt = :now
        WHERE id = :id AND matchType != 'manual'
        """,
    )
    suspend fun updateMatch(
        id: Long,
        animeId: Long?,
        episodeId: Long?,
        matchType: String,
        confidence: Float,
        now: Long,
    ): Int

    /** 用户在播放页手动指定集数：matchType 置为 manual，此后不再被自动识别覆盖。 */
    @Query(
        """
        UPDATE media_file SET
            animeId = :animeId,
            episodeId = :episodeId,
            matchType = 'manual',
            confidence = 1.0,
            updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun bindManually(id: Long, animeId: Long?, episodeId: Long, now: Long)

    @Query("DELETE FROM media_file WHERE id = :id")
    suspend fun delete(id: Long)

    // ── P1：媒体库与批量识别 ─────────────────────────────────────

    /**
     * 媒体库列表：连带 join 出作品标题 / 剧集标题，供 UI 按「番剧 → 集」分组。
     *
     * 用 `LEFT JOIN` 是因为「未识别」的文件在 anime / episode 里根本没有对应行，
     * 用 INNER JOIN 会把它们直接从列表里抹掉。
     */
    @Query(
        """
        SELECT mf.*,
               a.title         AS animeTitle,
               e.episodeTitle  AS episodeTitle,
               e.episodeNumber AS episodeNumber
        FROM media_file mf
        LEFT JOIN anime   a ON a.animeId   = mf.animeId
        LEFT JOIN episode e ON e.episodeId = mf.episodeId
        ORDER BY mf.relativePath ASC, mf.displayName ASC
        """,
    )
    fun observeLibraryDetailed(): Flow<List<MediaFileDisplay>>

    /**
     * 同一番剧下**已识别**的文件，供播放页拼连播列表（「上一集 / 下一集」）。
     *
     * 只取 `episodeId IS NOT NULL`：没有集数就无从谈「下一集」，
     * 把未识别的文件混进来只会让连播顺序变得无法解释。
     *
     * 排序**不在 SQL 里做**：集数序用的是 [EpisodeOrder]，与媒体库分组是同一个比较器。
     * 用 `CAST(episodeNumber AS INTEGER)` 在 SQL 里拼一个「大致等价」的顺序，
     * 遇到 `C1` / `O1` 这类非数字集数就会和列表对不上。
     */
    @Query(
        """
        SELECT mf.*,
               a.title         AS animeTitle,
               e.episodeTitle  AS episodeTitle,
               e.episodeNumber AS episodeNumber
        FROM media_file mf
        LEFT JOIN anime   a ON a.animeId   = mf.animeId
        LEFT JOIN episode e ON e.episodeId = mf.episodeId
        WHERE mf.animeId = :animeId AND mf.episodeId IS NOT NULL
        """,
    )
    suspend fun siblingsOf(animeId: Long): List<MediaFileDisplay>

    /**
     * 待识别的文件（批量识别的输入）。
     *
     * `episodeId IS NULL` 覆盖「从没识别」和「识别失败」两种情况；
     * `matchType != 'manual'` 把手动绑定的排除在外 —— 那是用户明确指定的，自动识别不得碰。
     */
    @Query(
        """
        SELECT * FROM media_file
        WHERE episodeId IS NULL AND matchType != 'manual'
        ORDER BY relativePath ASC, displayName ASC
        """,
    )
    suspend fun findUnmatched(): List<MediaFileEntity>

    /**
     * 「重新识别」：清空所有**自动**识别结果，`manual` 的保留（文档 §7.2）。
     *
     * @return 受影响行数
     */
    @Query(
        """
        UPDATE media_file SET
            animeId = NULL,
            episodeId = NULL,
            matchType = 'none',
            confidence = 0,
            updatedAt = :now
        WHERE matchType != 'manual'
        """,
    )
    suspend fun clearAutoMatches(now: Long): Int

    // ── v2：自动发现的媒体库（按目录聚合）─────────────────────────

    /**
     * 媒体库首页：按所在目录聚合出「文件夹 + N 个视频 + N 个 NEW」。
     *
     * `COUNT(CASE WHEN … THEN 1 END)` 而不是 `SUM(CASE … THEN 1 ELSE 0 END)`：
     * 前者数的是「非 NULL」，Room 能直接映射成 Int；后者的聚合类型在 SQLite 里更宽松，
     * 映射回来要额外转换。
     *
     * `WHERE folderPath IS NOT NULL` 顺带把 v1 的 SAF 旧行挡在新的文件夹页之外。
     *
     * `newCount` 的口径与 [MediaFileEntity.isNew] **必须逐字对应**（7 天窗口 + 未看过）：
     * 一处算「点开即消」、另一处只算时间窗，角标就会和列表里的 NEW 对不上。
     */
    @Query(
        """
        SELECT folderPath,
               MAX(folderName) AS folderName,
               COUNT(*)        AS videoCount,
               COUNT(CASE WHEN dateAdded >= :newSince AND lastViewedAt = 0 THEN 1 END) AS newCount,
               MAX(lastModified) AS latestModified
        FROM media_file
        WHERE folderPath IS NOT NULL
        GROUP BY folderPath
        ORDER BY folderName COLLATE NOCASE ASC
        """,
    )
    fun observeFolderSummaries(newSince: Long): Flow<List<VideoFolderSummary>>

    // ── v3：NEW 角标的「已看过」标记 ──────────────────────────────

    /**
     * 记下「用户打开过这个文件」。
     *
     * 只动 [MediaFileEntity.lastViewedAt]：识别结果、扫描元信息、收藏之类一概不碰。
     * 写库会触发上面那条聚合查询重发，于是**行内 NEW 消失与文件夹角标 -1 是同一次刷新**，
     * 不需要 UI 侧做任何手工递减。
     */
    @Query("UPDATE media_file SET lastViewedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun markViewed(id: Long, now: Long)

    /** 某个目录下的全部视频，供文件夹详情页（图二）展示。 */
    @Query(
        """
        SELECT * FROM media_file
        WHERE folderPath = :folderPath
        ORDER BY displayName COLLATE NOCASE ASC
        """,
    )
    fun observeFolderVideos(folderPath: String): Flow<List<MediaFileEntity>>

    /**
     * 刷新一次「自动发现」扫到的元信息。
     *
     * 两个 `CASE WHEN` 是刻意的防御：
     * - `durationMs`：磁盘遍历分支拿不到时长（传 0），不能把上次已经从 MediaStore 补上的时长抹掉；
     * - `dateAdded`：同理由，0 不该覆盖已知的入库时间。
     *
     * **不碰 `episodeId` / `animeId` / `fileHash`** —— 那些是识别结果与贵计算，重扫不得清掉。
     */
    @Query(
        """
        UPDATE media_file SET
            displayName     = :displayName,
            folderPath      = :folderPath,
            folderName      = :folderName,
            dateAdded       = CASE WHEN :dateAdded > 0 THEN :dateAdded ELSE dateAdded END,
            subtitleFormats = :subtitleFormats,
            sizeBytes       = :sizeBytes,
            lastModified    = :lastModified,
            durationMs      = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END,
            source          = :source,
            lastSeenAt      = :lastSeenAt,
            updatedAt       = :now
        WHERE uri = :uri
        """,
    )
    suspend fun updateDiscoveredMeta(
        uri: String,
        displayName: String,
        folderPath: String,
        folderName: String,
        dateAdded: Long,
        subtitleFormats: String?,
        sizeBytes: Long,
        lastModified: Long,
        durationMs: Long,
        source: String,
        lastSeenAt: Long,
        now: Long,
    )

    /**
     * 清掉本轮扫描**没再看到**的自动发现行（文件已被删除 / 移走）。
     *
     * 用「扫描开始时刻」而不是「扫到的路径列表」来做删除条件：一次全机扫描动辄几千条路径，
     * `NOT IN (...)` 会撞上 SQLite 的变量数上限，而且拼出来的 SQL 又长又慢。
     *
     * `source IN ('disk','mediastore')` 把 v1 的 SAF 旧行排除在外 —— 那些行的 `lastSeenAt` 恒为 0，
     * 不加这个条件会在第一次扫描时被全部误删。
     */
    @Query("DELETE FROM media_file WHERE source IN ('disk', 'mediastore') AND lastSeenAt < :scanStartedAt")
    suspend fun deleteUnseenScanned(scanStartedAt: Long): Int
}

@Dao
interface DanmakuCacheDao {

    @Query("SELECT * FROM danmaku_cache WHERE episodeId = :episodeId LIMIT 1")
    suspend fun find(episodeId: Long): DanmakuCacheEntity?

    @Upsert
    suspend fun upsert(entity: DanmakuCacheEntity)

    @Query("DELETE FROM danmaku_cache WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: Long)

    @Query("DELETE FROM danmaku_cache WHERE fetchedAt < :before")
    suspend fun deleteFetchedBefore(before: Long)

    // ── P2：缓存统计与清理 ────────────────────────────────────────

    /** 全部索引行。过期判定要区分「空结果 / 命中」两种 TTL，所以放在 Kotlin 侧算（文档 §10）。 */
    @Query("SELECT * FROM danmaku_cache")
    suspend fun all(): List<DanmakuCacheEntity>

    @Query("SELECT COUNT(*) FROM danmaku_cache")
    suspend fun count(): Int

    @Query("DELETE FROM danmaku_cache")
    suspend fun deleteAll()
}

@Dao
interface PlayHistoryDao {

    @Query("SELECT * FROM play_history WHERE mediaFileId = :mediaFileId LIMIT 1")
    suspend fun find(mediaFileId: Long): PlayHistoryEntity?

    @Upsert
    suspend fun upsert(entity: PlayHistoryEntity)

    /** 只更新弹幕时轴偏移，不动播放进度 */
    @Query(
        """
        UPDATE play_history SET danmakuOffsetMs = :offsetMs, updatedAt = :now
        WHERE mediaFileId = :mediaFileId
        """,
    )
    suspend fun updateDanmakuOffset(mediaFileId: Long, offsetMs: Long, now: Long)

    /**
     * **只在还没有记录时**写入弹幕偏移（P2：应用服务端建议的 `shift`）。
     *
     * 用 `IGNORE` 而非 `Upsert` 是刻意的：`play_history` 只有 `danmakuOffsetMs` 一列，
     * 分不清这个值是服务端 shift 还是用户手调，所以**只要已经有行就整体放弃写入** ——
     * 宁可漏用一次服务端建议，也不能把用户辛苦校准的偏移覆盖掉。
     *
     * @return 受影响行数；0 表示已有记录，本次未写入
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOffsetIfAbsent(entity: PlayHistoryEntity): Long
}

@Dao
interface AnimeDao {

    @Upsert
    suspend fun upsertAnime(anime: AnimeEntity)

    @Upsert
    suspend fun upsertEpisodes(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM episode WHERE episodeId = :episodeId LIMIT 1")
    suspend fun findEpisode(episodeId: Long): EpisodeEntity?

    @Query("DELETE FROM anime WHERE animeId = :animeId")
    suspend fun deleteAnime(animeId: Long)
}
