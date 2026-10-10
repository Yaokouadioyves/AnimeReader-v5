package com.animereader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON (de)serialisation of the reading history — Android-free, JVM-unit-testable. */
object HistoryCodec {

    fun encode(entries: List<HistoryEntry>): String = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject().apply {
                put("time", e.time)
                put("original", e.original)
                if (e.translated != null) put("translated", e.translated)
                if (e.langTag != null) put("langTag", e.langTag)
                if (e.method != null) put("method", e.method.name)
                if (e.providerName != null) put("providerName", e.providerName)
            })
        }
    }.toString()

    fun decode(json: String): List<HistoryEntry> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            HistoryEntry(
                time = o.optString("time", ""),
                original = o.optString("original", ""),
                translated = if (o.has("translated")) o.optString("translated") else null,
                langTag = if (o.has("langTag")) o.optString("langTag") else null,
                method = if (o.has("method")) {
                    runCatching { TranslationMethod.valueOf(o.optString("method")) }.getOrNull()
                } else null,
                providerName = if (o.has("providerName")) o.optString("providerName") else null,
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * File-backed reading history: survives service/app restarts (the previous history lived
 * in a StateFlow and vanished with the process). Load once at app start, save on push.
 */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "reading_history.json")

    fun load(): List<HistoryEntry> =
        if (file.exists()) HistoryCodec.decode(runCatching { file.readText() }.getOrDefault("")) else emptyList()

    /** Atomic-ish write (temp file + rename) so a crash mid-write can't corrupt the history. */
    fun save(entries: List<HistoryEntry>) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(HistoryCodec.encode(entries))
            if (!tmp.renameTo(file)) {
                file.writeText(HistoryCodec.encode(entries))
                tmp.delete()
            }
        }
    }
}
