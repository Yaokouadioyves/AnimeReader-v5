package com.animereader.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.animereader.data.ScanScript
import com.animereader.internal.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device OCR of the captured reading rectangle, with the model chosen by the scans' script
 * (like the screen-translate apps do) instead of always trying Japanese first: the Japanese
 * model "reads" Latin lettering too, badly, so a French/English page never reached the Latin
 * model. Positions are returned in ORIGINAL bitmap pixels (the pre-processing upscale is
 * mapped back) for the bubble clustering.
 */
class ScreenOcr {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    /**
     * [ScanScript.LATIN] → text LINES (for [BubbleClusters.clusterLines]);
     * [ScanScript.JAPANESE] → text blocks (vertical columns, for [BubbleClusters.cluster]),
     * falling back to the Latin model when the Japanese one finds nothing.
     */
    suspend fun recognize(bitmap: Bitmap, script: ScanScript): List<OcrBlock> = when (script) {
        ScanScript.LATIN -> latinLines(bitmap)
        ScanScript.JAPANESE -> japaneseBlocks(bitmap)
    }

    /**
     * Latin scans: plain upscale only. Grayscale + forced contrast (kept for Japanese) can merge
     * coloured lettering into its background and thin anti-aliased strokes; the Latin model is
     * trained on natural images.
     */
    private suspend fun latinLines(bitmap: Bitmap): List<OcrBlock> {
        val prepped = MangaImage.upscale(bitmap)
        val scale = prepped.width.toFloat() / bitmap.width.coerceAtLeast(1)
        return try {
            val t = latin.process(InputImage.fromBitmap(prepped, 0)).await()
            t.textBlocks.flatMap { it.lines }.mapNotNull { piece(it.text, it.boundingBox, scale) }
        } finally {
            if (prepped !== bitmap) prepped.recycle()
        }
    }

    private suspend fun japaneseBlocks(bitmap: Bitmap): List<OcrBlock> {
        val prepped = MangaImage.prepare(bitmap)
        val scale = prepped.width.toFloat() / bitmap.width.coerceAtLeast(1)
        val input = InputImage.fromBitmap(prepped, 0)
        return try {
            val ja = runCatching { japanese.process(input).await() }.getOrNull()
            val jaBlocks = ja?.textBlocks.orEmpty().mapNotNull { piece(it.text, it.boundingBox, scale) }
            if (jaBlocks.isNotEmpty()) return jaBlocks
            val lat = runCatching { latin.process(input).await() }.getOrNull()
            lat?.textBlocks.orEmpty().mapNotNull { piece(it.text, it.boundingBox, scale) }
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
        japanese.close()
    }
}
