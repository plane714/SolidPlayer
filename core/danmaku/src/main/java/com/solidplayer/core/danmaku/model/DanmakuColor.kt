package com.solidplayer.core.danmaku.model

/**
 * 弹幕颜色 → 可绘制 ARGB 的**唯一换算入口**（纯逻辑，可 JVM 单测）。
 *
 * ### 为什么必须有这个换算（这是一个真实的踩坑，见文档 §17.3）
 * 弹幕数据里的颜色只有 24 位：`PFieldParser` 解析时做的是 `rawColor and 0xFFFFFF`，
 * 高 8 位（alpha 通道）**恒为 0**。而 `Paint.color` 是**32 位整体赋值**（`setColor`），
 * 于是下面这种看起来完全正常的写法：
 * ```kotlin
 * paint.alpha = alphaValue   // 先设透明度
 * paint.color = item.color   // 再设颜色 ← 把上一步的 alpha 一起覆盖成 0
 * ```
 * 会让文字变成**全透明**。它不崩溃、不报错、日志里什么都没有，
 * 只是画面上一条弹幕都看不到。
 *
 * 另一条容易误判的点：`Paint.alpha` 与 `Paint.color` 谁后设谁生效，
 * 两个 setter 改的是同一份底层状态。所以这里不再提供「分两步设」的用法 ——
 * 直接合成一个 ARGB 交出去，顺序问题从根上不存在。
 */
object DanmakuColor {

    /** 24 位 RGB 的掩码：调用方传来的颜色高 8 位可能是脏数据，一律丢掉。 */
    private const val RGB_MASK = 0x00FFFFFF

    /**
     * 把 24 位 RGB 补上不透明的 alpha 位（`0xFF`）。
     *
     * 弹幕默认就是「不透明」，透明度由 [withAlpha] 单独施加。
     */
    fun opaque(rgb: Int): Int = rgb or RGB_MASK.inv()

    /**
     * 合成最终绘制的 ARGB。
     *
     * @param rgb 24 位 RGB（高 8 位会被丢弃）
     * @param alpha 不透明度 0~255，越界会被钳位（**0 表示完全透明**）
     */
    fun withAlpha(rgb: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return (a shl 24) or (rgb and RGB_MASK)
    }
}
