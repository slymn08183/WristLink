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
import androidx.health.services.client.HealthServices
import com.wristlink.R
import com.wristlink.ui.HealthActivity
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Coaching on the watch, the way Samsung Health does it:
 *
 * - Heart rate above or below the thresholds for 10 minutes while at rest.
 * - Inactivity: 50 minutes without moving, 09:00–17:00 on weekdays, never on Do Not Disturb,
 *   asleep, during a workout or with the watch off the wrist; "Not now" asks again in another
 *   50 minutes.
 * - Step goal nudges ("halfway there" before 2 pm, "almost there", "goal reached") and the
 *   10k/20k/30k/40k step badges, each once a day.
 *
 * Everything runs off data Health Services already delivers; the only alarm is the inactivity
 * one, moved forward every time the wearer really moves.
 */
object HealthAlerts {

    private const val TAG = "HealthAlerts"
    private const val CHANNEL = "wristlink_health_alerts"
    private const val PREFS = "health_alerts"
    private const val MINUTE_MS = InactivityRules.MINUTE_MS
    private const val INACTIVE_MS = InactivityRules.INACTIVE_MS
    /** After a flush, how long to wait for the late steps before deciding. */
    private const val CONFIRM_DELAY_MS = 2 * MINUTE_MS
    /** No heart rate for this long (it runs ~1 Hz when worn) means the watch is off the wrist. */
    private const val OFF_WRIST_MS = 10 * MINUTE_MS
    /** A planned check this overdue was lost (watch off, force stop), not just delivered late. */
    private const val LATE_GRACE_MS = 15 * MINUTE_MS
    private const val KEY_PLANNED_AT = "inactive.at"
    private const val KEY_PLANNED_CONFIRM = "inactive.confirm"
    private const val HR_WINDOW_MS = 10 * MINUTE_MS
    private const val HR_ALERT_COOLDOWN_MS = 60 * MINUTE_MS

    private const val ID_HR = 7101
    private const val ID_INACTIVE = 7102
    private const val ID_GOAL = 7103

    const val ACTION_CHECK_INACTIVE = "com.wristlink.health.CHECK_INACTIVE"
    const val ACTION_SNOOZE = "com.wristlink.health.SNOOZE_INACTIVE"
    /** The second look, two minutes after the first one flushed Health Services' batch. */
    const val EXTRA_CONFIRM = "confirm"

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

    private fun checkHeartRate(context: Context, profile: HealthProfile.Profile) {
        val now = System.currentTimeMillis()
        val prefs = prefs(context)
        if (now - prefs.getLong("hr.last", 0) < HR_ALERT_COOLDOWN_MS) return
        val minutes = HealthHistory.get(context).minutes(now - HR_WINDOW_MS, now)
        val withHr = minutes.filter { it.hrAvg != null }
        // Needs a reading most minutes, and no walking in between
        if (withHr.size < 8 || minutes.sumOf { it.steps } > 20) return
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
        val steps = HealthHistory.get(context).steps(today, now + 1)
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

    /**
     * The wearer really moved (see [InactivityRules.lastMove]): the check moves to 50 minutes
     * after that, or to 50 minutes into the next weekday window, and a "Time to move" still on
     * screen goes away.
     */
    fun onMoved(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_INACTIVE)
        if (!HealthProfile.current(context).inactivityAlert) return
        plan(context, InactivityRules.inWindow(LiveHealth.lastMove(context) + INACTIVE_MS))
    }

    /**
     * Make sure the inactivity check is armed, or cancelled when the reminder is off. Call on
     * app start, after boot or an update (both drop alarms) and when the reminder is switched.
     *
     * A check already planned is kept as it was, so calling this on every process start doesn't
     * push the reminder back; it's only re-armed when the system no longer holds it. One that
     * was lost (missed by more than [LATE_GRACE_MS], e.g. the watch was off) is planned afresh
     * 50 minutes from now rather than firing the moment the watch is back.
     */
    fun ensureScheduled(context: Context) {
        if (!HealthProfile.current(context).inactivityAlert) {
            cancel(context)
            return
        }
        val now = System.currentTimeMillis()
        LiveHealth.initLastMove(context, now)
        val prefs = prefs(context)
        val planned = prefs.getLong(KEY_PLANNED_AT, 0)
        if (planned > now - LATE_GRACE_MS) {
            if (!isArmed(context)) arm(context, planned, prefs.getBoolean(KEY_PLANNED_CONFIRM, false))
            return
        }
        plan(context, InactivityRules.inWindow(maxOf(LiveHealth.lastMove(context), now) + INACTIVE_MS))
    }

    internal fun checkInactive(context: Context, snooze: Boolean, confirm: Boolean) {
        val now = System.currentTimeMillis()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!HealthProfile.current(context).inactivityAlert) {
            cancel(context)
            return
        }
        if (snooze) {
            nm.cancel(ID_INACTIVE)
            plan(context, InactivityRules.inWindow(now + INACTIVE_MS))
            return
        }
        LiveHealth.initLastMove(context, now)
        val lastMove = LiveHealth.lastMove(context)
        val idle = now - lastMove
        if (idle < INACTIVE_MS - MINUTE_MS) {
            // Moved since this was planned: look again 50 minutes after that move
            plan(context, InactivityRules.inWindow(lastMove + INACTIVE_MS))
            return
        }
        if (InactivityRules.nextWindowStart(now) > now) {
            // Outside 09:00–17:00 on a weekday: look again once the window has been open a while
            plan(context, InactivityRules.inWindow(now))
            return
        }
        val quiet = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (quiet || HealthCollector.isAsleepNow(context) || workoutActive()) {
            plan(context, InactivityRules.inWindow(now + INACTIVE_MS))
            return
        }
        if (!confirm) {
            // Health Services hands steps over in batches that can be minutes late, so a walk
            // under way can still look like sitting. Ask it for what it has and decide in two
            // minutes; if the steps show a walk, onMoved replaces this check.
            runCatching { HealthServices.getClient(context).passiveMonitoringClient.flushAsync() }
                .onFailure { Log.w(TAG, "Couldn't flush passive data", it) }
            plan(context, now + CONFIRM_DELAY_MS, confirm = true)
            return
        }
        // Heart rate runs about once a second while worn: none for a while means it's off the wrist
        val hr = LiveHealth.latest(context)
        if (hr == null || now - hr.time > OFF_WRIST_MS) {
            plan(context, InactivityRules.inWindow(now + INACTIVE_MS))
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
        plan(context, InactivityRules.inWindow(now + INACTIVE_MS))
    }

    /** Arm the check for [at] and remember it, so [ensureScheduled] can put it back as it was. */
    private fun plan(context: Context, at: Long, confirm: Boolean = false) {
        prefs(context).edit().putLong(KEY_PLANNED_AT, at).putBoolean(KEY_PLANNED_CONFIRM, confirm).apply()
        arm(context, at, confirm)
    }

    private fun arm(context: Context, at: Long, confirm: Boolean) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        // Inexact is fine, a few minutes late doesn't matter and it lets the watch batch wakeups
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, checkIntent(context, confirm))
    }

    private fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(checkIntent(context, false))
        prefs(context).edit().remove(KEY_PLANNED_AT).remove(KEY_PLANNED_CONFIRM).apply()
    }

    /** The system still holds the check's PendingIntent: an alarm is set (gone after boot, update or force stop). */
    private fun isArmed(context: Context): Boolean = PendingIntent.getBroadcast(
        context, 0, checkBaseIntent(context), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    ) != null

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

    private fun checkBaseIntent(context: Context) =
        Intent(context, HealthAlertReceiver::class.java).setAction(ACTION_CHECK_INACTIVE)

    /**
     * One PendingIntent for both stages (extras don't make a new one), so planning either
     * replaces whatever was set before.
     */
    private fun checkIntent(context: Context, confirm: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, 0, checkBaseIntent(context).putExtra(EXTRA_CONFIRM, confirm),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The inactivity alarm and the "Not now" button. */
class HealthAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HealthAlerts.checkInactive(
            context,
            snooze = intent.action == HealthAlerts.ACTION_SNOOZE,
            confirm = intent.getBooleanExtra(HealthAlerts.EXTRA_CONFIRM, false)
        )
    }
}
