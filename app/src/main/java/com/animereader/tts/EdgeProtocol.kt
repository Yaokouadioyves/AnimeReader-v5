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

    /**
     * Endpoints, tried in order. Since 2026-10-10 Microsoft refuses the historical
     * speech.platform.bing.com socket (HTTP 403 for everyone) and Edge itself moved to
     * api.msedgeservices.com, which still accepts the v1 Sec-MS-GEC token. The legacy host stays
     * as a second chance in case Microsoft flips back.
     */
    val HOSTS = listOf(
        "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1?Ocp-Apim-Subscription-Key=$TRUSTED_TOKEN",
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_TOKEN",
    )

    /** Full socket URL for [host] (index in [HOSTS]) with a fresh connection id and auth token. */
    fun socketUrl(host: Int = 0, nowMillis: Long = System.currentTimeMillis()): String =
        HOSTS[host.mod(HOSTS.size)] +
            "&ConnectionId=" + newId() +
            "&Sec-MS-GEC=" + secMsGec(nowMillis) +
            "&Sec-MS-GEC-Version=" + SEC_VERSION

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

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

    fun ssmlMessage(
        text: String, voice: String, rateStr: String,
        timestamp: String = timestamp(), requestId: String = newId(),
    ): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${langOf(voice)}'>" +
            "<voice name='$voice'><prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>$escaped</prosody></voice></speak>"
        return "X-RequestId:$requestId\r\n" +
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

    /**
     * Audio part of a binary frame: [2-byte big-endian header length][headers][MP3]. The audio
     * starts *after* the 2 length bytes + headers — reading from `headerLen` instead used to
     * leave the headers' trailing CRLF inside every chunk, which corrupted ~1 MP3 frame per
     * chunk (audible clicks / micro-cuts). Returns -1 for a malformed frame.
     */
    fun audioOffset(frame: ByteArray): Int {
        if (frame.size < 2) return -1
        val headerLen = ((frame[0].toInt() and 0xff) shl 8) or (frame[1].toInt() and 0xff)
        val start = 2 + headerLen
        return if (start <= frame.size) start else -1
    }

    /** Header block of a binary frame (for its X-RequestId), or "" if malformed. */
    fun frameHeaders(frame: ByteArray): String {
        val start = audioOffset(frame)
        return if (start < 0) "" else String(frame, 2, start - 2, Charsets.US_ASCII)
    }

    /** The X-RequestId value of a frame's header block ("X-RequestId:abc" CRLF "Path:audio" -> "abc"). */
    fun requestIdOf(headers: String): String? =
        headers.lineSequence().firstOrNull { it.startsWith("X-RequestId:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    /**
     * Server minus device clock, from an HTTP Date header — the token is time-based, so a phone
     * clock a few minutes off gets 403 until we correct for it. Null if the header is unusable.
     */
    fun clockSkewMs(dateHeader: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        if (dateHeader.isNullOrBlank()) return null
        return runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            fmt.parse(dateHeader)!!.time - nowMillis
        }.getOrNull()
    }

    /**
     * Map language + timbre to an Edge neural voice. Féminine / Masculine use the newer
     * "Multilingual" voices where the language has them (more natural intonation, a livelier
     * pace); Neutre keeps the classic voice.
     */
    fun voiceFor(langTag: String, timbre: String): String {
        val t = when (timbre) { "Masculine" -> 1; "Neutre" -> 2; else -> 0 } // 0 = féminine
        val v = when (langTag.lowercase().substringBefore('-')) {
            "fr" -> listOf("fr-FR-VivienneMultilingualNeural", "fr-FR-RemyMultilingualNeural", "fr-FR-DeniseNeural")
            "en" -> listOf("en-US-AvaMultilingualNeural", "en-US-AndrewMultilingualNeural", "en-US-EmmaMultilingualNeural")
            "es" -> listOf("es-ES-ElviraNeural", "es-ES-AlvaroNeural", "es-ES-XimenaNeural")
            "ja" -> listOf("ja-JP-NanamiNeural", "ja-JP-KeitaNeural", "ja-JP-NanamiNeural")
            "pt" -> listOf("pt-BR-ThalitaMultilingualNeural", "pt-BR-AntonioNeural", "pt-BR-FranciscaNeural")
            "de" -> listOf("de-DE-SeraphinaMultilingualNeural", "de-DE-FlorianMultilingualNeural", "de-DE-KatjaNeural")
            "it" -> listOf("it-IT-ElsaNeural", "it-IT-GiuseppeMultilingualNeural", "it-IT-IsabellaNeural")
            "ko" -> listOf("ko-KR-SunHiNeural", "ko-KR-HyunsuMultilingualNeural", "ko-KR-SunHiNeural")
            else -> listOf("en-US-AvaMultilingualNeural", "en-US-AndrewMultilingualNeural", "en-US-EmmaMultilingualNeural")
        }
        return v[t]
    }
}
