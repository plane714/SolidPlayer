package com.solidplayer.ui.library

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 首页文件夹「长按拖拽排序」的运行时状态。
 *
 * ### 交出的必须是「屏幕上那份」
 * [drop] 给出的是**换位之后**的列表（[ReorderDrop.target]），因为那正是用户松手时眼前看到的
 * 顺序。落盘侧只做一件事：把「落点之前的那一段」定序，其余不动（见 `PinnedFolderCodec.moveOrder`），
 * 于是盘上算出来的结果与它逐字相同 —— 「所见即所存」。
 *
 * 早先版本交出的是同一份列表，但调用方却按**拖之前的下标** `visible[from]` 去取被拖项，
 * 只要真的移过位就会挪错人：列表在松手瞬间跳成另一个顺序，那份错的顺序还会被写进盘里。
 *
 * ### 手势必须挂在**不参与平移**的那一层
 * 被拖的行靠 `graphicsLayer.translationY` 跟着手指走，而 Compose 派发给节点的指针坐标是
 * **过了图层变换**的。如果把 `pointerInput` 也挂在做位移的那层上，就会出现「手指没动、
 * 图层动了 → 下一帧的局部坐标把这段位移正好抵消掉」：位移越大，局部坐标越显得"没动"，
 * 行就卡在半路上。所以 `FolderRow` 里是**外层 Box（无变换）收手势、内层 Row 做位移**。
 *
 * ### 位移由局部坐标直接算，不做增量累加
 * `offsetY = localY - grabY`。行在视口里的位置一旦变化（自动滚动、或者换位导致它的槽位挪了
 * 一行），下一帧的局部坐标天然把这段变化算了进去，于是位移自动跟着修正；累加式的位移在
 * 那时是「没跟上」的（要么行飘走、要么抽一下）。
 *
 * ### 一次拖拽里「谁在拖」只变两次
 * [draggedKey] 与 [order] / [index] 分开存：前者在起手与收手各变一次，后者每换一次位就变一次。
 * 行渲染只读前者，于是换位不会把整张表的行都重组一遍（这正是「卡顿」的来源之一）。
 */
@Stable
internal class FolderReorderState {

    /** 拖拽期间的本地顺序；`null` = 没在拖，界面直接用 VM 给的那一份。 */
    var order by mutableStateOf<List<FolderEntry>?>(null)
        private set

    /** 被拖行在本地顺序里的下标（换位时跟着变）与它的位移。 */
    var index by mutableIntStateOf(-1)
        private set

    /** 被拖的是哪一行（渲染时认人）。`order` 为空时是 null。 */
    var draggedKey by mutableStateOf<String?>(null)
        private set

    var offsetY by mutableFloatStateOf(0f)
        private set

    /**
     * 手指当前位置（**被拖行的局部坐标**）—— 自动滚动那一帧靠它算「离上下边缘还有多远」。
     *
     * 注意它是「局部」坐标：列表滚动时行的槽位在视口里变了，这个值也要跟着变（见 [shift]），
     * 否则贴边的自动滚动会越滚越偏。
     */
    var localY by mutableFloatStateOf(0f)
        private set

    /**
     * 这次拖拽**真的把行挪到了别的槽位**（越过了半个行高的换位阈值）。
     *
     * 判据刻意不是「手指动过」：按下时手指微抖一下就算拖拽的话，长按原地松手就再也弹不出
     * 那个「置顶 / 取消置顶」菜单了 —— 而抖动是按下时的常态。只有真的换过位，才算「这次是来排序的」。
     */
    var moved by mutableStateOf(false)
        private set

    val active: Boolean get() = order != null

    private var startIndex = -1
    private var grabY = 0f
    private var rowHeight = 1f

    fun begin(entries: List<FolderEntry>, index: Int, grabY: Float, rowHeight: Float) {
        if (index !in entries.indices) return
        order = entries
        this.index = index
        startIndex = index
        draggedKey = entries[index].folder.folderPath
        this.grabY = grabY
        this.rowHeight = rowHeight.coerceAtLeast(1f)
        localY = grabY
        offsetY = 0f
        moved = false
    }

    /** 手指在行内移到 [localY]。 */
    fun follow(localY: Float) {
        if (!active) return
        this.localY = localY
        offsetY = localY - grabY
        settle()
    }

    /**
     * 列表自动滚动了 [pixels]（正数 = 内容向上滚，即露出下面的项）。
     *
     * 位移与「手指在行内」的局部坐标都要加上同一个量：行的槽位在视口里上移了多少，
     * 行内坐标就大了多少（`局部 = 视口坐标 - 槽位偏移`）。只补位移不补坐标的话，自动滚动
     * 的下一帧会以为手指离边缘还差 `pixels`，于是贴着边缘时反而滚不动、或者滚着滚着忽然变向。
     */
    fun shift(pixels: Float) {
        if (!active || pixels == 0f) return
        offsetY += pixels
        localY += pixels
        settle()
    }

    /**
     * 越过邻居一半高度就换位，换位的同时把位移补回一个行高 —— 行始终待在手指底下，
     * 不会在换位那一帧跳一下。反方向的判断阈值是同一条（±半个行高），
     * 换位后位移必然落回 ±半个行高之内，所以不会来回抖。
     */
    private fun settle() {
        val list = order?.toMutableList() ?: return
        val half = rowHeight / 2f
        var swapped = false
        while (offsetY < -half && index > 0) {
            list.add(index - 1, list.removeAt(index))
            index -= 1
            offsetY += rowHeight
            swapped = true
        }
        while (offsetY > half && index < list.lastIndex) {
            list.add(index + 1, list.removeAt(index))
            index += 1
            offsetY -= rowHeight
            swapped = true
        }
        if (swapped) {
            order = list
            moved = true
        }
    }

    /**
     * 松手：交出落点并清空状态。
     *
     * [ReorderDrop.target] 是**换位之后**的完整顺序（= 用户松手时看到的那一份），
     * [ReorderDrop.from] / [ReorderDrop.to] 是起点与落点下标；原地没换过位时 `from == to`
     * （提交方会当成空操作），是否该弹菜单由 [moved] 决定。
     */
    fun drop(): ReorderDrop? {
        val list = order ?: return null
        val result = ReorderDrop(
            target = list.map { it.folder.folderPath },
            from = startIndex,
            to = index,
        )
        reset()
        return result
    }

    fun cancel() = reset()

    private fun reset() {
        order = null
        index = -1
        startIndex = -1
        draggedKey = null
        offsetY = 0f
        localY = 0f
        grabY = 0f
        moved = false
    }
}

/**
 * 一次拖拽的结果。
 *
 * [target] = **松手那一刻界面上的顺序**（被拖项已经落在 [to] 上）。落盘侧只认它，
 * 这是「所见即所存」这条不变量的载体 —— 别再用 `startOrder[from]` 之类的口径去重算被拖项是谁。
 */
internal data class ReorderDrop(
    val target: List<String>,
    val from: Int,
    val to: Int,
)

/** 长按之后这一把该怎么收尾。 */
internal enum class HoldOutcome {
    /** 弹「置顶 / 取消置顶」菜单（原地松手）。 */
    Menu,

    /** 提交这次顺序。 */
    Commit,

    /** 丢弃：行回弹，盘上不动。 */
    Discard,
}

/**
 * 长按之后该做什么 —— 从手势回调里摘出来的**决策**。
 *
 * 回调本身只能真机点，决策可以单测，所以这条判据单独放这里：
 *
 * - **一次都没换过位 → 弹菜单**，且这条**优先于** [endedWithUp]：拖拽被系统抢走时，
 *   只要没换过位也该弹菜单 —— 用户按了半天，弹菜单总比什么都不发生更容易理解。
 * - 换过位且**以抬手收尾** → 提交。
 * - 换过位但被抢走 → 丢弃（行回弹、盘上不动），别把半路的顺序写进盘里。
 *
 * P8.3 修的那个 bug 正是这条判据没被隔离：点击与长按各有一套 detector 各判一遍，
 * 「原地长按松手」被判成了单击。
 */
internal fun resolveHoldOutcome(moved: Boolean, endedWithUp: Boolean): HoldOutcome = when {
    !moved -> HoldOutcome.Menu
    endedWithUp -> HoldOutcome.Commit
    else -> HoldOutcome.Discard
}
