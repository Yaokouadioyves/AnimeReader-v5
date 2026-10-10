package com.animereader.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTextTest {

    @Test
    fun `lines of a bubble are joined with spaces`() {
        assertEquals("JE NE SAIS PAS CE QU'IL VEUT.", ScanText.joinLines(listOf("JE NE SAIS PAS", "CE QU'IL", "VEUT.")))
    }

    @Test
    fun `a word cut by the lettering is glued back without its hyphen`() {
        assertEquals("IL FAUT ATTENDRE ICI.", ScanText.joinLines(listOf("IL FAUT ATTEN-", "DRE ICI.")))
        assertEquals("WE NEED SOMETHING ELSE", ScanText.joinLines(listOf("WE NEED SOME-", "THING ELSE")))
    }

    @Test
    fun `a real compound keeps its hyphen across lines`() {
        assertEquals("C'EST PEUT-ÊTRE LUI.", ScanText.joinLines(listOf("C'EST PEUT-", "ÊTRE LUI.")))
        assertEquals("QU'EST-CE QUE TU FAIS ?", ScanText.joinLines(listOf("QU'EST-", "CE QUE TU FAIS ?")))
        assertEquals("Y A-T-IL QUELQU'UN ?", ScanText.joinLines(listOf("Y A-T-", "IL QUELQU'UN ?")))
        assertEquals("DIS-MOI TOUT.", ScanText.joinLines(listOf("DIS-", "MOI TOUT.")))
    }

    @Test
    fun `dots are normalised and stray spaces before punctuation removed`() {
        assertEquals("ATTENDS… QUOI ?", ScanText.joinLines(listOf("ATTENDS . . .", "QUOI ?")))
        assertEquals("NON, MERCI.", ScanText.joinLines(listOf("NON ,", "MERCI .")))
    }

    @Test
    fun `all caps becomes sentence case with the English pronoun I kept`() {
        assertEquals("Je ne sais pas. Et toi ?", ScanText.sentenceCase("JE NE SAIS PAS. ET TOI ?"))
        assertEquals("I think I'm lost. Where are we?", ScanText.sentenceCase("I THINK I'M LOST. WHERE ARE WE?"))
        assertEquals("Attends… je reviens !", ScanText.sentenceCase("ATTENDS… JE REVIENS !"))
    }

    @Test
    fun `text that already has normal casing is left alone`() {
        assertEquals("Il est là, Luffy.", ScanText.sentenceCase("Il est là, Luffy."))
        assertEquals("OK", ScanText.sentenceCase("OK"))
    }

    @Test
    fun `typical caps-lettering OCR confusions are fixed`() {
        assertEquals("IT'S OVER", ScanText.fixOcrConfusions("lT'S OVER"))
        assertEquals("I DON'T KNOW", ScanText.fixOcrConfusions("| DON'T KNOW"))
        assertEquals("COMME TOUJOURS", ScanText.fixOcrConfusions("C0MME TOUJOURS"))
        assertEquals("L'AMOUR", ScanText.fixOcrConfusions("l'AMOUR"))
        assertEquals("I'M HERE", ScanText.fixOcrConfusions("l'M HERE"))
    }

    @Test
    fun `numbers and normal text are not touched by the fix-ups`() {
        assertEquals("À 10H, LE 1ER", ScanText.fixOcrConfusions("À 10H, LE 1ER"))
        assertEquals("Il est là", ScanText.fixOcrConfusions("Il est là"))
        assertEquals("Hello world", ScanText.fixOcrConfusions("Hello world"))
    }

    @Test
    fun `clean chains joining, fix-ups and sentence case`() {
        assertEquals("C'est peut-être lui. Il faut attendre.", ScanText.clean(listOf("C'EST PEUT-", "ÊTRE LUI. IL FAUT ATTEN-", "DRE.")))
    }

    @Test
    fun `watermarks, credits and stray marks are noise`() {
        assertTrue(ScanText.isNoise("www.scan-vf.net"))
        assertTrue(ScanText.isNoise("SCAN-MANGA.COM"))
        assertTrue(ScanText.isNoise("Trad : Kenji"))
        assertTrue(ScanText.isNoise("CHECK: Lou"))
        assertTrue(ScanText.isNoise("discord.gg/abcd"))
        assertTrue(ScanText.isNoise("12"))
        assertTrue(ScanText.isNoise("!?"))
    }

    @Test
    fun `dialogue is not noise, even with a period before a short word`() {
        assertFalse(ScanText.isNoise("I want to go. To the city!"))
        assertFalse(ScanText.isNoise("C'est fini. Tout est perdu."))
        assertFalse(ScanText.isNoise("Oh."))
        assertFalse(ScanText.isNoise("Je traduis ça pour toi."))
    }
}
