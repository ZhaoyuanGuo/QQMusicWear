package com.qmusic.wear.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.qmusic.wear.data.model.FollowUser
import com.qmusic.wear.data.model.UserEvent
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.QmScreenScaffold
import com.qmusic.wear.ui.components.RoundCover
import com.qmusic.wear.ui.components.edgeContentPadding
import com.qmusic.wear.ui.components.edgeScalingParams
import com.qmusic.wear.ui.components.qmAutoCentering
import com.qmusic.wear.ui.components.qmRotarySnap

/**
 * 动态 / 关注（网易云源提供；其他源无数据时显示空态）。
 * 两个分区用顶部胶囊切换。
 */
@Composable
fun SocialScreen(
    uid: Long,
    onOpenPlayer: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var events by remember { mutableStateOf<List<UserEvent>>(emptyList()) }
    var follows by remember { mutableStateOf<List<FollowUser>>(emptyList()) }

    LaunchedEffect(uid) {
        events = ServiceLocator.repository.userEvents(uid)
        follows = ServiceLocator.repository.userFollows(uid)
        loading = false
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
            item { PageTitle("动态") }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SocialTab("动态", events.size, tab == 0, { tab = 0 }, Modifier.weight(1f))
                    SocialTab("关注", follows.size, tab == 1, { tab = 1 }, Modifier.weight(1f))
                }
            }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (tab == 0) {
                if (events.isEmpty()) {
                    item { EmptyHint("暂无动态或当前音乐源不支持") }
                } else {
                    items(events) { e ->
                        GlassRow(onClick = {
                            val first = e.songs.firstOrNull()
                            if (first != null) {
                                ServiceLocator.player.playFromList(e.songs, first.mid)
                                onOpenPlayer()
                            }
                        }) {
                            RoundCover(url = e.avatarUrl, size = 30.dp)
                            Spacer(Modifier.size(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    e.userName.ifEmpty { "好友" },
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = e.content.ifEmpty { e.songName },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (e.songName.isNotEmpty() && e.content.isNotEmpty()) {
                                    Text(
                                        text = "♪ ${e.songName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                if (follows.isEmpty()) {
                    item { EmptyHint("暂无关注或当前音乐源不支持") }
                } else {
                    items(follows) { f ->
                        GlassRow(onClick = {}) {
                            RoundCover(url = f.avatarUrl, size = 30.dp)
                            Spacer(Modifier.size(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.nick.ifEmpty { "用户" },
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = f.signature.ifEmpty { "已关注" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SocialTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 26.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f)
                },
            )
            .clickable(onClick = onClick),
    ) {
        Text(
            if (count > 0) "$label $count" else label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}