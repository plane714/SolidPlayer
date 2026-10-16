package com.solidplayer.core.data.network

import com.solidplayer.core.data.network.dto.DandanplayResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 统一异常映射 + 退避重试（文档 §10 / §11）。
 *
 * - 传输层异常（[MissingCredentialException] / [IOException] / [HttpException]）在这里收敛成 [ApiResult.Failure]
 * - `errorCode != 0` 的业务错误由 [asApiResult] 处理
 * - **`CancellationException` 必须原样抛出**，否则协程取消会被吞掉
 * - 429 / 5xx / 读超时会按 [RetryPolicy] 退避重试（P2），其余失败立即返回
 */
suspend inline fun <T> safeApiCall(crossinline block: suspend () -> T): ApiResult<T> =
    executeWithRetry { block() }

/**
 * 带退避重试的执行。
 *
 * 必须是 `@PublishedApi internal`：[safeApiCall] 是 inline 的，内联展开后仍需能访问到本函数。
 */
@PublishedApi
internal suspend fun <T> executeWithRetry(block: suspend () -> T): ApiResult<T> {
    var attempt = 1
    while (true) {
        try {
            return ApiResult.Success(block())
        } catch (e: Throwable) {
            // 协程取消不能被 retry 循环吞掉，必须原样抛出
            if (e is CancellationException) throw e

            if (attempt >= RetryPolicy.MAX_ATTEMPTS || !RetryPolicy.isRetryable(e)) {
                return toFailure(e)
            }
            delay(RetryPolicy.delayMillisFor(attempt))
            attempt++
        }
    }
}

/**
 * 异常 → [ApiResult.Failure] 的映射。
 *
 * **注意判断顺序**：[SocketTimeoutException] 是 [IOException] 的子类，必须先判它，
 * 否则「网络超时」会被吞成泛泛的「网络不可达」。
 */
@PublishedApi
internal fun toFailure(e: Throwable): ApiResult.Failure = when (e) {
    is MissingCredentialException ->
        ApiResult.Failure(ApiResult.CODE_CREDENTIAL_MISSING, e.message.orEmpty(), e)

    is SocketTimeoutException ->
        ApiResult.Failure(ApiResult.CODE_NETWORK, "网络超时", e)

    is IOException ->
        ApiResult.Failure(ApiResult.CODE_NETWORK, e.message ?: "网络不可达", e)

    is HttpException -> {
        val authFailed = e.code() == 401 || e.code() == 403
        ApiResult.Failure(
            code = if (authFailed) ApiResult.CODE_AUTH_FAILED else ApiResult.CODE_NETWORK,
            message = if (authFailed) "AppId 或 AppSecret 有误" else "服务端返回 ${e.code()}",
            cause = e,
        )
    }

    else -> ApiResult.Failure(ApiResult.CODE_UNKNOWN, e.message ?: e.javaClass.simpleName, e)
}

/**
 * 把带 `errorCode` 的响应体转成 [ApiResult]。`errorCode == 0` 才算成功。
 */
fun <T : DandanplayResponse> T.asApiResult(): ApiResult<T> =
    if (errorCode == 0 && success) {
        ApiResult.Success(this)
    } else {
        ApiResult.Failure(
            code = ApiResult.CODE_SERVER,
            message = errorMessage ?: errorDetail ?: "服务端错误 errorCode=$errorCode",
        )
    }

/**
 * 一步完成「传输层结果 → 校验 errorCode → 映射成领域对象」，
 * 省得每个 Repository 方法都写两层嵌套 `when`。
 */
inline fun <T : DandanplayResponse, R> ApiResult<T>.mapResponse(transform: (T) -> R): ApiResult<R> =
    when (this) {
        is ApiResult.Success -> data.asApiResult().map(transform)
        is ApiResult.Failure -> this
    }
