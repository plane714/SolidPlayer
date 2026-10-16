package com.solidplayer.core.data.repository

import com.solidplayer.core.data.network.dto.MatchResultV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动绑定判据单测。
 *
 * [MatchOutcome.autoBind] 是「这个识别结果可以直接写进库、不用问用户」的唯一口径，
 * 被两条路径共用：媒体库的单文件识别、播放页的「点开即识别」。
 * 它一旦放宽，用户点开的文件就会被绑到别的剧集上 —— 表现是弹幕整体错位，
 * 而且**已经写进库**，用户只能自己去媒体库发现。所以这里盯死边界。
 */
class MatchOutcomeTest {

    private fun candidate(episodeId: Long) = MatchResultV2(episodeId = episodeId, animeId = 1L)

    @Test
    fun `精确关联且只有一条候选时可直接绑定`() {
        val only = candidate(1001L)
        val outcome = MatchOutcome(isMatched = true, candidates = listOf(only))

        assertEquals(only, outcome.autoBind)
    }

    @Test
    fun `多候选即使 isMatched 也不自动绑定`() {
        // 服务端的 isMatched 只描述「有没有精确命中 hash」，不代表「唯一的答案」。
        // 真出现这种情况时宁可让用户挑，也不要猜一条写进库。
        val outcome = MatchOutcome(
            isMatched = true,
            candidates = listOf(candidate(1L), candidate(2L)),
        )

        assertNull(outcome.autoBind)
    }

    @Test
    fun `单候选但未精确关联时不自动绑定`() {
        // 模糊搜索结果：只有一条，但服务端明确说「不是精确命中」
        val outcome = MatchOutcome(isMatched = false, candidates = listOf(candidate(1L)))

        assertNull(outcome.autoBind)
    }

    @Test
    fun `没有候选时为 null`() {
        assertNull(MatchOutcome(isMatched = false, candidates = emptyList()).autoBind)
        // 服务端偶尔会给出 isMatched=true 却没有 matches 的畸形响应，同样不能绑
        assertNull(MatchOutcome(isMatched = true, candidates = emptyList()).autoBind)
    }
}
