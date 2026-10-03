package com.qmusic.wear.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.data.model.ProgressStyle
import com.qmusic.wear.data.model.UiShape
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.SectionHeader

/** 「显示」选项卡：屏幕常亮 / 低配置模式 / 界面形态 / 播放进度样式 */
internal fun ScalingLazyListScope.settingsDisplayTab(
    ui: SettingsUiState,
    vm: SettingsViewModel,
    resolvedProgressStyle: ProgressStyle,
) {
    // ---- 显示模式 ----
    item { SectionHeader("显示模式") }
    keepScreenOnRow(ui, vm)
    lowPerfRow(ui, vm)

    // ---- 界面形态（方表/圆表，全 app 布局跟随切换） ----
    item { SectionHeader("界面形态") }
    val shapeOptions = listOf(
        UiShape.AUTO to "跟随屏幕",
        UiShape.SQUARE to "方表",
        UiShape.ROUND to "圆表",
    )
    items(shapeOptions.size) { idx ->
        val (shape, label) = shapeOptions[idx]
        OptionRow(
            label = label,
            subtitle = when (shape) {
                UiShape.AUTO -> "按手表实际屏幕形状"
                UiShape.SQUARE -> "方屏专属布局与全部进度样式"
                UiShape.ROUND -> "经典圆盘播放页"
            },
            selected = ui.uiShape == shape,
            onClick = { vm.selectUiShape(shape) },
            iconRes = R.drawable.ic_brightness,
        )
    }

    // ---- 播放进度样式（六选一，带动画示意） ----
    item { SectionHeader("播放进度样式") }
    items(ProgressStyle.entries.size) { idx ->
        val s = ProgressStyle.entries[idx]
        ProgressStyleRow(
            style = s,
            // 未显式选择时高亮当前形态的默认样式（圆表=边缘进度环、方表=液体填充）
            selected = resolvedProgressStyle == s,
            onSelect = { vm.selectProgressStyle(s) },
        )
    }
}

/** 屏幕常亮开关 */
private fun ScalingLazyListScope.keepScreenOnRow(ui: SettingsUiState, vm: SettingsViewModel) {
    item {
        GlassRow(onClick = { vm.setKeepScreenOn(!ui.keepScreenOn) }) {
            Icon(
                painter = painterResource(R.drawable.ic_brightness),
                contentDescription = null,
                tint = if (ui.keepScreenOn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "屏幕常亮",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (ui.keepScreenOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "播放页不熄屏",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TogglePill(checked = ui.keepScreenOn)
        }
    }
}

/** 低配置设备模式开关：关闭特效与动画 */
private fun ScalingLazyListScope.lowPerfRow(ui: SettingsUiState, vm: SettingsViewModel) {
    item {
        GlassRow(onClick = { vm.setLowPerf(!ui.lowPerf) }) {
            Icon(
                painter = painterResource(R.drawable.ic_brightness),
                contentDescription = null,
                tint = if (ui.lowPerf) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "低配置设备模式",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (ui.lowPerf) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "关闭特效与动画，运行更流畅",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TogglePill(checked = ui.lowPerf)
        }
    }
}