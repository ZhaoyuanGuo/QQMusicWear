package com.qmusic.wear.data.repo

import com.qmusic.wear.data.api.CgiReq
import com.qmusic.wear.data.api.Credential
import com.qmusic.wear.data.api.Parsers
import com.qmusic.wear.data.api.QMusicApi
import com.qmusic.wear.data.api.findArrayWithKeys
import com.qmusic.wear.data.api.favPlaylists
import com.qmusic.wear.data.api.getArr
import com.qmusic.wear.data.api.getObj
import com.qmusic.wear.data.api.guessRecommend
import com.qmusic.wear.data.api.lyric
import com.qmusic.wear.data.api.long
import com.qmusic.wear.data.api.myPlaylists
import com.qmusic.wear.data.api.musicHallShelves
import com.qmusic.wear.data.api.playlistDetail
import com.qmusic.wear.data.api.recommendNewSongs
import com.qmusic.wear.data.api.setLike
import com.qmusic.wear.data.api.songInfoBatch
import com.qmusic.wear.data.api.str
import com.qmusic.wear.data.api.toplistSongs
import com.qmusic.wear.data.api.toplists
import com.qmusic.wear.data.api.userProfile
import com.qmusic.wear.data.api.MusicHallShelf
import com.qmusic.wear.data.api.ToplistItem
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.data.model.ResolvedUrl
import com.qmusic.wear.data.model.SearchResult
import com.qmusic.wear.data.model.Singer
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 播放地址解析 + 各页面数据仓库 */
class MusicRepository(private val api: QMusicApi) {

    private var guid: String = com.qmusic.wear.util.QmUtils.guid()

    /** 由 ServiceLocator 注入，用于 vkey 请求的 uin */
    var uinProvider: (() -> Long)? = null

    /** 「我喜欢」歌曲 mid 集合（红心状态；上限 100 首，超出部分不显示红心） */
    private val _likedMids = MutableStateFlow<Set<String>>(emptySet())
    val likedMids: StateFlow<Set<String>> = _likedMids.asStateFlow()

    /** 首页推荐：已登录用「猜你喜欢」，未登录用「推荐新歌」 */
    suspend fun recommendSongs(credential: Credential): List<Song> = try {
        if (credential.isLogged) {
            api.guessRecommend().ifEmpty { api.recommendNewSongs() }
        } else {
            api.recommendNewSongs()
        }
    } catch (t: Throwable) {
        try {
            api.recommendNewSongs()
        } catch (t2: Throwable) {
            emptyList()
        }
    }

    suspend fun playlist(disstid: Long): Pair<Playlist?, List<Song>> = api.playlistDetail(disstid)

    // ---- 推荐内容（排行榜 / 歌单广场，无需登录） ----

    suspend fun toplists(): List<ToplistItem> = try {
        api.toplists()
    } catch (t: Throwable) {
        emptyList()
    }

    suspend fun toplistSongs(topId: Int): List<Song> = try {
        api.toplistSongs(topId)
    } catch (t: Throwable) {
        emptyList()
    }

    suspend fun musicHallShelves(): List<MusicHallShelf> = try {
        api.musicHallShelves()
    } catch (t: Throwable) {
        emptyList()
    }

    /** 歌单详情分页（每页 100 首，供大歌单加载更多） */
    suspend fun playlistPage(disstid: Long, page: Int): Pair<Playlist?, List<Song>> =
        api.playlistDetail(disstid, page)

    suspend fun myPlaylistsSafe(): List<Playlist> = try {
        api.myPlaylists()
    } catch (t: Throwable) {
        emptyList()
    }

    suspend fun favPlaylistsSafe(): List<Playlist> = try {
        api.favPlaylists()
    } catch (t: Throwable) {
        emptyList()
    }

    /** 我的歌单 = 创建（含我喜欢） + 收藏，按 id 去重 */
    suspend fun allMyPlaylists(): List<Playlist> {
        val created = myPlaylistsSafe()
        val favs = favPlaylistsSafe()
        return (created + favs).distinctBy { it.disstid }
    }

    suspend fun profile(credential: Credential): UserProfile? = try {
        api.userProfile()
    } catch (t: Throwable) {
        if (credential.isLogged) UserProfile(credential.musicid, credential.nick, credential.avatarUrl) else null
    }

    suspend fun lyricOf(song: Song): String {
        val primary = try {
            api.lyric(song.mid, song.songId)
        } catch (t: Throwable) {
            ""
        }
        if (primary.isNotBlank()) return primary
        // 兑底：经典歌词接口（游客可用）
        return try {
            val text = api.getText(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=${song.mid}&g_tk=5381&format=json&nobase64=1&outCharset=utf-8",
                mapOf("Referer" to "https://y.qq.com/"),
            )
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            Parsers.extractLyric(json.parseToJsonElement(text).jsonObject)
        } catch (t: Throwable) {
            ""
        }
    }

    suspend fun searchSongs(query: String): List<Song> =
        searchAll(query).songs

    private suspend fun legacySearch(query: String, searchType: Int): JsonObject {
        return api.musicu(
            "req_1" to CgiReq(
                "music.search.SearchCgiService",
                "DoSearchForQQMusicDesktop",
                buildJsonObject {
                    put("search_type", searchType)
                    put("query", query)
                    put("page_num", 1)
                    put("num_per_page", 15)
                },
            ),
        )
    }

    private suspend fun cpSearch(query: String, t: Int): JsonObject {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val text = api.getText(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
                "?ct=24&qqmusic_ver=1298&new_json=1&remoteplace=txt.yqq.all&t=$t&aggr=1&cr=1" +
                "&catZhida=1&p=1&n=15&w=$enc&g_tk=5381&loginUin=0&hostUin=0&format=json" +
                "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0",
        )
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        return json.parseToJsonElement(text).jsonObject
    }

    /** 聚合搜索：歌曲 / 歌手 / 歌单。musicu Desktop 接口为主，client_search_cp 兜底。 */
    suspend fun searchAll(query: String): SearchResult {
        // 主通道：DoSearchForQQMusicDesktop（musicu GET，2026-09 实测稳定且带 media_mid）
        val songs = try {
            Parsers.parseSongsLoose(
                legacySearch(query, 0),
                listOf("req_1.data.body.song.list", "body.song.list", "body"),
            ).ifEmpty {
                Parsers.parseSongsLoose(cpSearch(query, 0), listOf("data.song.list", "data", "body"))
            }
        } catch (t: Throwable) {
            try {
                Parsers.parseSongsLoose(cpSearch(query, 0), listOf("data.song.list", "data", "body"))
            } catch (t2: Throwable) {
                emptyList()
            }
        }
        val singers = try {
            Parsers.parseSingersLoose(legacySearch(query, 1)).ifEmpty {
                Parsers.parseSingersLoose(cpSearch(query, 1))
            }
        } catch (t: Throwable) {
            try {
                Parsers.parseSingersLoose(cpSearch(query, 1))
            } catch (t2: Throwable) {
                emptyList()
            }
        }
        val playlists = try {
            Parsers.parsePlaylistsLoose(legacySearch(query, 3)).ifEmpty {
                Parsers.parsePlaylistsLoose(cpSearch(query, 3))
            }
        } catch (t: Throwable) {
            try {
                Parsers.parsePlaylistsLoose(cpSearch(query, 3))
            } catch (t2: Throwable) {
                emptyList()
            }
        }
        return SearchResult(songs, singers, playlists)
    }

    private data class Entry(val songIndex: Int, val prefix: String, val filename: String)

    /**
     * 解析下载地址：按指定音质取第一个「非加密」格式（mflac/mgg 无法本地播放），
     * 依次降级到高品 / 标准，全部失败返回 null。
     */
    suspend fun resolveForDownload(song: Song, quality: Quality): ResolvedUrl? {
        val primary = runCatching { resolveUrls(listOf(song), quality).firstOrNull() }
            .getOrNull()?.takeIf { !it.encrypted }
        if (primary != null) return primary
        val hi = runCatching { resolveUrls(listOf(song), Quality.HIGH).firstOrNull() }
            .getOrNull()?.takeIf { !it.encrypted }
        if (hi != null) return hi
        return runCatching { resolveUrls(listOf(song), Quality.STANDARD).firstOrNull() }
            .getOrNull()?.takeIf { !it.encrypted }
    }

    /**
     * 批量解析播放地址。
     * 1) 缺 media_mid 的歌曲（歌单/电台来源）先批量补全详情；
     * 2) 每首歌按音质链构造文件名，分批请求（vkey 每批上限 100），
     *    每首歌命中第一个非空 purl 即认为成功。
     */
    suspend fun resolveUrls(songs: List<Song>, quality: Quality): List<ResolvedUrl?> {
        if (songs.isEmpty()) return emptyList()

        // 缺 media_mid 的歌曲先补全（若补全接口可用则批1覆盖更多音质）
        val missing = songs.filter { it.mediaMid.isEmpty() }.map { it.mid }.distinct()
        val list = if (missing.isNotEmpty()) {
            val info = runCatching { api.songInfoBatch(missing) }.getOrDefault(emptyMap())
            songs.map { s ->
                if (s.mediaMid.isNotEmpty()) s
                else info[s.mid]?.let { f ->
                    s.copy(
                        mediaMid = f.mediaMid,
                        songType = if (f.songType != 0) f.songType else s.songType,
                        songId = if (f.songId != 0L) f.songId else s.songId,
                    )
                } ?: s
            }
        } else songs

        val out = arrayOfNulls<ResolvedUrl>(list.size)

        // ---- 批1：有 media_mid → 按音质链构造官方文件名请求 ----
        val chain = quality.chain()
        val entries = mutableListOf<Entry>()
        list.forEachIndexed { idx, song ->
            if (song.mediaMid.isEmpty()) return@forEachIndexed
            chain.forEach { prefix ->
                entries.add(Entry(idx, prefix, "$prefix${song.mediaMid}${extOf(prefix)}"))
            }
        }
        entries.chunked(100).forEach { chunk ->
            resolveChunk(list, chunk).forEach { (idx, url) -> out[idx] = url }
        }

        // ---- 批2：缺 media_mid → 不传 filename，服务器自动按标准音质下发 ----
        val pending = list.withIndex().filter { out[it.index] == null && it.value.mid.isNotEmpty() }
        pending.chunked(100).forEach { group ->
            resolveAuto(group.map { it.value }).forEach { (songMid, url) ->
                val idx = list.indexOfFirst { it.mid == songMid }
                if (idx >= 0) out[idx] = url
            }
        }

        // 诊断：附上首个失败歌曲的 mid，便于确认解析的是不是正确的歌曲标识
        val firstFail = out.indexOfFirst { it == null }
        if (firstFail >= 0) {
            lastResolveDebug = "mid=${list[firstFail].mid} $lastResolveDebug"
        }
        return out.toList()
    }

    private fun extOf(prefix: String): String = when (prefix) {
        "F0M0", "AIM0" -> ".mflac"
        "AI00" -> ".flac"
        in listOf("O400", "O600", "O800", "O801", "O4M0", "O6M0", "O8M0", "O8M1") -> ".mgg"
        in listOf("C200", "C400", "C600") -> ".m4a"
        else -> ".mp3"
    }

    /** 最近一次解析的诊断信息（失败时供 Toast 展示，便于现场排障） */
    @Volatile
    var lastResolveDebug: String = ""
        private set

    private suspend fun resolveChunk(songs: List<Song>, entries: List<Entry>): Map<Int, ResolvedUrl> {
        val resp = try {
            api.musicu(
                "req_1" to CgiReq(
                    // 经典模块：music.vkey.GetVkey 已对第三方客户端限流（恒 104009 invalidq）
                    "vkey.GetVkeyServer",
                    "CgiGetVkey",
                    buildJsonObject {
                        put("guid", guid)
                        put("filename", JsonArray(entries.map { JsonPrimitive(it.filename) }))
                        put("songmid", JsonArray(entries.map { JsonPrimitive(songs[it.songIndex].mid) }))
                        // songtype 统一 0：非 0 会被服务器按特殊曲库处理导致拒发（PC 实测）
                        put("songtype", JsonArray(entries.map { JsonPrimitive(0) }))
                        // param.uin 匿名：登录态经 cookie 鉴权，param.uin 与 cookie 不一致会 104009
                        put("uin", kotlinx.serialization.json.JsonPrimitive(""))
                        put("loginflag", 1)
                        put("platform", "20")
                    },
                ),
            )
        } catch (t: Throwable) {
            return emptyMap()
        }
        val dataObj = resp.getObj("req_1")?.getObj("data")
        val midurlinfo = dataObj?.get("midurlinfo") as? JsonArray ?: JsonArray(emptyList())

        // filename -> (purl, ekey)
        val map = mutableMapOf<String, Pair<String, String>>()
        midurlinfo.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val fn = obj.str("filename")
            val purl = obj.str("purl")
            val ekey = obj.str("ekey")
            if (fn.isNotEmpty()) map[fn] = purl to ekey
        }

        // 诊断：记录响应码与成功条数（失败时随 Toast 展示）
        runCatching {
            val okCount = midurlinfo.count { (it as? JsonObject)?.str("purl")?.isNotEmpty() == true }
            lastResolveDebug = "B1 c=${resp.long("code")}/${dataObj?.long("code")} ok=$okCount/${entries.size}"
        }

        val out = mutableMapOf<Int, ResolvedUrl>()
        entries.forEach { entry ->
            if (out.containsKey(entry.songIndex)) return@forEach
            val (purl, ekey) = map[entry.filename] ?: return@forEach
            if (purl.isEmpty()) return@forEach
            val full = if (purl.startsWith("http")) purl else "https://isure.stream.qqmusic.qq.com/$purl"
            val encrypted = entry.filename.endsWith(".mflac") || entry.filename.endsWith(".mgg")
            out[entry.songIndex] = ResolvedUrl(
                url = full,
                ekey = ekey,
                encrypted = encrypted,
                prefix = entry.prefix,
            )
        }
        return out
    }

    /**
     * 批2解析：缺 media_mid 的歌曲不传 filename，由服务器自动按标准音质（C400 m4a）下发。
     * 返回 songmid -> ResolvedUrl。
     */
    private suspend fun resolveAuto(songs: List<Song>): Map<String, ResolvedUrl> {
        if (songs.isEmpty()) return emptyMap()
        val resp = try {
            api.musicu(
                "req_1" to CgiReq(
                    "vkey.GetVkeyServer",
                    "CgiGetVkey",
                    buildJsonObject {
                        put("guid", guid)
                        put("songmid", JsonArray(songs.map { JsonPrimitive(it.mid) }))
                        // songtype 统一 0 + uin 匿名（PC 实测可下发的配置）
                        put("songtype", JsonArray(songs.map { JsonPrimitive(0) }))
                        put("uin", kotlinx.serialization.json.JsonPrimitive(""))
                        put("loginflag", 1)
                        put("platform", "20")
                    },
                ),
            )
        } catch (t: Throwable) {
            return emptyMap()
        }
        val dataObj = resp.getObj("req_1")?.getObj("data")
        val midurlinfo = dataObj?.get("midurlinfo") as? JsonArray ?: JsonArray(emptyList())

        runCatching {
            val okCount = midurlinfo.count { (it as? JsonObject)?.str("purl")?.isNotEmpty() == true }
            lastResolveDebug = "$lastResolveDebug | B2 c=${resp.long("code")}/${dataObj?.long("code")} ok=$okCount/${songs.size}"
        }

        val out = mutableMapOf<String, ResolvedUrl>()
        midurlinfo.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val songMid = obj.str("songmid")
            val purl = obj.str("purl")
            if (songMid.isEmpty() || purl.isEmpty()) return@forEach
            val full = if (purl.startsWith("http")) purl else "https://isure.stream.qqmusic.qq.com/$purl"
            out[songMid] = ResolvedUrl(url = full, prefix = "C400")
        }
        return out
    }

    fun songFrom(obj: JsonObject): Song? = Parsers.parseSong(obj)

    /**
     * 播放链路自检（设置页入口）：用当前登录态/网络跑三组探针，返回诊断文本。
     * T1 免费歌 B2（无 filename） / T2 免费歌 B1（双 mid 文件名） / T3 付费歌 B2（验证 cookie 授权）。
     */
    suspend fun selfTest(): String = buildString {
        appendLine("登录: ${if (api.credentialProvider().isLogged) "是" else "否（匿名）"}")
        // T1: 免费歌（Auld Lang Syne）B2 自动音质
        runCatching {
            val resp = api.musicu(
                "req_1" to CgiReq("vkey.GetVkeyServer", "CgiGetVkey", buildJsonObject {
                    put("guid", guid)
                    put("songmid", JsonArray(listOf(JsonPrimitive("002PBnMe45XM0W"))))
                    put("songtype", JsonArray(listOf(JsonPrimitive(0))))
                    put("uin", kotlinx.serialization.json.JsonPrimitive(""))
                    put("loginflag", 1)
                    put("platform", "20")
                }),
            )
            val info = resp.getObj("req_1")?.getObj("data")?.get("midurlinfo") as? JsonArray
            val o = (info?.firstOrNull() as? JsonObject)
            appendLine("T1免费B2: c=${resp.getObj("req_1")?.long("code")} result=${o?.long("result")} purl=${o?.str("purl")?.isNotEmpty() == true}")
        }.onFailure { appendLine("T1免费B2: 异常 ${it.message?.take(30)}") }
        // T2: 同歌 B1 双 mid 文件名
        runCatching {
            val resp = api.musicu(
                "req_1" to CgiReq("vkey.GetVkeyServer", "CgiGetVkey", buildJsonObject {
                    put("guid", guid)
                    put("songmid", JsonArray(listOf(JsonPrimitive("002PBnMe45XM0W"))))
                    put("songtype", JsonArray(listOf(JsonPrimitive(0))))
                    put("filename", JsonArray(listOf(JsonPrimitive("M500002PBnMe45XM0W002PBnMe45XM0W.mp3"))))
                    put("uin", kotlinx.serialization.json.JsonPrimitive(""))
                    put("loginflag", 1)
                    put("platform", "20")
                }),
            )
            val info = resp.getObj("req_1")?.getObj("data")?.get("midurlinfo") as? JsonArray
            val o = (info?.firstOrNull() as? JsonObject)
            appendLine("T2免费B1: c=${resp.getObj("req_1")?.long("code")} result=${o?.long("result")} purl=${o?.str("purl")?.isNotEmpty() == true}")
        }.onFailure { appendLine("T2免费B1: 异常 ${it.message?.take(30)}") }
        // T3: 付费歌（Come What May）B2 —— 验证绿钻 cookie 授权
        runCatching {
            val resp = api.musicu(
                "req_1" to CgiReq("vkey.GetVkeyServer", "CgiGetVkey", buildJsonObject {
                    put("guid", guid)
                    put("songmid", JsonArray(listOf(JsonPrimitive("00228bN72yDnTM"))))
                    put("songtype", JsonArray(listOf(JsonPrimitive(0))))
                    put("uin", kotlinx.serialization.json.JsonPrimitive(""))
                    put("loginflag", 1)
                    put("platform", "20")
                }),
            )
            val info = resp.getObj("req_1")?.getObj("data")?.get("midurlinfo") as? JsonArray
            val o = (info?.firstOrNull() as? JsonObject)
            appendLine("T3付费B2: c=${resp.getObj("req_1")?.long("code")} result=${o?.long("result")} purl=${o?.str("purl")?.isNotEmpty() == true}")
        }.onFailure { appendLine("T3付费B2: 异常 ${it.message?.take(30)}") }
    }.trim()

    /** 拉取「我喜欢」歌单曲目，更新红心集合（登录态才执行） */
    suspend fun refreshLikedMids() {
        if (!api.credentialProvider().isLogged) {
            _likedMids.value = emptySet()
            return
        }
        runCatching {
            val liked = allMyPlaylists().firstOrNull { it.name.contains("我喜欢") } ?: return
            val (_, songs) = playlist(liked.disstid)
            _likedMids.value = songs.map { it.mid }.toSet()
        }
    }

    /** 加入/移出「我喜欢」，成功后同步本地红心集合 */
    suspend fun setLiked(song: Song, like: Boolean): Boolean {
        val ok = api.setLike(song.songId, like)
        if (ok) {
            _likedMids.value = if (like) {
                _likedMids.value + song.mid
            } else {
                _likedMids.value - song.mid
            }
        }
        return ok
    }

    fun songsFrom(obj: JsonObject): List<Song> {
        val arr = findArrayWithKeys(
            obj,
            listOf(listOf("songmid", "songname"), listOf("mid", "name", "singer")),
        ) ?: return emptyList()
        return arr.filterIsInstance<JsonObject>().mapNotNull { Parsers.parseSong(it) }
    }
}
