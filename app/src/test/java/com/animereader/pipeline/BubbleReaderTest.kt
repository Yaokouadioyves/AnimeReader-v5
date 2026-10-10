package com.animereader.pipeline

import com.animereader.internal.RateGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class BubbleReaderTest {

    private val jpeg = byteArrayOf(1, 2, 3)
    private val target = TargetLang("en", "anglais")

    private class FakeBackend(
        val answer: (String) -> Pair<String, String>?,
        val fail: Throwable? = null,
    ) {
        var singleCalls = 0
        var batchCalls = 0
        val contexts = mutableListOf<String>()

        fun asBackend(name: String, gate: RateGate = RateGate(0, 45_000)) = BubbleReader.Backend(
            name = name,
            available = { true },
            single = VisionOcrCall { _, _, ctx ->
                singleCalls++; contexts.add(ctx)
                fail?.let { throw it }
                answer("0")
            },
            batch = VisionBatchCall { jpegs, _, ctx ->
                batchCalls++; contexts.add(ctx)
                fail?.let { throw it }
                List(jpegs.size) { i -> answer(i.toString())!! }
            },
            gate = gate,
        )
    }

    private fun reader(
        vararg backends: BubbleReader.Backend,
        offline: suspend (List<String>, String) -> List<TextTranslation?> =
            { ts, _ -> ts.map { TextTranslation("ML(${it.length})", "ML Kit", null) } },
        clock: () -> Long = { 1_000_000L },
    ) = BubbleReader(backends.toList(), offline, clock)

    @Test
    fun `uses the first backend and caches the result`() = runBlocking {
        val gemini = FakeBackend(answer = { "Bonjour" to "Hello" })
        val r = reader(gemini.asBackend("Gemini"))
        val line1 = r.read("bonjour", jpeg, target)
        assertEquals("Hello", line1.translated)
        assertEquals("Gemini", line1.provider)
        val line2 = r.read("bonjour", jpeg, target)   // cache hit → no second call
        assertEquals(1, gemini.singleCalls)
        assertEquals("Hello", line2.translated)
    }

    @Test
    fun `falls back to the second backend on 429 and cools the first down`() = runBlocking {
        val gemini = FakeBackend(answer = { null }, fail = IOException("Gemini OCR HTTP 429"))
        val mistral = FakeBackend(answer = { "Bonjour" to "Hello" })
        val r = reader(gemini.asBackend("Gemini"), mistral.asBackend("Mistral"))
        val line = r.read("bonjour", jpeg, target)
        assertEquals("Mistral", line.provider)
        assertEquals("Hello", line.translated)
        // Second read (different text): Gemini is in cooldown → only Mistral is called.
        r.read("autre texte", jpeg, target)
        assertEquals(1, gemini.singleCalls)
        assertEquals(2, mistral.singleCalls)
    }

    @Test
    fun `offline translation is the last resort`() = runBlocking {
        val r = reader() // no online backends at all
        val line = r.read("bonjour", jpeg, target)
        assertEquals("bonjour", line.original)
        assertEquals("ML(7)", line.translated)
        assertEquals("ML Kit", line.provider)
    }

    @Test
    fun `a bubble already in the target language is read as is, without any call`() = runBlocking {
        var calls = 0
        val r = reader(offline = { ts, _ -> calls++; ts.map { TextTranslation("X", "Google", "en") } })
        val line = r.read("Je ne sais pas ce qu'il veut.", jpeg, TargetLang("fr", "français"))
        assertNull(line.translated)
        assertEquals("fr", line.lang)
        assertEquals(0, calls)
    }

    @Test
    fun `an English bubble is translated even when the target is French`() = runBlocking {
        val r = reader(offline = { ts, _ -> ts.map { TextTranslation("Je ne sais pas.", "Google", "en") } })
        val line = r.read("I don't know what you want.", jpeg, TargetLang("fr", "français"))
        assertEquals("Je ne sais pas.", line.translated)
        assertEquals("Google", line.provider)
        assertEquals("en", line.lang)
    }

    @Test
    fun `when the guess is unsure, the translator's detected language decides`() = runBlocking {
        // "Bonjour" alone is too short to guess; Google answers it is already French.
        val r = reader(offline = { ts, _ -> ts.map { TextTranslation("Hello", "Google", "fr") } })
        val line = r.read("Bonjour", jpeg, TargetLang("fr", "français"))
        assertNull(line.translated)
        assertEquals("fr", line.lang)
    }

    @Test
    fun `the text fallback translates a whole page in one call`() = runBlocking {
        var calls = 0
        val r = reader(offline = { ts, _ -> calls++; ts.map { TextTranslation("FR(${it.length})", "Google", "en") } })
        val lines = r.readPage(
            listOf(PageBubble("Where are you going?", null), PageBubble("Je suis là, tu vois.", null), PageBubble("Wait for me!", null)),
            TargetLang("fr", "français"),
        )
        assertEquals(1, calls)
        assertEquals(listOf("FR(20)", null, "FR(12)"), lines.map { it.translated })
    }

    @Test
    fun `a translation echoing the source counts as none`() = runBlocking {
        val gemini = FakeBackend(answer = { "Bonjour" to "BONJOUR" })
        val r = reader(gemini.asBackend("Gemini"), offline = { ts, _ -> ts.map { null } })
        val line = r.read("bonjour", jpeg, target)
        assertEquals("Bonjour", line.original)
        assertNull(line.translated)
    }

    @Test
    fun `the previous lines feed the next call as dialogue context`() = runBlocking {
        val gemini = FakeBackend(answer = { "Salut Léa" to "Hi Lea" })
        val r = reader(gemini.asBackend("Gemini"))
        r.read("salut lea", jpeg, target)
        r.read("une autre bulle", jpeg, target)
        assertEquals("", gemini.contexts[0])                       // nothing before the first line
        assertEquals("Salut Léa => Hi Lea", gemini.contexts[1])    // then the dialogue memory
    }

    @Test
    fun `readPage translates a whole page in one batch call`() = runBlocking {
        val gemini = FakeBackend(answer = { i -> "orig$i" to "trad$i" })
        val r = reader(gemini.asBackend("Gemini"))
        val lines = r.readPage(
            listOf(PageBubble("bulle un", jpeg), PageBubble("bulle deux", jpeg), PageBubble("bulle trois", jpeg)),
            target,
        )
        assertEquals(1, gemini.batchCalls)
        assertEquals(0, gemini.singleCalls)
        assertEquals(listOf("orig0", "orig1", "orig2"), lines.map { it.original })
        assertEquals(listOf("trad0", "trad1", "trad2"), lines.map { it.translated })
        // The batch fed the cache: re-reading one bubble alone makes no new call.
        val cached = r.read("bulle deux", jpeg, target)
        assertEquals("trad1", cached.translated)
        assertEquals(1, gemini.batchCalls)
        assertEquals(0, gemini.singleCalls)
    }

    @Test
    fun `readPage falls back to the second backend on 429`() = runBlocking {
        val gemini = FakeBackend(answer = { null }, fail = IOException("Gemini OCR HTTP 429"))
        val mistral = FakeBackend(answer = { i -> "o$i" to "t$i" })
        val r = reader(gemini.asBackend("Gemini"), mistral.asBackend("Mistral"))
        val lines = r.readPage(listOf(PageBubble("a", jpeg), PageBubble("b", jpeg)), target)
        assertEquals(1, gemini.batchCalls)
        assertEquals(1, mistral.batchCalls)
        assertEquals(listOf("Mistral", "Mistral"), lines.map { it.provider })
    }

    @Test
    fun `readPage keeps the on-device text when everything online fails`() = runBlocking {
        val gemini = FakeBackend(answer = { null }, fail = IOException("Gemini OCR HTTP 500"))
        val r = reader(gemini.asBackend("Gemini"))
        val lines = r.readPage(listOf(PageBubble("texte local", jpeg), PageBubble("autre", jpeg)), target)
        assertEquals(listOf("texte local", "autre"), lines.map { it.original })
        assertEquals(listOf("ML(11)", "ML(5)"), lines.map { it.translated })
    }
}
