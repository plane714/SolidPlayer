package com.solidplayer.core.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「NEW」角标判定单测。
 *
 * 这条口径有**两个**消费方，而它们走的是两条完全不同的路径：
 * - 文件夹详情页的文件行：Kotlin 侧调 [isNew]；
 * - 媒体库首页的文件夹角标：SQL 里的 `COUNT(CASE WHEN dateAdded >= :newSince AND lastViewedAt = 0 …)`。
 *
 * 两边一旦漂移，用户看到的就是「列表里没有 NEW，角标却还挂着 3」这种无法自解释的状态，
 * 所以这里把边界钉死。SQL 那半边无法在纯 JVM 单测里跑到，改它时必须回头对一遍本文件。
 */
class NewBadgeTest {

    private val now = 1_700_000_000_000L

    /** 与 `VideoLibraryRepository.NEW_WINDOW_MS` 同值（7 天）；不直接引用是为了让测试表达口径本身。 */
    private val window = 7L * 24L * 60L * 60L * 1000L

    private val newSince = now - window

    private fun file(dateAdded: Long, lastViewedAt: Long = 0L): MediaFileEntity =
        MediaFileEntity(
            uri = "content://test/$dateAdded-$lastViewedAt",
            displayName = "测试.mkv",
            dateAdded = dateAdded,
            lastViewedAt = lastViewedAt,
        )

    @Test
    fun `窗口内且从未点开 算 NEW`() {
        assertTrue(file(dateAdded = now - 1L).isNew(newSince))
    }

    @Test
    fun `点开过就不再是 NEW`() {
        // 这正是本次改动的核心：看过就消失，而不是等 7 天自然过期
        assertFalse(file(dateAdded = now - 1L, lastViewedAt = now).isNew(newSince))
    }

    @Test
    fun `超出 7 天窗口不再是 NEW`() {
        assertFalse(file(dateAdded = newSince - 1L).isNew(newSince))
    }

    @Test
    fun `正好落在窗口边界上算 NEW`() {
        // 边界取「含」：SQL 侧写的是 >=，两边必须一致
        assertTrue(file(dateAdded = newSince).isNew(newSince))
    }

    @Test
    fun `拿不到入库时间的行不算 NEW`() {
        // dateAdded = 0 的退化情形：v1 的 SAF 旧行、磁盘遍历队不上的行
        assertFalse(file(dateAdded = 0L).isNew(newSince))
    }

    @Test
    fun `点开又重扫（dateAdded 更新）后仍不复活`() {
        // 重扫时 dateAdded 可能被 MediaStore 的入库时间覆盖成新值，
        // 但 lastViewedAt 是用户行为，不该被扫描抹掉 —— 看过就是看过
        assertFalse(file(dateAdded = now, lastViewedAt = now - 5_000L).isNew(newSince))
    }
}
