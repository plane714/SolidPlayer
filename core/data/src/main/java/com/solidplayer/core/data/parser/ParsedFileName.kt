package com.solidplayer.core.data.parser

/**
 * 文件名解析结果（文档 §6.4）。
 *
 * ⚠️ 文档里 `episode` 的声明写成了 `String?`（明显笔误），按其注释语义应为 **String?** ——
 * 需要保留字符串形态以兼容 `C1` / `S1` / `O1` 这类花絮、特别篇、OVA 编号。
 */
data class ParsedFileName(
    /** 原始文件名（含扩展名） */
    val raw: String,
    /** 清洗后的标题，用于 search API */
    val title: String,
    /** 季数，无则 null */
    val season: Int?,
    /** 集数，字符串形态 */
    val episode: String?,
    /** 规则命中置信度 [0f, 1f]，供 UI 排序参考 */
    val confidence: Float,
) {
    val hasEpisode: Boolean get() = !episode.isNullOrBlank()

    /** search API 的查询关键词：标题 + 集数 */
    fun searchKeyword(): String = listOfNotNull(title.ifBlank { null }, episode).joinToString(" ")
}
