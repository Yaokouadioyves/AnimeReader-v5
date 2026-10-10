package com.animereader.ocr

import android.graphics.Bitmap

/**
 * Prepares a captured scan region for OCR: small lettering is upscaled to a size the Latin
 * model reads well; colours are left untouched.
 */
object MangaImage {
    /**
     * Upscale only (Latin scans): small lettering is brought to a size the OCR reads well, the
     * colours are left untouched. Returns [src] itself when no upscale is needed.
     */
    fun upscale(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        if (w == 0 || h == 0) return src
        val scale = (LATIN_TARGET_MIN / minOf(w, h)).coerceIn(1f, LATIN_MAX_SCALE)
        return if (scale > 1.05f) Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
        else src
    }

    private const val LATIN_TARGET_MIN = 720f
    private const val LATIN_MAX_SCALE = 2f
}
