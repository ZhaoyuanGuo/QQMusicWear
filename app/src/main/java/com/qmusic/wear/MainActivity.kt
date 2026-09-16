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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import kotlinx.coroutines.launch
import com.qmusic.wear.ui.agreement.AgreementScreen
import com.qmusic.wear.ui.components.LiveCapsule
import com.qmusic.wear.ui.components.SwipeBackBox
import com.qmusic.wear.ui.home.DailyScreen
import com.qmusic.wear.ui.home.HomeScreen
import com.qmusic.wear.ui.list.SongListScreen
import com.qmusic.wear.ui.login.LoginScreen
import com.qmusic.wear.ui.lyrics.LyricsScreen
import com.qmusic.wear.ui.mine.RecentScreen
import com.qmusic.wear.ui.player.PlayerScreen
import com.qmusic.wear.ui.queue.QueueScreen
import com.qmusic.wear.ui.recommend.RankScreen
import com.qmusic.wear.ui.recommend.SquareScreen
import com.qmusic.wear.ui.recommend.ToplistScreen
import com.qmusic.wear.ui.settings.SettingsScreen
import com.qmusic.wear.ui.downloads.DownloadsScreen
import com.qmusic.wear.ui.source.SourceGateScreen
import com.qmusic.wear.data.source.SourceManager
import com.qmusic.wear.data.source.SourceState
import com.qmusic.wear.ui.theme.LocalIsAmbient
import com.qmusic.wear.ui.theme.QMusicTheme

/** 全部页面（状态机导航，配合 AnimatedContent 实现页面过渡） */
private enum class Screen {
    Home, Player, Lyrics, Queue, Login, Recent, SongList, Settings, Downloads, Daily, Rank, Toplist, Square,
}

/** 是否具备 Wear OS 共享库（真手表具备；缺失环境跳过 AOD 注册避免闪退） */
private fun hasWearableSharedLibrary(): Boolean = runCatching {
    Class.forName("android.support.wearable.R\$version")
}.isSuccess

/** 页面导航深度：驱动方向化转场（浅→深新页从右滑入，深→浅新页从左滑入） */
private fun navDepth(s: Screen): Int = when (s) {
    Screen.Home -> 0
    Screen.Player, Screen.Daily, Screen.Rank, Screen.Square, Screen.Recent, Screen.Settings, Screen.Downloads -> 1
    Screen.Lyrics, Screen.Queue, Screen.Login, Screen.SongList, Screen.Toplist -> 2
}

class MainActivity : ComponentActivity() {

    // AOD（环境模式）状态：Wear OS 4+ 经 AmbientLifecycleObserver 通知，CompositionLocal 下发
    private val isAmbientState = mutableStateOf(false)
    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbientState.value = true
        }

        override fun onExitAmbient() {
            isAmbientState.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AOD 观察依赖 wearable 共享库（com.google.android.wearable）；
        // 缺库环境（如非手表模拟器）直接跳过注册，否则启动即抛 IllegalStateException
        if (hasWearableSharedLibrary()) {
            lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        }
        setContent {
            QMusicTheme {
                CompositionLocalProvider(LocalIsAmbient provides isAmbientState.value) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    // —— 用户协议：首启（或协议更新后）必须同意才能进入应用 ——
    val agreementStore = remember { ServiceLocator.agreementStore }
    var agreed by remember { mutableStateOf(agreementStore.isAgreed) }
    if (!agreed) {
        AgreementScreen(
            onAgree = {
                agreementStore.setAgreed()
                agreed = true
            },
        )
        return
    }

    // —— 音乐源：协议后自动下载/加载（协议实现在外部源插件，APK 无明文） ——
    val sourceState by SourceManager.state.collectAsStateWithLifecycle()
    if (sourceState !is SourceState.Ready) {
        val retryScope = rememberCoroutineScope()
        val gateContext = LocalContext.current
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                retryScope.launch {
                    val result = runCatching {
                        gateContext.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull()
                    val err = when {
                        result == null -> "读取文件失败"
                        else -> SourceManager.importScript(result)
                    }
                    android.widget.Toast.makeText(
                        gateContext,
                        err ?: "音乐源导入成功",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        // 自动下载只触发一次（key=Unit）：若以 sourceState 为 key，状态变为 Downloading 会
        // 取消正在运行的下载协程（"coroutine scope left the composition"）。
        // 仅 Missing 态触发；Failed 态等待用户手动「重试」，避免失败重试循环。
        LaunchedEffect(Unit) {
            if (SourceManager.state.value is SourceState.Missing) {
                SourceManager.downloadNow()
            }
        }
        SourceGateScreen(
            state = sourceState,
            onRetry = { retryScope.launch { SourceManager.downloadNow() } },
            onImport = { importLauncher.launch(arrayOf("*/*")) },
        )
        return
    }

    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var songListId by rememberSaveable { mutableStateOf(0L) }
    var songListTitle by rememberSaveable { mutableStateOf("") }
    var toplistId by rememberSaveable { mutableStateOf(0) }
    var toplistTitle by rememberSaveable { mutableStateOf("") }

    // 开屏提示（可在设置页开关）
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (ServiceLocator.settingsStore.launchToastFlow.value) {
            android.widget.Toast.makeText(ctx, "仅供学习交流使用", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 多入口页面的来源记忆：右滑返回 / 返回键都回到“上一页”而非固定主页
    var songListFrom by rememberSaveable { mutableStateOf(Screen.Home) }
    var downloadsFrom by rememberSaveable { mutableStateOf(Screen.Home) }

    /** 各页面的上一页映射（歌词→播放、登录→设置、榜单→排行榜、歌单/下载→来源页，其余→主页） */
    fun backTarget(s: Screen): Screen = when (s) {
        Screen.Lyrics -> Screen.Player
        Screen.Queue -> Screen.Player
        Screen.Login -> Screen.Settings
        Screen.Toplist -> Screen.Rank
        Screen.SongList -> songListFrom
        Screen.Downloads -> downloadsFrom
        else -> Screen.Home
    }

    // Android 13+ 通知权限（媒体播放前台通知需要）
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 返回键：与右滑手势一致，回到上一页
    BackHandler(enabled = screen != Screen.Home) {
        screen = backTarget(screen)
    }

    // 播放状态：全局实况胶囊依赖（有歌曲时在所有页面底部悬浮，协议页不经过此处）
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                // 方向化转场：歌词从右推入、队列从下推入，其余按导航深度横向滑动
                when {
                    initialState == Screen.Player && targetState == Screen.Queue ->
                        slideInVertically(tween(260)) { it } togetherWith
                            slideOutVertically(tween(260)) { -it }

                    initialState == Screen.Queue && targetState == Screen.Player ->
                        slideInVertically(tween(260)) { -it } togetherWith
                            slideOutVertically(tween(260)) { it }

                    navDepth(targetState) > navDepth(initialState) ->
                        slideInHorizontally(tween(260)) { it } togetherWith
                            slideOutHorizontally(tween(260)) { -it }

                    navDepth(targetState) < navDepth(initialState) ->
                        slideInHorizontally(tween(260)) { -it } togetherWith
                            slideOutHorizontally(tween(260)) { it }

                    else -> fadeIn(tween(220)) togetherWith fadeOut(tween(220))
                }
            },
            label = "page_nav",
        ) { s ->
        when (s) {
            Screen.Home -> HomeScreen(
                onOpenPlayer = { screen = Screen.Player },
                onOpenRecent = { screen = Screen.Recent },
                onOpenDownloads = {
                    downloadsFrom = Screen.Home
                    screen = Screen.Downloads
                },
                onOpenSettings = { screen = Screen.Settings },
                onOpenPlaylist = { id, title ->
                    songListId = id
                    songListTitle = title
                    songListFrom = Screen.Home
                    screen = Screen.SongList
                },
                onOpenDaily = { screen = Screen.Daily },
                onOpenRank = { screen = Screen.Rank },
                onOpenSquare = { screen = Screen.Square },
            )

            // 播放页自带方向化手势（手指左滑歌词 / 右滑返回 / 上滑队列），不包 SwipeBackBox
            Screen.Player -> PlayerScreen(
                onOpenLyrics = { screen = Screen.Lyrics },
                onOpenDownloads = {
                    downloadsFrom = Screen.Player
                    screen = Screen.Downloads
                },
                onOpenQueue = { screen = Screen.Queue },
                onBack = { screen = Screen.Home },
            )

            Screen.Lyrics -> LyricsScreen(onBack = { screen = Screen.Player })

            // 队列页内部已自带 SwipeBackBox（右滑返回播放页）
            Screen.Queue -> QueueScreen(onBack = { screen = Screen.Player })

            Screen.Login -> SwipeBackBox(onBack = { screen = Screen.Settings }) {
                LoginScreen(
                    onBack = { screen = Screen.Settings },
                    onSuccess = { screen = Screen.Settings },
                )
            }

            Screen.Settings -> SwipeBackBox(onBack = { screen = Screen.Home }) {
                SettingsScreen(
                    onOpenLogin = { screen = Screen.Login },
                )
            }

            Screen.Downloads -> SwipeBackBox(onBack = { screen = backTarget(Screen.Downloads) }) {
                DownloadsScreen(
                    onOpenPlayer = { screen = Screen.Player },
                )
            }

            Screen.Recent -> SwipeBackBox(onBack = { screen = Screen.Home }) {
                RecentScreen(onOpenPlayer = { screen = Screen.Player })
            }

            Screen.SongList -> SwipeBackBox(onBack = { screen = backTarget(Screen.SongList) }) {
                SongListScreen(
                    disstid = songListId,
                    title = songListTitle,
                    onOpenPlayer = { screen = Screen.Player },
                )
            }

            Screen.Daily -> SwipeBackBox(onBack = { screen = Screen.Home }) {
                DailyScreen(
                    onOpenPlayer = { screen = Screen.Player },
                )
            }

            Screen.Rank -> SwipeBackBox(onBack = { screen = Screen.Home }) {
                RankScreen(
                    onOpenToplist = { topId, title ->
                        toplistId = topId
                        toplistTitle = title
                        screen = Screen.Toplist
                    },
                )
            }

            Screen.Toplist -> SwipeBackBox(onBack = { screen = Screen.Rank }) {
                ToplistScreen(
                    topId = toplistId,
                    title = toplistTitle,
                    onOpenPlayer = { screen = Screen.Player },
                )
            }

            Screen.Square -> SwipeBackBox(onBack = { screen = Screen.Home }) {
                SquareScreen(
                    onOpenPlaylist = { id, title ->
                        songListId = id
                        songListTitle = title
                        songListFrom = Screen.Square
                        screen = Screen.SongList
                    },
                )
            }
        }
        }

        // ---- 实况胶囊：全局悬浮常驻屏幕底部（协议页/播放页除外），点击进播放页 ----
        if (now.song != null && screen != Screen.Player) {
            LiveCapsule(
                coverUrl = now.song?.cover300.orEmpty(),
                songName = now.song?.name.orEmpty(),
                onClick = { screen = Screen.Player },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }
    }
}
