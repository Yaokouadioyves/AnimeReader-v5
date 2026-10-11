package com.animereader.pipeline

import com.animereader.ocr.Edge
import com.animereader.pipeline.ScrollTracker.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollTrackerTest {

    private fun texts(r: List<ScrollTracker.Ready>) = r.map { it.text }

    @Test fun completeBubbleIsReadOnceItsTextSettles_andNeverAgain() {
        val t = ScrollTracker()
        val b = Seen("Tu crois vraiment que je vais te laisser partir ?", Edge.COMPLETE)
        assertEquals(emptyList<String>(), texts(t.onFrame(listOf(b), still = false))) // first sight
        assertEquals(listOf(b.text), texts(t.onFrame(listOf(b), still = false)))      // settled
        assertEquals(emptyList<String>(), texts(t.onFrame(listOf(b), still = true)))   // already read
    }

    @Test fun bubbleCutAtTheBottomWaitsUntilItIsWhole() {
        val t = ScrollTracker()
        t.onFrame(listOf(Seen("Attends. Tu crois", Edge.CUT_BOTTOM)), still = false)
        t.onFrame(listOf(Seen("Attends. Tu crois vraiment", Edge.CUT_BOTTOM)), still = false)
        val whole = Seen("Attends. Tu crois vraiment que je vais céder ?", Edge.COMPLETE)
        assertEquals(emptyList<String>(), texts(t.onFrame(listOf(whole), still = false)))
        assertEquals(listOf(whole.text), texts(t.onFrame(listOf(whole), still = false)))
    }

    @Test fun pageStoppedOnACutBubbleReadsItOnlyIfItEndsASentence() {
        val t = ScrollTracker()
        val unfinished = Seen("Je ne sais pas si", Edge.CUT_BOTTOM)
        t.onFrame(listOf(unfinished), still = true)
        assertEquals(emptyList<String>(), texts(t.onFrame(listOf(unfinished), still = true)))

        val t2 = ScrollTracker()
        val finished = Seen("Je ne sais pas.", Edge.CUT_BOTTOM)
        t2.onFrame(listOf(finished), still = true)
        assertEquals(listOf(finished.text), texts(t2.onFrame(listOf(finished), still = true)))
    }

    @Test fun tailOfABubbleReadLowerDownIsSkipped() {
        val t = ScrollTracker()
        val whole = Seen("On se retrouve ce soir derrière la gare, compris ?", Edge.COMPLETE)
        t.onFrame(listOf(whole), false); t.onFrame(listOf(whole), false)
        val tail = Seen("derrière la gare, compris ?", Edge.CUT_TOP)
        assertEquals(emptyList<String>(), texts(t.onFrame(listOf(tail), false)))
    }

    @Test fun bubbleTallerThanTheRectangleIsAssembledAndReadWhenItsEndArrives() {
        val t = ScrollTracker()
        t.onFrame(listOf(Seen("Écoute-moi bien, parce que je ne le répéterai pas", Edge.CUT_BOTTOM)), false)
        t.onFrame(listOf(Seen("je ne le répéterai pas deux fois : demain matin", Edge.CUT_BOTH)), false)
        t.onFrame(listOf(Seen("deux fois : demain matin tu pars avec eux", Edge.CUT_BOTH)), false)
        val end = t.onFrame(listOf(Seen("tu pars avec eux, sans discuter.", Edge.CUT_TOP)), false)
        assertEquals(1, end.size)
        assertNull(end[0].index)
        assertEquals(
            "Écoute-moi bien, parce que je ne le répéterai pas deux fois : demain matin tu pars avec eux, sans discuter.",
            end[0].text,
        )
    }

    @Test fun bubbleSeenWholeOnlyOnceBeforeScrollingPastIsStillRead() {
        val t = ScrollTracker()
        val whole = Seen("Il faut partir maintenant, ils arrivent !", Edge.COMPLETE)
        t.onFrame(listOf(whole), false)                                  // seen once, fast scroll
        val r = t.onFrame(listOf(Seen("maintenant, ils arrivent !", Edge.CUT_TOP)), false)
        assertEquals(listOf(whole.text), texts(r))
    }

    @Test fun severalBubblesComeOutInReadingOrder() {
        val t = ScrollTracker()
        val frame = listOf(
            Seen("Première réplique du haut.", Edge.COMPLETE),
            Seen("Deuxième réplique, juste en dessous.", Edge.COMPLETE),
            Seen("Troisième, coupée par le", Edge.CUT_BOTTOM),
        )
        t.onFrame(frame, false)
        val r = t.onFrame(frame, false)
        assertEquals(listOf(0, 1), r.map { it.index })
    }

    @Test fun markReadPreventsARepeatAfterAManualReplay() {
        val t = ScrollTracker()
        val b = Seen("Bon, on y va.", Edge.COMPLETE)
        t.markRead(b.text)
        t.onFrame(listOf(b), false)
        assertEquals(emptyList<String>(), texts(t.onFrame(listOf(b), false)))
    }

    @Test fun stitchNeedsARealOverlap() {
        assertNull(ScrollTracker.stitch("Je pense que tu es", "tu es fatigué"))      // "tu es" too short
        assertEquals(
            "Je pense que tu es vraiment fatigué ce soir",
            ScrollTracker.stitch("Je pense que tu es vraiment", "tu es vraiment fatigué ce soir"),
        )
        assertNull(ScrollTracker.stitch("Rien à voir avec ça", "Une autre bulle complètement"))
    }

    @Test fun stitchToleratesAWordMisreadAtTheCut() {
        // The last line of the first capture was cut by the edge and read as "dem@".
        assertEquals(
            "Je ne le répéterai pas deux fois demain matin",
            ScrollTracker.stitch("Je ne le répéterai pas deux fois dem@", "pas deux fois demain matin"),
        )
    }

    @Test fun endsSentence() {
        assertEquals(true, ScrollTracker.endsSentence("C'est fini."))
        assertEquals(true, ScrollTracker.endsSentence("Quoi ?! »"))
        assertEquals(false, ScrollTracker.endsSentence("Je ne sais pas si"))
    }

    @Test fun stillPageInOverlayModeReleasesEveryWholeBubbleAtOnce_butNeverTwice() {
        val t = ScrollTracker()
        val a = Seen("Tu crois vraiment que je vais te laisser partir ?", Edge.COMPLETE)
        val b = Seen("Attends, je peux t'expliquer.", Edge.CUT_BOTTOM)    // ends a sentence
        val c = Seen("Et puis", Edge.CUT_BOTTOM)                           // really cut
        val d = Seen("la suite de la bulle", Edge.CUT_TOP)
        val frame = listOf(d, a, b, c)
        val first = t.onFrame(frame, still = true)            // seen once: nothing settled yet
        assertEquals(emptyList<String>(), texts(first))
        val flushed = t.flushStill(frame)
        assertEquals(listOf(a.text, b.text), texts(flushed))
        assertEquals(listOf(1, 2), flushed.map { it.index })
        assertEquals(emptyList<String>(), texts(t.onFrame(frame, still = true) + t.flushStill(frame)))
    }

    // ── A bubble already read glued to the new one under it (grouped as one by the OCR) ──

    @Test fun newBubbleGluedUnderAReadOneIsReadAlone() {
        val t = ScrollTracker()
        val old = Seen("Tu es sûr de vouloir y aller seul ?", Edge.COMPLETE)
        t.onFrame(listOf(old), false); t.onFrame(listOf(old), false)       // read
        val glued = Seen("Tu es sûr de vouloir y aller seul ? Oui, je n'ai plus le choix maintenant.", Edge.COMPLETE)
        assertEquals(emptyList<String>(), texts(t.onFrame(listOf(glued), false))) // settling
        val r = t.onFrame(listOf(glued), false)
        assertEquals(listOf("Oui, je n'ai plus le choix maintenant."), texts(r))
        assertNull(r[0].index) // no crop: it would show the old bubble too
        assertEquals(emptyList<String>(), texts(t.onFrame(listOf(glued), true)))  // never twice
    }

    @Test fun shortNewBubbleGluedToALongReadOneIsNotSwallowed() {
        // The glued text "contains" the old one at > 60 %: the plain duplicate test alone
        // would have dropped the new bubble entirely.
        val t = ScrollTracker()
        val old = Seen("Je t'avais pourtant prévenu de ne jamais revenir ici, Kenji.", Edge.COMPLETE)
        t.onFrame(listOf(old), false); t.onFrame(listOf(old), false)
        val glued = Seen(old.text + " Pardon...", Edge.COMPLETE)
        t.onFrame(listOf(glued), false)
        assertEquals(listOf("Pardon..."), texts(t.onFrame(listOf(glued), false)))
    }

    @Test fun newBubbleStillCutAtTheBottomWaitsEvenWhenGlued() {
        val t = ScrollTracker()
        val old = Seen("On se retrouve demain matin.", Edge.COMPLETE)
        t.onFrame(listOf(old), false); t.onFrame(listOf(old), false)
        t.onFrame(listOf(Seen(old.text + " Pas question, je", Edge.CUT_BOTTOM)), false)
        assertEquals(emptyList<String>(), texts(t.onFrame(listOf(Seen(old.text + " Pas question, je", Edge.CUT_BOTTOM)), false)))
        val whole = Seen(old.text + " Pas question, je viens avec toi.", Edge.COMPLETE)
        t.onFrame(listOf(whole), false)
        assertEquals(listOf("Pas question, je viens avec toi."), texts(t.onFrame(listOf(whole), false)))
    }

    @Test fun readBubbleGluedAfterTheNewOneIsStrippedToo() {
        val t = ScrollTracker()
        val old = Seen("Qu'est-ce que tu fabriques encore ?", Edge.COMPLETE)
        t.onFrame(listOf(old), false); t.onFrame(listOf(old), false)
        val glued = Seen("Rien du tout, je range. " + old.text, Edge.COMPLETE)
        t.onFrame(listOf(glued), false)
        assertEquals(listOf("Rien du tout, je range."), texts(t.onFrame(listOf(glued), false)))
    }

    @Test fun anOcrCrumbNextToAReadBubbleIsNotRead() {
        val t = ScrollTracker()
        val old = Seen("Tu es sûr de vouloir y aller seul ?", Edge.COMPLETE)
        t.onFrame(listOf(old), false); t.onFrame(listOf(old), false)
        val crumb = Seen(old.text + " il", Edge.COMPLETE)
        t.onFrame(listOf(crumb), false)
        assertEquals(emptyList<String>(), texts(t.onFrame(listOf(crumb), false)))
    }

    @Test fun stillPageInOverlayModeDoesNotReReadAGluedBubble() {
        val t = ScrollTracker()
        val old = Seen("Tu es sûr de vouloir y aller seul ?", Edge.COMPLETE)
        t.onFrame(listOf(old), false); t.onFrame(listOf(old), false)
        val glued = Seen(old.text + " Oui, je n'ai plus le choix.", Edge.COMPLETE)
        t.onFrame(listOf(glued), still = true)
        assertEquals(listOf("Oui, je n'ai plus le choix."), texts(t.flushStill(listOf(glued))))
    }
}
