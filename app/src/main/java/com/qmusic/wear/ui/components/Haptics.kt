package com.qmusic.wear.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

// -------------------------------------------------------------------------
// 触觉反馈
// -------------------------------------------------------------------------

/**
 * 统一触觉反馈（走系统 View.performHapticFeedback，自动遵守系统"触摸振动"开关）：
 * - tap：常规按钮点击（播放/切歌/收藏/模式切换等）
 * - tick：表冠音量步进刻度
 * - confirm：手势触发页面切换（滑动返回/换页）
 */
class QmHaptics(private val view: View) {
    fun tap() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    // 手势确认（滑动返回/换页）：API 30+ 用 GESTURE_END（X轴马达短促哒声）；
    // API 28-29 预设反馈均偏长，直接用 Vibrator 打 12ms 短脉冲（清脆哒声）
    fun confirm() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
            return
        }
        val v = view.context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            ?: return
        if (!v.hasVibrator()) return
        v.vibrate(android.os.VibrationEffect.createOneShot(12, 110))
    }
}

@Composable
fun rememberHaptics(): QmHaptics {
    val view = LocalView.current
    return remember { QmHaptics(view) }
}