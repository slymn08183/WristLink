package com.watchbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Whether the paired iPhone is in use right now, so its notifications don't buzz the watch
 * while they're already on the screen in front of you.
 *
 * ANCS doesn't say, but the iPhone keeps broadcasting Apple's "Nearby Info" advertisement,
 * whose action code says whether its screen is on (Celosia & Cunche, "Discontinued Privacy",
 * 2020). The scan is filtered in the Bluetooth chip and runs at low power, so the latest state
 * is always at hand when a notification arrives.
 *
 * The iPhone advertises from a random address; Android resolves it to the bonded identity
 * address with the bond's IRK, which is how our iPhone is told apart from others nearby.
 */
@SuppressLint("MissingPermission")
class PhoneActivityMonitor(context: Context, private val bondManager: BondManager) {

    companion object {
        private const val TAG = "PhoneActivity"
        private const val APPLE_COMPANY_ID = 0x004C
        private const val TYPE_NEARBY_INFO: Byte = 0x10

        /** Action codes (low nibble of the first Nearby Info byte) meaning the screen is on. */
        private val ACTIVE_ACTIONS = setOf(0x07, 0x09)

        /** Older than this, the last state says nothing about now. */
        private const val STALE_MS = 15_000L
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Volatile private var lastAction = -1
    @Volatile private var lastSeen = 0L
    private var lastLogged: String? = null
    private var scanning = false

    /** True only when a fresh advertisement from our own iPhone says its screen is on. */
    val isPhoneInUse: Boolean
        get() = lastAction in ACTIVE_ACTIONS && SystemClock.elapsedRealtime() - lastSeen < STALE_MS

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
            scanning = false
        }
    }

    @Synchronized
    fun start() {
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        // Only Apple advertisements that start with Nearby Info wake the app
        val filter = ScanFilter.Builder()
            .setManufacturerData(APPLE_COMPANY_ID, byteArrayOf(TYPE_NEARBY_INFO), byteArrayOf(0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            scanning = true
            Log.i(TAG, "Listening for the iPhone's Nearby Info")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the scan", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!scanning) return
        scanning = false
        lastAction = -1
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan: ${e.message}")
        }
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return
        val info = nearbyInfo(data) ?: return
        val action = info[0].toInt() and 0x0F
        if (!result.device.address.equals(bondManager.getSavedBondedAddress(), ignoreCase = true)) return

        // Each change of our iPhone's state once, to tell which codes mean "in use"
        val summary = "action=0x%02x raw=%s".format(action, info.joinToString("") { "%02x".format(it) })
        if (summary != lastLogged) {
            lastLogged = summary
            Log.i(TAG, "iPhone Nearby Info: $summary rssi=${result.rssi}")
        }
        lastAction = action
        lastSeen = SystemClock.elapsedRealtime()
    }

    /** The Nearby Info payload among the TLVs of Apple's manufacturer data, if any. */
    private fun nearbyInfo(data: ByteArray): ByteArray? {
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i]
            val len = data[i + 1].toInt() and 0xFF
            val end = i + 2 + len
            if (end > data.size) return null
            if (type == TYPE_NEARBY_INFO && len >= 1) return data.copyOfRange(i + 2, end)
            i = end
        }
        return null
    }
}
