package com.animereader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline neural voice: Gradium's Phonon 40m French model, run on the phone's CPU
 * (libptts_ffi.so + the model files bundled in assets/phonon, see the CI workflow).
 *
 * Same shape as [EdgeVoice]: one worker thread synthesizes lines one at a time (the library takes
 * one call at a time), [prefetch] queues the next bubbles while the current one plays, and the
 * audio is streamed to an AudioTrack as it is generated so a line starts before it is finished.
 * Lines the model can't read (not French, model missing or failing) go to [fallback].
 *
 * Phonon © Gradium, CC BY-NC 4.0: non-commercial use only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhononVoice(
    private val context: Context,
    private val fallback: VoiceProvider,
) : VoiceProvider {

    var onSpeechDone: ((String?) -> Unit)? = null

    /** Diagnostic when the model can't be loaded or a line fails (throttled). */
    var onError: ((String) -> Unit)? = null

    // The library is not thread-safe: every call to it runs on this single thread.
    private val engine = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class Key(val voice: String, val text: String)

    /** One line: its audio arrives in [chunks] while it is synthesized, closed at the end. */
    private class Synth(val key: Key) {
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        @Volatile var started = false
        @Volatile var cancelled = false
    }

    private val cache = LinkedHashMap<Key, Synth>()
    private val pending = Channel<Synth>(Channel.UNLIMITED)

    @Volatile private var tts: PhononTTS? = null
    @Volatile private var loadFailed = false
    @Volatile private var voice: String? = null
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null
    @Volatile private var lastErrorToast = 0L

    init {
        scope.launch { for (s in pending) withContext(engine) { synthesize(s) } }
    }

    /** True when the model files are in the APK (they are left out of local builds). */
    val bundled: Boolean by lazy { runCatching { context.assets.list(ASSET_DIR).orEmpty().isNotEmpty() }.getOrDefault(false) }

    /** Load the model ahead of the first line (the load takes a second or two). */
    fun prewarm() {
        if (tts == null && !loadFailed) scope.launch { withContext(engine) { load() } }
    }

    override fun prefetch(text: String, langTag: String, rate: Float, timbre: String) {
        val v = PhononVoices.voiceFor(langTag, timbre) ?: return
        if (text.isNotBlank() && bundled && !loadFailed) request(Key(v, text))
    }

    override suspend fun speak(text: String, langTag: String, rate: Float, timbre: String) {
        if (text.isBlank()) return
        val v = PhononVoices.voiceFor(langTag, timbre)
        if (v == null || !bundled || loadFailed) {
            fallback.speak(text, langTag, rate, timbre)
            return
        }
        val myGen = ++generation
        stopTrack()
        val s = synchronized(cache) { cache.remove(Key(v, text)) } ?: request(Key(v, text), keep = false)
        val id = text.hashCode().toString()
        val ok = try {
            play(s, rate, myGen)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
        if (generation != myGen) return
        if (ok) onSpeechDone?.invoke(id)
        else fallback.speak(text, langTag, rate, timbre) // this line failed: read it with the device voice
    }

    private fun request(key: Key, keep: Boolean = true): Synth {
        synchronized(cache) {
            cache[key]?.let { return it }
            val s = Synth(key)
            if (keep) {
                cache[key] = s
                while (cache.size > MAX_CACHED) {
                    val eldest = cache.entries.first()
                    eldest.value.cancelled = true
                    cache.remove(eldest.key)
                }
            }
            pending.trySend(s)
            return s
        }
    }

    /** Runs on [engine]. */
    private fun load(): PhononTTS? {
        tts?.let { return it }
        if (loadFailed) return null
        return try {
            val dir = unpackModel()
            // One thread per core is too many on phones mixing fast and slow cores (Gradium's advice).
            android.system.Os.setenv("RAYON_NUM_THREADS", THREADS.toString(), true)
            PhononTTS(dir.path, "fr").also { tts = it }
        } catch (e: Throwable) {
            loadFailed = true
            reportError("voix Phonon indisponible : ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /** Runs on [engine]: synthesize one line into its channel. */
    private fun synthesize(s: Synth) {
        if (s.cancelled) { s.chunks.close(); return }
        s.started = true
        try {
            val t = load() ?: throw IllegalStateException("modèle non chargé")
            if (voice != s.key.voice) { t.setVoice(s.key.voice); voice = s.key.voice }
            t.speak(s.key.text) { pcm ->
                if (s.cancelled) false else { s.chunks.trySend(pcm); true }
            }
            s.chunks.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Phonon synthesis failed: ${e.message}")
            s.chunks.close(e)
        }
    }

    /** Stream [s] to a fresh AudioTrack; true once it has been played to the end. */
    private suspend fun play(s: Synth, rate: Float, gen: Int): Boolean = withContext(Dispatchers.IO) {
        val t = newTrack(rate)
        track = t
        var frames = 0L
        try {
            t.play()
            for (pcm in s.chunks) {
                // Non-blocking writes: a paused/flushed track (stop()) must not hang this loop.
                var off = 0
                while (off < pcm.size) {
                    if (generation != gen) { s.cancelled = true; return@withContext false }
                    val n = t.write(pcm, off, pcm.size - off, AudioTrack.WRITE_NON_BLOCKING)
                    if (n < 0) throw IllegalStateException("AudioTrack.write $n")
                    off += n
                    if (n == 0) delay(10)
                }
                frames += pcm.size
            }
            if (frames == 0L) return@withContext false
            // stop() on a streaming track plays what is buffered (even a line shorter than the
            // start threshold), then the head position says when it has been heard.
            t.stop()
            var last = -1
            var still = 0
            while (generation == gen && t.playbackHeadPosition < frames && still < 10) {
                val pos = t.playbackHeadPosition
                still = if (pos == last) still + 1 else 0
                last = pos
                delay(50)
            }
            generation == gen
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (generation == gen) reportError("lecture : ${e.message}")
            false
        } finally {
            if (track === t) track = null
            runCatching { t.stop() }; runCatching { t.release() }
        }
    }

    private fun newTrack(rate: Float): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, SAMPLE_RATE * 4 / 5)) // ~200 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // The model has no speed control: the reading speed is a time-stretch, pitch kept.
        if (rate != 1f) runCatching { t.playbackParams = PlaybackParams().setSpeed(rate.coerceIn(0.5f, 2f)).setPitch(1f) }
        return t
    }

    /**
     * The library loads a folder: copy assets/phonon to the app's files once per model version
     * (a marker file says the copy is complete, so an interrupted copy is redone).
     */
    private fun unpackModel(): File {
        val dir = File(context.filesDir, "phonon-$MODEL_VERSION")
        val done = File(dir, ".complete")
        if (done.exists()) return dir
        context.filesDir.listFiles()?.filter { it.name.startsWith("phonon-") && it != dir }?.forEach { it.deleteRecursively() }
        dir.deleteRecursively()
        copyAssets(ASSET_DIR, dir)
        done.writeText(MODEL_VERSION)
        return dir
    }

    private fun copyAssets(path: String, to: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            context.assets.open(path).use { input -> to.outputStream().use { input.copyTo(it, 1 shl 16) } }
            return
        }
        to.mkdirs()
        for (c in children) copyAssets("$path/$c", File(to, c))
    }

    private fun stopTrack() {
        track?.let { runCatching { it.pause() }; runCatching { it.flush() } }
    }

    private fun reportError(msg: String) {
        Log.w(TAG, msg)
        val now = System.currentTimeMillis()
        if (now - lastErrorToast > 60_000L) { lastErrorToast = now; onError?.invoke(msg) }
    }

    override fun stop() {
        generation++
        // Drop the lines queued for the old page; the one being synthesized stops at its next frame.
        synchronized(cache) {
            cache.values.forEach { if (!it.started) it.cancelled = true }
            cache.clear()
        }
        stopTrack()
        fallback.stop()
    }

    override fun shutdown() {
        generation++
        pending.close()
        stopTrack()
        scope.launch { withContext(engine) { runCatching { tts?.close() }; tts = null } }
            .invokeOnCompletion { scope.cancel() }
    }

    companion object {
        private const val TAG = "AnimeReader/Phonon"
        private const val ASSET_DIR = "phonon"
        /** Bump with the bundled files (CI workflow) so phones re-copy them. */
        private const val MODEL_VERSION = "fr-40m-1"
        private const val SAMPLE_RATE = 24_000
        private const val THREADS = 4
        private const val MAX_CACHED = 6
    }
}

/** Which bundled voice reads a line. Pure (JVM-tested). */
object PhononVoices {
    const val MALE = "Augustin"
    const val FEMALE = "Noemie"

    /** The bundled model is French only: null means "not for Phonon", the fallback reads it. */
    fun voiceFor(langTag: String, timbre: String): String? {
        if (!langTag.lowercase().startsWith("fr")) return null
        return if (timbre == "Féminine") FEMALE else MALE
    }
}
