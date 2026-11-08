<h1 align="center">Kept You waiting huh?</h1>

<p align="center"><b>SolidPlayer</b> —— 为本地番剧配上弹幕的轻量安卓播放器</p>

<p align="center">
  <img alt="platform" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="kotlin" src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white">
  <img alt="compose" src="https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="media3" src="https://img.shields.io/badge/Media3-1.11.1-FF6F00">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-green">
</p>

---

## 简介

SolidPlayer 是一款**纯本地**的安卓番剧播放器：把你硬盘里的视频交给它，它会自动识别「这是哪部番剧的第几集」，
再从**弹弹play 开放弹幕网络**拉取对应弹幕，叠加在画面上播放。

没有在线片源、没有账号体系、没有视频上传 —— 视频始终留在你的设备上，App 只做**扫描 → 识别 → 拉弹幕 → 渲染**这一条链路。

> 一个视频文件 ↔ 一个 `episodeId`（番剧集编号），这是识别线与弹幕线之间唯一的耦合点；也是整个项目的设计基石。

## 核心特性

### 媒体库与扫描
- **自动发现本地视频**：按绝对路径聚合为「文件夹」，进入即见，无需手动导入。
- **NEW 标记**：7 天内新增且未看过的剧集自动打标，点开即消。
- **下拉刷新** / 扫描结果实时落库，适配大容量存储与不同 Android 版本的读取权限（API 30+ 使用 `MANAGE_EXTERNAL_STORAGE`，API 29 降级只读 MediaStore）。

### 智能识别
- **哈希 + 文件名双保险**：前 16MB MD5 哈希 + 文件名规则解析，精准定位番剧与集数。
- **批量识别**：单批上限 32、批间退避，鉴权/网络异常即中止，绝不无脑重试。
- **保守自动绑定**：仅当匹配结果唯一明确时才自动绑定，多候选一律不猜，交给你手动选择。
- 识别结果持久化，二次打开不再重复联网。

### 自研弹幕引擎
- **Canvas 硬件加速渲染**：不依赖第三方弹幕库，与播放内核解耦，只吃「播放位置 + 画面尺寸」两个输入。
- **轨道智能分配**：按显示区域高度计算轨道数，挤不进轨道的弹幕自动丢弃，避免叠字。
- **显示区域可调**：滑杆实时调整弹幕占屏比例。
- **倍速/拖拽严格同步**：位置由播放时钟直接推导，seek 与尺寸变化时才重置。
- 支持**屏蔽词**、**时间轴偏移**、解析期过滤，弹幕本地缓存（命中 24h / 空结果 7 天）。

### 沉浸式播放
- 单击切换控制栏显隐（3.5s 自动隐藏），长按 **3 倍速**，横滑快进/快退（满屏 ≈ 90s）。
- **方向循环**：跟随 / 锁横 / 锁竖；「跟随」尊重系统旋转锁定（锁定开着就不转）。
- 底部「下一集」原地换源，预测性返回自定义关闭，转场干净。
- 全局 edge-to-edge、深色模式自适应。

### 字幕
- 基于 Media3 内置字幕层，支持字号（12~40sp，默认 22sp）、描边开关、默认字幕语言。
- 内嵌字幕样式可被手动覆盖，透明背景不遮挡画面。

### 文件夹管理
- **图钉置顶**（最多 50 个）与**长按拖拽排序**，所见即所存。
- 文件夹详情页展示剧集列表，溢出菜单可**删除原文件**（二次确认点名文件，不可恢复）。
- 自研手势系统：点击与长按共用一次判定，边界稳定。

### 设置与缓存
- 弹弹play 凭据**由用户手动填写**并持久化于 DataStore，**不打包进 APK**。
- 「弹幕」「字幕」一级菜单集中管理播放偏好，与播放页共用同一份配置、双向即时生效。
- 弹幕缓存与**缩略图落盘缓存**可查看占用、一键清理（缩略图存 `cacheDir`，不误删用户数据）。

## 下载

前往 [**Releases**](https://github.com/plane714/SolidPlayer/releases/latest) 下载最新的 `solidplayer-<version>-release.apk`。

- 系统要求：**Android 7.0+（minSdk 24）**
- 通用包，覆盖常见 ABI
- 首次使用：在「设置」里填入你自己的[弹弹play 开放平台](https://doc.dandanplay.com/) `AppId` / `AppSecret`，否则只能扫描与播放、无法匹配弹幕

## 技术栈

| 维度 | 选型 |
|---|---|
| 语言 | Kotlin 2.2.10 |
| UI | Jetpack Compose（Material 3，BOM 2026.09） |
| 播放内核 | Media3 / ExoPlayer 1.11.1（单核） |
| 弹幕渲染 | 自研 Canvas 引擎（SurfaceView 叠加 + 硬件加速） |
| 网络 | Retrofit + OkHttp + kotlinx.serialization（签名拦截器） |
| 数据库 | Room 2.8.5 |
| 配置 | DataStore (Preferences) |
| 依赖注入 | Hilt 2.60.1 |
| 异步 | Coroutines + Flow；后台任务走应用级作用域（不引 WorkManager） |
| 构建 | AGP 9.4.1 + Gradle；compileSdk / targetSdk 37，minSdk 24 |

## 工程结构

单向依赖，`core:danmaku` 为纯逻辑模块（不依赖播放器，只依赖自己定义的 `PlayerClock` 接口）：

```
solidplayer/
├── app/            # :app           Compose 导航 + Hilt 装配 + 主题
└── core/
    ├── data/       # :core:data     网络签名/DTO、凭据、哈希、文件名解析、扫描、Room、Repository
    ├── player/     # :core:player   Media3 封装、PlayerClock
    └── danmaku/    # :core:danmaku  解析、轨道分配、Canvas 渲染（无 Compose）
```

更多设计细节见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 构建

需要 JDK 17+ 与 Android SDK（compileSdk 37）。

```bash
# Debug 包
./gradlew :app:assembleDebug

# Release 包（需本地配置签名）
./gradlew :app:assembleRelease
```

> 本项目开发环境为离线构建，实际执行时可能需要设置 `JAVA_HOME` 与 `GRADLE_USER_HOME`，并加 `--offline`。

## 致谢与声明

- 弹幕数据来自 [弹弹play 开放平台](https://doc.dandanplay.com/)。
- 技术思路同源 [xyoye/DanDanPlayForAndroid](https://github.com/xyoye/DanDanPlayForAndroid)，本项目代码独立实现。
- 本项目仅供个人学习与本地媒体播放使用，请勿用于任何商业或侵权用途。

## License

[MIT](LICENSE) © 2026 张三
