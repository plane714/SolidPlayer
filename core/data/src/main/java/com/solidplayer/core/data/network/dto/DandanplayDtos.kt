package com.solidplayer.core.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 弹弹play v2 接口 DTO（字段名与 swagger 严格对齐，来源见 `docs/ARCHITECTURE.md` 附录 A）。
 *
 * 说明：这里**刻意不用类继承**来表达 `ResponseBase`。
 * kotlinx.serialization 对多态继承要求 `sealed` + 序列化器注册，对「四个公共字段」这种小场景
 * 属于杀鸡用牛刀；直接展平字段更简单、也不会有 `additionalProperties=false` 的解析意外。
 */

/** 所有 v2 响应的公共头。各响应 DTO 直接实现它，便于统一判错。 */
interface DandanplayResponse {
    /** 0 表示成功 */
    val errorCode: Int
    val success: Boolean
    val errorMessage: String?
    /** 参数校验失败时用于定位字段的补充信息 */
    val errorDetail: String?
}

// ── 文件识别 ──────────────────────────────────────────────────────

@Serializable
enum class MatchMode {
    @SerialName("hashAndFileName")
    HASH_AND_FILE_NAME,

    @SerialName("fileNameOnly")
    FILE_NAME_ONLY,

    @SerialName("hashOnly")
    HASH_ONLY,
}

@Serializable
data class MatchRequest(
    /**
     * 视频文件名。
     * ⚠️ 待实测：swagger 描述为「不包含文件夹名称和扩展名」，而本项目文档 §4.2 要求「含扩展名」。
     * 官方客户端历史上是含扩展名提交的，此处按项目文档执行，接入时需用真实文件验证一次。
     */
    val fileName: String,
    /** 文件前 16MB 的 MD5，32 位小写十六进制 */
    val fileHash: String,
    val fileSize: Long,
    /** 视频时长（秒），可选但能显著提高识别率 */
    val videoDuration: Int = 0,
    val matchMode: MatchMode = MatchMode.HASH_AND_FILE_NAME,
)

@Serializable
data class MatchResultV2(
    /** ★ 弹幕库编号，识别线与弹幕线唯一的耦合点 */
    val episodeId: Long = 0L,
    val animeId: Long = 0L,
    val animeTitle: String? = null,
    val episodeTitle: String? = null,
    /** AnimeType 枚举字符串。用 String 而非 enum，避免服务端新增类型导致解析整体失败 */
    val type: String? = null,
    val typeDescription: String? = null,
    /** 弹幕偏移秒数：正值表示弹幕应延迟出现。P0 先只记录，不自动应用 */
    val shift: Double = 0.0,
    val imageUrl: String? = null,
)

@Serializable
data class MatchResponseV2(
    override val errorCode: Int = 0,
    override val success: Boolean = true,
    override val errorMessage: String? = null,
    override val errorDetail: String? = null,
    /** true = 已精确关联到某个弹幕库，此时 [matches] 只有 1 条，客户端应直接自动绑定 */
    val isMatched: Boolean = false,
    /** 按可能性从高到低排序 */
    val matches: List<MatchResultV2> = emptyList(),
) : DandanplayResponse

// ── 批量识别 ──────────────────────────────────────────────────────

@Serializable
data class BatchMatchRequest(
    /** ⚠️ 实测限制：单批最多 **32** 个请求（文档 §9.2 写的 50 有误，见 swagger 描述） */
    val requests: List<MatchRequest>,
)

@Serializable
data class BatchMatchResponseItem(
    val success: Boolean = false,
    val fileHash: String? = null,
    val matchResult: MatchResultV2? = null,
)

@Serializable
data class BatchMatchResponse(
    override val errorCode: Int = 0,
    override val success: Boolean = true,
    override val errorMessage: String? = null,
    override val errorDetail: String? = null,
    val results: List<BatchMatchResponseItem> = emptyList(),
) : DandanplayResponse

// ── 搜索 ──────────────────────────────────────────────────────────

@Serializable
data class SearchEpisodeDetails(
    val episodeId: Long = 0L,
    val episodeTitle: String? = null,
)

@Serializable
data class SearchEpisodesAnime(
    val animeId: Long = 0L,
    val animeTitle: String? = null,
    val type: String? = null,
    val typeDescription: String? = null,
    val episodes: List<SearchEpisodeDetails> = emptyList(),
)

@Serializable
data class SearchEpisodesResponse(
    override val errorCode: Int = 0,
    override val success: Boolean = true,
    override val errorMessage: String? = null,
    override val errorDetail: String? = null,
    val hasMore: Boolean = false,
    val animes: List<SearchEpisodesAnime> = emptyList(),
) : DandanplayResponse

// ── 番剧详情 ──────────────────────────────────────────────────────

@Serializable
data class BangumiEpisode(
    /** 季度 ID，为空表示只有一个季度 */
    val seasonId: String? = null,
    val episodeId: Long = 0L,
    val episodeTitle: String? = null,
    /** 短标题，可用于排序，可能含字母 */
    val episodeNumber: String? = null,
    val airDate: String? = null,
)

@Serializable
data class BangumiDetails(
    val animeId: Long = 0L,
    val bangumiId: String? = null,
    val animeTitle: String? = null,
    val imageUrl: String? = null,
    val rating: Double = 0.0,
    val type: String? = null,
    val typeDescription: String? = null,
    val summary: String? = null,
    val episodes: List<BangumiEpisode> = emptyList(),
)

@Serializable
data class BangumiDetailsResponse(
    override val errorCode: Int = 0,
    override val success: Boolean = true,
    override val errorMessage: String? = null,
    override val errorDetail: String? = null,
    // BangumiDetails 的字段直接展平（响应体就是这些字段本身）
    val animeId: Long = 0L,
    val bangumiId: String? = null,
    val animeTitle: String? = null,
    val imageUrl: String? = null,
    val rating: Double = 0.0,
    val type: String? = null,
    val typeDescription: String? = null,
    val summary: String? = null,
    val episodes: List<BangumiEpisode> = emptyList(),
) : DandanplayResponse {

    fun toDetails(): BangumiDetails = BangumiDetails(
        animeId = animeId,
        bangumiId = bangumiId,
        animeTitle = animeTitle,
        imageUrl = imageUrl,
        rating = rating,
        type = type,
        typeDescription = typeDescription,
        summary = summary,
        episodes = episodes,
    )
}

// ── 弹幕 ──────────────────────────────────────────────────────────

@Serializable
data class CommentData(
    val cid: Long = 0L,
    /** `出现时间(秒),模式(1/4/5),颜色(R*65536+G*256+B),用户ID` */
    val p: String? = null,
    val m: String? = null,
)

/**
 * 注意：该响应**没有** `ResponseBase` 字段（swagger 里 CommentResponseV2 是独立结构），
 * 因此它不实现 [DandanplayResponse]。
 */
@Serializable
data class CommentResponseV2(
    val count: Int = 0,
    val comments: List<CommentData> = emptyList(),
)
