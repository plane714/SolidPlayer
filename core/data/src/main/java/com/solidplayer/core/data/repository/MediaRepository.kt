package com.solidplayer.core.data.repository

import android.net.Uri
import com.solidplayer.core.data.db.EpisodeOrder
import com.solidplayer.core.data.db.MatchType
import com.solidplayer.core.data.db.MediaFileDao
import com.solidplayer.core.data.db.MediaFileDisplay
import com.solidplayer.core.data.db.MediaFileEntity
import com.solidplayer.core.data.di.IoDispatcher
import com.solidplayer.core.data.hash.FileHashCalculator
import com.solidplayer.core.data.media.MediaProbe
import com.solidplayer.core.data.parser.FileNameParser
import com.solidplayer.core.data.scanner.MediaFileEntry
import com.solidplayer.core.data.scanner.MediaScanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 一次（多目录）扫描的汇总。 */
data class ScanSummary(
    /** 本次扫描成功的目录数 */
    val folders: Int,
    /** 本次扫到的视频文件数（含已入库、只是被重新扫到的） */
    val scannedFiles: Int,
)

/** 识别所需的文件指纹。 */
data class FileFingerprint(
    /** 文件前 16MB 的 MD5 */
    val hash: String,
    /** 时长（秒），探测失败为 0 */
    val durationSeconds: Int,
)

/**
 * 本地媒体库（文档 §9.2 / §7）。
 *
 * P0 只做「单目录扫描 + 入库」；P1 补多目录扫描与「待识别集合」的供给。
 * 批量识别本身在 [MatchRepository] 里，这里只提供扫描条目与指纹缓存。
 */
@Singleton
class MediaRepository @Inject constructor(
    private val scanner: MediaScanner,
    private val mediaFileDao: MediaFileDao,
    private val hashCalculator: FileHashCalculator,
    private val probe: MediaProbe,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 媒体库列表（含 join 出来的番剧 / 剧集标题），供 UI 分组展示。 */
    fun observeLibraryDetailed(): Flow<List<MediaFileDisplay>> = mediaFileDao.observeLibraryDetailed()

    suspend fun findById(id: Long): MediaFileEntity? = mediaFileDao.findById(id)

    suspend fun findByUri(uri: String): MediaFileEntity? = mediaFileDao.findByUri(uri)

    /**
     * 记下「用户打开过这个文件」（进入播放页时调用）。
     *
     * 这是 NEW 角标「点开即消」的唯一写入口。放这里而不是让 UI 直接打 DAO，
     * 是因为「看过」的口径与 [MediaFileEntity.isNew] 是一对：改一处就得改另一处。
     */
    suspend fun markViewed(id: Long) = withContext(io) {
        mediaFileDao.markViewed(id, System.currentTimeMillis())
    }

    /**
     * 同一番剧下已识别的文件，按集数排好序。
     *
     * 播放页用它拼连播列表 —— 与媒体库分组复用 [EpisodeOrder]，
     * 保证「媒体库里看到的顺序」就是「点下一集跳过去的顺序」。
     */
    suspend fun siblingsOf(animeId: Long): List<MediaFileDisplay> = withContext(io) {
        mediaFileDao.siblingsOf(animeId).sortedWith(EpisodeOrder)
    }

    // ── 扫描 ──────────────────────────────────────────────────────

    /**
     * 扫描一个 SAF 目录树并落库。
     *
     * 扫描阶段**不计算哈希**、**不探测时长** —— 那两项都是重 I/O，
     * 放在「用户真的要对这个文件做识别」时才按需执行（文档 §12.5）。
     *
     * @return 扫描到的视频文件数
     */
    suspend fun scanTree(treeUri: Uri): Int = withContext(io) {
        val entries = scanner.scanTree(treeUri)
        val now = System.currentTimeMillis()
        for (entry in entries) {
            persistScannedEntry(entry, now)
        }
        entries.size
    }

    /** P1：扫描多个目录，逐个落库。单个目录失败不影响其它目录。 */
    suspend fun scanFolders(treeUris: List<String>): ScanSummary = withContext(io) {
        var scanned = 0
        var ok = 0
        for (raw in treeUris) {
            val count = runCatching { scanTree(Uri.parse(raw)) }.getOrNull() ?: continue
            scanned += count
            ok++
        }
        ScanSummary(folders = ok, scannedFiles = scanned)
    }

    private suspend fun persistScannedEntry(entry: MediaFileEntry, now: Long) {
        val parsed = FileNameParser.parse(entry.displayName)

        val inserted = mediaFileDao.insertIfAbsent(
            MediaFileEntity(
                uri = entry.uriString,
                documentId = entry.documentId,
                displayName = entry.displayName,
                relativePath = entry.relativePath,
                sizeBytes = entry.sizeBytes,
                lastModified = entry.lastModified,
                parsedTitle = parsed.title,
                parsedSeason = parsed.season,
                parsedEpisode = parsed.episode,
                matchType = MatchType.NONE,
                createdAt = now,
                updatedAt = now,
            ),
        )

        // IGNORE 策略下，已存在的行返回 -1，此时只刷新文件元信息，
        // 绝不能顺手清掉已识别出的 episodeId 与已算过的 fileHash（约束 B）
        if (inserted == -1L) {
            mediaFileDao.updateScannedMeta(
                uri = entry.uriString,
                displayName = entry.displayName,
                relativePath = entry.relativePath,
                sizeBytes = entry.sizeBytes,
                lastModified = entry.lastModified,
                now = now,
            )
        }
    }

    // ── 待识别集合（批量识别的输入）────────────────────────────────

    /** 尚未识别出 episodeId 的文件，转为扫描条目。 */
    suspend fun pendingEntries(): List<MediaFileEntry> = withContext(io) {
        mediaFileDao.findUnmatched().map(::toEntry)
    }

    /**
     * 按 id 取回条目（P2「仅重试失败项」）。
     *
     * 与 [pendingEntries] 的区别：那个按「还没识别」筛，这个按「上一轮谁失败了」筛 ——
     * 识别失败的文件确实也满足 `episodeId IS NULL`，但重试时没必要把从没试过的文件也拉进来一起跑。
     */
    suspend fun entriesFor(ids: List<Long>): List<MediaFileEntry> = withContext(io) {
        if (ids.isEmpty()) emptyList() else mediaFileDao.findByIds(ids).map(::toEntry)
    }

    /** 「重新识别」：清空自动识别结果（保留 manual），返回被清掉的行数。 */
    suspend fun resetAutoMatches(): Int = withContext(io) {
        mediaFileDao.clearAutoMatches(System.currentTimeMillis())
    }

    // ── 指纹 ──────────────────────────────────────────────────────

    /**
     * 一次性取回 `(前 16MB MD5, 时长)`，带 `(uri, size, lastModified)` 三元组缓存。
     *
     * 同一文件重复计算 16MB 的 I/O 是无法接受的 —— 一次扫描 100 个文件就是 1.6GB 的读取量（文档 §5）。
     * 这里用「库里的 fileHash + size/lastModified 是否仍然吻合」来判定缓存是否有效。
     *
     * 时长与哈希**一起返回**：两者都要打开文件，分开调用等于把容器解析两遍。
     */
    suspend fun fingerprint(entry: MediaFileEntry): FileFingerprint = withContext(io) {
        val existing = mediaFileDao.findByUri(entry.uriString)
        val cached = existing?.fileHash
        if (!cached.isNullOrBlank() &&
            existing.sizeBytes == entry.sizeBytes &&
            existing.lastModified == entry.lastModified
        ) {
            return@withContext FileFingerprint(cached, (existing.durationMs / 1000L).toInt())
        }

        val hash = hashCalculator.md5OfFirst16MB(entry.uri)
        val durationSeconds = probe.durationSeconds(entry.uri)
        mediaFileDao.updateFileMeta(
            uri = entry.uriString,
            displayName = entry.displayName,
            relativePath = entry.relativePath,
            sizeBytes = entry.sizeBytes,
            lastModified = entry.lastModified,
            durationMs = durationSeconds.toLong() * 1000L,
            fileHash = hash,
            now = System.currentTimeMillis(),
        )
        FileFingerprint(hash, durationSeconds)
    }

    /** 由库中记录还原一个扫描条目，供播放/识别流程复用。 */
    fun toEntry(entity: MediaFileEntity): MediaFileEntry = MediaFileEntry(
        uri = Uri.parse(entity.uri),
        documentId = entity.documentId.orEmpty(),
        displayName = entity.displayName,
        relativePath = entity.relativePath.orEmpty(),
        sizeBytes = entity.sizeBytes,
        lastModified = entity.lastModified,
    )
}
