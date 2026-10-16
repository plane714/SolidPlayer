package com.solidplayer.core.data.scanner

/** 视频扩展名白名单（文档 §9.2 第 2 步）。 */
object VideoExtensions {

    val SUPPORTED: Set<String> = setOf(
        "mkv", "mp4", "avi", "rmvb", "flv", "wmv", "mov", "ts", "m2ts", "webm", "mpg", "mpeg", "3gp", "m4v",
    )

    fun isVideo(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return false
        return fileName.substring(dot + 1).lowercase() in SUPPORTED
    }
}
