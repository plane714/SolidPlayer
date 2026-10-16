package com.solidplayer.core.danmaku.track

import com.solidplayer.core.danmaku.model.DanmakuMode

/**
 * 轨道分配（文档 §8.4），弹幕引擎的性能核心。
 *
 * 本类**纯计算、零 Android 依赖**，因此可以直接用 JVM 单测覆盖（文档 §14 要求）。
 *
 * 两条关键量：
 * - `tEnter = w / v` —— 弹幕完全进入屏幕所需时间（其尾巴越过右边界）
 * - `tLeave = (W + w) / v` —— 弹幕完全离屏所需时间
 *
 * **滚动弹幕的接纳条件**：新弹幕头部抵达右边界时，同轨道上一条弹幕的尾巴必须已经进入屏幕
 * （即 `now >= t0 + tEnter(prev)`），否则两条会重叠。由于同轨道弹幕速度相同，
 * 一旦进入屏幕间距恒定，**不会发生追尾**，因此无需按 `tLeave` 阻塞整条轨道 ——
 * `tLeave` 的用途是渲染层回收已完全离屏的弹幕。
 *
 * 无可用轨道时返回 `null`，由调用方直接丢弃该条弹幕（行业通行做法，强渲染会导致叠字）。
 *
 * ### 坐标空间：本类只认「弹幕显示区域」，不认画布
 * 纵向坐标以**显示区域的顶边**为原点，`areaHeightPx` 就是可用高度
 * （由 `DanmakuConfig.displayAreaRatio` 从画布高度折算，区域固定在画面顶部）。
 * 因此缩小显示区域 = 可用高度变小 = 轨道数变少，超出的弹幕在 [allocateScroll] 处被丢弃，
 * 同屏密度随之下降。本类不需要知道画布有多高，也不需要知道比例本身。
 */
class TrackAllocator {

    private var viewWidthPx = 0
    private var areaHeightPx = 0

    private var speedPxPerSecond = 0f
    private var trackHeightPx = 0f
    private var fixedDurationMs = 4_000L
    private var minGapMs = 0L

    /** `freeAtMs[i]` = 第 i 条轨道下一次可以接纳弹幕的时间点 */
    private var freeAtMs = LongArray(0)

    val trackCount: Int get() = freeAtMs.size

    val widthPx: Int get() = viewWidthPx

    val speed: Float get() = speedPxPerSecond

    val fixedDuration: Long get() = fixedDurationMs

    val trackHeight: Float get() = trackHeightPx

    /** 弹幕显示区域高度（px）。区域之外的画布空间不放任何轨道。 */
    val areaHeight: Int get() = areaHeightPx

    /**
     * 更新画面尺寸与配置。
     *
     * ### 只有**轨道数**变化才重建
     * 判据刻意落在「轨道数」而不是「宽/高/行高有没有变」上：
     * - 高度参数是**显示区域高度**，显示区域滑杆按 1% 步进时，绝大多数档位算出的轨道数是同一个 ——
     *   那些档位不该动轨道数组，更不该清空占用状态（清空后新弹幕可能被分到某条正在飘的弹幕
     *   占着的轨道上，两条直接叠在一起）。
     * - 宽/高/行高的**标量**部分每次都直接赋值生效，不依赖重建。
     *
     * 重建即清空占用状态，配合调用方（`DanmakuRenderer`）的重置一起发生：
     * 那个时机才是「轨道数真的变了，在飘弹幕的落点作废」的时机。
     *
     * @return 轨道数组是否被重建（= 轨道数变了）
     */
    fun updateLayout(
        widthPx: Int,
        heightPx: Int,
        trackHeightPx: Float,
        speedPxPerSecond: Float,
        fixedDurationMs: Long,
        minGapMs: Long,
    ): Boolean {
        this.speedPxPerSecond = speedPxPerSecond
        this.trackHeightPx = trackHeightPx
        this.fixedDurationMs = fixedDurationMs
        this.minGapMs = minGapMs
        // 标量直接生效：新弹幕的 `tEnter` / 离屏时长都按新值算，
        // 在飘的弹幕仍用各自出场时锁定的速度与生命（见 DanmakuRenderer.Active.speed）
        viewWidthPx = widthPx
        areaHeightPx = heightPx

        val count = if (trackHeightPx > 0f) (heightPx / trackHeightPx).toInt() else 1
        val wanted = count.coerceIn(1, MAX_TRACKS)
        if (wanted == freeAtMs.size) return false
        freeAtMs = LongArray(wanted)
        return true
    }

    /** 弹幕完全进入屏幕所需时间。 */
    fun enterDurationMs(itemWidthPx: Float): Long =
        if (speedPxPerSecond <= 0f) 0L
        else (itemWidthPx / speedPxPerSecond * 1000f).toLong()

    /** 弹幕完全离屏所需时间（渲染层据此回收）。 */
    fun leaveDurationMs(itemWidthPx: Float): Long = leaveDurationMs(itemWidthPx, speedPxPerSecond)

    /**
     * 指定速度下的离屏时长。
     *
     * 拖速度滑杆时，**已经在飘的弹幕必须按它出场时的速度算离屏时间**（P2），
     * 否则会「按新速度算生命、按旧速度算位置」，表现为弹幕提前消失或卡在屏上不动。
     */
    fun leaveDurationMs(itemWidthPx: Float, speedPxPerSecond: Float): Long =
        if (speedPxPerSecond <= 0f) 0L
        else ((viewWidthPx + itemWidthPx) / speedPxPerSecond * 1000f).toLong()

    /**
     * 为滚动弹幕分配轨道：自上而下找到第一条空闲轨道。
     *
     * @return 轨道下标；`null` 表示当前无可用轨道，调用方应丢弃该弹幕
     */
    fun allocateScroll(timeMs: Long, itemWidthPx: Float): Int? {
        for (i in freeAtMs.indices) {
            if (freeAtMs[i] <= timeMs) {
                freeAtMs[i] = timeMs + enterDurationMs(itemWidthPx) + minGapMs
                return i
            }
        }
        return null
    }

    /**
     * 为顶部/底部弹幕分配独占轨道：顶部自上而下、底部自下而上找（文档 §8.4）。
     */
    fun allocateFixed(mode: DanmakuMode, timeMs: Long): Int? {
        val n = freeAtMs.size
        if (mode == DanmakuMode.BOTTOM) {
            for (i in n - 1 downTo 0) {
                if (freeAtMs[i] <= timeMs) {
                    freeAtMs[i] = timeMs + fixedDurationMs
                    return i
                }
            }
        } else {
            for (i in 0 until n) {
                if (freeAtMs[i] <= timeMs) {
                    freeAtMs[i] = timeMs + fixedDurationMs
                    return i
                }
            }
        }
        return null
    }

    /** 轨道顶边 y 坐标。 */
    fun trackTop(track: Int): Float = track * trackHeightPx

    /**
     * 底部弹幕使用的轨道底边 y 坐标（自**显示区域**底边往上排）。
     *
     * 刻意不接受画布高度参数：区域缩小后，底部弹幕必须跟着贴到区域下边缘，
     * 否则「底部弹幕落在显示区域之外」—— 那样这个设置对底部弹幕就等于不存在。
     */
    fun trackBottom(track: Int): Float =
        areaHeightPx - (trackCount - 1 - track) * trackHeightPx

    /** seek / 尺寸变化后调用，清空全部占用状态。 */
    fun reset() {
        freeAtMs.fill(Long.MIN_VALUE)
    }

    companion object {
        /** 轨道数上限，防止极端字号下分配出上百条轨道。 */
        const val MAX_TRACKS = 64
    }
}
