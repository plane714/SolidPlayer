package com.solidplayer.ui.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Primary = Color(0xFF4C6FBF)
private val PrimaryDark = Color(0xFF9DB4E8)
private val Secondary = Color(0xFFB4525F)
private val SecondaryDark = Color(0xFFE8A7AE)

private val LightColors = lightColorScheme(
    primary = Primary,
    secondary = Secondary,
    background = Color(0xFFF7F8FB),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    secondary = SecondaryDark,
    background = Color(0xFF101216),
    surface = Color(0xFF191C22),
)

@Composable
fun SolidPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    // 状态栏/导航栏的「图标明暗」由系统按**窗口主题**决定，Compose 的配色改不到它，
    // 所以要在运行时补一刀（themes.xml 里的 windowLightStatusBar 只管窗口主题那一份）。
    //
    // 为什么不能只靠 values-night 覆盖：manifest 里 MainActivity 的 configChanges 含 uiMode，
    // 切系统深浅色时 Activity 不重建 → XML 主题不会重载 → 图标会一直停在旧的一档
    // （表现为「切了系统主题，得重启 App 状态栏图标才变」）。
    //
    // 判据用 darkTheme 而不是再读一次 isSystemInDarkTheme()：它是这份配色的唯一真源，
    // 以后加「应用内手动切 浅色/深色/跟随系统」时，这里不用改。
    val view = LocalView.current
    val activity = LocalActivity.current
    SideEffect {
        // 把判断放进 lambda 而不是写成 if (…) SideEffect { … }：避免条件式调用副作用 API。
        if (view.isInEditMode) return@SideEffect
        val window = activity?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
