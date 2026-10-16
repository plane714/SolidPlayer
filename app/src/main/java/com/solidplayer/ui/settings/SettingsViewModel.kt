package com.solidplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solidplayer.core.data.credential.DandanplayCredentialRepository
import com.solidplayer.core.data.credential.DandanplayCredentials
import com.solidplayer.core.data.network.ApiResult
import com.solidplayer.core.data.repository.DanmakuCacheStats
import com.solidplayer.core.data.repository.DanmakuRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface VerifyResult {
    data object Ok : VerifyResult

    data class Error(val message: String) : VerifyResult
}

data class SettingsUiState(
    val appId: String = "",
    val appSecret: String = "",
    val secretVisible: Boolean = false,
    val configured: Boolean = false,
    val verifying: Boolean = false,
    val verifyResult: VerifyResult? = null,
    val confirmClear: Boolean = false,
    /** 弹幕缓存概况（P2） */
    val cacheStats: DanmakuCacheStats = DanmakuCacheStats(),
    val clearingCache: Boolean = false,
    val confirmClearCache: Boolean = false,
    val hint: String? = null,
) {
    val inputComplete: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()
}

/**
 * 设置页（文档 §4.5.5 凭据 + P2 弹幕缓存）。
 *
 * 「测试连接」校验的是**尚未保存**的输入值，所以走的是
 * [DandanplayCredentialRepository.verify]（内部用裸 OkHttpClient 手工签名），
 * 而不是先保存再验证。
 *
 * 弹幕样式（透明度/字号/速度/屏蔽词）不在这里 —— 那些放在播放页的面板里做即时预览，
 * 这里只管「凭据」与「缓存」这两件跟播放画面无关的事。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialRepository: DandanplayCredentialRepository,
    private val danmakuRepository: DanmakuRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            credentialRepository.credentials.collect { credentials ->
                _state.update { current ->
                    current.copy(
                        configured = credentials != null,
                        // 只做预填，绝不覆盖用户正在编辑的内容
                        appId = if (current.appId.isEmpty()) credentials?.appId.orEmpty() else current.appId,
                        appSecret = if (current.appSecret.isEmpty()) {
                            credentials?.appSecret.orEmpty()
                        } else {
                            current.appSecret
                        },
                    )
                }
            }
        }
        refreshCacheStats()
    }

    fun onAppIdChange(value: String) {
        _state.update { it.copy(appId = value, verifyResult = null, hint = null) }
    }

    fun onAppSecretChange(value: String) {
        _state.update { it.copy(appSecret = value, verifyResult = null, hint = null) }
    }

    fun toggleSecretVisible() {
        _state.update { it.copy(secretVisible = !it.secretVisible) }
    }

    fun testConnection() {
        val snapshot = _state.value
        if (!snapshot.inputComplete) {
            _state.update { it.copy(hint = "请先填写 AppId 与 AppSecret") }
            return
        }
        _state.update { it.copy(verifying = true, verifyResult = null) }

        viewModelScope.launch {
            val result = credentialRepository.verify(
                DandanplayCredentials(snapshot.appId.trim(), snapshot.appSecret.trim()),
            )
            _state.update {
                it.copy(
                    verifying = false,
                    verifyResult = when (result) {
                        is ApiResult.Success -> VerifyResult.Ok
                        is ApiResult.Failure -> VerifyResult.Error(result.message)
                    },
                )
            }
        }
    }

    fun save() {
        val snapshot = _state.value
        if (!snapshot.inputComplete) {
            _state.update { it.copy(hint = "请先填写 AppId 与 AppSecret") }
            return
        }
        viewModelScope.launch {
            credentialRepository.save(snapshot.appId.trim(), snapshot.appSecret.trim())
            _state.update { it.copy(hint = "已保存", verifyResult = null) }
        }
    }

    fun askClear() = _state.update { it.copy(confirmClear = true) }

    fun dismissClear() = _state.update { it.copy(confirmClear = false) }

    fun clear() {
        viewModelScope.launch {
            credentialRepository.clear()
            _state.update {
                it.copy(
                    appId = "",
                    appSecret = "",
                    secretVisible = false,
                    confirmClear = false,
                    verifyResult = null,
                    hint = "凭据已清除",
                )
            }
        }
    }

    // ── 弹幕缓存（P2）────────────────────────────────────────────

    fun refreshCacheStats() {
        viewModelScope.launch {
            val stats = runCatching { danmakuRepository.cacheStats() }
                .getOrDefault(DanmakuCacheStats())
            _state.update { it.copy(cacheStats = stats) }
        }
    }

    fun askClearCache() = _state.update { it.copy(confirmClearCache = true) }

    fun dismissClearCache() = _state.update { it.copy(confirmClearCache = false) }

    fun clearCache() {
        _state.update { it.copy(clearingCache = true, confirmClearCache = false) }
        viewModelScope.launch {
            val removed = runCatching { danmakuRepository.clearCache() }.getOrDefault(0)
            val stats = runCatching { danmakuRepository.cacheStats() }
                .getOrDefault(DanmakuCacheStats())
            _state.update {
                it.copy(
                    clearingCache = false,
                    cacheStats = stats,
                    hint = if (removed > 0) "已清理 $removed 个剧集的弹幕缓存" else "没有可清理的缓存",
                )
            }
        }
    }

    fun consumeHint() = _state.update { it.copy(hint = null) }
}
