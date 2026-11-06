package com.wristlink.workout

import com.wristlink.health.HealthHistory
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Everything a workout records, kept in memory until it ends: one point per second (position,
 * altitude, heart rate, speed, distance so far), pauses, laps and time in each heart-rate zone.
 * [finish] turns it into what [HealthHistory.addWorkout] stores.
 *
 * Health Services batches data while the screen is off, so points are filed by the time they
 * were measured, not when they arrived. Not thread-safe: [WorkoutService] calls it from the
 * main thread only.
 */
class WorkoutRecorder(
    val type: WorkoutType,
    val startMs: Long,
    private val maxHr: Int
) {
    private class Point {
        var lat: Double? = null
        var lon: Double? = null
        var alt: Double? = null
        var hr: Double? = null
        var speed: Double? = null
        var distance: Double? = null
    }

    data class Result(
        val workout: HealthHistory.Workout,
        val laps: List<HealthHistory.Lap>,
        val track: List<HealthHistory.TrackPoint>,
        /** Last heart rate and when, for the recovery measurement. */
        val lastHr: Pair<Long, Double>?
    )

    /** Keyed by whole second (epoch ms, floored). */
    private val points = TreeMap<Long, Point>()

    /** Closed pauses as [start, end], plus the open one. */
    private val pauses = ArrayList<LongArray>()
    private var pausedSince: Long? = null

    private val laps = ArrayList<WorkoutState.Lap>()
    private var lapStart = startMs
    private var lapStartDistance = 0.0

    // Totals as Health Services reports them
    var distanceTotal = 0.0; private set
    var calories = 0.0; private set
    var steps = 0L; private set
    var elevationGainTotal: Double? = null; private set

    /** Distance summed from the DISTANCE deltas, which carry their own times. */
    private var runningDistance = 0.0
    private var haveDistanceDeltas = false

    private var hrSum = 0.0
    private var hrCount = 0
    private var hrMax: Double? = null
    private var lastHrT: Long? = null
    private var lastHrZone = 0
    private var lastHr: Pair<Long, Double>? = null
    val zoneMs = LongArray(HrZones.COUNT)

    private var cadenceSum = 0L
    private var cadenceCount = 0
    private var cadenceMax = 0L

    private val recentSpeeds = ArrayDeque<Double>()

    val distance: Double get() = max(distanceTotal, runningDistance)

    private fun point(t: Long): Point = points.getOrPut(t - Math.floorMod(t, 1000L)) { Point() }

    private fun pausedAt(t: Long): Boolean {
        pausedSince?.let { if (t >= it) return true }
        return pauses.any { t >= it[0] && t < it[1] }
    }

    // ---- Input ----

    fun onHeartRate(t: Long, bpm: Double) {
        if (bpm <= 0) return
        point(t).hr = bpm
        hrSum += bpm
        hrCount++
        hrMax = max(hrMax ?: 0.0, bpm)
        lastHr = t to bpm

        // The time since the previous reading counts towards that reading's zone; gaps over
        // 15 s (sensor off, batching hiccups) don't count at all
        val prev = lastHrT
        if (prev != null && t > prev && t - prev <= 15_000 && lastHrZone > 0 && !pausedAt(t)) {
            zoneMs[lastHrZone - 1] += t - prev
        }
        if (prev == null || t > prev) {
            lastHrT = t
            lastHrZone = HrZones.zoneOf(bpm, maxHr)
        }
    }

    fun onLocation(t: Long, lat: Double, lon: Double, alt: Double?) {
        val p = point(t)
        p.lat = lat
        p.lon = lon
        if (alt != null) p.alt = alt
    }

    /** Returns the speed smoothed over the last few readings, for the live screen. */
    fun onSpeed(t: Long, speed: Double): Double {
        if (speed < 0 || speed > 100) return recentSpeeds.lastOrNull() ?: 0.0
        point(t).speed = speed
        recentSpeeds.addLast(speed)
        while (recentSpeeds.size > 5) recentSpeeds.removeFirst()
        return recentSpeeds.average()
    }

    fun onDistanceDelta(end: Long, meters: Double) {
        if (meters <= 0) return
        haveDistanceDeltas = true
        runningDistance += meters
        point(end).distance = runningDistance
    }

    fun onCadence(t: Long, spm: Long) {
        if (spm <= 0) return
        cadenceSum += spm
        cadenceCount++
        cadenceMax = max(cadenceMax, spm)
    }

    fun onTotals(now: Long, distance: Double?, calories: Double?, steps: Long?, elevationGain: Double?) {
        if (distance != null) {
            distanceTotal = distance
            // Without deltas, the totals are the only distance the track gets
            if (!haveDistanceDeltas && distance > 0) point(now).distance = distance
        }
        if (calories != null) this.calories = calories
        if (steps != null) this.steps = steps
        if (elevationGain != null) elevationGainTotal = elevationGain
    }

    fun pause(t: Long) {
        if (pausedSince == null) pausedSince = t
    }

    fun resume(t: Long) {
        val since = pausedSince ?: return
        pauses.add(longArrayOf(since, max(since, t)))
        pausedSince = null
    }

    /** Close the lap in progress at [t]. */
    fun lap(t: Long): WorkoutState.Lap {
        val d = distance
        val lap = WorkoutState.Lap(
            n = laps.size + 1,
            start = lapStart,
            end = t,
            activeMs = activeBetween(lapStart, t),
            distance = (d - lapStartDistance).coerceAtLeast(0.0),
            avgHr = avgHr(lapStart, t)
        )
        laps.add(lap)
        lapStart = t
        lapStartDistance = d
        return lap
    }

    val currentLaps: List<WorkoutState.Lap> get() = laps.toList()
    val currentLapStart: Long get() = lapStart
    val currentLapStartDistance: Double get() = lapStartDistance

    // ---- Output ----

    fun shouldDiscard(activeMs: Long): Boolean = distance < 1.0 && activeMs < 10_000

    fun finish(endMs: Long, activeMs: Long): Result {
        resume(endMs)
        val distance = distance
        // Only keep laps if the user pressed Lap; then the last stretch is a lap too
        if (laps.isNotEmpty() && endMs - lapStart >= 1000) lap(endMs)

        val track = buildTrack()
        val activeS = activeMs / 1000.0

        val speedSamples = points.values.mapNotNull { it.speed }
        val maxSpeed = speedSamples.windowed(3, 1, partialWindows = false) { it.average() }.maxOrNull()
            ?: speedSamples.maxOrNull()
        val avgSpeed = if (distance > 0 && activeS > 0) distance / activeS else null

        val alts = track.mapNotNull { it.alt }
        val elevationGain = elevationGainTotal ?: computedGain(alts)

        val cadence = when {
            cadenceCount > 0 -> (cadenceSum.toDouble() / cadenceCount).roundToLong()
            steps > 0 && activeS >= 60 -> (steps / (activeS / 60)).roundToLong()
            else -> null
        }

        val summary = JSONObject().apply {
            put("version", 1)
            put("maxHrUsed", maxHr)
            put("pausedMs", (endMs - startMs - activeMs).coerceAtLeast(0))
            avgSpeed?.let { put("avgSpeed", round2(it)) }
            maxSpeed?.let { put("maxSpeed", round2(it)) }
            if (type.effort == WorkoutType.Effort.PACE) {
                avgSpeed?.let { WorkoutFormat.paceOf(it) }?.let { put("avgPace", it.roundToInt()) }
                maxSpeed?.let { WorkoutFormat.paceOf(it) }?.let { put("bestPace", it.roundToInt()) }
            }
            cadence?.let { put("avgCadence", it) }
            if (cadenceMax > 0) put("maxCadence", cadenceMax)
            if (alts.isNotEmpty()) {
                put("minAlt", alts.min().roundToInt())
                put("maxAlt", alts.max().roundToInt())
            }
            put("zones", JSONArray(zoneMs.map { (it / 1000.0).roundToLong() }))
            val splits = splits(track)
            if (splits.length() > 0) put("splits", splits)
        }

        val workout = HealthHistory.Workout(
            id = 0,
            type = type.key,
            start = startMs,
            end = endMs,
            activeMs = activeMs,
            distance = distance,
            calories = calories,
            steps = steps.toDouble(),
            avgHr = if (hrCount > 0) hrSum / hrCount else null,
            maxHr = hrMax,
            elevationGain = elevationGain,
            summary = summary.toString(),
            synced = false
        )
        val savedLaps = laps.map { HealthHistory.Lap(0, it.n, it.start, it.end, it.distance, it.avgHr) }
        return Result(workout, savedLaps, track, lastHr)
    }

    /** Points in time order, with the distance carried forward where a second had none. */
    private fun buildTrack(): List<HealthHistory.TrackPoint> {
        var d = 0.0
        return points.map { (t, p) ->
            p.distance?.let { d = max(d, it) }
            HealthHistory.TrackPoint(t, p.lat, p.lon, p.alt, p.hr, p.speed, d)
        }
    }

    private fun activeBetween(from: Long, to: Long): Long {
        var paused = 0L
        for (p in pauses) paused += overlap(from, to, p[0], p[1])
        pausedSince?.let { paused += overlap(from, to, it, to) }
        return (to - from - paused).coerceAtLeast(0)
    }

    private fun overlap(a0: Long, a1: Long, b0: Long, b1: Long): Long = (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0)

    private fun avgHr(from: Long, to: Long): Double? {
        val hrs = points.subMap(from - Math.floorMod(from, 1000L), to).values.mapNotNull { it.hr }
        return if (hrs.isEmpty()) null else hrs.average()
    }

    /** Elevation gain from altitudes, ignoring wobbles under 3 m (GPS altitude is noisy). */
    private fun computedGain(alts: List<Double>): Double {
        if (alts.size < 2) return 0.0
        var gain = 0.0
        var base = alts.first()
        for (a in alts) {
            if (a - base >= 3) {
                gain += a - base
                base = a
            } else if (a < base) {
                base = a
            }
        }
        return gain
    }

    /**
     * Splits per km: [{"n":1,"d":1000,"s":<active s>,"hr":<avg>,"elev":<m>}], plus the last
     * part-kilometre when it's at least 50 m.
     */
    private fun splits(track: List<HealthHistory.TrackPoint>): JSONArray {
        val out = JSONArray()
        if (!type.hasDistance || track.isEmpty()) return out
        var n = 1
        var splitStart = startMs
        var startAlt = track.firstOrNull { it.alt != null }?.alt
        var prev: HealthHistory.TrackPoint? = null
        var lastAlt = startAlt

        fun emit(end: Long, d: Double, endAlt: Double?) {
            out.put(JSONObject().apply {
                put("n", n)
                put("d", d.roundToInt())
                put("s", (activeBetween(splitStart, end) / 1000.0).roundToLong())
                avgHr(splitStart, end)?.let { put("hr", it.roundToInt()) }
                if (startAlt != null && endAlt != null) put("elev", (endAlt - startAlt!!).roundToInt())
            })
        }

        for (p in track) {
            p.alt?.let { lastAlt = it }
            val pd = p.distance ?: continue
            while (pd >= n * 1000.0) {
                // Interpolate when the km mark was crossed between the previous point and this one
                val pt = prev
                val crossT = if (pt?.distance != null && pd > pt.distance!!) {
                    val f = (n * 1000.0 - pt.distance!!) / (pd - pt.distance!!)
                    pt.t + ((p.t - pt.t) * f).roundToLong()
                } else {
                    p.t
                }
                emit(crossT, 1000.0, lastAlt)
                splitStart = crossT
                startAlt = lastAlt
                n++
            }
            prev = p
        }
        val rest = distance - (n - 1) * 1000.0
        if (rest >= 50) emit(track.last().t, rest, lastAlt)
        return out
    }

    private fun round2(v: Double) = (v * 100).roundToLong() / 100.0
}
