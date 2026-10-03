package com.qmusic.wear.ui.mine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.store.WeekStats
import com.qmusic.wear.ui.components.GlassPanel
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.SquareCover

/**
 * 「我的」页内容行：我的喜欢 → 听歌统计 → 最近播放 → 播客/电台 → 动态/关注。
 * 播客与动态按源能力/登录态条件显示。
 */
internal fun ScalingLazyListScope.mineLibraryRows(
    ui: MineUiState,
    stats: WeekStats,
    recentCount: Int,
    logged: Boolean,
    supportsPodcast: Boolean,
    supportsSocial: Boolean,
    onOpenLiked: (Playlist) -> Unit,
    onOpenRecent: () -> Unit,
    onOpenPodcast: () -> Unit,
    onOpenSocial: () -> Unit,
) {
    // ---- 我的喜欢 ----
    ui.likedPlaylist?.let { liked ->
        item {
            GlassRow(onClick = { onOpenLiked(liked) }) {
                SquareCover(url = liked.picUrl, size = 42.dp, corner = 12.dp)
                Spacer(Modifier.size(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("我的喜欢", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = "${liked.songCount}首",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_heart),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }

    // ---- 听歌统计（本地数据） ----
    if (stats.playCount > 0) {
        item {
            val hours = stats.totalSec / 3600
            val mins = (stats.totalSec % 3600) / 60
            val durText = when {
                hours > 0 -> "${hours}小时${mins}分钟"
                mins > 0 -> "${mins}分钟"
                else -> "刚刚开始"
            }
            GlassPanel {
                Column(Modifier.fillMaxWidth()) {
                    // 拆行显示避免长文案在圆屏折行：标题行 + 数值行 + 最常听
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_history),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text("本周已听", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.size(3.dp))
                    Text(
                        "$durText · ${stats.playCount}次",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 27.dp),
                    )
                    if (stats.topName.isNotEmpty()) {
                        Spacer(Modifier.size(2.dp))
                        Text(
                            "最常听：${stats.topName} · ${stats.topSingers}（${stats.topCount}次）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 27.dp),
                        )
                    }
                }
            }
        }
    }

    // ---- 最近播放 ----
    item {
        GlassRow(onClick = onOpenRecent) {
            Icon(
                painter = painterResource(R.drawable.ic_history),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("最近播放", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = if (recentCount == 0) "本地播放记录" else "最近播放 ${recentCount}首",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ---- 播客 / 电台（仅实现了 djRadios 的源显示，如网易云） ----
    if (supportsPodcast) {
        item {
            GlassRow(onClick = onOpenPodcast) {
                Icon(
                    painter = painterResource(R.drawable.ic_playlist),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("播客 / 电台", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "热门电台与节目",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // ---- 动态 / 关注（需登录 + 源实现了 userEvents/userFollows 才显示） ----
    if (logged && supportsSocial) {
        item {
            GlassRow(onClick = onOpenSocial) {
                Icon(
                    painter = painterResource(R.drawable.ic_user),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("动态 / 关注", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "好友动态与关注列表",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}