package com.wristlink.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wristlink.R
import com.wristlink.ancs.AncsConstants
import com.wristlink.ui.theme.WristLinkTheme
import kotlinx.coroutines.delay

class OngoingCallActivity : ComponentActivity() {

    companion object {
        /** Wall-clock time (ms) the call started, so the timer doesn't restart with the screen. */
        const val EXTRA_CALL_START = "call_start"
    }

    private var callUid: Int = -1

    // State, not plain vars: onNewIntent (another call) must redraw the screen
    private var callerName by mutableStateOf("Active Call")
    private var callStart by mutableLongStateOf(0L)

    private val callDismissedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras(intent)
        // Registered for the screen's whole life: a call ending while it sleeps must close it
        registerReceiver(
            callDismissedReceiver,
            IntentFilter("com.wristlink.CALL_DISMISSED"),
            RECEIVER_NOT_EXPORTED
        )
        setContent {
            WristLinkTheme {
                OngoingCallScreen(
                    callerName = callerName,
                    callStart = callStart,
                    onHangUp = { performAction(AncsConstants.ACTION_NEGATIVE) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readExtras(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(callDismissedReceiver)
        super.onDestroy()
    }

    private fun readExtras(intent: Intent) {
        callUid = intent.getIntExtra("call_uid", -1)
        callerName = intent.getStringExtra("caller_name") ?: "Active Call"
        // Older launches without the extra count from now, as before
        callStart = intent.getLongExtra(EXTRA_CALL_START, 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
    }

    private fun performAction(actionId: Byte) {
        val intent = Intent("com.wristlink.ACTION_PERFORM").apply {
            setPackage(packageName)
            putExtra("notification_uid", callUid)
            putExtra("action_id", actionId)
            putExtra("is_call", true)
        }
        sendBroadcast(intent)
        finish()
    }
}

@Composable
private fun OngoingCallScreen(
    callerName: String,
    callStart: Long,
    onHangUp: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }

    val elapsedSeconds = ((now - callStart) / 1000).coerceAtLeast(0)
    val timerText = if (elapsedSeconds >= 3600) {
        "%d:%02d:%02d".format(elapsedSeconds / 3600, elapsedSeconds % 3600 / 60, elapsedSeconds % 60)
    } else {
        "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Text(
                text = callerName,
                style = callerNameStyle(callerName),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF4CAF50), CircleShape)
                )
                Text(
                    text = timerText,
                    style = MaterialTheme.typography.body1.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Hang up button (red)
            Button(
                onClick = onHangUp,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFFD32F2F)
                ),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_call_decline),
                    contentDescription = "Hang Up",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
