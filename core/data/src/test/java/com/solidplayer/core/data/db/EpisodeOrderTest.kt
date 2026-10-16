package com.solidplayer.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 集数排序单测。
 *
 * [EpisodeOrder] 是媒体库分组列表与播放页「下一集」共用的比较器，
 * 它一旦出错，用户看到的是「列表里排第 3 位，点下一集却跳到第 5 集」——
 * 这类错位在真机上极难归因，所以在这里把边界钉死。
 */
class EpisodeOrderTest {

    private fun entry(displayName: String, episodeNumber: String?): MediaFileDisplay =
        MediaFileDisplay(
            file = MediaFileEntity(uri = "content://test/$displayName", displayName = displayName),
            episodeNumber = episodeNumber,
        )

    private fun List<MediaFileDisplay>.numbers(): List<String?> =
        map { it.episodeNumber }

    @Test
    fun `数字集数按数值排序而不是字符串序`() {
        val sorted = listOf(
            entry("第10集.mkv", "10"),
            entry("第2集.mkv", "2"),
            entry("第1集.mkv", "1"),
        ).sortedWith(EpisodeOrder)

        // 字符串序会把 "10" 排到 "2" 前面，那样连播顺序就是乱的
        assertEquals(listOf("1", "2", "10"), sorted.numbers())
    }

    @Test
    fun `非数字集数排在所有数字集数之后`() {
        val sorted = listOf(
            entry("OVA.mkv", "O1"),
            entry("第3集.mkv", "3"),
            entry("特别篇.mkv", "C1"),
        ).sortedWith(EpisodeOrder)

        assertEquals(listOf("3", "C1", "O1"), sorted.numbers())
    }

    @Test
    fun `集数为空排在最后`() {
        val sorted = listOf(
            entry("未知集.mkv", null),
            entry("第1集.mkv", "1"),
        ).sortedWith(EpisodeOrder)

        assertEquals(listOf("1", null), sorted.numbers())
    }

    @Test
    fun `集数相同时按文件名兜底且顺序稳定`() {
        val sorted = listOf(
            entry("同一集 B.mkv", "5"),
            entry("同一集 A.mkv", "5"),
        ).sortedWith(EpisodeOrder)

        assertEquals(listOf("同一集 A.mkv", "同一集 B.mkv"), sorted.map { it.file.displayName })
    }
}
