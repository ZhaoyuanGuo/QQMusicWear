package com.qmusic.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/** 全部页面（状态机导航，配合 AnimatedContent 实现页面过渡） */
internal enum class Screen {
    Home, Player, Lyrics, Queue, Login, Recent, SongList, Settings, Downloads, Daily, Rank, Toplist, Square, Artist, Album,
    Comments, Podcast, DjProgram, Social,
}

/** 页面导航深度：驱动方向化转场（浅→深新页从右滑入，深→浅新页从左滑入） */
internal fun navDepth(s: Screen): Int = when (s) {
    Screen.Home -> 0
    Screen.Player, Screen.Daily, Screen.Rank, Screen.Square, Screen.Recent, Screen.Settings, Screen.Downloads,
    Screen.Podcast, Screen.Social,
    -> 1
    Screen.Lyrics, Screen.Queue, Screen.Login, Screen.SongList, Screen.Toplist, Screen.Artist, Screen.Album,
    Screen.Comments, Screen.DjProgram,
    -> 2
}

/**
 * 应用内导航状态：当前页面 + 各二级页入参 + 多入口页面的来源记忆。
 * 原为 AppRoot 内散落的 rememberSaveable 变量，集中到此处后路由表可独立成文件；
 * [Saver] 保持与原先逐字段 rememberSaveable 完全一致的保存/恢复语义。
 */
internal class ScreenNav(
    initialScreen: Screen = Screen.Home,
    initialSongListId: Long = 0L,
    initialSongListTitle: String = "",
    initialArtistMid: String = "",
    initialArtistName: String = "",
    initialAlbumMid: String = "",
    initialToplistId: Long = 0L,
    initialToplistTitle: String = "",
    initialToplistCover: String = "",
    initialCommentSongId: Long = 0L,
    initialCommentMid: String = "",
    initialCommentName: String = "",
    initialRadioId: Long = 0L,
    initialRadioName: String = "",
    initialSocialUid: Long = 0L,
    initialSongListFrom: Screen = Screen.Home,
    initialDownloadsFrom: Screen = Screen.Home,
) {
    var screen by mutableStateOf(initialScreen)
    var songListId by mutableStateOf(initialSongListId)
    var songListTitle by mutableStateOf(initialSongListTitle)
    var artistMid by mutableStateOf(initialArtistMid)
    var artistName by mutableStateOf(initialArtistName)
    var albumMid by mutableStateOf(initialAlbumMid)
    var toplistId by mutableStateOf(initialToplistId)
    var toplistTitle by mutableStateOf(initialToplistTitle)
    var toplistCover by mutableStateOf(initialToplistCover)
    var commentSongId by mutableStateOf(initialCommentSongId)
    var commentMid by mutableStateOf(initialCommentMid)
    var commentName by mutableStateOf(initialCommentName)
    var radioId by mutableStateOf(initialRadioId)
    var radioName by mutableStateOf(initialRadioName)
    var socialUid by mutableStateOf(initialSocialUid)
    var songListFrom by mutableStateOf(initialSongListFrom)
    var downloadsFrom by mutableStateOf(initialDownloadsFrom)

    /** 各页面的上一页映射（歌词→播放、登录→设置、榜单→排行榜、歌单/下载→来源页，其余→主页） */
    fun backTarget(s: Screen): Screen = when (s) {
        Screen.Lyrics -> Screen.Player
        Screen.Queue -> Screen.Player
        Screen.Login -> Screen.Settings
        Screen.Toplist -> Screen.Rank
        Screen.SongList -> songListFrom
        Screen.Downloads -> downloadsFrom
        Screen.Comments -> Screen.Player
        Screen.DjProgram -> Screen.Podcast
        Screen.Podcast, Screen.Social -> Screen.Home
        else -> Screen.Home
    }

    companion object {
        val Saver: Saver<ScreenNav, Any> = listSaver(
            save = { n ->
                listOf(
                    n.screen, n.songListId, n.songListTitle, n.artistMid, n.artistName,
                    n.albumMid, n.toplistId, n.toplistTitle, n.toplistCover,
                    n.commentSongId, n.commentMid, n.commentName, n.radioId, n.radioName,
                    n.socialUid, n.songListFrom, n.downloadsFrom,
                )
            },
            restore = { v ->
                ScreenNav(
                    initialScreen = v[0] as Screen,
                    initialSongListId = v[1] as Long,
                    initialSongListTitle = v[2] as String,
                    initialArtistMid = v[3] as String,
                    initialArtistName = v[4] as String,
                    initialAlbumMid = v[5] as String,
                    initialToplistId = v[6] as Long,
                    initialToplistTitle = v[7] as String,
                    initialToplistCover = v[8] as String,
                    initialCommentSongId = v[9] as Long,
                    initialCommentMid = v[10] as String,
                    initialCommentName = v[11] as String,
                    initialRadioId = v[12] as Long,
                    initialRadioName = v[13] as String,
                    initialSocialUid = v[14] as Long,
                    initialSongListFrom = v[15] as Screen,
                    initialDownloadsFrom = v[16] as Screen,
                )
            },
        )
    }
}