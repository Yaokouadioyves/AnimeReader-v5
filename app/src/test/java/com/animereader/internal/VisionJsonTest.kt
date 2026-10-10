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

    @Test
    fun `numbered answers are put back in image order`() {
        val raw = """{"bubbles":[{"n":2,"original":"b","translation":"2"},{"n":1,"original":"a","translation":"1"}]}"""
        assertEquals(listOf("a" to "1", "b" to "2"), VisionJson.parseBatch(raw))
    }

    @Test
    fun `a skipped image leaves an empty slot instead of shifting the next bubbles`() {
        val raw = """{"bubbles":[{"n":1,"original":"a","translation":"1"},{"n":3,"original":"c","translation":"3"}]}"""
        assertEquals(listOf("a" to "1", "" to "", "c" to "3"), VisionJson.parseBatch(raw))
    }

    @Test
    fun `without usable numbers the reply order is kept`() {
        val raw = """{"bubbles":[{"n":1,"original":"a","translation":"1"},{"n":1,"original":"b","translation":"2"}]}"""
        assertEquals(listOf("a" to "1", "b" to "2"), VisionJson.parseBatch(raw))
    }
}
