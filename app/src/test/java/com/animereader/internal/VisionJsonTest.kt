package com.animereader.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisionJsonTest {

    @Test
    fun `parses a single result`() {
        val p = VisionJson.parseSingle("""{"original":"Bonjour !","translation":"Hello!"}""")
        assertEquals("Bonjour !" to "Hello!", p)
    }

    @Test
    fun `strips markdown fences`() {
        val raw = "```json\n{\"original\":\"A\",\"translation\":\"B\"}\n```"
        assertEquals("A" to "B", VisionJson.parseSingle(raw))
    }

    @Test
    fun `single returns null on garbage`() {
        assertNull(VisionJson.parseSingle("désolé, je ne peux pas"))
    }

    @Test
    fun `parses a bubbles batch object`() {
        val raw = """{"bubbles":[{"original":"a","translation":"1"},{"original":"b","translation":"2"}]}"""
        assertEquals(listOf("a" to "1", "b" to "2"), VisionJson.parseBatch(raw))
    }

    @Test
    fun `parses a bare array batch`() {
        val raw = """[{"original":"a","translation":"1"}]"""
        assertEquals(listOf("a" to "1"), VisionJson.parseBatch(raw))
    }

    @Test
    fun `batch returns empty on garbage`() {
        assertEquals(emptyList<Pair<String, String>>(), VisionJson.parseBatch("pas du JSON"))
        assertEquals(emptyList<Pair<String, String>>(), VisionJson.parseBatch("""{"autre":1}"""))
    }
}
