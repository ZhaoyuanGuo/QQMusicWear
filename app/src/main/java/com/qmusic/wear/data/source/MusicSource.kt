package com.qmusic.wear.data.source

/**
 * 音乐源描述（宿主侧静态注册表条目）。
 *
 * 真正的协议实现、品牌名与主题色由下载的 JS 源脚本 manifest 提供
 * （manifest.id / name / themeColor）；这里只保留「下载前」就要用到的
 * 兜底展示信息与脚本文件名（用于镜像拼接）。
 */
data class MusicSource(
    /** 源唯一 id（与 JS manifest.id 一致） */
    val id: String,
    /** 源脚本文件名（镜像基地址拼接用） */
    val fileName: String,
    /** 兜底展示名（manifest.name 缺失时使用） */
    val displayName: String,
    /** 兜底品牌色 ARGB（manifest.themeColor 缺失时使用） */
    val themeColor: Long,
    /** 门控/设置页的副标题说明 */
    val subtitle: String = "",
)

/**
 * 音乐源注册表：按展示顺序列出全部受支持的音乐源。
 *
 * 一次只加载一个源（不支持聚合搜索）；用户在门控页/设置页切换，
 * 切换后重新加载对应源脚本并切换登录态与主题色。
 */
object SourceRegistry {

    val sources: List<MusicSource> = listOf(
        MusicSource(
            id = "qmusic-web",
            fileName = "qmusic_source.js",
            displayName = "QQ音乐",
            themeColor = 0xFF31C27C,
            subtitle = "绿 · 官方曲库",
        ),
        MusicSource(
            id = "kugou-web",
            fileName = "kugou_source.js",
            displayName = "酷狗音乐",
            themeColor = 0xFF2BA3F0,
            subtitle = "蓝 · 蝰蛇音效",
        ),
        MusicSource(
            id = "netease-web",
            fileName = "netease_source.js",
            displayName = "网易云音乐",
            themeColor = 0xFFE23B2E,
            subtitle = "红 · 私人 FM",
        ),
        MusicSource(
            id = "fanqie-web",
            fileName = "fanqie_source.js",
            displayName = "番茄畅听",
            themeColor = 0xFFFF6A3D,
            subtitle = "橙 · 有声书",
        ),
    )

    val default: MusicSource = sources.first()

    fun byId(id: String): MusicSource = sources.firstOrNull { it.id == id } ?: default

    fun byFileName(fileName: String): MusicSource? = sources.firstOrNull { it.fileName == fileName }
}