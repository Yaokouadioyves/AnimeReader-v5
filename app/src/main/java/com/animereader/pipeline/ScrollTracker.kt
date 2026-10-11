package com.animereader.pipeline

import com.animereader.internal.TextDedup
import com.animereader.ocr.Edge

/**
 * Decides, capture after capture, which bubbles to read while the user scrolls a vertical strip
 * (content moves UP as they read down). Works bubble by bubble — not on the whole frame, whose
 * text changes on every scroll step:
 *  - a COMPLETE bubble is read once its text is the same on two consecutive captures (the OCR
 *    has settled), and never again (memory of what was read);
 *  - a bubble cut at the BOTTOM is waited for: the next captures bring it whole. If the page
 *    stops there, it is read when its text already ends a sentence;
 *  - a bubble cut at the TOP has already been read lower down — skipped;
 *  - a bubble taller than the rectangle (cut at both edges) is assembled across captures from
 *    the overlapping lines and read when its end comes into view.
 * Pure (no android.*) so it is JVM-unit-testable.
 */
class ScrollTracker(private val memory: Int = 40, private val tallMaxAge: Int = 8) {

    /** One bubble of the current capture, in reading order. */
    data class Seen(val text: String, val edge: Edge)

    /** A text to read now: [index] of the bubble in the frame, or null for an assembled one. */
    data class Ready(val index: Int?, val text: String)

    private val read = ArrayDeque<String>()
    private var previous: List<String> = emptyList()
    private var bottomCut: String? = null   // text of the bubble cut at the bottom last capture
    private var unsettled: List<String> = emptyList() // complete bubbles seen once, not read yet
    private var tall: String? = null        // a bubble taller than the rectangle, being assembled
    private var tallAge = 0

    fun reset() {
        read.clear(); previous = emptyList(); bottomCut = null; unsettled = emptyList(); tall = null; tallAge = 0
    }

    /** Remember [text] as read (e.g. read by a manual replay) so scrolling doesn't repeat it. */
    fun markRead(text: String) = remember(TextDedup.normalize(text))

    /**
     * Overlay mode: the page has stopped and its translations are about to be drawn over the
     * bubbles — the OCR then pauses until the page moves, so the second capture that normally
     * lets a bubble settle never comes. Releases now every bubble of this still capture that
     * [readableWhenStill] and was not read yet (call it after [onFrame] for the same capture).
     */
    fun flushStill(frame: List<Seen>): List<Ready> {
        val out = ArrayList<Ready>()
        for ((i, s) in unread(frame)) {
            if (!readableWhenStill(s)) continue
            val n = TextDedup.normalize(s.text)
            if (n.length < 2 || alreadyRead(n)) continue
            out += emit(i, s.text, n)
        }
        unsettled = emptyList()
        return out
    }

    fun onFrame(frame: List<Seen>, still: Boolean): List<Ready> {
        val out = ArrayList<Ready>()
        val items = unread(frame)
        val norms = items.map { TextDedup.normalize(it.second.text) }
        var newBottomCut: String? = null
        val newUnsettled = ArrayList<String>()
        var tallTouched = false

        for ((j, item) in items.withIndex()) {
            val (i, s) = item
            val n = norms[j]
            if (n.length < 2 || alreadyRead(n)) continue
            val stable = previous.any { it == n || TextDedup.bigramSimilarity(it, n) >= STABLE_SIMILARITY }
            when (s.edge) {
                Edge.COMPLETE -> if (stable) out += emit(i, s.text, n) else newUnsettled += s.text
                Edge.CUT_BOTTOM -> {
                    newBottomCut = s.text
                    if (still && stable && endsSentence(s.text)) out += emit(i, s.text, n)
                }
                Edge.CUT_BOTH -> {
                    val base = tall ?: bottomCut
                    tall = base?.let { stitch(it, s.text) } ?: s.text
                    tallAge = 0; tallTouched = true
                }
                Edge.CUT_TOP -> if (!endOfRead(n)) {
                    // The end of a bubble that never fitted in the rectangle, or one that went
                    // past (bottom-cut or seen whole only once → top-cut) between two captures.
                    val whole = (listOfNotNull(tall, bottomCut) + unsettled)
                        .firstNotNullOfOrNull { stitch(it, s.text) }
                    if (whole != null) {
                        out += emit(null, whole, TextDedup.normalize(whole))
                        tall = null; tallTouched = true
                    }
                }
            }
        }
        if (!tallTouched && tall != null && ++tallAge > tallMaxAge) tall = null // scrolled away
        previous = norms
        bottomCut = newBottomCut
        unsettled = newUnsettled
        return out
    }

    /**
     * The bubbles of [frame] minus what was already read in them. A bubble read a moment ago
     * can come back glued to the new one appearing just under it (the two texts were close
     * enough to be grouped as one): only the new part is kept, under a null index (its crop
     * would show the old bubble too). A merge whose new part is a mere OCR crumb is dropped.
     */
    private fun unread(frame: List<Seen>): List<Pair<Int?, Seen>> = frame.withIndex().mapNotNull { (i, s) ->
        val rest = unreadPart(s) ?: return@mapNotNull i to s
        if (TextDedup.normalize(rest.text).length < MIN_REMAINDER_CHARS) null else null to rest
    }

    /** [s] without the already-read text at its start and/or end; null when there is none. */
    private fun unreadPart(s: Seen): Seen? {
        if (read.isEmpty()) return null
        var words = s.text.split(' ').filter { it.isNotBlank() }
        var edge = s.edge
        var changed = false
        while (true) {
            val head = readSpan(words, fromStart = true)
            if (head > 0) {
                words = words.drop(head); changed = true
                edge = when (edge) { Edge.CUT_TOP -> Edge.COMPLETE; Edge.CUT_BOTH -> Edge.CUT_BOTTOM; else -> edge }
                continue
            }
            val tail = readSpan(words, fromStart = false)
            if (tail > 0) {
                words = words.dropLast(tail); changed = true
                edge = when (edge) { Edge.CUT_BOTTOM -> Edge.COMPLETE; Edge.CUT_BOTH -> Edge.CUT_TOP; else -> edge }
                continue
            }
            break
        }
        return if (changed) Seen(words.joinToString(" "), edge) else null
    }

    /**
     * Number of words at the start (or end) of [words] that make up a bubble already read —
     * the run closest to it (an OCR jitter allows a near match, but a longer run would eat the
     * first words of the new bubble), always leaving one word (a full match is a plain repeat,
     * handled by [alreadyRead]). Runs under [MIN_OVERLAP_CHARS] letters don't count: "tu es"
     * may be anywhere.
     */
    private fun readSpan(words: List<String>, fromStart: Boolean): Int {
        var best = 0; var bestSim = STRIP_SIMILARITY
        for (k in words.size - 1 downTo 1) {
            val part = if (fromStart) words.subList(0, k) else words.subList(words.size - k, words.size)
            val n = TextDedup.normalize(part.joinToString(" "))
            if (n.length < MIN_OVERLAP_CHARS) break // shorter runs only get shorter
            val sim = read.maxOf { if (it == n) 1.0 else TextDedup.bigramSimilarity(it, n) }
            if (sim > bestSim) { best = k; bestSim = sim }
        }
        return best
    }

    private fun emit(index: Int?, text: String, norm: String): Ready {
        remember(norm)
        return Ready(index, text)
    }

    private fun remember(norm: String) {
        if (norm.isEmpty()) return
        read.addLast(norm)
        while (read.size > memory) read.removeFirst()
    }

    private fun alreadyRead(norm: String): Boolean = TextDedup.isDuplicate(norm, read.toList())

    /** The visible tail of a bubble read when it was whole, lower on the screen. */
    private fun endOfRead(norm: String): Boolean = norm.length >= MIN_OVERLAP_CHARS && read.any { it.contains(norm) }

    companion object {
        private const val STABLE_SIMILARITY = 0.82
        private const val MIN_OVERLAP_CHARS = 8
        private const val STRIP_SIMILARITY = 0.8
        private const val MIN_REMAINDER_CHARS = 4

        /**
         * A bubble that can be read (and drawn over) on a still page: whole, or cut at the
         * bottom by the rectangle but already ending a sentence (only its outline is cut).
         */
        fun readableWhenStill(s: Seen): Boolean =
            s.edge == Edge.COMPLETE || (s.edge == Edge.CUT_BOTTOM && endsSentence(s.text))

        fun endsSentence(text: String): Boolean =
            text.trimEnd().trimEnd('"', '»', '\'', ')', '’', '”').trimEnd().lastOrNull()?.let { it in ".!?…" } == true

        /**
         * Joins two captures of the same bubble that overlap: the last words of [a] are the
         * first words of [b] (≥ 2 words and ≥ 8 letters, so a common "tu es" can't glue two
         * different bubbles; the word cut by the edge on either side may be misread, so one
         * word at the seam may be skipped). Null when they don't overlap.
         */
        fun stitch(a: String, b: String): String? {
            val na = TextDedup.normalize(a); val nb = TextDedup.normalize(b)
            if (na.isEmpty() || nb.isEmpty()) return null
            if (nb.length >= MIN_OVERLAP_CHARS && na.contains(nb)) return a
            if (na.length >= MIN_OVERLAP_CHARS && nb.contains(na)) return b
            val wa = a.split(' ').filter { it.isNotBlank() }
            val wb = b.split(' ').filter { it.isNotBlank() }
            val ka = wa.map { TextDedup.normalize(it) }
            val kb = wb.map { TextDedup.normalize(it) }
            for (skipA in 0..1) for (skipB in 0..1) {
                val endA = ka.size - skipA
                for (k in minOf(endA, kb.size - skipB) downTo 2) {
                    if ((0 until k).all { ka[endA - k + it] == kb[skipB + it] } &&
                        (0 until k).sumOf { kb[skipB + it].length } >= MIN_OVERLAP_CHARS
                    ) {
                        // b's copy of the seam words wins: in a they ended a line cut by the edge.
                        return (wa.subList(0, endA - k) + wb.subList(skipB, wb.size)).joinToString(" ")
                    }
                }
            }
            return null
        }
    }
}
