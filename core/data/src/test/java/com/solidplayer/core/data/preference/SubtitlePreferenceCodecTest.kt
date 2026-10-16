package com.solidplayer.core.data.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕样式偏好编解码单测。
 *
 * 与 `DanmakuPreferenceCodecTest` 同一取向：测试重点放在**钳位边界**与**默认值**，
 * 而不是 happy path —— 这一层的价值全在「脏数据不炸渲染器」。
 */
class SubtitlePreferenceCodecTest {

    @Test
    fun `默认字号是一个能看的中间值`() {
        // 回归点：默认值一旦被改到区间边缘，用户每次都要手动调回来
        val def = SubtitlePreferenceCodec.DEFAULT_TEXT_SIZE_SP
        assertTrue(def > SubtitlePreferenceCodec.MIN_TEXT_SIZE_SP)
        assertTrue(def < SubtitlePreferenceCodec.MAX_TEXT_SIZE_SP)
        assertEquals(def, SubtitlePreferenceCodec.normalizeTextSizeSp(def))
        assertEquals(def, SubtitlePreferences().textSizeSp)
    }

    @Test
    fun `字号越界被钳到约定区间`() {
        assertEquals(
            SubtitlePreferenceCodec.MIN_TEXT_SIZE_SP,
            SubtitlePreferenceCodec.normalizeTextSizeSp(-100),
        )
        assertEquals(
            SubtitlePreferenceCodec.MIN_TEXT_SIZE_SP,
            SubtitlePreferenceCodec.normalizeTextSizeSp(0),
        )
        assertEquals(
            SubtitlePreferenceCodec.MAX_TEXT_SIZE_SP,
            SubtitlePreferenceCodec.normalizeTextSizeSp(999),
        )
    }

    @Test
    fun `区间内的字号原样保留`() {
        assertEquals(30, SubtitlePreferenceCodec.normalizeTextSizeSp(30))
        assertEquals(12, SubtitlePreferenceCodec.normalizeTextSizeSp(12))
        assertEquals(40, SubtitlePreferenceCodec.normalizeTextSizeSp(40))
    }

    @Test
    fun `默认开启细描边`() {
        // 白字落在亮背景上时必须有一圈兜底的黑边（但不要黑底方块）
        assertTrue(SubtitlePreferenceCodec.DEFAULT_OUTLINE_ENABLED)
        assertTrue(SubtitlePreferences().outlineEnabled)
    }

    @Test
    fun `字号文案带上单位并顺手钳位`() {
        assertEquals("22sp", SubtitlePreferenceCodec.describeTextSize(22))
        assertEquals("12sp", SubtitlePreferenceCodec.describeTextSize(-5))
        assertEquals("40sp", SubtitlePreferenceCodec.describeTextSize(10_000))
    }

    @Test
    fun `描边开关可以关掉`() {
        assertFalse(SubtitlePreferences(outlineEnabled = false).outlineEnabled)
    }
}
