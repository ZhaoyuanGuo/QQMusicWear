package com.qmusic.wear.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import coil3.compose.AsyncImage

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