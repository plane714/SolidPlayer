package com.solidplayer.core.data.preference

import com.solidplayer.core.danmaku.model.DanmakuConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕样式偏好编解码单测（P2）。
 *
 * 这一层的价值全在「脏数据不炸渲染器」上，所以测试重点放在**钳位边界**与**异常输入**，
 * 而不是 happy path。
 */
class DanmakuPreferenceCodecTest {

    // ── 换算 ──────────────────────────────────────────────────────

    @Test
    fun `百分比换算成渲染参数`() {
        val config = DanmakuPreferenceCodec.toConfig(
            alphaPercent = 50,
            textScalePercent = 150,
            scrollSeconds = 6f,
            enabled = true,
        )
        assertEquals(0.5f, config.alpha, 1e-6f)
        assertEquals(1.5f, config.textScale, 1e-6f)
        assertEquals(6f, config.scrollDurationSeconds, 1e-6f)
    }

    @Test
    fun `越界的透明度被钳到 0 到 100`() {
        assertEquals(0f, configOf(alpha = -30).alpha, 1e-6f)
        assertEquals(1f, configOf(alpha = 999).alpha, 1e-6f)
    }

    @Test
    fun `越界的字号被钳到 50 到 200`() {
        assertEquals(0.5f, configOf(scale = 10).textScale, 1e-6f)
        assertEquals(2f, configOf(scale = 5000).textScale, 1e-6f)
    }

    @Test
    fun `越界的滚动时长被钳到 3 到 12 秒`() {
        assertEquals(3f, configOf(scroll = 0.5f).scrollDurationSeconds, 1e-6f)
        assertEquals(12f, configOf(scroll = 99f).scrollDurationSeconds, 1e-6f)
    }

    @Test
    fun `滚动时长是 NaN 时回落到默认值`() {
        assertEquals(
            DanmakuPreferenceCodec.DEFAULT_SCROLL_SECONDS,
            configOf(scroll = Float.NaN).scrollDurationSeconds,
            1e-6f,
        )
    }

    @Test
    fun `开关原样透传`() {
        assertFalse(configOf(enabled = false).enabled)
        assertTrue(configOf().enabled)
    }

    @Test
    fun `不传入的字段保持 DanmakuConfig 的默认值`() {
        val config = configOf()
        assertEquals(DanmakuConfig().textSizeSp, config.textSizeSp, 1e-6f)
        assertEquals(DanmakuConfig().strokeWidthDp, config.strokeWidthDp, 1e-6f)
    }

    // ── 描边宽度 ──────────────────────────────────────────────────

    @Test
    fun `默认带一点描边而不是实心`() {
        // 回归点：默认值一旦退回 0，白字落在亮背景上就糊成一片
        assertEquals(1.2f, DanmakuPreferenceCodec.DEFAULT_STROKE_DP, 1e-6f)
        assertEquals(1.2f, configOf().strokeWidthDp, 1e-6f)
        assertEquals(1.2f, DanmakuPreferences().config.strokeWidthDp, 1e-6f)
    }

    @Test
    fun `描边宽度 0 表示实心`() {
        assertEquals(0f, configOf(stroke = 0f).strokeWidthDp, 1e-6f)
        assertEquals(0f, configOf(stroke = -1f).strokeWidthDp, 1e-6f)
    }

    @Test
    fun `越界的描边宽度被钳到 0 到 3dp`() {
        assertEquals(3f, configOf(stroke = 99f).strokeWidthDp, 1e-6f)
    }

    @Test
    fun `描边宽度量化到 0_1dp`() {
        // 滑杆抛来的是连续值；落库/换算都按 0.1dp 收敛
        assertEquals(1.2f, DanmakuPreferenceCodec.normalizeStrokeWidthDp(1.234f), 1e-6f)
        assertEquals(1.3f, DanmakuPreferenceCodec.normalizeStrokeWidthDp(1.26f), 1e-6f)
        assertEquals(2f, DanmakuPreferenceCodec.normalizeStrokeWidthDp(1.98f), 1e-6f)
    }

    @Test
    fun `描边宽度是 NaN 时回落到默认值`() {
        assertEquals(
            DanmakuPreferenceCodec.DEFAULT_STROKE_DP,
            DanmakuPreferenceCodec.normalizeStrokeWidthDp(Float.NaN),
            1e-6f,
        )
    }

    // ── 显示区域 ──────────────────────────────────────────────────

    @Test
    fun `显示区域默认整屏`() {
        // 回归点：默认值一旦不是 100，老用户升级后弹幕区域会莫名变小
        assertEquals(100, DanmakuPreferenceCodec.DEFAULT_DISPLAY_AREA_PERCENT)
        assertEquals(1f, configOf().displayAreaRatio, 1e-6f)
        assertEquals(1f, DanmakuPreferences().config.displayAreaRatio, 1e-6f)
    }

    @Test
    fun `显示区域百分比换算成比例`() {
        assertEquals(0.5f, configOf(area = 50).displayAreaRatio, 1e-6f)
        assertEquals(0.25f, configOf(area = 25).displayAreaRatio, 1e-6f)
    }

    @Test
    fun `越界的显示区域被钳到 10 到 100`() {
        assertEquals(0.1f, configOf(area = 0).displayAreaRatio, 1e-6f)
        assertEquals(0.1f, configOf(area = -30).displayAreaRatio, 1e-6f)
        assertEquals(1f, configOf(area = 999).displayAreaRatio, 1e-6f)
    }

    @Test
    fun `显示区域高度按画布高度折算`() {
        val config = configOf(area = 40)
        assertEquals(400f, config.displayAreaHeightPx(1000), 0.001f)
        // 越界比例在折算口径上也要兜住，不能让轨道跑到画面之外
        assertEquals(50f, configOf(area = -1).displayAreaHeightPx(500), 0.001f)
    }

    // ── 屏蔽词规则 ────────────────────────────────────────────────

    @Test
    fun `规则按行拆分并裁掉空白与空行`() {
        val rules = DanmakuPreferenceCodec.parseRules("  前排  \n\n后排\n   \n/a.*b/")
        assertEquals(listOf("前排", "后排", "/a.*b/"), rules)
    }

    @Test
    fun `null 与空串都解析成空列表`() {
        assertTrue(DanmakuPreferenceCodec.parseRules(null).isEmpty())
        assertTrue(DanmakuPreferenceCodec.parseRules("").isEmpty())
        assertTrue(DanmakuPreferenceCodec.parseRules("\n \n\t\n").isEmpty())
    }

    @Test
    fun `格式化与解析可以往返`() {
        val rules = listOf("前排", "/^\\d+$/")
        val raw = DanmakuPreferenceCodec.formatRules(rules)
        assertEquals(rules, DanmakuPreferenceCodec.parseRules(raw))
    }

    // ── DanmakuPreferences ───────────────────────────────────────

    @Test
    fun `偏好对象同时提供配置与规则`() {
        val prefs = DanmakuPreferences(
            alphaPercent = 80,
            textScalePercent = 120,
            scrollSeconds = 5f,
            strokeWidthDp = 0.8f,
            enabled = false,
            blockRulesRaw = "前排\n/a/",
        )
        assertEquals(0.8f, prefs.config.alpha, 1e-6f)
        assertEquals(1.2f, prefs.config.textScale, 1e-6f)
        assertEquals(0.8f, prefs.config.strokeWidthDp, 1e-6f)
        assertFalse(prefs.config.enabled)
        assertEquals(listOf("前排", "/a/"), prefs.blockRules)
    }

    @Test
    fun `默认偏好等于文档默认值`() {
        val prefs = DanmakuPreferences()
        assertEquals(1f, prefs.config.alpha, 1e-6f)
        assertEquals(1f, prefs.config.textScale, 1e-6f)
        assertEquals(8f, prefs.config.scrollDurationSeconds, 1e-6f)
        assertTrue(prefs.config.strokeWidthDp > 0f)
        assertTrue(prefs.config.enabled)
        assertTrue(prefs.blockRules.isEmpty())
    }

    private fun configOf(
        alpha: Int = DanmakuPreferenceCodec.DEFAULT_ALPHA_PERCENT,
        scale: Int = DanmakuPreferenceCodec.DEFAULT_TEXT_SCALE_PERCENT,
        scroll: Float = DanmakuPreferenceCodec.DEFAULT_SCROLL_SECONDS,
        stroke: Float = DanmakuPreferenceCodec.DEFAULT_STROKE_DP,
        area: Int = DanmakuPreferenceCodec.DEFAULT_DISPLAY_AREA_PERCENT,
        enabled: Boolean = true,
    ) = DanmakuPreferenceCodec.toConfig(
        alphaPercent = alpha,
        textScalePercent = scale,
        scrollSeconds = scroll,
        strokeWidthDp = stroke,
        displayAreaPercent = area,
        enabled = enabled,
    )
}
