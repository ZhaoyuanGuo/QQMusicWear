package com.qmusic.wear.data.api

import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * QQ 音乐 Web 协议客户端。
 *
 * 协议参考：u.y.qq.com/cgi-bin/musicu.fcg（POST JSON, module/method RPC 风格）。
 * 请求公共参数（comm）按 Web 平台（ct=24 / cv=4747474 / yqq.json）构造。
 */
class QMusicApi(
    val credentialProvider: () -> Credential,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    val http: OkHttpClient get() = client

    // ---------------------------------------------------------------------
    // 基础设施
    // ---------------------------------------------------------------------

    private fun comm(): JsonObject = buildJsonObject {
        put("ct", 24)
        put("cv", 4747474)
        put("platform", "yqq.json")
        put("chid", 0)
        val cred = credentialProvider()
        if (cred.musicid != 0L) put("uin", cred.musicid)
        put("g_tk", com.qmusic.wear.util.QmUtils.gtk(cred.musickey).toInt())
        put("g_tk_new_20200303", com.qmusic.wear.util.QmUtils.gtk(cred.musickey).toInt())
        put("format", "json")
        put("inCharset", "utf-8")
        put("outCharset", "utf-8")
        put("notice", 0)
        put("needNewCode", 1)
    }

    /** 移动端 comm（音乐厅首页等移动专属模块要求 platform=android） */
    internal fun mobileComm(): JsonObject = buildJsonObject {
        put("ct", 19)
        put("cv", 190319)
        put("platform", "android")
        val cred = credentialProvider()
        if (cred.musicid != 0L) put("uin", cred.musicid)
        put("g_tk", com.qmusic.wear.util.QmUtils.gtk(cred.musickey).toInt())
        put("format", "json")
        put("inCharset", "utf-8")
        put("outCharset", "utf-8")
        put("notice", 0)
        put("needNewCode", 0)
    }

    private fun authCookies(): Map<String, String> {
        val cred = credentialProvider()
        if (cred.musicid == 0L || cred.musickey.isEmpty()) return emptyMap()
        val uin = cred.strMusicid.ifEmpty { cred.musicid.toString() }
        return mapOf(
            "uin" to uin,
            "qqmusic_uin" to uin,
            "qm_keyst" to cred.musickey,
            "qqmusic_key" to cred.musickey,
        )
    }

    /**
     * 发送一次 musicu.fcg RPC 调用，返回整个 JSON 响应。
     *
     * 2026-09 实测：POST 请求已被服务端大面积弃用（返回 500001），
     * GET + data= 参数（URL 编码 JSON）是当前稳定通道；个别时刻 GET 也
     * 间歇性 500001，因此采用「GET → GET 重试 → POST 兜底」的策略。
     */
    suspend fun musicu(
        vararg requests: Pair<String, CgiReq>,
        commOverride: JsonObject? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val root = buildJsonObject {
            put("comm", commOverride ?: comm())
            requests.forEach { (key, req) ->
                put(key, buildJsonObject {
                    put("module", req.module)
                    put("method", req.method)
                    put("param", req.param)
                })
            }
        }
        val payload = root.toString()
        val cookies = authCookies()
        val cookieHeader = if (cookies.isNotEmpty()) {
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } else null

        var lastResp: JsonObject? = null
        var lastError: Throwable? = null

        // 1) GET（data= URL 参数）尝试两次
        repeat(2) {
            if (lastResp?.long("code") == 0L) return@withContext lastResp!!
            try {
                lastResp = musicuGet(payload, cookieHeader)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        lastResp?.let { if (it.long("code") == 0L) return@withContext it }

        // 2) POST 兜底（部分老模块仍只认 POST）
        try {
            val resp = musicuPost(payload, cookieHeader)
            lastResp = resp
            if (resp.long("code") == 0L) return@withContext resp
        } catch (t: Throwable) {
            lastError = t
        }

        // 返回最后的响应（非 0 code 由调用方宽松解析处理），彻底失败才抛异常
        lastResp ?: throw (lastError ?: IllegalStateException("musicu.fcg 请求失败"))
    }

    private fun musicuGet(payload: String, cookieHeader: String?): JsonObject {
        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg?data=" +
            java.net.URLEncoder.encode(payload, "UTF-8")
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", WEB_UA)
            .header("Referer", "https://y.qq.com/")
        cookieHeader?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && body.isEmpty()) error("musicu.fcg HTTP ${resp.code}")
            return json.parseToJsonElement(body).jsonObject
        }
    }

    private fun musicuPost(payload: String, cookieHeader: String?): JsonObject {
        val builder = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", WEB_UA)
            .header("Referer", "https://y.qq.com/")
        cookieHeader?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && body.isEmpty()) error("musicu.fcg HTTP ${resp.code}")
            return json.parseToJsonElement(body).jsonObject
        }
    }

    suspend fun cgi(key: String, req: CgiReq): JsonObject = musicu(key to req)

    /** 直接 GET 请求（带登录 cookie），返回文本。 */
    suspend fun getText(url: String, extraHeaders: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("User-Agent", WEB_UA)
            val cookies = authCookies()
            if (cookies.isNotEmpty()) {
                builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                resp.body?.string().orEmpty()
            }
        }

    /** 不带任何登录态的裸 GET（用于登录流程第一步）。 */
    suspend fun getRaw(url: String, headers: Map<String, String> = emptyMap()): okhttp3.Response =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("User-Agent", WEB_UA)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute()
        }

    companion object {
        const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    }
}

/** musicu.fcg 的一个 module/method 子请求 */
data class CgiReq(
    val module: String,
    val method: String,
    val param: kotlinx.serialization.json.JsonObject,
)

/** 登录凭证（对应协议里的 musicid / musickey / encryptUin） */
data class Credential(
    val musicid: Long = 0L,
    val musickey: String = "",
    val strMusicid: String = "",
    val encryptUin: String = "",
    val nick: String = "",
    val avatarUrl: String = "",
    val createTime: Long = 0L,
    val keyExpiresIn: Long = 0L,
) {
    val isLogged: Boolean get() = musicid != 0L && musickey.isNotEmpty()
    val isExpired: Boolean get() = createTime > 0 && keyExpiresIn > 0 &&
        System.currentTimeMillis() / 1000 >= createTime + keyExpiresIn

    companion object {
        val EMPTY = Credential()
    }
}

// -------------------------------------------------------------------------
// 业务接口封装
// -------------------------------------------------------------------------

/**
 * 猜你喜欢（私人电台）。需要登录态。
 *
 * 2026-09 实测：返回结构迁移到 data.tracks（旧结构 data.track_info），
 * 且新结构常只返回 5 首——不足时并入「推荐新歌」补足。
 */
suspend fun QMusicApi.guessRecommend(): List<Song> {
    val resp = cgi("req_1", CgiReq("music.radioProxy.MbTrackRadioSvr", "get_radio_track", buildJsonObject {
        put("id", 99)
        put("num", 15)
        put("from", 0)
        put("scene", 0)
        put("song_ids", buildJsonArray())
    }))
    val data = resp.getObj("req_1")?.getObj("data")
    val tracks = data?.getArr("tracks") ?: data?.getArr("track_info")
    val songs = tracks?.filterIsInstance<JsonObject>()?.mapNotNull { t ->
        // 新结构可能包一层 songInfo / song_info，也可能直接是歌曲对象
        Parsers.parseSong(t.getObj("songInfo") ?: t.getObj("song_info") ?: t)
    }.orEmpty()
    if (songs.size >= 15) return songs
    val extra = runCatching { recommendNewSongs() }.getOrDefault(emptyList())
    return (songs + extra).distinctBy { it.mid }.take(30)
}

/** 推荐新歌（无需登录） */
suspend fun QMusicApi.recommendNewSongs(): List<Song> {
    val resp = cgi("req_1", CgiReq("newsong.NewSongServer", "get_new_song_info", buildJsonObject {
        put("type", 5)
    }))
    return Parsers.parseSongsLoose(resp, listOf("new_song", "song_list", "list", "data"))
}

/**
 * 批量按 mid 查歌曲详情（media_mid / songtype / songid）。
 * 歌单（CgiGetDiss）与电台新结构返回的歌曲常缺 file 字段，
 * vkey 解析文件名需要 media_mid，这里一次批量补全（上限 100）。
 */
suspend fun QMusicApi.songInfoBatch(mids: List<String>): Map<String, Song> {
    if (mids.isEmpty()) return emptyMap()
    val resp = cgi("req_1", CgiReq("music.musichallSong.SongInfoInter", "GetSongInfo", buildJsonObject {
        put("song_mids", buildJsonArray(mids.take(100).map { jStr(it) }))
    }))
    val arr = resp.getObj("req_1")?.getObj("data")?.getArr("track_info") ?: return emptyMap()
    return arr.filterIsInstance<JsonObject>()
        .mapNotNull { Parsers.parseSong(it) }
        .associateBy { it.mid }
}

/** 歌单详情（我喜欢 / 收藏歌单共用） */
suspend fun QMusicApi.playlistDetail(disstid: Long, page: Int = 1, num: Int = 100): Pair<Playlist?, List<Song>> {
    val resp = cgi("req_1", CgiReq("music.srfDissInfo.DissInfo", "CgiGetDiss", buildJsonObject {
        put("disstid", disstid)
        put("dirid", 0)
        put("tag", true)
        put("song_begin", num * (page - 1))
        put("song_num", num)
        put("userinfo", true)
        put("orderlist", true)
        put("onlysonglist", false)
    }))
    return Parsers.parsePlaylistDetail(resp)
}

/** 当前账号的创建/收藏歌单列表 */
suspend fun QMusicApi.myPlaylists(): List<Playlist> {
    val cred = credentialProvider()
    val resp = cgi("req_1", CgiReq("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", buildJsonObject {
        put("uin", cred.strMusicid.ifEmpty { cred.musicid.toString() })
    }))
    return Parsers.parsePlaylistsLoose(resp)
}

/** 收藏歌单。uin 必须传 encryptUin（社区多项目一致验证），且需 offset/size 分页参数 */
suspend fun QMusicApi.favPlaylists(): List<Playlist> {
    val cred = credentialProvider()
    val euin = cred.encryptUin.ifEmpty {
        cred.strMusicid.ifEmpty { cred.musicid.toString() }
    }
    val resp = cgi("req_1", CgiReq("music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo", buildJsonObject {
        put("uin", euin)
        put("offset", 0)
        put("size", 100)
    }))
    return Parsers.parsePlaylistsLoose(resp)
}

/** 歌词（lrc） */
suspend fun QMusicApi.lyric(songMid: String, songId: Long): String {
    val resp = cgi("req_1", CgiReq("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", buildJsonObject {
        put("crypt", 1)
        put("lrc_t", 0)
        put("qrc", 0)
        put("qrc_t", 0)
        put("roma", 0)
        put("roma_t", 0)
        put("trans", 0)
        put("trans_t", 0)
        put("needSingingAnnotations", false)
        put("type", 1)
        if (songId > 0) put("songId", songId) else put("songMid", songMid)
    }))
    return Parsers.extractLyric(resp)
}

/** 当前登录用户资料（头像、昵称） */
suspend fun QMusicApi.userProfile(): UserProfile? {
    val resp = getText(
        "https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg" +
            "?g_tk=${com.qmusic.wear.util.QmUtils.gtk(credentialProvider().musickey)}" +
            "&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&cid=205360838" +
            "&needNewCode=0&loginUin=${credentialProvider().musicid}&hostUin=0" +
            "&userid=${credentialProvider().musicid}&reqfrom=1",
    )
    return Parsers.parseUserProfile(resp, credentialProvider())
}

/**
 * 加入/移出「我喜欢」（like.fcgi-bin/like，dirid=1）。
 * 返回 true 表示服务端确认成功（code==0）。
 */
suspend fun QMusicApi.setLike(songId: Long, like: Boolean): Boolean {
    if (songId <= 0) return false
    val cred = credentialProvider()
    if (!cred.isLogged) return false
    val url = "https://c.y.qq.com/like/fcgi-bin/like" +
        "?g_tk=${com.qmusic.wear.util.QmUtils.gtk(cred.musickey)}" +
        "&uin=${cred.musicid}" +
        "&format=json&inCharset=utf-8&outCharset=utf-8&notice=0" +
        "&platform=yqq.json&needNewCode=0" +
        "&songid=$songId&dirid=1" +
        (if (like) "" else "&del=1")
    return withContext(Dispatchers.IO) {
        runCatching {
            val text = getText(url, mapOf("Referer" to "https://y.qq.com/"))
            if (text.isBlank()) return@runCatching false
            // 顶层扩展函数不能访问类私有 json，这里自建实例
            val parsed = Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(text).jsonObject
            parsed.long("code") == 0L
        }.getOrDefault(false)
    }
}

// -------------------------------------------------------------------------
// 推荐内容：排行榜 / 歌单广场（v8 与 splcloud 经典接口，无需登录）
// -------------------------------------------------------------------------

/** 顶层扩展函数用的宽松 Json（类私有 json 不可见） */
private val plainJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 排行榜条目 */
data class ToplistItem(
    val topId: Int = 0,
    val title: String = "",
    val picUrl: String = "",
    val updateInfo: String = "",
)

/** 歌单广场分类标签（旧 splcloud 接口已废弃，保留类型供兼容） */
data class SquareTag(val id: Long = 0L, val name: String = "")

/** 音乐厅首页栏目（官方移动端首页 feed 的一个卡片栏） */
data class MusicHallShelf(val title: String = "", val playlists: List<Playlist> = emptyList())

/** 排行榜列表（官方 v8 接口）：data.topList[] = {id, topTitle, picUrl, updateInfo} */
suspend fun QMusicApi.toplists(): List<ToplistItem> = withContext(Dispatchers.IO) {
    runCatching {
        val body = getText(
            "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg?format=json&outCharset=utf-8",
            mapOf("Referer" to "https://y.qq.com/"),
        )
        val arr = plainJson.parseToJsonElement(body).jsonObject
            .getObj("data")?.getArr("topList") ?: return@runCatching emptyList()
        arr.filterIsInstance<JsonObject>().mapNotNull { o ->
            val id = o.int("id")
            if (id == 0) return@mapNotNull null
            ToplistItem(
                topId = id,
                title = o.str("topTitle").ifEmpty { o.str("name") },
                picUrl = o.str("picUrl").ifEmpty { o.str("picurl") },
                updateInfo = o.str("updateInfo"),
            )
        }
    }.getOrDefault(emptyList())
}

/** 排行榜歌曲（musicu 网页版模块，与每日推荐同通道）：data.songInfoList[] */
suspend fun QMusicApi.toplistSongs(topId: Int): List<Song> {
    val resp = cgi("req_1", CgiReq("musicToplist.ToplistInfoServer", "GetDetail", buildJsonObject {
        put("topId", topId)
        put("offset", 0)
        put("num", 100)
        put("period", "")
    }))
    return Parsers.parseSongsLoose(resp, listOf("songInfoList", "songList", "list"))
}

/** 歌单广场：官方移动端音乐厅首页 feed（GetHomePage），提取歌单卡（type=500）按栏目分组 */
suspend fun QMusicApi.musicHallShelves(): List<MusicHallShelf> {
    val resp = musicu(
        "req_1" to CgiReq("music.musicHall.MusicHallHomePage", "GetHomePage", buildJsonObject { }),
        commOverride = mobileComm(),
    )
    val shelves = resp.getObj("req_1")?.getObj("data")?.getArr("v_shelf")
        ?: return emptyList()
    val out = mutableListOf<MusicHallShelf>()
    val seen = HashSet<Long>()
    for (sh in shelves.filterIsInstance<JsonObject>()) {
        val niches = sh.getArr("v_niche") ?: continue
        var title = ""
        val pls = mutableListOf<Playlist>()
        for (niche in niches.filterIsInstance<JsonObject>()) {
            if (title.isEmpty()) {
                title = niche.str("title_content").ifEmpty { niche.str("title_template") }
            }
            val cards = niche.getArr("v_card") ?: continue
            for (c in cards.filterIsInstance<JsonObject>()) {
                // type=500 为歌单卡；id 为纯数字 disstid 才可复用歌单详情链路
                if (c.int("type") != 500) continue
                val id = c.str("id").toLongOrNull() ?: continue
                if (id <= 0 || !seen.add(id)) continue
                val name = c.str("title").trim()
                if (name.isEmpty()) continue
                pls.add(
                    Playlist(
                        disstid = id,
                        name = name,
                        picUrl = c.str("cover"),
                        songCount = c.int("cnt"),
                        creatorNick = "",
                    ),
                )
            }
        }
        if (pls.isNotEmpty()) out.add(MusicHallShelf(title.ifEmpty { "精选推荐" }, pls))
    }
    return out
}
