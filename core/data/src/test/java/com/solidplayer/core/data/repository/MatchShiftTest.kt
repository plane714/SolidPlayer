package com.solidplayer.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 服务端 shift 换算单测（P2）。
 *
 * 这个函数的产出会直接写进 `play_history`，一旦被脏数据污染，
 * 用户看到的就是「弹幕整体跑到片尾」，所以异常输入的覆盖比正常值更重要。
 */
class MatchShiftTest {

    @Test
    fun `秒换算成毫秒`() {
        assertEquals(1_500L, MatchShift.toOffsetMs(1.5))
        assertEquals(2_000L, MatchShift.toOffsetMs(2.0))
    }

    @Test
    fun `负偏移表示弹幕提前`() {
        assertEquals(-2_250L, MatchShift.toOffsetMs(-2.25))
    }

    @Test
    fun `零与亚毫秒都归零`() {
        assertEquals(0L, MatchShift.toOffsetMs(0.0))
        assertEquals(0L, MatchShift.toOffsetMs(0.0001))
    }

    @Test
    fun `NaN 与无穷当作没有建议`() {
        assertEquals(0L, MatchShift.toOffsetMs(Double.NaN))
        assertEquals(0L, MatchShift.toOffsetMs(Double.POSITIVE_INFINITY))
        assertEquals(0L, MatchShift.toOffsetMs(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `超出上限的偏移被钳到正负一分钟`() {
        assertEquals(60_000L, MatchShift.toOffsetMs(100.0))
        assertEquals(60_000L, MatchShift.toOffsetMs(MatchShift.MAX_ABS_SECONDS))
        assertEquals(-60_000L, MatchShift.toOffsetMs(-9_999.0))
    }

    @Test
    fun `正好在边界内不被钳位`() {
        assertEquals(59_000L, MatchShift.toOffsetMs(59.0))
        assertEquals(-59_500L, MatchShift.toOffsetMs(-59.5))
    }
}
