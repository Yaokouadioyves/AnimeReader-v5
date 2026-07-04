package com.animereader.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateGateTest {

    @Test
    fun `first acquire is allowed`() {
        val g = RateGate(minGapMs = 1000, cooldownMs = 5000)
        assertTrue(g.tryAcquire(0))
    }

    @Test
    fun `respects the minimum gap between calls`() {
        val g = RateGate(minGapMs = 1000, cooldownMs = 5000)
        assertTrue(g.tryAcquire(1000))
        assertFalse(g.tryAcquire(1500))
        assertFalse(g.tryAcquire(1999))
        assertTrue(g.tryAcquire(2000))
    }

    @Test
    fun `denied attempts do not reset the gap`() {
        val g = RateGate(minGapMs = 1000, cooldownMs = 5000)
        assertTrue(g.tryAcquire(0))
        assertFalse(g.tryAcquire(900))   // denied — must not push the next window
        assertTrue(g.tryAcquire(1000))
    }

    @Test
    fun `cooldown blocks until it expires`() {
        val g = RateGate(minGapMs = 0, cooldownMs = 5000)
        g.reportRateLimited(2000)
        assertTrue(g.inCooldown(2001))
        assertFalse(g.tryAcquire(6999))
        assertFalse(g.inCooldown(7000))
        assertTrue(g.tryAcquire(7000))
    }
}
