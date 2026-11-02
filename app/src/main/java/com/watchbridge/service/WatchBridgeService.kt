package com.watchbridge.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.watchbridge.MainActivity
import com.watchbridge.R
import com.watchbridge.WatchBridgeApp
import com.watchbridge.companion.CompanionGattServer
import com.watchbridge.diagnostics.LinkStats
import com.watchbridge.ams.AmsMediaManager
import com.watchbridge.ams.MediaSessionBridge
import com.watchbridge.ancs.AncsSessionManager
import com.watchbridge.ble.BleAdvertiser
import com.watchbridge.ble.BleConnectionManager
import com.watchbridge.ble.BondManager
import com.watchbridge.ble.ConnectionStateMachine
import com.watchbridge.notification.AncsNotificationPipeline
import com.watchbridge.notification.AppIconResolver
import com.watchbridge.notification.AppNameResolver
import com.watchbridge.notification.CallNotificationHandler
import com.watchbridge.notification.NotificationActionReceiver
import com.watchbridge.notification.NotificationRenderer
import com.watchbridge.settings.SettingsManager
import com.watchbridge.tile.ConnectionTileService
import com.watchbridge.tile.MediaTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the entire BLE + ANCS lifecycle.
 */
class WatchBridgeService : Service() {

    companion object {
        private const val TAG = "WatchBridgeService"
        private const val NOTIFICATION_ID = 1
        private const val BLUETOOTH_OFF_NOTIFICATION_ID = 2
        private const val PHONE_BATTERY_NOTIFICATION_ID = 3
        private const val PHONE_LOST_NOTIFICATION_ID = 7

        /** Brief drops (reconnects, phone restarts) shouldn't buzz; a minute away is worth knowing. */
        private const val PHONE_LOST_ALERT_DELAY_MS = 60_000L
        private const val WAKE_LOCK_TAG = "WatchBridge::BleConnection"

        /** Alert when the iPhone's battery drops to this level... */
        private const val PHONE_BATTERY_LOW_PERCENT = 20
        /** ...and again only after it has recovered past this (hysteresis, not spam). */
        private const val PHONE_BATTERY_RESET_PERCENT = 25

        /** How often the watchdog checks for a silently dead background connect. */
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        /** WAITING_FOR_PHONE for this long → assume the background connect died; kick. */
        private const val WATCHDOG_STUCK_MS = 4 * 60_000L
        /** Screen-on nudge uses a shorter fuse: the user is looking at a stale watch. */
        private const val WATCHDOG_SCREEN_ON_STUCK_MS = 60_000L

        const val ACTION_START = "com.watchbridge.service.START"
        const val ACTION_STOP = "com.watchbridge.service.STOP"
        const val ACTION_CONNECT_BONDED = "com.watchbridge.service.CONNECT_BONDED"
        const val ACTION_START_ADVERTISING = "com.watchbridge.service.START_ADVERTISING"

        var connectionManager: BleConnectionManager? = null
            private set
        var advertiser: BleAdvertiser? = null
        var companionServer: CompanionGattServer? = null
            private set
        var sessionManager: AncsSessionManager? = null
            private set
        var stateMachine: ConnectionStateMachine? = null
            private set
        var bondManager: BondManager? = null
            private set
        var pipeline: AncsNotificationPipeline? = null
            private set
        var settingsManager: SettingsManager? = null
            private set
        var mediaManager: AmsMediaManager? = null
            private set
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var actionReceiver: NotificationActionReceiver? = null
    private var mediaSessionBridge: MediaSessionBridge? = null

    /** When the state machine last changed state; the watchdog measures stuckness from it. */
    @Volatile
    private var lastStateChangeMs = System.currentTimeMillis()

    /** Screen-on nudge: the user just raised their wrist — recover right now, not in minutes. */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            val sm = stateMachine ?: return
            if (sm.state.value == ConnectionStateMachine.State.WAITING_FOR_PHONE &&
                System.currentTimeMillis() - lastStateChangeMs >= WATCHDOG_SCREEN_ON_STUCK_MS
            ) {
                Log.i(TAG, "Screen-on nudge: kicking stale background connect")
                sm.kickReconnect()
            }
        }
    }

    /** Pauses reconnecting while the watch's Bluetooth is off, resumes when it's back on. */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> stateMachine?.onBluetoothStateChanged(enabled = false)
                BluetoothAdapter.STATE_ON -> stateMachine?.onBluetoothStateChanged(enabled = true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        LinkStats.init(this)
        val settings = SettingsManager(this)
        val connMgr = BleConnectionManager(this)
        val bondMgr = BondManager(this)
        val adv = BleAdvertiser(this)
        val sessMgr = AncsSessionManager()
        val sm = ConnectionStateMachine(connMgr, bondMgr)

        val renderer = NotificationRenderer(this, settings)
        val callHandler = CallNotificationHandler(this, renderer)
        val appNameResolver = AppNameResolver(this)
        val appIconResolver = AppIconResolver(this)
        val pipe = AncsNotificationPipeline(
            context = this,
            connectionManager = connMgr,
            sessionManager = sessMgr,
            renderer = renderer,
            callHandler = callHandler,
            appNameResolver = appNameResolver,
            appIconResolver = appIconResolver,
            settings = settings
        )

        val media = AmsMediaManager(connMgr)
        connMgr.setAmsCallbacks(
            onReady = media::onSubscribed,
            onRemoteCommands = media::onRemoteCommandsUpdate,
            onEntityUpdate = media::onEntityUpdate
        )

        connMgr.setAncsCallbacks(
            onNotificationSource = { data ->
                sessMgr.processNotificationEvent(data)
            },
            onDataSource = { data ->
                val result = sessMgr.processDataSourceFragment(data)
                if (result != null) {
                    pipe.handleDataSourceResponse(result)
                }
            }
        )

        sm.onDisconnected = {
            pipe.onDisconnected()
            sessMgr.onDisconnected()
            media.reset()
        }
        sm.onSessionReset = {
            sessMgr.resetSession()
        }

        connectionManager = connMgr
        sessionManager = sessMgr
        stateMachine = sm
        bondManager = bondMgr
        pipeline = pipe
        settingsManager = settings
        mediaManager = media
        advertiser = adv

        bondMgr.register()
        companionServer = CompanionGattServer(this).also { it.start() }

        val receiver = NotificationActionReceiver()
        registerReceiver(
            receiver,
            IntentFilter(NotificationActionReceiver.ACTION_PERFORM),
            RECEIVER_NOT_EXPORTED
        )
        actionReceiver = receiver

        pipe.start()
        sm.start()
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        // Bluetooth may already be off when the service starts (e.g. at boot)
        sm.onBluetoothStateChanged(enabled = bondMgr.isBluetoothEnabled())

        serviceScope.launch {
            sm.state.collect { state ->
                lastStateChangeMs = System.currentTimeMillis()
                if (state == ConnectionStateMachine.State.READY) LinkStats.onLinkUp() else LinkStats.onLinkDown()
                watchForLostPhone(state)
                // The service notification is posted once and never updated: Android won't let it
                // be hidden, but it can be swiped away, and re-posting it on every state change
                // would bring it back each time. Live status is on the tile and in the app.
                ConnectionTileService.requestUpdate(this@WatchBridgeService)
                showBluetoothOffAlert(state == ConnectionStateMachine.State.BLUETOOTH_OFF)

                when (state) {
                    ConnectionStateMachine.State.READY ->
                        sessMgr.updateState(AncsSessionManager.SessionState.ACTIVE)
                    ConnectionStateMachine.State.RECONNECTING ->
                        sessMgr.updateState(AncsSessionManager.SessionState.RECONNECTING)
                    ConnectionStateMachine.State.DISCONNECTED ->
                        sessMgr.updateState(AncsSessionManager.SessionState.DISCONNECTED)
                    else -> {}
                }
            }
        }

        // The phone's now playing, as a media session the watch's own media controls can use
        val sessionBridge = MediaSessionBridge(this, media)
        mediaSessionBridge = sessionBridge
        serviceScope.launch(Dispatchers.Main) {
            media.state.collect { sessionBridge.update(it) }
        }

        // Watchdog: Android's background connect dies silently when the iPhone rotates its
        // BLE address, leaving WAITING_FOR_PHONE forever with the phone in range. Kick the
        // state machine when that state has visibly outlived the rotation interval.
        serviceScope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (sm.state.value == ConnectionStateMachine.State.WAITING_FOR_PHONE &&
                    System.currentTimeMillis() - lastStateChangeMs >= WATCHDOG_STUCK_MS
                ) {
                    Log.i(TAG, "Watchdog: WAITING_FOR_PHONE too long, kicking reconnect")
                    sm.kickReconnect()
                }
            }
        }
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))

        // The connection tile shows the iPhone's battery
        var phoneBatteryAlerted = false
        serviceScope.launch {
            connMgr.phoneBattery.collect { level ->
                ConnectionTileService.requestUpdate(this@WatchBridgeService)

                // Buzz once when the phone's battery drops low, so it can be charged
                // before notifications stop arriving
                when {
                    level == null || level >= PHONE_BATTERY_RESET_PERCENT ->
                        phoneBatteryAlerted = false
                    level <= PHONE_BATTERY_LOW_PERCENT && !phoneBatteryAlerted -> {
                        phoneBatteryAlerted = true
                        if (settings.isPhoneBatteryAlertEnabled) showPhoneBatteryAlert(level)
                    }
                }
            }
        }
        serviceScope.launch {
            connMgr.phoneDeviceName.collect { ConnectionTileService.requestUpdate(this@WatchBridgeService) }
        }

        // Refresh the media tile when what it shows changes (not on every position tick)
        serviceScope.launch {
            media.state
                .map {
                    listOf(
                        it.available, it.playerName, it.title, it.artist, it.isPlaying,
                        it.supportedCommands, it.volume?.let { volume -> (volume * 100).toInt() }
                    )
                }
                .distinctUntilChanged()
                .collect { MediaTileService.requestUpdate(this@WatchBridgeService) }
        }

        // Turning the status notification on or off in Settings takes effect right away
        serviceScope.launch {
            settings.statusNotification.drop(1).collect { show ->
                if (show) {
                    startForeground(NOTIFICATION_ID, buildNotification())
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }

        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Required after startForegroundService() even when the user turned the notification off;
        // in that case it's dropped again right away
        startForeground(NOTIFICATION_ID, buildNotification())
        if (settingsManager?.statusNotification?.value == false) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }

        when (intent?.action) {
            ACTION_CONNECT_BONDED -> {
                stateMachine?.autoConnectToBonded()
            }
            ACTION_START_ADVERTISING -> {
                val adv = advertiser
                val sm = stateMachine
                if (adv != null && sm != null) {
                    sm.startAdvertisingForPairing(adv)
                } else {
                    Log.e(TAG, "Cannot start advertising: advertiser=$adv, stateMachine=$sm")
                }
            }
            ACTION_STOP -> {
                stateMachine?.disconnect()
                advertiser?.close()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        companionServer?.stop()
        companionServer = null
        Log.i(TAG, "Service destroyed")

        advertiser?.close()
        stateMachine?.disconnect()
        bondManager?.unregister()

        try {
            actionReceiver?.let { unregisterReceiver(it) }
        } catch (_: IllegalArgumentException) { }
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) { }
        try {
            unregisterReceiver(screenOnReceiver)
        } catch (_: IllegalArgumentException) { }
        showBluetoothOffAlert(false)

        releaseWakeLock()
        serviceScope.cancel()
        mediaSessionBridge?.release()
        mediaSessionBridge = null

        connectionManager = null
        sessionManager = null
        stateMachine = null
        bondManager = null
        pipeline = null
        settingsManager = null
        mediaManager = null
        advertiser = null
        isRunning = false
        ConnectionTileService.requestUpdate(this)
        MediaTileService.requestUpdate(this)

        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(10 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    /**
     * A notification while the watch's Bluetooth is off, since nothing arrives from the
     * iPhone meanwhile. Posted once per time Bluetooth goes off; removed when it's back on.
     */
    private fun showBluetoothOffAlert(show: Boolean) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (!show) {
            nm.cancel(BLUETOOTH_OFF_NOTIFICATION_ID)
            return
        }

        val openApp = PendingIntent.getActivity(
            this, BLUETOOTH_OFF_NOTIFICATION_ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, WatchBridgeApp.CONNECTION_ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth_disabled)
            .setContentTitle("Bluetooth is off")
            .setContentText("Turn on Bluetooth to get your phone's notifications")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(BLUETOOTH_OFF_NOTIFICATION_ID, notification)
    }

    private var phoneLostJob: Job? = null
    private var wasReady = false

    /**
     * After the link to the iPhone drops, wait a minute; if it's still gone, buzz once with a
     * "Phone disconnected" notification. It goes away by itself on reconnect. Not for Bluetooth
     * being off (that has its own alert) or a manual disconnect.
     */
    private fun watchForLostPhone(state: ConnectionStateMachine.State) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (state == ConnectionStateMachine.State.READY) {
            wasReady = true
            phoneLostJob?.cancel()
            nm.cancel(PHONE_LOST_NOTIFICATION_ID)
            return
        }
        val lost = wasReady && state != ConnectionStateMachine.State.BLUETOOTH_OFF &&
            state != ConnectionStateMachine.State.IDLE
        if (!lost || phoneLostJob?.isActive == true) return
        phoneLostJob = serviceScope.launch {
            delay(PHONE_LOST_ALERT_DELAY_MS)
            if (settingsManager?.disconnectAlert?.value != true) return@launch
            if (stateMachine?.state?.value == ConnectionStateMachine.State.READY) return@launch
            val openApp = PendingIntent.getActivity(
                this@WatchBridgeService, PHONE_LOST_NOTIFICATION_ID,
                Intent(this@WatchBridgeService, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this@WatchBridgeService, WatchBridgeApp.CONNECTION_ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bluetooth_disabled)
                .setContentTitle("Phone disconnected")
                .setContentText("Notifications will resume when your iPhone is back in range")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(PHONE_LOST_NOTIFICATION_ID, notification)
        }
        if (state == ConnectionStateMachine.State.IDLE) wasReady = false
    }

    private fun showPhoneBatteryAlert(level: Int) {
        val openApp = PendingIntent.getActivity(
            this, PHONE_BATTERY_NOTIFICATION_ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, WatchBridgeApp.PHONE_BATTERY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("Phone battery low")
            .setContentText("$level% left on your phone")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(android.app.NotificationManager::class.java)
            .notify(PHONE_BATTERY_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(statusText: String = "Keeping your phone connected"): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WatchBridgeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, WatchBridgeApp.SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher, "Stop", stopPendingIntent)
            // Not ongoing: One UI Watch shows "Can't clear" for ongoing notifications, and since
            // Android 13 a foreground service keeps running when its notification is dismissed
            .setSilent(true)
            .build()
    }
}
