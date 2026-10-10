package com.animereader.translate

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One segment of a Google web-translate batch: the translation + the language Google detected. */
data class GoogleSegment(val text: String, val detected: String?)

/** Parsing of the Google web-translate reply — Android-free, JVM-unit-testable. */
object GoogleWebJson {
    /**
     * The gtx endpoint answers a nested array: [[["trad","orig",…],["trad2","orig2",…]],…].
     * Concatenate the first element of every segment; null/garbage → null.
     */
    fun parse(raw: String): String? = runCatching {
        val segments = JSONArray(raw).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONArray(i) ?: continue
            sb.append(seg.optString(0, ""))
        }
        sb.toString().trim().ifBlank { null }
    }.getOrNull()

    /**
     * The multi-text endpoint (translate_a/t, several q=) answers one entry per text, in order:
     * [["trad","en"],["trad2","fr"]] with sl=auto, or bare strings ["trad","trad2"] otherwise.
     * Returns exactly [expected] entries (null where an entry is missing or empty), or null
     * when the reply is unusable.
     */
    fun parseBatch(raw: String, expected: Int): List<GoogleSegment?>? = runCatching {
        val s = raw.trim()
        if (expected == 1 && s.startsWith("\"")) {
            return@runCatching listOf(JSONArray("[$s]").optString(0).takeIf { it.isNotBlank() }?.let { GoogleSegment(it, null) })
        }
        val arr = JSONArray(s)
        if (arr.length() != expected) return@runCatching null
        List(expected) { i ->
            val item = arr.optJSONArray(i)
            val text = (item?.optString(0, "") ?: arr.optString(i, "")).trim()
            val lang = item?.optString(1, "")?.takeIf { it.isNotBlank() }
            if (text.isBlank()) null else GoogleSegment(text, lang)
        }
    }.getOrNull()
}

/**
 * Keyless Google Translate via the public web endpoint (client=gtx) — the same trick the
 * "Screen Translate"-style apps use, and the translation quality they are known for: the
 * full cloud NMT models, not the small on-device ML Kit ones. Like edge-tts, this is an
 * unofficial endpoint: PERSONAL USE — for commercial, swap to the paid Cloud Translation API.
 */
class GoogleWebTranslator : TranslationProvider {

    override suspend fun translate(text: String, targetCode: String): String {
        if (text.isBlank()) return text
        return translateBatch(listOf(text), targetCode).first()?.text
            ?: throw IOException("Google web: réponse vide")
    }

    /**
     * Translates a whole page of bubbles in ONE request (POST, so a busy page never hits the
     * URL length limit). Each answer carries the language Google detected, which tells the
     * caller when a bubble was already in the target language.
     */
    fun translateBatch(texts: List<String>, targetCode: String): List<GoogleSegment?> {
        if (texts.isEmpty()) return emptyList()
        val url = URL("https://translate.googleapis.com/translate_a/t?client=gtx&sl=auto&tl=$targetCode")
        val body = texts.joinToString("&") { "q=" + URLEncoder.encode(it, "UTF-8") }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
            )
            connectTimeout = 8000; readTimeout = 12000
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                conn.errorStream?.use { it.readBytes() }
                throw IOException("Google web HTTP ${conn.responseCode}")
            }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            return GoogleWebJson.parseBatch(raw, texts.size) ?: throw IOException("Google web: réponse illisible")
        } finally {
            conn.disconnect()
        }
    }
}
