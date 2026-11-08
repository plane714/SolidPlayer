package com.solidplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solidplayer.core.data.preference.DanmakuPreferenceCodec
import com.solidplayer.core.data.preference.DanmakuPreferences
import com.solidplayer.core.data.preference.SubtitleLanguage
import com.solidplayer.core.data.preference.SubtitlePreferenceCodec
import com.solidplayer.core.data.preference.SubtitlePreferences
import com.solidplayer.core.data.repository.DanmakuCacheStats
import com.solidplayer.ui.common.ThumbnailCacheStats
import kotlin.math.roundToInt

private const val APPLY_URL = "https://www.dandanplay.com/open.html"

/**
 * 设置页内的「二级页面」。
 *
 * 一级页面是一张菜单列表（字幕 / 弹幕 / 弹弹play 凭据 / 缓存），点进去才展开具体设置。
 * 用本地状态做二级页而不是引入导航路由：设置页内部层级很浅，一个 `when` 就够，
 * 还免去了「返回键」与导航栈同步的麻烦（见 [SettingsScreen] 里的 BackHandler）。
 */
private enum class SettingsPage { ROOT, SUBTITLE, DANMAKU, CREDENTIALS, CACHE, THUMBNAIL_CACHE }

/**
 * 软件设置页。
 *
 * 一级菜单按产品要求收敛为两类核心偏好：
 *   1. 字幕 —— 默认字幕语言（默认简体中文 / sc）、字幕样式；
 *   2. 弹幕 —— 原来放在播放页「弹幕设置」面板里的全部弹幕参数搬到这里。
 *
 * 另外保留「弹弹play 凭据」与「弹幕缓存」两项，它们不是「偏好」而是「配置 / 维护」，
 * 跟产品无关但删不掉，所以作为独立一级入口保留下来，不塞进上面两个菜单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }

    // 二级菜单（字幕 / 弹幕 / 凭据 / 缓存）里，系统返回手势 / 返回键应先回到一级菜单，
    // 而不是直接把设置页 pop 掉退回文件夹列表。只有停在一级菜单时才把返回交给导航栈。
    BackHandler(enabled = page != SettingsPage.ROOT) {
        page = SettingsPage.ROOT
    }

    LaunchedEffect(state.hint) {
        state.hint?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeHint()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (page) {
                            SettingsPage.ROOT -> "设置"
                            SettingsPage.SUBTITLE -> "字幕"
                            SettingsPage.DANMAKU -> "弹幕"
                            SettingsPage.CREDENTIALS -> "弹弹play 凭据"
                            SettingsPage.CACHE -> "弹幕缓存"
                            SettingsPage.THUMBNAIL_CACHE -> "缩略图缓存"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (page == SettingsPage.ROOT) onBack() else page = SettingsPage.ROOT
                        },
                    ) {
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
            when (page) {
                SettingsPage.ROOT -> SettingsRoot(
                    state = state,
                    onOpenSubtitle = { page = SettingsPage.SUBTITLE },
                    onOpenDanmaku = { page = SettingsPage.DANMAKU },
                    onOpenCredentials = { page = SettingsPage.CREDENTIALS },
                    onOpenCache = { page = SettingsPage.CACHE },
                    onOpenThumbnailCache = { page = SettingsPage.THUMBNAIL_CACHE },
                )

                SettingsPage.SUBTITLE -> SubtitleSettingsSection(
                    subtitlePrefs = state.subtitlePrefs,
                    onDefaultLanguageChange = viewModel::setDefaultLanguage,
                    onTextSizeChange = viewModel::setSubtitleTextSizeSp,
                    onOutlineChange = viewModel::setSubtitleOutlineEnabled,
                    onCommitStyle = viewModel::persistSubtitleStyle,
                    onReset = viewModel::resetSubtitleStyle,
                )

                SettingsPage.DANMAKU -> DanmakuSettingsSection(
                    prefs = state.danmakuPrefs,
                    onToggle = viewModel::setDanmakuEnabled,
                    onAlphaChange = viewModel::setAlphaPercent,
                    onTextScaleChange = viewModel::setTextScalePercent,
                    onScrollSecondsChange = viewModel::setScrollSeconds,
                    onStrokeWidthChange = viewModel::setStrokeWidthDp,
                    onDisplayAreaChange = viewModel::setDisplayAreaPercent,
                    onCommitStyle = viewModel::persistDanmakuStyle,
                    onApplyBlockRules = viewModel::applyBlockRules,
                    onReset = viewModel::resetDanmakuStyle,
                )

                SettingsPage.CREDENTIALS -> CredentialsSection(
                    state = state,
                    applyUrl = APPLY_URL,
                    onApply = { uriHandler.openUri(APPLY_URL) },
                    onAppIdChange = viewModel::onAppIdChange,
                    onAppSecretChange = viewModel::onAppSecretChange,
                    onToggleSecretVisible = viewModel::toggleSecretVisible,
                    onTestConnection = viewModel::testConnection,
                    onSave = viewModel::save,
                    onAskClear = viewModel::askClear,
                )

                SettingsPage.CACHE -> CacheSection(
                    stats = state.cacheStats,
                    clearing = state.clearingCache,
                    onRefresh = viewModel::refreshCacheStats,
                    onClear = viewModel::askClearCache,
                )

                SettingsPage.THUMBNAIL_CACHE -> ThumbnailCacheSection(
                    stats = state.thumbnailStats,
                    clearing = state.clearingThumbnailCache,
                    onRefresh = viewModel::refreshThumbnailStats,
                    onClear = viewModel::askClearThumbnailCache,
                )
            }
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

    if (state.confirmClearThumbnailCache) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearThumbnailCache,
            title = { Text("清除缩略图缓存？") },
            text = {
                Text(
                    "将删除 ${state.thumbnailStats.entries} 张缩略图（约 " +
                        "${formatBytes(state.thumbnailStats.bytes)}）。" +
                        "清除后再次浏览文件列表会重新生成缩略图，不影响视频文件本身。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearThumbnailCache) { Text("确认清除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearThumbnailCache) { Text("取消") }
            },
        )
    }
}

// ── 一级：菜单列表 ────────────────────────────────────────────────

@Composable
private fun SettingsRoot(
    state: SettingsUiState,
    onOpenSubtitle: () -> Unit,
    onOpenDanmaku: () -> Unit,
    onOpenCredentials: () -> Unit,
    onOpenCache: () -> Unit,
    onOpenThumbnailCache: () -> Unit,
) {
    SettingsMenuGroup {
        SettingsMenuItem(
            title = "字幕",
            subtitle = "默认字幕语言：${state.subtitlePrefs.defaultLanguage.displayName}",
            onClick = onOpenSubtitle,
        )
        SettingsMenuItem(
            title = "弹幕",
            subtitle = if (state.danmakuPrefs.enabled) "已开启 · 透明度 ${state.danmakuPrefs.alphaPercent}%" else "已关闭",
            onClick = onOpenDanmaku,
        )
    }
    SettingsMenuGroup {
        SettingsMenuItem(
            title = "弹弹play 凭据",
            subtitle = if (state.configured) "已配置" else "未配置（无法识别 / 拉取弹幕）",
            onClick = onOpenCredentials,
        )
        SettingsMenuItem(
            title = "弹幕缓存",
            subtitle = if (state.cacheStats.isEmpty) {
                "暂无缓存"
            } else {
                "${state.cacheStats.episodes} 个剧集 · ${formatBytes(state.cacheStats.bytes)}"
            },
            onClick = onOpenCache,
        )
        SettingsMenuItem(
            title = "缩略图缓存",
            subtitle = if (state.thumbnailStats.isEmpty) {
                "暂无缓存"
            } else {
                "${state.thumbnailStats.entries} 张 · ${formatBytes(state.thumbnailStats.bytes)}"
            },
            onClick = onOpenThumbnailCache,
        )
    }
}

@Composable
private fun SettingsMenuGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun SettingsMenuItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 二级：字幕 ────────────────────────────────────────────────────

@Composable
private fun SubtitleSettingsSection(
    subtitlePrefs: SubtitlePreferences,
    onDefaultLanguageChange: (SubtitleLanguage) -> Unit,
    onTextSizeChange: (Int) -> Unit,
    onOutlineChange: (Boolean) -> Unit,
    onCommitStyle: () -> Unit,
    onReset: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("默认字幕语言", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "打开视频时，优先自动选中的内嵌字幕轨道所用的语言。" +
                    "默认简体中文（sc）。片源没有匹配语言的字幕时，保留视频自带的第一条字幕。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 语言选项用一排单选式的文字按钮，项数少（5 个），比下拉菜单更直观
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SubtitleLanguage.entries.forEach { language ->
                    LanguageOptionRow(
                        language = language,
                        selected = subtitlePrefs.defaultLanguage == language,
                        onClick = { onDefaultLanguageChange(language) },
                    )
                }
            }

            SectionDivider()

            Text("字幕样式", style = MaterialTheme.typography.titleMedium)

            LabeledSlider(
                label = "字号",
                valueText = SubtitlePreferenceCodec.describeTextSize(subtitlePrefs.textSizeSp),
                value = subtitlePrefs.textSizeSp.toFloat(),
                range = SubtitlePreferenceCodec.MIN_TEXT_SIZE_SP.toFloat()..
                    SubtitlePreferenceCodec.MAX_TEXT_SIZE_SP.toFloat(),
                onChange = { onTextSizeChange(it.roundToInt()) },
                onChangeFinished = onCommitStyle,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("描边", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (subtitlePrefs.outlineEnabled) "细黑边" else "关（纯白字）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = subtitlePrefs.outlineEnabled,
                    onCheckedChange = {
                        onOutlineChange(it)
                        onCommitStyle()
                    },
                )
            }

            TextButton(onClick = onReset) { Text("恢复默认") }
        }
    }
}

@Composable
private fun LanguageOptionRow(
    language: SubtitleLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(language.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = language.tag,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 二级：弹幕（原播放页「弹幕设置」面板搬到这里）─────────────────

@Composable
private fun DanmakuSettingsSection(
    prefs: DanmakuPreferences,
    onToggle: (Boolean) -> Unit,
    onAlphaChange: (Int) -> Unit,
    onTextScaleChange: (Int) -> Unit,
    onScrollSecondsChange: (Float) -> Unit,
    onStrokeWidthChange: (Float) -> Unit,
    onDisplayAreaChange: (Int) -> Unit,
    onCommitStyle: () -> Unit,
    onApplyBlockRules: (String) -> Unit,
    onReset: () -> Unit,
) {
    // 屏蔽词是「编辑 → 应用」两段式（改完要重建时间轴，不能每敲一个字就跑一次）
    var rulesDraft by remember(prefs.blockRulesRaw) { mutableStateOf(prefs.blockRulesRaw) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("显示弹幕", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (prefs.enabled) "开启" else "关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = prefs.enabled, onCheckedChange = onToggle)
            }

            SectionDivider()

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
                text = "弹幕只铺在画面上部这块区域里。调小它 → 可用轨道变少 → 挤不进来的弹幕会被丢掉。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            SectionDivider()

            OutlinedTextField(
                value = rulesDraft,
                onValueChange = { rulesDraft = it },
                label = { Text("屏蔽词（一行一条）") },
                placeholder = { Text("前排\n看过的都懂\n/^\\d+$/  ← 斜杠包裹表示正则") },
                minLines = 3,
                maxLines = 6,
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
                ) {
                    Text("应用屏蔽词")
                }
                TextButton(onClick = onReset) { Text("恢复默认") }
            }
            Text(
                text = "普通词按「包含」匹配，/斜杠包裹/ 的按正则匹配。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 二级：凭据 ────────────────────────────────────────────────────

@Composable
private fun CredentialsSection(
    state: SettingsUiState,
    applyUrl: String,
    onApply: () -> Unit,
    onAppIdChange: (String) -> Unit,
    onAppSecretChange: (String) -> Unit,
    onToggleSecretVisible: () -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    onAskClear: () -> Unit,
) {
    StatusCard(configured = state.configured, applyUrl = applyUrl, onApply = onApply)

    OutlinedTextField(
        value = state.appId,
        onValueChange = onAppIdChange,
        label = { Text("AppId") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = state.appSecret,
        onValueChange = onAppSecretChange,
        label = { Text("AppSecret") },
        singleLine = true,
        visualTransformation = if (state.secretVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = onToggleSecretVisible) {
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
            onClick = onTestConnection,
            enabled = !state.verifying && state.inputComplete,
            modifier = Modifier.weight(1f),
        ) {
            if (state.verifying) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("测试连接")
            }
        }

        Button(
            onClick = onSave,
            enabled = state.inputComplete,
            modifier = Modifier.weight(1f),
        ) {
            Text("保存")
        }
    }

    VerifyResultText(state.verifyResult)

    TextButton(onClick = onAskClear, enabled = state.configured) {
        Text("清除凭据")
    }

    Text(
        text = "凭据只保存在本机应用私有目录，且不参与系统备份；" +
            "AppSecret 不会写入 APK，也不会打进任何日志。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── 二级：缓存 ────────────────────────────────────────────────────

@Composable
private fun CacheSection(
    stats: DanmakuCacheStats,
    clearing: Boolean,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    CacheCard(
        stats = stats,
        clearing = clearing,
        onClear = onClear,
    )
    OutlinedButton(onClick = onRefresh) { Text("刷新缓存统计") }
}

// ── 二级：缩略图缓存 ──────────────────────────────────────────────

@Composable
private fun ThumbnailCacheSection(
    stats: ThumbnailCacheStats,
    clearing: Boolean,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("缩略图缓存", style = MaterialTheme.typography.titleMedium)

            Text(
                text = if (stats.isEmpty) {
                    "暂无缓存。文件列表里的视频缩略图会缓存到本地，减少重复抽帧。"
                } else {
                    "${stats.entries} 张缩略图 · ${formatBytes(stats.bytes)}"
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
                    Text("清除缓存")
                }
            }

            Text(
                text = "缩略图缓存用于加速文件列表的加载；清除后再次浏览会重新生成，" +
                    "不影响视频文件本身。缓存最多占用约 128 MB，超出后自动回收最久未用的部分。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    OutlinedButton(onClick = onRefresh) { Text("刷新缓存统计") }
}

/**
 * 弹幕缓存区块。
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
private fun StatusCard(configured: Boolean, applyUrl: String, onApply: () -> Unit) {
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
                TextButton(onClick = onApply) { Text(applyUrl) }
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

/** 设置页里的分组分隔线。 */
@Composable
private fun SectionDivider() {
    androidx.compose.material3.HorizontalDivider()
}

/**
 * 带标题与数值的滑杆（设置页专用）。
 *
 * [onChangeFinished] 挂在 `onValueChangeFinished` 上（松手时才回调），
 * 用来把「拖动中的每帧改动」和「落库」分开。
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
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
