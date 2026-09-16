package com.qmusic.wear.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.ui.components.BlurCoverBackground
import com.qmusic.wear.ui.components.SwipeBackBox
import com.qmusic.wear.ui.theme.LocalIsAmbient
import kotlinx.coroutines.delay

@Composable
fun LyricsScreen(
    onBack: () -> Unit = {},
    vm: LyricsViewModel = viewModel(),
) {
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val isAmbient = LocalIsAmbient.current

    val listState = rememberLazyListState()

    // 用户手动拖动歌词后暂停自动跟随 4 秒，避免"浏览历史歌词时被拽回去"
    var autoScrollPaused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) autoScrollPaused = true
        }
    }
    LaunchedEffect(autoScrollPaused) {
        if (autoScrollPaused) {
            delay(4000)
            autoScrollPaused = false
        }
    }
    // 跟随播放位置滚动（+2 补偿头部 spacer 与歌名两个 item）；AOD 与手动暂停期间不跟随
    LaunchedEffect(ui.activeIndex, ui.lines.size, autoScrollPaused, isAmbient) {
        if (!autoScrollPaused && !isAmbient && ui.lines.isNotEmpty() && ui.activeIndex >= 0) {
            val target = (ui.activeIndex + 1).coerceAtLeast(1)
            runCatching { listState.animateScrollToItem(target) }
        }
    }

    LaunchedEffect(now.song?.mid) {
        now.song?.let { vm.loadLyric(it) }
    }

    // 背景在屏幕层级铺满整个圆屏（放进 contentPadding 里会被缩成方形显示不全）；
    // 外包 SwipeBackBox：右滑返回播放页
    SwipeBackBox(onBack = onBack) {
        Box(Modifier.fillMaxSize()) {
            BlurCoverBackground(
                coverUrl = now.song?.cover500.orEmpty(),
                blurRadius = 56.dp,
                // AOD 下整体再压暗一档（降亮度显示静态信息）
                scrim = if (isAmbient) 0.82f else 0.68f,
            )

            ScreenScaffold(
                timeText = { TimeText() },
            ) { contentPadding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                ) {
                    if (ui.lines.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (ui.loading) {
                                CircularProgressIndicator()
                            } else {
                                Text(
                                    "暂无歌词",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            item { Box(Modifier.height(76.dp)) }
                            // 歌曲名作为列表首元素，随滚动移动，不与歌词重合
                            item {
                                Text(
                                    text = now.song?.name.orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 30.dp, vertical = 4.dp),
                                )
                            }
                            itemsIndexed(ui.lines) { index, line ->
                                val active = index == ui.activeIndex
                                // 当前行颜色/字号平滑过渡（替代原来的硬切换）
                                val lineColor by animateColorAsState(
                                    targetValue = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    animationSpec = tween(220),
                                    label = "lyric_color",
                                )
                                val lineSize by animateFloatAsState(
                                    targetValue = if (active) MaterialTheme.typography.titleMedium.fontSize.value
                                    else MaterialTheme.typography.bodyMedium.fontSize.value,
                                    animationSpec = tween(220),
                                    label = "lyric_size",
                                )
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { ServiceLocator.player.seekTo(line.timeMs) },
                                ) {
                                    Text(
                                        text = line.text,
                                        fontSize = lineSize.sp,
                                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                        color = lineColor,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 28.dp, vertical = 6.dp),
                                    )
                                    // 译文：原文行下方灰色小字（无翻译的行不占位）
                                    ui.trans[index]?.let { t ->
                                        Text(
                                            text = t,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 28.dp)
                                                .padding(bottom = 6.dp),
                                        )
                                    }
                                }
                            }
                            item { Box(Modifier.height(140.dp)) }
                        }
                    }
                }
            }
        }
    }
}
