package com.solidplayer.core.danmaku.render

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * 弹幕渲染宿主 View。
 *
 * **与文档 §3.3 / §8.5 的差异（P0 取舍，需确认）**：
 * 文档写的是 `DanmakuSurfaceView`（SurfaceView + 硬件加速 Canvas）。
 * 但本项目播放器用 Media3 的 `PlayerView`，其视频输出走的也是 SurfaceView，
 * 两个 SurfaceView 叠加要额外处理 `setZOrderMediaOverlay` / 透明挖洞，在 P0 属于纯风险无收益。
 * 这里改用**普通 View + 硬件加速 Canvas**：普通 SurfaceView 默认位于宿主窗口之下，
 * 因此本 View 天然绘制在视频画面之上，且 `onDraw` 的 Canvas 本身就是硬件加速的。
 *
 * 结论：只有将来弹幕密度高到 CPU 绘制成为瓶颈时，才需要换成 SurfaceView / GLES 方案。
 *
 * 帧驱动用 `Choreographer`（与系统 vsync 对齐），位置一律取自 `PlayerClock`，
 * **不自建计时器**（文档 §8.3）。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    internal val renderer = DanmakuRenderer()

    private var positionProvider: (() -> Long)? = null
    private var running = false
    private val choreographer: Choreographer = Choreographer.getInstance()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            // invalidate → 本帧的 onDraw 会在同一个 vsync 内执行
            invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    init {
        // 弹幕层必须透出下层视频，因此不能有背景
        setBackgroundColor(0)
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    /** 绑定播放位置来源；传 `null` 表示暂停读取（此时不再绘制弹幕）。 */
    fun bindPositionProvider(provider: (() -> Long)?) {
        positionProvider = provider
    }

    fun start() {
        if (running) return
        running = true
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderer.resize(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val provider = positionProvider ?: return
        renderer.renderFrame(canvas, provider())
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
