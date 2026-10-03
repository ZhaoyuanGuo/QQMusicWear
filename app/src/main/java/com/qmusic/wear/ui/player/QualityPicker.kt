package com.qmusic.wear.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.ui.theme.LocalIsRoundScreen

/** 音质选择面板（半透明卡片，可滚动防裁切） */
@Composable
internal fun QualityPicker(
    selected: Quality,
    available: List<Quality>,
    switching: Boolean,
    onSelect: (Quality) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            // 圆表两侧大留白防弧边裁切；方表收窄让选项文案更宽
            .padding(horizontal = if (LocalIsRoundScreen.current) 22.dp else 2.dp)
            .verticalScroll(rememberScrollState())
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.player_quality),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        available.forEach { q ->
            RadioButton(
                selected = q == selected,
                onSelect = { onSelect(q) },
                // 胶囊统一占满面板宽度：Wear M3 RadioButton 默认按内容宽度收缩
                // （width(IntrinsicSize.Max)），不约束会因文案长短导致大小不一。
                modifier = Modifier.fillMaxWidth(),
                label = { Text(q.label) },
                secondaryLabel = {
                    Text(
                        text = when (q) {
                            Quality.STANDARD -> "128kbps MP3"
                            Quality.HIGH -> "320kbps MP3"
                            Quality.LOSSLESS -> "FLAC"
                            Quality.HI_RES -> "最高音质"
                        },
                    )
                },
            )
        }
        if (switching) {
            Text("切换中…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("完成")
        }
        Spacer(Modifier.height(6.dp))
    }
}