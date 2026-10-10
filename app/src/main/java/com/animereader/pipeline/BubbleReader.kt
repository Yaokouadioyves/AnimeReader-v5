package com.animereader.pipeline

import com.animereader.internal.RateGate
import com.animereader.internal.TextDedup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Target language: BCP-47-ish [code] for ML Kit + natural [promptName] for vision prompts. */
data class TargetLang(val code: String, val promptName: String)

/** One recognised (and maybe translated) line, ready to display/speak. */
data class ReadLine(val original: String, val translated: String?, val provider: String?)

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
    /** Text-only fallback translator chain; returns (translation, providerName) or null. */
    private val textTranslate: suspend (text: String, targetCode: String) -> Pair<String, String>?,
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

    private class CacheEntry(val original: String, val translated: String?, val provider: String?)

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

    /** Read one bubble: cache → first available online backend → offline translation. */
    suspend fun read(mlkitText: String, jpeg: ByteArray?, target: TargetLang): ReadLine {
        val key = cacheKey(mlkitText, target)
        synchronized(cacheLock) { cache[key] }?.let {
            return ReadLine(it.original, it.translated, it.provider)
        }

        var original = mlkitText
        var translated: String? = null
        var provider: String? = null

        if (jpeg != null) {
            val ctx = dialogueContext()
            for (b in backends) {
                if (!b.available()) continue
                if (!b.gate.tryAcquire(clock())) continue
                val r = runCatching { withContext(Dispatchers.IO) { b.single.call(jpeg, target.promptName, ctx) } }
                val err = r.exceptionOrNull()
                if (err is kotlinx.coroutines.CancellationException) throw err
                if (err != null) { reportError(b, err); continue }
                val res = r.getOrNull()
                if (res != null && res.first.isNotBlank()) {
                    original = res.first
                    translated = sanitize(res.first, res.second)
                    provider = b.name
                    synchronized(cacheLock) { cache[key] = CacheEntry(original, translated, provider) }
                    break
                }
            }
        }

        if (translated == null && target.code != "fr") {
            val t = runCatching { textTranslate(original, target.code) }.getOrNull()
            if (t != null && t.first.isNotBlank() && !t.first.equals(original, ignoreCase = true)) {
                translated = t.first
                if (provider == null) provider = t.second
            }
        }
        rememberLine(original, translated)
        return ReadLine(original, translated, provider)
    }

    /**
     * Read a whole page in ONE online request: every bubble crop goes into a single batch
     * call, which is what makes the free tiers last (one request per page instead of one
     * per bubble). Results come back aligned with [bubbles]; cache and offline fallback
     * are applied per bubble.
     */
    suspend fun readPage(bubbles: List<PageBubble>, target: TargetLang): List<ReadLine> {
        if (bubbles.isEmpty()) return emptyList()
        if (bubbles.size == 1) return listOf(read(bubbles[0].mlkitText, bubbles[0].jpeg, target))

        val results = arrayOfNulls<ReadLine>(bubbles.size)
        val missing = ArrayList<Int>()
        for (i in bubbles.indices) {
            val key = cacheKey(bubbles[i].mlkitText, target)
            val hit = synchronized(cacheLock) { cache[key] }
            if (hit != null) results[i] = ReadLine(hit.original, hit.translated, hit.provider)
            else if (bubbles[i].jpeg != null) missing.add(i)
        }

        if (missing.isNotEmpty()) {
            val ctx = dialogueContext()
            for (b in backends) {
                if (!b.available()) continue
                if (!b.gate.tryAcquire(clock())) continue
                val jpegs = missing.map { bubbles[it].jpeg!! }
                val r = runCatching { withContext(Dispatchers.IO) { b.batch.call(jpegs, target.promptName, ctx) } }
                val err = r.exceptionOrNull()
                if (err is kotlinx.coroutines.CancellationException) throw err
                if (err != null) { reportError(b, err); continue }
                val res = r.getOrDefault(emptyList())
                for ((slot, idx) in missing.withIndex()) {
                    val pair = res.getOrNull(slot) ?: continue
                    val original = pair.first.ifBlank { bubbles[idx].mlkitText }
                    if (original.isBlank()) continue
                    val translated = sanitize(original, pair.second)
                    results[idx] = ReadLine(original, translated, b.name)
                    synchronized(cacheLock) {
                        cache[cacheKey(bubbles[idx].mlkitText, target)] = CacheEntry(original, translated, b.name)
                    }
                }
                if (missing.all { results[it] != null }) break
            }
        }

        return bubbles.indices.map { i ->
            var line = results[i] ?: ReadLine(bubbles[i].mlkitText, null, null)
            if (line.translated == null && target.code != "fr" && line.original.isNotBlank()) {
                val t = runCatching { textTranslate(line.original, target.code) }.getOrNull()
                if (t != null && t.first.isNotBlank() && !t.first.equals(line.original, ignoreCase = true)) {
                    line = ReadLine(line.original, t.first, line.provider ?: t.second)
                }
            }
            if (i in missing) rememberLine(line.original, line.translated)
            line
        }
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
