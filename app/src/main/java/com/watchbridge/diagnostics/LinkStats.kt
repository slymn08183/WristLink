package com.watchbridge.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * Measures how well the link to the iPhone actually works: how long it stays up, how often it
 * drops, how long reconnecting takes, and how late notifications arrive.
 *
 * Notification delay is the time between the iPhone posting a notification (ANCS sends that
 * timestamp) and the watch hearing about it. ANCS timestamps have one-second resolution and both
 * clocks are network-synced, so single samples are only good to about a second; the median over
 * many is what to look at.
 *
 * Events are kept in a small CSV in app storage (`files/link_log.csv`, pullable with adb) so the
 * numbers survive restarts and can be looked at later.
 */
object LinkStats {

    private const val TAG = "LinkStats"
    private const val FILE_NAME = "link_log.csv"
    private const val MAX_LINES = 3000
    private const val WINDOW_MS = 24 * 60 * 60 * 1000L

    /** A delay this large means a clock is off or the event wasn't a fresh notification. */
    private const val MAX_PLAUSIBLE_DELAY_MS = 10 * 60 * 1000L

    private enum class Type(val code: String) {
        UP("up"), DOWN("down"), NOTIFICATION("notif"), UNAVAILABLE("unavail");

        companion object {
            fun of(code: String) = entries.firstOrNull { it.code == code }
        }
    }

    private data class Event(val timeMs: Long, val type: Type, val value: Long = 0, val flag: Boolean = false)

    data class Summary(
        /** Start of the period the numbers cover (the last 24 h, or since the first record). */
        val sinceMs: Long = 0,
        val connectedFraction: Float? = null,
        val drops: Int = 0,
        val medianReconnectMs: Long? = null,
        val longestReconnectMs: Long? = null,
        val notifications: Int = 0,
        val medianDelayMs: Long? = null,
        val p90DelayMs: Long? = null,
        val emptyContent: Int = 0,
        val unavailable: Int = 0
    )

    // One worker: file reads and writes happen in order, so loading at startup can't race a record
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val events = mutableListOf<Event>()
    private var file: File? = null
    private var linkUp = false

    private val _summary = MutableStateFlow(Summary())
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        scope.launch {
            val loaded = runCatching { f.readLines() }.getOrDefault(emptyList()).mapNotNull(::parse)
            synchronized(this@LinkStats) {
                events.addAll(0, loaded)
                // The process died while the link was up (update, crash, reboot), so the link
                // went down at some point: close that stretch rather than count the gap as connected
                val lastLink = loaded.lastOrNull { it.type == Type.UP || it.type == Type.DOWN }
                if (lastLink?.type == Type.UP) {
                    val down = Event(System.currentTimeMillis(), Type.DOWN)
                    events.add(loaded.size, down)
                    runCatching { f.appendText(format(down) + "\n") }
                }
                trimIfNeeded()
            }
            recompute()
        }
    }

    /** The link reached READY (ANCS subscribed). */
    fun onLinkUp() {
        synchronized(this) {
            if (linkUp) return
            linkUp = true
        }
        record(Event(System.currentTimeMillis(), Type.UP))
    }

    /** The link left READY. */
    fun onLinkDown() {
        synchronized(this) {
            if (!linkUp) return
            linkUp = false
        }
        record(Event(System.currentTimeMillis(), Type.DOWN))
    }

    /**
     * A new notification's content arrived.
     * @param postedAtMs when the iPhone posted it, from ANCS; null if it didn't say
     * @param announcedAtMs when the watch got the Notification Source event for it
     * @param emptyContent no message or subtitle came through (e.g. hidden by iPhone previews)
     */
    fun onNotification(postedAtMs: Long?, announcedAtMs: Long, emptyContent: Boolean) {
        val delay = postedAtMs?.let { announcedAtMs - it }
            ?.coerceAtLeast(0)
            ?.takeIf { it <= MAX_PLAUSIBLE_DELAY_MS }
            ?: -1
        record(Event(announcedAtMs, Type.NOTIFICATION, delay, emptyContent))
    }

    /** The iPhone never answered with a notification's content ("Open your phone to read it"). */
    fun onContentUnavailable() {
        record(Event(System.currentTimeMillis(), Type.UNAVAILABLE))
    }

    fun refresh() = scope.launch { recompute() }

    fun reset() {
        scope.launch {
            synchronized(this@LinkStats) {
                events.clear()
                // Keep the current link state as the starting point of the new period
                if (linkUp) events.add(Event(System.currentTimeMillis(), Type.UP))
                rewriteFile()
            }
            recompute()
        }
    }

    private fun record(event: Event) {
        scope.launch {
            synchronized(this@LinkStats) {
                events.add(event)
                runCatching { file?.appendText(format(event) + "\n") }
                    .onFailure { Log.w(TAG, "Couldn't write link log", it) }
                trimIfNeeded()
            }
            recompute()
        }
    }

    private fun trimIfNeeded() {
        if (events.size <= MAX_LINES) return
        repeat(events.size - MAX_LINES) { events.removeAt(0) }
        rewriteFile()
    }

    private fun rewriteFile() {
        runCatching { file?.writeText(events.joinToString("") { format(it) + "\n" }) }
            .onFailure { Log.w(TAG, "Couldn't rewrite link log", it) }
    }

    private fun recompute() {
        val now = System.currentTimeMillis()
        val all = synchronized(this) { events.sortedBy { it.timeMs } }
        if (all.isEmpty()) {
            _summary.value = Summary()
            return
        }
        val windowStart = maxOf(now - WINDOW_MS, all.first().timeMs)
        val inWindow = all.filter { it.timeMs >= windowStart }

        // Link state at the start of the window: whatever the last up/down before it said
        var up = all.lastOrNull { it.timeMs < windowStart && (it.type == Type.UP || it.type == Type.DOWN) }
            ?.type == Type.UP
        var upMs = 0L
        var lastChange = windowStart
        var downSince: Long? = null
        val reconnects = mutableListOf<Long>()
        var drops = 0
        for (e in inWindow) {
            when (e.type) {
                Type.UP -> {
                    if (!up) {
                        downSince?.let { reconnects += e.timeMs - it }
                        downSince = null
                        lastChange = e.timeMs
                        up = true
                    }
                }
                Type.DOWN -> {
                    if (up) {
                        upMs += e.timeMs - lastChange
                        lastChange = e.timeMs
                        downSince = e.timeMs
                        drops++
                        up = false
                    }
                }
                else -> Unit
            }
        }
        if (up) upMs += now - lastChange
        val span = now - windowStart

        val notifications = inWindow.filter { it.type == Type.NOTIFICATION }
        val delays = notifications.map { it.value }.filter { it >= 0 }.sorted()

        _summary.value = Summary(
            sinceMs = windowStart,
            connectedFraction = if (span > 0) (upMs.toFloat() / span).coerceIn(0f, 1f) else null,
            drops = drops,
            medianReconnectMs = reconnects.sorted().percentile(0.5),
            longestReconnectMs = reconnects.maxOrNull(),
            notifications = notifications.size,
            medianDelayMs = delays.percentile(0.5),
            p90DelayMs = delays.percentile(0.9),
            emptyContent = notifications.count { it.flag },
            unavailable = inWindow.count { it.type == Type.UNAVAILABLE }
        )
    }

    private fun List<Long>.percentile(p: Double): Long? =
        if (isEmpty()) null else this[((size - 1) * p).toInt()]

    private fun format(e: Event) = "${e.timeMs},${e.type.code},${e.value},${if (e.flag) 1 else 0}"

    private fun parse(line: String): Event? {
        val parts = line.split(',')
        if (parts.size < 4) return null
        return Event(
            timeMs = parts[0].toLongOrNull() ?: return null,
            type = Type.of(parts[1]) ?: return null,
            value = parts[2].toLongOrNull() ?: 0,
            flag = parts[3] == "1"
        )
    }
}
