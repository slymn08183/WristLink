package com.wristlink.health

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wristlink.R
import com.wristlink.ui.HealthActivity
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Coaching on the watch, the way Samsung Health does it:
 *
 * - Heart rate above or below the thresholds for 10 minutes while at rest.
 * - Inactivity: 50 minutes without moving, 09:00–17:00 on weekdays, never on Do Not Disturb or
 *   asleep or during a workout; "Not now" asks again in another 50 minutes.
 * - Step goal nudges ("halfway there" before 2 pm, "almost there", "goal reached") and the
 *   10k/20k/30k/40k step badges, each once a day.
 *
 * Everything runs off data Health Services already delivers; the only alarm is the inactivity
 * one, moved forward every time steps arrive.
 */
object HealthAlerts {

    private const val TAG = "HealthAlerts"
    private const val CHANNEL = "wristlink_health_alerts"
    private const val PREFS = "health_alerts"
    private const val MINUTE_MS = 60_000L
    private const val INACTIVE_MS = 50 * MINUTE_MS
    private const val HR_WINDOW_MS = 10 * MINUTE_MS
    private const val HR_ALERT_COOLDOWN_MS = 60 * MINUTE_MS

    private const val ID_HR = 7101
    private const val ID_INACTIVE = 7102
    private const val ID_GOAL = 7103

    const val ACTION_CHECK_INACTIVE = "com.wristlink.health.CHECK_INACTIVE"
    const val ACTION_SNOOZE = "com.wristlink.health.SNOOZE_INACTIVE"

    /** Set by the workout app: no inactivity or resting-HR alerts while working out. */
    @Volatile var workoutActive: () -> Boolean = { false }

    fun onNewData(context: Context) {
        val profile = HealthProfile.current(context)
        runCatching {
            if (profile.hrAlerts && !workoutActive()) checkHeartRate(context, profile)
            checkGoals(context, profile)
        }.onFailure { Log.w(TAG, "Check failed", it) }
    }

    // ---- Heart rate ----

    /** Whether a reading would count towards a heart-rate alert, so the next comes sooner. */
    fun outsideAlertRange(context: Context, bpm: Double): Boolean {
        val profile = HealthProfile.current(context)
        return profile.hrAlerts && !workoutActive() && (bpm > profile.hrHigh || bpm < profile.hrLow)
    }

    private fun checkHeartRate(context: Context, profile: HealthProfile.Profile) {
        val now = System.currentTimeMillis()
        val prefs = prefs(context)
        if (now - prefs.getLong("hr.last", 0) < HR_ALERT_COOLDOWN_MS) return
        val minutes = HealthHistory.get(context).minutes(now - HR_WINDOW_MS, now)
        val withHr = minutes.filter { it.hrAvg != null }
        // Three measurements in the window (HeartRateSampler measures every 3 minutes once one
        // is out of range), all out of range, and no walking in between
        if (withHr.size < 3 || minutes.sumOf { it.steps } > 20) return
        val high = withHr.all { it.hrAvg!! > profile.hrHigh }
        val low = withHr.all { it.hrAvg!! < profile.hrLow }
        if (!high && !low) return
        prefs.edit().putLong("hr.last", now).apply()
        val bpm = withHr.last().hrAvg!!.roundToInt()
        notify(
            context, ID_HR,
            if (high) "High heart rate" else "Low heart rate",
            "$bpm bpm for 10 minutes while resting (alert ${if (high) "above ${profile.hrHigh}" else "below ${profile.hrLow}"})",
            HealthActivity.SCREEN_HEART
        )
    }

    // ---- Goals and badges ----

    private fun checkGoals(context: Context, profile: HealthProfile.Profile) {
        if (!profile.goalNudges) return
        val now = System.currentTimeMillis()
        val today = HealthMetrics.startOfDay(now)
        val steps = HealthHistory.get(context).minutes(today, now + 1).sumOf { it.steps }
        val goal = profile.stepGoal.toDouble()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        fun once(key: String, block: () -> Unit) {
            val prefs = prefs(context)
            if (prefs.getLong("goal.$key", 0) == today) return
            prefs.edit().putLong("goal.$key", today).apply()
            block()
        }

        when {
            steps >= goal -> once("done") {
                notify(context, ID_GOAL, "Step goal reached", "${steps.roundToInt()} steps today. Nice work!", HealthActivity.SCREEN_TODAY)
            }
            steps >= goal * 0.9 -> once("almost") {
                notify(context, ID_GOAL, "Almost there", "${(goal - steps).roundToInt()} steps to your goal", HealthActivity.SCREEN_TODAY)
            }
            steps >= goal * 0.5 && hour < 14 -> once("half") {
                notify(context, ID_GOAL, "Halfway there", "Half your step goal before 2 pm", HealthActivity.SCREEN_TODAY)
            }
        }
        for (badge in listOf(40_000, 30_000, 20_000, 10_000)) {
            if (steps >= badge) {
                once("badge$badge") {
                    notify(context, ID_GOAL, "${badge / 1000}K steps", "New badge: ${badge / 1000},000 steps in a day", HealthActivity.SCREEN_TODAY)
                }
                break
            }
        }
    }

    // ---- Inactivity ----

    /** Steps arrived: the inactivity alarm moves to 50 minutes after them. */
    fun onMoved(context: Context, lastMove: Long) {
        if (!HealthProfile.current(context).inactivityAlert) return
        schedule(context, lastMove + INACTIVE_MS)
    }

    private fun schedule(context: Context, at: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        // Inexact is fine, a few minutes late doesn't matter and it lets the watch batch wakeups
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, checkIntent(context))
    }

    internal fun checkInactive(context: Context, snooze: Boolean) {
        val now = System.currentTimeMillis()
        val profile = HealthProfile.current(context)
        if (!profile.inactivityAlert) return
        if (snooze) {
            context.getSystemService(NotificationManager::class.java).cancel(ID_INACTIVE)
            schedule(context, now + INACTIVE_MS)
            return
        }
        val idle = now - LiveHealth.lastMove(context)
        if (idle < INACTIVE_MS - MINUTE_MS) return

        val window = nextWindowStart(now)
        if (window > now) {
            // Outside 09:00–17:00 on a weekday: look again once the window opens
            schedule(context, window + INACTIVE_MS)
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val quiet = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (quiet || HealthCollector.isAsleepNow(context) || workoutActive()) {
            schedule(context, now + INACTIVE_MS)
            return
        }
        val snoozeIntent = PendingIntent.getBroadcast(
            context, 1, Intent(context, HealthAlertReceiver::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notify(
            context, ID_INACTIVE, "Time to move",
            "You've been still for ${idle / MINUTE_MS} minutes. Stand up and stretch for a bit.",
            HealthActivity.SCREEN_TODAY,
            NotificationCompat.Action.Builder(0, "Not now", snoozeIntent).build()
        )
        schedule(context, now + INACTIVE_MS)
    }

    /** [now] when inside the window, else when it next opens. */
    private fun nextWindowStart(now: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        repeat(8) {
            val weekday = c.get(Calendar.DAY_OF_WEEK) !in setOf(Calendar.SATURDAY, Calendar.SUNDAY)
            val start = (c.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val end = start + 8 * 60 * MINUTE_MS
            if (weekday && now < end) return maxOf(now, start)
            c.add(Calendar.DAY_OF_YEAR, 1)
            c.set(Calendar.HOUR_OF_DAY, 0)
        }
        return now
    }

    // ---- Plumbing ----

    private fun notify(
        context: Context,
        id: Int,
        title: String,
        text: String,
        screen: String,
        action: NotificationCompat.Action? = null
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Health alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Heart rate alerts, reminders to move, step goals"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            context, id, HealthActivity.intent(context, screen),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .apply { if (action != null) addAction(action) }
            .build()
        runCatching { nm.notify(id, notification) }.onFailure { Log.w(TAG, "Couldn't notify", it) }
    }

    private fun checkIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, HealthAlertReceiver::class.java).setAction(ACTION_CHECK_INACTIVE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The inactivity alarm and the "Not now" button. */
class HealthAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HealthAlerts.checkInactive(context, snooze = intent.action == HealthAlerts.ACTION_SNOOZE)
    }
}
