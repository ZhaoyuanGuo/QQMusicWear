package com.qmusic.wear

import com.qmusic.wear.data.qplay.QPlayProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QPlay/UPnP 协议纯函数测试：时间格式换算、XML 转义往返、
 * SOAP 参数提取、TracksMetaData 解析（含脏数据容错）。
 */
class QPlayProtocolTest {

    @Test
    fun `parseDuration handles hms ms and plain seconds`() {
        assertEquals(3723, QPlayProtocol.parseDuration("1:02:03"))
        assertEquals(123, QPlayProtocol.parseDuration("2:03"))
        assertEquals(45, QPlayProtocol.parseDuration("45"))
        assertEquals(0, QPlayProtocol.parseDuration(""))
        assertEquals(0, QPlayProtocol.parseDuration("abc"))
    }

    @Test
    fun `hms formats with zero padded fields`() {
        assertEquals("0:00:00", QPlayProtocol.hms(0))
        assertEquals("0:01:05", QPlayProtocol.hms(65_000))
        assertEquals("1:02:03", QPlayProtocol.hms(3_723_000))
        // 负值按 0 处理
        assertEquals("0:00:00", QPlayProtocol.hms(-5000))
    }

    @Test
    fun `hms and toMs round trip`() {
        val samples = listOf(0L, 65_000L, 3_723_000L, 59_000L)
        samples.forEach { ms ->
            assertEquals(ms, QPlayProtocol.toMs(QPlayProtocol.hms(ms)))
        }
    }

    @Test
    fun `toMs parses hms and ms`() {
        assertEquals(3_723_000L, QPlayProtocol.toMs("1:02:03"))
        assertEquals(123_000L, QPlayProtocol.toMs("2:03"))
        assertEquals(0L, QPlayProtocol.toMs("nonsense"))
    }

    @Test
    fun `xml escape and unescape round trip`() {
        val raw = """Tom & Jerry <"quoted"> 'single'"""
        val escaped = QPlayProtocol.xmlEscape(raw)
        assertTrue(escaped.contains("&amp;"))
        assertTrue(escaped.contains("&lt;"))
        assertEquals(raw, QPlayProtocol.xmlUnescape(escaped))
    }

    @Test
    fun `arg extracts soap field and defaults empty`() {
        val body = "<CurrentURI>http://a/b.mp3</CurrentURI><QueueID>42</QueueID>"
        assertEquals("http://a/b.mp3", QPlayProtocol.arg(body, "CurrentURI"))
        assertEquals("42", QPlayProtocol.arg(body, "QueueID"))
        assertEquals("", QPlayProtocol.arg(body, "Missing"))
    }

    @Test
    fun `parseTracks extracts uri and metadata`() {
        val meta = """
            {"TracksMetaData":[
              {"trackURIs":["http://cdn/a.mp3"],"title":"歌A","creator":"歌手A",
               "album":"专辑","albumArtURI":"http://img/a.jpg","songID":"1001","duration":"3:45"},
              {"trackURIs":["http://cdn/b.mp3"],"title":"歌B","songID":"1002","duration":"0:30"}
            ]}
        """.trimIndent()
        val result = QPlayProtocol.parseTracks(QPlayProtocol.xmlEscape(meta))
        assertEquals(2, result.raw.size)
        assertEquals(2, result.tracks.size)
        assertEquals("http://cdn/a.mp3", result.tracks[0].uri)
        assertEquals("歌A", result.tracks[0].title)
        assertEquals("歌手A", result.tracks[0].creator)
        assertEquals("1001", result.tracks[0].songId)
        assertEquals(225, result.tracks[0].durationSec)
        assertEquals(30, result.tracks[1].durationSec)
    }

    @Test
    fun `parseTracks skips entries without trackURIs`() {
        val meta = """
            {"TracksMetaData":[
              {"title":"无地址","songID":"1"},
              {"trackURIs":["http://cdn/x.mp3"],"title":"有地址","songID":"2"}
            ]}
        """.trimIndent()
        val result = QPlayProtocol.parseTracks(QPlayProtocol.xmlEscape(meta))
        // 无地址项被跳过，但 raw 保留（GetTracksInfo 需原样回发）
        assertEquals(2, result.raw.size)
        assertEquals(1, result.tracks.size)
        assertEquals("有地址", result.tracks[0].title)
    }

    @Test
    fun `parseTracks tolerates malformed json`() {
        val result = QPlayProtocol.parseTracks("not-a-json")
        assertEquals(0, result.raw.size)
        assertEquals(0, result.tracks.size)
    }

    @Test
    fun `md5 matches known vector`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", QPlayProtocol.md5("abc"))
    }

    @Test
    fun `clientIp strips slash prefix and port`() {
        assertEquals("192.168.1.5", QPlayProtocol.clientIp("/192.168.1.5:52341"))
        assertEquals("192.168.1.5", QPlayProtocol.clientIp("192.168.1.5:52341"))
        assertEquals("192.168.1.5", QPlayProtocol.clientIp("/192.168.1.5"))
        assertEquals("", QPlayProtocol.clientIp(""))
    }
}
