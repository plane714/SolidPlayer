package com.solidplayer.ui.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 媒体库需要的存储权限判定。
 *
 * - **API 30+**：`MANAGE_EXTERNAL_STORAGE`（「所有文件访问」）。这是唯一能让 `java.io.File`
 *   读到共享目录的权限 —— targetSdk 远高于 30，分区存储是强制的，
 *   给 `READ_EXTERNAL_STORAGE` 也读不到目录树。
 * - **API ≤ 29**：`READ_EXTERNAL_STORAGE` 运行时权限。
 *
 * 该权限**无法在应用内弹窗申请**，只能把用户送到系统设置页（图三那个界面）手动打开，
 * 回来后在 `ON_RESUME` 重新判定。
 */
object StorageAccess {

    /** 权限是否已就绪。 */
    fun granted(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> allFilesGranted()
        else -> readPermissionGranted(context)
    }

    /** 是否走「所有文件访问」这套（决定提示文案与跳转目标）。 */
    fun usesAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private fun allFilesGranted(): Boolean =
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    private fun readPermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** 跳到系统的「所有文件访问」授权页，优先直接落到本应用的条目上。 */
    fun allFilesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
}

/**
 * 没权限就挡住内容，显示图三同款的授权引导页。
 *
 * 关键在于**回到前台要重新判定**：用户在系统设置里打开开关后返回，
 * 没有任何回调会通知我们，只能靠生命周期事件。
 */
@Composable
fun StorageAccessGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(StorageAccess.granted(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = StorageAccess.granted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val readPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    if (granted) {
        content()
        return
    }

    StorageAccessScreen(
        allFilesAccess = StorageAccess.usesAllFilesAccess(),
        onGrant = {
            if (StorageAccess.usesAllFilesAccess()) {
                val direct = context.let(StorageAccess::allFilesSettingsIntent)
                runCatching { context.startActivity(direct) }.onFailure {
                    // 个别 ROM 没有「按包名直达」的入口，退回权限总列表页
                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
            } else {
                readPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        },
    )
}

/** 图三那张授权页的翻版：标题 + 一个开关行 + 一段说明。 */
@Composable
private fun StorageAccessScreen(allFilesAccess: Boolean, onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 这是唯一一个不走 Scaffold 的全屏页，edge-to-edge 下没人替它躲系统栏，
            // 必须自己让开（含状态栏/导航栏/刘海）。
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = if (allFilesAccess) "所有文件访问权限" else "存储访问权限",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.size(20.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onGrant)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (allFilesAccess) "授予管理所有文件的权限" else "授予读取本地视频的权限",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = false,
                    onCheckedChange = null,
                )
            }
        }

        Spacer(modifier = Modifier.size(20.dp))

        Text(
            text = if (allFilesAccess) {
                "同意此授权意味着您允许该应用访问、读取、修改、删除此设备或任何已连接存储设备上" +
                    "公共存储空间的所有文件。\n\n" +
                    "solidplayer 只用它来发现本机视频并读取弹幕与字幕文件，不会上传或修改您的任何文件。\n\n" +
                    "该权限需要您在本机系统设置里手动打开。"
            } else {
                "授予读取本地视频后，应用才能扫描出手机里含视频的文件夹。\n\n" +
                    "solidplayer 只用它来发现本机视频，不会上传或修改您的任何文件。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onGrant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (allFilesAccess) "前往系统设置开启" else "立即授权")
        }

        Spacer(modifier = Modifier.size(8.dp))

        Text(
            text = "授权后返回本页即可自动刷新",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
