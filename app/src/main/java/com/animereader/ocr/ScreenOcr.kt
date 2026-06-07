package com.animereader.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.animereader.internal.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device OCR of a captured manga region. Pre-processes the image (upscale + grayscale +
 * contrast), tries the Japanese recogniser first (manga is usually JA) then the Latin one,
 * and assembles lines with furigana/noise filtering and manga reading order.
 */
class ScreenOcr {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    suspend fun recognize(bitmap: Bitmap): String {
        val prepped = MangaImage.prepare(bitmap)
        val input = InputImage.fromBitmap(prepped, 0)
        return try {
            val ja = runCatching { japanese.process(input).await() }.getOrNull()
            val jaText = ja?.let { assemble(it) }.orEmpty()
            if (jaText.isNotBlank()) return jaText
            val lat = runCatching { latin.process(input).await() }.getOrNull()
            lat?.let { assemble(it) }.orEmpty()
        } finally {
            if (prepped !== bitmap) prepped.recycle()
        }
    }

    private class Line(val text: String, val box: Rect)

    /** Filter furigana/noise (lines far smaller than the median) and order for reading. */
    private fun assemble(t: Text): String {
        val lines = ArrayList<Line>()
        for (block in t.textBlocks) for (ln in block.lines) {
            val bb = ln.boundingBox ?: continue
            if (ln.text.isNotBlank()) lines.add(Line(ln.text, bb))
        }
        if (lines.isEmpty()) return ""

        val heights = lines.map { it.box.height() }.sorted()
        val median = heights[heights.size / 2].coerceAtLeast(1)
        // Drop tiny lines (furigana / stray marks) unless that would remove everything.
        val kept = lines.filter { it.box.height() >= median * 0.55 }.ifEmpty { lines }

        // Vertical Japanese if most lines are taller than wide.
        val vertical = kept.count { it.box.height() > it.box.width() } > kept.size / 2
        val ordered = if (vertical) {
            // columns right-to-left, then top-to-bottom within a column
            kept.sortedWith(compareByDescending<Line> { it.box.centerX() / median }.thenBy { it.box.centerY() })
        } else {
            // rows top-to-bottom, then left-to-right within a row
            kept.sortedWith(compareBy<Line> { it.box.centerY() / median }.thenBy { it.box.centerX() })
        }
        val sep = if (vertical) "" else " "
        return normalize(ordered.joinToString(sep) { it.text })
    }

    private fun normalize(s: String): String =
        s.replace("\n", " ").replace(Regex("\\s+"), " ").trim()

    fun close() {
        latin.close()
        japanese.close()
    }
}
