package com.qmusic.wear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.qmusic.wear.ui.theme.LocalLowPerf

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
    // 该机 GPU 上模糊会在图像边界内侧衰减出暗边（宽≈2.2px/dp），半径越大暗边越宽；
    // 20dp 保证 1.6f 放大后暗边完全落在屏幕外（实测 44dp 即使用 1.5f 也压不住）
    blurRadius: Dp = 20.dp,
    scrim: Float = 0.55f,
) {
    // 低配置设备模式：跳过封面解码 + RenderEffect 模糊（全页最重的两步），仅保留品牌渐变 + 压暗
    val lowPerf = LocalLowPerf.current
    // 亮封面动态加压：平均亮度 > 0.55 时额外降饱和 + 提升 scrim（上限 0.76）
    val coverLum = rememberCoverLuminance(if (lowPerf) "" else coverUrl)
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
        if (coverUrl.isNotEmpty() && !lowPerf) {
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
                    // 模糊衰减暗边被 1.6f 放大推出屏幕外（配合 20dp 小半径），保证铺满圆屏
                    .graphicsLayer {
                        scaleX = 1.6f
                        scaleY = 1.6f
                    }
                    .blur(blurRadius, edgeTreatment = BlurredEdgeTreatment.Unbounded),
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