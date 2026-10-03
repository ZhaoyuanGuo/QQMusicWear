package com.qmusic.wear.ui.player

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.rotary.RotaryScrollableBehavior
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.ProgressStyle
import com.qmusic.wear.data.model.defaultProgressStyle
import com.qmusic.wear.ui.components.BlurCoverBackground
import com.qmusic.wear.ui.components.EdgeProgressRing
import com.qmusic.wear.ui.components.rememberHaptics
import com.qmusic.wear.ui.components.rotaryCustom
import com.qmusic.wear.ui.theme.LocalIsAmbient
import com.qmusic.wear.ui.theme.LocalIsRoundScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * 播放页（参考磁音手表版圆盘布局，480×480 圆屏）：
 * - 背景：封面高斯模糊铺满全屏（One UI Watch 质感）
 * - 中央：专辑封面大圆盘，歌名/歌手在盘内上部，上一首/播放/下一首精确位于盘心
 * - 底部：一排小圆钮（音量 / 喜欢 / 音质 / 下载），播放模式入口移至队列页
 * - 保留我们自己的环绕屏幕边缘进度环；
 *   手势：手指左滑进歌词页、右滑返回主页、上滑进队列；
 *   表冠：任意方向旋转进队列页（与队列页顶部回旋返回形成往返）
 *
 * 页面主体在 [PlayerContent]，圆盘在 [PlayerDisc]，控制键与音质面板分别在
 * [PlayerControls] / [QualityPicker]。
 */
@Composable
fun PlayerScreen(
    onOpenLyrics: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onOpenComments: () -> Unit = {},
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

    // 进度样式（设置→显示 六选一）：用户显式选择优先；未选择时按屏幕形态取默认值
    // ——圆表沿用 v2.0.0 经典界面（圆盘 + 屏幕边缘进度环），方表为液体填充
    val stylePref by ServiceLocator.settingsStore.progressStyleFlow.collectAsStateWithLifecycle()
    val isRoundUi = LocalIsRoundScreen.current
    val style = stylePref ?: defaultProgressStyle(isRoundUi)

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
        // - 圆表且样式未接管圆盘（边框描边/点阵）→ 原版边缘进度环
        // - 方表 + 边框描边 → 屏幕圆角边框描边（方表专属）
        // - 液体填充/唱片弧线/波形刻度（圆表已适配）/封面描边（圆表由圆盘内圈弧承载，避免双环）
        //   与方表其余样式 → 进度由圆盘/进度条/边框承载，此处不画
        // 音量调节时环/弧临时切换为音量比例（同款样式）
        val ratio = if (volFlashVisible && ui.maxVolume > 0) {
            ui.volume.toFloat() / ui.maxVolume
        } else {
            progress
        }
        when {
            isRoundUi &&
                style != ProgressStyle.LIQUID &&
                style != ProgressStyle.VINYL_ARC &&
                style != ProgressStyle.WAVEFORM &&
                style != ProgressStyle.COVER_STROKE ->
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
                        // 只列出该曲实际拥有的音质；用户设置档位超出时高亮到实际可用的最高档
                        val available = vm.availableQualities(now.song)
                        QualityPicker(
                            selected = if (ui.selectedQuality in available) {
                                ui.selectedQuality
                            } else {
                                available.lastOrNull() ?: ui.selectedQuality
                            },
                            available = available,
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
                        onOpenComments = onOpenComments,
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

        // 音量指示（点音量钮触发，约3.5秒自动消失）
        PlayerVolumeIndicator(
            visible = volFlashVisible,
            volume = ui.volume,
            maxVolume = ui.maxVolume,
            onAdjust = {
                vm.setVolume(it)
                volFlashTick++
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
        )
    }
}

/** 毫秒 → m:ss（顶部进度时间用） */
private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}