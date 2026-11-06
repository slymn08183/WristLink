package com.wristlink.sleep

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.wristlink.health.HealthCollector
import com.wristlink.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import kotlin.coroutines.resume

/**
 * Quiets the watch while its wearer sleeps, the way fitness bands do: Do Not Disturb on, and
 * raising the wrist no longer lights up the screen. Undone on waking.
 *
 * Where the watch detects sleep itself (Health Services `USER_ACTIVITY_ASLEEP`, e.g. Galaxy Watch4),
 * that alone decides, at any hour, naps included: see [onPlatformSleepState]. Nothing is polled then.
 *
 * Elsewhere a step-counter fallback runs: asleep means inside the night window and the step
 * counter barely moved since the last check; awake means a real burst of steps, or the window
 * ending. Checks run on an inexact alarm every [CHECK_INTERVAL_MS]; reading the hardware step
 * counter costs next to nothing.
 *
 * Needs two permissions only adb can grant on a watch (see README):
 *   cmd notification allow_dnd com.wristlink
 *   pm grant com.wristlink android.permission.WRITE_SECURE_SETTINGS
 * Without them it still tracks sleep but can't change anything.
 */
object SleepGuard {

    private const val TAG = "SleepGuard"
    private const val PREFS = "sleep_guard"
    private const val KEY_ASLEEP = "asleep"
    private const val KEY_LAST_STEPS = "last_steps"
    private const val KEY_SAVED_FILTER = "saved_filter"
    private const val KEY_SAVED_WAKE_GESTURE = "saved_wake_gesture"

    private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L

    /** Turning over in bed or a trip to the bathroom stays under this between two checks. */
    private const val ASLEEP_MAX_STEPS = 15
    private const val AWAKE_MIN_STEPS = 60

    /** Night window for the step-counter fallback, local time. */
    private const val WINDOW_START_HOUR = 22
    private const val WINDOW_END_HOUR = 10

    /** Raise wrist to wake (Settings.Secure, needs WRITE_SECURE_SETTINGS). */
    private const val WAKE_GESTURE = "wake_gesture_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Called after quiet mode turns on or off (the iPhone link pushes the new state). */
    @Volatile var onStateChanged: (() -> Unit)? = null

    /** Quiet mode is on right now. */
    fun isAsleep(context: Context): Boolean = prefs(context).getBoolean(KEY_ASLEEP, false)

    /** True when the watch's own sleep detection drives quiet mode, false for the step fallback. */
    val usesWatchSleepDetection: Boolean
        get() = HealthCollector.platformSleepSupported.value == true

    /** Can it actually turn on DND and turn off raise-to-wake? */
    fun hasControl(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Call once at app start. Schedules again once it's known whether the watch detects sleep. */
    fun start(context: Context) {
        val app = context.applicationContext
        schedule(app)
        scope.launch {
            HealthCollector.platformSleepSupported.filterNotNull().distinctUntilChanged().collect { schedule(app) }
        }
    }

    /** Start (or keep) checking; call on app start and whenever the setting changes. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context)
        if (!SettingsManager.get(context).isSleepGuardEnabled) {
            alarms.cancel(pending)
            if (isAsleep(context)) scope.launch { wakeUp(context) }
            return
        }
        if (usesWatchSleepDetection) {
            // The watch says when sleep starts and ends; nothing to poll. Catch up with right now,
            // e.g. turned on while already asleep.
            alarms.cancel(pending)
            onPlatformSleepState(context, HealthCollector.isAsleepNow(context))
            return
        }
        alarms.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
            CHECK_INTERVAL_MS,
            pending
        )
        // Take the step baseline now, so the first alarm can already tell stillness from activity
        check(context)
    }

    fun check(context: Context, onDone: () -> Unit = {}) {
        scope.launch {
            try {
                evaluate(context.applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Sleep check failed", e)
            } finally {
                onDone()
            }
        }
    }

    /**
     * The watch's own sleep detection (Health Services, see HealthCollector) changed state. Where
     * the watch has it, this is what drives quiet mode; the step-counter check stays as a fallback.
     */
    fun onPlatformSleepState(context: Context, asleep: Boolean) {
        if (!SettingsManager.get(context).isSleepGuardEnabled) return
        scope.launch {
            when {
                asleep && !isAsleep(context) -> fallAsleep(context)
                !asleep && isAsleep(context) -> wakeUp(context)
            }
        }
    }

    private suspend fun evaluate(context: Context) {
        if (usesWatchSleepDetection) {
            // A leftover alarm from the fallback: follow the watch's own state and stop polling
            schedule(context)
            return
        }
        val prefs = prefs(context)
        val steps = readStepCount(context)
        val lastSteps = prefs.getLong(KEY_LAST_STEPS, -1)
        if (steps != null) prefs.edit().putLong(KEY_LAST_STEPS, steps).apply()
        // The counter restarts at 0 on reboot; treat that as "no data" rather than a huge burst
        val moved = if (steps != null && lastSteps >= 0 && steps >= lastSteps) steps - lastSteps else null

        val asleep = isAsleep(context)
        val inWindow = inNightWindow()
        Log.i(TAG, "check: inWindow=$inWindow asleep=$asleep steps moved=$moved")

        when {
            asleep && !inWindow -> wakeUp(context)
            asleep && moved != null && moved >= AWAKE_MIN_STEPS -> wakeUp(context)
            !asleep && inWindow && moved != null && moved <= ASLEEP_MAX_STEPS -> fallAsleep(context)
        }
    }

    private fun fallAsleep(context: Context) {
        val prefs = prefs(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val editor = prefs.edit().putBoolean(KEY_ASLEEP, true)

        if (nm.isNotificationPolicyAccessGranted) {
            editor.putInt(KEY_SAVED_FILTER, nm.currentInterruptionFilter)
            // Alarms still ring: someone sleeping through their alarm is worse than a buzz
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        }
        runCatching {
            editor.putInt(KEY_SAVED_WAKE_GESTURE, Settings.Secure.getInt(context.contentResolver, WAKE_GESTURE, 1))
            Settings.Secure.putInt(context.contentResolver, WAKE_GESTURE, 0)
        }.onFailure { Log.w(TAG, "Can't turn off raise to wake: ${it.message}") }

        editor.apply()
        Log.i(TAG, "Asleep: quiet mode on")
        onStateChanged?.invoke()
    }

    private fun wakeUp(context: Context) {
        val prefs = prefs(context)
        val nm = context.getSystemService(NotificationManager::class.java)

        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(
                prefs.getInt(KEY_SAVED_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
            )
        }
        runCatching {
            Settings.Secure.putInt(context.contentResolver, WAKE_GESTURE, prefs.getInt(KEY_SAVED_WAKE_GESTURE, 1))
        }.onFailure { Log.w(TAG, "Can't restore raise to wake: ${it.message}") }

        prefs.edit().putBoolean(KEY_ASLEEP, false).apply()
        Log.i(TAG, "Awake: quiet mode off")
        onStateChanged?.invoke()
    }

    private fun inNightWindow(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= WINDOW_START_HOUR || hour < WINDOW_END_HOUR
    }

    /** Current value of the hardware step counter (steps since boot), or null if unavailable. */
    private suspend fun readStepCount(context: Context): Long? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val sensors = context.getSystemService(SensorManager::class.java)
        val counter = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sensors.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0].toLong())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensors.registerListener(listener, counter, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sensors.unregisterListener(listener) }
            }
        }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, SleepGuardReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class SleepGuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        SleepGuard.check(context) { pending.finish() }
    }
}
