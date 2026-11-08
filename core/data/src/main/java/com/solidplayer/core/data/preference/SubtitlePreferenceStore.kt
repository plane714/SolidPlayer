package com.solidplayer.core.data.preference

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 字幕样式偏好存储。
 *
 * 与凭据、常用目录、弹幕样式**共用同一个 DataStore 文件**，只是 key 不同。
 * 字号 / 描边都是全局偏好：改一次，所有视频的字幕都跟着变（文档 §7.2）。
 */
@Singleton
class SubtitlePreferenceStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val preferences: Flow<SubtitlePreferences> = dataStore.data
        .map { prefs ->
            SubtitlePreferences(
                textSizeSp = prefs[KEY_TEXT_SIZE_SP] ?: SubtitlePreferenceCodec.DEFAULT_TEXT_SIZE_SP,
                outlineEnabled = prefs[KEY_OUTLINE_ENABLED]
                    ?: SubtitlePreferenceCodec.DEFAULT_OUTLINE_ENABLED,
                defaultLanguage = SubtitleLanguage.fromTag(prefs[KEY_DEFAULT_LANGUAGE]),
            )
        }
        .distinctUntilChanged()

    /**
     * 一次写回两项样式。
     *
     * 与 `DanmakuPreferenceStore.setStyle` 同一条约定：调用方是面板上的滑杆与开关，
     * 它们在**松手 / 切换时**整体提交（见 `PlayerViewModel.persistSubtitleStyle`），
     * 一个 `edit` 只读-改-写一次 preferences 文件即可。
     *
     * 传入值在这里统一钳位：落库的值必须和读取侧的约定一致，否则下次读出来还得再纠一遍。
     */
    suspend fun setStyle(textSizeSp: Int, outlineEnabled: Boolean) {
        val size = SubtitlePreferenceCodec.normalizeTextSizeSp(textSizeSp)
        dataStore.edit { prefs ->
            prefs[KEY_TEXT_SIZE_SP] = size
            prefs[KEY_OUTLINE_ENABLED] = outlineEnabled
        }
    }

    /** 只改默认字幕语言（设置页「字幕」一级菜单用）。 */
    suspend fun setDefaultLanguage(language: SubtitleLanguage) {
        dataStore.edit { prefs -> prefs[KEY_DEFAULT_LANGUAGE] = language.tag }
    }

    /** 恢复默认。 */
    suspend fun reset() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_TEXT_SIZE_SP)
            prefs.remove(KEY_OUTLINE_ENABLED)
            prefs.remove(KEY_DEFAULT_LANGUAGE)
        }
    }

    private companion object {
        /** 老版本没有这几个 key → 读出来就是默认值，无需 Migration。 */
        val KEY_TEXT_SIZE_SP = intPreferencesKey("subtitle_text_size_sp")
        val KEY_OUTLINE_ENABLED = booleanPreferencesKey("subtitle_outline_enabled")

        /** 默认字幕语言的 tag（见 [SubtitleLanguage.tag]）。新增 key + 读侧回落默认，无需迁移。 */
        val KEY_DEFAULT_LANGUAGE = stringPreferencesKey("subtitle_default_language")
    }
}
