package com.watchbridge.companion

import android.annotation.SuppressLint
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
import android.util.Log
import com.watchbridge.diagnostics.LinkStats
import org.json.JSONObject
import java.util.UUID

/**
 * The watch's side of the link to the WristLink iPhone app.
 *
 * The iPhone is already connected to the watch (for notifications), so the iPhone app doesn't
 * scan or wait for advertising: it asks iOS for connected peripherals offering [SERVICE_UUID]
 * and talks to this GATT server over that same link.
 *
 * Protocol version 1 has a single characteristic, [INFO_UUID]: readable and notifiable JSON with
 * the watch's battery and link stats. Later versions add channels for health data.
 */
@SuppressLint("MissingPermission")
class CompanionGattServer(private val context: Context) {

    companion object {
        private const val TAG = "CompanionGatt"
        const val PROTOCOL_VERSION = 1

        val SERVICE_UUID: UUID = UUID.fromString("57a1e1c0-0001-4b8e-9a1f-6f4b10000000")
        val INFO_UUID: UUID = UUID.fromString("57a1e1c0-0002-4b8e-9a1f-6f4b10000000")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private val infoCharacteristic = BluetoothGattCharacteristic(
        INFO_UUID,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
            )
        )
    }

    /** Devices that turned on notifications for [INFO_UUID]. */
    private val subscribers = mutableSetOf<BluetoothDevice>()

    /** Last battery level pushed, to notify only on change. */
    private var lastBattery = -1

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
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                synchronized(subscribers) { subscribers.remove(device) }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != INFO_UUID) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                return
            }
            // Long reads come in pieces with growing offsets; the JSON is rebuilt each time, which
            // is fine because it only changes slowly
            val value = infoJson()
            val chunk = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enabled = value != null && value.isNotEmpty() && value[0].toInt() != 0
                synchronized(subscribers) {
                    if (enabled) subscribers.add(device) else subscribers.remove(device)
                }
                Log.i(TAG, "${device.address} ${if (enabled) "subscribed to" else "unsubscribed from"} info")
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
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
        s.addService(service)
        server = s
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        Log.i(TAG, "Companion service up (protocol $PROTOCOL_VERSION)")
    }

    fun stop() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
        server?.close()
        server = null
        synchronized(subscribers) { subscribers.clear() }
    }

    /** Push fresh info to every subscribed iPhone app. */
    fun notifyInfo() {
        val s = server ?: return
        val targets = synchronized(subscribers) { subscribers.toList() }
        if (targets.isEmpty()) return
        val value = infoJson()
        for (device in targets) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                s.notifyCharacteristicChanged(device, infoCharacteristic, false, value)
            } else {
                @Suppress("DEPRECATION")
                infoCharacteristic.value = value
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, infoCharacteristic, false)
            }
        }
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
            .put("battery", level)
            .put("charging", plugged)
            .put("connectedPct", stats.connectedFraction?.let { (it * 100).toInt() })
            .put("drops", stats.drops)
            .put("medianDelayMs", stats.medianDelayMs)
            .put("notifications", stats.notifications)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }
}
