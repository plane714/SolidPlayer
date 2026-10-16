package com.solidplayer.core.danmaku.model

/** 弹幕模式。注意：数值映射与 B 站不同，见 [com.solidplayer.core.danmaku.parser.PFieldParser]。 */
enum class DanmakuMode {
    /** 普通滚动（`p` 字段模式值 1） */
    SCROLL,

    /** 顶部固定（模式值 5） */
    TOP,

    /** 底部固定（模式值 4） */
    BOTTOM,
}
