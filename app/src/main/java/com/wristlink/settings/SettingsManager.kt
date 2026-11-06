package com.wristlink.settings

import android.content.Context
import android.content.SharedPreferences
import com.wristlink.ancs.AncsConstants
import com.wristlink.notification.NotificationChannels
import com.wristlink.tile.ConnectionTileService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted app settings for notification filtering, vibration, and behavior.
 *
 * One instance per process ([get]), shared by the screens, the service and the iPhone link, so a
 * change made in one place is seen everywhere at once.
 */
class SettingsManager private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "wristlink_settings"

        // Keys
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_RESPECT_DND = "respect_dnd"
        private const val KEY_SHOW_PRE_EXISTING = "show_pre_existing"
        private const val KEY_SHOW_SILENT = "show_silent"
        private const val KEY_SHOW_PHONE_INFO = "show_phone_info"
        private const val KEY_CALLS_ON_WATCH = "calls_on_watch"
        private const val KEY_PHONE_BATTERY_ALERT = "phone_battery_alert"
        private const val KEY_STATUS_NOTIFICATION = "status_notification"
        private const val KEY_SLEEP_GUARD = "sleep_guard"
        private const val KEY_DISCONNECT_ALERT = "disconnect_alert"
        private const val KEY_CALL_PERMISSIONS_ASKED = "call_permissions_asked"
        private const val KEY_CATEGORY_PREFIX = "category_enabled_"

        /** iOS notification categories with the names both apps show, in display order. */
        val CATEGORIES: List<Pair<Byte, String>> = listOf(
            AncsConstants.CATEGORY_INCOMING_CALL to "Incoming calls",
            AncsConstants.CATEGORY_MISSED_CALL to "Missed calls",
            AncsConstants.CATEGORY_VOICEMAIL to "Voicemail",
            AncsConstants.CATEGORY_SOCIAL to "Social",
            AncsConstants.CATEGORY_SCHEDULE to "Schedule",
            AncsConstants.CATEGORY_EMAIL to "Email",
            AncsConstants.CATEGORY_NEWS to "News",
            AncsConstants.CATEGORY_HEALTH_AND_FITNESS to "Health & Fitness",
            AncsConstants.CATEGORY_BUSINESS_AND_FINANCE to "Business & Finance",
            AncsConstants.CATEGORY_LOCATION to "Location",
            AncsConstants.CATEGORY_ENTERTAINMENT to "Entertainment",
            AncsConstants.CATEGORY_OTHER to "Other"
        )

        @Volatile private var instance: SettingsManager? = null

        fun get(context: Context): SettingsManager =
            instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Fires after any setting changes, wherever it was changed (the iPhone link pushes it on). */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    // Held here: SharedPreferences keeps only a weak reference to its listeners
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _changes.tryEmit(Unit) }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    // Observable state
    private val _vibrationEnabled = MutableStateFlow(prefs.getBoolean(KEY_VIBRATION_ENABLED, true))
    val vibrationEnabled: StateFlow<Boolean> = _vibrationEnabled.asStateFlow()

    private val _respectDnd = MutableStateFlow(prefs.getBoolean(KEY_RESPECT_DND, true))
    val respectDnd: StateFlow<Boolean> = _respectDnd.asStateFlow()

    private val _showPreExisting = MutableStateFlow(prefs.getBoolean(KEY_SHOW_PRE_EXISTING, false))
    val showPreExisting: StateFlow<Boolean> = _showPreExisting.asStateFlow()

    private val _showSilent = MutableStateFlow(prefs.getBoolean(KEY_SHOW_SILENT, false))
    val showSilent: StateFlow<Boolean> = _showSilent.asStateFlow()

    private val _showPhoneInfo = MutableStateFlow(prefs.getBoolean(KEY_SHOW_PHONE_INFO, true))
    val showPhoneInfo: StateFlow<Boolean> = _showPhoneInfo.asStateFlow()

    /**
     * Phone name and battery on the connection tile. Read straight from SharedPreferences,
     * like [isVibrationEnabled], since the tile uses its own SettingsManager instance.
     */
    val isPhoneInfoShown: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PHONE_INFO, true)

    val isOnboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun setOnboardingComplete() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    /**
     * Read straight from SharedPreferences (shared by every SettingsManager instance), so a
     * change made from another instance is never missed when picking a notification channel.
     */
    val isVibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)

    fun setVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply()
        _vibrationEnabled.value = enabled
        NotificationChannels.createAll(appContext, vibrate = enabled)
    }

    fun setRespectDnd(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESPECT_DND, enabled).apply()
        _respectDnd.value = enabled
    }

    fun setShowPreExisting(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_PRE_EXISTING, enabled).apply()
        _showPreExisting.value = enabled
    }

    fun setShowSilent(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SILENT, enabled).apply()
        _showSilent.value = enabled
    }

    private val _callsOnWatch = MutableStateFlow(prefs.getBoolean(KEY_CALLS_ON_WATCH, true))
    val callsOnWatch: StateFlow<Boolean> = _callsOnWatch.asStateFlow()

    /**
     * Answer calls on the watch (see WatchCalls). On by default, so a first pairing sets up
     * calls too; it only takes effect once the phone permissions are granted.
     * Read straight from SharedPreferences.
     */
    val isCallsOnWatchEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALLS_ON_WATCH, true)

    /** The phone permissions for calls were asked for at startup once already (don't nag). */
    val callPermissionsAsked: Boolean
        get() = prefs.getBoolean(KEY_CALL_PERMISSIONS_ASKED, false)

    fun markCallPermissionsAsked() {
        prefs.edit().putBoolean(KEY_CALL_PERMISSIONS_ASKED, true).apply()
    }

    fun setCallsOnWatch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALLS_ON_WATCH, enabled).apply()
        _callsOnWatch.value = enabled
    }

    private val _phoneBatteryAlert = MutableStateFlow(prefs.getBoolean(KEY_PHONE_BATTERY_ALERT, true))
    val phoneBatteryAlert: StateFlow<Boolean> = _phoneBatteryAlert.asStateFlow()

    /** Buzz the watch when the iPhone's battery drops low. Read straight from SharedPreferences. */
    val isPhoneBatteryAlertEnabled: Boolean
        get() = prefs.getBoolean(KEY_PHONE_BATTERY_ALERT, true)

    fun setPhoneBatteryAlert(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PHONE_BATTERY_ALERT, enabled).apply()
        _phoneBatteryAlert.value = enabled
    }

    private val _statusNotification = MutableStateFlow(prefs.getBoolean(KEY_STATUS_NOTIFICATION, true))

    /**
     * Keep the connection service in the foreground, with its notification. One UI Watch won't
     * let that notification be cleared and has no per-channel switch, so it can be turned off:
     * the service then runs as a plain background service, which Android allows because the app
     * is exempt from battery optimization, but which the system may kill more readily.
     */
    val statusNotification: StateFlow<Boolean> = _statusNotification.asStateFlow()

    fun setStatusNotification(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STATUS_NOTIFICATION, enabled).apply()
        _statusNotification.value = enabled
    }

    private val _sleepGuard = MutableStateFlow(prefs.getBoolean(KEY_SLEEP_GUARD, false))

    /** Quiet the watch automatically while asleep (see SleepGuard). */
    val sleepGuard: StateFlow<Boolean> = _sleepGuard.asStateFlow()

    val isSleepGuardEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLEEP_GUARD, false)

    fun setSleepGuard(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SLEEP_GUARD, enabled).apply()
        _sleepGuard.value = enabled
    }

    private val _disconnectAlert = MutableStateFlow(prefs.getBoolean(KEY_DISCONNECT_ALERT, true))

    /** Buzz when the iPhone has been out of reach for a while (like Samsung's alert for Android phones). */
    val disconnectAlert: StateFlow<Boolean> = _disconnectAlert.asStateFlow()

    fun setDisconnectAlert(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DISCONNECT_ALERT, enabled).apply()
        _disconnectAlert.value = enabled
    }

    fun setShowPhoneInfo(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_PHONE_INFO, enabled).apply()
        _showPhoneInfo.value = enabled
        ConnectionTileService.requestUpdate(appContext)
    }

    /**
     * Check if a given ANCS category is enabled for forwarding.
     * All categories are enabled by default.
     */
    fun isCategoryEnabled(categoryId: Byte): Boolean {
        return prefs.getBoolean(KEY_CATEGORY_PREFIX + categoryId, true)
    }

    fun setCategoryEnabled(categoryId: Byte, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CATEGORY_PREFIX + categoryId, enabled).apply()
    }

    /**
     * Get all category filter states as a map.
     */
    fun getAllCategoryFilters(): Map<Byte, Boolean> =
        CATEGORIES.associate { (id, _) -> id to isCategoryEnabled(id) }
}
