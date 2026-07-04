package com.animereader.internal

import kotlin.math.abs

/**
 * Cheap screen-change detector over a coarse luminance grid: lets the capture loop skip
 * the OCR entirely while the page under the rectangle hasn't moved, saving CPU/battery.
 * Pure math (no android.*) so it is JVM-unit-testable.
 */
object FrameDiff {

    /** ARGB pixels → integer luminance 0..255 (Rec. 601 weights). */
    fun luminance(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }

    /**
     * Fraction [0..1] of grid cells whose luminance moved by more than [tolerance].
     * A missing/mismatched previous grid counts as fully changed.
     */
    fun changedFraction(prev: IntArray?, cur: IntArray, tolerance: Int = 8): Double {
        if (prev == null || prev.size != cur.size || cur.isEmpty()) return 1.0
        var changed = 0
        for (i in cur.indices) if (abs(cur[i] - prev[i]) > tolerance) changed++
        return changed.toDouble() / cur.size
    }
}
