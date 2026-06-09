package com.animereader.internal

/**
 * Pure de-duplication logic for the read-aloud pipeline: it decides whether a freshly OCR'd line
 * is really the same bubble we just read (manga scrolls, so consecutive frames overlap, and OCR
 * jitters the punctuation/spacing). Extracted from the service so it is unit-testable on the JVM.
 */
object TextDedup {

    /** Lowercase, drop whitespace/punctuation (keeps CJK) so dedup ignores cosmetic OCR diffs. */
    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[\\s\\p{Punct}　・…]+"), "")

    /**
     * True when [norm] is effectively a repeat of one of the [recent] normalized lines:
     *  - exact match, or
     *  - one fully contains the other and the shorter is ≥60% of the longer (scroll continuation), or
     *  - bigram (Sørensen–Dice) similarity ≥ [threshold] (OCR jitter of the same line).
     */
    fun isDuplicate(norm: String, recent: List<String>, threshold: Double = 0.82): Boolean {
        for (prev in recent) {
            if (norm == prev) return true
            val shorter = if (norm.length <= prev.length) norm else prev
            val longer = if (norm.length <= prev.length) prev else norm
            if (shorter.isNotEmpty() && longer.contains(shorter) && shorter.length >= longer.length * 0.6) return true
            if (bigramSimilarity(norm, prev) >= threshold) return true
        }
        return false
    }

    /** Sørensen–Dice coefficient over character bigrams (0.0 disjoint … 1.0 identical). */
    fun bigramSimilarity(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        fun bigrams(s: String) = (0 until s.length - 1).mapTo(HashSet()) { s.substring(it, it + 2) }
        val sa = bigrams(a); val sb = bigrams(b)
        val inter = sa.count { it in sb }
        val union = sa.size + sb.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }
}
