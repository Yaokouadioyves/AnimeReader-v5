package com.animereader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream

/**
 * Free neural voice via Microsoft Edge "Read Aloud" TTS (no API key).
 *
 * Synthesis and playback are decoupled: every line is synthesized once (one WebSocket, reused,
 * one request at a time) into a small cache, and [prefetch] lets the queue start synthesizing
 * the *next* bubbles while the current one is playing — so consecutive bubbles follow each other
 * without the ~1 s synthesis gap. [prewarm] opens the socket during the OCR wait.
 *
 * Robustness: endpoints are tried in order ([EdgeProtocol.HOSTS]), a 403 caused by a skewed
 * phone clock is corrected from the server's Date header, and any failure falls back to the
 * fallback voice for that line
 * (Phonon when bundled, else the device voice). PERSONAL USE — for commercial, swap to on-device or a paid provider.
 */
class EdgeVoice(
    private val context: Context,
    private val fallback: VoiceProvider,
) : VoiceProvider {

    var onSpeechDone: ((String?) -> Unit)? = null

    /** Diagnostic: failure reason when edge-tts fails and we fall back (throttled). */
    var onError: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** One synthesized line, shared between a prefetch and the later speak(). */
    private data class Key(val voice: String, val rate: String, val text: String)
    private class Synth(val key: Key) {
        val result = CompletableDeferred<ByteArray>()
        @Volatile var started = false
    }

    /** The request currently on the socket; its audio frames and turn.end are routed here. */
    private class Turn(val requestId: String, val socket: WebSocket) {
        val audio = ByteArrayOutputStream()
        val end = CompletableDeferred<ByteArray>()
    }

    private class EdgeFailure(val code: Int?, val date: String?, msg: String) : Exception(msg)

    private val cache = object : LinkedHashMap<Key, Synth>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Synth>?) = size > 24
    }
    private val pending = Channel<Synth>(Channel.UNLIMITED)

    @Volatile private var generation = 0
    @Volatile private var player: MediaPlayer? = null

    // Socket state (touched by the single synth worker and the OkHttp listener).
    @Volatile private var ws: WebSocket? = null
    @Volatile private var opening: CompletableDeferred<WebSocket>? = null
    @Volatile private var openingSocket: WebSocket? = null
    @Volatile private var turn: Turn? = null
    @Volatile private var host = 0
    @Volatile private var clockSkewMs = 0L
    @Volatile private var lastErrorToast = 0L

    init {
        scope.launch { for (s in pending) synthesize(s) }
    }

    /** Open the socket ahead of a read so the handshake overlaps the OCR wait. */
    fun prewarm() {
        if (ws == null && opening == null) scope.launch { runCatching { openSocket() } }
    }

    /** Start synthesizing a line that will be spoken soon (no-op if already cached). */
    override fun prefetch(text: String, langTag: String, rate: Float, timbre: String) {
        if (text.isNotBlank()) request(text, langTag, rate, timbre)
    }

    override suspend fun speak(text: String, langTag: String, rate: Float, timbre: String) {
        if (text.isBlank()) return
        val myGen = ++generation
        main.post { releasePlayer() }
        val id = text.hashCode().toString()
        val audio = try {
            withTimeout(SPEAK_TIMEOUT_MS) { request(text, langTag, rate, timbre).result.await() }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e // service closing
            if (generation != myGen) return
            reportError(if (e is TimeoutCancellationException) "délai dépassé" else e.message ?: "erreur")
            // This line could not be synthesized online — read it with the device voice.
            fallback.speak(text, langTag, rate, timbre)
            return
        }
        if (generation == myGen) play(audio, myGen, id)
    }

    private fun request(text: String, langTag: String, rate: Float, timbre: String): Synth {
        val key = Key(EdgeProtocol.voiceFor(langTag, timbre), EdgeProtocol.ratePct(rate), text)
        synchronized(cache) {
            cache[key]?.let { if (!it.result.isCancelled) return it }
            val s = Synth(key)
            cache[key] = s
            pending.trySend(s)
            return s
        }
    }

    /** Runs on the single worker: one request on the socket at a time, with retries. */
    private suspend fun synthesize(s: Synth) {
        if (s.result.isCompleted) return // cancelled by stop() before its turn
        s.started = true
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) {
            try {
                val sock = openSocket()
                val t = Turn(EdgeProtocol.newId(), sock)
                turn = t
                val msg = EdgeProtocol.ssmlMessage(s.key.text, s.key.voice, s.key.rate, requestId = t.requestId)
                if (!sock.send(msg)) throw EdgeFailure(null, null, "socket fermé")
                val audio = withTimeout(TURN_TIMEOUT_MS) { t.end.await() }
                turn = null
                if (audio.isEmpty()) throw EdgeFailure(null, null, "audio vide")
                s.result.complete(audio)
                return
            } catch (e: Exception) {
                turn = null
                lastError = e
                dropSocket()
                if (e is EdgeFailure && e.code == 403) {
                    // Time-based token: fix a skewed phone clock first, otherwise try the next host.
                    val skew = EdgeProtocol.clockSkewMs(e.date, System.currentTimeMillis())
                    if (skew != null && kotlin.math.abs(skew - clockSkewMs) > 60_000L) clockSkewMs = skew
                    else host = (host + 1) % EdgeProtocol.HOSTS.size
                } else if (e is EdgeFailure && e.code != null) {
                    host = (host + 1) % EdgeProtocol.HOSTS.size
                }
                Log.w(TAG, "edge-tts attempt failed: ${e.message}")
            }
        }
        synchronized(cache) { if (cache[s.key] === s) cache.remove(s.key) } // retry on next read
        s.result.completeExceptionally(lastError ?: EdgeFailure(null, null, "échec"))
    }

    private suspend fun openSocket(): WebSocket {
        ws?.let { return it }
        val d = opening ?: synchronized(this) {
            opening ?: CompletableDeferred<WebSocket>().also { def ->
                opening = def
                val request = Request.Builder()
                    .url(EdgeProtocol.socketUrl(host, System.currentTimeMillis() + clockSkewMs))
                    .header("Origin", EdgeProtocol.ORIGIN)
                    .header("User-Agent", EdgeProtocol.USER_AGENT)
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .build()
                openingSocket = client.newWebSocket(request, listener)
            }
        }
        return withTimeout(CONNECT_TIMEOUT_MS) { d.await() }
    }

    private fun dropSocket() {
        runCatching { ws?.cancel() }
        ws = null
        opening?.cancel(); opening = null; openingSocket = null
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(EdgeProtocol.configMessage())
            if (webSocket !== openingSocket) { webSocket.cancel(); return } // superseded
            ws = webSocket
            opening?.complete(webSocket); opening = null; openingSocket = null
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!text.contains("Path:turn.end")) return
            val t = turn ?: return
            if (EdgeProtocol.requestIdOf(text) == t.requestId) t.end.complete(t.audio.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val t = turn ?: return
            val b = bytes.toByteArray()
            val start = EdgeProtocol.audioOffset(b)
            if (start < 0 || start >= b.size) return
            if (EdgeProtocol.requestIdOf(EdgeProtocol.frameHeaders(b)) != t.requestId) return
            t.audio.write(b, start, b.size - start)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            closed(webSocket, EdgeFailure(null, null, "fermé par le serveur ($code)"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed(webSocket, EdgeFailure(null, null, "fermé ($code)"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            val reason = ("WS " + (code?.toString() ?: "") + " " + (t.message ?: t.javaClass.simpleName)).trim()
            closed(webSocket, EdgeFailure(code, response?.header("Date"), reason))
        }

        private fun closed(webSocket: WebSocket, error: EdgeFailure) {
            // Only this socket's own waiters fail: a stale socket dying late must not take down
            // the connection that replaced it.
            if (ws === webSocket) ws = null
            if (webSocket === openingSocket) {
                opening?.let { if (!it.isCompleted) it.completeExceptionally(error) }
                opening = null; openingSocket = null
            }
            turn?.let { if (it.socket === webSocket) it.end.completeExceptionally(error) }
        }
    }

    private fun play(mp3: ByteArray, gen: Int, id: String) {
        main.post {
            if (generation != gen) return@post
            try {
                releasePlayer()
                val mp = MediaPlayer()
                player = mp
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(BytesSource(trimToSync(mp3)))
                mp.setOnPreparedListener { if (generation == gen) it.start() else runCatching { it.release() } }
                mp.setOnCompletionListener {
                    runCatching { it.release() }
                    if (player === it) player = null
                    if (generation == gen) onSpeechDone?.invoke(id)
                }
                mp.setOnErrorListener { p, what, extra ->
                    reportError("lecture $what/$extra")
                    runCatching { p.release() }
                    if (player === p) player = null
                    if (generation == gen) onSpeechDone?.invoke(id) // don't stall the queue
                    true
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                Log.w(TAG, "MediaPlayer failed: ${e.message}")
                reportError("lecture ${e.message}")
                onSpeechDone?.invoke(id)
            }
        }
    }

    private fun reportError(msg: String) {
        Log.w(TAG, "edge-tts: $msg")
        val now = System.currentTimeMillis()
        if (now - lastErrorToast > 60_000L) { lastErrorToast = now; onError?.invoke(msg) }
    }

    override fun stop() {
        generation++
        // Drop lines that were queued for synthesis but not started (the page changed).
        synchronized(cache) {
            val stale = cache.entries.filter { !it.value.started && !it.value.result.isCompleted }
            stale.forEach { it.value.result.cancel(); cache.remove(it.key) }
        }
        main.post { releasePlayer() }
        fallback.stop()
        // Keep the socket open and warm for the next read.
    }

    override fun shutdown() {
        generation++
        runCatching { ws?.close(1000, null) }; ws = null
        pending.close()
        main.post { releasePlayer() }
        runCatching { scope.cancel() }
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    /** Plays an in-memory MP3 (no temp file, no disk write between bubbles). */
    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    /** Defensive: start at the first MP3 frame sync (0xFF 0xEx). */
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
        private const val MAX_ATTEMPTS = 3
        private const val CONNECT_TIMEOUT_MS = 6_000L
        private const val TURN_TIMEOUT_MS = 12_000L
        private const val SPEAK_TIMEOUT_MS = 25_000L
    }
}
