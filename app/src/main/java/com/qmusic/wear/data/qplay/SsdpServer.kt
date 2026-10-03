package com.qmusic.wear.data.qplay

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress

/**
 * SSDP 组播服务器（QPlay/DLNA 设备发现）：
 * - 监听 239.255.255.250:1900，应答控制点（手机/PC QQ 音乐）的 M-SEARCH
 * - 周期发送 NOTIFY ssdp:alive 广播（主动出现在设备列表）
 * - stop 时发送 ssdp:byebye
 *
 * 交互格式与 Phase 0 抓包捕获的 QQ 音乐 PC 端（libupnp 1.6.19）一致。
 */
class SsdpServer(
    private val context: Context,
    private val httpPort: Int,
    private val udn: String,
) {
    companion object {
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SERVER_SIG = "Android UPnP/1.0 QQMusicWear/1.0"

        /** 与真实 QQ 音乐设备一致的 NT 宣告集合 */
        private val NOTIFY_TYPES = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-tencent-com:service:QPlay:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1",
            "urn:schemas-upnp-org:service:RenderingControl:1",
        )

        /** 对 M-SEARCH ST 的应答集合 */
        private val SEARCH_STS = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-tencent-com:service:QPlay:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1",
            "urn:schemas-upnp-org:service:RenderingControl:1",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        if (socket != null) return
        scope.launch {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifi?.createMulticastLock("QPlaySsdp")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                val sock = MulticastSocket(null)
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(SSDP_PORT))
                sock.joinGroup(InetAddress.getByName(SSDP_ADDR))
                socket = sock
                android.util.Log.i("QPlaySsdp", "SSDP listener ready on $SSDP_PORT, ip=${localIp()}")

                launch { advertiseLoop() }
                receiveLoop(sock)
            } catch (t: Throwable) {
                android.util.Log.e("QPlaySsdp", "SSDP start failed", t)
            }
        }
    }

    fun stop() {
        // 优雅下线：独立线程广播 byebye（scope 即将取消）
        Thread { runCatching { sendByebye() } }.start()
        scope.cancel()
        runCatching { socket?.close() }
        socket = null
        runCatching { if (lock?.isHeld == true) lock?.release() }
        lock = null
    }

    private suspend fun receiveLoop(sock: MulticastSocket) {
        val buf = ByteArray(2048)
        while (scope.isActive) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val msg = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                if (msg.startsWith("M-SEARCH")) {
                    val st = Regex("ST:\\s*(.+?)\\s*\\r?\\n", RegexOption.IGNORE_CASE).find(msg)?.groupValues?.get(1)?.trim() ?: continue
                    if (st.equals("ssdp:discover", true)) continue
                    answerSearch(sock, packet.socketAddress, st)
                }
                // 其他 NOTIFY（其他设备上下线）忽略
            } catch (t: Throwable) {
                if (!scope.isActive) break
                android.util.Log.w("QPlaySsdp", "receive error: ${t.message}")
                delay(300)
            }
        }
    }

    private fun answerSearch(sock: MulticastSocket, to: SocketAddress, st: String) {
        val entries = when {
            st.equals("ssdp:all", true) || st.equals("upnp:rootdevice", true) ->
                listOf("upnp:rootdevice", udn, "urn:schemas-upnp-org:device:MediaRenderer:1") + NOTIFY_TYPES.filter { it.contains("service:") }
            SEARCH_STS.any { it.equals(st, true) } -> listOf(st)
            else -> return
        }
        for (nt in entries) {
            val resp = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                append("EXT:\r\n")
                append("SERVER: $SERVER_SIG\r\n")
                append("LOCATION: http://${localIp()}:$httpPort/description.xml\r\n")
                append("ST: $nt\r\n")
                append("USN: $udn::$nt\r\n")
                append("BOOTID.UPNP.ORG: 1\r\n")
                append("CONFIGID.UPNP.ORG: 1\r\n\r\n")
            }
            runCatching {
                sock.send(DatagramPacket(resp.toByteArray(Charsets.ISO_8859_1), resp.length, to))
            }
        }
    }

    private suspend fun advertiseLoop() {
        while (scope.isActive) {
            sendAlive()
            delay(15_000)
        }
    }

    private fun sendAlive() {
        val ip = localIp()
        for (nt in NOTIFY_TYPES) {
            val pkt = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                append("LOCATION: http://$ip:$httpPort/description.xml\r\n")
                append("NT: $nt\r\n")
                append("NTS: ssdp:alive\r\n")
                append("USN: $udn::$nt\r\n")
                append("SERVER: $SERVER_SIG\r\n")
                append("BOOTID.UPNP.ORG: 1\r\n")
                append("CONFIGID.UPNP.ORG: 1\r\n\r\n")
            }
            sendMulticast(pkt)
        }
    }

    private fun sendByebye() {
        for (nt in NOTIFY_TYPES) {
            val pkt = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                append("NT: $nt\r\n")
                append("NTS: ssdp:byebye\r\n")
                append("USN: $udn::$nt\r\n")
                append("BOOTID.UPNP.ORG: 1\r\n")
                append("CONFIGID.UPNP.ORG: 1\r\n\r\n")
            }
            sendMulticast(pkt)
        }
    }

    private fun sendMulticast(text: String) {
        runCatching {
            DatagramSocket().use { s ->
                val addr = InetAddress.getByName(SSDP_ADDR)
                val data = text.toByteArray(Charsets.ISO_8859_1)
                s.send(DatagramPacket(data, data.size, addr, SSDP_PORT))
            }
        }
    }

    /** 局域网 IPv4 地址（优先 wlan0），供 LOCATION 使用 */
    private fun localIp(): String {
        return runCatching {
            val ni = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { it.name.startsWith("wlan") }
            for (n in ni) {
                for (addr in n.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress ?: ""
                }
            }
            ""
        }.getOrDefault("")
    }
}
