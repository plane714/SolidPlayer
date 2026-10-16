package com.solidplayer.core.data.repository

import com.solidplayer.core.data.network.dto.BatchMatchResponseItem

/**
 * 批量识别的纯逻辑：切批 + 把响应逐条对回请求。
 *
 * 单独抽出来是因为这两步最容易出错（切错批 → 整批 400；对错序 → episodeId 串到别的文件上），
 * 而它们本身完全不需要 Android 环境，**可以直接 JVM 单测**。
 */
object BatchIdentifyPlanner {

    /** 按 API 上限切批；`limit <= 0` 视为不切。 */
    fun <T> batches(items: List<T>, limit: Int): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        if (limit <= 0) return listOf(items)
        return items.chunked(limit)
    }

    /**
     * 把批量响应对回请求。
     *
     * 官方按**请求顺序**返回结果，因此数量一致**且每条回显的 fileHash 都能对上**时，
     * 直接按下标对齐 —— 这是最可靠的路径。
     *
     * 只要有一个条件不满足（服务端过滤、丢项，或回显的 hash 与请求不符），
     * 就退化成按 `fileHash` 匹配；仍匹配不到的对 `null`，由调用方记为「识别失败」。
     * **宁可漏标，也不能把别人的结果错填到这个文件上** —— 那会播错弹幕且很难被发现。
     *
     * @return 与 [requests] 等长、一一对应的 `(请求, 响应项?)` 列表
     */
    fun <T> align(
        requests: List<T>,
        results: List<BatchMatchResponseItem>,
        hashOf: (T) -> String,
    ): List<Pair<T, BatchMatchResponseItem?>> {
        if (requests.isEmpty()) return emptyList()

        val canAlignByIndex = results.size == requests.size &&
            results.withIndex().all { (index, item) ->
                item.fileHash == null || item.fileHash == hashOf(requests[index])
            }
        if (canAlignByIndex) {
            return requests.mapIndexed { index, request -> request to results[index] }
        }

        val byHash = HashMap<String, BatchMatchResponseItem>(results.size)
        for (item in results) {
            val key = item.fileHash ?: continue
            if (!byHash.containsKey(key)) byHash[key] = item
        }
        return requests.map { request -> request to byHash[hashOf(request)] }
    }
}
