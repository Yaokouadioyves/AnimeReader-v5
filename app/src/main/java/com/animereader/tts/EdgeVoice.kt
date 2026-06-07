package com.animereader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Free neural voice via Microsoft Edge "Read Aloud" TTS (no API key). Streams MP3 over a
 * WebSocket and plays it with MediaPlayer (low latency, ~1s). PERSONAL USE ONLY — for
 * commercial, swap to an on-device engine (Piper) or a paid provider.
 * Falls back to device TTS on any error.
 */
class EdgeVoice(
    private val context: Context,
    private val fallback: DeviceTts,
) : VoiceProvider {

    var onSpeechDone: ((String?) -> Unit)? = null

    /** Diagnostic: failure reason when edge-tts fails and we fall back. */
    var onError: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var generation = 0
    @Volatile private var ws: WebSocket? = null
    @Volatile private var player: MediaPlayer? = null

    override suspend fun speak(text: String, langTag: String, rate: Float, timbre: String) {
        if (text.isBlank()) return
        val myGen = ++generation
        cancelCurrent()
        val id = text.hashCode().toString()
        val voice = voiceFor(langTag, timbre)
        val rateStr = ratePct(rate)
        val audio = ByteArrayOutputStream()

        val url = WSS_URL +
            "&ConnectionId=" + UUID.randomUUID().toString().replace("-", "") +
            "&Sec-MS-GEC=" + secMsGec() +
            "&Sec-MS-GEC-Version=" + SEC_VERSION
        val request = Request.Builder()
            .url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", USER_AGENT)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(configMessage())
                webSocket.send(ssmlMessage(text, voice, rateStr))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation == myGen && text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    playMp3(audio.toByteArray(), myGen, id)
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (generation != myGen) return
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val headerLen = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
                if (headerLen in 0..b.size) audio.write(b, headerLen, b.size - headerLen)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = "WS " + (response?.code?.toString() ?: "") + " " + (t.message ?: t.javaClass.simpleName)
                Log.w(TAG, "edge-tts failed: $reason; falling back to device TTS")
                if (generation == myGen) {
                    onError?.invoke(reason.trim())
                    scope.launch { fallback.speak(text, langTag, rate, timbre) }
                }
            }
        })
    }

    private fun playMp3(mp3: ByteArray, gen: Int, id: String) {
        main.post {
            if (generation != gen) return@post
            val data = trimToSync(mp3)
            if (data.isEmpty()) { onError?.invoke("audio vide (handshake/token ?)"); onSpeechDone?.invoke(id); return@post }
            try {
                val f = File(context.cacheDir, "edge_tts.mp3")
                FileOutputStream(f).use { it.write(data) }
                releasePlayer()
                val mp = MediaPlayer()
                player = mp
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(f.absolutePath)
                mp.setOnPreparedListener { if (generation == gen) it.start() else runCatching { it.release() } }
                mp.setOnCompletionListener {
                    runCatching { it.release() }
                    if (player === it) player = null
                    if (generation == gen) onSpeechDone?.invoke(id)
                }
                mp.setOnErrorListener { p, what, extra -> onError?.invoke("MediaPlayer $what/$extra"); runCatching { p.release() }; true }
                mp.prepareAsync()
            } catch (e: Exception) {
                Log.w(TAG, "MediaPlayer failed: ${e.message}")
                onError?.invoke("MediaPlayer ${e.message}")
            }
        }
    }

    override fun stop() {
        generation++
        cancelCurrent()
        fallback.stop()
    }

    override fun shutdown() {
        cancelCurrent()
        runCatching { scope.cancel() }
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    private fun cancelCurrent() {
        runCatching { ws?.cancel() }; ws = null
        main.post { releasePlayer() }
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    /** MP3 streams may start mid-frame; skip to the first frame sync (0xFF 0xEx). */
    private fun trimToSync(b: ByteArray): ByteArray {
        var i = 0
        while (i < b.size - 1) {
            if (b[i] == 0xFF.toByte() && (b[i + 1].toInt() and 0xE0) == 0xE0) {
                return if (i == 0) b else b.copyOfRange(i, b.size)
            }
            i++
        }
        return b
    }

    // ── Protocol messages ──
    private fun timestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun configMessage(): String =
        "X-Timestamp:${timestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"

    private fun ssmlMessage(text: String, voice: String, rateStr: String): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${langOf(voice)}'>" +
            "<voice name='$voice'><prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>$escaped</prosody></voice></speak>"
        return "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp()}Z\r\n" +
            "Path:ssml\r\n\r\n$ssml"
    }

    private fun ratePct(rate: Float): String {
        val pct = ((rate - 1f) * 100f).toInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    // "fr-FR-DeniseNeural" -> "fr-FR"
    private fun langOf(voice: String): String = voice.substringBeforeLast('-')

    /** Map target language + timbre to an Edge neural voice. */
    private fun voiceFor(langTag: String, timbre: String): String {
        val male = timbre == "Masculine"
        return when (langTag.lowercase().substringBefore('-')) {
            "fr" -> if (male) "fr-FR-HenriNeural" else "fr-FR-DeniseNeural"
            "en" -> if (male) "en-US-GuyNeural" else "en-US-AriaNeural"
            "es" -> if (male) "es-ES-AlvaroNeural" else "es-ES-ElviraNeural"
            "ja" -> if (male) "ja-JP-KeitaNeural" else "ja-JP-NanamiNeural"
            "pt" -> if (male) "pt-BR-AntonioNeural" else "pt-BR-FranciscaNeural"
            else -> if (male) "en-US-GuyNeural" else "en-US-AriaNeural"
        }
    }

    private fun secMsGec(): String {
        // Windows file-time, rounded down to 5 min, in 100ns ticks (exact integer math).
        val winSeconds = (System.currentTimeMillis() / 1000L) + 11644473600L
        val rounded = winSeconds - (winSeconds % 300L)
        val ticks = rounded * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((ticks.toString() + TRUSTED_TOKEN).toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    companion object {
        private const val TAG = "AnimeReader/EdgeVoice"
        private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_TOKEN"
        private const val SEC_VERSION = "1-143.0.3650.75"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    }
}
