package com.qmusic.wear

import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.data.player.PlaybackFallback
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放失败自愈的音质候选测试。
 * 关键回归点：标准档必须包含自身（否则标准音质播放失败后无档可降、兜底失效）。
 */
class PlaybackFallbackTest {

    @Test
    fun `standard quality includes itself as last candidate`() {
        val c = PlaybackFallback.qualityCandidates(Quality.STANDARD)
        assertEquals(listOf(Quality.STANDARD), c)
    }

    @Test
    fun `high quality descends high then standard`() {
        val c = PlaybackFallback.qualityCandidates(Quality.HIGH)
        assertEquals(listOf(Quality.HIGH, Quality.STANDARD), c)
    }

    @Test
    fun `hi res descends all the way to standard`() {
        val c = PlaybackFallback.qualityCandidates(Quality.HI_RES)
        assertEquals(
            listOf(Quality.HI_RES, Quality.LOSSLESS, Quality.HIGH, Quality.STANDARD),
            c,
        )
    }

    @Test
    fun `candidates are strictly descending by ordinal`() {
        val c = PlaybackFallback.qualityCandidates(Quality.LOSSLESS)
        assertEquals(c.sortedByDescending { it.ordinal }, c)
    }

    @Test
    fun `unknown quality falls back to all qualities`() {
        val c = PlaybackFallback.qualityCandidates(null)
        assertEquals(Quality.entries.toList(), c)
    }
}
