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

/**
 * 睡眠定时器：到时自动暂停播放。
 * 独立单例，剩余秒数以 StateFlow 暴露供设置页/播放页显示。
 */
object SleepTimer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _remainingSec = MutableStateFlow(0L)
    val remainingSec: StateFlow<Long> = _remainingSec.asStateFlow()

    val isActive: Boolean get() = _remainingSec.value > 0

    private var job: Job? = null

    /** 启动/重设定时；minutes<=0 取消 */
    fun start(minutes: Int, onPause: () -> Unit) {
        job?.cancel()
        if (minutes <= 0) {
            _remainingSec.value = 0L
            return
        }
        _remainingSec.value = minutes * 60L
        job = scope.launch {
            while (_remainingSec.value > 0) {
                delay(1000)
                if (_remainingSec.value > 0) _remainingSec.value -= 1
            }
            runCatching { onPause() }
        }
    }

    fun cancel() {
        job?.cancel()
        _remainingSec.value = 0L
    }
}
