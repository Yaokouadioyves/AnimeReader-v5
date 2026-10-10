package com.animereader.translate

/**
 * Cheap on-device guess of a bubble's language, used to decide whether it needs translating
 * (a French bubble read in French must not be "translated") and which voice reads it. Scripts
 * settle CJK/Hangul at once; Latin text is scored on frequent function words, contractions and
 * accents. Returns null when unsure — the online translator then detects the language itself.
 * Android-free so it is JVM-unit-testable.
 */
object LangGuess {

    fun guess(text: String): String? {
        if (text.any { it.code in 0xAC00..0xD7AF || it.code in 0x1100..0x11FF }) return "ko"
        if (text.any { it.code in 0x3040..0x30FF }) return "ja"
        if (text.any { it.code in 0x4E00..0x9FFF }) return "zh"

        val lower = text.lowercase()
        val scores = HashMap<String, Int>()
        fun add(lang: String, n: Int = 1) { scores[lang] = (scores[lang] ?: 0) + n }

        for (word in WORD.findAll(lower).map { it.value }) {
            for ((lang, words) in STOPWORDS) if (word in words) add(lang)
        }
        // Contractions and elisions are strong, language-specific signals.
        add("en", 2 * EN_CONTRACTION.findAll(lower).count())
        add("fr", 2 * FR_ELISION.findAll(lower).count())
        // Accents, capped so one accented name can't outvote the words.
        add("fr", minOf(2, lower.count { it in "éèêëàâùûçœîïô" }))
        add("es", minOf(2, lower.count { it in "ñ¿¡" }))
        add("de", minOf(2, lower.count { it in "äöüß" }))
        add("pt", minOf(2, lower.count { it in "ãõ" }))

        val ranked = scores.filterValues { it > 0 }.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0
        return when {
            second == 0 -> best.key
            best.value >= 2 && best.value >= second * 1.5 -> best.key
            else -> null
        }
    }

    private val WORD = Regex("[\\p{L}]+")
    private val EN_CONTRACTION = Regex("\\b\\p{L}+['’](t|s|m|ll|re|ve|d)\\b")
    private val FR_ELISION = Regex("\\b(j|l|d|qu|n|c|s|m|t)['’]\\p{L}")

    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "fr" to setOf(
            "je", "tu", "il", "elle", "nous", "vous", "ils", "elles", "le", "les", "un", "une", "des", "du",
            "et", "est", "pas", "ne", "que", "qui", "quoi", "pour", "dans", "sur", "avec", "mais", "ou",
            "où", "ce", "cette", "ces", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses", "moi",
            "toi", "lui", "suis", "es", "sont", "fait", "faire", "bien", "très", "tout", "rien", "oui",
            "hein", "alors", "comme", "aussi", "encore", "jamais", "toujours", "peut", "être", "vais", "va",
            "veux", "sais", "ai", "as", "avez", "avons", "ont", "mais", "parce", "quand", "comment",
            "pourquoi", "ici", "là", "au", "aux", "leur", "ça", "cela", "votre", "notre",
        ),
        "en" to setOf(
            "i", "you", "he", "she", "we", "they", "it", "the", "a", "an", "and", "is", "are", "was",
            "were", "not", "that", "this", "what", "who", "for", "with", "but", "or", "my", "your", "his",
            "her", "our", "their", "me", "him", "us", "them", "be", "been", "have", "has", "do", "does",
            "did", "can", "will", "would", "should", "just", "all", "nothing", "yes", "yeah", "hey",
            "huh", "so", "like", "too", "never", "always", "here", "there", "why", "how", "when", "where",
            "of", "to", "in", "on", "at", "if", "no", "don", "about", "get", "got", "know", "want",
        ),
        "es" to setOf(
            "yo", "tú", "él", "ella", "nosotros", "ellos", "el", "los", "las", "una", "y", "es", "son",
            "está", "no", "que", "qué", "por", "para", "con", "pero", "mi", "tu", "su", "muy", "nada",
            "sí", "aquí", "porque", "cómo", "cuando", "dónde", "lo", "del", "al", "esto", "eso",
        ),
        "de" to setOf(
            "ich", "du", "er", "sie", "wir", "ihr", "der", "die", "das", "ein", "eine", "und", "ist",
            "nicht", "was", "wer", "für", "mit", "aber", "mein", "dein", "sein", "auch", "noch", "nur",
            "ja", "nein", "hier", "warum", "wie", "wenn", "wo", "zu", "den", "dem", "es",
        ),
        "it" to setOf(
            "io", "lui", "lei", "noi", "voi", "loro", "il", "lo", "gli", "uno", "una", "e", "è", "non",
            "che", "chi", "per", "con", "ma", "mio", "tuo", "suo", "molto", "niente", "sì", "qui",
            "perché", "come", "quando", "dove", "della", "sono", "questo",
        ),
        "pt" to setOf(
            "eu", "você", "ele", "ela", "nós", "eles", "o", "os", "um", "uma", "e", "é", "são", "está",
            "não", "que", "por", "para", "com", "mas", "meu", "seu", "muito", "nada", "sim", "aqui",
            "porque", "como", "quando", "onde", "isso", "isto", "do", "da", "no", "na",
        ),
    )
}
