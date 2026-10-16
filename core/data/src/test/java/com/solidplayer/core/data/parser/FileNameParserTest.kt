package com.solidplayer.core.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件名解析规则引擎单测（文档 §6.2 / §14：**每一条规则至少 3 个正例 + 2 个反例**）。
 *
 * 这个模块的正确率直接决定手动匹配的体验，必须可回归。
 */
class FileNameParserTest {

    // ── 规则 1：季集标记 SxxEyy ───────────────────────────────────

    @Test
    fun `规则1-正例 Title S02E07`() {
        val r = FileNameParser.parse("Title S02E07.mkv")
        assertEquals(2, r.season)
        assertEquals("7", r.episode)
        assertEquals("Title", r.title)
    }

    @Test
    fun `规则1-正例 带字幕组与英文标题`() {
        val r = FileNameParser.parse("[Group] Anime Title S1E105 [WEB-DL].mkv")
        assertEquals(1, r.season)
        assertEquals("105", r.episode)
        assertEquals("Anime Title", r.title)
    }

    @Test
    fun `规则1-正例 季集标记夹在噪声之间`() {
        val r = FileNameParser.parse("Show - S03E07 - 1080p.mp4")
        assertEquals(3, r.season)
        assertEquals("7", r.episode)
        assertEquals("Show", r.title)
    }

    @Test
    fun `规则1-反例 没有 E 段时不走本规则`() {
        val r = FileNameParser.parse("Anime - 07v2 [GB].mkv")
        assertNull(r.season)
        // 由规则 4 兜底解析出集数
        assertEquals("7", r.episode)
    }

    @Test
    fun `规则1-反例 中文话数不走本规则`() {
        val r = FileNameParser.parse("标题 第07话.mkv")
        assertNull(r.season)
        assertEquals("7", r.episode)
    }

    // ── 规则 2：中文话数 ──────────────────────────────────────────

    @Test
    fun `规则2-正例 第07话`() {
        val r = FileNameParser.parse("[组] 标题 第07话 [1080p].mkv")
        assertEquals("7", r.episode)
        assertEquals("标题", r.title)
    }

    @Test
    fun `规则2-正例 数字与单位之间有空格`() {
        assertEquals("12", FileNameParser.parse("标题 第 12 集.mkv").episode)
    }

    @Test
    fun `规则2-正例 繁体話 与三位数`() {
        val r = FileNameParser.parse("第103話.mkv")
        assertEquals("103", r.episode)
        assertEquals("", r.title)
    }

    @Test
    fun `规则2-反例 第1季 不是集数`() {
        val r = FileNameParser.parse("第1季.mkv")
        assertNull(r.episode)
        assertEquals("第1季", r.title)
    }

    @Test
    fun `规则2-反例 集数超过 999 直接跳过`() {
        val r = FileNameParser.parse("第1000话.mkv")
        assertNull(r.episode)
    }

    // ── 规则 3：方括号集数 ────────────────────────────────────────

    @Test
    fun `规则3-正例 中文字幕组方括号结构`() {
        val r = FileNameParser.parse("[组][标题][07][BDRip].mkv")
        assertEquals("7", r.episode)
    }

    @Test
    fun `规则3-正例 方括号之间夹着画质标记`() {
        assertEquals("12", FileNameParser.parse("[A][B][12][1080p][x264].mkv").episode)
    }

    @Test
    fun `规则3-正例 三位数集数`() {
        assertEquals("3", FileNameParser.parse("[Group][Anime][3][GB].mkv").episode)
    }

    @Test
    fun `规则3-反例 纯画质编码方括号不是集数`() {
        assertNull(FileNameParser.parse("[1080p][x264].mkv").episode)
    }

    @Test
    fun `规则3-反例 单个方括号标题不会被误判`() {
        val r = FileNameParser.parse("[Sakura] 进击的巨人 [BDRip].mkv")
        assertNull(r.episode)
        assertEquals("进击的巨人", r.title)
    }

    // ── 规则 3.5：花絮 / 特别篇 / OVA 前缀（保留前缀作为原始值）────

    @Test
    fun `规则3_5-正例 C1 花絮`() {
        val r = FileNameParser.parse("标题 C1.mkv")
        assertEquals("C1", r.episode)
        assertEquals("标题", r.title)
    }

    @Test
    fun `规则3_5-正例 O2 OVA`() {
        assertEquals("O2", FileNameParser.parse("标题 O2.mkv").episode)
    }

    @Test
    fun `规则3_5-正例 S1 特别篇`() {
        val r = FileNameParser.parse("Title S1 [OVA].mkv")
        assertEquals("S1", r.episode)
        assertEquals("Title", r.title)
    }

    @Test
    fun `规则3_5-反例 前缀后面没有数字`() {
        val r = FileNameParser.parse("Show Sabc.mkv")
        assertNull(r.episode)
        assertEquals("Show Sabc", r.title)
    }

    @Test
    fun `规则3_5-反例 只有前缀没有编号`() {
        assertNull(FileNameParser.parse("Show S.mkv").episode)
    }

    // ── 规则 4：分隔符集数 ────────────────────────────────────────

    @Test
    fun `规则4-正例 完整噪声文件名`() {
        val r = FileNameParser.parse("[Sakura][BDRip][1080p][x264_FLAC] 进击的巨人 - 07v2 [GB].mkv")
        assertEquals("7", r.episode)
        assertEquals("进击的巨人", r.title)
    }

    @Test
    fun `规则4-正例 中文标题加连字符`() {
        val r = FileNameParser.parse("[组] 标题 - 12 [x264].mp4")
        assertEquals("12", r.episode)
        assertEquals("标题", r.title)
    }

    @Test
    fun `规则4-正例 英文标题加连字符`() {
        assertEquals("3", FileNameParser.parse("[Sakura] Title - 03 [GB].mkv").episode)
    }

    @Test
    fun `规则4-反例 分辨率不会被当成集数`() {
        assertNull(FileNameParser.parse("Title 1080p.mkv").episode)
    }

    @Test
    fun `规则4-反例 方括号里的分辨率与编码不会被当成集数`() {
        assertNull(FileNameParser.parse("Title [1080p][x264].mkv").episode)
    }

    // ── 规则 5：结尾纯数字 ────────────────────────────────────────

    @Test
    fun `规则5-正例 标题加空格加集数`() {
        val r = FileNameParser.parse("标题 07.mkv")
        assertEquals("7", r.episode)
        assertEquals("标题", r.title)
    }

    @Test
    fun `规则5-正例 英文标题加集数`() {
        assertEquals("12", FileNameParser.parse("Anime 12.mp4").episode)
    }

    @Test
    fun `规则5-正例 单位数集数`() {
        assertEquals("5", FileNameParser.parse("Show 5.rmvb").episode)
    }

    @Test
    fun `规则5-反例 年份不会被拆成三位数集数`() {
        val r = FileNameParser.parse("剧场版 2024.mkv")
        assertNull(r.episode)
        assertEquals("剧场版 2024", r.title)
    }

    @Test
    fun `规则5-反例 四位分辨率不会被当成集数`() {
        assertNull(FileNameParser.parse("OVA 1024.mkv").episode)
    }

    // ── 规则 6：无集数 ────────────────────────────────────────────

    @Test
    fun `规则6-正例 剧场版无集数`() {
        val r = FileNameParser.parse("[Sakura] 剧场版 [BDRip][1080p].mkv")
        assertNull(r.episode)
        assertEquals("剧场版", r.title)
    }

    @Test
    fun `规则6-正例 OVA 无编号`() {
        val r = FileNameParser.parse("进击的巨人 OVA.mkv")
        assertNull(r.episode)
        assertEquals("进击的巨人 OVA", r.title)
    }

    @Test
    fun `规则6-正例 纯标题`() {
        val r = FileNameParser.parse("Movie.mkv")
        assertNull(r.episode)
        assertEquals("Movie", r.title)
    }

    @Test
    fun `规则6-反例 有集数时不会落到无集数分支`() {
        assertEquals("3", FileNameParser.parse("Title 第03话.mkv").episode)
    }

    @Test
    fun `规则6-反例 季集标记不会被当成无集数`() {
        assertEquals("7", FileNameParser.parse("Title S02E07.mkv").episode)
    }

    // ── 噪声词表与输出结构 ────────────────────────────────────────

    @Test
    fun `噪声词被剥离 年份保留`() {
        val r = FileNameParser.parse("Movie 2024 WEB-DL 1080p.mkv")
        assertNull(r.episode)
        assertEquals("Movie 2024", r.title)
    }

    @Test
    fun `对比度更高的噪声组合也会被清掉`() {
        val r = FileNameParser.parse("Anime 10bit HEVC FLAC CHS 简繁.mkv")
        assertNull(r.episode)
        assertEquals("Anime", r.title)
    }

    @Test
    fun `raw 字段保留原始文件名`() {
        val raw = "[Sakura] 进击的巨人 - 07v2 [GB].mkv"
        assertEquals(raw, FileNameParser.parse(raw).raw)
    }

    @Test
    fun `没有扩展名的输入同样可以解析`() {
        val r = FileNameParser.parse("进击的巨人 - 07")
        assertEquals("7", r.episode)
        assertEquals("进击的巨人", r.title)
    }

    @Test
    fun `空输入不抛异常`() {
        val r = FileNameParser.parse("   ")
        assertEquals("", r.title)
        assertNull(r.episode)
    }

    @Test
    fun `置信度按规则优先级的顺序递减`() {
        val seasonEpisode = FileNameParser.parse("Title S02E07.mkv").confidence
        val cnEpisode = FileNameParser.parse("标题 第07话.mkv").confidence
        val bracket = FileNameParser.parse("[组][标题][07][BDRip].mkv").confidence
        val delimited = FileNameParser.parse("[组] 标题 - 12 [x264].mp4").confidence
        val tail = FileNameParser.parse("标题 07.mkv").confidence

        assertTrue(seasonEpisode > cnEpisode)
        assertTrue(cnEpisode > bracket)
        assertTrue(bracket > delimited)
        assertTrue(delimited > tail)
        assertTrue(tail > 0f)
    }

    @Test
    fun `searchKeyword 拼出标题加集数`() {
        assertEquals("进击的巨人 7", FileNameParser.parse("进击的巨人 - 07.mkv").searchKeyword())
    }

    @Test
    fun `confidence 落在 0 到 1 之间`() {
        listOf("Title S02E07.mkv", "第1季.mkv", "Movie.mkv").forEach {
            val c = FileNameParser.parse(it).confidence
            assertTrue("confidence=$c", c in 0f..1f)
        }
    }
}
