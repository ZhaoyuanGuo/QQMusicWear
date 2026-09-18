package com.qmusic.wear.ui.mine

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.SearchResult
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.ui.components.GlassPanel
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.rememberHaptics
import com.qmusic.wear.ui.components.rotaryList
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.PlaylistRow
import com.qmusic.wear.ui.components.RoundCover
import com.qmusic.wear.ui.components.SectionHeader
import com.qmusic.wear.ui.components.SquareCover

/**
 * 「我的」页（每日推荐页右滑出现）：
 * 顶部搜索栏（歌曲/歌手/歌单） + 我的喜欢 / 最近播放 / 我的歌单 / 下载管理 / 设置。
 * 输入即搜索；左滑/右滑关闭（左滑=返回上一级）。
 */
@Composable
fun MineOverlay(
    onDismiss: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenArtist: (String, String) -> Unit = { _, _ -> },
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
    vm: MineViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val recent by ServiceLocator.historyStore.recentFlow.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val history by ServiceLocator.searchHistory.historyFlow.collectAsStateWithLifecycle()
    val cred by ServiceLocator.credential.collectAsStateWithLifecycle()
    // 凭据到期或接口全挂 → 顶部提示重新登录
    val sessionBad = (cred.isLogged && cred.isExpired) || ui.suspectSession
    var swipeAcc by remember { mutableFloatStateOf(0f) }
    val haptics = rememberHaptics()

    LaunchedEffect(Unit) { vm.load() }

    // 语音搜索：系统语音识别 → 识别结果填入搜索框自动触发搜索
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) {
            vm.onQueryChange(text)
        }
    }
    val launchVoice: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要搜索的歌曲、歌手或歌单")
        }
        // 设备无语音识别组件时静默忽略
        runCatching { voiceLauncher.launch(intent) }
    }
    // 输入态标记：区分"正在打字"与"点历史词/语音结果"，用于切到结果页时恢复焦点与键盘
    var typing by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.985f))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        swipeAcc += amount
                    },
                    onDragEnd = {
                        // 左滑：返回上一级（推荐页）；右滑：同样关闭
                        when {
                            swipeAcc < -70f -> {
                                haptics.confirm()
                                onDismiss()
                            }
                            swipeAcc > 70f -> {
                                haptics.confirm()
                                onDismiss()
                            }
                        }
                        swipeAcc = 0f
                    },
                )
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            PageTitle("我的")
            Spacer(Modifier.height(8.dp))

            when {
                // 主列表：搜索栏与搜索历史放进列表，随内容滚动（往下滑即跟随上移，不再悬浮）
                search.query.isBlank() -> Box(Modifier.weight(1f)) {
                    MineTabs(
                        ui = ui,
                        recentCount = recent.size,
                        sessionBad = sessionBad,
                        query = search.query,
                        onQueryChange = { typing = true; vm.onQueryChange(it) },
                        onVoiceSearch = launchVoice,
                        history = history,
                        onPickHistory = { typing = false; vm.onQueryChange(it) },
                        onOpenLiked = { liked -> onOpenPlaylist(liked.disstid, liked.name) },
                        onOpenRecent = onOpenRecent,
                        onOpenPlaylist = onOpenPlaylist,
                        onOpenDownloads = onOpenDownloads,
                        onOpenSettings = onOpenSettings,
                    )
                }

                search.searching -> Box(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize()) {
                        // 结果页搜索栏固定顶部，便于修改关键词
                        MineSearchField(
                            query = search.query,
                            onQueryChange = { typing = true; vm.onQueryChange(it) },
                            onVoiceSearch = launchVoice,
                            modifier = Modifier.focusRequester(fieldFocus),
                        )
                        LaunchedEffect(Unit) {
                            // 从主列表打字切换过来时恢复焦点与键盘（搜索栏换了挂载点）
                            if (typing) {
                                fieldFocus.requestFocus()
                                keyboard?.show()
                                typing = false
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }

                else -> Box(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize()) {
                        MineSearchField(
                            query = search.query,
                            onQueryChange = { typing = true; vm.onQueryChange(it) },
                            onVoiceSearch = launchVoice,
                            modifier = Modifier.focusRequester(fieldFocus),
                        )
                        LaunchedEffect(Unit) {
                            if (typing) {
                                fieldFocus.requestFocus()
                                keyboard?.show()
                                typing = false
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        SearchResults(
                            result = search.result ?: SearchResult(),
                            onPlaySong = { song ->
                                // 点播加入队列：追加到当前队列尾部并播放该曲（其余歌曲不动）
                                ServiceLocator.player.enqueueAndPlay(song)
                                onDismiss()
                                onOpenPlayer()
                            },
                            onPlayAll = { songs ->
                                if (songs.isNotEmpty()) {
                                    vm.playFrom(songs, songs.first().mid)
                                    onDismiss()
                                    onOpenPlayer()
                                }
                            },
                            onOpenArtist = { singer ->
                                onDismiss()
                                onOpenArtist(singer.mid, singer.name)
                            },
                            onOpenAlbumOfSong = { song ->
                                if (song.albumMid.isNotEmpty()) {
                                    onDismiss()
                                    onOpenAlbum(song.albumMid, song.albumName)
                                }
                            },
                            onOpenPlaylist = { pl ->
                                onDismiss()
                                onOpenPlaylist(pl.disstid, pl.name)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 搜索栏（歌曲/歌手/歌单）+ 右侧语音入口：
 * 麦克风加大为深色圆底按钮（此前灰色裸图标过小、难以发现和点中）。
 */
@Composable
private fun MineSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                if (query.isEmpty()) {
                    Text(
                        "搜索歌曲/歌手/歌单",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 文本编辑区占剩余宽度，麦克风固定在右侧
                Box(Modifier.weight(1f)) { inner() }
                // 语音搜索：34dp 深色圆底 + 18dp 图标（命中区与视觉一致）
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.40f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                        .clickable(onClick = onVoiceSearch),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = "语音搜索",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
}

/** 「我的」页主体：搜索栏/历史 → 异常提示 → 我的喜欢 → 最近播放 → 我的歌单 → 下载管理 → 设置 */
@Composable
private fun MineTabs(
    ui: MineUiState,
    recentCount: Int,
    sessionBad: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    history: List<String>,
    onPickHistory: (String) -> Unit,
    onOpenLiked: (Playlist) -> Unit,
    onOpenRecent: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 听歌统计（本地数据）
    val stats = ServiceLocator.playStats.weekStats.collectAsStateWithLifecycle().value
    // ScalingLazyColumn：圆屏自适应缩放 + 居中锚点，与首页/队列页滚动体验一致
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        Modifier
            .fillMaxWidth()
            .rotaryList(listState),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 搜索栏/搜索历史随列表滚动：往下滑即跟随上移，不再悬浮占位
        item {
            MineSearchField(query = query, onQueryChange = onQueryChange, onVoiceSearch = onVoiceSearch)
        }
        if (history.isNotEmpty()) {
            item {
                SearchHistoryRow(
                    history = history,
                    onPick = onPickHistory,
                    onClear = { ServiceLocator.searchHistory.clear() },
                )
            }
        }
        // 登录状态异常：凭据过期或接口全部拉取失败
        if (sessionBad) {
            item {
                GlassRow(onClick = onOpenSettings) {
                    Icon(
                        painter = painterResource(R.drawable.ic_user),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "登录可能已过期",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "点击去设置重新扫码登录",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 未登录提示：登录入口在设置里
        if (ui.profile == null && !sessionBad) {
            item {
                GlassRow(onClick = onOpenSettings) {
                    Icon(
                        painter = painterResource(R.drawable.ic_user),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "未登录 · 去设置登录",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 我的喜欢 ----
        ui.likedPlaylist?.let { liked ->
            item {
                GlassRow(onClick = { onOpenLiked(liked) }) {
                    SquareCover(url = liked.picUrl, size = 42.dp, corner = 12.dp)
                    Spacer(Modifier.size(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "我的喜欢",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = "${liked.songCount}首",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_heart),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        // ---- 听歌统计（本地数据） ----
        if (stats.playCount > 0) {
            item {
                val hours = stats.totalSec / 3600
                val mins = (stats.totalSec % 3600) / 60
                val durText = when {
                    hours > 0 -> "${hours}小时${mins}分钟"
                    mins > 0 -> "${mins}分钟"
                    else -> "刚刚开始"
                }
                GlassPanel {
                    Column(Modifier.fillMaxWidth()) {
                        // 拆行显示避免长文案在圆屏折行：标题行 + 数值行 + 最常听
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_history),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "本周已听",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Spacer(Modifier.size(3.dp))
                        Text(
                            "$durText · ${stats.playCount}次",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 27.dp),
                        )
                        if (stats.topName.isNotEmpty()) {
                            Spacer(Modifier.size(2.dp))
                            Text(
                                "最常听：${stats.topName} · ${stats.topSingers}（${stats.topCount}次）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 27.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---- 最近播放 ----
        item {
            GlassRow(onClick = onOpenRecent) {
                Icon(
                    painter = painterResource(R.drawable.ic_history),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "最近播放",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = if (recentCount == 0) "本地播放记录" else "最近播放 ${recentCount}首",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 我的歌单 ----
        if (ui.favPlaylists.isNotEmpty()) {
            item { SectionHeader("我的歌单") }
            items(ui.favPlaylists) { pl ->
                PlaylistRow(
                    name = pl.name,
                    coverUrl = pl.picUrl,
                    songCount = pl.songCount,
                    creatorNick = pl.creatorNick,
                    onClick = { onOpenPlaylist(pl.disstid, pl.name) },
                )
            }
        }

        // ---- 下载管理 ----
        item {
            GlassRow(onClick = onOpenDownloads) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "下载管理",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "长按删除歌曲",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 设置 ----
        item {
            GlassRow(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "音质与账号",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun SearchResults(
    result: SearchResult,
    onPlaySong: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onOpenArtist: (com.qmusic.wear.data.model.Singer) -> Unit,
    onOpenAlbumOfSong: (Song) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
) {
    val empty = result.songs.isEmpty() && result.singers.isEmpty() && result.playlists.isEmpty()
    if (empty) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "没有找到相关内容",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // 固定三选项卡（歌曲/歌手/歌单），置于搜索栏与结果之间；无结果的分区显示空态
    data class Tab(val label: String, val count: Int)
    val tabs = listOf(
        Tab("歌曲", result.songs.size),
        Tab("歌手", result.singers.size),
        Tab("歌单", result.playlists.size),
    )
    var sel by remember(result) { mutableStateOf(0) }
    val selIndex = if (sel >= tabs.size) 0 else sel
    val searchListState = rememberScalingLazyListState()

    Column(Modifier.fillMaxSize()) {
        // 分区选择条
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            tabs.forEachIndexed { i, tab ->
                ResultTab(
                    label = tab.label,
                    count = tab.count,
                    selected = i == selIndex,
                    onClick = { sel = i },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        val current = tabs[selIndex]
        if (current.count == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "没有找到相关${current.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        when (current.label) {
            "歌手" -> ScalingLazyColumn(
                state = searchListState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize().rotaryList(searchListState),
            ) {
                items(result.singers) { singer ->
                    GlassRow(onClick = { onOpenArtist(singer) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_user),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        SearchResultTexts(title = singer.name, subtitle = "查看歌曲")
                    }
                }
            }

            "歌单" -> ScalingLazyColumn(
                state = searchListState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize().rotaryList(searchListState),
            ) {
                items(result.playlists) { pl ->
                    GlassRow(onClick = { onOpenPlaylist(pl) }) {
                        RoundCover(url = pl.picUrl, size = 30.dp)
                        Spacer(Modifier.size(8.dp))
                        SearchResultTexts(
                            title = pl.name,
                            subtitle = "${pl.songCount}首 · ${pl.creatorNick}",
                        )
                    }
                }
            }

            else -> ScalingLazyColumn(
                state = searchListState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize().rotaryList(searchListState),
            ) {
                item { ListHeader2("播放全部") { onPlayAll(result.songs) } }
                items(result.songs) { song ->
                    GlassRow(
                        onClick = { onPlaySong(song) },
                        onLongClick = { onOpenAlbumOfSong(song) },
                    ) {
                        RoundCover(url = song.cover300, size = 30.dp)
                        Spacer(Modifier.size(8.dp))
                        SearchResultTexts(title = song.name, subtitle = song.singers)
                    }
                }
            }
        }
    }
}

/** 搜索分区切换片（选中=主题色底，未选中=玻璃底） */
@Composable
private fun ResultTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        colors = if (selected) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = modifier,
    ) {
        Text(
            if (count > 0) "$label $count" else label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SearchResultTexts(
    title: String,
    subtitle: String,
) {
    Column(Modifier.weight(1f)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 搜索历史：标题行（含清除）+ 换行排布的关键词胶囊 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchHistoryRow(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "最近搜索",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "清除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 外扩命中区（视觉不变）
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClear() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            history.take(6).forEach { h ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f))
                        .clickable { onPick(h) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        h,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListHeader2(title: String, playAll: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        if (playAll != null) {
            Text(
                "播放全部",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { playAll() },
            )
        }
    }
}
