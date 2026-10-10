package com.animereader.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPromptsTest {

    @Test
    fun `batch prompt numbers the images and asks for the scan clean-up`() {
        val p = VisionPrompts.batch(3, "français", "")
        assertTrue(p.contains("exactement 3 éléments"))
        assertTrue(p.contains("\"n\":1"))
        assertTrue(p.contains("casse normale"))
        assertTrue(p.contains("accents"))
        assertTrue(p.contains("déjà en français"))
        assertFalse(p.contains("Contexte"))
    }

    @Test
    fun `dialogue context is injected when present`() {
        val p = VisionPrompts.single("anglais", "Salut => Hi")
        assertTrue(p.startsWith("Contexte"))
        assertTrue(p.contains("Salut => Hi"))
    }
}
