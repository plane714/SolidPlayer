package com.solidplayer.core.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer

/**
 * Media3 ExoPlayer 的生命周期封装（文档 §3.3）。
 *
 * P0 只做「单核 ExoPlayer」这一件事：创建、喂 URI、播放控制、释放。
 * 不引入 ijkplayer / libVLC —— 一个片源要对应三套 Seek/时钟/尺寸逻辑，P0 没有收益。
 *
 * 数据源默认支持 `content://`（SAF 拿到的 URI 就是这种），不需要额外配置。
 */
class SolidPlayer(context: Context) {

    private val appContext: Context = context.applicationContext

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        // 拔耳机自动暂停，避免外放尴尬
        .setHandleAudioBecomingNoisy(true)
        .build()

    /** 弹幕引擎订阅的时钟 */
    val clock: Media3PlayerClock = Media3PlayerClock(player)

    /**
     * 加载媒体并起播。[uri] 可以是 `content://` 或 `file://`。
     */
    fun setMedia(uri: Uri, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        if (startPositionMs > 0L) {
            // prepare 之后再 seek，避免 seek 落在未准备好的时间轴上被丢弃
            player.seekTo(startPositionMs)
        }
        player.playWhenReady = autoPlay
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    /**
     * 跳转到指定位置（进度条拖动 / 快进快退）。
     *
     * 这里**不需要额外通知弹幕层**：Media3 会把位置跳变转成
     * `onPositionDiscontinuity`，[Media3PlayerClock] 已把它转发给弹幕引擎，
     * 引擎据此重置时间轴游标并清空活跃弹幕（文档 §8.6）。
     */
    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    fun release() {
        clock.release()
        player.release()
    }
}
