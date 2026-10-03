package com.qmusic.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import com.qmusic.wear.ui.theme.LocalIsAmbient
import com.qmusic.wear.ui.theme.LocalLowPerf
import com.qmusic.wear.ui.theme.QMusicTheme

class MainActivity : ComponentActivity() {

    // AOD（环境模式）状态：Wear OS 4+ 经 AmbientLifecycleObserver 通知，CompositionLocal 下发
    private val isAmbientState = mutableStateOf(false)
    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbientState.value = true
        }

        override fun onExitAmbient() {
            isAmbientState.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 上次运行发生未捕获崩溃：先用原生诊断页展示日志（可复制），避免反复闪退无迹可循
        val lastCrash = CrashLog.consumeUnreadFatal()
        if (lastCrash != null) {
            showCrashReport(lastCrash, showContinue = true)
            return
        }
        startApp()
    }

    private fun startApp() {
        // Application 初始化失败：无法正常进入应用，展示初始化错误供反馈
        ServiceLocator.startupError?.let {
            showCrashReport(it.stackTraceToString(), showContinue = false)
            return
        }
        try {
            // AOD 观察依赖 wearable 共享库（com.google.android.wearable）；
            // 缺库环境（如非手表模拟器）直接跳过注册，否则启动即抛 IllegalStateException
            if (hasWearableSharedLibrary()) {
                lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
            }
            setContent {
                QMusicTheme {
                    // 低配置设备模式全局下发（各页据此关特效/砍动画）
                    val lowPerf by ServiceLocator.settingsStore.lowPerfFlow.collectAsStateWithLifecycle()
                    CompositionLocalProvider(
                        LocalIsAmbient provides isAmbientState.value,
                        LocalLowPerf provides lowPerf,
                    ) {
                        AppRoot()
                    }
                }
            }
        } catch (t: Throwable) {
            // 同步组合崩溃：落盘并展示诊断页，让用户能把日志反馈出来
            CrashLog.log(t)
            showCrashReport(t.stackTraceToString(), showContinue = false)
        }
    }

    /** 启动崩溃诊断页入口（实现见 CrashReportPage.kt）；「继续启动」仅用于瞬时性问题 */
    private fun showCrashReport(detail: String, showContinue: Boolean) {
        showCrashReportPage(this, detail, showContinue) { startApp() }
    }
}

/** 是否具备 Wear OS 共享库（真手表具备；缺失环境跳过 AOD 注册避免闪退） */
private fun hasWearableSharedLibrary(): Boolean = runCatching {
    Class.forName("android.support.wearable.R\$version")
}.isSuccess