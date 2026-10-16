package com.solidplayer.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 首页文件夹的偏好存储：**图钉**（`pinned_folders`）与**显式顺序**（`folder_order`）。
 *
 * 与凭据 / 弹幕偏好 / 常用目录**共用同一个 DataStore 文件**，只是 key 不同。
 * 放 DataStore 而不是 Room 的理由见 [PinnedFolderCodec]：文件夹是聚合出来的投影，
 * 不是实体，为它加表或加列都要一次 Migration，而它本质上只是「排序与标记偏好」。
 *
 * 两个 key 的分工是硬约定，别混：
 * - [pin] / [unpin]（只在长按菜单里调）改图钉；[pin] 顺带把它提到顺序最前（「置顶」的字面意思）。
 * - [reorder]（拖拽）只改顺序，**一个图钉都不碰**。
 */
@Singleton
class PinnedFolderStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {

    /** 打了图钉的目录路径。顺序曾经是优先级，现在没有含义（位置由 [order] 决定）。 */
    val pinned: Flow<List<String>> = dataStore.data
        .map { PinnedFolderCodec.decode(it[KEY_PINNED], json) }
        .distinctUntilChanged()

    /**
     * 首页的显式顺序（谁排第几）。
     *
     * 空列表是**正常状态**（从没拖过任何一行），此时展示侧的 [PinnedFolderCodec.effectiveOrder]
     * 会退化用图钉列表顶上，老用户升级后看到的顺序与旧版一致。
     */
    val order: Flow<List<String>> = dataStore.data
        .map { PinnedFolderCodec.decodeOrder(it[KEY_ORDER], json) }
        .distinctUntilChanged()

    /**
     * 置顶：打上图钉，并把它提到顺序最前。
     *
     * 两个 key 必须在**同一次** `edit` 里改 —— 分两次写会出现「图钉已经亮了但还没排到最前」
     * 的中间态被 Flow 送出去，首页闪一下。
     */
    suspend fun pin(folderPath: String) {
        val key = folderPath.trim()
        if (key.isEmpty()) return
        dataStore.edit { prefs ->
            val flags = PinnedFolderCodec.decode(prefs[KEY_PINNED], json)
            val order = currentlyEffectiveOrder(prefs, flags)
            prefs[KEY_PINNED] = PinnedFolderCodec.encode(PinnedFolderCodec.pin(flags, key), json)
            prefs[KEY_ORDER] = PinnedFolderCodec.encodeOrder(
                listOf(key) + order.filterNot { it == key },
                json,
            )
        }
    }

    /**
     * 取消置顶：摘掉图钉，并把它从显式顺序里拿出来（退回目录名序）。
     *
     * 为什么要连顺序一起回退：位置只由「拖拽」和「置顶」两种动作决定，而这两者都是**显式**的
     * 排序动作，取消置顶是「置顶」的逆操作。只摘图钉不动位置的话，会出现「图钉没了、
     * 它还赖在最前面」—— 用户会以为菜单没生效。
     */
    suspend fun unpin(folderPath: String) {
        dataStore.edit { prefs ->
            val flags = PinnedFolderCodec.decode(prefs[KEY_PINNED], json)
            val order = currentlyEffectiveOrder(prefs, flags)
            prefs[KEY_PINNED] = PinnedFolderCodec.encode(PinnedFolderCodec.unpin(flags, folderPath), json)
            prefs[KEY_ORDER] = PinnedFolderCodec.encodeOrder(
                order.filterNot { it == folderPath },
                json,
            )
        }
    }

    /**
     * 长按拖拽排序：把 [from] 位置的那一行移到 [to]。
     *
     * [visible] 必须是**松手那一刻界面上那份顺序**（被拖项已经落在 [to] 上）。界面上的顺序
     * 就是「用户看到的」，把它当作落盘输入，才能保证「所见即所存」—— 早先版本交出的是换位
     * 之后的列表、却按拖之前的下标去取被拖项，结果只要真的移过位就会挪错人，顺序被写坏。
     *
     * 只写 [KEY_ORDER]：图钉与顺序彻底解耦，拖拽不改变任何一项的置顶状态。
     */
    suspend fun reorder(visible: List<String>, from: Int, to: Int) {
        dataStore.edit { prefs ->
            val flags = PinnedFolderCodec.decode(prefs[KEY_PINNED], json)
            val order = currentlyEffectiveOrder(prefs, flags)
            prefs[KEY_ORDER] = PinnedFolderCodec.encodeOrder(
                PinnedFolderCodec.moveOrder(visible, order, from, to),
                json,
            )
        }
    }

    /**
     * 按当前状态取反。**由调用方传入「现在是不是已经置顶」**，而不是在这里读一遍 Flow 再判断 ——
     * 读 Flow 要 `first()`，中间隔着一次磁盘往返，连点两下长按菜单可能两次都读到旧值。
     */
    suspend fun toggle(folderPath: String, currentlyPinned: Boolean) =
        if (currentlyPinned) unpin(folderPath) else pin(folderPath)

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_PINNED)
            prefs.remove(KEY_ORDER)
        }
    }

    /** 摊平当前生效的顺序：盘上那份为准，没有（从没拖过）就用图钉列表顶上，见 [PinnedFolderCodec.effectiveOrder]。 */
    private fun currentlyEffectiveOrder(prefs: Preferences, flags: List<String>): List<String> =
        PinnedFolderCodec.effectiveOrder(PinnedFolderCodec.decodeOrder(prefs[KEY_ORDER], json), flags)

    private companion object {
        val KEY_PINNED = stringPreferencesKey("pinned_folders")
        val KEY_ORDER = stringPreferencesKey("folder_order")
    }
}
