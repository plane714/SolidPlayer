package com.solidplayer.ui.library

import com.solidplayer.core.data.network.dto.MatchResultV2
import com.solidplayer.core.data.network.dto.SearchEpisodesAnime

/**
 * 媒体库页上的弹窗状态。
 *
 * 从 v1 的 `LibraryViewModel` 里抽出来单独成文件：新的「文件夹详情页」与首页要共用同一套识别弹窗，
 * 而它已经不隶属于某一个 ViewModel 了。
 */
sealed interface LibraryDialog {

    /**
     * 识别弹窗。文本框默认填入文件名，用户改掉它就是「手填文件名」（文档 §13 P0）。
     */
    data class Identify(
        val fileId: Long,
        val fileName: String,
        val manualName: String,
    ) : LibraryDialog

    /** 多候选选择（文档 §4.2 决策表：`matches.size > 1` 时弹窗让用户选，默认高亮第一条）。 */
    data class Candidates(
        val fileId: Long,
        val candidates: List<MatchResultV2>,
        val manual: Boolean,
    ) : LibraryDialog

    /** P1 手动搜索：拿标题去 `/api/v2/search/episodes`，从结果里选作品 → 选集。 */
    data class Search(
        val fileId: Long,
        val fileName: String,
        val keyword: String,
        val searching: Boolean = false,
        val searched: Boolean = false,
        val animes: List<SearchEpisodesAnime> = emptyList(),
        /** 展开的作品；一次只展开一个，避免长列表把弹窗撑爆 */
        val expandedAnimeId: Long? = null,
        val error: String? = null,
    ) : LibraryDialog
}
