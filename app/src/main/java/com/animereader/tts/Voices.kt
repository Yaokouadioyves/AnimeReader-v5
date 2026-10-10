package com.animereader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
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

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
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
