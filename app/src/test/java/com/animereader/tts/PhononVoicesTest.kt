package com.animereader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhononVoicesTest {

    @Test fun frenchLinesUseTheBundledVoices() {
        assertEquals("Augustin", PhononVoices.voiceFor("fr", "Masculine"))
        assertEquals("Augustin", PhononVoices.voiceFor("fr", "Neutre"))
        assertEquals("Noemie", PhononVoices.voiceFor("fr", "Féminine"))
        assertEquals("Augustin", PhononVoices.voiceFor("fr-FR", "Masculine"))
        assertEquals("Augustin", PhononVoices.voiceFor("FR", "Masculine"))
    }

    @Test fun otherLanguagesGoToTheFallback() {
        assertNull(PhononVoices.voiceFor("en", "Masculine"))
        assertNull(PhononVoices.voiceFor("es", "Féminine"))
        assertNull(PhononVoices.voiceFor("", "Masculine"))
    }
}
