package com.wristlink.health

/**
 * Turns Health Services' daily running totals (steps, distance, calories, floors) into what was
 * added since the last one. Pure logic, no Android, so it can be unit tested.
 *
 * Each total covers [start]..[end], where start is when Health Services began counting (local
 * midnight, or a restart of its own). A total belongs to the period of its START, never its end:
 * a point ending at 00:00:30 can still carry yesterday's total, and keying it on its end would add
 * yesterday's steps to today. Comparing start instants rather than calendar days also keeps a
 * timezone change from splitting or merging a period, since instants don't move with the zone.
 */
internal object DailyTotals {

    /** Starts this close together are the same period (boot-time conversion jitters a little). */
    const val SAME_PERIOD_SLACK_MS = 5 * 60_000L

    private const val DAY_MS = 24L * 3600 * 1000

    /** The last total seen: when its period began, when it was measured, and its value. */
    data class State(val start: Long, val time: Long, val total: Double)

    /** [amount] added over [from]..[to]; [total] is the running total it came from. */
    data class Delta(val from: Long, val to: Long, val amount: Double, val total: Double, val periodStart: Long)

    /**
     * Feed one total. Returns the state to keep (null: keep the old one) and what was added
     * (null: nothing to count).
     */
    fun next(last: State?, start: Long, end: Long, total: Double): Pair<State?, Delta?> {
        if (last == null || start > last.start + SAME_PERIOD_SLACK_MS) {
            // A new period (midnight, or Health Services restarted its count): all of it is new.
            // Spread from its start, but never over more than a day.
            val from = start.coerceIn(end - DAY_MS, end)
            val delta = if (total > 0 && end > from) Delta(from, end, total, total, start) else null
            return State(start, end, total) to delta
        }
        // A late point from an older period: its steps were counted (or are lost) already
        if (start < last.start - SAME_PERIOD_SLACK_MS) return null to null
        // Same period. Already seen, or out of order within a batch: nothing new
        if (end <= last.time) return null to null
        if (total >= last.total) {
            val added = total - last.total
            val delta = if (added > 0) Delta(last.time, end, added, total, last.start) else null
            return State(last.start, end, total) to delta
        }
        // Went down within the same period. A small dip is Health Services revising its count:
        // keep the higher baseline so the steps aren't counted twice when it climbs back. A big
        // drop is a reset; start again from the new value (what it holds can't be placed in time,
        // so it isn't counted rather than risk counting it twice).
        if (total < last.total * 0.5) return State(last.start, end, total) to null
        return null to null
    }

    /** Feed a batch of (start, end, total), oldest first by end. */
    fun fold(last: State?, totals: List<Triple<Long, Long, Double>>): Pair<State?, List<Delta>> {
        var state = last
        val deltas = mutableListOf<Delta>()
        for ((start, end, total) in totals.sortedBy { it.second }) {
            val (next, delta) = next(state, start, end, total)
            if (next != null) state = next
            if (delta != null) deltas += delta
        }
        return state to deltas
    }
}
