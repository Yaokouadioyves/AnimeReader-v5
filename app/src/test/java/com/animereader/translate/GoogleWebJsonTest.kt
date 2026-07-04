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
}
