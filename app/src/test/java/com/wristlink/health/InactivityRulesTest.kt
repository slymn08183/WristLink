package com.wristlink.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class InactivityRulesTest {

    private val zone = TimeZone.getTimeZone("Europe/Istanbul")
    private val min = InactivityRules.MINUTE_MS

    /** 2026-11-23 is a Monday. */
    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.NOVEMBER, day, hour, minute)
    }.timeInMillis

    @Test
    fun strayStepsAtADeskAreNotMoving() {
        val t = at(23, 10)
        // A few steps here and there over half an hour
        val minutes = mapOf(t to 4.0, t + 7 * min to 6.0, t + 15 * min to 5.0, t + 28 * min to 8.0)
        assertNull(InactivityRules.lastMove(minutes))
    }

    @Test
    fun aShortWalkIsMovingAndTheNewestOneCounts() {
        val t = at(23, 10)
        val minutes = mapOf(
            t to 40.0, t + min to 35.0,          // a walk
            t + 20 * min to 5.0,                 // a stray step later on
            t + 30 * min to 18.0, t + 31 * min to 15.0 // another, slower walk
        )
        assertEquals(t + 32 * min, InactivityRules.lastMove(minutes))
    }

    @Test
    fun aWalkSpreadOverACoarseIntervalStillCounts() {
        val t = at(23, 10)
        // 70 steps reported over 10 minutes: 7 a minute
        val minutes = (0 until 10).associate { t + it * min to 7.0 }
        assertEquals(t + 10 * min, InactivityRules.lastMove(minutes))
    }

    @Test
    fun insideTheWindowStaysPut() {
        assertEquals(at(23, 11, 30), InactivityRules.inWindow(at(23, 11, 30), zone))
    }

    @Test
    fun eveningMovesToNextMorningPlusFiftyMinutes() {
        assertEquals(at(24, 9, 50), InactivityRules.inWindow(at(23, 17, 20), zone))
        assertEquals(at(24, 9, 50), InactivityRules.inWindow(at(24, 7, 0), zone))
    }

    @Test
    fun fridayEveningMovesToMonday() {
        assertEquals(at(30, 9, 50), InactivityRules.inWindow(at(27, 18, 0), zone))
        assertEquals(at(30, 9, 50), InactivityRules.inWindow(at(29, 12, 0), zone))
    }
}
