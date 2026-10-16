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
)
