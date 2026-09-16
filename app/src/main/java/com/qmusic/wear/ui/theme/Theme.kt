package com.qmusic.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.MaterialTheme

/** AOD（环境模式）标志：MainActivity 经 AmbientLifecycleObserver 更新，各页据此停动画/降亮度 */
val LocalIsAmbient = staticCompositionLocalOf { false }

// QQ 音乐品牌绿
val QmGreen = Color(0xFF31C27C)
val QmGreenDim = Color(0xFF1E8F5A)
val QmInk = Color(0xFF0B0F14)
val QmSurface = Color(0xFF141A21)
val QmSurfaceHigh = Color(0xFF1C242E)
val QmOnSurface = Color(0xFFF2F5F7)
val QmOnSurfaceDim = Color(0xFF9AA7B2)
val QmError = Color(0xFFFF6B6B)

@Composable
fun QMusicTheme(content: @Composable () -> Unit) {
    // 在 Wear OS 默认深色方案上注入品牌色（M3 Expressive / One UI Watch 风格）
    val scheme = MaterialTheme.colorScheme.copy(
        primary = QmGreen,
        onPrimary = Color.Black,
        primaryContainer = QmGreenDim,
        onPrimaryContainer = Color.White,
        secondary = Color(0xFF5AD2E0),
        onSecondary = Color.Black,
        background = QmInk,
        onBackground = QmOnSurface,
        surfaceContainerLow = QmSurface,
        surfaceContainer = QmSurface,
        surfaceContainerHigh = QmSurfaceHigh,
        onSurface = QmOnSurface,
        onSurfaceVariant = QmOnSurfaceDim,
        outline = Color(0xFF2A3542),
        error = QmError,
        onError = Color.Black,
    )
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
