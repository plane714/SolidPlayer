package com.solidplayer.core.danmaku.filter

import com.solidplayer.core.danmaku.model.DanmakuItem
import com.solidplayer.core.danmaku.model.DanmakuMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏蔽词过滤器单测（P2）。
 *
 * 重点覆盖三类边界：
 * - 字面量与正则两条路径互不干扰
 * - 正则写错时**不吞规则**（退化成字面量）
 * - 空规则时零成本直通
 */
class DanmakuFilterTest {

    private fun item(text: String, timeMs: Long = 0L) =
        DanmakuItem(timeMs = timeMs, mode = DanmakuMode.SCROLL, color = 0xFFFFFF, text = text)

    // ── 字面量 ────────────────────────────────────────────────────

    @Test
    fun `普通词按包含匹配`() {
        val filter = DanmakuFilter.of(listOf("前排"))
        assertTrue(filter.matches("前排占座"))
        assertTrue(filter.matches("我来前排"))
        assertFalse(filter.matches("后排就坐"))
    }

    @Test
    fun `普通词匹配忽略大小写`() {
        val filter = DanmakuFilter.of(listOf("Spoiler"))
        assertTrue(filter.matches("SPOILER ALERT"))
        assertTrue(filter.matches("this is a spoiler"))
        assertFalse(filter.matches("no hints here"))
    }

    @Test
    fun `规则两端的空白被裁掉`() {
        val filter = DanmakuFilter.of(listOf("  前排  "))
        assertTrue(filter.matches("前排"))
    }

    // ── 正则 ──────────────────────────────────────────────────────

    @Test
    fun `斜杠包裹的规则按正则匹配`() {
        val filter = DanmakuFilter.of(listOf("/^\\d{4}-\\d{2}-\\d{2}$/"))
        assertTrue(filter.matches("2026-10-14"))
        assertFalse(filter.matches("前缀2026-10-14"))
    }

    @Test
    fun `正则同样忽略大小写`() {
        val filter = DanmakuFilter.of(listOf("/^abc$/"))
        assertTrue(filter.matches("ABC"))
    }

    @Test
    fun `正则非法时退化为字面量而不是丢弃规则`() {
        // "[a-" 是非法正则
        val filter = DanmakuFilter.of(listOf("/[a-/"))
        assertEquals(1, filter.size)
        assertTrue(filter.matches("含 [a- 的弹幕"))
        assertFalse(filter.matches("无关内容"))
    }

    @Test
    fun `单独一个斜杠按字面量处理`() {
        val filter = DanmakuFilter.of(listOf("/"))
        assertEquals(1, filter.size)
        assertTrue(filter.matches("1/2"))
    }

    // ── 空输入 ────────────────────────────────────────────────────

    @Test
    fun `空规则与空白规则都不生效`() {
        assertTrue(DanmakuFilter.of(emptyList()).isEmpty)
        assertTrue(DanmakuFilter.of(listOf("", "   ", "\n")).isEmpty)
    }

    @Test
    fun `重复规则只保留一条`() {
        assertEquals(1, DanmakuFilter.of(listOf("前排", "前排", " 前排 ")).size)
    }

    // ── apply ─────────────────────────────────────────────────────

    @Test
    fun `apply 丢弃命中项并保持原有顺序`() {
        val items = listOf(item("a", 1L), item("前排", 2L), item("b", 3L), item("前排来了", 4L))
        val kept = DanmakuFilter.of(listOf("前排")).apply(items)
        assertEquals(listOf("a", "b"), kept.map { it.text })
    }

    @Test
    fun `apply 在空规则时原样返回同一个列表`() {
        val items = listOf(item("a"), item("b"))
        assertSame(items, DanmakuFilter.NONE.apply(items))
    }

    @Test
    fun `正则与字面量可以同时生效`() {
        val filter = DanmakuFilter.of(listOf("前排", "/^\\d+$/"))
        assertTrue(filter.matches("前排"))
        assertTrue(filter.matches("12345"))
        assertFalse(filter.matches("12a34"))
    }
}
