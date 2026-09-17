package com.qmusic.wear.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.data.model.Song

/**
 * 高斯模糊封面背景：播放页 / 歌词页共用。
 * 封面按 Crop 裁切铺满整屏（圆屏设备自动被屏幕轮廓裁圆），
 * 叠加圆屏径向暗角 + 纵向压暗渐变保证前景可读；无封面时用品牌渐变兜底。
 * 亮色封面（如高亮黄/白专辑图）自动降饱和并加强压暗，避免整页被背景带偏。
 */
@Composable
fun BlurCoverBackground(
    coverUrl: String,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 44.dp,
    scrim: Float = 0.55f,
) {
    // 亮封面动态加压：平均亮度 > 0.55 时额外降饱和 + 提升 scrim（上限 0.76）
    val coverLum = rememberCoverLuminance(coverUrl)
    val isBright = (coverLum ?: 0f) > 0.55f
    val effScrim = if (isBright) (scrim + 0.16f).coerceAtMost(0.76f) else scrim
    Box(modifier.fillMaxSize()) {
        // 兜底品牌渐变
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF123527),
                            Color(0xFF0B0F14),
                            Color(0xFF0B0F14),
                        ),
                    ),
                ),
        )
        if (coverUrl.isNotEmpty()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (isBright) {
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.55f) })
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurRadius),
            )
            // 圆屏径向暗角：中心透明 -> 边缘加深，突出中心内容（One UI Watch 质感）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = if (isBright) 0.38f else 0.30f),
                            ),
                        ),
                    ),
            )
        }
        // 纵向整体压暗
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = effScrim * 0.6f),
                            Color.Black.copy(alpha = effScrim),
                            Color.Black.copy(alpha = effScrim * 1.15f),
                        ),
                    ),
                ),
        )
    }
}

/**
 * 中央唱片 + 环形进度（One UI Watch 音乐播放器风格）：
 * 外圈为当前播放进度（圆头描边、平滑动画），内部为圆形封面。
 */
@Composable
fun ProgressRingDisc(
    coverUrl: String,
    progress: Float,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 3.5.dp,
    ringGap: Dp = 3.5.dp,
    ringColor: Color = MaterialTheme.colorScheme.primary,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "ring_progress",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = ringWidth.toPx()
            val inset = strokePx / 2
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            // 轨道
            drawArc(
                color = Color.White.copy(alpha = 0.16f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            // 进度（从 12 点方向顺时针）
            if (animated > 0.002f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .padding(ringWidth + ringGap)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape),
        )
    }
}

/**
 * 环绕手表屏幕边缘的播放进度环（QQ 音乐手表版风格）：
 * 顶部留 60° 缺口给时间显示，其余 300° 为进度轨道，
 * 进度自缺口右端顺时针推进，圆头描边 + 平滑动画。
 */
@Composable
fun EdgeProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 3.dp,
    ringColor: Color = MaterialTheme.colorScheme.primary,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "edge_progress",
    )
    Canvas(modifier) {
        val strokePx = ringWidth.toPx()
        val inset = strokePx / 2 + 2.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        // 顶部缺口 60°，轨道从缺口右端顺时针绕一圈
        val start = -90f + 30f
        val sweep = 300f
        drawArc(
            color = Color.White.copy(alpha = 0.14f),
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
        if (animated > 0.002f) {
            drawArc(
                color = ringColor,
                startAngle = start,
                sweepAngle = sweep * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
    }
}

/** 圆形封面 */
@Composable
fun RoundCover(
    url: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = url,
        contentDescription = "歌曲封面",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** 圆角方封面（歌单） */
@Composable
fun SquareCover(
    url: String,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** 列表里的单曲行（播放页同款玻璃卡片风格，播放中有品牌色指示点；可选长按） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    liked: Boolean = false,
    downloaded: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    GlassRow(onClick = onClick, modifier = modifier, playing = playing, onLongClick = onLongClick) {
        RoundCover(url = song.cover300, size = 38.dp)
        Spacer(Modifier.size(9.dp))
        Column(Modifier.weight(1f)) {
            // 官方手表版样式：VIP 歌曲歌名绿色 + VIP 角标
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        playing -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song.vip) {
                    Spacer(Modifier.size(5.dp))
                    Text(
                        "VIP",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.62f),
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = song.singers,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            playing -> Box(
                Modifier
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            liked -> Icon(
                painter = painterResource(R.drawable.ic_heart),
                contentDescription = "已喜欢",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
            downloaded -> Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = "已下载",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

// -------------------------------------------------------------------------
// 统一玻璃拟态控件体系（与播放页副控制风格一致）
// -------------------------------------------------------------------------

/** 玻璃拟态行卡片：半透明圆角底 + 细描边，可点击（可选长按）；全列表行统一外观 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
    playing: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f))
        .border(
            0.5.dp,
            if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.14f),
            shape,
        )
    val interactive = when {
        onLongClick != null && onClick != null ->
            base.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        onClick != null -> base.clickable(onClick = onClick)
        else -> base
    }
    Row(
        modifier = interactive
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 玻璃拟态面板（纯展示容器，播放页卡片同款质感） */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(corner))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        content = content,
    )
}

/** 页面标题：品牌色标题 + 副说明（各列表页统一头部） */
@Composable
fun PageTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null && subtitle.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 数量徽章（如「128首」） */
@Composable
fun CountChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** 歌单头卡片：封面 + 标题 + 数量徽章 + 播放全部（歌单/喜欢/最近页统一头部） */
@Composable
fun PlaylistHeader(
    title: String,
    coverUrl: String,
    songCount: Int,
    onPlayAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onDownloadAll: (() -> Unit)? = null,
    downloadPending: Boolean = false,
) {
    GlassPanel(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareCover(url = coverUrl, size = 50.dp, corner = 12.dp)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                CountChip("${songCount}首")
            }
        }
        // 播放全部 + 下载全部：一行并排（大胶囊 + 小圆钮），下载不再单独占一行
        if ((onPlayAll != null || onDownloadAll != null) && songCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onPlayAll != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .clickable(onClick = onPlayAll)
                            .padding(vertical = 7.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "播放全部",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (onDownloadAll != null) {
                    Spacer(Modifier.size(6.dp))
                    // 下载全部小圆钮；下载中圆钮内显示进度环
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                            .clickable(enabled = !downloadPending, onClick = onDownloadAll),
                    ) {
                        if (downloadPending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_download),
                                contentDescription = "下载全部",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 歌单行：玻璃卡片 + 圆角封面 + 标题/副标题，可选尾部徽标 */
@Composable
fun PlaylistRow(
    name: String,
    coverUrl: String,
    songCount: Int,
    creatorNick: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
) {
    GlassRow(onClick = onClick, modifier = modifier) {
        SquareCover(url = coverUrl, size = 42.dp, corner = 12.dp)
        Spacer(Modifier.size(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${songCount}首 · $creatorNick",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        badge?.invoke()
    }
}

/** 小节标题（左对齐灰色小字，替代默认 ListHeader） */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 3.dp, bottom = 1.dp),
    )
}

/**
 * 右滑返回上一页：二级页内容包裹层。
 * 手指在屏幕任意位置向右拖动累计超过阈值即触发 onBack（与系统边缘返回手势同向，
 * 即页面流中「上一页在左侧」的标准导航模型）；与页内纵向滚动列表互不干扰。
 */
@Composable
fun SwipeBackBox(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    threshold: Float = 90f,
    content: @Composable BoxScope.() -> Unit,
) {
    val latestBack by rememberUpdatedState(onBack)
    val haptics = rememberHaptics()
    var swipeAcc by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeAcc = 0f },
                    onDragEnd = { swipeAcc = 0f },
                    onDragCancel = { swipeAcc = 0f },
                ) { _, dragAmount ->
                    swipeAcc += dragAmount
                    if (swipeAcc > threshold) {
                        // 手指向右滑 → 返回上一页
                        haptics.confirm()
                        latestBack()
                        swipeAcc = 0f
                    }
                }
            },
    ) {
        content()
    }
}

/**
 * 实况胶囊（One UI 8 Watch 风格）：
 * 固定尺寸迷你玻璃胶囊——圆形封面 + 歌名，歌名超长时在胶囊内滚动（胶囊大小不变），点击进播放页。
 * 全局悬浮：由 MainActivity 覆盖在所有页面（协议页除外）底部。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LiveCapsule(
    coverUrl: String,
    songName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            // 固定宽度：不随歌名长短改变大小
            .width(108.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), shape)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 7.dp, top = 4.dp, bottom = 4.dp),
    ) {
        RoundCover(url = coverUrl, size = 20.dp)
        Spacer(Modifier.size(6.dp))
        Text(
            text = songName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            // 超出胶囊宽度时自动跑马灯滚动，未溢出则静止
            modifier = Modifier
                .weight(1f)
                .basicMarquee(),
        )
    }
}

/**
 * 表冠旋转滚动（列表页通用）：给页面根布局挂 rotaryScrollable，
 * 旋转表冠驱动 ScalingLazyColumn 滚动（贴合 One UI 8 Watch 的列表操作习惯）。
 * 用法：在页面根 Box/ScreenScaffold 内容上 `.rotaryList(listState)`。
 */
@Composable
fun Modifier.rotaryList(state: androidx.wear.compose.foundation.lazy.ScalingLazyListState): Modifier {
    val focusRequester = androidx.compose.ui.focus.FocusRequester()
    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
    return then(
        rotaryScrollable(
            behavior = RotaryScrollableDefaults.snapBehavior(state),
            focusRequester = focusRequester,
        ),
    )
}

/**
 * 表冠旋转滚动（普通滚动容器）：适配 LazyColumn / verticalScroll 的 ScrollableState。
 */
@Composable
fun Modifier.rotaryGeneric(state: androidx.compose.foundation.gestures.ScrollableState): Modifier {
    val focusRequester = androidx.compose.ui.focus.FocusRequester()
    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
    return then(
        rotaryScrollable(
            behavior = RotaryScrollableDefaults.behavior(state),
            focusRequester = focusRequester,
        ),
    )
}

// -------------------------------------------------------------------------
// 触觉反馈
// -------------------------------------------------------------------------

/**
 * 统一触觉反馈（走系统 View.performHapticFeedback，自动遵守系统"触摸振动"开关）：
 * - tap：常规按钮点击（播放/切歌/收藏/模式切换等）
 * - tick：表冠音量步进刻度
 * - confirm：手势触发页面切换（滑动返回/换页）
 */
class QmHaptics(private val view: View) {
    fun tap() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
}

@Composable
fun rememberHaptics(): QmHaptics {
    val view = LocalView.current
    return remember { QmHaptics(view) }
}
