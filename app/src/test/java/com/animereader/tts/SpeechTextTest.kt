package com.animereader.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {

    private fun p(s: String) = SpeechText.prepare(s)

    @Test fun stretchedLettersAreReadAsOne() {
        assertEquals("Non !", p("Noooooon !"))
        assertEquals("Ah…", p("Ahhhh..."))
        assertEquals("Hm ?", p("Hmmm ?"))
    }

    @Test fun normalDoubleLettersAreKept() {
        assertEquals("Elle appelle ses amis, c'est terrible.", p("Elle appelle ses amis, c'est terrible."))
        assertEquals("Attends !", p("Attends !"))
    }

    @Test fun stackedPunctuationIsSimplified() {
        assertEquals("Quoi ?!", p("Quoi ?!?!"))
        assertEquals("Quoi ?!", p("Quoi !?"))
        assertEquals("Arrête !", p("Arrête !!!"))
        assertEquals("Hein ?", p("Hein ???"))
        assertEquals("Je…", p("Je....."))
    }

    @Test fun decorationAndLeadInsAreDropped() {
        assertEquals("Bonjour", p("♪ Bonjour ~ ♥"))
        assertEquals("Quoi ?", p("... Quoi ?"))
        assertEquals("C'est lui.", p("— C'est lui."))
        assertEquals("Peut-être.", p("Peut-être."))
    }

    @Test fun nothingLeftToSay() {
        assertEquals("", p("..."))
        assertEquals("", p("♪ ~ ♪"))
    }
}
