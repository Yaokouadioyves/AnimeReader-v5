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

/** A detected speech bubble: the blocks' merged text + their bounding box. */
data class Bubble(val text: String, val box: Box)

/**
 * Groups OCR text blocks into speech-bubble clusters and orders them in manga reading
 * order. Detection works on the *positions* of text, not its glyphs, so unusual manga
 * fonts don't hurt it — even when ML Kit misreads the letters it still boxes them.
 */
object BubbleClusters {

    /**
     * Two blocks belong to the same bubble when their boxes, inflated by [gap] px,
     * intersect. Union-find over all pairs; each cluster becomes one [Bubble] whose
     * text is its blocks joined in top-to-bottom / left-to-right order.
     */
    fun cluster(blocks: List<OcrBlock>, gap: Int): List<Bubble> {
        val usable = blocks.filter { it.text.isNotBlank() && it.box.width > 0 && it.box.height > 0 }
        if (usable.isEmpty()) return emptyList()

        val parent = IntArray(usable.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            parent[i] = r
            return r
        }
        for (i in usable.indices) for (j in i + 1 until usable.size) {
            if (near(usable[i].box, usable[j].box, gap)) {
                val ri = find(i); val rj = find(j)
                if (ri != rj) parent[ri] = rj
            }
        }

        return usable.indices.groupBy { find(it) }.values.map { idxs ->
            val bs = idxs.map { usable[it] }
            val box = Box(
                bs.minOf { it.box.left }, bs.minOf { it.box.top },
                bs.maxOf { it.box.right }, bs.maxOf { it.box.bottom },
            )
            val unit = bs.map { it.box.height }.sorted()[bs.size / 2].coerceAtLeast(1)
            val text = bs.sortedWith(compareBy({ it.box.cy / unit }, { it.box.cx }))
                .joinToString(" ") { it.text }
                .replace(Regex("\\s+"), " ").trim()
            Bubble(text, box)
        }
    }

    /**
     * Manga reading order: row bands top-to-bottom, right-to-left inside a band
     * ([rtl] = true, the manga default) or left-to-right (Western comics).
     */
    fun readingOrder(bubbles: List<Bubble>, rtl: Boolean = true): List<Bubble> {
        if (bubbles.size < 2) return bubbles
        val unit = bubbles.map { it.box.height }.sorted()[bubbles.size / 2].coerceAtLeast(1)
        return bubbles.sortedWith(
            compareBy<Bubble> { it.box.cy / unit }
                .thenBy { if (rtl) -it.box.cx else it.box.cx }
        )
    }

    private fun near(a: Box, b: Box, gap: Int): Boolean =
        a.left - gap < b.right && b.left - gap < a.right &&
            a.top - gap < b.bottom && b.top - gap < a.bottom
}
