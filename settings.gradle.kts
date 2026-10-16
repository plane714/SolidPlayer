pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "solidplayer"

// P0 只起 4 个模块（见 docs/ARCHITECTURE.md §3.1）：
//   :app            Compose 导航 + Hilt 装配 + 设置页/媒体库页/播放页
//   :core:danmaku   弹幕解析 + 轨道分配 + Canvas 渲染（纯 UI 无关，不依赖 :core:player）
//   :core:player    Media3 封装 + PlayerClock 实现
//   :core:data      network / credential / hash / parser / scanner / db / repository
include(":app")
include(":core:danmaku")
include(":core:player")
include(":core:data")
