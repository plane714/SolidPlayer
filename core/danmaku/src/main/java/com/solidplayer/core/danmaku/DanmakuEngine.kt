package com.solidplayer.core.danmaku

import com.solidplayer.core.danmaku.model.DanmakuConfig
import com.solidplayer.core.danmaku.model.DanmakuTimeline
import com.solidplayer.core.danmaku.render.DanmakuView

/**
 * 弹幕引擎门面（文档 §3.3）。
 *
 * 职责边界：
 * - 持有 [DanmakuConfig] / 时轴偏移 / 开关状态
 * - 订阅 [PlayerClock] 的事件，把「必须重置时间轴」的信号转成 [DanmakuView.renderer] 的 `reset()`
 * - **不关心播放内核是谁** —— 只认 [PlayerClock] 接口
 *
 * ### 关于「倍速切换 / 暂停恢复是否重置」的取舍
 * 文档 §8.6 要求这两种事件也重置时间轴。本实现**不重置**，原因是：
 * 渲染位置是 `x = W - v × (now - startMs)` 直接由播放位置推导的，
 * 而不是「上一帧位置 + 速度 × 帧间隔」的积分式，**不存在需要纠正的累计误差**；
 * 而重置会让此刻正在屏幕上飘的弹幕瞬间消失再重来，视觉上是明显的闪烁。
 * 只有真正的**位置跳变**（seek / 播放器重建）和**画面尺寸变化**才需要重置。
 */
class DanmakuEngine(private val view: DanmakuView) {

    private var clock: PlayerClock? = null
    private var config = DanmakuConfig()
    private var density: Float = view.resources.displayMetrics.density

    private val listener = object : PlayerClock.Listener {

        override fun onPositionDiscontinuity(newPositionMs: Long) {
            // seek / 快进快退 / 播放器重建：必须重置时间轴游标（文档 §8.6）
            view.renderer.reset()
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) = Unit

        override fun onSpeedChanged(speed: Float) = Unit

        override fun onVideoSizeChanged(width: Int, height: Int) {
            view.renderer.reset()
        }
    }

    /** 当前时轴偏移（毫秒），正值表示弹幕整体延后出现 */
    val offsetMs: Long get() = view.renderer.currentOffsetMs

    /** 当前活跃弹幕数，用于调试与性能观测 */
    val activeCount: Int get() = view.renderer.activeSize

    val configRef: DanmakuConfig get() = config

    init {
        view.renderer.setConfig(config, density)
    }

    /**
     * 绑定播放时钟并开始渲染。重复调用会先解绑旧时钟。
     */
    fun attach(clock: PlayerClock) {
        detach()
        this.clock = clock
        clock.addListener(listener)
        // 唯一的时钟来源：播放器 currentPosition（文档 §8.3）
        view.bindPositionProvider { this.clock?.positionMs ?: 0L }
        view.start()
    }

    fun detach() {
        clock?.removeListener(listener)
        clock = null
        view.bindPositionProvider(null)
        view.stop()
    }

    fun setTimeline(timeline: DanmakuTimeline) {
        view.renderer.setTimeline(timeline)
    }

    fun setConfig(config: DanmakuConfig) {
        this.config = config
        view.renderer.setConfig(config, density)
    }

    /**
     * 设置时轴偏移。UI 上的 ±0.5s 步进直接调用本方法，渲染器内部会重置时间轴。
     */
    fun setOffset(offsetMs: Long) {
        view.renderer.setOffset(offsetMs)
    }

    fun nudgeOffset(deltaMs: Long) {
        setOffset(offsetMs + deltaMs)
    }

    fun setEnabled(enabled: Boolean) {
        if (config.enabled == enabled) return
        setConfig(config.copy(enabled = enabled))
    }

    /** Activity / Fragment 销毁时调用，解绑时钟并停止帧回调。 */
    fun release() {
        detach()
        setTimeline(DanmakuTimeline.EMPTY)
    }
}
