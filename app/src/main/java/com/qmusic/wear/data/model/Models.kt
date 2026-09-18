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
