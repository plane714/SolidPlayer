package com.solidplayer.core.data.network

import com.solidplayer.core.data.credential.DandanplayCredentialStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 签名拦截器（文档 §4.4）。
 *
 * 关键设计：**拦截器持有的是「凭据容器」而不是凭据本身**。
 * 用户中途改了 AppId，无需重建 `OkHttpClient`，下一次请求自动生效。
 *
 * ⚠️ 拦截器运行在 OkHttp 的网络线程上，而 DataStore 是挂起 API。
 * 这里读的是 [DandanplayCredentialStore.current] —— 一个无阻塞的 `@Volatile` 内存缓存，
 * **绝不能 `runBlocking` 去读 DataStore**（会阻塞网络线程，严重时触发连接超时）。
 */
class DandanplaySignInterceptor(
    private val credentialStore: DandanplayCredentialStore,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() / 1000 },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 只对官方 API 域名签名；302 跳转后的加速服务（host 已变）不加签名
        if (request.url.host != API_HOST) {
            return chain.proceed(request)
        }

        val credentials = credentialStore.current() ?: throw MissingCredentialException()

        // encodedPath 天然不含域名与查询参数，正好符合签名要求
        val path = request.url.encodedPath
        val timestamp = timestampProvider()
        val signature = DandanplaySigner.sign(
            appId = credentials.appId,
            timestamp = timestamp,
            path = path,
            appSecret = credentials.appSecret,
        )

        val signed = request.newBuilder()
            .header(HEADER_APP_ID, credentials.appId)
            .header(HEADER_TIMESTAMP, timestamp.toString())
            .header(HEADER_SIGNATURE, signature)
            .build()

        return chain.proceed(signed)
    }

    companion object {
        const val API_HOST = "api.dandanplay.net"
        const val HEADER_APP_ID = "X-AppId"
        const val HEADER_TIMESTAMP = "X-Timestamp"
        const val HEADER_SIGNATURE = "X-Signature"
    }
}
