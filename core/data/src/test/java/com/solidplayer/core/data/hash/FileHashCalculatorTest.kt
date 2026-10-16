package com.solidplayer.core.data.hash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * 哈希计算单测。
 *
 * 这里刻意只测纯函数版本 `md5OfFirst16MB(InputStream)` —— 它不碰 Android API，
 * 因此能真实覆盖「只读前 16MB」这条最容易写错的规则。
 */
class FileHashCalculatorTest {

    /** 统计实际读取字节数，用来证明没有多读一个字节 */
    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }

    private fun md5Of(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pattern(size: Int): ByteArray =
        ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `小于 16MB 的文件等价于整文件 MD5`() {
        val data = pattern(1024 * 1024)
        assertEquals(
            md5Of(data),
            FileHashCalculator.md5OfFirst16MB(ByteArrayInputStream(data)),
        )
    }

    @Test
    fun `恰好 16MB 时等价于整文件 MD5`() {
        val data = pattern(FileHashCalculator.HASH_LENGTH.toInt())
        assertEquals(
            md5Of(data),
            FileHashCalculator.md5OfFirst16MB(ByteArrayInputStream(data)),
        )
    }

    @Test
    fun `大于 16MB 时只取前 16MB`() {
        val length = FileHashCalculator.HASH_LENGTH.toInt()
        val data = pattern(length + 3 * 1024 * 1024)
        val expected = md5Of(data.copyOf(length))

        assertEquals(expected, FileHashCalculator.md5OfFirst16MB(ByteArrayInputStream(data)))
    }

    @Test
    fun `只读取 16MB 一个字节都不多读`() {
        val length = FileHashCalculator.HASH_LENGTH.toInt()
        val counting = CountingInputStream(ByteArrayInputStream(pattern(length + 8 * 1024 * 1024)))

        FileHashCalculator.md5OfFirst16MB(counting)

        // 这是「4GB 的 BD 原盘不会 OOM」的核心保证
        assertEquals(FileHashCalculator.HASH_LENGTH, counting.bytesRead)
    }

    @Test
    fun `结果恒为 32 位小写十六进制`() {
        val hash = FileHashCalculator.md5OfFirst16MB(ByteArrayInputStream(pattern(4096)))
        assertEquals(32, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `空文件返回标准空 MD5`() {
        assertEquals(
            "d41d8cd98f00b204e9800998ecf8427e",
            FileHashCalculator.md5OfFirst16MB(ByteArrayInputStream(ByteArray(0))),
        )
    }

    @Test
    fun `内容不同则哈希不同`() {
        val a = ByteArray(2048) { 1 }
        val b = ByteArray(2048) { 2 }
        assertEquals(
            false,
            FileHashCalculator.md5OfFirst16MB(ByteArrayInputStream(a)) ==
                FileHashCalculator.md5OfFirst16MB(ByteArrayInputStream(b)),
        )
    }

    @Test
    fun `16MB 之后的内容不影响结果`() {
        val length = FileHashCalculator.HASH_LENGTH.toInt()
        val head = pattern(length)
        val withTailA = head + ByteArray(4096) { 7 }
        val withTailB = head + ByteArray(4096) { 9 }

        assertEquals(
            FileHashCalculator.md5OfFirst16MB(ByteArrayInputStream(withTailA)),
            FileHashCalculator.md5OfFirst16MB(ByteArrayInputStream(withTailB)),
        )
    }
}
