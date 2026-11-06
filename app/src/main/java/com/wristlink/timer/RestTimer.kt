package com.wristlink.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.wristlink.tile.RestTimerTileService

/**
 * A standalone rest timer for gym sets: pick a duration, rack the watch arm, get a
 * strong buzz when it's time to lift again.
 *
 * Deliberately not tied to the phone at all — iOS rest timers (Lyfta & co.) run as Live
 * Activities, which never reach ANCS, so mirroring them is impossible. A native timer is
 * the reliable version of the same thing.
 *
 * The end time lives in SharedPreferences and the buzz comes from an [AlarmManager] alarm
 * (see [RestTimerAlarmReceiver]), so the timer survives the activity being swiped away,
 * the screen sleeping, and doze.
 */
object RestTimer {

    private const val PREFS_NAME = "wristlink_rest_timer"

    /** Wall-clock end time (ms since epoch), 0 when no timer is running. */
    private const val KEY_END_TIME = "end_time"

    /** The last duration that was started, so "Repeat" knows what to repeat. */
    private const val KEY_LAST_DURATION = "last_duration_s"

    const val DEFAULT_DURATION_S = 90
    val PRESETS_S = listOf(60, 90, 120, 180)

    private const val REQUEST_CODE = 100

    fun start(context: Context, durationSeconds: Int) {
        val endTime = System.currentTimeMillis() + durationSeconds * 1000L
        prefs(context).edit()
            .putLong(KEY_END_TIME, endTime)
            .putInt(KEY_LAST_DURATION, durationSeconds)
            .apply()
        scheduleAlarm(context, endTime)
        RestTimerTileService.requestUpdate(context)
    }

    /** Extends a running timer; does nothing when none is running. */
    fun extend(context: Context, bySeconds: Int) {
        val endTime = endTime(context)
        if (endTime == 0L) return
        val newEnd = maxOf(endTime, System.currentTimeMillis()) + bySeconds * 1000L
        prefs(context).edit().putLong(KEY_END_TIME, newEnd).apply()
        scheduleAlarm(context, newEnd)
        RestTimerTileService.requestUpdate(context)
    }

    fun cancel(context: Context) {
        prefs(context).edit().putLong(KEY_END_TIME, 0L).apply()
        alarmManager(context).cancel(alarmIntent(context))
        RestTimerTileService.requestUpdate(context)
    }

    /** Called by the alarm receiver once it has fired, so the state matches. */
    fun clearFinished(context: Context) {
        prefs(context).edit().putLong(KEY_END_TIME, 0L).apply()
        RestTimerTileService.requestUpdate(context)
    }

    fun isRunning(context: Context): Boolean = remainingMs(context) > 0

    fun remainingMs(context: Context): Long {
        val endTime = endTime(context)
        if (endTime == 0L) return 0
        return (endTime - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun lastDurationSeconds(context: Context): Int =
        prefs(context).getInt(KEY_LAST_DURATION, DEFAULT_DURATION_S)

    /** The finish buzz, shared by the alarm receiver and the in-activity fallback. */
    fun vibrateFinished(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400, 150, 600), -1)
        )
    }

    private fun endTime(context: Context): Long = prefs(context).getLong(KEY_END_TIME, 0L)

    private fun scheduleAlarm(context: Context, endTimeMs: Long) {
        val am = alarmManager(context)
        val intent = alarmIntent(context)
        val elapsedAt = SystemClock.elapsedRealtime() + (endTimeMs - System.currentTimeMillis())

        // USE_EXACT_ALARM covers a timer app, but stay graceful if it's ever not granted:
        // a window of a few seconds still beats a crash
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedAt, intent)
        } else {
            am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedAt, 5000L, intent)
        }
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RestTimerAlarmReceiver::class.java)
                .setAction(RestTimerAlarmReceiver.ACTION_FINISHED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
