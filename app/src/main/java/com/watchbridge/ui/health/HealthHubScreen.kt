package com.watchbridge.ui.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchbridge.R
import com.watchbridge.health.HealthMetrics
import com.watchbridge.health.HealthProfile
import com.watchbridge.health.LiveHealth
import com.watchbridge.health.Rings
import com.watchbridge.ui.HealthActivity
import com.watchbridge.ui.components.WatchBridgeScaffold
import java.text.NumberFormat

/** The Health app's front page: today's rings, then a chip per area. */
@Composable
fun HealthHubScreen(onOpen: (String) -> Unit, onWorkout: () -> Unit) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val profile by HealthProfile.get(context).state.collectAsState()
    val reading by LiveHealth.heartRate.collectAsState()
    val data by rememberHealth { ctx ->
        val now = System.currentTimeMillis()
        val today = HealthMetrics.startOfDay(now)
        val day = HealthMetrics.day(ctx, today, now)
        val nights = HealthMetrics.nights(ctx, 7, now)
        val days = lastDays(7, now).map { HealthMetrics.day(ctx, it, now) }
        Triple(day, nights.lastOrNull()?.takeIf { it.day == today }, HealthMetrics.energy(ctx, nights, days))
    }

    WatchBridgeScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 48.dp),
            autoCentering = AutoCenteringParams()
        ) {
            item { Title("Health", HeartColor) }

            if (!profile.configured) {
                item {
                    Chip(
                        onClick = { onOpen(HealthActivity.SCREEN_PROFILE) },
                        label = { Text("Set up your profile") },
                        secondaryLabel = { Text("Age, height, weight for calories", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { Icon(painterResource(R.drawable.ic_person), null, Modifier.size(18.dp)) },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                val day = data?.first
                Card(modifier = Modifier.clickable { onOpen(HealthActivity.SCREEN_TODAY) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val rings = day?.let { Rings.of(it, profile) }
                        RingStack(
                            fractions = rings?.map { it.fraction } ?: listOf(0f, 0f, 0f),
                            colors = listOf(StepsColor, TimeColor, EnergyColor),
                            size = 52.dp,
                            thickness = 5.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = rings?.get(0)?.let { NumberFormat.getIntegerInstance().format(it.value) } ?: "–",
                                style = MaterialTheme.typography.title2,
                                color = MaterialTheme.colors.onSurface
                            )
                            Text(
                                text = rings?.get(0)?.let { "of ${NumberFormat.getIntegerInstance().format(it.goal)} ${it.unit}" } ?: "Today",
                                style = MaterialTheme.typography.caption3,
                                color = MaterialTheme.colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                HubChip(
                    label = "Heart rate",
                    detail = reading?.let { "${it.bpm} bpm · ${formatClock(context, it.time)}" } ?: "No readings yet",
                    icon = R.drawable.ic_heart,
                    onClick = { onOpen(HealthActivity.SCREEN_HEART) }
                )
            }
            item {
                val night = data?.second
                HubChip(
                    label = "Sleep",
                    detail = when {
                        night == null -> "No sleep recorded last night"
                        night.ongoing -> "Sleeping since ${formatClock(context, night.start)}"
                        else -> formatDuration(night.asleepMs) + (night.score?.let { " · score $it" } ?: "")
                    },
                    icon = R.drawable.ic_sleep,
                    onClick = { onOpen(HealthActivity.SCREEN_SLEEP) }
                )
            }
            data?.third?.let { energy ->
                item {
                    Card {
                        Caption("Energy score")
                        Text("${energy.score}", style = MaterialTheme.typography.title1, color = EnergyColor)
                        Caption("${energy.band} · from sleep and activity, without HRV")
                    }
                }
            }
            item {
                HubChip(label = "Workout", detail = "Run, walk, bike, more", icon = R.drawable.ic_steps, onClick = onWorkout)
            }
            item {
                HubChip(
                    label = "Profile & goals",
                    detail = "${profile.age} y · ${profile.heightCm} cm · ${profile.weightKg} kg",
                    icon = R.drawable.ic_person,
                    onClick = { onOpen(HealthActivity.SCREEN_PROFILE) }
                )
            }
        }
    }
}

@Composable
private fun HubChip(label: String, detail: String, icon: Int, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label) },
        secondaryLabel = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Box(Modifier.padding(0.dp)) { Icon(painterResource(icon), null, Modifier.size(18.dp)) } },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}
