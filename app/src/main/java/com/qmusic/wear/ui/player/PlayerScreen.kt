package com.qmusic.wear.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.ui.components.BlurCoverBackground
import com.qmusic.wear.ui.components.CoverColors
import com.qmusic.wear.ui.components.EdgeProgressRing
import com.qmusic.wear.ui.components.rememberCoverColor
import com.qmusic.wear.util.msTo_mmss

/**
 * 播放页（QQ 音乐手机版控件风格，480×480 圆屏）：
 * - 背景：封面高斯模糊铺满全屏
 * - 进度：环绕屏幕边缘的进度环（顶部缺口给时间显示）
 * - 中心：歌名/歌手 + 双三角切换键 + 品牌绿播放键 + 副控制（无封面图）
 * - 左右滑或点歌词按钮进歌词页
 */
@Composable
fun PlayerScreen(
    onOpenLyrics: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    vm: PlayerViewModel = viewModel(),
) {
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val playMode by ServiceLocator.player.playMode.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var swipeAcc by remember { mutableFloatStateOf(0f) }

    val progress = if (now.durationMs > 0) {
        (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f)
    } else 0f

    Box(Modifier.fillMaxSize()) {
        // 封面高斯模糊铺满整个圆形屏幕
        BlurCoverBackground(coverUrl = now.song?.cover500.orEmpty(), blurRadius = 46.dp, scrim = 0.62f)

        // 自适应主色晕染（借鉴官方「播放器适配歌曲自动变色」）：封面主色径向渐变叠加
        val coverTint = rememberCoverColor(now.song?.cover500.orEmpty())
        val tunedTint = remember(coverTint) {
            coverTint?.let { CoverColors.tune(it, minLum = 0.18f, maxLum = 0.42f) }
        }
        if (tunedTint != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(tunedTint.copy(alpha = 0.32f), Color.Transparent),
                        ),
                    ),
            )
        }

        // 环绕屏幕边界的播放进度（不受内容边距影响，正圆居中）
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
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                swipeAcc += amount
                            },
                            onDragEnd = {
                                // 左右滑均可进入歌词页
                                if (kotlin.math.abs(swipeAcc) > 100f) onOpenLyrics()
                                swipeAcc = 0f
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
                        playMode = playMode,
                        vm = vm,
                        onOpenDownloads = onOpenDownloads,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerContent(
    now: com.qmusic.wear.data.player.NowPlaying,
    playMode: com.qmusic.wear.data.model.PlayMode,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // ---- 顶部文字块：圆形小封面 / 歌名 / 歌手 / 时间（不侵入底部弧形按钮区） ----
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 圆形小封面（借鉴官方播放页封面位，圆屏适配：小圆图 + 细描边）
            val cover = now.song?.cover500.orEmpty()
            if (cover.isNotEmpty()) {
                coil3.compose.AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.22f), CircleShape),
                )
                Spacer(Modifier.height(5.dp))
            }
            Text(
                text = now.song?.name.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 26.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = now.song?.singers.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 30.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${now.positionMs.msTo_mmss()} / ${now.durationMs.msTo_mmss()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))

            // 主控制行（QQ 音乐手机版）：双三角切换键 + 品牌绿播放键
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QmIconButton(
                    resId = R.drawable.ic_qm_prev,
                    contentDescription = stringResource(R.string.cd_prev),
                    iconSize = 22.dp,
                    onClick = { ServiceLocator.player.previous() },
                )
                QmPlayPauseButton(playing = now.isPlaying)
                QmIconButton(
                    resId = R.drawable.ic_qm_next,
                    contentDescription = stringResource(R.string.cd_next),
                    iconSize = 22.dp,
                    onClick = { ServiceLocator.player.next() },
                )
            }
        }

        // ---- 副控制：沿进度环内侧底部弧形排列（避开顶部文字块与进度环） ----
        SubControlsArc(
            playMode = playMode,
            vm = vm,
            onOpenDownloads = onOpenDownloads,
        )
    }
}

/**
 * 底部弧形副控件组：五个小圆钮以屏幕中心为圆心、沿边缘进度环内侧的
 * 圆弧（42°..138°）排布，既贴合圆屏轮廓又不与进度环和顶部文字块重合。
 * 顺序：播放模式 / 喜欢 / 音量 / 音质 / 下载。歌词入口保留在左右滑手势。
 */
@Composable
private fun SubControlsArc(
    playMode: com.qmusic.wear.data.model.PlayMode,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 半径 = 屏幕半径 - 进度环区 - 控件半径 - 间隙
        val radius = (minOf(maxWidth, maxHeight) / 2) - 30.dp
        // 屏幕角度：0°=正右，90°=正下；五个角度左右对称覆盖底部弧
        val angles = listOf(42f, 66f, 90f, 114f, 138f)
        val download = vm.ui.collectAsStateWithLifecycle().value.download
        val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
        val downloads by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
        val likedMids by ServiceLocator.repository.likedMids.collectAsStateWithLifecycle()
        val curSong = now.song
        val liked = curSong != null && likedMids.contains(curSong.mid)

        SubControlChip(
            resId = when (playMode) {
                com.qmusic.wear.data.model.PlayMode.SEQUENTIAL -> R.drawable.ic_repeat
                com.qmusic.wear.data.model.PlayMode.REPEAT_ONE -> R.drawable.ic_repeat_one
                com.qmusic.wear.data.model.PlayMode.RANDOM -> R.drawable.ic_shuffle
            },
            contentDescription = playMode.label,
            tint = if (playMode == com.qmusic.wear.data.model.PlayMode.SEQUENTIAL) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            onClick = { vm.cyclePlayMode() },
            modifier = Modifier
                .align(Alignment.Center)
                .arcOffset(angles[0], radius),
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
            modifier = Modifier
                .align(Alignment.Center)
                .arcOffset(angles[1], radius),
        )
        SubControlChip(
            resId = R.drawable.ic_volume,
            contentDescription = stringResource(R.string.player_volume),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { vm.toggleVolume() },
            modifier = Modifier
                .align(Alignment.Center)
                .arcOffset(angles[2], radius),
        )
        SubControlChip(
            resId = R.drawable.ic_quality,
            contentDescription = stringResource(R.string.player_quality),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { vm.toggleQuality() },
            modifier = Modifier
                .align(Alignment.Center)
                .arcOffset(angles[3], radius),
        )
        // 下载控件：空闲=下载图标 / 下载中=进度环 / 完成=对勾
        val downloaded = now.song?.let { s -> downloads.any { it.song.mid == s.mid } } == true
        when {
            download.running -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .arcOffset(angles[4], radius)
                    .size(28.dp),
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
                modifier = Modifier
                    .align(Alignment.Center)
                    .arcOffset(angles[4], radius),
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
                modifier = Modifier
                    .align(Alignment.Center)
                    .arcOffset(angles[4], radius),
            )
        }
    }
}

/** 极坐标偏移：需配合 align(Center) 使用，偏移到 (radius, angle) 处 */
private fun Modifier.arcOffset(angleDeg: Float, radius: androidx.compose.ui.unit.Dp): Modifier =
    offset {
        val rad = Math.toRadians(angleDeg.toDouble())
        val r = radius.roundToPx()
        IntOffset(
            (r * kotlin.math.cos(rad)).roundToInt(),
            (r * kotlin.math.sin(rad)).roundToInt(),
        )
    }

/** 裸图标按钮（QQ 音乐手机版切换键样式：白色双三角，无底板） */
@Composable
private fun QmIconButton(
    resId: Int,
    contentDescription: String,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(iconSize + 12.dp)
            .clickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(resId),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 大号播放/暂停键：品牌绿圆底 + 白色图标 + 光晕 + 弹性缩放（QQ 音乐手机版） */
@Composable
private fun QmPlayPauseButton(playing: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (playing) 1f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "play_scale",
    )
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
                .size(58.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), CircleShape),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { ServiceLocator.player.togglePlayPause() },
        ) {
            Icon(
                painter = if (playing) painterResource(R.drawable.ic_qm_pause)
                else painterResource(R.drawable.ic_qm_play),
                contentDescription = stringResource(R.string.cd_play_pause),
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 副控制小圆钮：半透明底 + 细描边（可选长按） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SubControlChip(
    resId: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Icon(
            painter = painterResource(resId),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
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
            .clip(RoundedCornerShape(24.dp))
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
            .clip(RoundedCornerShape(24.dp))
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
