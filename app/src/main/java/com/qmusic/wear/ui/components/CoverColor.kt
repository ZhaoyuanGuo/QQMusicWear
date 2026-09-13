package com.qmusic.wear.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware

/**
 * 封面主色提取（借鉴官方手表版「播放器适配歌曲自动变色」思路）：
 * Coil 取小图（96px）→ Palette 提取主色，LruCache 缓存，供卡片流与播放页着色。
 */
object CoverColors {
    private val cache = LruCache<String, Int>(64)

    /** 取封面主色；加载或提取失败返回 null（调用方回退中性色） */
    suspend fun get(context: Context, url: String): Color? {
        if (url.isEmpty()) return null
        cache.get(url)?.let { return Color(it) }
        return runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(96, 96)
                .allowHardware(false) // Palette 需要可读像素
                .build()
            val result = context.imageLoader.execute(request)
            val bmp = (result.image as? BitmapImage)?.bitmap ?: return@runCatching null
            val rgb = dominantRgb(bmp) ?: return@runCatching null
            cache.put(url, rgb)
            Color(rgb)
        }.getOrNull()
    }

    private fun dominantRgb(bmp: Bitmap): Int? {
        val palette = Palette.from(bmp).clearFilters().generate()
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.lightMutedSwatch
            ?: palette.darkMutedSwatch
            ?: palette.dominantSwatch
        return swatch?.rgb
    }

    /** 提亮/压暗到适合深色 UI 的范围（官方风格：卡片底色鲜而不刺眼） */
    fun tune(color: Color, minLum: Float = 0.28f, maxLum: Float = 0.62f): Color {
        val lum = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
        val target = lum.coerceIn(minLum, maxLum)
        val factor = if (lum < 0.001f) 1f else target / lum
        return Color(
            red = (color.red * factor).coerceIn(0f, 1f),
            green = (color.green * factor).coerceIn(0f, 1f),
            blue = (color.blue * factor).coerceIn(0f, 1f),
            alpha = color.alpha,
        )
    }
}

/** 组合层：异步提取并 remember 封面主色（未就绪返回 null） */
@Composable
fun rememberCoverColor(url: String): Color? {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        color = CoverColors.get(context, url)
    }
    return color
}
