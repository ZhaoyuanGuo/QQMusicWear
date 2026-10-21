package com.qmusic.wear.data.player

import com.qmusic.wear.data.model.Quality

/**
 * 播放失败自愈的纯决策逻辑（与 Android/网络解耦，便于 JVM 单测）。
 */
internal object PlaybackFallback {

    /**
     * 兜底重解析的音质候选：从当前档位**含自身**起逐级降档（高 → 低）。
     *
     * 含当前档位是刻意的：标准档已是最后一档，若只降档则候选为空、兜底完全失效
     * （曾因此导致标准音质歌曲播放失败后无法自愈）。当前档位未知时退化为全部档位。
     */
    fun qualityCandidates(current: Quality?): List<Quality> {
        val base = current ?: return Quality.entries.toList()
        return Quality.entries
            .filter { it.ordinal <= base.ordinal }
            .sortedByDescending { it.ordinal }
    }
}
