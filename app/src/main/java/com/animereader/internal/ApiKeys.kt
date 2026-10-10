package com.animereader.internal

import com.animereader.BuildConfig
import com.animereader.data.TextProvider

/** Online-provider API keys, sourced from BuildConfig (local.properties). Blank = unconfigured. */
class ApiKeys(
    val openai: String = BuildConfig.OPENAI_API_KEY,
    val deepl: String = BuildConfig.DEEPL_API_KEY,
    val google: String = BuildConfig.GOOGLE_API_KEY,
) {
    fun forText(p: TextProvider): String = when (p) {
        TextProvider.OPENAI -> openai
        TextProvider.DEEPL -> deepl
        TextProvider.GOOGLE -> google
    }
}
