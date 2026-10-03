package com.qmusic.wear.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.ui.components.GlassPanel
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.SectionHeader
import com.qmusic.wear.util.formatBytes

/** 「通用」选项卡：存储占用统计 + 图片缓存清理 + 音乐源选择/更新/导入 */
internal fun ScalingLazyListScope.settingsStorageSection(ui: SettingsUiState, vm: SettingsViewModel) {
    // ---- 存储 ----
    item { SectionHeader("存储") }
    item {
        val downloads by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
        GlassPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("已下载歌曲", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${downloads.size} 首 · " + downloads.sumOf { it.sizeBytes }.formatBytes(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    item {
        GlassRow(onClick = { vm.clearImageCache() }) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (ui.cacheClearMessage == "") "清理中…" else "清理图片缓存",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "释放封面图片缓存空间",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    settingsSourceSection(ui, vm)
}

/** 音乐源列表：切源 / 更新源 / 从存储导入源 */
private fun ScalingLazyListScope.settingsSourceSection(ui: SettingsUiState, vm: SettingsViewModel) {
    item { SectionHeader("音乐源") }
    items(ui.sources.size) { idx ->
        val src = ui.sources[idx]
        SourceRow(
            name = src.displayName,
            subtitle = src.subtitle.ifEmpty { src.id },
            color = Color(src.themeColor),
            selected = src.id == ui.activeSourceId,
            enabled = !ui.sourceSwitching,
            onClick = { vm.selectSource(src.id) },
        )
    }
    item {
        GlassRow(onClick = { vm.updateSource() }) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    // null=空闲显示「更新音乐源」；""=更新中/结果提示待消失显示「更新中…」
                    if (ui.sourceUpdateMessage != null) "更新中…" else "更新音乐源",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    if (ui.sourceVersion > 0) "当前版本 v${ui.sourceVersion}" else "未加载",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    item {
        val ctx = LocalContext.current
        val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                val bytes = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes != null) vm.importSource(bytes)
            }
        }
        GlassRow(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("从存储导入源", style = MaterialTheme.typography.labelLarge)
                Text(
                    "镜像失效时兜底",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}