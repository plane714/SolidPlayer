package com.solidplayer.core.danmaku

/**
 * 弹幕引擎与播放内核之间**唯一**的契约（文档 §8.3）。
 *
 * `:core:danmaku` 只认这个接口，绝不依赖 Media3 —— 这是「约束 A：弹幕层与播放内核解耦」的落地方式。
 * 任何播放内核（Media3 / ijk / libVLC）只要实现本接口即可接入弹幕渲染。
 *
 * ⚠️ 唯一正确的时钟来源是播放器的 `currentPosition`。
 * 绝不可用自建 `Handler` / `Timer` 计时 —— 那一定会因帧率与系统调度产生累计漂移，
 * 表现为「看久了弹幕越来越不准」（文档 §8.3）。
 */
interface PlayerClock {

    /** 当前播放位置（毫秒） */
    val positionMs: Long

    /** 总时长（毫秒），未知时返回 0 */
    val durationMs: Long

    /** 播放倍速，正常为 1f */
    val speed: Float

    val isPlaying: Boolean

    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)

    interface Listener {

        /**
         * 位置发生跳变（seek / 播放器重建）。
         * 引擎收到后必须重置时间轴游标并清空活跃弹幕（文档 §8.6）。
         */
        fun onPositionDiscontinuity(newPositionMs: Long)

        fun onPlaybackStateChanged(isPlaying: Boolean)

        fun onSpeedChanged(speed: Float)

        /** 画面尺寸变化（含旋屏）。引擎需要据此重算轨道数。 */
        fun onVideoSizeChanged(width: Int, height: Int)
    }
}
