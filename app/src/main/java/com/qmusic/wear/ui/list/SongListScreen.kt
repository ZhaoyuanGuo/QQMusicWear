package com.qmusic.wear.ui.list

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.qmusic.wear.ui.components.PlaylistHeader
import com.qmusic.wear.ui.components.SongRow
import com.qmusic.wear.ui.components.rotaryList
import kotlinx.coroutines.launch

/** 歌单详情页：支持分页加载（大歌单）、全部下载、长按收藏 */
@Composable
fun SongListScreen(
    disstid: Long,
    title: String,
    onOpenPlayer: () -> Unit = {},
    vm: SongListViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val batch by ServiceLocator.downloads.batch.collectAsStateWithLifecycle()
    val downloadedSet by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
    val likedMids by ServiceLocator.repository.likedMids.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    LaunchedEffect(disstid) { vm.load(disstid) }

    ScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize().rotaryList(listState),
        ) {
            item {
                PlaylistHeader(
                    title = title,
                    coverUrl = ui.playlist?.picUrl.orEmpty(),
                    songCount = ui.songs.size,
                    onPlayAll = if (ui.songs.isEmpty()) null else {
                        { vm.playFrom(ui.songs.first().mid); onOpenPlayer() }
                    },
                )
            }

            // ---- 下载全部（串行队列，已下载自动跳过） ----
            if (ui.songs.isNotEmpty()) {
                item {
                    val pending = batch.running && batch.total > 0
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 30.dp),
                    ) {
                        Button(
                            onClick = { vm.downloadAll() },
                            enabled = !pending,
                        ) {
                            if (pending) {
                                CircularProgressIndicator(
                                    progress = { batch.done.toFloat() / batch.total },
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "下载中 ${batch.done}/${batch.total}",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_download),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.size(8.dp))
                                Text("下载全部", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (ui.loading && ui.songs.isEmpty()) {
                item { CircularProgressIndicator() }
            } else if (ui.songs.isEmpty()) {
                item {
                    Text(
                        text = ui.error.ifEmpty { "歌单为空" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(ui.songs) { song ->
                    SongRow(
                        song = song,
                        playing = now.song?.mid == song.mid,
                        liked = likedMids.contains(song.mid),
                        downloaded = downloadedSet.any { it.song.mid == song.mid },
                        onClick = {
                            // 点播加入队列：追加到当前队列尾部并播放该曲（歌单其余歌曲不动）
                            ServiceLocator.player.enqueueAndPlay(song)
                            onOpenPlayer()
                        },
                        onLongClick = {
                            scope.launch {
                                val like = !likedMids.contains(song.mid)
                                val ok = ServiceLocator.repository.setLiked(song, like)
                                Toast.makeText(
                                    ctx,
                                    if (!ok) "收藏失败（需登录）" else if (like) "已加入我喜欢" else "已取消喜欢",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }

                // ---- 加载更多（大歌单分页） ----
                if (ui.hasMore) {
                    item {
                        if (ui.loadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        } else {
                            Text(
                                text = "加载更多（已载 ${ui.songs.size} 首）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { vm.loadMore() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
