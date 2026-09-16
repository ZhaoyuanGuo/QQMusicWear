package com.qmusic.wear.ui.queue

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import coil3.compose.AsyncImage
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.PlayMode
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.ui.components.EdgeProgressRing
import com.qmusic.wear.ui.components.SwipeBackBox
import com.qmusic.wear.ui.components.rememberHaptics
import com.qmusic.wear.ui.components.rotaryList
import kotlinx.coroutines.launch

/**
 * 播放队列（参考磁音手表版 QueueSheet 布局）：播放页上滑进入的二级界面。
 * - 头部：左侧 随机/循环 两个圆形模式钮（激活态品牌绿）｜居中「队列」标题 + 「n / N」计数｜右侧绿色唱片定位钮
 * - 标题下一条细分隔线
 * - 列表：扁平行 = 圆角封面块 + 歌名/歌手；当前曲目行尾绿色播放动效条
 * - 点按切歌，长按移除出队列；额外叠加我们自己的边缘环形进度条
 */
@Composable
fun QueueScreen(
    onBack: () -> Unit,
) {
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val mode by ServiceLocator.player.playMode.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val progress = if (now.durationMs > 0) {
        (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f)
    } else 0f

    SwipeBackBox(onBack = onBack) {
        // 磁音队列页没有进度环，这里按需求额外加上我们自己的边缘环形进度条
        EdgeProgressRing(
            progress = progress,
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp),
        )
        ScreenScaffold(
            scrollState = listState,
            timeText = { TimeText() },
        ) { contentPadding ->
            ScalingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize().rotaryList(listState),
            ) {
                // 头部：模式钮 | 标题+计数 | 定位钮（磁音三段式）
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RoundModeButton(
                                resId = R.drawable.ic_shuffle,
                                label = "随机播放",
                                active = mode == PlayMode.RANDOM,
                                onClick = {
                                    ServiceLocator.player.setPlayMode(
                                        if (mode == PlayMode.RANDOM) PlayMode.SEQUENTIAL else PlayMode.RANDOM,
                                    )
                                },
                            )
                            RoundModeButton(
                                resId = if (mode == PlayMode.REPEAT_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                                label = "循环模式",
                                active = mode != PlayMode.RANDOM,
                                onClick = {
                                    ServiceLocator.player.setPlayMode(
                                        if (mode == PlayMode.REPEAT_ONE) PlayMode.SEQUENTIAL else PlayMode.REPEAT_ONE,
                                    )
                                },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "队列",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${(now.queueIndex + 1).coerceAtLeast(0)} / ${now.queue.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // 右上角绿色唱片钮：定位回当前播放曲目
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable {
                                    if (now.queueIndex >= 0) {
                                        scope.launch { listState.scrollToItem(now.queueIndex + 2) }
                                    }
                                },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_qm_play),
                                contentDescription = "回到当前曲目",
                                tint = Color.Black.copy(alpha = 0.72f),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                // 标题下细分隔线（磁音同款）
                item {
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .width(110.dp)
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.14f)),
                    )
                }

                // 空队列占位
                if (now.queue.isEmpty()) {
                    item {
                        Text(
                            "队列空空如也",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                itemsIndexed(now.queue) { index, song ->
                    QueueRow(
                        song = song,
                        isCurrent = index == now.queueIndex,
                        onClick = { ServiceLocator.player.playAt(index) },
                        onRemove = {
                            if (index == now.queueIndex) {
                                android.widget.Toast.makeText(ctx, "正在播放的曲目不可移除", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                ServiceLocator.player.removeAt(index)
                                android.widget.Toast.makeText(ctx, "已从队列移除", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }

                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

/** 圆形模式小钮（磁音样式）：激活态品牌绿圆底 + 深色图标，未激活灰色圆底 */
@Composable
private fun RoundModeButton(
    resId: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
            )
            .clickable {
                haptics.tap()
                onClick()
            },
    ) {
        Icon(
            painter = painterResource(resId),
            contentDescription = label,
            tint = if (active) Color.Black.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** 队列单曲行（磁音扁平样式）：圆角封面块 + 歌名/歌手；当前曲目行尾绿色播放动效条；长按移除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onRemove)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = song.cover300,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.singers,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            PlayingBars()
        }
    }
}

/** 绿色播放动效条（磁音当前曲目行尾的三根跳动小条） */
@Composable
private fun PlayingBars() {
    val tint = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "playing_bars")
    val h1 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "bar1",
    )
    val h2 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(640), RepeatMode.Reverse),
        label = "bar2",
    )
    val h3 by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520, delayMillis = 90), RepeatMode.Reverse),
        label = "bar3",
    )
    val heights = listOf(h1, h2, h3)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(start = 6.dp),
    ) {
        heights.forEach { h ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((3.dp + 9.dp * h))
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(tint),
            )
        }
    }
}
