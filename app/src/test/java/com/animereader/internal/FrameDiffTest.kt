package com.animereader.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameDiffTest {

    @Test
    fun `identical grids report zero change`() {
        val g = intArrayOf(10, 20, 30, 40)
        assertEquals(0.0, FrameDiff.changedFraction(g, g.copyOf()), 1e-9)
    }

    @Test
    fun `counts only cells beyond the tolerance`() {
        val prev = intArrayOf(100, 100, 100, 100)
        val cur = intArrayOf(100, 104, 100, 200) // +4 is within tolerance 8, +100 is not
        assertEquals(0.25, FrameDiff.changedFraction(prev, cur), 1e-9)
    }

    @Test
    fun `missing or mismatched previous grid counts as fully changed`() {
        assertEquals(1.0, FrameDiff.changedFraction(null, intArrayOf(1, 2)), 1e-9)
        assertEquals(1.0, FrameDiff.changedFraction(intArrayOf(1), intArrayOf(1, 2)), 1e-9)
    }

    @Test
    fun `luminance maps black and white to the extremes`() {
        val lum = FrameDiff.luminance(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        assertEquals(0, lum[0])
        assertEquals(255, lum[1])
    }

    @Test
    fun `ignored cells (our own drawn translations) do not count as a change`() {
        val prev = intArrayOf(100, 100, 100, 100)
        val cur = intArrayOf(250, 100, 100, 100)
        val ignore = booleanArrayOf(true, false, false, false)
        assertEquals(0.0, FrameDiff.changedFraction(prev, cur, ignore = ignore), 1e-9)
        assertEquals(1.0 / 3, FrameDiff.changedFraction(prev, intArrayOf(250, 0, 100, 100), ignore = ignore), 1e-9)
        assertEquals(0.0, FrameDiff.changedFraction(prev, cur, ignore = BooleanArray(4) { true }), 1e-9)
    }
}
