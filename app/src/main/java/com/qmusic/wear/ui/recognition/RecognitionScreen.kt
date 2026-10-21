package com.qmusic.wear.ui.recognition

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.recognition.AudioCapture
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.SongRow
import com.qmusic.wear.ui.theme.LocalIsAmbient
import com.qmusic.wear.ui.theme.LocalIsRoundScreen
import com.qmusic.wear.ui.theme.LocalLowPerf

/**
 * 听歌识曲页：中央麦克风按钮 → 录音（呼吸光晕，AOD/低配置模式静止）→ 识别中 → 结果卡片。
 * 无麦克风 / 未授权 / 源不支持 / 未识别到 均有独立文案，不弹崩溃也不静默失败。
 */
@Composable
fun RecognitionScreen(
    onOpenPlayer: () -> Unit,
    vm: RecognitionViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val isAmbient = LocalIsAmbient.current
    val isRound = LocalIsRoundScreen.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.startRecognize(ctx) else vm.markPermissionAsked()
    }
    val startOrRequest: () -> Unit = {
        if (AudioCapture.hasPermission(ctx)) vm.startRecognize(ctx)
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (isRound) 12.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PageTitle("听歌识曲")
        Spacer(Modifier.height(12.dp))
        when (ui.phase) {
            RecognitionPhase.Idle -> {
                MicButton(enabled = true, onClick = startOrRequest, isRound = isRound)
                Spacer(Modifier.height(10.dp))
                HintText("点击按钮，把手表靠近正在播放的音乐")
            }

            RecognitionPhase.Recording -> {
                MicButton(enabled = false, onClick = {}, isRound = isRound, recording = true)
                Spacer(Modifier.height(10.dp))
                HintText("正在聆听…请保持靠近声源")
            }

            RecognitionPhase.Recognizing -> {
                CircularProgressIndicator(modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(10.dp))
                HintText("识别中…")
            }

            RecognitionPhase.Result -> {
                val song = ui.result?.song
                if (song != null) {
                    SongRow(
                        song = song,
                        playing = false,
                        onClick = {
                            ServiceLocator.player.enqueueAndPlay(song)
                            onOpenPlayer()
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                RetryText("再试一次", onClick = { vm.reset(); startOrRequest() })
            }

            RecognitionPhase.NoMatch -> {
                HintText("没有识别到歌曲，换个片段或靠近一点再试试")
                Spacer(Modifier.height(10.dp))
                RetryText("重试", onClick = { vm.reset(); startOrRequest() })
            }

            RecognitionPhase.NoMic -> HintText("这台设备没有麦克风，无法使用听歌识曲")

            RecognitionPhase.NeedPermission -> HintText("需要麦克风权限才能识曲，请到系统设置里开启后重试")

            RecognitionPhase.Error -> {
                HintText(ui.message.ifBlank { "识别失败，请重试" })
                Spacer(Modifier.height(10.dp))
                RetryText("重试", onClick = { vm.reset(); startOrRequest() })
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RetryText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 中央麦克风按钮：录音中呼吸光晕（AOD/低配置模式静止为低亮度，与播放键同一处理思路） */
@Composable
private fun MicButton(
    enabled: Boolean,
    onClick: () -> Unit,
    isRound: Boolean,
    recording: Boolean = false,
) {
    val size = if (isRound) 64.dp else 72.dp
    val isAmbient = LocalIsAmbient.current
    val lowPerf = LocalLowPerf.current
    val glowAlpha: Float = if (recording && !enabled && !isAmbient && !lowPerf) {
        val pulse by rememberInfiniteTransition(label = "rec_glow").animateFloat(
            initialValue = 0.18f,
            targetValue = 0.36f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "rec_glow_pulse",
        )
        pulse
    } else if (enabled) {
        0.16f
    } else {
        0.10f
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha))
            .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = if (recording) "正在录音" else "开始识别",
            tint = if (enabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(size * 0.42f),
        )
    }
}
