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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import coil3.compose.AsyncImage
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.HomeCard
import com.qmusic.wear.ui.components.QmScreenScaffold
import com.qmusic.wear.ui.components.edgeScalingParams
import com.qmusic.wear.ui.components.qmAutoCentering
import com.qmusic.wear.ui.components.qmRotarySnap
import com.qmusic.wear.ui.components.rememberHaptics
import com.qmusic.wear.ui.theme.LocalIsRoundScreen
import com.qmusic.wear.ui.mine.MineOverlay

/**
 * 首页 = 推荐大卡片流（由当前音乐源的 homeFeed 契约给出，数量与内容随源变化）：
 * 每张大卡含标题/副标题/封面/代表歌曲与动作；点击按 action 分发到对应页面或直接开播。
 * 关闭/离线时退到「已下载音乐」。
 */
@Composable
fun HomeScreen(
    onOpenPlayer: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenArtist: (String, String) -> Unit = { _, _ -> },
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
    onOpenPodcast: () -> Unit = {},
    onOpenSocial: () -> Unit = {},
    /** 卡片动作分发：action / targetId / 标题 */
    onOpenCard: (String, String, String) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var swipeAcc by remember { mutableFloatStateOf(0f) }
    val haptics = rememberHaptics()

    // 离线兜底：无网时首页直接落到「已下载音乐」，推荐卡不可用不再整屏报错
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val downloads by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
    var offline by remember { mutableStateOf(!com.qmusic.wear.util.isNetworkOnline(ctx)) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                offline = !com.qmusic.wear.util.isNetworkOnline(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize()) {
        QmScreenScaffold(
            scrollState = listState,
            timeText = { TimeText() },
            // 「我的」页打开时隐藏首页指示条，避免与其自带的指示条同位置叠画两根
            showScrollIndicator = !showMenu,
        ) { contentPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    // 焦点组：与「我的」页互斥持焦——「我的」页打开时本列表让出表冠焦点，
                    // 关闭时（active 翻转）触发内置焦点协调重新把焦点还给本列表
                    .hierarchicalFocusGroup(active = !showMenu)
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
                    scalingParams = edgeScalingParams(),
                    state = listState,
                    // 表冠滚动走 ScalingLazyColumn 内置支持（snap 手感），
                    // 不再外挂 rotaryScrollable——外挂层会与内置焦点协调抢焦点
                    rotaryScrollableBehavior = qmRotarySnap(listState),
                    contentPadding = PaddingValues(
                        top = contentPadding.calculateTopPadding() + 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 30.dp,
                    ),
                    autoCentering = qmAutoCentering(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (!offline) {
                        if (ui.loading && ui.cards.isEmpty()) {
                            item { CircularProgressIndicator() }
                        }
                        items(ui.cards) { card ->
                            val index = ui.cards.indexOf(card)
                            val rep = card.songs.firstOrNull()
                            BigCard(
                                title = card.title,
                                subtitle = card.subtitle.ifEmpty {
                                    if (card.songs.isNotEmpty()) "为你推荐 · ${card.songs.size} 首" else ""
                                },
                                coverUrl = card.coverUrl.ifEmpty { rep?.cover300.orEmpty() },
                                songName = card.songName.ifEmpty { rep?.name.orEmpty() },
                                singers = card.singers.ifEmpty { rep?.singers.orEmpty() },
                                playingThis = now.isPlaying && rep != null && now.song?.mid == rep.mid,
                                loading = ui.loading && card.songs.isEmpty(),
                                colors = cardColors(card, index),
                                onToggle = {
                                    when {
                                        rep != null && now.song?.mid == rep.mid ->
                                            ServiceLocator.player.togglePlayPause()

                                        rep != null -> vm.playFrom(card.songs, rep.mid)
                                        else -> {}
                                    }
                                    if (rep != null) onOpenPlayer()
                                },
                                onOpen = { onOpenCard(card.action, card.targetId, card.title) },
                            )
                        }
                    } else {
                        // ---- 离线模式：只保留可离线播放的能力 ----
                        item {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_download),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text("当前无网络连接", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (downloads.isEmpty()) "暂无已下载歌曲" else "已下载 ${downloads.size} 首可离线播放",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (downloads.isNotEmpty()) {
                            item {
                                BigCard(
                                    title = "已下载音乐",
                                    subtitle = "离线播放 · ${downloads.size} 首",
                                    coverUrl = downloads.first().song.cover300,
                                    songName = downloads.first().song.name,
                                    singers = downloads.first().song.singers,
                                    playingThis = false,
                                    loading = false,
                                    colors = cardColors(null, 0),
                                    onToggle = {
                                        ServiceLocator.player.playFromList(
                                            downloads.map { it.song },
                                            downloads.first().song.mid,
                                        )
                                        onOpenPlayer()
                                    },
                                    onOpen = onOpenDownloads,
                                )
                            }
                        }
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
                    onOpenArtist = { mid, name ->
                        showMenu = false
                        onOpenArtist(mid, name)
                    },
                    onOpenAlbum = { mid, name ->
                        showMenu = false
                        onOpenAlbum(mid, name)
                    },
                    onOpenPodcast = { showMenu = false; onOpenPodcast() },
                    onOpenSocial = { showMenu = false; onOpenSocial() },
                )
            }
        }
    }
}

/** 卡片渐变：优先源给定配色，否则按序轮换官方风配色（紫/橙/蓝/青） */
private fun cardColors(card: HomeCard?, index: Int): List<Color> {
    val start = card?.colorStart
    val end = card?.colorEnd
    if (start != null && end != null) return listOf(Color(start), Color(end))
    return cardPalette[index % cardPalette.size]
}

/** 官方风格卡片配色轮换（紫/橙/蓝/青） */
private val cardPalette = listOf(
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
    // 屏幕形状：圆表保持弧边留白；方表由物理黑边承担边界，卡片直接顶屏
    val isRound = LocalIsRoundScreen.current
    val hPad = if (isRound) 18.dp else 2.dp
    // 按压弹性反馈：按下缩至0.97，松开回弹
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "card_press")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 圆屏适配：左右收窄，避免直角边角被圆形表盘裁切；方屏收窄留白
            .padding(horizontal = hPad)
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
                .padding(end = if (isRound) 68.dp else 62.dp),
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
                .padding(end = if (isRound) 68.dp else 62.dp),
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
            Column(Modifier.weight(1f)) {
                Text(
                    text = songName.ifEmpty { "点击进入" },
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