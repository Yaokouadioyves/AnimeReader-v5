package com.animereader.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleWebJsonTest {

    @Test
    fun `parses a single-segment reply`() {
        val raw = """[[["Hello","Bonjour",null,null,10]],null,"fr"]"""
        assertEquals("Hello", GoogleWebJson.parse(raw))
    }

    @Test
    fun `concatenates multi-segment replies`() {
        val raw = """[[["Hello, ","Bonjour, ",null,null,1],["how are you?","comment ça va ?",null,null,1]],null,"fr"]"""
        assertEquals("Hello, how are you?", GoogleWebJson.parse(raw))
    }

    @Test
    fun `garbage or empty replies give null`() {
        assertNull(GoogleWebJson.parse("pas du json"))
        assertNull(GoogleWebJson.parse("""[[],null,"fr"]"""))
        assertNull(GoogleWebJson.parse("""[[["","x"]],null,"fr"]"""))
    }

    @Test
    fun `batch reply gives each translation with its detected language`() {
        val raw = """[["Je n'arrive pas à y croire.","en"],["Où vas-tu?","en"],["Je suis là","fr"]]"""
        assertEquals(
            listOf(GoogleSegment("Je n'arrive pas à y croire.", "en"), GoogleSegment("Où vas-tu?", "en"), GoogleSegment("Je suis là", "fr")),
            GoogleWebJson.parseBatch(raw, 3),
        )
    }

    @Test
    fun `batch reply of bare strings and a lone string are accepted`() {
        assertEquals(listOf(GoogleSegment("Bonjour", null), GoogleSegment("Salut", null)), GoogleWebJson.parseBatch("""["Bonjour","Salut"]""", 2))
        assertEquals(listOf(GoogleSegment("Bonjour", null)), GoogleWebJson.parseBatch("\"Bonjour\"", 1))
    }

    @Test
    fun `batch reply with the wrong count or garbage is rejected`() {
        assertNull(GoogleWebJson.parseBatch("""[["a","en"]]""", 2))
        assertNull(GoogleWebJson.parseBatch("pas du json", 1))
        assertEquals(listOf(null, GoogleSegment("b", "en")), GoogleWebJson.parseBatch("""[["","en"],["b","en"]]""", 2))
    }
}
