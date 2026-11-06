package com.wristlink.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wristlink.ble.BleScanner
import com.wristlink.ble.ConnectionStateMachine
import com.wristlink.call.WatchCalls
import com.wristlink.service.WristLinkService
import com.wristlink.ui.components.WristLinkScaffold
import com.wristlink.ui.theme.SurfaceCard
import kotlinx.coroutines.delay
import java.util.UUID

@SuppressLint("MissingPermission")
@Composable
fun PairingScreen(
    onStartAdvertising: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnected: () -> Unit
) {
    val context = LocalContext.current
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter = bluetoothManager.adapter

    val smState by (WristLinkService.stateMachine?.state
        ?: kotlinx.coroutines.flow.MutableStateFlow(ConnectionStateMachine.State.IDLE))
        .collectAsState()

    // Only phones: the system list also holds earbuds, TVs and the Android phone the watch was
    // set up with, none of which can be connected to like an iPhone
    val bondedPhones = adapter?.bondedDevices
        ?.filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
        .orEmpty()
    // iPhones advertise Apple's own SDP record; if any phone has it, hide the others (the Android
    // phone used for watch setup can't be talked to over ANCS anyway)
    val iPhones = bondedPhones.filter { dev -> dev.uuids?.any { it.uuid == APPLE_SDP_UUID } == true }
    val bondedDevices = iPhones.ifEmpty { bondedPhones }
        .map { dev ->
            BleScanner.ScannedDevice(
                name = dev.name ?: "Phone",
                address = dev.address,
                rssi = 0
            )
        }

    // Galaxy watches only show up in the phone's Bluetooth list while they're discoverable
    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    val startPairing = {
        discoverableLauncher.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
        )
        onStartAdvertising()
    }

    // With "Answer calls on watch" on, pairing ends with one more step: making the watch the
    // phone's call audio. Often that link comes up with the pairing; this covers when it doesn't.
    var showCallStep by remember { mutableStateOf(false) }
    val watchName = adapter?.name ?: "this watch"

    LaunchedEffect(smState) {
        if (smState == ConnectionStateMachine.State.READY) {
            delay(500)
            if (WatchCalls.isEnabled(context)) showCallStep = true else onConnected()
        }
    }

    val listState = rememberScalingLazyListState()

    WristLinkScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 48.dp),
            autoCentering = AutoCenteringParams()
        ) {
            item {
                Text(
                    text = "Pair with phone",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center
                )
            }

            if (showCallStep) {
                item { CallAudioStep(watchName = watchName, onDone = onConnected) }
                return@ScalingLazyColumn
            }

            // Previously paired devices
            if (bondedDevices.isNotEmpty()) {
                item {
                    Text(
                        text = "Paired before",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                items(bondedDevices) { device ->
                    Chip(
                        onClick = {
                            val btDevice = adapter.getRemoteDevice(device.address)
                            onDeviceSelected(btDevice)
                        },
                        label = {
                            Text(
                                text = device.name ?: "Phone",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = "Tap to reconnect",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            when (smState) {
                ConnectionStateMachine.State.ADVERTISING -> {
                    item {
                        AdvertisingContent(watchName = PAIRING_NAME)
                    }
                    item {
                        Chip(
                            onClick = startPairing,
                            label = { Text("Not showing up?", maxLines = 1) },
                            secondaryLabel = {
                                Text("Make the watch visible again", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Chip(
                            onClick = {
                                val adv = WristLinkService.advertiser
                                val sm = WristLinkService.stateMachine
                                if (adv != null && sm != null) {
                                    sm.stopAdvertising(adv)
                                }
                            },
                            label = {
                                Text(
                                    text = "Cancel",
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                ConnectionStateMachine.State.CONNECTING,
                ConnectionStateMachine.State.CONNECTED -> {
                    item {
                        // A list item stacks its children on top of each other unless they're in a Column
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Connecting…\nAccept the pairing\non your phone",
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colors.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    item {
                        Chip(
                            onClick = startPairing,
                            label = {
                                Text(
                                    text = "Start pairing",
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Text(
                            text = "Turn off Bluetooth on any\nAndroid phone this watch\nwas set up with",
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallAudioStep(watchName: String, onDone: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Paired!",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.primary
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "One more step for calls",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // The phone lists the watch once, under the name it was paired with
                    // ("WristLink"); that one entry also carries the call audio
                    text = "On your phone: Settings → Bluetooth.\n" +
                        "The watch you just paired (\"WristLink\" or \"$watchName\") " +
                        "should say Connected. If it says Not Connected, tap it.",
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Chip(
            onClick = onDone,
            label = {
                Text(
                    text = "Done",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Already Connected? Just tap Done.",
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onSurfaceVariant
        )
    }
}

/** Name the watch advertises under while pairing (set by BleAdvertiser). */
private const val PAIRING_NAME = "WristLink"
private const val DISCOVERABLE_SECONDS = 300
/** Apple-specific service record every iPhone exposes over Bluetooth Classic. */
private val APPLE_SDP_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")

@Composable
private fun AdvertisingContent(watchName: String) {
    val transition = rememberInfiniteTransition(label = "advPulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.alpha(pulseAlpha)) {
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ready to pair",
            style = MaterialTheme.typography.body1,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.primary
        )
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "On your phone, open\nSettings → Bluetooth\nand tap",
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // The one thing to find in a long device list, so it gets the big type
                Text(
                    text = watchName,
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "It can be listed twice.\nEither one works.",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
            }
        }
    }
}
