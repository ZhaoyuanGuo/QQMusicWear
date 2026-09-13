package com.qmusic.wear.data.api

import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.model.UserProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ------------------------- JSON 构建快捷方式 -------------------------

fun buildJsonArray(elements: List<JsonElement> = emptyList()): JsonArray =
    buildJsonArray { elements.forEach { add(it) } }

fun jsonObjectOf(vararg pairs: Pair<String, JsonElement>): JsonObject = jsonObjectOf(pairs.toList())

fun jsonObjectOf(pairs: List<Pair<String, JsonElement>>): JsonObject =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

fun jStr(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
fun jNum(v: Long): JsonElement = JsonPrimitive(v)
fun jNum(v: Int): JsonElement = JsonPrimitive(v)
fun jBool(v: Boolean): JsonElement = JsonPrimitive(v)

// ------------------------- JSON 读取快捷方式 -------------------------

fun JsonElement?.asStringOrNull(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

fun JsonElement?.asLongOrNull(default: Long = 0L): Long = when (this) {
    null, is JsonNull -> default
    is JsonPrimitive -> content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong() ?: default
    else -> default
}

fun JsonElement?.asIntOrNull(default: Int = 0): Int = asLongOrNull(default.toLong()).toInt()

fun JsonObject.getObj(key: String): JsonObject? = (this[key] as? JsonObject)

fun JsonObject.getArr(key: String): JsonArray? = (this[key] as? JsonArray)

fun JsonObject.str(key: String): String = this[key].asStringOrNull().orEmpty()

fun JsonObject.long(key: String): Long = this[key].asLongOrNull()

fun JsonObject.int(key: String): Int = this[key].asIntOrNull()

/**
 * 递归查找第一个「元素都是对象、且对象包含全部候选键之一组」的数组。
 * 反向工程的第三方接口字段命名不稳定，用宽松探测保证健壮性。
 */
fun findArrayWithKeys(root: JsonElement, requiredAnyOf: List<List<String>>): JsonArray? {
    when (root) {
        is JsonObject -> {
            for ((_, v) in root) {
                findArrayWithKeys(v, requiredAnyOf)?.let { return it }
            }
        }
        is JsonArray -> {
            val objs = root.filterIsInstance<JsonObject>()
            if (objs.isNotEmpty()) {
                val matches = objs.all { obj ->
                    requiredAnyOf.any { keys -> keys.all { k -> obj.containsKey(k) } }
                }
                if (matches) return root
            }
            for (e in root) {
                findArrayWithKeys(e, requiredAnyOf)?.let { return it }
            }
        }
        else -> Unit
    }
    return null
}

/** 递归查找第一个包含全部指定键的对象 */
fun findObjectWithKeys(root: JsonElement, keys: List<String>): JsonObject? {
    when (root) {
        is JsonObject -> {
            if (keys.all { root.containsKey(it) }) return root
            for ((_, v) in root) findObjectWithKeys(v, keys)?.let { return it }
        }
        is JsonArray -> {
            for (e in root) findObjectWithKeys(e, keys)?.let { return it }
        }
        else -> Unit
    }
    return null
}

// -------------------------------------------------------------------------
// 各接口响应 -> 领域模型（宽松解析）
// -------------------------------------------------------------------------

object Parsers {

    private val SONG_KEY_SETS = listOf(
        listOf("songmid", "songname"),
        listOf("mid", "name", "singer"),
        listOf("songmid", "name"),
        listOf("mid", "songname"),
    )

    fun parseSong(obj: JsonObject): Song? {
        val mid = obj.str("songmid").ifEmpty { obj.str("mid") }
        if (mid.isEmpty()) return null
        val name = obj.str("songname").ifEmpty { obj.str("name") }
        val singers = (obj.getArr("singer") ?: obj.getArr("singers"))
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { it.str("name").ifEmpty { it.str("title") }.ifEmpty { null } }
            ?.joinToString(" / ")
            .orEmpty()
        val albumMid = obj.str("albummid").ifEmpty { obj.getObj("album")?.str("mid").orEmpty() }
        val albumName = obj.str("albumname").ifEmpty { obj.getObj("album")?.str("name").orEmpty() }
        val songId = obj.long("songid").let { if (it == 0L) obj.long("id") else it }
        val interval = obj.int("interval")
        val mediaMid = obj.getObj("file")?.str("media_mid").orEmpty()
        val songType = obj.int("songtype").let { if (it != 0) it else obj.int("song_type") }
        val pay = obj.getObj("pay")
        val vip = (pay?.int("pay_play") ?: 0) != 0 || (pay?.int("payplay") ?: 0) != 0
        return Song(
            songId = songId,
            mid = mid,
            name = name,
            singers = singers,
            albumName = albumName,
            albumMid = albumMid,
            mediaMid = mediaMid,
            intervalSec = interval,
            songType = songType,
            vip = vip,
        )
    }

    /** 从整个响应中按候选字段名递归找歌曲数组 */
    fun parseSongsLoose(root: JsonObject, preferredKeys: List<String>): List<Song> {
        for (key in preferredKeys) {
            val node = locate(root, key) ?: continue
            val arr = when (node) {
                is JsonArray -> node
                is JsonObject -> node.getArr("list") ?: node.getArr("track_info")
                    ?: node.getArr("songlist") ?: node.getArr("songs")
                else -> null
            } ?: continue
            val songs = arr.filterIsInstance<JsonObject>().mapNotNull { parseSong(it) }
            if (songs.isNotEmpty()) return songs
        }
        // 兜底：全树扫描
        val arr = findArrayWithKeys(root, SONG_KEY_SETS) ?: return emptyList()
        return arr.filterIsInstance<JsonObject>().mapNotNull { parseSong(it) }
    }

    private fun locate(root: JsonElement, path: String): JsonElement? {
        var cur: JsonElement = root
        for (seg in path.split(".")) {
            cur = when (cur) {
                is JsonObject -> cur[seg] ?: return null
                else -> return null
            }
        }
        return cur
    }

    fun parsePlaylistDetail(resp: JsonObject): Pair<Playlist?, List<Song>> {
        // musicu 响应包在 req_1 下；兼容调用方直接传 data 的情况
        val data = resp.getObj("req_1")?.getObj("data")
            ?: resp.getObj("data")
            ?: resp
        val dirinfo = data.getObj("dirinfo") ?: data.getObj("dissinfo") ?: JsonObject(emptyMap())
        val disstid = data.long("disstid").let { if (it == 0L) dirinfo.long("disstid") else it }
        val playlist = Playlist(
            disstid = disstid,
            name = dirinfo.str("title").ifEmpty { dirinfo.str("dissname").ifEmpty { data.str("dissname") } },
            picUrl = dirinfo.str("picurl").ifEmpty { dirinfo.str("logo").ifEmpty { data.str("logo") } },
            songCount = data.int("songnum").let { if (it == 0) data.int("total") else it },
            creatorNick = dirinfo.str("creator").ifEmpty { dirinfo.getObj("creator")?.str("nick").orEmpty() },
        )
        val songs = data.getArr("songlist")
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { obj ->
                // CgiGetDiss 的歌曲包一层 JSON 字符串（或嵌套 json 对象）
                val inner = when (val el = obj["json"]) {
                    is JsonPrimitive -> runCatchingJson(el.content)
                    is JsonObject -> el
                    else -> null
                } ?: obj
                parseSong(inner)
            }
            .orEmpty()
        return playlist to songs
    }

    private fun runCatchingJson(text: String): JsonObject? = runCatching {
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            .parseToJsonElement(text).jsonObject
    }.getOrNull()

    fun parsePlaylistsLoose(root: JsonObject): List<Playlist> {
        val arr = findArrayWithKeys(
            root,
            listOf(
                listOf("disstid", "dissname"),
                listOf("disstid", "dirname"),
                listOf("dissid", "dissname"),
                // GetPlaylistByUin 返回 v_playlist（2026-09 实测：tid/dirName/songNum/picUrl）
                listOf("tid", "dirName"),
                listOf("id", "title", "picurl"),
                listOf("id", "title", "song_count"),
                // CgiGetPlaylistFavInfo 返回 v_list（tid/title）
                listOf("tid", "title"),
            ),
        ) ?: return emptyList()
        return arr.filterIsInstance<JsonObject>().mapNotNull { obj ->
            val disstid = obj.long("disstid").let { if (it == 0L) obj.long("dissid") else it }
                .let { if (it == 0L) obj.long("id") else it }
                .let { if (it == 0L) obj.long("tid") else it }
            if (disstid == 0L) return@mapNotNull null
            Playlist(
                disstid = disstid,
                name = obj.str("dissname")
                    .ifEmpty { obj.str("dirName") }
                    .ifEmpty { obj.str("diss_name") }
                    .ifEmpty { obj.str("dirname") }
                    .ifEmpty { obj.str("title").ifEmpty { obj.str("name") } },
                picUrl = obj.str("picUrl")
                    .ifEmpty { obj.str("picurl") }
                    .ifEmpty { obj.str("diss_cover") }
                    .ifEmpty { obj.str("logo").ifEmpty { obj.str("imgurl") } },
                songCount = obj.int("songNum")
                    .let { if (it == 0) obj.int("songnum") else it }
                    .let { if (it == 0) obj.int("song_count") else it },
                creatorNick = obj.str("nick").ifEmpty { obj.str("nickname").ifEmpty { obj.getObj("creator")?.str("nick").orEmpty() } }
                    .ifEmpty { obj.getObj("creator")?.str("name").orEmpty() },
            )
        }
    }

    /** 歌手搜索结果（宽松解析） */
    fun parseSingersLoose(root: JsonObject): List<com.qmusic.wear.data.model.Singer> {
        val arr = findArrayWithKeys(
            root,
            listOf(
                // DoSearchForQQMusicDesktop: singer.list[] = {singerID, singerMID, singerName}
                listOf("singerID", "singerName"),
                listOf("singermid", "singername"),
                listOf("singerid", "singername"),
                listOf("singermid", "name"),
            ),
        ) ?: return emptyList()
        return arr.filterIsInstance<JsonObject>().mapNotNull { obj ->
            val name = obj.str("singername").ifEmpty { obj.str("singerName") }
            if (name.isEmpty()) return@mapNotNull null
            com.qmusic.wear.data.model.Singer(
                mid = obj.str("singermid").ifEmpty { obj.str("singerMID") },
                id = obj.long("singerid").let { if (it == 0L) obj.long("singerID") else it },
                name = name,
            )
        }
    }

    fun extractLyric(resp: JsonObject): String {
        val data = resp.getObj("data") ?: resp
        val raw = data.str("lyric")
        if (raw.isEmpty()) return ""
        val decoded = runCatching { java.util.Base64.getDecoder().decode(raw).toString(Charsets.UTF_8) }
        return if (decoded.getOrNull()?.startsWith("[") == true || decoded.getOrNull()?.contains("[0") == true) {
            decoded.getOrDefault(raw)
        } else raw
    }

    fun parseUserProfile(text: String, cred: Credential): UserProfile? {
        if (!cred.isLogged) return null
        val root = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return UserProfile(cred.musicid, cred.nick, cred.avatarUrl)

        val creator = findObjectWithKeys(root, listOf("nick")) ?: findObjectWithKeys(root, listOf("nickname"))
        val nick = creator?.str("nick")
            ?.ifEmpty { creator.str("nickname") }
            ?.ifEmpty { creator.getObj("friend")?.str("nick").orEmpty() }
            ?.ifEmpty { cred.nick }
            .orEmpty()
        val avatar = creator?.let {
            it.str("avatar")
                .ifEmpty { it.str("headpic") }
                .ifEmpty { it.getObj("friend")?.str("avatar").orEmpty() }
        }.orEmpty().ifEmpty { cred.avatarUrl }
        // encryptUin：收藏歌单接口 CgiGetPlaylistFavInfo 必需
        val encryptUin = creator?.str("encrypt_uin")
            ?.ifEmpty { creator.str("encryptUin") }
            ?.ifEmpty { creator.str("encuin") }
            .orEmpty()
        return UserProfile(cred.musicid, nick, avatar, encryptUin)
    }
}
