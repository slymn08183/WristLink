package com.wristlink.companion

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.wristlink.ble.BleConnectionManager
import com.wristlink.diagnostics.LinkStats
import com.wristlink.health.HealthCollector
import com.wristlink.health.HealthHistory
import com.wristlink.health.HealthMetrics
import com.wristlink.health.HealthProfile
import com.wristlink.health.LiveHealth
import com.wristlink.health.HealthStore
import com.wristlink.service.WristLinkService
import com.wristlink.settings.SettingsManager
import com.wristlink.weather.WeatherStore
import com.wristlink.workout.WorkoutSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The watch's side of the link to the WristLink iPhone app.
 *
 * The iPhone is already connected to the watch (for notifications), so the iPhone app doesn't
 * scan or wait for advertising: it asks iOS for connected peripherals offering [SERVICE_UUID]
 * and talks to this GATT server over that same link.
 *
 * - [INFO_UUID] (read, notify): JSON with battery, link stats and how many health samples wait.
 *   A notification has to fit one packet, so when the full JSON doesn't, a compact one goes out
 *   (`"compact":true`, only what changes), or just `{"changed":true}` and the iPhone reads it.
 * - [SYNC_UUID] (write, notify): newline-terminated JSON messages. The iPhone writes
 *   `{"op":"pull","after":ID,"max":N}` and gets `{"op":"batch","items":[...],"more":B}` back,
 *   split into notifications that fit the MTU; once the samples are in Apple Health it writes
 *   `{"op":"ack","upTo":ID}` and the watch drops them. Settings go both ways (`getSettings`,
 *   `setSetting`, and `settings` pushed whenever they change on the watch), and the iPhone sends
 *   the forecast (`weather`, see WeatherStore). Writes from the iPhone are newline-framed too and
 *   may arrive in several pieces, with or without response. After anything that may have cut a
 *   message short (a dropped queue, a disconnect, a new subscription, a restart) the next message
 *   starts with an extra newline, which ends whatever partial line the iPhone still holds.
 * - The iPhone app says what it can do with `{"op":"hello","features":[…]}`. One that has
 *   "images" fetches app icons and album art for the watch (PhoneImages).
 *
 * To keep the iPhone (and both batteries) mostly asleep, the info notification announcing new
 * samples goes out at most every [PENDING_NOTIFY_INTERVAL_MS], or sooner when many are waiting,
 * and changes close together go out as one.
 */
@SuppressLint("MissingPermission")
class CompanionGattServer(private val context: Context) {

    companion object {
        private const val TAG = "CompanionGatt"
        const val PROTOCOL_VERSION = 6

        val SERVICE_UUID: UUID = UUID.fromString("57a1e1c0-0001-4b8e-9a1f-6f4b10000000")
        val INFO_UUID: UUID = UUID.fromString("57a1e1c0-0002-4b8e-9a1f-6f4b10000000")
        val SYNC_UUID: UUID = UUID.fromString("57a1e1c0-0003-4b8e-9a1f-6f4b10000000")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEFAULT_MTU = 23
        private const val BUSY_RETRY_MS = 30L
        private const val MAX_BUSY_RETRIES = 50
        /** onNotificationSent with an error: the piece didn't go out; try it again after this. */
        private const val FAILED_RETRY_MS = 50L
        /** No onNotificationSent this long after a piece: the stack lost it (or the link is gone). */
        private const val SENT_WATCHDOG_MS = 2_500L
        /** Lost callbacks in a row before the GATT server is opened afresh. */
        private const val MAX_STALLS = 3
        /** GATT_CONNECTION_CONGESTED: the piece was sent, the stack is just full. */
        private const val GATT_CONGESTED = 0x8f
        private const val MAX_ATTRIBUTE_LENGTH = 512
        private const val MAX_BATCH = 100
        private const val PENDING_NOTIFY_INTERVAL_MS = 15 * 60 * 1000L
        private const val PENDING_NOTIFY_THRESHOLD = 300
        /** Info changes this close together (an ack, a battery tick, the weather) go out as one. */
        private const val INFO_NOTIFY_DELAY_MS = 1_500L
        /** Several changes in a row (a slider, quiet mode flipping DND and wake) go out as one. */
        private const val SETTINGS_PUSH_DELAY_MS = 400L
        /** Messages from this size (a workout with its track, the summary) get the fast interval. */
        private const val BIG_MESSAGE_BYTES = 4 * 1024
        /** Back to the low-power interval this long after the outbox emptied. */
        private const val RELAX_DELAY_MS = 2_000L
        /** Album art arrives as about 20 KB of base64. */
        private const val MAX_INBOX_BYTES = 96 * 1024
        private const val NEWLINE = '\n'.code.toByte()
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val store = HealthStore.get(context)

    @Volatile
    private var server: BluetoothGattServer? = null
    @Volatile
    private var running = false

    // GATT callbacks arrive on binder threads; everything stateful runs on this one thread
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    private val infoCharacteristic = notifiable(
        INFO_UUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
    )
    private val syncCharacteristic = notifiable(
        SYNC_UUID,
        // Without response too: images come as many writes, and iOS can send several per interval
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
    )

    private val subscribers = mutableMapOf<UUID, MutableSet<BluetoothDevice>>()
    private val mtus = mutableMapOf<BluetoothDevice, Int>()
    /** iPhone apps that said they fetch images for the watch. */
    private val imageClients = mutableSetOf<BluetoothDevice>()
    /** Bytes written by each iPhone that don't end in a newline yet. */
    private val inboxes = mutableMapOf<BluetoothDevice, Inbox>()
    /** iPhone apps that frame their writes with newlines (said hello, or sent one). */
    private val framedClients = mutableSetOf<BluetoothDevice>()

    /** One message on its way out, sent piece by piece from [offset]. */
    private class Outgoing(
        val device: BluetoothDevice,
        val characteristic: BluetoothGattCharacteristic,
        var bytes: ByteArray,
        /** Named in the log once the last piece is out (big messages only). */
        val label: String?
    ) {
        var offset = 0
    }

    /** The piece of the outbox's first message the stack has, waiting for onNotificationSent. */
    private class InFlight(val message: Outgoing, val length: Int) {
        val sentAt = SystemClock.elapsedRealtime()
    }

    /** Notifications go out one at a time: the next waits for onNotificationSent. */
    private val outbox = ArrayDeque<Outgoing>()
    private var inFlight: InFlight? = null
    private var sentWatchdog: Job? = null
    private var retryJob: Job? = null
    /** Times in a row the stack refused (or failed) the piece at the head of the outbox. */
    private var busyRetries = 0
    /** onNotificationSent callbacks lost in a row. */
    private var stalls = 0
    /**
     * Devices whose sync stream is known to sit between two messages. Any other gets a newline
     * first, ending a partial message left over on the iPhone.
     */
    private val cleanDevices = mutableSetOf<BluetoothDevice>()

    private var spedUp = false
    private var relaxJob: Job? = null

    private var lastBattery = -1
    private var lastPlugged: Boolean? = null
    private var lastPendingNotifyMs = 0L
    private var infoNotify: Job? = null
    /** The info each device was last notified, so a notification that changes nothing stays off the air. */
    private val lastInfoSent = mutableMapOf<BluetoothDevice, ByteArray>()
    /** The info JSON as of each device's read at offset 0; long reads take the rest from it. */
    private val infoReadCache = ConcurrentHashMap<BluetoothDevice, ByteArray>()

    private var settingsWatcher: Job? = null
    private var profileWatcher: Job? = null
    private var settingsPush: Job? = null
    /** What was last sent, so a push that changes nothing stays off the air. */
    private var lastSettingsSent: String? = null

    /** Do Not Disturb changed on the watch itself (quick panel, schedules, bedtime mode). */
    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = onSettingsChanged()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (level != lastBattery || plugged != lastPlugged) {
                lastBattery = level
                lastPlugged = plugged
                notifyInfo()
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                infoReadCache.remove(device)
                scope.launch {
                    subscribers.values.forEach { it.remove(device) }
                    mtus.remove(device)
                    inboxes.remove(device)
                    framedClients.remove(device)
                    imageClients.remove(device)
                    lastInfoSent.remove(device)
                    updateImageRequester()
                    dropMessages(device)
                    sendNext()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            scope.launch { mtus[device] = mtu }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != INFO_UUID) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                return
            }
            // A long read comes in pieces with growing offsets: every piece from the same JSON,
            // or the iPhone would put together halves of two different ones
            val value = if (offset == 0) {
                infoBytes(infoJson()).also { infoReadCache[device] = it }
            } else {
                infoReadCache[device] ?: infoBytes(infoJson()).also { infoReadCache[device] = it }
            }
            val chunk = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val ok = characteristic.uuid == SYNC_UUID && value != null
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                    offset, null
                )
            }
            if (ok) scope.launch { receive(device, value!!) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enabled = value != null && value.isNotEmpty() && value[0].toInt() != 0
                val uuid = descriptor.characteristic.uuid
                scope.launch {
                    val set = subscribers.getOrPut(uuid) { mutableSetOf() }
                    if (enabled) set.add(device) else set.remove(device)
                    when (uuid) {
                        SYNC_UUID -> {
                            // Whatever was half sent is lost to the iPhone, or will be
                            cleanDevices.remove(device)
                            if (!enabled) dropMessages(device, syncCharacteristic)
                            updateImageRequester()
                        }
                        INFO_UUID -> {
                            lastInfoSent.remove(device)
                            if (!enabled) dropMessages(device, infoCharacteristic)
                        }
                    }
                    sendNext()
                }
                Log.i(TAG, "${device.address} ${if (enabled) "subscribed to" else "unsubscribed from"} $uuid")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            scope.launch { onSent(device, status) }
        }
    }

    fun start() {
        if (running) return
        if (!openServer()) return
        running = true
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ContextCompat.registerReceiver(
            context, dndReceiver, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        settingsWatcher = scope.launch { SettingsManager.get(context).changes.collect { onSettingsChanged() } }
        profileWatcher = scope.launch { HealthProfile.get(context).state.collect { onSettingsChanged() } }
        Log.i(TAG, "Companion service up (protocol $PROTOCOL_VERSION)")
    }

    private fun openServer(): Boolean {
        val s = bluetoothManager?.openGattServer(context, callback) ?: run {
            Log.w(TAG, "Couldn't open GATT server")
            return false
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(infoCharacteristic)
        service.addCharacteristic(syncCharacteristic)
        s.addService(service)
        server = s
        return true
    }

    /** The service is going away: nothing of this server may keep running or waiting. */
    fun stop() {
        if (!running) return
        running = false
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { context.unregisterReceiver(dndReceiver) }
        val s = server
        server = null
        runCatching { s?.close() }
        scope.cancel()
        infoReadCache.clear()
        // Image fetches waiting for an answer get nothing now instead of after their timeout
        if (PhoneImages.requester != null) PhoneImages.onDisconnected()
        executor.execute {
            outbox.clear()
            inFlight = null
            subscribers.clear()
            mtus.clear()
            inboxes.clear()
            framedClients.clear()
            imageClients.clear()
            cleanDevices.clear()
            lastInfoSent.clear()
            if (spedUp) {
                spedUp = false
                WristLinkService.connectionManager?.relaxAfterTransfer(BleConnectionManager.OWNER_COMPANION)
            }
        }
        executor.shutdown()
    }

    /** Push fresh info to every subscribed iPhone app, once for changes close together. */
    fun notifyInfo() {
        scope.launch {
            if (infoNotify?.isActive == true) return@launch
            infoNotify = scope.launch {
                delay(INFO_NOTIFY_DELAY_MS)
                pushInfo()
            }
        }
    }

    private fun pushInfo() {
        val devices = subscribers[INFO_UUID].orEmpty()
        if (devices.isEmpty()) return
        val info = infoJson()
        for (device in devices) {
            val bytes = notifiedInfo(info, chunkSize(device))
            if (lastInfoSent[device]?.contentEquals(bytes) == true) continue
            lastInfoSent[device] = bytes
            enqueue(Outgoing(device, infoCharacteristic, bytes, label = null))
        }
    }

    /** New health samples were stored; tell the iPhone, but not too often. */
    fun onHealthDataAdded() {
        scope.launch {
            val now = System.currentTimeMillis()
            if (now - lastPendingNotifyMs >= PENDING_NOTIFY_INTERVAL_MS ||
                store.pendingCount() >= PENDING_NOTIFY_THRESHOLD
            ) {
                lastPendingNotifyMs = now
                notifyInfo()
            }
        }
    }

    /** A setting changed on the watch (or quiet mode flipped some): tell the iPhone app. */
    fun onSettingsChanged() {
        scope.launch {
            settingsPush?.cancel()
            settingsPush = scope.launch {
                delay(SETTINGS_PUSH_DELAY_MS)
                val devices = subscribers[SYNC_UUID].orEmpty()
                if (devices.isEmpty()) return@launch
                val values = RemoteSettings.snapshot(context)
                if (values.toString() == lastSettingsSent) return@launch
                devices.forEach { sendSettings(it, error = null, values = values) }
            }
        }
    }

    /** Point PhoneImages at an iPhone app that can fetch images, if one is listening. */
    private fun updateImageRequester() {
        val device = imageClients.firstOrNull { it in subscribers[SYNC_UUID].orEmpty() }
        if (device == null) {
            if (PhoneImages.requester != null) PhoneImages.onDisconnected()
            return
        }
        PhoneImages.requester = { request ->
            if (running) {
                scope.launch { sendMessage(device, request) }
                true
            } else {
                false
            }
        }
    }

    /** Bytes of one iPhone's writes that don't end in a newline yet; grows as needed. */
    private class Inbox {
        var buffer = ByteArray(256)
        var length = 0

        fun append(source: ByteArray, from: Int, to: Int) {
            val count = to - from
            if (length + count > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, length + count))
            }
            System.arraycopy(source, from, buffer, length, count)
            length += count
        }

        fun text(): String = String(buffer, 0, length, Charsets.UTF_8)

        fun clear() {
            length = 0
            // Don't hold on to an image's worth of memory between images
            if (buffer.size > 4096) buffer = ByteArray(256)
        }
    }

    /** Collect a write and handle every complete line in it. Only the new bytes are searched. */
    private fun receive(device: BluetoothDevice, value: ByteArray) {
        val inbox = inboxes.getOrPut(device) { Inbox() }
        var start = 0
        for (i in value.indices) {
            if (value[i] != NEWLINE) continue
            framedClients += device
            val line = if (inbox.length == 0) {
                String(value, start, i - start, Charsets.UTF_8)
            } else {
                inbox.append(value, start, i)
                inbox.text().also { inbox.clear() }
            }
            start = i + 1
            if (line.isNotBlank()) handleSyncRequest(device, line)
        }
        if (start < value.size) inbox.append(value, start, value.size)

        // iPhone apps before protocol 4 send each request as one write with no newline. Only
        // for apps that never framed anything, and only once it could be a whole object.
        if (inbox.length > 0 && device !in framedClients &&
            inbox.buffer[0] == '{'.code.toByte() && inbox.buffer[inbox.length - 1] == '}'.code.toByte()
        ) {
            val text = inbox.text()
            if (runCatching { JSONObject(text) }.isSuccess) {
                inbox.clear()
                handleSyncRequest(device, text)
            }
        }
        if (inbox.length > MAX_INBOX_BYTES) {
            Log.w(TAG, "Dropping ${inbox.length} unframed bytes from ${device.address}")
            inbox.clear()
        }
    }

    private fun handleSyncRequest(device: BluetoothDevice, text: String) {
        val request = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return
        when (request.optString("op")) {
            "pull" -> {
                val after = request.optLong("after", 0)
                val max = request.optInt("max", MAX_BATCH).coerceIn(1, MAX_BATCH)
                val batch = store.batch(after, max)
                val more = batch.size == max && store.batch(batch.last().id, 1).isNotEmpty()
                val reply = JSONObject()
                    .put("op", "batch")
                    .put("items", JSONArray(batch.map { it.toJson() }))
                    .put("more", more)
                sendMessage(device, reply, label = "batch of ${batch.size} samples after $after")
                Log.i(TAG, "Queued ${batch.size} samples after $after (more=$more)")
            }
            "ack" -> {
                store.acknowledge(request.optLong("upTo"))
                lastPendingNotifyMs = System.currentTimeMillis()
                notifyInfo()
            }
            "getSettings" -> sendSettings(device, error = null)
            "setSetting" -> {
                val key = request.optString("key")
                val value = request.opt("value")
                val error = RemoteSettings.apply(context, key, value)
                Log.i(TAG, "setSetting $key=$value ${error ?: "ok"}")
                sendSettings(device, error)
            }
            "nextWorkout" -> {
                val workout = HealthHistory.get(context).nextUnsyncedWorkout()
                val message = workout?.let { WorkoutSync.message(context, it) } ?: WorkoutSync.none()
                val text = message.toString()
                val label = "workout ${workout?.id ?: "none"} (${text.length} bytes)"
                sendMessage(device, text, label)
                Log.i(TAG, "Queued $label")
                // The last one sent, readable over adb, for when the iPhone can't read it
                if (workout != null) runCatching {
                    context.getExternalFilesDir(null)?.resolve("last-workout.json")?.writeText(text)
                }
            }
            "workoutAck" -> {
                HealthHistory.get(context).markWorkoutSynced(request.optLong("id"))
                notifyInfo()
            }
            "summary" -> {
                // Up to everything the watch keeps, so a new iPhone app can fill its history once
                val days = request.optInt("days", 7).coerceIn(1, HealthHistory.MAX_AGE_DAYS)
                val summary = runCatching { HealthMetrics.summaryJson(context, days) }
                    .onFailure { Log.e(TAG, "Summary failed", it) }
                    .getOrElse { JSONObject().put("op", "summary").put("error", it.toString()) }
                val text = summary.toString()
                val label = "summary of $days days (${text.length} bytes)"
                sendMessage(device, text, label)
                Log.i(TAG, "Queued $label")
            }
            "weather" -> {
                WeatherStore.save(context, request)
                notifyInfo()
            }
            "hello" -> {
                val features = request.optJSONArray("features")
                val images = features != null && (0 until features.length()).any { features.optString(it) == "images" }
                if (images) imageClients.add(device) else imageClients.remove(device)
                framedClients += device
                Log.i(TAG, "${device.address} says hello, features=$features")
                updateImageRequester()
            }
            "image" -> {
                val kind = request.optString("kind")
                val key = request.optString("key")
                val data = request.optString("data")
                val result = if (request.optBoolean("missing") || data.isEmpty()) {
                    PhoneImages.Result.Missing
                } else {
                    runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
                        ?.let { PhoneImages.Result.Image(it) } ?: PhoneImages.Result.Missing
                }
                PhoneImages.onImage(kind, key, result)
            }
        }
    }

    private fun sendSettings(
        device: BluetoothDevice,
        error: String?,
        values: JSONObject = RemoteSettings.snapshot(context)
    ) {
        lastSettingsSent = values.toString()
        val reply = JSONObject().put("op", "settings").put("values", values)
        if (error != null) reply.put("error", error)
        sendMessage(device, reply)
    }

    private fun sendMessage(device: BluetoothDevice, message: JSONObject, label: String? = null) =
        sendMessage(device, message.toString(), label)

    /**
     * One JSON message on the sync characteristic, newline-terminated, and newline-led too when
     * the iPhone may hold part of an earlier one. Split into pieces when it is sent.
     */
    private fun sendMessage(device: BluetoothDevice, json: String, label: String?) {
        if (!running) return
        val lead = if (device in cleanDevices) "" else "\n"
        cleanDevices += device
        val bytes = (lead + json + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size >= BIG_MESSAGE_BYTES) speedUp()
        enqueue(Outgoing(device, syncCharacteristic, bytes, label))
    }

    private fun enqueue(message: Outgoing) {
        outbox.addLast(message)
        sendNext()
    }

    /**
     * The largest piece one notification can carry to this device. The link has one ATT MTU,
     * but the iPhone's exchange (reported here) and the watch's own client exchange (Nordic) can
     * disagree, and a piece longer than the real one is cut short by the stack without an error:
     * take the smaller. The client's is also the fallback when this server never heard of an MTU
     * (the service restarted while the iPhone stayed connected). An attribute value is at most
     * 512 bytes however large the MTU, and the stack throws on anything longer.
     */
    private fun chunkSize(device: BluetoothDevice): Int {
        val serverMtu = mtus[device]
        // 23 is Nordic's value before (or without) an exchange: says nothing about the link
        val clientMtu = WristLinkService.connectionManager?.clientMtu?.takeIf { it > DEFAULT_MTU }
        val mtu = when {
            serverMtu != null && clientMtu != null -> minOf(serverMtu, clientMtu)
            serverMtu != null -> serverMtu
            clientMtu != null -> clientMtu
            else -> DEFAULT_MTU
        }
        return (mtu - 3).coerceIn(DEFAULT_MTU - 3, MAX_ATTRIBUTE_LENGTH)
    }

    private fun sendNext() {
        if (inFlight != null || retryJob?.isActive == true) return
        val s = server ?: return
        val message = outbox.firstOrNull() ?: run {
            onOutboxDrained()
            return
        }
        val device = message.device
        val end = minOf(message.offset + chunkSize(device), message.bytes.size)
        val value = message.bytes.copyOfRange(message.offset, end)
        val characteristic = message.characteristic
        inFlight = InFlight(message, value.size)
        // A throw here would take the whole bridge down with it, so treat it like a busy stack
        val queued = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                s.notifyCharacteristicChanged(device, characteristic, false, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, characteristic, false)
            }
        }.onFailure { Log.w(TAG, "Notification to ${device.address} failed", it) }.getOrDefault(false)
        if (!queued) {
            // Usually the stack is just busy. Dropping a piece would break the whole message
            // (a summary is ~20 of them), so the same piece goes again shortly. A device that
            // keeps refusing is gone: its queue is dropped rather than retried forever.
            inFlight = null
            retryOrGiveUp(device, BUSY_RETRY_MS)
            return
        }
        armSentWatchdog()
    }

    /**
     * One watchdog for the whole stream rather than a timer per piece: it wakes when the piece in
     * flight would be overdue, and goes back to sleep if that one was confirmed meanwhile.
     */
    private fun armSentWatchdog() {
        if (sentWatchdog?.isActive == true) return
        sentWatchdog = scope.launch {
            while (true) {
                val piece = inFlight ?: return@launch
                val wait = piece.sentAt + SENT_WATCHDOG_MS - SystemClock.elapsedRealtime()
                if (wait > 0) {
                    delay(wait)
                } else {
                    // So the piece sent next gets a watchdog of its own
                    sentWatchdog = null
                    onSentMissing()
                    return@launch
                }
            }
        }
    }

    /** The head piece didn't go out: the same piece again after [delayMs], up to a limit. */
    private fun retryOrGiveUp(device: BluetoothDevice, delayMs: Long) {
        if (++busyRetries <= MAX_BUSY_RETRIES) {
            retryJob = scope.launch {
                delay(delayMs)
                retryJob = null
                sendNext()
            }
        } else {
            Log.w(TAG, "Notifications to ${device.address} keep failing, dropping its queue")
            busyRetries = 0
            dropMessages(device)
            sendNext()
        }
    }

    private fun onSent(device: BluetoothDevice, status: Int) {
        // Late, after the watchdog gave up on it, or for a queue dropped meanwhile
        val sent = inFlight ?: return
        if (sent.message.device != device) return
        inFlight = null
        stalls = 0
        if (status != BluetoothGatt.GATT_SUCCESS && status != GATT_CONGESTED) {
            // Not sent: moving on would leave a hole in the message
            Log.w(TAG, "Notification to ${device.address} failed with status $status, sending it again")
            retryOrGiveUp(device, FAILED_RETRY_MS)
            return
        }
        busyRetries = 0
        val message = sent.message
        message.offset += sent.length
        if (message.offset >= message.bytes.size && outbox.firstOrNull() === message) {
            outbox.removeFirst()
            message.label?.let { Log.i(TAG, "Sent the last piece of $it to ${device.address}") }
        }
        sendNext()
    }

    /**
     * The stack never confirmed the piece in flight (callbacks are held back while the link is
     * congested, and lost when it drops). Whether it went out is unknown, so the message starts
     * over behind a newline, which makes the iPhone drop what it got of it. If confirmations
     * keep getting lost, the GATT server itself is stuck: open it afresh.
     */
    private fun onSentMissing() {
        val stuck = inFlight ?: return
        inFlight = null
        val message = stuck.message
        stalls++
        Log.w(
            TAG, "No confirmation for a notification to ${message.device.address} " +
                "(${message.label ?: "${message.bytes.size} bytes"}, ${message.offset}/${message.bytes.size} sent), stall $stalls"
        )
        if (stalls >= MAX_STALLS) {
            stalls = 0
            reopenServer()
            return
        }
        if (message.characteristic === syncCharacteristic && message.offset > 0 &&
            message.bytes.firstOrNull() != NEWLINE
        ) {
            message.bytes = byteArrayOf(NEWLINE) + message.bytes
        }
        message.offset = 0
        sendNext()
    }

    /** Last resort for a stuck server: everything queued goes, and the iPhone sets up again. */
    private fun reopenServer() {
        Log.w(TAG, "Notifications keep getting stuck, opening the GATT server again")
        val old = server
        server = null
        runCatching { old?.close() }
        outbox.clear()
        inFlight = null
        retryJob?.cancel()
        retryJob = null
        busyRetries = 0
        subscribers.clear()
        mtus.clear()
        inboxes.clear()
        framedClients.clear()
        imageClients.clear()
        cleanDevices.clear()
        lastInfoSent.clear()
        infoReadCache.clear()
        updateImageRequester()
        onOutboxDrained()
        // Adding the service again makes Android tell the iPhone its services changed, so the
        // iPhone app discovers and subscribes again
        if (running) openServer()
    }

    /**
     * Drop what's queued for [device] (only on [characteristic], if given). The next sync
     * message to it then starts with a newline, in case part of a dropped one went out.
     */
    private fun dropMessages(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic? = null) {
        fun matches(message: Outgoing) =
            message.device == device && (characteristic == null || message.characteristic === characteristic)
        if (inFlight?.message?.let(::matches) == true) inFlight = null
        val removed = outbox.removeAll(::matches)
        if (removed || characteristic == null || characteristic === syncCharacteristic) cleanDevices.remove(device)
        if (removed) busyRetries = 0
    }

    /** A big message is queued: ask for the fast connection interval until the outbox is empty. */
    private fun speedUp() {
        relaxJob?.cancel()
        relaxJob = null
        if (spedUp) return
        spedUp = true
        WristLinkService.connectionManager?.speedUpForTransfer(BleConnectionManager.OWNER_COMPANION)
    }

    private fun onOutboxDrained() {
        if (!spedUp || relaxJob?.isActive == true) return
        relaxJob = scope.launch {
            delay(RELAX_DELAY_MS)
            if (outbox.isNotEmpty() || inFlight != null) return@launch
            spedUp = false
            WristLinkService.connectionManager?.relaxAfterTransfer(BleConnectionManager.OWNER_COMPANION)
        }
    }

    private fun infoJson(): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val stats = LinkStats.summary.value
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()

        return JSONObject()
            .put("protocol", PROTOCOL_VERSION)
            .put("app", version)
            .put("model", Build.MODEL)
            .put("battery", level)
            .put("charging", plugged)
            .put("connectedPct", stats.connectedFraction?.let { (it * 100).toInt() })
            .put("drops", stats.drops)
            .put("medianDelayMs", stats.medianDelayMs)
            .put("notifications", stats.notifications)
            .put("pending", store.pendingCount())
            .put("sleepSupported", HealthCollector.platformSleepSupported.value)
            .put("workoutsPending", HealthHistory.get(context).unsyncedWorkoutCount())
            .put("hr", LiveHealth.latest(context)?.let { JSONObject().put("bpm", it.bpm).put("t", it.time) })
            // When the forecast on the watch was fetched (s), so the iPhone knows when to send a new one
            .put("weatherAt", WeatherStore.fetchedAtSeconds(context))
    }

    private fun infoBytes(info: JSONObject) = info.toString().toByteArray(Charsets.UTF_8)

    /**
     * The info as one notification, which can't be split: the full JSON when it fits, else the
     * fields that change (still with every key an older iPhone app requires) marked compact, else
     * just a note that it changed, for the iPhone to read it.
     */
    private fun notifiedInfo(info: JSONObject, max: Int): ByteArray {
        val full = infoBytes(info)
        if (full.size <= max) return full
        val compact = JSONObject().put("compact", true)
        for (key in listOf(
            "protocol", "battery", "charging", "drops", "notifications",
            "pending", "workoutsPending", "weatherAt", "hr"
        )) {
            info.opt(key)?.let { compact.put(key, it) }
        }
        infoBytes(compact).let { if (it.size <= max) return it }
        compact.remove("hr")
        infoBytes(compact).let { if (it.size <= max) return it }
        return infoBytes(JSONObject().put("changed", true))
    }

    private fun notifiable(uuid: UUID, properties: Int, permissions: Int) = BluetoothGattCharacteristic(
        uuid, properties or BluetoothGattCharacteristic.PROPERTY_NOTIFY, permissions
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
            )
        )
    }
}
