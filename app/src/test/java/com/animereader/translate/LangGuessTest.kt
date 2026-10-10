package com.animereader.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LangGuessTest {

    @Test
    fun `French scan lines are French`() {
        assertEquals("fr", LangGuess.guess("Je ne sais pas ce qu'il veut."))
        assertEquals("fr", LangGuess.guess("C'EST PEUT-ÊTRE LUI QUI A FAIT ÇA !"))
        assertEquals("fr", LangGuess.guess("Quoi ?!"))
        assertEquals("fr", LangGuess.guess("Il est là."))
    }

    @Test
    fun `English scan lines are English`() {
        assertEquals("en", LangGuess.guess("I don't know what you want."))
        assertEquals("en", LangGuess.guess("WHERE ARE YOU GOING?"))
        assertEquals("en", LangGuess.guess("What?!"))
        assertEquals("en", LangGuess.guess("It's over."))
    }

    @Test
    fun `scripts settle CJK and Hangul at once`() {
        assertEquals("ja", LangGuess.guess("なにをしている"))
        assertEquals("ko", LangGuess.guess("뭐 하는 거야"))
        assertEquals("zh", LangGuess.guess("你在做什么"))
    }

    @Test
    fun `names, sound effects and too-short text stay unknown`() {
        assertNull(LangGuess.guess("Luffy"))
        assertNull(LangGuess.guess("BOOM"))
        assertNull(LangGuess.guess(""))
    }
}
