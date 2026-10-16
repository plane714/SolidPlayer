package com.solidplayer.core.data.repository

import com.solidplayer.core.data.di.ApplicationScope
import com.solidplayer.core.data.network.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 媒体库批量识别的调度器（文档 §9.2）。
 *
 * ### 为什么不是 WorkManager
 * 文档 §3.1 / §9.2 写的是 WorkManager，但本项目当前**离线构建**，
 * `androidx.work` 不在 Gradle 离线缓存里，加依赖会直接构建失败。
 * 折中方案：跑在**应用级作用域**（`@ApplicationScope`，生命周期与进程一致）上的单例调度器 ——
 * 用户从媒体库页返回、切到播放页都不会中断识别，进度通过 [progress] 暴露给任意页面。
 *
 * 相比 WorkManager 缺的是「进程被杀后续跑」，而识别本身是**可重入**的：
 * `findUnmatched()` 只会捞 `episodeId IS NULL` 的行，被杀后重新点一次「识别」即等价于续跑。
 */
@Singleton
class LibraryIdentifyCoordinator @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val matchRepository: MatchRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val matched: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        /** 上一轮失败的文件 id（P2：给「仅重试失败项」用）；只活在内存里 */
        val failedIds: List<Long> = emptyList(),
        /** 一次性提示（完成汇总 / 失败原因），UI 消费后调 [consumeMessage] */
        val message: String? = null,
    ) {
        val percent: Float
            get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

        /** 是否有「只重试失败项」可点 —— 运行中不给点，避免和当前任务打架。 */
        val canRetryFailed: Boolean get() = !running && failedIds.isNotEmpty()
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var job: Job? = null

    /** 识别所有待处理文件；已在跑则忽略本次请求。 */
    fun identifyPending() = start(reset = false)

    /** 「重新识别」：先清掉自动识别结果（`manual` 保留），再重跑一遍。 */
    fun reidentifyAll() = start(reset = true)

    /**
     * 只重试上一轮失败的文件（P2）。
     *
     * 全量识别里「从未识别」和「识别失败」是混在一起的，用户想再争取一次时
     * 没必要把已经识别成功的几百个文件重新跑一遍 16MB 哈希。
     */
    fun retryFailed() {
        val ids = _progress.value.failedIds
        if (ids.isEmpty()) return
        start(reset = false, onlyIds = ids)
    }

    fun cancel() {
        job?.cancel()
        job = null
        _progress.update { it.copy(running = false, message = "已取消识别") }
    }

    /**
     * 只识别指定的这批文件。
     *
     * 媒体库首页的「文件夹」是分页进入的，用户在某个目录里点「识别本目录」时，
     * 期望的是「就识别我眼前这些」，而不是把全机几千个待识别文件一起跑掉。
     */
    fun identifyFiles(ids: List<Long>) {
        if (ids.isEmpty()) return
        start(reset = false, onlyIds = ids)
    }

    fun consumeMessage() = _progress.update { it.copy(message = null) }

    private fun start(reset: Boolean, onlyIds: List<Long>? = null) {
        if (job?.isActive == true) return
        job = appScope.launch { runIdentify(reset, onlyIds) }
    }

    private suspend fun runIdentify(reset: Boolean, onlyIds: List<Long>? = null) {
        _progress.value = Progress(running = true, message = if (reset) "正在清除旧的识别结果…" else "正在准备…")

        if (reset) {
            runCatching { mediaRepository.resetAutoMatches() }
        }

        val entries = runCatching {
            if (onlyIds == null) mediaRepository.pendingEntries() else mediaRepository.entriesFor(onlyIds)
        }.getOrDefault(emptyList())

        if (entries.isEmpty()) {
            _progress.value = Progress(running = false, message = "没有待识别的文件")
            return
        }

        _progress.value = Progress(running = true, total = entries.size, message = "正在计算文件指纹…")

        when (val result = matchRepository.identifyBatch(entries) { done, total ->
            _progress.update { it.copy(running = true, done = done, total = total) }
        }) {
            is ApiResult.Failure -> _progress.update { it.copy(running = false, message = describe(result)) }

            is ApiResult.Success -> {
                val summary = result.data
                _progress.update {
                    it.copy(
                        running = false,
                        done = summary.attempted,
                        total = entries.size,
                        matched = summary.matched,
                        failed = summary.failed,
                        skipped = summary.skipped,
                        failedIds = summary.failedIds,
                        message = "识别完成：成功 ${summary.matched}，" +
                            "未匹配 ${summary.failed}，跳过 ${summary.skipped}",
                    )
                }
            }
        }
    }

    private fun describe(failure: ApiResult.Failure): String = when {
        failure.isCredentialMissing -> "尚未配置弹弹play 凭据，请先前往设置填写 AppId / AppSecret"
        failure.isAuthFailure -> "AppId 或 AppSecret 有误，请前往设置重新填写"
        else -> "识别中断：${failure.message}"
    }
}
