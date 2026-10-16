package com.solidplayer.core.player

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.solidplayer.core.danmaku.PlayerClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [PlayerClock] 的 Media3 实现（文档 §8.3）。
 *
 * 只做一件事：把 Media3 `Player.Listener` 的四个回调转发成弹幕引擎能听懂的事件。
 * 弹幕引擎因此完全不知道 Media3 的存在。
 *
 * 注意 `positionMs` 每次都是**实时读取** `player.currentPosition`，
 * 不做任何本地缓存或推算 —— 这是「不许漂移」的根本保证。
 */
class Media3PlayerClock(private val player: Player) : PlayerClock {

    private val listeners = CopyOnWriteArrayList<PlayerClock.Listener>()

    private val playerListener = object : Player.Listener {

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val position = player.currentPosition
            for (listener in listeners) listener.onPositionDiscontinuity(position)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            for (listener in listeners) listener.onPlaybackStateChanged(isPlaying)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            for (listener in listeners) listener.onSpeedChanged(playbackParameters.speed)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            for (listener in listeners) {
                listener.onVideoSizeChanged(videoSize.width, videoSize.height)
            }
        }
    }

    init {
        player.addListener(playerListener)
    }

    override val positionMs: Long get() = player.currentPosition

    override val durationMs: Long
        get() = player.duration.let { if (it > 0L) it else 0L }

    override val speed: Float get() = player.playbackParameters.speed

    override val isPlaying: Boolean get() = player.isPlaying

    override fun addListener(listener: PlayerClock.Listener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: PlayerClock.Listener) {
        listeners.remove(listener)
    }

    /** 解绑底层播放器监听。由 [SolidPlayer.release] 统一调用。 */
    internal fun release() {
        player.removeListener(playerListener)
        listeners.clear()
    }
}
