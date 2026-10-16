package com.solidplayer.core.data.repository

import kotlinx.serialization.json.Json

/**
 * 首页文件夹的「图钉」与「显式顺序」两套偏好的持久化格式（DataStore 里两串 JSON 数组）。
 *
 * ### 为什么是文件夹路径的列表，而不是给 `media_file` 加一列
 * 首页的「文件夹」根本不是一张表，它是 `media_file` 按 `folderPath` 聚合出来的投影
 * （见 `MediaFileDao.observeFolderSummaries`）。图钉与顺序都是「跟人走」的偏好、不是文件属性，
 * 加列就得给聚合查询再加一层 `GROUP BY` 之外的开关，还要一次 Migration —— 得不偿失。
 * 存成 DataStore 里的一串路径，零 Migration，且目录消失时自然失配、不会报错。
 *
 * ### 两个 key 各管一件事
 * - `pinned_folders`（图钉）：**只有长按菜单能改**，拖拽永远不碰。这就是
 *   「拖一下就被置顶」那个毛病的根治法 —— 想置顶只能显式点菜单。
 * - `folder_order`（顺序）：谁排第几。拖拽与菜单里的「置顶」都只改它。
 *
 * 早期版本只有一个 key，同时当「图钉集合」和「置顶区顺序」用：想让某个目录停在名称序中间，
 * 只能把它塞进置顶区，于是**顺带置顶 + 自己飞到列表顶部**。两件事必须拆开才能各说各话。
 *
 * ### 为什么是 List 而不是 Set
 * **顺序本身是数据**。`Set` 没有顺序，换回 List 时顺序就变成未定义的了。
 *
 * 抽成独立对象是为了让这段格式能被**纯 JVM 单测**覆盖 —— 与 [LibraryFolderCodec] 同理：
 * DataStore 里存的是字符串，编解码写错等于静默丢掉用户全部的图钉与顺序。
 */
object PinnedFolderCodec {

    /**
     * 最多记住这么多条图钉。
     *
     * 置顶是「首页前几个位置」的偏好，正常用户不会置顶几十个 —— 上限只是兜底，
     * 防止某次异常写入把 preferences 撑成大文件。
     */
    const val MAX_PINNED = 50

    /**
     * 显式顺序最多记这么长。
     *
     * 比图钉宽得多，因为**落点要靠它表达**：「拖到第 30 位」就必须把前 30 位都写下来。
     * 400 条路径约 20 KB，一次 DataStore 写入完全无感；真超上限时被截掉的那一段必然落在
     * 名称序区里，退回目录名序，用户看不出来。
     */
    const val MAX_ORDER = 400

    fun encode(paths: List<String>, json: Json): String =
        json.encodeToString(clean(paths, MAX_PINNED))

    fun encodeOrder(paths: List<String>, json: Json): String =
        json.encodeToString(clean(paths, MAX_ORDER))

    /**
     * 解码。
     *
     * 失败一律退化为空列表，绝不让脏数据把首页拖崩；顺手做三件规范化：
     * 去掉空串与磁盘路径里不可能出现的首尾空白、去重（**保留首次出现的位置**，顺序即优先级）。
     */
    fun decode(raw: String?, json: Json): List<String> = decodeLimited(raw, json, MAX_PINNED)

    fun decodeOrder(raw: String?, json: Json): List<String> = decodeLimited(raw, json, MAX_ORDER)

    /**
     * 置顶：**已有就先摘掉再插到最前**。
     *
     * 这样重复置顶只会把它提前，而不是留下两条；也顺带保证「列表内无重复」这条不变量
     * 不依赖 [decode]（运行期的列表是这里一步步算出来的，可能整场都没经过磁盘往返）。
     */
    fun pin(pinned: List<String>, folderPath: String): List<String> {
        val key = folderPath.trim()
        if (key.isEmpty()) return pinned
        return (listOf(key) + pinned.filterNot { it == key }).take(MAX_PINNED)
    }

    fun unpin(pinned: List<String>, folderPath: String): List<String> =
        pinned.filterNot { it == folderPath }

    /**
     * 摊平后的显式顺序。
     *
     * 盘上还没有 `folder_order` 时（老用户升级、或从没拖过任何一行）退化成**图钉列表本身**：
     * 旧版本里它正是「置顶区」，顺序即优先级 —— 于是升级后首页一模一样，零迁移。
     * 一旦用户拖过一行，`folder_order` 就有值了，此后它说了算。
     *
     * 注意 `folder_order` 里那些**已经不存在的目录**（文件被删 / 换存储根 / SD 卡没挂上）
     * 一律原样留着，只在 [effectiveOrder] 之后的渲染里自然失配、不显示 —— 写回会引入
     * 「每次进首页都可能触发一次 DataStore 写入」的路径，而代价只是列表里少一项（不会出错）。
     */
    fun effectiveOrder(order: List<String>, pinned: List<String>): List<String> =
        if (order.isEmpty()) pinned else order

    /**
     * 拖拽落点 → 新的显式顺序。
     *
     * [visible] 必须是**松手那一刻界面上的顺序**（被拖项已经在落点上），[order] 是摊平后的
     * 现有顺序（见 [effectiveOrder]），[from] / [to] 是被拖项在拖之前的起点与落点下标。
     *
     * 规则只有一条：**显式顺序至少要覆盖到落点**（`visible.take(to + 1)`），剩下的原有显式项
     * 按原相对顺序接在后面，没进顺序的目录仍按目录名序排在最后。
     *
     * 为什么要覆盖到落点：首页 = 「显式顺序里的项（按顺序）+ 其余（按目录名）」。
     * 想让被拖项停在中间某处，它**前面**的每一项都得先被显式定序，否则「中间」无从表达 ——
     * 早先「落在名称序区就吸附回顶部」的写法看着像省事，实际是「拖了它自己飞回上面」。
     *
     * 为什么落盘只认 [visible] 而不自己从 `order` 重算一遍：这样「用户看到的」与「盘上存的」
     * 逐字相同（见 `FolderListViewModel.commitReorder` 的乐观更新共用同一条不变量）。
     * [from] 只用来判断「有没有真的动过」——没换过位就原样返回，一次拖拽不该产生一版新顺序。
     */
    fun moveOrder(
        visible: List<String>,
        order: List<String>,
        from: Int,
        to: Int,
    ): List<String> {
        if (from !in visible.indices || to !in visible.indices || from == to) return order
        val required = visible.take(to + 1)
        val requiredSet = required.toHashSet()
        val rest = order.filterNot { it in requiredSet }
        return (required + rest).take(MAX_ORDER)
    }

    /** 统一口径的清洗：去空白、去空串、去重（保留首次出现）、截断。 */
    private fun clean(paths: List<String>, limit: Int): List<String> =
        paths.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(limit)

    private fun decodeLimited(raw: String?, json: Json, limit: Int): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())
            .let { clean(it, limit) }
    }
}
