package com.qmusic.wear.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import coil3.compose.AsyncImage
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.ui.components.rotaryList
import com.qmusic.wear.ui.components.rememberHaptics
import com.qmusic.wear.ui.mine.MineOverlay

/**
 * 首页 = 纯推荐音乐大卡片流（借鉴手机版 QQ 音乐首页推荐板块，圆屏适配）：
 * - 「每日30首」大卡（品牌绿）：代表歌曲 + 播放键，点卡片进每日推荐列表
 * - 「猜你想听」大卡（紫）：随机开播推荐歌曲
 * - 「排行榜」大卡（橙）：巅峰榜/热歌/新歌等榜单入口
 * - 「歌单广场」大卡（蓝）：推荐/分类歌单入口
 * - 我喜欢/我的歌单等账号内容在右滑「我的」页
 */
@Composable
fun HomeScreen(
    onOpenPlayer: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenDaily: () -> Unit,
    onOpenRank: () -> Unit,
    onOpenSquare: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var swipeAcc by remember { mutableFloatStateOf(0f) }
    val haptics = rememberHaptics()

    Box(Modifier.fillMaxSize()) {
        ScreenScaffold(
            scrollState = listState,
            timeText = { TimeText() },
        ) { contentPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                swipeAcc += amount
                            },
                            onDragEnd = {
                                // 左右滑均打开「我的」选项卡（阈值防误触）
                                if (kotlin.math.abs(swipeAcc) > 110f) {
                                    haptics.confirm()
                                    showMenu = true
                                }
                                swipeAcc = 0f
                            },
                        )
                    },
            ) {
                ScalingLazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = contentPadding.calculateTopPadding() + 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 30.dp,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize().rotaryList(listState),
                ) {
                    // ---- 大卡 1：每日30首 ----
                    item {
                        val rep = now.song?.takeIf { s -> ui.songs.any { it.mid == s.mid } }
                            ?: ui.songs.firstOrNull()
                        BigCard(
                            title = "每日30首",
                            subtitle = if (ui.songs.isNotEmpty()) "为你推荐 · ${ui.songs.size} 首" else "正在加载…",
                            coverUrl = rep?.cover300.orEmpty(),
                            songName = rep?.name.orEmpty(),
                            singers = rep?.singers.orEmpty(),
                            playingThis = now.isPlaying && rep != null && now.song?.mid == rep.mid,
                            loading = ui.loading && ui.songs.isEmpty(),
                            colors = listOf(Color(0xFF1C2B22), Color(0xFF121813)),
                            onToggle = {
                                if (rep != null) {
                                    if (now.song?.mid == rep.mid) {
                                        ServiceLocator.player.togglePlayPause()
                                    } else {
                                        vm.playFrom(ui.songs, rep.mid)
                                    }
                                    onOpenPlayer()
                                }
                            },
                            onOpen = onOpenDaily,
                        )
                    }

                    // ---- 大卡 2：猜你想听（随机开播） ----
                    if (ui.songs.isNotEmpty()) {
                        item {
                            val lucky = remember(ui.songs) { ui.songs.randomOrNull() }
                            BigCard(
                                title = "猜你想听",
                                subtitle = "私人雷达 · 随心听",
                                coverUrl = lucky?.cover300.orEmpty(),
                                songName = lucky?.name.orEmpty(),
                                singers = lucky?.singers.orEmpty(),
                                playingThis = false,
                                loading = false,
                                colors = listOf(Color(0xFF232033), Color(0xFF15131D)),
                                onToggle = {
                                    lucky?.let {
                                        vm.playFrom(ui.songs, it.mid)
                                        onOpenPlayer()
                                    }
                                },
                                onOpen = onOpenDaily,
                            )
                        }
                    }

                    // ---- 大卡 3：排行榜 ----
                    item {
                        BigCard(
                            title = "排行榜",
                            subtitle = "巅峰榜 · 热歌 · 新歌",
                            coverUrl = "",
                            songName = "",
                            singers = "",
                            playingThis = false,
                            loading = false,
                            colors = listOf(Color(0xFF2E2318), Color(0xFF1B1510)),
                            onToggle = onOpenRank,
                            onOpen = onOpenRank,
                        )
                    }

                    // ---- 大卡 4：歌单广场 ----
                    item {
                        BigCard(
                            title = "歌单广场",
                            subtitle = "官方精选歌单",
                            coverUrl = "",
                            songName = "",
                            singers = "",
                            playingThis = false,
                            loading = false,
                            colors = listOf(Color(0xFF1B2836), Color(0xFF111821)),
                            onToggle = onOpenSquare,
                            onOpen = onOpenSquare,
                        )
                    }
                }
            }

            // 「我的」页：右滑出现（搜索/最近播放/下载/设置/账号都在这里）
            AnimatedVisibility(
                visible = showMenu,
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
            ) {
                MineOverlay(
                    onDismiss = { showMenu = false },
                    onOpenPlayer = {
                        showMenu = false
                        onOpenPlayer()
                    },
                    onOpenRecent = { showMenu = false; onOpenRecent() },
                    onOpenDownloads = { showMenu = false; onOpenDownloads() },
                    onOpenSettings = { showMenu = false; onOpenSettings() },
                    onOpenPlaylist = { id, title ->
                        showMenu = false
                        onOpenPlaylist(id, title)
                    },
                )
            }
        }
    }
}

/** 官方风格卡片配色轮换（紫/橙/蓝/青，备用） */
private val cardColors = listOf(
    listOf(Color(0xFF232033), Color(0xFF15131D)),
    listOf(Color(0xFF2E2318), Color(0xFF1B1510)),
    listOf(Color(0xFF1B2836), Color(0xFF111821)),
    listOf(Color(0xFF1C2F2B), Color(0xFF101917)),
)

/**
 * 大卡片（官方 HomePlayView 圆屏适配版）：
 * 固定高度分区布局——左上标题/副标题、右侧居中封面、底部播放键+代表歌曲，互不重叠。
 */
@Composable
private fun BigCard(
    title: String,
    subtitle: String,
    coverUrl: String,
    songName: String,
    singers: String,
    playingThis: Boolean,
    loading: Boolean,
    colors: List<Color>,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    // 按压弹性反馈：按下缩至0.97，松开回弹
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "card_press")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 圆屏适配：左右收窄，避免直角边角被圆形表盘裁切
            .padding(horizontal = 18.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .height(104.dp)
            .clip(shape)
            .background(Brush.verticalGradient(colors))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onOpen,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // 右侧居中封面
        if (coverUrl.isNotEmpty()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_playlist),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        // 左上标题 + 副标题（右留出封面宽度）
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(end = 68.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.66f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 底部：播放键 + 代表歌曲（右留出封面宽度）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(end = 68.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp))
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.94f))
                        .clickable(onClick = onToggle),
                ) {
                    Icon(
                        painter = painterResource(
                            if (playingThis) R.drawable.ic_qm_pause else R.drawable.ic_qm_play,
                        ),
                        contentDescription = if (playingThis) "暂停" else "播放",
                        tint = Color(0xFF0E1512),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Column {
                Text(
                    text = songName.ifEmpty { "点击开始聆听" },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = singers,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
