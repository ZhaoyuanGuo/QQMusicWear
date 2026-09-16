package com.qmusic.wear.data.api

import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.data.model.ResolvedUrl
import com.qmusic.wear.data.model.SearchResult
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.model.UserProfile
import com.qmusic.wear.data.source.PlaylistDetailDto
import com.qmusic.wear.data.source.PlaylistDto
import com.qmusic.wear.data.source.PlaylistDtoRef
import com.qmusic.wear.data.source.ResolveResultDto
import com.qmusic.wear.data.source.SearchResultDto
import com.qmusic.wear.data.source.SongDto
import com.qmusic.wear.data.source.SourceDtos
import com.qmusic.wear.data.source.SourceManager
import com.qmusic.wear.data.source.ToplistItemDto
import com.qmusic.wear.data.source.UserProfileDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * QQ 音乐业务接口门面。
 *
 * 协议实现（端点、模块、登录链、解析）在可下载的音乐源插件里
 * （source/qmusic_source.js，Rhino 运行时），APK 不含任何协议明文。
 * 这里只做：参数打包 -> SourceManager 调用 -> DTO -> 领域模型。
 */
class QMusicApi(
    val credentialProvider: () -> Credential,
) {
    /** 通用 HTTP 客户端（下载管理等非协议用途） */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    internal suspend fun call(name: String, argsJson: String): String = SourceManager.call(name, argsJson)

    internal suspend fun callSongs(name: String, argsJson: String): List<Song> =
        SourceDtos.json
            .decodeFromString(ListSerializer(SongDto.serializer()), call(name, argsJson))
            .map { it.toModel() }

    internal suspend fun callPlaylists(name: String, argsJson: String): List<Playlist> =
        SourceDtos.json
            .decodeFromString(ListSerializer(PlaylistDto.serializer()), call(name, argsJson))
            .map { it.toModel() }

    internal fun args(vararg pairs: Pair<String, Any?>): String = buildJsonObject {
        pairs.forEach { (k, v) ->
            put(k, when (v) {
                null -> JsonNull
                is JsonPrimitive -> v
                is JsonObject -> v
                is JsonArray -> v
                is String -> JsonPrimitive(v)
                is Long -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is List<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) })
                else -> JsonPrimitive(v.toString())
            })
        }
    }.toString()

    /** 聚合搜索：歌曲 / 歌手 / 歌单 */
    suspend fun searchAll(query: String): SearchResult {
        val dto = SourceDtos.json.decodeFromString(
            SearchResultDto.serializer(),
            call("searchAll", args("query" to query)),
        )
        return SearchResult(
            songs = dto.songs.map { it.toModel() },
            singers = dto.singers.map { it.toModel() },
            playlists = dto.playlists.map { it.toModel() },
        )
    }

    /**
     * 批量解析播放地址，返回与输入等长的结果列表（失败项为 null）；
     * 第二个值为源侧诊断字符串（失败时随 Toast 展示）。
     */
    suspend fun resolveUrls(songs: List<Song>, quality: Quality): Pair<List<ResolvedUrl?>, String> {
        val songsJson = JsonArray(songs.map { s ->
            buildJsonObject {
                put("mid", s.mid)
                put("mediaMid", s.mediaMid)
                put("songId", s.songId)
                put("songType", s.songType)
            }
        })
        val dto = SourceDtos.json.decodeFromString(
            ResolveResultDto.serializer(),
            call("resolveUrls", args("quality" to quality.name, "songs" to songsJson)),
        )
        return dto.items.map { it?.toModel() } to dto.debug
    }
}

// -------------------------------------------------------------------------
// 业务接口（扩展函数保持既有签名，调用方无需改动）
// -------------------------------------------------------------------------

/** 猜你喜欢（私人电台，需登录态）；不足 15 首自动并入推荐新歌 */
suspend fun QMusicApi.guessRecommend(): List<Song> =
    callSongs("recommendSongs", "{}")

/** 推荐新歌（无需登录） */
suspend fun QMusicApi.recommendNewSongs(): List<Song> =
    callSongs("recommendNewSongs", "{}")

/** 歌单详情（我喜欢 / 收藏歌单共用） */
suspend fun QMusicApi.playlistDetail(
    disstid: Long,
    page: Int = 1,
    num: Int = 100,
): Pair<Playlist?, List<Song>> {
    val dto = SourceDtos.json.decodeFromString(
        PlaylistDetailDto.serializer(),
        call("playlistDetail", args("disstid" to disstid, "page" to page, "num" to num)),
    )
    return dto.playlist?.toModel() to dto.songs.map { it.toModel() }
}

/** 当前账号的创建歌单列表 */
suspend fun QMusicApi.myPlaylists(): List<Playlist> =
    callPlaylists("myPlaylists", "{}")

/** 收藏歌单（uin 传 encryptUin） */
suspend fun QMusicApi.favPlaylists(): List<Playlist> =
    callPlaylists("favPlaylists", "{}")

/** 当前登录用户资料（头像、昵称）；未登录返回 null */
suspend fun QMusicApi.userProfile(): UserProfile? {
    val raw = call("userProfile", "{}")
    if (raw.isEmpty() || raw == "null") return null
    return SourceDtos.json.decodeFromString(UserProfileDto.serializer(), raw).toModel()
}

/** 歌词（lrc；源侧含经典接口兜底） */
suspend fun QMusicApi.lyric(songMid: String, songId: Long): String =
    SourceDtos.json.decodeFromString(
        String.serializer(),
        call("lyric", args("mid" to songMid, "songId" to songId)),
    )

/** 歌词翻译（译文 LRC；无译文或源版本过旧时返回空串） */
suspend fun QMusicApi.lyricTrans(songMid: String, songId: Long): String =
    SourceDtos.json.decodeFromString(
        String.serializer(),
        call("lyricTrans", args("mid" to songMid, "songId" to songId)),
    )

/** 加入/移出「我喜欢」 */
suspend fun QMusicApi.setLike(songId: Long, like: Boolean): Boolean =
    SourceDtos.json.decodeFromString(
        Boolean.serializer(),
        call("setLike", args("songId" to songId, "like" to like)),
    )

/** 排行榜列表 */
suspend fun QMusicApi.toplists(): List<ToplistItem> =
    SourceDtos.json
        .decodeFromString(ListSerializer(ToplistItemDto.serializer()), call("toplists", "{}"))
        .map { it.toModel() }

/** 排行榜歌曲 */
suspend fun QMusicApi.toplistSongs(topId: Int): List<Song> =
    callSongs("toplistSongs", args("topId" to topId))

/** 歌单广场栏目 */
suspend fun QMusicApi.musicHallShelves(): List<MusicHallShelf> =
    SourceDtos.json
        .decodeFromString(ListSerializer(PlaylistDtoRef.serializer()), call("musicHallShelves", "{}"))
        .map { it.toModel() }

// -------------------------------------------------------------------------
// 领域类型
// -------------------------------------------------------------------------

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

/** 排行榜条目 */
data class ToplistItem(
    val topId: Int = 0,
    val title: String = "",
    val picUrl: String = "",
    val updateInfo: String = "",
)

/** 音乐厅首页栏目（官方移动端首页 feed 的一个卡片栏） */
data class MusicHallShelf(val title: String = "", val playlists: List<Playlist> = emptyList())
