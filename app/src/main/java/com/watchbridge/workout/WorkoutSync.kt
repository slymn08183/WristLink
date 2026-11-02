package com.watchbridge.workout

import android.content.Context
import com.watchbridge.health.HealthHistory
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Workouts for the iPhone (companion protocol 6, "Workouts"): the `{"op":"workout", ...}` reply
 * to `nextWorkout`. The route is thinned to about one point every [THIN_MS].
 */
object WorkoutSync {

    private const val THIN_MS = 3_000L

    /** Reply when every workout is already in Apple Health. */
    fun none(): JSONObject = JSONObject().put("op", "workout").put("none", true)

    fun message(context: Context, workout: HealthHistory.Workout): JSONObject {
        val history = HealthHistory.get(context)
        val laps = history.laps(workout.id)
        val track = history.track(workout.id)

        val summary = workout.summary?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

        return JSONObject()
            .put("op", "workout")
            .put("id", workout.id)
            .put("type", workout.type)
            .put("start", workout.start)
            .put("end", workout.end)
            .put("activeMs", workout.activeMs)
            .put("distance", round(workout.distance, 1))
            .put("calories", workout.calories.roundToInt())
            .put("steps", workout.steps.roundToLong())
            .put("avgHr", workout.avgHr?.roundToInt() ?: JSONObject.NULL)
            .put("maxHr", workout.maxHr?.roundToInt() ?: JSONObject.NULL)
            .put("elevationGain", workout.elevationGain.roundToInt())
            .put("summary", summary)
            .put("laps", JSONArray().apply {
                laps.forEach { lap ->
                    put(
                        JSONObject()
                            .put("n", lap.n)
                            .put("s", lap.start)
                            .put("e", lap.end)
                            .put("d", round(lap.distance, 1))
                            .put("hr", lap.avgHr?.roundToInt() ?: JSONObject.NULL)
                    )
                }
            })
            .put("track", thin(track))
    }

    /**
     * One entry per [THIN_MS] window: [t, lat, lon, alt, hr, speed], where t is the window's
     * first point and each value is the last one seen in the window (position from a single
     * point, so lat/lon always belong together).
     */
    private fun thin(track: List<HealthHistory.TrackPoint>): JSONArray {
        val out = JSONArray()
        if (track.isEmpty()) return out
        val origin = track.first().t
        var i = 0
        while (i < track.size) {
            val bucket = (track[i].t - origin) / THIN_MS
            val t = track[i].t
            var pos: HealthHistory.TrackPoint? = null
            var alt: Double? = null
            var hr: Double? = null
            var speed: Double? = null
            while (i < track.size && (track[i].t - origin) / THIN_MS == bucket) {
                val p = track[i]
                if (p.lat != null && p.lon != null) pos = p
                p.alt?.let { alt = it }
                p.hr?.let { hr = it }
                p.speed?.let { speed = it }
                i++
            }
            out.put(
                JSONArray()
                    .put(t)
                    .put(pos?.lat?.let { round(it, 6) } ?: JSONObject.NULL)
                    .put(pos?.lon?.let { round(it, 6) } ?: JSONObject.NULL)
                    .put(alt?.let { round(it, 1) } ?: JSONObject.NULL)
                    .put(hr?.roundToInt() ?: JSONObject.NULL)
                    .put(speed?.let { round(it, 2) } ?: JSONObject.NULL)
            )
        }
        return out
    }

    private fun round(v: Double, decimals: Int): Double {
        var f = 1.0
        repeat(decimals) { f *= 10 }
        return (v * f).roundToLong() / f
    }
}
