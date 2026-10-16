package com.solidplayer.core.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/**
 * 一个「常用目录」（P1 多目录管理）。
 *
 * @param treeUri  `ACTION_OPEN_DOCUMENT_TREE` 返回的 tree URI，已 takePersistableUriPermission
 * @param displayName 展示用名，取 tree URI 解码后的末段目录名
 * @param addedAt 加入时间，用于稳定排序（先加的排前面）
 */
@Serializable
data class LibraryFolder(
    val treeUri: String,
    val displayName: String = "",
    val addedAt: Long = 0L,
)

/**
 * [LibraryFolder] 列表与 JSON 之间的编解码。
 *
 * 抽成独立对象是为了让 DataStore 的持久化格式能被 **纯 JVM 单测** 覆盖 ——
 * DataStore 里存的是字符串，读失败就等于静默丢用户已配置的全部目录，
 * 所以这段逻辑必须可回归。此处**不引用任何 Android API**，单测可直接跑。
 */
object LibraryFolderCodec {

    fun encode(folders: List<LibraryFolder>, json: Json): String = json.encodeToString(folders)

    /** 解码失败一律返回空列表，绝不让脏数据把设置页整体拖崩。 */
    fun decode(raw: String?, json: Json): List<LibraryFolder> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<LibraryFolder>>(raw) }
            .getOrDefault(emptyList())
    }

    /** 同一个 treeUri 只保留一条；新加入的排在末尾。返回是否真的加入了。 */
    fun add(folders: List<LibraryFolder>, folder: LibraryFolder): List<LibraryFolder> =
        if (folders.any { it.treeUri == folder.treeUri }) folders else folders + folder

    fun remove(folders: List<LibraryFolder>, treeUri: String): List<LibraryFolder> =
        folders.filterNot { it.treeUri == treeUri }

    /**
     * 从 tree URI 猜一个可读的目录名：`.../tree/primary%3AMovies` → `Movies`。
     *
     * 标准库的 `Uri.decode` 在 JVM 单测里是抛异常的 stub，所以这里自带一个最小百分号解码。
     * 注意必须**按字节累积再整体按 UTF-8 解码**：`%E7%95%AA` 是「番」的三个 UTF-8 字节，
     * 逐字节当成 char 拼出来会是乱码。
     */
    fun displayNameOf(treeUri: String): String {
        val decoded = percentDecode(treeUri)
        val tail = decoded.substringAfterLast("tree/", decoded)
        val last = tail.trimEnd('/').substringAfterLast('/')
        val name = if (last.contains(':')) last.substringAfterLast(':') else last
        return name.ifBlank { tail.ifBlank { decoded } }
    }

    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        var runStart = 0
        while (i < value.length) {
            if (value[i] == '%' && i + 3 <= value.length) {
                val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    if (runStart < i) {
                        out.write(value.substring(runStart, i).toByteArray(Charsets.UTF_8))
                    }
                    out.write(code)
                    i += 3
                    runStart = i
                    continue
                }
            }
            i++
        }
        if (runStart < value.length) {
            out.write(value.substring(runStart).toByteArray(Charsets.UTF_8))
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
