package com.solidplayer.core.data.repository

import com.solidplayer.core.danmaku.filter.DanmakuFilter
import com.solidplayer.core.danmaku.model.DanmakuItem
import com.solidplayer.core.danmaku.model.DanmakuTimeline
import com.solidplayer.core.danmaku.parser.PFieldParser
import com.solidplayer.core.data.db.DanmakuCacheDao
import com.solidplayer.core.data.db.DanmakuCacheEntity
import com.solidplayer.core.data.db.PlayHistoryDao
import com.solidplayer.core.data.db.PlayHistoryEntity
import com.solidplayer.core.data.di.IoDispatcher
import com.solidplayer.core.data.network.ApiResult
import com.solidplayer.core.data.network.DandanplayApi
import com.solidplayer.core.data.network.dto.CommentResponseV2
import com.solidplayer.core.data.network.safeApiCall
import com.solidplayer.core.data.preference.DanmakuPreferenceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 弹幕加载结果。 */
data class DanmakuLoadResult(
    val timeline: DanmakuTimeline,
    /** 服务端返回的条数（未套屏蔽词前的原始规模） */
    val count: Int,
    val fromCache: Boolean,
    /** 被屏蔽词挡掉的条数，用于给用户一个「确实生效了」的反馈 */
    val blocked: Int = 0,
)

/**
 * 弹幕缓存概况（P2 设置页展示用）。
 *
 * `bytes` 是**落盘占用**，不是弹幕条数 —— 用户想清缓存时关心的是「占了我多少存储」。
 */
data class DanmakuCacheStats(
    val episodes: Int = 0,
    val bytes: Long = 0L,
) {
    val isEmpty: Boolean get() = episodes == 0 && bytes == 0L
}

/**
 * 弹幕拉取与缓存（文档 §4.3 / §10）。
 *
 * 弹弹play 官方明确提示：**调用量过大或过于频繁会触发检测与拦截机制**。
 * 因此缓存不是可选项 —— 命中缓存直接读本地文件，不发请求。
 *
 * P2 起额外承担两件事：构建时间轴时套一道**屏蔽词过滤**，以及缓存本身的统计与清理。
 */
@Singleton
class DanmakuRepository @Inject constructor(
    private val api: DandanplayApi,
    private val cacheDao: DanmakuCacheDao,
    private val cacheStore: DanmakuCacheStore,
    private val playHistoryDao: PlayHistoryDao,
    private val preferenceStore: DanmakuPreferenceStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * 取某个弹幕库的时间轴。
     *
     * @param forceRefresh 忽略缓存强制重新拉取
     */
    suspend fun loadTimeline(
        episodeId: Long,
        forceRefresh: Boolean = false,
    ): ApiResult<DanmakuLoadResult> = withContext(io) {
        if (episodeId <= 0L) {
            return@withContext ApiResult.Success(DanmakuLoadResult(DanmakuTimeline.EMPTY, 0, false))
        }

        if (!forceRefresh) {
            readFromCache(episodeId)?.let { return@withContext ApiResult.Success(it) }
        }

        when (val transport = safeApiCall {
            api.comments(
                episodeId = episodeId,
                from = 0L,
                // 固定 true：第三方弹幕已全部并入，v2 里没有 /related 与 /extcomment
                withRelated = true,
                chConvert = 0,
            )
        }) {
            is ApiResult.Failure -> transport

            is ApiResult.Success -> {
                val response = transport.data
                persist(episodeId, response)
                ApiResult.Success(buildTimeline(response, fromCache = false))
            }
        }
    }

    private suspend fun readFromCache(episodeId: Long): DanmakuLoadResult? {
        val index = cacheDao.find(episodeId) ?: return null
        if (isExpired(index)) return null

        val response = cacheStore.readResponse(episodeId)
        if (response == null) {
            // 索引在、文件丢了：清掉脏索引，让调用方走网络
            cacheDao.delete(episodeId)
            return null
        }
        return buildTimeline(response, fromCache = true)
    }

    private suspend fun persist(episodeId: Long, response: CommentResponseV2) {
        val path = cacheStore.writeResponse(episodeId, response) ?: return
        cacheDao.upsert(
            DanmakuCacheEntity(
                episodeId = episodeId,
                source = "dandanplay",
                withRelated = true,
                chConvert = 0,
                count = response.count,
                filePath = path,
                fetchedAt = System.currentTimeMillis(),
            ),
        )
    }

    // ── 每集弹幕时轴偏移（文档 §8.6）──────────────────────────────

    suspend fun danmakuOffset(mediaFileId: Long): Long = withContext(io) {
        playHistoryDao.find(mediaFileId)?.danmakuOffsetMs ?: 0L
    }

    /** 断点续播用的上次播放位置（`play_history.positionMs`）。 */
    suspend fun playPosition(mediaFileId: Long): Long = withContext(io) {
        playHistoryDao.find(mediaFileId)?.positionMs ?: 0L
    }

    suspend fun setDanmakuOffset(mediaFileId: Long, offsetMs: Long) = withContext(io) {
        val now = System.currentTimeMillis()
        val existing = playHistoryDao.find(mediaFileId)
        if (existing == null) {
            playHistoryDao.upsert(
                PlayHistoryEntity(mediaFileId = mediaFileId, danmakuOffsetMs = offsetMs, updatedAt = now),
            )
        } else {
            playHistoryDao.updateDanmakuOffset(mediaFileId, offsetMs, now)
        }
        Unit
    }

    suspend fun savePlayPosition(mediaFileId: Long, positionMs: Long, durationMs: Long) =
        withContext(io) {
            val now = System.currentTimeMillis()
            val existing = playHistoryDao.find(mediaFileId)
            if (existing == null) {
                playHistoryDao.upsert(
                    PlayHistoryEntity(
                        mediaFileId = mediaFileId,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        updatedAt = now,
                    ),
                )
            } else {
                playHistoryDao.upsert(
                    existing.copy(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        updatedAt = now,
                    ),
                )
            }
            Unit
        }

    private suspend fun buildTimeline(
        response: CommentResponseV2,
        fromCache: Boolean,
    ): DanmakuLoadResult {
        val comments = response.comments
        if (comments.isEmpty()) {
            return DanmakuLoadResult(DanmakuTimeline.EMPTY, response.count, fromCache, blocked = 0)
        }

        val parsed = ArrayList<DanmakuItem>(comments.size)
        for (comment in comments) {
            val p = comment.p ?: continue
            val m = comment.m ?: continue
            PFieldParser.parseItem(p, m)?.let(parsed::add)
        }

        // 屏蔽词在**解析期**过滤：过滤一次就省下内存、轨道与每帧判断，
        // 代价只是改屏蔽词要重新解析，而原始 JSON 就在本地，重解析零网络开销。
        val filter = DanmakuFilter.of(preferenceStore.blockRules.first())
        val kept = filter.apply(parsed)

        return DanmakuLoadResult(
            timeline = DanmakuTimeline.of(kept),
            count = response.count,
            fromCache = fromCache,
            blocked = parsed.size - kept.size,
        )
    }

    /**
     * 空结果缓存 TTL 更长 —— 避免反复请求确定没有资源的冷门剧集（文档 §10）。
     */
    private fun isExpired(index: DanmakuCacheEntity, now: Long = System.currentTimeMillis()): Boolean {
        val ttl = if (index.count == 0) EMPTY_TTL_MS else HIT_TTL_MS
        return now - index.fetchedAt > ttl
    }

    // ── 缓存治理（P2）────────────────────────────────────────────

    /** 缓存概况：剧集数 + 落盘占用。 */
    suspend fun cacheStats(): DanmakuCacheStats = withContext(io) {
        DanmakuCacheStats(episodes = cacheDao.count(), bytes = cacheStore.totalBytes())
    }

    /**
     * 清空全部弹幕缓存（DB 索引 + 落盘文件），返回清掉的剧集数。
     *
     * UI 侧必须二次确认：清掉之后下次播放要么重新联网拉，要么就是「无弹幕」。
     */
    suspend fun clearCache(): Int = withContext(io) {
        val episodes = cacheDao.count()
        cacheStore.clearAll()
        cacheDao.deleteAll()
        episodes
    }

    /**
     * 按 TTL 清理过期缓存，返回清掉的剧集数。
     *
     * 过期判定要在 Kotlin 侧做：空结果缓存 TTL 是 7 天、命中的是 24 小时，
     * 一条 `fetchedAt < :before` 的 SQL 表达不了这个差异。
     */
    suspend fun pruneExpiredCache(): Int = withContext(io) {
        val now = System.currentTimeMillis()
        val expired = cacheDao.all().filter { isExpired(it, now) }
        if (expired.isEmpty()) return@withContext 0

        cacheStore.deleteAll(expired.map { it.episodeId })
        for (index in expired) {
            cacheDao.delete(index.episodeId)
        }
        expired.size
    }

    private companion object {
        /** 命中缓存 TTL：≥ 24 小时 */
        const val HIT_TTL_MS = 24L * 60L * 60L * 1000L

        /** 空结果 TTL：7 天 */
        const val EMPTY_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
