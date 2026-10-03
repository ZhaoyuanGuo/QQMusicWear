package com.qmusic.wear.data.model

/** 歌曲 */
data class Song(
    val songId: Long = 0L,
    val mid: String = "",
    val name: String = "",
    val singers: String = "",
    val albumName: String = "",
    val albumMid: String = "",
    val mediaMid: String = "",
    val intervalSec: Int = 0,
    val songType: Int = 0,
    /** 是否会员歌曲（pay.pay_play / pay.payplay 非 0），列表显示 VIP 角标 */
    val vip: Boolean = false,
    /** 300px 封面（列表用；由音乐源插件生成） */
    val cover300: String = "",
    /** 500px 封面（播放页/歌词页模糊背景用） */
    val cover500: String = "",
    /**
     * 该曲目实际拥有的音质（Quality 名称，升序，如 ["STANDARD","HIGH","LOSSLESS"]）。
     * 由音乐源插件按真实文件信息填充；为空表示源未提供，UI 降级为显示全部档位。
     */
    val qualities: List<String> = emptyList(),
)

/** 歌单 */
data class Playlist(
    val disstid: Long = 0L,
    val name: String = "",
    val picUrl: String = "",
    val songCount: Int = 0,
    val creatorNick: String = "",
)

/** 歌手 */
data class Singer(
    val mid: String = "",
    val id: Long = 0L,
    val name: String = "",
)

/** 专辑详情（专辑页） */
data class AlbumDetail(
    val name: String = "",
    val coverUrl: String = "",
    val songs: List<Song> = emptyList(),
)

/** 登录用户资料 */
data class UserProfile(
    val musicid: Long = 0L,
    val nick: String = "",
    val avatarUrl: String = "",
    /** 加密 uin（收藏歌单同步必需，来自资料接口 creator.encrypt_uin） */
    val encryptUin: String = "",
    /** 会员身份徽标（绿钻SVIP/概念版SVIP/黑胶SVIP 等，空=无会员身份），由音乐源插件判定 */
    val vipLabel: String = "",
)

/**
 * 首页推送大卡（homeFeed 契约）。
 * action 决定点击行为（宿主分发到页面/播放），songs 为该卡可直接播放的内容。
 */
data class HomeCard(
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val action: String = "",
    val targetId: String = "",
    val coverUrl: String = "",
    val songName: String = "",
    val singers: String = "",
    val colorStart: Long? = null,
    val colorEnd: Long? = null,
    val songs: List<Song> = emptyList(),
)

/** 聚合搜索结果 */
data class SearchResult(
    val songs: List<Song> = emptyList(),
    val singers: List<Singer> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

/** 解析后的播放地址 */
data class ResolvedUrl(
    val url: String = "",
    val ekey: String = "",
    val encrypted: Boolean = false,
    val prefix: String = "",
    /** 文件扩展名（由音乐源插件按前缀给出，下载落盘用） */
    val ext: String = "mp3",
)

/** 歌手专辑列表项（歌手页「专辑」分区） */
data class AlbumItem(
    val albumMid: String = "",
    val name: String = "",
    val picUrl: String = "",
    val publishTime: Long = 0L,
    val songCount: Int = 0,
)

/** 单条评论 */
data class SongComment(
    val userName: String = "",
    val avatarUrl: String = "",
    val content: String = "",
    val time: Long = 0L,
    val likedCount: Int = 0,
)

/** 歌曲评论分页结果 */
data class SongComments(
    val hotComments: List<SongComment> = emptyList(),
    val comments: List<SongComment> = emptyList(),
    val total: Int = 0,
    val more: Boolean = false,
)

/** 电台 / 播客节目 */
data class RadioStation(
    val id: Long = 0L,
    val name: String = "",
    val picUrl: String = "",
    val desc: String = "",
    val programCount: Int = 0,
    val djName: String = "",
)

/** 电台单期节目 */
data class DjProgram(
    val programId: Long = 0L,
    val name: String = "",
    val coverUrl: String = "",
    val durationSec: Int = 0,
    val radioId: Long = 0L,
    val radioName: String = "",
    /** 节目主音频（可直接播放） */
    val song: Song? = null,
)

/** 用户动态 */
data class UserEvent(
    val id: Long = 0L,
    val type: Int = 0,
    val userName: String = "",
    val avatarUrl: String = "",
    val content: String = "",
    val songName: String = "",
    val songs: List<Song> = emptyList(),
)

/** 关注的用户 */
data class FollowUser(
    val userId: Long = 0L,
    val nick: String = "",
    val avatarUrl: String = "",
    val signature: String = "",
)

/** 播放模式 */
enum class PlayMode(val label: String) {
    SEQUENTIAL("顺序播放"),
    REPEAT_ONE("单曲循环"),
    RANDOM("随机播放"),
}

/**
 * 音质（label 用于界面展示）。
 * 音质 -> 文件名前缀链由音乐源插件提供（SourceManager.prefixToQuality）。
 */
enum class Quality(val label: String) {
    STANDARD("标准"),
    HIGH("高品"),
    LOSSLESS("无损"),
    HI_RES("Hi-Res");
}
