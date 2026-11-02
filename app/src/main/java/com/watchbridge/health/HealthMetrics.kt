package com.watchbridge.health

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Everything the health screens show that isn't a raw number: daily totals and rings, resting
 * heart rate, nights of sleep with our own score and "animal", the energy score, streaks.
 *
 * The rules follow Samsung Health where they can (see docs/samsung-health-analysis.md); where
 * Samsung relies on its private sensors (sleep stages, HRV) the numbers here are our own and
 * the screens say so. All reads hit the database: call from a background thread.
 */
object HealthMetrics {

    const val DAY_MS = 24L * 3600 * 1000
    private const val HOUR_MS = 3600L * 1000
    private const val MINUTE_MS = HealthHistory.MINUTE_MS

    /** Asleep stretches closer together than this are one night, with the gap awake. */
    const val NIGHT_GAP_MS = 60 * MINUTE_MS

    /** A minute with this many steps counts towards active time. */
    private const val MOVE_STEPS_PER_MINUTE = 30.0
    /** Samsung's active hour: at least 20 steps (or any exercise) in it. */
    private const val ACTIVE_HOUR_STEPS = 20.0
    /** Steps in a minute of sleep that count as being up. */
    private const val RESTLESS_STEPS = 20.0

    private const val PREFS = "health_metrics"

    data class Day(
        val start: Long,
        val steps: Double,
        val distance: Double,
        val floors: Double,
        val activeCalories: Double,
        val totalCalories: Double,
        val exerciseCalories: Double,
        /** Active time: moving or working out. */
        val activeMinutes: Int,
        val exerciseMinutes: Int,
        val raisedHrMinutes: Int,
        val activeHours: Int,
        val restingHr: Int?,
        val hrMin: Int?,
        val hrMax: Int?,
        val hourlySteps: DoubleArray,
        val hourlyHrMin: Array<Int?>,
        val hourlyHrMax: Array<Int?>
    ) {
        fun stepGoalMet(p: HealthProfile.Profile) = steps >= p.stepGoal
        fun allGoalsMet(p: HealthProfile.Profile) =
            steps >= p.stepGoal && activeMinutes >= p.activeMinutesGoal && activeCalories >= p.activeCaloriesGoal
    }

    data class Night(
        /** Local midnight of the day this night ends on. */
        val day: Long,
        val start: Long,
        val end: Long,
        val asleepMs: Long,
        val awakeMs: Long,
        val gaps: List<Pair<Long, Long>>,
        val avgHr: Int?,
        val minHr: Int?,
        /** Steps per 10 minutes from [start]. */
        val movement: IntArray,
        /** 0–1, how much this night overlapped the one before (null without one). */
        val regularity: Double?,
        /** Bedtime vs the goal, minutes either way. */
        val bedtimeOffsetMin: Int,
        val napMinutes: Int,
        val score: Int?,
        val ongoing: Boolean
    ) {
        val inBedMs: Long get() = end - start
        val efficiency: Double get() = if (inBedMs > 0) asleepMs.toDouble() / inBedMs else 0.0
        val band: String? get() = score?.let(::band)
    }

    data class Animal(val name: String, val short: Boolean, val wakes: Boolean, val irregular: Boolean) {
        val description: String
            get() = when (name) {
                "Lion" -> "Sleeps long and steady, every night alike"
                "Penguin" -> "Enough sleep, but wakes up often"
                "Mole" -> "Sleeps at different times every night"
                "Walrus" -> "Sleeps too little"
                "Hedgehog" -> "Wakes often, and bedtimes vary"
                "Deer" -> "Short nights with many wake-ups"
                "Alligator" -> "Short nights at changing times"
                else -> "Short, broken and irregular nights"
            }
    }

    data class Energy(val score: Int, val factors: Map<String, Double>) {
        val band: String get() = band(score)
    }

    fun band(score: Int): String = when {
        score >= 85 -> "Excellent"
        score >= 75 -> "Good"
        score >= 60 -> "Fair"
        else -> "Attention"
    }

    // ---- Calories ----

    /**
     * Health Services' daily calories may or may not include the resting burn depending on the
     * watch. Decide once from a full day's total against the BMR, then remember it.
     */
    private fun includesBasal(context: Context, dayTotal: Double, elapsedFraction: Double): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains("includesBasal")) return prefs.getBoolean("includesBasal", true)
        val bmr = HealthProfile.current(context).bmrPerDay * elapsedFraction
        val includes = dayTotal >= bmr * 0.8
        // Only trust it once enough of the day has gone by
        if (elapsedFraction >= 0.5) prefs.edit().putBoolean("includesBasal", includes).apply()
        return includes
    }

    /** The active part of [delta] kcal recorded over [from]..[to]. */
    fun activePart(context: Context, from: Long, to: Long, delta: Double, dayTotal: Double): Double {
        val dayStart = startOfDay(to)
        val fraction = ((to - dayStart).toDouble() / DAY_MS).coerceIn(0.01, 1.0)
        if (!includesBasal(context, dayTotal, fraction)) return delta
        val bmr = HealthProfile.current(context).bmrPerDay * (to - from) / DAY_MS
        return (delta - bmr).coerceAtLeast(0.0)
    }

    // ---- Days ----

    fun day(context: Context, dayStart: Long, now: Long = System.currentTimeMillis()): Day {
        val profile = HealthProfile.current(context)
        val history = HealthHistory.get(context)
        val dayEnd = dayStart + DAY_MS
        val minutes = history.minutes(dayStart, dayEnd)
        val workouts = history.workouts(dayStart, dayEnd)

        val hourlySteps = DoubleArray(24)
        val hourlyMin = arrayOfNulls<Int>(24)
        val hourlyMax = arrayOfNulls<Int>(24)
        val exerciseMinuteSet = HashSet<Long>()
        for (w in workouts) {
            var m = maxOf(w.start, dayStart) / MINUTE_MS * MINUTE_MS
            while (m < minOf(w.end, dayEnd)) {
                exerciseMinuteSet += m
                m += MINUTE_MS
            }
        }

        var steps = 0.0
        var distance = 0.0
        var floors = 0.0
        var calories = 0.0
        var raised = 0
        val activeSet = HashSet<Long>(exerciseMinuteSet)
        for (m in minutes) {
            val hour = ((m.t - dayStart) / HOUR_MS).toInt().coerceIn(0, 23)
            steps += m.steps
            distance += m.distance
            floors += m.floors
            calories += m.calories
            hourlySteps[hour] += m.steps
            if (m.steps >= MOVE_STEPS_PER_MINUTE) activeSet += m.t
            if (m.hrAvg != null && m.hrAvg >= profile.raisedHrThreshold) raised++
            m.hrMin?.roundToInt()?.let { v -> hourlyMin[hour] = minOf(hourlyMin[hour] ?: v, v) }
            m.hrMax?.roundToInt()?.let { v -> hourlyMax[hour] = maxOf(hourlyMax[hour] ?: v, v) }
        }
        val exerciseHours = exerciseMinuteSet.map { ((it - dayStart) / HOUR_MS).toInt() }.toSet()
        val activeHours = (0 until 24).count { hourlySteps[it] >= ACTIVE_HOUR_STEPS || it in exerciseHours }

        val fraction = ((minOf(now, dayEnd) - dayStart).toDouble() / DAY_MS).coerceIn(0.0, 1.0)
        val bmrSoFar = profile.bmrPerDay * fraction
        val (active, total) = when {
            calories <= 0 -> 0.0 to bmrSoFar
            includesBasal(context, calories, fraction.coerceAtLeast(0.01)) ->
                (calories - bmrSoFar).coerceAtLeast(0.0) to maxOf(calories, bmrSoFar)
            else -> calories to bmrSoFar + calories
        }

        return Day(
            start = dayStart,
            steps = steps,
            distance = distance,
            floors = floors,
            activeCalories = active,
            totalCalories = total,
            exerciseCalories = workouts.sumOf { w ->
                val overlap = minOf(w.end, dayEnd) - maxOf(w.start, dayStart)
                if (w.end > w.start) w.calories * overlap / (w.end - w.start) else 0.0
            },
            activeMinutes = activeSet.size,
            exerciseMinutes = exerciseMinuteSet.size,
            raisedHrMinutes = raised,
            activeHours = activeHours,
            restingHr = restingHr(minutes),
            hrMin = hourlyMin.filterNotNull().minOrNull(),
            hrMax = hourlyMax.filterNotNull().maxOrNull(),
            hourlySteps = hourlySteps,
            hourlyHrMin = hourlyMin,
            hourlyHrMax = hourlyMax
        )
    }

    fun restingHr(context: Context, dayStart: Long): Int? =
        restingHr(HealthHistory.get(context).minutes(dayStart, dayStart + DAY_MS))

    /**
     * Samsung measures resting heart rate with its own sensors; we take the low end (5th
     * percentile) of minutes spent still: no steps in the minute or the two before it.
     */
    private fun restingHr(minutes: List<HealthHistory.Minute>): Int? {
        val steps = minutes.associate { it.t to it.steps }
        val still = minutes.mapNotNull { m ->
            val hr = m.hrAvg ?: return@mapNotNull null
            val quiet = (0..2).all { (steps[m.t - it * MINUTE_MS] ?: 0.0) < 1.0 }
            if (quiet) hr else null
        }.sorted()
        if (still.size < 15) return null
        return still[(still.size * 0.05).toInt()].roundToInt()
    }

    /** Days in a row (up to today) with the step goal met; today only counts once it's met. */
    fun streak(context: Context, now: Long = System.currentTimeMillis()): Int {
        val goal = HealthProfile.current(context).stepGoal
        val history = HealthHistory.get(context)
        var day = startOfDay(now)
        var count = 0
        if (history.minutes(day, day + DAY_MS).sumOf { it.steps } >= goal) count++
        repeat(HealthHistory.MAX_AGE_DAYS) {
            day = startOfDay(day - HOUR_MS * 12)
            if (history.minutes(day, day + DAY_MS).sumOf { it.steps } < goal) return count
            count++
        }
        return count
    }

    // ---- Sleep ----

    /**
     * The night that ends on the day starting at [dayStart]: the longest sleep that ended between
     * 18:00 the evening before and 18:00 that day. Other sleeps that day are naps.
     */
    fun night(context: Context, dayStart: Long, now: Long = System.currentTimeMillis()): Night? {
        val sessions = sessions(context, dayStart - DAY_MS, dayStart + DAY_MS, now)
        val windowStart = dayStart - 6 * HOUR_MS
        val windowEnd = dayStart + 18 * HOUR_MS
        val inWindow = sessions.filter { it.last().second in (windowStart + 1)..windowEnd }
        val main = inWindow.maxByOrNull { s -> s.sumOf { it.second - it.first } } ?: return null
        val naps = inWindow.filter { it !== main }.sumOf { s -> s.sumOf { it.second - it.first } }
        val previous = sessions.filter { it.last().second in (windowStart - DAY_MS + 1)..windowStart }
            .maxByOrNull { s -> s.sumOf { it.second - it.first } }
        return buildNight(context, dayStart, main, previous, (naps / MINUTE_MS).toInt(), now)
    }

    /** Nights for the [count] days up to today, oldest first; days without sleep left out. */
    fun nights(context: Context, count: Int, now: Long = System.currentTimeMillis()): List<Night> {
        val today = startOfDay(now)
        return (count - 1 downTo 0).mapNotNull { night(context, startOfDay(today - it * DAY_MS + HOUR_MS * 12), now) }
    }

    /** Asleep stretches grouped into sessions. */
    private fun sessions(context: Context, from: Long, to: Long, now: Long): List<List<Pair<Long, Long>>> {
        val stretches = HealthHistory.get(context).sleep(from, to).map { it.start to it.end }.toMutableList()
        HealthCollector.asleepSince(context)?.let { if (it < now) stretches += it to now }
        val sessions = mutableListOf<MutableList<Pair<Long, Long>>>()
        for (s in stretches.sortedBy { it.first }) {
            val last = sessions.lastOrNull()
            if (last != null && s.first - last.last().second < NIGHT_GAP_MS) last += s else sessions += mutableListOf(s)
        }
        return sessions
    }

    private fun buildNight(
        context: Context,
        dayStart: Long,
        stretches: List<Pair<Long, Long>>,
        previous: List<Pair<Long, Long>>?,
        napMinutes: Int,
        now: Long
    ): Night {
        val profile = HealthProfile.current(context)
        val start = stretches.first().first
        val end = stretches.last().second
        val gaps = stretches.zipWithNext { a, b -> a.second to b.first }.filter { it.second > it.first }
        val minutes = HealthHistory.get(context).minutes(start, end)
        val asleepMinutes = minutes.filter { m -> stretches.any { m.t >= it.first && m.t < it.second } }
        val restless = asleepMinutes.count { it.steps >= RESTLESS_STEPS }
        val awake = gaps.sumOf { it.second - it.first } + restless * MINUTE_MS
        val asleep = (end - start - awake).coerceAtLeast(0)

        val buckets = IntArray((((end - start) / (10 * MINUTE_MS)) + 1).toInt().coerceAtMost(200))
        for (m in minutes) {
            val i = ((m.t - start) / (10 * MINUTE_MS)).toInt()
            if (i in buckets.indices) buckets[i] += m.steps.roundToInt()
        }
        val hrs = asleepMinutes.mapNotNull { it.hrAvg }

        val regularity = previous?.let {
            val ps = it.first().first + DAY_MS
            val pe = it.last().second + DAY_MS
            val overlap = (minOf(end, pe) - maxOf(start, ps)).coerceAtLeast(0)
            overlap.toDouble() / maxOf(end - start, pe - ps)
        }
        val bedtime = minuteOfDay(start)
        var offset = bedtime - profile.bedtimeMinutes
        if (offset > 720) offset -= 1440
        if (offset < -720) offset += 1440

        val night = Night(
            day = dayStart, start = start, end = end, asleepMs = asleep, awakeMs = awake, gaps = gaps,
            avgHr = hrs.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            minHr = hrs.minOrNull()?.roundToInt(),
            movement = buckets, regularity = regularity, bedtimeOffsetMin = offset,
            napMinutes = napMinutes, score = null, ongoing = HealthCollector.asleepSince(context) != null && end >= now
        )
        return night.copy(score = score(night, profile))
    }

    /**
     * Our own sleep score, 0–100, without stages: duration against 7–9 h (40), efficiency (25),
     * regularity with the night before (20), bedtime against the goal (15). Naps add up to 5.
     * Like Samsung, no score for less than an hour of sleep.
     */
    private fun score(n: Night, profile: HealthProfile.Profile): Int? {
        val hours = n.asleepMs / 3_600_000.0
        if (hours < 1 || n.ongoing) return null
        val goalHours = profile.sleepGoalMinutes / 60.0
        val duration = when {
            hours < minOf(7.0, goalHours) -> 40 - (minOf(7.0, goalHours) - hours) * 10
            hours > 9 -> 40 - (hours - 9) * 8
            else -> 40.0
        }.coerceIn(0.0, 40.0)
        val efficiency = (25 - (0.9 - n.efficiency).coerceAtLeast(0.0) * 100).coerceIn(0.0, 25.0)
        val regularity = n.regularity?.let { 20 * it } ?: 15.0
        val timing = (15 - (abs(n.bedtimeOffsetMin) - 30).coerceAtLeast(0) / 10.0).coerceIn(0.0, 15.0)
        val naps = minOf(5.0, n.napMinutes / 10.0)
        return (duration + efficiency + regularity + timing + naps).roundToInt().coerceIn(0, 100)
    }

    /** Samsung's sleep animals from three axes that need no stages; needs 3 nights. */
    fun animal(context: Context, nights: List<Night>): Animal? {
        val done = nights.filter { !it.ongoing && it.asleepMs >= HOUR_MS }.takeLast(7)
        if (done.size < 3) return null
        val goal = HealthProfile.current(context).sleepGoalMinutes * MINUTE_MS
        val short = done.map { it.asleepMs }.average() < goal - HOUR_MS || done.map { it.efficiency }.average() < 0.85
        val wakes = done.map { it.awakeMs.toDouble() / it.inBedMs }.average() >= 0.10
        val irregular = done.mapNotNull { it.regularity }.let { it.isNotEmpty() && it.average() < 0.7 }
        val name = when {
            short && wakes && irregular -> "Shark"
            short && wakes -> "Deer"
            short && irregular -> "Alligator"
            wakes && irregular -> "Hedgehog"
            short -> "Walrus"
            irregular -> "Mole"
            wakes -> "Penguin"
            else -> "Lion"
        }
        return Animal(name, short, wakes, irregular)
    }

    // ---- Energy score ----

    /**
     * Samsung's energy score without its HRV factor: each factor is 0–1 and the score is their
     * average. Needs last night's sleep.
     */
    fun energy(context: Context, nights: List<Night>, days: List<Day>): Energy? {
        val profile = HealthProfile.current(context)
        val last = nights.lastOrNull()?.takeIf { !it.ongoing && it.day == startOfDay(System.currentTimeMillis()) }
            ?: return null
        val week = nights.filter { !it.ongoing }.takeLast(7)
        val factors = linkedMapOf<String, Double>()

        val hours = last.asleepMs / 3_600_000.0
        factors["sleepTime"] = when {
            hours < 7 -> 1 - (7 - hours) * 0.2
            hours > 9 -> 1 - (hours - 9) * 0.2
            else -> 1.0
        }.coerceIn(0.0, 1.0)
        if (week.size >= 3) {
            factors["sleepConsistency"] = consistency(week.map { it.asleepMs / 60_000.0 })
            factors["midpointConsistency"] = consistency(week.map {
                val mid = minuteOfDay((it.start + it.end) / 2).toDouble()
                if (mid > 720) mid - 1440 else mid
            })
        }
        last.regularity?.let { factors["regularity"] = it }
        factors["timing"] = (1 - (abs(last.bedtimeOffsetMin) - 30).coerceAtLeast(0) / 150.0).coerceIn(0.0, 1.0)
        val past = days.dropLast(1)
        past.lastOrNull()?.let { factors["yesterdayActivity"] = (it.activeMinutes.toDouble() / profile.activeMinutesGoal).coerceIn(0.0, 1.0) }
        if (past.size >= 3) {
            val active = past.map { it.activeMinutes.toDouble() }
            val mean = active.average()
            factors["activityConsistency"] = if (mean <= 0) 0.0 else (1 - stdDev(active) / mean).coerceIn(0.0, 1.0)
        }
        val avgHrs = week.mapNotNull { it.avgHr }
        if (last.avgHr != null && avgHrs.size >= 3) {
            factors["sleepingHr"] = (1 - (last.avgHr - avgHrs.average()).coerceAtLeast(0.0) * 0.1).coerceIn(0.0, 1.0)
        }
        return Energy((factors.values.average() * 100).roundToInt(), factors)
    }

    /** 1 when the values hardly vary (≤ 30 min apart), 0 at two hours. */
    private fun consistency(values: List<Double>): Double = (1 - (stdDev(values) - 30) / 90).coerceIn(0.0, 1.0)

    private fun stdDev(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    // ---- For the iPhone ----

    /** The `summary` message of companion protocol 6 (docs/companion-protocol.md). */
    fun summaryJson(context: Context, count: Int): JSONObject {
        val profile = HealthProfile.current(context)
        val now = System.currentTimeMillis()
        val today = startOfDay(now)
        val days = (count - 1 downTo 0).map { day(context, startOfDay(today - it * DAY_MS + HOUR_MS * 12), now) }
        val nights = nights(context, maxOf(count, 7), now)
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun nullable(v: Any?): Any = v ?: JSONObject.NULL
        return JSONObject()
            .put("op", "summary")
            .put("at", now)
            .put("profile", profile.toJson())
            .put("restingRange", JSONArray(listOf(profile.restingRange.first, profile.restingRange.last)))
            .put("days", JSONArray(days.map { d ->
                JSONObject()
                    .put("day", dayFormat.format(Date(d.start)))
                    .put("start", d.start)
                    .put("steps", d.steps.roundToInt())
                    .put("distance", (d.distance * 10).roundToInt() / 10.0)
                    .put("floors", d.floors.roundToInt())
                    .put("activeCalories", d.activeCalories.roundToInt())
                    .put("totalCalories", d.totalCalories.roundToInt())
                    .put("activeMinutes", d.activeMinutes)
                    .put("exerciseMinutes", d.exerciseMinutes)
                    .put("raisedHrMinutes", d.raisedHrMinutes)
                    .put("activeHours", d.activeHours)
                    .put("restingHr", nullable(d.restingHr))
                    .put("hrMin", nullable(d.hrMin))
                    .put("hrMax", nullable(d.hrMax))
                    .put("stepGoalMet", d.stepGoalMet(profile))
                    .put("allGoalsMet", d.allGoalsMet(profile))
                    .put("hourlySteps", JSONArray(d.hourlySteps.map { it.roundToInt() }))
                    .put("hourlyHrMin", JSONArray(d.hourlyHrMin.map(::nullable)))
                    .put("hourlyHrMax", JSONArray(d.hourlyHrMax.map(::nullable)))
            }))
            .put("sleep", JSONArray(nights.takeLast(count).map { n ->
                JSONObject()
                    .put("night", dayFormat.format(Date(n.day)))
                    .put("start", n.start)
                    .put("end", n.end)
                    .put("inBedMin", n.inBedMs / MINUTE_MS)
                    .put("asleepMin", n.asleepMs / MINUTE_MS)
                    .put("awakeMin", n.awakeMs / MINUTE_MS)
                    .put("efficiency", (n.efficiency * 100).roundToInt() / 100.0)
                    .put("score", nullable(n.score))
                    .put("band", nullable(n.band))
                    .put("avgHr", nullable(n.avgHr))
                    .put("minHr", nullable(n.minHr))
                    .put("ongoing", n.ongoing)
                    .put("gaps", JSONArray(n.gaps.map { JSONArray(listOf(it.first, it.second)) }))
                    .put("movement", JSONArray(n.movement.toList()))
            }))
            .put("animal", animal(context, nights)?.let {
                JSONObject().put("name", it.name).put("short", it.short).put("wakes", it.wakes)
                    .put("irregular", it.irregular).put("description", it.description)
            } ?: JSONObject.NULL)
            .put("energy", energy(context, nights, days)?.let {
                JSONObject().put("score", it.score).put("band", it.band).put("reduced", true)
            } ?: JSONObject.NULL)
            .put("streak", streak(context, now))
    }

    // ---- Time helpers ----

    fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun minuteOfDay(time: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = time }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }
}
