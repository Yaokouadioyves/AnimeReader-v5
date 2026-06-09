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

/**
 * Free neural voice via Microsoft Edge "Read Aloud" TTS (no API key). The WebSocket is opened once
 * and **reused** for every utterance, so only the first read pays the ~0.7 s handshake; later reads
 * start audio in ~0.2 s. [prewarm] opens the socket ahead of time (overlapping the OCR/translation
 * wait) so even the first read feels instant. Output is MP3 (the endpoint refuses raw PCM), played
 * with MediaPlayer. PERSONAL USE — for commercial, swap to on-device (Piper) or a paid provider.
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
    @Volatile private var wsReady = false
    @Volatile private var pendingSsml: String? = null
    @Volatile private var player: MediaPlayer? = null

    /** The utterance currently being synthesized; audio frames are routed into it. */
    private class Utterance(
        val gen: Int, val id: String,
        val text: String, val langTag: String, val rate: Float, val timbre: String,
    ) { val audio = ByteArrayOutputStream() }
    @Volatile private var current: Utterance? = null
    private val lock = Any()

    /** Open the socket ahead of a read so the handshake overlaps the OCR wait (no-op if already up). */
    fun prewarm() {
        if (ws == null) ensureSocket()
    }

    override suspend fun speak(text: String, langTag: String, rate: Float, timbre: String) {
        if (text.isBlank()) return
        val myGen = ++generation
        main.post { releasePlayer() }
        val voice = EdgeProtocol.voiceFor(langTag, timbre)
        val ssml = EdgeProtocol.ssmlMessage(text, voice, EdgeProtocol.ratePct(rate))
        synchronized(lock) {
            current = Utterance(myGen, text.hashCode().toString(), text, langTag, rate, timbre)
            pendingSsml = ssml
        }
        flushOrConnect()
    }

    private fun flushOrConnect() {
        val sock = ws
        if (sock != null && wsReady) {
            val toSend = synchronized(lock) { pendingSsml.also { pendingSsml = null } } ?: return
            if (!sock.send(toSend)) {
                // Socket went stale between checks → reconnect and retry on open.
                synchronized(lock) { pendingSsml = toSend }
                ws = null; wsReady = false
                ensureSocket()
            }
        } else {
            ensureSocket()
        }
    }

    @Synchronized
    private fun ensureSocket() {
        if (ws != null) return // already open or connecting; onOpen will flush any pending SSML
        val request = Request.Builder()
            .url(EdgeProtocol.socketUrl())
            .header("Origin", EdgeProtocol.ORIGIN)
            .header("User-Agent", EdgeProtocol.USER_AGENT)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .build()
        wsReady = false
        ws = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(EdgeProtocol.configMessage())
            wsReady = true
            val toSend = synchronized(lock) { pendingSsml.also { pendingSsml = null } }
            if (toSend != null) webSocket.send(toSend)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!text.contains("Path:turn.end")) return
            val u = current ?: return
            if (generation == u.gen) playMp3(u.audio.toByteArray(), u.gen, u.id)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val u = current ?: return
            if (generation != u.gen) return
            val b = bytes.toByteArray()
            if (b.size < 2) return
            val headerLen = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
            if (headerLen in 0..b.size) synchronized(lock) { u.audio.write(b, headerLen, b.size - headerLen) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (ws === webSocket) { ws = null; wsReady = false }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val reason = "WS " + (response?.code?.toString() ?: "") + " " + (t.message ?: t.javaClass.simpleName)
            Log.w(TAG, "edge-tts failed: $reason; falling back to device TTS")
            if (ws === webSocket) { ws = null; wsReady = false }
            val u = current
            if (u != null && generation == u.gen) {
                onError?.invoke(reason.trim())
                synchronized(lock) { pendingSsml = null }
                // The pending text could not be synthesized — fall back to the device voice.
                scope.launch { fallback.speak(u.text, u.langTag, u.rate, u.timbre) }
            }
        }
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
        synchronized(lock) { pendingSsml = null; current = null }
        main.post { releasePlayer() }
        fallback.stop()
        // Keep the socket open and warm for the next read.
    }

    override fun shutdown() {
        generation++
        runCatching { ws?.close(1000, null) }; ws = null; wsReady = false
        main.post { releasePlayer() }
        runCatching { scope.cancel() }
        runCatching { client.dispatcher.executorService.shutdown() }
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

    companion object {
        private const val TAG = "AnimeReader/EdgeVoice"
    }
}
