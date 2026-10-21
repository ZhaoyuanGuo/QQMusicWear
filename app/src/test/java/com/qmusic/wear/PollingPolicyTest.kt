package com.qmusic.wear

import com.qmusic.wear.data.player.PollingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放状态轮询策略测试。
 * 核心：播放中 500ms；暂停/空闲但前台亮屏 2s；后台+灭屏+未播放进入 60s 休眠档。
 * 快照仅在播放中且间隔达标时写盘。
 */
class PollingPolicyTest {

    @Test
    fun `playing polls at 500ms`() {
        assertEquals(500L, PollingPolicy.intervalMs(isPlaying = true, interactive = true))
        // 播放中即使不在前台（后台听歌）也保持平滑刷新
        assertEquals(500L, PollingPolicy.intervalMs(isPlaying = true, interactive = false))
    }

    @Test
    fun `paused but interactive polls at 2s to save battery`() {
        assertEquals(2_000L, PollingPolicy.intervalMs(isPlaying = false, interactive = true))
        assertTrue(PollingPolicy.intervalMs(false, true) > PollingPolicy.intervalMs(true, true))
    }

    @Test
    fun `idle and non-interactive enters dormant tier`() {
        assertEquals(60_000L, PollingPolicy.intervalMs(isPlaying = false, interactive = false))
        // 休眠档必须比前台空闲档更省电
        assertTrue(PollingPolicy.intervalMs(false, false) > PollingPolicy.intervalMs(false, true))
    }

    @Test
    fun `persist only when playing and interval reached`() {
        assertTrue(
            PollingPolicy.shouldPersist(
                isPlaying = true, restoring = false, queueEmpty = false,
                sinceLastPersistMs = 5_000L,
            ),
        )
    }

    @Test
    fun `no persist when paused`() {
        assertFalse(
            PollingPolicy.shouldPersist(
                isPlaying = false, restoring = false, queueEmpty = false,
                sinceLastPersistMs = 10_000L,
            ),
        )
    }

    @Test
    fun `no persist while restoring`() {
        assertFalse(
            PollingPolicy.shouldPersist(
                isPlaying = true, restoring = true, queueEmpty = false,
                sinceLastPersistMs = 10_000L,
            ),
        )
    }

    @Test
    fun `no persist for empty queue`() {
        assertFalse(
            PollingPolicy.shouldPersist(
                isPlaying = true, restoring = false, queueEmpty = true,
                sinceLastPersistMs = 10_000L,
            ),
        )
    }

    @Test
    fun `no persist before interval elapses`() {
        assertFalse(
            PollingPolicy.shouldPersist(
                isPlaying = true, restoring = false, queueEmpty = false,
                sinceLastPersistMs = 4_999L,
            ),
        )
    }
}
