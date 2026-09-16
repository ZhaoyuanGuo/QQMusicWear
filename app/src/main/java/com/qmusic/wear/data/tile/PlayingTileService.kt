package com.qmusic.wear.data.tile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.InlineImageResource
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 「正在播放」磁贴（One UI 8.0 Watch 风格）：
 * 深色圆角底 + 中央圆形封面 + 环形播放进度（品牌绿、圆头描边、12 点起步）
 * + 歌名（白）/ 歌手（灰）+ 播放时间。点击整块磁贴打开应用；每 60 秒自动刷新进度。
 */
class PlayingTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 最近一次构建时拿到的内联封面（ARGB_8888 原始像素）；磁贴渲染器按 key 取资源 */
    @Volatile
    private var inlineCover: ByteArray? = null

    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> {
        val future = SettableFuture.create<Tile>()
        scope.launch {
            val tile = runCatching { buildTile(requestParams) }
                .getOrElse { buildEmptyTile() }
            future.set(tile)
        }
        return future
    }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> {
        val future = SettableFuture.create<Resources>()
        val resources = Resources.Builder().setVersion(RESOURCES_VERSION)
            // 内置图标兜底（始终提供）
            .addIdToImageMapping(KEY_ICON, iconResource())
            // 内联封面（封面获取失败时同样映射到图标，避免渲染缺图）
            .addIdToImageMapping(KEY_COVER, inlineCover?.let(::inlineImageResource) ?: iconResource())
            .build()
        future.set(resources)
        return future
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 磁贴构建
    // ------------------------------------------------------------------

    private suspend fun buildTile(requestParams: TileRequest): Tile {
        val now = ServiceLocator.player.state.value
        val song = now.song ?: return buildEmptyTile()

        val progress = if (now.durationMs > 0) {
            (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f)
        } else 0f

        // One UI 8.0 Watch 配色：深蓝黑底 + 白主文字 + 灰次要文字 + 品牌绿进度
        inlineCover = runCatching {
            withTimeout(COVER_TIMEOUT_MS) { fetchCoverArgb(song.cover300, COVER_PX) }
        }.getOrNull()

        val tapModifier = Modifiers.Builder().setClickable(openAppAction()).build()

        // 中央圆形封面 + 环形进度（One UI Watch 播放器同款：圆头描边、12 点方向起步）
        val discArc = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(-90f))
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(360f))
                    .setThickness(dp(3f))
                    .setColor(argb(0xFF2A3038.toInt()))
                    .setStrokeCap(LayoutElementBuilders.STROKE_CAP_ROUND)
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees((360f * progress).coerceAtLeast(1f)))
                    .setThickness(dp(3f))
                    .setColor(argb(0xFF31C27C.toInt()))
                    .setStrokeCap(LayoutElementBuilders.STROKE_CAP_ROUND)
                    .build(),
            )
            .build()
        val disc = LayoutElementBuilders.Box.Builder()
            .setModifiers(tapModifier)
            .setWidth(dp(118f))
            .setHeight(dp(118f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(discArc)
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(if (inlineCover != null) KEY_COVER else KEY_ICON)
                    .setModifiers(
                        Modifiers.Builder()
                            // 背景圆角同时会把图片裁成圆形（磁贴渲染器行为），兼作加载占位底色
                            .setBackground(
                                Background.Builder()
                                    .setColor(argb(0xFF1A2028.toInt()))
                                    .setCorner(Corner.Builder().setRadius(dp(52f)).build())
                                    .build(),
                            )
                            .build(),
                    )
                    .setWidth(dp(104f))
                    .setHeight(dp(104f))
                    .build(),
            )
            .build()

        // 歌名 / 歌手 / 时间
        val title = LayoutElementBuilders.Text.Builder()
            .setText(song.name.ifEmpty { "未知歌曲" })
            .setMaxLines(2)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(14f))
                    .setColor(argb(0xFFFFFFFF.toInt()))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                    .build(),
            )
            .build()
        val artist = LayoutElementBuilders.Text.Builder()
            .setText(song.singers.ifEmpty { "未知歌手" })
            .setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(11f))
                    .setColor(argb(0xFF9AA3AD.toInt()))
                    .build(),
            )
            .build()
        val time = LayoutElementBuilders.Text.Builder()
            .setText("${fmt(now.positionMs)} / ${fmt(now.durationMs)}")
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(10f))
                    .setColor(argb(0xFF9AA3AD.toInt()))
                    .build(),
            )
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setModifiers(
                Modifiers.Builder()
                    .setPadding(
                        Padding.Builder()
                            .setStart(dp(14f)).setEnd(dp(14f))
                            .setTop(dp(10f)).setBottom(dp(10f))
                            .build(),
                    )
                    .build(),
            )
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(disc)
            .addContent(spacer(10f))
            .addContent(title)
            .addContent(spacer(2f))
            .addContent(artist)
            .addContent(spacer(6f))
            .addContent(time)
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(openAppAction())
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(0xFF0E1116.toInt()))
                            .setCorner(Corner.Builder().setRadius(dp(18f)).build())
                            .build(),
                    )
                    .build(),
            )
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .build()

        return tileOf(root)
    }

    /** 无播放/兜底磁贴：品牌图标 + 提示文案 */
    private fun buildEmptyTile(): Tile {
        val icon = LayoutElementBuilders.Image.Builder()
            .setResourceId(KEY_ICON)
            .setWidth(dp(32f))
            .setHeight(dp(32f))
            .build()
        val text = LayoutElementBuilders.Text.Builder()
            .setText("暂无播放")
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(12f))
                    .setColor(argb(0xFF9AA3AD.toInt()))
                    .build(),
            )
            .build()
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(icon)
            .addContent(spacer(6f))
            .addContent(text)
            .build()
        val root = LayoutElementBuilders.Box.Builder()
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(openAppAction())
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(0xFF0E1116.toInt()))
                            .setCorner(Corner.Builder().setRadius(dp(18f)).build())
                            .build(),
                    )
                    .build(),
            )
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .build()
        return tileOf(root)
    }

    private fun tileOf(root: LayoutElementBuilders.LayoutElement): Tile =
        Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                Timeline.Builder()
                    .addTimelineEntry(
                        TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder().setRoot(root).build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .setFreshnessIntervalMillis(60_000)
            .build()

    private fun openAppAction(): Clickable =
        Clickable.Builder()
            .setId("open_player")
            .setOnClick(
                androidx.wear.protolayout.ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        androidx.wear.protolayout.ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName("com.qmusic.wear.MainActivity")
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun iconResource(): ImageResource =
        ImageResource.Builder()
            .setAndroidResourceByResId(
                AndroidImageResourceByResId.Builder()
                    .setResourceId(R.drawable.ic_notification)
                    .build(),
            )
            .build()

    /** ARGB_8888 原始像素 → 内联图片资源（渲染器仅支持原始像素格式） */
    private fun inlineImageResource(bytes: ByteArray): ImageResource =
        ImageResource.Builder()
            .setInlineResource(
                InlineImageResource.Builder()
                    .setData(bytes)
                    .setWidthPx(COVER_PX)
                    .setHeightPx(COVER_PX)
                    .setFormat(androidx.wear.protolayout.ResourceBuilders.IMAGE_FORMAT_ARGB_8888)
                    .build(),
            )
            .build()

    private fun spacer(heightDp: Float): LayoutElementBuilders.Spacer =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(heightDp)).build()

    /** 毫秒 → m:ss */
    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    // ------------------------------------------------------------------
    // 封面下载（缩放后导出 ARGB_8888 原始像素，供内联图片使用）
    // ------------------------------------------------------------------

    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private fun fetchCoverArgb(url: String, targetPx: Int): ByteArray? {
        if (url.isEmpty()) return null
        val net = http.newCall(
            okhttp3.Request.Builder()
                .url(url)
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/133")
                .build(),
        ).execute()
        val bytes = try {
            if (!net.isSuccessful) return null
            net.body?.bytes() ?: return null
        } finally {
            net.close()
        }

        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val argbSrc = if (src.config == Bitmap.Config.ARGB_8888) {
            src
        } else {
            val copy = src.copy(Bitmap.Config.ARGB_8888, false)
            src.recycle()
            copy
        }
        val scaled = Bitmap.createScaledBitmap(argbSrc, targetPx, targetPx, true)
        val buffer = ByteBuffer.allocate(targetPx * targetPx * 4).order(ByteOrder.LITTLE_ENDIAN)
        scaled.copyPixelsToBuffer(buffer)
        if (scaled !== argbSrc) scaled.recycle()
        argbSrc.recycle()
        return buffer.array()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val KEY_COVER = "cover"
        const val KEY_ICON = "icon"
        const val COVER_PX = 128
        const val COVER_TIMEOUT_MS = 3000L
    }
}
