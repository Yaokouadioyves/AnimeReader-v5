package com.animereader.ocr

import android.graphics.Bitmap
import com.animereader.internal.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device OCR of a captured region. Tries the Japanese recogniser first (manga is
 * usually JA), then falls back to the Latin recogniser. Models download on first use.
 */
class ScreenOcr {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    suspend fun recognize(bitmap: Bitmap): String {
        val input = InputImage.fromBitmap(bitmap, 0)
        val ja = runCatching { japanese.process(input).await().text.trim() }.getOrDefault("")
        if (ja.isNotEmpty()) return normalize(ja)
        val lat = runCatching { latin.process(input).await().text.trim() }.getOrDefault("")
        return normalize(lat)
    }

    private fun normalize(s: String): String = s.replace("\n", " ").replace(Regex("\\s+"), " ").trim()

    fun close() {
        latin.close()
        japanese.close()
    }
}
