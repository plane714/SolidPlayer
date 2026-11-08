package com.solidplayer.core.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer

/** 一条**内嵌**字幕轨道（视频容器自带的，不含旁挂字幕文件）。 */
data class EmbeddedSubtitleTrack(
    /** 在单一字幕 TrackGroup 内的下标 */
    val indexInGroup: Int,
    /** 语言标记（来自容器元数据，可能是 `chi`/`eng` 这类码，也可能为空） */
    val language: String?,
    /**
     * 展示用标签：**优先用字幕轨原来的名字**（容器里存的轨道标题，`Format.label`，
     * 如「简体中文」「Signs & Songs」等原样照搬，不做翻译）；没有标题时退回语言码，
     * 再没有才用 `字幕 N`。
     */
    val label: String,
    /** 是否当前选中的轨道 */
    val selected: Boolean,
)

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

    // ── 内嵌字幕轨道（视频容器自带）─────────────────────────────────
    //
    // 只用 Media3 的 TrackSelectionParameters 做轨道切换，**不加载旁挂字幕文件**：
    // 需求是「选择添加字幕轨道」= 在视频自带的字幕轨之间切，或整体关闭字幕。
    // 内嵌字幕由 ExoPlayer 的 text renderer 解析、PlayerView 的内置字幕层渲染，
    // 样式仍由 applySubtitleStyle 统一控制，两者互不冲突。

    /**
     * 当前视频的**内嵌字幕轨道**列表（空列表 = 该视频没有字幕轨）。
     *
     * 一条视频通常只有一个字幕 TrackGroup，里面可能有多条不同语言的轨道。
     * 这里把该 TrackGroup 的所有子项摊平成 [EmbeddedSubtitleTrack]，并标出哪条是当前选中的。
     *
     * 若容器里出现多个字幕 Group（少见），只取第一个 —— 跨 Group 的 override 会互相排斥，
     * 摊平到一个下拉菜单里反而让语义含糊。
     */
    fun embeddedSubtitleTracks(): List<EmbeddedSubtitleTrack> {
        val tracks: Tracks = player.currentTracks
        val group: Tracks.Group = tracks.groups.firstOrNull { it.type == TRACK_TYPE_TEXT }
            ?: return emptyList()
        return (0 until group.length).map { i ->
            val format = group.getTrackFormat(i)
            val language = format.language?.takeIf { it.isNotBlank() }
            EmbeddedSubtitleTrack(
                indexInGroup = i,
                language = language,
                label = subtitleTrackLabel(format.label, language, i),
                selected = group.isTrackSelected(i),
            )
        }
    }

    /**
     * 选中的字幕轨道是否来自「当前 override」而不是默认渲染器自动选的。
     *
     * 主要用于「关闭字幕」之后把该状态说清楚 —— 见 [disableSubtitles]。
     */
    fun hasSubtitleGroup(): Boolean =
        player.currentTracks.groups.any { it.type == TRACK_TYPE_TEXT }

    /**
     * 选中某一条内嵌字幕轨道（[indexInGroup] 取自 [embeddedSubtitleTracks]）。
     *
     * 用 `TrackSelectionOverride` 显式钉住整条 Group 并指定轨道下标 —— 只设 language 覆盖
     * 在「同语言有多条轨（如正片 + 评论音轨字幕）」时会分不清是哪条。
     */
    fun selectSubtitleTrack(indexInGroup: Int) {
        val group = player.currentTracks.groups.firstOrNull { it.type == TRACK_TYPE_TEXT } ?: return
        val mediaTrackGroup: TrackGroup = group.mediaTrackGroup
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(mediaTrackGroup, indexInGroup))
            .build()
    }

    /** 关闭字幕（保留视频内的字幕轨道，只是不渲染）。 */
    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(TRACK_TYPE_TEXT)
            .build()
    }

    fun release() {
        clock.release()
        player.release()
    }

    private companion object {
        /** Media3 `C.TRACK_TYPE_TEXT` 的数值常量，避免为这一个常量引入 C 的整包依赖。 */
        const val TRACK_TYPE_TEXT = 3

        /**
         * 轨道标签：**优先用字幕轨原来的名字**（容器里的轨道标题 `Format.label` 原样照搬），
         * 其次退回语言码，最后才是 `字幕 N`。
         *
         * 为什么不翻译语言名：不同片源给同名轨道的叫法五花八门（「简体」「简中」「CHS」「中文·正片」
         * 「Signs & Songs」…），统一翻成「简体中文」反而会**抹掉轨道原本的区别**，
         * 用户在多条同语言轨之间就分不清哪条是正片、哪条是特效/注释。原样展示最不容易选错。
         */
        fun subtitleTrackLabel(originalLabel: String?, language: String?, index: Int): String {
            val original = originalLabel?.trim().orEmpty()
            if (original.isNotEmpty()) return original
            val byLanguage = language?.trim()?.takeIf { it.isNotEmpty() }
            return byLanguage ?: "字幕 ${index + 1}"
        }
    }
}
