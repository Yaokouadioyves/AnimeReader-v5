package com.animereader.tts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechQueueTest {

    private fun item(id: String) = SpeechItem(id = id, text = id, langTag = "fr", rate = 1f, timbre = "Neutre")

    @Test
    fun `speaks items sequentially, waiting for the done signal`() = runTest {
        val spoken = mutableListOf<String>()
        val q = SpeechQueue(scope = this, speak = { spoken.add(it.id) })
        q.enqueue(item("a")); q.enqueue(item("b"))
        runCurrent()
        assertEquals(listOf("a"), spoken)   // b waits until a finishes
        q.signalDone("a")
        runCurrent()
        assertEquals(listOf("a", "b"), spoken)
        q.signalDone("b")
        runCurrent()
        assertEquals(null, q.currentId)
    }

    @Test
    fun `a done signal for another id is ignored`() = runTest {
        val spoken = mutableListOf<String>()
        val q = SpeechQueue(scope = this, speak = { spoken.add(it.id) })
        q.enqueue(item("a")); q.enqueue(item("b"))
        runCurrent()
        q.signalDone("zzz")
        runCurrent()
        assertEquals(listOf("a"), spoken)
    }

    @Test
    fun `safety timeout advances a stuck queue`() = runTest {
        val spoken = mutableListOf<String>()
        val q = SpeechQueue(scope = this, speak = { spoken.add(it.id) }, doneTimeoutMs = 30_000)
        q.enqueue(item("a")); q.enqueue(item("b"))
        runCurrent()
        assertEquals(listOf("a"), spoken)
        advanceTimeBy(30_001)   // never signalled → timeout releases it
        runCurrent()
        assertEquals(listOf("a", "b"), spoken)
    }

    @Test
    fun `backlog overflow drops the oldest waiting items`() = runTest {
        val spoken = mutableListOf<String>()
        val q = SpeechQueue(scope = this, speak = { spoken.add(it.id) }, maxQueued = 2)
        q.enqueue(item("a")); q.enqueue(item("b")); q.enqueue(item("c")); q.enqueue(item("d"))
        runCurrent()             // queue was trimmed to [c, d] before the worker started
        assertEquals(listOf("c"), spoken)
        q.signalDone("c"); runCurrent()
        q.signalDone("d"); runCurrent()
        assertEquals(listOf("c", "d"), spoken)
    }

    @Test
    fun `clear drops the backlog and releases the current item`() = runTest {
        val spoken = mutableListOf<String>()
        val q = SpeechQueue(scope = this, speak = { spoken.add(it.id) })
        q.enqueue(item("a")); q.enqueue(item("b"))
        runCurrent()
        q.clear()
        runCurrent()
        assertEquals(listOf("a"), spoken)
        assertEquals(null, q.currentId)
    }

    @Test
    fun `start callback reports each item before speaking`() = runTest {
        val started = mutableListOf<String>()
        val q = SpeechQueue(scope = this, speak = {}, onItemStart = { started.add(it.id) })
        q.enqueue(item("a"))
        runCurrent()
        assertEquals(listOf("a"), started)
    }
}
