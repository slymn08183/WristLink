package com.wristlink.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wristlink.diagnostics.LinkStats
import com.wristlink.ui.components.WristLinkScaffold
import com.wristlink.ui.theme.SurfaceCard
import kotlin.math.roundToInt

/** How the link to the iPhone has been doing over the last 24 hours. */
@Composable
fun StatsScreen() {
    val stats by LinkStats.summary.collectAsState()
    val listState = rememberScalingLazyListState()

    // Uptime keeps growing while the screen is open; recount when it opens
    LaunchedEffect(Unit) { LinkStats.refresh() }

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
                    text = "Connection",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center
                )
            }
            item {
                Text(
                    text = if (stats.sinceMs == 0L) "No data yet" else "Last ${formatSpan(System.currentTimeMillis() - stats.sinceMs)}",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
            }

            item {
                StatCard(
                    label = "Notification delay",
                    value = stats.medianDelayMs?.let { formatDelay(it) } ?: "–",
                    detail = stats.p90DelayMs?.let { "9 in 10 within ${formatDelay(it)}" }
                        ?: "Waiting for new notifications"
                )
            }
            item {
                StatCard(
                    label = "Notifications",
                    value = stats.notifications.toString(),
                    detail = buildList {
                        add("${stats.emptyContent} without content")
                        if (stats.unavailable > 0) add("${stats.unavailable} never loaded")
                    }.joinToString(" · ")
                )
            }
            item {
                StatCard(
                    label = "Connected",
                    value = stats.connectedFraction?.let { "${(it * 100).roundToInt()}%" } ?: "–",
                    detail = when (stats.drops) {
                        0 -> "No drops"
                        1 -> "1 drop"
                        else -> "${stats.drops} drops"
                    }
                )
            }
            if (stats.medianReconnectMs != null) {
                item {
                    StatCard(
                        label = "Reconnecting",
                        value = formatSpan(stats.medianReconnectMs!!),
                        detail = "Longest ${formatSpan(stats.longestReconnectMs ?: 0)}"
                    )
                }
            }

            item {
                Chip(
                    onClick = { LinkStats.reset() },
                    label = { Text("Reset", maxLines = 1) },
                    secondaryLabel = { Text("Start counting again", maxLines = 1) },
                    colors = ChipDefaults.outlinedChipColors(),
                    border = ChipDefaults.outlinedChipBorder(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, detail: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.title2,
            color = MaterialTheme.colors.onSurface
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

/** ANCS timestamps are whole seconds, so sub-second precision would be made up. */
private fun formatDelay(ms: Long): String = when {
    ms < 1_000 -> "under 1 s"
    ms < 60_000 -> "${(ms / 1000.0).roundToInt()} s"
    else -> formatSpan(ms)
}

private fun formatSpan(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${s % 3600 / 60} min"
    }
}
