package com.solidplayer.core.data.preference

/**
 * 字幕样式偏好的取值约束与编解码。
 *
 * **纯逻辑、零 Android 依赖**，可直接 JVM 单测（文档 §14 要求关键算法带单测）。
 *
 * ### 为什么用「sp」而不是「占屏高百分比」
 * 视频字幕由 Media3 的 `SubtitleView` 渲染，它的 `setFractionalTextSize` 把字号定义成
 * **视口高度的一个比例**（默认 0.0533 ≈ 视口高度的 1/18.75）。这套比例在大屏上是灾难：
 * 手机横屏视口高 1080px 时约 21sp 尚可，同一份设置搬到视口高 1600px 的平板上就变成 42sp，
 * 半屏都是字幕。用户能感知的单位是「多大号的字」，不是「屏高的百分之几」，
 * 所以这里统一用 sp（`setFixedTextSize`），换设备换分辨率都不变。
 *
 * ### 为什么钳位放在读取侧
 * DataStore 里的值是「历史遗留 + 用户输入」的混合体：旧版本可能没有这两个 key，
 * 用户也可能写进越界值。钳位放在读取侧，让「写坏了的值」也能被纠正，
 * 而不是让 `SubtitleView` 拿着 `textSizeSp = 999` 画出一整屏的字。
 */
/**
 * 默认字幕语言（跟人走的全局偏好）。
 *
 * 这里的「语言」是给**字幕轨道自动选择**用的口径：打开视频时，先在视频内嵌的字幕轨道里
 * 找一条语言标记匹配的轨道自动选中；一条都对不上时，退化为「视频自带的第一条」或关闭。
 *
 * ### 为什么用一组预置标签而不是自由输入
 * - Media3 的内嵌轨道语言来自容器元数据（ISO 639-2/B 三字母码居多，如 `chi`/`zho`/`eng`），
 *   文件名旁挂标记却常见 `sc`/`chs`/`zh-CN`/`简中`。两者形态完全不一致，需要一张**别名表**
 *   统一归一，否则「默认简体中文」会形同虚设。预置一组有代表性的语言正好把这张表固定下来。
 * - 用户真正关心的是「我要简体字、不要繁体」这一档，不需要细到每一种方言。
 *
 * [tag] 是落库/比较用的稳定标识，[aliases] 是这条语言在文件名与容器元数据里可能出现的样子（全小写）。
 */
enum class SubtitleLanguage(
    val tag: String,
    val displayName: String,
    val aliases: Set<String>,
) {
    /** 默认项：简体中文 */
    SIMPLIFIED_CHINESE(
        tag = "sc",
        displayName = "简体中文",
        aliases = setOf(
            "sc", "chs", "zh-sc", "zh_cn", "zh-cn", "zhcn", "zhhans", "hans",
            "chi", "zho", "zh", "chinese", "中文", "简体", "简中", "简", "简体中文",
        ),
    ),
    TRADITIONAL_CHINESE(
        tag = "tc",
        displayName = "繁体中文",
        aliases = setOf(
            "tc", "cht", "zh-tc", "zh_tw", "zh-tw", "zhtw", "zhhk", "zh-hk", "zhhant", "hant",
            "繁体", "繁中", "繁", "繁体中文", "繁體中文", "臺灣", "台湾",
        ),
    ),
    JAPANESE(
        tag = "ja",
        displayName = "日语",
        aliases = setOf("ja", "jp", "jpn", "japanese", "日本語", "日语"),
    ),
    ENGLISH(
        tag = "en",
        displayName = "英语",
        aliases = setOf("en", "eng", "english", "英语", "英文"),
    ),
    KOREAN(
        tag = "ko",
        displayName = "韩语",
        aliases = setOf("ko", "kor", "korean", "한국어", "韩语"),
    ),
    ;

    /**
     * 判断一个「语言标记」是否属于本条语言。
     *
     * 归一规则：全小写、去首尾空白。命中 [aliases] 即算匹配；
     * 额外做一次「前缀归一」兜底，覆盖 `zh-CN.ass` 里带地区后缀、`eng (SDH)` 里带括注这类情况。
     */
    fun matches(raw: String?): Boolean {
        val token = raw?.trim()?.lowercase() ?: return false
        if (token.isEmpty()) return false
        if (token in aliases) return true
        // 去掉地区/描述后缀（-cn / _tw / (sdh) / .default 等）再比一次
        val head = token.substringBefore('-')
            .substringBefore('_')
            .substringBefore('(')
            .substringBefore('.')
            .trim()
        return head.isNotEmpty() && head in aliases
    }

    companion object {
        /** 默认语言：简体中文（产品要求默认匹配 SC）。 */
        val DEFAULT: SubtitleLanguage = SIMPLIFIED_CHINESE

        /** 按落库的 tag 还原；未知 tag 回落到默认值（读侧兜底，老数据 / 脏数据都安全）。 */
        fun fromTag(tag: String?): SubtitleLanguage =
            entries.firstOrNull { it.tag.equals(tag?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

object SubtitlePreferenceCodec {

    /**
     * 默认字幕字号（sp）。
     *
     * 取 22sp 而不是 Media3 的比例默认值：后者在手机上约等于 21sp（差不多），
     * 但在平板上会翻倍。22sp 是「手机横屏一句中文两行以内、不挡画面」的经验值，
     * 一屏字幕通常在 1~2 行，落在这个字号上观感最稳。
     */
    const val DEFAULT_TEXT_SIZE_SP = 22

    /** 12sp 再小就只剩糊字；40sp 已经一句占掉半屏。 */
    const val MIN_TEXT_SIZE_SP = 12
    const val MAX_TEXT_SIZE_SP = 40

    /**
     * 默认开启细描边。
     *
     * 与弹幕同一条理由（文档 §17.4）：白字落在亮背景（雪景、白墙）上时，
     * 光靠填充会糊成一片，一圈淡黑边是可读性的兜底。
     * 但**绝不要黑底方块** —— 那是 Media3 内置字幕层的默认观感，
     * 一整块黑色底板压在画面上，比看不清更难受。
     */
    const val DEFAULT_OUTLINE_ENABLED = true

    /** 字号：越界钳到约定区间。 */
    fun normalizeTextSizeSp(sp: Int): Int = sp.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)

    /** 界面上展示的取值文案。 */
    fun describeTextSize(sp: Int): String = "${normalizeTextSizeSp(sp)}sp"
}

/**
 * 字幕样式偏好（全局，落 DataStore）。
 *
 * 与弹幕样式偏好同源（文档 §7.2）：它们是「跟人走」而不是「跟文件走」的，
 * 所以进 DataStore 而不是 DB —— 也正因为如此，**不需要任何 DB Migration**。
 */
data class SubtitlePreferences(
    /** 字号（sp） */
    val textSizeSp: Int = SubtitlePreferenceCodec.DEFAULT_TEXT_SIZE_SP,
    /** 是否画细描边；false = 纯填充（白字无任何边） */
    val outlineEnabled: Boolean = SubtitlePreferenceCodec.DEFAULT_OUTLINE_ENABLED,
    /**
     * 默认字幕语言：打开视频时在**内嵌字幕轨道**里优先自动选中的语言。
     *
     * 默认简体中文（产品要求）。匹配不到时播放页会退化为「视频自带的第一条字幕」，
     * 具体选择逻辑见 `SolidPlayer.applyDefaultSubtitleLanguage`。
     */
    val defaultLanguage: SubtitleLanguage = SubtitleLanguage.DEFAULT,
)
