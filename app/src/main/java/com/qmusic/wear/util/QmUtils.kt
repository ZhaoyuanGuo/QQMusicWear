package com.qmusic.wear.util

import java.util.UUID

/** QQ 音乐 Web 协议通用工具 */
object QmUtils {

    /** 设备 guid（vkey 请求等接口需要的伪设备标识） */
    fun guid(): String = UUID.randomUUID().toString().replace("-", "").take(32)

    /**
     * 由 musickey 计算 g_tk（QQ 系通用的 hash33 算法）。
     * 未登录（musickey 为空）时返回 5381（标准初值）。
     */
    fun gtk(musickey: String): Long {
        var hash: Long = 5381
        for (ch in musickey) {
            hash += (hash shl 5) + ch.code
        }
        return hash and 0x7fffffffL
    }

    /** 由 qrsig 计算 ptqrtoken（扫码登录轮询参数，同为 hash33 算法） */
    fun ptqrtoken(qrsig: String): Int {
        var hash = 0
        for (ch in qrsig) {
            hash += (hash shl 5) + ch.code
        }
        return hash and 0x7fffffff
    }
}

/** 毫秒 -> mm:ss */
fun Long.msTo_mmss(): String {
    val totalSec = this / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
