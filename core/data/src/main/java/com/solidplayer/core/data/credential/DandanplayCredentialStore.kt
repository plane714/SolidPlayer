package com.solidplayer.core.data.credential

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.solidplayer.core.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据读写唯一出口（文档 §4.5.2）。
 *
 * ### 为什么用「@Volatile 内存缓存 + Flow 后台同步」而不是让拦截器直接读 DataStore
 * 拦截器运行在 OkHttp 的**网络线程**上，而 DataStore 是**挂起 API**。
 * 在那里 `runBlocking` 会阻塞网络线程，严重时触发 OkHttp 连接超时。
 * 用「后台收集 Flow → 写入 volatile 字段 → 拦截器同步读」这个模式：
 * - 读取是 O(1) 且无阻塞
 * - 用户改完凭据后，下一次请求立即生效（无需重建 OkHttpClient）
 *
 * ### 存储与安全
 * - 落在应用私有目录 `/data/data/com.solidplayer/files/datastore/`，非 root 设备其他应用不可读
 * - 已在 manifest 设置 `allowBackup="false"`，并把 datastore 目录加入备份排除规则（文档 §12.1）
 * - 若日后要更高强度，只需在本类内部加一层 Keystore + AES，**上层调用方零改动**
 */
@Singleton
class DandanplayCredentialStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /** 内存缓存：让拦截器可以在非协程上下文同步读取，避免网络线程上阻塞读盘 */
    @Volatile
    private var cached: DandanplayCredentials? = null

    /** 供拦截器同步调用。返回 `null` 表示用户尚未配置 */
    fun current(): DandanplayCredentials? = cached

    /** 供 UI 观察配置状态，驱动「未配置」提示条 */
    val credentials: Flow<DandanplayCredentials?> = dataStore.data.map { prefs ->
        val id = prefs[KEY_APP_ID].orEmpty()
        val secret = prefs[KEY_APP_SECRET].orEmpty()
        if (id.isBlank() || secret.isBlank()) null else DandanplayCredentials(id, secret)
    }

    init {
        // 预热缓存，保证进程启动后第一个网络请求就能读到凭据
        appScope.launch {
            credentials.collect { cached = it }
        }
    }

    /** 冷启动时若缓存尚未就绪，等待首次读取完成 */
    suspend fun awaitLoaded(): DandanplayCredentials? {
        if (cached == null) cached = credentials.first()
        return cached
    }

    suspend fun save(appId: String, appSecret: String) {
        dataStore.edit { prefs ->
            prefs[KEY_APP_ID] = appId.trim()
            prefs[KEY_APP_SECRET] = appSecret.trim()
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_APP_ID)
            prefs.remove(KEY_APP_SECRET)
        }
    }

    private companion object {
        val KEY_APP_ID = stringPreferencesKey("dandanplay_app_id")
        val KEY_APP_SECRET = stringPreferencesKey("dandanplay_app_secret")
    }
}
