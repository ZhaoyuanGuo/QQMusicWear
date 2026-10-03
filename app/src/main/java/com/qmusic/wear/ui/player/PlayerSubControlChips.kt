package com.qmusic.wear.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.data.player.NowPlaying
import kotlinx.coroutines.launch

/**
 * 构建副控件 chip（音量/喜欢/音质/下载/评论）；底部横排与方案D四角布局共用。
 * 方案D四角布局只取前 4 个（2×2 网格），评论钮仅在底部横排显示。
 * 评论钮按源能力显示：源未实现 songComments 时直接不出现，
 * 避免点进去只有空列表。
 */
@Composable
internal fun subControlChips(
    now: NowPlaying,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
    onVolumeAdjust: () -> Unit,
    onOpenComments: () -> Unit = {},
): List<@Composable () -> Unit> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val uiState = vm.ui.collectAsStateWithLifecycle().value
    val downloads by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
    val likedMids by ServiceLocator.repository.likedMids.collectAsStateWithLifecycle()
    val curSong = now.song
    // 下载状态按歌曲收敛：切歌后不再沿用上一首的「下载中/已完成」
    val download = uiState.download.takeIf { it.mid == curSong?.mid } ?: DownloadUi()
    val liked = curSong != null && likedMids.contains(curSong.mid)
    val downloaded = curSong?.let { s -> downloads.any { it.song.mid == s.mid } } == true

    // 四个钮（顺序：左 → 右）
    val chips: List<@Composable () -> Unit> = listOf(
        {
            SubControlChip(
                resId = R.drawable.ic_volume,
                contentDescription = stringResource(R.string.player_volume),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onVolumeAdjust,
            )
        },
        {
            // 红心收藏：单击加入/移出「我喜欢」（与手机端账号联动）
            SubControlChip(
                resId = R.drawable.ic_heart,
                contentDescription = if (liked) "取消喜欢" else "加入我喜欢",
                tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    if (curSong != null) {
                        scope.launch {
                            val ok = ServiceLocator.repository.setLiked(curSong, !liked)
                            android.widget.Toast.makeText(
                                ctx,
                                if (!ok) "收藏失败（需登录）" else if (!liked) "已加入我喜欢" else "已取消喜欢",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        },
        {
            SubControlChip(
                resId = R.drawable.ic_quality,
                contentDescription = stringResource(R.string.player_quality),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                // 角标显示当前实际播放音质（由解析出的文件名前缀判定，HQ/SQ/HR/128），
                // 未开始播放时回退到设置档位
                badge = qualityBadge(vm.actualQuality(now.qualityPrefix, uiState.selectedQuality)),
                onClick = { vm.toggleQuality() },
            )
        },
        {
            // 下载控件：空闲=下载图标 / 下载中=进度环 / 完成=对勾
            when {
                download.running -> Box(
                    contentAlignment = Alignment.Center,
                    // 与其余按钮的42dp命中区对齐，避免状态切换时行内抖动；点击进下载管理；
                    // 圆形裁剪让 ripple 呈圆形，避免方形灰块
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenDownloads),
                ) {
                    CircularProgressIndicator(
                        progress = { download.progress },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                download.done || downloaded -> SubControlChip(
                    resId = R.drawable.ic_check,
                    contentDescription = "已下载（点击查看，长按删除）",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onOpenDownloads,
                    onLongClick = {
                        if (curSong != null) ServiceLocator.downloads.remove(curSong.mid)
                    },
                )
                else -> SubControlChip(
                    resId = R.drawable.ic_download,
                    contentDescription = stringResource(R.string.player_download),
                    tint = if (download.message.isNotEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    onClick = { vm.downloadCurrent() },
                )
            }
        },
    )
    // 评论：仅当源实现了 songComments 才出现（QQ / 网易云 / 酷狗 / 酷狗概念版均支持）
    val caps by com.qmusic.wear.data.source.SourceManager.capabilitiesFlow.collectAsStateWithLifecycle()
    val allChips = if (caps.contains("songComments")) {
        chips + listOf<@Composable () -> Unit> {
            SubControlChip(
                resId = R.drawable.ic_comment,
                contentDescription = "评论",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onOpenComments,
            )
        }
    } else {
        chips
    }
    // 构建完成后直接返回给调用方布局（底部横排 / 方案D四角共用）
    return allChips
}

/** 音质短角标（音质钮上显示，不点开也能看到） */
internal fun qualityBadge(q: Quality): String = when (q) {
    Quality.STANDARD -> "128"
    Quality.HIGH -> "HQ"
    Quality.LOSSLESS -> "SQ"
    Quality.HI_RES -> "HR"
}