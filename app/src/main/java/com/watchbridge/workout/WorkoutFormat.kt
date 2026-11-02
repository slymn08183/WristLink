package com.watchbridge.workout

import kotlin.math.roundToInt

/** Text for workout numbers. Metric throughout, like the rest of the app. */
object WorkoutFormat {

    /** 3723000 → "1:02:03", 83000 → "1:23". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /** Whole minutes, for ambient mode where seconds would go stale: "1:02", "12 min". */
    fun durationCoarse(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (m >= 60) "%d:%02d h".format(m / 60, m % 60) else "$m min"
    }

    /** Metres → "850 m" below 1 km, else "5.01 km". */
    fun distance(m: Double): String =
        if (m < 1000) "${m.roundToInt()} m" else "%.2f km".format(m / 1000)

    /** Just the km number, for big displays: "5.01". */
    fun km(m: Double): String = "%.2f".format(m / 1000)

    /** Seconds per km → "5'12\"", or "–'–\"" when standing still. */
    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm <= 0 || secPerKm > 3600) return "–'–\""
        val s = secPerKm.roundToInt()
        return "%d'%02d\"".format(s / 60, s % 60)
    }

    /** m/s → seconds per km, null when (nearly) still. */
    fun paceOf(speed: Double?): Double? = if (speed == null || speed < 0.3) null else 1000.0 / speed

    /** m/s → "24.3" (km/h). */
    fun speedKmh(speed: Double?): String = if (speed == null) "–" else "%.1f".format(speed * 3.6)

    fun bpm(hr: Double?): String = hr?.roundToInt()?.toString() ?: "–"

    fun kcal(kcal: Double): String = kcal.roundToInt().toString()
}
