package com.solidplayer.core.data.parser

/**
 * 文件名解析规则引擎（文档 §6）。哈希匹配失败时的兜底手段，同时也是手动搜索的输入源。
 *
 * 设计成**有序规则表**，按优先级依次尝试，先命中先返回。纯 JVM 实现，可完整回归。
 *
 * 预处理三步：
 * ```
 * [Sakura][BDRip][1080p][x264_FLAC] 进击的巨人 - 07v2 [GB].mkv
 *   ↓ 1. 去掉扩展名
 * [Sakura][BDRip][1080p][x264_FLAC] 进击的巨人 - 07v2 [GB]
 *   ↓ 2. 提取集数（规则表）
 *   ↓ 3. 移除方括号内容 + 噪声词 → title = "进击的巨人"
 * ```
 */
object FileNameParser {

    /** 集数上限：超过这个值大概率是年份或分辨率，不是集数（文档 §6.2） */
    const val MAX_EPISODE = 999

    // ── 规则 1：季集标记  Title S02E07.mkv ─────────────────────────
    private val RULE_SEASON_EPISODE = Regex("[Ss](\\d{1,2})[Ee](\\d{1,3})")

    // ── 规则 2：中文话数  [组] 标题 第07话 [1080p] ─────────────────
    private val RULE_CN_EPISODE = Regex("第\\s*(\\d{1,3})\\s*[话話集]")

    // ── 规则 3：方括号集数  [组][标题][07][BDRip] ──────────────────
    // 要求数字外面还包着一对方括号，避免误伤 [1080p] 之类
    private val RULE_BRACKET_EPISODE = Regex("\\]\\[\\s*(\\d{1,3})(?:v\\d)?\\s*\\]\\[")

    // ── 规则 3.5：花絮 / 特别篇 / OVA 前缀  C1 / S1 / O1 ───────────
    private val RULE_PREFIX_EPISODE = Regex("(?:^|[\\s\\-_\\[\\]])([CSO])(\\d{1,3})(?:v\\d)?(?=$|[\\s\\-_\\[\\]\\.])")

    // ── 规则 4：分隔符集数  [组] 标题 - 07 [x264].mp4 ─────────────
    private val RULE_DELIM_EPISODE = Regex("(?:^|[\\s\\-_]+)(\\d{1,3})(?:v\\d)?[\\s\\.]")

    // ── 规则 5：结尾纯数字  标题 07.mp4 ───────────────────────────
    // 在「已去扩展名」的串上匹配，前缀用 [^\d] 挡住「2024」这种年份 ——
    // 否则正则会退化成只吃掉最后三位，把年份 2024 解析成第 24 集
    private val RULE_TAIL_NUMBER = Regex("(?:^|[^\\d])(\\d{1,3})(?:v\\d)?$")

    private val EXTENSION_REGEX = Regex("\\.[A-Za-z0-9]{1,5}$")
    private val BRACKET_REGEX = Regex("\\[[^\\]]*\\]")
    private val PAREN_REGEX = Regex("\\([^)]*\\)")
    private val SEPARATOR_REGEX = Regex("[\\s_\\-·]+")

    /** 规则命中后用于从标题里摘掉的那段文字的兜底清理 */
    private val DANGLING_SEPARATOR = Regex("(?:^[\\s\\-_.]+)|(?:[\\s\\-_.]+$)")

    /**
     * 需要剥离的噪声词表（文档 §6.3），大小写不敏感。
     * 只在词边界处匹配，避免把标题里的子串误删。
     */
    private val NOISE_WORDS: List<String> = listOf(
        // 画质
        "1080p", "720p", "2160p", "4K", "8K", "FHD", "HD", "SD",
        // 编码
        "x264", "x265", "H264", "H265", "HEVC", "AVC", "10bit", "8bit",
        // 片源
        "BDRip", "BDRemux", "WEB-DL", "WEBRip", "HDTV", "DVDRip", "BluRay",
        // 音频
        "AAC", "FLAC", "DTS", "AC3", "EAC3", "TrueHD", "Ma10p",
        // 字幕
        "GB", "BIG5", "CHS", "CHT", "JPSC", "JPTC", "简繁", "简体", "繁体",
        // 容器
        "MKV", "MP4", "AVI", "RMVB",
    )

    private val NOISE_REGEX: Regex = run {
        val alternation = NOISE_WORDS
            .sortedByDescending { it.length }        // 长词优先，避免 "HDTV" 被 "HD" 抢先切碎
            .joinToString("|") { Regex.escape(it) }
        Regex("(?i)(?<![A-Za-z0-9])(?:$alternation)(?![A-Za-z0-9])")
    }

    /**
     * 解析文件名。
     *
     * @param rawFileName 文件名，含或不含扩展名都可以
     */
    fun parse(rawFileName: String): ParsedFileName {
        val raw = rawFileName.trim()
        if (raw.isEmpty()) return ParsedFileName(raw, "", null, null, 0f)

        val base = stripExtension(raw)

        // 规则 1：季集标记
        RULE_SEASON_EPISODE.find(base)?.let { m ->
            val season = m.groupValues[1].toIntOrNull()
            val episode = m.groupValues[2].toIntOrNull()
            if (episode != null && episode <= MAX_EPISODE) {
                return build(raw, base, season, episode.toString(), m.value, CONF_SEASON_EPISODE)
            }
        }

        // 规则 2：中文话数
        RULE_CN_EPISODE.find(base)?.let { m ->
            val episode = m.groupValues[1].toIntOrNull()
            if (episode != null && episode <= MAX_EPISODE) {
                return build(raw, base, null, episode.toString(), m.value, CONF_CN_EPISODE)
            }
        }

        // 规则 3：方括号集数
        RULE_BRACKET_EPISODE.find(base)?.let { m ->
            val episode = m.groupValues[1].toIntOrNull()
            if (episode != null && episode <= MAX_EPISODE) {
                // 摘掉的是内层方括号本体 `[07]`，不是整段 `][07][`
                val token = "[${m.groupValues[1]}]"
                return build(raw, base, null, episode.toString(), token, CONF_BRACKET_EPISODE)
            }
        }

        // 规则 3.5：花絮 / 特别篇 / OVA 前缀，保留前缀作为集数原始值
        RULE_PREFIX_EPISODE.find(base)?.let { m ->
            val prefix = m.groupValues[1]
            val number = m.groupValues[2].toIntOrNull()
            if (number != null && number <= MAX_EPISODE) {
                val value = prefix.uppercase() + number
                return build(raw, base, null, value, m.value, CONF_PREFIX_EPISODE)
            }
        }

        // 规则 4：分隔符集数
        RULE_DELIM_EPISODE.find(base)?.let { m ->
            val episode = m.groupValues[1].toIntOrNull()
            if (episode != null && episode <= MAX_EPISODE) {
                return build(raw, base, null, episode.toString(), m.value, CONF_DELIM_EPISODE)
            }
        }

        // 规则 5：结尾纯数字
        RULE_TAIL_NUMBER.find(base)?.let { m ->
            val episode = m.groupValues[1].toIntOrNull()
            if (episode != null && episode <= MAX_EPISODE) {
                return build(raw, base, null, episode.toString(), m.value, CONF_TAIL_NUMBER)
            }
        }

        // 规则 6：无集数（剧场版 / OVA）
        return ParsedFileName(
            raw = raw,
            title = cleanTitle(base),
            season = null,
            episode = null,
            confidence = 0f,
        )
    }

    private fun build(
        raw: String,
        base: String,
        season: Int?,
        episode: String,
        token: String,
        confidence: Float,
    ): ParsedFileName = ParsedFileName(
        raw = raw,
        title = cleanTitle(base, token),
        season = season,
        episode = episode,
        confidence = confidence,
    )

    private fun stripExtension(name: String): String {
        val m = EXTENSION_REGEX.find(name) ?: return name
        return name.substring(0, m.range.first)
    }

    /**
     * 清洗出用于搜索的标题：先摘掉已识别的集数片段，再依次去掉方括号内容、圆括号内容与噪声词。
     */
    private fun cleanTitle(base: String, episodeToken: String? = null): String {
        var text = base
        if (episodeToken != null) {
            val idx = text.indexOf(episodeToken)
            if (idx >= 0) {
                text = text.removeRange(idx, idx + episodeToken.length)
            }
        }
        text = BRACKET_REGEX.replace(text, " ")
        text = PAREN_REGEX.replace(text, " ")
        text = NOISE_REGEX.replace(text, " ")
        text = SEPARATOR_REGEX.replace(text, " ")
        text = DANGLING_SEPARATOR.replace(text, "")
        return text.trim()
    }

    // 规则命中置信度（文档 §6.2 的优先级顺序即置信度递减顺序）
    private const val CONF_SEASON_EPISODE = 0.95f
    private const val CONF_CN_EPISODE = 0.90f
    private const val CONF_BRACKET_EPISODE = 0.80f
    private const val CONF_PREFIX_EPISODE = 0.65f
    private const val CONF_DELIM_EPISODE = 0.60f
    private const val CONF_TAIL_NUMBER = 0.45f
}
