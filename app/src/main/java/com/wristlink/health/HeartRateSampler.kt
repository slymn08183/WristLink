package com.wristlink.health

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.wristlink.workout.WorkoutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Heart rate every 10 minutes: the sensor runs for about 20 seconds and the steady part of
 * what it reads is kept, like Samsung Health's "every 10 minutes" setting.
 *
 * Replaces the passive heart-rate subscription, which on Galaxy watches keeps the sensor on
 * all the time (see [HealthCollector]). After a reading outside the alert range the next one
 * comes in 3 minutes, so [HealthAlerts] can tell a lasting high or low from a blip. Nothing is
 * measured during a workout, which records heart rate itself.
 */
object HeartRateSampler {

    private const val TAG = "HeartRateSampler"
    private const val INTERVAL_MS = 10 * 60_000L
    private const val FOLLOW_UP_MS = 3 * 60_000L
    private const val MEASURE_MS = 20_000L
    /** The first readings while the sensor settles are often off. */
    private const val SETTLE_MS = 6_000L
    private const val ACTION = "com.wristlink.health.SAMPLE_HEART_RATE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Next measurement in [delayMs] (replacing any scheduled one). */
    fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java)
        // Allowed while idle: in Doze the system still lets one through every ~9 minutes
        alarms.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pendingIntent(app)
        )
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, Receiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val app = context.applicationContext
            val pending = goAsync()
            val wakeLock = app.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristLink:heartRate")
                .apply { acquire(MEASURE_MS + 10_000) }
            scope.launch {
                var next = INTERVAL_MS
                try {
                    if (!WorkoutState.active.value) {
                        val bpm = measure(app)
                        if (bpm != null && HealthAlerts.outsideAlertRange(app, bpm)) next = FOLLOW_UP_MS
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Measuring failed", e)
                } finally {
                    schedule(app, next)
                    if (wakeLock.isHeld) wakeLock.release()
                    pending.finish()
                }
            }
        }
    }

    /** Runs the sensor for [MEASURE_MS] and records the settled readings; returns their median. */
    private suspend fun measure(context: Context): Double? {
        val client = HealthServices.getClient(context).measureClient
        val readings = ArrayList<Pair<Long, Double>>()
        val started = System.currentTimeMillis()
        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit
            override fun onDataReceived(data: DataPointContainer) {
                val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
                data.getData(DataType.HEART_RATE_BPM).forEach {
                    if (it.value > 0) synchronized(readings) { readings.add(it.getTimeInstant(boot).toEpochMilli() to it.value) }
                }
            }
        }
        client.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        try {
            delay(MEASURE_MS)
        } finally {
            withTimeoutOrNull(5_000) {
                runCatching { client.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback).await() }
            }
        }
        val settled = synchronized(readings) { readings.filter { it.first >= started + SETTLE_MS }.sortedBy { it.first } }
        if (settled.isEmpty()) {
            Log.i(TAG, "No heart rate (${readings.size} readings while settling); off the wrist?")
            return null
        }
        val median = settled.map { it.second }.sorted()[settled.size / 2]
        HealthCollector.recordHeartRates(context, settled)
        Log.i(TAG, "Heart rate ${median.toInt()} bpm from ${settled.size} readings")
        return median
    }
}
