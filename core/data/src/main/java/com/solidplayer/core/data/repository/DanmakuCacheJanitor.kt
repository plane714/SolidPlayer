package com.solidplayer.core.data.repository

import com.solidplayer.core.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弹幕缓存的过期清理（P2）。
 *
 * 为什么需要它：缓存的 TTL 判定发生在**读取时**（`readFromCache` 发现过期就去网络），
 * 于是「再也不会被打开的那些剧集」的缓存永远等不到人来读，只会一直占着存储。
 * 这里在每次进程启动时跑一遍 TTL 清理兜住这部分。
 *
 * 跑在应用级作用域上，**不阻塞冷启动**：清理失败也无所谓，
 * 最坏结果就是过期缓存多留一个进程生命周期。
 */
@Singleton
class DanmakuCacheJanitor @Inject constructor(
    private val danmakuRepository: DanmakuRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    fun pruneOnStart() {
        appScope.launch {
            runCatching { danmakuRepository.pruneExpiredCache() }
            Unit
        }
    }
}
