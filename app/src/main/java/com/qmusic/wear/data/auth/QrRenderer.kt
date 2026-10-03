package com.qmusic.wear.data.auth

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.io.ByteArrayOutputStream

/**
 * 二维码文本 -> PNG 字节。
 *
 * 部分音乐源（酷狗 / 网易云）的扫码登录接口只返回二维码**内容文本**，
 * 由宿主本地渲染成图（避免在 JS 源里内置二维码编码器）。
 */
internal object QrRenderer {

    fun render(text: String, sizePx: Int = 480): ByteArray? = runCatching {
        if (text.isEmpty()) return null
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        val black = Color.BLACK
        val white = Color.WHITE
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) black else white)
            }
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bos.toByteArray()
    }.getOrNull()
}