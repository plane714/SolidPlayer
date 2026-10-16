package com.solidplayer.core.data.network

import okio.ByteString.Companion.toByteString
import java.security.MessageDigest

/**
 * 弹弹play 开放平台签名（文档 §4.4）。
 *
 * ```
 * Signature = Base64( SHA256( AppId + Timestamp + Path + AppSecret ) )
 * ```
 *
 * 三个必须注意的坑：
 * 1. [path] **不含域名，也不含查询参数** —— 例如 `/api/v2/comment/123450001`
 * 2. 拼接顺序严格为 `AppId → Timestamp → Path → AppSecret`，中间**无分隔符**
 * 3. 302 跳转后的加速服务**不应再附加签名头** —— 签名与 Path 强绑定，跳转后 Path 已变
 *
 * 实现说明：这里用 okio 的 `ByteString.base64()` 而不是 `android.util.Base64`。
 * 两者输出一致（标准 Base64、不换行），但 okio 是纯 JVM 实现，
 * 因此本类可以被普通 JVM 单测直接覆盖 —— 这是文档 §13 的 P0 头号风险点，必须有回归测试。
 */
object DandanplaySigner {

    fun sign(appId: String, timestamp: Long, path: String, appSecret: String): String {
        val raw = appId + timestamp + path + appSecret
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.toByteString().base64()
    }
}
