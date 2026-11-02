package com.watchbridge.workout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Text
import com.watchbridge.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val PAGE_CONTROLS = 0
private const val PAGE_MAIN = 1
private const val PAGE_ZONES = 2
private const val PAGE_LAPS = 3
private const val PAGE_ROUTE = 4

private val PausedColor = Color(0xFFFFD54F)
private val FinishColor = Color(0xFFEF5350)

/** Wall-clock "now", ticking on the second, for the running duration. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    return now
}

/**
 * The workout in progress: controls, the main numbers, heart-rate zones, laps and (outdoors)
 * the route, side by side. Back twice quickly marks a lap; back once goes to the watch face
 * and leaves the workout running.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveWorkoutPager(live: WorkoutState.Live, onLeave: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val hasRoute = live.type?.outdoor == true && live.gps != WorkoutState.Signal.NOT_USED
    val pagerState = rememberPagerState(initialPage = PAGE_MAIN) { if (hasRoute) 5 else 4 }
    val scope = rememberCoroutineScope()
    val now = rememberNow()

    val lap = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        WorkoutService.lap(context)
    }

    // Double-press back = lap. A single press waits a moment to be sure it isn't the first of two
    var pendingBack by remember { mutableStateOf<Job?>(null) }
    BackHandler {
        val pending = pendingBack
        if (pending != null && pending.isActive) {
            pending.cancel()
            pendingBack = null
            lap()
        } else {
            pendingBack = scope.launch {
                delay(400)
                pendingBack = null
                onLeave()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                PAGE_CONTROLS -> ControlsPage(live, onLap = lap, onShowMain = { scope.launch { pagerState.animateScrollToPage(PAGE_MAIN) } })
                PAGE_MAIN -> MainPage(live, now, onLap = lap)
                PAGE_ZONES -> ZonesPage(live)
                PAGE_LAPS -> LapsPage(live, now, onLap = lap)
                PAGE_ROUTE -> RoutePage(live)
            }
        }
        HorizontalPageIndicator(pageIndicatorState = pagerIndicator(pagerState))
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun pagerIndicator(state: PagerState) = object : PageIndicatorState {
    override val pageOffset: Float get() = state.currentPageOffsetFraction
    override val selectedPage: Int get() = state.currentPage
    override val pageCount: Int get() = state.pageCount
}

@Composable
private fun PhaseLabel(live: WorkoutState.Live) {
    val (text, color) = when (live.phase) {
        WorkoutState.Phase.PAUSED -> (if (live.autoPaused) "Auto-paused" else "Paused") to PausedColor
        WorkoutState.Phase.STARTING -> "Starting…" to MaterialTheme.colors.onSurfaceVariant
        WorkoutState.Phase.ENDING -> "Finishing…" to MaterialTheme.colors.onSurfaceVariant
        else -> (live.type?.label ?: "Workout") to MaterialTheme.colors.primary
    }
    Text(text = text, style = MaterialTheme.typography.caption1, color = color, textAlign = TextAlign.Center)
}

/** Duration, distance, pace or speed, heart rate and calories. Long-press anywhere for a lap. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainPage(live: WorkoutState.Live, now: Long, onLap: () -> Unit) {
    val type = live.type ?: WorkoutType.OTHER
    val zone = live.hr?.let { HrZones.zoneOf(it, live.maxHr) } ?: 0
    Column(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLap
            )
            .padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live.gps != WorkoutState.Signal.NOT_USED) {
                SignalDot(live.gps)
                Spacer(Modifier.width(4.dp))
            }
            PhaseLabel(live)
        }
        Text(
            text = WorkoutFormat.duration(live.activeMs(now)),
            style = MaterialTheme.typography.display2,
            color = if (live.phase == WorkoutState.Phase.PAUSED) PausedColor else MaterialTheme.colors.onBackground
        )
        if (type.hasDistance || live.distance > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = WorkoutFormat.km(live.distance), style = MaterialTheme.typography.title1, color = MaterialTheme.colors.onBackground)
                Text(text = " km", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        when (type.effort) {
            WorkoutType.Effort.PACE -> MetricLine(WorkoutFormat.pace(WorkoutFormat.paceOf(live.speed)), "/km")
            WorkoutType.Effort.SPEED -> MetricLine(WorkoutFormat.speedKmh(live.speed), "km/h")
            WorkoutType.Effort.NONE -> if (live.steps > 0) MetricLine(live.steps.toString(), "steps")
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_heart),
                contentDescription = "Heart rate",
                tint = if (zone > 0) HrZones.colors[zone - 1] else Color(0xFFEF5350),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(text = WorkoutFormat.bpm(live.hr), style = MaterialTheme.typography.title3, color = MaterialTheme.colors.onBackground)
            Spacer(Modifier.width(12.dp))
            Text(text = WorkoutFormat.kcal(live.calories), style = MaterialTheme.typography.title3, color = MaterialTheme.colors.onBackground)
            Text(text = " kcal", style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun MetricLine(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text = value, style = MaterialTheme.typography.title2, color = MaterialTheme.colors.onBackground)
        Text(text = " $unit", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
    }
}

/** Current heart rate and zone on top, time in each zone below. */
@Composable
private fun ZonesPage(live: WorkoutState.Live) {
    val zone = live.hr?.let { HrZones.zoneOf(it, live.maxHr) } ?: 0
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = WorkoutFormat.bpm(live.hr),
                style = MaterialTheme.typography.title1,
                color = if (zone > 0) HrZones.colors[zone - 1] else MaterialTheme.colors.onBackground
            )
            Text(text = " bpm", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(
            text = when {
                live.heartRate == WorkoutState.Signal.OFF_BODY -> "Not on wrist"
                zone > 0 -> "Zone $zone · ${HrZones.labels[zone - 1]}"
                live.hr != null -> "Below zone 1"
                else -> "Reading heart rate…"
            },
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        ZoneBars(zoneMs = live.zoneMs, maxHr = live.maxHr, current = zone.takeIf { it > 0 }, modifier = Modifier.fillMaxWidth())
    }
}

/** The lap in progress, a Lap button, and the laps so far (newest first). */
@Composable
private fun LapsPage(live: WorkoutState.Live, now: Long, onLap: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val type = live.type ?: WorkoutType.OTHER
    // Active time of the lap in progress: the workout's active time minus the closed laps'
    val lapActive = (live.activeMs(now) - live.laps.sumOf { it.activeMs }).coerceAtLeast(0)
    val lapDistance = (live.distance - live.lapStartDistance).coerceAtLeast(0.0)
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 24.dp, start = 10.dp, end = 10.dp, bottom = 40.dp)
    ) {
        item {
            Text(text = "Lap ${live.laps.size + 1}", style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.primary)
        }
        item {
            Text(text = WorkoutFormat.duration(lapActive), style = MaterialTheme.typography.title1, color = MaterialTheme.colors.onBackground)
        }
        if (type.hasDistance) {
            item {
                Text(
                    text = "${WorkoutFormat.distance(lapDistance)} · ${lapPace(lapActive, lapDistance, type)}",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
            }
        }
        item {
            Button(
                onClick = onLap,
                colors = ButtonDefaults.primaryButtonColors(),
                modifier = Modifier.padding(top = 6.dp).size(ButtonDefaults.DefaultButtonSize)
            ) {
                Icon(painterResource(R.drawable.ic_workout_lap), contentDescription = "Lap", modifier = Modifier.size(24.dp))
            }
        }
        items(live.laps.asReversed()) { lap ->
            LapRow(lap, type)
        }
    }
}

@Composable
fun LapRow(lap: WorkoutState.Lap, type: WorkoutType) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "${lap.n}", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.primary, modifier = Modifier.width(18.dp))
        Text(text = WorkoutFormat.duration(lap.activeMs), style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onSurface, modifier = Modifier.weight(1f))
        if (type.hasDistance) {
            Text(text = WorkoutFormat.distance(lap.distance), style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        Text(text = lap.avgHr?.let { "${WorkoutFormat.bpm(it)} bpm" } ?: "", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

private fun lapPace(activeMs: Long, distance: Double, type: WorkoutType): String {
    if (distance < 10 || activeMs <= 0) return if (type.effort == WorkoutType.Effort.SPEED) "– km/h" else "–'–\" /km"
    val speed = distance / (activeMs / 1000.0)
    return if (type.effort == WorkoutType.Effort.SPEED) "${WorkoutFormat.speedKmh(speed)} km/h"
    else "${WorkoutFormat.pace(WorkoutFormat.paceOf(speed))} /km"
}

/** The route so far as a line. */
@Composable
private fun RoutePage(live: WorkoutState.Live) {
    val version by WorkoutState.routeVersion.collectAsState()
    val points = remember(version) { WorkoutState.route() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (points.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SignalDot(live.gps)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (live.gps == WorkoutState.Signal.UNAVAILABLE) "GPS unavailable" else "Waiting for GPS…",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
            }
        } else {
            RouteCanvas(points, Modifier.fillMaxSize().padding(42.dp))
            Text(
                text = WorkoutFormat.distance(live.distance),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
            )
        }
    }
}

/** Pause/resume, lap, finish. Finish asks for a second tap so a brush doesn't end the workout. */
@Composable
private fun ControlsPage(live: WorkoutState.Live, onLap: () -> Unit, onShowMain: () -> Unit) {
    val context = LocalContext.current
    var confirmFinish by remember { mutableStateOf(false) }
    LaunchedEffect(confirmFinish) {
        if (confirmFinish) {
            delay(3000)
            confirmFinish = false
        }
    }
    val paused = live.phase == WorkoutState.Phase.PAUSED
    val enabled = live.phase == WorkoutState.Phase.ACTIVE || paused

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PhaseLabel(live)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (paused) WorkoutService.resume(context) else WorkoutService.pause(context)
                    onShowMain()
                },
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(backgroundColor = PausedColor),
                modifier = Modifier.size(ButtonDefaults.LargeButtonSize)
            ) {
                Icon(
                    painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause),
                    contentDescription = if (paused) "Resume" else "Pause",
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp)
                )
            }
            Button(
                onClick = onLap,
                enabled = enabled,
                colors = ButtonDefaults.primaryButtonColors(),
                modifier = Modifier.size(ButtonDefaults.LargeButtonSize)
            ) {
                Icon(painterResource(R.drawable.ic_workout_lap), contentDescription = "Lap", modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                if (confirmFinish) {
                    confirmFinish = false
                    WorkoutService.finish(context)
                } else {
                    confirmFinish = true
                }
            },
            enabled = live.phase != WorkoutState.Phase.ENDING,
            colors = ButtonDefaults.buttonColors(backgroundColor = FinishColor),
            modifier = Modifier.size(ButtonDefaults.DefaultButtonSize)
        ) {
            Icon(painterResource(R.drawable.ic_workout_stop), contentDescription = "Finish", tint = Color.Black, modifier = Modifier.size(24.dp))
        }
        Text(
            text = if (confirmFinish) "Tap again to finish" else "Finish",
            style = MaterialTheme.typography.caption2,
            color = if (confirmFinish) FinishColor else MaterialTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Ambient: white on black, nothing that needs a refresh more than once a minute (the
 * system wakes us that often). Updates still land whenever Health Services sends a batch.
 */
@Composable
fun AmbientWorkoutScreen(live: WorkoutState.Live, tick: Long) {
    val now = if (tick > 0) maxOf(tick, System.currentTimeMillis()) else System.currentTimeMillis()
    val type = live.type ?: WorkoutType.OTHER
    val grey = Color(0xFFB0B0B0)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
            style = MaterialTheme.typography.caption1,
            color = grey
        )
        Text(
            text = if (live.phase == WorkoutState.Phase.PAUSED) "Paused" else type.label,
            style = MaterialTheme.typography.caption2,
            color = grey
        )
        Text(text = WorkoutFormat.durationCoarse(live.activeMs(now)), style = MaterialTheme.typography.title1, color = Color.White)
        if (type.hasDistance || live.distance > 0) {
            Text(text = WorkoutFormat.distance(live.distance), style = MaterialTheme.typography.title2, color = Color.White)
        }
        Text(text = "${WorkoutFormat.bpm(live.hr)} bpm", style = MaterialTheme.typography.title3, color = grey)
    }
}
