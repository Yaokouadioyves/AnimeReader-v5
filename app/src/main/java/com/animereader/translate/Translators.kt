package com.animereader.translate

import com.animereader.data.TextProvider
import com.animereader.data.TranslationMethod
import com.animereader.internal.ApiKeys
import com.animereader.internal.await
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/** Translate source text into a target language. */
interface TranslationProvider {
    /** @param targetCode BCP-47-ish code ("en", "ja", …). Returns translated text. */
    suspend fun translate(text: String, targetCode: String): String
}

/** Offline, on-device translation via ML Kit. Fully functional (downloads models on demand). */
class MlKitTranslator : TranslationProvider {
    override suspend fun translate(text: String, targetCode: String): String {
        val target = TranslateLanguage.fromLanguageTag(targetCode) ?: return text
        val source = detectSource(text)
        if (source == target) return text
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            translator.translate(text).await()
        } finally {
            translator.close()
        }
    }

    /** Heuristic: any CJK glyph → Japanese source, otherwise French (the prototype's source). */
    private fun detectSource(text: String): String {
        val cjk = text.any { c ->
            val code = c.code
            code in 0x3000..0x9FFF || code in 0xFF00..0xFFEF
        }
        return if (cjk) TranslateLanguage.JAPANESE else TranslateLanguage.FRENCH
    }
}

/**
 * Online provider stub. Wire real HTTP calls here (Retrofit/OkHttp) using [apiKey].
 * Throws so the caller can fall back to offline translation until you implement it.
 */
class OnlineTranslator(
    val provider: TextProvider,
    private val apiKey: String,
) : TranslationProvider {
    override suspend fun translate(text: String, targetCode: String): String {
        check(apiKey.isNotBlank()) {
            "Clé API ${provider.displayName} manquante (local.properties)."
        }
        // TODO: implement the real call, e.g.:
        //   OPENAI  → POST https://api.openai.com/v1/chat/completions
        //   DEEPL   → POST https://api-free.deepl.com/v2/translate
        //   GOOGLE  → POST https://translation.googleapis.com/language/translate/v2
        throw NotImplementedError("Provider ${provider.displayName}: intégration réseau à compléter.")
    }
}

object Translators {
    fun create(method: TranslationMethod, provider: TextProvider, apiKeys: ApiKeys): TranslationProvider =
        when (method) {
            TranslationMethod.OFFLINE -> MlKitTranslator()
            TranslationMethod.ONLINE -> OnlineTranslator(provider, apiKeys.forText(provider))
        }
}
