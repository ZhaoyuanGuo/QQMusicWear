package com.qmusic.wear.data.store

import com.qmusic.wear.data.api.int
import com.qmusic.wear.data.api.long
import com.qmusic.wear.data.api.str
import com.qmusic.wear.data.model.Song
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [Song] 的本地持久化 JSON 编解码（手写，避免给领域模型引入 @Serializable 污染）。
 *
 * 历史上 HistoryStore / QueueStore / LocalLikesStore / DownloadManager 各自实现一份，
 * 字段一多就漏（曾发生下载记录丢 cover300 导致重启后无封面）。此处统一为唯一实现，
 * 各 Store 复用，字段增删只需改一处。
 */
internal object SongJson {

    /** 编码：mid 为空视为无效，返回 null（调用方应跳过） */
    fun encode(song: Song): JsonObject? {
        if (song.mid.isEmpty()) return null
        return buildJsonObject {
            put("songId", song.songId)
            put("mid", song.mid)
            put("name", song.name)
            put("singers", song.singers)
            put("albumName", song.albumName)
            put("albumMid", song.albumMid)
            put("mediaMid", song.mediaMid)
            put("intervalSec", song.intervalSec)
            put("songType", song.songType)
            put("vip", song.vip)
            put("cover300", song.cover300)
            put("cover500", song.cover500)
        }
    }

    /** 解码：mid 缺失/为空返回 null */
    fun decode(obj: JsonObject): Song? {
        val mid = obj.str("mid")
        if (mid.isEmpty()) return null
        return Song(
            songId = obj.long("songId"),
            mid = mid,
            name = obj.str("name"),
            singers = obj.str("singers"),
            albumName = obj.str("albumName"),
            albumMid = obj.str("albumMid"),
            mediaMid = obj.str("mediaMid"),
            intervalSec = obj.int("intervalSec"),
            songType = obj.int("songType"),
            vip = (obj["vip"] as? JsonPrimitive)?.content == "true",
            cover300 = obj.str("cover300"),
            cover500 = obj.str("cover500"),
        )
    }
}
