package com.solidplayer.core.danmaku.model

/**
 * 单条弹幕。
 *
 * [textWidth] 是懒测量后的缓存：`Paint.measureText()` 有开销，测量一次后写入本字段，
 * 渲染循环内不再调用（文档 §8.5 铁律 2）。`0f` 表示尚未测量。
 */
data class DanmakuItem(
    val timeMs: Long,
    val mode: DanmakuMode,
    /**
     * 弹幕自带颜色，**只有 24 位**（`0xRRGGBB`，高 8 位无意义）。
     *
     * 直接丢给 `Paint.color` 会因 alpha 为 0 而**完全看不见**，
     * 绘制前必须经 [DanmakuColor.withAlpha] 补上不透明位（文档 §17.3）。
     */
    val color: Int,
    val text: String,
    var textWidth: Float = 0f,
)
