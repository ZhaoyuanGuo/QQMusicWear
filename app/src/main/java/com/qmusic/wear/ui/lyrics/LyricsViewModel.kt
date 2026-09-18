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
    /** 原文行索引 → 罗马音（无罗马音行为空 map） */
    val roma: Map<Int, String> = emptyMap(),
    val activeIndex: Int = -1,
    /** 当前活动行的卡拉OK填充进度 0..1（行内时间占比） */
    val activeProgress: Float = 0f,
)

class LyricsViewModel : ViewModel() {

    private val _ui = MutableStateFlow(LyricsUiState())
    val ui: StateFlow<LyricsUiState> = _ui.asStateFlow()

    private var loadedMid: String? = null

    init {
        // 周期性根据播放位置刷新高亮行与卡拉OK填充进度（100ms 足够平滑，负载可控）
        viewModelScope.launch {
            while (true) {
                delay(100)
                val now = ServiceLocator.player.state.value
                val lines = _ui.value.lines
                val idx = activeIndexFor(now.positionMs)
                val progress = karaokeProgress(lines, idx, now.positionMs)
                val cur = _ui.value
                // 暂停/未播放时进度不变，避免无谓的状态更新
                if (idx != cur.activeIndex ||
                    (idx >= 0 && kotlin.math.abs(progress - cur.activeProgress) > 0.004f)
                ) {
                    _ui.value = cur.copy(activeIndex = idx, activeProgress = progress)
                }
            }
        }
    }

    /** 活动行内播放进度（行起始→下一行起始的占比）；无下一行时视为整行 */
    private fun karaokeProgress(lines: List<LyricLine>, idx: Int, positionMs: Long): Float {
        if (idx < 0 || idx >= lines.size) return 0f
        val start = lines[idx].timeMs
        val end = if (idx + 1 < lines.size) lines[idx + 1].timeMs else start + 1
        if (end <= start) return 1f
        return ((positionMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
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
            // 缓存优先：下载时已预取歌词的话（含离线场景）直接读盘，不发网络请求
            val cached = runCatching { ServiceLocator.lyricsCache.get(song.mid) }.getOrNull()
            if (cached != null) {
                applyLyrics(cached.text, cached.trans, cached.roma)
                return@launch
            }
            // 原文/译文/罗马音并行拉取；译文与罗马音失败/缺失不影响原文展示
            val textDeferred = async {
                runCatching { ServiceLocator.repository.lyricOf(song) }.getOrDefault("")
            }
            val transDeferred = async {
                runCatching { ServiceLocator.repository.lyricTransOf(song) }.getOrDefault("")
            }
            val romaDeferred = async {
                runCatching { ServiceLocator.repository.lyricRomaOf(song) }.getOrDefault("")
            }
            val text = textDeferred.await()
            val trans = transDeferred.await()
            val roma = romaDeferred.await()
            applyLyrics(text, trans, roma)
            // 拉取成功时回写缓存，下次离线可用
            if (text.isNotBlank()) {
                runCatching { ServiceLocator.lyricsCache.put(song.mid, text, trans, roma) }
            }
        }
    }

    private fun applyLyrics(text: String, trans: String, roma: String) {
        val lines = parseLrc(text)
        _ui.value = LyricsUiState(
            loading = false,
            lines = lines,
            trans = matchTrans(lines, parseLrc(trans)),
            roma = matchTrans(lines, parseLrc(roma)),
        )
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
