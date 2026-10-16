package com.solidplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solidplayer.core.data.db.MatchType
import com.solidplayer.core.data.db.MediaFileEntity
import com.solidplayer.core.data.db.isNew
import com.solidplayer.core.data.repository.VideoLibraryRepository
import com.solidplayer.ui.common.VideoThumbnail

private val BadgeRed = Color(0xFFE53935)

/** 字幕角标的绿：图二里是明确的「有字幕」信号，同样写死不跟主题。 */
private val SubtitleGreen = Color(0xFF2E9E5B)

private val THUMB_WIDTH = 124.dp
private val THUMB_HEIGHT = 70.dp

/**
 * 文件夹详情页（图二）：一个目录下的视频列表。
 *
 * 缩略图刻意固定 16:9 的盒子：竖屏手机的视频、录屏、番剧混在一个目录里很常见，
 * 让每行自适应高度会让整列看起来像被咬过一口。裁切（`ContentScale.Crop`）比留黑边整齐。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    onBack: () -> Unit,
    onOpenPlayer: (Long) -> Unit,
    viewModel: FolderDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // NEW 的判定窗口在进入页面时算一次即可（同一次浏览里不该跳变）。
    // 是否真的挂 NEW 还要看「有没有点开过」（`isNew`）—— 那个条件随用户操作变，
    // 但它是写在文件行自己的数据上，漏掉的只是「窗口本身不跳」这件事。
    val newSince = remember { System.currentTimeMillis() - VideoLibraryRepository.NEW_WINDOW_MS }

    // 一次性提示：**先消费、再显示**（理由见 FolderListScreen 里的同款说明）。
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        snackbarHostState.showSnackbar(message)
    }

    val openPlayer: (Long) -> Unit = remember(onOpenPlayer) { { onOpenPlayer(it) } }
    val openIdentify: (Long) -> Unit = remember { { viewModel.openIdentify(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.folderName.ifBlank { "文件夹" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.identify.running) {
                        IconButton(onClick = viewModel::cancelIdentify) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(
                            onClick = viewModel::identifyFolder,
                            enabled = state.pendingCount > 0,
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "识别本目录")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // 下拉刷新 = 重扫全机（与首页同一个入口），本目录列表随 Room Flow 自动更新。
        // 页面顶部固定着计数栏，下拉手势由列表提供嵌套滚动 —— 空列表时也走 LazyColumn，
        // 否则整块不可滚动，用户在最需要刷新的空目录里反而拉不动。
        PullToRefreshBox(
            isRefreshing = state.scanning,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                VideoCountBar(
                    total = state.videos.size,
                    pending = state.pendingCount,
                    onIdentify = viewModel::identifyFolder,
                    enabled = !state.busy,
                )

                if (state.identify.running) {
                    LinearProgressIndicator(
                        progress = { state.identify.percent },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (state.videos.isEmpty()) {
                        item(contentType = CONTENT_EMPTY) {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "这个文件夹里没有视频了，下拉可以重新扫描本机",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(
                            items = state.videos,
                            key = { it.id },
                            contentType = { CONTENT_VIDEO },
                        ) { video ->
                            VideoRow(
                                video = video,
                                isNew = video.isNew(newSince),
                                onPlay = openPlayer,
                                onIdentify = openIdentify,
                            )
                        }
                    }
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        null -> Unit

        is LibraryDialog.Identify -> IdentifyDialog(
            dialog = dialog,
            busy = state.matching,
            onManualNameChange = viewModel::onManualNameChange,
            onAutoMatch = viewModel::runAutoMatch,
            onManualMatch = viewModel::runManualMatch,
            onOpenSearch = { viewModel.openSearch(dialog.fileId) },
            onDismiss = viewModel::dismissDialog,
        )

        is LibraryDialog.Candidates -> CandidatesDialog(
            dialog = dialog,
            onSelect = viewModel::selectCandidate,
            onDismiss = viewModel::dismissDialog,
        )

        is LibraryDialog.Search -> ManualSearchDialog(
            dialog = dialog,
            onKeywordChange = viewModel::onSearchKeywordChange,
            onSearch = viewModel::runSearch,
            onToggleAnime = viewModel::toggleAnimeExpanded,
            onPick = viewModel::bindSearchEpisode,
            onDismiss = viewModel::dismissDialog,
        )
    }
}

private const val CONTENT_VIDEO = "video-row"

/** 空态那一项：与文件行分槽位，避免复用池把整块空态当成文件行复用 */
private const val CONTENT_EMPTY = "video-empty"

@Composable
private fun VideoCountBar(
    total: Int,
    pending: Int,
    onIdentify: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (pending > 0) "$total 个视频 · $pending 个待识别" else "$total 个视频 · 全部已识别",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (pending > 0) {
            TextButton(onClick = onIdentify, enabled = enabled) { Text("识别本目录") }
        }
    }
}

/**
 * 视频行：缩略图（含时长 / NEW 角标）+ 文件名 + 字幕角标 + 溢出菜单。
 *
 * 整行点击 = 播放（与旧媒体库一致：能少一次点击就少一次），
 * 溢出菜单里再给「识别」这个显式入口。
 */
@Composable
private fun VideoRow(
    video: MediaFileEntity,
    isNew: Boolean,
    onPlay: (Long) -> Unit,
    onIdentify: (Long) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay(video.id) }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(THUMB_WIDTH, THUMB_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
        ) {
            VideoThumbnail(
                uri = video.uri,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp)),
                contentDescription = video.displayName,
            )

            if (isNew) {
                Text(
                    text = "NEW",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(BadgeRed, RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }

            if (video.durationMs > 0L) {
                Text(
                    text = formatDuration(video.durationMs),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color(0x99000000), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (video.durationMs > 0L) {
                Text(
                    text = formatDuration(video.durationMs) + sizeSuffix(video.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            video.subtitleFormats?.takeIf { it.isNotBlank() }?.let { formats ->
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    formats.split(',').forEach { format ->
                        Text(
                            text = format.trim(),
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .background(SubtitleGreen, RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("播放") },
                    onClick = {
                        menuOpen = false
                        onPlay(video.id)
                    },
                )
                DropdownMenuItem(
                    text = { Text("识别 / 手动匹配") },
                    onClick = {
                        menuOpen = false
                        onIdentify(video.id)
                    },
                )
                HorizontalDivider()
                Text(
                    text = matchLabel(video),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun matchLabel(video: MediaFileEntity): String =
    if (video.episodeId == null) {
        "未识别 · episodeId 为空"
    } else {
        val source = if (video.matchType == MatchType.MANUAL) "手动" else "自动"
        "$source · episodeId=${video.episodeId}"
    }

private fun sizeSuffix(bytes: Long): String = if (bytes <= 0L) {
    ""
} else {
    " · " + when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}

/** `23:40` / `1:02:03` */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
