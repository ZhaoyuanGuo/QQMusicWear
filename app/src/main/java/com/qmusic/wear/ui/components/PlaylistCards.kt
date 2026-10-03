package com.qmusic.wear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R

/** 歌单头卡片：封面 + 标题 + 数量徽章 + 播放全部（歌单/喜欢/最近页统一头部） */
@Composable
fun PlaylistHeader(
    title: String,
    coverUrl: String,
    songCount: Int,
    onPlayAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onDownloadAll: (() -> Unit)? = null,
    downloadPending: Boolean = false,
) {
    GlassPanel(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareCover(url = coverUrl, size = 50.dp, corner = 12.dp)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                CountChip("${songCount}首")
            }
        }
        // 播放全部 + 下载全部：一行并排（大胶囊 + 小圆钮），下载不再单独占一行
        if ((onPlayAll != null || onDownloadAll != null) && songCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onPlayAll != null) {
                    PlayAllChip(
                        onClick = onPlayAll,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onDownloadAll != null) {
                    Spacer(Modifier.size(6.dp))
                    // 下载全部小圆钮；下载中圆钮内显示进度环
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                            .clickable(enabled = !downloadPending, onClick = onDownloadAll),
                    ) {
                        if (downloadPending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_download),
                                contentDescription = "下载全部",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 歌单行：玻璃卡片 + 圆角封面 + 标题/副标题，可选尾部徽标 */
@Composable
fun PlaylistRow(
    name: String,
    coverUrl: String,
    songCount: Int,
    creatorNick: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
) {
    // 副标题拼接：创作者为空时不留悬空的「·」；计数异常（源数据把播放量塞进 songCount
    // 之类，出现 >10 万的不合理值）时降级不显示，避免「23307075首」的尴尬
    val countOk = songCount in 1..100_000
    val subtitle = buildString {
        if (countOk) append("${songCount}首")
        if (creatorNick.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(creatorNick)
        }
    }
    GlassRow(onClick = onClick, modifier = modifier) {
        SquareCover(url = coverUrl, size = 42.dp, corner = 12.dp)
        Spacer(Modifier.size(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        badge?.invoke()
    }
}