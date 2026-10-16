package com.solidplayer.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「常用目录」管理（P1 多目录）。
 *
 * P0 只存一个 tree URI；P1 起存一个目录列表，序列化成 JSON 放在同一个 DataStore 里。
 * 旧版本单 URI 的 key 保留为**只读兜底**：只要新 key 还没出现，就把它当成「唯一的一个目录」，
 * 用户升级后不会丢已选目录。
 *
 * 与凭据分开存放：凭据走 [com.solidplayer.core.data.credential.DandanplayCredentialStore]，
 * 二者共用同一个 DataStore 文件但 key 不同，互不影响。
 */
@Singleton
class LibraryFolderStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {

    /** 已配置的目录列表，按加入顺序排列。 */
    val folders: Flow<List<LibraryFolder>> = dataStore.data.map { readList(it) }

    /**
     * 加入一个目录。
     *
     * @return true = 新加入；false = 目录为空或已存在（调用方据此决定要不要重新扫描）
     */
    suspend fun addFolder(treeUri: String): Boolean {
        val uri = treeUri.trim()
        if (uri.isEmpty()) return false

        var added = false
        dataStore.edit { prefs ->
            val current = readList(prefs)
            val next = LibraryFolderCodec.add(
                current,
                LibraryFolder(
                    treeUri = uri,
                    displayName = LibraryFolderCodec.displayNameOf(uri),
                    addedAt = System.currentTimeMillis(),
                ),
            )
            added = next.size != current.size

            prefs[KEY_FOLDERS] = LibraryFolderCodec.encode(next, json)
            // 迁移完成，旧 key 不再有意义
            prefs.remove(KEY_TREE_URI)
        }
        return added
    }

    suspend fun removeFolder(treeUri: String) {
        dataStore.edit { prefs ->
            val next = LibraryFolderCodec.remove(readList(prefs), treeUri)
            prefs[KEY_FOLDERS] = LibraryFolderCodec.encode(next, json)
            prefs.remove(KEY_TREE_URI)
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_FOLDERS)
            prefs.remove(KEY_TREE_URI)
        }
    }

    private fun readList(prefs: Preferences): List<LibraryFolder> {
        // 新 key 存在就以它为准 —— 即使用户把目录全删了（"[]"），也不能让旧 key 把目录「复活」
        if (prefs.contains(KEY_FOLDERS)) {
            return LibraryFolderCodec.decode(prefs[KEY_FOLDERS], json)
        }
        val legacy = prefs[KEY_TREE_URI]?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(LibraryFolder(legacy, LibraryFolderCodec.displayNameOf(legacy), 0L))
    }

    private companion object {
        val KEY_FOLDERS = stringPreferencesKey("library_folders")
        val KEY_TREE_URI = stringPreferencesKey("library_tree_uri")
    }
}
