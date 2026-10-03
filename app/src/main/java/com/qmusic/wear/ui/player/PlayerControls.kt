package com.qmusic.wear.ui.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.ui.components.rememberHaptics
import com.qmusic.wear.ui.theme.LocalLowPerf

/** 裸图标按钮（深色圆底 + 白色图标）；命中区扩到42dp提升手指容错 */
@Composable
internal fun QmIconButton(
    resId: Int,
    contentDescription: String,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(42.dp)
            // 圆形裁剪：ripple 沿圆形扩散，不出现方形灰块
            .clip(CircleShape)
            .clickable {
                haptics.tap()
                onClick()
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(iconSize + 16.dp)
                .clip(CircleShape)
                // 深色圆底：亮色封面上依然清晰（白色半透明底会被亮背景吞掉）
                .background(Color.Black.copy(alpha = 0.40f))
                .border(0.5.dp, Color.White.copy(alpha = 0.16f), CircleShape),
        ) {
            Icon(
                painter = painterResource(resId),
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** 大号播放/暂停键：品牌绿圆底 + 白色图标 + 呼吸光晕 + 弹性缩放（QQ 音乐手机版） */
@Composable
internal fun QmPlayPauseButton(playing: Boolean, isAmbient: Boolean) {
    val haptics = rememberHaptics()
    val scale by animateFloatAsState(
        targetValue = if (playing) 1f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "play_scale",
    )
    // 播放时光晕呼吸起伏；暂停/AOD 静止为低亮度；低配置设备模式取消呼吸动画（静止低亮度）
    val lowPerf = LocalLowPerf.current
    val glowAlpha: Float = if (playing && !isAmbient && !lowPerf) {
        val pulse by rememberInfiniteTransition(label = "glow").animateFloat(
            initialValue = 0.16f,
            targetValue = 0.30f,
            animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
            label = "glow_pulse",
        )
        pulse
    } else 0.13f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        // 深色底盘：亮色封面上给品牌绿键提供分离度
        Box(
            Modifier
                .size(60.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
        )
        // 外圈光晕
        Box(
            Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha), CircleShape),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    haptics.tap()
                    ServiceLocator.player.togglePlayPause()
                },
        ) {
            // 线条形变图标：播放三角 ↔ 暂停双杠，按路径逐点插值流动（非缩放/淡入淡出）
            PlayPauseMorphIcon(playing = playing)
        }
    }
}

/**
 * 线条形变图标：播放三角 ↔ 暂停双杠。
 * 原理：两个图标都拆成结构相同的两个四边形路径（各 4 个角点），
 * 形变时逐点插值——双杠的内缘流动倾斜成三角的两条斜边，外缘收拢汇成三角尖。
 */
@Composable
private fun PlayPauseMorphIcon(playing: Boolean) {
    // 形变进度：0 = 播放三角，1 = 暂停双杠（轻微回弹让线条有"流动感"）
    val morph by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "play_morph",
    )
    Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension / 24f
        fun pt(v: Pair<Float, Float>) = Offset(v.first * s, v.second * s)

        // 24 视口下的角点（顺时针：左上 → 右上 → 右下 → 左下）
        // 播放三角（拆左右两半，保证与双杠同为四边形可插值；右半右侧两点重合于尖角）
        val playL = listOf(Pair(7f, 5f), Pair(13f, 8.5f), Pair(13f, 15.5f), Pair(7f, 19f))
        val playR = listOf(Pair(13f, 8.5f), Pair(19f, 12f), Pair(19f, 12f), Pair(13f, 15.5f))
        // 暂停双杠
        val pauseL = listOf(Pair(7.4f, 5f), Pair(11f, 5f), Pair(11f, 19f), Pair(7.4f, 19f))
        val pauseR = listOf(Pair(13f, 5f), Pair(16.6f, 5f), Pair(16.6f, 19f), Pair(13f, 19f))

        fun morphQuad(from: List<Pair<Float, Float>>, to: List<Pair<Float, Float>>): Path {
            val path = Path()
            val pts = from.indices.map { i ->
                val a = pt(from[i])
                val b = pt(to[i])
                Offset(a.x + (b.x - a.x) * morph, a.y + (b.y - a.y) * morph)
            }
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1..3) path.lineTo(pts[i].x, pts[i].y)
            path.close()
            return path
        }

        val white = Color.White
        drawPath(morphQuad(playL, pauseL), white)
        drawPath(morphQuad(playR, pauseR), white)
    }
}

/** 音量加减小圆钮（音量指示胶囊两侧的触控 +/−） */
@Composable
internal fun VolumeStepButton(symbol: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable {
                haptics.tap()
                onClick()
            },
    ) {
        Text(
            text = symbol,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}