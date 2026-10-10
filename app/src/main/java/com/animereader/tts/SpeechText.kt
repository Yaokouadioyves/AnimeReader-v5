package com.animereader.tts

/**
 * Turns a bubble's text into something a voice reads well. Scans are written for the eye:
 * stretched letters ("NOOOON", "Ahhhh"), stacked punctuation ("?!?!", "!!!"), decoration
 * (♪ ♥ ~ *) and dash/ellipsis lead-ins. Read as-is, a TTS spells them out or stumbles.
 * The caption keeps the original text; only the spoken copy goes through here. Pure (JVM-tested).
 */
object SpeechText {

    private val decoration = Regex("[♪♫♬♥♡❤★☆✦✧※•·~～*#_|\\[\\]{}<>^]")
    private val stretched = Regex("(\\p{L})\\1{2,}")          // 3+ identical letters → 1
    private val mixedMarks = Regex("[!?]*\\?[!?]*!+[!?]*|[!?]*![!?]*\\?+[!?]*") // ?!?! / !?
    private val bangs = Regex("!{2,}")
    private val questions = Regex("\\?{2,}")
    private val dots = Regex("\\.{2,}|…+")
    private val leadIn = Regex("^[\\s…\\-–—.,]+")
    private val spaces = Regex("\\s+")

    fun prepare(text: String): String {
        var t = text.replace(decoration, " ")
        t = t.replace(stretched, "$1")
        t = t.replace(mixedMarks, "?!")
        t = t.replace(bangs, "!").replace(questions, "?")
        t = t.replace(dots, "…")
        t = t.replace(spaces, " ").trim()
        t = t.replace(leadIn, "")
        return t.trim()
    }
}
