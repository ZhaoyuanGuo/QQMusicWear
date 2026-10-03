package com.qmusic.wear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

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
            // 固定宽度：不随歌名长短改变大小（方表屏窄，胶囊相应收窄避免挤占过多屏宽）
            .width(if (com.qmusic.wear.ui.theme.LocalIsRoundScreen.current) 108.dp else 96.dp)
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