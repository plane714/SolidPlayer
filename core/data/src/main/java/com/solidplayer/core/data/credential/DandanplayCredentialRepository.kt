package com.solidplayer.core.data.credential

import com.solidplayer.core.data.di.BareClient
import com.solidplayer.core.data.di.IoDispatcher
import com.solidplayer.core.data.network.ApiResult
import com.solidplayer.core.data.network.DandanplayApi
import com.solidplayer.core.data.network.DandanplaySigner
import com.solidplayer.core.data.network.DandanplaySignInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据配置用例（文档 §4.5）。
 */
@Singleton
class DandanplayCredentialRepository @Inject constructor(
    private val store: DandanplayCredentialStore,
    @BareClient private val bareClient: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    val credentials: Flow<DandanplayCredentials?> = store.credentials

    val isConfigured: Flow<Boolean> = store.credentials.map { it != null }

    fun current(): DandanplayCredentials? = store.current()

    suspend fun awaitLoaded(): DandanplayCredentials? = store.awaitLoaded()

    suspend fun save(appId: String, appSecret: String) {
        store.save(appId, appSecret)
    }

    suspend fun clear() {
        store.clear()
    }

    /**
     * 「测试连接」：校验**用户刚输入但还没保存**的凭据（文档 §4.5.5）。
     *
     * 因此不能走 [store]（那里还是旧值），而是手工构造一次性请求。
     * 用的 `bareClient` 是**不带签名拦截器**的裸客户端 —— 否则拦截器会覆盖掉手工设的请求头。
     *
     * 探针选 `/api/v2/bangumi/shin`：轻量、无需登录。
     */
    suspend fun verify(input: DandanplayCredentials): ApiResult<Unit> = withContext(io) {
        val appId = input.appId.trim()
        val appSecret = input.appSecret.trim()
        if (appId.isEmpty() || appSecret.isEmpty()) {
            return@withContext ApiResult.Failure(ApiResult.CODE_CREDENTIAL_MISSING, "AppId 与 AppSecret 都不能为空")
        }

        val path = DandanplayApi.VERIFY_PATH
        val timestamp = System.currentTimeMillis() / 1000
        val request = Request.Builder()
            .url(DandanplayApi.BASE_URL.trimEnd('/') + path)
            .header(DandanplaySignInterceptor.HEADER_APP_ID, appId)
            .header(DandanplaySignInterceptor.HEADER_TIMESTAMP, timestamp.toString())
            .header(
                DandanplaySignInterceptor.HEADER_SIGNATURE,
                DandanplaySigner.sign(appId, timestamp, path, appSecret),
            )
            .build()

        try {
            bareClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> ApiResult.Success(Unit)

                    response.code == 401 || response.code == 403 ->
                        ApiResult.Failure(ApiResult.CODE_AUTH_FAILED, "AppId 或 AppSecret 有误")

                    else -> ApiResult.Failure(ApiResult.CODE_SERVER, "服务端返回 ${response.code}")
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(ApiResult.CODE_NETWORK, e.message ?: "网络不可达", e)
        }
    }
}
