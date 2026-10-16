package com.solidplayer.core.danmaku.parser

import com.solidplayer.core.danmaku.model.DanmakuItem
import com.solidplayer.core.danmaku.model.DanmakuMode

/** `p` 字段解析结果。 */
data class ParsedP(
    val timeMs: Long,
    val mode: DanmakuMode,
    val color: Int,
    val userId: String,
)

/**
 * 弹弹play `p` 字段解析（文档 §4.3 / §8.2）。
 *
 * `p` 格式：`出现时间(秒,两位小数),模式,颜色,用户ID`
 * - 模式：`1` 普通滚动 / `4` 底部 / `5` 顶部 —— **与 B 站不同**，不可照抄 B 站解析器
 * - 颜色：32 位整数，`R × 65536 + G × 256 + B`
 *
 * 解析**必须容错**：线上弹幕数据总有脏数据，任何一条字段异常只跳过该条，绝不中断整批解析。
 */
object PFieldParser {

    /** 颜色字段缺失时的兜底值：白色 */
    const val DEFAULT_COLOR: Int = 0xFFFFFF

    private const val MODE_BOTTOM = 4
    private const val MODE_TOP = 5

    /**
     * 解析单条 `p`。返回 `null` 表示该条数据非法，调用方应当跳过。
     */
    fun parse(p: String): ParsedP? {
        if (p.isEmpty()) return null
        val parts = p.split(',')
        if (parts.size < 3) return null

        val seconds = parts[0].trim().toFloatOrNull() ?: return null
        if (seconds.isNaN() || seconds < 0f) return null

        val modeCode = parts[1].trim().toIntOrNull() ?: return null

        // 颜色不是必需字段，缺了就退回默认色，不因此丢弃整条弹幕
        val rawColor = parts[2].trim().toIntOrNull() ?: DEFAULT_COLOR

        return ParsedP(
            timeMs = (seconds * 1000f).toLong(),
            mode = modeOf(modeCode),
            // ★ 只留 24 位 RGB，高 8 位（alpha）恒为 0。交给 Paint 之前必须先过
            //   `DanmakuColor.withAlpha` 补上不透明位，否则文字全透明、一条都看不见（文档 §17.3）
            color = rawColor and 0xFFFFFF,
            userId = parts.getOrNull(3)?.trim().orEmpty(),
        )
    }

    /**
     * 模式数值 → [DanmakuMode]。
     * 未知数值按滚动处理（宁可渲染出来，也不要静默丢弹幕）。
     */
    fun modeOf(code: Int): DanmakuMode = when (code) {
        MODE_BOTTOM -> DanmakuMode.BOTTOM
        MODE_TOP -> DanmakuMode.TOP
        else -> DanmakuMode.SCROLL
    }

    /**
     * 由 `(p, m)` 组装一条可渲染弹幕。文本为空或 `p` 非法的返回 `null`。
     */
    fun parseItem(p: String, text: String): DanmakuItem? {
        val parsed = parse(p) ?: return null
        val content = text.trim()
        if (content.isEmpty()) return null
        return DanmakuItem(
            timeMs = parsed.timeMs,
            mode = parsed.mode,
            color = parsed.color,
            text = content,
        )
    }

    /**
     * 批量解析。脏数据被逐条跳过，返回值仍按时间升序（由 [com.solidplayer.core.danmaku.model.DanmakuTimeline] 保证）。
     */
    fun parseAll(entries: List<Pair<String, String>>): List<DanmakuItem> {
        val out = ArrayList<DanmakuItem>(entries.size)
        for ((p, m) in entries) {
            parseItem(p, m)?.let(out::add)
        }
        return out
    }
}
