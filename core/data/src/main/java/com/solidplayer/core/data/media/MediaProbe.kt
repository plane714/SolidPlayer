package com.solidplayer.core.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频元信息探测。
 *
 * P0 只用它拿时长 —— `POST /api/v2/match` 的 `videoDuration` 虽是可选项，
 * 但官方明确说「提供可显著提高识别率」。
 *
 * 刻意**不在扫描阶段**批量探测：`MediaMetadataRetriever` 要真正打开容器，
 * 几百个文件会把首扫拖成分钟级。改为「要识别哪个文件时才探哪一个」。
 */
@Singleton
class MediaProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 返回时长（秒）；探测失败返回 0，调用方按「无时长」继续，不阻断流程。 */
    suspend fun durationSeconds(uri: Uri): Int = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return@withContext 0
            (ms / 1000L).toInt().coerceAtLeast(0)
        } catch (_: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun durationSeconds(uriString: String): Int = durationSeconds(uriString.toUri())
}
