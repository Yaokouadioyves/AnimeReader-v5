package com.animereader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Snapshot of every user-tunable preference (reader behaviour + visual tweaks). */
data class Settings(
    // Translation
    val lang: String = "en",
    val translateOn: Boolean = true,
    val method: TranslationMethod = TranslationMethod.ONLINE,
    val provider: TextProvider = TextProvider.OPENAI,
    // Voice & reading
    val ttsEnabled: Boolean = true,
    val voiceMethod: VoiceMethod = VoiceMethod.ONLINE, // free neural voice; falls back to device TTS
    val voiceProvider: VoiceProviderId = VoiceProviderId.ELEVENLABS,
    val speed: Float = 1f,
    val timbre: String = "Féminine",
    val captureInterval: Float = 1f,
    val autoplay: Boolean = true,
    val captionScale: Float = 1f,   // subtitle text size (0.85 / 1.0 / 1.2)
    val geminiKey: String = "",     // user's free Gemini API key (on-device only)
    val mistralKey: String = "",    // optional Mistral key — free fallback when Gemini is rate-limited
    // Page reading direction (order of the bubbles of one row).
    val readingRtl: Boolean = true, // manga (and French/English manga scans): right-to-left
    // Translation drawn over each bubble (true) or in the subtitle bar (false).
    val overlayMode: Boolean = true,
    // Overlay geometry, remembered across sessions (-1 = never placed yet)
    val rectX: Int = -1, val rectY: Int = -1, val rectW: Int = -1, val rectH: Int = -1,
    val bubbleX: Int = -1, val bubbleY: Int = -1,
    // Visual tweaks
    val direction: ThemeDirection = ThemeDirection.SUMI,
    val mode: AppMode = AppMode.LIGHT,
    val accent: String = "auto",     // "auto" or "#rrggbb"
    val textScale: Float = 1f,
    // App state
    val onboardingDone: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "animereader_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val lang = stringPreferencesKey("lang")
        val translateOn = booleanPreferencesKey("translateOn")
        val method = stringPreferencesKey("method")
        val provider = stringPreferencesKey("provider")
        val ttsEnabled = booleanPreferencesKey("ttsEnabled")
        val voiceMethod = stringPreferencesKey("voiceMethod")
        val voiceProvider = stringPreferencesKey("voiceProvider")
        val speed = floatPreferencesKey("speed")
        val timbre = stringPreferencesKey("timbre")
        val captureInterval = floatPreferencesKey("captureInterval")
        val autoplay = booleanPreferencesKey("autoplay")
        val captionScale = floatPreferencesKey("captionScale")
        val geminiKey = stringPreferencesKey("geminiKey")
        val mistralKey = stringPreferencesKey("mistralKey")
        val readingRtl = booleanPreferencesKey("readingRtl")
        val overlayMode = booleanPreferencesKey("overlayMode")
        val rectX = intPreferencesKey("rectX")
        val rectY = intPreferencesKey("rectY")
        val rectW = intPreferencesKey("rectW")
        val rectH = intPreferencesKey("rectH")
        val bubbleX = intPreferencesKey("bubbleX")
        val bubbleY = intPreferencesKey("bubbleY")
        val direction = stringPreferencesKey("direction")
        val mode = stringPreferencesKey("mode")
        val accent = stringPreferencesKey("accent")
        val textScale = floatPreferencesKey("textScale")
        val onboardingDone = booleanPreferencesKey("onboardingDone")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings()
        Settings(
            lang = p[Keys.lang] ?: d.lang,
            translateOn = p[Keys.translateOn] ?: d.translateOn,
            method = p[Keys.method]?.let { enumOrNull<TranslationMethod>(it) } ?: d.method,
            provider = p[Keys.provider]?.let { enumOrNull<TextProvider>(it) } ?: d.provider,
            ttsEnabled = p[Keys.ttsEnabled] ?: d.ttsEnabled,
            voiceMethod = p[Keys.voiceMethod]?.let { enumOrNull<VoiceMethod>(it) } ?: d.voiceMethod,
            voiceProvider = p[Keys.voiceProvider]?.let { enumOrNull<VoiceProviderId>(it) } ?: d.voiceProvider,
            speed = p[Keys.speed] ?: d.speed,
            timbre = p[Keys.timbre] ?: d.timbre,
            captureInterval = p[Keys.captureInterval] ?: d.captureInterval,
            autoplay = p[Keys.autoplay] ?: d.autoplay,
            captionScale = p[Keys.captionScale] ?: d.captionScale,
            geminiKey = p[Keys.geminiKey] ?: d.geminiKey,
            mistralKey = p[Keys.mistralKey] ?: d.mistralKey,
            readingRtl = p[Keys.readingRtl] ?: d.readingRtl,
            overlayMode = p[Keys.overlayMode] ?: d.overlayMode,
            rectX = p[Keys.rectX] ?: d.rectX, rectY = p[Keys.rectY] ?: d.rectY,
            rectW = p[Keys.rectW] ?: d.rectW, rectH = p[Keys.rectH] ?: d.rectH,
            bubbleX = p[Keys.bubbleX] ?: d.bubbleX, bubbleY = p[Keys.bubbleY] ?: d.bubbleY,
            direction = p[Keys.direction]?.let { enumOrNull<ThemeDirection>(it) } ?: d.direction,
            mode = p[Keys.mode]?.let { enumOrNull<AppMode>(it) } ?: d.mode,
            accent = p[Keys.accent] ?: d.accent,
            textScale = p[Keys.textScale] ?: d.textScale,
            onboardingDone = p[Keys.onboardingDone] ?: d.onboardingDone,
        )
    }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            // Build a Settings from current prefs, transform, then write back every field.
            val cur = Settings(
                lang = p[Keys.lang] ?: "en",
                translateOn = p[Keys.translateOn] ?: true,
                method = p[Keys.method]?.let { enumOrNull<TranslationMethod>(it) } ?: TranslationMethod.ONLINE,
                provider = p[Keys.provider]?.let { enumOrNull<TextProvider>(it) } ?: TextProvider.OPENAI,
                ttsEnabled = p[Keys.ttsEnabled] ?: true,
                voiceMethod = p[Keys.voiceMethod]?.let { enumOrNull<VoiceMethod>(it) } ?: VoiceMethod.ONLINE,
                voiceProvider = p[Keys.voiceProvider]?.let { enumOrNull<VoiceProviderId>(it) } ?: VoiceProviderId.ELEVENLABS,
                speed = p[Keys.speed] ?: 1f,
                timbre = p[Keys.timbre] ?: "Féminine",
                captureInterval = p[Keys.captureInterval] ?: 1f,
                autoplay = p[Keys.autoplay] ?: true,
                captionScale = p[Keys.captionScale] ?: 1f,
                geminiKey = p[Keys.geminiKey] ?: "",
                mistralKey = p[Keys.mistralKey] ?: "",
                readingRtl = p[Keys.readingRtl] ?: true,
                overlayMode = p[Keys.overlayMode] ?: true,
                rectX = p[Keys.rectX] ?: -1, rectY = p[Keys.rectY] ?: -1,
                rectW = p[Keys.rectW] ?: -1, rectH = p[Keys.rectH] ?: -1,
                bubbleX = p[Keys.bubbleX] ?: -1, bubbleY = p[Keys.bubbleY] ?: -1,
                direction = p[Keys.direction]?.let { enumOrNull<ThemeDirection>(it) } ?: ThemeDirection.SUMI,
                mode = p[Keys.mode]?.let { enumOrNull<AppMode>(it) } ?: AppMode.LIGHT,
                accent = p[Keys.accent] ?: "auto",
                textScale = p[Keys.textScale] ?: 1f,
                onboardingDone = p[Keys.onboardingDone] ?: false,
            )
            val next = transform(cur)
            p[Keys.lang] = next.lang
            p[Keys.translateOn] = next.translateOn
            p[Keys.method] = next.method.name
            p[Keys.provider] = next.provider.name
            p[Keys.ttsEnabled] = next.ttsEnabled
            p[Keys.voiceMethod] = next.voiceMethod.name
            p[Keys.voiceProvider] = next.voiceProvider.name
            p[Keys.speed] = next.speed
            p[Keys.timbre] = next.timbre
            p[Keys.captureInterval] = next.captureInterval
            p[Keys.autoplay] = next.autoplay
            p[Keys.captionScale] = next.captionScale
            p[Keys.geminiKey] = next.geminiKey
            p[Keys.mistralKey] = next.mistralKey
            p[Keys.readingRtl] = next.readingRtl
            p[Keys.overlayMode] = next.overlayMode
            p[Keys.rectX] = next.rectX; p[Keys.rectY] = next.rectY
            p[Keys.rectW] = next.rectW; p[Keys.rectH] = next.rectH
            p[Keys.bubbleX] = next.bubbleX; p[Keys.bubbleY] = next.bubbleY
            p[Keys.direction] = next.direction.name
            p[Keys.mode] = next.mode.name
            p[Keys.accent] = next.accent
            p[Keys.textScale] = next.textScale
            p[Keys.onboardingDone] = next.onboardingDone
        }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        runCatching { enumValueOf<T>(name) }.getOrNull()
}
