package com.animereader.internal

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the JSON the vision models (Gemini / Mistral) return for OCR+translation.
 * Tolerant of markdown code fences and of a bare array instead of {"bubbles":[…]}.
 * Kept free of android.* so it is JVM-unit-testable.
 */
object VisionJson {

    /** Strip the ```json … ``` fences some models wrap around JSON despite instructions. */
    fun unfence(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        }
        return s
    }

    /** {"original":…,"translation":…} → pair, or null when unparsable. */
    fun parseSingle(raw: String): Pair<String, String>? = runCatching {
        val o = JSONObject(unfence(raw))
        o.optString("original", "") to o.optString("translation", "")
    }.getOrNull()

    /** {"bubbles":[{"original":…,"translation":…},…]} or a bare array → ordered list. */
    fun parseBatch(raw: String): List<Pair<String, String>> = runCatching {
        val s = unfence(raw)
        val arr: JSONArray =
            if (s.startsWith("[")) JSONArray(s)
            else JSONObject(s).optJSONArray("bubbles") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("original", "") to o.optString("translation", "")
        }
    }.getOrDefault(emptyList())
}
