package com.qmusic.wear

import com.qmusic.wear.data.source.ArtistAlbumsDto
import com.qmusic.wear.data.source.DjProgramDto
import com.qmusic.wear.data.source.FollowUserDto
import com.qmusic.wear.data.source.RadioDto
import com.qmusic.wear.data.source.SongCommentsDto
import com.qmusic.wear.data.source.SourceDtos
import com.qmusic.wear.data.source.SearchResultDto
import com.qmusic.wear.data.source.ResolvedUrlDto
import com.qmusic.wear.data.source.ResolveResultDto
import com.qmusic.wear.data.source.UserEventDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 源插件返回值 DTO 契约测试（与 qmusic_source.js 输出字段一一对应） */
class SourceDtosTest {

    private val json = SourceDtos.json

    @Test
    fun `search result parses with defaults on missing fields`() {
        val dto = json.decodeFromString(
            SearchResultDto.serializer(),
            """{"songs":[{"mid":"001","name":"歌名","singers":"A/B"}]}""",
        )
        assertEquals(1, dto.songs.size)
        assertEquals("001", dto.songs[0].mid)
        assertEquals("歌名", dto.songs[0].name)
        assertEquals(0L, dto.songs[0].songId)
        assertEquals(0, dto.singers.size)
        assertEquals(0, dto.playlists.size)
    }

    @Test
    fun `singers list field maps to joined string model`() {
        val dto = json.decodeFromString(
            SearchResultDto.serializer(),
            """{"songs":[{"mid":"m1","name":"n","singers":"A/B"}]}""",
        )
        // singers 为字符串时原样解析；为数组时由 JS 侧 join，Kotlin 只收字符串
        assertEquals("A/B", dto.songs[0].singers)
    }

    @Test
    fun `resolved url defaults ext to mp3`() {
        val dto = json.decodeFromString(
            ResolvedUrlDto.serializer(),
            """{"url":"https://x"}""",
        )
        assertEquals("mp3", dto.ext)
        assertFalse(dto.encrypted)
    }

    @Test
    fun `resolve result tolerates null items and debug`() {
        val dto = json.decodeFromString(
            ResolveResultDto.serializer(),
            """{"items":[null,{"url":"u","ekey":"k","encrypted":true,"prefix":"RS02","ext":"flac"}],"debug":"d"}""",
        )
        assertEquals(2, dto.items.size)
        assertEquals("d", dto.debug)
        val second = dto.items[1]!!
        assertTrue(second.encrypted)
        assertEquals("flac", second.ext)
    }

    @Test
    fun `unknown fields are ignored`() {
        val dto = json.decodeFromString(
            ResolvedUrlDto.serializer(),
            """{"url":"u","futureField":123}""",
        )
        assertEquals("u", dto.url)
    }

    @Test
    fun `song comments parse hot and normal lists`() {
        val dto = json.decodeFromString(
            SongCommentsDto.serializer(),
            """{"hotComments":[{"userName":"H","content":"hot","likedCount":9}],
                "comments":[{"userName":"C","content":"nice","time":1,"likedCount":2}],
                "total":30,"more":true,"future":1}""",
        )
        val model = dto.toModel()
        assertEquals(1, model.hotComments.size)
        assertEquals("hot", model.hotComments[0].content)
        assertEquals(1, model.comments.size)
        assertEquals("nice", model.comments[0].content)
        assertEquals(30, model.total)
        assertTrue(model.more)
    }

    @Test
    fun `artist albums parse and default empty`() {
        val dto = json.decodeFromString(
            ArtistAlbumsDto.serializer(),
            """{"albums":[{"albumMid":"77","name":"Alb","songCount":5}],"hasMore":true}""",
        )
        assertEquals(1, dto.albums.size)
        assertEquals("77", dto.albums[0].albumMid)
        assertTrue(dto.hasMore)
        val empty = json.decodeFromString(ArtistAlbumsDto.serializer(), """{}""")
        assertEquals(0, empty.albums.size)
        assertFalse(empty.hasMore)
    }

    @Test
    fun `dj program parses nested song and radio`() {
        val dto = json.decodeFromString(
            DjProgramDto.serializer(),
            """{"programId":1,"name":"P","durationSec":60,"radioId":9,"radioName":"R",
                "song":{"mid":"ne_1","name":"A"}}""",
        )
        val model = dto.toModel()
        assertEquals(1L, model.programId)
        assertEquals(9L, model.radioId)
        assertEquals("ne_1", model.song?.mid)
    }

    @Test
    fun `radio user event and follow dtos map fields`() {
        val radio = json.decodeFromString(
            RadioDto.serializer(),
            """{"id":9,"name":"R","programCount":5,"djName":"DJ"}""",
        ).toModel()
        assertEquals(9L, radio.id)
        assertEquals("DJ", radio.djName)

        val event = json.decodeFromString(
            UserEventDto.serializer(),
            """{"id":1,"type":18,"userName":"E","content":"分享","songs":[{"mid":"ne_1","name":"A"}]}""",
        ).toModel()
        assertEquals("E", event.userName)
        assertEquals(1, event.songs.size)

        val follow = json.decodeFromString(
            FollowUserDto.serializer(),
            """{"userId":5,"nick":"F","signature":"s"}""",
        ).toModel()
        assertEquals(5L, follow.userId)
        assertEquals("F", follow.nick)
    }
}
