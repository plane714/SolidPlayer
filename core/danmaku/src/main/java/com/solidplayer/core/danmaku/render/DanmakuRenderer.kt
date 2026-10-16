package com.solidplayer.core.danmaku.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.SparseArray
import com.solidplayer.core.danmaku.model.DanmakuColor
import com.solidplayer.core.danmaku.model.DanmakuConfig
import com.solidplayer.core.danmaku.model.DanmakuItem
import com.solidplayer.core.danmaku.model.DanmakuMode
import com.solidplayer.core.danmaku.model.DanmakuTimeline
import com.solidplayer.core.danmaku.track.TrackAllocator

/**
 * 弹幕渲染器：一帧的完整处理流程（文档 §8.5）。
 *
 * ```
 * 1. now = 播放位置 - 时轴偏移
 * 2. 游标单向前进，取出 [上一帧, now] 区间内新激活的弹幕（seek 时才二分重定位）
 * 3. 为新弹幕分配轨道
 * 4. 更新活跃集合中每条弹幕的位置
 * 5. 移除已离屏的弹幕，对象回收到池
 * 6. 先画描边（半透明黑），再画原色填充 —— 默认 1.2dp 细描边，宽度可调，0 = 纯实心
 * ```
 *
 * **渲染循环内三条铁律**（文档 §8.5）：
 * 1. 零分配 —— 活跃弹幕用对象池 + 交换删除，不在帧回调里 new 对象、不拼字符串
 * 2. 文字宽度只测一次 —— 测完写入 `DanmakuItem.textWidth`
 * 3. `Paint` 按颜色缓存复用
 *
 * 位置全部由「播放位置」直接推导（`x = W - v × (now - startMs)`），
 * 而不是「上一帧位置 + 速度 × 帧间隔」的积分方式 —— 因此**不存在累计漂移**，
 * 帧率抖动、掉帧、倍速切换都不会让弹幕跑偏。
 */
class DanmakuRenderer {

    private val allocator = TrackAllocator()

    private var config = DanmakuConfig()
    private var density = 1f
    private var textSizePx = 0f
    private var alphaValue = 255
    private var strokeWidthPx = 0f
    private var baselineOffset = 0f

    private var canvasWidth = 0
    private var canvasHeight = 0

    /** 当前时间轴（升序） */
    private var timeline: DanmakuTimeline = DanmakuTimeline.EMPTY

    /** 时轴偏移：`effectiveTime = 播放位置 - offsetMs`（正数表示弹幕整体延后出现） */
    private var offsetMs = 0L

    /** 游标：指向下一个尚未激活的时间轴条目 */
    private var cursor = 0
    private var lastPositionMs = UNSET

    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fillPaints = SparseArray<Paint>(8)

    /** 活跃弹幕对象池，`[0, activeCount)` 为有效区间 */
    private val pool = ArrayList<Active>(INITIAL_POOL_SIZE)
    private var activeCount = 0

    val activeSize: Int get() = activeCount

    val allocatorRef: TrackAllocator get() = allocator

    private class Active {
        var item: DanmakuItem? = null
        var track = 0
        var startMs = 0L
        var lifeMs = 0L
        var width = 0f
        var mode = DanmakuMode.SCROLL

        /**
         * 出场时锁定的滚动速度（px/s）。
         *
         * 位置与生命都按它算，因此**中途调速度不会让已经在飘的弹幕发生跳变**（P2 速度设置）。
         */
        var speed = 0f
    }

    init {
        measurePaint.typeface = TYPEFACE
        strokePaint.typeface = TYPEFACE
        strokePaint.style = Paint.Style.STROKE
    }

    // ── 配置与布局 ────────────────────────────────────────────────

    /**
     * 应用新配置。
     *
     * **刻意做成差异失效**：P2 的透明度/字号/速度滑杆会在拖动过程中连续调用本方法，
     * 若无脑清空 `fillPaints` 并把整条时间轴的 `textWidth` 归零，
     * 每拖一格就要对上万条弹幕重测一次文字宽度，帧率会直接塌掉。
     * 现在只有「真的变了的那个维度」才失效对应缓存。
     */
    fun setConfig(config: DanmakuConfig, density: Float) {
        val previous = this.config

        val newTextSizePx = config.textSizeSp * config.textScale * density
        val newAlphaValue = (config.alpha.coerceIn(0f, 1f) * 255f).toInt()
        val newStrokeWidthPx = config.strokeWidthDp * density

        val textSizeChanged = newTextSizePx != textSizePx || density != this.density
        val alphaChanged = newAlphaValue != alphaValue
        // 显示区域变了 → 轨道数可能跟着变：这是**轨道布局**变化，不只是「参数」变化
        val areaChanged = config.displayAreaRatio != previous.displayAreaRatio
        // 行高由字号密度决定；速度与固定时长直接喂给轨道分配器
        val layoutChanged = textSizeChanged ||
            areaChanged ||
            config.scrollDurationSeconds != previous.scrollDurationSeconds ||
            config.fixedDurationMs != previous.fixedDurationMs ||
            config.minGapMs != previous.minGapMs

        this.config = config
        this.density = density
        textSizePx = newTextSizePx
        alphaValue = newAlphaValue
        strokeWidthPx = newStrokeWidthPx

        measurePaint.textSize = textSizePx
        strokePaint.textSize = textSizePx
        strokePaint.strokeWidth = strokeWidthPx
        // 描边色本身就是半透明黑，透明度是「描边自身的 alpha × 用户透明度」，两者都要保留
        strokePaint.color = DanmakuColor.withAlpha(
            rgb = STROKE_RGB,
            alpha = alphaValue * STROKE_ALPHA / 255,
        )
        baselineOffset = -measurePaint.fontMetrics.ascent

        // 字号进了这两份缓存：文字宽度缓存与按色 Paint 缓存
        if (textSizeChanged) {
            for (item in timeline.items) item.textWidth = 0f
        }
        // 不透明度烘进了按色 Paint 缓存
        if (textSizeChanged || alphaChanged) {
            fillPaints.clear()
        }

        if (layoutChanged) {
            val tracksRebuilt = applyAllocatorLayout()
            // 行高变了 → 轨道落点整体上移/下移 → 重置一次最干净（P2 字号设置就是这么做的）。
            // 显示区域则只在**轨道数真的变了**时才重置：区域滑杆按 1% 步进，
            // 多数档位算出的轨道数与上一档相同，此时轨道下标全部有效、落点也没变，
            // 重置只是白白把游标退回 60s 重扫一遍（那些弹幕还要重新分配轨道，
            // 分不到的那几条会当场消失）。
            // 轨道数变少时那些越界的下标必须清理 —— 否则 `trackTop(越界下标)` 会把弹幕画到区域之外。
            // 只改速度则不重置：在飘的弹幕按自己出场时的速度继续走完（见 Active.speed）。
            if (textSizeChanged || (areaChanged && tracksRebuilt)) reset()
        }
    }

    fun resize(width: Int, height: Int) {
        if (width == canvasWidth && height == canvasHeight) return
        canvasWidth = width
        canvasHeight = height
        applyAllocatorLayout()
        reset()
    }

    /**
     * 把配置里的布局参数推给轨道分配器。
     *
     * @return 轨道数组是否被重建（= 轨道数变了）。调用方据此决定要不要 `reset()`。
     */
    private fun applyAllocatorLayout(): Boolean {
        val speed = if (config.scrollDurationSeconds > 0f) {
            canvasWidth / config.scrollDurationSeconds
        } else {
            0f
        }
        // 喂给分配器的是**显示区域高度**而不是画布高度：轨道只在区域内排，
        // 区域之外（画面下半部）自然就不会出现任何弹幕（P9 显示区域设置）。
        return allocator.updateLayout(
            widthPx = canvasWidth,
            heightPx = config.displayAreaHeightPx(canvasHeight).toInt(),
            trackHeightPx = config.trackHeightPx(density),
            speedPxPerSecond = speed,
            fixedDurationMs = config.fixedDurationMs,
            minGapMs = config.minGapMs,
        )
    }

    // ── 时间轴 / 偏移 ─────────────────────────────────────────────

    fun setTimeline(timeline: DanmakuTimeline) {
        this.timeline = timeline
        reset()
    }

    /** 时轴偏移调整后必须重置，否则活跃弹幕会带着旧时轴继续飘。 */
    fun setOffset(offsetMs: Long) {
        if (this.offsetMs == offsetMs) return
        this.offsetMs = offsetMs
        reset()
    }

    val currentOffsetMs: Long get() = offsetMs

    /**
     * 清空活跃弹幕并重定位游标。seek / 旋屏 / 时间轴或偏移变更后调用（文档 §8.6）。
     */
    fun reset() {
        clearActive()
        allocator.reset()
        cursor = 0
        lastPositionMs = UNSET
    }

    // ── 渲染主流程 ────────────────────────────────────────────────

    /**
     * 渲染一帧。[positionMs] 必须直接来自 `PlayerClock.positionMs`，不可自行推算。
     */
    fun renderFrame(canvas: Canvas, positionMs: Long) {
        if (!config.enabled || timeline.isEmpty || canvasWidth <= 0 || canvasHeight <= 0) {
            clearActive()
            cursor = 0
            lastPositionMs = positionMs
            return
        }

        val now = positionMs - offsetMs

        // 位置跳变（seek / 播放器重建）或长时间无帧（>1s）时重置游标与活跃集合（§8.6 / §12.2）
        if (lastPositionMs == UNSET ||
            now < lastPositionMs - BACKWARD_TOLERANCE_MS ||
            now - lastPositionMs > DRIFT_RESET_MS
        ) {
            resetCursorAt(now)
        }

        val items = timeline.items

        // 2 + 3. 游标单向前进，激活新增弹幕并分配轨道
        while (cursor < items.size && items[cursor].timeMs <= now) {
            activate(items[cursor])
            cursor++
        }

        // 4 + 5. 回收已离屏的，剩下的位置在 draw 里按播放位置直接算
        var i = 0
        while (i < activeCount) {
            val a = pool[i]
            if (now - a.startMs >= a.lifeMs) recycle(i) else i++
        }

        // 6. 绘制：先描边后填充
        for (k in 0 until activeCount) {
            drawOne(canvas, pool[k], now)
        }

        lastPositionMs = now
    }

    /**
     * 重定位游标：把游标退回到「可能仍在屏幕上」的最早条目，让随后的一帧统一激活。
     * 用二分查找而非从头线性扫描，避免长视频上 seek 时扫过上万个条目。
     */
    private fun resetCursorAt(now: Long) {
        clearActive()
        allocator.reset()
        val items = timeline.items
        cursor = lowerBound(items, now - MAX_LOOKBACK_MS)
    }

    private fun activate(item: DanmakuItem) {
        // 铁律 2：文字宽度只测一次，结果缓存回 DanmakuItem
        if (item.textWidth <= 0f) {
            item.textWidth = measurePaint.measureText(item.text)
            if (item.textWidth <= 0f) item.textWidth = 1f
        }
        val width = item.textWidth

        val track = when (item.mode) {
            DanmakuMode.SCROLL -> allocator.allocateScroll(item.timeMs, width)
            DanmakuMode.TOP, DanmakuMode.BOTTOM -> allocator.allocateFixed(item.mode, item.timeMs)
        } ?: return   // 无可用轨道 → 直接丢弃（文档 §8.4）

        val active = obtain()
        active.item = item
        active.track = track
        // 以时间轴上的计划时间作为起点，而不是当前帧时间 —— 这样帧抖动不会让弹幕「晚出场」
        active.startMs = item.timeMs
        active.width = width
        active.mode = item.mode
        // 速度在此刻锁定：之后拖速度滑杆不影响这一条
        active.speed = allocator.speed
        active.lifeMs = when (item.mode) {
            DanmakuMode.SCROLL -> allocator.leaveDurationMs(width, active.speed)
            DanmakuMode.TOP, DanmakuMode.BOTTOM -> allocator.fixedDuration
        }
    }

    private fun drawOne(canvas: Canvas, active: Active, now: Long) {
        val item = active.item ?: return
        val elapsedMs = now - active.startMs

        val x = when (active.mode) {
            DanmakuMode.SCROLL -> canvasWidth - active.speed * elapsedMs / 1000f
            DanmakuMode.TOP, DanmakuMode.BOTTOM -> (canvasWidth - active.width) / 2f
        }
        val top = when (active.mode) {
            DanmakuMode.SCROLL, DanmakuMode.TOP -> allocator.trackTop(active.track)
            DanmakuMode.BOTTOM -> allocator.trackBottom(active.track) - allocator.trackHeight
        }
        val baseline = top + baselineOffset

        // 描边可选：默认 1.2dp 细黑边（亮背景下的可读性兜底），
        // 拖到 0 就是「实心」，只画填充层（文档 §8.5 第 6 步）
        if (strokeWidthPx > 0f) {
            canvas.drawText(item.text, x, baseline, strokePaint)
        }
        canvas.drawText(item.text, x, baseline, paintFor(item.color))
    }

    private fun paintFor(color: Int): Paint {
        val cached = fillPaints.get(color)
        if (cached != null) return cached
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        paint.typeface = TYPEFACE
        paint.textSize = textSizePx
        paint.style = Paint.Style.FILL
        // ★ 颜色与透明度**必须一次合成**：`Paint.color` 是 32 位整体赋值，
        // 拆成「先 paint.alpha = … 再 paint.color = …」会把 alpha 覆盖成 0 ——
        // 弹幕数据里的颜色高 8 位恒为 0（见 DanmakuColor / 文档 §17.3）。
        paint.color = DanmakuColor.withAlpha(color, alphaValue)
        fillPaints.put(color, paint)
        return paint
    }

    // ── 对象池（铁律 1：帧内零分配）────────────────────────────────

    private fun obtain(): Active {
        if (activeCount == pool.size) pool.add(Active())
        return pool[activeCount++]
    }

    private fun recycle(index: Int) {
        val last = activeCount - 1
        val removed = pool[index]
        if (index != last) {
            pool[index] = pool[last]
            pool[last] = removed
        }
        removed.item = null
        activeCount--
    }

    private fun clearActive() {
        for (i in 0 until activeCount) pool[i].item = null
        activeCount = 0
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE

        /** 位置回退容差：播放器偶发回退 1~2ms 不应触发重置 */
        const val BACKWARD_TOLERANCE_MS = 50L

        /** 单帧间隔超过此值视为停滞（掉帧 / 后台恢复），重置游标 */
        const val DRIFT_RESET_MS = 1_000L

        /**
         * seek 后向前回看的时间窗口，必须覆盖**最长的弹幕生命周期**，否则 seek 回来会漏掉仍在屏上的长弹幕。
         *
         * 生命周期 `tLeave = (W + w) / v`，而 `v = W / scrollDuration`，
         * 于是 `tLeave = scrollDuration × (1 + w / W)`：
         * 滚动时长上限 12s（P2 速度设置），一条 3 倍屏宽的长弹幕就是 `12 × 4 = 48s`。
         * 因此取 60s 兜住 —— 代价只是 seek 那一帧多激活几条已经过期的弹幕，随即被回收。
         */
        const val MAX_LOOKBACK_MS = 60_000L

        const val INITIAL_POOL_SIZE = 256

        /** 描边色（RGB）：黑 */
        const val STROKE_RGB = 0x000000

        /**
         * 描边自身的不透明度（0xC0 ≈ 75% 黑）。
         *
         * 与用户透明度相乘后再交给 [DanmakuColor.withAlpha]，
         * 这样「描边别糊住画面」和「用户调的透明度」两个诉求都能满足
         * （早年这里直接写 `strokePaint.alpha = …`，会被随后的 `color` 赋值覆盖掉）。
         */
        const val STROKE_ALPHA = 0xC0

        val TYPEFACE: Typeface = Typeface.DEFAULT_BOLD

        /** 二分查找第一个 `timeMs >= target` 的下标 */
        fun lowerBound(items: List<DanmakuItem>, target: Long): Int {
            var lo = 0
            var hi = items.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (items[mid].timeMs < target) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
