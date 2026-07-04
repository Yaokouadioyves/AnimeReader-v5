package com.animereader.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleClustersTest {

    private fun block(text: String, l: Int, t: Int, r: Int, b: Int) = OcrBlock(text, Box(l, t, r, b))

    @Test
    fun `nearby blocks merge into one bubble with union box`() {
        val bubbles = BubbleClusters.cluster(
            listOf(
                block("Bonjour", 10, 10, 110, 40),
                block("le monde", 12, 46, 100, 76), // 6px below → within gap
            ),
            gap = 12,
        )
        assertEquals(1, bubbles.size)
        assertEquals("Bonjour le monde", bubbles[0].text)
        assertEquals(Box(10, 10, 110, 76), bubbles[0].box)
    }

    @Test
    fun `distant blocks stay separate bubbles`() {
        val bubbles = BubbleClusters.cluster(
            listOf(
                block("A", 0, 0, 50, 30),
                block("B", 400, 500, 460, 530),
            ),
            gap = 12,
        )
        assertEquals(2, bubbles.size)
    }

    @Test
    fun `blank blocks are ignored`() {
        val bubbles = BubbleClusters.cluster(
            listOf(block("  ", 0, 0, 50, 30), block("Texte", 100, 100, 200, 130)),
            gap = 12,
        )
        assertEquals(1, bubbles.size)
        assertEquals("Texte", bubbles[0].text)
    }

    @Test
    fun `manga order reads right bubble before left on the same row`() {
        val right = Bubble("droite", Box(300, 10, 400, 60))
        val left = Bubble("gauche", Box(10, 12, 110, 62))
        assertEquals(listOf("droite", "gauche"), BubbleClusters.readingOrder(listOf(left, right), rtl = true).map { it.text })
    }

    @Test
    fun `manga order reads top row before bottom row`() {
        val top = Bubble("haut", Box(10, 0, 110, 50))
        val bottom = Bubble("bas", Box(300, 400, 400, 450))
        assertEquals(listOf("haut", "bas"), BubbleClusters.readingOrder(listOf(bottom, top), rtl = true).map { it.text })
    }

    @Test
    fun `western order reads left before right`() {
        val right = Bubble("right", Box(300, 10, 400, 60))
        val left = Bubble("left", Box(10, 12, 110, 62))
        assertEquals(listOf("left", "right"), BubbleClusters.readingOrder(listOf(right, left), rtl = false).map { it.text })
    }
}
