package com.animereader.internal

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Gemini API client (no extra dependencies — HttpURLConnection + org.json).
 * Streaming TTS: audio PCM chunks are delivered via [onPcm] as the SSE events arrive,
 * so playback can start before the whole clip is generated.
 */
object GeminiClient {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    const val TTS_MODEL = "gemini-2.5-flash-preview-tts"

    /** Output is signed 16-bit PCM, mono, 24 kHz. */
    const val TTS_SAMPLE_RATE = 24000

    /**
     * Streams speech for [text] in [voiceName]. Calls [onPcm] with raw PCM16 chunks.
     * [isActive] is polled so a newer utterance can cancel this one. Throws on HTTP error
     * so the caller can fall back to device TTS.
     */
    fun ttsStream(
        apiKey: String,
        text: String,
        voiceName: String,
        isActive: () -> Boolean,
        onPcm: (ByteArray) -> Unit,
    ) {
        val url = URL("$BASE/$TTS_MODEL:streamGenerateContent?alt=sse&key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15000
            readTimeout = 60000
        }
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", text)))))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig",
                        JSONObject().put("voiceName", voiceName))))
            })
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        if (conn.responseCode != 200) {
            conn.disconnect()
            throw IOException("Gemini TTS HTTP ${conn.responseCode}")
        }
        try {
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    if (!isActive()) break
                    val line = reader.readLine() ?: break
                    val l = line.trim()
                    if (!l.startsWith("data:")) continue
                    val json = l.removePrefix("data:").trim()
                    if (json.isEmpty() || json == "[DONE]") continue
                    runCatching {
                        val parts = JSONObject(json)
                            .getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val p = parts.getJSONObject(i)
                            if (p.has("inlineData")) {
                                onPcm(Base64.decode(p.getJSONObject("inlineData").getString("data"), Base64.DEFAULT))
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
