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
) {
    /** 300px 封面（列表用） */
    val cover300: String get() = coverUrl(300)

    /** 500px 封面（播放页/歌词页模糊背景用） */
    val cover500: String get() = coverUrl(500)

    private fun coverUrl(size: Int): String =
        if (albumMid.isEmpty()) ""
        else "https://y.gtimg.cn/music/photo_new/T002R${size}x${size}M000$albumMid.jpg?max_age=2592000"
}

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
)

/** 播放模式 */
enum class PlayMode(val label: String) {
    SEQUENTIAL("顺序播放"),
    REPEAT_ONE("单曲循环"),
    RANDOM("随机播放"),
}

/** 音质（label 用于界面展示，chain 为 vkey 解析文件名前缀链，按优先级排列） */
enum class Quality(val label: String) {
    STANDARD("标准"),
    HIGH("高品"),
    LOSSLESS("无损"),
    HI_RES("Hi-Res");

    /** 依次尝试的文件名前缀（第一个解析出 purl 的生效） */
    fun chain(): List<String> = when (this) {
        STANDARD -> listOf("M500", "C200")
        HIGH -> listOf("M800", "M500", "C400")
        LOSSLESS -> listOf("AI00", "F0M0", "M800")
        HI_RES -> listOf("AIM0", "O800", "O600", "F0M0", "AI00", "M800")
    }

    companion object {
        /** 由文件名前缀反查音质（用于播放页展示当前音质） */
        fun fromPrefix(prefix: String): Quality? = entries.firstOrNull { q -> q.chain().contains(prefix) }
    }
}
