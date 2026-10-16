package com.solidplayer.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solidplayer.core.danmaku.model.DanmakuConfig
import com.solidplayer.core.danmaku.model.DanmakuTimeline
import com.solidplayer.core.data.credential.DandanplayCredentialRepository
import com.solidplayer.core.data.db.MatchType
import com.solidplayer.core.data.db.MediaFileEntity
import com.solidplayer.core.data.network.ApiResult
import com.solidplayer.core.data.preference.DanmakuPreferenceCodec
import com.solidplayer.core.data.preference.DanmakuPreferenceStore
import com.solidplayer.core.data.preference.DanmakuPreferences
import com.solidplayer.core.data.preference.SubtitlePreferenceCodec
import com.solidplayer.core.data.preference.SubtitlePreferenceStore
import com.solidplayer.core.data.preference.SubtitlePreferences
import com.solidplayer.core.data.repository.DanmakuRepository
import com.solidplayer.core.data.repository.MatchRepository
import com.solidplayer.core.data.repository.MediaRepository
import com.solidplayer.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 连播列表里的一项。 */
data class PlaylistEntry(
    val mediaFileId: Long,
    /** 集数，可能是 `C1` / `O1` 这类非纯数字；取不到就是 null */
    val episodeNumber: String?,
    /** 展示用标题：优先剧集名，退化为文件名 */
    val title: String,
)

data class PlayerUiState(
    val ready: Boolean = false,
    val fileName: String = "",
    val uri: String? = null,
    val episodeId: Long? = null,
    val startPositionMs: Long = 0L,
    /** 弹幕样式偏好（全局，来自 DataStore）。开关也在这里面 —— P2 起是持久的。 */
    val danmakuPrefs: DanmakuPreferences = DanmakuPreferences(),
    /** 字幕样式偏好（全局，来自 DataStore）。作用于 PlayerView 内置的字幕层。 */
    val subtitlePrefs: SubtitlePreferences = SubtitlePreferences(),
    val offsetMs: Long = 0L,
    /** 服务端返回的弹幕条数 */
    val danmakuCount: Int = 0,
    /** 被屏蔽词挡掉的条数 */
    val blockedCount: Int = 0,
    val danmakuStatus: String = "正在准备…",
    val timeline: DanmakuTimeline? = null,
    /** 弹幕加载失败（可重试）—— 顶部提示条据此显示「重试」按钮 */
    val retryable: Boolean = false,
    /** 当前倍速。不持久化：它是「这一次观看」的参数，不是偏好 */
    val speed: Float = 1f,
    /**
     * 「点开即识别」正在进行 —— 只在文件还没有 `episodeId` 时可能为 true。
     *
     * 与 [retryable] 分开：识别中不该出现「重试」按钮（请求还在飞），
     * 而识别失败后必须给（否则用户只能退回媒体库）。
     */
    val matching: Boolean = false,
    /**
     * 识别**有结果、但定不下来**是哪一集（服务端返回了多个候选）。
     *
     * 与 [retryable] 分开：这里再重试一遍还是那几条候选，候选之间没有先后可言，
     * 所以既不该提示、也不该给按钮 —— 顶部提示条遇到它直接不显示（见 `PlayerScreen.OverlayBanner`），
     * 按「无弹幕」静默降级。用户要匹配得自己回媒体库手动选（文档 §23）。
     */
    val ambiguous: Boolean = false,
    /** 同番剧连播列表（按集数排序，含当前文件）；未识别出番剧时为 null */
    val playlist: List<PlaylistEntry>? = null,
    /** 当前文件在连播列表里的下标；-1 表示不在列表里（未识别） */
    val currentIndex: Int = -1,
    val message: String? = null,
) {
    val danmakuEnabled: Boolean get() = danmakuPrefs.enabled

    val danmakuConfig: DanmakuConfig get() = danmakuPrefs.config

    val nextEpisodeId: Long? get() = playlist?.getOrNull(currentIndex + 1)?.mediaFileId

    /** 下一集的标题，用作「下一集」按钮的无障碍描述。 */
    val nextEpisodeTitle: String? get() = playlist?.getOrNull(currentIndex + 1)?.title

    val prevEpisodeId: Long? get() = playlist?.getOrNull(currentIndex - 1)?.mediaFileId

    /** 「第 7 集 / 共 12 集」；没有列表或集数解析不出来时为 null。 */
    val episodeLabel: String?
        get() {
            val list = playlist ?: return null
            val number = list.getOrNull(currentIndex)?.episodeNumber ?: return null
            if (number.isBlank()) return null
            return "第 $number 集 / 共 ${list.size} 集"
        }
}

/**
 * 播放页（文档 §9.1）。
 *
 * 关键设计：**播放器启动不等待弹幕拉取**。
 * 第 6 步启动播放器与第 7 步拉弹幕是并行的，弹幕晚到就晚渲染，绝不阻塞起播；
 * 网络慢的时候用户先看到画面，弹幕随后出现。
 *
 * 识别（`match`）也走同一条原则 —— **点开即识别**（见 [identifyAndLoad]）：
 * 全机扫描只做本地入库、一个请求都不发，用户真正点开某个文件时才为它发一次识别。
 *
 * P2 起弹幕样式（透明度 / 字号 / 滚动速度 / 开关 / 屏蔽词）统一从
 * [DanmakuPreferenceStore] 派生，改完立即作用于当前画面，同时成为下次打开的默认值。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val danmakuRepository: DanmakuRepository,
    private val matchRepository: MatchRepository,
    private val credentialRepository: DandanplayCredentialRepository,
    private val preferenceStore: DanmakuPreferenceStore,
    private val subtitleStore: SubtitlePreferenceStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 可变的：支持「下一集」原地换源，不重建页面（见 [switchEpisode]）。 */
    private var mediaFileId: Long = savedStateHandle.get<Long>(Routes.PLAYER_ARG_ID) ?: 0L

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferenceStore.preferences.collect { prefs ->
                _state.update { it.copy(danmakuPrefs = prefs) }
            }
        }
        viewModelScope.launch {
            subtitleStore.preferences.collect { prefs ->
                _state.update { it.copy(subtitlePrefs = prefs) }
            }
        }
        loadMedia()
    }

    private fun loadMedia() {
        viewModelScope.launch {
            // ★ 先钉住本次要加载的文件：识别/拉弹幕都要跨网络，期间用户可能已经
            // switchEpisode 换成下一集了。所有状态写回都要拿它跟当前 mediaFileId 比一次。
            val target = mediaFileId
            val entity = mediaRepository.findById(target)
            if (entity == null) {
                _state.update { it.copy(ready = false, message = "找不到该媒体记录") }
                return@launch
            }

            val offset = danmakuRepository.danmakuOffset(target)
            val startPosition = danmakuRepository.playPosition(target)
            val playlist = loadPlaylist(entity)

            _state.update {
                it.copy(
                    ready = true,
                    fileName = entity.displayName,
                    uri = entity.uri,
                    episodeId = entity.episodeId,
                    startPositionMs = startPosition,
                    offsetMs = offset,
                    playlist = playlist,
                    currentIndex = playlist?.indexOfFirst { entry -> entry.mediaFileId == target } ?: -1,
                    retryable = false,
                    matching = entity.episodeId == null,
                    ambiguous = false,
                    danmakuStatus = if (entity.episodeId == null) "正在识别弹幕库…" else "正在加载弹幕…",
                )
            }

            // NEW 角标「点开即消」：进页就算打开过。写库会让媒体库的 Room Flow 重发，
            // 于是详情页行内的 NEW 与首页文件夹角标会自动各减一个。
            //
            // 放在这里而不是 onDispose：onDispose 在「下一集」原地换源时**不会触发**，
            // 而切过去的那一集同样已经被用户打开了。
            mediaRepository.markViewed(entity.id)

            val episodeId = entity.episodeId
            if (episodeId != null) {
                loadTimeline(episodeId)
            } else {
                identifyAndLoad(entity)
            }
        }
    }

    // ── 点开即识别 ───────────────────────────────────────────────

    /**
     * 文件还没识别过时，为**这一个**文件发一次 `match` 请求，成功就接着拉弹幕。
     *
     * ### 为什么识别放在这里，而不是扫描时批量做
     * 扫描是纯本地动作，一次全机扫描可能有几百个文件；批量识别会把它们全部打给
     * 弹弹play（官方明确会对调用量大的来源限流拦截，文档 §10）。
     * 而用户实际会看的只是其中几个 —— 「点开才识别」把请求量收敛到真实使用量，
     * 代价只是打开未识别文件的**第一次**多等一次往返。
     *
     * 与媒体库的关系：这里只处理**能自动确定**的情况（唯一候选）。
     * 多候选 / 无结果一律**不猜** —— 猜错的代价是弹幕全错位，比没有弹幕糟得多。
     * 但两者的**打扰程度**不同，见下面 `pick == null` 分支里的说明。
     *
     * 凭据缺失时**一个请求都不发**，直接按无弹幕降级（约束：缺凭据不阻塞播放）。
     */
    private suspend fun identifyAndLoad(entity: MediaFileEntity) {
        val target = entity.id

        if (!credentialRepository.isConfigured.first()) {
            updateIfCurrent(target) {
                it.copy(
                    matching = false,
                    retryable = true,
                    danmakuStatus = "尚未配置弹弹play 凭据，本次按无弹幕播放",
                )
            }
            return
        }

        // `matchByHashAndName` 里「算前 16MB 哈希」是有 (uri,size,lastModified) 缓存的，
        // 所以同一个文件第二次点开不会再读一遍 16MB（文档 §5）。
        val outcome = when (val result = matchRepository.matchByHashAndName(mediaRepository.toEntry(entity))) {
            is ApiResult.Failure -> {
                val text = describe(result)
                updateIfCurrent(target) { it.copy(matching = false, retryable = true, danmakuStatus = text, message = text) }
                return
            }

            is ApiResult.Success -> result.data
        }

        val pick = outcome.autoBind
        if (pick == null) {
            // 「定不下来」还要再分两种，它们对用户的意义完全不同：
            //
            //   0 候选 —— 可能是文件名太不规范，也可能只是一次网络抖动。
            //             仍然值得说一句、并保留「重试」（提示条 + 按钮）。
            //
            //   多候选 —— 服务端明确一次给了好几条，再点一次「重试」还是这几条。
            //             提示与按钮都只会变成噪音，而且用户此刻正要开始看片，
            //             所以**静默按无弹幕降级**：不提示、不给重试、顶栏也只写「无弹幕」。
            //             要匹配得自己回媒体库手动选（文档 §23）。
            if (outcome.candidates.isEmpty()) {
                updateIfCurrent(target) {
                    it.copy(
                        matching = false,
                        retryable = true,
                        ambiguous = false,
                        danmakuStatus = "未找到匹配的弹幕库 · 可在媒体库手动匹配",
                    )
                }
            } else {
                updateIfCurrent(target) {
                    it.copy(
                        matching = false,
                        retryable = false,
                        ambiguous = true,
                        danmakuStatus = "无弹幕",
                    )
                }
            }
            return
        }

        val episodeId = pick.episodeId
        if (episodeId <= 0L) {
            updateIfCurrent(target) {
                it.copy(matching = false, retryable = true, ambiguous = false, danmakuStatus = "匹配结果异常，无弹幕播放")
            }
            return
        }

        // 自动绑定（非 manual）——用户后续在媒体库里手动改过就再也不会被覆盖（文档 §7.2）。
        matchRepository.persistMatch(mediaFileId = target, result = pick, matchType = MatchType.HASH)
        updateIfCurrent(target) {
            it.copy(
                episodeId = episodeId,
                matching = false,
                ambiguous = false,
                retryable = false,
                danmakuStatus = "正在加载弹幕…",
            )
        }
        loadTimeline(episodeId)
    }


    // ── 连播（「下一集」）──────────────────────────────────────────

    /**
     * 拼连播列表：同番剧下已识别的文件，按集数排序。
     *
     * 未识别出番剧（`animeId`/`episodeId` 为空）时返回 null ——
     * 这时「下一集」无从谈起，UI 直接把按钮置灰，而不是猜一个顺序。
     */
    private suspend fun loadPlaylist(entity: MediaFileEntity): List<PlaylistEntry>? {
        val animeId = entity.animeId ?: return null
        if (entity.episodeId == null) return null

        return mediaRepository.siblingsOf(animeId).map { display ->
            PlaylistEntry(
                mediaFileId = display.file.id,
                episodeNumber = display.episodeNumber,
                title = display.episodeTitle?.takeIf { it.isNotBlank() } ?: display.file.displayName,
            )
        }
    }

    /**
     * 切到另一集（「下一集」按钮 / 播完自动连播）。
     *
     * **刻意不走导航**：复用同一个 PlayerScreen 与同一个 `ExoPlayer` 实例。
     * 如果改成 `navigate(player/{id})`，每次切集都会把播放器与弹幕引擎重建一遍 ——
     * 表现为大约几百毫秒的黑屏，以及方向与系统栏的重设闪烁。
     *
     * 代价：导航参数 `player/{mediaFileId}` 会停留在用户最初点进来的那一集，
     * 若此刻发生进程重建会从那一集开始。进度本身按文件存库，不会丢。
     */
    fun switchEpisode(mediaFileId: Long) {
        if (mediaFileId == this.mediaFileId) return
        this.mediaFileId = mediaFileId

        // 先把状态清成「准备中」，让 UI 立刻退出上一集的弹幕与进度；
        // uri 置空会让播放侧的 LaunchedEffect 等新 uri 到位后再起播
        _state.update {
            it.copy(
                ready = false,
                uri = null,
                episodeId = null,
                startPositionMs = 0L,
                timeline = null,
                danmakuCount = 0,
                blockedCount = 0,
                retryable = false,
                matching = false,
                ambiguous = false,
                danmakuStatus = "正在准备…",
                message = null,
            )
        }
        loadMedia()
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed.coerceIn(MIN_SPEED, MAX_SPEED)) }
    }

    // ── 弹幕加载 ─────────────────────────────────────────────────

    fun reloadDanmaku() {
        _state.value.episodeId?.let { loadTimeline(it, forceRefresh = true) }
    }

    /**
     * 重试。
     *
     * 两条路径分开处理，因为它们失败的原因根本不同：
     * - **有 `episodeId`**：失败的是「拉弹幕」（网络抖动）。走缓存优先的正常路径，
     *   不强制刷新 —— 上一次失败时本地缓存往往已经写好了，直接读缓存比再打一次服务端
     *   更划算，也顺带少消耗一次配额（文档 §10）。
     * - **没有 `episodeId`**：失败的是「识别」本身。重试 = 再发一次 `match`。
     */
    fun retryDanmaku() {
        val episodeId = _state.value.episodeId
        if (episodeId != null) {
            loadTimeline(episodeId)
            return
        }

        val target = mediaFileId
        _state.update {
            it.copy(
                matching = true,
                retryable = false,
                ambiguous = false,
                danmakuStatus = "正在识别弹幕库…",
            )
        }
        viewModelScope.launch {
            val entity = mediaRepository.findById(target) ?: return@launch
            identifyAndLoad(entity)
        }
    }

    private fun loadTimeline(episodeId: Long, forceRefresh: Boolean = false) {
        val target = mediaFileId
        viewModelScope.launch {
            when (val result = danmakuRepository.loadTimeline(episodeId, forceRefresh)) {
                is ApiResult.Success -> {
                    val data = result.data
                    updateIfCurrent(target) {
                        it.copy(
                            timeline = data.timeline,
                            danmakuCount = data.count,
                            blockedCount = data.blocked,
                            danmakuStatus = describeLoad(data.count, data.blocked, data.fromCache),
                            retryable = false,
                        )
                    }
                }

                is ApiResult.Failure -> updateIfCurrent(target) {
                    it.copy(
                        timeline = DanmakuTimeline.EMPTY,
                        danmakuCount = 0,
                        blockedCount = 0,
                        danmakuStatus = "无弹幕播放",
                        retryable = true,
                        message = describe(result),
                    )
                }
            }
        }
    }

    /**
     * 只在「这次异步结果对应的文件仍然是当前播放的文件」时才写状态。
     *
     * 识别与拉弹幕都要跨网络，用户完全可能在等待期间按了「下一集」——
     * 迟到的结果若照写不误，下一集的开头就会短暂出现上一集的弹幕与进度。
     */
    private inline fun updateIfCurrent(mediaFileId: Long, transform: (PlayerUiState) -> PlayerUiState) {
        if (mediaFileId != this.mediaFileId) return
        _state.update(transform)
    }

    private fun describeLoad(count: Int, blocked: Int, fromCache: Boolean): String = when {
        count == 0 -> "该剧集暂无弹幕"
        else -> buildString {
            append("弹幕 $count 条")
            if (blocked > 0) append("（已屏蔽 $blocked 条）")
            if (fromCache) append(" · 本地缓存")
        }
    }

    // ── 弹幕样式（P2）────────────────────────────────────────────
    //
    // 三个样式参数走「先改内存、松手再落库」两段式：
    //
    // 1. `Slider` 是**完全受控**的组件 —— 拖动时它只把新值通过 `onValueChange` 抛出来，
    //    自己不记住（Material3 的 `dispatchRawDelta` 里，只要 `onValueChange != null`
    //    就不写内部 `value`）。所以「改一下 → 写 DataStore → 等 Flow 绕一圈回来 → 界面刷新」
    //    这种写法会让滑杆要等一次磁盘往返才动，拖起来发滞；更糟的是只要链路里任何一环
    //    没把值原样写回去，滑杆就**一动不动**。
    // 2. 因此这里同步更新 `_state`（当帧就能看到滑杆跟手 + 弹幕即时预览），
    //    真正的持久化留到 [`persistDanmakuStyle`]，由滑杆的 `onValueChangeFinished` 触发。

    fun toggleDanmaku() {
        val enabled = _state.value.danmakuEnabled
        viewModelScope.launch { preferenceStore.setEnabled(!enabled) }
    }

    fun setAlphaPercent(percent: Int) {
        val value = percent.coerceIn(
            DanmakuPreferenceCodec.MIN_ALPHA_PERCENT,
            DanmakuPreferenceCodec.MAX_ALPHA_PERCENT,
        )
        _state.update { it.copy(danmakuPrefs = it.danmakuPrefs.copy(alphaPercent = value)) }
    }

    fun setTextScalePercent(percent: Int) {
        val value = percent.coerceIn(
            DanmakuPreferenceCodec.MIN_TEXT_SCALE_PERCENT,
            DanmakuPreferenceCodec.MAX_TEXT_SCALE_PERCENT,
        )
        _state.update { it.copy(danmakuPrefs = it.danmakuPrefs.copy(textScalePercent = value)) }
    }

    fun setScrollSeconds(seconds: Float) {
        val value = if (seconds.isNaN()) {
            DanmakuPreferenceCodec.DEFAULT_SCROLL_SECONDS
        } else {
            seconds.coerceIn(
                DanmakuPreferenceCodec.MIN_SCROLL_SECONDS,
                DanmakuPreferenceCodec.MAX_SCROLL_SECONDS,
            )
        }
        _state.update { it.copy(danmakuPrefs = it.danmakuPrefs.copy(scrollSeconds = value)) }
    }

    /**
     * 描边宽度（dp），0 = 实心。
     *
     * 内存里保留滑杆抛来的连续值（拖动跟手），**量化到 0.1dp 只在落库时做**
     * （见 [DanmakuPreferenceStore.setStyle]）—— 否则拖到手感会一格一格跳。
     */
    fun setStrokeWidthDp(dp: Float) {
        val value = if (dp.isNaN()) {
            DanmakuPreferenceCodec.DEFAULT_STROKE_DP
        } else {
            dp.coerceIn(
                DanmakuPreferenceCodec.MIN_STROKE_DP,
                DanmakuPreferenceCodec.MAX_STROKE_DP,
            )
        }
        _state.update { it.copy(danmakuPrefs = it.danmakuPrefs.copy(strokeWidthDp = value)) }
    }

    /**
     * 弹幕显示区域（画面高度的百分比，自顶部起算）。
     *
     * 拖动时只改内存 → `DanmakuConfig.displayAreaRatio` 变 → 引擎按新区域重建轨道并重置，
     * 画面上的弹幕密度**当帧就能看到变化**（与其他样式滑杆同一套「内存先行」套路）。
     */
    fun setDisplayAreaPercent(percent: Int) {
        val value = percent.coerceIn(
            DanmakuPreferenceCodec.MIN_DISPLAY_AREA_PERCENT,
            DanmakuPreferenceCodec.MAX_DISPLAY_AREA_PERCENT,
        )
        _state.update { it.copy(danmakuPrefs = it.danmakuPrefs.copy(displayAreaPercent = value)) }
    }

    /**
     * 把当前样式整体写回 DataStore。
     *
     * 由弹幕设置面板在**滑杆松手**与**面板关闭**时调用 —— 拖动过程中每帧写盘没有意义，
     * 反而会把 DataStore 的写队列堆满。读的是 `_state` 里的最新值，
     * 所以最后停在哪一格就存哪一格。
     */
    fun persistDanmakuStyle() {
        val prefs = _state.value.danmakuPrefs
        viewModelScope.launch {
            preferenceStore.setStyle(
                alphaPercent = prefs.alphaPercent,
                textScalePercent = prefs.textScalePercent,
                scrollSeconds = prefs.scrollSeconds,
                strokeWidthDp = prefs.strokeWidthDp,
                displayAreaPercent = prefs.displayAreaPercent,
            )
        }
    }

    /**
     * 应用屏蔽词。
     *
     * 屏蔽词走的是**解析期**过滤，所以改完必须重建时间轴 ——
     * 但只是重读本地那份原始 JSON，不发任何网络请求（见 `DanmakuRepository.buildTimeline`）。
     */
    fun applyBlockRules(raw: String) {
        val episodeId = _state.value.episodeId
        viewModelScope.launch {
            preferenceStore.setBlockRules(raw)
            episodeId?.let { loadTimeline(it) }
        }
    }

    // ── 字幕样式 ─────────────────────────────────────────────────
    //
    // 与弹幕样式同一条约定：**内存先行、松手落库**（见上面的长注释）。
    // 字幕由 PlayerView 内置的字幕层渲染，改完立刻反映到当前画面。

    fun setSubtitleTextSizeSp(sp: Int) {
        val value = SubtitlePreferenceCodec.normalizeTextSizeSp(sp)
        _state.update { it.copy(subtitlePrefs = it.subtitlePrefs.copy(textSizeSp = value)) }
    }

    fun setSubtitleOutlineEnabled(enabled: Boolean) {
        _state.update { it.copy(subtitlePrefs = it.subtitlePrefs.copy(outlineEnabled = enabled)) }
    }

    /** 把当前字幕样式整体写回 DataStore（滑杆松手 / 切换开关 / 面板关闭时调用）。 */
    fun persistSubtitleStyle() {
        val prefs = _state.value.subtitlePrefs
        viewModelScope.launch {
            subtitleStore.setStyle(
                textSizeSp = prefs.textSizeSp,
                outlineEnabled = prefs.outlineEnabled,
            )
        }
    }

    /** 恢复默认：弹幕与字幕一起回到默认值（面板只有一个「重置」入口）。 */
    fun resetStyles() {
        viewModelScope.launch {
            preferenceStore.reset()
            subtitleStore.reset()
        }
    }

    // ── 时轴偏移（文档 §8.6）─────────────────────────────────────

    /** UI 手动微调 ±0.5s 后写入 `play_history.danmaku_offset_ms`，下次打开自动生效。 */
    fun nudgeOffset(deltaMs: Long) {
        val target = mediaFileId
        val newOffset = _state.value.offsetMs + deltaMs
        _state.update { it.copy(offsetMs = newOffset) }
        viewModelScope.launch { danmakuRepository.setDanmakuOffset(target, newOffset) }
    }

    fun resetOffset() {
        val target = mediaFileId
        _state.update { it.copy(offsetMs = 0L) }
        viewModelScope.launch { danmakuRepository.setDanmakuOffset(target, 0L) }
    }

    /**
     * 落库播放进度。
     *
     * **播完的文件记为「未开始」**：否则连播到下一集后，用户再点开这一集
     * 会直接跳到片尾 —— 断点续播在这种场景下反而是反效果。
     */
    fun savePosition(positionMs: Long, durationMs: Long) {
        if (positionMs <= 0L) return

        // ★ 必须在 launch **之前**捕获：协程体真正执行时 mediaFileId 可能已经被
        // switchEpisode 改成下一集了，那样这一集的进度就会写进下一集的记录
        val target = mediaFileId
        val position = if (durationMs > 0L && positionMs >= durationMs - FINISH_TOLERANCE_MS) {
            0L
        } else {
            positionMs
        }
        viewModelScope.launch {
            danmakuRepository.savePlayPosition(target, position, durationMs)
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun describe(failure: ApiResult.Failure): String = when {
        failure.isCredentialMissing -> "尚未配置弹弹play 凭据，本次按无弹幕播放"
        failure.isAuthFailure -> "AppId 或 AppSecret 有误，请前往设置检查"
        else -> "拉取弹幕失败：${failure.message}，已按无弹幕播放"
    }

    private companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2f

        /** 距片尾这么近就认为「已播完」。留一点余量，避免拖动到 99.9% 被误判成未看完。 */
        const val FINISH_TOLERANCE_MS = 3_000L
    }
}
