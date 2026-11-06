package com.wristlink.companion

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.wristlink.call.WatchCalls
import com.wristlink.health.HealthCollector
import com.wristlink.health.HealthProfile
import com.wristlink.settings.SettingsManager
import com.wristlink.sleep.SleepGuard
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch settings the iPhone app can read and change over the companion link.
 *
 * WristLink's own settings always work. System settings need the permissions the installer
 * grants with adb (WRITE_SECURE_SETTINGS, WRITE_SETTINGS, Do Not Disturb access); the snapshot
 * says which are available so the iPhone can grey out the rest.
 */
object RemoteSettings {

    /** Samsung's watch-face status icons, e.g. "Disconnected from phone" (0 = shown, 1 = hidden). */
    private const val STATUS_ICONS = "home_status_indicator_display_config"
    private const val WAKE_GESTURE = "wake_gesture_enabled"

    private fun settings(context: Context) = SettingsManager.get(context)

    fun snapshot(context: Context): JSONObject {
        val s = settings(context)
        val resolver = context.contentResolver
        val nm = context.getSystemService(NotificationManager::class.java)
        val secure = canWriteSecure(context)
        val filters = s.getAllCategoryFilters()

        return JSONObject()
            .put("vibration", s.vibrationEnabled.value)
            .put("respectDnd", s.respectDnd.value)
            .put("showPreExisting", s.showPreExisting.value)
            .put("showSilent", s.showSilent.value)
            // Only really on once the watch has the phone permissions (asked on the watch)
            .put("callsOnWatch", s.callsOnWatch.value && WatchCalls.hasPermissions(context))
            .put("canTalkOnWatch", WatchCalls.hasPermissions(context))
            .put("phoneBatteryAlert", s.phoneBatteryAlert.value)
            .put("statusNotification", s.statusNotification.value)
            .put("sleepGuard", s.sleepGuard.value)
            .put("disconnectAlert", s.disconnectAlert.value)
            .put("showPhoneInfo", s.showPhoneInfo.value)
            // Sleep as the watch sees it, and whether quiet mode is on because of it
            .put("asleep", if (SleepGuard.usesWatchSleepDetection) HealthCollector.isAsleepNow(context) else SleepGuard.isAsleep(context))
            .put("quietNow", SleepGuard.isAsleep(context))
            .put("sleepDetection", if (SleepGuard.usesWatchSleepDetection) "watch" else "steps")
            .put("sleepGuardReady", SleepGuard.hasControl(context))
            .put("profile", HealthProfile.current(context).toJson())
            .put("categories", JSONArray(SettingsManager.CATEGORIES.map { (id, name) ->
                JSONObject().put("id", id.toInt()).put("name", name).put("enabled", filters[id] ?: true)
            }))
            // System
            .put("canChangeSystem", secure)
            .put("canChangeDnd", nm.isNotificationPolicyAccessGranted)
            .put("raiseToWake", Settings.Secure.getInt(resolver, WAKE_GESTURE, 1) == 1)
            .put("screenTimeoutSec", Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 15000) / 1000)
            .put("use24Hour", Settings.System.getString(resolver, Settings.System.TIME_12_24) == "24")
            .put("hideStatusIcons", Settings.System.getInt(resolver, STATUS_ICONS, 0) == 1)
            .put("doNotDisturb", nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
            .put("brightness", (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255).coerceIn(1, 100))
            .put("autoBrightness", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 1) == 1)
            .put("alwaysOnDisplay", Settings.Global.getInt(resolver, AOD, 0) == 1)
            .put("tiltToWakeAod", Settings.Global.getInt(resolver, AOD_TILT_TO_WAKE, 1) == 1)
            .put("wakeByBezel", Settings.Global.getInt(resolver, BEZEL_WAKE, 1) == 1)
            .put("doubleTapToWake", Settings.Secure.getInt(resolver, DOUBLE_TAP_WAKE, 1) == 1)
            .put("touchSensitivity", Settings.System.getInt(resolver, TOUCH_SENSITIVITY, 0) == 1)
            .put("hapticFeedback", Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1)
            .put("fontScale", Settings.System.getFloat(resolver, Settings.System.FONT_SCALE, 1f).toDouble())
    }

    // Wear OS / Samsung keys found on a Galaxy Watch4 (One UI 8 Watch)
    private const val AOD = "ambient_enabled"
    private const val AOD_TILT_TO_WAKE = "ambient_tilt_to_wake"
    private const val BEZEL_WAKE = "setting_wake_by_turning_bezel"
    private const val DOUBLE_TAP_WAKE = "double_tap_to_wake"
    private const val TOUCH_SENSITIVITY = "setting_touch_sensitivity_on"

    /** Apply one change; returns an error message, or null when it worked. */
    fun apply(context: Context, key: String, value: Any?): String? = runCatching {
        val s = settings(context)
        val resolver = context.contentResolver
        val on = value == true
        when {
            key == "vibration" -> s.setVibrationEnabled(on)
            key == "respectDnd" -> s.setRespectDnd(on)
            key == "showPreExisting" -> s.setShowPreExisting(on)
            key == "showSilent" -> s.setShowSilent(on)
            key == "callsOnWatch" -> {
                if (on && !WatchCalls.hasPermissions(context)) {
                    return "Allow phone access on the watch first: WristLink, Settings, Calls on watch"
                }
                s.setCallsOnWatch(on)
            }
            key == "phoneBatteryAlert" -> s.setPhoneBatteryAlert(on)
            key == "statusNotification" -> s.setStatusNotification(on)
            key == "disconnectAlert" -> s.setDisconnectAlert(on)
            key == "showPhoneInfo" -> s.setShowPhoneInfo(on)
            key == "sleepGuard" -> {
                s.setSleepGuard(on)
                SleepGuard.schedule(context)
            }
            key.startsWith("profile.") -> return HealthProfile.get(context).apply(key.removePrefix("profile."), value)
            key.startsWith("category.") -> s.setCategoryEnabled(key.removePrefix("category.").toByte(), on)

            key == "raiseToWake" -> Settings.Secure.putInt(resolver, WAKE_GESTURE, if (on) 1 else 0)
            key == "screenTimeoutSec" -> Settings.System.putInt(
                resolver, Settings.System.SCREEN_OFF_TIMEOUT, ((value as Number).toInt() * 1000).coerceIn(5000, 300000)
            )
            key == "use24Hour" -> Settings.System.putString(resolver, Settings.System.TIME_12_24, if (on) "24" else "12")
            key == "hideStatusIcons" -> Settings.System.putInt(resolver, STATUS_ICONS, if (on) 1 else 0)
            key == "doNotDisturb" -> context.getSystemService(NotificationManager::class.java).setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            key == "brightness" -> Settings.System.putInt(
                resolver, Settings.System.SCREEN_BRIGHTNESS, ((value as Number).toInt().coerceIn(1, 100) * 255 / 100)
            )
            key == "autoBrightness" -> Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) 1 else 0)
            key == "alwaysOnDisplay" -> Settings.Global.putInt(resolver, AOD, if (on) 1 else 0)
            key == "tiltToWakeAod" -> Settings.Global.putInt(resolver, AOD_TILT_TO_WAKE, if (on) 1 else 0)
            key == "wakeByBezel" -> Settings.Global.putInt(resolver, BEZEL_WAKE, if (on) 1 else 0)
            key == "doubleTapToWake" -> Settings.Secure.putInt(resolver, DOUBLE_TAP_WAKE, if (on) 1 else 0)
            key == "touchSensitivity" -> Settings.System.putInt(resolver, TOUCH_SENSITIVITY, if (on) 1 else 0)
            key == "hapticFeedback" -> Settings.System.putInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, if (on) 1 else 0)
            key == "fontScale" -> Settings.System.putFloat(
                resolver, Settings.System.FONT_SCALE, (value as Number).toFloat().coerceIn(0.8f, 1.4f)
            )
            else -> return "Unknown setting $key"
        }
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    private fun canWriteSecure(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
}
