package com.qmusic.wear

import androidx.compose.runtime.Composable
import com.qmusic.wear.data.player.NowPlaying
import com.qmusic.wear.ui.browse.AlbumScreen
import com.qmusic.wear.ui.browse.ArtistScreen
import com.qmusic.wear.ui.comments.CommentsScreen
import com.qmusic.wear.ui.components.SwipeBackBox
import com.qmusic.wear.ui.downloads.DownloadsScreen
import com.qmusic.wear.ui.home.DailyScreen
import com.qmusic.wear.ui.home.HomeScreen
import com.qmusic.wear.ui.list.SongListScreen
import com.qmusic.wear.ui.login.LoginScreen
import com.qmusic.wear.ui.lyrics.LyricsScreen
import com.qmusic.wear.ui.mine.RecentScreen
import com.qmusic.wear.ui.player.PlayerScreen
import com.qmusic.wear.ui.podcast.DjProgramScreen
import com.qmusic.wear.ui.podcast.PodcastScreen
import com.qmusic.wear.ui.queue.QueueScreen
import com.qmusic.wear.ui.recommend.RankScreen
import com.qmusic.wear.ui.recommend.SquareScreen
import com.qmusic.wear.ui.recommend.ToplistScreen
import com.qmusic.wear.ui.settings.SettingsScreen
import com.qmusic.wear.ui.social.SocialScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 单个页面的路由分发：把 [Screen] 映射为具体页面组合，并处理各页面的入参与返回目标。
 * 返回逻辑统一走 [ScreenNav.backTarget]（与右滑手势、系统返回键一致）。
 */
@Composable
internal fun AppRoute(
    s: Screen,
    nav: ScreenNav,
    now: NowPlaying,
    navScope: CoroutineScope,
) {
    when (s) {
        Screen.Home -> HomeScreen(
            onOpenPlayer = { nav.screen = Screen.Player },
            onOpenRecent = { nav.screen = Screen.Recent },
            onOpenDownloads = {
                nav.downloadsFrom = Screen.Home
                nav.screen = Screen.Downloads
            },
            onOpenSettings = { nav.screen = Screen.Settings },
            onOpenPlaylist = { id, title ->
                nav.songListId = id
                nav.songListTitle = title
                nav.songListFrom = Screen.Home
                nav.screen = Screen.SongList
            },
            onOpenArtist = { mid, name ->
                nav.artistMid = mid
                nav.artistName = name
                nav.screen = Screen.Artist
            },
            onOpenAlbum = { mid, _ ->
                nav.albumMid = mid
                nav.screen = Screen.Album
            },
            onOpenPodcast = { nav.screen = Screen.Podcast },
            onOpenSocial = {
                nav.socialUid = ServiceLocator.credential.value.musicid
                nav.screen = Screen.Social
            },
            // 首页大卡动作分发（随音乐源变化，数量与类型不固定）
            onOpenCard = { action, targetId, title ->
                when (action) {
                    "daily", "songs" -> nav.screen = Screen.Daily
                    "rank" -> nav.screen = Screen.Rank
                    "square" -> nav.screen = Screen.Square
                    "playlist" -> {
                        nav.songListId = targetId.toLongOrNull() ?: 0L
                        nav.songListTitle = title
                        nav.songListFrom = Screen.Home
                        nav.screen = Screen.SongList
                    }
                    "toplist" -> {
                        nav.toplistId = targetId.toLongOrNull() ?: 0L
                        nav.toplistTitle = title
                        nav.toplistCover = ""
                        nav.screen = Screen.Toplist
                    }
                    "album" -> {
                        nav.albumMid = targetId
                        nav.screen = Screen.Album
                    }
                    "artist" -> {
                        nav.artistMid = targetId
                        nav.artistName = title
                        nav.screen = Screen.Artist
                    }
                    "recent" -> nav.screen = Screen.Recent
                    "downloads" -> {
                        nav.downloadsFrom = Screen.Home
                        nav.screen = Screen.Downloads
                    }
                    // 私人漫游（私人FM）：拉一批推荐直接开播
                    "radio" -> navScope.launch {
                        val songs = ServiceLocator.repository.radioSongs()
                        if (songs.isNotEmpty()) {
                            ServiceLocator.player.playFromList(songs, songs.first().mid)
                            nav.screen = Screen.Player
                        }
                    }
                    "podcast" -> nav.screen = Screen.Podcast
                    "events" -> {
                        nav.socialUid = ServiceLocator.credential.value.musicid
                        nav.screen = Screen.Social
                    }
                    else -> Unit
                }
            },
        )

        // 播放页自带方向化手势（手指左滑歌词 / 右滑返回 / 上滑队列），不包 SwipeBackBox
        Screen.Player -> PlayerScreen(
            onOpenLyrics = { nav.screen = Screen.Lyrics },
            onOpenDownloads = {
                nav.downloadsFrom = Screen.Player
                nav.screen = Screen.Downloads
            },
            onOpenQueue = { nav.screen = Screen.Queue },
            onOpenComments = {
                // 评论目标取当前播放歌曲（进入后固定在打开时的那首）
                val song = now.song
                if (song != null) {
                    nav.commentSongId = song.songId
                    nav.commentMid = song.mid
                    nav.commentName = song.name
                    nav.screen = Screen.Comments
                }
            },
            onBack = { nav.screen = Screen.Home },
        )

        Screen.Lyrics -> LyricsScreen(onBack = { nav.screen = Screen.Player })

        // 队列页内部已自带 SwipeBackBox（右滑返回播放页）
        Screen.Queue -> QueueScreen(onBack = { nav.screen = Screen.Player })

        Screen.Login -> SwipeBackBox(onBack = { nav.screen = Screen.Settings }) {
            LoginScreen(
                onBack = { nav.screen = Screen.Settings },
                onSuccess = { nav.screen = Screen.Settings },
            )
        }

        Screen.Settings -> SwipeBackBox(onBack = { nav.screen = Screen.Home }) {
            SettingsScreen(
                onOpenLogin = { nav.screen = Screen.Login },
            )
        }

        Screen.Downloads -> SwipeBackBox(onBack = { nav.screen = nav.backTarget(Screen.Downloads) }) {
            DownloadsScreen(
                onOpenPlayer = { nav.screen = Screen.Player },
            )
        }

        Screen.Recent -> SwipeBackBox(onBack = { nav.screen = Screen.Home }) {
            RecentScreen(onOpenPlayer = { nav.screen = Screen.Player })
        }

        Screen.SongList -> SwipeBackBox(onBack = { nav.screen = nav.backTarget(Screen.SongList) }) {
            SongListScreen(
                disstid = nav.songListId,
                title = nav.songListTitle,
                onOpenPlayer = { nav.screen = Screen.Player },
            )
        }

        Screen.Daily -> SwipeBackBox(onBack = { nav.screen = Screen.Home }) {
            DailyScreen(
                onOpenPlayer = { nav.screen = Screen.Player },
            )
        }

        Screen.Rank -> SwipeBackBox(onBack = { nav.screen = Screen.Home }) {
            RankScreen(
                onOpenToplist = { topId, title, cover ->
                    nav.toplistId = topId
                    nav.toplistTitle = title
                    nav.toplistCover = cover
                    nav.screen = Screen.Toplist
                },
            )
        }

        Screen.Toplist -> SwipeBackBox(onBack = { nav.screen = Screen.Rank }) {
            ToplistScreen(
                topId = nav.toplistId,
                title = nav.toplistTitle,
                coverUrl = nav.toplistCover,
                onOpenPlayer = { nav.screen = Screen.Player },
            )
        }

        Screen.Square -> SwipeBackBox(onBack = { nav.screen = Screen.Home }) {
            SquareScreen(
                onOpenPlaylist = { id, title ->
                    nav.songListId = id
                    nav.songListTitle = title
                    nav.songListFrom = Screen.Square
                    nav.screen = Screen.SongList
                },
            )
        }

        Screen.Artist -> SwipeBackBox(onBack = { nav.screen = Screen.Home }) {
            ArtistScreen(
                singerMid = nav.artistMid,
                singerName = nav.artistName,
                onOpenPlayer = { nav.screen = Screen.Player },
                onOpenAlbum = { mid, _ ->
                    nav.albumMid = mid
                    nav.screen = Screen.Album
                },
            )
        }

        Screen.Album -> SwipeBackBox(onBack = { nav.screen = Screen.Home }) {
            AlbumScreen(
                albumMid = nav.albumMid,
                onOpenPlayer = { nav.screen = Screen.Player },
            )
        }

        Screen.Comments -> SwipeBackBox(onBack = { nav.screen = Screen.Player }) {
            CommentsScreen(
                songId = nav.commentSongId,
                mid = nav.commentMid,
                name = nav.commentName,
            )
        }

        Screen.Podcast -> SwipeBackBox(onBack = { nav.screen = Screen.Home }) {
            PodcastScreen(
                onOpenRadio = { id, name ->
                    nav.radioId = id
                    nav.radioName = name
                    nav.screen = Screen.DjProgram
                },
            )
        }

        Screen.DjProgram -> SwipeBackBox(onBack = { nav.screen = Screen.Podcast }) {
            DjProgramScreen(
                radioId = nav.radioId,
                radioName = nav.radioName,
                onOpenPlayer = { nav.screen = Screen.Player },
            )
        }

        Screen.Social -> SwipeBackBox(onBack = { nav.screen = Screen.Home }) {
            SocialScreen(
                uid = nav.socialUid,
                onOpenPlayer = { nav.screen = Screen.Player },
            )
        }
    }
}