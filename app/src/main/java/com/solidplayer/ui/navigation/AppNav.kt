package com.solidplayer.ui.navigation

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.solidplayer.ui.library.FolderDetailScreen
import com.solidplayer.ui.library.FolderListScreen
import com.solidplayer.ui.permission.StorageAccessGate
import com.solidplayer.ui.player.PlayerScreen
import com.solidplayer.ui.settings.SettingsScreen

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val PLAYER_ARG_ID = "mediaFileId"
    const val PLAYER = "player/{$PLAYER_ARG_ID}"

    /** 文件夹详情：参数是**绝对路径**，必须整体编码（见 [folder]）。 */
    const val FOLDER_ARG_PATH = "folderPath"
    const val FOLDER = "folder/{$FOLDER_ARG_PATH}"

    fun player(mediaFileId: Long): String = "player/$mediaFileId"

    /**
     * 目录路径里带 `/`，直接拼进路由会把它当成路径分隔符，
     * 「folder/A/B/C」就匹配不上只有一个参数位的 `folder/{folderPath}` 了。
     */
    fun folder(path: String): String = "folder/${Uri.encode(path)}"
}

@Composable
fun SolidPlayerNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Routes.LIBRARY,
        // ── 返回方向不做任何过渡 ──────────────────────────────────────
        //
        // 为什么要显式关掉：本项目 targetSdk 37，而 **Android 16（API 36）起，系统对
        // 目标版本 ≥36 的应用默认开启「预测性返回」**（不再需要 manifest 里
        // `enableOnBackInvokedCallback="true"` 的显式申请）。开启后，侧滑返回的过程中
        // 系统会拿 pop 动画渲染出一份「上一页的预览」跟着手指走 —— 在播放页这种
        // 全屏沉浸的界面上，预览会露出后面媒体库的一角，观感很碎。
        //
        // Navigation Compose 的预测性返回预览就是**手势进度驱动的 pop 过渡**，
        // 所以把 pop 两侧的过渡设为 None，手势期间就没有可预览的动画了 ——
        // 返回退化成一次瞬切，这正是「取消返回动画」想要的效果。
        //
        // 刻意只改返回方向：进入播放页 / 设置页仍然保留默认过渡，不是把所有动画一刀切掉。
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {

        composable(Routes.LIBRARY) {
            // 首页就是「自动发现的视频文件夹」，没有存储权限时用图三那张授权页挡住
            StorageAccessGate {
                FolderListScreen(
                    onOpenFolder = { path -> navController.navigate(Routes.folder(path)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
        }

        composable(
            route = Routes.FOLDER,
            arguments = listOf(navArgument(Routes.FOLDER_ARG_PATH) { type = NavType.StringType }),
        ) {
            FolderDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenPlayer = { mediaFileId -> navController.navigate(Routes.player(mediaFileId)) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument(Routes.PLAYER_ARG_ID) { type = NavType.LongType }),
        ) {
            PlayerScreen(onBack = { navController.popBackStack() })
        }
    }
}
