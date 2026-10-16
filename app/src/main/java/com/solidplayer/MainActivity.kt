package com.solidplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.solidplayer.ui.navigation.SolidPlayerNavHost
import com.solidplayer.ui.theme.SolidPlayerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全 App 统一 edge-to-edge。
        //
        // Android 15+（targetSdk ≥ 35）本来就会强制，这行主要是让旧版本表现一致：
        // 它负责状态栏/导航栏透明化、按当次配置给图标一个正确的初始明暗，
        // 并在 API 21~27 的导航栏上补一层系统标准的半透明 scrim。
        //
        // 必须在 setContent 之前调用；此后 MainActivity 不再干预 decorFitsSystemWindows
        // （播放页只在进出沉浸式时临时改它，且退出时不再还原，见 PlayerScreen）。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SolidPlayerTheme {
                SolidPlayerNavHost()
            }
        }
    }
}
