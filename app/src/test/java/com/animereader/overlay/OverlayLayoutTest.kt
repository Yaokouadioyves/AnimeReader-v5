package com.animereader.overlay

import com.animereader.ocr.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutTest {

    /** Fake text measurer: ~0.5 px of width per px of size per character, 1.2 × size per line. */
    private fun measurer(text: String): (Float, Int) -> Int = { size, width ->
        val perLine = (width / (size * 0.5f)).toInt().coerceAtLeast(1)
        val lines = (text.length + perLine - 1) / perLine
        (lines * size * 1.2f).toInt()
    }

    @Test fun shortTranslationKeepsTheOriginalLetterSizeAndCoversTheText() {
        val text = Box(100, 200, 300, 260) // 2 lines of 30 px
        val p = OverlayLayout.place(text, 30, 1080, 1600, 20f, measurer("Salut !"))
        assertEquals(30 * 0.95f, p.textSizePx, 0.01f)
        assertTrue(p.box.left <= text.left && p.box.top <= text.top)
        assertTrue(p.box.right >= text.right && p.box.bottom >= text.bottom)
    }

    @Test fun longerTranslationShrinksThenWidensBeforeGrowing() {
        val text = Box(100, 200, 300, 260)
        val long = "Je ne vais certainement pas te laisser partir comme ça, tu m'entends ?"
        val p = OverlayLayout.place(text, 30, 1080, 1600, 12f, measurer(long))
        assertTrue(p.textSizePx < 30f)
        val m = measurer(long)(p.textSizePx, p.box.width - 2 * p.inset)
        assertTrue("fits", m <= p.box.height - 2 * p.inset)
    }

    @Test fun tooLongAtMinimumSizeGrowsTheBoxVerticallyInsideTheRegion() {
        val text = Box(500, 10, 560, 40)
        val p = OverlayLayout.place(text, 30, 1080, 400, 14f, measurer("x".repeat(400)))
        assertEquals(14f, p.textSizePx, 0.01f)
        assertTrue(p.box.top >= 0 && p.box.bottom <= 400)
        assertTrue(p.box.height > text.height + 2 * OverlayLayout.padFor(30))
    }

    @Test fun widenedBoxStaysInsideTheRegion() {
        val text = Box(0, 100, 200, 160) // against the left edge
        val p = OverlayLayout.place(text, 30, 220, 400, 29f, measurer("y".repeat(60)))
        assertTrue(p.box.left >= 0 && p.box.right <= 220)
    }

    @Test fun paperColourIsTheBubbleBackgroundNotTheInk() {
        val w = 40; val h = 20
        val px = IntArray(w * h) { 0xFFFAF8F0.toInt() }
        for (y in 8..11) for (x in 5..34) px[y * w + x] = 0xFF101010.toInt() // a line of lettering
        val paper = OverlayLayout.paperColor(px, w, h, Box(0, 0, w, h), step = 1)
        assertEquals(0xFFFAF8F0.toInt(), paper)
        assertEquals(0xFF141414.toInt(), OverlayLayout.inkFor(paper))
    }

    @Test fun darkBubbleGetsWhiteInk() {
        val px = IntArray(100) { 0xFF0A0A0A.toInt() }
        val paper = OverlayLayout.paperColor(px, 10, 10, Box(0, 0, 10, 10), step = 1)
        assertEquals(0xFFFFFFFF.toInt(), OverlayLayout.inkFor(paper))
    }

    @Test fun gridMaskFlagsOnlyTheCellsUnderTheTranslations() {
        // 320×320 region, 32×32 grid → 10 px cells; a box over cells 1..2 × 3..4.
        val m = OverlayLayout.gridMask(listOf(Box(10, 30, 30, 50)), 320, 320, 32)
        val on = m.indices.filter { m[it] }.map { it / 32 to it % 32 }
        assertEquals(listOf(3 to 1, 3 to 2, 4 to 1, 4 to 2), on)
    }
}
