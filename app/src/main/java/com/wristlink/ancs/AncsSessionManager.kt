package com.wristlink.ancs

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages ANCS session state: tracks active notification UIDs,
 * handles session lifecycle, and coordinates event processing.
 *
 * Events and Data Source fragments arrive on the Bluetooth library's callback thread, while
 * resets come from the state machine's coroutines, so the UID set and the parser's buffer are
 * only touched under [lock].
 */
class AncsSessionManager {

    enum class SessionState {
        DISCONNECTED,
        DISCOVERING,
        SUBSCRIBING,
        ACTIVE,
        RECONNECTING
    }

    private val lock = Any()

    private val _sessionState = MutableStateFlow(SessionState.DISCONNECTED)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // A bounded buffer drops events in a burst (tryEmit fails once it's full), and e.g. a
    // lost "Removed" leaves a stale notification on the watch
    private val _events = MutableSharedFlow<AncsNotificationEvent>(extraBufferCapacity = Int.MAX_VALUE)
    val events: SharedFlow<AncsNotificationEvent> = _events.asSharedFlow()

    private val _eventLog = MutableStateFlow<List<String>>(emptyList())
    val eventLog: StateFlow<List<String>> = _eventLog.asStateFlow()

    /** Set of currently known notification UIDs. */
    private val activeUids = mutableSetOf<UInt>()

    /** Parser for Data Source fragments. */
    private val attributeParser = AncsAttributeParser()

    fun updateState(state: SessionState) {
        _sessionState.value = state
    }

    /**
     * Process an incoming 8-byte Notification Source event.
     */
    fun processNotificationEvent(data: ByteArray) {
        val event = AncsNotificationEvent.parse(data) ?: return

        synchronized(lock) {
            when {
                event.isAdded || event.isModified -> activeUids.add(event.notificationUid)
                event.isRemoved -> activeUids.remove(event.notificationUid)
            }
            // Under the lock too, so events keep their order against a concurrent reset
            _events.tryEmit(event)
            appendLog(event.toString())
        }
    }

    /**
     * Seconds since the epoch at first, so numbers from before a restart (still on notifications
     * the watch shows) are never handed out again; one up per ANCS subscription.
     */
    private var session = (System.currentTimeMillis() / 1000).toInt()

    /**
     * ANCS is about to be subscribed (again): iOS numbers notifications anew per session, so
     * UIDs seen so far mean nothing from here on. Called before the subscription, so the marker
     * comes before every event of the new session in [events].
     */
    fun startNewSession() {
        synchronized(lock) {
            session++
            activeUids.clear()
            attributeParser.reset()
            _events.tryEmit(AncsNotificationEvent.sessionStart(session))
        }
    }

    /**
     * Process a Data Source fragment. Returns parsed result if complete.
     */
    fun processDataSourceFragment(data: ByteArray): Any? =
        synchronized(lock) { attributeParser.feedFragment(data) }

    /**
     * Clear session state on disconnect. UIDs become invalid after disconnect.
     */
    fun onDisconnected() {
        synchronized(lock) {
            activeUids.clear()
            attributeParser.reset()
        }
        _sessionState.value = SessionState.DISCONNECTED
    }

    /**
     * Reset for a fresh session (e.g., after long disconnect).
     */
    fun resetSession() {
        synchronized(lock) {
            activeUids.clear()
            attributeParser.reset()
            _eventLog.value = emptyList()
        }
    }

    fun isUidActive(uid: UInt): Boolean = synchronized(lock) { uid in activeUids }

    fun getActiveUidCount(): Int = synchronized(lock) { activeUids.size }

    private fun appendLog(message: String) {
        val current = _eventLog.value
        // Keep last 50 entries
        _eventLog.value = (current + message).takeLast(50)
    }
}
