package com.solidplayer.core.danmaku.parser

import com.solidplayer.core.danmaku.model.DanmakuMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `p` 字段解析单测 —— 文档 §13 的 P0 三大技术风险点之一。
 * 这里同时锁住「模式值与 B 站不同」这一最容易踩的坑。
 */
class PFieldParserTest {

    // ── 正例：模式映射 ────────────────────────────────────────────

    @Test
    fun `模式 1 解析为滚动`() {
        val parsed = PFieldParser.parse("12.34,1,16777215,abc123")
        assertNotNull(parsed)
        assertEquals(DanmakuMode.SCROLL, parsed!!.mode)
    }

    @Test
    fun `模式 5 解析为顶部 - 与 B 站相反`() {
        val parsed = PFieldParser.parse("1.00,5,16711680,user")
        assertNotNull(parsed)
        assertEquals(DanmakuMode.TOP, parsed!!.mode)
    }

    @Test
    fun `模式 4 解析为底部 - 与 B 站相反`() {
        val parsed = PFieldParser.parse("1.00,4,16711680,user")
        assertNotNull(parsed)
        assertEquals(DanmakuMode.BOTTOM, parsed!!.mode)
    }

    @Test
    fun `未知模式按滚动处理而不是丢弃`() {
        assertEquals(DanmakuMode.SCROLL, PFieldParser.parse("1.00,6,0,user")!!.mode)
        assertEquals(DanmakuMode.SCROLL, PFieldParser.parse("1.00,0,0,user")!!.mode)
        assertEquals(DanmakuMode.SCROLL, PFieldParser.parse("1.00,99,0,user")!!.mode)
    }

    // ── 正例：时间与颜色 ──────────────────────────────────────────

    @Test
    fun `时间保留两位小数精度`() {
        assertEquals(12_340L, PFieldParser.parse("12.34,1,0,u")!!.timeMs)
        assertEquals(445_600L, PFieldParser.parse("445.6,1,0,u")!!.timeMs)
        assertEquals(0L, PFieldParser.parse("0.00,1,0,u")!!.timeMs)
        assertEquals(1_005L, PFieldParser.parse("1.005,1,0,u")!!.timeMs)
    }

    @Test
    fun `颜色按 R 65536 + G 256 + B 解码`() {
        // 红
        assertEquals(0xFF0000, PFieldParser.parse("0.00,1,16711680,u")!!.color)
        // 白
        assertEquals(0xFFFFFF, PFieldParser.parse("0.00,1,16777215,u")!!.color)
        // 蓝
        assertEquals(0x0000FF, PFieldParser.parse("0.00,1,255,u")!!.color)
    }

    @Test
    fun `用户 ID 原样取出`() {
        assertEquals("12345", PFieldParser.parse("0.00,1,0,12345")!!.userId)
    }

    // ── 反例：脏数据必须只跳过该条，不抛异常 ──────────────────────

    @Test
    fun `字段不足返回 null`() {
        assertNull(PFieldParser.parse(""))
        assertNull(PFieldParser.parse("12.34"))
        assertNull(PFieldParser.parse("12.34,1"))
    }

    @Test
    fun `时间非法返回 null`() {
        assertNull(PFieldParser.parse("abc,1,0,u"))
        assertNull(PFieldParser.parse("NaN,1,0,u"))
        assertNull(PFieldParser.parse("-1.00,1,0,u"))
    }

    @Test
    fun `模式非法返回 null`() {
        assertNull(PFieldParser.parse("1.00,x,0,u"))
        assertNull(PFieldParser.parse("1.00,,0,u"))
    }

    @Test
    fun `颜色非法回退为白色而不是丢弃整条`() {
        val parsed = PFieldParser.parse("1.00,1,xyz,u")
        assertNotNull(parsed)
        assertEquals(PFieldParser.DEFAULT_COLOR, parsed!!.color)
    }

    @Test
    fun `允许只有三个字段`() {
        val parsed = PFieldParser.parse("1.00,1,16777215")
        assertNotNull(parsed)
        assertEquals("", parsed!!.userId)
    }

    // ── 组装与批量 ────────────────────────────────────────────────

    @Test
    fun `文本为空时忽略该条`() {
        assertNull(PFieldParser.parseItem("1.00,1,0,u", "   "))
        assertNull(PFieldParser.parseItem("bad", "hello"))
    }

    @Test
    fun `文本保留原内容并去除首尾空白`() {
        val item = PFieldParser.parseItem("1.00,1,16711680,u", "  前方高能  ")
        assertNotNull(item)
        assertEquals("前方高能", item!!.text)
        assertEquals(0xFF0000, item.color)
    }

    @Test
    fun `批量解析逐条跳过脏数据`() {
        val items = PFieldParser.parseAll(
            listOf(
                "1.00,1,16777215,a" to "第一条",
                "garbage" to "丢弃",
                "2.00,5,16711680,b" to "第二条",
                "3.00,4,255,c" to "",
            ),
        )
        assertEquals(2, items.size)
        assertEquals("第一条", items[0].text)
        assertEquals("第二条", items[1].text)
        assertEquals(DanmakuMode.TOP, items[1].mode)
    }
}
