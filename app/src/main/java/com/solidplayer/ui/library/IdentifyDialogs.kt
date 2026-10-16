package com.solidplayer.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.solidplayer.core.data.network.dto.MatchResultV2

/**
 * 单文件识别的两个弹窗（识别 / 多候选）。
 *
 * 从 v1 的 `LibraryScreen` 原样搬过来的，逻辑没动 —— 它们只依赖 [LibraryDialog] 与几个回调，
 * 谁持有状态（首页还是文件夹详情页）都能复用。
 */
@Composable
internal fun IdentifyDialog(
    dialog: LibraryDialog.Identify,
    busy: Boolean,
    onManualNameChange: (String) -> Unit,
    onAutoMatch: () -> Unit,
    onManualMatch: () -> Unit,
    onOpenSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("识别文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "本地文件：${dialog.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "自动识别会读取文件前 16MB 计算哈希；也可以直接改下面的文件名后按名字匹配。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = dialog.manualName,
                    onValueChange = onManualNameChange,
                    label = { Text("文件名（可手动修改）") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(text = "  正在识别…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAutoMatch, enabled = !busy) { Text("自动识别") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenSearch, enabled = !busy) { Text("手动搜索") }
                TextButton(onClick = onManualMatch, enabled = !busy) { Text("按此名字") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            }
        },
    )
}

@Composable
internal fun CandidatesDialog(
    dialog: LibraryDialog.Candidates,
    onSelect: (MatchResultV2) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择匹配的剧集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Card(colors = CardDefaults.cardColors()) {
                    Text(
                        text = "共 ${dialog.candidates.size} 个候选，默认第一条最可能。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                dialog.candidates.forEachIndexed { index, candidate ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = candidate.animeTitle.orEmpty().ifBlank { "未知作品" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = "${candidate.episodeTitle.orEmpty()} · episodeId=${candidate.episodeId}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier.clickable { onSelect(candidate) },
                    )
                    if (index == 0) {
                        Text(
                            text = "↑ 推荐",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
