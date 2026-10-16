package com.solidplayer.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solidplayer.core.data.network.dto.SearchEpisodeDetails
import com.solidplayer.core.data.network.dto.SearchEpisodesAnime

/**
 * P1 手动搜索弹窗（文档 §4.2 决策表的「`matches` 为空」分支）。
 *
 * 哈希和文件名都没命中时，这是唯一的兜底路径：拿解析出的标题去
 * `/api/v2/search/episodes` 搜，用户点某一集 → 写 `manual` 绑定。
 */
@Composable
internal fun ManualSearchDialog(
    dialog: LibraryDialog.Search,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    onToggleAnime: (Long) -> Unit,
    onPick: (SearchEpisodesAnime, SearchEpisodeDetails) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!dialog.searching) onDismiss() },
        title = { Text("手动搜索剧集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "本地文件：${dialog.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = dialog.keyword,
                        onValueChange = onKeywordChange,
                        label = { Text("作品名") },
                        singleLine = true,
                        enabled = !dialog.searching,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(onClick = onSearch, enabled = !dialog.searching) { Text("搜索") }
                }

                if (dialog.searching) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(text = "  正在搜索…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                dialog.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (dialog.animes.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 弹窗高度有限：候选多时内部滚动，避免把按钮挤出屏幕
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        dialog.animes.forEach { anime ->
                            AnimeResultRow(
                                anime = anime,
                                expanded = dialog.expandedAnimeId == anime.animeId,
                                onToggle = { onToggleAnime(anime.animeId) },
                                onPick = { episode -> onPick(anime, episode) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun AnimeResultRow(
    anime: SearchEpisodesAnime,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: (SearchEpisodeDetails) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    text = anime.animeTitle.orEmpty().ifBlank { "未知作品" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
            },
            supportingContent = {
                Text(
                    text = listOfNotNull(
                        anime.typeDescription?.takeIf { it.isNotBlank() },
                        "${anime.episodes.size} 集",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            },
            modifier = Modifier.clickable(onClick = onToggle),
        )

        if (expanded) {
            anime.episodes.forEach { episode ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = episode.episodeTitle.orEmpty().ifBlank { "episodeId=${episode.episodeId}" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .padding(start = 24.dp)
                        .clickable { onPick(episode) },
                )
            }
        }
    }
}
