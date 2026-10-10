package com.animereader.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleEdgesTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val grey = 0xFF808080.toInt()

    /** A synthetic capture: [bg] page, optional black-outlined white bubble, black "glyphs". */
    private class Canvas(val w: Int, val h: Int, bg: Int) {
        val px = IntArray(w * h) { bg }
        fun fill(l: Int, t: Int, r: Int, b: Int, c: Int) {
            for (y in maxOf(0, t) until minOf(h, b)) for (x in maxOf(0, l) until minOf(w, r)) px[y * w + x] = c
        }
        /** White ellipse-ish bubble (rectangle) with a 2-px outline, clipped by the canvas. */
        fun bubble(l: Int, t: Int, r: Int, b: Int, ink: Int, paper: Int) {
            fill(l, t, r, b, ink)
            fill(l + 2, t + 2, r - 2, b - 2, paper)
        }
        /** Text lines as thin dark strokes with gaps (letters), inside [l, r) from top [t]. */
        fun text(l: Int, t: Int, r: Int, lines: Int, lineH: Int) {
            for (k in 0 until lines) {
                val y = t + k * lineH * 3 / 2
                var x = l
                while (x < r) { fill(x, y, x + 2, y + lineH, 0xFF000000.toInt()); x += 6 }
            }
        }
        fun grid(factor: Int) = BubbleEdges.minPool(px, w, h, factor)
    }

    @Test fun closedBubbleHuggingTheBottomIsWhole() {
        val c = Canvas(300, 400, white)              // white page around a closed bubble
        c.bubble(60, 300, 240, 398, black, white)   // outline 2 px above the bottom edge
        c.text(90, 330, 210, 3, 12)
        val box = Box(90, 330, 210, 390)             // 10 px from the edge: "near" it
        val o = BubbleEdges.probe(c.grid(3), box)
        assertEquals(BubbleEdges.Outline.CLOSED, o)
        assertEquals(Edge.COMPLETE, BubbleEdges.classify(box, 12, 400, o))
    }

    @Test fun bubbleRunningOutOfTheBottomIsCut() {
        val c = Canvas(300, 400, grey)
        c.bubble(60, 300, 240, 460, black, white)   // continues below the rectangle
        c.text(90, 330, 210, 4, 12)
        val box = Box(90, 330, 210, 396)
        val o = BubbleEdges.probe(c.grid(3), box)
        assertEquals(BubbleEdges.Outline.OPEN_BOTTOM, o)
        assertEquals(Edge.CUT_BOTTOM, BubbleEdges.classify(box, 12, 400, o))
    }

    @Test fun bubbleRunningOutOfTheTopIsCutAtTheTop() {
        val c = Canvas(300, 400, grey)
        c.bubble(60, -50, 240, 80, black, white)
        c.text(90, 2, 210, 3, 12)
        val box = Box(90, 2, 210, 56)
        val o = BubbleEdges.probe(c.grid(3), box)
        assertEquals(BubbleEdges.Outline.OPEN_TOP, o)
        assertEquals(Edge.CUT_TOP, BubbleEdges.classify(box, 12, 400, o))
    }

    @Test fun oneThinOutlineSurvivesTheDownscale() {
        val c = Canvas(300, 400, white)
        c.fill(60, 300, 240, 398, black)
        c.fill(61, 301, 239, 397, white)             // 1-px outline only
        c.text(90, 330, 210, 3, 12)
        assertEquals(BubbleEdges.Outline.CLOSED, BubbleEdges.probe(c.grid(4), Box(90, 330, 210, 384)))
    }

    @Test fun borderlessTextOnWhitePageIsInconclusiveAndTheMarginDecides() {
        val c = Canvas(300, 400, white)               // webtoon: text straight on the white strip
        c.text(90, 362, 210, 2, 12)
        val box = Box(90, 362, 210, 392)
        val o = BubbleEdges.probe(c.grid(3), box)
        assertEquals(BubbleEdges.Outline.UNKNOWN, o)
        assertEquals(Edge.CUT_BOTTOM, BubbleEdges.classify(box, 12, 400, o)) // 20 px < 1.2 lines
    }

    @Test fun textFarFromBothEdgesIsCompleteWithoutLookingAtTheImage() {
        assertEquals(Edge.COMPLETE, BubbleEdges.classify(Box(90, 100, 210, 160), 12, 400, null))
    }

    @Test fun tallBubbleCutAtBothEdges() {
        val c = Canvas(300, 400, grey)
        c.bubble(40, -30, 260, 440, black, white)
        c.text(70, 4, 230, 16, 15)
        val box = Box(70, 4, 230, 392)
        val o = BubbleEdges.probe(c.grid(3), box)
        assertEquals(BubbleEdges.Outline.OPEN_BOTH, o)
        assertEquals(Edge.CUT_BOTH, BubbleEdges.classify(box, 15, 400, o))
    }

    @Test fun darkBubbleIsInconclusive() {
        val c = Canvas(300, 400, grey)
        c.bubble(60, 300, 240, 398, white, 0xFF202020.toInt()) // white text style on black bubble
        assertEquals(BubbleEdges.Outline.UNKNOWN, BubbleEdges.probe(c.grid(3), Box(90, 330, 210, 384)))
    }

    @Test fun minPoolKeepsTheDarkestPixel() {
        val px = intArrayOf(white, white, white, black)
        val g = BubbleEdges.minPool(px, 2, 2, 2)
        assertEquals(1, g.w); assertEquals(1, g.h)
        assertEquals(0, g.lum[0])
    }
}
