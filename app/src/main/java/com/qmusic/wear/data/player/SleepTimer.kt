package com.qmusic.wear.data.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.qmusic.wear.ServiceLocator

/**
 * 睡眠定时器：到时自动暂停播放，最后 [FADE_SEC] 秒媒体音量线性渐弱。
 * 独立单例，剩余秒数以 StateFlow 暴露供设置页/播放页显示。
 */
object SleepTimer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _remainingSec = MutableStateFlow(0L)
    val remainingSec: StateFlow<Long> = _remainingSec.asStateFlow()

    val isActive: Boolean get() = _remainingSec.value > 0

    /** 渐弱时长（秒）：倒数进入该区间后音量线性降到 0 */
    private const val FADE_SEC = 30

    private var job: Job? = null

    /** 渐弱前的原始音量（暂停后恢复，避免下次手动播放无声） */
    private var fadeBaseVolume = -1

    /** 启动/重设定时；minutes<=0 取消。到时回调 [onFinish] 暂停播放 */
    fun start(minutes: Int, onFinish: () -> Unit) {
        job?.cancel()
        restoreVolumeIfNeeded()
        if (minutes <= 0) {
            _remainingSec.value = 0L
            return
        }
        _remainingSec.value = minutes * 60L
        job = scope.launch {
            while (_remainingSec.value > 0) {
                delay(1000)
                if (_remainingSec.value > 0) _remainingSec.value -= 1
                fadeTick()
            }
            runCatching { onFinish() }
            restoreVolumeIfNeeded()
        }
    }

    fun cancel() {
        job?.cancel()
        _remainingSec.value = 0L
        restoreVolumeIfNeeded()
    }

    /** 剩余 [FADE_SEC] 秒内每秒把媒体音量线性降低（对播放器未初始化等异常静默跳过） */
    private fun fadeTick() {
        val r = _remainingSec.value
        if (r !in 1..FADE_SEC) return
        val player = runCatching { ServiceLocator.player }.getOrNull() ?: return
        if (fadeBaseVolume < 0) fadeBaseVolume = player.currentVolume
        if (fadeBaseVolume <= 0) return
        val target = (fadeBaseVolume * r / FADE_SEC).coerceAtLeast(0L).toInt()
        runCatching { player.setVolume(target) }
    }

    /** 恢复渐弱前的音量 */
    private fun restoreVolumeIfNeeded() {
        if (fadeBaseVolume < 0) return
        val player = runCatching { ServiceLocator.player }.getOrNull()
        if (player != null) runCatching { player.setVolume(fadeBaseVolume) }
        fadeBaseVolume = -1
    }
}
