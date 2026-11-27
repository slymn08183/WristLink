package com.wristlink.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyTotalsTest {

    private val min = 60_000L
    private val day = 24 * 60 * min
    private val yesterday = 0L
    private val today = day

    @Test
    fun countsWhatWasAddedSinceTheLastTotal() {
        val (state, deltas) = DailyTotals.fold(
            null,
            listOf(Triple(today, today + 60 * min, 500.0), Triple(today, today + 70 * min, 800.0))
        )
        assertEquals(listOf(500.0, 300.0), deltas.map { it.amount })
        assertEquals(today, deltas[0].from)
        assertEquals(today + 60 * min, deltas[1].from)
        assertEquals(DailyTotals.State(today, today + 70 * min, 800.0), state)
    }

    @Test
    fun yesterdaysTotalEndingAfterMidnightIsNotAddedToToday() {
        val last = DailyTotals.State(yesterday, today - 5 * min, 9_000.0)
        val (state, deltas) = DailyTotals.fold(
            last,
            listOf(
                // Yesterday's total, measured a few seconds after midnight
                Triple(yesterday, today + 30_000, 9_040.0),
                // Today's first total
                Triple(today, today + 3 * min, 25.0),
                Triple(today, today + 10 * min, 60.0)
            )
        )
        assertEquals(listOf(40.0, 25.0, 35.0), deltas.map { it.amount })
        assertEquals(today, deltas[1].from)
        assertEquals(DailyTotals.State(today, today + 10 * min, 60.0), state)
    }

    @Test
    fun aLatePointFromYesterdayIsIgnoredOnceTodayStarted() {
        val last = DailyTotals.State(today, today + 10 * min, 60.0)
        val (state, delta) = DailyTotals.next(last, yesterday, today - min, 9_100.0)
        assertNull(state)
        assertNull(delta)
    }

    @Test
    fun aSmallDipKeepsTheBaselineAndABigDropStartsANewOne() {
        val last = DailyTotals.State(today, today + 60 * min, 1_000.0)
        val (dipState, dipDelta) = DailyTotals.next(last, today, today + 61 * min, 990.0)
        assertNull(dipState)
        assertNull(dipDelta)

        val (dropState, dropDelta) = DailyTotals.next(last, today, today + 62 * min, 50.0)
        assertEquals(DailyTotals.State(today, today + 62 * min, 50.0), dropState)
        assertNull(dropDelta)
        // Counting goes on from the new baseline
        val (_, after) = DailyTotals.next(dropState, today, today + 63 * min, 80.0)
        assertEquals(30.0, after!!.amount, 0.0)
    }

    @Test
    fun startJitterIsTheSamePeriod() {
        val last = DailyTotals.State(today, today + 60 * min, 1_000.0)
        val (_, delta) = DailyTotals.next(last, today + 2_000, today + 61 * min, 1_010.0)
        assertEquals(10.0, delta!!.amount, 0.0)
        assertEquals(today + 60 * min, delta.from)
    }

    @Test
    fun aDuplicatePointAddsNothing() {
        val last = DailyTotals.State(today, today + 60 * min, 1_000.0)
        val (state, delta) = DailyTotals.next(last, today, today + 60 * min, 1_000.0)
        assertNull(state)
        assertNull(delta)
    }
}
