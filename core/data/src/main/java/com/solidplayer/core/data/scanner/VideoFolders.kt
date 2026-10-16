package com.solidplayer.core.data.scanner

/**
 * 由文件绝对路径推导「所在目录」。
 *
 * 单独抽出来是因为两条扫描分支（磁盘遍历 / MediaStore 索引）都要做同一件事，
 * 而**存储根这一层要特殊处理**：直接放在 `/storage/emulated/0` 下的视频，
 * 父目录名是 `0`（emulated 卷号），展示成 `0` 没有任何意义，统一叫「内部存储」。
 */
object VideoFolders {

    const val ROOT_FOLDER_NAME = "内部存储"

    /** @return `folderPath` to `folderName` */
    fun folderOf(filePath: String, rootPaths: Set<String>): Pair<String, String> {
        val parent = filePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isEmpty()) return "" to ROOT_FOLDER_NAME

        if (parent in rootPaths) {
            val volume = parent.substringAfterLast('/')
            // primary 卷展示成「内部存储」；SD 卡用它的卷号（XXXX-XXXX），好歹能区分
            val name = if (volume == "0" || volume == "emulated") ROOT_FOLDER_NAME else volume
            return parent to name
        }

        val name = parent.substringAfterLast('/').ifBlank { ROOT_FOLDER_NAME }
        return parent to name
    }
}
