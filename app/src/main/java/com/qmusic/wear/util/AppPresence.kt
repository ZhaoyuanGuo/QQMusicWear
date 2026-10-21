package com.qmusic.wear.util

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * 全局前后台 / 亮灭屏状态（进程级，供轮询休眠与常驻卡片收放共用）。
 *
 * - foreground：任一 Activity STARTED 即为前台（ProcessLifecycleOwner）
 * - screenOn：亮屏/解锁/表冠抬腕（ACTION_SCREEN_ON）置位，息屏清零
 * - interactive = foreground && screenOn：用户此刻看得见界面，UI 状态需要实时刷新
 *
 * init 幂等，由 ServiceLocator.init 调用一次；接收器随进程存活，无需注销。
 */
@SuppressLint("StaticFieldLeak") // 只持有 applicationContext，进程级单例（lint 架构性误报）
internal object AppPresence {

    private val _foregroundFlow = MutableStateFlow(false)
    private val _screenOnFlow = MutableStateFlow(true)

    /** 前台且亮屏：轮询保持常规节奏；否则视为静止可休眠 */
    val interactive: Boolean get() = _foregroundFlow.value && _screenOnFlow.value

    /** interactive 的可订阅版本（轮询休眠/卡片收放共用） */
    val interactiveFlow: StateFlow<Boolean> =
        combine(_foregroundFlow, _screenOnFlow) { fg, on -> fg && on }
            .stateIn(
                scope = CoroutineScope(Dispatchers.Unconfined),
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    private lateinit var appContext: Context
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        _screenOnFlow.value = isScreenActuallyOn()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _foregroundFlow.value = true
                // 回前台可能伴随屏幕未变（分屏/弹窗返回），补一次亮屏校准
                if (isScreenActuallyOn()) _screenOnFlow.value = true
            }

            override fun onStop(owner: LifecycleOwner) {
                _foregroundFlow.value = false
            }
        })
        // SCREEN_ON/OFF、USER_PRESENT 是受保护的系统广播（仅系统可发），
        // 声明 NOT_EXPORTED 即可正常接收，且不向其它应用暴露
        appContext.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // StateFlow 自动去重，值真正翻转时 interactiveFlow 才会发射并唤醒轮询
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> _screenOnFlow.value = true
                Intent.ACTION_SCREEN_OFF -> _screenOnFlow.value = false
            }
        }
    }

    private fun isScreenActuallyOn(): Boolean = runCatching {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isInteractive
    }.getOrDefault(true)
}
