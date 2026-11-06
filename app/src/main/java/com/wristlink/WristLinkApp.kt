package com.wristlink

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.wristlink.notification.NotificationChannels
import com.wristlink.health.HealthCollector
import com.wristlink.health.HealthAlerts
import com.wristlink.service.WristLinkService
import com.wristlink.settings.SettingsManager
import com.wristlink.sleep.SleepGuard
import com.wristlink.workout.WorkoutState

class WristLinkApp : Application() {

    companion object {
        const val SERVICE_CHANNEL_ID = "wristlink_service"
        const val CONNECTION_ALERTS_CHANNEL_ID = "wristlink_connection_alerts"
        const val MEDIA_CHANNEL_ID = "wristlink_media"
        const val PHONE_BATTERY_CHANNEL_ID = "wristlink_phone_battery"
        const val REST_TIMER_CHANNEL_ID = "wristlink_rest_timer"
    }

    override fun onCreate() {
        super.onCreate()
        // Before anything opens a database or a preferences file
        LegacyImport.runOnce(this)
        createServiceChannel()
        NotificationChannels.createAll(this, vibrate = SettingsManager.get(this).isVibrationEnabled)
        HealthCollector.onSleepStateChanged = { asleep ->
            SleepGuard.onPlatformSleepState(this, asleep)
            WristLinkService.companionServer?.onSettingsChanged()
        }
        SleepGuard.onStateChanged = { WristLinkService.companionServer?.onSettingsChanged() }
        // No move reminders or resting heart-rate alerts mid-workout
        HealthAlerts.workoutActive = { WorkoutState.active.value }
        HealthCollector.register(this)
        SleepGuard.start(this)
    }

    private fun createServiceChannel() {
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps BLE connection alive"
            setShowBadge(false)
        }

        val alerts = NotificationChannel(
            CONNECTION_ALERTS_CHANNEL_ID,
            "Connection alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Problems that stop phone notifications, like Bluetooth being off"
            setShowBadge(false)
        }

        val media = NotificationChannel(
            MEDIA_CHANNEL_ID,
            "Now playing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Music playing on your phone, with playback controls"
            setShowBadge(false)
        }

        val phoneBattery = NotificationChannel(
            PHONE_BATTERY_CHANNEL_ID,
            "Phone battery",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alert when the phone's battery runs low"
            setShowBadge(false)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
        }

        val restTimer = NotificationChannel(
            REST_TIMER_CHANNEL_ID,
            "Rest timer",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Rest timer finished"
            setShowBadge(false)
            enableVibration(true)
            // Long, insistent: has to be felt mid-workout with the arm in motion
            vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 600)
        }

        val nm = getSystemService(NotificationManager::class.java)
        // Channel of an early WristLink test build; Android raises a foreground service's
        // IMPORTANCE_NONE channel to LOW, so it never managed to hide the notification
        nm.deleteNotificationChannel("wristlink_service_hidden")
        nm.createNotificationChannels(listOf(channel, alerts, media, phoneBattery, restTimer))
    }
}
