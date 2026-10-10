package com.animereader.pipeline

import com.animereader.internal.RateGate
import com.animereader.internal.TextDedup
import com.animereader.translate.LangGuess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Target language: BCP-47-ish [code] for ML Kit + natural [promptName] for vision prompts. */
data class TargetLang(val code: String, val promptName: String)

/**
 * One recognised (and maybe translated) line, ready to display/speak. [lang] is the language of
 * [original] when known — it picks the voice for a line that is read untranslated.
 */
data class ReadLine(val original: String, val translated: String?, val provider: String?, val lang: String? = null)

/**
 * Result of a text translation. [translated] is null when the provider found the text already
 * in the target language; [detected] is the source language it saw, when it reports one.
 */
data class TextTranslation(val translated: String?, val provider: String, val detected: String?)

/** One detected bubble: the on-device OCR text + its JPEG crop (null = no upload possible). */
class PageBubble(val mlkitText: String, val jpeg: ByteArray?)

/**
 * A vision OCR+translate call. [dialogueContext] carries the last few lines of the
 * conversation so pronouns/tone stay coherent across bubbles. Throws on HTTP errors;
 * the message carries the status code.
 */
fun interface VisionOcrCall {
    @Throws(Exception::class)
    fun call(jpeg: ByteArray, targetLanguage: String, dialogueContext: String): Pair<String, String>?
}

/** Same, for a whole page of bubble crops in one request (answers in input order). */
fun interface VisionBatchCall {
    @Throws(Exception::class)
    fun call(jpegs: List<ByteArray>, targetLanguage: String, dialogueContext: String): List<Pair<String, String>>
}

/**
 * The OCR→translation decision core, extracted from OverlayService so it is JVM-unit-testable:
 * result cache, per-provider rate gates, the Gemini→Mistral fallback chain, and the offline
 * ML Kit translation as last resort. Knows nothing about Android, capture or TTS.
 */
class BubbleReader(
    private val backends: List<Backend>,
    /**
     * Text-only translator chain for the bubbles the vision backends didn't translate: one call
     * for a whole page, answers aligned with [texts] (null = that text failed).
     */
    private val textTranslate: suspend (texts: List<String>, targetCode: String) -> List<TextTranslation?>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onNotice: (String) -> Unit = {},
) {
    /** One online vision provider (Gemini, Mistral, …) with its own free-tier pacing. */
    class Backend(
        val name: String,
        val available: () -> Boolean,
        val single: VisionOcrCall,
        val batch: VisionBatchCall,
        val gate: RateGate,
    )

    private class CacheEntry(val line: ReadLine)

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?) = size > 60
    }

    // Rolling dialogue memory fed back into the vision prompts for coherent translations.
    private val dialogueLock = Any()
    private val dialogue = ArrayDeque<String>()

    private fun rememberLine(original: String, translated: String?) {
        if (original.isBlank()) return
        synchronized(dialogueLock) {
            dialogue.addLast(if (translated.isNullOrBlank()) original else "$original => $translated")
            while (dialogue.size > 6) dialogue.removeFirst()
        }
    }

    private fun dialogueContext(): String =
        synchronized(dialogueLock) { dialogue.takeLast(4).joinToString("\n") }

    /** Read one bubble: cache → first available online backend → text translation. */
    suspend fun read(mlkitText: String, jpeg: ByteArray?, target: TargetLang): ReadLine =
        readPage(listOf(PageBubble(mlkitText, jpeg)), target).first()

    /**
     * Read a whole page with as few online requests as possible: every bubble crop goes into
     * ONE vision batch call (what makes the free tiers last), then every bubble still lacking
     * a translation goes into ONE text-translation call. Results come back aligned with
     * [bubbles]; finished lines are cached by their on-device OCR text.
     */
    suspend fun readPage(bubbles: List<PageBubble>, target: TargetLang): List<ReadLine> {
        if (bubbles.isEmpty()) return emptyList()

        val results = arrayOfNulls<ReadLine>(bubbles.size)
        val cached = BooleanArray(bubbles.size)
        val missing = ArrayList<Int>()
        for (i in bubbles.indices) {
            val hit = synchronized(cacheLock) { cache[cacheKey(bubbles[i].mlkitText, target)] }
            if (hit != null) { results[i] = hit.line; cached[i] = true }
            else if (bubbles[i].jpeg != null) missing.add(i)
        }

        if (missing.isNotEmpty()) {
            val ctx = dialogueContext()
            for (b in backends) {
                if (!b.available()) continue
                if (!b.gate.tryAcquire(clock())) continue
                val r = runCatching {
                    withContext(Dispatchers.IO) {
                        if (missing.size == 1) listOfNotNull(b.single.call(bubbles[missing[0]].jpeg!!, target.promptName, ctx))
                        else b.batch.call(missing.map { bubbles[it].jpeg!! }, target.promptName, ctx)
                    }
                }
                val err = r.exceptionOrNull()
                if (err is kotlinx.coroutines.CancellationException) throw err
                if (err != null) { reportError(b, err); continue }
                val res = r.getOrDefault(emptyList())
                for ((slot, idx) in missing.withIndex()) {
                    if (results[idx] != null) continue
                    val pair = res.getOrNull(slot) ?: continue
                    val original = pair.first.ifBlank { bubbles[idx].mlkitText }
                    if (original.isBlank()) continue
                    results[idx] = ReadLine(original, sanitize(original, pair.second), b.name, LangGuess.guess(original))
                }
                if (missing.all { results[it] != null }) break
            }
        }

        val lines = bubbles.indices.map { i -> results[i] ?: ReadLine(bubbles[i].mlkitText, null, null) }
        val done = completeTranslations(lines, cached, target)
        for (i in bubbles.indices) {
            if (cached[i] || done[i].original.isBlank()) continue
            synchronized(cacheLock) { cache[cacheKey(bubbles[i].mlkitText, target)] = CacheEntry(done[i]) }
            rememberLine(done[i].original, done[i].translated)
        }
        return done
    }

    /**
     * Fills the translations the vision step didn't give, in ONE text call. A bubble already in
     * the target language (French scan read in French) is never sent — it is simply read as is.
     * When the on-device guess can't tell, the translator decides: a reply whose detected
     * language is the target means "nothing to translate".
     */
    private suspend fun completeTranslations(lines: List<ReadLine>, skip: BooleanArray, target: TargetLang): List<ReadLine> {
        val out = lines.toMutableList()
        val todo = ArrayList<Int>()
        for ((i, line) in lines.withIndex()) {
            if (skip[i] || line.translated != null || line.original.isBlank()) continue
            val guess = line.lang ?: LangGuess.guess(line.original)
            out[i] = line.copy(lang = guess)
            if (guess != target.code) todo.add(i)
        }
        if (todo.isEmpty()) return out

        val answers = runCatching { textTranslate(todo.map { out[it].original }, target.code) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull() ?: return out
        for ((slot, i) in todo.withIndex()) {
            val a = answers.getOrNull(slot) ?: continue
            val line = out[i]
            val lang = a.detected ?: line.lang
            val translated = a.translated?.takeIf { lang != target.code }?.let { sanitize(line.original, it) }
            out[i] = line.copy(
                translated = translated,
                provider = if (translated != null) line.provider ?: a.provider else line.provider,
                lang = lang,
            )
        }
        return out
    }

    private fun cacheKey(mlkitText: String, target: TargetLang) =
        "${target.code}|${TextDedup.normalize(mlkitText)}"

    /**
     * Providers sometimes echo the source back (names, onomatopoeia, OCR noise) instead of
     * translating. A near-echo is not a translation: labelling it as one made French bubbles get
     * read by the target-language voice — so treat it as "no translation".
     */
    private fun sanitize(original: String, translation: String): String? {
        val t = TextDedup.normalize(translation)
        if (t.isEmpty()) return null
        val o = TextDedup.normalize(original)
        return if (t == o || TextDedup.bigramSimilarity(t, o) >= 0.80) null else translation
    }

    private fun reportError(b: Backend, e: Throwable) {
        val msg = e.message ?: e.javaClass.simpleName
        if (msg.contains("429")) {
            b.gate.reportRateLimited(clock())
            onNotice("Quota ${b.name} atteint → bascule sur le fournisseur suivant")
        } else {
            onNotice("OCR ${b.name} ⚠️ $msg")
        }
    }
}
