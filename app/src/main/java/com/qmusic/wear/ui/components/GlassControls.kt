package com.qmusic.wear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.data.model.Song

// -------------------------------------------------------------------------
// 统一玻璃拟态控件体系（与播放页副控制风格一致）
// -------------------------------------------------------------------------

/** 玻璃拟态行卡片：半透明圆角底 + 细描边，可点击（可选长按）；全列表行统一外观 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
    playing: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f))
        .border(
            0.5.dp,
            if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.14f),
            shape,
        )
    val interactive = when {
        onLongClick != null && onClick != null ->
            base.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        onClick != null -> base.clickable(onClick = onClick)
        else -> base
    }
    Row(
        modifier = interactive
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 玻璃拟态面板（纯展示容器，播放页卡片同款质感） */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(corner))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** 列表里的单曲行（播放页同款玻璃卡片风格，播放中有品牌色指示点；可选长按） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    liked: Boolean = false,
    downloaded: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    GlassRow(onClick = onClick, modifier = modifier, playing = playing, onLongClick = onLongClick) {
        RoundCover(url = song.cover300, size = 38.dp)
        Spacer(Modifier.size(9.dp))
        Column(Modifier.weight(1f)) {
            // 官方手表版样式：VIP 歌曲歌名绿色 + VIP 角标
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        playing -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song.vip) {
                    Spacer(Modifier.size(5.dp))
                    Text(
                        "VIP",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.62f),
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = song.singers,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            playing -> Box(
                Modifier
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            liked -> Icon(
                painter = painterResource(R.drawable.ic_heart),
                contentDescription = "已喜欢",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
            downloaded -> Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = "已下载",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** 页面标题：品牌色标题 + 副说明（各列表页统一头部） */
@Composable
fun PageTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null && subtitle.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 数量徽章（如「128首」） */
@Composable
fun CountChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** 播放全部小胶囊：品牌绿玻璃底（列表页统一入口样式，榜单/歌单/搜索共用） */
@Composable
fun PlayAllChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "播放全部",
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_play),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 小节标题（左对齐灰色小字，替代默认 ListHeader） */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 3.dp, bottom = 1.dp),
    )
}