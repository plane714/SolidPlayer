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
 * 字幕样式偏好存储单测。
 *
 * ### 为什么非要给这个类写单测
 * 与 `DanmakuPreferenceStoreTest` 同一个理由：这里的 bug **不会崩、不会报错、日志里也什么都没有**。
 * 写进去的值如果不对，读出来的还是新值之外的旧值，而读取侧又会老老实实把它当成「用户的选择」，
 * 表现为「滑杆拖了半天，字幕纹丝不动」。只有「写完之后再读出来断言」才拦得住。
 *
 * 存储用真实的 DataStore（临时文件，纯 JVM），不 mock 掉 `edit`。
 */
class SubtitlePreferenceStoreTest {

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var store: SubtitlePreferenceStore

    @Before
    fun setUp() {
        file = File(
            File(System.getProperty("java.io.tmpdir") ?: "."),
            "subtitle-prefs-${System.nanoTime()}.preferences_pb",
        )
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = SubtitlePreferenceStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    private fun read(): SubtitlePreferences = runBlocking { store.preferences.first() }

    @Test
    fun `setStyle 把新值真正写进 DataStore`() {
        // 回归点：新值必须落到存储里，而不是被当成「读出来的旧值」又写回去
        runBlocking { store.setStyle(textSizeSp = 30, outlineEnabled = false) }

        val prefs = read()
        assertEquals(30, prefs.textSizeSp)
        assertFalse(prefs.outlineEnabled)
    }

    @Test
    fun `setStyle 连续改动以最后一次为准`() {
        runBlocking {
            store.setStyle(18, true)
            store.setStyle(24, false)
            store.setStyle(36, true)
        }

        val prefs = read()
        assertEquals(36, prefs.textSizeSp)
        assertTrue(prefs.outlineEnabled)
    }

    @Test
    fun `setStyle 对越界字号钳位`() {
        runBlocking { store.setStyle(textSizeSp = 999, outlineEnabled = true) }
        assertEquals(SubtitlePreferenceCodec.MAX_TEXT_SIZE_SP, read().textSizeSp)

        runBlocking { store.setStyle(textSizeSp = -1, outlineEnabled = true) }
        assertEquals(SubtitlePreferenceCodec.MIN_TEXT_SIZE_SP, read().textSizeSp)
    }

    @Test
    fun `关掉描边是一个合法状态 不能被误当成默认值`() {
        // false 必须能存下来：它不是「缺 key」，是用户明确的选择
        runBlocking { store.setStyle(textSizeSp = 22, outlineEnabled = false) }
        assertFalse(read().outlineEnabled)
    }

    @Test
    fun `老版本没有这两个 key 时读出来是默认值`() {
        // 升级路径：老包装的 App 里没有 subtitle_* 这两个 key，
        // 不能读成 0sp / 无描边，否则字幕直接看不见
        val prefs = read()
        assertEquals(SubtitlePreferenceCodec.DEFAULT_TEXT_SIZE_SP, prefs.textSizeSp)
        assertTrue(prefs.outlineEnabled)
    }

    @Test
    fun `reset 后两项回到默认值`() {
        runBlocking {
            store.setStyle(textSizeSp = 40, outlineEnabled = false)
            store.reset()
        }

        val prefs = read()
        assertEquals(SubtitlePreferenceCodec.DEFAULT_TEXT_SIZE_SP, prefs.textSizeSp)
        assertEquals(SubtitlePreferenceCodec.DEFAULT_OUTLINE_ENABLED, prefs.outlineEnabled)
    }
}
