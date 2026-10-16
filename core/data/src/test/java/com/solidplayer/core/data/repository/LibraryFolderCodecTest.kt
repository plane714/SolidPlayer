package com.solidplayer.core.data.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「常用目录」持久化格式单测。
 *
 * 这段逻辑看着简单，但它是**用户配置的唯一副本**：DataStore 里存的是字符串，
 * 一旦编解码写错，用户下次打开 App 就会发现「我配的目录全没了」。
 * 因此这里不碰 Android API，直接跑在 JVM 上。
 */
class LibraryFolderCodecTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun folder(uri: String, name: String = "x", at: Long = 1L) =
        LibraryFolder(treeUri = uri, displayName = name, addedAt = at)

    @Test
    fun `编码后解码可完整还原`() {
        val folders = listOf(
            folder("content://tree/a", "番剧", 111L),
            folder("content://tree/b", "剧场版", 222L),
        )

        val restored = LibraryFolderCodec.decode(LibraryFolderCodec.encode(folders, json), json)

        assertEquals(folders, restored)
    }

    @Test
    fun `空串与 null 都解码为空列表`() {
        assertTrue(LibraryFolderCodec.decode(null, json).isEmpty())
        assertTrue(LibraryFolderCodec.decode("", json).isEmpty())
        assertTrue(LibraryFolderCodec.decode("   ", json).isEmpty())
    }

    @Test
    fun `脏数据不抛异常而是退化为空列表`() {
        // 用户升级 / 手工改过 preferences 文件时可能撞上，绝不能让它把设置页拖崩
        assertTrue(LibraryFolderCodec.decode("{not json at all", json).isEmpty())
        assertTrue(LibraryFolderCodec.decode("""{"treeUri":1}""", json).isEmpty())
    }

    @Test
    fun `空列表编码后仍能解码回空列表`() {
        assertTrue(LibraryFolderCodec.decode(LibraryFolderCodec.encode(emptyList(), json), json).isEmpty())
    }

    @Test
    fun `add 对同一 treeUri 去重`() {
        val one = folder("content://tree/a")
        val list = LibraryFolderCodec.add(emptyList(), one)
        val again = LibraryFolderCodec.add(list, folder("content://tree/a", "改个名字"))

        assertEquals(1, again.size)
        assertEquals("x", again.first().displayName)
    }

    @Test
    fun `add 保留加入顺序`() {
        val list = LibraryFolderCodec
            .add(emptyList(), folder("content://tree/a"))
            .let { LibraryFolderCodec.add(it, folder("content://tree/b")) }
            .let { LibraryFolderCodec.add(it, folder("content://tree/c")) }

        assertEquals(listOf("content://tree/a", "content://tree/b", "content://tree/c"), list.map { it.treeUri })
    }

    @Test
    fun `remove 只删指定目录`() {
        val list = listOf(folder("a"), folder("b"), folder("c"))
        val left = LibraryFolderCodec.remove(list, "b")
        assertEquals(listOf("a", "c"), left.map { it.treeUri })
        assertFalse(LibraryFolderCodec.remove(list, "zzz").size != list.size)
    }

    @Test
    fun `displayNameOf 从 tree URI 取出末段目录名`() {
        assertEquals(
            "Movies",
            LibraryFolderCodec.displayNameOf(
                "content://com.android.externalstorage.documents/tree/primary%3AMovies",
            ),
        )
    }

    @Test
    fun `displayNameOf 支持带子目录的 tree URI`() {
        assertEquals(
            "番剧",
            LibraryFolderCodec.displayNameOf(
                "content://com.android.externalstorage.documents/tree/primary%3AMovies%2F%E7%95%AA%E5%89%A7",
            ),
        )
    }

    @Test
    fun `displayNameOf 遇到不认识的形式时退化为原串而不是抛异常`() {
        assertEquals("whatever", LibraryFolderCodec.displayNameOf("whatever"))
    }
}
