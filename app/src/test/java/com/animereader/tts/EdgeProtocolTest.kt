package com.animereader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Edge "Read Aloud" wire format. The reference [secMsGec] values were produced by an
 * independent Python implementation that successfully synthesized audio against the live endpoint,
 * so a mismatch here means the auth token (and thus the voice) is broken.
 */
class EdgeProtocolTest {

    @Test fun secMsGec_matchesReference() {
        assertEquals(
            "42301B335578FEFDAE2637DED1ABD614505D432559EC08032B82048483726AFF",
            EdgeProtocol.secMsGec(1_700_000_000_000L),
        )
    }

    @Test fun secMsGec_is64UppercaseHex() {
        val t = EdgeProtocol.secMsGec(1_700_000_000_000L)
        assertEquals(64, t.length)
        assertTrue(t.all { it in "0123456789ABCDEF" })
    }

    @Test fun secMsGec_stableWithin5MinWindow_changesAcrossIt() {
        val base = 1_700_000_000_000L
        assertEquals(EdgeProtocol.secMsGec(base), EdgeProtocol.secMsGec(base + 1_000L))
        assertNotEquals(EdgeProtocol.secMsGec(base), EdgeProtocol.secMsGec(base + 300_000L))
    }

    @Test fun ratePct_signedPercent() {
        assertEquals("+0%", EdgeProtocol.ratePct(1f))
        assertEquals("+50%", EdgeProtocol.ratePct(1.5f))
        // Float truncation toward zero: (0.8f-1f)*100f = -19.999… → -19 (acceptable for a TTS rate).
        assertEquals("-19%", EdgeProtocol.ratePct(0.8f))
        assertEquals("-50%", EdgeProtocol.ratePct(0.5f))
    }

    @Test fun langOf_stripsVoiceName() {
        assertEquals("fr-FR", EdgeProtocol.langOf("fr-FR-DeniseNeural"))
        assertEquals("fr-FR", EdgeProtocol.langOf("fr-FR-VivienneMultilingualNeural"))
        assertEquals("ja-JP", EdgeProtocol.langOf("ja-JP-NanamiNeural"))
    }

    @Test fun voiceFor_mapsLanguageAndTimbre() {
        assertEquals("fr-FR-VivienneMultilingualNeural", EdgeProtocol.voiceFor("fr-FR", "Féminine"))
        assertEquals("fr-FR-RemyMultilingualNeural", EdgeProtocol.voiceFor("fr", "Masculine"))
        assertEquals("fr-FR-DeniseNeural", EdgeProtocol.voiceFor("fr", "Neutre"))
        assertEquals("en-US-AvaMultilingualNeural", EdgeProtocol.voiceFor("en", "Féminine"))
        assertEquals("de-DE-FlorianMultilingualNeural", EdgeProtocol.voiceFor("de", "Masculine"))
        assertEquals("it-IT-ElsaNeural", EdgeProtocol.voiceFor("it", "Féminine"))
        assertEquals("ko-KR-SunHiNeural", EdgeProtocol.voiceFor("ko-KR", "Féminine"))
        // Unknown language falls back to English.
        assertEquals("en-US-AndrewMultilingualNeural", EdgeProtocol.voiceFor("xx", "Masculine"))
    }

    @Test fun socketUrl_triesTheNewHostFirst() {
        val first = EdgeProtocol.socketUrl(0, 1_700_000_000_000L)
        assertTrue(first.startsWith("wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1?Ocp-Apim-Subscription-Key="))
        assertTrue(first.contains("&Sec-MS-GEC=" + EdgeProtocol.secMsGec(1_700_000_000_000L)))
        assertTrue(EdgeProtocol.socketUrl(1).startsWith("wss://speech.platform.bing.com/"))
        assertTrue(EdgeProtocol.socketUrl(2).startsWith("wss://api.msedgeservices.com/")) // wraps around
    }

    private fun frame(headers: String, audio: ByteArray): ByteArray {
        val h = headers.toByteArray(Charsets.US_ASCII)
        return byteArrayOf((h.size shr 8).toByte(), h.size.toByte()) + h + audio
    }

    @Test fun audioOffset_skipsLengthPrefixAndHeaders() {
        // Real frame shape: headers end with CRLF, which used to leak into the MP3 stream.
        val headers = "X-RequestId:abc\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n"
        val mp3 = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 1, 2, 3)
        val f = frame(headers, mp3)
        val start = EdgeProtocol.audioOffset(f)
        assertEquals(mp3.toList(), f.copyOfRange(start, f.size).toList())
        assertEquals("abc", EdgeProtocol.requestIdOf(EdgeProtocol.frameHeaders(f)))
    }

    @Test fun audioOffset_rejectsMalformedFrames() {
        assertEquals(-1, EdgeProtocol.audioOffset(byteArrayOf(0)))
        assertEquals(-1, EdgeProtocol.audioOffset(byteArrayOf(0, 50, 1, 2)))
        assertEquals("", EdgeProtocol.frameHeaders(byteArrayOf(0, 50, 1, 2)))
    }

    @Test fun requestIdOf_readsTextMessages() {
        val turnEnd = "X-RequestId:c9375\r\nContent-Type:application/json; charset=utf-8\r\nPath:turn.end\r\n\r\n{}"
        assertEquals("c9375", EdgeProtocol.requestIdOf(turnEnd))
        assertEquals(null, EdgeProtocol.requestIdOf("Path:turn.end"))
    }

    @Test fun clockSkewMs_fromHttpDate() {
        val now = 1_700_000_000_000L // Tue, 14 Nov 2023 22:13:20 GMT
        assertEquals(0L, EdgeProtocol.clockSkewMs("Tue, 14 Nov 2023 22:13:20 GMT", now))
        assertEquals(300_000L, EdgeProtocol.clockSkewMs("Tue, 14 Nov 2023 22:18:20 GMT", now))
        assertEquals(null, EdgeProtocol.clockSkewMs("garbage", now))
        assertEquals(null, EdgeProtocol.clockSkewMs(null, now))
    }

    @Test fun configMessage_requestsMp3() {
        assertTrue(EdgeProtocol.configMessage().contains("audio-24khz-48kbitrate-mono-mp3"))
        assertTrue(EdgeProtocol.configMessage().contains("Path:speech.config"))
    }

    @Test fun ssmlMessage_escapesXmlAndCarriesVoice() {
        val m = EdgeProtocol.ssmlMessage("a & b < c > d", "fr-FR-DeniseNeural", "+0%", requestId = "rid42")
        assertTrue(m.startsWith("X-RequestId:rid42\r\n"))
        assertTrue(m.contains("a &amp; b &lt; c &gt; d"))
        assertTrue(m.contains("fr-FR-DeniseNeural"))
        assertTrue(m.contains("Path:ssml"))
    }
}
