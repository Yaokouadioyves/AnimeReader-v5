package com.animereader.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.animereader.internal.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device OCR of the captured reading rectangle with the Latin model (French / English scans).
 * Returns text LINES for [BubbleClusters.clusterLines], positioned in ORIGINAL bitmap pixels
 * (the pre-processing upscale is mapped back).
 */
class ScreenOcr {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Plain upscale only: grayscale + forced contrast can merge coloured lettering into its
     * background and thin anti-aliased strokes; the Latin model is trained on natural images.
     */
    suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
        val prepped = MangaImage.upscale(bitmap)
        val scale = prepped.width.toFloat() / bitmap.width.coerceAtLeast(1)
        return try {
            val t = latin.process(InputImage.fromBitmap(prepped, 0)).await()
            t.textBlocks.flatMap { it.lines }.mapNotNull { piece(it.text, it.boundingBox, scale) }
        } finally {
            if (prepped !== bitmap) prepped.recycle()
        }
    }

    private fun piece(raw: String, bb: Rect?, scale: Float): OcrBlock? {
        bb ?: return null
        val s = if (scale <= 0f) 1f else scale
        val text = raw.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return null
        return OcrBlock(
            text,
            Box((bb.left / s).toInt(), (bb.top / s).toInt(), (bb.right / s).toInt(), (bb.bottom / s).toInt()),
        )
    }

    fun close() {
        latin.close()
    }
}
