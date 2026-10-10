package com.animereader.data

/** Visual direction — mirrors the three design directions (theme.jsx). */
enum class ThemeDirection { SUMI, NEON, MATERIAL }

enum class AppMode { LIGHT, DARK }

/** Translation backend. */
enum class TranslationMethod { OFFLINE, ONLINE }

/** Online text-translation providers. */
enum class TextProvider(val displayName: String, val org: String) {
    OPENAI("GPT-4o", "OpenAI"),
    DEEPL("DeepL", "DeepL Pro"),
    GOOGLE("Google", "Cloud Translate"),
}

/** Voice (TTS) backend. */
enum class VoiceMethod { OFFLINE, ONLINE }

/** Online voice providers. */
enum class VoiceProviderId(val displayName: String, val org: String) {
    ELEVENLABS("ElevenLabs", "Voix IA réalistes"),
    GOOGLE("Google", "Cloud TTS · WaveNet"),
    OPENAI("OpenAI", "TTS HD"),
}

/** A target language for translation + speech. */
data class Lang(
    val code: String,   // BCP-47-ish ("fr", "en", "es", "ja", "pt")
    val tag: String,    // short uppercase label
    val label: String,  // French name
    val native: String, // endonym
)

/** A line recognised + (optionally) translated + (optionally) spoken. */
data class HistoryEntry(
    val time: String,
    val original: String,
    val translated: String? = null,
    val langTag: String? = null,
    val method: TranslationMethod? = null,
    val providerName: String? = null,
)

object Catalog {
    val languages = listOf(
        Lang("fr", "FR", "Français", "Français"),
        Lang("en", "EN", "Anglais", "English"),
        Lang("es", "ES", "Espagnol", "Español"),
        Lang("ja", "JA", "Japonais", "日本語"),
        Lang("pt", "PT", "Portugais", "Português"),
        Lang("de", "DE", "Allemand", "Deutsch"),
        Lang("it", "IT", "Italien", "Italiano"),
        Lang("ko", "KO", "Coréen", "한국어"),
    )

    fun lang(code: String): Lang = languages.firstOrNull { it.code == code } ?: languages[1]

    val timbres = listOf("Féminine", "Masculine", "Neutre")
    val captureIntervals = listOf(0.5f, 1f, 1.5f, 2f)
}
