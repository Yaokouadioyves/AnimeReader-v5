package com.animereader.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleClustersTest {

    private fun block(text: String, l: Int, t: Int, r: Int, b: Int) = OcrBlock(text, Box(l, t, r, b))

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

    // ── Line clustering for Latin scans ──

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = OcrBlock(text, Box(l, t, r, b))

    @Test
    fun `centred lines of one bubble join, hyphenated words glued back`() {
        val bubbles = BubbleClusters.clusterLines(
            listOf(
                line("IL FAUT ATTEN-", 100, 100, 260, 124),
                line("DRE ICI,", 130, 130, 230, 154),     // narrower, centred under the first
                line("D'ACCORD ?", 125, 160, 235, 184),
            ),
        )
        assertEquals(1, bubbles.size)
        assertEquals("IL FAUT ATTENDRE ICI, D'ACCORD ?", bubbles[0].text)
        assertEquals(Box(100, 100, 260, 184), bubbles[0].box)
    }

    @Test
    fun `two bubbles side by side stay apart`() {
        val bubbles = BubbleClusters.clusterLines(
            listOf(
                line("QUI ES-TU ?", 20, 100, 160, 124),
                line("TOI D'ABORD.", 400, 104, 560, 128),   // same height band, far to the right
            ),
        )
        assertEquals(2, bubbles.size)
    }

    @Test
    fun `a bubble stacked just under another but offset sideways stays apart`() {
        val bubbles = BubbleClusters.clusterLines(
            listOf(
                line("PREMIÈRE BULLE", 20, 100, 200, 124),
                line("SECONDE", 170, 132, 330, 156),       // small overlap, no alignment
            ),
        )
        assertEquals(2, bubbles.size)
    }

    @Test
    fun `different letter sizes (sound effect, narration) do not merge`() {
        val bubbles = BubbleClusters.clusterLines(
            listOf(
                line("ON Y VA !", 100, 100, 220, 124),
                line("BAM", 90, 128, 260, 200),            // 3× taller lettering right below
            ),
        )
        assertEquals(2, bubbles.size)
    }

    @Test
    fun `one text line split in two pieces by the OCR is rejoined in order`() {
        val bubbles = BubbleClusters.clusterLines(
            listOf(
                line("SAIS PAS", 190, 100, 290, 124),
                line("JE NE", 100, 101, 170, 125),
            ),
        )
        assertEquals(1, bubbles.size)
        assertEquals("JE NE SAIS PAS", bubbles[0].text)
    }

    @Test
    fun `bubbles a few pixels apart vertically share a reading row`() {
        // Centres 99 and 101 used to fall in different integer bands and swap the order.
        val right = Bubble("droite", Box(300, 74, 400, 124))
        val left = Bubble("gauche", Box(10, 76, 110, 126))
        assertEquals(listOf("droite", "gauche"), BubbleClusters.readingOrder(listOf(left, right), rtl = true).map { it.text })
        assertEquals(listOf("gauche", "droite"), BubbleClusters.readingOrder(listOf(right, left), rtl = false).map { it.text })
    }

    @Test fun stackedLinesStayApartWhenTheVetoSaysTwoBubbles() {
        val lines = listOf(
            line("PREMIÈRE BULLE.", 70, 60, 230, 72),
            line("DEUXIÈME BULLE.", 70, 80, 230, 92), // 8 px under: close enough to be grouped
        )
        assertEquals(1, BubbleClusters.clusterLines(lines).size)
        val apart = BubbleClusters.clusterLines(lines, apart = { upper, lower -> upper.top == 60 && lower.top == 80 })
        assertEquals(2, apart.size)
    }
}
