package com.solidplayer.core.data.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页文件夹偏好（图钉 `pinned_folders` / 顺序 `folder_order`）的持久化格式单测。
 *
 * 与 `LibraryFolderCodecTest` 同一个理由：DataStore 里存的是两串 JSON，编解码写错就等于
 * 静默丢掉用户全部的图钉与顺序；而**顺序本身是数据**（谁在第几位），所以顺序相关的行为
 * 必须有回归保护，不能只测「能存能读」。
 */
class PinnedFolderCodecTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(raw: String?) = PinnedFolderCodec.decode(raw, json)

    private fun decodeOrder(raw: String?) = PinnedFolderCodec.decodeOrder(raw, json)

    /**
     * 把「显式顺序」渲染成首页看到的那份列表（不在顺序里的按目录名序排在最后）。
     *
     * 这就是 `FolderListViewModel.reorderEntries` 的口径，放在这里是为了守住整条链路唯一的
     * 正确性依据：**落盘结果渲染出来 == 用户松手时看到的那份**。
     */
    private fun render(all: List<String>, order: List<String>): List<String> =
        all.sortedBy { order.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }

    // ── 编解码 ────────────────────────────────────────────────

    @Test
    fun `编码后解码可完整还原且顺序不变`() {
        val paths = listOf("/storage/emulated/0/番剧/A", "/storage/emulated/0/Movies", "/x/B")

        assertEquals(paths, decode(PinnedFolderCodec.encode(paths, json)))
        assertEquals(paths, decodeOrder(PinnedFolderCodec.encodeOrder(paths, json)))
    }

    @Test
    fun `空串与 null 都解码为空列表`() {
        assertTrue(decode(null).isEmpty())
        assertTrue(decode("").isEmpty())
        assertTrue(decode("   ").isEmpty())
        assertTrue(decodeOrder(null).isEmpty())
        assertTrue(decodeOrder("").isEmpty())
    }

    @Test
    fun `脏数据不抛异常而是退化为空列表`() {
        // 用户升级 / 手工改过 preferences 文件时可能撞上，绝不能让它把首页拖崩
        assertTrue(decode("{not json at all").isEmpty())
        assertTrue(decode("""{"path":"/a"}""").isEmpty())
        assertTrue(decode("""[1,2,3]""").isEmpty())
        assertTrue(decodeOrder("""{"order":["/a"]}""").isEmpty())
    }

    @Test
    fun `空列表编码后仍能解码回空列表`() {
        assertTrue(decode(PinnedFolderCodec.encode(emptyList(), json)).isEmpty())
        assertTrue(decodeOrder(PinnedFolderCodec.encodeOrder(emptyList(), json)).isEmpty())
    }

    @Test
    fun `解码顺手去掉重复与空白项`() {
        val raw = """["/a", "/a", "", "   ", "/b"]"""
        assertEquals(listOf("/a", "/b"), decode(raw))
        assertEquals(listOf("/a", "/b"), decodeOrder(raw))
    }

    @Test
    fun `顺序比图钉宽得多，不会被图钉的上限截断`() {
        val paths = (0 until PinnedFolderCodec.MAX_PINNED + 20).map { "/dir/$it" }

        assertEquals(paths.size, decodeOrder(PinnedFolderCodec.encodeOrder(paths, json)).size)
        // 图钉那一份还是老上限
        assertEquals(PinnedFolderCodec.MAX_PINNED, decode(PinnedFolderCodec.encode(paths, json)).size)
    }

    // ── 图钉 ──────────────────────────────────────────────────

    @Test
    fun `pin 把新目录放在最前`() {
        val pinned = PinnedFolderCodec.pin(emptyList(), "/a")
            .let { PinnedFolderCodec.pin(it, "/b") }

        // 后置顶的优先级更高 —— 首页第一行应该就是刚长按过的那一个
        assertEquals(listOf("/b", "/a"), pinned)
    }

    @Test
    fun `pin 一个已置顶的目录只会把它提前而不是留下两条`() {
        val pinned = listOf("/a", "/b", "/c")

        val moved = PinnedFolderCodec.pin(pinned, "/c")

        assertEquals(listOf("/c", "/a", "/b"), moved)
        assertEquals(moved.size, moved.distinct().size)
    }

    @Test
    fun `pin 空路径是空操作`() {
        val pinned = listOf("/a")
        assertEquals(pinned, PinnedFolderCodec.pin(pinned, "   "))
    }

    @Test
    fun `pin 有上限，不会让偏好文件无限增长`() {
        var pinned = emptyList<String>()
        repeat(PinnedFolderCodec.MAX_PINNED + 20) { index ->
            pinned = PinnedFolderCodec.pin(pinned, "/dir/$index")
        }

        assertEquals(PinnedFolderCodec.MAX_PINNED, pinned.size)
        // 最新的那条一定还在（被截掉的是最老的）
        assertEquals("/dir/${PinnedFolderCodec.MAX_PINNED + 19}", pinned.first())
    }

    @Test
    fun `unpin 只删指定目录，其余顺序不变`() {
        val pinned = listOf("/a", "/b", "/c")

        assertEquals(listOf("/a", "/c"), PinnedFolderCodec.unpin(pinned, "/b"))
        assertEquals(pinned, PinnedFolderCodec.unpin(pinned, "/zzz"))
    }

    @Test
    fun `pin 与 unpin 互为逆操作`() {
        val start = listOf("/a", "/b")
        val result = PinnedFolderCodec.unpin(PinnedFolderCodec.pin(start, "/c"), "/c")

        assertEquals(start, result)
        assertFalse(result.contains("/c"))
    }

    // ── 图钉与顺序是两个独立的口径 ──────────────────────────────

    @Test
    fun `effectiveOrder 在没有显式顺序时退化用图钉列表`() {
        // 老版本里 pinned_folders 就是「置顶区」，顺序即优先级 —— 升级后首页必须一模一样
        assertEquals(
            listOf("/b", "/a"),
            PinnedFolderCodec.effectiveOrder(order = emptyList(), pinned = listOf("/b", "/a")),
        )
    }

    @Test
    fun `effectiveOrder 有显式顺序时图钉不再参与排序`() {
        assertEquals(
            listOf("/c", "/a"),
            PinnedFolderCodec.effectiveOrder(order = listOf("/c", "/a"), pinned = listOf("/b")),
        )
    }

    // ── 长按拖拽排序（moveOrder）──────────────────────────────
    //
    // `visible` = **松手那一刻界面上的顺序**（被拖项已经落在落点上），`order` = 盘上那份显式顺序。
    // 落点语义只有一条：显式顺序至少要覆盖到落点，其余原有顺序项按原相对顺序接在后面。
    //
    // ⚠️ 这里的 `from` 是**拖之前**的下标。早先版本传进来的却是换位之后的列表，主流程又按
    // `visible[from]` 去取被拖项，于是只要真的移过位就会挪错人：「所见即所存」那几条断言
    // 就是防它复发。

    @Test
    fun `moveOrder 把被拖项放到落点上`() {
        // /b 从第 2 位拖到第 1 位，松手时它已经在最前面了
        val target = listOf("/b", "/a", "/c", "/d")
        val order = listOf("/a", "/b", "/c")

        assertEquals(listOf("/b", "/a", "/c"), PinnedFolderCodec.moveOrder(target, order, from = 1, to = 0))
    }

    @Test
    fun `moveOrder 把落点之前的每一项都显式定序`() {
        // 把 /c 从第 3 位拖到第 2 位：想让它停在中间，/a 与 /c 都得写进顺序
        val target = listOf("/a", "/c", "/b", "/d")

        val next = PinnedFolderCodec.moveOrder(target, listOf("/a"), from = 2, to = 1)

        assertEquals(listOf("/a", "/c"), next)
        assertEquals(target, render(listOf("/a", "/b", "/c", "/d"), next))
    }

    @Test
    fun `moveOrder 把目录拖到末尾时整张表都被定序`() {
        val target = listOf("/b", "/c", "/d", "/a")

        val next = PinnedFolderCodec.moveOrder(target, listOf("/a"), from = 0, to = 3)

        assertEquals(target, next)
        assertEquals(target, render(listOf("/a", "/b", "/c", "/d"), next))
    }

    @Test
    fun `moveOrder 不会丢掉落点之后原有的顺序项`() {
        val target = listOf("/a", "/c", "/b", "/d")

        val next = PinnedFolderCodec.moveOrder(target, listOf("/a", "/b", "/d"), from = 2, to = 1)

        assertEquals(listOf("/a", "/c", "/b", "/d"), next)
    }

    @Test
    fun `moveOrder 保留盘上已经失效的路径`() {
        // /gone 是换存储根 / 拔了 SD 卡留下的死路径。拖一下不该顺手把它清掉
        // （清了就等于「未挂载存储上的顺序偏好全没了」），但渲染时它自然失配、不显示。
        val target = listOf("/c", "/a", "/b")

        val next = PinnedFolderCodec.moveOrder(target, listOf("/gone", "/a"), from = 2, to = 0)

        assertEquals(listOf("/c", "/gone", "/a"), next)
        assertEquals(listOf("/c", "/a", "/b"), render(listOf("/a", "/b", "/c"), next))
    }

    @Test
    fun `moveOrder 原地不动与越界都原样返回`() {
        val order = listOf("/a")

        assertEquals(order, PinnedFolderCodec.moveOrder(listOf("/a", "/b"), order, from = 1, to = 1))
        assertEquals(order, PinnedFolderCodec.moveOrder(listOf("/a", "/b"), order, from = -1, to = 1))
        assertEquals(order, PinnedFolderCodec.moveOrder(listOf("/a", "/b"), order, from = 0, to = 9))
        assertTrue(PinnedFolderCodec.moveOrder(emptyList(), emptyList(), from = 0, to = 0).isEmpty())
    }

    @Test
    fun `moveOrder 有上限，超出的部分退回目录名序`() {
        val order = (0 until PinnedFolderCodec.MAX_ORDER).map { "/dir/$it" }
        val target = listOf("/new") + order // /new 从末尾被拖到最前

        val moved = PinnedFolderCodec.moveOrder(target, order, from = PinnedFolderCodec.MAX_ORDER, to = 0)

        assertEquals(PinnedFolderCodec.MAX_ORDER, moved.size)
        assertEquals("/new", moved.first())
        assertEquals(moved.size, moved.distinct().size)
    }

    @Test
    fun `moveOrder 反复拖动都是所见即所存`() {
        val all = listOf("/a", "/b", "/c", "/d")
        var order = emptyList<String>()

        // 第一拖：把 /d 拖到第 2 位（前一版界面上 /d 在最后）
        var visible = render(all, order)
        var target = visible.filterNot { it == "/d" }.let { it.take(1) + "/d" + it.drop(1) }
        order = PinnedFolderCodec.moveOrder(target, order, from = visible.indexOf("/d"), to = 1)
        assertEquals(target, render(all, order))

        // 第二拖：在上一版界面上把 /b 拖到最前
        visible = render(all, order)
        target = listOf("/b") + visible.filterNot { it == "/b" }
        order = PinnedFolderCodec.moveOrder(target, order, from = visible.indexOf("/b"), to = 0)
        assertEquals(target, render(all, order))

        // 第三拖：把 /a 拖到最后（落点在最末尾 → 整张表都被定序）
        visible = render(all, order)
        target = visible.filterNot { it == "/a" } + "/a"
        order = PinnedFolderCodec.moveOrder(target, order, from = visible.indexOf("/a"), to = target.lastIndex)
        assertEquals(target, render(all, order))

        assertEquals(order.size, order.distinct().size)
    }
}
