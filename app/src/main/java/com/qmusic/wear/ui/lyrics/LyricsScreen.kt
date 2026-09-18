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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
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
import com.qmusic.wear.ui.theme.LocalLowPerf
import kotlinx.coroutines.delay

@Composable
fun LyricsScreen(
    onBack: () -> Unit = {},
    vm: LyricsViewModel = viewModel(),
) {
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val isAmbient = LocalIsAmbient.current
    val lowPerf = LocalLowPerf.current

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
                blurRadius = 20.dp,
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
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f),
                                    animationSpec = tween(220),
                                    label = "lyric_color",
                                )
                                val lineSize by animateFloatAsState(
                                    targetValue = if (active) 18f else 13f,
                                    animationSpec = tween(220),
                                    label = "lyric_size",
                                )
                                val karaoke = active && !isAmbient && !lowPerf
                                // 卡拉OK填充（仅活动行）按帧驱动：positionNow 为框架插值的
                                // 实时位置（state 轮询 500ms 会产生阶梯感），逐帧重算行内进度
                                var fill by remember(line.timeMs) { mutableStateOf(0f) }
                                LaunchedEffect(karaoke, line.timeMs) {
                                    if (!karaoke) {
                                        fill = 0f
                                        return@LaunchedEffect
                                    }
                                    val endMs = ui.lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 1)
                                    while (true) {
                                        withFrameNanos {
                                            val pos = ServiceLocator.player.positionNow()
                                            fill = if (endMs > line.timeMs) {
                                                ((pos - line.timeMs).toFloat() / (endMs - line.timeMs))
                                                    .coerceIn(0f, 1f)
                                            } else 1f
                                        }
                                    }
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { ServiceLocator.player.seekTo(line.timeMs) },
                                ) {
                                    if (karaoke) {
                                        // 卡拉OK模式：暗色底字 + 亮色按行接力覆盖
                                        KaraokeText(
                                            text = line.text,
                                            fill = fill,
                                            fillColor = lineColor,
                                            baseColor = lineColor.copy(alpha = 0.32f),
                                            fontSize = lineSize,
                                            shadowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                        )
                                    } else {
                                        // AOD/低配/非活动行：纯色（省 GPU，AOD 更省电）
                                        Text(
                                            text = line.text,
                                            fontSize = lineSize.sp,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            textAlign = TextAlign.Center,
                                            style = TextStyle(
                                                color = lineColor,
                                                shadow = if (active) Shadow(
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                                    blurRadius = 12f,
                                                    offset = Offset.Zero,
                                                ) else null,
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 28.dp, vertical = 6.dp),
                                        )
                                    }
                                    // 罗马音：原文行下方小字（无罗马音的行不占位）
                                    ui.roma[index]?.let { r ->
                                        Text(
                                            text = r,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 28.dp),
                                        )
                                    }
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

                    // 顶部/底部渐隐边缘已上移至屏幕层级（见下方），保证全宽无端点硬边
                }
            }

            // 顶部/底部渐隐边缘（屏幕级、全宽、从屏幕边缘起渐变）：
            // 放在 contentPadding 内会因圆屏两侧留空 + 顶部硬起点形成「内缩暗卡」；
            // 这里从 y=0 / y=bottom 开始渐变且铺满全宽，所有过渡都是渐变、无任何硬边
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                        ),
                    ),
            )
        }
    }
}

/**
 * 卡拉OK逐行填充文本。
 *
 * 不能用整段 Brush.horizontalGradient：英文长句换行后每个视觉行都会按同一
 * 横向比例同时填色（用户看到的「多行同时变绿」）。这里改为按视觉行宽度
 * 接力分配填充预算（第一行填满才开始填第二行，行内从左往右逐字推进）：
 * 底层暗色完整文本 + 上层亮色文本按各行区域 clip 后显示。
 */
@Composable
private fun KaraokeText(
    text: String,
    fill: Float,
    fillColor: Color,
    baseColor: Color,
    fontSize: Float,
    shadowColor: Color,
) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val style = TextStyle(
        shadow = Shadow(color = shadowColor, blurRadius = 12f, offset = Offset.Zero),
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 6.dp),
    ) {
        // 底层：未填充部分（暗色）
        Text(
            text = text,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = style.copy(color = baseColor),
            modifier = Modifier.fillMaxWidth(),
        )
        // 覆盖层：已填充部分（亮色，按行区域裁剪）
        Text(
            text = text,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = style.copy(color = fillColor),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val l = layout ?: return@drawWithContent // 首帧未布局时先不画，避免整行闪绿
                    val widths = (0 until l.lineCount)
                        .map { l.getLineRight(it) - l.getLineLeft(it) }
                    val budget = fill.coerceIn(0f, 1f) * widths.sum()
                    val path = Path()
                    var acc = 0f
                    for (i in widths.indices) {
                        val w = widths[i]
                        val remain = budget - acc
                        acc += w
                        if (remain <= 0f) break
                        val top = l.getLineTop(i)
                        val bottom = l.getLineBottom(i)
                        if (remain >= w) {
                            // 该行已填满：整行全宽裁剪
                            path.addRect(Rect(0f, top, size.width, bottom))
                        } else {
                            // 该行填充中：从该行左缘按剩余预算推进
                            path.addRect(Rect(l.getLineLeft(i), top, l.getLineLeft(i) + remain, bottom))
                        }
                    }
                    if (path.isEmpty) return@drawWithContent
                    clipPath(path) { this@drawWithContent.drawContent() }
                },
        )
    }
}
