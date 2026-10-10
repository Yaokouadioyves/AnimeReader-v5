package com.animereader.overlay

import com.animereader.ocr.Box

/**
 * Geometry of the translations drawn over the bubbles (overlay mode). Pure (no android.*) so it
 * is JVM-unit-testable; the service only supplies a text measurer and draws the result.
 */
object OverlayLayout {

    /** Where to draw one translation: its [box] (region pixels) and its text size in pixels. */
    data class Placement(val box: Box, val textSizePx: Float, val inset: Int)

    /**
     * Covers the lettering of a bubble ([text] = box of its OCR lines) and fits [measure]d text
     * inside: the original letter height first, then smaller (down to [minSizePx]) and a bit
     * wider (≤ 1.3×, translations into French run ~15–20% longer than English). If even that is
     * not enough, the box grows vertically around the bubble's centre — a slightly bigger box
     * beats unreadable text. [measure] returns the height the text takes at a size and width.
     */
    fun place(
        text: Box,
        lineHeight: Int,
        regionW: Int,
        regionH: Int,
        minSizePx: Float,
        measure: (sizePx: Float, widthPx: Int) -> Int,
    ): Placement {
        val pad = padFor(lineHeight)
        val inset = (pad / 2).coerceAtLeast(1)
        val base = clamp(Box(text.left - pad, text.top - pad, text.right + pad, text.bottom + pad), regionW, regionH)
        val maxSize = maxOf(lineHeight * 0.95f, minSizePx)

        var size = maxSize
        while (true) {
            for (k in WIDEN) {
                val box = widen(base, k, regionW)
                val inner = (box.width - 2 * inset).coerceAtLeast(1)
                if (measure(size, inner) <= box.height - 2 * inset) return Placement(box, size, inset)
            }
            if (size <= minSizePx) break
            size = maxOf(minSizePx, size * 0.9f)
        }
        val wide = widen(base, WIDEN.last(), regionW)
        val need = measure(minSizePx, (wide.width - 2 * inset).coerceAtLeast(1)) + 2 * inset
        return Placement(grow(wide, need, regionH), minSizePx, inset)
    }

    /** Margin added around the OCR text box so the original glyphs are fully covered. */
    fun padFor(lineHeight: Int): Int = (lineHeight * 0.35f).toInt().coerceAtLeast(3)

    /**
     * The bubble's paper colour: the most frequent colour (4 bits per channel) inside [box],
     * averaged over its pixels. The lettering is a minority of a bubble's pixels, so this is the
     * background the translation should be drawn on (white, a tinted narration box, black…).
     */
    fun paperColor(argb: IntArray, width: Int, height: Int, box: Box, step: Int = 2): Int {
        val l = box.left.coerceIn(0, width - 1); val r = box.right.coerceIn(l + 1, width)
        val t = box.top.coerceIn(0, height - 1); val b = box.bottom.coerceIn(t + 1, height)
        val counts = IntArray(4096)
        val sr = LongArray(4096); val sg = LongArray(4096); val sb = LongArray(4096)
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val p = argb[y * width + x]
                val red = p shr 16 and 0xFF; val green = p shr 8 and 0xFF; val blue = p and 0xFF
                val bin = (red shr 4 shl 8) or (green shr 4 shl 4) or (blue shr 4)
                counts[bin]++; sr[bin] += red.toLong(); sg[bin] += green.toLong(); sb[bin] += blue.toLong()
                x += step
            }
            y += step
        }
        var best = 0
        for (i in counts.indices) if (counts[i] > counts[best]) best = i
        val n = counts[best].coerceAtLeast(1)
        return (0xFF shl 24) or ((sr[best] / n).toInt() shl 16) or ((sg[best] / n).toInt() shl 8) or (sb[best] / n).toInt()
    }

    /** Dark ink on pale paper, white ink on dark paper. */
    fun inkFor(paper: Int): Int {
        val lum = ((paper shr 16 and 0xFF) * 299 + (paper shr 8 and 0xFF) * 587 + (paper and 0xFF) * 114) / 1000
        return if (lum >= 140) 0xFF141414.toInt() else 0xFFFFFFFF.toInt()
    }

    /**
     * Cells of the [n]×[n] change-detection grid (see FrameDiff) that a drawn translation
     * covers: the capture sees our own drawing there, so only the page around it can tell
     * whether the user scrolled.
     */
    fun gridMask(boxes: List<Box>, regionW: Int, regionH: Int, n: Int): BooleanArray {
        val mask = BooleanArray(n * n)
        if (regionW <= 0 || regionH <= 0) return mask
        for (bx in boxes) {
            val c0 = (bx.left.toLong() * n / regionW).toInt().coerceIn(0, n - 1)
            val c1 = ((bx.right - 1).toLong() * n / regionW).toInt().coerceIn(c0, n - 1)
            val r0 = (bx.top.toLong() * n / regionH).toInt().coerceIn(0, n - 1)
            val r1 = ((bx.bottom - 1).toLong() * n / regionH).toInt().coerceIn(r0, n - 1)
            for (r in r0..r1) for (c in c0..c1) mask[r * n + c] = true
        }
        return mask
    }

    private val WIDEN = floatArrayOf(1f, 1.15f, 1.3f)

    private fun clamp(b: Box, w: Int, h: Int) = Box(
        b.left.coerceIn(0, maxOf(0, w - 1)), b.top.coerceIn(0, maxOf(0, h - 1)),
        b.right.coerceIn(1, maxOf(1, w)), b.bottom.coerceIn(1, maxOf(1, h)),
    )

    /** Widens [b] by [k] around its centre, shifted back inside the region when it overflows. */
    private fun widen(b: Box, k: Float, regionW: Int): Box {
        val w = minOf((b.width * k).toInt(), regionW)
        var left = b.cx - w / 2
        left = left.coerceIn(0, maxOf(0, regionW - w))
        return Box(left, b.top, left + w, b.bottom)
    }

    /** Grows [b] to [height] around its centre, kept inside the region. */
    private fun grow(b: Box, height: Int, regionH: Int): Box {
        if (height <= b.height) return b
        val h = minOf(height, regionH)
        var top = b.cy - h / 2
        top = top.coerceIn(0, maxOf(0, regionH - h))
        return Box(b.left, top, b.right, top + h)
    }
}
