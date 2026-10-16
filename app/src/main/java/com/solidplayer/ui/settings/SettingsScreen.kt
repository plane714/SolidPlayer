package com.solidplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solidplayer.core.data.repository.DanmakuCacheStats

private const val APPLY_URL = "https://www.dandanplay.com/open.html"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(state.hint) {
        state.hint?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeHint()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(
                configured = state.configured,
                onApply = { uriHandler.openUri(APPLY_URL) },
            )

            OutlinedTextField(
                value = state.appId,
                onValueChange = viewModel::onAppIdChange,
                label = { Text("AppId") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.appSecret,
                onValueChange = viewModel::onAppSecretChange,
                label = { Text("AppSecret") },
                singleLine = true,
                // 默认掩码，可切换显示（文档 §4.5.5）
                visualTransformation = if (state.secretVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = viewModel::toggleSecretVisible) {
                        Icon(
                            imageVector = if (state.secretVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (state.secretVisible) "隐藏" else "显示",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !state.verifying && state.inputComplete,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("测试连接")
                    }
                }

                Button(
                    onClick = viewModel::save,
                    enabled = state.inputComplete,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存")
                }
            }

            VerifyResultText(state.verifyResult)

            TextButton(onClick = viewModel::askClear, enabled = state.configured) {
                Text("清除凭据")
            }

            Text(
                text = "凭据只保存在本机应用私有目录，且不参与系统备份；" +
                    "AppSecret 不会写入 APK，也不会打进任何日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CacheCard(
                stats = state.cacheStats,
                clearing = state.clearingCache,
                onClear = viewModel::askClearCache,
            )
        }
    }

    if (state.confirmClear) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClear,
            title = { Text("清除凭据？") },
            text = { Text("清除后将无法识别文件与拉取弹幕，本地扫描与本地播放不受影响。") },
            confirmButton = {
                TextButton(onClick = viewModel::clear) { Text("确认清除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClear) { Text("取消") }
            },
        )
    }

    if (state.confirmClearCache) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearCache,
            title = { Text("清理弹幕缓存？") },
            text = {
                Text(
                    "将删除 ${state.cacheStats.episodes} 个剧集的本地弹幕（约 " +
                        "${formatBytes(state.cacheStats.bytes)}）。" +
                        "清理后再次播放这些剧集需要重新联网拉取，离线时将没有弹幕。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearCache) { Text("确认清理") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearCache) { Text("取消") }
            },
        )
    }
}

/**
 * 弹幕缓存区块（P2）。
 *
 * 为什么要给用户这个入口：弹弹play 官方对调用频率敏感（文档 §10），
 * 所以缓存是「必须做」的；但既然是缓存，就也得让用户看得见、清得掉。
 */
@Composable
private fun CacheCard(
    stats: DanmakuCacheStats,
    clearing: Boolean,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("弹幕缓存", style = MaterialTheme.typography.titleMedium)

            Text(
                text = if (stats.isEmpty) {
                    "暂无缓存。播放过的剧集会把弹幕存在本地，减少重复请求。"
                } else {
                    "${stats.episodes} 个剧集 · ${formatBytes(stats.bytes)}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedButton(
                onClick = onClear,
                enabled = !stats.isEmpty && !clearing,
            ) {
                if (clearing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("清理缓存")
                }
            }

            Text(
                text = "超过 24 小时的缓存会在应用启动时自动清理；" +
                    "没有弹幕的剧集结果会保留 7 天，避免反复请求。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 字节数的可读展示。缓存量级在 KB ~ 几十 MB，MB 保留一位小数足够。 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

@Composable
private fun StatusCard(configured: Boolean, onApply: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (configured) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (configured) "状态：已配置" else "状态：未配置",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (configured) {
                    "文件识别与弹幕拉取已可用。"
                } else {
                    "未配置凭据时：本地扫描与本地播放照常可用，但无法识别文件、拉不到弹幕。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("申请入口：", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onApply) { Text(APPLY_URL) }
            }
        }
    }
}

@Composable
private fun VerifyResultText(result: VerifyResult?) {
    when (result) {
        null -> Unit
        is VerifyResult.Ok -> Text(
            text = "✓ 连接成功，凭据可用",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )

        is VerifyResult.Error -> Text(
            text = "✗ ${result.message}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
