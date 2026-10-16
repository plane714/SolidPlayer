package com.solidplayer.core.data.preference

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.solidplayer.core.danmaku.model.DanmakuConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弹幕样式偏好存储（P2，文档 §7.2 / §13）。
 *
 * 与凭据、常用目录**共用同一个 DataStore 文件**，只是 key 不同：
 * 透明度 / 字号 / 滚动速度 / 开关 / 屏蔽词都是全局偏好，放 DB 反而是错配 ——
 * 它们是「跟人走」而不是「跟文件走」的。
 *
 * ### 读取侧统一钳位
 * [DanmakuPreferences] 由 [DanmakuPreferenceCodec] 换算，越界值在这里就被纠正，
 * 上层（渲染器 / UI）永远拿不到非法参数。
 */
@Singleton
class DanmakuPreferenceStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val preferences: Flow<DanmakuPreferences> = dataStore.data
        .map { prefs ->
            DanmakuPreferences(
                alphaPercent = prefs[KEY_ALPHA] ?: DanmakuPreferenceCodec.DEFAULT_ALPHA_PERCENT,
                textScalePercent = prefs[KEY_TEXT_SCALE]
                    ?: DanmakuPreferenceCodec.DEFAULT_TEXT_SCALE_PERCENT,
                scrollSeconds = prefs[KEY_SCROLL_SECONDS]
                    ?: DanmakuPreferenceCodec.DEFAULT_SCROLL_SECONDS,
                strokeWidthDp = prefs[KEY_STROKE_DP]
                    ?: DanmakuPreferenceCodec.DEFAULT_STROKE_DP,
                displayAreaPercent = prefs[KEY_DISPLAY_AREA]
                    ?: DanmakuPreferenceCodec.DEFAULT_DISPLAY_AREA_PERCENT,
                enabled = prefs[KEY_ENABLED] ?: true,
                blockRulesRaw = prefs[KEY_BLOCK_RULES].orEmpty(),
            )
        }
        .distinctUntilChanged()

    /** 直接喂给渲染器的配置。 */
    val config: Flow<DanmakuConfig> = preferences.map { it.config }.distinctUntilChanged()

    /** 屏蔽词规则列表。 */
    val blockRules: Flow<List<String>> = preferences.map { it.blockRules }.distinctUntilChanged()

    /**
     * 一次写回五项样式（透明度 / 字号 / 滚动时长 / 描边宽度 / 显示区域）。
     *
     * ### 为什么不拆成五个 setter
     * 调用方是弹幕设置面板上的五根滑杆，它们在**松手时**整体提交（见 `PlayerViewModel.persistDanmakuStyle`）。
     * 一个 `edit` 只读-改-写一次 preferences 文件，比连着五次划算；
     * 而且拖动过程中的即时反馈由内存镜像负责，本来也不需要每帧落库。
     *
     * 传入值在这里统一钳位：落库的值必须和 [DanmakuPreferenceCodec] 的约定一致，
     * 否则下次读出来还得再纠一遍。
     */
    suspend fun setStyle(
        alphaPercent: Int,
        textScalePercent: Int,
        scrollSeconds: Float,
        strokeWidthDp: Float,
        displayAreaPercent: Int,
    ) {
        val alpha = alphaPercent.coerceIn(
            DanmakuPreferenceCodec.MIN_ALPHA_PERCENT,
            DanmakuPreferenceCodec.MAX_ALPHA_PERCENT,
        )
        val scale = textScalePercent.coerceIn(
            DanmakuPreferenceCodec.MIN_TEXT_SCALE_PERCENT,
            DanmakuPreferenceCodec.MAX_TEXT_SCALE_PERCENT,
        )
        val scroll = if (scrollSeconds.isNaN()) {
            DanmakuPreferenceCodec.DEFAULT_SCROLL_SECONDS
        } else {
            scrollSeconds.coerceIn(
                DanmakuPreferenceCodec.MIN_SCROLL_SECONDS,
                DanmakuPreferenceCodec.MAX_SCROLL_SECONDS,
            )
        }
        // 钳位 + 量化到 0.1dp 交给 codec：滑杆抛来的是连续值，只有这一条出口收敛
        val stroke = DanmakuPreferenceCodec.normalizeStrokeWidthDp(strokeWidthDp)
        val area = displayAreaPercent.coerceIn(
            DanmakuPreferenceCodec.MIN_DISPLAY_AREA_PERCENT,
            DanmakuPreferenceCodec.MAX_DISPLAY_AREA_PERCENT,
        )

        dataStore.edit { prefs ->
            prefs[KEY_ALPHA] = alpha
            prefs[KEY_TEXT_SCALE] = scale
            prefs[KEY_SCROLL_SECONDS] = scroll
            prefs[KEY_STROKE_DP] = stroke
            prefs[KEY_DISPLAY_AREA] = area
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ENABLED] = enabled }
    }

    /** 屏蔽词原文（按行分隔）。写入时统一格式化，避免尾随换行反复触发 UI 重组。 */
    suspend fun setBlockRules(raw: String) {
        val canonical = DanmakuPreferenceCodec.formatRules(DanmakuPreferenceCodec.parseRules(raw))
        dataStore.edit { prefs -> prefs[KEY_BLOCK_RULES] = canonical }
    }

    /** 恢复默认（屏蔽词一并清空）。 */
    suspend fun reset() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_ALPHA)
            prefs.remove(KEY_TEXT_SCALE)
            prefs.remove(KEY_SCROLL_SECONDS)
            prefs.remove(KEY_STROKE_DP)
            prefs.remove(KEY_DISPLAY_AREA)
            prefs.remove(KEY_ENABLED)
            prefs.remove(KEY_BLOCK_RULES)
        }
    }

    private companion object {
        val KEY_ALPHA = intPreferencesKey("danmaku_alpha_percent")
        val KEY_TEXT_SCALE = intPreferencesKey("danmaku_text_scale_percent")
        val KEY_SCROLL_SECONDS = floatPreferencesKey("danmaku_scroll_seconds")

        /** 描边宽度（dp）。P3 之后新增：老版本没有这个 key，读的时候回落到默认值即可，不需要迁移。 */
        val KEY_STROKE_DP = floatPreferencesKey("danmaku_stroke_width_dp")

        /**
         * 显示区域（画面高度的百分比）。
         *
         * 同样是**新增 key + 读侧回落默认值**：老版本没有它 → 读出来是 100（整屏），
         * 与升级前的观感一致，不会让老用户一开应用发现弹幕区域莫名变小。
         */
        val KEY_DISPLAY_AREA = intPreferencesKey("danmaku_display_area_percent")
        val KEY_ENABLED = booleanPreferencesKey("danmaku_enabled")
        val KEY_BLOCK_RULES = stringPreferencesKey("danmaku_block_rules")
    }
}
