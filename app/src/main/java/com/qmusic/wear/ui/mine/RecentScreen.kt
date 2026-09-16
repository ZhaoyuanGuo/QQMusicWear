package com.qmusic.wear.ui.mine

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.SongRow
import com.qmusic.wear.ui.components.rotaryList
import kotlinx.coroutines.launch

/** 二级界面：最近播放（本地记录） */
@Composable
fun RecentScreen(
    onOpenPlayer: () -> Unit = {},
) {
    val recent by ServiceLocator.historyStore.recentFlow.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val downloadedSet by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
    val likedMids by ServiceLocator.repository.likedMids.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

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
                PageTitle(
                    title = stringResource(R.string.mine_recent),
                    subtitle = if (recent.isEmpty()) null else "${recent.size}首",
                )
            }
            if (recent.isEmpty()) {
                item {
                    Text(
                        text = "暂无播放记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(recent) { song ->
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
                                Toast.makeText(
                                    ctx,
                                    if (!ok) "收藏失败（需登录）" else if (like) "已加入我喜欢" else "已取消喜欢",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
                item { Box(Modifier.height(40.dp)) }
            }
        }
    }
}
