package com.solidplayer.core.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签名算法单测 —— 文档 §13 列出的 **P0 头号技术风险点**（「错一个字段全盘 401」）。
 *
 * 期望值由一份独立的 Python 实现（`hashlib.sha256` + `base64.b64encode`）算出，
 * 属于跨实现交叉验证，不是把当前实现跑一遍再抄回来。
 */
class DandanplaySignerTest {

    @Test
    fun `拉取弹幕路径的签名与独立实现一致`() {
        assertEquals(
            "DWPXb1aVAvFzKxru4sh7sk/CkDxkTtgM95FpHjE0PsU=",
            DandanplaySigner.sign(
                appId = "testapp",
                timestamp = 1_700_000_000L,
                path = "/api/v2/comment/123450001",
                appSecret = "secret123",
            ),
        )
    }

    @Test
    fun `识别接口路径的签名与独立实现一致`() {
        assertEquals(
            "FCcWwOfy/yV4cOOzNMAXDEs4ssnlDn3PSyuabIHfcOw=",
            DandanplaySigner.sign(
                appId = "my-app-id",
                timestamp = 1_700_000_000L,
                path = "/api/v2/match",
                appSecret = "my-app-secret",
            ),
        )
    }

    @Test
    fun `极短输入的签名与独立实现一致`() {
        assertEquals(
            "utrtYShGU2xPVqxftd2d8C08uj3rjWE6LJ435E0Zjrk=",
            DandanplaySigner.sign(appId = "a", timestamp = 1L, path = "/", appSecret = "b"),
        )
    }

    @Test
    fun `全空输入的签名与独立实现一致`() {
        assertEquals(
            "X+zrZv/IbzjZUnhsbWlsecLbwjndTpG0ZynXOif7V+k=",
            DandanplaySigner.sign(appId = "", timestamp = 0L, path = "", appSecret = ""),
        )
    }

    @Test
    fun `输出为 44 字符的标准 Base64 且不带换行`() {
        val signature = DandanplaySigner.sign("id", 1L, "/api/v2/match", "secret")
        assertEquals(44, signature.length)
        assertTrue(signature.none { it == '\n' || it == '\r' })
        assertTrue(signature.endsWith("="))
    }

    // ── 任何一项变化都必须改变签名（防止字段拼接顺序被改错）──────────

    @Test
    fun `拼接顺序 AppId 时间戳 路径 AppSecret 缺一不可`() {
        val baseline = DandanplaySigner.sign("id", 100L, "/api/v2/match", "secret")

        assertNotEquals(baseline, DandanplaySigner.sign("id2", 100L, "/api/v2/match", "secret"))
        assertNotEquals(baseline, DandanplaySigner.sign("id", 101L, "/api/v2/match", "secret"))
        assertNotEquals(baseline, DandanplaySigner.sign("id", 100L, "/api/v2/match2", "secret"))
        assertNotEquals(baseline, DandanplaySigner.sign("id", 100L, "/api/v2/match", "secret2"))
    }

    @Test
    fun `路径带查询参数会算出不同签名 - 这就是拦截器必须用 encodedPath 的原因`() {
        val withoutQuery = DandanplaySigner.sign("id", 100L, "/api/v2/comment/123", "s")
        val withQuery = DandanplaySigner.sign("id", 100L, "/api/v2/comment/123?withRelated=true", "s")
        assertNotEquals(withoutQuery, withQuery)
    }

    @Test
    fun `路径带域名会算出不同签名`() {
        val pathOnly = DandanplaySigner.sign("id", 100L, "/api/v2/match", "s")
        val withHost = DandanplaySigner.sign("id", 100L, "https://api.dandanplay.net/api/v2/match", "s")
        assertNotEquals(pathOnly, withHost)
    }

    @Test
    fun `同一输入始终稳定`() {
        val first = DandanplaySigner.sign("id", 100L, "/api/v2/match", "s")
        repeat(5) {
            assertEquals(first, DandanplaySigner.sign("id", 100L, "/api/v2/match", "s"))
        }
    }
}
