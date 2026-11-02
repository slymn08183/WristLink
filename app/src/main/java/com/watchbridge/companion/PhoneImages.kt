package com.watchbridge.companion

import android.util.Log
import com.watchbridge.service.WatchBridgeService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Images the watch gets from the internet through the iPhone app instead of its own Wi-Fi:
 * app icons for notifications and album art for the music player.
 *
 * The watch sends `{"op":"fetch","kind":"icon"|"art","key":…}` (plus details for the search) on
 * the companion link, which also wakes the iPhone app in the background, and the app answers with
 * `{"op":"image","kind":…,"key":…,"data":"<base64 JPEG>"}`, or `"missing":true` when there is no
 * such image. While a transfer runs the Bluetooth link switches to its fast interval.
 */
object PhoneImages {

    private const val TAG = "PhoneImages"
    /** Back to the low-power interval this long after the last image arrived. */
    private const val RELAX_DELAY_MS = 3_000L

    /** What came back for a request. */
    sealed interface Result {
        class Image(val bytes: ByteArray) : Result
        /** The iPhone looked and there is no such image. */
        data object Missing : Result
    }

    /**
     * Sends a request to the iPhone app; set by CompanionGattServer while an app that fetches
     * images is connected. Returns false when it couldn't be sent.
     */
    @Volatile var requester: ((JSONObject) -> Boolean)? = null

    val available: Boolean get() = requester != null

    private val pending = mutableMapOf<String, CompletableDeferred<Result?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var relaxJob: Job? = null

    /**
     * Ask the iPhone for an image and wait up to [timeoutMs] for it. Null when no iPhone app is
     * connected or it didn't answer in time. Concurrent requests for the same image share one fetch.
     */
    suspend fun fetch(kind: String, key: String, timeoutMs: Long, details: Map<String, String> = emptyMap()): Result? {
        val send = requester ?: return null
        val id = "$kind:$key"
        var isNew = false
        val deferred = synchronized(pending) {
            pending.getOrPut(id) { isNew = true; CompletableDeferred() }
        }
        if (isNew) {
            val request = JSONObject().put("op", "fetch").put("kind", kind).put("key", key)
            details.forEach { (name, value) -> if (value.isNotEmpty()) request.put(name, value) }
            WatchBridgeService.connectionManager?.speedUpForTransfer()
            if (!send(request)) {
                synchronized(pending) { pending.remove(id) }
                scheduleRelax()
                return null
            }
            Log.i(TAG, "Asked the iPhone for $id")
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) {
            synchronized(pending) { if (pending[id] === deferred) pending.remove(id) }
            Log.w(TAG, "No answer from the iPhone for $id")
            scheduleRelax()
        }
        return result
    }

    /** The iPhone app answered (CompanionGattServer). */
    fun onImage(kind: String, key: String, result: Result) {
        val deferred = synchronized(pending) { pending.remove("$kind:$key") }
        Log.i(TAG, "Got $kind:$key " + if (result is Result.Image) "(${result.bytes.size} bytes)" else "(none)")
        deferred?.complete(result)
        scheduleRelax()
    }

    /** The iPhone app went away: everything still waiting gets nothing. */
    fun onDisconnected() {
        requester = null
        val waiting = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        waiting.forEach { it.complete(null) }
        scheduleRelax()
    }

    private fun scheduleRelax() {
        relaxJob?.cancel()
        relaxJob = scope.launch {
            delay(RELAX_DELAY_MS)
            val idle = synchronized(pending) { pending.isEmpty() }
            if (idle) WatchBridgeService.connectionManager?.relaxAfterTransfer()
        }
    }
}
