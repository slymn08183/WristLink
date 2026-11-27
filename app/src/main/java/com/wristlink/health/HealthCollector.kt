package com.wristlink.health

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.IntervalDataPoint
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
    private const val CLOCK_JUMP_MS = 2 * 60_000L

    private val DAILY_TYPES = listOf(HealthStore.STEPS, HealthStore.DISTANCE, HealthStore.FLOORS, HealthStore.CALORIES)

    /** The last heart rate queued for Apple Health; in memory, a restart just queues the next one. */
    @Volatile private var lastQueuedHr: Pair<Long, Double>? = null

    private val wanted = setOf(
        DataType.STEPS_DAILY,
        DataType.DISTANCE_DAILY,
        DataType.CALORIES_DAILY,
        DataType.FLOORS_DAILY,
        DataType.HEART_RATE_BPM
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

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        followClockJump(context, prefs, boot)
        // The batch's running totals go into one write, not one per point, and only once the
        // samples they account for are saved
        val edit = prefs.edit()
        // One transaction per database for the whole batch: one disk sync, not one per reading
        val recorded = store.inTransaction { history.inTransaction { record(context, points, store, history, boot, prefs, edit) } }
        edit.apply()

        // Only now, so the complications and tiles this wakes read what was just saved
        recorded.lastHeartRate?.let { (t, bpm) -> LiveHealth.onHeartRate(context, t, bpm) }
        if (recorded.steps.isNotEmpty()) LiveHealth.onActivity(context, lastMove(history, recorded.steps))
        rollDay(context)
        HealthAlerts.onNewData(context)
    }

    private class Recorded(val steps: List<DailyTotals.Delta>, val lastHeartRate: Pair<Long, Double>?)

    /**
     * When the wearer last really moved, judged from the per-minute steps around the new ones
     * rather than from the deltas themselves: deltas cover anything from seconds to hours, and
     * a few stray steps at a desk must not count as getting up.
     */
    private fun lastMove(history: HealthHistory, steps: List<DailyTotals.Delta>): Long? {
        val to = steps.maxOf { it.to }
        val from = maxOf(steps.minOf { it.from }, to - 2 * 3600_000L) - InactivityRules.MOVE_WINDOW_MIN * InactivityRules.MINUTE_MS
        return InactivityRules.lastMove(history.stepMinutes(from, to + InactivityRules.MINUTE_MS))?.coerceAtMost(to)
    }

    private fun record(
        context: Context,
        points: DataPointContainer,
        store: HealthStore,
        history: HealthHistory,
        boot: Instant,
        prefs: SharedPreferences,
        edit: SharedPreferences.Editor
    ): Recorded {

        // Daily types are running totals since the start of the day; keep what was added since
        // last time, spread over the minutes it covers, and queue it for Apple Health
        fun daily(type: String, column: String, data: List<IntervalDataPoint<out Number>>, outbox: Boolean = true): List<DailyTotals.Delta> {
            if (data.isEmpty()) return emptyList()
            val totals = data.map {
                Triple(it.getStartInstant(boot).toEpochMilli(), it.getEndInstant(boot).toEpochMilli(), it.value.toDouble())
            }
            val (state, deltas) = DailyTotals.fold(loadTotal(prefs, type), totals)
            if (state != null) saveTotal(edit, type, state)
            for (d in deltas) {
                history.addSpread(column, d.from, d.to, d.amount)
                if (outbox) store.add(type, d.from, d.to, d.amount)
            }
            return deltas
        }

        val steps = daily(HealthStore.STEPS, "steps", points.getData(DataType.STEPS_DAILY))
        // A workout carries its own distance and energy to Apple Health; the passive stream
        // leaves those out meanwhile so they aren't counted twice
        val workingOut = HealthAlerts.workoutActive()
        daily(HealthStore.DISTANCE, "distance", points.getData(DataType.DISTANCE_DAILY), outbox = !workingOut)
        daily(HealthStore.FLOORS, "floors", points.getData(DataType.FLOORS_DAILY))
        // Apple Health wants active energy; Health Services' total includes the resting burn
        daily(HealthStore.CALORIES, "calories", points.getData(DataType.CALORIES_DAILY), outbox = false).forEach { d ->
            // The total this delta came from, not the batch's largest: a batch can straddle midnight
            val active = HealthMetrics.activePart(context, d.from, d.to, d.amount, dayTotal = d.total, periodStart = d.periodStart)
            if (active > 0.5 && !workingOut) store.add(HealthStore.ACTIVE_CALORIES, d.from, d.to, active)
        }

        val heartRates = points.getData(DataType.HEART_RATE_BPM)
            .filter { it.value > 0 }
            .map { it.getTimeInstant(boot).toEpochMilli() to it.value }
            .sortedBy { it.first }
        for ((t, bpm) in heartRates) {
            // The watch's own history keeps every reading. Apple Health gets one a minute, plus
            // any sudden change: with the sensor measuring continuously that's ~60k readings a
            // day otherwise, all of them sent over Bluetooth and written on the iPhone.
            val last = lastQueuedHr
            if (last == null || t - last.first >= HR_QUEUE_INTERVAL_MS || abs(bpm - last.second) >= HR_QUEUE_JUMP_BPM) {
                store.add(HealthStore.HEART_RATE, t, t, bpm)
                lastQueuedHr = t to bpm
            }
            history.addHeartRate(t, bpm)
        }
        return Recorded(steps, heartRates.lastOrNull())
    }

    /** The last daily total seen for [type]. */
    private fun loadTotal(prefs: SharedPreferences, type: String): DailyTotals.State? {
        val time = prefs.getLong("$type.time", -1)
        if (time < 0) return null
        // Saved before the start was kept: totals were keyed on their day, so that day's midnight
        val start = prefs.getLong("$type.start", startOfDay(time))
        return DailyTotals.State(start, time, prefs.getFloat("$type.total", 0f).toDouble())
    }

    private fun saveTotal(edit: SharedPreferences.Editor, type: String, state: DailyTotals.State) {
        edit.putLong("$type.start", state.start)
            .putLong("$type.time", state.time)
            .putFloat("$type.total", state.total.toFloat())
            // Still read by seedToday
            .putLong("$type.day", dayOf(state.start + DailyTotals.SAME_PERIOD_SLACK_MS))
    }

    /**
     * How far the wall clock jumped since the last batch on this boot (time or date set by hand).
     * Health Services times points from boot, so after a jump the same point converts to a
     * different wall-clock time; saved times are moved by as much so the two still compare.
     * A timezone change needs nothing: instants don't move with the zone.
     */
    private fun followClockJump(context: Context, prefs: SharedPreferences, boot: Instant) {
        val bootMs = boot.toEpochMilli()
        val bootId = bootId(context, bootMs)
        val shift = if (prefs.getLong("clock.bootId", Long.MIN_VALUE) == bootId) {
            bootMs - prefs.getLong("clock.boot", bootMs)
        } else 0L
        val edit = prefs.edit().putLong("clock.bootId", bootId).putLong("clock.boot", bootMs)
        // Below this it's ordinary drift (network time nudging the clock), covered by the slack
        if (abs(shift) > CLOCK_JUMP_MS) {
            Log.i(TAG, "Clock moved ${shift / 1000} s, moving saved totals with it")
            for (type in DAILY_TYPES) {
                val state = loadTotal(prefs, type) ?: continue
                saveTotal(edit, type, state.copy(start = state.start + shift, time = state.time + shift))
            }
        }
        // apply() updates what prefs reads at once; the disk write follows in the background
        edit.apply()
    }

    /** Tells one boot from the next: the system's boot count, else the boot time to 10 minutes. */
    private fun bootId(context: Context, bootMs: Long): Long {
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        return if (count >= 0) count.toLong() else -(bootMs / (10 * 60_000L)) - 1
    }

    /**
     * Once per day: queue yesterday's resting heart rate for Apple Health, drop history older
     * than HealthHistory keeps, and drop outbox samples the iPhone never picked up.
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
        HealthStore.get(context).pruneOld()
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
