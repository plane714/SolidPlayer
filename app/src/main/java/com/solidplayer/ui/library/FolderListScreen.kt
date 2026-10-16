package com.solidplayer.ui.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

/** 红角标的颜色：刻意写死，不跟随主题 —— 它是「新内容」的信号色，不该被配色方案改掉。 */
private val BadgeRed = Color(0xFFE53935)

/**
 * 媒体库首页（图一）：自动发现的全机「含视频的文件夹」。
 *
 * 列表项只有三层节点（图标 / 两行文字 / 角标），刻意不用 Material3 的 `ListItem`——
 * 一次全机扫描出来的文件夹动辄上百个，每行多几个节点的代价会直接落在滑动帧率上。
 * 「已置顶」的图钉图标只在置顶时多出一个节点。
 *
 * 交互：**单击进入目录；长按弹「置顶 / 取消置顶」菜单，长按后直接拖 = 调整顺序**
 * （原地松手不改顺序、还是弹那个菜单）。偏好存在 DataStore 里（见 `PinnedFolderStore`），
 * **顺序**与**图钉**是两个独立的 key：拖拽只改顺序、永远不会让图钉出现或消失，
 * 想置顶只能走长按菜单 —— 这是「拖一下就被置顶」那条毛病的根治法。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    onOpenFolder: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: FolderListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorder = remember { FolderReorderState() }

    // 拖拽期间渲染这一份本地顺序（VM 的那份要等落盘 + Flow 回环，先落盘再改会闪一下）
    val displayed = reorder.order ?: state.folders

    // 进页面自动扫一次（进程内只做一次，见 FolderListViewModel.autoScanOnce）
    LaunchedEffect(Unit) { viewModel.autoScanOnce() }

    // 一次性提示：**先消费、再显示**。
    //
    // 顺序反过来（先 showSnackbar 再 consume）会漏掉一种情况：`showSnackbar` 是**挂起**的，
    // 一直等到提示自己超时消失才返回。用户在这几秒里点进某个文件夹，本页离开组合、
    // 协程被取消 → `consumeMessage()` 永远执行不到 → `state.message` 一直是「扫描完成…」，
    // 下次回到首页时本 Effect 以同一个 key 重新启动，于是**又弹一遍**。
    // 先消费就没有这个窗口：消息在进入组合的那一刻就被取走，显示与否只影响这一次。
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        snackbarHostState.showSnackbar(message)
    }

    // 拖到列表上下边缘时自动滚动：折叠屏一行放不下几个目录，没有这一段就只能在同一屏里排序。
    //
    // 方向不能反：手指贴**上**边缘想把它送到更前面 → 内容要往回滚（dy 为负，露出上面的项），
    // 被拖行才会继续越过邻居。滚出来的实际距离交给 `shift()` 校正（见 FolderReorderState）。
    val density = LocalDensity.current
    LaunchedEffect(reorder.active) {
        if (!reorder.active) return@LaunchedEffect
        val zone = with(density) { 72.dp.toPx() }
        val maxStep = with(density) { 16.dp.toPx() }
        while (isActive) {
            withFrameNanos { }
            val order = reorder.order ?: break
            val layout = listState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.index == reorder.index } ?: continue
            val finger = info.offset + reorder.localY
            val dy = when {
                finger < layout.viewportStartOffset + zone && reorder.index > 0 ->
                    -maxStep * ((layout.viewportStartOffset + zone - finger) / zone).coerceIn(0f, 1f)

                finger > layout.viewportEndOffset - zone && reorder.index < order.lastIndex ->
                    maxStep * ((finger - layout.viewportEndOffset + zone) / zone).coerceIn(0f, 1f)

                else -> 0f
            }
            if (dy != 0f) reorder.shift(listState.scrollBy(dy))
        }
    }

    val openFolder: (String) -> Unit = remember(onOpenFolder) { { onOpenFolder(it) } }
    val togglePin: (String) -> Unit = remember(viewModel) { { viewModel.togglePin(it) } }
    val commitDrop: (ReorderDrop) -> Unit = remember(viewModel) {
        { drop -> viewModel.commitReorder(drop) }
    }

    // 起手时要把**当前**这份顺序交给拖拽会话，但手势块只在「行的 key」变化时才重启
    // （见 FolderRow 的注释），所以不能直接把 displayed 传进去 —— 它会被冻在行首次组合的那一刻。
    // 这里用一个稳定的 State 持有最新值，再经**稳定的** onBegin 闭包回传：块里现读，
    // 拿到的永远是按下那一瞬间的顺序。
    val latestDisplayed = rememberUpdatedState(displayed)
    val beginDrag: (Int, Float, Float) -> Unit = remember(reorder) {
        { index, grabY, rowHeight -> reorder.begin(latestDisplayed.value, index, grabY, rowHeight) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文件夹") },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("识别全部待识别") },
                                enabled = !state.busy && state.totalVideos > 0,
                                onClick = {
                                    menuExpanded = false
                                    viewModel.identifyAll()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("重新识别全部") },
                                enabled = !state.busy && state.totalVideos > 0,
                                onClick = {
                                    menuExpanded = false
                                    viewModel.reidentifyAll()
                                },
                            )
                            if (state.identify.canRetryFailed) {
                                DropdownMenuItem(
                                    text = { Text("只重试失败的 ${state.identify.failedIds.size} 个") },
                                    enabled = !state.busy,
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.retryFailed()
                                    },
                                )
                            }
                            if (state.identify.running) {
                                DropdownMenuItem(
                                    text = { Text("取消识别") },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.cancelIdentify()
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenSettings()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // 下拉刷新同样走「重扫全机」。顶栏那个刷新按钮保留：它是显式入口，
        // 下拉是顺手，两者共用同一份 `refresh()`（仓库侧已串行化，不会并发扫两遍）。
        //
        // 扫描中的进度提示交给下拉指示器（`isRefreshing` 绑的就是 `state.scanning`，
        // 从顶栏按钮触发时它一样会转），线性条只留给「识别」——否则两个进度会叠在一起。
        PullToRefreshBox(
            isRefreshing = state.scanning,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!state.credentialsConfigured) {
                    CredentialBanner(onClick = onOpenSettings)
                }
                if (state.degraded) {
                    DegradedBanner()
                }

                if (state.identify.running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (displayed.isNotEmpty()) {
                    SummaryBar(
                        folders = displayed.size,
                        videos = state.totalVideos,
                        newCount = state.totalNew,
                    )
                }

                // 空态也放进 LazyColumn：下拉刷新靠嵌套滚动驱动，
                // 整块不可滚动时「一个文件夹都没扫到」这种最该刷新的状态反而拉不动
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    if (displayed.isEmpty()) {
                        item(contentType = CONTENT_EMPTY) {
                            EmptyHint(
                                scanning = state.scanning,
                                modifier = Modifier.fillParentMaxSize(),
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = displayed,
                            key = { _, entry -> entry.folder.folderPath },
                            contentType = { _, _ -> CONTENT_FOLDER },
                        ) { index, entry ->
                            val dragging = reorder.draggedKey == entry.folder.folderPath
                            FolderRow(
                                entry = entry,
                                index = index,
                                reorder = reorder,
                                onBegin = beginDrag,
                                // 换位动画挂在 item 根上（`animateItem` 是 LazyItemScope 的扩展，
                                // 只有这个 lambda 里拿得到）。进出场淡入淡出关掉：这两页本来就是
                                // 整屏一次出现，淡入看起来像「加载慢」。
                                //
                                // **被拖的那一行必须把换位动画关掉**：它的位置是「布局位移 +
                                // graphicsLayer 跟手位移」拼出来的，动画再插一杠子（布局慢慢挪、
                                // 跟手位移按最终位置算）就会和手指对不上 —— 表现为行自己飘。
                                modifier = Modifier.animateItem(
                                    fadeInSpec = null,
                                    placementSpec = if (dragging) {
                                        null
                                    } else {
                                        spring(stiffness = Spring.StiffnessMediumLow)
                                    },
                                    fadeOutSpec = null,
                                ),
                                onOpen = openFolder,
                                onTogglePin = togglePin,
                                onDrop = commitDrop,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val CONTENT_FOLDER = "video-folder"

/** 空态那一项：与文件夹行分槽位，避免复用池把整块空态当成列表行复用 */
private const val CONTENT_EMPTY = "folder-empty"

/**
 * 一个文件夹行。
 *
 * 手势只有一条：**单击进目录；长按浮起 —— 原地松手弹「置顶 / 取消置顶」菜单，拖动则调顺序**。
 *
 * ### 单击与长按必须共用一次判定（P8.3 的教训）
 * 早先是 `clickable` 管点击、`detectDragGesturesAfterLongPress` 管长按。两个 detector
 * **各自算一遍同一个长按超时**，在同一次按下上互相抢：`clickable` 只要在它自己的超时前
 * 看到抬手就判点击（它并不知道拖拽库也在盯着这次按下），于是「按到临界时长」的手势会
 * **进子文件夹** —— 用户的感觉就是「明明长按了却进了目录」。
 * 现在整条手势只有 [awaitPressIntent] 一个判定入口，三种归宿互斥：单击 / 长按 / 事件被抢走。
 *
 * ### 长按之后的收尾
 * 「原地松手弹菜单」的判据是 `FolderReorderState.moved` = **换过位**（不是「手指动过」——
 * 按下时手指抖一下是常态）；是否**以抬手收尾**由 [dragUntilUp] 的返回值给出
 * （库里的 `onDragEnd` / `onDragCancel` 分界其实是「up 收尾 / 非 up 收尾」，别按字面理解）。
 *
 * ### 手势块里的普通值必须从 State 里现读
 * `pointerInput(key)` 的块**只在 key 变化时才重启**：行还挂在 `path` 上时，块里捕获的
 * `index` 之类的普通值会一直冻在「这行首次组合」的那一刻。而列表是会变的（扫描完成、
 * 上一次置顶、上一次拖拽都会重排），于是按下时 `begin()` 拿到的是旧下标 + 旧列表 ——
 * 表现为「指头一按下去整张表跳回旧顺序」「换位换错行」「拖起来卡顿」。
 * 所以 [index] 经 `rememberUpdatedState` 取最新值；列表那份由屏幕侧同样处理，经 [onBegin] 现读。
 *
 * ⚠️ 手势挂在**外层 Box**、位移挂在内层 Row，反过来指针坐标会把位移抵消掉（见 `FolderReorderState`）。
 *
 * 菜单用 `DropdownMenu` 挂在行右侧的**零尺寸锚点**上：`DropdownMenu` 是个 Popup，
 * 它的弹出位置由**调用处那个布局节点的包围盒**决定 —— 直接放进整行的话菜单会从行的左上角探出来，
 * 所以外面套一层 `Box` 并把锚点对齐到行尾。
 *
 * ⚠️ 这个函数刻意**不接收整份列表**（早先是 `entries: List<FolderEntry>`）：列表每换一次位就是
 * 一个新实例，参数一变整屏可见行全部重组（`List` 又不稳定），几十行乘几十次换位就是「卡顿」。
 * 列表只经 [onBegin] 在起手那一刻读一次。
 *
 * [onTogglePin] / [onOpen] 收到的是**路径**而不是闭包：`items` 里现写 lambda 会让每个可见行
 * 每帧都新建一个 lambda 实例（被记住的只有页面级那一份）。
 */
@Composable
private fun FolderRow(
    entry: FolderEntry,
    index: Int,
    reorder: FolderReorderState,
    onBegin: (index: Int, grabY: Float, rowHeight: Float) -> Unit,
    onOpen: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDrop: (ReorderDrop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = entry.folder
    val path = summary.folderPath
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val dragging = reorder.draggedKey == path
    // 见上面「手势块里的普通值必须从 State 里现读」：index 会随重排变，块却不会重启
    val latestIndex = rememberUpdatedState(index)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 被拖的那一行要压在邻居上面，否则它会从别人身子底下"穿过"
            .zIndex(if (dragging) 1f else 0f)
            .pointerInput(path) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    when (val intent = awaitPressIntent(down.id)) {
                        // 单击：进目录
                        PressIntent.Tap -> onOpen(path)

                        // 长按成立：震动 → 浮起跟手 → 松手落位；
                        // **一次都没换过位**才弹回原来的「置顶 / 取消置顶」菜单
                        is PressIntent.Hold -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBegin(latestIndex.value, intent.change.position.y, size.height.toFloat())
                            val endedWithUp = try {
                                dragUntilUp(intent.change.id) { change ->
                                    reorder.follow(change.position.y)
                                    change.consume()
                                }
                            } catch (e: CancellationException) {
                                // 手势被打断（组合销毁 / 行被回收）：把行放回去，
                                // 否则它会一直停在"浮起"态，得再拖一次才能复位
                                reorder.cancel()
                                throw e
                            }
                            val moved = reorder.moved
                            val drop = reorder.drop()
                            when (resolveHoldOutcome(moved, endedWithUp)) {
                                HoldOutcome.Commit -> drop?.let(onDrop)
                                HoldOutcome.Menu -> menuOpen = true
                                HoldOutcome.Discard -> Unit
                            }
                        }

                        // 事件归了别人（例如下滑滚动列表），这里什么都不做
                        PressIntent.Cancelled -> Unit
                    }
                }
            }
            // 手势自己实现了，进目录这条路要显式给无障碍留一个入口
            .semantics { onClick { onOpen(path); true } },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    // 浮起：给个不透明的底色 + 阴影，别让被拖的行看起来和邻居一样"贴"在列表里。
                    // 位移写在 graphicsLayer 里（只重绘、不重新布局），并且线程内读 `offsetY`，
                    // 所以跟手的每一帧都不会触发重组。
                    if (dragging) {
                        Modifier
                            .graphicsLayer { translationY = reorder.offsetY }
                            .shadow(4.dp, RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(56.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(30.dp),
                    )
                }
                if (summary.newCount > 0) {
                    CountBadge(
                        count = summary.newCount,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 8.dp, y = (-8).dp),
                    )
                }
            }

            Spacer(modifier = Modifier.size(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.pinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "已置顶",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                    }
                    Text(
                        text = summary.folderName?.takeIf { it.isNotBlank() }
                            ?: summary.folderPath.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${summary.videoCount} 个视频",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(if (entry.pinned) "取消置顶" else "置顶") },
                    onClick = {
                        menuOpen = false
                        onTogglePin(summary.folderPath)
                    },
                )
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(BadgeRed, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun SummaryBar(folders: Int, videos: Int, newCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$folders 个文件夹 · $videos 个视频",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (newCount > 0) {
            Text(
                text = "$newCount 个新视频",
                style = MaterialTheme.typography.labelMedium,
                color = BadgeRed,
            )
        }
    }
}

@Composable
private fun CredentialBanner(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(
            text = "尚未配置弹弹play 凭据，点击前往设置（本地扫描与播放不受影响）",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * 分区存储下读不到真实目录时的降级提示。
 *
 * 只发生在 Android 10（API 29）：那个版本既没有「所有文件访问」，又已经强制分区存储。
 * 与其让用户疑惑「为什么少了几个文件夹」，不如直说。
 */
@Composable
private fun DegradedBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "当前系统不允许遍历目录（Android 10 的分区存储限制），只能列出系统媒体库已索引的视频，" +
                "结果可能不全。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun EmptyHint(scanning: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = if (scanning) "正在扫描手机里的视频…" else "没有找到任何视频文件夹，下拉可以重新扫描",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 一次按下在手势前半程的归宿 —— 三种，且互斥。
 */
private sealed interface PressIntent {

    /** 长按阈值**内**抬手：这是一次单击。 */
    data object Tap : PressIntent

    /** 越过长按阈值：进入拖拽；[change] 是判定成立那一刻的指针（要用它的位置算起手高度）。 */
    data class Hold(val change: PointerInputChange) : PressIntent

    /** 事件被别的组件抢走 / 移出屏幕：这次手势与我们无关（例如在行上滑动 = 滚列表）。 */
    data object Cancelled : PressIntent
}

/**
 * 判定这次按下是「单击」还是「长按」。
 *
 * ### 为什么不能交给 `clickable` + 拖拽库各判一遍
 * 两个 detector 会各自跑一遍**同一个** `viewConfiguration.longPressTimeoutMillis`，
 * 在同一次按下上互相抢。`clickable` 只看「它自己的超时前有没有抬手」，并不知道拖拽库
 * 也在盯着这次按下 —— 于是「按住但没到阈值就松手」会被它当成普通点击，用户看到的是
 * **「我明明长按了，却进了子文件夹」**。判定只留一处，行为才可预期：
 * **阈值内抬手 = 单击，越过阈值 = 长按。**
 *
 * ### 为什么返回「被抢走」而不是抛异常
 * LazyColumn 的滚动会消费移动事件，这正是「在行上滑动 = 滚列表」能工作的原因；
 * 消费检查放在 **Final** pass 是因为它晚于 Main pass，能看到本轮所有消费者的动作。
 * 移到屏幕外同理 —— 那已经不是「在按这一行」了。
 */
private suspend fun AwaitPointerEventScope.awaitPressIntent(pointerId: PointerId): PressIntent {
    // 与拖拽库内部同一处前置判断：down 与 up 落在同一批事件里时（极快的点击），
    // 那已经是一次点击了，别再去等一个永远不会再来的「下一步」。
    if (currentEvent.changes.fastAll { it.changedToUpIgnoreConsumed() }) return PressIntent.Tap

    // 先取当前事件里的位置：手指完全静止时可能一个事件都收不到，
    // 超时那一刻只能靠这里记下的位置算「手指落在行的哪个高度」。
    var pressed = currentEvent.changes.fastFirstOrNull { it.id == pointerId }
    var tapped = false
    try {
        withTimeout(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.fastAll { it.changedToUpIgnoreConsumed() }) {
                    tapped = true // 阈值内全部抬起 → 单击
                    return@withTimeout
                }
                if (event.changes.fastAny { it.isConsumed || it.isOutOfBounds(size, extendedTouchPadding) }) {
                    return@withTimeout
                }
                val consumeCheck = awaitPointerEvent(PointerEventPass.Final)
                if (consumeCheck.changes.fastAny { it.isConsumed }) return@withTimeout
                event.changes.fastFirstOrNull { it.id == pointerId }?.let { pressed = it }
            }
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        // 长按成立
        return pressed?.let { PressIntent.Hold(it) } ?: PressIntent.Cancelled
    }
    return if (tapped) PressIntent.Tap else PressIntent.Cancelled
}

/**
 * 拖拽循环：把 up 之前的每个位置交给 [onDrag]，返回**是否以抬手收尾**（false = 被抢走）。
 *
 * 不用库里的 `drag()`（它是 internal，且 `onDragEnd` / `onDragCancel` 的分界其实是
 * 「up 收尾 / 非 up 收尾」而不是名字暗示的「抬起 / 被打断」，绕一层反而更难看清）。
 * 就这几行，自己写：**返回值**才是「这次拖拽有没有正常结束」的准确信号。
 *
 * `onDrag` 里要消费事件：不消费的话 LazyColumn 会一边跟着滚一边被拖，列表会"跑"。
 */
private suspend fun AwaitPointerEventScope.dragUntilUp(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.fastFirstOrNull { it.id == pointerId } ?: return false
        if (change.changedToUpIgnoreConsumed()) return true
        if (change.isConsumed) return false
        onDrag(change)
    }
}

