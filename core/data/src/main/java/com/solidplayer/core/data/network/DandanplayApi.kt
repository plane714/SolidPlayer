package com.solidplayer.core.data.network

import com.solidplayer.core.data.network.dto.BangumiDetailsResponse
import com.solidplayer.core.data.network.dto.BatchMatchRequest
import com.solidplayer.core.data.network.dto.BatchMatchResponse
import com.solidplayer.core.data.network.dto.CommentResponseV2
import com.solidplayer.core.data.network.dto.MatchRequest
import com.solidplayer.core.data.network.dto.MatchResponseV2
import com.solidplayer.core.data.network.dto.SearchEpisodesResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 弹弹play 开放平台 v2 接口定义（文档 §4.1）。
 *
 * ⚠️ v2 中**不存在** `/api/v2/related` 与 `/api/v2/extcomment`，
 * 第三方弹幕已全部并入 `withRelated=true`。照抄老教程会直接 404。
 */
interface DandanplayApi {

    /** 单文件识别：核心接口。固定用 `hashAndFileName`，服务端自己会先精确后模糊。 */
    @POST("api/v2/match")
    suspend fun match(@Body request: MatchRequest): MatchResponseV2

    /** 批量识别：媒体库首扫用，省掉 N 次往返。单批最多 32 条。 */
    @POST("api/v2/match/batch")
    suspend fun matchBatch(@Body request: BatchMatchRequest): BatchMatchResponse

    /** 手动搜索：哈希匹配失败时的兜底，也是「手填文件名」的落点。 */
    @GET("api/v2/search/episodes")
    suspend fun searchEpisodes(
        @Query("anime") anime: String? = null,
        @Query("episode") episode: String? = null,
        @Query("v2") v2: Boolean = true,
    ): SearchEpisodesResponse

    /** 番剧详情：含每集的 episodeId / episodeNumber / episodeTitle。 */
    @GET("api/v2/bangumi/{bangumiId}")
    suspend fun bangumi(@Path("bangumiId") bangumiId: String): BangumiDetailsResponse

    /**
     * 拉取弹幕。
     *
     * - `withRelated` 固定传 `true`（第三方弹幕已并入）
     * - 该接口**返回 302 跳转到弹幕加速服务**，OkHttp 需开启 followRedirects
     * - 加速服务域名不需要签名（见 [DandanplaySignInterceptor]）
     */
    @GET("api/v2/comment/{episodeId}")
    suspend fun comments(
        @Path("episodeId") episodeId: Long,
        @Query("from") from: Long = 0L,
        @Query("withRelated") withRelated: Boolean = true,
        @Query("chConvert") chConvert: Int = 0,
    ): CommentResponseV2

    companion object {
        const val BASE_URL = "https://${DandanplaySignInterceptor.API_HOST}/"

        /** 轻量探针接口：无需登录，用于「测试连接」校验尚未保存的凭据 */
        const val VERIFY_PATH = "/api/v2/bangumi/shin"

        /**
         * 批量识别单批上限。swagger 明确写「列表中最多包括32个请求」——
         * 文档 §9.2 写的 50 是错的，超过会整批被拒。
         */
        const val BATCH_LIMIT = 32
    }
}
