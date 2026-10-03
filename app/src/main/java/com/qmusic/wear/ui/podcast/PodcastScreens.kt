package com.qmusic.wear.ui.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.DjProgram
import com.qmusic.wear.data.model.RadioStation
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.QmScreenScaffold
import com.qmusic.wear.ui.components.SquareCover
import com.qmusic.wear.ui.components.edgeContentPadding
import com.qmusic.wear.ui.components.edgeScalingParams
import com.qmusic.wear.ui.components.qmAutoCentering
import com.qmusic.wear.ui.components.qmRotarySnap

/**
 * 播客 / 电台（网易云源提供；QQ/酷狗源无数据时显示空态）。
 * - PodcastScreen：热门电台列表
 * - DjProgramScreen：某电台的节目列表（节目主音频可直接播放）
 */

@Composable
fun PodcastScreen(onOpenRadio: (Long, String) -> Unit) {
    val listState = rememberScalingLazyListState()
    var loading by remember { mutableStateOf(true) }
    var radios by remember { mutableStateOf<List<RadioStation>>(emptyList()) }

    LaunchedEffect(Unit) {
        radios = ServiceLocator.repository.djRadios()
        loading = false
    }

    QmScreenScaffold(scrollState = listState, timeText = { TimeText() }) { contentPadding ->
        ScalingLazyColumn(
            scalingParams = edgeScalingParams(),
            state = listState,
            rotaryScrollableBehavior = qmRotarySnap(listState),
            contentPadding = edgeContentPadding(contentPadding, bottomExtra = 30.dp),
            autoCentering = qmAutoCentering(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { PageTitle("播客电台") }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (radios.isEmpty()) {
                item {
                    Text(
                        "当前音乐源暂不支持播客/电台",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(radios) { r ->
                    GlassRow(onClick = { onOpenRadio(r.id, r.name) }) {
                        SquareCover(url = r.picUrl, size = 42.dp, corner = 10.dp)
                        Spacer(Modifier.size(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                r.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = r.djName.ifEmpty { r.desc.ifEmpty { "${r.programCount} 期" } },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DjProgramScreen(
    radioId: Long,
    radioName: String,
    onOpenPlayer: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    var loading by remember { mutableStateOf(true) }
    var programs by remember { mutableStateOf<List<DjProgram>>(emptyList()) }

    LaunchedEffect(radioId) {
        programs = ServiceLocator.repository.djPrograms(radioId)
        loading = false
    }

    fun playFrom(program: DjProgram) {
        val songs = programs.mapNotNull { it.song }
        val mid = program.song?.mid ?: return
        if (songs.isEmpty()) return
        ServiceLocator.player.playFromList(songs, mid)
        onOpenPlayer()
    }

    QmScreenScaffold(scrollState = listState, timeText = { TimeText() }) { contentPadding ->
        ScalingLazyColumn(
            scalingParams = edgeScalingParams(),
            state = listState,
            rotaryScrollableBehavior = qmRotarySnap(listState),
            contentPadding = edgeContentPadding(contentPadding, bottomExtra = 30.dp),
            autoCentering = qmAutoCentering(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { PageTitle(radioName.ifEmpty { "电台节目" }) }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (programs.isEmpty()) {
                item {
                    Text(
                        "节目加载失败或暂不支持",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(programs) { p ->
                    GlassRow(onClick = { playFrom(p) }) {
                        SquareCover(url = p.coverUrl, size = 42.dp, corner = 10.dp)
                        Spacer(Modifier.size(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (p.song != null) "点击播放" else "暂无可播放音频",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}