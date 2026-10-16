package com.solidplayer.core.data.repository

import com.solidplayer.core.data.db.MatchType
import com.solidplayer.core.data.db.MediaFileDao
import com.solidplayer.core.data.db.MediaFileEntity
import com.solidplayer.core.data.db.VideoFolderSummary
import com.solidplayer.core.data.di.IoDispatcher
import com.solidplayer.core.data.parser.FileNameParser
import com.solidplayer.core.data.scanner.DiscoveredVideo
import com.solidplayer.core.data.scanner.VideoScanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 一次全机自动刷新的结果。 */
data class DiscoverSummary(
    /** 扫到的视频文件总数 */
    val videos: Int,
    /** 去重后的目录数 */
    val folders: Int,
    /** 因文件已不存在而被清掉的行数 */
    val removed: Int,
    /** 本次是否降级成了「只读媒体库索引」（分区存储下读不到真实目录时为 true） */
    val degraded: Boolean,
)

/**
 * 自动发现的媒体库（T3 首页）。
 *
 * 分工：扫描与合并交给 [VideoScanner]（不碰数据库），落库与聚合查询在这里。
 *
 * 为什么**先落库再展示**，而不是直接把扫描结果丢给 UI：
 * 1. 识别结果（`episodeId` / `animeId`）本来就挂在 `media_file` 行上，
 *    UI 必须从库里读，才能显示「已识别 · 第 7 集」；
 * 2. 目录聚合（`GROUP BY folderPath`）是 SQL 的强项，写在 Kotlin 里只会重复造轮子；
 * 3. 扫一次的结果能被多页复用，退到首页不会触发重扫。
 */
@Singleton
class VideoLibraryRepository @Inject constructor(
    private val scanner: VideoScanner,
    private val mediaFileDao: MediaFileDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 「本次进程已自动扫过」的闸门，见 [claimAutoScan]。 */
    private val autoScanGate = AtomicBoolean(false)

    /** 扫描互斥，见 [refresh]。 */
    private val refreshMutex = Mutex()

    /**
     * 首页的文件夹列表。
     *
     * `newSince` 在**每次订阅时**现算：「NEW」是「当下往前 7 天」，而查询参数一旦传给
     * Room 就固定了。页面每次进来重新订阅，窗口就跟着刷新 —— 角标是唯一会因此过期的信息，
     * 不值得为它再摆一套定时任务。
     *
     * 另一半条件是「用户还没点开过」（`lastViewedAt = 0`），那半边不需要窗口：
     * `markViewed` 写库会让 Room 重发这条查询，行内 NEW 与文件夹角标在同一次刷新里一起变。
     */
    fun observeFolders(): Flow<List<VideoFolderSummary>> =
        mediaFileDao.observeFolderSummaries(System.currentTimeMillis() - NEW_WINDOW_MS)

    /** 某个目录下的视频（图二列表）。 */
    fun observeFolderVideos(folderPath: String): Flow<List<MediaFileEntity>> =
        mediaFileDao.observeFolderVideos(folderPath)

    /** 当前是否有能力直接遍历共享存储（决定首页要不要提示「结果可能不全」）。 */
    fun canTraverse(): Boolean = scanner.canTraverse()

    /**
     * 「本次进程是否该自动扫一次」的闸门 —— 只有第一次调用返回 `true`。
     *
     * 为什么要放在**单例**上而不是 ViewModel 的字段里：扫描策略是「只在这几个时机扫」，
     * 而 ViewModel 是导航栈条目的作用域 —— 旋转、系统回收后恢复、甚至导航图重建都会
     * 换出一个新实例，把它记在 VM 里等于「某些重建会顺带重扫一遍全机」。
     * 记在单例上，生命周期就跟进程一致：**冷启动扫一次，之后只认手动下拉**。
     *
     * 用 `compareAndSet` 而不是「读 + 写」两步：并发进来的两次调用也只会有一个拿到放行。
     */
    fun claimAutoScan(): Boolean = autoScanGate.compareAndSet(false, true)

    /**
     * 全机重新发现一遍。
     *
     * 只有**全部扫完**才会清「没再看到」的行：扫描中途抛异常时直接往上抛，
     * 一行都不删 —— 宁可留下已删除文件的僵尸行，也不能因为一次失败的扫描把整个库掏空。
     *
     * 用 [refreshMutex] 串行化：首页与文件夹详情页现在都能下拉刷新，
     * 两页各自的「正在扫描」标志互相看不见，只靠调用侧去重挡不住并发扫描 ——
     * 并发跑两遍遍历不只是浪费 I/O，第二轮还会按自己的 `scanStartedAt` 去删
     * 「第一轮扫到、第二轮还没扫到」的行。
     */
    suspend fun refresh(onFolder: (String) -> Unit = {}): DiscoverSummary = refreshMutex.withLock {
        withContext(io) {
            val scanStartedAt = System.currentTimeMillis()
            val videos = scanner.scan(onFolder)

            val seenAt = System.currentTimeMillis()
            for (video in videos) {
                persist(video, seenAt)
            }

            val removed = mediaFileDao.deleteUnseenScanned(scanStartedAt)
            DiscoverSummary(
                videos = videos.size,
                folders = videos.asSequence().map { it.folderPath }.distinct().count(),
                removed = removed,
                degraded = !scanner.canTraverse(),
            )
        }
    }

    /**
     * 落库一个扫到的视频。
     *
     * `uri` 是 `media_file` 的唯一键，但它**会变**：同一个文件在「没被 MediaStore 索引」时
     * 只能用 `file://`，被索引之后换成 `content://media/...`。
     * 所以换源插入前先按稳定键（绝对路径，存在 `documentId`）把旧行捞出来，
     * 把识别结果整体带过去 —— 否则每次 uri 形态一变，用户辛苦识别出来的集数就白费了。
     */
    private suspend fun persist(video: DiscoveredVideo, seenAt: Long) {
        val previous = mediaFileDao.findByDocumentId(video.path)
        if (previous == null || previous.uri != video.uri) {
            insert(video, seenAt, previous)
            return
        }
        mediaFileDao.updateDiscoveredMeta(
            uri = video.uri,
            displayName = video.displayName,
            folderPath = video.folderPath,
            folderName = video.folderName,
            dateAdded = video.dateAdded,
            subtitleFormats = video.subtitleFormats.joinToString(",").ifBlank { null },
            sizeBytes = video.sizeBytes,
            lastModified = video.lastModified,
            durationMs = video.durationMs,
            source = video.source,
            lastSeenAt = seenAt,
            now = seenAt,
        )
    }

    private suspend fun insert(video: DiscoveredVideo, seenAt: Long, previous: MediaFileEntity?) {
        val parsed = FileNameParser.parse(video.displayName)
        mediaFileDao.insertIfAbsent(
            MediaFileEntity(
                uri = video.uri,
                // 绝对路径当稳定键（v1 的 SAF 行这里存的是 documentId，两套值不会互相干扰）
                documentId = video.path,
                displayName = video.displayName,
                relativePath = video.folderPath,
                folderPath = video.folderPath,
                folderName = video.folderName,
                sizeBytes = video.sizeBytes,
                lastModified = video.lastModified,
                dateAdded = video.dateAdded,
                subtitleFormats = video.subtitleFormats.joinToString(",").ifBlank { null },
                source = video.source,
                lastSeenAt = seenAt,
                durationMs = previous?.durationMs ?: video.durationMs,
                fileHash = previous?.fileHash,
                parsedTitle = previous?.parsedTitle ?: parsed.title,
                parsedSeason = previous?.parsedSeason ?: parsed.season,
                parsedEpisode = previous?.parsedEpisode ?: parsed.episode,
                animeId = previous?.animeId,
                episodeId = previous?.episodeId,
                matchType = previous?.matchType ?: MatchType.NONE,
                confidence = previous?.confidence ?: 0f,
                createdAt = previous?.createdAt ?: seenAt,
                updatedAt = seenAt,
            ),
        )
    }

    companion object {
        /** 「NEW」的判定窗口：入库 7 天内算新。 */
        const val NEW_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
