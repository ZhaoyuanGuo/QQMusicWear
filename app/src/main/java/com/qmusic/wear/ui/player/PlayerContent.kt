package com.qmusic.wear.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.ProgressStyle
import com.qmusic.wear.data.player.NowPlaying
import com.qmusic.wear.ui.theme.LocalIsAmbient
import com.qmusic.wear.ui.theme.LocalLowPerf
import com.qmusic.wear.ui.theme.LocalIsRoundScreen

/** 播放页主体：圆盘 + 进度样式分流（液体填充/方案D四角/边缘环）+ 底部副控件 + 盘心控制键组 */
@Composable
internal fun PlayerContent(
    now: NowPlaying,
    vm: PlayerViewModel,
    onOpenDownloads: () -> Unit,
    onOpenComments: () -> Unit,
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
        val lowPerf = LocalLowPerf.current
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
            // contentAlignment 必须为 Center：描边环为 disc+16dp、圆盘为 disc，
            // 若按默认 TopStart 排布，圆盘会被顶到左上角，与进度环错位 8dp
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.align(Alignment.Center),
            ) {
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
                // 圆表波形刻度适配：进度胶囊条嵌在底部弧线排布的副控件「弧心」里（弧形容器正中）
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
            // ---- 底部副控件：圆表沿下缘弧线排布（需整屏尺寸算位置），方表横排贴底 ----
            SubControlsRow(
                now = now,
                vm = vm,
                onOpenDownloads = onOpenDownloads,
                onOpenComments = onOpenComments,
                onVolumeAdjust = onVolumeAdjust,
                modifier = if (isRound) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                },
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