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
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.wristlink.diagnostics.LinkStats
import com.wristlink.health.HealthCollector
import com.wristlink.health.HealthHistory
import com.wristlink.health.HealthMetrics
import com.wristlink.health.HealthProfile
import com.wristlink.health.LiveHealth
import com.wristlink.health.HealthStore
import com.wristlink.settings.SettingsManager
import com.wristlink.weather.WeatherStore
import com.wristlink.workout.WorkoutSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The watch's side of the link to the WristLink iPhone app.
 *
 * The iPhone is already connected to the watch (for notifications), so the iPhone app doesn't
 * scan or wait for advertising: it asks iOS for connected peripherals offering [SERVICE_UUID]
 * and talks to this GATT server over that same link.
 *
 * - [INFO_UUID] (read, notify): JSON with battery, link stats and how many health samples wait.
 * - [SYNC_UUID] (write, notify): newline-terminated JSON messages. The iPhone writes
 *   `{"op":"pull","after":ID,"max":N}` and gets `{"op":"batch","items":[...],"more":B}` back,
 *   split into notifications that fit the MTU; once the samples are in Apple Health it writes
 *   `{"op":"ack","upTo":ID}` and the watch drops them. Settings go both ways (`getSettings`,
 *   `setSetting`, and `settings` pushed whenever they change on the watch), and the iPhone sends
 *   the forecast (`weather`, see WeatherStore). Writes from the iPhone are newline-framed too and
 *   may arrive in several pieces, with or without response.
 * - The iPhone app says what it can do with `{"op":"hello","features":[…]}`. One that has
 *   "images" fetches app icons and album art for the watch (PhoneImages).
 *
 * To keep the iPhone (and both batteries) mostly asleep, the info notification announcing new
 * samples goes out at most every [PENDING_NOTIFY_INTERVAL_MS], or sooner when many are waiting.
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
        private const val MAX_ATTRIBUTE_LENGTH = 512
        private const val MAX_BATCH = 100
        private const val PENDING_NOTIFY_INTERVAL_MS = 15 * 60 * 1000L
        private const val PENDING_NOTIFY_THRESHOLD = 300
        /** Several changes in a row (a slider, quiet mode flipping DND and wake) go out as one. */
        private const val SETTINGS_PUSH_DELAY_MS = 400L
        /** Album art arrives as about 20 KB of base64. */
        private const val MAX_INBOX_BYTES = 96 * 1024
        private const val NEWLINE = '\n'.code.toByte()
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val store = HealthStore.get(context)
    private var server: BluetoothGattServer? = null

    // GATT callbacks arrive on binder threads; everything stateful runs on this one thread
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

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
    private val inbox = mutableMapOf<BluetoothDevice, ByteArray>()

    /** Notifications go out one at a time: the next waits for onNotificationSent. */
    private val outbox = ArrayDeque<Triple<BluetoothDevice, BluetoothGattCharacteristic, ByteArray>>()
    private var sending = false
    /** Times in a row the stack refused the piece at the head of the outbox. */
    private var busyRetries = 0

    private var lastBattery = -1
    private var lastPendingNotifyMs = 0L

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
            if (level != lastBattery) {
                lastBattery = level
                notifyInfo()
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_DISCONNECTED) scope.launch {
                subscribers.values.forEach { it.remove(device) }
                mtus.remove(device)
                inbox.remove(device)
                imageClients.remove(device)
                updateImageRequester()
                outbox.removeAll { it.first == device }
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
            // Long reads come in pieces with growing offsets; the JSON only changes slowly
            val value = infoJson()
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
                    if (uuid == SYNC_UUID) updateImageRequester()
                }
                Log.i(TAG, "${device.address} ${if (enabled) "subscribed to" else "unsubscribed from"} $uuid")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            scope.launch {
                sending = false
                sendNext()
            }
        }
    }

    fun start() {
        if (server != null) return
        val s = bluetoothManager?.openGattServer(context, callback) ?: run {
            Log.w(TAG, "Couldn't open GATT server")
            return
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(infoCharacteristic)
        service.addCharacteristic(syncCharacteristic)
        s.addService(service)
        server = s
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ContextCompat.registerReceiver(
            context, dndReceiver, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        settingsWatcher = scope.launch { SettingsManager.get(context).changes.collect { onSettingsChanged() } }
        profileWatcher = scope.launch { HealthProfile.get(context).state.collect { onSettingsChanged() } }
        Log.i(TAG, "Companion service up (protocol $PROTOCOL_VERSION)")
    }

    fun stop() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { context.unregisterReceiver(dndReceiver) }
        settingsWatcher?.cancel()
        profileWatcher?.cancel()
        server?.close()
        server = null
    }

    /** Push fresh info to every subscribed iPhone app. */
    fun notifyInfo() {
        scope.launch {
            val value = infoJson()
            subscribers[INFO_UUID]?.forEach { enqueue(it, infoCharacteristic, value) }
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
            scope.launch { sendMessage(device, request) }
            true
        }
    }

    /** Collect a write and handle every complete line in it. */
    private fun receive(device: BluetoothDevice, value: ByteArray) {
        var buffer = (inbox[device] ?: ByteArray(0)) + value
        while (true) {
            val newline = buffer.indexOf(NEWLINE)
            if (newline < 0) break
            val line = buffer.copyOfRange(0, newline).toString(Charsets.UTF_8)
            buffer = buffer.copyOfRange(newline + 1, buffer.size)
            if (line.isNotBlank()) handleSyncRequest(device, line)
        }
        // iPhone apps before protocol 4 send each request as one write with no newline
        if (buffer.isNotEmpty() && buffer[0] == '{'.code.toByte()) {
            val text = buffer.toString(Charsets.UTF_8)
            if (runCatching { JSONObject(text) }.isSuccess) {
                handleSyncRequest(device, text)
                buffer = ByteArray(0)
            }
        }
        inbox[device] = if (buffer.size > MAX_INBOX_BYTES) ByteArray(0) else buffer
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
                sendMessage(device, reply)
                Log.i(TAG, "Sent ${batch.size} samples after $after (more=$more)")
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
                sendMessage(device, workout?.let { WorkoutSync.message(context, it) } ?: WorkoutSync.none())
                Log.i(TAG, "Sent workout ${workout?.id ?: "none"}")
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
                sendMessage(device, summary)
                Log.i(TAG, "Sent summary of $days days")
            }
            "weather" -> {
                WeatherStore.save(context, request)
                notifyInfo()
            }
            "hello" -> {
                val features = request.optJSONArray("features")
                val images = features != null && (0 until features.length()).any { features.optString(it) == "images" }
                if (images) imageClients.add(device) else imageClients.remove(device)
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

    /** One JSON message on the sync characteristic, newline-terminated, split to fit the MTU. */
    private fun sendMessage(device: BluetoothDevice, message: JSONObject) {
        val bytes = (message.toString() + "\n").toByteArray(Charsets.UTF_8)
        // An attribute value is at most 512 bytes however large the MTU (iPhones offer 517+), and
        // the stack throws on anything longer
        val chunk = minOf((mtus[device] ?: DEFAULT_MTU) - 3, MAX_ATTRIBUTE_LENGTH)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunk, bytes.size)
            enqueue(device, syncCharacteristic, bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    private fun enqueue(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        outbox.addLast(Triple(device, characteristic, value))
        sendNext()
    }

    private fun sendNext() {
        if (sending) return
        val s = server ?: return
        val (device, characteristic, value) = outbox.removeFirstOrNull() ?: return
        sending = true
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
            sending = false
            if (++busyRetries <= MAX_BUSY_RETRIES) {
                outbox.addFirst(Triple(device, characteristic, value))
                scope.launch {
                    delay(BUSY_RETRY_MS)
                    sendNext()
                }
            } else {
                Log.w(TAG, "Notifications to ${device.address} keep failing, dropping its queue")
                busyRetries = 0
                outbox.removeAll { it.first == device }
                sendNext()
            }
            return
        }
        busyRetries = 0
    }

    private fun infoJson(): ByteArray {
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
            .toString()
            .toByteArray(Charsets.UTF_8)
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
