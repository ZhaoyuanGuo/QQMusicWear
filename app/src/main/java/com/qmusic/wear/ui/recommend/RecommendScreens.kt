package com.qmusic.wear.ui.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.api.MusicHallShelf
import com.qmusic.wear.data.api.ToplistItem
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.PlaylistRow
import com.qmusic.wear.ui.components.RoundCover
import com.qmusic.wear.ui.components.SectionHeader
import com.qmusic.wear.ui.components.SongRow
import com.qmusic.wear.ui.components.SquareCover
import kotlinx.coroutines.launch

/**
 * 推荐内容页（借鉴手机版 QQ 音乐首页的推荐板块，圆屏适配）：
 * - RankScreen：排行榜选择（巅峰榜/飙升榜/热歌等，官方 v8 接口）
 * - ToplistScreen：榜单歌曲列表（与歌单列表同款交互）
 * - SquareScreen：歌单广场（分类标签横向切换 + 推荐歌单列表）
 */

// ---------------------------------------------------------------------
// 排行榜
// ---------------------------------------------------------------------

@Composable
fun RankScreen(
    onOpenToplist: (Int, String) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    var loading by remember { mutableStateOf(true) }
    var lists by remember { mutableStateOf<List<ToplistItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        lists = ServiceLocator.repository.toplists()
        loading = false
    }

    ScreenScaffold(scrollState = listState, timeText = { TimeText() }) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 30.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { PageTitle("排行榜") }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (lists.isEmpty()) {
                item {
                    Text(
                        "榜单加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(lists) { t ->
                    GlassRow(onClick = { onOpenToplist(t.topId, t.title) }) {
                        SquareCover(url = t.picUrl, size = 42.dp, corner = 10.dp)
                        Spacer(Modifier.size(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.title,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                t.updateInfo.ifEmpty { "查看榜单" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.ic_qm_play),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// 榜单歌曲列表
// ---------------------------------------------------------------------

@Composable
fun ToplistScreen(
    topId: Int,
    title: String,
    onOpenPlayer: () -> Unit,
) {
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val likedMids by ServiceLocator.repository.likedMids.collectAsStateWithLifecycle()
    val downloadedSet by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    var loading by remember { mutableStateOf(true) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }

    LaunchedEffect(topId) {
        songs = ServiceLocator.repository.toplistSongs(topId)
        loading = false
    }

    fun playAll() {
        if (songs.isNotEmpty()) {
            ServiceLocator.player.playFromList(songs, songs.first().mid)
            onOpenPlayer()
        }
    }

    ScreenScaffold(scrollState = listState, timeText = { TimeText() }) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 30.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                PageTitle(title)
                if (songs.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Button(onClick = { playAll() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_qm_play),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("播放全部", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (songs.isEmpty()) {
                item {
                    Text(
                        "榜单加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(songs) { song ->
                    SongRow(
                        song = song,
                        playing = now.song?.mid == song.mid,
                        liked = likedMids.contains(song.mid),
                        downloaded = downloadedSet.any { it.song.mid == song.mid },
                        onClick = {
                            ServiceLocator.player.playFromList(songs, song.mid)
                            onOpenPlayer()
                        },
                        onLongClick = {
                            scope.launch {
                                val like = !likedMids.contains(song.mid)
                                val ok = ServiceLocator.repository.setLiked(song, like)
                                android.widget.Toast.makeText(
                                    context,
                                    if (!ok) "收藏失败（需登录）" else if (like) "已加入我喜欢" else "已取消喜欢",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// 歌单广场（官方音乐厅首页 feed：栏目分组歌单卡）
// ---------------------------------------------------------------------

@Composable
fun SquareScreen(
    onOpenPlaylist: (Long, String) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    var loading by remember { mutableStateOf(true) }
    var shelves by remember { mutableStateOf<List<MusicHallShelf>>(emptyList()) }

    LaunchedEffect(Unit) {
        shelves = ServiceLocator.repository.musicHallShelves()
        loading = false
    }

    ScreenScaffold(scrollState = listState, timeText = { TimeText() }) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 30.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { PageTitle("歌单广场") }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (shelves.isEmpty()) {
                item {
                    Text(
                        "歌单加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                for (shelf in shelves) {
                    item {
                        SectionHeader(shelf.title)
                        Spacer(Modifier.height(2.dp))
                    }
                    items(shelf.playlists) { pl ->
                        PlaylistRow(
                            name = pl.name,
                            coverUrl = pl.picUrl,
                            songCount = pl.songCount,
                            creatorNick = pl.creatorNick,
                            onClick = { onOpenPlaylist(pl.disstid, pl.name) },
                        )
                    }
                }
            }
        }
    }
}
