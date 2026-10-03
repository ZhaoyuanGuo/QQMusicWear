package com.qmusic.wear.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.qmusic.wear.data.model.ProgressStyle
import com.qmusic.wear.data.player.NowPlaying
import com.qmusic.wear.ui.components.rememberCoverColor

/**
 * 中央圆盘：按进度样式切换盘面——
 * - LIQUID 液体填充：盘面全透明（全屏封面与水体由 PlayerContent 的 LiquidFullScreen
 *   在底层绘制，取消旋转圆盘），此容器仅承载顶部歌名/歌手与盘心控制键组
 * - VINYL_ARC 唱片弧线：黑胶盘身（沟槽静止）+ 旋转封面标签 + 主轴
 * - 其余样式：原版旋转封面
 */
@Composable
internal fun PlayerDisc(
    now: NowPlaying,
    disc: Dp,
    style: ProgressStyle,
    progress: Float,
    discRotation: Float,
    coverAlpha: Float,
    isAmbient: Boolean,
    modifier: Modifier = Modifier,
) {
    val coverUrl = now.song?.cover500.orEmpty()
    val discTint = when (style) {
        // 液体填充：透明容器，露出底层全屏液体画面
        ProgressStyle.LIQUID -> Color.Transparent
        ProgressStyle.VINYL_ARC -> Color(0xFF0A0C10)
        else -> rememberCoverColor(coverUrl) ?: MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(disc)
            .clip(CircleShape)
            .background(discTint),
    ) {
        when (style) {
            ProgressStyle.LIQUID -> {
                // 盘面无内容：画面全部来自底层 LiquidFullScreen
            }

            ProgressStyle.VINYL_ARC -> {
                // 黑胶沟槽盘身（静止）
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    for (i in 1..5) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.05f),
                            radius = r * (0.32f + i * 0.115f),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }
                // 旋转封面标签
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(disc * 0.52f)
                        .clip(CircleShape)
                        .graphicsLayer {
                            rotationZ = discRotation
                            alpha = coverAlpha
                        },
                ) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = if (isAmbient) 0.50f else 0.15f)),
                    )
                }
                // 主轴
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                )
            }

            else -> {
                // 原版：封面填充 + 旋转
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = discRotation
                            alpha = coverAlpha
                        },
                )
            }
        }

        // 整体只轻微压暗（黑胶盘身自带深底、液体填充无盘面，均略过）；AOD 下再压暗一档降亮度
        if (style != ProgressStyle.VINYL_ARC && style != ProgressStyle.LIQUID) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isAmbient) 0.50f else 0.10f)),
            )
        }
        // 顶部局部渐变 scrim：只压暗歌名区域，保证可读性的同时不糊住整个封面
        // （液体填充的 scrim 由 LiquidFullScreen 全屏绘制，避免透明圆盘内出现圆形压痕）
        if (style != ProgressStyle.LIQUID) {
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
        }

        // 控制键组已上移到 PlayerContent 全屏层（圆盘宽度不足以测量 164dp 行宽）

        // 歌名/歌手：盘内上部（上移让位给控制键组，避免播放键顶到歌手名）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = disc * 0.12f),
        ) {
            Text(
                text = now.song?.name.orEmpty(),
                style = MaterialTheme.typography.titleSmall.copy(
                    // 文字阴影：亮色封面上保证白字分离度
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.55f),
                        blurRadius = 10f,
                        offset = Offset.Zero,
                    ),
                ),
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = now.song?.singers.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.60f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
    }
}