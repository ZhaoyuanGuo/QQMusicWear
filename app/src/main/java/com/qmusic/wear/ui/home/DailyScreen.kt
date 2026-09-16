package com.qmusic.wear.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.SongRow
import com.qmusic.wear.ui.components.rotaryList
import kotlinx.coroutines.launch

/**
 * 每日推荐列表页（首页 Hero 卡的二级页）：
 * 复用 HomeViewModel（Activity 作用域共享实例，首页已加载则不重复请求）。
 */
@Composable
fun DailyScreen(
    onOpenPlayer: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val likedMids by ServiceLocator.repository.likedMids.collectAsStateWithLifecycle()
    val downloadedSet by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()

    LaunchedEffect(Unit) {
        if (ui.songs.isEmpty() && !ui.loading) vm.load()
    }

    ScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 60.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize().rotaryList(listState),
        ) {
            item { PageTitle("每日30首") }

            if (ui.loading && ui.songs.isEmpty()) {
                item { CircularProgressIndicator() }
            } else if (ui.songs.isEmpty()) {
                item {
                    Text(
                        text = "加载失败，下拉重试",
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
                            // 点播加入队列：追加到当前队列尾部并播放该曲（其余歌曲不动）
                            ServiceLocator.player.enqueueAndPlay(song)
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
