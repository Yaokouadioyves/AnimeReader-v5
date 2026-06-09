package com.animereader.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the read-aloud de-dup so a scrolling/jittering page never re-reads the same bubble. */
class TextDedupTest {

    @Test fun normalize_lowercasesAndStripsPunctuationKeepsCjk() {
        assertEquals("helloworld", TextDedup.normalize("Hello, World!"))
        assertEquals("こんにちは", TextDedup.normalize("こんにちは…"))
        assertEquals("abc", TextDedup.normalize("  A b\tc  "))
    }

    @Test fun bigramSimilarity_bounds() {
        assertEquals(1.0, TextDedup.bigramSimilarity("manga", "manga"), 1e-9)
        assertEquals(0.0, TextDedup.bigramSimilarity("abcd", "wxyz"), 1e-9)
        assertTrue(TextDedup.bigramSimilarity("nuit", "nuir") in 0.0..1.0)
    }

    @Test fun isDuplicate_exactMatch() {
        assertTrue(TextDedup.isDuplicate("bonjour", listOf("salut", "bonjour")))
    }

    @Test fun isDuplicate_scrollContinuationByContainment() {
        // The bubble scrolled in; the new read fully contains a recent line (≥60%).
        assertTrue(TextDedup.isDuplicate("jecroisquilarrive", listOf("croisquilarrive")))
    }

    @Test fun isDuplicate_ocrJitterBySimilarity() {
        // One-character OCR slip on the same line (similarity ~0.95, no containment).
        assertTrue(TextDedup.isDuplicate("attendsunmomentjarive", listOf("attendsunmomentjarrive")))
    }

    @Test fun isDuplicate_distinctLinesAreNotDuplicates() {
        assertFalse(TextDedup.isDuplicate("ledragonattaque", listOf("ilfautpartirmaintenant")))
        assertFalse(TextDedup.isDuplicate("bonjour", emptyList()))
    }
}
