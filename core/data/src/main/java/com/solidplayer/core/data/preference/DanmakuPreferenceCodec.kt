package com.solidplayer.core.data.preference

import com.solidplayer.core.danmaku.model.DanmakuConfig
import kotlin.math.round

/**
 * 弹幕样式偏好的取值约束与编解码（P2）。
 *
 * **纯逻辑、零 Android 依赖**，可直接 JVM 单测（文档 §14 要求关键算法带单测）。
 *
 * ### 为什么所有取值都要钳位
 * DataStore 里的值是「历史遗留 + 用户输入」的混合体：旧版本可能没这几个 key、
 * 用户也可能写进越界值。钳位放在**读取侧**而不是写入侧，
 * 是为了让「写坏了的值」也能被纠正，而不是让渲染器拿着 `alpha = 8f` 画出一片空白。
 */
object DanmakuPreferenceCodec {

    const val DEFAULT_ALPHA_PERCENT = 100
    const val DEFAULT_TEXT_SCALE_PERCENT = 100
    const val DEFAULT_SCROLL_SECONDS = 8f
    const val DEFAULT_STROKE_DP = 1.2f

    /** 显示区域默认**整屏** —— 老用户装上这个版本，观感与之前完全一致，不会被静默改掉。 */
    const val DEFAULT_DISPLAY_AREA_PERCENT = 100

    const val MIN_ALPHA_PERCENT = 0
    const val MAX_ALPHA_PERCENT = 100

    /** 字号 50% ~ 200%：再小看不清，再大半屏塞不下一行 */
    const val MIN_TEXT_SCALE_PERCENT = 50
    const val MAX_TEXT_SCALE_PERCENT = 200

    /** 横穿屏幕 3 ~ 12 秒：低于 3s 快得像闪屏，高于 12s 会大量积压 */
    const val MIN_SCROLL_SECONDS = 3f
    const val MAX_SCROLL_SECONDS = 12f

    /**
     * 描边宽度 0 ~ 3dp：0 = 实心，3dp 已经是「字被黑边糊住」的极限。
     *
     * 默认 1.2dp（淡黑边）—— 白字落在亮背景上时的可读性兜底。
     */
    const val MIN_STROKE_DP = 0f
    const val MAX_STROKE_DP = 3f

    /**
     * 显示区域 10% ~ 100%（画面上部，100% = 整屏）。
     *
     * 下限取 10%：手机上约等于「只留一两行」，再低就等于把弹幕关掉了 ——
     * 而想关弹幕的用户有开关，不需要靠把区域拖到看不见。
     */
    const val MIN_DISPLAY_AREA_PERCENT = 10
    const val MAX_DISPLAY_AREA_PERCENT = 100

    /** 描边宽度的落库精度：滑杆是连续的，存成 0.1dp 的整数倍，读出来才整齐。 */
    private const val STROKE_STEP = 10f

    /**
     * 描边宽度：NaN 退回默认、越界钳位、再量化到 0.1dp。
     *
     * 量化放在这里而不是 UI：滑杆每一帧都会抛一个连续 float 过来，
     * 只有落库/换算这一个出口收敛，值才不会以 `1.2000000476837158` 这种形态到处都是。
     */
    fun normalizeStrokeWidthDp(value: Float): Float {
        val clamp = if (value.isNaN()) DEFAULT_STROKE_DP else value.coerceIn(MIN_STROKE_DP, MAX_STROKE_DP)
        return round(clamp * STROKE_STEP) / STROKE_STEP
    }

    /** 把五项偏好拼成渲染器能吃的 [DanmakuConfig]。 */
    fun toConfig(
        alphaPercent: Int,
        textScalePercent: Int,
        scrollSeconds: Float,
        strokeWidthDp: Float = DEFAULT_STROKE_DP,
        displayAreaPercent: Int = DEFAULT_DISPLAY_AREA_PERCENT,
        enabled: Boolean,
    ): DanmakuConfig = DanmakuConfig(
        textScale = clamp(textScalePercent, MIN_TEXT_SCALE_PERCENT, MAX_TEXT_SCALE_PERCENT) / 100f,
        alpha = clamp(alphaPercent, MIN_ALPHA_PERCENT, MAX_ALPHA_PERCENT) / 100f,
        scrollDurationSeconds = clamp(
            value = scrollSeconds,
            min = MIN_SCROLL_SECONDS,
            max = MAX_SCROLL_SECONDS,
            fallback = DEFAULT_SCROLL_SECONDS,
        ),
        strokeWidthDp = normalizeStrokeWidthDp(strokeWidthDp),
        displayAreaRatio = clamp(
            displayAreaPercent,
            MIN_DISPLAY_AREA_PERCENT,
            MAX_DISPLAY_AREA_PERCENT,
        ) / 100f,
        enabled = enabled,
    )

    /**
     * 屏蔽词原文 → 规则列表：按行拆、裁空白、去空行。
     *
     * 用户是从输入框里粘进来的，尾随换行和缩进都很常见，这里统一消化掉，
     * 免得下游 `DanmakuFilter` 还要重复做一遍。
     */
    fun parseRules(raw: String?): List<String> =
        raw.orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun formatRules(rules: List<String>): String = rules.joinToString("\n")

    private fun clamp(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)

    private fun clamp(value: Float, min: Float, max: Float, fallback: Float): Float =
        if (value.isNaN()) fallback else value.coerceIn(min, max)
}

/**
 * 弹幕样式偏好（全局，落 DataStore；文档 §7.2 —— 只有「每集时轴偏移」才进 DB）。
 *
 * 字段存的是**用户可读的单位**（百分比、秒），换算成渲染参数的活交给 [config]，
 * 这样 UI 层不需要知道 `alpha` 是 0~1 还是 0~255。
 */
data class DanmakuPreferences(
    val alphaPercent: Int = DanmakuPreferenceCodec.DEFAULT_ALPHA_PERCENT,
    val textScalePercent: Int = DanmakuPreferenceCodec.DEFAULT_TEXT_SCALE_PERCENT,
    val scrollSeconds: Float = DanmakuPreferenceCodec.DEFAULT_SCROLL_SECONDS,
    /** 描边宽度（dp）；0 = 实心，默认 1.2dp 细黑边 */
    val strokeWidthDp: Float = DanmakuPreferenceCodec.DEFAULT_STROKE_DP,
    /**
     * 弹幕显示区域占画面高度的百分比（**自顶部起算**），100 = 整屏。
     *
     * 这是「弹幕太密、挡画面」的主治项：区域越小轨道越少，
     * 挤不进来的弹幕直接被丢弃，同屏条数随之下降。
     */
    val displayAreaPercent: Int = DanmakuPreferenceCodec.DEFAULT_DISPLAY_AREA_PERCENT,
    val enabled: Boolean = true,
    /** 屏蔽词原文，按行分隔 */
    val blockRulesRaw: String = "",
) {

    val config: DanmakuConfig
        get() = DanmakuPreferenceCodec.toConfig(
            alphaPercent = alphaPercent,
            textScalePercent = textScalePercent,
            scrollSeconds = scrollSeconds,
            strokeWidthDp = strokeWidthDp,
            displayAreaPercent = displayAreaPercent,
            enabled = enabled,
        )

    val blockRules: List<String>
        get() = DanmakuPreferenceCodec.parseRules(blockRulesRaw)
}
