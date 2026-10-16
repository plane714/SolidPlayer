package com.solidplayer.core.data.network

/**
 * 网络层统一返回类型（文档 §11）。
 *
 * **网络层不抛业务异常** —— 全部收敛成 [Failure]，由 Repository 决定怎么降级。
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>

    data class Failure(
        val code: Int,
        val message: String,
        val cause: Throwable? = null,
    ) : ApiResult<Nothing> {

        /** 仅「鉴权失败」需要引导用户回设置页，其余都不打扰用户 */
        val isAuthFailure: Boolean get() = code == CODE_AUTH_FAILED

        val isCredentialMissing: Boolean get() = code == CODE_CREDENTIAL_MISSING
    }

    companion object {
        /** 用户尚未配置 AppId/AppSecret —— 这是正常初始状态，不算错误、不打告警日志 */
        const val CODE_CREDENTIAL_MISSING = -1000

        /** 签名错误 / AppId 或 AppSecret 无效 */
        const val CODE_AUTH_FAILED = -1001

        /** 网络不可达、超时 */
        const val CODE_NETWORK = -1002

        /** 服务端返回的 errorCode 非 0 */
        const val CODE_SERVER = -1003

        const val CODE_UNKNOWN = -1099
    }
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}
