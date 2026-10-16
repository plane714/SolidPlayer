package com.solidplayer.core.danmaku.model

/**
 * 按 [DanmakuItem.timeMs] 升序排列的不可变时间轴。
 *
 * 升序是渲染循环「游标单向前进」的前提，因此构造入口 [of] 内部强制排序。
 */
class DanmakuTimeline private constructor(val items: List<DanmakuItem>) {

    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        val EMPTY = DanmakuTimeline(emptyList())

        fun of(items: List<DanmakuItem>): DanmakuTimeline =
            if (items.isEmpty()) EMPTY else DanmakuTimeline(items.sortedBy { it.timeMs })
    }
}
