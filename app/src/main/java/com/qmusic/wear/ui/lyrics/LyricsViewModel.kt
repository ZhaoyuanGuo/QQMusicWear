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
)

class LyricsViewModel : ViewModel() {

    private val _ui = MutableStateFlow(LyricsUiState())
    val ui: StateFlow<LyricsUiState> = _ui.asStateFlow()

    private var loadedMid: String? = null

    init {
        // 周期性根据播放位置刷新高亮行（100ms 足够）；卡拉OK填充进度由
        // 歌词页对活动行按帧驱动（withFrameNanos + positionNow），不走本循环
        viewModelScope.launch {
            while (true) {
                delay(100)
                val now = ServiceLocator.player.state.value
                val idx = activeIndexFor(now.positionMs)
                val cur = _ui.value
                if (idx != cur.activeIndex) {
                    _ui.value = cur.copy(activeIndex = idx)
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
            // 缓存优先：下载时已预取歌词的话（含离线场景）直接读盘，不发网络请求
            val cached = runCatching { ServiceLocator.lyricsCache.get(song.mid) }.getOrNull()
            if (cached != null) {
                applyLyrics(
                    stripMeta(cached.text, song.name),
                    stripMeta(cached.trans, song.name),
                    stripMeta(cached.roma, song.name),
                )
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
            applyLyrics(
                stripMeta(text, song.name),
                stripMeta(trans, song.name),
                stripMeta(roma, song.name),
            )
            // 拉取成功时回写缓存（存原始文本，过滤逻辑在读取侧做，便于后续调整）
            if (text.isNotBlank()) {
                runCatching { ServiceLocator.lyricsCache.put(song.mid, text, trans, roma) }
            }
        }
    }

    /**
     * 过滤歌曲开头的制作名单/标题行/版权声明行。
     * 这类行时间戳密度极高（0.3~0.6s 一行），参与卡拉OK高亮会造成
     * 「多行同时爆绿」的观感；且与正文歌词无关，直接从时间轴中剔除。
     */
    private fun stripMeta(lrc: String, songName: String): String {
        if (lrc.isBlank()) return lrc
        val kept = StringBuilder()
        lrc.lineSequence().forEach { line ->
            val content = line.substringAfterLast(']').trim()
            if (content.isNotEmpty() && isMetaLine(content, songName)) return@forEach
            kept.appendLine(line)
        }
        return kept.toString().trimEnd()
    }

    private val metaKeywords = listOf(
        "作词", "作曲", "填词", "谱曲", "编曲", "改编", "制作", "监制", "统筹",
        "策划", "出品", "发行", "企划", "宣传", "营销", "推广", "版权", "经纪",
        "录音", "混音", "母带", "和声", "和音", "配唱", "吉他", "贝司", "贝斯",
        "键盘", "弦乐", "管乐", "鼓", "琴", "箫", "笛", "唢呐", "二胡", "琵琶",
        "古筝", "扬琴", "提琴", "指挥", "乐团", "乐队", "合唱", "声乐", "人声",
        "演唱", "主唱", "伴唱", "说唱", "翻译", "音译", "鸣谢", "感谢", "题字",
        "美术", "设计", "视觉", "摄影", "造型", "服装", "化妆", "导演", "编剧",
        "原著", "艺人", "项目", "财务", "法务", "A&R",
    )

    private fun isMetaLine(content: String, songName: String): Boolean {
        val s = content.trim()
        if (s.isEmpty()) return false
        // 首行「歌名 (专辑) - 歌手」标题行（与页面顶部歌名重复）
        if (songName.isNotEmpty() && s.length <= 80 && s.contains(songName) && s.contains(" - ")) return true
        // 版权声明行
        if (s.contains("未经著作权人许可") || s.contains("不得翻唱")) return true
        // 英文署名行：Lyrics by / Composed by / Produced by ...
        val lower = s.lowercase()
        val enCredits = listOf(
            "lyrics by", "composed by", "produced by", "written by", "arranged by",
            "mixed by", "mastered by", "engineered by", "recorded by",
            "executive producer", "music by", "programmed by", "vocal director",
            "backing vocals", "background vocals",
        )
        if (enCredits.any { lower.contains(it) }) return true
        // 中文署名行：短前缀 + 冒号（词：/编曲：/录音棚：/音乐出品：…）。
        // 单字关键词要求整段前缀相等（避免「一句词：」误伤），多字关键词用包含匹配。
        val sep = s.indexOfFirst { it == '：' || it == ':' }
        if (sep in 1..12) {
            val prefix = s.substring(0, sep).trim()
            if (prefix.isNotEmpty()) {
                val hit = metaKeywords.any { kw ->
                    if (kw.length == 1) prefix == kw else prefix.contains(kw, ignoreCase = true)
                }
                if (hit) return true
            }
        }
        return false
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
