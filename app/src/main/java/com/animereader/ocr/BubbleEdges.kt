package com.animereader.ocr

/** Where a bubble stands relative to the top/bottom edges of the reading rectangle. */
enum class Edge { COMPLETE, CUT_BOTTOM, CUT_TOP, CUT_BOTH }

/**
 * Decides whether a bubble is entirely inside the reading rectangle or cut by its top/bottom
 * edge — the rectangle rarely frames a vertical scroll exactly. Two clues:
 *  1. text margin: a bubble whose first/last line is within ~1 line height of an edge may
 *     continue beyond it (lines of one bubble are less than a line height apart);
 *  2. outline: from the text, flood-fill the bubble's pale interior ("paint bucket"); if the
 *     fill stays enclosed the bubble is whole even when its text hugs the edge, if it runs out
 *     through an edge the bubble is cut there. Borderless lettering (text straight on the page,
 *     frequent in webtoons) leaks everywhere → inconclusive, the text margin decides alone.
 * Pure (no android.*) so it is JVM-unit-testable.
 */
object BubbleEdges {

    enum class Outline { CLOSED, OPEN_TOP, OPEN_BOTTOM, OPEN_BOTH, UNKNOWN }

    /** A luminance grid (0..255), [w] × [h] cells, each cell = [factor] × [factor] pixels. */
    class Grid(val lum: IntArray, val w: Int, val h: Int, val factor: Int)

    /**
     * Downsamples ARGB pixels keeping the DARKEST pixel of each cell: a 1-2 px bubble outline
     * survives the reduction (an average would fade it and let the fill leak through).
     */
    fun minPool(argb: IntArray, width: Int, height: Int, factor: Int): Grid {
        val f = factor.coerceAtLeast(1)
        val gw = (width + f - 1) / f
        val gh = (height + f - 1) / f
        val lum = IntArray(gw * gh) { 255 }
        for (y in 0 until height) {
            val row = y * width
            val g = (y / f) * gw
            for (x in 0 until width) {
                val p = argb[row + x]
                val l = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                val i = g + x / f
                if (l < lum[i]) lum[i] = l
            }
        }
        return Grid(lum, gw, gh, f)
    }

    /** Follows the pale interior around [text] (box in PIXELS) and reports which edges it reaches. */
    fun probe(grid: Grid, text: Box): Outline {
        val w = grid.w; val h = grid.h; val f = grid.factor
        val l = (text.left / f).coerceIn(0, w - 1)
        val r = ((text.right - 1) / f).coerceIn(l, w - 1)
        val t = (text.top / f).coerceIn(0, h - 1)
        val b = ((text.bottom - 1) / f).coerceIn(t, h - 1)

        // The paper behind the glyphs is the bright majority of the text box.
        val inside = ArrayList<Int>((r - l + 1) * (b - t + 1))
        for (y in t..b) for (x in l..r) inside.add(grid.lum[y * w + x])
        inside.sort()
        val paper = inside[inside.size * 3 / 4]
        if (paper < MIN_PAPER) return Outline.UNKNOWN // dark/saturated bubble: no clear interior
        val threshold = paper - INK_DROP

        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0; var tail = 0
        for (y in t..b) for (x in l..r) {
            val i = y * w + x
            if (grid.lum[i] >= threshold && !seen[i]) { seen[i] = true; queue[tail++] = i }
        }
        if (tail == 0) return Outline.UNKNOWN

        var top = false; var bottom = false; var left = false; var right = false
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            if (y == 0) top = true
            if (y == h - 1) bottom = true
            if (x == 0) left = true
            if (x == w - 1) right = true
            if (x > 0) tail = push(grid, seen, queue, tail, i - 1, threshold)
            if (x < w - 1) tail = push(grid, seen, queue, tail, i + 1, threshold)
            if (y > 0) tail = push(grid, seen, queue, tail, i - w, threshold)
            if (y < h - 1) tail = push(grid, seen, queue, tail, i + w, threshold)
        }
        // Reaching both sides means the fill spread over the page background or a full-width
        // gutter (borderless lettering), not inside a bubble.
        if (left && right) return Outline.UNKNOWN
        return when {
            top && bottom -> Outline.OPEN_BOTH
            top -> Outline.OPEN_TOP
            bottom -> Outline.OPEN_BOTTOM
            else -> Outline.CLOSED
        }
    }

    private fun push(grid: Grid, seen: BooleanArray, queue: IntArray, tail: Int, i: Int, threshold: Int): Int {
        if (seen[i] || grid.lum[i] < threshold) return tail
        seen[i] = true
        queue[tail] = i
        return tail + 1
    }

    /**
     * True when a dark line crosses the gap between two stacked text lines ([upper] above
     * [lower], boxes in PIXELS) over most of their common width: the outlines of two bubbles
     * placed one under the other. Their lines may be close enough to look like one bubble —
     * this keeps them apart. Dark/saturated backgrounds are inconclusive (false).
     */
    fun separated(grid: Grid, upper: Box, lower: Box): Boolean {
        val w = grid.w; val h = grid.h; val f = grid.factor
        val l = (maxOf(upper.left, lower.left) / f).coerceIn(0, w - 1)
        val r = ((minOf(upper.right, lower.right) - 1) / f).coerceIn(0, w - 1)
        // One cell of margin on each side: glyph bottoms/tops and accents spill out of the boxes.
        val t = (upper.bottom / f + 1).coerceIn(0, h - 1)
        val b = (lower.top / f - 1).coerceIn(0, h - 1)
        if (r - l < 2 || b < t) return false
        val band = ArrayList<Int>((r - l + 1) * (b - t + 1))
        for (y in t..b) for (x in l..r) band.add(grid.lum[y * w + x])
        band.sort()
        val paper = band[band.size * 3 / 4]
        if (paper < MIN_PAPER) return false
        val threshold = paper - INK_DROP
        val need = ((r - l + 1) * SEPARATOR_SPAN).toInt().coerceAtLeast(2)
        for (y in t..b) {
            var dark = 0
            for (x in l..r) if (grid.lum[y * w + x] < threshold) dark++
            if (dark >= need) return true
        }
        return false
    }

    /** True when [box] comes within ~1.2 line heights of the top / bottom of the rectangle. */
    fun nearTop(box: Box, lineHeight: Int): Boolean = box.top <= margin(lineHeight)
    fun nearBottom(box: Box, lineHeight: Int, regionHeight: Int): Boolean =
        box.bottom >= regionHeight - margin(lineHeight)

    private fun margin(lineHeight: Int) = (lineHeight * 1.2f).toInt().coerceAtLeast(4)

    /**
     * Combines both clues. [outline] is only needed (and only computed by the caller) when the
     * text is near an edge; a bubble whose text keeps its distance from both edges is complete.
     */
    fun classify(box: Box, lineHeight: Int, regionHeight: Int, outline: Outline?): Edge {
        val nearT = nearTop(box, lineHeight)
        val nearB = nearBottom(box, lineHeight, regionHeight)
        if (!nearT && !nearB) return Edge.COMPLETE
        val o = outline ?: Outline.UNKNOWN
        val cutTop = nearT && (o == Outline.OPEN_TOP || o == Outline.OPEN_BOTH || o == Outline.UNKNOWN)
        val cutBottom = nearB && (o == Outline.OPEN_BOTTOM || o == Outline.OPEN_BOTH || o == Outline.UNKNOWN)
        return when {
            cutTop && cutBottom -> Edge.CUT_BOTH
            cutTop -> Edge.CUT_TOP
            cutBottom -> Edge.CUT_BOTTOM
            else -> Edge.COMPLETE
        }
    }

    private const val MIN_PAPER = 150
    private const val INK_DROP = 45
    private const val SEPARATOR_SPAN = 0.6
}
