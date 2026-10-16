package com.solidplayer.core.danmaku.model

/**
 * 弹幕渲染配置。P0 只暴露渲染必需项；透明度/字号/速度的外部 UI 调参放在 P2（文档 §13）。
 */
data class DanmakuConfig(
    /** 基准字号（sp） */
    val textSizeSp: Float = 16f,
    /** 字号缩放，最终字号 = [textSizeSp] × [textScale] */
    val textScale: Float = 1f,
    /** 整体不透明度 [0f, 1f] */
    val alpha: Float = 1f,
    /**
     * 描边宽度（dp）。**默认 1.2f —— 淡淡的黑边**。
     *
     * 文字用弹幕自带色填充（没带颜色时是纯白，见 `PFieldParser.DEFAULT_COLOR`），
     * 外面再套一层半透明黑描边：白色弹幕落在亮背景（雪景、白墙、字幕）上时，
     * 光靠填充会糊成一片，这层描边是可读性的兜底。
     *
     * `0f` 表示「实心」（只画填充，不画描边）—— 面板上的滑杆可以拖到这个值。
     * 渲染器只在 `strokeWidthPx > 0` 时才画那一层（文档 §8.5 第 6 步）。
     */
    val strokeWidthDp: Float = 1.2f,
    /** 顶部/底部弹幕固定展示时长 */
    val fixedDurationMs: Long = 4_000L,
    /** 同一轨道相邻两条滚动弹幕的最小间隔，用于控制视觉密度 */
    val minGapMs: Long = 0L,
    /** 滚动弹幕横穿屏幕所需秒数，速度 v = 屏幕宽度 / 该值 */
    val scrollDurationSeconds: Float = 8f,
    /** 弹幕总开关 */
    val enabled: Boolean = true,
    /**
     * 弹幕显示区域占画面高度的比例（**从顶部起算**），`1f` = 整屏。
     *
     * ### 为什么需要它
     * 原来的弹幕铺满整屏 —— 轨道数等于「画面高度 / 行高」，热番一集能铺十几二十条，
     * 画面主体（人脸、字幕）全被盖住。缩小区域后轨道数变少，轨道用尽时超出的弹幕
     * 被直接丢弃（`TrackAllocator` 返回 `null` 的约定），**同屏条数随之下降**，
     * 下半屏彻底让给视频。
     *
     * ### 三种弹幕共用同一块区域
     * 滚动 / 顶部 / 底部弹幕都排在这块区域里：底部弹幕贴的是**区域**的下边缘，
     * 而不是画布下边缘 —— 否则「区域」对底部弹幕就没有任何约束力了。
     */
    val displayAreaRatio: Float = 1f,
) {
    /** 单行弹幕占用的高度（含行间距） */
    fun trackHeightPx(density: Float): Float =
        textSizeSp * textScale * density * TRACK_HEIGHT_RATIO

    /**
     * 弹幕显示区域高度（px）。
     *
     * 比例下限钳在 [MIN_DISPLAY_AREA_RATIO]：0 会让轨道数归零（`TrackAllocator` 兜底成 1 条），
     * 负值则会把轨道排到画面之外 —— 两种情况都是「弹幕凭空消失」，不如留一条可见的轨道。
     */
    fun displayAreaHeightPx(canvasHeightPx: Int): Float =
        canvasHeightPx * displayAreaRatio.coerceIn(MIN_DISPLAY_AREA_RATIO, 1f)

    private companion object {
        /** 行高相对字号的比例，留出约 45% 行间距 */
        const val TRACK_HEIGHT_RATIO = 1.45f

        /** 显示区域下限：约等于「至少留一行弹幕」。UI 的最小档位是 25%，这里是脏数据兜底。 */
        const val MIN_DISPLAY_AREA_RATIO = 0.05f
    }
}
