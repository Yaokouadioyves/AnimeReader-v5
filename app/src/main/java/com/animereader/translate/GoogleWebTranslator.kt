package com.animereader.translate

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
        val q = URLEncoder.encode(text, "UTF-8")
        val url = URL(
            "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=$targetCode&dt=t&q=$q"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
            )
            connectTimeout = 8000; readTimeout = 12000
        }
        try {
            if (conn.responseCode != 200) throw IOException("Google web HTTP ${conn.responseCode}")
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            return GoogleWebJson.parse(raw) ?: throw IOException("Google web: réponse illisible")
        } finally {
            conn.disconnect()
        }
    }
}
