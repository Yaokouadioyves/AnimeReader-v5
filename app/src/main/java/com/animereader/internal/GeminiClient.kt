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
    // Fastest free models measured: 3.1 TTS (~4s vs 5.7s) and flash-lite vision (~2.5s, higher rate limit).
    const val TTS_MODEL = "gemini-3.1-flash-tts-preview"
    const val VISION_MODEL = "gemini-2.5-flash-lite"

    /**
     * Manga OCR + translation in one vision call. Returns (original, translation) or null on error.
     * [imageJpeg] is the captured region; [targetLanguage] is a natural language name (e.g. "anglais").
     */
    fun ocrTranslate(apiKey: String, imageJpeg: ByteArray, targetLanguage: String, dialogueContext: String = ""): Pair<String, String>? {
        val prompt = contextBlock(dialogueContext) +
            "Tu es un moteur OCR de manga expert (japonais vertical, lettrage stylisé, furigana). " +
                "Lis uniquement le texte des bulles dans cette image, corrige les erreurs d'OCR, puis " +
                "traduis en $targetLanguage de manière naturelle et fidèle au ton. Réponds STRICTEMENT en JSON " +
                "{\"original\":\"<texte source>\",\"translation\":\"<traduction>\"}. Aucun texte lisible -> chaînes vides."
        return VisionJson.parseSingle(generateVision(apiKey, prompt, listOf(imageJpeg)))
    }

    /** One call for a whole page: reads+translates every bubble crop, in the given order. */
    fun ocrTranslateBatch(apiKey: String, imagesJpeg: List<ByteArray>, targetLanguage: String, dialogueContext: String = ""): List<Pair<String, String>> {
        if (imagesJpeg.isEmpty()) return emptyList()
        val prompt = contextBlock(dialogueContext) +
            "Voici ${imagesJpeg.size} images, chacune est une bulle de manga (lettrage stylisé possible). " +
                "Pour CHAQUE image, dans le même ordre : lis le texte, corrige les erreurs d'OCR, puis traduis en " +
                "$targetLanguage naturellement. Réponds STRICTEMENT en JSON " +
                "{\"bubbles\":[{\"original\":\"…\",\"translation\":\"…\"}, …]} avec exactement ${imagesJpeg.size} éléments. " +
                "Image illisible -> chaînes vides."
        return VisionJson.parseBatch(generateVision(apiKey, prompt, imagesJpeg))
    }

    /** The last dialogue lines, injected for coherent pronouns/tone across bubbles. */
    private fun contextBlock(dialogueContext: String): String =
        if (dialogueContext.isBlank()) ""
        else "Contexte — répliques précédentes du dialogue (original => traduction), pour garder " +
            "pronoms, ton et vocabulaire cohérents :\n$dialogueContext\n\n"

    /** POSTs a vision generateContent with N images + a prompt; returns the model text. */
    private fun generateVision(apiKey: String, prompt: String, imagesJpeg: List<ByteArray>): String {
        val url = URL("$BASE/$VISION_MODEL:generateContent?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15000; readTimeout = 45000
        }
        val parts = JSONArray()
        imagesJpeg.forEach { jpeg ->
            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64)))
        }
        parts.put(JSONObject().put("text", prompt))
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode != 200) { conn.disconnect(); throw IOException("Gemini OCR HTTP ${conn.responseCode}") }
        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

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
