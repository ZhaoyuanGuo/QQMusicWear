package com.qmusic.wear.data.qplay

import com.qmusic.wear.data.api.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * QPlay/UPnP 协议中的纯解析函数（无 Android 依赖，便于 JVM 单测）。
 *
 * 与 [QPlayServer] 的 SOAP/GENA 交互解耦：时间格式换算、XML 转义、
 * SOAP 参数提取、TracksMetaData 解析都在此集中实现。
 */
internal object QPlayProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    /** UPnP XML 文本转义 */
    fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** UPnP XML 文本反转义 */
    fun xmlUnescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** "H:MM:SS" / "M:SS" / "S" → 秒（无法解析返回 0） */
    fun parseDuration(s: String): Int {
        val parts = s.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return 0
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> parts[0]
        }
    }

    /**
     * 毫秒 → H:MM:SS（负值按 0 处理）。
     *
     * 注意：早期实现用 `${s % 60 / 10}${s % 60}` 拼秒字段，秒数 ≥10 时会拼错
     * （如 45 秒 → "0:00:445"），发往控制点的时长字符串非法。此处改为标准补零格式。
     */
    fun hms(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return "%d:%02d:%02d".format(h, m, sec)
    }

    /** H:MM:SS / M:SS → 毫秒（无法解析返回 0） */
    fun toMs(hmsStr: String): Long {
        val p = hmsStr.split(":").mapNotNull { it.toIntOrNull() }
        if (p.isEmpty()) return 0
        return when (p.size) {
            3 -> (p[0] * 3600 + p[1] * 60 + p[2]).toLong() * 1000
            2 -> (p[0] * 60 + p[1]).toLong() * 1000
            else -> p[0].toLong() * 1000
        }
    }

    /** 从 SOAP 请求体提取 `<name>...</name>` 文本（缺失返回空串） */
    fun arg(body: String, name: String): String =
        Regex("<$name>([\\s\\S]*?)</$name>").find(body)?.groupValues?.get(1)?.trim() ?: ""

    /**
     * 从 socket 远端地址提取客户端 IP（去掉 "/" 前缀与端口），未知返回空串。
     * 形如 "/192.168.1.5:52341"、"192.168.1.5:52341"、"/192.168.1.5" 均可正确解析。
     */
    fun clientIp(remoteAddress: String): String =
        remoteAddress.substringAfter("/").substringBefore(":").trim()

    /** 单条 QPlay 曲目元数据（TracksMetaData 数组的一项） */
    data class TrackMeta(
        val uri: String,
        val title: String,
        val creator: String,
        val album: String,
        val artUri: String,
        val songId: String,
        val durationSec: Int,
        val raw: JsonObject,
    )

    /** TracksMetaData 解析结果：原始 JSON 对象（GetTracksInfo 回发用）+ 已解析曲目 */
    data class TrackParseResult(val raw: List<JsonObject>, val tracks: List<TrackMeta>)

    /**
     * 解析 SetTracksInfo 的 TracksMetaData（XML 转义的 JSON 字符串）。
     * 无 trackURIs 的项被跳过（不可播放）；解析失败返回空结果，绝不抛异常。
     */
    fun parseTracks(metaXmlEscaped: String): TrackParseResult = runCatching {
        val root = json.parseToJsonElement(xmlUnescape(metaXmlEscaped)).jsonObject
        val arr = root["TracksMetaData"]?.jsonArray ?: return TrackParseResult(emptyList(), emptyList())
        val raw = arr.map { it.jsonObject }
        val tracks = raw.mapNotNull { o ->
            val uri = (o["trackURIs"] as? JsonArray)?.firstOrNull()
                ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?: return@mapNotNull null
            TrackMeta(
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
        TrackParseResult(raw, tracks)
    }.getOrDefault(TrackParseResult(emptyList(), emptyList()))
}
