package com.watchbridge.health

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

    // Calories are left out for now: Health Services reports the total burn, resting included,
    // and Apple Health wants active and basal energy separately
    private val wanted = setOf(
        DataType.STEPS_DAILY,
        DataType.DISTANCE_DAILY,
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

    private fun hasPermissions(context: Context) = listOf(
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.BODY_SENSORS
    ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Health Services times points from boot; this turns them into wall-clock instants. */
    private fun bootInstant(): Instant =
        Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

    fun onDataPoints(context: Context, points: DataPointContainer) {
        val store = HealthStore.get(context)
        val boot = bootInstant()

        // Daily types are running totals since midnight; store what was added since last time
        recordDailyDelta(context, store, HealthStore.STEPS,
            points.getData(DataType.STEPS_DAILY).map { it.getEndInstant(boot).toEpochMilli() to it.value.toDouble() })
        recordDailyDelta(context, store, HealthStore.DISTANCE,
            points.getData(DataType.DISTANCE_DAILY).map { it.getEndInstant(boot).toEpochMilli() to it.value })
        recordDailyDelta(context, store, HealthStore.CALORIES,
            points.getData(DataType.CALORIES_DAILY).map { it.getEndInstant(boot).toEpochMilli() to it.value })

        for (hr in points.getData(DataType.HEART_RATE_BPM)) {
            if (hr.value <= 0) continue
            val t = hr.getTimeInstant(boot).toEpochMilli()
            store.add(HealthStore.HEART_RATE, t, t, hr.value)
        }
    }

    private fun recordDailyDelta(
        context: Context,
        store: HealthStore,
        type: String,
        totals: List<Pair<Long, Double>>
    ) {
        if (totals.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
            if (delta > 0 && time > from) store.add(type, from, time, delta)
            if (day != lastDay || total >= lastTotal) {
                prefs.edit()
                    .putLong("$type.day", day)
                    .putLong("$type.time", time)
                    .putFloat("$type.total", total.toFloat())
                    .apply()
            }
        }
    }

    fun onUserActivity(context: Context, info: UserActivityInfo) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val changedAt = info.stateChangeTime.toEpochMilli()
        val asleep = info.userActivityState == UserActivityState.USER_ACTIVITY_ASLEEP
        val sleepStart = prefs.getLong("sleep.start", -1)
        Log.i(TAG, "User activity: ${info.userActivityState} at ${info.stateChangeTime}")

        if (asleep && sleepStart < 0) {
            prefs.edit().putLong("sleep.start", changedAt).apply()
        } else if (!asleep && sleepStart >= 0) {
            if (changedAt > sleepStart) HealthStore.get(context).add(HealthStore.SLEEP, sleepStart, changedAt, 1.0)
            prefs.edit().remove("sleep.start").apply()
        }
        onSleepStateChanged?.invoke(asleep)
    }

    /** The platform said "asleep" and hasn't said otherwise since. */
    fun isAsleepNow(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("sleep.start", -1) >= 0

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
