package com.qmusic.wear

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.qmusic.wear.ui.home.DailyScreen
import com.qmusic.wear.ui.home.HomeScreen
import com.qmusic.wear.ui.list.SongListScreen
import com.qmusic.wear.ui.login.LoginScreen
import com.qmusic.wear.ui.lyrics.LyricsScreen
import com.qmusic.wear.ui.mine.RecentScreen
import com.qmusic.wear.ui.player.PlayerScreen
import com.qmusic.wear.ui.recommend.RankScreen
import com.qmusic.wear.ui.recommend.SquareScreen
import com.qmusic.wear.ui.recommend.ToplistScreen
import com.qmusic.wear.ui.settings.SettingsScreen
import com.qmusic.wear.ui.downloads.DownloadsScreen
import com.qmusic.wear.ui.theme.QMusicTheme

/** 全部页面（状态机导航，配合 AnimatedContent 实现页面过渡） */
private enum class Screen {
    Home, Player, Lyrics, Login, Recent, SongList, Settings, Downloads, Daily, Rank, Toplist, Square,
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QMusicTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var songListId by rememberSaveable { mutableStateOf(0L) }
    var songListTitle by rememberSaveable { mutableStateOf("") }
    var toplistId by rememberSaveable { mutableStateOf(0) }
    var toplistTitle by rememberSaveable { mutableStateOf("") }

    // Android 13+ 通知权限（媒体播放前台通知需要）
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 返回键：歌词 -> 播放页，登录 -> 设置页，其余二级页 -> 主页
    BackHandler(enabled = screen != Screen.Home) {
        screen = when (screen) {
            Screen.Lyrics -> Screen.Player
            Screen.Login -> Screen.Settings
            else -> Screen.Home
        }
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            fadeIn(tween(220)) togetherWith fadeOut(tween(220))
        },
        label = "page_nav",
    ) { s ->
        when (s) {
            Screen.Home -> HomeScreen(
                onOpenPlayer = { screen = Screen.Player },
                onOpenRecent = { screen = Screen.Recent },
                onOpenDownloads = { screen = Screen.Downloads },
                onOpenSettings = { screen = Screen.Settings },
                onOpenPlaylist = { id, title ->
                    songListId = id
                    songListTitle = title
                    screen = Screen.SongList
                },
                onOpenDaily = { screen = Screen.Daily },
                onOpenRank = { screen = Screen.Rank },
                onOpenSquare = { screen = Screen.Square },
            )

            Screen.Player -> PlayerScreen(
                onOpenLyrics = { screen = Screen.Lyrics },
                onOpenDownloads = { screen = Screen.Downloads },
            )

            Screen.Lyrics -> LyricsScreen()

            Screen.Login -> LoginScreen(
                onBack = { screen = Screen.Settings },
                onSuccess = { screen = Screen.Settings },
            )

            Screen.Settings -> SettingsScreen(
                onOpenLogin = { screen = Screen.Login },
            )

            Screen.Downloads -> DownloadsScreen(
                onOpenPlayer = { screen = Screen.Player },
            )

            Screen.Recent -> RecentScreen(onOpenPlayer = { screen = Screen.Player })

            Screen.SongList -> SongListScreen(
                disstid = songListId,
                title = songListTitle,
                onOpenPlayer = { screen = Screen.Player },
            )

            Screen.Daily -> DailyScreen(
                onOpenPlayer = { screen = Screen.Player },
            )

            Screen.Rank -> RankScreen(
                onOpenToplist = { topId, title ->
                    toplistId = topId
                    toplistTitle = title
                    screen = Screen.Toplist
                },
            )

            Screen.Toplist -> ToplistScreen(
                topId = toplistId,
                title = toplistTitle,
                onOpenPlayer = { screen = Screen.Player },
            )

            Screen.Square -> SquareScreen(
                onOpenPlaylist = { id, title ->
                    songListId = id
                    songListTitle = title
                    screen = Screen.SongList
                },
            )
        }
    }
}
