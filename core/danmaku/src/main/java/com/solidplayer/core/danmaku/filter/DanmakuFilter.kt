package com.solidplayer.core.danmaku.filter

import com.solidplayer.core.danmaku.model.DanmakuItem

/**
 * 弹幕屏蔽词过滤器（P2）。
 *
 * ### 规则语法
 * - 普通词：按**包含**匹配，大小写不敏感（例：`前排`、`fuck`）
 * - 正则：写成 `/.../`，斜杠内是正则体，同样大小写不敏感（例：`/^\d{4}-\d{2}-\d{2}$/`）
 *
 * ### 两条容错约定
 * 1. **正则写错不吞规则**：`/.../` 编译失败时退化成「按斜杠**内**的文本做字面量包含匹配」。
 *    用户既然写了 `/`，八成是想挡那串字符；退化成字面量比整条规则消失（或把 `/[a-/`
 *    连斜杠一起当字面量，几乎永远匹配不到）都有用。
 * 2. **单独一个 `/` 不是正则**：规则长度不足 3 时一律按字面量处理。
 *
 * ### 为什么在「解析期」过滤而不是渲染期
 * 过滤一次省下的是内存 + 轨道占用 + 每帧的字符串判断；代价只是改屏蔽词要重新解析，
 * 而原始 JSON 就在本地（`DanmakuCacheStore`），重解析零网络开销。
 * 反过来放渲染期，等于拿帧预算换重解析，不划算。
 *
 * 本类**零 Android 依赖**，可直接 JVM 单测。
 */
class DanmakuFilter private constructor(
    /** 已统一小写的字面量关键词 */
    private val keywords: List<String>,
    private val patterns: List<Regex>,
) {

    /** 规则为空表示「不过滤」，调用方可以据此跳过整趟遍历。 */
    val isEmpty: Boolean get() = keywords.isEmpty() && patterns.isEmpty()

    /** 规则条数（字面量 + 正则），仅用于展示与断言。 */
    val size: Int get() = keywords.size + patterns.size

    /** `true` = 该文本命中屏蔽规则，应当丢弃。 */
    fun matches(text: String): Boolean {
        if (isEmpty) return false
        for (pattern in patterns) {
            if (pattern.containsMatchIn(text)) return true
        }
        if (keywords.isEmpty()) return false
        val lower = text.lowercase()
        for (keyword in keywords) {
            if (lower.contains(keyword)) return true
        }
        return false
    }

    /**
     * 过滤整条时间轴。规则为空时**原样返回同一个列表**，不产生任何拷贝。
     */
    fun apply(items: List<DanmakuItem>): List<DanmakuItem> {
        if (isEmpty || items.isEmpty()) return items
        val kept = ArrayList<DanmakuItem>(items.size)
        for (item in items) {
            if (!matches(item.text)) kept.add(item)
        }
        return kept
    }

    companion object {

        val NONE = DanmakuFilter(emptyList(), emptyList())

        /**
         * 由用户输入的规则列表构建过滤器。空白规则被忽略，重复规则只保留一条。
         */
        fun of(rules: List<String>): DanmakuFilter {
            if (rules.isEmpty()) return NONE

            val keywords = LinkedHashSet<String>()
            val patterns = ArrayList<Regex>()

            for (raw in rules) {
                val rule = raw.trim()
                if (rule.isEmpty()) continue

                val body = regexBody(rule)
                if (body != null) {
                    val compiled = runCatching {
                        Regex(body, RegexOption.IGNORE_CASE)
                    }.getOrNull()
                    if (compiled != null) {
                        patterns += compiled
                    } else if (body.isNotEmpty()) {
                        // 正则非法：退化成「按正则体做字面量匹配」，别把这条规则丢掉
                        keywords += body.lowercase()
                    }
                    continue
                }

                keywords += rule.lowercase()
            }

            if (keywords.isEmpty() && patterns.isEmpty()) return NONE
            return DanmakuFilter(keywords.toList(), patterns)
        }

        /** 取出 `/.../` 里的正则体；不是正则写法返回 `null`。 */
        private fun regexBody(rule: String): String? {
            if (rule.length < 3) return null
            if (!rule.startsWith('/') || !rule.endsWith('/')) return null
            return rule.substring(1, rule.length - 1)
        }
    }
}
