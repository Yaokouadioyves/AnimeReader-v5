package com.animereader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    val voiceMethod: VoiceMethod = VoiceMethod.OFFLINE,
    val voiceProvider: VoiceProviderId = VoiceProviderId.ELEVENLABS,
    val speed: Float = 1f,
    val timbre: String = "Féminine",
    val captureInterval: Float = 1f,
    val autoplay: Boolean = true,
    val captionScale: Float = 1f,   // subtitle text size (0.85 / 1.0 / 1.2)
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
                voiceMethod = p[Keys.voiceMethod]?.let { enumOrNull<VoiceMethod>(it) } ?: VoiceMethod.OFFLINE,
                voiceProvider = p[Keys.voiceProvider]?.let { enumOrNull<VoiceProviderId>(it) } ?: VoiceProviderId.ELEVENLABS,
                speed = p[Keys.speed] ?: 1f,
                timbre = p[Keys.timbre] ?: "Féminine",
                captureInterval = p[Keys.captureInterval] ?: 1f,
                autoplay = p[Keys.autoplay] ?: true,
                captionScale = p[Keys.captionScale] ?: 1f,
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
