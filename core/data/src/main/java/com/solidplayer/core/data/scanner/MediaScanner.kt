package com.solidplayer.core.data.scanner

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单目录扫描（文档 §9.2）。
 *
 * P0 走 SAF（`ACTION_OPEN_DOCUMENT_TREE`）而不是 MediaStore：
 * - 用户明确指定「哪个目录是番剧目录」，扫描范围可控、不需要读存储权限
 * - MediaStore 在 P1 做「自动发现」时再引入
 *
 * 全链路用 `DocumentsContract` 直接递归查询。不用 `DocumentFile` 是因为它每个节点都要单独
 * 发一次 IPC，几百个文件时明显更慢。
 */
class MediaScanner(private val resolver: ContentResolver) {

    /**
     * 递归扫描一个 SAF 目录树。
     *
     * @param treeUri `ACTION_OPEN_DOCUMENT_TREE` 返回的树 URI
     */
    suspend fun scanTree(treeUri: Uri): List<MediaFileEntry> = withContext(Dispatchers.IO) {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
        val result = ArrayList<MediaFileEntry>(64)
        walk(treeUri, rootUri, "", result, 0)
        result.sortBy { it.relativePath + "/" + it.displayName }
        result
    }

    private fun walk(
        treeUri: Uri,
        directoryUri: Uri,
        path: String,
        out: MutableList<MediaFileEntry>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return

        val documentId = runCatching { DocumentsContract.getDocumentId(directoryUri) }.getOrNull() ?: return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)

        val cursor: Cursor = runCatching { resolver.query(childrenUri, PROJECTION, null, null, null) }
            .getOrNull() ?: return

        cursor.use {
            while (it.moveToNext()) {
                val childDocumentId = it.getString(COLUMN_INDEX_ID) ?: continue
                val displayName = it.getString(COLUMN_INDEX_NAME) ?: continue
                val mimeType = it.getString(COLUMN_INDEX_MIME)
                val size = if (it.isNull(COLUMN_INDEX_SIZE)) 0L else it.getLong(COLUMN_INDEX_SIZE)
                val lastModified = if (it.isNull(COLUMN_INDEX_MODIFIED)) {
                    0L
                } else {
                    it.getLong(COLUMN_INDEX_MODIFIED)
                }

                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                if (isDirectory) {
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId)
                    walk(treeUri, childUri, "$path/$displayName", out, depth + 1)
                } else if (VideoExtensions.isVideo(displayName)) {
                    out += MediaFileEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId),
                        documentId = childDocumentId,
                        displayName = displayName,
                        relativePath = path,
                        sizeBytes = size,
                        lastModified = lastModified,
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 8
        const val COLUMN_INDEX_ID = 0
        const val COLUMN_INDEX_NAME = 1
        const val COLUMN_INDEX_MIME = 2
        const val COLUMN_INDEX_SIZE = 3
        const val COLUMN_INDEX_MODIFIED = 4

        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
