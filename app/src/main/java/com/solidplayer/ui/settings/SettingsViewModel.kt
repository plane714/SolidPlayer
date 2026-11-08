package com.solidplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solidplayer.core.data.credential.DandanplayCredentialRepository
import com.solidplayer.core.data.credential.DandanplayCredentials
import com.solidplayer.core.data.network.ApiResult
import com.solidplayer.core.data.preference.DanmakuPreferenceCodec
import com.solidplayer.core.data.preference.DanmakuPreferenceStore
import com.solidplayer.core.data.preference.DanmakuPreferences
import com.solidplayer.core.data.preference.SubtitleLanguage
import com.solidplayer.core.data.preference.SubtitlePreferenceCodec
import com.solidplayer.core.data.preference.SubtitlePreferenceStore
import com.solidplayer.core.data.preference.SubtitlePreferences
import com.solidplayer.core.data.repository.DanmakuCacheStats
import com.solidplayer.core.data.repository.DanmakuRepository
import com.solidplayer.ui.common.ThumbnailCacheStats
import com.solidplayer.ui.common.ThumbnailCacheStore
import com.solidplayer.ui.common.VideoThumbnailCache
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
    /** 缩略图缓存概况（文件列表缩略图的落盘缓存）。 */
    val thumbnailStats: ThumbnailCacheStats = ThumbnailCacheStats(),
    val clearingThumbnailCache: Boolean = false,
    val confirmClearThumbnailCache: Boolean = false,
    /** 弹幕样式偏好（「弹幕」一级菜单里编辑的就是这一份）。 */
    val danmakuPrefs: DanmakuPreferences = DanmakuPreferences(),
    /** 字幕样式与默认语言偏好（「字幕」一级菜单里编辑的就是这一份）。 */
    val subtitlePrefs: SubtitlePreferences = SubtitlePreferences(),
    val hint: String? = null,
) {
    val inputComplete: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()
}

/**
 * 设置页（凭据 + 弹幕/字幕偏好 + 弹幕缓存）。
 *
 * 「测试连接」校验的是**尚未保存**的输入值，所以走
 * [DandanplayCredentialRepository.verify]（内部用裸 OkHttpClient 手工签名），
 * 而不是先保存再验证。
 *
 * ### 弹幕 / 字幕偏好为什么也走这里
 * 产品要求设置页出一级菜单「字幕」「弹幕」，把原来播放页面板里的弹幕参数收进来。
 * 这两份偏好与播放页共用同一套 DataStore（[DanmakuPreferenceStore] / [SubtitlePreferenceStore]），
 * 所以这里与 PlayerViewModel 写的是同一份数据 —— 改哪边都即时生效、下次打开沿用。
 *
 * 与播放页一致地走「内存先行、松手落库」：滑杆拖动时只改内存（避免每帧写盘、也避免滑杆发滞），
 * 松手 / 切开关时才调用 `persist*`。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialRepository: DandanplayCredentialRepository,
    private val danmakuRepository: DanmakuRepository,
    private val danmakuStore: DanmakuPreferenceStore,
    private val subtitleStore: SubtitlePreferenceStore,
    private val thumbnailCacheStore: ThumbnailCacheStore,
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
        viewModelScope.launch {
            danmakuStore.preferences.collect { prefs ->
                _state.update { it.copy(danmakuPrefs = prefs) }
            }
        }
        viewModelScope.launch {
            subtitleStore.preferences.collect { prefs ->
                _state.update { it.copy(subtitlePrefs = prefs) }
            }
        }
        refreshCacheStats()
        refreshThumbnailStats()
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

    // ── 字幕偏好（「字幕」一级菜单）─────────────────────────────────

    /** 默认字幕语言。写库后由 DataStore Flow 回灌到 state，界面自动刷新。 */
    fun setDefaultLanguage(language: SubtitleLanguage) {
        _state.update { it.copy(subtitlePrefs = it.subtitlePrefs.copy(defaultLanguage = language)) }
        viewModelScope.launch { subtitleStore.setDefaultLanguage(language) }
    }

    fun setSubtitleTextSizeSp(sp: Int) {
        val value = SubtitlePreferenceCodec.normalizeTextSizeSp(sp)
        _state.update { it.copy(subtitlePrefs = it.subtitlePrefs.copy(textSizeSp = value)) }
    }

    fun setSubtitleOutlineEnabled(enabled: Boolean) {
        _state.update { it.copy(subtitlePrefs = it.subtitlePrefs.copy(outlineEnabled = enabled)) }
    }

    /** 字幕样式落库（字号 / 描边；不含默认语言，那一项选中即写）。 */
    fun persistSubtitleStyle() {
        val prefs = _state.value.subtitlePrefs
        viewModelScope.launch {
            subtitleStore.setStyle(
                textSizeSp = prefs.textSizeSp,
                outlineEnabled = prefs.outlineEnabled,
            )
        }
    }

    fun resetSubtitleStyle() {
        viewModelScope.launch { subtitleStore.reset() }
    }

    // ── 弹幕偏好（「弹幕」一级菜单）─────────────────────────────────

    fun setDanmakuEnabled(enabled: Boolean) {
        _state.update { it.copy(danmakuPrefs = it.danmakuPrefs.copy(enabled = enabled)) }
        viewModelScope.launch { danmakuStore.setEnabled(enabled) }
    }

    fun setAlphaPercent(percent: Int) {
        val value = percent.coerceIn(
            DanmakuPreferenceCodec.MIN_ALPHA_PERCENT,
            DanmakuPreferenceCodec.MAX_ALPHA_PERCENT,
        )
        _state.update { it.copy(danmakuPrefs = it.danmakuPrefs.copy(alphaPercent = value)) }
    }

    fun setTextScalePercent(percent: Int) {
        val value = percent.coerceIn(
            DanmakuPreferenceCodec.MIN_TEXT_SCALE_PERCENT,
            DanmakuPreferenceCodec.MAX_TEXT_SCALE_PERCENT,
        )
        _state.update { it.copy(danmakuPrefs = it.danmakuPrefs.copy(textScalePercent = value)) }
    }

    fun setScrollSeconds(seconds: Float) {
        val value = if (seconds.isNaN()) {
            DanmakuPreferenceCodec.DEFAULT_SCROLL_SECONDS
        } else {
            seconds.coerceIn(
                DanmakuPreferenceCodec.MIN_SCROLL_SECONDS,
                DanmakuPreferenceCodec.MAX_SCROLL_SECONDS,
            )
        }
        _state.update { it.copy(danmakuPrefs = it.danmakuPrefs.copy(scrollSeconds = value)) }
    }

    fun setStrokeWidthDp(dp: Float) {
        val value = if (dp.isNaN()) {
            DanmakuPreferenceCodec.DEFAULT_STROKE_DP
        } else {
            dp.coerceIn(DanmakuPreferenceCodec.MIN_STROKE_DP, DanmakuPreferenceCodec.MAX_STROKE_DP)
        }
        _state.update { it.copy(danmakuPrefs = it.danmakuPrefs.copy(strokeWidthDp = value)) }
    }

    fun setDisplayAreaPercent(percent: Int) {
        val value = percent.coerceIn(
            DanmakuPreferenceCodec.MIN_DISPLAY_AREA_PERCENT,
            DanmakuPreferenceCodec.MAX_DISPLAY_AREA_PERCENT,
        )
        _state.update { it.copy(danmakuPrefs = it.danmakuPrefs.copy(displayAreaPercent = value)) }
    }

    /** 把当前五项弹幕样式整体写回 DataStore（滑杆松手时调用）。 */
    fun persistDanmakuStyle() {
        val prefs = _state.value.danmakuPrefs
        viewModelScope.launch {
            danmakuStore.setStyle(
                alphaPercent = prefs.alphaPercent,
                textScalePercent = prefs.textScalePercent,
                scrollSeconds = prefs.scrollSeconds,
                strokeWidthDp = prefs.strokeWidthDp,
                displayAreaPercent = prefs.displayAreaPercent,
            )
        }
    }

    fun applyBlockRules(raw: String) {
        viewModelScope.launch { danmakuStore.setBlockRules(raw) }
    }

    fun resetDanmakuStyle() {
        viewModelScope.launch { danmakuStore.reset() }
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

    // ── 缩略图缓存 ───────────────────────────────────────────────

    fun refreshThumbnailStats() {
        viewModelScope.launch {
            val stats = runCatching { thumbnailCacheStore.stats() }
                .getOrDefault(ThumbnailCacheStats())
            _state.update { it.copy(thumbnailStats = stats) }
        }
    }

    fun askClearThumbnailCache() = _state.update { it.copy(confirmClearThumbnailCache = true) }

    fun dismissClearThumbnailCache() =
        _state.update { it.copy(confirmClearThumbnailCache = false) }

    fun clearThumbnailCache() {
        _state.update { it.copy(clearingThumbnailCache = true, confirmClearThumbnailCache = false) }
        viewModelScope.launch {
            val removed = runCatching { thumbnailCacheStore.clearAll() }.getOrDefault(0)
            // 磁盘删干净之后，内存 LRU 也一起清掉 —— 否则刚清完的图仍会从内存里显示出来，
            // 用户会以为「没清掉」。
            VideoThumbnailCache.clear()
            val stats = runCatching { thumbnailCacheStore.stats() }
                .getOrDefault(ThumbnailCacheStats())
            _state.update {
                it.copy(
                    clearingThumbnailCache = false,
                    thumbnailStats = stats,
                    hint = if (removed > 0) "已清除 $removed 张缩略图缓存" else "没有可清除的缩略图缓存",
                )
            }
        }
    }

    fun consumeHint() = _state.update { it.copy(hint = null) }
}
