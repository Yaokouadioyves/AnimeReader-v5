package com.animereader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.animereader.data.VoiceMethod
import com.animereader.data.VoiceProviderId
import com.animereader.internal.ApiKeys
import kotlinx.coroutines.delay
import java.util.Locale

/** Speak a line of text. */
interface VoiceProvider {
    suspend fun speak(text: String, langTag: String, rate: Float, timbre: String)
    fun stop()
    fun shutdown()
}

/** Offline device TTS (android.speech.tts). Fully functional. */
class DeviceTts(context: Context) : VoiceProvider {
    @Volatile private var ready = false

    /** Invoked (on a binder thread) with the utterance id when speech finishes or errors. */
    var onSpeechDone: ((String?) -> Unit)? = null

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onSpeechDone?.invoke(utteranceId) }
            @Deprecated("deprecated in API level 21") override fun onError(utteranceId: String?) { onSpeechDone?.invoke(utteranceId) }
        })
    }

    override suspend fun speak(text: String, langTag: String, rate: Float, timbre: String) {
        if (text.isBlank()) return
        var tries = 0
        while (!ready && tries < 60) { delay(50); tries++ }
        if (!ready) { Log.w(TAG, "TTS non initialisé"); return }
        val locale = Locale.forLanguageTag(langTag)
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
        tts.setSpeechRate(rate.coerceIn(0.5f, 2f))
        // Timbre maps loosely to pitch (device voices vary).
        tts.setPitch(when (timbre) { "Masculine" -> 0.85f; "Neutre" -> 1.0f; else -> 1.1f })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    override fun stop() { runCatching { tts.stop() } }
    override fun shutdown() { runCatching { tts.shutdown() } }

    companion object { private const val TAG = "AnimeReader/TTS" }
}

/**
 * Online voice stub (ElevenLabs / Google Cloud TTS / OpenAI). Wire the real fetch-audio-and-play
 * here using [apiKey]; until then it transparently falls back to device TTS so reading still works.
 */
class OnlineVoice(
    private val provider: VoiceProviderId,
    private val apiKey: String,
    private val fallback: DeviceTts,
) : VoiceProvider {
    override suspend fun speak(text: String, langTag: String, rate: Float, timbre: String) {
        try {
            check(apiKey.isNotBlank()) { "Clé API ${provider.displayName} manquante" }
            // TODO: fetch synthesized audio from the provider and play it via MediaPlayer/ExoPlayer.
            throw NotImplementedError("Voix ${provider.displayName}: intégration à compléter")
        } catch (e: Throwable) {
            Log.w("AnimeReader/Voice", "${provider.displayName} indisponible (${e.message}) → repli TTS appareil")
            fallback.speak(text, langTag, rate, timbre)
        }
    }

    override fun stop() = fallback.stop()
    override fun shutdown() {}
}

object Voices {
    fun create(
        method: VoiceMethod,
        provider: VoiceProviderId,
        apiKeys: ApiKeys,
        device: DeviceTts,
    ): VoiceProvider = when (method) {
        VoiceMethod.OFFLINE -> device
        VoiceMethod.ONLINE -> OnlineVoice(provider, apiKeys.forVoice(provider), device)
    }
}
