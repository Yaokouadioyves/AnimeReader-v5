package com.animereader.ocr

/**
 * Cleans the OCR text of one bubble of a French/English scan before it is translated or read
 * aloud. Scan lettering is a hard case for both steps: it is ALL CAPS (translators then answer
 * in caps and pick worse senses, the voice reads it flatly), words are cut across lines with a
 * hyphen, bold "I" comes out as "|" or "l", and scan groups stamp their site on the pages.
 * Android-free so it is JVM-unit-testable.
 */
object ScanText {

    /** Full clean-up of one bubble: line joining, OCR fix-ups, then sentence case. */
    fun clean(lines: List<String>): String = polish(joinLines(lines))

    /** OCR fix-ups then sentence case, for a bubble whose lines are already joined. */
    fun polish(text: String): String = sentenceCase(fixOcrConfusions(text))

    /**
     * Joins the OCR lines of one bubble. A line ending with a hyphen is glued to the next one
     * without a space: the hyphen is dropped for a word cut by the lettering ("ATTEN-" + "DRE")
     * but kept for a real compound ("PEUT-" + "ÊTRE", "EST-" + "CE", "DIS-" + "MOI").
     */
    fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isEmpty()) { sb.append(line); continue }
            val endsCut = sb.length >= 2 && sb.last() in HYPHENS && sb[sb.length - 2].isLetter()
            if (endsCut && line.first().isLetter()) {
                val head = lastWord(sb.substring(0, sb.length - 1))
                val tail = firstWord(line)
                if (isCompound(head, tail)) sb.setCharAt(sb.length - 1, '-')
                else sb.setLength(sb.length - 1)
                sb.append(line)
            } else {
                sb.append(' ').append(line)
            }
        }
        return tidy(sb.toString())
    }

    /**
     * Fixes the letter confusions typical of comic lettering, inside words only: "|" → "I",
     * a lowercase "l" in an otherwise upper-case word → "I" ("lT'S" → "IT'S"), and "0" between
     * letters → "O" ("C0MME" → "COMME"). Numbers ("10", "1er") are left alone.
     */
    fun fixOcrConfusions(s: String): String {
        val caps = isMostlyUpper(s)
        return s.split(' ').joinToString(" ") { fixToken(it, caps) }
    }

    /**
     * Rewrites text that is (almost) entirely upper-case into normal sentence case: lowercase,
     * then a capital at the start of each sentence and on the English pronoun "I". Text that
     * already has normal casing is returned unchanged.
     */
    fun sentenceCase(s: String): String {
        if (s.count { it.isLetter() } < 4 || !isMostlyUpper(s)) return s

        val out = StringBuilder(s.lowercase())
        var capNext = true
        for (i in out.indices) {
            val c = out[i]
            if (capNext && c.isLetter()) { out.setCharAt(i, c.uppercaseChar()); capNext = false }
            else if (c in SENTENCE_END) capNext = true
        }
        return PRONOUN_I.replace(out) { it.value.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    /**
     * True for a bubble that should not be read: fewer than two letters (page numbers, stray
     * marks) or a scan-group watermark/credit.
     */
    fun isNoise(s: String): Boolean = s.count { it.isLetter() } < 2 || isWatermark(s)

    /**
     * True for a scan-group watermark or credit line (web address, "Trad :", "Check :", …).
     * Checked per OCR line BEFORE grouping, so a site stamped next to a bubble is dropped
     * without taking the dialogue with it.
     */
    fun isWatermark(s: String): Boolean = WEB_ADDRESS.containsMatchIn(s) || CREDIT.containsMatchIn(s)

    private fun isMostlyUpper(s: String): Boolean {
        val letters = s.count { it.isLetter() }
        return letters > 0 && s.count { it.isUpperCase() } >= letters * 0.8
    }

    private fun fixToken(token: String, capsText: Boolean): String {
        if (token.isEmpty()) return token
        if (token == "|") return "I"
        if (token.none { it == '|' || it == 'l' || it == '0' }) return token
        val letters = token.count { it.isLetter() }
        val upperRest = token.count { it.isUpperCase() }
        // A lowercase "l" read for a capital I: only in caps lettering, inside a word whose other
        // letters are all capitals ("lT'S", "HAlR") — never in normal text like "Il".
        val capsWord = capsText && upperRest >= 2 && upperRest == letters - token.count { it == 'l' }
        // "l'M HERE" / "l'AMOUR": an elided l in caps lettering is an English I or a French L.
        val elided = capsText && token.length > 2 && token[0] == 'l' && (token[1] == '\'' || token[1] == '’')
        val chars = token.toCharArray()
        for (i in chars.indices) {
            val prev = chars.getOrNull(i - 1)
            val next = chars.getOrNull(i + 1)
            when (chars[i]) {
                '|' -> chars[i] = 'I'
                'l' -> if (capsWord || (elided && i == 0)) chars[i] = if (isFrenchElision(chars, i)) 'L' else 'I'
                '0' -> if ((prev?.isLetter() == true || i == 0) && next?.isLetter() == true) chars[i] = 'O'
            }
        }
        return String(chars)
    }

    /** "l'AMOUR" is a French elided article (→ L'), "l'M"/"l'LL" an English I (→ I'). */
    private fun isFrenchElision(chars: CharArray, i: Int): Boolean {
        if (i != 0 || chars.getOrNull(1) != '\'' && chars.getOrNull(1) != '’') return false
        val rest = String(chars, 2, chars.size - 2).uppercase().trimEnd { !it.isLetter() }
        return rest !in ENGLISH_CONTRACTIONS
    }

    private fun isCompound(head: String, tail: String): Boolean {
        val t = tail.lowercase().removePrefix("t-")
        return t in COMPOUND_TAILS || head.lowercase() in COMPOUND_HEADS
    }

    private fun lastWord(s: String): String = s.takeLastWhile { it.isLetter() || it == '\'' || it == '’' }
    private fun firstWord(s: String): String = s.takeWhile { it.isLetter() || it == '-' }

    private fun tidy(s: String): String = s
        .replace(Regex("\\.{2,}|(?:\\. ){2,}\\."), "…")
        .replace(Regex("\\s+([,.…)])"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val HYPHENS = setOf('-', '‐', '‑', '–')
    // "…" is left out on purpose: in bubbles it mostly marks a pause inside a sentence.
    private val SENTENCE_END = setOf('.', '!', '?', '«', '—', '“')
    private val PRONOUN_I = Regex("(?<![\\p{L}'’])i(?=('|’)(m|ll|ve|d|s)\\b|(?![\\p{L}'’]))")
    private val ENGLISH_CONTRACTIONS = setOf("M", "LL", "VE", "D")

    // Second halves of French/English hyphenated compounds: keep the hyphen when a line ends there.
    private val COMPOUND_TAILS = setOf(
        "être", "etre", "ce", "ci", "là", "la", "moi", "toi", "lui", "nous", "vous", "leur", "le", "les",
        "en", "y", "il", "ils", "elle", "elles", "on", "je", "tu", "même", "meme", "midi", "dessus",
        "dessous", "dedans", "dehors", "mère", "mere", "père", "pere", "frère", "frere", "sœur", "soeur",
        "fils", "fille", "bas", "haut", "delà", "dela", "self", "made", "known", "up", "in", "out",
    )
    // First halves that are only ever hyphenated as compounds.
    private val COMPOUND_HEADS = setOf(
        "peut", "est", "qu'est", "qu’est", "c'est", "c’est", "dis", "dites", "va", "vas", "allez",
        "donne", "donnez", "laisse", "laissez", "grand", "grande", "beau", "belle", "après", "apres",
        "arrière", "arriere", "avant", "demi", "mi", "sous", "vice", "ex", "non", "self", "well", "half",
        "là", "celui", "celle", "ceux", "celles", "au", "aux", "rendez", "sais", "crois", "vois",
    )
    private val WEB_ADDRESS = Regex(
        "(https?://|www\\.|discord\\.gg|\\b[\\w-]{2,}\\.(com|net|org|fr|io|gg|xyz|site|online|info)\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val CREDIT = Regex(
        "^\\s*(trad(uction|ucteur|uit)?|translat(ion|or|ed)s?|clean(ing|er|eur)?|edit(ion|eur|or)?|" +
            "check(eur|er|ing)?|typo(graphie)?|typeset(ting|ter)?|q\\.?c|raws?|scans?|proof(read(ing|er)?)?|" +
            "redraw(ing|er)?|team)\\s*[:=/]",
        RegexOption.IGNORE_CASE,
    )
}
