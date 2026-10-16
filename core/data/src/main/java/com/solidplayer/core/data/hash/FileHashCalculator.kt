package com.solidplayer.core.data.hash

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * 文件哈希计算（文档 §5）。
 *
 * **规则：取文件前 16MB（16 × 1024 × 1024 字节）计算 MD5。**
 * 这是识别准确率的关键 —— 弹弹play 服务端的索引就是按这个规则建的，
 * 用整文件 MD5 会永远匹配不上。
 *
 * 三个实现要点：
 * 1. **必须用 SAF 流式读取**。Android 10+ 拿到的 `content://` URI 无法转成真实文件路径，
 *    只有 `ContentResolver.openInputStream()` 这一条路（文档 §12.3）
 * 2. **读满 16MB 立刻停止**，不要 `readBytes()` —— 一个 4GB 的 BD 原盘会直接 OOM
 * 3. **哈希结果必须缓存**，键为 `(uri, size, lastModified)` 三元组
 *    （缓存由 [com.solidplayer.core.data.repository.MediaRepository] 负责，
 *    一次扫描 100 个文件就是 1.6GB 的读取量，不能重复算）
 */
class FileHashCalculator(private val resolver: ContentResolver) {

    suspend fun md5OfFirst16MB(uri: Uri): String = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { input -> md5OfFirst16MB(input) }
            ?: throw IOException("无法打开输入流: $uri")
    }

    companion object {

        /** 弹弹play 约定的哈希长度：前 16MB */
        const val HASH_LENGTH: Long = 16L * 1024L * 1024L

        private const val BUFFER_SIZE = 64 * 1024

        /**
         * 纯函数版本，不碰 Android API —— 因此可以被 JVM 单测直接喂 `ByteArrayInputStream`。
         */
        fun md5OfFirst16MB(input: InputStream): String {
            val md = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = HASH_LENGTH

            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read <= 0) break          // 文件小于 16MB，读到 EOF 即止
                md.update(buffer, 0, read)
                remaining -= read
            }

            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
