package com.solidplayer.core.danmaku.model

import com.solidplayer.core.danmaku.parser.PFieldParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 颜色换算单测 —— 守住文档 §17.3 那个「弹幕全部消失」的缺陷。
 *
 * 这类缺陷的特点是：**不崩溃、不报错、日志里什么都没有**。
 * `Paint.color` 把 alpha 覆盖成 0 之后，画面只是「安静地什么都没有」，
 * 事后只能靠断言回溯，所以这里把不变量固定下来。
 */
class DanmakuColorTest {

    // ── 核心不变量 ────────────────────────────────────────────────

    @Test
    fun `三字节颜色补全后 alpha 必须是 255`() {
        val colors = listOf(0xFFFFFF, 0x000000, 0xFF0000, 0x00FF00, 0x0000FF, 0x123456)
        for (rgb in colors) {
            val argb = DanmakuColor.withAlpha(rgb, alpha = 255)
            assertEquals(
                "颜色 0x%06X 补全后 alpha 不是 255，弹幕会整体不可见".format(rgb),
                0xFF,
                argb ushr 24 and 0xFF,
            )
            assertEquals("24 位 RGB 部分必须原样保留", rgb, argb and 0xFFFFFF)
        }
    }

    @Test
    fun `黑色不能变成全透明`() {
        // 最危险的一条：0x000000 一旦被当成完整 ARGB 就是「全透明黑」，
        // 直接丢给 Paint 会静默消失
        assertEquals(0xFF000000.toInt(), DanmakuColor.withAlpha(0x000000, alpha = 255))
    }

    @Test
    fun `opaque 等价于全不透明`() {
        assertEquals(0xFFFFFFFF.toInt(), DanmakuColor.opaque(0xFFFFFF))
        assertEquals(0xFF123456.toInt(), DanmakuColor.opaque(0x123456))
        // 传进来的高 8 位是脏数据时要丢掉，不能被带进结果
        assertEquals(0xFFFFFFFF.toInt(), DanmakuColor.opaque(0x7FFFFFFF))
    }

    // ── 透明度施加 ────────────────────────────────────────────────

    @Test
    fun `透明度 0 表示完全透明`() {
        assertEquals(0x00FFFFFF, DanmakuColor.withAlpha(0xFFFFFF, alpha = 0))
    }

    @Test
    fun `透明度按 8 位通道线性施加`() {
        assertEquals(0x80FFFFFF.toInt(), DanmakuColor.withAlpha(0xFFFFFF, alpha = 0x80))
        assertEquals(0x40FF0000, DanmakuColor.withAlpha(0xFF0000, alpha = 0x40))
    }

    @Test
    fun `越界的透明度被钳位而不是回绕`() {
        // 回绕（如 256 → 0、-1 → 255）会让滑杆一拖到边界弹幕瞬间消失
        assertEquals(0xFFFFFFFF.toInt(), DanmakuColor.withAlpha(0xFFFFFF, alpha = 256))
        assertEquals(0xFFFFFFFF.toInt(), DanmakuColor.withAlpha(0xFFFFFF, alpha = 999))
        assertEquals(0x00FFFFFF, DanmakuColor.withAlpha(0xFFFFFF, alpha = -1))
    }

    // ── 端到端：解析 → 绘制前的换算 ───────────────────────────────

    @Test
    fun `解析出的弹幕颜色经换算后可直接绘制`() {
        // 16777215 = 白、16711680 = 红，都是弹弹play 线上真实取值
        for (raw in listOf("12.34,1,16777215,uid", "1.00,5,16711680,uid")) {
            val parsed = PFieldParser.parse(raw)!!
            val argb = DanmakuColor.withAlpha(parsed.color, alpha = 255)
            assertEquals(
                "解析出的颜色换算后 alpha 仍是 0 —— 弹幕会一条都看不见",
                0xFF,
                argb ushr 24 and 0xFF,
            )
            assertEquals("解析结果的 24 位 RGB 不能被换算改动", parsed.color, argb and 0xFFFFFF)
        }
    }
}
