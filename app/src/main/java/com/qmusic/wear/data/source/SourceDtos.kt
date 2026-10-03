package com.qmusic.wear.data.source

import com.qmusic.wear.data.api.MusicHallShelf
import com.qmusic.wear.data.api.ToplistItem
import com.qmusic.wear.data.model.AlbumItem
import com.qmusic.wear.data.model.DjProgram
import com.qmusic.wear.data.model.FollowUser
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.RadioStation
import com.qmusic.wear.data.model.ResolvedUrl
import com.qmusic.wear.data.model.Singer
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.model.SongComment
import com.qmusic.wear.data.model.SongComments
import com.qmusic.wear.data.model.UserEvent
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
    /** 该曲目实际拥有的音质（Quality 名称升序）；旧源不下发时为空 */
    val qualities: List<String> = emptyList(),
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
        qualities = qualities,
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
    val vipLabel: String = "",
) {
    fun toModel() = UserProfile(musicid, nick, avatarUrl, encryptUin, vipLabel)
}

@Serializable
internal data class ToplistItemDto(
    // 64 位：网易云多数榜单 id 超过 Int32 上限（如 18176153161），用 Int 会导致整表解码失败
    val topId: Long = 0L,
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

// ---- 新增可选能力（歌手专辑 / 评论 / 播客 / 动态 / 关注） ----

@Serializable
internal data class AlbumItemDto(
    val albumMid: String = "",
    val albumId: Long = 0L,
    val name: String = "",
    val picUrl: String = "",
    val publishTime: Long = 0L,
    val songCount: Int = 0,
) {
    fun toModel() = AlbumItem(albumMid, name, picUrl, publishTime, songCount)
}

@Serializable
internal data class ArtistAlbumsDto(
    val albums: List<AlbumItemDto> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
internal data class CommentDto(
    val userName: String = "",
    val avatarUrl: String = "",
    val content: String = "",
    val time: Long = 0L,
    val likedCount: Int = 0,
) {
    fun toModel() = SongComment(userName, avatarUrl, content, time, likedCount)
}

@Serializable
internal data class SongCommentsDto(
    val comments: List<CommentDto> = emptyList(),
    val hotComments: List<CommentDto> = emptyList(),
    val total: Int = 0,
    val more: Boolean = false,
) {
    fun toModel() = SongComments(
        hotComments = hotComments.map { it.toModel() },
        comments = comments.map { it.toModel() },
        total = total,
        more = more,
    )
}

@Serializable
internal data class RadioDto(
    val id: Long = 0L,
    val name: String = "",
    val picUrl: String = "",
    val desc: String = "",
    val programCount: Int = 0,
    val djName: String = "",
) {
    fun toModel() = RadioStation(id, name, picUrl, desc, programCount, djName)
}

@Serializable
internal data class DjProgramDto(
    val programId: Long = 0L,
    val name: String = "",
    val coverUrl: String = "",
    val durationSec: Int = 0,
    val radioId: Long = 0L,
    val radioName: String = "",
    val song: SongDto? = null,
) {
    fun toModel() = DjProgram(
        programId, name, coverUrl, durationSec, radioId, radioName, song?.toModel(),
    )
}

@Serializable
internal data class UserEventDto(
    val id: Long = 0L,
    val type: Int = 0,
    val userName: String = "",
    val avatarUrl: String = "",
    val content: String = "",
    val songName: String = "",
    val songs: List<SongDto> = emptyList(),
) {
    fun toModel() = UserEvent(
        id, type, userName, avatarUrl, content, songName, songs.map { it.toModel() },
    )
}

@Serializable
internal data class FollowUserDto(
    val userId: Long = 0L,
    val nick: String = "",
    val avatarUrl: String = "",
    val signature: String = "",
) {
    fun toModel() = FollowUser(userId, nick, avatarUrl, signature)
}

/**
 * 首页推送卡片（homeFeed 契约）：各音乐源可给出完全不同的首页大卡。
 * action 决定点击行为，由宿主分发到对应页面/播放；songs 为该卡的可直接播放内容。
 */
@Serializable
internal data class HomeCardDto(
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    /** songs|daily|rank|square|playlist|toplist|album|artist|recent|downloads|radio|podcast|events */
    val action: String = "",
    /** playlist/toplist/album/artist 的目标 id */
    val targetId: String = "",
    val coverUrl: String = "",
    val songName: String = "",
    val singers: String = "",
    /** 卡片渐变起止色（#RRGGBB，可选；缺省由宿主按序轮换） */
    val colorStart: String = "",
    val colorEnd: String = "",
    val songs: List<SongDto> = emptyList(),
)

@Serializable
internal data class HomeFeedDto(
    val cards: List<HomeCardDto> = emptyList(),
)
