package com.qmusic.wear.ui.settings

import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.ui.components.SectionHeader
import com.qmusic.wear.util.msTo_mmss

/** 「播放设置」选项卡：播放音质 / 下载音质 / 睡眠定时 */
internal fun ScalingLazyListScope.settingsPlaybackTab(
    ui: SettingsUiState,
    vm: SettingsViewModel,
    sleepRemaining: Long,
) {
    // ---- 播放音质 ----
    item { SectionHeader("播放音质") }
    items(Quality.entries.size) { idx ->
        val q = Quality.entries[idx]
        QualityRow(
            q = q,
            selected = q == ui.quality,
            onSelect = { vm.selectQuality(q) },
        )
    }

    // ---- 下载音质（独立于播放音质） ----
    item { SectionHeader("下载音质") }
    items(Quality.entries.size) { idx ->
        val q = Quality.entries[idx]
        QualityRow(
            q = q,
            selected = q == ui.downloadQuality,
            onSelect = { vm.selectDownloadQuality(q) },
        )
    }

    // ---- 睡眠定时 ----
    item {
        SectionHeader(
            if (sleepRemaining > 0) {
                "睡眠定时 · 剩余 ${(sleepRemaining * 1000L).msTo_mmss()}"
            } else {
                "睡眠定时"
            },
        )
    }
    val sleepOptions = listOf(0 to "关闭", 15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟")
    items(sleepOptions.size) { idx ->
        val (minutes, label) = sleepOptions[idx]
        OptionRow(
            label = label,
            selected = ui.sleepMinutes == minutes && (minutes != 0 || sleepRemaining <= 0L),
            onClick = { vm.selectSleepTimer(minutes) },
        )
    }
}