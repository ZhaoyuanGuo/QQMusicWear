package com.qmusic.wear.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.download.Downloaded
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.SongRow

/**
 * 下载管理页：单击播放（本地文件，离线可用），长按删除（需确认）。
 */
@Composable
fun DownloadsScreen(
    onOpenPlayer: () -> Unit = {},
) {
    val downloads by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    // 待确认删除的歌曲（弹确认框）
    var pendingDelete by remember { mutableStateOf<Downloaded?>(null) }

    val totalMb = downloads.sumOf { it.sizeBytes } / 1024 / 1024
    val freeGb = ServiceLocator.downloads.freeBytes() / 1024 / 1024 / 1024

    ScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                PageTitle(
                    title = "下载管理",
                    subtitle = if (downloads.isEmpty()) null else {
                        "${downloads.size}首 · ${totalMb}MB · 可用${freeGb}GB"
                    },
                )
            }
            if (downloads.isEmpty()) {
                item {
                    Text(
                        text = "暂无下载\n播放页点击下载按钮缓存歌曲",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                items(downloads, key = { it.song.mid }) { d ->
                    SongRow(
                        song = d.song,
                        playing = now.song?.mid == d.song.mid,
                        downloaded = true,
                        onClick = {
                            ServiceLocator.player.playFromList(
                                downloads.map { it.song },
                                d.song.mid,
                            )
                            onOpenPlayer()
                        },
                        onLongClick = { pendingDelete = d },
                    )
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }

    // 删除确认（长按触发，防误删）
    pendingDelete?.let { target ->
        AlertDialog(
            visible = true,
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除下载？") },
            text = { Text("「${target.song.name}」的本地文件将被删除，不再离线可播。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        ServiceLocator.downloads.remove(target.song.mid)
                        pendingDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}
