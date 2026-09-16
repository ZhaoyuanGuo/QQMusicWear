package com.qmusic.wear.ui.mine

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
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
import com.qmusic.wear.ui.components.rotaryGeneric
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.PlaylistRow
import com.qmusic.wear.ui.components.RoundCover
import com.qmusic.wear.ui.components.SectionHeader
import com.qmusic.wear.ui.components.SquareCover

/**
 * 「我的」页（每日推荐页右滑出现）：
 * 顶部搜索栏（歌曲/歌手/歌单） + 我的喜欢 / 最近播放 / 我的歌单 / 下载管理 / 设置。
 * 输入即搜索；左滑/右滑或点击「返回推荐」关闭（左滑=返回上一级）。
 */
@Composable
fun MineOverlay(
    onDismiss: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
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

    LaunchedEffect(Unit) { vm.load() }

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
                            swipeAcc < -70f -> onDismiss()
                            swipeAcc > 70f -> onDismiss()
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

            // ---------- 顶部搜索栏 ----------
            BasicTextField(
                value = search.query,
                onValueChange = vm::onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = "搜索",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        if (search.query.isEmpty()) {
                            Text(
                                "搜索歌曲 / 歌手 / 歌单",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )

            Spacer(Modifier.height(10.dp))

            // 搜索历史（空查询时显示，点词直接搜）
            if (search.query.isBlank() && history.isNotEmpty()) {
                SearchHistoryRow(
                    history = history,
                    onPick = { vm.onQueryChange(it) },
                    onClear = { ServiceLocator.searchHistory.clear() },
                )
                Spacer(Modifier.height(8.dp))
            }

            when {
                search.query.isBlank() -> MineTabs(
                    ui = ui,
                    recentCount = recent.size,
                    sessionBad = sessionBad,
                    onOpenLiked = { liked -> onOpenPlaylist(liked.disstid, liked.name) },
                    onOpenRecent = onOpenRecent,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenDownloads = onOpenDownloads,
                    onOpenSettings = onOpenSettings,
                )

                search.searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                else -> SearchResults(
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
                    onSearchSinger = { name -> vm.onQueryChange(name) },
                    onOpenPlaylist = { pl ->
                        onDismiss()
                        onOpenPlaylist(pl.disstid, pl.name)
                    },
                )
            }

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.padding(horizontal = 30.dp),
            ) {
                Text("返回推荐", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** 「我的」页主体：异常提示 → 我的喜欢 → 最近播放 → 我的歌单 → 下载管理 → 设置 */
@Composable
private fun MineTabs(
    ui: MineUiState,
    recentCount: Int,
    sessionBad: Boolean,
    onOpenLiked: (Playlist) -> Unit,
    onOpenRecent: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .rotaryGeneric(scrollState),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 登录状态异常：凭据过期或接口全部拉取失败
        if (sessionBad) {
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

        // 未登录提示：登录入口在设置里
        if (ui.profile == null && !sessionBad) {
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

        // ---- 我的喜欢 ----
        ui.likedPlaylist?.let { liked ->
            GlassRow(onClick = { onOpenLiked(liked) }) {
                SquareCover(url = liked.picUrl, size = 42.dp, corner = 10.dp)
                Spacer(Modifier.size(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "我的喜欢",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
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

        // ---- 听歌统计（本地数据） ----
        val stats = ServiceLocator.playStats.weekStats.collectAsStateWithLifecycle().value
        if (stats.playCount > 0) {
            val hours = stats.totalSec / 3600
            val mins = (stats.totalSec % 3600) / 60
            val durText = when {
                hours > 0 -> "${hours}小时${mins}分钟"
                mins > 0 -> "${mins}分钟"
                else -> "刚刚开始"
            }
            GlassPanel {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_history),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "本周已听 $durText · ${stats.playCount}次",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (stats.topName.isNotEmpty()) {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "最常听：${stats.topName} · ${stats.topSingers}（${stats.topCount}次）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // ---- 最近播放 ----
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

        // ---- 我的歌单 ----
        if (ui.favPlaylists.isNotEmpty()) {
            SectionHeader("我的歌单")
            ui.favPlaylists.forEach { pl ->
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
                    "已下载歌曲 · 长按删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 设置 ----
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
                    "默认音质 · 账号与登录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SearchResults(
    result: SearchResult,
    onPlaySong: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onSearchSinger: (String) -> Unit,
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
    val searchListState = rememberLazyListState()

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
            "歌手" -> LazyColumn(
                state = searchListState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize().rotaryGeneric(searchListState),
            ) {
                items(result.singers) { singer ->
                    GlassRow(onClick = { onSearchSinger(singer.name) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_user),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        SearchResultTexts(title = singer.name, subtitle = "查看歌曲")
                    }
                }
            }

            "歌单" -> LazyColumn(
                state = searchListState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize().rotaryGeneric(searchListState),
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

            else -> LazyColumn(
                state = searchListState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize().rotaryGeneric(searchListState),
            ) {
                item { ListHeader2("播放全部") { onPlayAll(result.songs) } }
                items(result.songs) { song ->
                    GlassRow(onClick = { onPlaySong(song) }) {
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
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
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
                modifier = Modifier.clickable { onClear() },
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            history.take(6).forEach { h ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f))
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
