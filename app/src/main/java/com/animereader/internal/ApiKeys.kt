package com.animereader.internal

import com.animereader.BuildConfig
import com.animereader.data.TextProvider
import com.animereader.data.VoiceProviderId

/** Online-provider API keys, sourced from BuildConfig (local.properties). Blank = unconfigured. */
class ApiKeys(
    val openai: String = BuildConfig.OPENAI_API_KEY,
    val deepl: String = BuildConfig.DEEPL_API_KEY,
    val google: String = BuildConfig.GOOGLE_API_KEY,
    val elevenlabs: String = BuildConfig.ELEVENLABS_API_KEY,
) {
    fun forText(p: TextProvider): String = when (p) {
        TextProvider.OPENAI -> openai
        TextProvider.DEEPL -> deepl
        TextProvider.GOOGLE -> google
    }

    fun forVoice(p: VoiceProviderId): String = when (p) {
        VoiceProviderId.ELEVENLABS -> elevenlabs
        VoiceProviderId.GOOGLE -> google
        VoiceProviderId.OPENAI -> openai
    }
}
