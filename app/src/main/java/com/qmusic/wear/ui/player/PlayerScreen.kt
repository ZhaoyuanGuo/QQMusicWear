package com.qmusic.wear.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import coil3.compose.AsyncImage
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.ui.components.BlurCoverBackground
import com.qmusic.wear.ui.components.EdgeProgressRing
import com.qmusic.wear.ui.components.rememberCoverColor
import com.qmusic.wear.ui.components.rememberHaptics
import com.qmusic.wear.ui.components.rotaryGeneric
import com.qmusic.wear.ui.theme.LocalIsAmbient
import kotlin.math.abs
import androidx.compose.foundation.layout.fillMaxHeight

/**
 * 播放页（参考磁音手表版圆盘布局，480×480 圆屏）：
 * - 背景：封面高斯模糊铺满全屏（One UI Watch 质感）
 * - 中央：专辑封面大圆盘，歌名/歌手在盘内上部，上一首/播放/下一首精确位于盘心
 * - 底部：一排小圆钮（音量 / 喜欢 / 音质 / 下载），播放模式入口移至队列页
 * - 保留我们自己的环绕屏幕边缘进度环；
 *   手势：手指左滑进歌词页、右滑返回主页、上滑进队列
 */
@Composable
fun PlayerScreen(
    onOpenLyrics: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onBack: () -> Unit = {},
    vm: PlayerViewModel = viewModel(),
) {
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val isAmbient = LocalIsAmbient.current
    val haptics = rememberHaptics()
    var swipeAcc by remember { mutableFloatStateOf(0f) }
    var swipeAccY by remember { mutableFloatStateOf(0f) }

    // 表冠调音量时的临时音量指示（音量面板未打开时弹出，约1秒自动消失）
    var volFlashTick by remember { mutableIntStateOf(0) }
    var volFlashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(volFlashTick) {
        if (volFlashTick > 0) {
            volFlashVisible = true
            delay(900)
            volFlashVisible = false
        }
    }

    // 表冠旋转调音量：自定义 ScrollableState，累计增量每 40px 触发一档音量步进
    val volAcc = remember { mutableFloatStateOf(0f) }
    val volScrollState = remember {
        object : ScrollableState {
            override val isScrollInProgress: Boolean get() = false
            override fun dispatchRawDelta(delta: Float): Float {
                // 仅在音量面板打开时表冠才调音量，防止误触
                if (!ui.showVolume) return 0f
                volAcc.floatValue += delta
                var consumed = 0f
                while (abs(volAcc.floatValue) >= 40f) {
                    val dir = if (volAcc.floatValue > 0f) 1 else -1
                    val next = (ServiceLocator.player.currentVolume + dir)
                        .coerceIn(0, ServiceLocator.player.maxVolume)
                    if (next != ServiceLocator.player.currentVolume) {
                        vm.setVolume(next)
                        haptics.tick()
                        volFlashTick++
                    }
                    consumed += 40f * dir
                    volAcc.floatValue -= 40f * dir
                }
                return consumed
            }
            override suspend fun scroll(
                scrollPriority: MutatePriority,
                block: suspend ScrollScope.() -> Unit,
            ) {
                // 表冠为增量事件：把滚动范围委托给 dispatchRawDelta 直接消费
                block(object : ScrollScope {
                    override fun scrollBy(pixels: Float): Float = dispatchRawDelta(pixels)
                })
            }
        }
    }

    val progress = if (now.durationMs > 0) {
        (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f)
    } else 0f

    Box(Modifier.fillMaxSize()) {
        // 封面高斯模糊铺满全屏（修复：封面只在小圆盘内、四周留黑的问题）；AOD 下再压暗
        BlurCoverBackground(
            coverUrl = now.song?.cover500.orEmpty(),
            scrim = if (isAmbient) 0.72f else 0.55f,
        )

        // 环绕屏幕边界的播放进度（保留我们自己的环形进度条，不受内容边距影响）
        EdgeProgressRing(
            progress = progress,
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp),
        )

        ScreenScaffold(
            timeText = { TimeText() },
        ) { contentPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .rotaryGeneric(volScrollState)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                swipeAcc += amount
                            },
                            onDragEnd = {
                                // 手指向左滑进歌词页，向右滑返回主页（页面流方向，与二级页右滑返回一致）
                                when {
                                    swipeAcc < -100f -> {
                                        haptics.confirm()
                                        onOpenLyrics()
                                    }
                                    swipeAcc > 100f -> {
                                        haptics.confirm()
                                        onBack()
                                    }
                                }
                                swipeAcc = 0f
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        // 上滑进入播放队列（磁音同款二级界面入口）
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                swipeAccY += amount
                            },
                            onDragEnd = {
                                if (swipeAccY < -80f) {
                                    haptics.confirm()
                                    onOpenQueue()
                                }
                                swipeAccY = 0f
                            },
                        )
                    },
            ) {
                when {
                    ui.showQuality -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        QualityPicker(
                            selected = ui.selectedQuality,
                            switching = ui.switching,
                            onSelect = vm::selectQuality,
                            onDismiss = vm::toggleQuality,
                        )
                    }

                    ui.showVolume -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        VolumeSection(ui = ui, vm = vm)
                    }

                    now.song == null -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "正在准备播放…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> PlayerContent(
                        now = now,
                        vm = vm,
                        onOpenDownloads = onOpenDownloads,
                    )
                }
            }
        }

        // 表冠音量临时指示：玻璃小胶囊，约1秒自动消失
        AnimatedVisibility(
            visible = volFlashVisible && !ui.showVolume,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_volume),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "音量 ${ui.volume}/${ui.maxVolume}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun PlayerContent(
    now: com.qmusic.wear.data.player.NowPlaying,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
) {
    val isAmbient = LocalIsAmbient.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // ---- 中央大圆盘（磁音同款）：封面填充，占短边 86%，与屏幕/进度环同心（不超环） ----
        val disc = minOf(maxWidth, maxHeight) * 0.86f
        val coverTint = rememberCoverColor(now.song?.cover500.orEmpty())

        // 播放时封面缓慢旋转（约30秒/圈），暂停即停并轻微变暗；AOD 下静止
        var discRotation by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(now.isPlaying, isAmbient) {
            if (now.isPlaying && !isAmbient) {
                var lastFrame = withFrameNanos { it }
                while (true) {
                    withFrameNanos { frameTime ->
                        val dt = (frameTime - lastFrame) / 1_000_000_000f
                        lastFrame = frameTime
                        discRotation = (discRotation + dt * 12f) % 360f
                    }
                }
            }
        }
        val coverAlpha by animateFloatAsState(
            targetValue = if (now.isPlaying) 1f else 0.82f,
            label = "cover_alpha",
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .size(disc)
                .clip(CircleShape)
                .background(coverTint ?: MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = now.song?.cover500.orEmpty(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = discRotation
                        alpha = coverAlpha
                    },
            )
            // 整体只轻微压暗（AOD 下再压暗一档降亮度），让封面更透亮
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isAmbient) 0.50f else 0.10f)),
            )
            // 顶部局部渐变 scrim：只压暗歌名区域，保证可读性的同时不糊住整个封面
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.42f)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = if (isAmbient) 0.55f else 0.45f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            // 控制键组：精确位于盘心——圆心处横向弦最宽，小圆屏也不会裁掉两侧切歌键
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                QmIconButton(
                    resId = R.drawable.ic_qm_prev,
                    contentDescription = stringResource(R.string.cd_prev),
                    iconSize = 18.dp,
                    onClick = { ServiceLocator.player.previous() },
                )
                QmPlayPauseButton(playing = now.isPlaying, isAmbient = isAmbient)
                QmIconButton(
                    resId = R.drawable.ic_qm_next,
                    contentDescription = stringResource(R.string.cd_next),
                    iconSize = 18.dp,
                    onClick = { ServiceLocator.player.next() },
                )
            }

            // 歌名/歌手：盘内上部（避开盘心控件组）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = disc * 0.15f),
            ) {
                Text(
                    text = now.song?.name.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = now.song?.singers.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.60f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }

        // ---- 底部副控件行（磁音：播放页下方一排小圆钮），播放模式入口已移至队列页 ----
        SubControlsRow(
            now = now,
            vm = vm,
            onOpenDownloads = onOpenDownloads,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }
}

/**
 * 底部副控件行：音量 / 喜欢 / 音质 / 下载 四个小圆钮横排。
 * 播放模式切换入口移至队列页头部（磁音同款），歌词入口仅为左右滑手势。
 */
@Composable
private fun SubControlsRow(
    now: com.qmusic.wear.data.player.NowPlaying,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val uiState = vm.ui.collectAsStateWithLifecycle().value
    val download = uiState.download
    val downloads by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
    val likedMids by ServiceLocator.repository.likedMids.collectAsStateWithLifecycle()
    val curSong = now.song
    val liked = curSong != null && likedMids.contains(curSong.mid)
    val downloaded = curSong?.let { s -> downloads.any { it.song.mid == s.mid } } == true

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // 命中区扩到42dp后缩小间距，视觉节奏接近原样
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        SubControlChip(
            resId = R.drawable.ic_volume,
            contentDescription = stringResource(R.string.player_volume),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { vm.toggleVolume() },
        )
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
        SubControlChip(
            resId = R.drawable.ic_quality,
            contentDescription = stringResource(R.string.player_quality),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // 角标显示当前音质，不点开也能看到（HQ/SQ/HR/128）
            badge = when (uiState.selectedQuality) {
                Quality.STANDARD -> "128"
                Quality.HIGH -> "HQ"
                Quality.LOSSLESS -> "SQ"
                Quality.HI_RES -> "HR"
            },
            onClick = { vm.toggleQuality() },
        )
        // 下载控件：空闲=下载图标 / 下载中=进度环 / 完成=对勾
        when {
            download.running -> Box(
                contentAlignment = Alignment.Center,
                // 与其余按钮的42dp命中区对齐，避免状态切换时行内抖动
                modifier = Modifier.size(42.dp),
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
    }
}

/** 裸图标按钮（磁音样式：半透明白色圆底 + 白色图标）；命中区扩到42dp提升手指容错 */
@Composable
private fun QmIconButton(
    resId: Int,
    contentDescription: String,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(42.dp)
            .clickable {
                haptics.tap()
                onClick()
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(iconSize + 16.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
        ) {
            Icon(
                painter = painterResource(resId),
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** 大号播放/暂停键：品牌绿圆底 + 白色图标 + 呼吸光晕 + 弹性缩放（QQ 音乐手机版） */
@Composable
private fun QmPlayPauseButton(playing: Boolean, isAmbient: Boolean) {
    val haptics = rememberHaptics()
    val scale by animateFloatAsState(
        targetValue = if (playing) 1f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "play_scale",
    )
    // 播放时光晕呼吸起伏；暂停/AOD 静止为低亮度
    val glowAlpha: Float = if (playing && !isAmbient) {
        val pulse by rememberInfiniteTransition(label = "glow").animateFloat(
            initialValue = 0.16f,
            targetValue = 0.30f,
            animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
            label = "glow_pulse",
        )
        pulse
    } else 0.13f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        // 外圈光晕
        Box(
            Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha), CircleShape),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    haptics.tap()
                    ServiceLocator.player.togglePlayPause()
                },
        ) {
            // 线条形变图标：播放三角 ↔ 暂停双杠，按路径逐点插值流动（非缩放/淡入淡出）
            PlayPauseMorphIcon(playing = playing)
        }
    }
}

/**
 * 线条形变图标：播放三角 ↔ 暂停双杠。
 * 原理：两个图标都拆成结构相同的两个四边形路径（各 4 个角点），
 * 形变时逐点插值——双杠的内缘流动倾斜成三角的两条斜边，外缘收拢汇成三角尖。
 */
@Composable
private fun PlayPauseMorphIcon(playing: Boolean) {
    // 形变进度：0 = 播放三角，1 = 暂停双杠（轻微回弹让线条有"流动感"）
    val morph by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "play_morph",
    )
    Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension / 24f
        fun pt(v: Pair<Float, Float>) = Offset(v.first * s, v.second * s)

        // 24 视口下的角点（顺时针：左上 → 右上 → 右下 → 左下）
        // 播放三角（拆左右两半，保证与双杠同为四边形可插值；右半右侧两点重合于尖角）
        val playL = listOf(Pair(7f, 5f), Pair(13f, 8.5f), Pair(13f, 15.5f), Pair(7f, 19f))
        val playR = listOf(Pair(13f, 8.5f), Pair(19f, 12f), Pair(19f, 12f), Pair(13f, 15.5f))
        // 暂停双杠
        val pauseL = listOf(Pair(7.4f, 5f), Pair(11f, 5f), Pair(11f, 19f), Pair(7.4f, 19f))
        val pauseR = listOf(Pair(13f, 5f), Pair(16.6f, 5f), Pair(16.6f, 19f), Pair(13f, 19f))

        fun morphQuad(from: List<Pair<Float, Float>>, to: List<Pair<Float, Float>>): Path {
            val path = Path()
            val pts = from.indices.map { i ->
                val a = pt(from[i])
                val b = pt(to[i])
                Offset(a.x + (b.x - a.x) * morph, a.y + (b.y - a.y) * morph)
            }
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1..3) path.lineTo(pts[i].x, pts[i].y)
            path.close()
            return path
        }

        val white = Color.White
        drawPath(morphQuad(playL, pauseL), white)
        drawPath(morphQuad(playR, pauseR), white)
    }
}

/** 副控制小圆钮：半透明底 + 细描边（可选长按）；命中区扩到42dp（视觉圆底28dp不变），可选角标 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SubControlChip(
    resId: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    badge: String? = null,
) {
    val haptics = rememberHaptics()
    Box(
        contentAlignment = Alignment.Center,
        // 命中区42dp，视觉圆底28dp不变
        modifier = modifier
            .size(42.dp)
            .combinedClickable(
                onClick = {
                    haptics.tap()
                    onClick()
                },
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.09f))
                .border(0.5.dp, Color.White.copy(alpha = 0.14f), CircleShape),
        ) {
            Icon(
                painter = painterResource(resId),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
        }
        // 角标（音质指示）：骑在圆底右下角
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 6.sp, lineHeight = 7.sp),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-4).dp, y = (-5).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 2.dp),
            )
        }
    }
}

/** 音量面板（半透明卡片） */
@Composable
private fun VolumeSection(ui: PlayerUiState, vm: PlayerViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_volume), null, tint = MaterialTheme.colorScheme.primary)
        Slider(
            value = ui.volume,
            onValueChange = { vm.setVolume(it) },
            valueProgression = 0..ui.maxVolume,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "音量 ${ui.volume}/${ui.maxVolume}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.wear.compose.material3.Button(onClick = { vm.toggleVolume() }) {
            Text("完成")
        }
    }
}

/** 音质选择面板（半透明卡片，可滚动防裁切） */
@Composable
private fun QualityPicker(
    selected: Quality,
    switching: Boolean,
    onSelect: (Quality) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState())
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.player_quality),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        PlayerViewModel.allQualities().forEach { q ->
            RadioButton(
                selected = q == selected,
                onSelect = { onSelect(q) },
                label = { Text(q.label) },
                secondaryLabel = {
                    Text(
                        text = when (q) {
                            Quality.STANDARD -> "128kbps MP3"
                            Quality.HIGH -> "320kbps MP3"
                            Quality.LOSSLESS -> "FLAC"
                            Quality.HI_RES -> "最高音质"
                        },
                    )
                },
            )
        }
        if (switching) {
            Text("切换中…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        androidx.wear.compose.material3.Button(onClick = onDismiss) {
            Text("完成")
        }
        Spacer(Modifier.height(6.dp))
    }
}
