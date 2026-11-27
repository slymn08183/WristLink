package com.wristlink.notification

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.wristlink.ancs.AncsActionHandler
import com.wristlink.ancs.AncsAttributeParser
import com.wristlink.ancs.AncsConstants
import com.wristlink.ancs.AncsNotificationEvent
import com.wristlink.ancs.AncsSessionManager
import com.wristlink.ble.BleConnectionManager
import com.wristlink.ble.PhoneActivityMonitor
import com.wristlink.diagnostics.LinkStats
import com.wristlink.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the full notification pipeline with filtering, DND respect,
 * and category-based settings.
 */
class AncsNotificationPipeline(
    private val context: Context,
    private val connectionManager: BleConnectionManager,
    private val sessionManager: AncsSessionManager,
    private val renderer: NotificationRenderer,
    private val callHandler: CallNotificationHandler,
    private val appNameResolver: AppNameResolver,
    private val appIconResolver: AppIconResolver,
    private val settings: SettingsManager,
    private val phoneActivity: PhoneActivityMonitor? = null
) {

    companion object {
        private const val TAG = "AncsNotifPipeline"
        private const val REQUEST_DELAY_MS = 100L

        /**
         * Enough to read a bundled or cached icon, never enough to wait for a download: a
         * first-time app's notification shows right away without its icon, and the download
         * finishes in the background for the next one.
         */
        private const val ICON_WAIT_MS = 300L

        /** Ask again if iOS hasn't answered an attribute request by then. */
        private const val ATTRIBUTE_TIMEOUT_MS = 4000L
        private const val MAX_ATTRIBUTE_RETRIES = 2

        /** After a new session, when to drop what the iPhone no longer lists (see onSessionStart). */
        private const val STALE_SWEEP_MIN_MS = 10_000L
        private const val STALE_SWEEP_MAX_MS = 60_000L
        private const val STALE_SWEEP_POLL_MS = 1_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Unlimited: a burst of notifications (e.g. pre-existing ones on connect) must never be
    // dropped, or they'd never be requested
    private val attributeRequestQueue = Channel<AncsNotificationEvent>(Channel.UNLIMITED)
    private val pendingEvents = ConcurrentHashMap<UInt, AncsNotificationEvent>()

    /** UIDs whose attributes haven't arrived yet → retries used so far. */
    private val awaitingAttributes = ConcurrentHashMap<UInt, Int>()

    /** New (not pre-existing) notifications → when the watch heard about them, for LinkStats. */
    private val announcedAt = ConcurrentHashMap<UInt, Long>()

    /** Arrived while the iPhone was in use: seen there already, so never shown here. */
    private val seenOnPhone = ConcurrentHashMap.newKeySet<UInt>()

    /**
     * Pre-existing notifications that won't be shown, fetched only to match them with ones the
     * watch still shows from an earlier session (NotificationRenderer.adoptStale).
     */
    private val matchOnly = ConcurrentHashMap.newKeySet<UInt>()

    @Volatile
    private var staleSweep: Job? = null

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun start() {
        scope.launch {
            sessionManager.events.collect { event ->
                handleEvent(event)
            }
        }

        scope.launch {
            for (event in attributeRequestQueue) {
                requestAttributes(event)
                delay(REQUEST_DELAY_MS)
            }
        }

        NotificationActionReceiver.onAction = { uid, actionId, session ->
            performAction(uid, actionId, session)
        }

        Log.i(TAG, "Notification pipeline started")
    }

    /** The service is going away. */
    fun stop() {
        if (NotificationActionReceiver.onAction != null) NotificationActionReceiver.onAction = null
        attributeRequestQueue.close()
        scope.cancel()
    }

    fun handleDataSourceResponse(result: Any?) {
        when (result) {
            is AncsAttributeParser.NotificationAttributes -> {
                onNotificationAttributesReceived(result)
            }
            is AncsAttributeParser.AppAttributes -> {
                onAppAttributesReceived(result)
            }
        }
    }

    private fun handleEvent(event: AncsNotificationEvent) {
        when {
            event.isSessionStart -> onSessionStart(event.session)
            event.isAdded || event.isModified -> {
                val heardAt = System.currentTimeMillis()

                // Like an Apple Watch: what pops up on the iPhone in your hand stays there.
                // Its later updates stay away too, or they'd bring it back. Calls always ring.
                val uid = event.notificationUid
                if (uid in seenOnPhone) return
                if (event.isAdded && !event.isPreExisting &&
                    !callHandler.isCallCategory(event.categoryId) &&
                    phoneActivity?.isPhoneInUse == true
                ) {
                    seenOnPhone += uid
                    Log.d(TAG, "iPhone in use, skipped uid=$uid cat=${event.categoryName}")
                    return
                }

                // Apply filters
                if (!shouldShowNotification(event)) {
                    if (event.isAdded && event.isPreExisting && renderer.hasStale()) {
                        // Not shown, but maybe still on the watch from before: find out which
                        matchOnly += uid
                        pendingEvents[uid] = event
                        awaitingAttributes[uid] = 0
                        attributeRequestQueue.trySend(event)
                        return
                    }
                    Log.d(TAG, "Filtered out: uid=${event.notificationUid} cat=${event.categoryName}")
                    return
                }
                matchOnly.remove(uid)

                // Only fresh notifications say anything about delay; pre-existing ones are old
                // and a modified one keeps its original timestamp
                if (event.isAdded && !event.isPreExisting) {
                    announcedAt[event.notificationUid] = heardAt
                }

                // Nothing is shown until the content arrives (usually well under a second):
                // a "Loading..." placeholder could get stuck if the content never came.
                pendingEvents[event.notificationUid] = event
                awaitingAttributes[event.notificationUid] = 0
                attributeRequestQueue.trySend(event)
            }
            event.isRemoved -> {
                matchOnly.remove(event.notificationUid)
                if (seenOnPhone.remove(event.notificationUid)) return
                pendingEvents.remove(event.notificationUid)
                awaitingAttributes.remove(event.notificationUid)
                announcedAt.remove(event.notificationUid)
                renderer.cancelNotification(event.notificationUid)

                when (event.categoryId) {
                    AncsConstants.CATEGORY_INCOMING_CALL -> callHandler.cancelIncomingCall()
                    AncsConstants.CATEGORY_ACTIVE_CALL -> callHandler.cancelActiveCall()
                }
            }
        }
    }

    /**
     * Check if a notification should be shown based on current settings.
     */
    private fun shouldShowNotification(event: AncsNotificationEvent): Boolean {
        // Respect DND / Theater mode
        if (settings.respectDnd.value && isDndActive()) {
            // Always let calls through in DND
            if (event.categoryId != AncsConstants.CATEGORY_INCOMING_CALL) {
                return false
            }
        }

        // Filter silent notifications
        if (event.isSilent && !settings.showSilent.value) {
            return false
        }

        // Filter pre-existing notifications
        if (event.isPreExisting && !settings.showPreExisting.value) {
            return false
        }

        // Check category filter
        if (!settings.isCategoryEnabled(event.categoryId)) {
            return false
        }

        return true
    }

    /** Any DND mode, including Samsung's (priority only) and the one the iPhone app turns on. */
    private fun isDndActive(): Boolean {
        val filter = notificationManager.currentInterruptionFilter
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun requestAttributes(event: AncsNotificationEvent) {
        val command = AncsActionHandler.buildGetNotificationAttributes(
            notificationUid = event.notificationUid
        )
        connectionManager.writeControlPoint(command)
        Log.d(TAG, "Requested attributes for uid=${event.notificationUid}")

        // A lost or garbled response would mean the notification never shows up: ask again
        scope.launch {
            delay(ATTRIBUTE_TIMEOUT_MS)
            val uid = event.notificationUid
            // Removed, superseded by a newer event, or already rendered
            if (pendingEvents[uid] !== event) return@launch
            val retries = awaitingAttributes[uid] ?: return@launch

            if (retries < MAX_ATTRIBUTE_RETRIES) {
                Log.w(TAG, "No attributes for uid=$uid after ${ATTRIBUTE_TIMEOUT_MS}ms, retrying")
                awaitingAttributes[uid] = retries + 1
                attributeRequestQueue.trySend(event)
            } else if (matchOnly.remove(uid)) {
                awaitingAttributes.remove(uid)
                pendingEvents.remove(uid, event)
            } else {
                Log.e(TAG, "Giving up on attributes for uid=$uid")
                awaitingAttributes.remove(uid)
                announcedAt.remove(uid)
                LinkStats.onContentUnavailable()
                if (!pendingEvents.remove(uid, event)) return@launch
                when (event.categoryId) {
                    AncsConstants.CATEGORY_INCOMING_CALL -> callHandler.showIncomingCall(event, null)
                    AncsConstants.CATEGORY_ACTIVE_CALL -> callHandler.showActiveCall(event, null)
                    else -> renderer.showUnavailableNotification(event)
                }
            }
        }
    }

    private fun onNotificationAttributesReceived(attrs: AncsAttributeParser.NotificationAttributes) {
        awaitingAttributes.remove(attrs.notificationUid)
        announcedAt.remove(attrs.notificationUid)?.let { announced ->
            LinkStats.onNotification(
                postedAtMs = attrs.timestampMillis,
                announcedAtMs = announced,
                emptyContent = attrs.message.isNullOrBlank() && attrs.subtitle.isNullOrBlank()
            )
        }
        val event = pendingEvents[attrs.notificationUid]
        if (event == null) {
            Log.w(TAG, "Received attributes for unknown uid=${attrs.notificationUid}")
            return
        }
        if (matchOnly.remove(attrs.notificationUid)) {
            if (pendingEvents.remove(attrs.notificationUid, event)) renderer.adoptStale(event, attrs)
            return
        }

        val appId = attrs.appIdentifier
        val appDisplayName = if (appId != null) {
            if (appNameResolver.needsRequest(appId)) {
                appNameResolver.markPending(appId)
                val cmd = AncsActionHandler.buildGetAppAttributes(appId)
                connectionManager.writeControlPoint(cmd)
            }
            appNameResolver.getDisplayName(appId)
        } else null

        if (callHandler.isCallCategory(event.categoryId)) {
            when (event.categoryId) {
                AncsConstants.CATEGORY_INCOMING_CALL ->
                    callHandler.showIncomingCall(event, attrs)
                AncsConstants.CATEGORY_ACTIVE_CALL ->
                    callHandler.showActiveCall(event, attrs)
            }
            pendingEvents.remove(attrs.notificationUid)
            Log.d(TAG, "Rendered call uid=${attrs.notificationUid}: ${attrs.title}")
            return
        }

        scope.launch {
            val appIcon = appId?.let { resolveAppIcon(it) }

            // iOS may have removed or modified the notification while we waited for the icon
            if (!pendingEvents.remove(attrs.notificationUid, event)) return@launch

            renderer.showFullNotification(event, attrs, appDisplayName, appIcon)
            Log.d(TAG, "Rendered notification uid=${attrs.notificationUid}: ${attrs.title}")
        }
    }

    /**
     * Cached icons come back immediately. For an app we haven't seen before the notification
     * doesn't wait: it shows without an icon and the download keeps going in the background
     * for next time.
     */
    private suspend fun resolveAppIcon(appId: String): Bitmap? =
        withTimeoutOrNull(ICON_WAIT_MS) { appIconResolver.getIcon(appId) }

    private fun onAppAttributesReceived(attrs: AncsAttributeParser.AppAttributes) {
        val displayName = attrs.displayName
        if (displayName != null) {
            appNameResolver.cacheAppName(attrs.appIdentifier, displayName)
            Log.d(TAG, "Resolved app: ${attrs.appIdentifier} -> $displayName")
        }
    }

    private fun performAction(notificationUid: UInt, actionId: Byte, session: Int?) {
        // A card from an earlier session: iOS may have given its UID to another notification
        if (session != null && session != renderer.session) {
            Log.i(TAG, "Ignored action $actionId on uid=$notificationUid from an earlier session")
            return
        }
        val command = AncsActionHandler.buildPerformAction(notificationUid, actionId)
        connectionManager.writeControlPoint(command)
        Log.i(TAG, "Performed action $actionId on uid=$notificationUid")
    }

    /**
     * ANCS was subscribed again (AncsSessionManager.startNewSession): UIDs start over. What is
     * still waiting belongs to the old numbering. Notifications on screen stay; the iPhone lists
     * what it still has as pre-existing, those are matched back, and once that list is through
     * the rest is removed (cleared on the iPhone meanwhile).
     */
    private fun onSessionStart(session: Int) {
        pendingEvents.clear()
        awaitingAttributes.clear()
        announcedAt.clear()
        seenOnPhone.clear()
        matchOnly.clear()
        staleSweep?.cancel()
        val stale = renderer.startNewSession(session)
        Log.i(TAG, "ANCS session $session${if (stale) ", matching notifications kept from before" else ""}")
        if (!stale) return
        staleSweep = scope.launch {
            // Pre-existing ones come in a burst right after subscribing; then wait for their content
            delay(STALE_SWEEP_MIN_MS)
            var waited = STALE_SWEEP_MIN_MS
            while (awaitingAttributes.isNotEmpty() && waited < STALE_SWEEP_MAX_MS) {
                delay(STALE_SWEEP_POLL_MS)
                waited += STALE_SWEEP_POLL_MS
            }
            // Only over a link that stayed up: a drop means the list may be incomplete
            if (connectionManager.isReady) renderer.sweepStale(session)
        }
    }

    /**
     * Notifications stay on the watch until the user clears them (here or on the iPhone),
     * even while the iPhone is out of range. Only call screens go: their state is unknown now.
     */
    fun onDisconnected() {
        // The iPhone's list may not have come through; matching goes on in the next session
        staleSweep?.cancel()
        pendingEvents.clear()
        awaitingAttributes.clear()
        announcedAt.clear()
        // UIDs start over with the next session
        seenOnPhone.clear()
        matchOnly.clear()
        callHandler.cancelIncomingCall()
        callHandler.cancelActiveCall()
    }
}
