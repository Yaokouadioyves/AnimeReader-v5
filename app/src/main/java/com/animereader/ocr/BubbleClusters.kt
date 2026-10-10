package com.animereader.ocr

/** Axis-aligned box in region pixels. Android-free so the clustering is JVM-unit-testable. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val cx: Int get() = (left + right) / 2
    val cy: Int get() = (top + bottom) / 2
}

/** One OCR text block with its position inside the captured region. */
data class OcrBlock(val text: String, val box: Box)

/**
 * A detected speech bubble: the lines' merged text, their bounding box and the median height of
 * a line (the unit used to judge whether the bubble is cut by an edge of the rectangle).
 */
data class Bubble(val text: String, val box: Box, val lineHeight: Int = box.height)

/**
 * Groups OCR text lines into speech-bubble clusters and orders them for reading. Detection works on the *positions* of text, not its glyphs, so unusual manga
 * fonts don't hurt it — even when ML Kit misreads the letters it still boxes them.
 */
object BubbleClusters {

    /**
     * Groups horizontal text LINES (Latin scans) into bubbles. Unlike [cluster]'s plain
     * proximity test, two lines only join when they look like one piece of lettering:
     *  - similar letter height (≤ 1.7× — a narration box or a sound effect next to a bubble
     *    uses another size),
     *  - stacked with a vertical gap under one line height, and overlapping horizontally with
     *    centred, left or right alignment (bubble lettering is usually centred),
     *  - or the same text line split in two pieces by the OCR.
     * The bubble text is its lines in reading order, joined/cleaned by [ScanText.joinLines].
     */
    fun clusterLines(lines: List<OcrBlock>): List<Bubble> {
        val usable = lines.filter { it.text.isNotBlank() && it.box.width > 0 && it.box.height > 0 }
        if (usable.isEmpty()) return emptyList()

        val parent = IntArray(usable.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            parent[i] = r
            return r
        }
        for (i in usable.indices) for (j in i + 1 until usable.size) {
            if (sameLettering(usable[i].box, usable[j].box)) {
                val ri = find(i); val rj = find(j)
                if (ri != rj) parent[ri] = rj
            }
        }

        return usable.indices.groupBy { find(it) }.values.map { idxs ->
            val ls = idxs.map { usable[it] }
            val box = Box(
                ls.minOf { it.box.left }, ls.minOf { it.box.top },
                ls.maxOf { it.box.right }, ls.maxOf { it.box.bottom },
            )
            val ordered = bands(ls, { it.box }).flatMap { band -> band.sortedBy { it.box.left } }
            val lineH = ls.map { it.box.height }.sorted()[ls.size / 2].coerceAtLeast(1)
            Bubble(ScanText.joinLines(ordered.map { it.text }), box, lineH)
        }
    }

    /**
     * Reading order: bubbles whose vertical spans overlap form a row, rows go top-to-bottom,
     * and inside a row right-to-left ([rtl] = true, manga and French manga scans) or
     * left-to-right (comics, webtoons).
     */
    fun readingOrder(bubbles: List<Bubble>, rtl: Boolean = true): List<Bubble> {
        if (bubbles.size < 2) return bubbles
        return bands(bubbles, { it.box }).flatMap { row ->
            if (rtl) row.sortedByDescending { it.box.cx } else row.sortedBy { it.box.cx }
        }
    }

    /**
     * Splits items into horizontal rows: an item joins the current row when it overlaps the
     * row vertically by at least 30% of the smaller height. Rows come out top-to-bottom.
     * Comparing overlaps (not integer buckets of the centre) keeps two side-by-side bubbles
     * a few pixels apart in the same row.
     */
    private fun <T> bands(items: List<T>, box: (T) -> Box): List<List<T>> {
        val rows = ArrayList<MutableList<T>>()
        var rowTop = 0; var rowBottom = 0
        for (it in items.sortedBy { box(it).top }) {
            val b = box(it)
            val overlap = minOf(rowBottom, b.bottom) - maxOf(rowTop, b.top)
            val minH = minOf(rowBottom - rowTop, b.height).coerceAtLeast(1)
            if (rows.isNotEmpty() && overlap >= minH * 0.3) {
                rows.last().add(it)
                rowBottom = maxOf(rowBottom, b.bottom)
            } else {
                rows.add(mutableListOf(it))
                rowTop = b.top; rowBottom = b.bottom
            }
        }
        return rows
    }

    private fun sameLettering(a: Box, b: Box): Boolean {
        val h = minOf(a.height, b.height).coerceAtLeast(1)
        if (maxOf(a.height, b.height) > h * 1.7) return false

        // Pieces of the same text line (OCR split it, e.g. around a wide space).
        val vOverlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val hGap = maxOf(a.left, b.left) - minOf(a.right, b.right)
        if (vOverlap >= h * 0.6 && hGap <= h * 1.2) return true

        // Lines stacked inside one bubble.
        val vGap = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)
        if (vGap > h * 0.9 || vOverlap > h * 0.5) return false
        val hOverlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val narrow = minOf(a.width, b.width).coerceAtLeast(1)
        if (hOverlap < narrow * 0.25) return false
        val tol = h * 1.5
        return kotlin.math.abs(a.cx - b.cx) <= tol ||
            kotlin.math.abs(a.left - b.left) <= tol ||
            kotlin.math.abs(a.right - b.right) <= tol ||
            hOverlap >= narrow * 0.6
    }
}
