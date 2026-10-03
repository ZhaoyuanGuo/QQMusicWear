package com.qmusic.wear.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.data.model.ProgressStyle
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.player.ProgressStylePreview

/** 设置页选项卡胶囊行：通用 / 显示 / 播放设置 */
@Composable
internal fun SettingsTabChips(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        listOf("通用", "显示", "播放设置").forEachIndexed { idx, label ->
            val isSelected = selected == idx
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                    )
                    .clickable { onSelect(idx) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** 播放进度样式选择行：动画示意图（循环假进度驱动，保证动起来）+ 名称 + 选中圆点 */
@Composable
internal fun ProgressStyleRow(style: ProgressStyle, selected: Boolean, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f))
            .border(
                0.5.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else Color.White.copy(alpha = 0.14f),
                shape,
            )
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            ProgressStylePreview(style, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = style.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                SelectionDot(selected)
            }
        }
    }
}

/** 开关胶囊：小型 iOS 风格 toggle 指示器（选中绿底右浮点，未选中灰底左浮点） */
@Composable
internal fun TogglePill(checked: Boolean) {
    Box(
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = Modifier
            .size(width = 26.dp, height = 16.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            .padding(horizontal = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** 音乐源选择行：品牌色圆点 + 名称 + 说明 + 选中圆点指示 */
@Composable
internal fun SourceRow(
    name: String,
    subtitle: String,
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    GlassRow(onClick = { if (enabled) onClick() }) {
        Box(
            modifier = Modifier
                .size(17.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SelectionDot(selected)
    }
}

/** 音质选择行：图标 + 名称 + 码率说明 + 选中圆点 */
@Composable
internal fun QualityRow(q: Quality, selected: Boolean, onSelect: () -> Unit) {
    OptionRow(
        label = q.label,
        subtitle = when (q) {
            Quality.STANDARD -> "128kbps"
            Quality.HIGH -> "320kbps"
            Quality.LOSSLESS -> "FLAC 无损"
            Quality.HI_RES -> "Hi-Res 至高"
        },
        selected = selected,
        onClick = onSelect,
        iconRes = R.drawable.ic_quality,
    )
}

/** 通用单选行：可选图标 + 名称 + 副标题 + 选中圆点指示 */
@Composable
internal fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    iconRes: Int? = null,
) {
    GlassRow(onClick = onClick) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SelectionDot(selected)
    }
}

/** 单选状态指示：外圈 + 选中实心圆点 */
@Composable
private fun SelectionDot(selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(18.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                ),
        )
    }
}

/** 账号昵称旁的会员身份徽标（按平台着色） */
@Composable
internal fun VipBadge(label: String) {
    val badgeColor = when {
        label.contains("绿钻") -> Color(0xFF1ECE6B)
        label.contains("概念版") || label.contains("酷狗") -> Color(0xFF2AA2E6)
        label.contains("黑胶") || label.contains("音乐包") -> Color(0xFFEC4141)
        else -> Color(0xFF8E8E93)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(badgeColor)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}