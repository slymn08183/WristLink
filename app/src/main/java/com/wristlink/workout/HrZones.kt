package com.wristlink.workout

import androidx.compose.ui.graphics.Color

/**
 * Heart-rate zones in max-HR mode (ecosystem plan §6): percent of max HR,
 * Z1 50–60, Z2 61–70, Z3 71–80, Z4 81–90, Z5 91–100. Below 50 % is no zone (0).
 */
object HrZones {

    const val COUNT = 5

    val labels = listOf("Low intensity", "Weight control", "Aerobic", "Anaerobic", "Maximum")

    /** Lower bound of each zone, fraction of max HR. */
    private val lowerBounds = doubleArrayOf(0.50, 0.61, 0.71, 0.81, 0.91)

    val colors = listOf(
        Color(0xFF90A4AE), // Z1 grey-blue
        Color(0xFF4FC3F7), // Z2 blue
        Color(0xFF81C784), // Z3 green
        Color(0xFFFFB74D), // Z4 orange
        Color(0xFFEF5350)  // Z5 red
    )

    /** 1..5, or 0 below zone 1. */
    fun zoneOf(bpm: Double, maxHr: Int): Int {
        if (maxHr <= 0) return 0
        val pct = bpm / maxHr
        for (z in COUNT downTo 1) if (pct >= lowerBounds[z - 1]) return z
        return 0
    }

    /** bpm range of zone [z] (1..5) for [maxHr], for the zone screen's labels. */
    fun range(z: Int, maxHr: Int): IntRange {
        val lo = kotlin.math.ceil(maxHr * lowerBounds[z - 1]).toInt()
        val hi = if (z == COUNT) maxHr else kotlin.math.ceil(maxHr * lowerBounds[z]).toInt() - 1
        return lo..hi
    }
}
