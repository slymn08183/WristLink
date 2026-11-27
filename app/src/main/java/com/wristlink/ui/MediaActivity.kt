package com.wristlink.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.wristlink.ble.BondManager
import com.wristlink.service.WristLinkService
import com.wristlink.settings.SettingsManager
import com.wristlink.ui.screens.NowPlayingScreen
import com.wristlink.ui.theme.WristLinkTheme

/**
 * The "Music Control" launcher icon: a second entry in the app list that opens straight
 * into the media controls. Same app and process as WristLink, just its own task.
 */
class MediaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startBridgeIfNeeded()

        setContent {
            WristLinkTheme {
                // The bridge may still be starting; pick up its media state once it exists
                val bridge by WristLinkService.bridge.collectAsState()
                NowPlayingScreen(media = bridge?.media)
            }
        }
    }

    /** Opened straight from the launcher, maybe before WristLink itself ever ran. */
    private fun startBridgeIfNeeded() {
        if (WristLinkService.isRunning) return
        if (!SettingsManager.get(this).isOnboardingComplete) return
        if (BondManager(this).getSavedBondedAddress() == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return

        startForegroundService(
            Intent(this, WristLinkService::class.java)
                .setAction(WristLinkService.ACTION_CONNECT_BONDED)
        )
    }
}
