package com.qmusic.wear

import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.store.SongJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SongJson] 编解码往返测试。
 * 统一实现后，各本地 Store（历史/队列/红心/下载）共用同一份字段定义，
 * 这里覆盖「字段不丢失」这一历史高发 bug（如下载记录丢封面）。
 */
class SongJsonTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val fullSong = Song(
        songId = 12345L,
        mid = "kg_abc",
        name = "歌名",
        singers = "歌手A/歌手B",
        albumName = "专辑",
        albumMid = "alb_1",
        mediaMid = "mm_1",
        intervalSec = 240,
        songType = 1,
        vip = true,
        cover300 = "https://img/300.jpg",
        cover500 = "https://img/500.jpg",
    )

    @Test
    fun `round trip preserves all persisted fields`() {
        val encoded = SongJson.encode(fullSong)!!
        // 经字符串序列化再解析，模拟真实落盘/读盘
        val decoded = SongJson.decode(json.parseToJsonElement(encoded.toString()).jsonObject)!!
        assertEquals(fullSong.songId, decoded.songId)
        assertEquals(fullSong.mid, decoded.mid)
        assertEquals(fullSong.name, decoded.name)
        assertEquals(fullSong.singers, decoded.singers)
        assertEquals(fullSong.albumName, decoded.albumName)
        assertEquals(fullSong.albumMid, decoded.albumMid)
        assertEquals(fullSong.mediaMid, decoded.mediaMid)
        assertEquals(fullSong.intervalSec, decoded.intervalSec)
        assertEquals(fullSong.songType, decoded.songType)
        assertEquals(fullSong.vip, decoded.vip)
        assertEquals(fullSong.cover300, decoded.cover300)
        assertEquals(fullSong.cover500, decoded.cover500)
    }

    @Test
    fun `vip flag survives boolean encoding`() {
        val encoded = SongJson.encode(fullSong.copy(vip = true))!!
        assertTrue((encoded["vip"]?.toString() ?: "").contains("true"))
        assertEquals(true, SongJson.decode(encoded)!!.vip)
    }

    @Test
    fun `encode returns null for blank mid`() {
        assertNull(SongJson.encode(Song(mid = "")))
    }

    @Test
    fun `decode returns null when mid missing`() {
        val obj = json.parseToJsonElement("""{"songId":1,"name":"x"}""").jsonObject
        assertNull(SongJson.decode(obj))
    }

    @Test
    fun `decode applies defaults on missing fields`() {
        val obj = json.parseToJsonElement("""{"mid":"m1"}""").jsonObject
        val song = SongJson.decode(obj)!!
        assertEquals("m1", song.mid)
        assertEquals(0L, song.songId)
        assertEquals("", song.name)
        assertEquals(false, song.vip)
    }

    @Test
    fun `decode ignores unknown future fields`() {
        val obj: JsonObject = json.parseToJsonElement(
            """{"mid":"m1","name":"n","futureField":123}""",
        ).jsonObject
        val song = SongJson.decode(obj)!!
        assertEquals("m1", song.mid)
        assertEquals("n", song.name)
    }
}
