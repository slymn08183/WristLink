package com.wristlink.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
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
import com.wristlink.call.WatchCalls
import com.wristlink.ui.theme.WristLinkTheme

class IncomingCallActivity : ComponentActivity() {

    private var callUid: Int = -1

    // State, not a plain var: a waiting call arrives through onNewIntent and must redraw
    private var callerName by mutableStateOf("Unknown Caller")

    private val callDismissedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras(intent)
        // For the whole life of the screen, not just while resumed: if it sleeps while ringing,
        // the end of the call must still close it
        registerReceiver(
            callDismissedReceiver,
            IntentFilter("com.wristlink.CALL_DISMISSED"),
            RECEIVER_NOT_EXPORTED
        )
        setContent {
            WristLinkTheme {
                IncomingCallScreen(
                    callerName = callerName,
                    onAccept = {
                        // Talk on the watch when it's the phone's hands-free device,
                        // otherwise answer on the phone
                        if (WatchCalls.answer(this)) finish() else performAction(AncsConstants.ACTION_POSITIVE)
                    },
                    onDecline = { performAction(AncsConstants.ACTION_NEGATIVE) }
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
        callerName = intent.getStringExtra("caller_name") ?: "Unknown Caller"
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

/** Names longer than this get the smaller style, so two lines still leave room for the buttons. */
private const val LONG_NAME = 14

/** The caller's name, at most two lines, smaller when long, so it never pushes buttons off the round screen. */
@Composable
internal fun callerNameStyle(name: String): TextStyle =
    if (name.length > LONG_NAME) MaterialTheme.typography.title3 else MaterialTheme.typography.title2

@Composable
private fun IncomingCallScreen(
    callerName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "ring")
    val ringAlpha by transition.animateFloat(
        initialValue = 0.0f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Text(
                text = "Incoming Call",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colors.primary.copy(alpha = ringAlpha),
                        shape = CircleShape
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = callerName,
                    style = callerNameStyle(callerName),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Decline button (red)
                Button(
                    onClick = onDecline,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFFD32F2F)
                    ),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_call_decline),
                        contentDescription = "Decline",
                        modifier = Modifier.size(24.dp)
                    )
                }
                // Accept button (green)
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF388E3C)
                    ),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_call_accept),
                        contentDescription = "Accept",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
