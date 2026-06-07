package com.animereader.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.animereader.internal.GeminiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Online neural voice via Gemini 2.5 Flash TTS, played in streaming as PCM chunks arrive
 * (lower latency). Falls back to device TTS on any error / missing key.
 */
class GeminiVoice(
    private val keyProvider: () -> String,
    private val fallback: DeviceTts,
) : VoiceProvider {

    /** Invoked with the utterance id when playback finishes (for caption sync). */
    var onSpeechDone: ((String?) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null

    override suspend fun speak(text: String, langTag: String, rate: Float, timbre: String) {
        val key = keyProvider()
        if (key.isBlank() || text.isBlank()) { fallback.speak(text, langTag, rate, timbre); return }
        val myGen = ++generation
        stopTrack()
        val id = text.hashCode().toString()
        val prompt = "$STYLE\n\nTexte : $text"
        scope.launch {
            val at = createTrack()
            track = at
            runCatching { at.play() }
            val ok = runCatching {
                GeminiClient.ttsStream(key, prompt, voiceFor(timbre), { generation == myGen }) { pcm ->
                    if (generation == myGen) runCatching { at.write(pcm, 0, pcm.size) }
                }
            }.onFailure { Log.w(TAG, "Gemini TTS failed: ${it.message}") }.isSuccess
            if (generation == myGen) {
                runCatching { at.stop() }; runCatching { at.release() }
                if (track === at) track = null
                if (ok) onSpeechDone?.invoke(id) else fallback.speak(text, langTag, rate, timbre)
            } else {
                runCatching { at.release() }
            }
        }
    }

    override fun stop() {
        generation++
        stopTrack()
        fallback.stop()
    }

    override fun shutdown() {
        runCatching { scope.cancel() }
    }

    private fun stopTrack() {
        track?.let { runCatching { it.pause(); it.flush(); it.stop(); it.release() } }
        track = null
    }

    private fun createTrack(): AudioTrack {
        val rate = GeminiClient.TTS_SAMPLE_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, rate)) // ~0.5s buffer
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Gemini prebuilt voices, loosely mapped from the chosen timbre. */
    private fun voiceFor(timbre: String): String = when (timbre) {
        "Masculine" -> "Charon"
        "Neutre" -> "Zephyr"
        else -> "Aoede" // Féminine
    }

    companion object {
        private const val TAG = "AnimeReader/GeminiVoice"
        // Style direction so the delivery is clear, clean and expressive (it is not read aloud).
        const val STYLE =
            "Lis le texte suivant comme un comédien de doublage de manga : voix claire, " +
                "limpide et expressive, diction nette, ton naturel et vivant, intonation qui " +
                "transmet l'émotion du personnage, rythme fluide. Ne lis que le texte, n'ajoute rien."
    }
}
