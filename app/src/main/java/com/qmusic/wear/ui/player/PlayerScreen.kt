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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import androidx.wear.compose.foundation.rotary.RotaryScrollableBehavior
import coil3.compose.AsyncImage
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.ProgressStyle
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.ui.components.BlurCoverBackground
import com.qmusic.wear.ui.components.EdgeProgressRing
import com.qmusic.wear.ui.components.rememberCoverColor
import com.qmusic.wear.ui.components.rememberHaptics
import com.qmusic.wear.ui.components.rotaryCustom
import com.qmusic.wear.ui.theme.LocalIsAmbient
import com.qmusic.wear.ui.theme.LocalIsRoundScreen
import androidx.compose.foundation.layout.fillMaxHeight

/**
 * 播放页（参考磁音手表版圆盘布局，480×480 圆屏）：
 * - 背景：封面高斯模糊铺满全屏（One UI Watch 质感）
 * - 中央：专辑封面大圆盘，歌名/歌手在盘内上部，上一首/播放/下一首精确位于盘心
 * - 底部：一排小圆钮（音量 / 喜欢 / 音质 / 下载），播放模式入口移至队列页
 * - 保留我们自己的环绕屏幕边缘进度环；
 *   手势：手指左滑进歌词页、右滑返回主页、上滑进队列；
 *   表冠：任意方向旋转进队列页（与队列页顶部回旋返回形成往返）
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
            delay(3500)
            volFlashVisible = false
        }
    }

    // 表冠导航：播放页旋转表冠（任意方向）→ 模拟上滑手势进入队列页；
    // 队列页顶部继续回旋则返回播放页（见 QueueScreen），形成表冠往返切换。
    // 音质面板打开时忽略表冠，避免误触导航；音量调节保留在底部音量钮弹出的 +/− 胶囊。
    val showQualityNow = rememberUpdatedState(ui.showQuality)
    val crownBehavior = remember {
        object : RotaryScrollableBehavior {
            var lastNavMs = 0L
            override suspend fun CoroutineScope.performScroll(
                timestampMillis: Long,
                delta: Float,
                inputDeviceId: Int,
                orientation: Orientation,
            ) {
                if (showQualityNow.value) return
                if (timestampMillis - lastNavMs < 800) return
                lastNavMs = timestampMillis
                haptics.confirm()
                onOpenQueue()
            }
        }
    }

    val progress = if (now.durationMs > 0) {
        (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f)
    } else 0f

    // 进度样式（设置→显示 六选一，默认液体填充）
    val style by ServiceLocator.settingsStore.progressStyleFlow.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // 封面高斯模糊铺满全屏（修复：封面只在小圆盘内、四周留黑的问题）；AOD 下再压暗。
        // 液体填充样式由 LiquidFullScreen 自绘全屏清晰封面 + 水体，跳过此全屏模糊避免双重开销
        if (style != ProgressStyle.LIQUID) {
            BlurCoverBackground(
                coverUrl = now.song?.cover500.orEmpty(),
                scrim = if (isAmbient) 0.72f else 0.55f,
            )
        }

        // 播放进度指示（按 设置→显示 选择的样式分流）：
        // - 圆表且样式未接管圆盘（边框描边/封面描边/点阵）→ 原版边缘进度环
        // - 方表 + 边框描边 → 屏幕圆角边框描边（方表专属）
        // - 液体填充/唱片弧线/波形刻度（圆表已适配）与方表其余样式 → 进度由圆盘/进度条/边框承载，此处不画
        // 音量调节时环/弧临时切换为音量比例（同款样式）
        val isRoundUi = LocalIsRoundScreen.current
        val ratio = if (volFlashVisible && ui.maxVolume > 0) {
            ui.volume.toFloat() / ui.maxVolume
        } else {
            progress
        }
        when {
            isRoundUi &&
                style != ProgressStyle.LIQUID &&
                style != ProgressStyle.VINYL_ARC &&
                style != ProgressStyle.WAVEFORM ->
                EdgeProgressRing(
                    progress = ratio,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(5.dp),
                )

            !isRoundUi && style == ProgressStyle.BORDER ->
                SquareBorderProgress(
                    progress = ratio,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                )
        }

        ScreenScaffold(
            // 播放页不显示系统时间：顶部缺口两侧改放当前/总时长
            timeText = {},
        ) { _ ->
            // 注意：不应用 contentPadding——圆屏内缩会把内容区压到 ~340×284，
            // 导致圆盘缩小、控制行溢出（下一曲键被压缩至零宽消失）、底部钮行悬空。
            // 播放页是全屏沉浸布局（背景/进度环本就在 padding 外），按物理屏幕约束排布。
            Box(
                Modifier
                    .fillMaxSize()
                    .rotaryCustom(crownBehavior)
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
                    onVolumeAdjust = { volFlashTick++ },
                    style = style,
                    progress = progress,
                    ringRatio = ratio,
                )
                }
            }
        }

        // 顶部进度环缺口正中：单行时间 0:07 / 4:29（替代系统时间，AOD 自动降亮度）
        if (now.song != null) {
            val topAlpha = if (isAmbient) 0.45f else 1f
            Text(
                text = "${formatMs(now.positionMs)} / ${formatMs(now.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f * topAlpha),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .fillMaxWidth(),
            )
        }

        // 音量指示（点音量钮触发，约3.5秒自动消失）：
        // 底部小胶囊显示音量值并提供触控加减，播放进度环同时切换为音量弧
        AnimatedVisibility(
            visible = volFlashVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VolumeStepButton("−") {
                    vm.setVolume((ui.volume - 1).coerceIn(0, ui.maxVolume))
                    volFlashTick++
                }
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
                VolumeStepButton("+") {
                    vm.setVolume((ui.volume + 1).coerceIn(0, ui.maxVolume))
                    volFlashTick++
                }
            }
        }
    }
}

@Composable
private fun PlayerContent(
    now: com.qmusic.wear.data.player.NowPlaying,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
    onVolumeAdjust: () -> Unit,
    style: ProgressStyle,
    progress: Float,
    ringRatio: Float,
) {
    val isAmbient = LocalIsAmbient.current
    val isRound = LocalIsRoundScreen.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // ---- 方案D布局（方表 + 波形/点阵）：圆盘保留，四角放副控件，圆盘下方为进度条 ----
        val dLayout = !isRound && (style == ProgressStyle.WAVEFORM || style == ProgressStyle.DOTS)
        // ---- 中央大圆盘：0.76（1.9.1 比例）；圆表波形适配缩至 0.64 给进度条让位 ----
        val discFactor = if (isRound && style == ProgressStyle.WAVEFORM) 0.64f else 0.76f
        val disc = minOf(maxWidth, maxHeight) * discFactor

        // 播放时封面缓慢旋转（约30秒/圈），暂停即停并轻微变暗；AOD 下静止
        // 低配置设备模式：每帧 withFrameNanos 重绘旋转开销大，直接静止
        // 液体填充：封面静止铺全屏（不旋转），无需旋转帧循环
        var discRotation by remember { mutableFloatStateOf(0f) }
        val lowPerf = com.qmusic.wear.ui.theme.LocalLowPerf.current
        LaunchedEffect(now.isPlaying, isAmbient, lowPerf, style) {
            if (now.isPlaying && !isAmbient && !lowPerf && style != ProgressStyle.LIQUID) {
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

        // ---- 液体填充：全屏清晰封面为底层，模糊水体 + 层次波纹自下而上淹没（无圆盘） ----
        if (style == ProgressStyle.LIQUID) {
            LiquidFullScreen(
                coverUrl = now.song?.cover500.orEmpty(),
                progress = progress,
                coverAlpha = coverAlpha,
                isAmbient = isAmbient,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (dLayout) {
            // 圆盘（歌名/控制键仍在盘内）+ 四角副控件 + 盘下进度条
            PlayerDisc(
                now = now,
                disc = disc,
                style = style,
                progress = progress,
                discRotation = discRotation,
                coverAlpha = coverAlpha,
                isAmbient = isAmbient,
                modifier = Modifier.align(Alignment.Center),
            )
            CornerSubControls(
                now = now,
                vm = vm,
                onOpenDownloads = onOpenDownloads,
                onVolumeAdjust = onVolumeAdjust,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp),
            ) {
                when (style) {
                    ProgressStyle.WAVEFORM -> WaveformProgress(
                        progress = progress,
                        modifier = Modifier.width(96.dp).height(16.dp),
                    )
                    else -> DotsProgress(
                        progress = progress,
                        modifier = Modifier.width(96.dp).height(10.dp),
                        count = 12,
                    )
                }
            }
        } else {
            Box(Modifier.align(Alignment.Center)) {
                // 封面描边 / 唱片弧线：进度弧画在圆盘外圈（音量调节时临时显示音量弧）
                if (style == ProgressStyle.COVER_STROKE || style == ProgressStyle.VINYL_ARC) {
                    CoverStrokeRing(
                        progress = ringRatio,
                        modifier = Modifier.size(disc + 16.dp),
                    )
                }
                PlayerDisc(
                    now = now,
                    disc = disc,
                    style = style,
                    progress = progress,
                    discRotation = discRotation,
                    coverAlpha = coverAlpha,
                    isAmbient = isAmbient,
                )
            }
            if (isRound && style == ProgressStyle.WAVEFORM) {
                // 圆表波形刻度适配：进度胶囊条置于底部副控件行上方
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 52.dp),
                ) {
                    WaveformProgress(
                        progress = progress,
                        modifier = Modifier
                            .width(124.dp)
                            .height(22.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.32f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            // ---- 底部副控件行：横排贴底（间距/尺寸经实机验证不被圆屏裁切） ----
            SubControlsRow(
                now = now,
                vm = vm,
                onOpenDownloads = onOpenDownloads,
                onVolumeAdjust = onVolumeAdjust,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            )
        }

        // ---- 控制键组：盘心略下移。放在圆盘之外（全屏层）测量——
        // 行宽 164dp 大于圆盘 0.76×短边（方表 139.8dp），放在盘内会被钳制：
        // 末位按钮被压缩且整行失去水平居中（实测小米方表播放键右偏 23px） ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 10.dp),
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
    }
}

/**
 * 中央圆盘：按进度样式切换盘面——
 * - LIQUID 液体填充：盘面全透明（全屏封面与水体由 PlayerContent 的 LiquidFullScreen
 *   在底层绘制，取消旋转圆盘），此容器仅承载顶部歌名/歌手与盘心控制键组
 * - VINYL_ARC 唱片弧线：黑胶盘身（沟槽静止）+ 旋转封面标签 + 主轴
 * - 其余样式：原版旋转封面
 */
@Composable
private fun PlayerDisc(
    now: com.qmusic.wear.data.player.NowPlaying,
    disc: Dp,
    style: ProgressStyle,
    progress: Float,
    discRotation: Float,
    coverAlpha: Float,
    isAmbient: Boolean,
    modifier: Modifier = Modifier,
) {
    val coverUrl = now.song?.cover500.orEmpty()
    val discTint = when (style) {
        // 液体填充：透明容器，露出底层全屏液体画面
        ProgressStyle.LIQUID -> Color.Transparent
        ProgressStyle.VINYL_ARC -> Color(0xFF0A0C10)
        else -> rememberCoverColor(coverUrl) ?: MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(disc)
            .clip(CircleShape)
            .background(discTint),
    ) {
        when (style) {
            ProgressStyle.LIQUID -> {
                // 盘面无内容：画面全部来自底层 LiquidFullScreen
            }

            ProgressStyle.VINYL_ARC -> {
                // 黑胶沟槽盘身（静止）
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    for (i in 1..5) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.05f),
                            radius = r * (0.32f + i * 0.115f),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }
                // 旋转封面标签
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(disc * 0.52f)
                        .clip(CircleShape)
                        .graphicsLayer {
                            rotationZ = discRotation
                            alpha = coverAlpha
                        },
                ) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = if (isAmbient) 0.50f else 0.15f)),
                    )
                }
                // 主轴
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                )
            }

            else -> {
                // 原版：封面填充 + 旋转
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = discRotation
                            alpha = coverAlpha
                        },
                )
            }
        }

        // 整体只轻微压暗（黑胶盘身自带深底、液体填充无盘面，均略过）；AOD 下再压暗一档降亮度
        if (style != ProgressStyle.VINYL_ARC && style != ProgressStyle.LIQUID) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isAmbient) 0.50f else 0.10f)),
            )
        }
        // 顶部局部渐变 scrim：只压暗歌名区域，保证可读性的同时不糊住整个封面
        // （液体填充的 scrim 由 LiquidFullScreen 全屏绘制，避免透明圆盘内出现圆形压痕）
        if (style != ProgressStyle.LIQUID) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.42f)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = if (isAmbient) 0.62f else 0.55f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }

        // 控制键组已上移到 PlayerContent 全屏层（圆盘宽度不足以测量 164dp 行宽）

        // 歌名/歌手：盘内上部（上移让位给控制键组，避免播放键顶到歌手名）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = disc * 0.12f),
        ) {
            Text(
                text = now.song?.name.orEmpty(),
                style = MaterialTheme.typography.titleSmall.copy(
                    // 文字阴影：亮色封面上保证白字分离度
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.55f),
                        blurRadius = 10f,
                        offset = Offset.Zero,
                    ),
                ),
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
}

/** 方案D四角布局（方表 + 波形/点阵）：音量(左上) / 音质(右上) / 喜欢(左下) / 下载(右下) */
@Composable
private fun CornerSubControls(
    now: com.qmusic.wear.data.player.NowPlaying,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
    onVolumeAdjust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = subControlChips(now, vm, onOpenDownloads, onVolumeAdjust)
    Box(modifier) {
        Box(Modifier.align(Alignment.TopStart).padding(3.dp)) { chips[0]() }
        Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) { chips[2]() }
        Box(Modifier.align(Alignment.BottomStart).padding(3.dp)) { chips[1]() }
        Box(Modifier.align(Alignment.BottomEnd).padding(3.dp)) { chips[3]() }
    }
}

/**
 * 底部副控件行：音量 / 喜欢 / 音质 / 下载 四个小圆钮横排。
 * 视觉圆底 28dp、间距 12dp；命中区保持 42dp——用 -2dp 行距抵消命中区外扩，
 * 视觉效果与 1.9.1 完全一致（28dp 圆 + 12dp 视觉间隙），触控容错不减。
 */
@Composable
private fun SubControlsRow(
    now: com.qmusic.wear.data.player.NowPlaying,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
    onVolumeAdjust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = subControlChips(now, vm, onOpenDownloads, onVolumeAdjust)
    // 命中区42dp + 行距-2dp → 视觉圆底中心距 40dp（= 28dp 圆 + 12dp 间隙，与 1.9.1 一致）
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((-2).dp),
        modifier = modifier,
    ) {
        chips.forEach { chip -> chip() }
    }
}

/** 构建四个副控件 chip（音量/喜欢/音质/下载）；底部横排与方案D四角布局共用 */
@Composable
private fun subControlChips(
    now: com.qmusic.wear.data.player.NowPlaying,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
    onVolumeAdjust: () -> Unit,
): List<@Composable () -> Unit> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val uiState = vm.ui.collectAsStateWithLifecycle().value
    val download = uiState.download
    val downloads by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
    val likedMids by ServiceLocator.repository.likedMids.collectAsStateWithLifecycle()
    val curSong = now.song
    val liked = curSong != null && likedMids.contains(curSong.mid)
    val downloaded = curSong?.let { s -> downloads.any { it.song.mid == s.mid } } == true

    // 四个钮（顺序：左 → 右）
    val chips: List<@Composable () -> Unit> = listOf(
        {
            SubControlChip(
                resId = R.drawable.ic_volume,
                contentDescription = stringResource(R.string.player_volume),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onVolumeAdjust,
            )
        },
        {
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
        },
        {
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
        },
        {
            // 下载控件：空闲=下载图标 / 下载中=进度环 / 完成=对勾
            when {
                download.running -> Box(
                    contentAlignment = Alignment.Center,
                    // 与其余按钮的42dp命中区对齐，避免状态切换时行内抖动；点击进下载管理；
                    // 圆形裁剪让 ripple 呈圆形，避免方形灰块
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenDownloads),
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
        },
    )
    // 构建完成后直接返回给调用方布局（底部横排 / 方案D四角共用）
    return chips
}

/** 裸图标按钮（深色圆底 + 白色图标）；命中区扩到42dp提升手指容错 */
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
            // 圆形裁剪：ripple 沿圆形扩散，不出现方形灰块
            .clip(CircleShape)
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
                // 深色圆底：亮色封面上依然清晰（白色半透明底会被亮背景吞掉）
                .background(Color.Black.copy(alpha = 0.40f))
                .border(0.5.dp, Color.White.copy(alpha = 0.16f), CircleShape),
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
    // 播放时光晕呼吸起伏；暂停/AOD 静止为低亮度；低配置设备模式取消呼吸动画（静止低亮度）
    val lowPerf = com.qmusic.wear.ui.theme.LocalLowPerf.current
    val glowAlpha: Float = if (playing && !isAmbient && !lowPerf) {
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
        // 深色底盘：亮色封面上给品牌绿键提供分离度
        Box(
            Modifier
                .size(60.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
        )
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
        // 命中区42dp，视觉圆底28dp不变；圆形裁剪让 ripple 呈圆形，避免方形灰块
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
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
                // 深色圆底：任何封面色上都清晰
                .background(Color.Black.copy(alpha = 0.42f))
                .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape),
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
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp, lineHeight = 8.sp),
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

/** 音量加减小圆钮（音量指示胶囊两侧的触控 +/−） */
@Composable
private fun VolumeStepButton(symbol: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable {
                haptics.tap()
                onClick()
            },
    ) {
        Text(
            text = symbol,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
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
            // 圆表两侧大留白防弧边裁切；方表收窄让选项文案更宽
            .padding(horizontal = if (LocalIsRoundScreen.current) 22.dp else 2.dp)
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

/** 毫秒 → m:ss（顶部进度时间用） */
private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}
