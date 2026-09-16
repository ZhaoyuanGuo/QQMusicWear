package com.qmusic.wear.ui.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 单行歌词 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

/** 歌词页 UI 状态 */
data class LyricsUiState(
    val loading: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
    /** 原文行索引 → 译文（无翻译行为空 map） */
    val trans: Map<Int, String> = emptyMap(),
    val activeIndex: Int = -1,
)

class LyricsViewModel : ViewModel() {

    private val _ui = MutableStateFlow(LyricsUiState())
    val ui: StateFlow<LyricsUiState> = _ui.asStateFlow()

    private var loadedMid: String? = null

    init {
        // 周期性根据播放位置刷新高亮行
        viewModelScope.launch {
            while (true) {
                delay(300)
                val now = ServiceLocator.player.state.value
                val idx = activeIndexFor(now.positionMs)
                if (idx != _ui.value.activeIndex) {
                    _ui.value = _ui.value.copy(activeIndex = idx)
                }
            }
        }
    }

    private fun activeIndexFor(positionMs: Long): Int {
        val lines = _ui.value.lines
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        return idx
    }

    fun loadLyric(song: Song) {
        if (loadedMid == song.mid && _ui.value.lines.isNotEmpty()) return
        loadedMid = song.mid
        viewModelScope.launch {
            _ui.value = LyricsUiState(loading = true)
            // 原文与译文并行拉取；译文失败/缺失不影响原文展示
            val textDeferred = async {
                runCatching { ServiceLocator.repository.lyricOf(song) }.getOrDefault("")
            }
            val transDeferred = async {
                runCatching { ServiceLocator.repository.lyricTransOf(song) }.getOrDefault("")
            }
            val lines = parseLrc(textDeferred.await())
            val transLines = parseLrc(transDeferred.await())
            _ui.value = LyricsUiState(
                loading = false,
                lines = lines,
                trans = matchTrans(lines, transLines),
            )
        }
    }

    /** 按时间轴把译文行对齐到原文行：每条原文取时间点之前最近的译文 */
    private fun matchTrans(origin: List<LyricLine>, trans: List<LyricLine>): Map<Int, String> {
        if (origin.isEmpty() || trans.isEmpty()) return emptyMap()
        val out = mutableMapOf<Int, String>()
        var j = 0
        origin.forEachIndexed { i, line ->
            while (j + 1 < trans.size && trans[j + 1].timeMs <= line.timeMs) j++
            if (trans[j].timeMs <= line.timeMs) {
                val t = trans[j].text
                if (t.isNotBlank() && t != line.text) out[i] = t
            }
        }
        return out
    }

    /** 解析 LRC：支持一行多个时间戳 */
    private fun parseLrc(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()
        val regex = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        val out = mutableListOf<LyricLine>()
        lrc.lineSequence().forEach { line ->
            val stamps = regex.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val text = line.substringAfterLast(']').trim()
            if (text.isEmpty()) return@forEach
            stamps.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                out.add(LyricLine(timeMs = min * 60000 + sec * 1000 + frac, text = text))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
