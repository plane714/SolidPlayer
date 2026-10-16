package com.solidplayer.core.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 退避重试策略单测（P2，文档 §10 / §11）。
 *
 * 重点验证「该重试的会重试、不该重试的绝不重试」：
 * 把「网络不可达」也纳入重试，会让用户白等好几秒才看到降级提示。
 */
class RetryPolicyTest {

    // ── 退避时长 ──────────────────────────────────────────────────

    @Test
    fun `退避时长按 1 秒 2 秒递增`() {
        assertEquals(1_000L, RetryPolicy.delayMillisFor(1))
        assertEquals(2_000L, RetryPolicy.delayMillisFor(2))
    }

    @Test
    fun `非法次数按首次退避处理而不抛异常`() {
        assertEquals(1_000L, RetryPolicy.delayMillisFor(0))
        assertEquals(1_000L, RetryPolicy.delayMillisFor(-5))
    }

    // ── 状态码判定 ────────────────────────────────────────────────

    @Test
    fun `429 与 5xx 可重试`() {
        assertTrue(RetryPolicy.isRetryableHttpStatus(429))
        assertTrue(RetryPolicy.isRetryableHttpStatus(500))
        assertTrue(RetryPolicy.isRetryableHttpStatus(503))
        assertTrue(RetryPolicy.isRetryableHttpStatus(599))
    }

    @Test
    fun `鉴权与参数错误不重试`() {
        assertFalse(RetryPolicy.isRetryableHttpStatus(400))
        assertFalse(RetryPolicy.isRetryableHttpStatus(401))
        assertFalse(RetryPolicy.isRetryableHttpStatus(403))
        assertFalse(RetryPolicy.isRetryableHttpStatus(404))
    }

    @Test
    fun `状态码边界之外不算 5xx`() {
        assertFalse(RetryPolicy.isRetryableHttpStatus(600))
        assertFalse(RetryPolicy.isRetryableHttpStatus(499))
    }

    // ── 异常判定 ──────────────────────────────────────────────────

    @Test
    fun `读超时可重试`() {
        assertTrue(RetryPolicy.isRetryable(SocketTimeoutException("timeout")))
    }

    @Test
    fun `网络不可达不重试而是立即降级`() {
        // UnknownHostException 是 IOException 的子类，但不属于「瞬时故障」
        assertFalse(RetryPolicy.isRetryable(UnknownHostException("api.dandanplay.net")))
        assertFalse(RetryPolicy.isRetryable(IOException("connection refused")))
    }

    @Test
    fun `业务异常不重试`() {
        assertFalse(RetryPolicy.isRetryable(MissingCredentialException()))
        assertFalse(RetryPolicy.isRetryable(IllegalStateException("boom")))
    }
}
