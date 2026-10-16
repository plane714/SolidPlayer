package com.solidplayer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solidplayer.core.data.credential.DandanplayCredentialRepository
import com.solidplayer.core.data.db.VideoFolderSummary
import com.solidplayer.core.data.repository.LibraryIdentifyCoordinator
import com.solidplayer.core.data.repository.PinnedFolderCodec
import com.solidplayer.core.data.repository.PinnedFolderStore
import com.solidplayer.core.data.repository.VideoLibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页列表的一行：目录聚合 + 它是否被置顶。
 *
 * 为什么不把 `pinned` 直接塞进 [VideoFolderSummary]：那是 Room 的查询投影
 * （`MediaFileDao.observeFolderSummaries` 的返回类型），多一个字段就要让 Room
 * 去游标里找一列根本不存在的值。置顶是 DataStore 里的偏好，**两条数据在这里合流**。
 */
data class FolderEntry(
    val folder: VideoFolderSummary,
    val pinned: Boolean,
)

data class FolderListUiState(
    val folders: List<FolderEntry> = emptyList(),
    val scanning: Boolean = false,
    /** 只读到系统媒体库索引（分区存储下读不到真实目录）时为 true，首页要给一句提示 */
    val degraded: Boolean = false,
    val credentialsConfigured: Boolean = false,
    val identify: LibraryIdentifyCoordinator.Progress = LibraryIdentifyCoordinator.Progress(),
    val message: String? = null,
) {
    val busy: Boolean get() = scanning || identify.running

    val totalVideos: Int get() = folders.sumOf { it.folder.videoCount }

    val totalNew: Int get() = folders.sumOf { it.folder.newCount }
}

/**
 * 媒体库首页（图一）：自动发现的「含视频的文件夹」列表。
 *
 * 页面**不持有识别任务** —— 批量识别在应用级的 [LibraryIdentifyCoordinator] 上跑，
 * 用户点进文件夹、再退回来，进度都还在。
 */
@HiltViewModel
class FolderListViewModel @Inject constructor(
    private val repository: VideoLibraryRepository,
    private val credentialRepository: DandanplayCredentialRepository,
    private val coordinator: LibraryIdentifyCoordinator,
    private val pinnedStore: PinnedFolderStore,
) : ViewModel() {

    private val _state = MutableStateFlow(FolderListUiState(degraded = repository.canTraverse().not()))
    val state: StateFlow<FolderListUiState> = _state.asStateFlow()

    init {
        // 三路合流：目录聚合（Room Flow）、图钉、显式顺序（DataStore 两个 key）。
        // 任何一边变了都会重发，所以「刚置顶完点心爱的目录」与「扫到一个新目录」
        // 走的是同一条刷新路径，不需要在动作之后手动补一次查询。
        viewModelScope.launch {
            combine(
                repository.observeFolders(),
                pinnedStore.pinned,
                pinnedStore.order,
            ) { folders, pinned, order ->
                mergeFolders(folders, PinnedFolderCodec.effectiveOrder(order, pinned), pinned)
            }.collect { entries -> _state.update { it.copy(folders = entries) } }
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

    /**
     * 首页首次出现时自动扫描一次。
     *
     * 闸门放在**仓库（单例）**上而不是本 VM 的字段里：扫描时机被明确收敛成
     * 「每次进入软件一次 + 用户手动下拉」，而 VM 是导航栈条目的作用域 ——
     * 旋转、系统回收后恢复、导航图重建都会换出新实例，记在 VM 里就变成
     * 「某些重建顺带重扫一遍全机」。真正的重扫入口只有下拉刷新与顶栏按钮。
     */
    fun autoScanOnce() {
        if (!repository.claimAutoScan()) return
        refresh()
    }

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
            _state.update {
                it.copy(
                    scanning = false,
                    degraded = repository.canTraverse().not(),
                    message = text,
                )
            }

            // 刻意**不在扫描后顺带识别**：扫描是纯本地动作（遍历 + 入库），
            // 而识别要按文件算 16MB 哈希再打弹弹play —— 一次全机扫描会瞬间产生
            // 几十上百次请求（官方明确会限流拦截，见文档 §10）。
            // 识别只认两个显式入口：媒体库的「识别全部 / 识别本目录」，
            // 以及播放页的「点开即识别」（见 PlayerViewModel.identifyAndLoad）。
        }
    }

    fun identifyAll() {
        if (!requireCredentials()) return
        coordinator.identifyPending()
    }

    fun reidentifyAll() {
        if (!requireCredentials()) return
        coordinator.reidentifyAll()
    }

    fun retryFailed() {
        if (!_state.value.identify.canRetryFailed) return
        if (!requireCredentials()) return
        coordinator.retryFailed()
    }

    fun cancelIdentify() = coordinator.cancel()

    // ── 置顶（长按菜单）与排序（长按拖拽）──────────────────────────

    /**
     * 置顶 / 取消置顶。**只有长按菜单走这里** —— 拖拽永远不会调它，
     * 这正是「拖一下就被置顶」的根治法（见 `PinnedFolderStore` 的 key 分工）。
     *
     * 「现在是哪种状态」从**当前列表**读（那正是用户长按看到的那一行），而不是在仓库里
     * 再查一次 —— 一旦列表还没加载完就被长按，查出来的状态可能与用户看到的不符。
     * 菜单项本身也是按这个状态渲染的，两者同源。
     */
    fun togglePin(folderPath: String) {
        if (folderPath.isBlank()) return
        val entry = _state.value.folders.firstOrNull { it.folder.folderPath == folderPath }
        val currentlyPinned = entry?.pinned ?: return
        viewModelScope.launch { pinnedStore.toggle(folderPath, currentlyPinned) }
    }

    /**
     * 长按拖拽落点提交。
     *
     * **先乐观改内存、再落盘**：写盘 → Flow 回环 → 重新合并至少隔一两帧，先落盘会看到
     * 列表「弹回旧顺序再跳成新顺序」闪一下。
     *
     * 贴到内存上的就是 [ReorderDrop.target] —— **松手那一刻界面上的顺序**。盘上算出来的结果
     * 与它逐字相同（[PinnedFolderCodec.moveOrder] 只截出「落点之前的这一段」并要求它等于
     * 界面上那份的前缀），所以 Flow 随后送回来的那份不需要比对、也不会来回抖。
     * 「所见即所存」是这一整条链路唯一的正确性依据：别再自己从下标重算一遍被拖项是谁。
     *
     * 参数是模块内部的拖拽会话产物（[ReorderDrop]），所以这里也是 `internal`：
     * 首页之外没有第二个入口，拖拽这条线整体不对外暴露。
     */
    internal fun commitReorder(drop: ReorderDrop) {
        if (drop.from == drop.to) return
        _state.update { it.copy(folders = reorderEntries(it.folders, drop.target)) }
        viewModelScope.launch { pinnedStore.reorder(drop.target, drop.from, drop.to) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun requireCredentials(): Boolean {
        if (_state.value.credentialsConfigured) return true
        _state.update { it.copy(message = "尚未配置弹弹play 凭据，请先前往设置填写 AppId / AppSecret") }
        return false
    }
}

/**
 * 把「目录聚合」「图钉」「显式顺序」并成首页要渲染的那一份列表。
 *
 * 图钉只决定那一行画不画图钉图标，**不参与排序**：顺序完全由 [order] 说了算 ——
 * 两者绑在一起时，「想排个序」就必然「顺手置顶」（见 `PinnedFolderCodec` 的 key 分工）。
 *
 * [order] 里那些已经不存在的目录（文件被删 / 换存储根）在这里自然失配、不显示，
 * 只影响展示、不回写盘。
 */
private fun mergeFolders(
    folders: List<VideoFolderSummary>,
    order: List<String>,
    pinned: List<String>,
): List<FolderEntry> {
    if (folders.isEmpty()) return emptyList()
    val flags = pinned.toHashSet()
    return reorderEntries(
        entries = folders.map { FolderEntry(folder = it, pinned = it.folderPath in flags) },
        order = order,
    )
}

/**
 * 按显式顺序 [order] 重排 [entries]，**各行的图钉状态原样保留**。
 *
 * 排序规则只有一条：在 [order] 里有位置的按位置排，没有的排在最后。
 * 用 `sortedBy` 而不是 `sortedWith(compareBy(...))` 的额外字段，是因为 Kotlin 的排序是**稳定**的：
 * 不在 [order] 里的那批在比较键上全部相等（同一个 `Int.MAX_VALUE`），于是它们的相对顺序
 * 原样保留 —— 目录名序不用在这里再写一遍（第二套排序规则就是第二个会漂移的地方）。
 *
 * 拖拽落点的乐观更新与「目录聚合 × 偏好」的合流共用这一个函数：两处各写一份排序，
 * 就是两处会各自漂移的地方（见 `FolderListViewModel.commitReorder`）。
 */
private fun reorderEntries(
    entries: List<FolderEntry>,
    order: List<String>,
): List<FolderEntry> {
    if (order.isEmpty()) return entries
    val rank = order.withIndex().associate { (index, path) -> path to index }
    return entries.sortedBy { rank[it.folder.folderPath] ?: Int.MAX_VALUE }
}
