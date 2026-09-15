package com.qmusic.wear

import com.qmusic.wear.data.source.SourceDtos
import com.qmusic.wear.data.source.SearchResultDto
import com.qmusic.wear.data.source.ResolvedUrlDto
import com.qmusic.wear.data.source.ResolveResultDto
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
}
