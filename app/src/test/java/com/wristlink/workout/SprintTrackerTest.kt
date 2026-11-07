package com.wristlink.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SprintTrackerTest {

    private fun kmh(v: Double) = v / 3.6

    /** Feeds one reading a second, starting at t = 0. */
    private fun SprintTracker.feed(vararg speedsKmh: Double, from: Long = 0, paused: Boolean = false): Long {
        var t = from
        for (v in speedsKmh) {
            onSpeed(t, kmh(v), paused)
            t += 1000
        }
        return t
    }

    @Test
    fun countsOneSprintThroughAStumble() {
        val s = SprintTracker()
        // Jog, sprint for 4 s with a dip to 17 km/h (still above the end threshold), jog again
        s.feed(10.0, 10.0, 22.0, 24.0, 17.0, 23.0, 22.0, 10.0, 10.0)
        assertEquals(1, s.sprints)
        s.endSprint()
        assertTrue(s.sprintDistance > 20)
        assertTrue(s.maxSpeed * 3.6 in 22.0..24.0)
    }

    @Test
    fun aSingleGpsSpikeIsNotASprintOrTopSpeed() {
        val s = SprintTracker()
        s.feed(8.0, 8.0, 30.0, 8.0, 8.0, 50.0, 8.0)
        assertEquals(0, s.sprints)
        // Averaged with its neighbour, the 30 km/h spike reads 19 km/h; 50 is dropped outright
        assertTrue(s.maxSpeed * 3.6 < 20)
    }

    @Test
    fun twoSeparateSprints() {
        val s = SprintTracker()
        s.feed(10.0, 25.0, 25.0, 25.0, 8.0, 8.0, 8.0, 26.0, 26.0, 26.0, 8.0)
        assertEquals(2, s.sprints)
    }

    @Test
    fun pausesAndGapsDontCountAsDistance() {
        val s = SprintTracker()
        val t = s.feed(10.0, 10.0)
        s.feed(10.0, 10.0, from = t + 60_000)
        // Only the second of each pair adds distance: 2 × 10 km/h × 1 s
        assertEquals(2 * kmh(10.0), s.bandDistance.sum(), 0.01)
    }
}
