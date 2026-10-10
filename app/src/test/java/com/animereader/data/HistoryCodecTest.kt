package com.animereader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryCodecTest {

    @Test
    fun `round-trips a full entry`() {
        val entries = listOf(
            HistoryEntry(
                time = "14:03", original = "Bonjour", translated = "Hello",
                langTag = "EN", method = TranslationMethod.ONLINE, providerName = "Gemini",
            ),
        )
        assertEquals(entries, HistoryCodec.decode(HistoryCodec.encode(entries)))
    }

    @Test
    fun `round-trips an entry with null fields`() {
        val entries = listOf(HistoryEntry(time = "09:12", original = "Texte brut"))
        assertEquals(entries, HistoryCodec.decode(HistoryCodec.encode(entries)))
    }

    @Test
    fun `keeps order across multiple entries`() {
        val entries = listOf(
            HistoryEntry(time = "10:00", original = "un"),
            HistoryEntry(time = "10:01", original = "deux"),
        )
        assertEquals(listOf("un", "deux"), HistoryCodec.decode(HistoryCodec.encode(entries)).map { it.original })
    }

    @Test
    fun `garbage decodes to an empty history`() {
        assertEquals(emptyList<HistoryEntry>(), HistoryCodec.decode("pas du json"))
        assertEquals(emptyList<HistoryEntry>(), HistoryCodec.decode(""))
    }
}
