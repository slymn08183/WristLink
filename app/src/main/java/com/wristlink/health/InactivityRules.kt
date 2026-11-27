package com.wristlink.health

import java.util.Calendar
import java.util.TimeZone

/**
 * The pure parts of the "Time to move" reminder: what counts as moving, and when it may fire.
 * No Android here, so it can be unit tested.
 */
internal object InactivityRules {

    const val MINUTE_MS = 60_000L
    const val INACTIVE_MS = 50 * MINUTE_MS

    /**
     * Moving means at least this many steps within [MOVE_WINDOW_MIN] minutes. Shifting in a chair
     * or reaching for something gives a handful of steps; getting up and walking anywhere gives
     * dozens a minute. Five minutes rather than one because Health Services' totals can be
     * coarse, and a short walk spread over a long interval still adds up to the same steps.
     */
    const val MOVE_MIN_STEPS = 30.0
    const val MOVE_WINDOW_MIN = 5

    private const val WINDOW_START_HOUR = 9
    private const val WINDOW_HOURS = 8

    /**
     * When the wearer last really moved, from per-minute step counts (minute start → steps): the
     * end of the newest minute with steps whose last [MOVE_WINDOW_MIN] minutes reach
     * [MOVE_MIN_STEPS]; null when no stretch qualifies.
     */
    fun lastMove(minutes: Map<Long, Double>): Long? {
        for (t in minutes.keys.sortedDescending()) {
            if ((minutes[t] ?: 0.0) <= 0) continue
            val steps = (0 until MOVE_WINDOW_MIN).sumOf { minutes[t - it * MINUTE_MS] ?: 0.0 }
            if (steps >= MOVE_MIN_STEPS) return t + MINUTE_MS
        }
        return null
    }

    /** [now] when inside 09:00–17:00 on a weekday, else when that window next opens. */
    fun nextWindowStart(now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(zone).apply { timeInMillis = now }
        repeat(8) {
            val weekday = c.get(Calendar.DAY_OF_WEEK) !in setOf(Calendar.SATURDAY, Calendar.SUNDAY)
            val start = (c.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, WINDOW_START_HOUR); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val end = start + WINDOW_HOURS * 60 * MINUTE_MS
            if (weekday && now < end) return maxOf(now, start)
            c.add(Calendar.DAY_OF_YEAR, 1)
            c.set(Calendar.HOUR_OF_DAY, 0)
        }
        return now
    }

    /**
     * A check planned for [at], moved to [INACTIVE_MS] after the window next opens when [at]
     * falls outside it: no point waking the watch in the evening or at the weekend just to find
     * it may not remind, and a full 50 minutes into the day gives the wearer time to get going.
     */
    fun inWindow(at: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val window = nextWindowStart(at, zone)
        return if (window == at) at else window + INACTIVE_MS
    }
}
