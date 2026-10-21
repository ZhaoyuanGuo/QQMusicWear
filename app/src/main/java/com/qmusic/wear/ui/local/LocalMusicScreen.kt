package com.qmusic.wear.ui.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.local.LocalTrack
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.PlayAllChip
import com.qmusic.wear.ui.components.QmScreenScaffold
import com.qmusic.wear.ui.components.SongRow
import com.qmusic.wear.ui.components.edgeScalingParams
import com.qmusic.wear.ui.components.qmAutoCentering
import com.qmusic.wear.ui.components.qmRotarySnap
import kotlinx.coroutines.launch

/**
 * 本地音乐页：列出本设备可播放的音频——系统媒体库中被索引的共享文件（含其它音乐
 * App / 用户放进 /sdcard/Music 等的歌曲，需音频读取权限），并合并应用专属外部目录。
 *
 * 播放全部 / 单击从该曲开播（整表替换队列，全本地离线可播），长按删除。
 * 进入时若无缓存自动扫描一次；「重新扫描」可随时刷新。
 */
@Composable
fun LocalMusicScreen(
    onOpenPlayer: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // 先装载缓存（幂等：空时从磁盘读回并写入 Flow），再订阅扫描结果 Flow 刷新
    remember { ServiceLocator.localMusic.currentTracks() }
    val tracks by ServiceLocator.localMusic.tracksFlow.collectAsStateWithLifecycle()
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()

    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(Pair(0, 0)) }
    var hasPerm by remember { mutableStateOf(ServiceLocator.localMusic.hasAudioPermission()) }
    // 首次进入且无缓存时自动扫描一次
    var autoScanned by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<LocalTrack?>(null) }

    fun startScan() {
        if (scanning) return
        scanning = true
        progress = 0 to 0
        scope.launch {
            ServiceLocator.localMusic.rescan { done, total -> progress = done to total }
            scanning = false
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPerm = granted
        startScan()
    }

    LaunchedEffect(Unit) {
        if (!autoScanned) {
            autoScanned = true
            if (tracks.isEmpty()) startScan()
        }
    }

    val totalMb = tracks.sumOf { it.sizeBytes } / 1024 / 1024
    val songs = tracks.map { it.song }
    val uris = tracks.map { it.uri }

    val listState = rememberScalingLazyListState()
    QmScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        ScalingLazyColumn(
            scalingParams = edgeScalingParams(),
            state = listState,
            rotaryScrollableBehavior = qmRotarySnap(listState),
            contentPadding = contentPadding,
            autoCentering = qmAutoCentering(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                PageTitle(
                    title = "本地音乐",
                    subtitle = if (tracks.isEmpty()) null else "${tracks.size}首 · ${totalMb}MB",
                )
            }

            if (scanning) {
                item {
                    Text(
                        text = if (progress.second > 0) "扫描中 ${progress.first}/${progress.second}" else "扫描中…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 未授予音频权限：提示授权以显示其它 App / 共享目录里的歌曲
            if (!hasPerm && !scanning) {
                item {
                    Text(
                        text = "授予音频权限后，才能读出其它音乐 App 下载到共享目录（如 Music/Download）的歌曲",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                item {
                    OutlinedButton(onClick = { permLauncher.launch(ServiceLocator.localMusic.audioPermission) }) {
                        Text("授予权限")
                    }
                }
            }

            if (tracks.isEmpty() && !scanning) {
                item {
                    Text(
                        text = "未发现可播放的本地音乐\n\n可将 MP3/FLAC/M4A/OGG 等放入其它 App 的共享下载目录或下方目录后重新扫描：\n${ServiceLocator.localMusic.directoryPath()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                item {
                    Button(onClick = { startScan() }) { Text("重新扫描") }
                }
            } else if (tracks.isNotEmpty()) {
                item {
                    PlayAllChip(
                        onClick = {
                            ServiceLocator.player.playLocal(songs, uris, startIndex = 0)
                            onOpenPlayer()
                        },
                    )
                }
                itemsIndexed(tracks, key = { _, t -> t.song.mid }) { index, t ->
                    SongRow(
                        song = t.song,
                        playing = now.song?.mid == t.song.mid,
                        downloaded = true,
                        onClick = {
                            // 整表替换为本地列表，从点击曲目标位置开播（离线可播）
                            ServiceLocator.player.playLocal(songs, uris, startIndex = index)
                            onOpenPlayer()
                        },
                        onLongClick = { pendingDelete = t },
                    )
                }
                item {
                    Button(onClick = { startScan() }) { Text("重新扫描") }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            visible = true,
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除本地文件？") },
            text = { Text("「${target.song.name}」的本地音频文件将被删除，此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        ServiceLocator.localMusic.remove(target)
                        pendingDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}
