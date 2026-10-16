package com.solidplayer.core.data.repository

import android.content.Context
import com.solidplayer.core.data.network.dto.CommentResponseV2
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弹幕原始 JSON 的落盘存储（文档 §7.2）。
 *
 * **弹幕原文不入库**：一条弹幕库动辄上万条记录，进 SQLite 会让媒体库查询变慢。
 * 策略是「原始 JSON 落盘 + DB 只存索引」，需要时按路径读文件解析。
 */
@Singleton
class DanmakuCacheStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    private val directory: File
        get() = File(context.filesDir, CACHE_DIRNAME)

    fun fileFor(episodeId: Long): File = File(directory, "$episodeId.json")

    suspend fun readResponse(episodeId: Long): CommentResponseV2? = withContext(Dispatchers.IO) {
        val file = fileFor(episodeId)
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString<CommentResponseV2>(file.readText())
        }.getOrNull()
    }

    suspend fun writeResponse(episodeId: Long, response: CommentResponseV2): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val file = fileFor(episodeId)
                file.writeText(json.encodeToString(response))
                file.absolutePath
            }.getOrNull()
        }

    suspend fun delete(episodeId: Long) = withContext(Dispatchers.IO) {
        runCatching { fileFor(episodeId).delete() }
        Unit
    }

    /** 批量删除（P2 过期清理用）。 */
    suspend fun deleteAll(episodeIds: Collection<Long>) = withContext(Dispatchers.IO) {
        if (episodeIds.isEmpty()) return@withContext
        for (id in episodeIds) {
            runCatching { fileFor(id).delete() }
        }
        Unit
    }

    /** 清空整个缓存目录，返回被删掉的文件数。 */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        val files = runCatching { directory.listFiles() }.getOrNull() ?: return@withContext 0
        var removed = 0
        for (file in files) {
            if (runCatching { file.delete() }.getOrDefault(false)) removed++
        }
        removed
    }

    /**
     * 缓存占用字节数。
     *
     * 直接数落盘文件而不是累加 `count` 字段 —— 后者是「弹幕条数」不是「文件大小」，
     * 而用户想知道的正是「这些缓存占了我多少存储」。
     */
    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        val files = runCatching { directory.listFiles() }.getOrNull() ?: return@withContext 0L
        var total = 0L
        for (file in files) {
            total += runCatching { file.length() }.getOrDefault(0L)
        }
        total
    }

    private companion object {
        const val CACHE_DIRNAME = "danmaku"
    }
}
