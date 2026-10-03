package com.qmusic.wear.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.SearchResult
import com.qmusic.wear.data.model.Singer
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.RoundCover
import com.qmusic.wear.ui.components.edgeListHorizontalInset
import com.qmusic.wear.ui.components.edgeListPadding
import com.qmusic.wear.ui.components.edgeScalingParams
import com.qmusic.wear.ui.components.qmAutoCentering
import com.qmusic.wear.ui.components.qmRotarySnap
import com.qmusic.wear.ui.theme.LocalIsRoundScreen

/** 搜索结果页：歌曲/歌手/歌单三分区（固定标签条 + 分区列表） */
@Composable
internal fun SearchResults(
    listState: ScalingLazyListState,
    result: SearchResult,
    onPlaySong: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onOpenArtist: (Singer) -> Unit,
    onOpenAlbumOfSong: (Song) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
) {
    val empty = result.songs.isEmpty() && result.singers.isEmpty() && result.playlists.isEmpty()
    if (empty) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "没有找到相关内容",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // 固定三选项卡（歌曲/歌手/歌单），置于搜索栏与结果之间；无结果的分区显示空态
    data class Tab(val label: String, val count: Int)
    val tabs = listOf(
        Tab("歌曲", result.songs.size),
        Tab("歌手", result.singers.size),
        Tab("歌单", result.playlists.size),
    )
    var sel by remember(result) { mutableStateOf(0) }
    val selIndex = if (sel >= tabs.size) 0 else sel
    val isRound = LocalIsRoundScreen.current
    // 与下方结果列表卡片同宽（同搜索框）
    val headerInset = edgeListHorizontalInset()

    Column(Modifier.fillMaxSize()) {
        // 分区选择条（圆表压矮并收紧间距，左右与列表对齐）
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (isRound) 4.dp else 5.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = headerInset),
        ) {
            tabs.forEachIndexed { i, tab ->
                ResultTab(
                    label = tab.label,
                    count = tab.count,
                    selected = i == selIndex,
                    onClick = { sel = i },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(if (isRound) 4.dp else 6.dp))

        val current = tabs[selIndex]
        if (current.count == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "没有找到相关${current.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        when (current.label) {
            "歌手" -> ScalingLazyColumn(
                state = listState,
                rotaryScrollableBehavior = qmRotarySnap(listState),
                contentPadding = edgeListPadding(),
                autoCentering = qmAutoCentering(),
                scalingParams = edgeScalingParams(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(result.singers) { singer ->
                    GlassRow(onClick = { onOpenArtist(singer) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_user),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        SearchResultTexts(title = singer.name, subtitle = "查看歌曲")
                    }
                }
            }

            "歌单" -> ScalingLazyColumn(
                state = listState,
                rotaryScrollableBehavior = qmRotarySnap(listState),
                contentPadding = edgeListPadding(),
                autoCentering = qmAutoCentering(),
                scalingParams = edgeScalingParams(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(result.playlists) { pl ->
                    GlassRow(onClick = { onOpenPlaylist(pl) }) {
                        RoundCover(url = pl.picUrl, size = 30.dp)
                        Spacer(Modifier.size(8.dp))
                        SearchResultTexts(
                            title = pl.name,
                            subtitle = "${pl.songCount}首 · ${pl.creatorNick}",
                        )
                    }
                }
            }

            else -> ScalingLazyColumn(
                contentPadding = edgeListPadding(),
                autoCentering = qmAutoCentering(),
                scalingParams = edgeScalingParams(),
                state = listState,
                rotaryScrollableBehavior = qmRotarySnap(listState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { ListHeader2("歌曲") { onPlayAll(result.songs) } }
                items(result.songs) { song ->
                    GlassRow(
                        onClick = { onPlaySong(song) },
                        onLongClick = { onOpenAlbumOfSong(song) },
                    ) {
                        RoundCover(url = song.cover300, size = 30.dp)
                        Spacer(Modifier.size(8.dp))
                        SearchResultTexts(title = song.name, subtitle = song.singers)
                    }
                }
            }
        }
    }
}

/** 搜索分区切换片（选中=主题色底，未选中=玻璃底）：紧凑胶囊，压低高度给结果列表让位 */
@Composable
private fun ResultTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 圆表顶部弧区窄，标签条压矮（方表数值不变）
    val isRound = LocalIsRoundScreen.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = if (isRound) 22.dp else 26.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f)
                },
            )
            .clickable(onClick = onClick)
            // 圆表标签与列表同宽后每片更窄，内边距再收一档，保证「歌曲 30」不被裁
            .padding(horizontal = if (isRound) 5.dp else 10.dp, vertical = if (isRound) 2.dp else 4.dp),
    ) {
        Text(
            if (count > 0) "$label $count" else label,
            style = MaterialTheme.typography.labelSmall,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SearchResultTexts(
    title: String,
    subtitle: String,
) {
    Column(Modifier.weight(1f)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ListHeader2(title: String, playAll: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        if (playAll != null) {
            Text(
                "播放全部",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { playAll() },
            )
        }
    }
}