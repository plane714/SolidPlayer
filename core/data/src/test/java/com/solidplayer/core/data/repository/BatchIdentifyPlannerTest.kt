package com.solidplayer.core.data.repository

import com.solidplayer.core.data.network.dto.BatchMatchResponseItem
import com.solidplayer.core.data.network.dto.MatchResultV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量识别切批与结果对齐单测。
 *
 * 这两步出错的后果都比「少识别一个」严重：
 * - 切批超限 → 整批 400，用户看到「全部失败」
 * - 对错序 → 把 A 文件的 episodeId 写到 B 文件上，**播错弹幕且用户很难发现**
 */
class BatchIdentifyPlannerTest {

    @Test
    fun `空列表切不出任何批`() {
        assertTrue(BatchIdentifyPlanner.batches(emptyList<Int>(), 32).isEmpty())
    }

    @Test
    fun `按上限切批且不丢项`() {
        val items = (1..33).toList()
        val batches = BatchIdentifyPlanner.batches(items, 32)

        assertEquals(2, batches.size)
        assertEquals(32, batches[0].size)
        assertEquals(1, batches[1].size)
        assertEquals(items, batches.flatten())
    }

    @Test
    fun `刚好整除时不多出空批`() {
        assertEquals(2, BatchIdentifyPlanner.batches((1..64).toList(), 32).size)
    }

    @Test
    fun `非法上限退化为不切批`() {
        assertEquals(1, BatchIdentifyPlanner.batches((1..33).toList(), 0).size)
    }

    private fun item(hash: String, episodeId: Long, success: Boolean = true) = BatchMatchResponseItem(
        success = success,
        fileHash = hash,
        matchResult = if (success) MatchResultV2(episodeId = episodeId, animeId = episodeId / 10) else null,
    )

    @Test
    fun `数量一致时按下标对齐`() {
        val requests = listOf("h1", "h2")
        val results = listOf(item("h1", 101), item("h2", 102))

        val aligned = BatchIdentifyPlanner.align(requests, results) { it }

        assertEquals(listOf("h1", "h2"), aligned.map { it.first })
        assertEquals(listOf(101L, 102L), aligned.map { it.second?.matchResult?.episodeId })
    }

    @Test
    fun `数量不一致时按 fileHash 对齐而不是错位`() {
        val requests = listOf("h1", "h2", "h3")
        // 服务端漏掉了 h2
        val results = listOf(item("h3", 303), item("h1", 101))

        val aligned = BatchIdentifyPlanner.align(requests, results) { it }

        assertEquals(101L, aligned[0].second?.matchResult?.episodeId)
        assertNull(aligned[1].second)
        assertEquals(303L, aligned[2].second?.matchResult?.episodeId)
    }

    @Test
    fun `完全匹配不上时记为 null 而不是错填`() {
        val aligned = BatchIdentifyPlanner.align(listOf("h1"), listOf(item("other", 999))) { it }

        assertEquals(1, aligned.size)
        assertNull(aligned.first().second)
    }

    @Test
    fun `空请求返回空结果`() {
        assertTrue(BatchIdentifyPlanner.align(emptyList<String>(), emptyList()) { it }.isEmpty())
    }
}
