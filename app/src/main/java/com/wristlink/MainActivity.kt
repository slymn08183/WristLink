package com.wristlink

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.wristlink.ble.BleScanner
import com.wristlink.ble.BondManager
import com.wristlink.call.WatchCalls
import com.wristlink.ble.ConnectionStateMachine
import com.wristlink.service.WristLinkService
import com.wristlink.settings.SettingsManager
import com.wristlink.ui.screens.HomeScreen
import com.wristlink.ui.screens.NowPlayingScreen
import com.wristlink.ui.screens.OnboardingScreen
import com.wristlink.ui.screens.PairingScreen
import com.wristlink.ui.screens.SettingsScreen
import com.wristlink.ui.screens.StatsScreen
import com.wristlink.ui.theme.WristLinkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var bleScanner: BleScanner
    private lateinit var localSettings: SettingsManager
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Only the required ones decide; the call permissions are optional
        permissionsGranted = requiredPermissions().all { isGranted(it) }
        if (permissionsGranted) {
            startBridgeService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleScanner = BleScanner(this)
        localSettings = SettingsManager.get(this)

        checkPermissions()

        val startRoute = if (localSettings.isOnboardingComplete) "home" else "onboarding"

        setContent {
            WristLinkTheme {
                val navController = rememberSwipeDismissableNavController()

                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = startRoute
                ) {
                    composable("onboarding") {
                        OnboardingScreen(
                            onComplete = {
                                localSettings.setOnboardingComplete()
                                navController.navigate("home") {
                                    popUpTo("onboarding") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("home") {
                        HomeScreen(
                            onNavigateToPairing = {
                                navController.navigate("pairing")
                            },
                            onNavigateToSettings = {
                                navController.navigate("settings")
                            },
                            onNavigateToMedia = {
                                navController.navigate("media")
                            },
                            onReconnect = {
                                val sm = WristLinkService.stateMachine
                                val reconnecting = if (sm != null) {
                                    sm.autoConnectToBonded()
                                } else if (BondManager(this@MainActivity).hasPairedPhone()) {
                                    // Service not running: start it, it reconnects to the paired phone
                                    startForegroundService(
                                        Intent(this@MainActivity, WristLinkService::class.java)
                                            .setAction(WristLinkService.ACTION_CONNECT_BONDED)
                                    )
                                    true
                                } else {
                                    false
                                }
                                // Only a watch that never paired a phone goes to pairing
                                if (!reconnecting) navController.navigate("pairing")
                            },
                            onTurnOnBluetooth = { turnOnBluetooth() },
                            onDisconnect = {
                                WristLinkService.stateMachine?.disconnect()
                            }
                        )
                    }
                    composable("pairing") {
                        PairingScreen(
                            onStartAdvertising = {
                                ensureServiceRunning()
                                val intent = Intent(
                                    this@MainActivity,
                                    WristLinkService::class.java
                                ).apply {
                                    action = WristLinkService.ACTION_START_ADVERTISING
                                }
                                startForegroundService(intent)
                            },
                            onDeviceSelected = { device ->
                                connectToDevice(device)
                            },
                            onConnected = {
                                navController.popBackStack()
                            }
                        )
                    }
                    composable("settings") {
                        val settings = WristLinkService.settingsManager ?: localSettings
                        SettingsScreen(
                            settings = settings,
                            onOpenStats = { navController.navigate("stats") }
                        )
                    }
                    composable("stats") {
                        StatsScreen()
                    }
                    composable("media") {
                        NowPlayingScreen(media = WristLinkService.mediaManager)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (WristLinkService.isRunning) {
            val sm = WristLinkService.stateMachine
            if (sm?.state?.value == ConnectionStateMachine.State.IDLE) {
                sm.autoConnectToBonded()
            }
        }
    }

    private fun requiredPermissions(): List<String> {
        val required = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        // POST_NOTIFICATIONS required on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return required
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        val missingRequired = requiredPermissions().filterNot { isGranted(it) }
        // "Answer calls on watch" is on by default, so ask for its permissions once, up front,
        // together with Bluetooth's instead of making the user find the setting later. Without
        // them the bridge still runs (calls are answered on the phone); the setting asks again.
        val missingCall = if (localSettings.isCallsOnWatchEnabled && !localSettings.callPermissionsAsked) {
            WatchCalls.PERMISSIONS.filterNot { isGranted(it) }
        } else {
            emptyList()
        }

        if (missingRequired.isEmpty() && missingCall.isEmpty()) {
            permissionsGranted = true
            startBridgeService()
        } else {
            if (missingCall.isNotEmpty()) localSettings.markCallPermissionsAsked()
            permissionLauncher.launch((missingRequired + missingCall).toTypedArray())
        }
    }

    private fun startBridgeService() {
        if (!WristLinkService.isRunning && localSettings.isOnboardingComplete) {
            // The state machine doesn't exist until the service has started, so let the
            // service connect to the bonded iPhone itself
            Log.i(TAG, "Starting WristLinkService and connecting to bonded iPhone")
            val intent = Intent(this, WristLinkService::class.java).apply {
                action = WristLinkService.ACTION_CONNECT_BONDED
            }
            startForegroundService(intent)
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "connectToDevice: ${device.address}")
        ensureServiceRunning()

        // Service may need a moment to initialize — poll until ready
        CoroutineScope(Dispatchers.Main).launch {
            var attempts = 0
            while (WristLinkService.stateMachine == null && attempts < 20) {
                delay(100)
                attempts++
            }
            val sm = WristLinkService.stateMachine
            if (sm != null) {
                Log.i(TAG, "Service ready after ${attempts * 100}ms, connecting...")
                sm.connectTo(device)
            } else {
                Log.e(TAG, "Service state machine still null after 2s!")
            }
        }
    }

    /** One-tap "Turn on Bluetooth?" dialog, else the Bluetooth (or main) settings screen. */
    private fun turnOnBluetooth() {
        val actions = listOf(
            BluetoothAdapter.ACTION_REQUEST_ENABLE,
            Settings.ACTION_BLUETOOTH_SETTINGS,
            Settings.ACTION_SETTINGS
        )
        for (action in actions) {
            try {
                startActivity(Intent(action))
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No screen for $action")
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed: $action")
            }
        }
    }

    private fun ensureServiceRunning() {
        if (!WristLinkService.isRunning) {
            Log.i(TAG, "Starting WristLinkService")
            val intent = Intent(this, WristLinkService::class.java).apply {
                action = WristLinkService.ACTION_START
            }
            startForegroundService(intent)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
