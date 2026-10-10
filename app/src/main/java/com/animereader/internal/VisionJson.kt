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

    /**
     * {"bubbles":[{"n":1,"original":…,"translation":…},…]} or a bare array → list in image order.
     * When every element carries a distinct image number "n" (1-based), answers are put back in
     * that order, so a model that reorders or skips one can't shift every following bubble.
     * Otherwise the reply order is kept; non-object entries become empty pairs (alignment kept).
     */
    fun parseBatch(raw: String): List<Pair<String, String>> = runCatching {
        val s = unfence(raw)
        val arr: JSONArray =
            if (s.startsWith("[")) JSONArray(s)
            else JSONObject(s).optJSONArray("bubbles") ?: JSONArray()
        val items = (0 until arr.length()).map { arr.optJSONObject(it) }
        val pairs = items.map { o -> (o?.optString("original", "") ?: "") to (o?.optString("translation", "") ?: "") }
        val numbers = items.map { it?.optInt("n", 0) ?: 0 }
        if (numbers.all { it in 1..items.size + 3 } && numbers.toSet().size == numbers.size) {
            val out = MutableList(numbers.max()) { "" to "" }
            numbers.forEachIndexed { i, n -> out[n - 1] = pairs[i] }
            out
        } else pairs
    }.getOrDefault(emptyList())
}
