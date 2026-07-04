package com.animereader.internal

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Mistral vision client (HttpURLConnection + org.json, like [GeminiClient]).
 * Used as the fallback OCR+translation backend when Gemini is rate-limited: the free
 * "Experiment" tier of La Plateforme includes vision-capable chat models. NOT the
 * `mistral-ocr` document API — that one has no free API tier and returns document
 * markdown, not bubble reading.
 */
object MistralClient {

    private const val ENDPOINT = "https://api.mistral.ai/v1/chat/completions"
    const val VISION_MODEL = "mistral-small-latest" // vision-capable + free-tier eligible

    /** Manga OCR + translation in one vision call. Returns (original, translation) or null. */
    fun ocrTranslate(apiKey: String, imageJpeg: ByteArray, targetLanguage: String, dialogueContext: String = ""): Pair<String, String>? {
        val prompt = contextBlock(dialogueContext) +
            "Tu es un moteur OCR de manga expert (lettrage stylisé, polices fantaisistes). " +
                "Lis uniquement le texte des bulles dans cette image, corrige les erreurs d'OCR, puis " +
                "traduis en $targetLanguage de manière naturelle et fidèle au ton. Réponds STRICTEMENT en JSON " +
                "{\"original\":\"<texte source>\",\"translation\":\"<traduction>\"}. Aucun texte lisible -> chaînes vides."
        val text = chatVision(apiKey, prompt, listOf(imageJpeg))
        return VisionJson.parseSingle(text)
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
        val text = chatVision(apiKey, prompt, imagesJpeg)
        return VisionJson.parseBatch(text)
    }

    /** The last dialogue lines, injected for coherent pronouns/tone across bubbles. */
    private fun contextBlock(dialogueContext: String): String =
        if (dialogueContext.isBlank()) ""
        else "Contexte — répliques précédentes du dialogue (original => traduction), pour garder " +
            "pronoms, ton et vocabulaire cohérents :\n$dialogueContext\n\n"

    /** POSTs a vision chat completion; returns the assistant text. Throws on HTTP errors. */
    private fun chatVision(apiKey: String, prompt: String, imagesJpeg: List<ByteArray>): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 15000; readTimeout = 45000
        }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        imagesJpeg.forEach { jpeg ->
            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        }
        val body = JSONObject().apply {
            put("model", VISION_MODEL)
            put("temperature", 0.2)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode != 200) {
            conn.disconnect()
            throw IOException("Mistral OCR HTTP ${conn.responseCode}")
        }
        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return JSONObject(resp).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }
}
