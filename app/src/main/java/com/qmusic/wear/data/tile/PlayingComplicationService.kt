package com.qmusic.wear.data.tile

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.EmptyComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.qmusic.wear.R

/**
 * 「正在播放」表盘 Complication（SHORT_TEXT）：
 * 主文本=歌名（≤8 字截断），副文本=歌手。
 * 数据来自 PlaybackService 写入的 SharedPreferences（qmusic_complication），
 * 只做秒读，无网络、无 bindService，满足 Complication 快速返回的要求。
 */
class PlayingComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val prefs = getSharedPreferences(COMPLICATION_PREFS, MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, "").orEmpty()
        val singers = prefs.getString(KEY_SINGERS, "").orEmpty()

        // 无歌曲（尚未播放过/已被清空）：返回空数据，表盘显示默认占位
        if (name.isEmpty()) {
            listener.onComplicationData(EmptyComplicationData())
            return
        }

        listener.onComplicationData(
            ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(name.take(MAX_TEXT_LEN)).build(),
                contentDescription = PlainComplicationText.Builder(name).build(),
            )
                .setTitle(PlainComplicationText.Builder(singers).build())
                .build(),
        )
    }

    /** 表盘编辑器里的预览样式 */
    override fun getPreviewData(type: ComplicationType): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("正在播放").build(),
            contentDescription = PlainComplicationText.Builder(getString(R.string.complication_label)).build(),
        )
            .setTitle(PlainComplicationText.Builder("QQ音乐").build())
            .build()

    private companion object {
        const val COMPLICATION_PREFS = "qmusic_complication"
        const val KEY_NAME = "name"
        const val KEY_SINGERS = "singers"
        const val MAX_TEXT_LEN = 8
    }
}
