package com.solidplayer.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.solidplayer.core.danmaku.DanmakuEngine
import com.solidplayer.core.danmaku.model.DanmakuTimeline
import com.solidplayer.core.danmaku.render.DanmakuView
import com.solidplayer.core.data.preference.DanmakuPreferenceCodec
import com.solidplayer.core.data.preference.DanmakuPreferences
import com.solidplayer.core.data.preference.SubtitlePreferenceCodec
import com.solidplayer.core.data.preference.SubtitlePreferences
import com.solidplayer.core.player.SolidPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val OFFSET_STEP_MS = 500L

/** 长按画面时的临时倍速。 */
private const val LONG_PRESS_SPEED = 3f

/** 播放中控制层的自动隐藏延时。 */
private const val CONTROLS_AUTO_HIDE_MS = 3_500L

/** 黑幕的兜底揭示时限：超过它还没拿到视频宽高就直接放行，见 [PlayerScreen] 里的说明。 */
private const val SURFACE_REVEAL_TIMEOUT_MS = 1_500L

/**
 * 进度条位置轮询间隔。
 *
 * 200ms 是「肉眼看不出跳格」与「不白白唤醒主线程」之间的折中。
 * 位置读数本身是 O(1)（直接读播放器的 currentPosition），
 * 写回 State 时若值未变 Compose 不会触发重组，所以暂停时轮询也不产生任何绘制。
 */
private const val POSITION_POLL_MS = 200L

/** 倍速候选。 */
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * 紧凑控件的屏宽分界（dp）。
 *
 * 与设置面板「屏宽 > 600dp 就给固定 320dp 宽」用的是同一条线 —— 语义一致：
 * 线以下是手机（竖屏时尤其窄），线以上是横屏手机 / 平板，控件可以铺开。
 */
private const val COMPACT_WIDTH_DP = 600

/**
 * 播放页的方向策略。
 *
 * 默认 [FOLLOW]：**不强制旋转**。竖屏视频、录屏、竖屏短视频在这个 App 的目标里占相当比例，
 * 一进页就锁横屏会把它们压在屏幕中间一条。用户想锁的时候顶栏按钮循环切换。
 *
 * `FOLLOW` 用 `SENSOR` 而不是 `UNSPECIFIED`：后者的实际行为取决于系统的「自动旋转」开关，
 * 开关关掉时横放手机也不会横过来 —— 那就不叫「跟随手机」了。
 */
private enum class OrientationMode {
    FOLLOW,
    LANDSCAPE,
    PORTRAIT,
    ;

    fun next(): OrientationMode = when (this) {
        FOLLOW -> LANDSCAPE
        LANDSCAPE -> PORTRAIT
        PORTRAIT -> FOLLOW
    }

    /** 交给 `Activity.requestedOrientation` 的系统常量。 */
    val activityOrientation: Int
        get() = when (this) {
            FOLLOW -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
}

/** 三种状态各给一个能一眼分辨的图标（跟随用旋转圈、锁定时用「固定姿态」那对图标）。 */
private val OrientationMode.icon: ImageVector
    get() = when (this) {
        OrientationMode.FOLLOW -> Icons.Filled.ScreenRotation
        OrientationMode.LANDSCAPE -> Icons.Filled.StayCurrentLandscape
        OrientationMode.PORTRAIT -> Icons.Filled.StayCurrentPortrait
}

/** 无文字按钮，图标必须自己说清楚「现在是什么状态 + 点一下会怎样」。 */
private val OrientationMode.description: String
    get() = when (this) {
        OrientationMode.FOLLOW -> "方向跟随手机，点击锁定横屏"
        OrientationMode.LANDSCAPE -> "已锁定横屏，点击改为锁定竖屏"
        OrientationMode.PORTRAIT -> "已锁定竖屏，点击改回跟随手机"
    }

/**
 * 横滑调进度的灵敏度：**横向滑满整个屏幕宽度 ≈ 前进/后退 90 秒**。
 *
 * 用「占屏宽的比例」而不是「每 dp 多少秒」，是为了让手感与屏幕尺寸无关 ——
 * 手机上滑半屏和折叠屏上滑半屏，跳过的时长是一致的。
 */
private const val SEEK_FULL_SWEEP_MS = 90_000L

/** 横滑调进度时画面中央的浮层内容。 */
private data class SeekPreview(
    /** 松手后会跳到的时间点 */
    val positionMs: Long,
    /** 相对当前播放位置的偏移（正数快进、负数后退） */
    val deltaMs: Long,
)

/** 主题色：进度条已播放段与选中态。 */
private val Accent = Color(0xFFFB7299)

/** 横滑调进度的偏移色：快进偏暖、后退偏冷（与「涨红跌绿」同一套直觉）。 */
private val SeekForward = Color(0xFFFF8A80)
private val SeekBackward = Color(0xFF9CCC9C)

// ── 弹幕设置面板的配色 ────────────────────────────────────────────
// 面板永远盖在画面上，不跟随 App 的明暗主题，所以这里全部写死。

/** 面板底色：接近不透明的黑，画面仍隐约透出来 */
private val PanelBackground = Color(0xF0121214)

/** 面板正文（滑杆标题、按钮文字） */
private val PanelLabel = Color(0xE6FFFFFF)

/** 面板里的说明文字 */
private val PanelHint = Color(0x99FFFFFF)

/** 分组分隔线 */
private val PanelDivider = Color(0x1FFFFFFF)

/** 面板内的填充按钮：统一用主题强调色，避免在深底上出现 Material 默认的浅色块 */
private val PanelButtonColors
    @Composable get() = ButtonDefaults.buttonColors(
        containerColor = Accent,
        contentColor = Color.White,
    )

/** 面板内的屏蔽词输入框 */
private val PanelTextFieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = Accent,
        unfocusedBorderColor = Color(0x4DFFFFFF),
        focusedLabelColor = Accent,
        unfocusedLabelColor = PanelHint,
        focusedPlaceholderColor = Color(0x4DFFFFFF),
        unfocusedPlaceholderColor = Color(0x4DFFFFFF),
        cursorColor = Accent,
    )

/** 面板内的开关（字幕描边用）：与滑杆同一套强调色 */
private val PanelSwitchColors
    @Composable get() = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = Accent,
        uncheckedThumbColor = Color(0xFFBDBDBD),
        uncheckedTrackColor = Color(0x40FFFFFF),
        uncheckedBorderColor = Color.Transparent,
    )

/**
 * 播放页（沉浸式控制层）。
 *
 * 布局取向与「视频 + 顶栏 + 控制栏」的传统写法相反：**画面铺满整屏，控件浮在画面上**。
 * 原来的写法里，顶栏与三行控制条会把画面挤掉近一半高度，对一个以「看」为主的页面来说
 * 是本末倒置 —— 控制栏只是辅助，需要时才出现。
 *
 * 交互：
 * - 单击画面：切换控制层显隐；播放中 3.5s 无操作自动隐藏
 * - 双击画面：播放 / 暂停（与底部按钮同一份实现）
 * - 横滑画面：调进度。拖动中只显示浮层与目标时间，松手才真正 seek
 *   （滑满整屏 ≈ 90 秒，见 [SEEK_FULL_SWEEP_MS]）
 * - 长按画面：临时 3 倍速，松手恢复
 * - 拖动进度条：拖动中只更新画面上的数字，松手才真正 seek
 *   （每拖一像素就 seek 一次会让播放器持续重建缓冲区，表现为卡顿）
 *
 * 方向**不强制旋转**：默认跟随手机（竖屏视频、录屏在竖屏下才不被压成中间一条），
 * 顶栏按钮可循环「跟随 → 锁横屏 → 锁竖屏」。窄屏（低于 [COMPACT_WIDTH_DP]）下
 * 上下控制栏整体收紧一档，五个控件才在一行里排得开。
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // 播放器与弹幕引擎的生命周期都挂在 Composable 上，离开页面即释放。
    // 「下一集」是原地换源（见 PlayerViewModel.switchEpisode），不会重建这两者。
    val solidPlayer = remember { SolidPlayer(context) }
    var engine by remember { mutableStateOf<DanmakuEngine?>(null) }
    /** 拿到 PlayerView 才能设置内置字幕层的样式（字号 / 描边 / 去黑底） */
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    var controlsVisible by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    /** 拖动中的进度（0..1）；null 表示没在拖 */
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var accelerating by remember { mutableStateOf(false) }
    /** 横滑调进度的浮层内容；null 表示当前没在横滑 */
    var seekPreview by remember { mutableStateOf<SeekPreview?>(null) }
    /** 方向策略；默认跟随手机，不强制旋转 */
    var orientationMode by remember { mutableStateOf(OrientationMode.FOLLOW) }

    /**
     * 画面「遮黑幕」的两个开关，用来消掉进页那一瞬的满屏闪动（详见 [VideoSurface] 上方的说明）。
     *
     * - [videoSized]：播放器已经报出了视频的真实宽高；
     * - [surfaceRevealed]：宽高带来的重新测量已经落到画面上，黑幕可以撤了。
     */
    var videoSized by remember { mutableStateOf(false) }
    var surfaceRevealed by remember { mutableStateOf(false) }

    /**
     * 窄屏走紧凑控件（见 [COMPACT_WIDTH_DP]）。
     *
     * 用 `LocalConfiguration` 而不是 `BoxWithConstraints`：屏宽本来就是 Configuration 的一部分，
     * 旋转时它自己会变、Compose 也会跟着重组，不需要再引一层 subcomposition 去量。
     */
    val compact = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP

    // ── 沉浸式：隐藏系统栏（方向交给用户/传感器）──────────────────
    // 这套逻辑挂在本 Composable 上而不是导航路由上，且不随「下一集」重跑，
    // 因此切集时不会出现方向翻转与系统栏闪现。
    DisposableEffect(Unit) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // 只把系统栏显示回来，**不要**再把 setDecorFitsSystemWindows 还原成 true：
            // edge-to-edge 已由 MainActivity.enableEdgeToEdge() 全局生效，还原会让
            // 退出播放页后整个 App（首页/详情页）退回非 edge-to-edge 布局。
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            viewModel.savePosition(solidPlayer.player.currentPosition, solidPlayer.player.safeDuration())
            engine?.release()
            solidPlayer.release()
        }
    }

    // 方向：默认跟随手机，顶栏按钮可在「跟随 → 锁横 → 锁竖」之间循环。
    //
    // 翻转**不会重建 Activity**：manifest 的 `configChanges` 已含 `orientation|screenSize`，
    // 所以转手机时画面、播放进度、弹幕时间轴都不会断 —— 这正是能用 SENSOR 跟随的前提。
    LaunchedEffect(orientationMode) {
        activity?.requestedOrientation = orientationMode.activityOrientation
    }

    // 播放中不让屏幕熄灭；暂停/后台交给系统正常休眠
    DisposableEffect(playing) {
        val window = activity?.window
        if (playing) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // ── 播放状态：来自播放器回调，不靠按钮点击手动维护 ────────────
    // （原来只在点按钮时翻转 isPlaying，视频播完或拔耳机自动暂停后图标不会跟着变）
    DisposableEffect(solidPlayer) {
        val player = solidPlayer.player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        ended = false
                        durationMs = player.safeDuration()
                    }

                    Player.STATE_ENDED -> {
                        ended = true
                        controlsVisible = true
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) videoSized = true
            }
        }
        player.addListener(listener)
        player.safeDuration().takeIf { it > 0L }?.let { durationMs = it }
        onDispose { player.removeListener(listener) }
    }

    // ── 起播：播放器启动**不等**弹幕 ─────────────────────────────
    LaunchedEffect(state.uri, state.startPositionMs) {
        val uri = state.uri ?: return@LaunchedEffect
        solidPlayer.setMedia(Uri.parse(uri), startPositionMs = state.startPositionMs)
    }

    // ── 撤掉遮画面的黑幕（消「进页一闪满屏」）─────────────────────
    //
    // 视频宽高到达时，`AspectRatioFrameLayout` 才把画面从「铺满整屏」调成真实比例，
    // 而那次 `setAspectRatio` 只做 `requestLayout` —— 要等下一次窗口遍历才落到画面上。
    // 立刻撤黑幕会露出中间那一两帧**被拉伸成满屏**的画面（就是用户看到的那一闪），
    // 所以这里多等两个 vsync 再揭示。
    LaunchedEffect(videoSized) {
        if (!videoSized) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        surfaceRevealed = true
    }

    // 兜底：万一某个文件始终报不出宽高（容器异常 / 只有音轨），黑幕不能永远盖着 ——
    // 「拉伸着能看」总好过「全黑什么也看不见」。正常文件的宽高在几百毫秒内就到位，
    // 走不到这条路径。
    LaunchedEffect(state.uri) {
        if (state.uri == null) return@LaunchedEffect
        delay(SURFACE_REVEAL_TIMEOUT_MS)
        surfaceRevealed = true
    }

    // 进度与缓冲位置轮询（拖动期间让位给手指）
    val scrubbingRef = rememberUpdatedState(scrubbing)
    val durationRef = rememberUpdatedState(durationMs)
    LaunchedEffect(solidPlayer) {
        val player = solidPlayer.player
        while (true) {
            if (scrubbingRef.value == null) {
                positionMs = player.currentPosition
                bufferedMs = player.bufferedPosition
                if (durationMs <= 0L) durationMs = player.safeDuration()
            }
            delay(POSITION_POLL_MS)
        }
    }

    // 倍速走播放器参数。弹幕引擎对倍速变化**不重置**时间轴
    // （弹幕 x 由播放位置直接推导，不存在需要纠正的累计误差）
    LaunchedEffect(state.speed, solidPlayer) {
        solidPlayer.setSpeed(state.speed)
    }

    // ── 弹幕引擎接线 ─────────────────────────────────────────────
    LaunchedEffect(engine) {
        engine?.attach(solidPlayer.clock)
    }

    // 时间轴为 null（切集瞬间）时必须显式喂一份空时间轴：
    // 「什么都不做」会让引擎继续拿着上一集的弹幕渲染，而播放位置已经跳到新集开头
    LaunchedEffect(engine, state.timeline) {
        engine?.setTimeline(state.timeline ?: DanmakuTimeline.EMPTY)
    }

    // 样式（含开关）走同一条通路：DanmakuConfig 是 data class，改哪一项就只影响哪一项
    LaunchedEffect(engine, state.danmakuConfig) {
        engine?.setConfig(state.danmakuConfig)
    }

    // ── 字幕样式接线 ─────────────────────────────────────────────
    // 字幕由 PlayerView 内置的字幕层渲染，偏好一变就重新套一遍样式，
    // 所以在设置面板里拖字号滑杆时，画面上的字幕是**即时**跟着变的。
    LaunchedEffect(playerView, state.subtitlePrefs) {
        playerView?.applySubtitleStyle(state.subtitlePrefs)
    }

    LaunchedEffect(engine, state.offsetMs) {
        engine?.setOffset(state.offsetMs)
    }

    // 一次性提示：**先消费、再显示**（见 FolderListScreen 里的同款说明）。
    // 反过来的话，`showSnackbar` 挂起期间页面一旦离开组合（退出去/换页），
    // 协程被取消、`consumeMessage()` 永远不会执行，这条消息会在下次进页时再弹一遍。
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        snackbarHostState.showSnackbar(message)
    }

    // ── 抽出来给多处复用的动作 ───────────────────────────────────
    val goToNextEpisode = {
        state.nextEpisodeId?.let { next ->
            // 切集前先把当前进度落库：原地换源不会触发本页的 onDispose
            viewModel.savePosition(solidPlayer.player.currentPosition, solidPlayer.player.safeDuration())
            // 立刻停住画面：新一集的 uri 要等一次 DB 查询才到位，
            // 不停的话用户点完「下一集」还会看到上一集继续播几十毫秒
            solidPlayer.pause()
            viewModel.switchEpisode(next)
        }
        Unit
    }

    /** 收起弹幕设置面板。顺带把样式落库 —— 面板可能一直开着就被关掉了。 */
    val dismissSettings = {
        viewModel.persistDanmakuStyle()
        viewModel.persistSubtitleStyle()
        showSettings = false
    }

    /** 播放 / 暂停。底部按钮与「双击画面」共用一份实现（含暂停时落库进度）。 */
    val togglePlayPause = {
        if (solidPlayer.player.isPlaying) {
            solidPlayer.pause()
            viewModel.savePosition(
                solidPlayer.player.currentPosition,
                solidPlayer.player.safeDuration(),
            )
        } else {
            solidPlayer.play()
        }
    }

    // 面板打开时返回键先收面板，而不是直接退出播放页。
    // （原来用的是 ModalBottomSheet，它自带这个行为；换成自绘浮层后必须自己补上）
    BackHandler(enabled = showSettings) { dismissSettings() }

    // 播完自动接下一集
    LaunchedEffect(ended) {
        if (ended && state.nextEpisodeId != null) goToNextEpisode()
    }

    // 控制层自动隐藏：暂停、拖动、长按加速、菜单展开时都不隐藏。
    // 键用「是否在拖动」这个布尔量而不是 scrubbing 本身 —— 后者每移动一像素就变一次，
    // 会把本 Effect 按帧重启一遍（虽然每次都立即 return，但白建白拆协程）
    LaunchedEffect(
        controlsVisible,
        playing,
        scrubbing != null,
        accelerating,
        speedMenuOpen,
        showSettings,
    ) {
        val keepVisible = !controlsVisible || !playing || scrubbing != null ||
            accelerating || speedMenuOpen || showSettings
        if (keepVisible) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VideoSurface(
            player = solidPlayer.player,
            onPlayerViewReady = { playerView = it },
            onDanmakuViewReady = { danmakuView ->
                if (engine == null) engine = DanmakuEngine(danmakuView)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 黑幕：视频宽高就绪前盖住画面。
        //
        // 为什么需要它：`PlayerView` 内部的 `AspectRatioFrameLayout` 在拿到视频宽高之前
        // `aspectRatio = 0`，此时它的测量结果就是**父容器的整块尺寸** —— 于是首帧被拉伸成
        // 满屏（竖屏手机上是一条被拉长的 16:9），下一次遍历拿到真实比例后才缩回正常大小。
        // 表现就是用户说的「从全屏大小突然闪到正常大小」。
        //
        // 用一层不透明的黑 `Box` 而不是把 `PlayerView` 设成 INVISIBLE：SurfaceView 一旦
        // 不可见，它自己的 surface 会被销毁，PlayerView 的首帧回调就无从谈起；
        // 而「普通 View 画在 SurfaceView 之上」是本页既有的事实（弹幕层就是这么叠上去的）。
        // 这一层没有任何指针输入修饰符，不参与命中测试，不会挡住下面的手势层。
        if (!surfaceRevealed) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        // 手势层：单击切控制层 / 双击播放暂停 / 横滑调进度 / 长按临时加速。
        // 放在控件之前（也就是控件之下），点在按钮上时事件被按钮吃掉，不会误触发。
        //
        // **面板打开时必须关掉**：手势层用的是 `awaitFirstDown(requireUnconsumed = false)`，
        // 它不理会事件是否已被上层（滑杆）消费 —— 不停用的话，在滑杆上按住不放
        // 会一边拖滑杆一边触发 3 倍速，点一下滑杆还会顺手把控制层切没。
        //
        // 四种手势必须在**同一个 awaitEachGesture 循环**里判定：拆成多个 pointerInput
        // 会互相消费事件（拖动一旦被别人消费，判定期就会把这一手势当成「事件被吃掉」，
        // 反过来又触发长按加速）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(solidPlayer, showSettings) {
                    if (showSettings) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        // ── 判定期：在「长按阈值」之内先看手指往哪走 ──────────
                        // 三种去向：抬手（单击 / 双击候选）、横向超过 slop（拖动调进度）、
                        // 超时未动（长按加速）。
                        var dx = 0f
                        var horizontalDrag = false
                        val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) break
                                dx = change.position.x - down.position.x
                                if (abs(dx) > viewConfiguration.touchSlop) {
                                    horizontalDrag = true
                                    break
                                }
                            }
                        } == null

                        when {
                            // 超时未动：长按临时加速，松手恢复
                            timedOut -> {
                                val previousSpeed = solidPlayer.player.playbackParameters.speed
                                solidPlayer.setSpeed(LONG_PRESS_SPEED)
                                accelerating = true
                                try {
                                    waitForUpOrCancellation()
                                } finally {
                                    solidPlayer.setSpeed(previousSpeed)
                                    accelerating = false
                                }
                            }

                            horizontalDrag -> {
                                // 拖动中只更新浮层与进度条，**松手才 seek** ——
                                // 每拖一像素就 seek 一次会让播放器反复重建缓冲区，表现为卡顿
                                // （与手绘进度条同一条约定）。
                                val duration = durationRef.value
                                val startPosition = solidPlayer.player.currentPosition
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                var target: Long? = null
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    // 时长还没就绪时拖了也不知道往哪跳，只把手势吃掉
                                    if (duration <= 0L) continue
                                    dx = change.position.x - down.position.x
                                    val next =
                                        (startPosition + (dx / width * SEEK_FULL_SWEEP_MS).toLong())
                                            .coerceIn(0L, duration)
                                    target = next
                                    seekPreview = SeekPreview(next, next - startPosition)
                                    scrubbing = (next.toFloat() / duration).coerceIn(0f, 1f)
                                }
                                seekPreview = null
                                scrubbing = null
                                target?.let {
                                    solidPlayer.seekTo(it)
                                    positionMs = it
                                }
                            }

                            // 抬手：等一个双击窗口。等到了是双击（播放/暂停），等不到是单击（切控制层）。
                            // 代价是单击最多晚 doubleTapTimeout（约 300ms）才切控制层 ——
                            // 「同一个手势位既要点一下又要双击两下」避不开这个取舍。
                            else -> {
                                val secondDown =
                                    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                                        awaitFirstDown(requireUnconsumed = false)
                                    }
                                if (secondDown != null) {
                                    secondDown.consume()
                                    waitForUpOrCancellation()
                                    togglePlayPause()
                                } else {
                                    controlsVisible = !controlsVisible
                                }
                            }
                        }
                    }
                },
        )

        // 弹幕设置面板打开时收起上下控制栏：面板纵向铺满整屏，
        // 留着控制栏会被面板盖掉右半边，看起来像按钮凭空断了一截
        AnimatedVisibility(
            visible = controlsVisible && !showSettings,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PlayerTopBar(
                fileName = state.fileName.ifBlank { "播放" },
                subtitle = listOfNotNull(state.episodeLabel, state.danmakuStatus)
                    .joinToString(" · "),
                orientationMode = orientationMode,
                compact = compact,
                canReloadDanmaku = state.episodeId != null,
                onBack = onBack,
                onCycleOrientation = { orientationMode = orientationMode.next() },
                onReloadDanmaku = viewModel::reloadDanmaku,
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !showSettings,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerBottomBar(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                scrubbingFraction = scrubbing,
                playing = playing,
                compact = compact,
                danmakuEnabled = state.danmakuEnabled,
                episodeLabel = state.episodeLabel,
                speed = state.speed,
                speedMenuOpen = speedMenuOpen,
                hasNext = state.nextEpisodeId != null,
                nextEpisodeTitle = state.nextEpisodeTitle,
                onScrub = { scrubbing = it },
                onScrubFinished = { fraction ->
                    val target = (durationMs * fraction).toLong()
                    solidPlayer.seekTo(target)
                    positionMs = target
                    scrubbing = null
                },
                onTogglePlay = togglePlayPause,
                onToggleDanmaku = viewModel::toggleDanmaku,
                onOpenSettings = { showSettings = true },
                onNextEpisode = goToNextEpisode,
                onSpeedMenuOpenChange = { speedMenuOpen = it },
                onSpeedSelected = viewModel::setSpeed,
            )
        }

        if (accelerating) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(color = Color(0x99000000), shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "${formatSpeed(LONG_PRESS_SPEED)} 加速中",
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
        }

        // 横滑调进度的浮层：位置与「加速中」一致，形态也一致。
        // 必须给出**目标时间**而不是只给一个偏移量 —— 隔几分钟调一次时轴，
        // 用户需要知道「松手会跳到哪儿」，否则只能靠试。
        seekPreview?.let { preview ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(color = Color(0xCC000000), shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${formatTime(preview.positionMs)} / ${formatTime(durationMs)}",
                        color = Color.White,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = formatDelta(preview.deltaMs),
                        color = if (preview.deltaMs >= 0L) SeekForward else SeekBackward,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // 弹幕侧的异常提示：只在控制层可见时出现，避免长时间遮挡画面
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            OverlayBanner(
                state = state,
                onRetry = viewModel::retryDanmaku,
                modifier = Modifier.padding(top = 64.dp),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 104.dp),
        )

        // 播放设置面板放在本 Box 的**最后一个子项**（也就是最上层）：
        // 它要盖住画面、盖住控制栏，并且面板外侧的点击层要能挡住下层所有手势
        PlaySettingsPanel(
            visible = showSettings,
            prefs = state.danmakuPrefs,
            subtitlePrefs = state.subtitlePrefs,
            blockedCount = state.blockedCount,
            offsetMs = state.offsetMs,
            onAlphaChange = viewModel::setAlphaPercent,
            onTextScaleChange = viewModel::setTextScalePercent,
            onScrollSecondsChange = viewModel::setScrollSeconds,
            onStrokeWidthChange = viewModel::setStrokeWidthDp,
            onDisplayAreaChange = viewModel::setDisplayAreaPercent,
            onSubtitleTextSizeChange = viewModel::setSubtitleTextSizeSp,
            onSubtitleOutlineChange = viewModel::setSubtitleOutlineEnabled,
            // 滑杆松手 / 开关切换 / 面板关闭时落库。拖动过程中只改内存，见 PlayerViewModel 里的说明。
            onCommitStyle = viewModel::persistDanmakuStyle,
            onCommitSubtitleStyle = viewModel::persistSubtitleStyle,
            onApplyBlockRules = viewModel::applyBlockRules,
            onNudgeOffset = viewModel::nudgeOffset,
            onResetOffset = viewModel::resetOffset,
            onReset = viewModel::resetStyles,
            onDismiss = dismissSettings,
        )
    }
}

/**
 * 画面顶部的提示条。
 *
 * 三种情况会露头：「点开即识别」正在跑（纯提示，不给按钮）、
 * 文件没能识别出弹幕库（给「重试」= 再发一次识别）、弹幕加载失败（给「重试」= 再拉一次）。
 * 其余情况不打扰用户 —— 文档 §11「任何弹幕相关的失败都不能阻塞播放」。
 *
 * **多候选是个例外：一律不露头**（见 [PlayerUiState.ambiguous]）。那种情况多提示一句
 * 只是噪音 —— 用户此刻正要开始看片，而他能做的动作只有「回媒体库手动选」，
 * 不如直接按无弹幕播过去。注意它挡不住：这时 `episodeId == null`，
 * 下面 `noEpisode` 那半边本来就会让提示条弹出来。
 */
@Composable
private fun OverlayBanner(
    state: PlayerUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.ready) return
    if (state.ambiguous) return

    val noEpisode = state.episodeId == null
    // 识别中一定显示；其余只在「未识别」或「弹幕加载失败」时显示
    if (!state.matching && !noEpisode && !state.retryable) return

    Row(
        modifier = modifier
            .background(color = Color(0x99000000), shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = state.danmakuStatus,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
        // 识别中不给重试：请求还在飞，再点一下只会多打一次服务端
        if (state.retryable) {
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/**
 * 顶部：返回、标题与弹幕状态、重新拉取、方向循环。
 *
 * 竖屏（[compact]）整体收紧一档：图标 44dp、标题 13sp、副标题 10sp。
 * 竖屏下这两行文字要和右侧按钮抢宽度，字号不收会把标题挤成省略号。
 *
 * 挖孔内边距加在**渐变的后面**：背景铺满刘海区（否则顶部会缺一条渐变），只把内容推进安全区。
 * 横屏时挖孔在左右两侧，同一行代码顺带把返回按钮从刘海下面挪出来。
 */
@Composable
private fun PlayerTopBar(
    fileName: String,
    subtitle: String,
    orientationMode: OrientationMode,
    compact: Boolean,
    canReloadDanmaku: Boolean,
    onBack: () -> Unit,
    onCycleOrientation: () -> Unit,
    onReloadDanmaku: () -> Unit,
) {
    val iconButtonSize = if (compact) 44.dp else 48.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 4.dp)
            .padding(top = 2.dp, bottom = if (compact) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(iconButtonSize)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                color = Color.White,
                fontSize = if (compact) 13.sp else 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = Color(0xB3FFFFFF),
                fontSize = if (compact) 10.sp else 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onReloadDanmaku,
            enabled = canReloadDanmaku,
            modifier = Modifier.size(iconButtonSize),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "重新拉取弹幕",
                tint = if (canReloadDanmaku) Color.White else Color(0x59FFFFFF),
            )
        }
        IconButton(onClick = onCycleOrientation, modifier = Modifier.size(iconButtonSize)) {
            Icon(
                imageVector = orientationMode.icon,
                contentDescription = orientationMode.description,
                tint = Color.White,
            )
        }
    }
}

/** 底部：时间、进度条、播放控制行。 */
@Composable
private fun PlayerBottomBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubbingFraction: Float?,
    playing: Boolean,
    compact: Boolean,
    danmakuEnabled: Boolean,
    episodeLabel: String?,
    speed: Float,
    speedMenuOpen: Boolean,
    hasNext: Boolean,
    nextEpisodeTitle: String?,
    onScrub: (Float) -> Unit,
    onScrubFinished: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onOpenSettings: () -> Unit,
    onNextEpisode: () -> Unit,
    onSpeedMenuOpenChange: (Boolean) -> Unit,
    onSpeedSelected: (Float) -> Unit,
) {
    // 拖动中显示手指指向的位置，松手后回到播放器的真实位置
    val shownPositionMs = scrubbingFraction?.let { (durationMs * it).toLong() } ?: positionMs
    val playedFraction = if (durationMs > 0L) {
        (shownPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bufferedFraction = if (durationMs > 0L) {
        (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val iconButtonSize = if (compact) 44.dp else 48.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 12.dp)
            .padding(top = if (compact) 16.dp else 24.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTime(shownPositionMs),
                color = Color.White,
                fontSize = if (compact) 11.sp else 12.sp,
            )
            Text(
                text = " / ${formatTime(durationMs)}",
                color = Color(0xB3FFFFFF),
                fontSize = if (compact) 11.sp else 12.sp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = episodeLabel.orEmpty(),
                color = Color(0xB3FFFFFF),
                fontSize = if (compact) 11.sp else 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        PlayerProgressBar(
            playedFraction = playedFraction,
            bufferedFraction = bufferedFraction,
            enabled = durationMs > 0L,
            onScrub = onScrub,
            onScrubFinished = onScrubFinished,
            modifier = Modifier.fillMaxWidth(),
        )

        // 控制行：竖屏下五个控件挤在一行，靠 [compact] 把每项的宽度各收一档
        // （图标 44dp、按钮内边距 8dp、「倍速」二字省掉），否则会互相顶到换行。
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(iconButtonSize)) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White,
                )
            }
            DanmakuToggle(enabled = danmakuEnabled, compact = compact, onClick = onToggleDanmaku)
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(iconButtonSize)) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = "弹幕设置",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            NextEpisodeButton(
                enabled = hasNext,
                nextEpisodeTitle = nextEpisodeTitle,
                compact = compact,
                onClick = onNextEpisode,
            )
            SpeedButton(
                speed = speed,
                expanded = speedMenuOpen,
                compact = compact,
                onExpandedChange = onSpeedMenuOpenChange,
                onSelected = onSpeedSelected,
            )
        }
    }
}

/**
 * 可拖动的进度条。
 *
 * 用 `Canvas` 手绘而不是 Material3 的 `Slider`：后者的轨道/滑块尺寸与内边距是为**表单**设计的，
 * 放进播放器要么过粗、要么靠 `Modifier.scale` 硬缩（连带把触摸区也缩掉）。
 *
 * 触摸区高度 28dp，视觉轨道 3dp —— 手指按得准，画面看着细。
 */
@Composable
private fun PlayerProgressBar(
    playedFraction: Float,
    bufferedFraction: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor = Color(0x59FFFFFF)
    val bufferedColor = Color(0x99FFFFFF)
    val accent = Accent
    val thumbRadius = if (enabled) 6.5f else 0f

    Canvas(
        modifier = modifier
            .height(28.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var fraction = (down.position.x / size.width).coerceIn(0f, 1f)
                    onScrub(fraction)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val next = (change.position.x / size.width).coerceIn(0f, 1f)
                        if (next != fraction) {
                            fraction = next
                            onScrub(fraction)
                        }
                        change.consume()
                    }
                    onScrubFinished(fraction)
                }
            },
    ) {
        val centerY = size.height / 2f
        val barHeight = 3.dp.toPx()
        val radius = thumbRadius.dp.toPx()
        val left = radius
        val barWidth = (size.width - radius * 2f).coerceAtLeast(0f)
        val corner = CornerRadius(barHeight / 2f)

        fun segment(fraction: Float): Size = Size((barWidth * fraction).coerceAtLeast(0f), barHeight)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(left, centerY - barHeight / 2f),
            size = Size(barWidth, barHeight),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = bufferedColor,
            topLeft = Offset(left, centerY - barHeight / 2f),
            size = segment(bufferedFraction.coerceIn(0f, 1f)),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = accent,
            topLeft = Offset(left, centerY - barHeight / 2f),
            size = segment(playedFraction.coerceIn(0f, 1f)),
            cornerRadius = corner,
        )
        if (radius > 0f) {
            drawCircle(
                color = accent,
                radius = radius,
                center = Offset(left + barWidth * playedFraction.coerceIn(0f, 1f), centerY),
            )
        }
    }
}

/** 弹幕开关。用「弹」字方块而不是图标 —— 弹幕这个概念没有通用的图形符号。 */
@Composable
private fun DanmakuToggle(enabled: Boolean, compact: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Accent else Color(0x99FFFFFF)
    TextButton(
        onClick = onClick,
        contentPadding = toggleContentPadding(compact),
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 18.dp else 20.dp)
                .border(width = 1.dp, color = tint, shape = RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "弹", color = tint, fontSize = if (compact) 10.sp else 11.sp)
        }
        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
        Text(
            text = "弹幕",
            color = if (enabled) Color.White else Color(0x99FFFFFF),
            fontSize = if (compact) 12.sp else 13.sp,
        )
    }
}

@Composable
private fun NextEpisodeButton(
    enabled: Boolean,
    nextEpisodeTitle: String?,
    compact: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = toggleContentPadding(compact),
    ) {
        Icon(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = nextEpisodeTitle?.let { "下一集：$it" } ?: "下一集",
            tint = if (enabled) Color.White else Color(0x59FFFFFF),
            modifier = Modifier.size(if (compact) 16.dp else 18.dp),
        )
        Spacer(Modifier.width(if (compact) 2.dp else 4.dp))
        Text(
            text = "下一集",
            color = if (enabled) Color.White else Color(0x59FFFFFF),
            fontSize = if (compact) 12.sp else 13.sp,
        )
    }
}

@Composable
private fun SpeedButton(
    speed: Float,
    expanded: Boolean,
    compact: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (Float) -> Unit,
) {
    Box {
        TextButton(
            onClick = { onExpandedChange(true) },
            contentPadding = toggleContentPadding(compact),
        ) {
            // 紧凑态省掉「倍速」二字：`1.0x` 在播放器语境里没有别的解释，
            // 而竖屏那一行最缺的就是宽度
            Text(
                text = if (compact) formatSpeed(speed) else "倍速 ${formatSpeed(speed)}",
                color = Color.White,
                fontSize = if (compact) 12.sp else 13.sp,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = formatSpeed(option),
                            color = if (option == speed) {
                                Accent
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        onSelected(option)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

/** 底栏那几个文字按钮的内边距：紧凑态左右各收 4dp，一行五个控件才排得下。 */
private fun toggleContentPadding(compact: Boolean): PaddingValues = if (compact) {
    PaddingValues(horizontal = 8.dp, vertical = 4.dp)
} else {
    ButtonDefaults.TextButtonContentPadding
}

/**
 * 播放设置面板（**右侧滑出**）：弹幕与字幕两块。
 *
 * 放在播放页而不是设置页，是为了**即时预览**：拖滑杆时画面上的弹幕/字幕立刻跟着变，
 * 不需要「改完再退出去重进一次」这种反馈闭环。形态照弹弹play —— 贴在屏幕右侧纵向铺满，
 * 画面留在左边，调参时能直接看到效果；比底部弹出的面板少挡画面（横屏下底栏本来就在下半屏）。
 *
 * 字幕只有两项（字号 / 描边），刻意**复用弹幕那套滑杆与开关的形态和配色**：
 * 两块参数是同一类操作，长得一样用户才不用重新学一遍。
 * 但两者的渲染路径完全不同 —— 弹幕是自研 Canvas 引擎，字幕走 `PlayerView` 内置字幕层，
 * 所以选项粒度也不同（弹幕的描边能连续调，字幕只能选有没有）。
 *
 * 三处配套约定，改动前先看一眼：
 * 1. 面板打开时**上下控制栏收起**（见 [PlayerScreen] 里 `visible = controlsVisible && !showSettings`）；
 * 2. 面板打开时**画面手势层停用**（`pointerInput(solidPlayer, showSettings)`），
 *    否则在滑杆上长按会连带触发 3 倍速；
 * 3. 点面板外侧任意位置收起 —— 那层点击层覆盖全屏，因此它必须是最上层，且只能在面板打开时存在。
 *
 * 面板永远盖在画面上，所以配色**不跟随 App 主题**，固定深底白字。
 */
@Composable
private fun PlaySettingsPanel(
    visible: Boolean,
    prefs: DanmakuPreferences,
    subtitlePrefs: SubtitlePreferences,
    blockedCount: Int,
    offsetMs: Long,
    onAlphaChange: (Int) -> Unit,
    onTextScaleChange: (Int) -> Unit,
    onScrollSecondsChange: (Float) -> Unit,
    onStrokeWidthChange: (Float) -> Unit,
    onDisplayAreaChange: (Int) -> Unit,
    onSubtitleTextSizeChange: (Int) -> Unit,
    onSubtitleOutlineChange: (Boolean) -> Unit,
    onCommitStyle: () -> Unit,
    onCommitSubtitleStyle: () -> Unit,
    onApplyBlockRules: (String) -> Unit,
    onNudgeOffset: (Long) -> Unit,
    onResetOffset: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 屏蔽词是「编辑 → 应用」两段式：滑杆要即时反馈，屏蔽词却需要重建时间轴，不适合每敲一个字就跑一次
    var rulesDraft by remember(prefs.blockRulesRaw) { mutableStateOf(prefs.blockRulesRaw) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 横屏给固定宽度（约占 1/3 屏宽），竖屏按比例 —— 竖屏手机上固定 320dp 会占满整块屏幕
        val panelWidth = if (maxWidth > 600.dp) 320.dp else maxWidth * 0.86f

        // 面板外侧的点击层：点一下就收起。未展开时整块不参与测量，也不会挡住下层手势
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x33000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .background(PanelBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("弹幕与字幕", color = Color.White, fontSize = 15.sp)
                    TextButton(
                        onClick = onReset,
                        colors = ButtonDefaults.textButtonColors(contentColor = Accent),
                    ) {
                        Text("重置", fontSize = 13.sp)
                    }
                }

                SectionTitle("弹幕")

                LabeledSlider(
                    label = "透明度",
                    valueText = "${prefs.alphaPercent}%",
                    value = prefs.alphaPercent.toFloat(),
                    range = DanmakuPreferenceCodec.MIN_ALPHA_PERCENT.toFloat()..
                        DanmakuPreferenceCodec.MAX_ALPHA_PERCENT.toFloat(),
                    onChange = { onAlphaChange(it.toInt()) },
                    onChangeFinished = onCommitStyle,
                )

                LabeledSlider(
                    label = "字号",
                    valueText = "${prefs.textScalePercent}%",
                    value = prefs.textScalePercent.toFloat(),
                    range = DanmakuPreferenceCodec.MIN_TEXT_SCALE_PERCENT.toFloat()..
                        DanmakuPreferenceCodec.MAX_TEXT_SCALE_PERCENT.toFloat(),
                    onChange = { onTextScaleChange(it.toInt()) },
                    onChangeFinished = onCommitStyle,
                )

                LabeledSlider(
                    label = "显示区域",
                    valueText = if (prefs.displayAreaPercent >=
                        DanmakuPreferenceCodec.MAX_DISPLAY_AREA_PERCENT
                    ) {
                        "整屏"
                    } else {
                        "${prefs.displayAreaPercent}%"
                    },
                    value = prefs.displayAreaPercent.toFloat(),
                    range = DanmakuPreferenceCodec.MIN_DISPLAY_AREA_PERCENT.toFloat()..
                        DanmakuPreferenceCodec.MAX_DISPLAY_AREA_PERCENT.toFloat(),
                    onChange = { onDisplayAreaChange(it.toInt()) },
                    onChangeFinished = onCommitStyle,
                )

                Text(
                    text = "弹幕只铺在画面上部这块区域里。调小它 → 可用轨道变少 → " +
                        "挤不进来的弹幕会被丢掉，是把「弹幕太密、挡画面」压下去最直接的一项。",
                    color = PanelHint,
                    fontSize = 11.sp,
                )

                LabeledSlider(
                    label = "滚动时长",
                    valueText = "%.1fs".format(prefs.scrollSeconds),
                    value = prefs.scrollSeconds,
                    range = DanmakuPreferenceCodec.MIN_SCROLL_SECONDS..
                        DanmakuPreferenceCodec.MAX_SCROLL_SECONDS,
                    onChange = onScrollSecondsChange,
                    onChangeFinished = onCommitStyle,
                )

                Text(
                    text = "滚动时长越长，弹幕飘得越慢、同屏条数越多。",
                    color = PanelHint,
                    fontSize = 11.sp,
                )

                LabeledSlider(
                    label = "描边宽度",
                    valueText = if (prefs.strokeWidthDp <= 0f) {
                        "实心"
                    } else {
                        "%.1fdp".format(prefs.strokeWidthDp)
                    },
                    value = prefs.strokeWidthDp,
                    range = DanmakuPreferenceCodec.MIN_STROKE_DP..
                        DanmakuPreferenceCodec.MAX_STROKE_DP,
                    onChange = onStrokeWidthChange,
                    onChangeFinished = onCommitStyle,
                )
                Text(
                    text = "黑边是白色弹幕落在亮背景上时的兜底；拖到最左（实心）就只剩文字本身。",
                    color = PanelHint,
                    fontSize = 11.sp,
                )

                SectionDivider()

                // ── 时轴偏移（按集保存）────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("时轴偏移", color = PanelLabel, fontSize = 13.sp)
                    Text(
                        text = formatOffset(offsetMs),
                        color = Accent,
                        fontSize = 13.sp,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onNudgeOffset(-OFFSET_STEP_MS) },
                        modifier = Modifier.weight(1f),
                        colors = PanelButtonColors,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("−0.5s", fontSize = 12.sp) }
                    Button(
                        onClick = { onNudgeOffset(OFFSET_STEP_MS) },
                        modifier = Modifier.weight(1f),
                        colors = PanelButtonColors,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("+0.5s", fontSize = 12.sp) }
                    TextButton(
                        onClick = onResetOffset,
                        colors = ButtonDefaults.textButtonColors(contentColor = Accent),
                    ) { Text("归零", fontSize = 12.sp) }
                }
                Text(
                    text = "偏移量按集保存，下次打开自动生效。",
                    color = PanelHint,
                    fontSize = 11.sp,
                )

                SectionDivider()

                OutlinedTextField(
                    value = rulesDraft,
                    onValueChange = { rulesDraft = it },
                    label = { Text("屏蔽词（一行一条）") },
                    placeholder = { Text("前排\n看过的都懂\n/^\\d+$/  ← 斜杠包裹表示正则") },
                    minLines = 3,
                    maxLines = 6,
                    colors = PanelTextFieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onApplyBlockRules(rulesDraft) },
                        modifier = Modifier.weight(1f),
                        colors = PanelButtonColors,
                    ) {
                        Text("应用屏蔽词", fontSize = 13.sp)
                    }
                    TextButton(
                        onClick = onReset,
                        colors = ButtonDefaults.textButtonColors(contentColor = Accent),
                    ) { Text("恢复默认", fontSize = 12.sp) }
                }

                Text(
                    text = if (blockedCount > 0) {
                        "当前已屏蔽 $blockedCount 条。普通词按「包含」匹配，/斜杠包裹/ 的按正则匹配。"
                    } else {
                        "普通词按「包含」匹配，/斜杠包裹/ 的按正则匹配。"
                    },
                    color = PanelHint,
                    fontSize = 11.sp,
                )

                SectionDivider()

                // ── 字幕（走 PlayerView 内置的字幕层）──────────────────────
                // 与弹幕刻意用同一套滑杆 / 开关形态：都是「调一个显示参数」，长得一样才好上手。
                SectionTitle("字幕")

                LabeledSlider(
                    label = "字号",
                    valueText = SubtitlePreferenceCodec.describeTextSize(subtitlePrefs.textSizeSp),
                    value = subtitlePrefs.textSizeSp.toFloat(),
                    range = SubtitlePreferenceCodec.MIN_TEXT_SIZE_SP.toFloat()..
                        SubtitlePreferenceCodec.MAX_TEXT_SIZE_SP.toFloat(),
                    onChange = { onSubtitleTextSizeChange(it.roundToInt()) },
                    onChangeFinished = onCommitSubtitleStyle,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("描边", color = PanelLabel, fontSize = 13.sp)
                        Text(
                            text = if (subtitlePrefs.outlineEnabled) "细黑边" else "关（纯白字）",
                            color = PanelHint,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = subtitlePrefs.outlineEnabled,
                        onCheckedChange = {
                            onSubtitleOutlineChange(it)
                            onCommitSubtitleStyle()
                        },
                        colors = PanelSwitchColors,
                    )
                }
                Text(
                    text = "字幕固定为白字、无黑底方块（不再跟随字幕文件自带的样式）；" +
                        "描边的粗细由播放器内置字幕层决定，这里只能选有没有。",
                    color = PanelHint,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** 面板内的分组分隔线。 */
@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(PanelDivider),
    )
}

/** 面板内的分组标题（「弹幕」/「字幕」）。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
    )
}

/**
 * 带标题与数值的滑杆（面板内专用，配色固定为深底白字）。
 *
 * [onChangeFinished] 挂在 `Slider` 的 `onValueChangeFinished` 上（松手时才回调），
 * 用来把「拖动中的每帧改动」和「落库」分开 —— 前者只改内存，后者一次写盘。
 */
@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = PanelLabel, fontSize = 13.sp)
            Text(text = valueText, color = Accent, fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Color(0x40FFFFFF),
            ),
        )
    }
}

/**
 * 视频 + 弹幕叠加层。
 *
 * `PlayerView`（内部是 SurfaceView）与 [DanmakuView] 都放在同一个 FrameLayout 里。
 * 由于 SurfaceView 的内容默认位于宿主窗口**之下**，后添加的 DanmakuView 天然绘制在画面之上，
 * 不需要 `setZOrderMediaOverlay` 之类的技巧。
 *
 * 控制层则完全交给上层的 Compose 浮层：`PlayerView` 自带控件已关闭，
 * 它既不受 DanmakuView 遮挡，也不需要在两种坐标系之间做换算。
 *
 * **字幕**由 `PlayerView` 内置的字幕层渲染（`PlayerView.subtitleView`），
 * 所以这里要把 PlayerView 也交出去 —— 只有拿到它才能改字幕字号与描边（见 [applySubtitleStyle]）。
 */
@Composable
private fun VideoSurface(
    player: ExoPlayer,
    onPlayerViewReady: (PlayerView) -> Unit,
    onDanmakuViewReady: (DanmakuView) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                val playerView = PlayerView(ctx).apply {
                    useController = false          // 控制层由 Compose 浮层实现
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
                addView(playerView)
                val danmakuView = DanmakuView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
                addView(danmakuView)
                onPlayerViewReady(playerView)
                onDanmakuViewReady(danmakuView)
            }
        },
    )
}

/**
 * 把字幕样式偏好套到 PlayerView 内置的字幕层上。
 *
 * ### 白字、无黑底
 * `PlayerView` 默认用 `CaptionStyleCompat.DEFAULT`，那是「白字 + **不透明黑底方块**」，
 * 而且 `SubtitleView.applyEmbeddedStyles` 默认是 true —— 字幕文件（ASS/SSA）自带的
 * 字体、字号、颜色乃至黑底会被照单全收。所以「黑底」有两个来源，必须一起关：
 *
 * | 来源 | 关掉的开关 |
 * |---|---|
 * | 字幕文件自带的边缘样式 | `setApplyEmbeddedStyles(false)` |
 * | 字幕文件自带的字号 | `setApplyEmbeddedFontSizes(false)` |
 * | 内置层默认的黑底 | `CaptionStyleCompat` 的背景色设为透明 |
 *
 * 关掉前两项之后，样式才完全由这里说了算；否则调字号滑杆会出现
 * 「有的字幕跟着变、有的不变」这种玄学现象。
 *
 * ### 字号用 sp 而不是「视口高度的百分比」
 * Media3 的 `setFractionalTextSize` 把字号定义成视口高度的一个比例，
 * 同一个数字在手机上是 21sp、在平板上就是 42sp。用户能感知的是「多大号的字」，
 * 所以这里用 `setFixedTextSize(SP, …)`（理由详见 `SubtitlePreferenceCodec`）。
 *
 * ### 描边
 * `CaptionStyleCompat` 只有「哪种边」和「边的颜色」，**没有线宽** ——
 * 内置字幕层的描边粗细是库写死的。所以面板上给它的是一个开关而不是滑杆：
 * 开 = `EDGE_TYPE_OUTLINE` + 黑边，关 = `EDGE_TYPE_NONE`（纯白字）。
 * 想要连续可调的描边，只能像弹幕那样自研渲染层。
 */
private fun PlayerView.applySubtitleStyle(prefs: SubtitlePreferences) {
    val captions = subtitleView ?: return
    captions.setApplyEmbeddedStyles(false)
    captions.setApplyEmbeddedFontSizes(false)
    captions.setStyle(
        // Media3 1.11 起 CaptionStyleCompat 是 6 参数。逐个写清楚，顺序容易记错：
        //   foreground / background / window / edgeType / edgeColor / typeface
        // 内置层默认的那块「黑底」是 **background = BLACK**（见 CaptionStyleCompat.DEFAULT），
        // 所以把 background 与 window 都给透明色，才是真的「白字无黑底」。
        CaptionStyleCompat(
            /* foregroundColor = */ android.graphics.Color.WHITE,
            /* backgroundColor = */ android.graphics.Color.TRANSPARENT,
            /* windowColor = */ android.graphics.Color.TRANSPARENT,
            /* edgeType = */ if (prefs.outlineEnabled) {
                CaptionStyleCompat.EDGE_TYPE_OUTLINE
            } else {
                CaptionStyleCompat.EDGE_TYPE_NONE
            },
            /* edgeColor = */ android.graphics.Color.BLACK,
            /* typeface = */ null,
        ),
    )
    captions.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSizeSp.toFloat())
}

/** 播放器时长：未就绪时 Media3 返回 `C.TIME_UNSET`（负数），统一归零。 */
private fun ExoPlayer.safeDuration(): Long = duration.takeIf { it > 0L } ?: 0L

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** `mm:ss`，超过一小时才带上小时位。 */
private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatSpeed(speed: Float): String {
    val text = when {
        speed % 1f == 0f -> "%.1f"
        speed * 10f % 1f == 0f -> "%.1f"
        else -> "%.2f"
    }
    return String.format(Locale.US, text, speed) + "x"
}

private fun formatOffset(offsetMs: Long): String {
    val sign = if (offsetMs > 0) "+" else if (offsetMs < 0) "-" else ""
    val abs = kotlin.math.abs(offsetMs)
    return "$sign%.1fs".format(abs / 1000.0)
}

/** 横滑浮层上的位移文案，如「+00:35」「-02:10」「原位」。 */
private fun formatDelta(deltaMs: Long): String = when {
    deltaMs == 0L -> "原位"
    deltaMs > 0L -> "+${formatTime(deltaMs)}"
    else -> "-${formatTime(-deltaMs)}"
}
