package com.solidplayer.core.data.repository

/**
 * 服务端建议的弹幕偏移换算（P2）。
 *
 * `MatchResultV2.shift` 是**秒**（可为小数、可为负），含义是「官方认为这个文件的弹幕
 * 整体该提前或延后多少」。它和用户手动微调的偏移共用 `play_history.danmaku_offset_ms` 一列，
 * 因此换算过程必须**防御性**：
 *
 * - `NaN` / 无穷 → 直接当作「没有建议」（返回 0），不要让它污染 DB
 * - 超出 ±60 秒 → 钳位。正常的片头偏差在十秒量级，超过一分钟基本是服务端脏数据，
 *   真按它偏移会让弹幕整体跑到片尾
 *
 * 纯函数，可 JVM 单测。
 */
internal object MatchShift {

    /** 允许的最大绝对偏移（秒），超过即钳位。 */
    const val MAX_ABS_SECONDS = 60.0

    /** 秒 → 毫秒。非法值返回 `0L`（表示不应用）。 */
    fun toOffsetMs(shiftSeconds: Double): Long {
        if (shiftSeconds.isNaN() || shiftSeconds.isInfinite()) return 0L
        val clamped = shiftSeconds.coerceIn(-MAX_ABS_SECONDS, MAX_ABS_SECONDS)
        return Math.round(clamped * 1000.0)
    }
}
