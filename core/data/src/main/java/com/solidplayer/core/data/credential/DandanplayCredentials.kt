package com.solidplayer.core.data.credential

/**
 * 弹弹play 开放平台凭据。
 *
 * ⚠️ 项目铁律（文档 §14）：**凭据只允许通过 [DandanplayCredentialStore] 读写**。
 * 禁止在其他任何地方出现 AppId/AppSecret 字面量，或绕过该 Store 直读 DataStore。
 */
data class DandanplayCredentials(
    val appId: String,
    val appSecret: String,
) {
    /** 脱敏展示用，任何日志都不允许打印明文 */
    fun masked(): String = "AppId=${appId.take(4)}****"
}
