package com.animereader.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Prepares a captured manga region for OCR: upscales small text, drops colour/screentones
 * (grayscale) and boosts contrast so ML Kit reads faint, low-contrast lettering better.
 * Returns a NEW bitmap (caller owns it).
 */
object MangaImage {
    private const val TARGET_MIN = 1000f   // upscale so the short side reaches ~1000px
    private const val MAX_SCALE = 3f
    private const val CONTRAST = 1.6f

    fun prepare(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        if (w == 0 || h == 0) return src

        val scale = (TARGET_MIN / minOf(w, h)).coerceIn(1f, MAX_SCALE)
        val base = if (scale > 1.05f)
            Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
        else src

        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val t = (-(0.5f * CONTRAST) + 0.5f) * 255f
        val matrix = ColorMatrix().apply { setSaturation(0f) } // grayscale
        matrix.postConcat(ColorMatrix(floatArrayOf(
            CONTRAST, 0f, 0f, 0f, t,
            0f, CONTRAST, 0f, 0f, t,
            0f, 0f, CONTRAST, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(base, 0f, 0f, paint)

        if (base !== src) base.recycle()
        return out
    }
}
