package com.wristlink.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.time.Instant
import java.util.Calendar

/**
 * Collects daily activity, heart rate and sleep from Wear OS Health Services in passive mode.
 *
 * Passive mode is the battery-friendly way: the watch's sensor hub does the measuring and the
 * system hands over batches every so often (to [PassiveHealthService]), waking the app only then.
 * Samples go into [HealthStore] until the iPhone app takes them.
 */
object HealthCollector {

    private const val TAG = "HealthCollector"
    private const val PREFS = "health_collector"
    private const val HR_QUEUE_INTERVAL_MS = 60_000L
    private const val HR_QUEUE_JUMP_BPM = 15.0

    /** The last heart rate queued for Apple Health; in memory, a restart just queues the next one. */
    @Volatile private var lastQueuedHr: Pair<Long, Double>? = null

    /**
     * No heart rate: on Galaxy watches a passive heart-rate subscription keeps the sensor
     * measuring every second, whatever Samsung Health is set to (measured 2026-11-07: ~1
     * reading a second with Samsung Health on "every 10 minutes", and with it disabled). That
     * was the biggest drain on the watch. [HeartRateSampler] measures every 10 minutes instead.
     */
    private val wanted = setOf(
        DataType.STEPS_DAILY,
        DataType.DISTANCE_DAILY,
        DataType.CALORIES_DAILY,
        DataType.FLOORS_DAILY
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _platformSleepSupported = MutableStateFlow<Boolean?>(null)

    /** Whether the watch itself detects sleep (UserActivityState.ASLEEP); null until known. */
    val platformSleepSupported: StateFlow<Boolean?> = _platformSleepSupported.asStateFlow()

    /** Called whenever the platform's sleep state changes (used by SleepGuard). */
    @Volatile var onSleepStateChanged: ((asleep: Boolean) -> Unit)? = null

    fun register(context: Context) {
        val app = context.applicationContext
        if (!hasPermissions(app)) {
            Log.w(TAG, "Missing ACTIVITY_RECOGNITION or BODY_SENSORS, not collecting")
            return
        }
        scope.launch {
            seedToday(app)
            try {
                val client = HealthServices.getClient(app).passiveMonitoringClient
                val caps = client.getCapabilitiesAsync().await()
                val types = wanted.filter { it in caps.supportedDataTypesPassiveMonitoring }.toSet()
                val sleep = UserActivityState.USER_ACTIVITY_ASLEEP in caps.supportedUserActivityStates
                _platformSleepSupported.value = sleep
                Log.i(
                    TAG,
                    "Capabilities: passive=${caps.supportedDataTypesPassiveMonitoring.map { it.name }} " +
                        "activityStates=${caps.supportedUserActivityStates} → collecting ${types.map { it.name }} sleep=$sleep"
                )
                val config = PassiveListenerConfig.builder()
                    .setDataTypes(types)
                    .setShouldUserActivityInfoBeRequested(sleep)
                    .build()
                client.setPassiveListenerServiceAsync(PassiveHealthService::class.java, config).await()
                Log.i(TAG, "Passive listener registered")
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't register passive monitoring", e)
            }
        }
    }

    /**
     * The history started empty: give it what today has collected so far (the running totals
     * from before this version), spread from midnight, so today's screens aren't blank.
     */
    private fun seedToday(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("history.seeded", false)) return
        val history = HealthHistory.get(context)
        val now = System.currentTimeMillis()
        for ((type, column) in listOf(HealthStore.STEPS to "steps", HealthStore.DISTANCE to "distance")) {
            val time = prefs.getLong("$type.time", -1)
            if (time < 0 || prefs.getLong("$type.day", -1) != dayOf(now)) continue
            history.addSpread(column, startOfDay(time), time, prefs.getFloat("$type.total", 0f).toDouble())
        }
        prefs.edit().putBoolean("history.seeded", true).apply()
    }

    private fun hasPermissions(context: Context) = listOf(
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.BODY_SENSORS
    ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Health Services times points from boot; this turns them into wall-clock instants. */
    private fun bootInstant(): Instant =
        Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

    fun onDataPoints(context: Context, points: DataPointContainer) {
        val store = HealthStore.get(context)
        val history = HealthHistory.get(context)
        val boot = bootInstant()

        // Daily types are running totals since midnight; keep what was added since last time,
        // spread over the minutes it covers, and queue it for Apple Health
        fun daily(type: String, column: String, totals: List<Pair<Long, Double>>, outbox: Boolean = true) =
            recordDailyDelta(context, type, totals).onEach { (from, to, delta) ->
                history.addSpread(column, from, to, delta)
                if (outbox) store.add(type, from, to, delta)
            }

        val steps = daily(HealthStore.STEPS, "steps",
            points.getData(DataType.STEPS_DAILY).map { it.getEndInstant(boot).toEpochMilli() to it.value.toDouble() })
        // A workout carries its own distance and energy to Apple Health; the passive stream
        // leaves those out meanwhile so they aren't counted twice
        val workingOut = HealthAlerts.workoutActive()
        daily(HealthStore.DISTANCE, "distance", outbox = !workingOut, totals =
            points.getData(DataType.DISTANCE_DAILY).map { it.getEndInstant(boot).toEpochMilli() to it.value })
        daily(HealthStore.FLOORS, "floors",
            points.getData(DataType.FLOORS_DAILY).map { it.getEndInstant(boot).toEpochMilli() to it.value })
        // Apple Health wants active energy; Health Services' total includes the resting burn
        val calories = points.getData(DataType.CALORIES_DAILY).map { it.getEndInstant(boot).toEpochMilli() to it.value }
        daily(HealthStore.CALORIES, "calories", calories, outbox = false).forEach { (from, to, delta) ->
            val active = HealthMetrics.activePart(context, from, to, delta, dayTotal = calories.maxOf { it.second })
            if (active > 0.5 && !workingOut) store.add(HealthStore.ACTIVE_CALORIES, from, to, active)
        }

        // Not subscribed any more, but a subscription from an older version may still deliver
        val heartRates = points.getData(DataType.HEART_RATE_BPM)
            .filter { it.value > 0 }
            .map { it.getTimeInstant(boot).toEpochMilli() to it.value }
            .sortedBy { it.first }
        recordHeartRates(context, heartRates, newData = false)
        Log.i(TAG, "Delivery: ${heartRates.size} heart rates, steps +${steps.sumOf { it.third }.toInt()}")
        if (steps.isNotEmpty()) LiveHealth.onActivity(context, lastMove = steps.last().second)

        rollDay(context)
        HealthAlerts.onNewData(context)
    }

    /**
     * Heart-rate readings, oldest first, from [HeartRateSampler] (or a passive delivery). The
     * watch's own history keeps every one; Apple Health gets one a minute plus sudden changes.
     */
    fun recordHeartRates(context: Context, readings: List<Pair<Long, Double>>, newData: Boolean = true) {
        if (readings.isEmpty()) return
        val store = HealthStore.get(context)
        val history = HealthHistory.get(context)
        for ((t, bpm) in readings) {
            val last = lastQueuedHr
            if (last == null || t - last.first >= HR_QUEUE_INTERVAL_MS || abs(bpm - last.second) >= HR_QUEUE_JUMP_BPM) {
                store.add(HealthStore.HEART_RATE, t, t, bpm)
                lastQueuedHr = t to bpm
            }
            history.addHeartRate(t, bpm)
        }
        readings.last().let { (t, bpm) -> LiveHealth.onHeartRate(context, t, bpm) }
        if (newData) HealthAlerts.onNewData(context)
    }

    /** One stretch of a daily total: from, to, amount added. */
    private fun recordDailyDelta(
        context: Context,
        type: String,
        totals: List<Pair<Long, Double>>
    ): List<Triple<Long, Long, Double>> {
        if (totals.isEmpty()) return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val added = mutableListOf<Triple<Long, Long, Double>>()
        for ((time, total) in totals.sortedBy { it.first }) {
            val day = dayOf(time)
            val lastDay = prefs.getLong("$type.day", -1)
            val lastTime = prefs.getLong("$type.time", -1)
            val lastTotal = prefs.getFloat("$type.total", 0f).toDouble()

            val (from, delta) = if (day != lastDay || lastTime < 0) {
                // First reading of the day: count from midnight
                startOfDay(time) to total
            } else {
                lastTime to total - lastTotal
            }
            if (delta > 0 && time > from) added += Triple(from, time, delta)
            if (day != lastDay || total >= lastTotal) {
                prefs.edit()
                    .putLong("$type.day", day)
                    .putLong("$type.time", time)
                    .putFloat("$type.total", total.toFloat())
                    .apply()
            }
        }
        return added
    }

    /**
     * Once per day: queue yesterday's resting heart rate for Apple Health and drop history
     * older than HealthHistory keeps.
     */
    private fun rollDay(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = startOfDay(System.currentTimeMillis())
        val last = prefs.getLong("rolled", -1)
        if (last == today) return
        prefs.edit().putLong("rolled", today).apply()
        if (last < 0) return
        val yesterday = startOfDay(today - 12 * 3600 * 1000L)
        HealthMetrics.restingHr(context, yesterday)?.let {
            HealthStore.get(context).add(HealthStore.RESTING_HR, yesterday, yesterday, it.toDouble())
        }
        HealthHistory.get(context).prune()
    }

    fun onUserActivity(context: Context, info: UserActivityInfo) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val changedAt = info.stateChangeTime.toEpochMilli()
        val asleep = info.userActivityState == UserActivityState.USER_ACTIVITY_ASLEEP
        val sleepStart = prefs.getLong("sleep.start", -1)
        Log.i(TAG, "User activity: ${info.userActivityState} at ${info.stateChangeTime}")

        if (asleep && sleepStart < 0) {
            // Back to sleep soon after waking: the time between was awake inside the same night
            val lastEnd = prefs.getLong("sleep.lastEnd", -1)
            if (lastEnd in (changedAt - HealthMetrics.NIGHT_GAP_MS) until changedAt) {
                HealthStore.get(context).add(HealthStore.AWAKE, lastEnd, changedAt, 1.0)
            }
            prefs.edit().putLong("sleep.start", changedAt).apply()
        } else if (!asleep && sleepStart >= 0) {
            if (changedAt > sleepStart) {
                HealthStore.get(context).add(HealthStore.SLEEP, sleepStart, changedAt, 1.0)
                HealthHistory.get(context).addSleep(sleepStart, changedAt)
            }
            prefs.edit().remove("sleep.start").putLong("sleep.lastEnd", changedAt).apply()
        }
        onSleepStateChanged?.invoke(asleep)
    }

    /** The platform said "asleep" and hasn't said otherwise since. */
    fun isAsleepNow(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("sleep.start", -1) >= 0

    /** When the stretch of sleep going on now began, or null when awake. */
    fun asleepSince(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("sleep.start", -1).takeIf { it >= 0 }

    private fun dayOf(time: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = time }
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
