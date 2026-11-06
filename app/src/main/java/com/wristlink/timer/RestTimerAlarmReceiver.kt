package com.wristlink.timer

import android.app.PendingIntent
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.wristlink.R
import com.wristlink.WristLinkApp
import com.wristlink.ui.RestTimerActivity

/**
 * Fires when the rest is over: a strong buzz plus a notification, even if the timer
 * activity is long gone and the watch is asleep.
 */
class RestTimerAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FINISHED = "com.wristlink.timer.FINISHED"
        private const val NOTIFICATION_ID = 4
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FINISHED) return

        RestTimer.clearFinished(context)
        RestTimer.vibrateFinished(context)

        val openTimer = PendingIntent.getActivity(
            context, NOTIFICATION_ID, Intent(context, RestTimerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, WristLinkApp.REST_TIMER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("Rest over")
            .setContentText("Next set — go!")
            .setContentIntent(openTimer)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
