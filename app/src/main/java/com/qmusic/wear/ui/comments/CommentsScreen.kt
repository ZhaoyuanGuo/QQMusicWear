package com.qmusic.wear.ui.comments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.model.SongComment
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.QmScreenScaffold
import com.qmusic.wear.ui.components.RoundCover
import com.qmusic.wear.ui.components.SectionHeader
import com.qmusic.wear.ui.components.edgeContentPadding
import com.qmusic.wear.ui.components.edgeScalingParams
import com.qmusic.wear.ui.components.qmAutoCentering
import com.qmusic.wear.ui.components.qmRotarySnap
import kotlinx.coroutines.launch

private const val COMMENT_PAGE = 20

/**
 * 歌曲评论页（QQ / 网易云 / 酷狗均支持；源未实现时显示空态）。
 * 支持「加载更多」分页。
 */
@Composable
fun CommentsScreen(
    songId: Long,
    mid: String,
    name: String,
) {
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var hot by remember { mutableStateOf<List<SongComment>>(emptyList()) }
    var comments by remember { mutableStateOf<List<SongComment>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }

    val song = remember(songId, mid, name) { Song(songId = songId, mid = mid, name = name) }

    LaunchedEffect(songId, mid) {
        val res = ServiceLocator.repository.songComments(song, offset = 0)
        hot = res.hotComments
        comments = res.comments
        hasMore = res.more
        loading = false
    }

    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            val res = ServiceLocator.repository.songComments(song, offset = comments.size)
            comments = comments + res.comments
            hasMore = res.more && res.comments.isNotEmpty()
            loadingMore = false
        }
    }

    QmScreenScaffold(scrollState = listState, timeText = { TimeText() }) { contentPadding ->
        ScalingLazyColumn(
            scalingParams = edgeScalingParams(),
            state = listState,
            rotaryScrollableBehavior = qmRotarySnap(listState),
            contentPadding = edgeContentPadding(contentPadding, bottomExtra = 30.dp),
            autoCentering = qmAutoCentering(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { PageTitle("评论") }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (hot.isEmpty() && comments.isEmpty()) {
                item {
                    Text(
                        "暂无评论或当前音乐源不支持",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                if (hot.isNotEmpty()) {
                    item { SectionHeader("热门评论") }
                    items(hot) { c -> CommentRow(c) }
                }
                if (comments.isNotEmpty()) {
                    item { SectionHeader("最新评论") }
                    items(comments) { c -> CommentRow(c) }
                }
                if (hasMore) {
                    item {
                        if (loadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        } else {
                            Text(
                                text = "加载更多评论",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { loadMore() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(c: SongComment) {
    GlassRow(onClick = {}) {
        RoundCover(url = c.avatarUrl, size = 30.dp)
        Spacer(Modifier.size(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = c.userName.ifEmpty { "匿名用户" },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = c.content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (c.likedCount > 0) {
                Text(
                    text = "赞 ${c.likedCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}