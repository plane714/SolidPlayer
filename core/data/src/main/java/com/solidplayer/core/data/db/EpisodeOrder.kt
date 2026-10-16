package com.solidplayer.core.data.db

/**
 * 集数排序。
 *
 * 媒体库的「番剧分组列表」与播放页的「上一集 / 下一集」**共用同一个比较器**。
 * 两处各写一套迟早会错位：列表里明明排在第 3 位，点「下一集」却跳到了第 5 集，
 * 而这种错位在真机上极难归因。
 *
 * 排序键（与 P1 的 `groupByAnime` 完全一致）：
 * 1. 能解析成整数的集数优先 —— `C1` / `O1` 这类特别篇、OVA 解析不出来，统一排到末尾
 * 2. 再按集数字符串，给 `1` 与 `01` 一个稳定顺序
 * 3. 最后按文件名兜底，保证集数完全并列（同一集的多个清晰度）时顺序稳定
 */
val EpisodeOrder: Comparator<MediaFileDisplay> = compareBy(
    { it.episodeNumber?.toIntOrNull() ?: Int.MAX_VALUE },
    { it.episodeNumber.orEmpty() },
    { it.file.displayName },
)
