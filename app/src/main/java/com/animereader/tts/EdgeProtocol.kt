package com.animereader.tts

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Pure (Android-free) encoder for the Microsoft Edge "Read Aloud" TTS WebSocket protocol:
 * the Sec-MS-GEC auth token and the config / SSML frames. Kept separate from [EdgeVoice] so
 * the wire format and the token math can be unit-tested on the JVM without a device.
 */
object EdgeProtocol {

    const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val SEC_VERSION = "1-143.0.3650.75"
    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3" // endpoint only emits MP3
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_TOKEN"

    /** Full socket URL with a fresh connection id and a current auth token. */
    fun socketUrl(nowMillis: Long = System.currentTimeMillis()): String =
        WSS_URL +
            "&ConnectionId=" + UUID.randomUUID().toString().replace("-", "") +
            "&Sec-MS-GEC=" + secMsGec(nowMillis) +
            "&Sec-MS-GEC-Version=" + SEC_VERSION

    /**
     * Sec-MS-GEC token: SHA-256 (uppercase hex) of the Windows file-time — rounded *down* to the
     * previous 5-minute boundary, expressed in 100-ns ticks — concatenated with the trusted token.
     * Uses exact integer math (no floating point) so it matches the reference implementation.
     */
    fun secMsGec(nowMillis: Long = System.currentTimeMillis()): String {
        val winSeconds = (nowMillis / 1000L) + 11644473600L
        val rounded = winSeconds - (winSeconds % 300L)
        val ticks = rounded * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((ticks.toString() + TRUSTED_TOKEN).toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun timestamp(date: Date = Date()): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(date)
    }

    fun configMessage(timestamp: String = timestamp()): String =
        "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}"

    fun ssmlMessage(text: String, voice: String, rateStr: String, timestamp: String = timestamp()): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${langOf(voice)}'>" +
            "<voice name='$voice'><prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>$escaped</prosody></voice></speak>"
        return "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp}Z\r\n" +
            "Path:ssml\r\n\r\n$ssml"
    }

    fun ratePct(rate: Float): String {
        val pct = ((rate - 1f) * 100f).toInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    /** "fr-FR-DeniseNeural" -> "fr-FR". */
    fun langOf(voice: String): String = voice.substringBeforeLast('-')

    /** Map target language + timbre to an Edge neural voice. */
    fun voiceFor(langTag: String, timbre: String): String {
        val male = timbre == "Masculine"
        return when (langTag.lowercase().substringBefore('-')) {
            "fr" -> if (male) "fr-FR-HenriNeural" else "fr-FR-DeniseNeural"
            "en" -> if (male) "en-US-GuyNeural" else "en-US-AriaNeural"
            "es" -> if (male) "es-ES-AlvaroNeural" else "es-ES-ElviraNeural"
            "ja" -> if (male) "ja-JP-KeitaNeural" else "ja-JP-NanamiNeural"
            "pt" -> if (male) "pt-BR-AntonioNeural" else "pt-BR-FranciscaNeural"
            "de" -> if (male) "de-DE-ConradNeural" else "de-DE-KatjaNeural"
            "it" -> if (male) "it-IT-DiegoNeural" else "it-IT-ElsaNeural"
            "ko" -> if (male) "ko-KR-InJoonNeural" else "ko-KR-SunHiNeural"
            else -> if (male) "en-US-GuyNeural" else "en-US-AriaNeural"
        }
    }
}
