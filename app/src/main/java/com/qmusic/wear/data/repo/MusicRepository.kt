package com.qmusic.wear.data.repo

import com.qmusic.wear.data.api.Credential
import com.qmusic.wear.data.api.MusicHallShelf
import com.qmusic.wear.data.api.QMusicApi
import com.qmusic.wear.data.api.ToplistItem
import com.qmusic.wear.data.api.favPlaylists
import com.qmusic.wear.data.api.guessRecommend
import com.qmusic.wear.data.api.lyric
import com.qmusic.wear.data.api.musicHallShelves
import com.qmusic.wear.data.api.myPlaylists
import com.qmusic.wear.data.api.playlistDetail
import com.qmusic.wear.data.api.recommendNewSongs
import com.qmusic.wear.data.api.setLike
import com.qmusic.wear.data.api.toplistSongs
import com.qmusic.wear.data.api.toplists
import com.qmusic.wear.data.api.userProfile
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.data.model.ResolvedUrl
import com.qmusic.wear.data.model.SearchResult
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放地址解析与各页面数据仓库（协议实现均在音乐源插件内） */
class MusicRepository(private val api: QMusicApi) {

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

    suspend fun lyricOf(song: Song): String =
        runCatching { api.lyric(song.mid, song.songId) }.getOrDefault("")

    suspend fun searchSongs(query: String): List<Song> =
        searchAll(query).songs

    /** 聚合搜索：歌曲 / 歌手 / 歌单 */
    suspend fun searchAll(query: String): SearchResult =
        runCatching { api.searchAll(query) }.getOrDefault(SearchResult())

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

    /** 批量解析播放地址（协议细节在源插件内） */
    suspend fun resolveUrls(songs: List<Song>, quality: Quality): List<ResolvedUrl?> {
        if (songs.isEmpty()) return emptyList()
        return try {
            val (items, debug) = api.resolveUrls(songs, quality)
            lastResolveDebug = debug
            items
        } catch (t: Throwable) {
            lastResolveDebug = "ERR ${t.message}"
            emptyList()
        }
    }

    /** 最近一次解析的诊断信息（失败时供 Toast 展示，便于现场排障） */
    @Volatile
    var lastResolveDebug: String = ""
        private set

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
}
