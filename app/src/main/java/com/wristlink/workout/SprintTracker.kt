package com.wristlink.workout

import kotlin.math.max

/**
 * Sprints, top speed and running intensity from speed readings about once a second, the way
 * football tracking vests count them.
 *
 * - A sprint starts after a second at [SPRINT_KMH] or faster and lasts until the speed drops
 *   under [SPRINT_END_KMH] (the gap keeps a stumble mid-sprint from counting as two).
 * - Speed bands, by time and distance: walking under 7 km/h, jogging to 15, running to 20,
 *   sprinting above.
 * - Each reading is averaged with the previous one, and anything over [MAX_KMH] is dropped:
 *   one GPS jump would otherwise be the match's top speed.
 *
 * Readings must come in time order; out-of-order or duplicate ones are skipped. Gaps over
 * [MAX_GAP_MS] (and paused time) break the chain, so they never count as distance.
 */
class SprintTracker {

    companion object {
        const val SPRINT_KMH = 20.0
        const val SPRINT_END_KMH = 16.0
        const val HIGH_INTENSITY_KMH = 15.0
        const val MAX_KMH = 36.0
        private const val MIN_SPRINT_MS = 1_000L
        private const val MAX_GAP_MS = 5_000L

        /** Upper edges of the walk, jog and run bands; sprinting is everything above. */
        val BAND_EDGES_KMH = doubleArrayOf(7.0, 15.0, 20.0)
        val BAND_LABELS = listOf("Walk", "Jog", "Run", "Sprint")

        private fun ms(kmh: Double) = kmh / 3.6
    }

    var sprints = 0; private set
    /** m/s */
    var maxSpeed = 0.0; private set
    var sprintDistance = 0.0; private set
    var longestSprint = 0.0; private set
    /** Distance at [HIGH_INTENSITY_KMH] or faster, sprints included. */
    var highIntensityDistance = 0.0; private set
    val bandMs = LongArray(4)
    val bandDistance = DoubleArray(4)

    private var lastT: Long? = null
    private var lastRaw: Double? = null
    private var inSprint = false
    private var sprintRun = 0.0
    private var fastMs = 0L
    private var fastDistance = 0.0

    /** Returns true when this reading started a new sprint. */
    fun onSpeed(t: Long, speed: Double, paused: Boolean): Boolean {
        val prevT = lastT
        if (prevT != null && t <= prevT) return false
        if (speed < 0 || speed > ms(MAX_KMH)) return false
        val prevRaw = lastRaw
        lastT = t
        lastRaw = speed
        val dt = if (prevT == null) 0L else t - prevT
        if (paused || prevRaw == null || dt > MAX_GAP_MS) {
            endSprint()
            return false
        }

        val v = (speed + prevRaw) / 2
        val d = v * dt / 1000.0
        maxSpeed = max(maxSpeed, v)
        val band = BAND_EDGES_KMH.indexOfFirst { v < ms(it) }.let { if (it < 0) 3 else it }
        bandMs[band] += dt
        bandDistance[band] += d
        if (v >= ms(HIGH_INTENSITY_KMH)) highIntensityDistance += d

        if (inSprint) {
            if (v >= ms(SPRINT_END_KMH)) sprintRun += d else endSprint()
            return false
        }
        if (v >= ms(SPRINT_KMH)) {
            fastMs += dt
            fastDistance += d
            if (fastMs >= MIN_SPRINT_MS) {
                inSprint = true
                sprints++
                sprintRun = fastDistance
                return true
            }
        } else {
            fastMs = 0
            fastDistance = 0.0
        }
        return false
    }

    /** Close a sprint still running (at the end, or before a pause). */
    fun endSprint() {
        if (inSprint) {
            sprintDistance += sprintRun
            longestSprint = max(longestSprint, sprintRun)
        }
        inSprint = false
        sprintRun = 0.0
        fastMs = 0
        fastDistance = 0.0
    }
}
