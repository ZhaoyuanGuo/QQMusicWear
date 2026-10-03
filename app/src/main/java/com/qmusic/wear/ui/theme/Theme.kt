package com.qmusic.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.UiShape

/** AOD（环境模式）标志：MainActivity 经 AmbientLifecycleObserver 更新，各页据此停动画/降亮度 */
val LocalIsAmbient = staticCompositionLocalOf { false }

/** 低配置设备模式：MainActivity 从设置读取后全局下发，各页据此关闭重特效/缩短动画 */
val LocalLowPerf = staticCompositionLocalOf { false }

/** 屏幕形状：true=圆表（如 Galaxy Watch 450x450），false=方表（如小米手表 368x448） */
val LocalIsRoundScreen = staticCompositionLocalOf { false }

// 品牌色兜底（QQ 音乐绿）：实际品牌色随当前音乐源 manifest.themeColor 动态切换
val QmGreen = Color(0xFF31C27C)
val QmGreenDim = Color(0xFF1E8F5A)
// 页面底色：纯黑——卡片之间的背景在 OLED 上完全不发光（省电且沉浸）；QmInk 保留作次要深色
val QmInk = Color(0xFF0B0F14)
val QmSurface = Color(0xFF141A21)
val QmSurfaceHigh = Color(0xFF1C242E)
val QmOnSurface = Color(0xFFF2F5F7)
val QmOnSurfaceDim = Color(0xFF9AA7B2)
val QmError = Color(0xFFFF6B6B)

@Composable
fun QMusicTheme(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    // 屏幕形状：默认按 OEM 硬件上报识别；用户在 设置→显示 选择方表/圆表后全局覆盖，
    // 驱动全 app 两套布局（播放页据此显示圆表原版或六种新样式）
    val uiShape by ServiceLocator.settingsStore.uiShapeFlow.collectAsStateWithLifecycle()
    val isRound = when (uiShape) {
        UiShape.SQUARE -> false
        UiShape.ROUND -> true
        UiShape.AUTO -> cfg.isScreenRound
    }
    // 品牌色：随当前音乐源切换（酷狗蓝 / 网易云红 / QQ 绿 / 番茄橙）
    val brandArgb by com.qmusic.wear.data.source.SourceManager.themeColorFlow.collectAsStateWithLifecycle()
    val brand = Color(brandArgb)
    // 容器色 = 品牌色压暗（保证白字对比度）
    val brandDim = androidx.compose.ui.graphics.lerp(Color.Black, brand, 0.62f)
    val density = LocalDensity.current
    // 方表窄屏字号补偿：小米方表 368px@2.0=184dp，圆表 450px@2.0=225dp，同为 320dpi 下
    // 同一 sp 字号物理大小相同，但方表屏窄，字号相对占比大 ~22%，观感偏大且更早触发截断。
    // 按屏宽比例整体缩小 sp 字号（dp 不动，触控目标不受影响），0.82=184/225 与圆表比例一致
    val fontFactor = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) / 225f).coerceIn(0.82f, 1f)
    CompositionLocalProvider(
        LocalIsRoundScreen provides isRound,
        LocalDensity provides Density(density.density, density.fontScale * fontFactor),
    ) {
        // 在 Wear OS 默认深色方案上注入品牌色（M3 Expressive / One UI Watch 风格）
        val scheme = MaterialTheme.colorScheme.copy(
            primary = brand,
            onPrimary = Color.Black,
            primaryContainer = brandDim,
            onPrimaryContainer = Color.White,
            secondary = Color(0xFF5AD2E0),
            onSecondary = Color.Black,
            background = Color.Black,
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
}
