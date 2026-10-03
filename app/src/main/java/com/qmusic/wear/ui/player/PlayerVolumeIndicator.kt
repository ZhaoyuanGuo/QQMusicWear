package com.qmusic.wear.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R

/**
 * 音量指示（点音量钮触发，约3.5秒自动消失）：
 * 底部小胶囊显示音量值并提供触控加减，播放进度环同时切换为音量弧。
 */
@Composable
internal fun PlayerVolumeIndicator(
    visible: Boolean,
    volume: Int,
    maxVolume: Int,
    onAdjust: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VolumeStepButton("−") { onAdjust((volume - 1).coerceIn(0, maxVolume)) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_volume),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "音量 $volume/$maxVolume",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            VolumeStepButton("+") { onAdjust((volume + 1).coerceIn(0, maxVolume)) }
        }
    }
}