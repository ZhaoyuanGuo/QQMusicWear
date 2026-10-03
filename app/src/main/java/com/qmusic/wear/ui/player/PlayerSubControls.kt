package com.qmusic.wear.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.data.player.NowPlaying
import com.qmusic.wear.ui.components.rememberHaptics
import com.qmusic.wear.ui.theme.LocalIsRoundScreen

/** 方案D四角布局（方表 + 波形/点阵）：音量(左上) / 音质(右上) / 喜欢(左下) / 下载(右下) */
@Composable
internal fun CornerSubControls(
    now: NowPlaying,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
    onVolumeAdjust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = subControlChips(now, vm, onOpenDownloads, onVolumeAdjust)
    Box(modifier) {
        Box(Modifier.align(Alignment.TopStart).padding(3.dp)) { chips[0]() }
        Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) { chips[2]() }
        Box(Modifier.align(Alignment.BottomStart).padding(3.dp)) { chips[1]() }
        Box(Modifier.align(Alignment.BottomEnd).padding(3.dp)) { chips[3]() }
    }
}

/**
 * 底部副控件行：音量 / 喜欢 / 音质 / 下载（/ 评论）小圆钮。
 * 视觉圆底 28dp、间距 12dp；命中区保持 42dp——用 -2dp 行距抵消命中区外扩，
 * 视觉效果与 1.9.1 完全一致（28dp 圆 + 12dp 视觉间隙），触控容错不减。
 *
 * 排布按屏幕形态分叉：
 * - 圆表：5 个钮横排会顶到圆屏弧边（总宽 ≈188dp 超出中下部可用弦长），改为沿圆屏下缘排成一段
 *   对称圆弧——中点在正下方（6 点钟），两侧按钮沿弧线抬升，既贴合圆屏轮廓又避免互相遮挡；
 * - 方表：屏幕足够宽，保持一行五个不变。
 */
@Composable
internal fun SubControlsRow(
    now: NowPlaying,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
    onOpenComments: () -> Unit = {},
    onVolumeAdjust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = subControlChips(now, vm, onOpenDownloads, onVolumeAdjust, onOpenComments)
    if (LocalIsRoundScreen.current) {
        // 弧线排布：以屏幕中心为圆心，把 5 个钮按等张角(30°)铺在下缘圆弧上。
        // 摆放半径 = 屏幕半径 − 27dp（半个命中区 21dp + 视觉余量），圆底距屏幕边缘约 13dp 不裁切。
        BoxWithConstraints(modifier) {
            val cx = maxWidth / 2
            val cy = maxHeight / 2
            val radius = minOf(maxWidth, maxHeight) / 2 - 27.dp
            val stepDeg = 30f
            val mid = (chips.size - 1) / 2f
            chips.forEachIndexed { i, chip ->
                val rad = (i - mid) * stepDeg * (Math.PI / 180.0)
                val x = cx + radius * kotlin.math.sin(rad).toFloat()
                val y = cy + radius * kotlin.math.cos(rad).toFloat()
                // 命中区 42dp：偏移到「中心 − 21dp」使圆钮圆心正好落在弧线上
                Box(Modifier.offset(x = x - 21.dp, y = y - 21.dp)) { chip() }
            }
        }
    } else {
        // 命中区42dp + 行距-2dp → 视觉圆底中心距 40dp（= 28dp 圆 + 12dp 间隙，与 1.9.1 一致）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((-2).dp),
            modifier = modifier,
        ) {
            chips.forEach { chip -> chip() }
        }
    }
}

/** 副控制小圆钮：半透明底 + 细描边（可选长按）；命中区扩到42dp（视觉圆底28dp不变），可选角标 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SubControlChip(
    resId: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    badge: String? = null,
) {
    val haptics = rememberHaptics()
    Box(
        contentAlignment = Alignment.Center,
        // 命中区42dp，视觉圆底28dp不变；圆形裁剪让 ripple 呈圆形，避免方形灰块
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = {
                    haptics.tap()
                    onClick()
                },
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                // 深色圆底：任何封面色上都清晰
                .background(Color.Black.copy(alpha = 0.42f))
                .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        ) {
            Icon(
                painter = painterResource(resId),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
        }
        // 角标（音质指示）：骑在圆底右下角
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp, lineHeight = 8.sp),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-4).dp, y = (-5).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 2.dp),
            )
        }
    }
}