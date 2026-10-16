package com.solidplayer.core.data.network

import retrofit2.HttpException
import java.net.SocketTimeoutException

/**
 * 退避重试策略（文档 §10 / §11）。
 *
 * ### 哪些要重试，哪些不要
 * | 情况 | 重试？ | 依据 |
 * |---|---|---|
 * | 429（限流） | 是 | §10 明确要求指数退避，弹弹play 会限流 |
 * | 5xx（服务端故障） | 是 | §11「服务端 5xx / 429 退避重试；最终失败降级为无弹幕」 |
 * | 读超时 | 是 | 服务端偶发慢，最典型的瞬时故障 |
 * | 其他 4xx | **否** | 请求本身有问题，重试只是浪费配额（§11） |
 * | 网络不可达（`IOException` 其他子类） | **否** | §11 要求「静默降级：无弹幕播放」。重试 3 次只会让用户白等 3 秒 |
 * | 未配置凭据 | **否** | 这是正常初始状态，不是错误 |
 *
 * 退避序列：1s → 2s。
 *
 * ### 与文档 §10 的差异
 * §10 写的是「1s → 2s → 4s，最多 3 次」，即 4 次尝试。这里取**总尝试 3 次**（2 次重试），
 * 原因是读超时设的是 20 秒：4 次尝试最坏要等 `20×4 + 7 ≈ 87s` 才降级，
 * 而弹幕晚到本来就不阻塞播放，§11 也要求「任何弹幕相关的失败都不能阻塞播放」——
 * 快速降级比多试一次更符合这个原则。429 / 5xx 通常响应很快，2 次重试已足够吃掉瞬时抖动。
 *
 * 判定逻辑是纯函数，可 JVM 单测。
 */
object RetryPolicy {

    /** 单次请求的最大尝试次数（含首次）。 */
    const val MAX_ATTEMPTS = 3

    /** 首次重试前的等待时长。 */
    const val BASE_DELAY_MS = 1_000L

    /** 第 [attempt] 次**尝试**失败后应等待的毫秒数：1s → 2s（`attempt` 从 1 开始）。 */
    fun delayMillisFor(attempt: Int): Long = BASE_DELAY_MS shl (attempt - 1).coerceAtLeast(0)

    /** 429 与 5xx 视为可重试。 */
    fun isRetryableHttpStatus(status: Int): Boolean = status == HTTP_TOO_MANY_REQUESTS || status in 500..599

    fun isRetryable(error: Throwable): Boolean = when (error) {
        is HttpException -> isRetryableHttpStatus(error.code())
        is SocketTimeoutException -> true
        else -> false
    }

    private const val HTTP_TOO_MANY_REQUESTS = 429
}
