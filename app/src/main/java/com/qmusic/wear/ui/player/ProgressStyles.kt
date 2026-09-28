package com.qmusic.wear.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import coil3.compose.AsyncImage
import com.qmusic.wear.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * 播放页六种进度样式渲染器（设置→显示 可选，默认液体填充）。
 * - 边框描边：进度沿屏幕圆角边框走一圈（方表专属；圆表回退原版边缘进度环）
 * - 封面描边：进度沿圆盘封面外缘描一圈（PlayerContent 内挂在圆盘外）
 * - 唱片弧线：进度弧 + 黑胶盘身（盘面见 PlayerScreen.PlayerDisc）
 * - 液体填充：全屏清晰封面为底层，模糊水体遮罩自下而上淹没，层次波纹液面
 * - 波形刻度：一排波形条，播过的点亮
 * - 点阵进度：一排圆点，点亮的数量=进度
 * 另含 [ProgressStylePreview]：设置页动画示意，用循环假进度驱动真实渲染器。
 */

/** 方表边框描边：进度沿屏幕圆角矩形边框顺时针延伸 */
@Composable
fun SquareBorderProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    corner: Dp = 18.dp,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400), label = "border_stroke")
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f + 2.dp.toPx()
        val track = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(inset, inset, size.width - inset, size.height - inset),
                    cornerRadius = CornerRadius(corner.toPx(), corner.toPx()),
                ),
            )
        }
        drawPath(track, Color.White.copy(alpha = 0.16f), style = Stroke(stroke, cap = StrokeCap.Round))
        val measure = PathMeasure()
        measure.setPath(track, false)
        val segment = Path()
        measure.getSegment(0f, measure.length * animated, segment, true)
        drawPath(segment, primary, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** 封面描边 / 唱片弧线：圆盘外圈的圆形进度弧（12 点方向起针，顺时针） */
@Composable
fun CoverStrokeRing(progress: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400), label = "cover_stroke")
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val radius = size.minDimension / 2f - stroke / 2f - 1.dp.toPx()
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        drawCircle(color = Color.White.copy(alpha = 0.16f), radius = radius, style = Stroke(stroke))
        drawArc(
            color = primary,
            startAngle = -90f,
            sweepAngle = 360f * animated,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * 液体填充（模糊遮罩式）：底层为全屏清晰封面（静止，不再旋转圆盘），
 * 水位以下以「同一封面对齐 + 高斯模糊 + 压暗」的遮罩直接盖在封面上；
 * 液面为多层半透明波纹（无色线），层次观感参考 MixAlpha 充电动画。
 * API 31+ 才有真实 blur，低版本以压暗+半透明近似水下观感。
 */
@Composable
internal fun LiquidFullScreen(
    coverUrl: String,
    progress: Float,
    coverAlpha: Float,
    isAmbient: Boolean,
    modifier: Modifier = Modifier,
) {
    // 水位平滑上升，避免按秒跳变
    val level by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "liquid_level")
    BoxWithConstraints(modifier) {
        val screenH = maxHeight
        // 底层：全屏清晰封面（静止）
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = coverAlpha },
        )
        if (level > 0.01f) {
            // 模糊水体：与全屏封面对齐的模糊拷贝，裁剪后只露出水位以下部分
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(level)
                    .clipToBounds(),
            ) {
                // 整屏等大的封面拷贝整体上移 (1-level)，与底层清晰封面逐像素对齐
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(screenH)
                        .graphicsLayer { translationY = -size.height * (1f - level) },
                ) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 0.9f }
                            .blur(12.dp),
                    )
                    // 水体压暗（AOD 再深一档）
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF04140C).copy(alpha = if (isAmbient) 0.62f else 0.42f)),
                    )
                }
            }
            // 层次波纹液面
            LiquidWaveSurface(
                level = level,
                isAmbient = isAmbient,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxSize(),
            )
        }
        // 顶部渐变 scrim：压暗歌名区域；底部轻压暗保证控制行在亮封面上可读
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.42f)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = if (isAmbient) 0.62f else 0.55f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.20f)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)),
                    ),
                ),
        )
    }
}

/** 液面波纹：三层正弦波缓慢流动，AOD/低配置模式下静止 */
@Composable
private fun LiquidWaveSurface(
    level: Float,
    isAmbient: Boolean,
    modifier: Modifier = Modifier,
) {
    val lowPerf = com.qmusic.wear.ui.theme.LocalLowPerf.current
    val phase = if (!lowPerf && !isAmbient) {
        rememberInfiniteTransition(label = "liquid_wave").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing)),
            label = "wave_phase",
        ).value
    } else {
        0f
    }
    Canvas(modifier) {
        drawWaveLayers(lineY = size.height * (1f - level), phase = phase)
    }
}

/**
 * 三层半透明正弦波（后→前波长/振幅/不透明度递进），波峰越过液面线叠出层次，
 * 只用深色半透明填充、不加色线；填充自波面向下，深处自然叠加变暗（同 MixAlpha）。
 * 振幅按画布高度比例缩放，全屏与设置页示意卡共用。
 */
private fun DrawScope.drawWaveLayers(lineY: Float, phase: Float) {
    val tint = Color(0xFF04140C)
    val twoPi = 2f * PI.toFloat()
    // (波长系数，振幅=画布高比例，不透明度)
    val layers = listOf(
        Triple(1.15f, 0.020f, 0.16f),
        Triple(0.78f, 0.028f, 0.26f),
        Triple(1.42f, 0.040f, 0.38f),
    )
    layers.forEachIndexed { i, (wavelengthF, ampF, alpha) ->
        val lambda = size.width * wavelengthF
        val base = (phase + i * 0.35f) * twoPi
        val amp = size.height * ampF
        val path = Path()
        path.moveTo(0f, lineY + amp * sin(base))
        var x = 0f
        val step = 8f
        while (x < size.width) {
            x = minOf(x + step, size.width)
            path.lineTo(x, lineY + amp * sin(x / lambda * twoPi + base))
        }
        path.lineTo(size.width, size.height)
        path.lineTo(0f, size.height)
        path.close()
        drawPath(path, tint.copy(alpha = alpha))
    }
}

/** 波形刻度：一排波形条，播过的点亮为品牌绿 */
@Composable
fun WaveformProgress(progress: Float, modifier: Modifier = Modifier, barCount: Int = 24) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400), label = "waveform_progress")
    val lit = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val gap = 1.5.dp.toPx()
        val barW = (size.width - gap * (barCount - 1)) / barCount
        val midY = size.height / 2f
        for (i in 0 until barCount) {
            // 确定性波形：同一首歌每帧形状一致，不闪烁
            val t = ((sin(i * 1.7) + 1.0) / 2.0).toFloat()
            val h = size.height * (0.32f + 0.62f * t)
            val x = i * (barW + gap)
            drawRoundRect(
                color = if (x + barW / 2f <= size.width * animated) lit else Color.White.copy(alpha = 0.22f),
                topLeft = Offset(x, midY - h / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/** 点阵进度：一排圆点，点亮的数量=进度 */
@Composable
fun DotsProgress(progress: Float, modifier: Modifier = Modifier, count: Int = 16) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400), label = "dots_progress")
    val lit = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val dot = minOf(size.height, (size.width - gap * (count - 1)) / count)
        val total = dot * count + gap * (count - 1)
        val startX = (size.width - total) / 2f
        for (i in 0 until count) {
            val cx = startX + i * (dot + gap) + dot / 2f
            drawCircle(
                color = if (cx <= startX + total * animated) lit else Color.White.copy(alpha = 0.22f),
                radius = dot / 2f,
                center = Offset(cx, size.height / 2f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 设置→显示：样式动画示意（六张动图，循环假进度驱动真实渲染器，保证动起来）
// ---------------------------------------------------------------------------

/** 设置页样式示意卡：58dp 高的迷你播放页画面，进度循环推进 */
@Composable
fun ProgressStylePreview(style: com.qmusic.wear.data.model.ProgressStyle, modifier: Modifier = Modifier) {
    val fake by rememberInfiniteTransition(label = "style_preview").animateFloat(
        initialValue = 0.04f,
        targetValue = 0.96f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "style_preview_progress",
    )
    Box(
        modifier
            .height(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0D1218)),
    ) {
        when (style) {
            com.qmusic.wear.data.model.ProgressStyle.LIQUID -> {
                // 示意：伪封面铺满卡片为底层，模糊水体（压暗近似）自下而上淹没，
                // 液面为三层半透明波纹缓慢流动（无色线，MixAlpha 观感）
                val wavePhase by rememberInfiniteTransition(label = "liquid_wave").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
                    label = "wave_phase",
                )
                Box(Modifier.fillMaxSize()) {
                    PseudoCover(Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(fake)
                            .clipToBounds(),
                    ) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF04140C).copy(alpha = 0.45f)))
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        drawWaveLayers(lineY = size.height * (1f - fake), phase = wavePhase)
                    }
                }
            }

            com.qmusic.wear.data.model.ProgressStyle.BORDER -> {
                SquareBorderProgress(
                    progress = fake,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                    corner = 12.dp,
                )
            }

            com.qmusic.wear.data.model.ProgressStyle.COVER_STROKE -> {
                Box(Modifier.align(Alignment.Center).size(52.dp)) {
                    CoverStrokeRing(progress = fake, modifier = Modifier.fillMaxSize())
                    PseudoCover(Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape))
                }
            }

            com.qmusic.wear.data.model.ProgressStyle.VINYL_ARC -> {
                Box(Modifier.align(Alignment.Center).size(58.dp)) {
                    CoverStrokeRing(progress = fake, modifier = Modifier.fillMaxSize())
                    // 迷你黑胶：深色盘身 + 沟槽 + 封面标签
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0A0C10)),
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f
                            for (i in 1..3) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.07f),
                                    radius = r * (0.34f + i * 0.16f),
                                    style = Stroke(1.dp.toPx()),
                                )
                            }
                        }
                        PseudoCover(Modifier.align(Alignment.Center).size(22.dp).clip(CircleShape))
                    }
                }
            }

            com.qmusic.wear.data.model.ProgressStyle.WAVEFORM -> {
                WaveformProgress(
                    progress = fake,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(26.dp),
                    barCount = 22,
                )
            }

            com.qmusic.wear.data.model.ProgressStyle.DOTS -> {
                DotsProgress(
                    progress = fake,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(10.dp),
                )
            }
        }
    }
}

/** 伪封面：示意卡里代替真实专辑封面（品牌青底 + 音符图标） */
@Composable
private fun PseudoCover(modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFF1E5A46)), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_menu),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp),
        )
    }
}
