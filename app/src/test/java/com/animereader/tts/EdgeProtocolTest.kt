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
        assertEquals("ja-JP", EdgeProtocol.langOf("ja-JP-NanamiNeural"))
    }

    @Test fun voiceFor_mapsLanguageAndTimbre() {
        assertEquals("fr-FR-HenriNeural", EdgeProtocol.voiceFor("fr", "Masculine"))
        assertEquals("fr-FR-DeniseNeural", EdgeProtocol.voiceFor("fr-FR", "Feminine"))
        assertEquals("en-US-AriaNeural", EdgeProtocol.voiceFor("en", "Feminine"))
        // Unknown language falls back to English.
        assertEquals("en-US-GuyNeural", EdgeProtocol.voiceFor("de", "Masculine"))
    }

    @Test fun configMessage_requestsMp3() {
        assertTrue(EdgeProtocol.configMessage().contains("audio-24khz-48kbitrate-mono-mp3"))
        assertTrue(EdgeProtocol.configMessage().contains("Path:speech.config"))
    }

    @Test fun ssmlMessage_escapesXmlAndCarriesVoice() {
        val m = EdgeProtocol.ssmlMessage("a & b < c > d", "fr-FR-DeniseNeural", "+0%")
        assertTrue(m.contains("a &amp; b &lt; c &gt; d"))
        assertTrue(m.contains("fr-FR-DeniseNeural"))
        assertTrue(m.contains("Path:ssml"))
    }
}
