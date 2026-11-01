package com.solidplayer.ui.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solidplayer.core.data.db.MatchType
import com.solidplayer.core.data.db.MediaFileEntity
import com.solidplayer.core.data.credential.DandanplayCredentialRepository
import com.solidplayer.core.data.network.ApiResult
import com.solidplayer.core.data.network.dto.MatchResultV2
import com.solidplayer.core.data.network.dto.SearchEpisodeDetails
import com.solidplayer.core.data.network.dto.SearchEpisodesAnime
import com.solidplayer.core.data.parser.FileNameParser
import com.solidplayer.core.data.repository.DeleteOriginalFileResult
import com.solidplayer.core.data.repository.LibraryIdentifyCoordinator
import com.solidplayer.core.data.repository.MatchOutcome
import com.solidplayer.core.data.repository.MatchRepository
import com.solidplayer.core.data.repository.MediaRepository
import com.solidplayer.core.data.repository.VideoLibraryRepository
import com.solidplayer.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderDetailUiState(
    val folderPath: String = "",
    val folderName: String = "",
    val videos: List<MediaFileEntity> = emptyList(),
    val credentialsConfigured: Boolean = false,
    /** 下拉刷新触发的全机重扫正在进行（列表外只显示一条进度线） */
    val scanning: Boolean = false,
    val matching: Boolean = false,
    val identify: LibraryIdentifyCoordinator.Progress = LibraryIdentifyCoordinator.Progress(),
    val dialog: LibraryDialog? = null,
    val message: String? = null,
) {
    /** 本目录里还没识别的（手动绑定的不算） */
    val pendingCount: Int
        get() = videos.count { it.episodeId == null && it.matchType != MatchType.MANUAL }

    val busy: Boolean get() = scanning || matching || identify.running
}

/**
 * 文件夹详情页（图二）：一个目录下的视频列表 + 逐文件识别。
 *
 * 识别逻辑整体沿用 P0/P1 的三条路径（哈希自动识别 / 手填文件名 / 手动搜索），
 * 只是把「当前文件」的载体从「全库列表」换成了「本目录列表」。
 * 批量识别交给应用级的 [LibraryIdentifyCoordinator]，切页不中断。
 */
@HiltViewModel
class FolderDetailViewModel @Inject constructor(
    private val repository: VideoLibraryRepository,
    private val mediaRepository: MediaRepository,
    private val matchRepository: MatchRepository,
    private val credentialRepository: DandanplayCredentialRepository,
    private val coordinator: LibraryIdentifyCoordinator,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * 路由参数是 `Uri.encode` 过的（目录路径里有 `/`，不编码会被当成路由分隔符）。
     * 这里解回来 —— Navigation 是否已经解过一次取决于版本，对不含 `%` 的常规路径两者等价。
     */
    val folderPath: String = Uri.decode(
        savedStateHandle.get<String>(Routes.FOLDER_ARG_PATH).orEmpty(),
    )

    private val _state = MutableStateFlow(FolderDetailUiState(folderPath = folderPath))
    val state: StateFlow<FolderDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeFolderVideos(folderPath).collect { videos ->
                _state.update {
                    it.copy(
                        videos = videos,
                        folderName = videos.firstOrNull()?.folderName
                            ?: folderPath.substringAfterLast('/').ifBlank { "文件夹" },
                    )
                }
            }
        }
        viewModelScope.launch {
            credentialRepository.isConfigured.collect { configured ->
                _state.update { it.copy(credentialsConfigured = configured) }
            }
        }
        viewModelScope.launch {
            coordinator.progress.collect { progress ->
                _state.update { it.copy(identify = progress, message = progress.message ?: it.message) }
                if (progress.message != null) coordinator.consumeMessage()
            }
        }
    }

    // ── 本目录批量识别 ────────────────────────────────────────────

    fun identifyFolder() {
        if (!requireCredentials()) return
        val ids = _state.value.videos
            .filter { it.episodeId == null && it.matchType != MatchType.MANUAL }
            .map { it.id }
        if (ids.isEmpty()) {
            _state.update { it.copy(message = "本目录没有待识别的文件") }
            return
        }
        coordinator.identifyFiles(ids)
    }

    fun cancelIdentify() = coordinator.cancel()

    // ── 下拉刷新（重扫全机）────────────────────────────────────────

    /**
     * 下拉刷新：重扫全机，再让当前目录的列表跟着更新。
     *
     * 刻意**不做「只扫本目录」的捷径**：扫描的产物是「本机有哪些含视频的文件夹」，
     * 只扫一个目录并不能回答「这个目录里新加进来的文件是不是已经入库了」之外的任何问题，
     * 却要额外维护一套「局部扫描不得删除其它目录的失效行」的规则。
     * 复用首页同一个入口，行为一致、也不会漏掉「文件被移到别的目录」这种情况。
     *
     * 这里是**手动**入口，与首页的「进入软件自动扫一次」是两回事：
     * 本页不自动触发扫描（见 `VideoLibraryRepository.claimAutoScan` 的说明）。
     */
    fun refresh() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, message = null) }
        viewModelScope.launch {
            val text = runCatching { repository.refresh() }.fold(
                onSuccess = { summary ->
                    buildString {
                        append("扫描完成：${summary.folders} 个文件夹，${summary.videos} 个视频")
                        if (summary.removed > 0) append("，清理 ${summary.removed} 条失效记录")
                    }
                },
                onFailure = { "扫描失败：${it.message ?: it.javaClass.simpleName}" },
            )
            _state.update { it.copy(scanning = false, message = text) }
        }
    }

    // ── 删除原文件 ────────────────────────────────────────────────

    /** 先弹确认框 —— 删的是磁盘上的真实视频，不可恢复，不能只凭菜单一点就删。 */
    fun askDeleteOriginal(fileId: Long) {
        val entity = _state.value.videos.firstOrNull { it.id == fileId } ?: return
        _state.update {
            it.copy(dialog = LibraryDialog.DeleteOriginal(fileId = fileId, fileName = entity.displayName))
        }
    }

    /** 确认删除：先关弹窗，再真正删盘；结果用一次性 snackbar 告知，行会随 Room Flow 自动消失。 */
    fun confirmDeleteOriginal() {
        val dialog = _state.value.dialog as? LibraryDialog.DeleteOriginal ?: return
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            when (val result = repository.deleteOriginalFile(dialog.fileId)) {
                DeleteOriginalFileResult.Deleted ->
                    _state.update { it.copy(message = "已删除：${dialog.fileName}") }

                is DeleteOriginalFileResult.Failed ->
                    _state.update { it.copy(message = "删除失败：${result.message}") }
            }
        }
    }

    // ── 单文件识别（P0 路径）──────────────────────────────────────

    fun openIdentify(fileId: Long) {
        val entity = _state.value.videos.firstOrNull { it.id == fileId } ?: return
        _state.update {
            it.copy(
                dialog = LibraryDialog.Identify(
                    fileId = fileId,
                    fileName = entity.displayName,
                    manualName = entity.displayName,
                ),
            )
        }
    }

    fun onManualNameChange(value: String) {
        val dialog = _state.value.dialog as? LibraryDialog.Identify ?: return
        _state.update { it.copy(dialog = dialog.copy(manualName = value)) }
    }

    /** 「自动识别」：上传前 16MB 哈希 + 文件名（`matchMode = hashAndFileName`）。 */
    fun runAutoMatch() {
        val dialog = _state.value.dialog as? LibraryDialog.Identify ?: return
        val entity = _state.value.videos.firstOrNull { it.id == dialog.fileId } ?: return
        launchMatch(dialog.fileId, manual = false) {
            matchRepository.matchByHashAndName(mediaRepository.toEntry(entity))
        }
    }

    /** 「按手填名字匹配」：不带哈希，`matchMode = fileNameOnly`，识别结果记为 manual。 */
    fun runManualMatch() {
        val dialog = _state.value.dialog as? LibraryDialog.Identify ?: return
        val name = dialog.manualName.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(message = "请先输入文件名") }
            return
        }
        launchMatch(dialog.fileId, manual = true) { matchRepository.matchByFileName(name) }
    }

    private fun launchMatch(fileId: Long, manual: Boolean, request: suspend () -> ApiResult<MatchOutcome>) {
        _state.update { it.copy(matching = true) }
        viewModelScope.launch {
            when (val result = request()) {
                is ApiResult.Failure -> _state.update {
                    it.copy(matching = false, message = describeFailure(result))
                }

                is ApiResult.Success -> {
                    val outcome = result.data
                    val pick = outcome.autoBind
                    when {
                        // 精确关联：直接自动绑定，不弹窗打扰（文档 §4.2）
                        // 判据与播放页的「点开即识别」共用 MatchOutcome.autoBind
                        pick != null -> {
                            bind(fileId, pick, manual, MatchType.HASH)
                            _state.update {
                                it.copy(matching = false, dialog = null, message = "已自动匹配成功")
                            }
                        }

                        outcome.candidates.isNotEmpty() -> _state.update {
                            it.copy(
                                matching = false,
                                dialog = LibraryDialog.Candidates(fileId, outcome.candidates, manual),
                            )
                        }

                        else -> _state.update {
                            it.copy(
                                matching = false,
                                dialog = null,
                                message = "未找到匹配结果，可试试「手动搜索」，或把文件名改成规范形式（如「进击的巨人 - 07」）",
                            )
                        }
                    }
                }
            }
        }
    }

    fun selectCandidate(candidate: MatchResultV2) {
        val dialog = _state.value.dialog as? LibraryDialog.Candidates ?: return
        bind(
            fileId = dialog.fileId,
            result = candidate,
            manual = dialog.manual,
            autoMatchType = MatchType.FILENAME,
        )
        _state.update { it.copy(dialog = null, message = "已绑定：${candidate.displayName()}") }
    }

    private fun bind(fileId: Long, result: MatchResultV2, manual: Boolean, autoMatchType: String) {
        if (result.episodeId <= 0L) return
        viewModelScope.launch {
            if (manual) {
                matchRepository.bindManually(fileId, result)
            } else {
                matchRepository.persistMatch(mediaFileId = fileId, result = result, matchType = autoMatchType)
            }
        }
    }

    // ── 手动搜索（P1）─────────────────────────────────────────────

    fun openSearch(fileId: Long) {
        val entity = _state.value.videos.firstOrNull { it.id == fileId } ?: return
        val parsed = FileNameParser.parse(entity.displayName)
        val keyword = entity.parsedTitle.orEmpty().ifBlank { parsed.title }.ifBlank { entity.displayName }

        _state.update {
            it.copy(
                dialog = LibraryDialog.Search(
                    fileId = fileId,
                    fileName = entity.displayName,
                    keyword = keyword,
                ),
            )
        }
        // 直接帮用户搜一次，省掉他再点一下按钮
        runSearch()
    }

    fun onSearchKeywordChange(value: String) {
        updateSearchDialog { it.copy(keyword = value) }
    }

    fun toggleAnimeExpanded(animeId: Long) {
        val dialog = _state.value.dialog as? LibraryDialog.Search ?: return
        val next = if (dialog.expandedAnimeId == animeId) null else animeId
        _state.update { it.copy(dialog = dialog.copy(expandedAnimeId = next)) }
    }

    fun runSearch() {
        val dialog = _state.value.dialog as? LibraryDialog.Search ?: return
        val keyword = dialog.keyword.trim()
        if (keyword.isEmpty()) {
            _state.update { it.copy(dialog = dialog.copy(error = "请输入作品名")) }
            return
        }
        updateSearchDialog { it.copy(searching = true, error = null) }

        viewModelScope.launch {
            when (val result = matchRepository.searchEpisodes(keyword, episode = null)) {
                is ApiResult.Failure -> updateSearchDialog {
                    it.copy(searching = false, error = describeFailure(result))
                }

                is ApiResult.Success -> updateSearchDialog {
                    it.copy(
                        searching = false,
                        searched = true,
                        animes = result.data,
                        expandedAnimeId = result.data.firstOrNull()?.animeId,
                        error = if (result.data.isEmpty()) "没有搜到相关作品，换个关键词试试" else null,
                    )
                }
            }
        }
    }

    fun bindSearchEpisode(anime: SearchEpisodesAnime, episode: SearchEpisodeDetails) {
        val dialog = _state.value.dialog as? LibraryDialog.Search ?: return
        viewModelScope.launch {
            val ok = matchRepository.bindFromSearch(dialog.fileId, anime, episode)
            _state.update {
                it.copy(
                    dialog = null,
                    message = if (ok) {
                        "已手动绑定：${anime.animeTitle.orEmpty()} · ${episode.episodeTitle.orEmpty()}"
                    } else {
                        "绑定失败：该剧集编号无效"
                    },
                )
            }
        }
    }

    private fun updateSearchDialog(transform: (LibraryDialog.Search) -> LibraryDialog.Search) {
        val dialog = _state.value.dialog as? LibraryDialog.Search ?: return
        _state.update { it.copy(dialog = transform(dialog)) }
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun requireCredentials(): Boolean {
        if (_state.value.credentialsConfigured) return true
        _state.update { it.copy(message = "尚未配置弹弹play 凭据，请先前往设置填写 AppId / AppSecret") }
        return false
    }

    private fun describeFailure(failure: ApiResult.Failure): String = when {
        failure.isCredentialMissing -> "尚未配置弹弹play 凭据，请先前往设置填写 AppId / AppSecret"
        failure.isAuthFailure -> "AppId 或 AppSecret 有误，请前往设置重新填写"
        else -> "识别失败：${failure.message}"
    }
}

/** 候选在下拉/列表里的展示文案。 */
fun MatchResultV2.displayName(): String {
    val anime = animeTitle.orEmpty().ifBlank { "未知作品" }
    val episode = episodeTitle.orEmpty().ifBlank { "episodeId=$episodeId" }
    return "$anime · $episode"
}
