package com.qmusic.wear.data.model

/**
 * 显示形态：用户在 设置→显示 中选择的界面形态。
 * 选择后全局覆盖系统屏幕形状识别（LocalIsRoundScreen），驱动全 app 两套布局。
 */
enum class UiShape(val label: String) {
    /** 跟随手表实际屏幕形状（默认） */
    AUTO("跟随屏幕"),

    /** 方表：方屏专属布局 + 六种进度样式 */
    SQUARE("方表"),

    /** 圆表：经典圆盘播放页（液体/黑胶/波形已适配） */
    ROUND("圆表"),
}

/** 播放页进度样式（设置→显示 中选择；未选择时按屏幕形态取默认值） */
enum class ProgressStyle(val label: String) {
    /** 液体填充：模糊遮罩水位淹没封面，底层仍是清晰封面（方表默认） */
    LIQUID("液体填充"),

    /** 边框描边：进度沿屏幕圆角边框走一圈（方表专属；圆表即经典屏幕边缘进度环，圆表默认） */
    BORDER("边框描边"),

    /** 封面描边：进度沿圆盘封面外缘描一圈 */
    COVER_STROKE("封面描边"),

    /** 唱片弧线：黑胶盘身 + 外圈进度弧（圆表已适配） */
    VINYL_ARC("唱片弧线"),

    /** 波形刻度：波形条播过的点亮（方表用方案D圆盘布局；圆表以胶囊条适配） */
    WAVEFORM("波形刻度"),

    /** 点阵进度：一排圆点，点亮的数量=进度（方表用方案D圆盘布局） */
    DOTS("点阵进度"),
}

/**
 * 未显式选择进度样式时的默认值（两套 UI 各自独立）：
 * - 圆表：沿用 v2.0.0 经典播放页——中央圆盘 + 屏幕边缘进度环
 *   （该外观在圆表下由 [ProgressStyle.BORDER] 承载）
 * - 方表：液体填充
 */
fun defaultProgressStyle(isRound: Boolean): ProgressStyle =
    if (isRound) ProgressStyle.BORDER else ProgressStyle.LIQUID
