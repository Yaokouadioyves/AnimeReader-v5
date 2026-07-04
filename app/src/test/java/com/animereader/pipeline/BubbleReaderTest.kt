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

        fun asBackend(name: String, gate: RateGate = RateGate(0, 45_000)) = BubbleReader.Backend(
            name = name,
            available = { true },
            single = VisionOcrCall { _, _ ->
                singleCalls++
                fail?.let { throw it }
                answer("0")
            },
            batch = VisionBatchCall { jpegs, _ ->
                batchCalls++
                fail?.let { throw it }
                List(jpegs.size) { i -> answer(i.toString())!! }
            },
            gate = gate,
        )
    }

    private fun reader(
        vararg backends: BubbleReader.Backend,
        offline: suspend (String, String) -> String? = { t, _ -> "ML[$t]" },
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
        assertEquals("ML[bonjour]", line.translated)
        assertEquals("ML Kit", line.provider)
    }

    @Test
    fun `no translation when the target is the original language`() = runBlocking {
        val r = reader()
        val line = r.read("bonjour", jpeg, TargetLang("fr", "français"))
        assertNull(line.translated)
    }

    @Test
    fun `a translation echoing the source counts as none`() = runBlocking {
        val gemini = FakeBackend(answer = { "Bonjour" to "BONJOUR" })
        val r = reader(gemini.asBackend("Gemini"), offline = { _, _ -> null })
        val line = r.read("bonjour", jpeg, target)
        assertEquals("Bonjour", line.original)
        assertNull(line.translated)
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
        assertEquals(listOf("ML[texte local]", "ML[autre]"), lines.map { it.translated })
    }
}
