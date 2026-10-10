package com.animereader.internal

/**
 * Free-tier pacing for one online provider: a minimum gap between calls plus a longer
 * cooldown after the provider answers 429. Clock-injected by the caller so it is
 * JVM-unit-testable (no android.* and no real time).
 */
class RateGate(
    private val minGapMs: Long,
    private val cooldownMs: Long,
) {
    private var lastCall = Long.MIN_VALUE / 2
    private var cooldownUntil = Long.MIN_VALUE / 2

    /** True when a call is allowed at [now]; records the attempt so the gap restarts. */
    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        if (now < cooldownUntil || now - lastCall < minGapMs) return false
        lastCall = now
        return true
    }

    /** The provider said 429 — hold every call for the cooldown window. */
    @Synchronized
    fun reportRateLimited(now: Long) {
        cooldownUntil = now + cooldownMs
    }

    @Synchronized
    fun inCooldown(now: Long): Boolean = now < cooldownUntil
}
