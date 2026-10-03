package com.qmusic.wear.ui.mine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.PlaylistRow
import com.qmusic.wear.ui.components.SectionHeader

/** 「我的」页尾部入口：我的歌单列表 → 下载管理 → 设置 */
internal fun ScalingLazyListScope.mineEntryRows(
    ui: MineUiState,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // ---- 我的歌单 ----
    if (ui.favPlaylists.isNotEmpty()) {
        item { SectionHeader("我的歌单") }
        items(ui.favPlaylists) { pl ->
            PlaylistRow(
                name = pl.name,
                coverUrl = pl.picUrl,
                songCount = pl.songCount,
                creatorNick = pl.creatorNick,
                onClick = { onOpenPlaylist(pl.disstid, pl.name) },
            )
        }
    }

    // ---- 下载管理 ----
    item {
        GlassRow(onClick = onOpenDownloads) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("下载管理", style = MaterialTheme.typography.labelLarge)
                Text(
                    "长按删除歌曲",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ---- 设置 ----
    item {
        GlassRow(onClick = onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("设置", style = MaterialTheme.typography.labelLarge)
                Text(
                    "音质与账号",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}