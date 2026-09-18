package com.qmusic.wear.data.store

import android.content.Context
import com.qmusic.wear.data.api.int
import com.qmusic.wear.data.api.long
import com.qmusic.wear.data.api.str
import com.qmusic.wear.data.model.PlayMode
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 登录凭证持久化（SharedPreferences）。
 * 历史方案曾用 DataStore，这里保持零额外依赖且同步可读。
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("qmusic_credential", Context.MODE_PRIVATE)

    private val _credentialFlow = MutableStateFlow(read())
    val credentialFlow: StateFlow<com.qmusic.wear.data.api.Credential> = _credentialFlow.asStateFlow()

    private fun read(): com.qmusic.wear.data.api.Credential {
        if (!prefs.contains(KEY_MUSICID)) return com.qmusic.wear.data.api.Credential.EMPTY
        return com.qmusic.wear.data.api.Credential(
            musicid = prefs.getLong(KEY_MUSICID, 0L),
            musickey = prefs.getString(KEY_MUSICKEY, "").orEmpty(),
            strMusicid = prefs.getString(KEY_STR_MUSICID, "").orEmpty(),
            encryptUin = prefs.getString(KEY_ENCRYPT_UIN, "").orEmpty(),
            nick = prefs.getString(KEY_NICK, "").orEmpty(),
            avatarUrl = prefs.getString(KEY_AVATAR, "").orEmpty(),
            createTime = prefs.getLong(KEY_CREATE_TIME, 0L),
            keyExpiresIn = prefs.getLong(KEY_EXPIRES_IN, 0L),
        )
    }

    suspend fun save(cred: com.qmusic.wear.data.api.Credential) {
        prefs.edit()
            .putLong(KEY_MUSICID, cred.musicid)
            .putString(KEY_MUSICKEY, cred.musickey)
            .putString(KEY_STR_MUSICID, cred.strMusicid)
            .putString(KEY_ENCRYPT_UIN, cred.encryptUin)
            .putString(KEY_NICK, cred.nick)
            .putString(KEY_AVATAR, cred.avatarUrl)
            .putLong(KEY_CREATE_TIME, cred.createTime)
            .putLong(KEY_EXPIRES_IN, cred.keyExpiresIn)
            .apply()
        _credentialFlow.value = cred
    }

    suspend fun clear() {
        prefs.edit().clear().apply()
        _credentialFlow.value = com.qmusic.wear.data.api.Credential.EMPTY
    }

    private companion object {
        const val KEY_MUSICID = "musicid"
        const val KEY_MUSICKEY = "musickey"
        const val KEY_STR_MUSICID = "str_musicid"
        const val KEY_ENCRYPT_UIN = "encrypt_uin"
        const val KEY_NICK = "nick"
        const val KEY_AVATAR = "avatar_url"
        const val KEY_CREATE_TIME = "create_time"
        const val KEY_EXPIRES_IN = "key_expires_in"
    }
}

/** 设置持久化：播放模式 / 默认播放音质 / 默认下载音质 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE)

    private val _playModeFlow = MutableStateFlow(readPlayMode())
    val playModeFlow: StateFlow<PlayMode> = _playModeFlow.asStateFlow()

    /** 开屏提示（启动 Toast「仅供学习交流使用」）开关 */
    private val _launchToastFlow = MutableStateFlow(prefs.getBoolean(KEY_LAUNCH_TOAST, true))
    val launchToastFlow: StateFlow<Boolean> = _launchToastFlow.asStateFlow()

    fun setLaunchToast(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LAUNCH_TOAST, enabled).apply()
        _launchToastFlow.value = enabled
    }

    /** 播放页屏幕常亮（显示模式设置） */
    private val _keepScreenOnFlow = MutableStateFlow(prefs.getBoolean(KEY_KEEP_SCREEN_ON, false))
    val keepScreenOnFlow: StateFlow<Boolean> = _keepScreenOnFlow.asStateFlow()

    fun setKeepScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
        _keepScreenOnFlow.value = enabled
    }

    /** 低配置设备模式：关闭模糊背景/封面旋转/光晕等重特效，削减页面转场，低端手表更流畅 */
    private val _lowPerfFlow = MutableStateFlow(prefs.getBoolean(KEY_LOW_PERF, false))
    val lowPerfFlow: StateFlow<Boolean> = _lowPerfFlow.asStateFlow()

    fun setLowPerf(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOW_PERF, enabled).apply()
        _lowPerfFlow.value = enabled
    }

    private fun readPlayMode(): PlayMode =
        runCatching { PlayMode.valueOf(prefs.getString(KEY_PLAY_MODE, PlayMode.SEQUENTIAL.name)!!) }
            .getOrDefault(PlayMode.SEQUENTIAL)

    private fun readQuality(key: String, fallback: Quality): Quality =
        runCatching { Quality.valueOf(prefs.getString(key, fallback.name)!!) }
            .getOrDefault(fallback)

    suspend fun setPlayMode(mode: PlayMode) {
        prefs.edit().putString(KEY_PLAY_MODE, mode.name).apply()
        _playModeFlow.value = mode
    }

    suspend fun setQuality(quality: Quality) {
        prefs.edit().putString(KEY_QUALITY, quality.name).apply()
    }

    suspend fun setDownloadQuality(quality: Quality) {
        prefs.edit().putString(KEY_DOWNLOAD_QUALITY, quality.name).apply()
    }

    suspend fun quality(): Quality = readQuality(KEY_QUALITY, Quality.STANDARD)

    /** 下载音质独立于播放音质（无损在手表上很占空间，可单独选低一档） */
    suspend fun downloadQuality(): Quality = readQuality(KEY_DOWNLOAD_QUALITY, Quality.HIGH)

    private companion object {
        const val KEY_PLAY_MODE = "play_mode"
        const val KEY_QUALITY = "quality"
        const val KEY_DOWNLOAD_QUALITY = "download_quality"
        const val KEY_LAUNCH_TOAST = "launch_toast"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_LOW_PERF = "low_perf"
    }
}

/**
 * 最近播放记录（本地持久化，按 mid 去重、最新在前，上限 [MAX_RECORDS] 条）。
 * 序列化手写 JSON，避免给领域模型引入 @Serializable 污染。
 */
class HistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("qmusic_history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _recentFlow = MutableStateFlow(read())
    val recentFlow: StateFlow<List<Song>> = _recentFlow.asStateFlow()

    private fun read(): List<Song> = runCatching {
        val text = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        json.parseToJsonElement(text).jsonArray
            .filterIsInstance<JsonObject>()
            .mapNotNull { obj ->
                val mid = obj.str("mid")
                if (mid.isEmpty()) return@mapNotNull null
                Song(
                    songId = obj.long("songId"),
                    mid = mid,
                    name = obj.str("name"),
                    singers = obj.str("singers"),
                    albumName = obj.str("albumName"),
                    albumMid = obj.str("albumMid"),
                    mediaMid = obj.str("mediaMid"),
                    intervalSec = obj.int("intervalSec"),
                    songType = obj.int("songType"),
                )
            }
    }.getOrDefault(emptyList())

    suspend fun record(song: Song) {
        if (song.mid.isEmpty()) return
        val current = _recentFlow.value.toMutableList()
        current.removeAll { it.mid == song.mid }
        current.add(0, song)
        val trimmed = current.take(MAX_RECORDS)
        _recentFlow.value = trimmed
        persist(trimmed)
    }

    suspend fun clear() {
        prefs.edit().remove(KEY_RECENT).apply()
        _recentFlow.value = emptyList()
    }

    private fun persist(songs: List<Song>) {
        val arr = buildJsonArray {
            songs.forEach { s ->
                add(buildJsonObject {
                    put("songId", s.songId)
                    put("mid", s.mid)
                    put("name", s.name)
                    put("singers", s.singers)
                    put("albumName", s.albumName)
                    put("albumMid", s.albumMid)
                    put("mediaMid", s.mediaMid)
                    put("intervalSec", s.intervalSec)
                    put("songType", s.songType)
                })
            }
        }
        prefs.edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    companion object {
        private const val KEY_RECENT = "recent_songs"
        private const val MAX_RECORDS = 100
    }
}

/** 搜索历史（本地持久化，最新在前，去重，上限 [MAX_HISTORY] 条） */
class SearchHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("qmusic_search", Context.MODE_PRIVATE)

    private val _historyFlow = MutableStateFlow(read())
    val historyFlow: StateFlow<List<String>> = _historyFlow.asStateFlow()

    private fun read(): List<String> =
        prefs.getString(KEY_HISTORY, null)
            ?.split("\u0001")
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val updated = (listOf(q) + _historyFlow.value.filterNot { it == q }).take(MAX_HISTORY)
        _historyFlow.value = updated
        prefs.edit().putString(KEY_HISTORY, updated.joinToString("\u0001")).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
        _historyFlow.value = emptyList()
    }

    companion object {
        private const val KEY_HISTORY = "search_history"
        private const val MAX_HISTORY = 10
    }
}

/**
 * 「我喜欢」本地红心（未登录时使用；登录后自动合并到云端，见 MusicRepository）。
 * 序列化与 [HistoryStore] 同风格：手写 JSON，避免给领域模型引入 @Serializable 污染。
 */
class LocalLikesStore(context: Context) {

    private val prefs = context.getSharedPreferences("qmusic_likes", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _songsFlow = MutableStateFlow(read())
    val songsFlow: StateFlow<List<Song>> = _songsFlow.asStateFlow()

    val songs: List<Song> get() = _songsFlow.value
    val mids: Set<String> get() = _songsFlow.value.map { it.mid }.toSet()

    private fun read(): List<Song> = runCatching {
        val text = prefs.getString(KEY_LIKES, null) ?: return emptyList()
        json.parseToJsonElement(text).jsonArray
            .filterIsInstance<JsonObject>()
            .mapNotNull { obj ->
                val mid = obj.str("mid")
                if (mid.isEmpty()) return@mapNotNull null
                Song(
                    songId = obj.long("songId"),
                    mid = mid,
                    name = obj.str("name"),
                    singers = obj.str("singers"),
                    albumName = obj.str("albumName"),
                    albumMid = obj.str("albumMid"),
                    mediaMid = obj.str("mediaMid"),
                    intervalSec = obj.int("intervalSec"),
                    songType = obj.int("songType"),
                )
            }
    }.getOrDefault(emptyList())

    fun add(song: Song) {
        if (song.mid.isEmpty() || _songsFlow.value.any { it.mid == song.mid }) return
        val updated = (listOf(song) + _songsFlow.value).take(MAX_LIKES)
        _songsFlow.value = updated
        persist(updated)
    }

    fun remove(mid: String) {
        if (_songsFlow.value.none { it.mid == mid }) return
        val updated = _songsFlow.value.filterNot { it.mid == mid }
        _songsFlow.value = updated
        persist(updated)
    }

    /** 批量移除（合并成功后清掉对应本地记录） */
    fun removeMids(mids: Collection<String>) {
        val set = mids.toSet()
        if (set.isEmpty()) return
        val updated = _songsFlow.value.filterNot { it.mid in set }
        if (updated.size == _songsFlow.value.size) return
        _songsFlow.value = updated
        persist(updated)
    }

    private fun persist(songs: List<Song>) {
        val arr = buildJsonArray {
            songs.forEach { s ->
                add(buildJsonObject {
                    put("songId", s.songId)
                    put("mid", s.mid)
                    put("name", s.name)
                    put("singers", s.singers)
                    put("albumName", s.albumName)
                    put("albumMid", s.albumMid)
                    put("mediaMid", s.mediaMid)
                    put("intervalSec", s.intervalSec)
                    put("songType", s.songType)
                })
            }
        }
        prefs.edit().putString(KEY_LIKES, arr.toString()).apply()
    }

    private companion object {
        const val KEY_LIKES = "liked_songs"
        const val MAX_LIKES = 200
    }
}

/** 本地播放统计的单条记录 */
data class PlayEvent(
    val mid: String,
    val name: String,
    val singers: String,
    /** 触发时间（毫秒） */
    val ts: Long,
    /** 歌曲时长（秒，来自曲目信息，作为已听时长的近似值） */
    val sec: Int,
)

/** 本周听歌统计快照 */
data class WeekStats(
    val totalSec: Int = 0,
    val playCount: Int = 0,
    /** 本周最常听的歌曲名（无记录为空串） */
    val topName: String = "",
    val topSingers: String = "",
    val topCount: Int = 0,
)

/**
 * 听歌统计（本地持久化，仅记录「切到某首歌」事件 + 曲目时长近似）。
 * 保留最近 30 天，供「我的」页展示本周时长与最常听歌曲。
 */
class PlayStatsStore(context: Context) {

    private val prefs = context.getSharedPreferences("qmusic_stats", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _events = MutableStateFlow(read())
    val events: StateFlow<List<PlayEvent>> = _events.asStateFlow()

    /** 最近 7 天的统计快照（事件变化时重算） */
    private val _weekStats = MutableStateFlow(computeWeek(_events.value))
    val weekStats: StateFlow<WeekStats> = _weekStats.asStateFlow()

    private fun read(): List<PlayEvent> = runCatching {
        val text = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        json.parseToJsonElement(text).jsonArray
            .filterIsInstance<JsonObject>()
            .mapNotNull { obj ->
                val mid = obj.str("mid")
                if (mid.isEmpty()) return@mapNotNull null
                PlayEvent(
                    mid = mid,
                    name = obj.str("name"),
                    singers = obj.str("singers"),
                    ts = obj.long("ts"),
                    sec = obj.int("sec"),
                )
            }
    }.getOrDefault(emptyList())

    /** 记录一次「切歌」事件（时长取歌曲自身时长作近似） */
    fun record(song: Song, atMs: Long = System.currentTimeMillis()) {
        if (song.mid.isEmpty()) return
        val ev = PlayEvent(
            mid = song.mid,
            name = song.name,
            singers = song.singers,
            ts = atMs,
            sec = song.intervalSec,
        )
        val cutoff = atMs - KEEP_MS
        val updated = (listOf(ev) + _events.value).filter { it.ts >= cutoff }.take(MAX_EVENTS)
        _events.value = updated
        _weekStats.value = computeWeek(updated)
        persist(updated)
    }

    fun clear() {
        prefs.edit().remove(KEY_EVENTS).apply()
        _events.value = emptyList()
        _weekStats.value = WeekStats()
    }

    private fun computeWeek(events: List<PlayEvent>): WeekStats {
        val cutoff = System.currentTimeMillis() - WEEK_MS
        val week = events.filter { it.ts >= cutoff }
        if (week.isEmpty()) return WeekStats()
        val top = week.groupBy { it.mid }
            .maxByOrNull { (_, list) -> list.size }
            ?.value
            ?.maxByOrNull { it.ts }
        val topCount = top?.let { t -> week.count { it.mid == t.mid } } ?: 0
        return WeekStats(
            totalSec = week.sumOf { it.sec },
            playCount = week.size,
            topName = top?.name.orEmpty(),
            topSingers = top?.singers.orEmpty(),
            topCount = topCount,
        )
    }

    private fun persist(events: List<PlayEvent>) {
        val arr = buildJsonArray {
            events.forEach { e ->
                add(buildJsonObject {
                    put("mid", e.mid)
                    put("name", e.name)
                    put("singers", e.singers)
                    put("ts", e.ts)
                    put("sec", e.sec)
                })
            }
        }
        prefs.edit().putString(KEY_EVENTS, arr.toString()).apply()
    }

    private companion object {
        const val KEY_EVENTS = "play_events"
        const val MAX_EVENTS = 2000
        const val KEEP_MS = 30L * 24 * 3600 * 1000
        const val WEEK_MS = 7L * 24 * 3600 * 1000
    }
}

/**
 * 用户协议：按版本号记录已同意状态。
 * 首启未同意时弹窗；协议内容修订后把 [AGREEMENT_VERSION] +1，老用户会重新收到弹窗。
 */
class AgreementStore(context: Context) {

    private val prefs = context.getSharedPreferences("qmusic_agreement", Context.MODE_PRIVATE)

    /** 是否已同意当前版本协议 */
    val isAgreed: Boolean
        get() = prefs.getInt(KEY_AGREED_VERSION, -1) >= AGREEMENT_VERSION

    fun setAgreed() {
        prefs.edit().putInt(KEY_AGREED_VERSION, AGREEMENT_VERSION).apply()
    }

    companion object {
        private const val KEY_AGREED_VERSION = "agreed_version"

        /** 协议内容变更时 +1，老用户将重新收到弹窗 */
        const val AGREEMENT_VERSION = 3
    }
}

/** 一首歌的歌词缓存包（原文/译文/罗马音） */
data class LyricBundle(
    val text: String,
    val trans: String,
    val roma: String,
)

/**
 * 歌词磁盘缓存：下载歌曲时预取落盘，离线也能看歌词（含译文/罗马音）。
 * 存储为 files/lyrics/<mid>.json；仅缓存原文非空的条目。
 */
class LyricsCache(context: Context) {

    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun get(mid: String): LyricBundle? {
        if (mid.isEmpty()) return null
        return runCatching {
            val f = File(dir, "$mid.json")
            if (!f.exists()) return null
            val obj = json.parseToJsonElement(f.readText()).jsonObject
            LyricBundle(
                text = obj.str("text"),
                trans = obj.str("trans"),
                roma = obj.str("roma"),
            )
        }.getOrNull()
    }

    fun put(mid: String, text: String, trans: String, roma: String) {
        if (mid.isEmpty() || text.isBlank()) return
        runCatching {
            val obj = buildJsonObject {
                put("text", text)
                put("trans", trans)
                put("roma", roma)
            }
            File(dir, "$mid.json").writeText(obj.toString())
        }
    }

    /** 缓存占用（字节），供设置页存储统计展示 */
    fun sizeBytes(): Long =
        runCatching { dir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }
}
