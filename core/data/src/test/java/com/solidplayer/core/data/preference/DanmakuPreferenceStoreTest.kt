package com.solidplayer.core.data.preference

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 弹幕样式偏好存储单测。
 *
 * ### 为什么非要给这个类写单测
 * 这里的 bug **不会崩、不会报错、日志里也什么都没有**：写进去的值如果不对，
 * 读出来的还是旧值，而读取侧又会老老实实把它当成「用户的选择」。
 * 曾经的版本就是这样 —— `setAlphaPercent(40)` 执行完，DataStore 里仍是 100，
 * Flow 因为 `distinctUntilChanged` 一声不吭，界面上的滑杆纹丝不动。
 * 只有「写完之后再读出来断言」才能拦住它。
 *
 * 存储用真实的 DataStore（临时文件 + okio 实现，纯 JVM），
 * 不 mock 掉 `edit` —— 要测的恰恰就是「写盘之后读回来是什么」。
 */
class DanmakuPreferenceStoreTest {

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var store: DanmakuPreferenceStore

    @Before
    fun setUp() {
        file = File(
            File(System.getProperty("java.io.tmpdir") ?: "."),
            "danmaku-prefs-${System.nanoTime()}.preferences_pb",
        )
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = DanmakuPreferenceStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    private fun read(): DanmakuPreferences = runBlocking { store.preferences.first() }

    @Test
    fun `setStyle 把新值真正写进 DataStore`() {
        // 回归点：新值必须落到存储里，而不是被当成「读出来的旧值」又写回去
        runBlocking {
            store.setStyle(
                alphaPercent = 30,
                textScalePercent = 150,
                scrollSeconds = 5f,
                strokeWidthDp = 2f,
                displayAreaPercent = 50,
            )
        }

        val prefs = read()
        assertEquals(30, prefs.alphaPercent)
        assertEquals(150, prefs.textScalePercent)
        assertEquals(5f, prefs.scrollSeconds, 0.001f)
        assertEquals(2f, prefs.strokeWidthDp, 0.001f)
        assertEquals(50, prefs.displayAreaPercent)
    }

    @Test
    fun `setStyle 连续改动以最后一次为准`() {
        // 滑杆拖动就是连续回调，最终值取决于松手那一刻
        runBlocking {
            store.setStyle(90, 90, 9f, strokeWidthDp = 0.5f, displayAreaPercent = 80)
            store.setStyle(80, 100, 10f, strokeWidthDp = 1.5f, displayAreaPercent = 60)
            store.setStyle(70, 110, 11f, strokeWidthDp = 2.5f, displayAreaPercent = 40)
        }

        val prefs = read()
        assertEquals(70, prefs.alphaPercent)
        assertEquals(110, prefs.textScalePercent)
        assertEquals(11f, prefs.scrollSeconds, 0.001f)
        assertEquals(2.5f, prefs.strokeWidthDp, 0.001f)
        assertEquals(40, prefs.displayAreaPercent)
    }

    @Test
    fun `setStyle 对越界值钳位到约定区间`() {
        runBlocking {
            store.setStyle(
                alphaPercent = -5,
                textScalePercent = 999,
                scrollSeconds = 1f,
                strokeWidthDp = 99f,
                displayAreaPercent = 0,
            )
        }

        val prefs = read()
        assertEquals(DanmakuPreferenceCodec.MIN_ALPHA_PERCENT, prefs.alphaPercent)
        assertEquals(DanmakuPreferenceCodec.MAX_TEXT_SCALE_PERCENT, prefs.textScalePercent)
        assertEquals(DanmakuPreferenceCodec.MIN_SCROLL_SECONDS, prefs.scrollSeconds, 0.001f)
        assertEquals(DanmakuPreferenceCodec.MAX_STROKE_DP, prefs.strokeWidthDp, 0.001f)
        assertEquals(DanmakuPreferenceCodec.MIN_DISPLAY_AREA_PERCENT, prefs.displayAreaPercent)
    }

    @Test
    fun `setStyle 遇到 NaN 滚动时长时退回默认值`() {
        runBlocking {
            store.setStyle(
                alphaPercent = 60,
                textScalePercent = 60,
                scrollSeconds = Float.NaN,
                strokeWidthDp = 1f,
                displayAreaPercent = 60,
            )
        }

        assertEquals(DanmakuPreferenceCodec.DEFAULT_SCROLL_SECONDS, read().scrollSeconds, 0.001f)
    }

    @Test
    fun `setStyle 把描边宽度量化到 0_1dp`() {
        // 滑杆抛来的是连续值，落库必须收敛到 0.1dp，否则读出来是 1.200000047 这种数
        runBlocking {
            store.setStyle(
                alphaPercent = 100,
                textScalePercent = 100,
                scrollSeconds = 8f,
                strokeWidthDp = 1.234f,
                displayAreaPercent = 100,
            )
        }

        assertEquals(1.2f, read().strokeWidthDp, 0.001f)
    }

    @Test
    fun `setStyle 遇到 NaN 描边宽度时退回默认值`() {
        runBlocking {
            store.setStyle(
                alphaPercent = 100,
                textScalePercent = 100,
                scrollSeconds = 8f,
                strokeWidthDp = Float.NaN,
                displayAreaPercent = 100,
            )
        }

        assertEquals(DanmakuPreferenceCodec.DEFAULT_STROKE_DP, read().strokeWidthDp, 0.001f)
    }

    @Test
    fun `描边宽度可以被拖到 0 - 即实心`() {
        // 0 是合法值，不能被「越界钳位」误伤成默认值
        runBlocking {
            store.setStyle(
                alphaPercent = 100,
                textScalePercent = 100,
                scrollSeconds = 8f,
                strokeWidthDp = 0f,
                displayAreaPercent = 100,
            )
        }

        assertEquals(0f, read().strokeWidthDp, 0.001f)
    }

    @Test
    fun `老版本没有描边 key 时读出来是默认的细描边`() {
        // 升级路径：P3 之前装的包没有 danmaku_stroke_width_dp 这个 key，
        // 不能读成 0（实心），否则白字在亮背景上会糊成一片
        val prefs = read()
        assertEquals(DanmakuPreferenceCodec.DEFAULT_STROKE_DP, prefs.strokeWidthDp, 0.001f)
        assertTrue(prefs.strokeWidthDp > 0f)
    }

    @Test
    fun `老版本没有显示区域 key 时读出来是整屏`() {
        // 升级路径：没有 danmaku_display_area_percent 的包必须读成 100（整屏），
        // 而不是被钳到下限 10 —— 否则老用户一升级弹幕就只剩顶部一条窄带
        val prefs = read()
        assertEquals(DanmakuPreferenceCodec.DEFAULT_DISPLAY_AREA_PERCENT, prefs.displayAreaPercent)
        assertEquals(1f, prefs.config.displayAreaRatio, 1e-6f)
    }

    @Test
    fun `setStyle 不碰开关与屏蔽词`() {
        // 样式与另外两项是独立的 key，写样式不能顺手把别的抹掉
        runBlocking {
            store.setEnabled(false)
            store.setBlockRules("前排\n看过的都懂")
            store.setStyle(
                alphaPercent = 50,
                textScalePercent = 120,
                scrollSeconds = 6f,
                strokeWidthDp = 1.5f,
                displayAreaPercent = 70,
            )
        }

        val prefs = read()
        assertFalse(prefs.enabled)
        assertEquals(listOf("前排", "看过的都懂"), prefs.blockRules)
        assertEquals(50, prefs.alphaPercent)
    }

    @Test
    fun `reset 后五项样式回到默认值`() {
        runBlocking {
            store.setStyle(
                alphaPercent = 20,
                textScalePercent = 180,
                scrollSeconds = 4f,
                strokeWidthDp = 3f,
                displayAreaPercent = 25,
            )
            store.reset()
        }

        val prefs = read()
        assertEquals(DanmakuPreferenceCodec.DEFAULT_ALPHA_PERCENT, prefs.alphaPercent)
        assertEquals(DanmakuPreferenceCodec.DEFAULT_TEXT_SCALE_PERCENT, prefs.textScalePercent)
        assertEquals(DanmakuPreferenceCodec.DEFAULT_SCROLL_SECONDS, prefs.scrollSeconds, 0.001f)
        assertEquals(DanmakuPreferenceCodec.DEFAULT_STROKE_DP, prefs.strokeWidthDp, 0.001f)
        assertEquals(DanmakuPreferenceCodec.DEFAULT_DISPLAY_AREA_PERCENT, prefs.displayAreaPercent)
        assertTrue(prefs.enabled)
    }
}
