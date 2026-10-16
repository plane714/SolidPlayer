package com.solidplayer.core.data.scanner

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 枚举设备上的**共享存储根**（内部存储 + 可能插着的 SD 卡）。
 *
 * 为什么不用 `File("/sdcard")` 这类硬编码：多卷设备上 SD 卡的挂载点是
 * `/storage/XXXX-XXXX`，只有系统知道。`getExternalFilesDirs()` 会为每个卷各返回一个
 * 应用私有目录（`/storage/XXXX-XXXX/Android/data/<pkg>/files`），
 * 把 `/Android/data/` 之后的部分砍掉就是卷根，这是唯一不依赖厂商约定的取法。
 */
class StorageRoots(private val context: Context) {

    fun roots(): List<File> {
        val out = LinkedHashSet<String>()

        runCatching { Environment.getExternalStorageDirectory()?.absolutePath }
            .getOrNull()
            ?.let { out += it }

        runCatching { context.getExternalFilesDirs(null) }
            .getOrNull()
            ?.forEach { dir ->
                val path = dir?.absolutePath ?: return@forEach
                val marker = path.indexOf(MARKER)
                out += if (marker > 0) path.substring(0, marker) else path
            }

        return out.map(::File).filter { it.isDirectory && it.canRead() }
    }

    private companion object {
        const val MARKER = "/Android/data/"
    }
}
