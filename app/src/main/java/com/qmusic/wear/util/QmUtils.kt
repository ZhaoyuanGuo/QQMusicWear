package com.qmusic.wear.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 通用 UI 工具（协议相关工具已移至音乐源插件） */

/** 毫秒 -> mm:ss */
fun Long.msTo_mmss(): String {
    val totalSec = this / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

/** 字节数 -> 人类可读大小（B/KB/MB/GB） */
fun Long.formatBytes(): String {
    if (this < 1024) return "$this B"
    val kb = this / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/**
 * 网络是否可用（严格判定）：有活动网络、具备 INTERNET 能力且已通过系统出网验证（VALIDATED）。
 * 仅看 INTERNET 声明能力会把"WiFi 连着但路由器断网/需要认证"误判为在线，导致离线兜底不生效。
 * 验证标记缺失（刚连上/系统验证滞后）时按离线处理，调用方可用 [probeNetworkOnline] 出网探测二次确认。
 */
fun isNetworkOnline(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val nw = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(nw) ?: return false
    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}.getOrDefault(false)

/** 是否"名义有网但未验证"：具备 INTERNET 能力但 VALIDATED 缺失（刚连上/验证滞后/路由器断网） */
fun isNetworkUnverified(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val nw = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(nw) ?: return false
    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}.getOrDefault(false)

/** 出网探测专用客户端：短超时，独立于业务请求 */
private val probeClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
}

/** 204 探测端点：国内（小米/vivo）+ 海外（谷歌）各备一个，任一命中即真在线 */
private val probeEndpoints = listOf(
    "http://connect.rom.miui.com/generate_204",
    "http://wifi.vivo.com.cn/generate_204",
    "https://www.gstatic.com/generate_204",
)

/** 出网探测：并连请求 204 端点（整体 2s 超时），任一有响应即认为真实在线 */
suspend fun probeNetworkOnline(): Boolean = withContext(Dispatchers.IO) {
    coroutineScope {
        probeEndpoints.map { url ->
            async {
                runCatching {
                    probeClient.newCall(Request.Builder().url(url).head().build())
                        .execute().use { it.code == 204 }
                }.getOrDefault(false)
            }
        }.awaitAll().any { it }
    }
}
