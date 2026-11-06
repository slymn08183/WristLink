package com.wristlink.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wristlink.timer.RestTimer
import com.wristlink.ui.theme.WristLinkTheme
import kotlinx.coroutines.delay

/**
 * The "Rest Timer" launcher icon: a standalone between-sets timer. Preset durations,
 * a countdown ring, and a buzz that cuts through a workout when the rest is over
 * (the buzz itself comes from the alarm, so it fires even with this screen gone).
 */
class RestTimerActivity : ComponentActivity() {

    companion object {
        /** Start a timer of this many seconds immediately (used by the tile's presets). */
        const val EXTRA_DURATION_S = "duration_s"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requested = intent.getIntExtra(EXTRA_DURATION_S, 0)
        if (requested > 0) RestTimer.start(this, requested)

        setContent {
            WristLinkTheme {
                RestTimerScreen(
                    onKeepScreenOn = { on ->
                        if (on) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun RestTimerScreen(onKeepScreenOn: (Boolean) -> Unit) {
    val context = LocalContext.current

    var running by remember { mutableStateOf(RestTimer.isRunning(context)) }
    var remainingMs by remember { mutableLongStateOf(RestTimer.remainingMs(context)) }
    var totalMs by remember { mutableLongStateOf(remainingMs) }

    // Tick while running; the screen staying on is what makes the countdown glanceable
    LaunchedEffect(running) {
        onKeepScreenOn(running)
        while (running) {
            remainingMs = RestTimer.remainingMs(context)
            if (remainingMs <= 0) running = false
            delay(100)
        }
    }

    if (running) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = if (totalMs > 0) remainingMs.toFloat() / totalMs else 0f,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                strokeWidth = 5.dp
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatTime(remainingMs),
                    style = MaterialTheme.typography.display1,
                    color = MaterialTheme.colors.onBackground
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row {
                    CompactChip(
                        onClick = {
                            RestTimer.extend(context, 15)
                            totalMs += 15_000
                            remainingMs = RestTimer.remainingMs(context)
                        },
                        label = { Text("+15s") },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    CompactChip(
                        onClick = {
                            RestTimer.cancel(context)
                            running = false
                        },
                        label = { Text("Stop") },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }
        }
    } else {
        val listState = rememberScalingLazyListState()
        val lastDuration = remember { RestTimer.lastDurationSeconds(context) }
        val start = { seconds: Int ->
            RestTimer.start(context, seconds)
            totalMs = seconds * 1000L
            remainingMs = totalMs
            running = true
        }

        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            autoCentering = AutoCenteringParams()
        ) {
            item {
                Text(
                    text = "Rest Timer",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            item {
                Chip(
                    onClick = { start(lastDuration) },
                    label = { Text("Repeat · ${formatTime(lastDuration * 1000L)}") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            RestTimer.PRESETS_S.forEach { seconds ->
                item {
                    Chip(
                        onClick = { start(seconds) },
                        label = { Text(formatTime(seconds * 1000L)) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** 90500ms → "1:31" (rounded up, so the buzz lands at "0:00", not after a second of it). */
private fun formatTime(ms: Long): String {
    val totalSeconds = (ms + 999) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
