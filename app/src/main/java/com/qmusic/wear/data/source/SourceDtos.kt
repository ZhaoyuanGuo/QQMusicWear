package com.qmusic.wear.data.source

import com.qmusic.wear.data.api.MusicHallShelf
import com.qmusic.wear.data.api.ToplistItem
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.ResolvedUrl
import com.qmusic.wear.data.model.Singer
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.model.UserProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 音乐源插件返回值 DTO（与 source/qmusic_source.js 的输出契约一一对应）。
 * JS 侧负责协议与解析，Kotlin 侧只做类型化映射。
 */
internal object SourceDtos {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
}

@Serializable
internal data class SongDto(
    val songId: Long = 0L,
    val mid: String = "",
    val name: String = "",
    val singers: String = "",
    val albumName: String = "",
    val albumMid: String = "",
    val mediaMid: String = "",
    val intervalSec: Int = 0,
    val songType: Int = 0,
    val vip: Boolean = false,
    val cover300: String = "",
    val cover500: String = "",
) {
    fun toModel() = Song(
        songId = songId,
        mid = mid,
        name = name,
        singers = singers,
        albumName = albumName,
        albumMid = albumMid,
        mediaMid = mediaMid,
        intervalSec = intervalSec,
        songType = songType,
        vip = vip,
        cover300 = cover300,
        cover500 = cover500,
    )
}

@Serializable
internal data class PlaylistDto(
    val disstid: Long = 0L,
    val name: String = "",
    val picUrl: String = "",
    val songCount: Int = 0,
    val creatorNick: String = "",
) {
    fun toModel() = Playlist(disstid, name, picUrl, songCount, creatorNick)
}

@Serializable
internal data class SingerDto(
    val mid: String = "",
    val id: Long = 0L,
    val name: String = "",
) {
    fun toModel() = Singer(mid, id, name)
}

@Serializable
internal data class UserProfileDto(
    val musicid: Long = 0L,
    val nick: String = "",
    val avatarUrl: String = "",
    val encryptUin: String = "",
) {
    fun toModel() = UserProfile(musicid, nick, avatarUrl, encryptUin)
}

@Serializable
internal data class ToplistItemDto(
    val topId: Int = 0,
    val title: String = "",
    val picUrl: String = "",
    val updateInfo: String = "",
) {
    fun toModel() = ToplistItem(topId, title, picUrl, updateInfo)
}

@Serializable
internal data class PlaylistDtoRef(
    val title: String = "",
    @SerialName("playlists") val items: List<PlaylistDto> = emptyList(),
) {
    fun toModel() = MusicHallShelf(title, items.map { it.toModel() })
}

@Serializable
internal data class SearchResultDto(
    val songs: List<SongDto> = emptyList(),
    val singers: List<SingerDto> = emptyList(),
    val playlists: List<PlaylistDto> = emptyList(),
)

@Serializable
internal data class PlaylistDetailDto(
    val playlist: PlaylistDto? = null,
    val songs: List<SongDto> = emptyList(),
)

@Serializable
internal data class ResolvedUrlDto(
    val url: String = "",
    val ekey: String = "",
    val encrypted: Boolean = false,
    val prefix: String = "",
    val ext: String = "mp3",
) {
    fun toModel() = ResolvedUrl(url, ekey, encrypted, prefix, ext)
}

@Serializable
internal data class ResolveResultDto(
    val items: List<ResolvedUrlDto?> = emptyList(),
    val debug: String = "",
)

@Serializable
internal data class ArtistSongsDto(
    val songs: List<SongDto> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
internal data class AlbumDetailDto(
    val name: String = "",
    val coverUrl: String = "",
    val songs: List<SongDto> = emptyList(),
)
