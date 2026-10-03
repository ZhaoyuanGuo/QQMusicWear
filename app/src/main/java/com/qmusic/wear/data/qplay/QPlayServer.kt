package com.qmusic.wear.data.qplay

import android.content.Context
import android.os.Build
import com.qmusic.wear.data.model.ResolvedUrl
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.player.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * QPlay/DLNA 渲染器：让手表出现在手机 QQ 音乐的 QPlay 设备列表里，
 * 接收投放（队列下发）并用本机 PlayerConnection 播放。
 *
 * 协议要点（Phase 0 实测，详见 tools/qplay_probe/）：
 * - 设备描述必须含 qq:X_QPlay_SoftwareCapability=QPlay:2 + 腾讯 QPlay:1 服务
 * - QPlayAuth 用占位 Code 即可通过
 * - SetTracksInfo 的 TracksMetaData 为 JSON 数组，trackURIs 为可直接播放的 CDN 直链
 * - GENA 事件必须回发（TRANSITIONING→PLAYING），否则手机端 UI 卡死、无限重发指令
 */
class QPlayServer(
    private val context: Context,
    private val player: PlayerConnection,
) {
    companion object {
        const val PORT = 49153
        private const val FRIENDLY_NAME = "QQMusicWear 手表"
        private const val UDN_KEY = "qplay_udn"

        // UPnP XML 转义
        private fun xmlEscape(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

        private fun xmlUnescape(s: String): String = s
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** "H:MM:SS" / "M:SS" → 秒 */
        private fun parseDuration(s: String): Int {
            val parts = s.split(":").mapNotNull { it.toIntOrNull() }
            if (parts.isEmpty()) return 0
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                else -> parts[0]
            }
        }

        /** 毫秒 → H:MM:SS */
        private fun hms(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "${s / 3600}:${(s % 3600) / 60 / 10}${(s % 3600) / 60 % 10}:${(s % 60) / 10}${s % 60}"
        }

        /** H:MM:SS → 毫秒 */
        private fun toMs(hmsStr: String): Long {
            val p = hmsStr.split(":").mapNotNull { it.toIntOrNull() }
            if (p.isEmpty()) return 0
            return when (p.size) {
                3 -> (p[0] * 3600 + p[1] * 60 + p[2]).toLong() * 1000
                2 -> (p[0] * 60 + p[1]).toLong() * 1000
                else -> p[0].toLong() * 1000
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val actionMutex = Mutex()

    private var http: MinimalHttpServer? = null
    private var ssdp: SsdpServer? = null
    private var scope: CoroutineScope? = null

    private val udn: String by lazy {
        val prefs = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE)
        prefs.getString(UDN_KEY, null) ?: "uuid:qmusicwear-${(0 until 8).map { "%01x".format((0..15).random()) }.joinToString("")}"
            .also { prefs.edit().putString(UDN_KEY, it).apply() }
    }

    // ---- QPlay 队列状态 ----
    private var rawTracks: List<JsonObject> = emptyList()
    private var tracks: List<QPlayTrack> = emptyList()
    private var queueId = ""
    private var pendingIndex = 0
    /** SetTracksInfo 已到达、等待 Play 启动新队列 */
    private var queueLoaded = false

    // ---- GENA 订阅 ----
    private class GenaSub(val sid: String, val callback: String)
    private val avtSubs = CopyOnWriteArrayList<GenaSub>()
    private val rcsSubs = CopyOnWriteArrayList<GenaSub>()
    private val seqMap = ConcurrentHashMap<String, AtomicInteger>()
    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(3))
        .build()

    /** 上次推送的 (状态#曲目) 指纹，去重用 */
    private var lastEventKey: String? = null

    private data class QPlayTrack(
        val uri: String,
        val title: String,
        val creator: String,
        val album: String,
        val artUri: String,
        val songId: String,
        val durationSec: Int,
        val raw: JsonObject,
    )

    fun start() {
        if (http != null) return
        val server = MinimalHttpServer(PORT) { req -> handle(req) }
        server.start()
        http = server
        ssdp = SsdpServer(context, PORT, udn).also { it.start() }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        // 播放状态变化 → GENA 事件回发
        s.launch {
            player.state.collectLatest { st ->
                val transport = transportState()
                val key = "$transport#${st.queueIndex}#${st.isPlaying}"
                if (key != lastEventKey) {
                    lastEventKey = key
                    notifyAvt()
                }
            }
        }
        android.util.Log.i("QPlayServer", "started, port=$PORT, udn=$udn")
    }

    fun stop() {
        http?.stop()
        http = null
        ssdp?.stop()
        ssdp = null
        avtSubs.clear()
        rcsSubs.clear()
        seqMap.clear()
        lastEventKey = null
        scope?.cancel()
        scope = null
        android.util.Log.i("QPlayServer", "stopped")
    }

    // ==================== HTTP 路由 ====================

    private suspend fun handle(req: MinimalHttpServer.Request): MinimalHttpServer.Response {
        val method = req.method
        val path = req.path
        return when {
            method == "GET" && path.startsWith("/description.xml") ->
                MinimalHttpServer.Response.text(deviceDescription(), headers = mapOf("CONTENT-TYPE" to "text/xml; charset=\"utf-8\""))

            method == "GET" && path.startsWith("/_urn-") && path.endsWith("_scpd.xml") ->
                MinimalHttpServer.Response.text(scpd(path.removePrefix("/")), headers = mapOf("CONTENT-TYPE" to "text/xml; charset=\"utf-8\""))

            method == "GET" && path.startsWith("/icon.png") -> MinimalHttpServer.Response(
                200, mapOf("CONTENT-TYPE" to "image/png"), tinyPng(),
            )

            method == "SUBSCRIBE" || method == "UNSUBSCRIBE" -> handleGena(req)

            method == "POST" && path.contains("_control") -> {
                val body = MinimalHttpServer.Response.text(handleSoap(req), headers = mapOf("CONTENT-TYPE" to "text/xml; charset=\"utf-8\""))
                body
            }

            else -> MinimalHttpServer.Response.text("", 404)
        }
    }

    private fun tinyPng(): ByteArray = android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
        android.util.Base64.DEFAULT,
    )

    // ==================== UPnP 描述文档 ====================

    private fun deviceDescription(): String {
        val ip = localIp()
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:qq="http://www.tencent.com">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>$FRIENDLY_NAME</friendlyName>
<manufacturer>Tencent</manufacturer>
<manufacturerURL>https://y.qq.com</manufacturerURL>
<modelDescription>QQMusicWear Watch Renderer</modelDescription>
<modelName>QQMusicWear</modelName>
<modelNumber>1.0</modelNumber>
<UDN>$udn</UDN>
<serviceList>
<service>
<serviceType>urn:schemas-tencent-com:service:QPlay:1</serviceType>
<serviceId>urn:tencent-com:serviceId:QPlay</serviceId>
<SCPDURL>_urn-schemas-upnp-org-service-QPlay_scpd.xml</SCPDURL>
<controlURL>_urn-schemas-upnp-org-service-QPlay_control</controlURL>
<eventSubURL>_urn-schemas-upnp-org-service-QPlay_event</eventSubURL>
</service>
<service>
<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
<SCPDURL>_urn-schemas-upnp-org-service-AVTransport_scpd.xml</SCPDURL>
<controlURL>_urn-schemas-upnp-org-service-AVTransport_control</controlURL>
<eventSubURL>_urn-schemas-upnp-org-service-AVTransport_event</eventSubURL>
</service>
<service>
<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
<SCPDURL>_urn-schemas-upnp-org-service-ConnectionManager_scpd.xml</SCPDURL>
<controlURL>_urn-schemas-upnp-org-service-ConnectionManager_control</controlURL>
<eventSubURL>_urn-schemas-upnp-org-service-ConnectionManager_event</eventSubURL>
</service>
<service>
<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
<SCPDURL>_urn-schemas-upnp-org-service-RenderingControl_scpd.xml</SCPDURL>
<controlURL>_urn-schemas-upnp-org-service-RenderingControl_control</controlURL>
<eventSubURL>_urn-schemas-upnp-org-service-RenderingControl_event</eventSubURL>
</service>
</serviceList>
<modelURL>https://y.qq.com</modelURL>
<qq:AppVersion>22.61</qq:AppVersion>
<qq:MiniVersion>2.1</qq:MiniVersion>
<qq:PlatformOS>android</qq:PlatformOS>
<qq:X_QPlay_SoftwareCapability>QPlay:2</qq:X_QPlay_SoftwareCapability>
</device>
</root>"""
    }

    /** SCPD 直接回放真实 QQ 音乐端抓取的文档（assets/qplay/） */
    private fun scpd(name: String): String = runCatching {
        context.assets.open("qplay/$name").bufferedReader().use { it.readText() }
    }.getOrElse {
        """<?xml version="1.0"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList></actionList><serviceStateTable></serviceStateTable></scpd>"""
    }

    private fun localIp(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress ?: "127.0.0.1"
    }.getOrDefault("127.0.0.1")

    // ==================== GENA 事件 ====================

    private fun handleGena(req: MinimalHttpServer.Request): MinimalHttpServer.Response {
        val isAvt = req.path.contains("AVTransport")
        return if (req.method == "SUBSCRIBE") {
            val callback = req.header("callback")?.substringAfter("<")?.substringBefore(">")
            val sid = "uuid:${java.util.UUID.randomUUID()}"
            if (callback != null) {
                val sub = GenaSub(sid, callback)
                (if (isAvt) avtSubs else rcsSubs).add(sub)
                // UPnP 规范：订阅成功立即回发初始状态事件（SEQ=0）
                scope?.launch { notify(if (isAvt) avtSubs else rcsSubs, sid) }
            }
            MinimalHttpServer.Response(
                200,
                mapOf("SID" to sid, "TIMEOUT" to "Second-1800", "CONTENT-LENGTH" to "0"),
            )
        } else {
            val sid = req.header("sid")
            if (sid != null) {
                avtSubs.removeAll { it.sid == sid }
                rcsSubs.removeAll { it.sid == sid }
                seqMap.remove(sid)
            }
            MinimalHttpServer.Response(200, mapOf("CONTENT-LENGTH" to "0"))
        }
    }

    private fun transportState(): String {
        val s = player.state.value
        return when {
            s.song == null -> "NO_MEDIA_PRESENT"
            s.isPlaying -> "PLAYING"
            else -> "PAUSED_PLAYBACK"
        }
    }

    private fun avtLastChange(): String {
        val s = player.state.value
        val idx = if (s.queueIndex >= 0) s.queueIndex else pendingIndex
        val track = tracks.getOrNull(idx)
        return "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">" +
            "<TransportState val=\"${transportState()}\"/>" +
            "<NumberOfTracks val=\"${tracks.size}\"/>" +
            "<CurrentTrack val=\"${idx + 1}\"/>" +
            "<CurrentTrackDuration val=\"${hms((track?.durationSec ?: (s.durationMs / 1000).toInt()).toLong() * 1000)}\"/>" +
            "<AVTransportURI val=\"${xmlEscape("qplay://$queueId")}\"/>" +
            "<CurrentTrackURI val=\"${xmlEscape(track?.uri ?: "")}\"/>" +
            "</InstanceID></Event>"
    }

    private suspend fun notifyAvt() = notify(avtSubs)

    private suspend fun notify(subs: CopyOnWriteArrayList<GenaSub>, onlySid: String? = null) {
        if (subs.isEmpty()) return
        val isAvt = subs === avtSubs
        val lastChange = if (isAvt) avtLastChange() else {
            "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
                "<Volume channel=\"Master\" val=\"${volumePercent()}\"/><Mute channel=\"Master\" val=\"0\"/>" +
                "</InstanceID></Event>"
        }
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
            "<e:property><LastChange>${xmlEscape(lastChange)}</LastChange></e:property></e:propertyset>"

        for (sub in subs) {
            if (onlySid != null && sub.sid != onlySid) continue
            val seq = seqMap.getOrPut(sub.sid) { AtomicInteger(0) }.getAndIncrement()
            runCatching {
                val request = okhttp3.Request.Builder()
                    .url(sub.callback)
                    .method("NOTIFY", okhttp3.RequestBody.create("text/xml; charset=\"utf-8\"".toMediaTypeOrNull(), body))
                    .header("SID", sub.sid)
                    .header("NT", "upnp:event")
                    .header("NTS", "upnp:propchange")
                    .header("SEQ", seq.toString())
                    .build()
                httpClient.newCall(request).execute().use { it.close() }
            }.onFailure {
                android.util.Log.w("QPlayServer", "GENA notify failed: ${it.message}")
            }
        }
    }

    private fun volumePercent(): Int {
        val max = player.maxVolume
        return if (max <= 0) 0 else (player.currentVolume * 100 / max).coerceIn(0, 100)
    }

    // ==================== SOAP 动作 ====================

    private suspend fun handleSoap(req: MinimalHttpServer.Request): String {
        val soapAction = (req.header("soapaction") ?: "").replace("\"", "")
        val (service, action) = if (soapAction.contains("#")) {
            val i = soapAction.indexOf('#')
            soapAction.substring(0, i) to soapAction.substring(i + 1)
        } else {
            "urn:schemas-upnp-org:service:AVTransport:1" to "Unknown"
        }
        val body = req.body
        android.util.Log.d("QPlayServer", "SOAP $action")

        // 序列化处理，避免并发操作队列状态
        return actionMutex.withLock {
            when (action) {
                "QPlayAuth" -> {
                    val seed = arg(body, "Seed")
                    soapResponse(service, action, "<Code>${md5(seed)}</Code><MID>QMW</MID><DID>WATCH</DID>")
                }

                "SetTracksInfo" -> {
                    val raw = arg(body, "TracksMetaData")
                    val qid = arg(body, "QueueID")
                    loadQueue(raw, qid)
                    soapResponse(service, action, "<NumberOfSuccess>${tracks.size}</NumberOfSuccess>")
                }

                "SetAVTransportURI" -> {
                    val uri = arg(body, "CurrentURI")
                    if (uri.startsWith("qplay://")) queueId = uri.removePrefix("qplay://")
                    soapResponse(service, action, "")
                }

                "Seek" -> {
                    val unit = arg(body, "Unit")
                    val target = arg(body, "Target")
                    when {
                        unit == "TRACK_NR" -> {
                            val nr = (target.toIntOrNull() ?: 1).coerceAtLeast(1) - 1
                            pendingIndex = nr
                            // 新队列未启动：只记索引，等 Play；已在播放：直接跳曲
                            if (!queueLoaded && player.state.value.song != null) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) { player.playAt(nr) }
                            }
                        }
                        unit == "REL_TIME" -> {
                            kotlinx.coroutines.withContext(Dispatchers.Main) { player.seekTo(toMs(target)) }
                        }
                    }
                    notify(avtSubs)
                    soapResponse(service, action, "")
                }

                "Play" -> {
                    val s = player.state.value
                    if (queueLoaded && tracks.isNotEmpty()) {
                        // 新队列：填充队列并从索引处开播
                        val songs = tracks.map { it.toSong() }
                        val urls = tracks.map { ResolvedUrl(url = it.uri, prefix = "QPLAY") }
                        val start = pendingIndex.coerceIn(0, tracks.lastIndex)
                        queueLoaded = false
                        kotlinx.coroutines.withContext(Dispatchers.Main) { player.play(songs, urls, start) }
                    } else if (s.song != null && !s.isPlaying) {
                        // 暂停恢复（重复 Play 只回发事件，不重置队列）
                        kotlinx.coroutines.withContext(Dispatchers.Main) { player.togglePlayPause() }
                    }
                    notify(avtSubs)
                    soapResponse(service, action, "")
                }

                "Pause" -> {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { player.pause() }
                    notify(avtSubs)
                    soapResponse(service, action, "")
                }

                "Stop" -> {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { player.pause() }
                    notify(avtSubs)
                    soapResponse(service, action, "")
                }

                "SetPlayMode" -> {
                    // REPEAT_ALL/REPEAT_ONE/NORMAL/SHUFFLE：跟随手表自身播放模式，静默接受
                    soapResponse(service, action, "")
                }

                "GetTransportInfo" -> soapResponse(
                    service, action,
                    "<CurrentTransportState>${transportState()}</CurrentTransportState>" +
                        "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>",
                )

                "GetPositionInfo" -> {
                    val s = player.state.value
                    val idx = if (s.queueIndex >= 0) s.queueIndex else pendingIndex
                    val track = tracks.getOrNull(idx)
                    val durationMs = (track?.durationSec ?: (s.durationMs / 1000).toInt()).toLong() * 1000
                    soapResponse(
                        service, action,
                        "<Track>${idx + 1}</Track>" +
                            "<TrackDuration>${hms(durationMs)}</TrackDuration>" +
                            "<TrackMetaData></TrackMetaData>" +
                            "<TrackURI>${xmlEscape(track?.uri ?: "")}</TrackURI>" +
                            "<RelTime>${hms(s.positionMs)}</RelTime>" +
                            "<AbsTime>${hms(s.positionMs)}</AbsTime>" +
                            "<RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>",
                    )
                }

                "GetMediaInfo" -> {
                    val s = player.state.value
                    val idx = if (s.queueIndex >= 0) s.queueIndex else pendingIndex
                    val track = tracks.getOrNull(idx)
                    val durationMs = (track?.durationSec ?: (s.durationMs / 1000).toInt()).toLong() * 1000
                    soapResponse(
                        service, action,
                        "<NrTracks>${tracks.size}</NrTracks>" +
                            "<MediaDuration>${hms(durationMs)}</MediaDuration>" +
                            "<CurrentURI>${xmlEscape("qplay://$queueId")}</CurrentURI>" +
                            "<CurrentURIMetaData></CurrentURIMetaData>" +
                            "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>" +
                            "<PlayMedium>NETWORK</PlayMedium>" +
                            "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>" +
                            "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>",
                    )
                }

                "GetTracksInfo" -> {
                    val meta = xmlEscape(json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("TracksMetaData" to JsonArray(rawTracks)))))
                    soapResponse(service, action, "<StartingIndex>0</StartingIndex><TracksMetaData>$meta</TracksMetaData>")
                }

                "GetTracksCount" -> soapResponse(service, action, "<NrTracks>${tracks.size}</NrTracks>")
                "GetMaxTracks" -> soapResponse(service, action, "<MaxTracks>300</MaxTracks>")

                "GetMute" -> soapResponse(service, action, "<CurrentMute>0</CurrentMute>")
                "GetVolume" -> soapResponse(service, action, "<CurrentVolume>${volumePercent()}</CurrentVolume>")
                "SetVolume" -> {
                    val v = (arg(body, "DesiredVolume").toIntOrNull() ?: 0).coerceIn(0, 100)
                    val max = player.maxVolume
                    if (max > 0) {
                        val target = (v * max + 50) / 100
                        kotlinx.coroutines.withContext(Dispatchers.Main) { player.setVolume(target) }
                    }
                    soapResponse(service, action, "")
                }
                "SetMute" -> soapResponse(service, action, "")

                "GetProtocolInfo" -> soapResponse(
                    service, action,
                    "<Source></Source>" +
                        "<Sink>http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;http-get:*:audio/mp4:DLNA.ORG_OP=01;http-get:*:audio/x-flac:DLNA.ORG_OP=01;http-get:*:audio/ogg:DLNA.ORG_OP=01;</Sink>",
                )

                "GetCurrentConnectionIDs" -> soapResponse(service, action, "<ConnectionIDs>0</ConnectionIDs>")
                "GetCurrentConnectionInfo" -> soapResponse(
                    service, action,
                    "<RcsID>0</RcsID><AVTransportID>0</AVTransportID>" +
                        "<ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager>" +
                        "<PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction>" +
                        "<Status>OK</Status>",
                )

                else -> soapResponse(service, action, "")
            }
        }
    }

    private fun arg(body: String, name: String): String =
        Regex("<$name>([\\s\\S]*?)</$name>").find(body)?.groupValues?.get(1)?.trim() ?: ""

    private fun soapResponse(service: String, action: String, inner: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:${action}Response xmlns:u="$service">
$inner
</u:${action}Response>
</s:Body>
</s:Envelope>"""

    // ==================== QPlay 队列 → 播放器 ====================

    /** 解析 TracksMetaData（XML 转义的 JSON），填充队列状态 */
    private fun loadQueue(metaXmlEscaped: String, qid: String) {
        if (qid.isNotEmpty()) queueId = qid
        runCatching {
            val root = json.parseToJsonElement(xmlUnescape(metaXmlEscaped)).jsonObject
            val arr = root["TracksMetaData"]?.jsonArray ?: return
            rawTracks = arr.map { it.jsonObject }
            tracks = rawTracks.mapNotNull { o ->
                val uri = (o["trackURIs"] as? JsonArray)?.firstOrNull()
                    ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?: return@mapNotNull null
                QPlayTrack(
                    uri = uri,
                    title = o.str("title"),
                    creator = o.str("creator"),
                    album = o.str("album"),
                    artUri = o.str("albumArtURI"),
                    songId = o.str("songID"),
                    durationSec = parseDuration(o.str("duration")),
                    raw = o,
                )
            }
            pendingIndex = 0
            queueLoaded = true
            android.util.Log.i("QPlayServer", "queue loaded: id=$queueId size=${tracks.size} first=${tracks.firstOrNull()?.title}")
        }.onFailure {
            android.util.Log.e("QPlayServer", "SetTracksInfo parse failed", it)
        }
    }

    private fun JsonObject.str(name: String): String =
        this[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: ""

    /** QPlay 曲目 → 本 app 的 Song 模型（直链播放，无需音乐源解析；mid 用数字 ID 以便下载/历史兜底） */
    private fun QPlayTrack.toSong(): Song = Song(
        songId = songId.toLongOrNull() ?: 0L,
        mid = songId,
        name = title,
        singers = creator,
        albumName = album,
        albumMid = "",
        mediaMid = "",
        intervalSec = durationSec,
        songType = 0,
        vip = false,
        cover300 = artUri,
        cover500 = artUri,
    )
}
