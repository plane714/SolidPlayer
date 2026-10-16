package com.solidplayer.ui.library

import com.solidplayer.core.data.db.VideoFolderSummary
import com.solidplayer.core.data.repository.PinnedFolderCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖拽会话（`FolderReorderState`）的手感与落点口径单测。
 *
 * 位置对不对要靠眼睛看，但**交给落盘侧的那份顺序**只有把 begin / follow / shift / drop
 * 串起来才验得出来。这里覆盖三类：
 * 1. 换位阈值与位移补偿（跟手的前提）；
 * 2. 自动滚动只补位移、同时校正行内坐标（否则贴边滚动会越滚越偏）；
 * 3. 交出去的 `target` 就是界面上那份 —— 早先版本在调用侧按「拖之前的下标」取被拖项，
 *    只要真的移过位就挪错人；
 * 4. 「这次算不算拖过」的判据是**换过位**，不是「手指动过」—— 它决定长按原地松手弹不弹
 *    「置顶 / 取消置顶」菜单。
 */
class FolderReorderStateTest {

    private fun entries(vararg names: String): List<FolderEntry> =
        names.map { name ->
            FolderEntry(
                folder = VideoFolderSummary(
                    folderPath = "/$name",
                    folderName = name,
                    videoCount = 1,
                ),
                pinned = false,
            )
        }

    /** 行高与手指落点（px，具体单位无所谓，只要传进去的和断言里用的是同一套）。 */
    private val rowPx = 80f
    private val grabPx = 30f

    private fun state(vararg names: String): FolderReorderState =
        FolderReorderState().apply {
            begin(entries(*names), index = 0, grabY = grabPx, rowHeight = rowPx)
        }

    @Test
    fun `越过半个行高才换位`() {
        val state = state("a", "b", "c")

        state.follow(grabPx + rowPx / 2f - 1f)
        assertEquals(0, state.index)

        state.follow(grabPx + rowPx / 2f + 1f)
        assertEquals(1, state.index)
        // 换位时位移补回一个行高 —— 行始终待在手指底下，不会跳一下
        assertEquals(rowPx / 2f + 1f - rowPx, state.offsetY, 0.001f)
    }

    @Test
    fun `落点交给的是换位之后那份顺序`() {
        val state = state("a", "b", "c")

        state.follow(grabPx + rowPx + 1f) // 往下拖过一整行
        val drop = state.drop()!!

        // /a 现在在第 2 位：from 是拖之前的下标，to 是落点
        assertEquals(listOf("/b", "/a", "/c"), drop.target)
        assertEquals(0, drop.from)
        assertEquals(1, drop.to)
        assertFalse(state.active)
    }

    @Test
    fun `原地松手不产生新顺序，并且算作没拖过`() {
        val state = FolderReorderState().apply {
            begin(entries("a", "b"), index = 1, grabY = grabPx, rowHeight = rowPx)
        }

        state.follow(grabPx + 4f) // 只有按下时的那点抖动，没换过位

        // onDragEnd / onDragCancel 靠它决定「原地松手仍然弹长按菜单」
        assertFalse(state.moved)

        val drop = state.drop()!!
        assertEquals(listOf("/a", "/b"), drop.target)
        assertEquals(drop.from, drop.to)
        assertNull(state.draggedKey)
    }

    @Test
    fun `换过位才算拖过，手指微动不算`() {
        val state = state("a", "b")

        // 抖动（远不到换位阈值）：仍是「原地松手」，松手要弹长按菜单
        state.follow(grabPx + 4f)
        assertFalse(state.moved)

        // 越过半个行高换位：这次是真的来排序的，松手不再弹菜单
        state.follow(grabPx + rowPx / 2f + 1f)
        assertEquals(1, state.index)
        assertTrue(state.moved)
    }

    @Test
    fun `自动滚动补位移时，行内坐标一起走`() {
        val state = state("a", "b", "c")
        val scroll = 60f

        state.shift(scroll) // 列表向上滚了 60px

        // 行内坐标跟着滚动一起变大：下一帧自动滚动按「行的槽位 + 行内坐标」判断手指在视口里的
        // 位置，漏掉这一段就会越滚越偏（贴着边缘反而滚不动、或者滚着滚着忽然变向）。
        assertEquals(grabPx + scroll, state.localY, 0.001f)

        // 位移把这段滚动补了回去：行在视口里的落点没动，仍然待在手指底下
        // （槽位 = 下标 * 行高 - 已滚距离）
        assertEquals(0f, state.index * rowPx - scroll + state.offsetY, 0.001f)
    }

    @Test
    fun `认人用的 key 不跟着换位变`() {
        val state = state("a", "b", "c")
        assertEquals("/a", state.draggedKey)

        state.follow(grabPx + rowPx + 1f) // 换一次位
        assertEquals(1, state.index)
        // 换位只改「第几位」；行渲染只读 draggedKey，才不会因为换位把整张表都重组一遍
        assertEquals("/a", state.draggedKey)

        state.cancel()
        assertNull(state.draggedKey)
        assertNull(state.order)
    }

    @Test
    fun `拖拽结果落盘后渲染出来与松手时看到的一致`() {
        val all = listOf("/a", "/b", "/c", "/d")
        val state = FolderReorderState().apply {
            begin(entries("a", "b", "c", "d"), index = 1, grabY = grabPx, rowHeight = rowPx)
        }

        state.follow(grabPx + rowPx * 2f + 1f) // 把 /b 往下拖两行
        val drop = state.drop()!!

        // 「所见即所存」：盘上算出来的顺序渲染出来 == 松手那一刻界面上的那份。
        // 早先版本这条不成立（挪的是另一个人），列表会在松手瞬间跳成别的顺序。
        val order = PinnedFolderCodec.moveOrder(drop.target, listOf("/a"), drop.from, drop.to)
        val rendered = all.sortedBy {
            order.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
        }

        assertEquals(drop.target, rendered)
    }

    @Test
    fun `原地长按松手只弹菜单，任何情况都不会去开目录`() {
        // 没换过位：无论拖拽是抬手收尾还是被系统抢走，都只弹菜单。
        // P8.3 的 bug 就出在这条 —— 点击与长按各一套 detector，原地长按被判成了单击。
        assertEquals(HoldOutcome.Menu, resolveHoldOutcome(moved = false, endedWithUp = true))
        assertEquals(HoldOutcome.Menu, resolveHoldOutcome(moved = false, endedWithUp = false))
    }

    @Test
    fun `换过位才提交，被抢走就丢弃`() {
        assertEquals(HoldOutcome.Commit, resolveHoldOutcome(moved = true, endedWithUp = true))
        assertEquals(HoldOutcome.Discard, resolveHoldOutcome(moved = true, endedWithUp = false))
    }
}
