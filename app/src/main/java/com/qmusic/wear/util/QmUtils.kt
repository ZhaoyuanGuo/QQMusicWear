package com.qmusic.wear.util

/** 通用 UI 工具（协议相关工具已移至音乐源插件） */

/** 毫秒 -> mm:ss */
fun Long.msTo_mmss(): String {
    val totalSec = this / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
