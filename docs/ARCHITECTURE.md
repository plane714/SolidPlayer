# solidplayer 架构设计文档

> Android 本地番剧播放器 —— 自动识别本地视频文件，匹配弹弹play 开放弹幕网络的弹幕。
>
> 文档版本：v1.1 ｜ 最后更新：2026-10-13
> 接口依据：`https://api.dandanplay.net/swagger/v2/swagger.json`（swagger 缓存见 `.workbuddy/tmp/sw.json`）
>
> **v1.1 变更**：凭据交付方式由「内置 APK + R8 混淆」调整为「**用户手动填入并持久化**」。
> AppId/AppSecret 不再进入 APK，§12.1 的密钥泄露风险随之消除；新增 §4.5 凭据配置。

---

## 1. 项目概述

### 1.1 目标

用户在本地（或外置存储）存放番剧视频文件，App 需要做到：

1. **自动识别** —— 通过文件哈希 + 文件名，判断这是「哪部番剧的第几集」
2. **匹配弹幕** —— 用识别出的节目编号 `episodeId` 从弹弹play 拉取弹幕
3. **渲染弹幕** —— 在播放画面上按时间轴叠加渲染弹幕，与视频严格同步

### 1.2 核心不变量

这是整个架构的基石，所有设计都围绕它展开：

> **一个视频文件只能对应一个 `episodeId`；`episodeId` 是识别线与弹幕线之间唯一的耦合点。**

```
本地文件 ──[识别线：hash + 文件名]──> episodeId ──[弹幕线：拉取 + 渲染]──> 屏幕上滚动的弹幕
                                          ▲
                                    唯一的耦合点
```

由此推导出两条硬约束：

- **约束 A**：弹幕引擎与播放内核解耦。弹幕层只依赖两个输入 —— 播放位置（含倍速）和画面尺寸。换播放器不影响弹幕。
- **约束 B**：`episodeId` 必须持久化。同一个文件第二次打开时不应重新走网络识别。

---

## 2. 技术选型

| 维度 | 选型 | 说明 |
|---|---|---|
| 语言 | Kotlin | — |
| UI | Jetpack Compose | 播放状态、弹幕设置均为流驱动，Compose 表达力更好 |
| 播放内核 | **Media3 (ExoPlayer) 单核** | 不引入 ijkplayer / libVLC；覆盖绝大多数番剧片源 |
| 弹幕渲染 | **自研 Canvas 引擎** | `SurfaceView` 叠加 + 硬件加速 Canvas；高密度再考虑 GLES |
| 网络 | Retrofit + OkHttp + kotlinx.serialization | 签名逻辑挂在 OkHttp Interceptor |
| 数据库 | Room | 媒体库、映射关系、缓存索引 |
| 配置 | DataStore (Preferences) | 弹幕样式、屏蔽词、AppId/AppSecret |
| 后台 | WorkManager | 批量哈希与批量识别 |
| DI | Hilt | — |
| 异步 | Coroutines + Flow | — |
| minSdk | 21 | 与现有 DanDanPlay 保持一致 |
| 凭据交付 | **用户手动填入，持久化于 DataStore** | AppId/AppSecret 不进 APK；首次启动引导用户配置（见 §4.5） |

---

## 3. 工程结构与模块划分

### 3.1 Gradle 模块

```
solidplayer/
├── settings.gradle.kts
├── app/                        # :app  —— Compose 导航 + Hilt 装配 + Application
├── core/
│   ├── common/                 # :core:common —— Result、Dispatcher、扩展函数、日志
│   ├── network/                # :core:network —— Retrofit/OkHttp + 签名拦截器 + DTO
│   ├── database/               # :core:database —— Room Entity/DAO/Migration
│   ├── player/                 # :core:player —— Media3 封装 + PlayerClock
│   └── danmaku/                # :core:danmaku —— 解析 + 轨道分配 + Canvas 渲染
├── data/                       # :data —— Repository 实现，串起 network/database/preference
└── feature/
    ├── library/                # :feature:library —— 媒体库界面
    ├── player/                 # :feature:player —— 播放器界面 + 弹幕设置面板
    └── settings/               # :feature:settings —— 凭据配置 + 全局偏好
```

**P0 起手只需 4 个模块**：`:app`、`:core:player`、`:core:danmaku`、`:core:data`。
等 UI 逻辑变复杂了，再把 `feature` 拆出来。过早拆模块只会增加构建配置的心智负担。

### 3.2 分层与依赖规则

```
        表现层  feature/library · feature/player
                       │  （只依赖 domain 暴露的 UseCase / Repository 接口）
        业务层  data/repository: MediaRepository · MatchRepository
                DanmakuRepository · PlaybackController
                       │
        数据层  MediaScanner · HashCalculator · DandanplayApi · DanmakuCache
                       │
      基础设施  OkHttp(签名拦截器) · Room · DataStore · WorkManager
```

**依赖方向单向向下，禁止反向依赖**。`core:danmaku` 是纯 UI 无关模块，**不允许**依赖 `core:player`（只依赖自己定义的 `PlayerClock` 接口）。

### 3.3 包结构

```
com.solidplayer
├── core.player
│   ├── SolidPlayer.kt            # Media3 ExoPlayer 生命周期封装
│   ├── PlayerClock.kt            # 时钟抽象（danmaku 依赖它）
│   └── Media3PlayerClock.kt      # PlayerClock 的 Media3 实现
├── core.danmaku
│   ├── model/                    # DanmakuItem / DanmakuMode / DanmakuTimeline
│   ├── parser/                   # PFieldParser / XmlParser(兼容外部 XML)
│   ├── track/                    # TrackAllocator（轨道分配）
│   ├── render/                   # DanmakuSurfaceView / DanmakuRenderer
│   └── DanmakuEngine.kt          # 引擎门面
├── core.network
│   ├── DandanplayApi.kt          # Retrofit 接口定义
│   ├── DandanplaySigner.kt       # 签名算法
│   ├── DandanplaySignInterceptor.kt
│   └── dto/                      # MatchRequest / MatchResponseV2 / CommentResponseV2 ...
└── data
    ├── scanner/MediaScanner.kt
    ├── hash/FileHashCalculator.kt
    ├── parser/FileNameParser.kt      # 文件名解析规则引擎
    ├── credential/
    │   ├── DandanplayCredentials.kt      # data class
    │   └── DandanplayCredentialStore.kt  # DataStore 持久化 + 内存缓存
    └── repository/               # MediaRepository / MatchRepository
                                  # DanmakuRepository / CredentialRepository
```

---

## 4. 弹弹play 开放平台接入

### 4.1 接口清单

基地址：`https://api.dandanplay.net`

| 用途 | 接口 | 说明 |
|---|---|---|
| 单文件识别 | `POST /api/v2/match` | 核心接口，见 §4.2 |
| **批量识别** | `POST /api/v2/match/batch` | 请求 `{requests:[MatchRequest]}` → `{results:[{success, fileHash, matchResult}]}`。媒体库首扫用它一次提交，省掉 N 次往返 |
| 手动搜索 | `GET /api/v2/search/episodes` | `anime`、`episode`、`v2` → `animes[].episodes[].episodeId` |
| 作品搜索 | `GET /api/v2/search/anime` | `keyword` → `animes[]`（仅作品级，无集数） |
| 番剧详情 | `GET /api/v2/bangumi/{bangumiId}` | 含 `episodes[].episodeId / episodeNumber / episodeTitle` |
| 拉取弹幕 | `GET /api/v2/comment/{episodeId}` | 见 §4.3 |
| 发送弹幕 | `POST /api/v2/comment/{episodeId}/app` | 需登录，弹幕进「应用私有弹幕库」 |
| 登录 | `POST /api/v2/login` | 返回 `token`(JWT)，后续 `Authorization: Bearer {token}` |

> ⚠️ **v2 中不存在** `/api/v2/related` 和 `/api/v2/extcomment`。第三方弹幕已全部并入 `withRelated=true`。
> 网上大量老教程仍在讲这两个接口，照抄会直接 404。

### 4.2 文件识别 `POST /api/v2/match`

**请求体 `MatchRequest`**：

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `fileName` | string | 是 | 文件名（含扩展名，不含路径） |
| `fileHash` | string | 是 | 文件前 16MB 的 MD5（32 位小写十六进制） |
| `fileSize` | integer | 是 | 文件总字节数 |
| `videoDuration` | integer | 建议 | 视频时长（秒）。提供可显著提高识别率 |
| `matchMode` | enum | 是 | `hashAndFileName` / `fileNameOnly` / `hashOnly` |

**`matchMode` 选型**：固定用 `hashAndFileName`。服务端逻辑本身就是「先按 hash 精确查找，失败后按文件名模糊搜索」，一次调用覆盖两种策略，不需要客户端自己重试。

**响应 `MatchResponseV2`**（继承 `ResponseBase`）：

```
MatchResponseV2 : ResponseBase {
    isMatched: boolean          # true = 已精确关联到某个弹幕库
    matches:   MatchResultV2[]  # 按可能性从高到低排序
}

MatchResultV2 {
    episodeId:       long    # ★ 弹幕库编号（剧集 ID）
    animeId:         long    # 作品编号
    animeTitle:      string  # 作品标题
    episodeTitle:    string  # 剧集完整标题
    type:            string  # AnimeType 枚举
    typeDescription: string  # 类型中文描述
    shift:           number  # 语义待实测确认，P0 先忽略
    imageUrl:        string  # 海报地址
}
```

**结果处理决策表**（对应流程图）：

| 条件 | 处理 |
|---|---|
| `isMatched == true` | **精确关联**，此时 `matches` 只有 1 条，客户端应**直接自动绑定**，不要弹窗打扰用户 |
| `matches.size > 1` | 弹窗让用户从候选列表中选择，默认高亮第一条 |
| `matches` 为空 | 走手动搜索：用文件名解析出的标题调 `/api/v2/search/episodes` |
| 请求失败 | 标记为「识别失败」，不阻塞播放，允许用户在播放页手动匹配 |

**`AnimeType` 枚举值**（`type` 字段）：
`tvseries`、`tvspecial`、`ova`、`movie`、`musicvideo`、`web`、`other`、`jpmovie`、`jpdrama`、`unknown`、`tmdbtv`、`tmdbmovie`

### 4.3 拉取弹幕 `GET /api/v2/comment/{episodeId}`

**查询参数**：

| 参数 | 默认 | 取值 |
|---|---|---|
| `from` | `0` | 起始弹幕编号，忽略此编号之前的弹幕（增量拉取用） |
| `withRelated` | `false` | **固定传 `true`** —— 一并返回所有第三方关联网址的弹幕 |
| `chConvert` | `0` | `0` 不转换 / `1` 转简体 / `2` 转繁体 |

> ⚠️ 该接口**返回 302 跳转到弹幕加速服务**。OkHttp 默认自动跟随重定向，但需注意：**加速服务域名不需要签名**（见 §4.4）。

**响应 `CommentResponseV2`**：

```
CommentResponseV2 {
    count:    integer        # 弹幕总数
    comments: CommentData[]
}

CommentData {
    cid: long    # 弹幕编号
    p:   string  # "出现时间,模式,颜色,用户ID"
    m:   string  # 弹幕正文
}
```

**`p` 字段格式详解**（官方定义，与 B 站 XML 不同，不可照抄 B 站解析器）：

| 位置 | 含义 | 格式 |
|---|---|---|
| 0 | 出现时间 | 秒，两位小数，如 `12.34`、`445.6` |
| 1 | 模式 | `1` 普通滚动 ／ `4` 底部 ／ `5` 顶部 |
| 2 | 颜色 | 32 位整数，`R × 65536 + G × 256 + B`，R/G/B ∈ [0,255] |
| 3 | 用户 ID | 字符串，通常为数字，不含特殊字符 |

---

### 4.4 鉴权与签名

所有请求需携带三个请求头：

```
X-AppId:     {appId}
X-Timestamp: {unix 时间戳，单位：秒}
X-Signature: {签名}
```

**签名算法**：

```
Signature = Base64( SHA256( AppId + Timestamp + Path + AppSecret ) )
```

**三个必须注意的坑**：

1. **`Path` 不包含域名，也不包含查询参数**。
   - ✅ `/api/v2/comment/123450001`
   - ❌ `https://api.dandanplay.net/api/v2/comment/123450001?withRelated=true`
2. 拼接顺序严格为 `AppId → Timestamp → Path → AppSecret`，中间无分隔符。
3. **302 跳转到的加速服务不应再附加签名头**。签名与 Path 强绑定，跳转后 Path 已变，附加原签名必然错误。做法是在拦截器里判断 host，只对 `api.dandanplay.net` 签名（见下方实现）。*加速服务是否要求独立鉴权，接入时需实测确认。*

**参考实现 —— `DandanplaySigner.kt`**：

```kotlin
package com.solidplayer.core.network

import android.util.Base64
import java.security.MessageDigest

object DandanplaySigner {

    /**
     * 生成弹弹play 开放平台签名。
     *
     * @param appId     应用 ID
     * @param timestamp Unix 时间戳（秒）
     * @param path      接口路径，不含域名与查询参数，如 "/api/v2/comment/123450001"
     * @param appSecret 应用密钥
     * @return Base64 编码的签名字符串
     */
    fun sign(appId: String, timestamp: Long, path: String, appSecret: String): String {
        val raw = appId + timestamp + path + appSecret
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
```

**参考实现 —— `DandanplaySignInterceptor.kt`**：

```kotlin
package com.solidplayer.core.network

import okhttp3.Interceptor
import okhttp3.Response

class DandanplaySignInterceptor(
    private val credentialStore: DandanplayCredentialStore,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() / 1000 },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 只对官方 API 域名签名；302 跳转后的加速服务不需要签名
        if (request.url.host != API_HOST) {
            return chain.proceed(request)
        }

        // 凭据由用户配置，可能尚未填写。
        // 这里读的是内存缓存（@Volatile），不做阻塞 IO —— 拦截器运行在网络线程上，
        // 绝不能 runBlocking 去读 DataStore。
        val credentials = credentialStore.current()
            ?: throw MissingCredentialException()

        // encodedPath 天然不含域名与查询参数，正好符合签名要求
        val path = request.url.encodedPath
        val timestamp = timestampProvider()
        val signature = DandanplaySigner.sign(
            appId = credentials.appId,
            timestamp = timestamp,
            path = path,
            appSecret = credentials.appSecret,
        )

        val signed = request.newBuilder()
            .header("X-AppId", credentials.appId)
            .header("X-Timestamp", timestamp.toString())
            .header("X-Signature", signature)
            .build()

        return chain.proceed(signed)
    }

    companion object {
        const val API_HOST = "api.dandanplay.net"
    }
}
```

**OkHttp 客户端装配**：

```kotlin
@Provides
@Singleton
fun provideOkHttpClient(credentialStore: DandanplayCredentialStore): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(DandanplaySignInterceptor(credentialStore))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)          // 必须开启：comment 接口依赖 302 跳转
        .followSslRedirects(true)
        .build()
```

> 注意：拦截器持有的是**凭据容器**而不是凭据本身。用户中途改了 AppId，
> 无需重建 `OkHttpClient`，下一次请求自动生效。

---

### 4.5 凭据配置（AppId / AppSecret）

AppId 与 AppSecret 由用户自行在开发者中心申请，**首次启动后手动填入**，不进入 APK。
这样既避免了 §12.1 的密钥逆向风险，也省掉了自建代理服务。代价是首次使用需要一次配置动作。

#### 4.5.1 存储设计

- **存储位置**：DataStore Preferences，落在应用私有目录 `/data/data/com.solidplayer/files/datastore/`
- **可见性**：非 root 设备上其他应用不可读；卸载即清除
- **备份**：必须设置 `android:allowBackup="false"`，或将 datastore 目录加入备份排除规则，避免凭据被云备份带出

> 明文存储的取舍：Android 应用私有目录本身已提供沙箱隔离，对本项目（个人使用）足够。
> 若日后需要更高强度，可在 `DandanplayCredentialStore` 内加一层 AES 加解密，密钥托管到 Android Keystore，
> 上层调用方无需改动 —— 这正是把凭据读写收口到单一类的原因。

#### 4.5.2 参考实现 —— `DandanplayCredentialStore.kt`

```kotlin
package com.solidplayer.data.credential

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class DandanplayCredentials(
    val appId: String,
    val appSecret: String,
)

@Singleton
class DandanplayCredentialStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val appScope: CoroutineScope,
) {
    /** 内存缓存：让拦截器可以在非协程上下文同步读取，避免网络线程上阻塞读盘 */
    @Volatile
    private var cached: DandanplayCredentials? = null

    /** 供拦截器同步调用。返回 null 表示用户尚未配置 */
    fun current(): DandanplayCredentials? = cached

    /** 供 UI 观察配置状态，驱动「未配置」提示条 */
    val credentials: Flow<DandanplayCredentials?> = dataStore.data.map { prefs ->
        val id = prefs[KEY_APP_ID].orEmpty()
        val secret = prefs[KEY_APP_SECRET].orEmpty()
        if (id.isBlank() || secret.isBlank()) null
        else DandanplayCredentials(id, secret)
    }

    init {
        // 预热缓存，保证进程启动后第一个网络请求就能读到凭据
        appScope.launch { credentials.collect { cached = it } }
    }

    /** 冷启动时若缓存尚未就绪，等待首次读取完成 */
    suspend fun awaitLoaded(): DandanplayCredentials? {
        if (cached == null) cached = credentials.first()
        return cached
    }

    suspend fun save(appId: String, appSecret: String) {
        dataStore.edit { prefs ->
            prefs[KEY_APP_ID] = appId.trim()
            prefs[KEY_APP_SECRET] = appSecret.trim()
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_APP_ID)
            prefs.remove(KEY_APP_SECRET)
        }
    }

    private companion object {
        val KEY_APP_ID = stringPreferencesKey("dandanplay_app_id")
        val KEY_APP_SECRET = stringPreferencesKey("dandanplay_app_secret")
    }
}
```

**为什么用 `@Volatile` 内存缓存 + Flow 同步，而不是让拦截器直接读 DataStore**：
拦截器运行在 OkHttp 的网络线程上，DataStore 是挂起 API，在那里 `runBlocking` 会阻塞网络线程、
严重时触发 OkHttp 的连接超时。用「后台收集 Flow → 写入 volatile 字段 → 拦截器同步读」这个模式，
既保证读取是 O(1) 且无阻塞，又保证用户改完凭据后下一次请求立即生效。

#### 4.5.3 未配置凭据时的异常

```kotlin
package com.solidplayer.core.network

import java.io.IOException

/** 用户尚未配置 AppId/AppSecret。由拦截器抛出，Repository 捕获后转为友好提示 */
class MissingCredentialException : IOException("弹弹play AppId/AppSecret 尚未配置")
```

#### 4.5.4 首次启动与降级策略

**核心原则：凭据缺失不阻塞本地能力。**

```
App 启动
  └─ 读取凭据（awaitLoaded）
       ├─ 已配置 ──→ 正常进入媒体库
       └─ 未配置 ──→ 依然进入媒体库，顶部常驻一条可点击提示：
                      「尚未配置弹弹play 凭据，点击前往设置」

本地目录扫描 / 本地播放  ──→ 始终可用（不依赖网络）
文件识别 match            ──→ 阻断，提示先配置凭据
拉取弹幕 comment          ──→ 阻断，按「无弹幕」静默降级播放
```

之所以不做成「启动即强制跳转配置页」，是因为凭据只影响网络功能。
让用户先能浏览和播放本地文件，再在真正需要识别时引导配置，体验更顺。

#### 4.5.5 设置页要点（`:feature:settings`）

| 元素 | 说明 |
|---|---|
| AppId 输入框 | 明文显示，允许粘贴 |
| AppSecret 输入框 | 默认掩码（`••••••••`），带「显示」切换按钮 |
| **测试连接** 按钮 | 用**尚未保存的**输入值做一次轻量校验（见下） |
| 保存 / 清除凭据 | 保存后提示「已保存」；清除需二次确认 |
| 状态显示 | 「已配置 / 未配置」+ 申请入口链接（https://www.dandanplay.com/open.html） |

**「测试连接」的实现要点**：它必须能校验**用户刚输入但还没保存**的凭据，
所以不能走 `credentialStore`（那里还是旧值）。做法是手工构造一次性请求：

```kotlin
suspend fun verify(input: DandanplayCredentials): Boolean = withContext(Dispatchers.IO) {
    // 选一个轻量且无需登录的接口作为探针
    val path = "/api/v2/bangumi/shin"
    val timestamp = System.currentTimeMillis() / 1000
    val request = Request.Builder()
        .url("https://${DandanplaySignInterceptor.API_HOST}$path")
        .header("X-AppId", input.appId)
        .header("X-Timestamp", timestamp.toString())
        .header("X-Signature",
            DandanplaySigner.sign(input.appId, timestamp, path, input.appSecret))
        .build()

    runCatching {
        bareClient.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}
```

`bareClient` 是一个**不带签名拦截器**的裸 `OkHttpClient`（否则拦截器会覆盖掉手工设的请求头）。

---

## 5. 文件哈希计算

**规则：取文件前 16MB（16 × 1024 × 1024 字节）计算 MD5。**

这是识别准确率的关键 —— 弹弹play 的服务端索引就是按这个规则建的。用整文件 MD5 会永远匹配不上。

**参考实现 —— `FileHashCalculator.kt`**：

```kotlin
package com.solidplayer.data.hash

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

class FileHashCalculator(private val resolver: ContentResolver) {

    suspend fun md5OfFirst16MB(uri: Uri): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = HASH_LENGTH

        resolver.openInputStream(uri)?.use { input ->
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read <= 0) break          // 文件小于 16MB，读到 EOF 即止
                md.update(buffer, 0, read)
                remaining -= read
            }
        } ?: throw IOException("无法打开输入流: $uri")

        md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val HASH_LENGTH = 16L * 1024 * 1024   // 16MB
        const val BUFFER_SIZE = 64 * 1024
    }
}
```

**三个实现要点**：

1. **必须用 SAF 流式读取**。Android 10+ 拿到的 `content://` URI 无法转成真实文件路径，只有 `ContentResolver.openInputStream()` 这一条路。
2. **读满 16MB 立刻停止**，不要 `readBytes()`。一个 4GB 的 BD 原盘会直接 OOM。
3. **哈希结果必须缓存**，键为 `(uri, size, lastModified)` 三元组。同一文件重复计算 16MB 的 I/O 是无法接受的（一次扫描 100 个文件就是 1.6GB 的读取量）。

---

## 6. 文件名解析规则引擎

哈希匹配失败时的兜底手段，同时也是手动搜索的输入源。设计成**有序规则表**，按优先级依次尝试，先命中先返回。

### 6.1 预处理步骤

```
原始文件名: [Sakura][BDRip][1080p][x264_FLAC] 进击的巨人 - 07v2 [GB].mkv
    ↓ 1. 去掉扩展名
[Sakura][BDRip][1080p][x264_FLAC] 进击的巨人 - 07v2 [GB]
    ↓ 2. 移除方括号内的「非标题」内容（字幕组/画质/编码/语言标记）
进击的巨人 - 07v2
    ↓ 3. 提取集数（见 6.2 规则表）
title="进击的巨人", season=null, episode=7
```

### 6.2 集数提取规则表

按优先级从上到下匹配，命中即停：

| 优先级 | 规则名称 | 正则（示意） | 匹配示例 |
|---|---|---|---|
| 1 | 季集标记 | `[Ss](\d{1,2})[Ee](\d{1,3})` | `Title S02E07.mkv` → S2 E7 |
| 2 | 中文话数 | `第\s*(\d{1,3})\s*[话話集]` | `[组] 标题 第07话 [1080p]` |
| 3 | 方括号集数 | `\]\[\s*(\d{1,3})\s*\]\[` | `[组][标题][07][BDRip]` |
| 4 | 分隔符集数 | `[\s\-_]+(\d{1,3})(?:v\d)?[\s\.]` | `[组] 标题 - 07 [x264].mp4` |
| 5 | 结尾纯数字 | `(\d{1,3})(?:v\d)?\.[A-Za-z0-9]{2,4}$` | `标题 07.mp4` |
| 6 | 无集数 | 以上均不匹配 | 剧场版 / OVA → `episode = null` |

**特殊形态处理**：

- `C1` / `S1` / `O1` 前缀（花絮 / 特别篇 / OVA）：保留前缀作为集数原始值，`episode` 存字符串而非整数
- `v2` / `v3` 后缀（修正版）：剥离后缀，不参与集数解析
- 集数超过 999：大概率不是集数（可能是年份或分辨率），跳过

### 6.3 需要剥离的噪声词表

预处理阶段命中以下词表的内容一律丢弃（大小写不敏感）：

| 类别 | 词表 |
|---|---|
| 画质 | `1080p` `720p` `2160p` `4K` `8K` `FHD` `HD` `SD` |
| 编码 | `x264` `x265` `H264` `H265` `HEVC` `AVC` `10bit` `8bit` |
| 片源 | `BDRip` `BDRemux` `WEB-DL` `WEBRip` `HDTV` `DVDRip` `BluRay` |
| 音频 | `AAC` `FLAC` `DTS` `AC3` `EAC3` `TrueHD` `Ma10p` |
| 字幕 | `GB` `BIG5` `CHS` `CHT` `JPSC` `JPTC` `简繁` `简体` `繁体` |
| 容器 | `MKV` `MP4` `AVI` `RMVB` |

### 6.4 输出结构

```kotlin
data class ParsedFileName(
    val raw: String,
    val title: String,          // 清洗后的标题，用于 search API
    val season: Int?,           // 季数，无则 null
    val episode: String?,       // 集数，保留字符串以兼容 C1/S1/O1 形态
    val confidence: Float,      // 规则命中置信度，供 UI 排序参考
)
```

**单测要求**：每一条规则至少 3 个正例 + 2 个反例。这个模块的正确率直接决定手动匹配的体验，必须可回归。

---

## 7. 数据库设计（Room）

### 7.1 建表 DDL

> Room Entity 是唯一事实来源。以下 DDL 是设计意图的表达，用于 review 与人工核对；
> 实际请开启 `exportSchema = true`，让 Room 生成 schema JSON 并纳入版本管理，以便写 Migration。

```sql
-- ── 媒体文件表：核心表，一个文件对应一个 episodeId ────────────────
CREATE TABLE media_file (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    uri            TEXT    NOT NULL,           -- content:// URI
    document_id    TEXT,                       -- DocumentsContract 的 documentId，用于去重
    display_name   TEXT    NOT NULL,           -- 文件名（含扩展名）
    relative_path  TEXT,                       -- 相对路径，用于分组展示
    size_bytes     INTEGER NOT NULL,           -- 文件总大小
    last_modified  INTEGER NOT NULL,           -- 用于失效检测
    duration_ms    INTEGER NOT NULL DEFAULT 0, -- 视频时长
    file_hash      TEXT,                       -- 前 16MB 的 MD5
    parsed_title   TEXT,                       -- 文件名解析结果
    parsed_season  INTEGER,
    parsed_episode TEXT,                       -- 字符串，兼容 C1/S1/O1
    anime_id       INTEGER,                    -- 识别结果：作品编号
    episode_id     INTEGER,                    -- ★ 识别结果：弹幕库编号
    match_type     TEXT    NOT NULL DEFAULT 'none',  -- none/hash/filename/manual
    confidence     REAL    NOT NULL DEFAULT 0, -- 匹配置信度
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL,
    UNIQUE(uri)
);
CREATE INDEX idx_media_file_hash      ON media_file(file_hash);
CREATE INDEX idx_media_file_episode   ON media_file(episode_id);
CREATE INDEX idx_media_file_document  ON media_file(document_id);

-- ── 作品表 ────────────────────────────────────────────────────
CREATE TABLE anime (
    anime_id   INTEGER PRIMARY KEY,            -- 来自 API
    title      TEXT    NOT NULL,
    type       TEXT,                           -- AnimeType 枚举字符串
    image_url  TEXT,
    rating     REAL,
    is_on_air  INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL
);

-- ── 剧集表 ────────────────────────────────────────────────────
CREATE TABLE episode (
    episode_id     INTEGER PRIMARY KEY,        -- 来自 API
    anime_id       INTEGER NOT NULL,
    episode_title  TEXT,
    episode_number TEXT,                       -- 短标题，可含字母
    air_date       TEXT,
    FOREIGN KEY(anime_id) REFERENCES anime(anime_id) ON DELETE CASCADE
);
CREATE INDEX idx_episode_anime ON episode(anime_id);

-- ── 弹幕缓存索引（原始弹幕另存为文件，不入库）────────────────────
CREATE TABLE danmaku_cache (
    episode_id   INTEGER PRIMARY KEY,
    source       TEXT    NOT NULL DEFAULT 'dandanplay',
    with_related INTEGER NOT NULL DEFAULT 1,
    ch_convert   INTEGER NOT NULL DEFAULT 0,
    count        INTEGER NOT NULL DEFAULT 0,   -- 弹幕条数，0 表示空缓存
    file_path    TEXT    NOT NULL,             -- 原始 JSON 落盘路径
    fetched_at   INTEGER NOT NULL,             -- 用于 TTL 判断
    etag         TEXT
);

-- ── 播放历史 ──────────────────────────────────────────────────
CREATE TABLE play_history (
    media_file_id     INTEGER PRIMARY KEY,
    position_ms       INTEGER NOT NULL DEFAULT 0,
    duration_ms       INTEGER NOT NULL DEFAULT 0,
    danmaku_offset_ms INTEGER NOT NULL DEFAULT 0,  -- 每集的弹幕时轴偏移
    updated_at        INTEGER NOT NULL,
    FOREIGN KEY(media_file_id) REFERENCES media_file(id) ON DELETE CASCADE
);
```

### 7.2 设计说明

- **弹幕原文不入库**。一条弹幕库动辄上万条记录，进 SQLite 会让媒体库查询变慢。策略是**原始 JSON 落盘 + DB 只存索引**（`danmaku_cache`），需要时按 `file_path` 读文件解析。
- **弹幕样式偏好不进 DB**。透明度、字号、速度、描边宽度、显示区域、屏蔽词是全局偏好，放 DataStore；只有「每集时轴偏移」是跟内容绑定的，放 `play_history.danmaku_offset_ms`。
- **`match_type` 区分匹配来源**，用于 UI 展示角标（自动 / 手动），也用于重扫时决定是否覆盖已有结果：**`manual` 优先级最高，重扫不得覆盖**。
- **`file_hash` 建索引**是必要的。批量识别时需要用 hash 反查已有记录，避免重复请求。

---

## 8. 弹幕引擎设计

### 8.1 数据模型

```kotlin
enum class DanmakuMode { SCROLL, TOP, BOTTOM }

data class DanmakuItem(
    val timeMs: Long,          // 出现时间（毫秒）
    val mode: DanmakuMode,
    val color: Int,            // 0xRRGGBB
    val text: String,
    val textWidth: Float = 0f, // 懒测量后缓存，渲染循环内不再测量
)

/** 按 timeMs 升序排列的不可变时间轴 */
class DanmakuTimeline(val items: List<DanmakuItem>)
```

### 8.2 `p` 字段解析

```kotlin
fun parseP(p: String): Triple<Long, Int, Int>? {
    val parts = p.split(',')
    if (parts.size < 3) return null
    val timeMs = (parts[0].toFloatOrNull() ?: return null).let { (it * 1000).toLong() }
    val mode   = parts[1].toIntOrNull() ?: return null
    val color  = parts[2].toIntOrNull() ?: return null
    return Triple(timeMs, mode, color)
}

// 模式映射：注意与 B 站不同！
// 1 -> SCROLL, 4 -> BOTTOM, 5 -> TOP
```

**解析必须容错**：线上弹幕数据总有脏数据，任何一条字段缺失都只跳过该条，不能中断整个解析。

### 8.3 时钟抽象 `PlayerClock`

弹幕引擎与 Media3 解耦的关键：

```kotlin
interface PlayerClock {
    val positionMs: Long
    val durationMs: Long
    val speed: Float
    val isPlaying: Boolean

    fun addListener(listener: Listener)

    interface Listener {
        /** 位置发生跳变（seek / 播放器重建），引擎必须重置时间轴 */
        fun onPositionDiscontinuity(newPositionMs: Long)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onSpeedChanged(speed: Float)
        fun onVideoSizeChanged(width: Int, height: Int)
    }
}
```

**Media3 实现要点**：包一层 `Player.Listener`，把 `onPositionDiscontinuity`、`onPlaybackStateChanged`、`onPlaybackParametersChanged`、`onVideoSizeChanged` 转发出来即可。

> ⚠️ **绝对不要自建 `Handler` / `Timer` 计时**。自建计时器一定会因为帧率和系统调度产生累计漂移，表现为「看久了弹幕越来越不准」。**唯一正确的时钟来源是 `player.currentPosition`**。

### 8.4 轨道分配算法

这是弹幕引擎的性能核心。

**轨道数计算**：

```
trackCount = floor(可用高度 / (弹幕高度 + 行间距))
弹幕高度 = textSize × textScale
```

**滚动弹幕（SCROLL）**：

- 每条轨道维护 `freeAtMs` —— 该轨道下一次可以接纳弹幕的时间点
- 一条宽度为 `w` 的弹幕进入宽度为 `W` 的屏幕，速度为 `v`（px/s，通常取 `W / 8s`）
- 该弹幕**完全离屏**所需时间：`tLeave = (W + w) / v`
- 该弹幕**完全进入屏幕**所需时间：`tEnter = w / v`
- 轨道可接纳新弹幕的条件：`now >= freeAtMs`
- 接纳后更新：`freeAtMs = now + max(tLeave, 前一条弹幕的 tEnter 需求)`（保证不追尾）

**顶部 / 底部弹幕（TOP / BOTTOM）**：

- 独占轨道，固定展示时长（默认 4s，可配置）
- 同样维护 `freeAtMs`，`now >= freeAtMs` 才可接纳
- 顶部从上方轨道开始找，底部从下方轨道开始找

**无可用轨道时**：直接丢弃该弹幕（不渲染）。这是行业通行做法，强行渲染会导致叠字。

**性能优化**：按时间分桶（如每 5 秒一桶）做预分组，渲染时只需处理当前桶和相邻桶，避免每帧全量扫描上万条弹幕。

### 8.5 渲染循环

```
Choreographer.FrameCallback 每帧触发：
  1. now = playerClock.positionMs
  2. 从有序时间轴取出 [lastFramePos, now] 区间内新激活的弹幕
     （用游标指针单向前进，seek 时才二分重定位）
  3. 为新弹幕分配轨道
  4. 更新活跃集合中每条弹幕的位置
  5. 移除已离屏的弹幕，回收对象到对象池
  6. Canvas 绘制：
       - 按计算好的位置 drawText
       - 默认**实心**：只画一层文字填充，颜色取弹幕自带色（无颜色数据时为纯白）
       - 若配了描边宽度（`strokeWidthDp > 0`）则先画半透明黑描边再画填充；
         **默认 1.2dp**，可在弹幕设置面板调整，拖到 0 = 纯实心
       - ★ 颜色与透明度必须**一次合成**（`DanmakuColor.withAlpha(rgb, alpha)`）：
         弹幕颜色只有 24 位、alpha 恒为 0，且 `Paint.color` 是 32 位整体赋值 ——
         先 `Paint.alpha` 再 `Paint.color` 会把透明度抹成 0（详见 §17.3）
  7. lastFramePos = now
```

**渲染循环内三条铁律**：

1. **零分配**。不在 `onDraw` / 帧回调里 `new` 任何对象、不拼字符串。对象池 + `SparseArray` 复用。
2. **文字宽度只测一次**。`Paint.measureText()` 有开销，测量后缓存进 `DanmakuItem.textWidth`。
3. **`Paint` 复用**。按颜色缓存 `Paint` 实例，不要每条弹幕新建。

### 8.6 同步重置时机

以下事件必须重置引擎内部时间轴（清空活跃弹幕 + 重定位游标）：

| 事件 | 触发源 |
|---|---|
| Seek / 快进快退 | `PlayerClock.Listener.onPositionDiscontinuity` |
| 暂停 → 播放 | `onPlaybackStateChanged` |
| 倍速切换 | `onSpeedChanged` |
| 画面尺寸变化（含旋屏） | `onVideoSizeChanged` |
| 弹幕时轴偏移调整 | UI 主动调用 `engine.setOffset(ms)` |

**弹幕时轴偏移**：`effectiveTime = danmakuTime - offsetMs`。用户手动微调（±0.5s 步进）后写入 `play_history.danmaku_offset_ms`，下次打开自动生效。

---

## 9. 关键流程时序

### 9.1 首次打开一个视频文件

```
用户点击文件
   │
   ├─ 1. 查 media_file 表：已有 episode_id？
   │        ├─ 有 ────────────────────────────┐
   │        └─ 无                              │
   │             ↓                             │
   ├─ 2. 计算前 16MB MD5                        │
   │        （先查 (uri,size,lastModified) 缓存）│
   │             ↓                             │
   ├─ 3. POST /api/v2/match                     │
   │             ↓                             │
   ├─ 4. 结果处理（见 §4.2 决策表）              │
   │        · isMatched → 自动绑定              │
   │        · 多候选    → 用户选择              │
   │        · 无结果    → 搜索 API 手动匹配      │
   │             ↓                             │
   ├─ 5. 写入 media_file（episode_id + match_type）
   │             ↓                             │
   ├─ 6. 启动播放器 ──────────────────────────←─┘
   │             │
   │             ├─ 7. 查 danmaku_cache：命中且未过期？
   │             │        ├─ 是 → 读本地文件
   │             │        └─ 否 ↓
   │             │             GET /api/v2/comment/{episodeId}?withRelated=true
   │             │             → 落盘 + 写 danmaku_cache
   │             │
   │             └─ 8. 解析弹幕 → 构建 DanmakuTimeline
   │                          → 交给 DanmakuEngine
   │                          → 绑定 PlayerClock，开始渲染
```

**关键设计**：第 6 步播放器启动**不等待**第 7 步弹幕拉取。弹幕晚到就晚渲染，绝不阻塞起播。网络慢的时候用户先看到画面，弹幕随后出现。

### 9.2 媒体库全量扫描

```
WorkManager 后台任务
   │
   ├─ 1. 扫描目录（MediaStore 或 SAF tree）
   ├─ 2. 过滤视频扩展名（mkv/mp4/avi/rmvb/flv/wmv/mov/ts/m2ts）
   ├─ 3. 逐文件计算 hash（限并发，建议 2-3 个并行，避免 I/O 饱和）
   │        · 命中 (uri,size,lastModified) 缓存则跳过
   ├─ 4. 文件名解析（本地，零成本）
   ├─ 5. 组装成批 → POST /api/v2/match/batch（分批，每批 ≤ 32 个，见 §15.3）
   ├─ 6. 写库：media_file.episode_id / anime_id / match_type
   │        · match_type = 'manual' 的记录不覆盖
   └─ 7. 剩余未匹配的 → 标记待手动处理
```

> **⚠️ 本节已被 §22 修订**：第 5~7 步的「扫描后自动批量识别」**已取消**。
> 现实里扫描是纯本地动作，识别只认「媒体库的显式按钮」与「播放页点开文件」两个入口。
> 本节保留作为原始设计与分期依据，实际行为以 §22 为准。

---

## 10. 缓存与限流策略

弹弹play 官方明确提示：**调用量过大或过于频繁会触发检测与拦截机制**。必须做到：

| 策略 | 做法 |
|---|---|
| 弹幕缓存 | `danmaku_cache.fetched_at` 做 TTL 判断，建议 **≥ 24 小时**。命中缓存直接读本地文件，不发请求 |
| 空结果缓存 | `count == 0` 也要缓存，且 TTL 更长（避免反复请求确定没有资源的冷门剧集） |
| 单飞（single-flight） | 同一 `episodeId` 的并发请求合并为一个 `Deferred`，用 `Mutex` + `ConcurrentHashMap<Long, Deferred<T>>` 实现 |
| 退避重试 | 429 / 5xx 走指数退避（1s → 2s → 4s），最多 3 次。4xx（除 429）不重试 |
| 请求串行化 | 批量识别分批提交，批与批之间加间隔，不要打满 |
| 缓存哈希 | 避免重复计算 16MB，直接减少 I/O |
| UA 标识 | 设置可识别的 User-Agent，便于官方识别来源 |

---

## 11. 错误处理约定

**统一响应基类 `ResponseBase`**：

```
ResponseBase {
    errorCode:    integer   # 0 表示成功
    success:      boolean
    errorMessage: string
    errorDetail:  string
}
```

**分层约定**：

- 网络层**不抛业务异常**，返回 `sealed interface ApiResult<out T>`（`Success` / `Failure(code, message)`）
  （传输层异常如 `MissingCredentialException`、`IOException` 由 Repository 的 `safeApiCall` 统一捕获并映射为 `Failure`）
- Repository 层负责把 `ApiResult` 转成 `Flow<Result<T>>`；`errorCode != 0` 时按以下分类处理：

| 错误类型 | 处理 |
|---|---|
| **未配置凭据**（`MissingCredentialException`） | 引导跳转设置页；这是正常的初始状态，不算错误，不打告警日志 |
| 鉴权失败（签名错 / AppId 或 AppSecret 无效） | 提示「AppId 或 AppSecret 有误」，**不重试**，跳转设置页并高亮「测试连接」 |
| 网络不可达 | 静默降级：无弹幕播放，播放页顶部给出可点击的提示条 |
| 识别无结果 | 不视为错误，正常引导用户手动匹配 |
| 弹幕为空（`count == 0`） | 正常状态，提示「该剧集暂无弹幕」 |
| 服务端 5xx / 429 | 退避重试；最终失败降级为无弹幕 |

**核心原则：任何弹幕相关的失败都不能阻塞播放。**

---

## 12. 风险与对策

### 12.1 凭据管理（v1.1 起已降级，不再是高危项）

**原风险**：AppSecret 若内置在 APK 中，反编译即可提取并冒用 —— 且这是签名算法公开带来的固有问题，
R8 混淆只能提高门槛，无法根治。

**现状**：凭据改由用户手动填入并持久化（见 §4.5），**AppSecret 不进入 APK**，该风险已消除。

剩余需要守住的三条底线：

| 项 | 要求 |
|---|---|
| 不进 APK | `BuildConfig` / `strings.xml` / `AndroidManifest.xml` 中不得出现任何 AppId、AppSecret 字面量 |
| 不进日志 | 网络日志必须脱敏，禁止打印 `X-Signature` 头与 AppSecret（调试期尤其容易踩） |
| 不进备份 | 设置 `android:allowBackup="false"`，或把 datastore 目录加入备份排除规则 |

**为未来留的余地**：若日后要面向多用户分发、且用户不愿自行申请凭据，
可再引入自建签名代理服务。届时只需替换 `DandanplayCredentialStore` 的实现，
`DandanplaySignInterceptor` 与所有 Repository 代码均无需改动。

### 12.2 弹幕与画面时钟漂移

**对策**：严格以 `player.currentPosition` 为主时钟（见 §8.3），并在 `onPositionDiscontinuity` 时重置时间轴。可加轻量漂移校正：每 5 秒对比一次引擎内部推算位置与播放器实际位置，偏差超过 100ms 就强制重置游标。

### 12.3 SAF 路径限制

`content://` URI 拿不到真实路径，`File` API 完全不可用。

**对策**：全链路走 `ContentResolver` / `DocumentsContract`。展示用的「路径」从 `documentId` 反推，不可用于文件操作。

### 12.4 文件名解析准确率

命名极其混乱，规则表不可能覆盖 100%。

**对策**：规则表可单测、可迭代；解析出的 `title` 只作为搜索 API 的输入，最终结果由用户确认；`parsed_title` 入库便于后续分析失败案例，持续补规则。

### 12.5 大数据量扫描性能

首扫 100 个文件 = 1.6GB 读取 + 100 次网络请求。

**对策**：WorkManager 后台执行 + 哈希缓存 + `match/batch` 批量接口 + 限制并发数。UI 上给出进度条而非阻塞等待。

---

## 13. 分期实施计划

| 阶段 | 目标 | 验收标准 |
|---|---|---|
| **P0 骨架** | 凭据设置页 → 单目录扫描 → 手填文件名 → `match` → 拉弹幕 → 基础 Canvas 渲染 | 端到端能看上有弹幕的番 |
| **P1 媒体库** | 哈希自动匹配 + Room 媒体库 + 搜索手动匹配 + `match/batch` 批量识别 | 无需手动干预即可识别大部分文件 |
| **P2 播放增强** | 弹幕设置（透明度/字号/速度/描边宽度/屏蔽词/时轴偏移）+ 缓存策略 + 错误降级 | 可日用 |
| **P3 进阶** | 登录发弹幕、追番同步、远程播放、第三方源整合 | — |

**P0 优先验证的三个技术风险点**（越早暴露越好）：

1. 签名拦截器是否正确（错一个字段全盘 401）
2. `p` 字段解析是否正确（模式值与 B 站不同）
3. 弹幕与画面的时钟同步是否准确（决定整个播放体验）

---

## 14. 编码约定

- 网络层不抛业务异常，统一返回 `ApiResult`
- 所有磁盘 / 网络 I/O 显式切到 `Dispatchers.IO`
- 弹幕渲染循环内**零对象分配**
- API 调用集中在 `core:network`，禁止在 UI 层直接发起网络请求
- 常量集中管理，禁止魔法数字散落（如 `16 * 1024 * 1024` 必须命名）
- 关键算法（签名、哈希、文件名解析、轨道分配）必须带单测
- **凭据只允许通过 `DandanplayCredentialStore` 读写**，禁止在其他任何地方出现 AppId/AppSecret 字面量或绕过该类直读 DataStore

---

## 附录 A：接口字段速查

```
POST /api/v2/match
  req:  { fileName, fileHash, fileSize, videoDuration, matchMode }
  resp: { errorCode, success, errorMessage, isMatched, matches[ ] }
        matches[]: { episodeId, animeId, animeTitle, episodeTitle,
                     type, typeDescription, shift, imageUrl }

POST /api/v2/match/batch
  req:  { requests: [ MatchRequest ] }
  resp: { errorCode, success, results[ ] }
        results[]: { success, fileHash, matchResult }

GET  /api/v2/search/episodes?anime=&episode=&v2=true
  resp: { hasMore, animes[ ] }
        animes[]: { animeId, animeTitle, type, typeDescription,
                    episodes[ ]: { episodeId, episodeTitle } }

GET  /api/v2/bangumi/{bangumiId}
  resp: { animeId, bangumiId, animeTitle, imageUrl, rating,
          type, typeDescription, summary, titles[], seasons[],
          episodes[ ]: { seasonId, episodeId, episodeTitle,
                         episodeNumber, airDate } }

GET  /api/v2/comment/{episodeId}?from=0&withRelated=true&chConvert=0
  resp: { count, comments[ ] }
        comments[]: { cid, p, m }
        p = "出现时间(秒),模式(1/4/5),颜色(R*65536+G*256+B),用户ID"

POST /api/v2/login
  resp: { token(JWT), userId, userName, email, tokenExpireTime }
        → 后续请求头 Authorization: Bearer {token}
```

## 附录 B：参考资料

- 开放平台文档：https://doc.dandanplay.com/open/
- 接口调试工具（swagger）：https://api.dandanplay.net/swagger/v2/swagger.json
- 开发者中心（申请 AppId）：https://www.dandanplay.com/open.html
- 本地 swagger 缓存：`.workbuddy/tmp/sw.json`

---

## 15. P1「媒体库」落地记录（2026-10-14）

§13 的 P1 已实现并跑通构建。**本节记录实现与设计稿的差异，后续维护以本节为准。**

### 15.1 交付内容

| P1 需求（§13） | 实现位置 |
|---|---|
| 哈希自动匹配 | `MediaRepository.fingerprint()`：`(uri,size,lastModified)` 命中缓存即跳过 16MB 读取；哈希与时长**一次打开文件同时取**（原先分两次调用等于解析两遍容器） |
| Room 媒体库 | `MediaFileDao.observeLibraryDetailed()`：`media_file LEFT JOIN anime LEFT JOIN episode` 一次给齐标题 |
| 搜索手动匹配 | `MatchRepository.searchEpisodes()` + `bindFromSearch()`；UI 为 `ManualSearchDialog`（作品列表可展开选集） |
| `match/batch` 批量识别 | `MatchRepository.identifyBatch()`：切批 → 提交 → 回填 → 落目录表；纯逻辑抽到 `BatchIdentifyPlanner` |
| 多目录管理 | `LibraryFolderStore` 由单 tree URI 改为 JSON 目录列表；旧 key 保留只读迁移兜底 |
| 识别进度 | `LibraryIdentifyCoordinator`（应用级作用域单例）+ `LibraryScreen` 进度条 |

**新增关键约束**：`MatchRepository.applyMatch()` 是识别结果回填的**唯一出口** ——
它同时写 `media_file` 与 `anime`/`episode` 目录表。目录表是媒体库「按番剧分组」的数据源，
漏写这一半，UI 就只能显示成 `animeId=123`。

### 15.2 与文档不一致的落地决策

1. **批量识别不跑 WorkManager。** 文档 §3.1 / §9.2 写的是 WorkManager，但本项目**离线构建**，
   `androidx.work` 不在 Gradle 离线缓存里，加依赖直接构建失败。改用
   **应用级作用域（`@ApplicationScope`）上的单例调度器** `LibraryIdentifyCoordinator`：
   用户切页面不中断识别，进度通过 `StateFlow` 暴露给任意页面。
   相比 WorkManager 只少了「进程被杀后续跑」，而识别本身**可重入**
   （`findUnmatched()` 只捞 `episodeId IS NULL`，重启后重点一次「识别全部」即等价续跑）。
2. **多目录走 DataStore JSON 列表，不新增表。** 目录是全局配置、条数个位数，
   为它建表 + Migration 不划算；`LibraryFolderStore` 保留旧单 URI key 作只读迁移兜底，
   升级用户不会丢已选目录。
3. **`identifyBatch` 遇到鉴权/网络失败立即中止**（而不是继续打后续批次），
   已回填的部分保留；`LibraryIdentifyCoordinator` 会把失败原因透出成一句话。
4. **「批量识别」不会覆盖 `manual` 结果**：SQL 层的 `WHERE matchType != 'manual'` 是硬保护，
   不依赖调用方自觉。

### 15.3 顺带修正

- §9.2 批量上限「每批 ≤ 50 个」→ **32**（swagger 实测，`DandanplayApi.BATCH_LIMIT`）。
  超出上限会整批被拒，所以 `BatchIdentifyPlanner.batches()` 是硬约束而非建议。
- §13 P1 的「MediaStore 自动发现」**未实现**：仍沿用 SAF 目录扫描。
  MediaStore 自动发现要读存储权限、并在 Android 10+ 上受分区存储限制，
  与「用户明确指定目录」的既定设计冲突，暂不引入。

### 15.4 验证

- `./gradlew :app:assembleDebug --offline` → BUILD SUCCESSFUL（`app-debug.apk`）
- `./gradlew test --offline` → **113 例全绿**（P0 的 95 例 + P1 新增 18 例）
- P1 新增单测：`LibraryFolderCodecTest`（持久化格式 + tree URI 解码，含 UTF-8 多字节）、
  `BatchIdentifyPlannerTest`（切批边界 + 结果对齐不错位）

### 15.5 尚未做（留给 P2）

- `MatchResultV2.shift`（弹幕偏移秒数）仍然**只记录不应用**，留到 P2 的弹幕设置一起做
- 弹幕样式（透明度 / 字号 / 速度 / 屏蔽词）与缓存清理策略
- 识别失败没有独立重试入口，目前靠再点一次「识别全部」

---

## 16. P2「播放增强」落地记录（2026-10-14）

§13 的 P2 已实现并跑通构建。**本节记录实现与设计稿的差异，后续维护以本节为准。**

### 16.1 交付内容

| P2 需求（§13） | 实现位置 |
|---|---|
| 弹幕透明度 / 字号 / 滚动速度 / 描边宽度 | `DanmakuPreferenceStore`（DataStore）+ `DanmakuPreferenceCodec`（钳位与编解码，纯逻辑带单测）；UI 为播放页右侧 `DanmakuSettingsPanel`，拖动即时预览 |
| 屏蔽词 | `DanmakuFilter`（纯逻辑，带单测）；接在 `DanmakuRepository.buildTimeline` 的**解析期** |
| 时轴偏移 | P0 已有；P2 补上「服务端 `shift` 自动落一次」（`MatchShift` + `MatchRepository.applyServerShift`） |
| 缓存策略 | `DanmakuRepository.cacheStats / clearCache / pruneExpiredCache` + `DanmakuCacheJanitor`（冷启动清理）；UI 为设置页「弹幕缓存」区块 |
| 错误降级 | `RetryPolicy`（429 / 5xx / 读超时退避重试）；播放页顶部提示条可点「重试」；媒体库「只重试这 N 个」 |
| 识别失败独立重试入口（§15.5 遗留） | `BatchIdentifySummary.failedIds` + `LibraryIdentifyCoordinator.retryFailed()` |

**新增关键约定**：弹幕样式偏好（透明度/字号/速度/描边宽度/开关/屏蔽词）**全部是全局偏好，进 DataStore 不进 DB**；
只有「每集时轴偏移」跟内容绑定，留在 `play_history.danmaku_offset_ms`。这条 §7.2 早有定论，P2 只是把它落实。

**DB 仍为 version 1**：P2 全程零 Migration。代价见 §16.2 第 3 条。

### 16.2 与文档不一致的落地决策

1. **弹幕设置在播放页的侧边面板里，不在设置页。**
   §13 只说「弹幕设置」，没说放哪。放播放页是为了**即时预览** —— 拖滑杆时画面上的弹幕立刻跟着变，
   不需要「改完退出去重进一次」这种反馈闭环。相应地 `SettingsScreen` 只保留「凭据 + 弹幕缓存」两件事
   （顶部标题已由「弹弹play 凭据」改为「设置」），职责不被撑大。
   （形态在 §17.2 从底部 `ModalBottomSheet` 改为**右侧滑出面板**。）

2. **屏蔽词在解析期过滤，不在渲染期。**
   §8.5 的三条铁律要求渲染循环内零额外开销。放渲染期意味着每帧对每条弹幕跑字符串/正则匹配；
   放解析期则只在构建时间轴时跑一次，代价是「改屏蔽词要重建时间轴」。
   而原始 JSON 就在本地（`DanmakuCacheStore`），重建是**零网络**的，所以这个代价可以忽略。
   屏蔽词规则语法：普通词按**包含**匹配，`/.../` 包裹的按**正则**匹配，都忽略大小写。

3. **服务端 `shift` 用「零 Migration」方案应用**（`play_history` 只有一列偏移，分不清来源）：
   `INSERT OR IGNORE` —— **只在还没有 `play_history` 行时写入**，已有记录一律放弃写入。
   宁可这次不用官方建议，也不能覆盖用户辛苦校准的偏移。
   **已知缝隙**：若该文件在识别之前就先播过一次（因此已写了行），这次回填的 `shift` 不会生效。
   根治要给 `play_history` 加一列来源标记 + DB v2 Migration，按「P2 零 Migration」原则暂不做。

4. **退避重试取「总尝试 3 次（2 次重试）」而不是 §10 的字面值。**
   §10 写「1s → 2s → 4s，最多 3 次」（即 4 次尝试）。但读超时设的是 20 秒，
   4 次尝试最坏要 `20×4 + 7 ≈ 87s` 才降级。§11 明确「任何弹幕相关的失败都不能阻塞播放」，
   所以宁可少试一次：**1s → 2s，总尝试 3 次**。429 / 5xx 响应快，2 次重试足够吃掉瞬时抖动。

5. **「网络不可达」不重试。** §11 要求「静默降级：无弹幕播放」，
   重试 3 次只会让用户白等约 3 秒才看到降级提示。只有 429 / 5xx / **读超时**算瞬时故障。

### 16.3 顺带修掉的 5 个真问题

P2 的「可调参数」把几个原本不会触发的坑照出来了，一并修掉：

| # | 问题 | 修法 |
|---|---|---|
| 1 | `TrackAllocator.updateLayout` 原先只在**宽高**变化时重建轨道数组。改字号后行高变了但轨道数不变，会分配出屏幕之外的轨道（弹幕画在画面下方看不见） | 行高（`trackHeightPx`）变化也触发重建 |
| 2 | `DanmakuRenderer.setConfig` 原先每次都清空按色 `Paint` 缓存、并把**整条时间轴**的 `textWidth` 归零。滑杆拖动时每帧对上万条弹幕重测文字宽度，帧率直接塌 | 改成**差异失效**：只有字号真变才清 `textWidth`，字号或不透明度变才清 `Paint` 缓存 |
| 3 | 滚动速度此前只有 `resize()` 会同步给轨道分配器 —— 运行期改速度**根本不生效** | `setConfig` 里按需调用 `applyAllocatorLayout()` |
| 4 | 速度中途变化会让**正在飘**的弹幕横向跳变（位置按 `x = W - v×elapsed` 算，`v` 一变全体位移） | `Active.speed` 锁定出场速度；位置与生命都按它算，改速度只影响**新出场**的弹幕 |
| 5 | `MAX_LOOKBACK_MS = 20s` 不足以覆盖最长弹幕生命周期。生命周期 `= scrollDuration × (1 + w/W)`，滚动时长上限 12s 时，一条 3 倍屏宽的长弹幕就是 48s —— seek 回来会漏掉仍在屏上的长弹幕 | 提到 **60s**。代价只是 seek 那一帧多激活几条已过期的，随即被回收 |

### 16.4 验证

- `./gradlew :app:assembleDebug --offline` → BUILD SUCCESSFUL（`app-debug.apk`，24 MB，
  versionCode 3 / `0.3.0-p2`）
- `./gradlew test --offline` → **151 例全绿**（P0 95 + P1 18 + P2 38），0 失败 0 错误
- P2 新增单测：`DanmakuFilterTest`(12)、`DanmakuPreferenceCodecTest`(12)、`RetryPolicyTest`(8)、`MatchShiftTest`(6)
- 编译只剩既有的 KT-73255 注解 target 警告

**踩坑**：早期版本把 DataStore 的读改写封成了一个泛型 helper，并按 `Key<Int>` / `Key<Float>`
写了两个重载 —— JVM 泛型擦除后它们撞成同一签名（`Key` + `Function1`），
报 `Platform declaration clash`。教训是：这类 helper **只能有一个泛型函数**，
需要区分的默认值必须作为参数显式传入。
（该 helper 已在 §17 的重构中删除，但约束依然适用于日后新增的 DataStore 封装。）

### 16.5 尚未做（留给 P3）

- **单飞（single-flight）**：§10 要求的「同一 `episodeId` 并发请求合并」仍未做。
  目前靠「弹幕晚到不阻塞播放」+ 缓存兜住，实际并发窗口极小。
- `MatchResultV2.shift` 的来源标记（需要 DB v2 Migration，见 §16.2 第 3 条）
- MediaStore 自动发现（§15.3 已说明为何不做）
- 播放倍速控件：`SolidPlayer.setSpeed` 与弹幕引擎的倍速无漂移设计都已就绪，但没有 UI 入口
- 登录发弹幕 / 追番同步 / 远程播放（§13 P3）

## 17. 缺陷修复记录（P3 之后）

### 17.1 弹幕设置面板的滑杆拖不动（2026-10-14）

**现象**：播放页 → 弹幕设置，透明度 / 字号 / 滚动时长三根滑杆怎么拖都不动。

**根因**：`DanmakuPreferenceStore` 的写入口把**新值丢了**。三个 setter 都是

```kotlin
suspend fun setAlphaPercent(percent: Int) = update(key, fallback) { it.coerceIn(MIN, MAX) }
```

而 `update` 的 transform 收到的是**存储里的旧值** —— 参数 `percent` 从头到尾没被用过。
于是「写入」实际是「把旧值钳位后再写回去」，DataStore 内容不变，
`preferences` 的 `distinctUntilChanged()` 一声不吭，`_state` 不更新，UI 自然不动。

**为什么滑杆一动都不动，而不是「拖了会弹回去」**：
Material3 的 `Slider` 是**完全受控**组件 —— `SliderState.dispatchRawDelta()` 里
只要 `onValueChange != null` 就只回调、**不写**内部 `value`，滑块位置 100% 由外部传入的
`value` 决定。外部不更新，滑块就一格都不走。

**修复**：
| 改动 | 说明 |
|---|---|
| `DanmakuPreferenceStore.setStyle(alpha, scale, scroll)` | 取代三个 setter；一次 `edit` 写回三项，并在写入侧统一钳位 |
| `PlayerViewModel.setXxx()` | 改为**同步更新内存** `_state.danmakuPrefs`（当帧跟手 + 弹幕即时预览），不再直接落库 |
| `PlayerViewModel.persistDanmakuStyle()` | 松手 / 面板关闭时把当前样式整体写回 DataStore |
| `LabeledSlider` / `DanmakuSettingsPanel` | 接上 `Slider.onValueChangeFinished` 作为提交时机 |

**顺带解决的问题**：原先「拖动 → 每帧写 DataStore → 等 Flow 绕回来」的链路，
即使写对了也会让滑杆等一次磁盘往返才动。改成内存先行后，拖动跟手且写盘次数从
「每帧一次」降到「每个手势一次」。

**验证**：`test` **161 例全绿**（新增 `DanmakuPreferenceStoreTest` 6 例，
断言形式固定为「写完再读出来比对」—— 这类静默失效只有这个角度测得出来）；
`:app:assembleDebug` 通过。

### 17.2 弹幕设置面板改为右侧滑出 + 弹幕默认「白色实心」（2026-10-14）

**需求**：设置面板从底部弹出改为贴右侧滑出（照弹弹play 的形态）；弹幕默认改为**实心**。

**面板**：`DanmakuSettingsSheet`（`ModalBottomSheet`）→ `DanmakuSettingsPanel`（自绘右侧浮层）。

| 维度 | 实现 |
|---|---|
| 位置与尺寸 | `BoxWithConstraints` + `AnimatedVisibility(align = CenterEnd)`；横屏（屏宽 > 600dp）固定 320dp，竖屏按 86% 宽 —— 竖屏手机上固定 320dp 会占满整屏 |
| 动画 | `slideInHorizontally / slideOutHorizontally` |
| 收起 | 点面板外侧（全屏点击层，`indication = null`）+ **返回键**（`BackHandler`） |
| 配色 | 面板永远盖在画面上，**不跟随 App 明暗主题**，写死深底白字（`PanelBackground/PanelLabel/PanelHint/PanelDivider`），滑杆与输入框各自显式指定颜色 |

**改形态连带的三处交互调整**（少任何一处都会出洋相）：

1. 面板打开时**收起上下控制栏** —— 面板纵向铺满整屏，留着控制栏会被盖掉右半边。
2. 面板打开时**停用画面手势层**（`pointerInput(solidPlayer, showSettings)` 里直接 return）。
   原因：手势层用的是 `awaitFirstDown(requireUnconsumed = false)`，**不理会事件是否已被上层消费**，
   不停用的话在滑杆上按住不放会一边拖滑杆一边触发 3 倍速，点滑杆还会顺手把控制栏切没。
   （`ModalBottomSheet` 时期不会有这个问题 —— 它是独立窗口，触摸根本到不了这一层。）
3. 面板必须是根 `Box` 的**最后一个子项**：它要盖住画面、盖住控制栏，点击层还得压住所有下层手势。

**弹幕默认实心**（⚠️ **已被 §17.4 取代，默认改回 1.2dp 细描边**）：
`DanmakuConfig.strokeWidthDp` 由 `1.2f` 改为 `0f`。
渲染器只在 `strokeWidthPx > 0` 时才画那一层黑边，所以默认就是「纯色填充」；
文字颜色取弹幕自带色，没有颜色数据时是纯白（`PFieldParser.DEFAULT_COLOR = 0xFFFFFF`）。
描边能力保留在字段上（亮背景下的可读性兜底），需要时调大即可。

> 代价说明：描边为 0 时，白字落在亮背景上会看不清 —— 后来发现这个代价不能接受，见 §17.4。
>
> ⚠️ **这次改动把 17.3 的缺陷引爆了**：关掉描边后弹幕一条都看不到，
> 因为此前唯一可见的图层就是那层描边 —— 填充图层一直是全透明的。

### 17.3 弹幕一条都不显示（2026-10-14）

**现象**：§17.2 把弹幕默认改成「实心」后，真机实测**画面上一条弹幕都没有**。
顶部状态栏仍显示「弹幕 N 条」，没有报错、没有崩溃、日志干净。

**根因**：文字填充图层的 alpha 一直是 0 —— 这不是新引入的缺陷，而是从 P0 就存在、被描边遮住的**潜伏缺陷**：

| 环节 | 位置 | 事实 |
|---|---|---|
| 解析 | `PFieldParser` | `color = rawColor and 0xFFFFFF` → **高 8 位（alpha）恒为 0** |
| 绘制 | `DanmakuRenderer.paintFor` | 先 `paint.alpha = alphaValue`，**再** `paint.color = color` |
| Android | `Paint.setColor(int)` | 官方文档：*"the color is an int containing alpha as well as r,g,b"* —— 32 位整体赋值，**把上一步的 alpha 一起覆盖成 0** |

于是 `Paint.color = 0x00FFFFFF`（白）实际是「全透明白」，填充图层什么都没画出来。
此前之所以「看起来正常」，是因为 `strokeWidthDp = 1.2f` 那次描边用**另一个** `strokePaint`
（它的 color 是默认黑、alpha 单独用 `strokePaint.alpha` 设的）画出了一层黑色空心轮廓 ——
用户看到的是描边，不是填充。§17.2 一关描边，可见图层随之消失。

> 反证：这也解释了「透明度滑杆拖了没反应」—— 它从来只作用在描边那一层上。

**修法**：

| 文件 | 改动 |
|---|---|
| `core/danmaku/.../model/DanmakuColor.kt` | 新增。纯逻辑换算入口：`opaque(rgb)` / `withAlpha(rgb, alpha)`，把 24 位 RGB 补成 32 位 ARGB |
| `DanmakuRenderer.paintFor` | `paint.alpha = …` + `paint.color = …` 两步 → 一行 `paint.color = DanmakuColor.withAlpha(color, alphaValue)`。**顺序问题从根上消失** |
| `DanmakuRenderer.setConfig` | 描边色接上此前**声明未使用**的 `STROKE_COLOR`（拆为 `STROKE_RGB` + `STROKE_ALPHA = 0xC0`），并用 `withAlpha` 把「描边自身透明度 × 用户透明度」一次算完 |
| `PFieldParser` / `DanmakuItem` | 加注释指向 `DanmakuColor`，避免有人只在一处「修好」颜色 |
| `core/danmaku/src/test/.../DanmakuColorTest.kt` | 新增 7 例，锁住「三字节颜色补全后 alpha 必须是 255」「黑色不能变成全透明」等不变量 |

**教训（写代码时直接套用）**：
1. `Paint.alpha` 与 `Paint.color` 改的是**同一份底层状态**，谁后调谁生效 —— 不要分两步设，直接合成一个 ARGB。
2. 项目里凡是「带颜色的数据源」，都要在**唯一入口**处补全 alpha，别指望下游每一处都记得。
3. 「关掉某个兜底图层才暴露」的缺陷，只能靠**不变量断言**守（不崩不报错，reproduce 全靠人眼）。

**验证**：`test` **168 例全绿**（161 + 新增 7）；`:app:assembleDebug` 通过。
### 17.4 弹幕默认带一点描边 + 描边宽度可调（2026-10-14）

**需求**（§17.2 / §17.3 的收尾）：默认**带一点描边**，并且描边宽度要能在弹幕设置面板里调。

**默认值**：`DanmakuConfig.strokeWidthDp` 回到 `1.2f`。
和 P0–P2 观感一致，但这次是「填充 + 描边」两层都真能画出来 ——
此前只有描边可见（§17.3），现在填充层也修好了，白字带一圈淡黑边。

**新增偏好项**（第 4 项弹幕样式）：

| 环节 | 实现 |
|---|---|
| 取值约定 | `DanmakuPreferenceCodec`：`DEFAULT_STROKE_DP = 1.2f`、`MIN = 0f`、`MAX = 3f`；`normalizeStrokeWidthDp()` 负责 NaN 回落 → 钳位 → **量化到 0.1dp** |
| 存储 | `DanmakuPreferenceStore` 新 key `danmaku_stroke_width_dp`（float）；`setStyle` 加第 4 个参数；`reset()` 一并移除 |
| 升级路径 | **老版本没有这个 key → 读出来就是默认 1.2dp**，无需 Migration（与 alpha/scale/scroll 同一套「缺 key 用默认值」策略） |
| ViewModel | `setStrokeWidthDp` 走既有的「内存先行」：内存里保留连续值（拖动跟手），量化只在落库时做 |
| UI | 右侧面板新增「描边宽度」滑杆，范围 0~3dp；值为 0 时显示「实心」 |
| 测试 | `DanmakuPreferenceCodecTest` +6 例、`DanmakuPreferenceStoreTest` +3 例（量化 / NaN 回落 / 老 key 缺失 / 0 不被误钳成默认值） |

**设计取舍 —— 为什么量化放在落库侧而不是内存侧**：滑杆每帧都会抛一个连续 float，
若在内存里就量化成 0.1dp，拖动会一格一格跳；只有写盘这一个出口收敛，
值才不会以 `1.2000000476837158` 这种形态遍地存在。

**描边的构成**：`strokePaint` 的颜色是「淡黑 × 用户透明度」一次合成的 ARGB
（`STROKE_RGB = 0x000000`、`STROKE_ALPHA = 0xC0`），填充层同理走 `DanmakuColor.withAlpha` ——
两层都不再出现「先设 alpha 再设 color 被覆盖」的问题（§17.3）。

**验证**：`test` **177 例全绿**（168 + 新增 9）；`:app:assembleDebug` 通过。

---

## 18. 字幕 / 手势 / 导航 / 列表性能（2026-10-14）

### 18.1 字幕：改为「白字 + 无黑底 + 细描边」，字号与描边可调

**背景**：项目此前**没有任何字幕代码** —— 视频里内嵌的字幕是 `PlayerView` 内置字幕层
（`PlayerView.subtitleView`）顺手渲染的，用的是 Media3 的默认样式：**白字 + 不透明黑底方块**，
而且 `applyEmbeddedStyles` 默认为 true，字幕文件（ASS/SSA）自带的字体/字号/颜色/黑底会被照单全收。

| 项 | 落地做法 |
|---|---|
| 拿到字幕层 | `VideoSurface` 新增 `onPlayerViewReady` 回调（原来只交出了 `DanmakuView`），`PlayerScreen` 存一个 `playerView` State |
| 去黑底 | `CaptionStyleCompat(白, **透明**, edgeType, 黑)`；同时关掉 `setApplyEmbeddedStyles(false)` |
| 关自带字号 | `setApplyEmbeddedFontSizes(false)` —— 不关的话调字号滑杆会出现「有的字幕跟着变、有的不变」 |
| 描边 | `EDGE_TYPE_OUTLINE` / `EDGE_TYPE_NONE` 二选一，由面板上的开关控制 |
| 字号 | `setFixedTextSize(SP, 值)`，**不是** `setFractionalTextSize` |
| 生效时机 | `LaunchedEffect(playerView, state.subtitlePrefs)` → 改偏好即时套用，与弹幕同一套「面板内即时预览」 |

**为什么字号用 sp 而不是 Media3 的比例**：`setFractionalTextSize` 把字号定义成视口高度的比例
（默认 0.0533 ≈ 1/18.75）。同一份设置在手机横屏（视口高 1080px）约 21sp 尚可，
搬到视口高 1600px 的平板上就是 42sp，半屏都是字。用户能感知的是「多大号的字」，
所以统一成 sp，换设备换分辨率都不变。默认 **22sp**，区间 **12~40sp**。

**为什么字幕的描边是开关、弹幕的描边是滑杆**：`CaptionStyleCompat` 只有「哪种边」和「边的颜色」，
**没有线宽** —— 内置字幕层的描边粗细写死在库里。想要连续可调的描边只能自研渲染层
（像弹幕那样读 Cue 自绘），本期不做。这是**刻意的能力差**，不是遗漏。

**偏好存储**：`SubtitlePreferenceCodec`（纯逻辑带单测）+ `SubtitlePreferenceStore`（DataStore）。
与弹幕样式同一套约定 —— **读取侧钳位、缺 key 回落默认值，因此零 Migration**；DB 仍为 version 1。
key：`subtitle_text_size_sp` / `subtitle_outline_enabled`。

**面板**：原 `DanmakuSettingsPanel` 更名 `PlaySettingsPanel`，标题「弹幕设置」→「弹幕与字幕」，
在末尾新增「字幕」分组（字号滑杆 + 描边开关），沿用弹幕那套滑杆/开关形态与深底白字配色。
面板顶部的「重置」**同时重置弹幕与字幕**（`PlayerViewModel.resetStyles()`）。

### 18.2 播放手势：双击暂停/播放 + 横滑调进度

四个手势统一在**同一个 `awaitEachGesture` 循环**里判定，先跑一段「判定期」：

```
按下
 └─ 在 longPressTimeout 内观察
      ├─ 横向位移 > touchSlop ──────→ 拖动调进度（到抬手为止）
      ├─ 抬手 ──────────────────────→ 等 doubleTapTimeout
      │                                 ├─ 等到第二次按下 → 双击：播放/暂停
      │                                 └─ 等不到          → 单击：切控制层显隐
      └─ 超时未动 ──────────────────→ 长按：临时 3 倍速，松手恢复
```

**为什么不拆成多个 `pointerInput`**：拖动一旦被另一个手势识别器消费，
判定期就会把这一手势当成「事件被上层吃掉」，从而走进长按分支（表现为「一横滑就 3 倍速」）。
判定必须在一处做完，只有一个真值来源。

| 细节 | 取舍 |
|---|---|
| 灵敏度 | 横向滑满整屏 ≈ **90 秒**（`SEEK_FULL_SWEEP_MS`，按屏宽比例算，不按 dp） |
| 松手才 seek | 拖动中只更新浮层与进度条位置（复用 `scrubbing`，顺带让进度条跟手）；每像素 seek 一次会反复重建缓冲区 |
| 浮层 | 画面中央显示「目标时间 / 总时长」+ 位移（`+00:35` 偏暖、`-02:10` 偏冷） |
| 单击延迟 | 单击最多晚约 300ms 才切控制层 —— 「同一个手势位既要单击又要双击」避不开 |
| 复用 | 播放/暂停抽成 `togglePlayPause`，底部按钮与双击共用一份实现（含暂停时落库进度） |

### 18.3 取消预测性返回的预览动画

**背景**：本项目 `targetSdk = 37`，而 **Android 16（API 36）起，系统对目标版本 ≥36 的应用
默认开启「预测性返回」**，不再需要 manifest 里 `enableOnBackInvokedCallback="true"` 的显式申请。
开启后侧滑返回的过程中，系统会拿 pop 动画渲染一份「上一页的预览」跟着手指走 ——
在播放页这种全屏沉浸的界面上，预览会露出后面媒体库的一角。

**做法（两层）**：

| 层 | 改动 | 作用范围 |
|---|---|---|
| 真正生效的一层 | `SolidPlayerNavHost` 把 `popEnterTransition` / `popExitTransition` 设为 `None` | 任何版本都生效：没有可动画的 pop 过渡，也就没有可预览的东西 |
| 兜底 | manifest `android:enableOnBackInvokedCallback="false"` | Android 13~15；Android 16+ 起该属性可能被系统忽略 |

**刻意只改返回方向**：进入播放页 / 设置页仍保留默认过渡，不是把所有动画一刀切掉。

### 18.4 媒体库快速上下滑动卡顿

按「快速滑动」这个现象定位，命中三处，逐条修：

| # | 问题 | 修法 |
|---|---|---|
| 1 | 列表项**没有 `contentType`**。LazyColumn 的节点复用池是按 contentType 分类的 —— 不分类时，快速滑动中「分组头」占用的节点会被直接拿去复用给「文件行」，复用时整棵子树必须重新组合 | 给两种 item 分别打上 `CONTENT_GROUP` / `CONTENT_FILE` |
| 2 | 行内节点过多。`ListItem` 为了通用性带了最小触摸尺寸与三槽位布局；尾部还有一个与整行点击**完全重复**的「播放」按钮；分隔线是独立的 `HorizontalDivider` 节点 | 换成手写「三行文本 + 一个图标」的 `Row`；去掉重复的「播放」按钮（整行点击即播放）；分隔线改成整行 `drawBehind` 画一条线 |
| 3 | 回调 lambda 写在 `items` 里（`{ onOpenPlayer(display.file.id) }`），每次重组都是新对象 → 列表项**永远无法跳过重组**；而识别进度每完成一个文件就刷一次 StateFlow | 回调在页面级定型（`openFile` / `identifyFile`），并把 `GroupHeader` 的参数从「整个 `LibraryGroup`（含 List，不稳定类型）」换成三个基本类型 |

**已知边界**：以上都是「减少每帧要做的事」。若在 **debug 包**上仍觉卡顿，先与 release 包对比 ——
Compose 在 debug（无 R8、无基线配置）下本身就慢 2~3 倍，本项目目前也没有 baseline profile。

### 18.5 验证

- `:app:assembleDebug --offline` → BUILD SUCCESSFUL（`app-debug.apk`，24.9 MB）
- `test --offline` → **189 例全绿**（原 177 + 新增 `SubtitlePreferenceCodecTest` 6 例 +
  `SubtitlePreferenceStoreTest` 6 例），0 失败 0 错误
- DB 仍为 **version 1，零 Migration**：字幕偏好是全局偏好，进 DataStore 不进 DB（§7.2）

### 18.6 尚未做 / 已知取舍

- **字幕描边的粗细不可调**：`CaptionStyleCompat` 没有线宽参数（见 §18.1）。
  要做到连续可调，只能自研字幕渲染层（订阅 `Player.Cues` 自绘）。
- **不支持外挂字幕文件**（同目录的 `.srt` / `.ass`）：只渲染视频里内嵌的字幕轨。
  外挂字幕需要给 `MediaItem` 配 `SubtitleConfiguration` 并扫描同目录文件，本期未做。
- **单击切控制层有约 300ms 延迟**：双击与单击共用一个手势位，避不开（见 §18.2）。
- **媒体库列表行的「播放」按钮被移除**：整行点击即播放，右侧只留「识别」。
  这是为了减少每行的节点数（§18.4），如果更看重显式入口，把 `TextButton` 加回去即可。
- **没有 baseline profile**：debug 包下的 Compose 本身慢 2~3 倍，判断滑动是否真的卡
  应先在 release 包上复现。


## 19. 媒体库自动发现：首页改成「文件夹」（2026-10-14）

### 19.1 需求与形态

主页面从「用户手选 SAF 目录 + 按番剧分组」整体换成**自动发现的全机视频文件夹**：

- 首页（图一）：每个含视频的目录一行，`目录名` + `N 个视频`，有 7 天内新增视频时右上角挂红色数字角标；
- 点进目录（图二）：视频列表，每行 = 缩略图（叠时长 + NEW）+ 文件名 + 字幕格式角标（SRT/ASS/TXT）；
- 进首页前先过一道**所有文件访问**授权页（图三）。

这一条把 §15.3 里「MediaStore 自动发现暂不引入」的结论推翻了 —— 当时不做的理由是
「与『用户明确指定目录』的设计冲突」，而现在的产品决定恰恰是不要用户指定。

### 19.2 权限：为什么必须要「所有文件访问」

`targetSdk` 远高于 30，分区存储强制生效，**只给 `READ_EXTERNAL_STORAGE` 是读不到目录树的**
（`java.io.File.listFiles()` 直接返回空）。所以：

| 系统版本 | 用哪套权限 | 能否遍历真实目录 |
|---|---|---|
| API 30+ | `MANAGE_EXTERNAL_STORAGE`（系统设置里手动开） | 能 |
| API 24~28 | `READ_EXTERNAL_STORAGE`（运行时申请） | 能（无分区存储） |
| **API 29** | **两边都不成立** | **不能** |

API 29 是唯一的死角：`MANAGE_EXTERNAL_STORAGE` 是 API 30 才引入的，而 API 29 已经强制分区存储。
那个版本上 [VideoScanner] 自动退化成「只读 MediaStore 索引」，首页会挂一条降级提示 ——
与其让用户疑惑「为什么少了几个文件夹」，不如直说。

`MANAGE_EXTERNAL_STORAGE` **无法在应用内弹窗申请**，只能跳系统设置，
回来后在 `ON_RESUME` 重新判定（`StorageAccessGate`）。上架 Google Play 需单独申请豁免。

### 19.3 扫描：两条来源合并

```
VideoScanner.scan()
   ├─ MediaStoreVideoScanner → 索引到的视频（带 时长 / DATE_ADDED / content:// uri）
   ├─ DiskVideoScanner       → 遍历共享存储（覆盖最全，但没有时长、没有 content://）
   └─ 合并：以磁盘遍历为准，用 MediaStore **按绝对路径对齐**补元信息；
            遍历没覆盖到的再补一遍
```

- 磁盘遍历用 `java.io.File` 走递归，**跳过 `Android/data` 与 `Android/obb`**（应用私有缓存，
  量极大且播放不了），但保留 `Android/media`（微信 / WhatsApp 下载的视频落在这里）。
- 存储根靠 `getExternalFilesDirs()` 反推（砍掉 `/Android/data/<pkg>/files`），
  这是唯一不依赖厂商约定的多卷枚举方式。
- **字幕角标在扫描期算**：同一个目录里文件名等于「视频名」或「视频名.语言」的
  `.srt/.ass/.ssa/.txt/...` 就是旁挂字幕。分区存储下读不到同目录文件，所以那一路拿不到角标。

### 19.4 落库：为什么先写库再展示

识别结果（`episodeId`）本来就挂在 `media_file` 行上，UI 必须从库读；
目录聚合（`GROUP BY folderPath`）也是 SQL 的强项。所以扫描完先入库，页面从库里读。

DB 升到 **version 2**，`media_file` 增加六列：

| 列 | 用途 |
|---|---|
| `folderPath` / `folderName` | 首页按目录聚合 |
| `dateAdded` | NEW 角标判定（7 天窗口；§20 起还要再叠一个「未看过」条件） |
| `subtitleFormats` | 图二的字幕角标 |
| `source` | `disk` / `mediastore` / `saf`，清理时用来区分新旧行 |
| `lastSeenAt` | 扫完删掉「本轮没再看到」的行 = 同步文件删除 |

**迁移策略（刻意为之）**：v1 用 SAF 扫进来的旧行 `folderPath` 一律留空，
**不从 `relativePath` 回填** —— v1 的 `relativePath` 是「相对用户所选目录树根」的路径，
与 v2 的「相对存储根」不是同一套语义，强行回填会造出一堆点进去是空的「文件夹」。
旧行留在库里不参与新首页，等扫到同一文件时会以绝对路径重新入库。

**换源时保住识别结果**：同一文件的 `uri` 会变（没被索引时是 `file://`，被索引后是
`content://media/...`），而 `uri` 是 `media_file` 的唯一键。所以落库前先按**稳定键**
（绝对路径，存在 `documentId`）捞旧行，把 `episodeId` / `fileHash` / `animeId` 整体带过去 ——
否则每次 uri 形态一变，用户辛苦识别的集数就白费了。

> **§20（P5）起的修正**：上面这条「入库 7 天内算新」只是 NEW 的**一半**条件。
> 用户点开看过的视频仍然一直挂着 NEW、文件夹角标也不会自己减下去，
> 那个数字就失去了「还有多少没看」的含义。P5 给 `media_file` 加了 `lastViewedAt`（DB → v3），
> NEW 的口径改成「7 天窗口内 **且** 从未点开过」。历史记录保留原文，实际判定以 §20.3 为准。

### 19.5 逐个视频的能力边界

- **时长**：磁盘遍历分支拿不到，靠 MediaStore 按路径补齐。没被索引的文件时长为 0，
  图二那一行的时长角标就空着（不为它去逐个打开容器探测：几百个文件会把首扫拖成分钟级，§12.5）。
- **缩略图**：优先 `ContentResolver.loadThumbnail`（API 29+，系统缩略图库，命中时是纯文件读取），
  它**只认 `content://`**；`file://` 只能退回 `MediaMetadataRetriever` 抽帧。
  缓存是自写的 `LruCache`（按像素字节计量）—— 项目离线构建，Gradle 缓存里没有 Coil/Glide。
- **失败记忆**：拿不到缩略图的 uri 会记下来，滑动时不反复尝试打开容器。

### 19.6 验证

- `:app:assembleDebug --offline` → BUILD SUCCESSFUL（`app-debug.apk`，24.9 MB，
  versionCode 5 / `0.5.0-p4`）
- `test --offline` → **189 例全绿**，0 失败 0 错误（与 §18.5 的基线一致，无回归）
- `core/data/schemas/…/2.json` 已生成；`dateAdded` / `lastSeenAt` 在实体上标了
  `@ColumnInfo(defaultValue = "0")`，让导出 schema 与 `MIGRATION_1_2` 出来的表严格一致
  （`ALTER TABLE ADD COLUMN ... NOT NULL` 在非空表上没有默认值会直接失败，所以迁移侧必须有 `DEFAULT 0`）

### 19.7 尚未做

- 首页顶栏的**搜索与网格/列表切换**（图一右侧那两个图标）未实现，只做了「重新扫描 + 更多菜单」。
- 文件夹图标未按内容区分（图一里 Camera / Movies 各自有专属图标），目前统一用文件夹图标。
- **未做 SAF→绝对路径的识别结果迁移**：v1 旧行只做到「不污染新首页」，没有按文件名把
  `episodeId` 搬到新行上。
- 时长缺失的文件不会在进入目录时补探测。

---

## 20. P5：方向跟随 / NEW 点开即消 / 下拉刷新（2026-10-14）

四条需求：

1. 点进视频时**方向跟随手机**，不再强制旋转，竖屏 UI 作相应调整；
2. 视频的 **NEW 标志在点开后消失**，所在文件夹的角标数字随之 -1；
3. 文件列表支持**下拉刷新扫描**；
4. 扫描**只在「每次进入软件」与「手动下拉」时进行**。

### 20.1 播放页方向：默认跟随手机

原来 `PlayerScreen` 一进来就把 `landscape = true` → `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，
退出页面才恢复 `UNSPECIFIED`。竖屏视频、录屏、竖屏短视频会被压在屏幕中间一条，
而这三类在这个 App 的目标里占比不低。

现在默认 `OrientationMode.FOLLOW` → `SCREEN_ORIENTATION_SENSOR`，顶栏按钮在
**跟随 → 锁横屏 → 锁竖屏**之间循环，三种状态各有一个能一眼分辨的图标
（`ScreenRotation` / `StayCurrentLandscape` / `StayCurrentPortrait`）——
无文字按钮只能靠 `contentDescription` 同时说清「现在是什么状态」与「点一下会怎样」。

| 取向 | 常量 | 理由 |
|---|---|---|
| 跟随（默认） | `SCREEN_ORIENTATION_SENSOR` | 纯按传感器，横放就横屏、竖立就竖屏 |
| 锁定横屏 | `SENSOR_LANDSCAPE` | 用户显式要横屏（躺床上看等场景） |
| 锁定竖屏 | `SENSOR_PORTRAIT` | 用户显式要竖屏 |
| 退出页面 | `UNSPECIFIED` | 交给系统，媒体库页继续跟随系统「自动旋转」开关 |

**刻意不用 `UNSPECIFIED` 做「跟随」**：它的实际行为取决于系统的「自动旋转」开关 ——
开关关掉时横放手机也不会横过来，那就不叫「跟随手机」了。

**转手机不会重建 Activity**：manifest 的 `configChanges` 已含 `orientation|screenSize|screenLayout|…`，
所以翻转时画面、播放进度、弹幕时间轴都不会断（`SolidPlayer` 与弹幕引擎都是 `remember` 的）。
这正是敢用 `SENSOR` 的前提 —— 否则每次转屏都会重建播放器，表现为黑一下再从头开始。

系统栏仍然隐藏（沉浸式），竖屏也一样：这是一块以「看」为主的页面。

### 20.2 竖屏适配

| 位置 | 紧凑态（`screenWidthDp < 600`） | 常规态 |
|---|---|---|
| 控制行图标按钮 | 44dp | 48dp |
| 文字按钮内边距 | 左右 8dp | Material 默认 |
| 按钮字号 | 12sp | 13sp |
| 倍速按钮文案 | `1.0x`（省掉「倍速」二字） | `倍速 1.0x` |
| 顶部栏图标 / 标题 / 副标题 | 44dp / 13sp / 10sp | 48dp / 14sp / 11sp |
| 时间行字号 | 11sp | 12sp |

分界线 `COMPACT_WIDTH_DP = 600` 与设置面板「屏宽 > 600dp 就给固定 320dp 宽」是同一条线，
语义一致：线以下是手机（竖屏时尤其窄），线以上是横屏手机 / 平板。

竖屏下五个控件挤在一行，不收宽度就会互相顶到换行 —— 这是这一档存在的唯一理由。

**挖孔内边距**：顶栏与底栏各自加 `windowInsetsPadding(WindowInsets.displayCutout)`，
且加在**背景渐变之后**：让渐变铺满刘海区（否则顶部会缺一条），只把内容推进安全区。
横屏时挖孔在左右两侧，同一行代码顺带把返回 / 倍速按钮从挖孔下面挪出来。

### 20.3 NEW 角标：点开即消（DB v3）

原本 NEW 只看 `dateAdded`（入库 7 天内），看过与否完全不参与 —— 角标里的数字既不会因为
看过而减少，也无法回答「还剩几个没看」。

DB 升到 **version 3**，`media_file` 加一列：

| 列 | 类型 | 用途 |
|---|---|---|
| `lastViewedAt` | `INTEGER NOT NULL DEFAULT 0` | 最近一次打开该文件的时刻；0 = 从未打开过 |

**判定口径只有一处**：`MediaFileEntity.isNew(newSince)`（与实体同文件），
= `lastViewedAt == 0L && dateAdded >= newSince`。

但它有**两个消费方、两条不同的路径**，必须逐字对齐：

| 消费方 | 实现 |
|---|---|
| 文件夹详情页的文件行 | Kotlin：`video.isNew(newSince)` |
| 媒体库首页的文件夹角标 | SQL：`COUNT(CASE WHEN dateAdded >= :newSince AND lastViewedAt = 0 …)` |

两边一旦漂移，用户看到的就是「列表里没有 NEW，角标却还挂着 3」这种无法自解释的状态。
SQL 那半边进不了纯 JVM 单测，所以 `NewBadgeTest` 把边界（窗口含端点、`dateAdded = 0`、
看过之后不再复活）钉死，改 SQL 时必须回头对一遍。

**迁移默认 0 = 从未看过是刻意的**：老行升级后 NEW 语义**不变**（仍由 7 天窗口那半边决定），
不会因为升级凭空冒出一堆 NEW。

**触发点**：`PlayerViewModel.loadMedia()` 里文件加载成功即 `markViewed`。

- 放这里而不是 `onDispose`：**换集是原地换源**，`onDispose` 根本不触发，而切过去的那一集
  同样已经被用户打开了（见 `switchEpisode`）。
- 写库后 Room 会让首页的聚合查询重发，于是**行内 NEW 消失与文件夹角标 -1 是同一次刷新**，
  UI 侧不做任何手工递减。
- 「看过」的定义就是「进过播放页」，不看时长 —— 更严格的定义（播够 N 秒）需要挂在
  播放器进度回调上，成本与误判都更高。

### 20.4 扫描时机：冷启动一次 + 手动下拉

「自动扫一次」的闸门从 `FolderListViewModel` 的字段挪到了
`VideoLibraryRepository.claimAutoScan()`（`AtomicBoolean`，单例 = 进程级）。

原因是原实现里它是个 **VM 字段**，而 VM 是导航栈条目的作用域：旋转、系统回收后恢复、
导航图重建都会换出新实例 ⇒ 记在 VM 里等于「某些重建会顺带重扫一遍全机」。

| 时机 | 是否扫描 |
|---|---|
| 冷启动进入媒体库首页 | ✅ 一次（闸门放行） |
| 首页下拉刷新 / 顶栏刷新按钮 | ✅ 手动 |
| 文件夹详情页下拉刷新 | ✅ 手动 |
| 从详情页返回、旋转、切后台再回来 | ❌ |
| 进入文件夹详情页 | ❌ |

**下拉刷新**两页都加，用 material3 的 `PullToRefreshBox`（首页顶栏的刷新按钮保留，它是显式入口）。

- **空态也必须可滚动**：下拉靠嵌套滚动驱动，原来空态是个 `Box(fillMaxSize)`，
  在「一个文件夹都没扫到」这种最该刷新的状态下反而拉不动。现在空态是 `LazyColumn`
  里的一项（`fillParentMaxSize`），并按 `contentType` 与列表行分槽位。
- **`refresh()` 加 `Mutex`**：首页与详情页各有自己的 `state.scanning`，互相看不见 ——
  并发跑两遍遍历不只是浪费 I/O，第二轮还会按自己的 `scanStartedAt` 去删「第一轮刚入库、
  第二轮还没扫到」的行。
- **进度提示只留一个**：扫描中的提示交给下拉指示器（`isRefreshing` 绑的就是 `state.scanning`，
  从顶栏按钮触发时它一样会转），顶部那条线性进度条只留给「识别」，否则两个进度会叠在一起。

### 20.5 验证

- `:app:assembleDebug --offline` → BUILD SUCCESSFUL（`app-debug.apk`，23.8 MB，versionCode 6 / `0.5.0-p5`）
- `test --offline` → **195 例全绿**（189 → 195，新增 `NewBadgeTest` 6 例），0 失败 0 错误
- `core/data/schemas/…/3.json` 已生成；`lastViewedAt` 的 `notNull = true` / `defaultValue = "0"`
  与 `MIGRATION_2_3` 出来的表严格一致

### 20.6 尚未做

- **方向偏好不持久化**：每次进播放页都回到「跟随」。P3 起就没持久化过，本次也没顺手加
  （要加就是 DataStore 里一条 `player_orientation`，与弹幕 / 字幕那套偏好同构）。
- 竖屏下**画面填充（裁剪黑边）**没做，仍是 `PlayerView` 默认的 FIT —— 竖屏看横屏片源会上下留黑，
  但 FIT 是唯一不会裁掉内容的模式，要「填满」得再给一个模式切换入口。
- 「看过」不区分「点开就退」与「看完」，只记录时刻（`lastViewedAt`），没有做观看进度维度的统计。

---

## 21. 三个体验缺陷修复（2026-10-14）

用户实测提的三条，根因互不相干，但都属于「功能都对、观感不对」这一类 —— 不崩不报错，
只能靠人眼发现。共同教训：**凡是「时间上错开」的东西（异步布局、挂起中的副作用、缓存容量），
都要有一个明确的收敛口径**，否则它会以「偶尔闪一下」「回来又弹一次」这种形态长期存在。

### 21.1 进播放页时画面「从满屏闪到正常」（`PlayerScreen`）

**现象**：每次进视频，画面先以**铺满整屏**的尺寸出现一瞬（竖屏手机上是一条被拉长的 16:9），
随即缩回真实比例。

**根因**：`PlayerView` 内部的 `AspectRatioFrameLayout` 在拿到视频宽高之前 `aspectRatio = 0`，
此时它的测量结果就是**父容器的整块尺寸** ⇒ 首帧被拉着填满整屏。
宽高到达后 `setAspectRatio()` **只做 `requestLayout`**，要等下一次窗口遍历才落到画面上：

| 时刻 | 画面 |
|---|---|
| t0 首帧渲染 | 拉伸满屏（ratio 还是 0） |
| t1 `onVideoSizeChanged` → `setAspectRatio` | 仅排队一次 layout，画面仍是拉伸的 |
| t2 下一次窗口遍历 | 才缩回真实比例 ← **中间的 t0~t2 就是那一闪** |

所以「首帧回调早于宽高回调」「宽高回调早于布局」这两种错序都要挡住，光靠 `PlayerView`
自己的 shutter（只盖到首帧）是盖不住 t1~t2 的。

**修法**：`PlayerScreen` 里在 `VideoSurface` **之后**叠一层不透明黑 `Box`（普通 View 画在
SurfaceView 之上，与弹幕层同一套事实），满足「宽高已到 **且** 再等两个 vsync」才撤掉：

- 用 `withFrameNanos` 等两帧，而不是「收到回调就撤」—— 等的正是上面 t1~t2 那次异步布局；
- **不能**改成把 `PlayerView` 设成 `INVISIBLE`：SurfaceView 一旦不可见，它自己的 surface
  会被销毁，连首帧回调都无从谈起；
- 黑幕不带任何指针输入修饰符，不参与命中测试，不会挡住下面的四手势层；
- 兜底：`state.uri` 起 1.5s 后无条件揭示。异常容器 / 纯音轨报不出宽高时，
  「拉伸着能看」也好过「全黑什么都看不见」。

### 21.2 扫描完成的提示「回到首页又弹一次」（`FolderListScreen` / `FolderDetailScreen`）

**现象**：首页扫完弹出的「扫描完成：N 个文件夹…」在点进任意文件夹、再返回首页时会**再弹一遍**。

**根因**：一次性消息的消费顺序反了。

```kotlin
// ❌ 原来：先显示、后消费
state.message?.let { snackbarHostState.showSnackbar(it); viewModel.consumeMessage() }
```

`showSnackbar` 是**挂起**的，一直等到提示自己超时（约 4s）才返回。用户在这几秒里点进文件夹，
本页离开组合、协程被取消 ⇒ `consumeMessage()` 永远执行不到 ⇒ `state.message` 一直挂着
「扫描完成…」；返回时 `LaunchedEffect(state.message)` 以**同一个 key** 重新启动，于是又弹一遍。
（`FolderListViewModel` 是导航栈条目的作用域，返回时**不会**重建，这条消息也就不会自愈。）

**修法**：**先消费、再显示**——消息在进入组合的那一刻就被取走，显示与否只影响这一次。
三处一次性提示（首页 / 详情页 / 播放页）统一成同一个写法。

### 21.3 每次进子文件夹，缩略图都重新加载（`VideoThumbnails`）

**现象**：反复进出同一个目录，缩略图每次都从头解一遍（先占位图标、再逐张刷出来）。

**根因**：两件事叠在一起，都指向同一个后果 —— LRU 被挤爆。

| 环节 | 事实 |
|---|---|
| 缓存容量 | `VideoThumbnailCache` 按**像素字节数**计量（24MB） |
| `content://` 分支 | `loadThumbnail(uri, Size(320,180))` 会按尺寸缩，一张 ≈ 230KB ✅ |
| `file://` 分支 | `getFrameAtTime` 交出来的是**整张关键帧**：1080p 一张 ≈ 8MB ❌ |
| 并发 | 解码跑在 `Dispatchers.IO`（64 线程），滑动时十几路一起解、一起 `put` |

于是「以磁盘遍历为准、MediaStore 没索引到就存 `file://`」的那批文件（`.nomedia` 目录、
刚拷进去还没被媒体扫描器收编的视频），一张缩略图就吃掉 1/3 个缓存 —— 一屏还没滑完，
先前解好的就被挤掉了，下次进目录自然全部重来。

**修法**：

| 改动 | 说明 |
|---|---|
| `frameAt(context, uri, widthPx, heightPx)` | 抽帧后**等比缩到显示尺寸**再交出去，并 `recycle()` 掉那张整帧 |
| 缩放用等比不用撑满 | 竖屏视频撑进 16:9 会被压扁；裁切交给上层 `ContentScale.Crop` |
| `loadVideoThumbnail` 里统一再收一次口 | 「进缓存的图必须接近显示尺寸」不能在两个分支各写一遍（`loadThumbnail` 的实现并不保证）；已够小的图原样返回，无额外拷贝 |
| 解码闸门 `Semaphore(2)` | 抽帧同时最多 2 路，避免「刚写进 LRU 就被新来的挤掉」的抖动 |

缩到 320×180 后一张 ≈ 230KB，24MB 的缓存能装下 100+ 条，一个目录整屏都在里面。

### 21.4 验证

- `:app:assembleDebug --offline` → BUILD SUCCESSFUL（`app-debug.apk`，24.5 MB，与 §20.5 的基线一致）
- `test --offline --rerun-tasks` → **195 例全绿**（0 失败 0 错误；本次未触碰 `core/*` 的业务逻辑与任何测试，用例数不变）
- 三条都只在 `:app` 内改动：`ui/player/PlayerScreen.kt`、`ui/library/FolderListScreen.kt`、
  `ui/library/FolderDetailScreen.kt`、`ui/common/VideoThumbnails.kt`


---

## 22. 识别改为「点开才匹配」（2026-10-14）

需求：**减少对弹弹play 的请求次数** —— 平常扫描文件时不做匹配，只在点击文件时才匹配弹幕。

### 22.1 原来的请求量来自哪里

`FolderListViewModel.refresh()` 在扫描成功后顺手调了一次 `coordinator.identifyPending()`：

```
冷启动 / 下拉刷新 → 全机遍历（本地，快）
                  → 对**每一个**未识别文件算前 16MB 哈希
                  → POST /api/v2/match/batch（每 32 个一批，批间 300ms）
```

假设库里 400 个待识别文件、用户当天只想看其中 3 集，代价是：
**400 次哈希读取（6.4 GB I/O）＋ 13 次批量请求**，而其中 397 个文件的识别结果
在用户下次打开软件前完全用不到（还会被下次扫描的 `findUnmatched()` 捞到重新试一遍）。
弹弹play 官方明确会对调用量大的来源限流拦截（§10），这是最容易被拦的一条路。

### 22.2 新的请求口径

| 用户动作 | 对弹弹play 的请求 |
|---|---|
| 冷启动自动扫描 / 下拉刷新扫描 | **0** |
| 点开一个已识别的文件 | 0（弹幕缓存命中）或 1（`comment/{episodeId}`） |
| 点开一个未识别的文件 | 1（`match`）＋ 0/1（`comment`） |
| 同一个未识别文件再点开一次 | 0（`episodeId` 已回填）或 1（上次没匹配上，重试一次） |
| 媒体库「识别本目录」/「识别全部」 | 保留（显式入口，用户点了才发） |

一次点击最多多一次 `match` 往返，换来的是「打开软件不产生任何请求」。
串流顺序也变了：**先算本地哈希（有缓存）→ 有 `episodeId` 才拉弹幕**，
播放器起播依旧不等这些（§9.1 的「不阻塞起播」没变）。

### 22.3 实现

| 位置 | 改动 |
|---|---|
| `FolderListViewModel.refresh()` | **删掉**扫完的 `coordinator.identifyPending()`，并在原地留注释说明为什么不在这里识别 |
| `PlayerViewModel.loadMedia()` | `episodeId == null` 时不再直接显示「尚未识别」，改为走 `identifyAndLoad(entity)` |
| `PlayerViewModel.identifyAndLoad()` | 见下 |
| `PlayerUiState.matching` | 新增：识别进行中。与 `retryable` 分开，**识别中不给「重试」按钮**（请求还在飞，再点只会多打一次） |
| `PlayerViewModel.retryDanmaku()` | 分流：有 `episodeId` → 重新拉弹幕（缓存优先）；没有 → 重新识别 |
| `PlayerScreen.OverlayBanner` | 三种露头情况：识别中（纯提示）/ 未识别（可重试）/ 弹幕加载失败（可重试）。文案统一取 `state.danmakuStatus` |
| `FolderDetailViewModel` | 单文件识别改用 `outcome.autoBind`（§22.4），行为不变 |

`identifyAndLoad` 的三条出口：

| 情况 | 处理 |
|---|---|
| **没有凭据** | **一个请求都不发**，直接写「尚未配置凭据，本次按无弹幕播放」+ 可重试（缺凭据不阻塞播放，§12.1） |
| 请求失败（网络 / 鉴权） | `describe(failure)` 的文案进提示条 + `retryable = true` |
| 结果**不能自动确定**（0 候选 / 多候选） | **不猜**。提示「未找到匹配的弹幕库 · 可在媒体库手动匹配」或「匹配到多个候选 · 请到媒体库确认是哪一个」 |
| 唯一候选 | `persistMatch`（`matchType = hash`）→ 写 `episodeId` → `loadTimeline` |

**多候选为什么不挑第一条**：猜错的代价是整集弹幕错位，而且已经写进库了，
用户很难察觉是自己手动改过的文件被绑错了。宁可他回媒体库点一下。

### 22.4 自动绑定判据收敛成一处

「这个结果可以直接写库、不用问用户」的判据原来是散在 ViewModel 里的
`outcome.isMatched && outcome.candidates.size == 1`。现在播放页也要用同一条判据，
所以收敛成 `MatchOutcome.autoBind`（与 `MatchOutcome` 同文件）：

```kotlin
val autoBind: MatchResultV2?
    get() = candidates.singleOrNull()?.takeIf { isMatched }
```

>`takeIf` 的顺序有讲究：**`isMatched` 描述的是「服务端有没有精确命中 hash」，
>不是「有没有唯一的答案」**。真出现 `isMatched = true` 却给了多条候选时，
>多一条都不绑 —— `MatchOutcomeTest` 的四个用例把这条边界钉死
>（精确单条 → 可绑；多候选 → 不可；单条但非精确 → 不可；空候选 → 不可，哪怕 `isMatched = true`）。

### 22.5 迟到的异步结果不再串集

识别与拉弹幕都要跨网络，用户在等待期间完全可能按「下一集」。原来 `loadMedia()` 与
`loadTimeline()` 都是拿到结果就 `_state.update`，迟到的结果会写进**下一集**的状态里
（表现为下一集开头闪出上一集的弹幕）。

现在两个流程都先钉住自己的 `mediaFileId`，写回前过一道 `updateIfCurrent(target)`：
文件已经不是当前播放的，结果直接丢弃。这是「点开即识别」带来的新延迟窗口逼出来的修复 ——
但原实现里同一集内快速连点「下一集」也会踩到，属于顺带修掉的旧问题。

### 22.6 验证

- `test :app:assembleDebug --offline` → **BUILD SUCCESSFUL**
- 单测 **199 例全绿**（195 → 199，新增 `MatchOutcomeTest` 4 例），0 失败 0 错误
- `app-debug.apk` 24.5 MB（与 §21.4 基线一致，本次只改逻辑不引依赖）
- 改动面：`app/ui/player/{PlayerViewModel,PlayerScreen}.kt`、`app/ui/library/{FolderListViewModel,FolderDetailViewModel}.kt`、
  `core/data/repository/MatchRepository.kt`、`core/data/src/test/.../MatchOutcomeTest.kt`

### 22.7 已知取舍

- **未识别文件每次点开都会重试一次 `match`**（没有落库「上次识别失败」这个状态，
  因为那需要给 `media_file` 加列 + 一次 Migration）。命中缓存的部分（16MB 哈希）不受影响；
  真要根治，`match_type` 里加一个 `none` 之外的失败位是更省事的做法 —— 但那又要动 DB，本期不做。
- **扫描期间不再显示识别进度**：识别进度条（`LibraryIdentifyCoordinator.Progress`）现在只在
  用户点了「识别全部 / 识别本目录」之后出现，这是预期的。
- **播放页不弹候选列表**：多候选时**静默按无弹幕播过去**（§23.2），要匹配得回媒体库手动选。
  要在全屏播放器上摆候选列表，得把 `LibraryDialog.Candidates` 那套搬过来，和「点开就播」
  的体验冲突，暂不做。

## 23. 文件夹置顶 + 多候选不再打扰（2026-10-15）

### 23.1 文件夹置顶（长按 → 置顶 / 取消置顶）

首页的「文件夹」**不是实体**，是 `media_file` 按 `folderPath` 聚合出来的投影
（`MediaFileDao.observeFolderSummaries`）。所以置顶**没有走 Room**：给聚合查询加一个
「是否置顶」的列，既要给 `media_file` 加列 + 一次 Migration，又解决不了「同一个目录下
成百上千行都要写一遍」的问题 —— 它本质上是**排序偏好**，跟人走、不跟文件走。

| 位置 | 改动 |
|---|---|
| `core/data/repository/PinnedFolderCodec.kt`（新） | 有序 JSON 数组 + `pin` / `unpin` / `retainExisting`，纯 JVM 可单测 |
| `core/data/repository/PinnedFolderStore.kt`（新） | DataStore 新 key `pinned_folders`；**所有写入口都是「读-改-写一次 `edit`」**，两个入口并发时不会互相吃掉对方的改动 |
| `FolderListViewModel` | `combine(observeFolders(), pinnedStore.pinned)` 合流；新增 `FolderEntry(folder, pinned)` 与 `togglePin()` |
| `FolderListScreen` | `FolderRow` 从 `clickable` 换成 `combinedClickable`（长按弹菜单）；置顶行加图钉图标 |

要点：

- **顺序本身就是数据**：列表越靠前优先级越高，新置顶的插到最前，所以存 `List` 而不是 `Set`
  （`Set` 回到 List 时顺序是未定义的）。
- **排序在 Kotlin 侧做，不写第二套 SQL**：`sortedBy { rank[path] ?: Int.MAX_VALUE }` 利用
  Kotlin 排序的**稳定性**，未置顶的那批原样保留 SQL 的目录名序 —— 否则「目录名序」就有了
  两个各自会漂移的实现。合并逻辑是纯函数 `mergeFolders`，写在 `FolderListViewModel.kt` 末尾。
- **目录消失 = 置顶自然失效**：`retainExisting` 只在展示时过滤、**不回写盘** ——
  写回等于「每次进首页都可能触发一次 DataStore 写入」，代价远大于留一条死路径。
- **长按菜单挂在行右侧的零尺寸锚点上**：`DropdownMenu` 是 Popup，弹出位置由调用处那个
  布局节点的包围盒决定；直接放进整行的话菜单会从行的左上角探出来。
- 长按加了 `HapticFeedbackType.LongPress`：没有任何震动反馈时，用户不知道自己按上没按上。
- **零 Migration**：`pinned_folders` 是新 key，缺省即空列表（`decode(null) == []`）。

### 23.2 多候选不再弹提示（静默按无弹幕播放）

`Outcome.autoBind == null` 原先只有一条出口：`retryable = true` + 一句提示，于是**多候选**与
**0 候选**都会被顶上黑条（连「重试」按钮）拦一次。但这两件事对用户的意义完全不同：

| 情况 | 重试有用吗 | 现在的处理 |
|---|---|---|
| 0 候选（文件名不规范 / 网络抖动） | 可能有用 | 保留提示条 + 「重试」 |
| **多候选**（服务端一次给了好几条） | 没用，还是那几条 | **静默按无弹幕降级**：不提示、不给重试、顶栏只写「无弹幕」 |

实现上新增 `PlayerUiState.ambiguous`（与 `retryable` 分开），`PlayerScreen.OverlayBanner`
第一句就 `if (state.ambiguous) return`。**不能只靠原有条件挡住**：这时 `episodeId == null`，
`noEpisode` 那半边本来就会让提示条露头。

匹配成功的路径**没有改**：唯一候选 → `persistMatch` → `loadTimeline` → 弹幕正常渲染。
「弹幕」开关（`DanmakuPreferences.enabled`）仍由用户自己掌握，**不随匹配结果被改写** ——
替用户打开开关会覆盖掉「我不想看弹幕」这个明确表达。

### 23.3 验证

- `test :app:assembleDebug --offline` → **BUILD SUCCESSFUL**（1m 8s）
- 单测 **212 例全绿**（199 → 212，新增 `PinnedFolderCodecTest` 13 例），0 失败 0 错误
- `app-debug.apk` 24.5 MB，与 §21.4 基线一致（只改逻辑、不引依赖）
- 改动面：`core/data/repository/{PinnedFolderCodec,PinnedFolderStore}.kt`（新）、
  `core/data/src/test/.../PinnedFolderCodecTest.kt`（新）、
  `app/ui/library/{FolderListViewModel,FolderListScreen}.kt`、`app/ui/player/{PlayerViewModel,PlayerScreen}.kt`

### 23.4 尚未做

- 置顶项之间的**手工排序**：目前只有「最近置顶的排最前」这一条规则，不能拖动调整。
- 置顶状态不展示在文件夹详情页（那一页没有置顶入口，也不该有）。
- 多候选时**播放页没有任何回头路**：既没有提示也没有重试入口（顶栏的「重新拉取弹幕」
  在 `episodeId == null` 时本来就是禁用的），用户要匹配只能自己回媒体库 —— 这是
  「不打扰」的必然代价，见 §22.7。

## 24. 首页文件夹「长按拖拽排序」（2026-10-15）

§23.4 里那条「置顶项之间不能拖拽排序」在这里补上。交互定位与 §23 一致：**长按是唯一的
「非进入」入口**，所以排序也挂在长按上 —— 不必新加拖拽把手（行内节点数是§19 之后一直在守的线）。

### 24.1 手势：一个长按，两种结果

| 操作 | 结果 |
|---|---|
| 单击 | 进入目录 |
| 长按 → **原地松手** | 浮起 → 落回原位 → 弹「置顶 / 取消置顶」菜单（§23 的老行为，没变） |
| 长按 → **拖动** | 被拖行浮起跟手，其余行让位；松手即落位 |
| 先滑动 | 列表滚动（长按未成立，事件归 LazyColumn） |

实现上**不能**再挂 `combinedClickable(onLongClick = …)`：它和
`detectDragGesturesAfterLongPress` 并存时，长按那一刻会同时弹菜单 + 浮起。
「原地松手弹菜单」的判定走 `FolderReorderState.moved`（区分「拖过」与「没拖」）。
⚠️ **判据与回调分支在 P8.2 都改过**：早先判「手指动过」、且挂在 `onDragCancel` 上，
结果是长按原地松手根本不弹菜单 —— 详见 §25.7。

### 24.2 数据模型：`pinned_folders` 从「置顶表」变成「自定义顺序区」

> ⚠️ **本节描述的模型已被 §25 改掉**（图钉与顺序拆成两个 key）。留在这里是为了记录当时的
> 取舍与踩坑；现在的口径看 §25.2。

不新增 key、不新增表：§23 那份有序 `List` 本来就是顺序数据，现在把它直接当**顺序**用。

```
界面顺序 = [ 自定义顺序区（pinned_folders 的顺序）] + [ 其余（SQL 的目录名序）]
```

`PinnedFolderCodec.move(visible, pinned, from, to)` 的两条规则：

| 落点 | 结果 |
|---|---|
| 在自定义区内（`to < pinned.size`） | 精确插到该位置 |
| 在名称序区 | 名称序区是 SQL 给的，表达不了「插在中间」→ **吸附到自定义区末尾** |

被拖进来的目录**自动成为自定义区一员**（图钉亮起）—— 这就是「拖一下就置顶到该位置」，
不必先置顶再拖。已满 `MAX_PINNED=50` 时被挤掉的是自定义区最后一条，它退回名称序区、列表里还看得见。

| 位置 | 改动 |
|---|---|
| `PinnedFolderCodec` | 新增纯函数 `move(...)`（可单测） |
| `PinnedFolderStore` | 新增 `reorder(visible, from, to)`，仍走「读-改-写一次 `edit`」 |
| `FolderListViewModel` | 新增 `commitReorder()`；`mergeFolders` 拆出 `applyPinnedOrder`，乐观更新与合流共用一套排序 |
| `FolderReorderState.kt`（新） | 拖拽会话：本地顺序、跟手位移、换位阈値、落点提交 |
| `FolderListScreen` | `combinedClickable` → `clickable` + 拖拽手势；`items` → `itemsIndexed`；边缘自动滚动 |

三个要点：

- **落盘保留死路径**：`reorder` 先按 `visible` 过滤出活着的再算下标，算完把不存在的路径
  接回末尾。直接覆盖的话，用户随手拖一下就会把「没挂载的 SD 卡 / 换过存储根」的置顶全清掉。
- **先乐观改内存、再落盘**：写盘 → Flow 回环 → 重新合并要隔一两帧，先落盘会看到
  列表「弹回旧顺序再跳成新顺序」。VM 用与仓库同一个 `move()`，所以随后到货的那份必然一致。
- **拖拽期间不给 VM 提交中间态**：跟手的每一次换位都只改本地顺序，只有松手才写一次盘。

### 24.3 两个必须写下来的坑

1. **手势层与位移层必须分开**。被拖行靠 `graphicsLayer.translationY` 跟手，而 Compose 派发给
   节点的指针坐标是**过了图层变换**的。把 `pointerInput` 挂在做位移的那层上，会出现「手指没动、
   图层动了 → 下一帧的局部坐标把位移正好抵消掉」：位移越大、局部坐标越显得没动，**行卡在半路**。
   所以 `FolderRow` 是「外层 Box（无变换）收手势、内层 Row 做位移」。
   位移也**不做增量累加**，而是 `offset = localY - grabY` —— 自动滚动时行在视口里的位置整体变化，
   累加式那一刻是没跟上的，而局部坐标天然把它算了进去。
2. **被拖那一行要关掉 `animateItem` 的换位动画**。它的位置 = 布局位移 + graphicsLayer 跟手位移，
   动画再插一杠子（布局慢慢挪、跟手位移按最终位置算）就会和手指对不上，表现为行自己飘。
   邻居行保留动画，这就是「让位」的观感来源。

### 24.4 边缘自动滚动

手指停在列表上下 72dp 内就自动滚，速度随深度线性到 16dp/帧。

**方向不能反**：手指贴上边缘是「要送到更前面」→ 内容必须**往回滚**（`scrollBy` 为负，
露出上面的项），被拖行才会继续越过邻居去换位。滚出来的实际距离交给 `shift()` 加到位移里，
否则行会原地不动而列表在跑。两端分别用 `index > 0` / `index < lastIndex` 兜住，
避免「已经在第一位了还一直滚」。

### 24.5 验证

- `test :app:assembleDebug --offline` → **BUILD SUCCESSFUL**
- 单测 **219 例全绿**（212 → 219，`PinnedFolderCodecTest` 20 例，新增 7 例覆盖 `move` 的
  区内落点 / 拖进自定义区 / 吸附到末尾 / 越界 / 上限 / 反复拖动不重复）
- `versionCode 6 → 7`、`versionName 0.5.0-p5 → 0.5.0-p8`，产物同步到
  `dist/solidplayer-0.5.0-p8-debug.apk`（24,950,325 B）
- **零新依赖**：`unzip -l` 两边都是 248 个条目，`zipalign -c -P 16` 校验 248 项全 OK、
  `.so` 仍是 16KB 页对齐，`zipfile.testzip()` CRC 全通过（体积比 §23.3 的包略小是 zip
  填充结构差异，条目与内容一一对应）
- 改动面：`core/data/repository/{PinnedFolderCodec,PinnedFolderStore}.kt`、
  `core/data/src/test/.../PinnedFolderCodecTest.kt`、
  `app/ui/library/{FolderListViewModel,FolderListScreen}.kt`、`app/ui/library/FolderReorderState.kt`（新）

### 24.6 取舍与尚未做

- ~~**落进名称序区会「吸附」**~~ —— §25 已把落点改成精确，名称序区不再是「表达不了的区域」。
- **还没有「拖出置顶区 = 取消置顶」**：§25 之后这条不再需要 —— 拖拽彻底不碰图钉，
  想取消置顶就走长按菜单（隐式操作让位给显式动作）。
- 触摸阈值内的原地松手弹菜单：判定用的是拖拽库的触摸阈值，所以「长按后手指抖了一下又松手」
  仍然会弹菜单 —— 这是有意的（抖动不该被当成排序）。
- 横屏 / 折叠屏的内边距没有专门处理：边缘自动滚动用的是视图坐标，屏幕变宽只是让「滚到边缘」
  更容易触发。
- 未做长按拖拽的无障碍替代（拖拽本质上是纯手势操作，TalkBack 下拿不到；「置顶 / 取消置顶」
  菜单仍在，顺序至少可以通过反复置顶调整）。

## 25. 拖拽排序：两个 bug 与「图钉 / 顺序」拆开（2026-10-15）

§24 落地后在真机上立刻暴露了两个症状，另有一条语义问题。三条都在这节收掉。

### 25.1 症状与根因

| 症状 | 根因 |
|---|---|
| 松手后列表跳成另一个顺序，而且这份错的顺序被写进 DataStore | `FolderReorderState.drop()` 交出的是**换位之后**的列表，而 `PinnedFolderCodec.move(visible, pinned, from, to)` 里是 `val item = visible[from]` —— `from` 是**拖之前**的下标。两个口径错位：只要 `from != to`，它挪的就是**另一个人**（换位之后站在原来位置上的那个）。 |
| 指头一按下去整张表跳回旧顺序、换位换错行、拖起来卡顿 | 每行 `pointerInput(path)` 的块**只在键变化时才重启**；块里捕获的 `index` / `entries` 是这一行**首次组合时**的值。扫描完成、上一次置顶、上一次拖拽之后按下，`begin()` 拿到的就是旧下标 + 旧列表。列表每换一次位还会给整屏每一行一个新 `entries` 实例 → 全部重组，这就是「卡顿」。 |
| 拖一下非置顶项就被置顶、还飞到列表顶部 | 名称序区由 SQL 决定、存不下「插在第几位」，于是 §24.2 只能把落点吸附到自定义区末尾（= 顶部）。图钉与顺序共用一个 key，**想排序就必然置顶**。 |

### 25.2 模型：图钉与顺序是两个 key

```
pinned_folders  图钉（有序 JSON）  ← 只有长按菜单能改
folder_order    显式顺序（有序 JSON）← 拖拽与菜单里的「置顶」都只改它

界面顺序 = [ 显式顺序里的项（按顺序）] + [ 其余（SQL 的目录名序）]
图钉     = 一个独立标记，只决定那一行画不画图标，不参与排序
```

- **零迁移**：`folder_order` 为空（老用户升级、或从没拖过任何一行）时，
  `effectiveOrder(order, pinned)` 退化成**图钉列表本身** —— 旧版本里它正是「置顶区」，
  顺序即优先级，升级后首页一模一样。一旦拖过一行，`folder_order` 说了算。
- **`pin` / `unpin`（菜单）**：`pin` = 打图钉 + 提到顺序最前（「置顶」的字面意思）；
  `unpin` = 摘图钉 + 从顺序里拿出来（退回目录名序）。位置只由「拖拽」与「置顶」两种**显式
  排序动作**决定，取消置顶是置顶的逆操作 —— 只摘图钉不动位置的话会出现「图钉没了、
  它还赖在最前面」，用户会以为菜单没生效。
- 两个 key 必须在**同一次** `dataStore.edit` 里改：分两次写会送出「图钉已经亮了但还没排到
  最前」的中间态，首页闪一下。

### 25.3 `moveOrder`：落点精确

`PinnedFolderCodec.moveOrder(visible, order, from, to)`：

```
required = visible.take(to + 1)          // 落点之前的这一段必须被显式定序
rest     = order - required              // 原有顺序项，按原相对顺序接在后面
newOrder = (required + rest).take(MAX_ORDER = 400)
```

为什么「至少要覆盖到落点」：界面 = 显式顺序 + 名称序。想让被拖项停在中间某处，
它**前面**的每一项都得先被显式定序，否则「中间」无从表达。§24 那种「落在名称序区就吸附」
看着像省事，实际是「拖了它自己飞回上面」。落在末尾时会把整张表都定序 —— 这也是为什么
`MAX_ORDER` 比 `MAX_PINNED` 宽得多（400 条约 20 KB）；真超上限时被截掉的那一段必然落在
名称序区里，退回目录名序，用户看不出来。

**`visible` 必须是「松手那一刻界面上的顺序」**（被拖项已经在 `to` 上），`from` 只用来判断
「有没有真的动过」。落盘侧不再自己从 `order` 重算一遍 —— 这样「用户看到的」与「盘上存的」
逐字相同，也就是下面这条不变量。

### 25.4 「所见即所存」

```
render(moveOrder(drop.target, order, from, to))  ==  drop.target
```

`drop.target` = 松手时界面上那份顺序。这一条同时被两处消费：

- **UI 侧**：`commitReorder` 直接把它贴到内存上（乐观更新），不再自己算；
- **数据侧**：`reorder` 用它当落盘输入。

两处同一份输入，所以 Flow 回环送回来的结果与乐观值一致，不会「弹回旧顺序再跳成新顺序」。
单测里 `FolderReorderStateTest.拖拽结果落盘后渲染出来与松手时看到的一致` 与
`PinnedFolderCodecTest.moveOrder 反复拖动都是所见即所存` 就是钉这条的 —— §25.1 第一个
bug 当年正是这条不变量不成立。

### 25.5 手势块里的普通值必须从 State 里现读

`pointerInput(key)` 的块只在键变化时重启，而键是「行的路径」—— 行还在，块就不重启。
于是块里任何**普通值**（`index`、整份列表）都会冻在行首次组合的那一刻。修法：

| 位置 | 修法 |
|---|---|
| 行内 `index` | `val latestIndex = rememberUpdatedState(index)`，`onDragStart` 里读 `.value` |
| 屏幕侧那份顺序 | `rememberUpdatedState(displayed)` + 一个**稳定的** `onBegin(index, grabY, rowHeight)` 闭包，块里现读 |
| 「谁在拖」 | `FolderReorderState.draggedKey` 单独成 state：一次拖拽只变两次（起、落），而行渲染只读它 —— 读 `order`/`index` 派生值的话，每换一次位整屏行都要重组 |

顺带把**整份列表从 `FolderRow` 的参数里去掉**（早先是 `entries: List<FolderEntry>`）：
列表每换一次位就是一个新实例、参数一变整屏可见行全部重组（`List` 又不稳定），
几十行乘几十次换位就是那个「卡顿」的大头。列表现在只在起手那一刻经 `onBegin` 读一次。

### 25.6 自动滚动：`localY` 也要跟着补

`shift(pixels)` 早先只把位移补回去。但 `localY` 是**行内局部坐标**（`局部 = 视口坐标 - 槽位偏移`），
列表滚了多少，行的槽位在视口里就上移多少，这个局部坐标必须加上同一个量。漏掉的话，
自动滚动的下一帧会以为手指离边缘还差 `pixels` —— 表现为贴边时滚着滚着忽然变慢/变向。
两个都补上，「行的槽位 + 行内坐标」才恒等于手指在视口里的位置。

### 25.7 长按菜单为什么弹不出来：回调分支选错了

现象：长按一行、手指不动直接松手，**「置顶 / 取消置顶」菜单不出现，也没有任何别的反应**。

两个独立的原因叠在一起：

**① 分支错了。** `detectDragGesturesAfterLongPress` 内部是这样的（语义摘录）：

```kotlin
val drag = awaitLongPressOrCancellation(down.id)   // 长按成立 → 返回 change
if (drag != null) {
    onDragStart(drag.position)
    if (drag(drag.id) { onDrag(it, it.positionChange()); it.consume() }) onDragEnd()
    else onDragCancel()
}
```

`drag()` 见到 **up 就返回 true**（当成「优雅收尾」）—— 也就是说「长按成立 → 原地松手」
走的是 `onDragEnd`，取消分支只留给「事件被别的组件抢走 / 组合被销毁 / 协程被取消」。
P8 把弹菜单只写在 `onDragCancel` 里，于是那段代码在执行路径上根本不存在；
`onDragEnd` 拿到的 drop 又满足 `from == to`（原地没换位），被提交方当空操作丢掉 ——
合起来就是「长按毫无反应」。

**② 判据错了。** `moved` 原本由 `onDrag` 里的 `noteMoved()` 置位 = 「手指动过」。
但按下时手指微抖是常态，`onDrag` 只要有位移回调就会来 —— 于是「手抖一下再松手」也判成拖拽，
菜单同样弹不出来。判据改成 **`settle()` 真的换过位**（`swapped` 时置位）：
`moved == false` ⇔ 「手指移动始终没超过半个行高」⇔ 用户没打算排序。

修完之后：`onDragEnd` 里先读 `moved`、再 `drop()`（`drop` 会 `reset`，之后读就没了），
`moved` 为真才提交顺序，否则 `menuOpen = true`。`onDragCancel` 保留同样的兜底，处理真取消。

> 教训：这类「两个回调哪个会来」的问题不要靠读名字猜，去看库里的 `drag()` 实现 ——
> `onDragEnd` / `onDragCancel` 的命名会让人以为是「抬起 / 被打断」，
> 实际分界是「up 收尾 / 非 up 收尾」。
>
> ⚠️ **P8.3 把这一整段判定换掉了**（不再用拖拽库，见 §26）：本节记录的是当时的修法，
> 它只治了「原地松手不弹菜单」，没治「两套 detector 抢同一次按下」。

### 25.8 验证

- `test :app:assembleDebug --offline` → **BUILD SUCCESSFUL**
- 单测 **228 例全绿**（219 → 228）：
  - `PinnedFolderCodecTest` 改写 `move` 段为 `moveOrder` 语义（落点精确 / 覆盖落点 /
    保留落点之后原有顺序项 / 保留死路径 / 越界 / 上限 / 反复拖动都是所见即所存）；
    新增 `effectiveOrder` 的两条（空顺序退化用图钉、有顺序时图钉不参与排序）。
  - **`app/src/test/.../FolderReorderStateTest.kt`（新）**：app 模块第一次有单测。
    覆盖换位阈值与位移补偿、`shift` 的两个补偿、`draggedKey` 不跟着换位变、
    「拖拽结果落盘后渲染出来与松手时看到的一致」（§25.4 那条不变量），
    以及「换过位才算拖过」（§25.7 的判据，P8.2 补）。
- 版本 `versionCode 7 → 8 → 9`、`versionName 0.5.0-p8 → p8.1 → p8.2`，P8.2 产物在
  `dist/solidplayer-0.5.0-p8.2-debug.apk`（24,950,329 B，与 P8.1 同尺寸：manifest 字符串与
  版本号长度不变、dex 只涨 240 B，差额被 zip 对齐填充吸收）
- **零新依赖**：`unzip` 248 个条目、`zipfile.testzip()` CRC 全通过、manifest 确为 0.5.0-p8.2；
  逐条目比对 P8.1 与 P8.2 只有 `classes17.dex` 与 `AndroidManifest.xml` 的 CRC 变了
- 改动面：`core/data/repository/{PinnedFolderCodec,PinnedFolderStore}.kt`、
  `core/data/src/test/.../PinnedFolderCodecTest.kt`、
  `app/ui/library/{FolderReorderState,FolderListViewModel,FolderListScreen}.kt`、
  `app/src/test/.../FolderReorderStateTest.kt`（新）、`app/build.gradle.kts`

### 25.9 仍未做

- **`unpin` 会把它踢回名称序区**：位置是「显式动作」的结果，取消置顶就是撤销那次置顶。
  如果更想让它留在原地只摘图钉，改一行 —— 但那样菜单看着像没生效。
- **落盘后 `folder_order` 只覆盖到落点**：拖到列表很深处会把那一段全部定序（上限 400）。
  这是名称序区「表达不了中间」的必然代价，也是这套模型能零迁移的原因。
- 真机手感（跟手是否贴手、换位阈值、边缘自动滚动速度）仍需人工过一遍。

## 26. 单击与长按必须共用一次判定（2026-10-15，P8.3）

### 26.1 症状

长按一行、手指不动、松手 —— **进入了子文件夹**。（P8.2 修掉「菜单弹不出来」之后才露出来的。）

### 26.2 根因：两套 detector 抢同一次按下

点击挂在 `Modifier.clickable` 上，长按拖拽挂在 `detectDragGesturesAfterLongPress` 上。
两个 detector **各自跑一遍同一个 `viewConfiguration.longPressTimeoutMillis`**：

- 拖拽库：阈值内抬手 → 长按不成立 → 什么都不做；
- `clickable`：阈值内（它自己的那个）抬手 → **判为普通点击** → 进目录。

两者在阈值附近不是「互斥」，而是**各判各的**。用户按住的时间只要落在「长按没成立」
的那一侧（对体感来说都是"长按"），`clickable` 就把它当单击。P8.2 把弹菜单补进
`onDragEnd` 只是让"长按成立"那一侧能工作，**判定仍然是两套**。

### 26.3 落地：一次判定，三种互斥归宿

整行只留一个 `pointerInput`，`awaitEachGesture` 里由 `awaitPressIntent` 判一次：

| 归宿 | 触发条件 | 动作 |
|---|---|---|
| `Tap` | 长按阈值**内**抬手 | 进目录 |
| `Hold` | 越过阈值 | 震动 + 浮起 + `dragUntilUp` 跟手 |
| `Cancelled` | 事件被消费 / 移出屏幕 | 什么都不做（**滑动滚列表走的正是这条**） |

要点：

- **消费检查放在 `Final` pass** —— 它晚于 Main pass，能看到本轮所有消费者的动作，
  所以「按住行往下滑」会稳定地归 `Cancelled`，滚动不被劫持。
- **`pressed` 先取 `currentEvent`**：手指完全静止时可能一个事件都收不到，
  超时那一刻只能靠这里记下的位置算「手指落在行的哪个高度」。
- **自研 `dragUntilUp` 而不是库里的 `drag()`**（后者是 internal）：它返回
  **是否以抬手收尾**，这才是「原地松手」判据的准确来源。
  顺带摆脱了 §25.7 那个「`onDragEnd` / `onDragCancel` 名字骗人」的坑。
- **决策抽成纯函数 `resolveHoldOutcome(moved, endedWithUp)`**：手势回调只能真机点，
  决策可以单测 —— 「原地长按松手**绝不进目录**」这条不变量就钉在这里。
  规则：没换过位 → 一律弹菜单（哪怕拖拽是被抢走的）；换过位 + 抬手收尾 → 提交；否则丢弃。
- **手势被打断要复位**：`dragUntilUp` 抛 `CancellationException`（组合销毁 / 行被回收）时
  调 `reorder.cancel()`，否则行会一直停在浮起态，得再拖一次才能复位。

### 26.4 代价

- **没有水波纹了**：`clickable` 自带 indication，自研手势没有。列表项的点击反馈只剩
  "页面切换"本身。要补就得自己维护 `InteractionSource` + `indication`（以及每行一个实例），
  当前判断是不值。
- **无障碍要显式补**：加了 `.semantics { onClick { … } }` 保住「TalkBack 能进目录」，
  但拖拽本身仍然拿不到（§25.9 已有的缝隙）。

### 26.5 验证

- `test :app:assembleDebug --offline` → **BUILD SUCCESSFUL**
- 单测 **230 例全绿**（228 → 230）：新增 `原地长按松手只弹菜单，任何情况都不会去开目录`
  （`moved = false` × `endedWithUp = true/false` 两种组合都必须是 `Menu`）与
  `换过位才提交，被抢走就丢弃`
- 版本 `versionCode 9 → 10`、`versionName 0.5.0-p8.2 → 0.5.0-p8.3`，产物
  `dist/solidplayer-0.5.0-p8.3-debug.apk`（25,106,520 B）
- **零新依赖**：248 个条目、CRC 全通过、manifest 确为 0.5.0-p8.3；
  逐条目比对只 `classes17.dex` 与 `AndroidManifest.xml` 变了
- 改动面：`app/ui/library/FolderListScreen.kt`（手势重写 + `PressIntent`/`awaitPressIntent`/
  `dragUntilUp`）、`app/ui/library/FolderReorderState.kt`（`HoldOutcome`/`resolveHoldOutcome`）、
  `app/src/test/.../FolderReorderStateTest.kt`、`app/build.gradle.kts`

### 26.6 仍未做

- 多指按下没有专门处理（第二个手指按在另一行上会各自判各自的，可能同时浮起两行）。
- 行上没有"手指按住"的即时视觉反馈（原来的 ripple 随 `clickable` 一起去掉了）。



---

## 27. 弹幕显示区域可调（2026-10-15，P9）

### 27.1 需求

> 在弹幕设置页面添加调整弹幕显示区域的选项，现在弹幕太密了，影响看视频观感

### 27.2 为什么「区域」才是密度的主治项

面板上原有四根滑杆里，能影响密度的只有**滚动时长**，而且它的作用方向与直觉相反 ——
拖长只是让弹幕飘得慢，同屏条数反而**变多**。其余三项（透明度 / 字号 / 描边）都不改变条数。

根子在于轨道数：`轨道数 = 画面高度 / 行高`，铺满整屏。热番一集能铺十几二十条，
画面主体（人脸、字幕）全被盖住。缩小显示区域就是**直接减少轨道数** ——
挤不进轨道的弹幕被 `TrackAllocator` 丢弃（它本来就以 `null` 表示「无可用轨道」），
同屏条数随之下降，下半屏彻底让给视频。

| 环节 | 实现 |
|---|---|
| 取值约定 | `DanmakuPreferenceCodec`：`DEFAULT_DISPLAY_AREA_PERCENT = 100`、`MIN = 10`、`MAX = 100`；`toConfig` 里钳位后 ÷100 得比例 |
| 渲染模型 | `DanmakuConfig` 新增 `displayAreaRatio`；`displayAreaHeightPx(canvasHeight)` = 画布高 × 比例（比例再钳到 `MIN_DISPLAY_AREA_RATIO = 0.05f` 兜底） |
| 轨道分配 | `TrackAllocator.updateLayout` 的 `heightPx` 参数改成**显示区域高度**（不是画布高度）；重建判据改为「算出来的轨道数是否变化」，并返回该布尔值供渲染层决定要不要重置 |
| 底部弹幕 | `trackBottom(track)` **去掉画布高度参数**，改为贴区域下边缘 |
| 存储 | `DanmakuPreferenceStore` 新 key `danmaku_display_area_percent`（int）；`setStyle` 加第 5 个参数；`reset()` 一并移除 |
| 升级路径 | **老版本没有这个 key → 读出来是 100（整屏）**，观感与升级前完全一致，不会被静默改掉 |
| ViewModel | `setDisplayAreaPercent` 走既有的「内存先行、松手落库」 |
| UI | 右侧面板在「字号」与「滚动时长」之间新增「显示区域」滑杆，100% 时显示「整屏」 |
| 测试 | `TrackAllocatorTest` +5 例、`DanmakuPreferenceCodecTest` +4 例、`DanmakuPreferenceStoreTest` +2 例 |

### 27.3 三个必须写下来的决定

**① 区域是「顶部起算的一块」，不是「中心带」**。底部弹幕改为贴**区域**下边缘，
而不是画布下边缘 —— 否则用户把区域压到 30% 后，底部弹幕仍会飘在画面最下方，
这个设置对底部弹幕就等于不存在。「三种弹幕共用同一块区域」这条比「底部弹幕永远贴底」更符合
用户对「显示区域」的理解。

**② 只在「轨道数真的变了」时 `reset()`**。轨道数变少时，在飘弹幕记的 `track` 下标可能越界，
`trackTop(越界下标)` 会把它画到区域之外 —— 表现是「调小区域后，有几条弹幕突然跑到画面下方」。
所以 `DanmakuRenderer.setConfig` 里把 `areaChanged` 单列一个变量，并在
**`applyAllocatorLayout()` 返回「轨道数组被重建」时才触发 `reset()`**。

这条比「区域一变就重置」要紧：区域滑杆按 1% 步进，多数档位算出的轨道数与上一档**相同**，
那些档位若也重置，只会白白把游标退回 60 s 重扫一遍，还要让屏上所有弹幕重新分配轨道
（分不到的那几条当场消失）—— 与 §16.2 第 3 条「拖动滑杆每帧重测上万条文字宽度」是同一类浪费。

**③ 重建判据落在「轨道数」而不是「宽/高/行高有没有变」上**。`updateLayout` 原先的判据是
`widthPx != viewWidthPx || heightPx != viewHeightPx || trackHeightPx != this.trackHeightPx`，
在「轨道数不变」时也会重建并清空占用状态 —— 那会让新弹幕被分到正在飘的弹幕占着的轨道上（直接叠字）。
改成只比较算出来的轨道数 `wanted == freeAtMs.size`，**标量（宽/高/速度/展示时长/最小间隔）
每次都直接赋值生效**，与是否重建无关。
参数名 `heightPx` 在 `TrackAllocator` 里保留，但语义是**显示区域高度**（不是画布高度），
注释与文档统一按这个口径写 —— 传错的表现是「滑杆拖了没反应」（轨道数组仍是旧的，弹幕照旧铺满整屏），
不是崩溃，只能靠单测钉住。

### 27.4 取舍

- **下限 10% 而不是 0**：手机上 10% 约等于「只留一两行」，再低就等于把弹幕关掉了 ——
  而想关弹幕的用户有开关，不需要靠把区域拖到看不见。0 还会让轨道数归零
  （`TrackAllocator` 兜底成 1 条），语义上很别扭。
- **区域固定在顶部**，没有做「居中的浮空字幕区」或「自定义上下边界」。
  顶部的理由是视频主体（人脸、动作）通常在中下部，字幕也压在下方 ——
  把弹幕留在上面是遮挡代价最小的位置。真要更自由，得做成拖拽边框的手势，本期不做。
- **滑杆是百分比而不是「行数」**：行数在换字号/换设备后对不上
  （同一行数在大屏上占的比例小得多），百分比与屏幕尺寸解耦，和其余四项的单位口径也一致。

### 27.5 验证

- `test :app:assembleDebug --offline` → **BUILD SUCCESSFUL**
- 单测 **242 例全绿**（230 → 242）：`TrackAllocatorTest` 20 → 27
  （区域决定轨道数 / 缩小区域同屏条数下降 / 只有轨道数变化才重建 / 轨道数不变时保留占用状态 /
  不重建时标量参数仍立即生效 / 区域内轨道坐标不越界 / 区域为 0 仍留一条）、
  `DanmakuPreferenceCodecTest` 17 → 21、`DanmakuPreferenceStoreTest` 9 → 11
  （含「老版本没有区域 key 时读出来是整屏」的升级路径断言）
- 版本 `versionCode 10 → 11`、`versionName 0.5.0-p8.3 → 0.5.1-p9`
- **零新依赖**、**DB 仍为 version 1**（纯 DataStore 新 key + 读侧回落，零 Migration）
- 改动面：`core/danmaku/.../model/DanmakuConfig.kt`、`track/TrackAllocator.kt`、
  `render/DanmakuRenderer.kt`、`core/data/.../preference/DanmakuPreferenceCodec.kt` /
  `DanmakuPreferenceStore.kt`、`app/.../ui/player/PlayerViewModel.kt` / `PlayerScreen.kt`
