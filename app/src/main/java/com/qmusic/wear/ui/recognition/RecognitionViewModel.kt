package com.qmusic.wear.ui.recognition

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.RecognizeResult
import com.qmusic.wear.data.recognition.AudioCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 识曲页所处阶段 */
enum class RecognitionPhase { Idle, Recording, Recognizing, Result, NoMatch, NoMic, NeedPermission, Error }

data class RecognitionUiState(
    val phase: RecognitionPhase = RecognitionPhase.Idle,
    val result: RecognizeResult? = null,
    val message: String = "",
)

/**
 * 听歌识曲页 ViewModel：录音 → 交给当前源的 recognizeSong handler → 展示结果。
 * 无麦克风/未授权/源不支持均落到对应阶段，由 UI 给出提示而非崩溃。
 */
class RecognitionViewModel : ViewModel() {

    private val _ui = MutableStateFlow(RecognitionUiState())
    val ui: StateFlow<RecognitionUiState> = _ui.asStateFlow()

    /** 录音前是否已经发起过权限申请，避免拒绝授权后无限重复弹窗 */
    private var permissionAsked = false

    fun hasPermission(context: Context): Boolean = AudioCapture.hasPermission(context)

    fun markPermissionAsked() { permissionAsked = true }

    /** 开始一次识别：探测麦克风/权限 → 暂停播放 → 录音 → 送识别 */
    fun startRecognize(context: Context) {
        if (!AudioCapture.hasMicrophone(context)) {
            _ui.value = RecognitionUiState(phase = RecognitionPhase.NoMic)
            return
        }
        if (!AudioCapture.hasPermission(context)) {
            // 未授权且此前没申请过：交由 UI 层发起系统权限弹窗，不在此处直接报错
            _ui.value = RecognitionUiState(
                phase = if (permissionAsked) RecognitionPhase.NeedPermission else RecognitionPhase.Idle,
            )
            return
        }
        // 录音前暂停播放：避免麦克风录到扬声器里正在放的歌，产生"识别到自己"的误判；
        // 结束后不自动恢复，与手机端听歌识曲的通用交互一致
        runCatching { ServiceLocator.player.pause() }
        _ui.value = RecognitionUiState(phase = RecognitionPhase.Recording)
        viewModelScope.launch {
            val captured = runCatching { AudioCapture.record(context) }
            captured.fold(
                onSuccess = { audio ->
                    _ui.value = _ui.value.copy(phase = RecognitionPhase.Recognizing)
                    val result = ServiceLocator.repository.recognizeSong(
                        audio.base64(),
                        AudioCapture.TARGET_SAMPLE_RATE,
                        audio.durationMs,
                    )
                    _ui.value = RecognitionUiState(
                        phase = if (result.song != null) RecognitionPhase.Result else RecognitionPhase.NoMatch,
                        result = result,
                    )
                },
                onFailure = {
                    _ui.value = RecognitionUiState(
                        phase = RecognitionPhase.Error,
                        message = it.message ?: "录音失败",
                    )
                },
            )
        }
    }

    fun reset() {
        _ui.value = RecognitionUiState()
    }
}
