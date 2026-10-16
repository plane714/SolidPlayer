package com.solidplayer.core.data.repository

import com.solidplayer.core.data.db.AnimeDao
import com.solidplayer.core.data.db.AnimeEntity
import com.solidplayer.core.data.db.EpisodeEntity
import com.solidplayer.core.data.db.MatchType
import com.solidplayer.core.data.db.MediaFileDao
import com.solidplayer.core.data.db.PlayHistoryDao
import com.solidplayer.core.data.db.PlayHistoryEntity
import com.solidplayer.core.data.di.IoDispatcher
import com.solidplayer.core.data.network.ApiResult
import com.solidplayer.core.data.network.DandanplayApi
import com.solidplayer.core.data.network.dto.BatchMatchRequest
import com.solidplayer.core.data.network.dto.MatchMode
import com.solidplayer.core.data.network.dto.MatchRequest
import com.solidplayer.core.data.network.dto.MatchResultV2
import com.solidplayer.core.data.network.dto.SearchEpisodeDetails
import com.solidplayer.core.data.network.dto.SearchEpisodesAnime
import com.solidplayer.core.data.network.mapResponse
import com.solidplayer.core.data.network.safeApiCall
import com.solidplayer.core.data.parser.FileNameParser
import com.solidplayer.core.data.scanner.MediaFileEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 识别结果。**不做自动绑定决策**，决策表在 ViewModel 里，UI 才能按需弹窗（文档 §4.2）。 */
data class MatchOutcome(
    /** true = 已精确关联到某个弹幕库，`candidates` 只有 1 条，应直接自动绑定 */
    val isMatched: Boolean,
    /** 按可能性从高到低排序的候选 */
    val candidates: List<MatchResultV2>,
) {
    /**
     * 「无需询问用户即可直接绑定」的那条候选，没有就是 `null`。
     *
     * 判据必须**只有这一处**：媒体库的单文件识别与播放页的「点开即识别」共用它。
     * 两处各写一遍 `isMatched && candidates.size == 1` 的话，
     * 任何一处放宽（比如「多候选也挑第一条」）都会让另一处悄悄错绑到别的剧集上。
     */
    val autoBind: MatchResultV2?
        get() = candidates.singleOrNull()?.takeIf { isMatched }
}

/** 批量识别汇总，用于给用户一个可验收的结果。 */
data class BatchIdentifySummary(
    /** 输入文件数 */
    val total: Int,
    /** 成功绑定 episodeId 的文件数 */
    val matched: Int,
    /** 服务端返回了结果但没能绑定（无匹配 / 结果异常） */
    val failed: Int,
    /** 本地阶段就放弃的（文件已删、权限失效、读指纹失败） */
    val skipped: Int,
    /**
     * 失败的具体文件 id（P2：给「仅重试失败项」用）。
     *
     * **只活在内存里**，不落库：它是「上一次识别的结果」，重启后自然失效，
     * 而重试入口本来也只是为了让用户少等一遍全量识别。
     */
    val failedIds: List<Long> = emptyList(),
) {
    val attempted: Int get() = total - skipped
}

/** 组好的一条批量请求，保留 mediaFileId 以便回填时知道写哪一行。 */
private data class PreparedMatch(
    val mediaFileId: Long,
    val request: MatchRequest,
)

/**
 * 文件识别（文档 §4.2 / §9.1 / §9.2）。
 *
 * P1 起承担两条路径：
 * - 单文件：`match`（用户点某个文件的「识别」）
 * - 批量：`match/batch`（媒体库一键识别待处理文件）
 *
 * 两条路径共用同一套**回填逻辑** [applyMatch]：写 `media_file.episodeId / animeId / matchType`
 * 并落 `anime` / `episode` 目录表 —— 目录表是媒体库「按番剧分组」的数据来源。
 */
@Singleton
class MatchRepository @Inject constructor(
    private val api: DandanplayApi,
    private val mediaFileDao: MediaFileDao,
    private val animeDao: AnimeDao,
    private val playHistoryDao: PlayHistoryDao,
    private val mediaRepository: MediaRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    // ── 单文件识别 ────────────────────────────────────────────────

    /**
     * 走哈希 + 文件名识别。
     *
     * `matchMode` 固定用 `hashAndFileName`：服务端逻辑本身就是「先按 hash 精确查找，
     * 失败后按文件名模糊搜索」，一次调用覆盖两种策略，客户端不需要自己重试。
     */
    suspend fun matchByHashAndName(entry: MediaFileEntry): ApiResult<MatchOutcome> {
        val fingerprint = try {
            mediaRepository.fingerprint(entry)
        } catch (e: Exception) {
            return ApiResult.Failure(ApiResult.CODE_UNKNOWN, "读取文件失败：${e.message}", e)
        }
        return requestMatch(
            MatchRequest(
                fileName = entry.displayName,
                fileHash = fingerprint.hash,
                fileSize = entry.sizeBytes,
                videoDuration = fingerprint.durationSeconds,
                matchMode = MatchMode.HASH_AND_FILE_NAME,
            ),
        )
    }

    /**
     * 「手填文件名」入口（P0 的验收路径之一）。
     *
     * 用户手里没有文件、或者文件在别处时，直接拿一个文件名去撞库。
     * 此时没有 hash，所以固定用 `fileNameOnly`，hash 字段留空、size 传 0。
     */
    suspend fun matchByFileName(fileName: String): ApiResult<MatchOutcome> = requestMatch(
        MatchRequest(
            fileName = fileName.trim(),
            fileHash = "",
            fileSize = 0L,
            videoDuration = 0,
            matchMode = MatchMode.FILE_NAME_ONLY,
        ),
    )

    private suspend fun requestMatch(request: MatchRequest): ApiResult<MatchOutcome> =
        safeApiCall { api.match(request) }
            .mapResponse { MatchOutcome(isMatched = it.isMatched, candidates = it.matches) }

    // ── 批量识别（P1）──────────────────────────────────────────────

    /**
     * 批量识别一批文件（文档 §9.2）。
     *
     * 流程：组装请求（算指纹，`(uri,size,lastModified)` 命中缓存则跳过 16MB 读取）
     * → 按 [DandanplayApi.BATCH_LIMIT] 切批 → 逐批提交 → 回填结果并落目录表。
     *
     * 任何一批鉴权/网络失败会**立刻中止**并原样返回失败，不继续打服务端（文档 §10）。
     * 中止前已回填的部分保留 —— 下次重跑时 `findUnmatched()` 天然只会捞剩下的。
     *
     * @param onProgress `(done, total)`，总数为「成功组装出请求的条数」
     */
    suspend fun identifyBatch(
        entries: List<MediaFileEntry>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ApiResult<BatchIdentifySummary> = withContext(io) {
        if (entries.isEmpty()) {
            return@withContext ApiResult.Success(BatchIdentifySummary(0, 0, 0, 0))
        }

        // 1. 组装请求。单个文件读不出来（已被删除 / 权限失效）只跳过它，整批继续。
        val prepared = ArrayList<PreparedMatch>(entries.size)
        var skipped = 0
        for (entry in entries) {
            val entity = mediaFileDao.findByUri(entry.uriString)
            if (entity == null) {
                skipped++
                continue
            }
            val fingerprint = runCatching { mediaRepository.fingerprint(entry) }.getOrNull()
            if (fingerprint == null) {
                skipped++
                continue
            }
            prepared += PreparedMatch(
                mediaFileId = entity.id,
                request = MatchRequest(
                    fileName = entry.displayName,
                    fileHash = fingerprint.hash,
                    fileSize = entry.sizeBytes,
                    videoDuration = fingerprint.durationSeconds,
                    matchMode = MatchMode.HASH_AND_FILE_NAME,
                ),
            )
            onProgress(0, prepared.size)
        }

        if (prepared.isEmpty()) {
            return@withContext ApiResult.Success(
                BatchIdentifySummary(entries.size, matched = 0, failed = 0, skipped = skipped),
            )
        }

        var matched = 0
        var failed = 0
        var done = 0
        val failedIds = ArrayList<Long>()

        val batches = BatchIdentifyPlanner.batches(prepared, DandanplayApi.BATCH_LIMIT)
        batches.forEachIndexed { index, batch ->
            val transport = safeApiCall { api.matchBatch(BatchMatchRequest(batch.map { it.request })) }
                .mapResponse { it.results }

            when (transport) {
                // 鉴权失败 / 网络不可达：立刻停，别继续打服务端
                is ApiResult.Failure -> {
                    onProgress(done, prepared.size)
                    return@withContext transport
                }

                is ApiResult.Success -> {
                    val aligned = BatchIdentifyPlanner.align(batch, transport.data) { it.request.fileHash }
                    for ((item, response) in aligned) {
                        done++
                        val result = response?.matchResult
                        val ok = response?.success == true &&
                            result != null &&
                            applyMatch(item.mediaFileId, result, MatchType.HASH, confidence = 1f)
                        if (ok) {
                            matched++
                        } else {
                            failed++
                            failedIds += item.mediaFileId
                        }
                        onProgress(done, prepared.size)
                    }
                }
            }

            // 批间间隔：官方明确提示调用过于频繁会触发拦截（文档 §10）
            if (index != batches.lastIndex) delay(BATCH_INTERVAL_MS)
        }

        ApiResult.Success(
            BatchIdentifySummary(
                total = entries.size,
                matched = matched,
                failed = failed,
                skipped = skipped,
                failedIds = failedIds,
            ),
        )
    }

    // ── 手动搜索兜底 ──────────────────────────────────────────────

    /**
     * 手动搜索：哈希与文件名都没命中时，用解析出的标题 + 集数调搜索接口（文档 §4.2）。
     */
    suspend fun searchEpisodes(
        animeKeyword: String,
        episode: String? = null,
    ): ApiResult<List<SearchEpisodesAnime>> {
        val keyword = animeKeyword.trim()
        if (keyword.isEmpty()) return ApiResult.Success(emptyList())
        return safeApiCall { api.searchEpisodes(anime = keyword, episode = episode) }
            .mapResponse { it.animes }
    }

    /** 用文件名解析结果直接发起搜索，省掉调用方自己拼关键词。 */
    suspend fun searchByFileName(rawFileName: String): ApiResult<List<SearchEpisodesAnime>> {        val parsed = FileNameParser.parse(rawFileName)
        return searchEpisodes(parsed.searchKeyword(), parsed.episode)
    }

    // ── 结果回填 ──────────────────────────────────────────────────

    /**
     * 写入自动识别结果。**`manual` 优先**：已有的手动绑定不会被自动识别覆盖（文档 §7.2）。
     *
     * @return true = 真的写进去了（false 表示被 `manual` 保护挡下，或 episodeId 非法）
     */
    suspend fun persistMatch(
        mediaFileId: Long,
        result: MatchResultV2,
        matchType: String = MatchType.HASH,
        confidence: Float = 1f,
    ): Boolean = withContext(io) { applyMatch(mediaFileId, result, matchType, confidence) }

    /**
     * 用户从**匹配候选**里选一条：写 manual 标记（此后不再被自动识别覆盖）+ 落目录表。
     */
    suspend fun bindManually(mediaFileId: Long, result: MatchResultV2): Boolean = withContext(io) {
        val episodeId = result.episodeId.takeIf { it > 0L } ?: return@withContext false
        val animeId = result.animeId.takeIf { it > 0L }
        upsertCatalog(animeId, result.animeTitle, episodeId, result.episodeTitle, result.type, result.imageUrl)
        mediaFileDao.bindManually(mediaFileId, animeId, episodeId, System.currentTimeMillis())
        applyServerShift(mediaFileId, result.shift)
        true
    }

    /**
     * 用户从**搜索结果**里选定某一集：写 manual 标记 + 落目录表。
     *
     * 搜索响应里没有海报，`imageUrl` 留空 —— 目录表里的海报只用于展示，缺了不影响识别。
     */
    suspend fun bindFromSearch(
        mediaFileId: Long,
        anime: SearchEpisodesAnime,
        episode: SearchEpisodeDetails,
    ): Boolean = withContext(io) {
        if (episode.episodeId <= 0L) return@withContext false
        val animeId = anime.animeId.takeIf { it > 0L }
        upsertCatalog(animeId, anime.animeTitle, episode.episodeId, episode.episodeTitle, anime.type, null)
        mediaFileDao.bindManually(mediaFileId, animeId, episode.episodeId, System.currentTimeMillis())
        true
    }

    private suspend fun applyMatch(
        mediaFileId: Long,
        result: MatchResultV2,
        matchType: String,
        confidence: Float,
    ): Boolean {
        val episodeId = result.episodeId.takeIf { it > 0L } ?: return false
        val animeId = result.animeId.takeIf { it > 0L }
        val affected = mediaFileDao.updateMatch(
            id = mediaFileId,
            animeId = animeId,
            episodeId = episodeId,
            matchType = matchType,
            confidence = confidence,
            now = System.currentTimeMillis(),
        )
        if (affected <= 0) return false
        upsertCatalog(animeId, result.animeTitle, episodeId, result.episodeTitle, result.type, result.imageUrl)
        applyServerShift(mediaFileId, result.shift)
        return true
    }

    /**
     * 应用服务端建议的弹幕偏移（P2）。
     *
     * **只在还没有 `play_history` 行时写入**（`INSERT OR IGNORE`）。
     * `play_history` 只有一列偏移，分不清值是服务端 shift 还是用户手调，
     * 所以「已经存在记录」就一律放弃写入 —— 宁可这次不用建议，也不能覆盖用户的校准结果。
     *
     * 已知缝隙：如果文件在识别之前就先播过一次（因此已经写了行），
     * 这次的 shift 就不会生效。要根治得给 `play_history` 加一列来源标记 + Migration，
     * 权衡后（P2 零 Migration 原则）接受这个缝隙，用户仍可手动 ±0.5s 调整。
     */
    private suspend fun applyServerShift(mediaFileId: Long, shiftSeconds: Double) {
        val offsetMs = MatchShift.toOffsetMs(shiftSeconds)
        if (offsetMs == 0L) return
        playHistoryDao.insertOffsetIfAbsent(
            PlayHistoryEntity(
                mediaFileId = mediaFileId,
                danmakuOffsetMs = offsetMs,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 落 `anime` / `episode` 目录表 —— 媒体库「按番剧分组」靠它拿标题。
     *
     * `episode.animeId` 有非空外键指向 `anime`，所以**必须先写 anime**；
     * `animeId` 缺失时直接放弃写目录，只保留 media_file 上的 episodeId（不影响播弹幕）。
     */
    private suspend fun upsertCatalog(
        animeId: Long?,
        animeTitle: String?,
        episodeId: Long?,
        episodeTitle: String?,
        animeType: String?,
        imageUrl: String?,
    ) {
        if (animeId == null) return
        animeDao.upsertAnime(
            AnimeEntity(
                animeId = animeId,
                title = animeTitle.orEmpty().ifBlank { UNKNOWN_ANIME_TITLE },
                type = animeType,
                imageUrl = imageUrl,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        if (episodeId == null) return
        animeDao.upsertEpisodes(
            listOf(
                EpisodeEntity(
                    episodeId = episodeId,
                    animeId = animeId,
                    episodeTitle = episodeTitle,
                ),
            ),
        )
    }

    private companion object {
        /** 单批之间的间隔，避免批量识别把服务端打满（文档 §10） */
        const val BATCH_INTERVAL_MS = 300L

        const val UNKNOWN_ANIME_TITLE = "未知作品"
    }
}
