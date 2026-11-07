package com.wristlink.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wristlink.health.HealthHistory
import com.wristlink.health.HealthProfile
import com.wristlink.ui.components.WristLinkScaffold
import com.wristlink.ui.theme.SurfaceCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private class SummaryData(
    val workout: HealthHistory.Workout,
    val laps: List<HealthHistory.Lap>,
    val route: List<DoubleArray>,
    val extra: JSONObject
)

/**
 * A finished workout, right after the finish or from Past workouts: the headline numbers,
 * heart-rate zones, splits per km, laps and a small route.
 */
@Composable
fun WorkoutSummaryScreen(id: Long, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) {
        if (confirmDelete) {
            delay(3000)
            confirmDelete = false
        }
    }
    val recovery by WorkoutState.recovery.collectAsState()
    val measuring = recovery?.workoutId == id
    var data by remember { mutableStateOf<SummaryData?>(null) }
    var missing by remember { mutableStateOf(false) }

    // Reload when the recovery measurement finishes, so its number appears
    LaunchedEffect(id, measuring) {
        val loaded = withContext(Dispatchers.IO) {
            val history = HealthHistory.get(context)
            val w = history.workout(id) ?: return@withContext null
            val route = history.track(id).mapNotNull { p ->
                if (p.lat != null && p.lon != null) doubleArrayOf(p.lat, p.lon) else null
            }
            val extra = w.summary?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            SummaryData(w, history.laps(id), route, extra)
        }
        data = loaded
        missing = loaded == null
    }

    WristLinkScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 48.dp),
            autoCentering = AutoCenteringParams()
        ) {
            val d = data
            if (d == null) {
                item {
                    Text(
                        text = if (missing) "Workout not found" else "Loading…",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
                return@ScalingLazyColumn
            }
            val w = d.workout
            val type = WorkoutType.fromKey(w.type)
            val x = d.extra

            item {
                Text(text = type.label, style = MaterialTheme.typography.title3, color = MaterialTheme.colors.primary)
            }
            item {
                Text(
                    text = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(w.start)),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
            }
            item {
                Text(text = WorkoutFormat.duration(w.activeMs), style = MaterialTheme.typography.display3, color = MaterialTheme.colors.onBackground)
            }
            if (w.distance > 0) {
                item {
                    Text(text = WorkoutFormat.distance(w.distance), style = MaterialTheme.typography.title1, color = MaterialTheme.colors.onBackground)
                }
            }
            item {
                WorkoutPairCard("Calories", "${WorkoutFormat.kcal(w.calories)} kcal", "Total time", WorkoutFormat.duration(w.end - w.start))
            }
            if (w.avgHr != null) {
                item {
                    WorkoutPairCard("Avg heart rate", "${WorkoutFormat.bpm(w.avgHr)} bpm", "Max", "${WorkoutFormat.bpm(w.maxHr)} bpm")
                }
            }
            when (type.effort) {
                WorkoutType.Effort.PACE -> if (x.has("avgPace")) item {
                    WorkoutPairCard(
                        "Avg pace", "${WorkoutFormat.pace(x.optDouble("avgPace"))}/km",
                        "Best", "${WorkoutFormat.pace(x.optDouble("bestPace", Double.NaN))}/km"
                    )
                }
                else -> if (x.has("avgSpeed")) item {
                    WorkoutPairCard(
                        "Avg speed", "${WorkoutFormat.speedKmh(x.optDouble("avgSpeed"))} km/h",
                        "Max", "${WorkoutFormat.speedKmh(x.optDouble("maxSpeed", Double.NaN).takeIf { !it.isNaN() })} km/h"
                    )
                }
            }
            if (x.has("sprints")) {
                item {
                    WorkoutPairCard(
                        "Sprints", "${x.optInt("sprints")}",
                        "Longest", WorkoutFormat.distance(x.optDouble("longestSprint", 0.0))
                    )
                }
                item {
                    WorkoutPairCard(
                        "Sprinting", WorkoutFormat.distance(x.optDouble("sprintDistance", 0.0)),
                        "Over 15 km/h", WorkoutFormat.distance(x.optDouble("highIntensityDistance", 0.0))
                    )
                }
                x.optJSONArray("speedBands")?.let { bands ->
                    item { SectionTitle("Speed") }
                    item {
                        SpeedBands(
                            bandMs = List(4) { bands.optLong(it) * 1000 },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        )
                    }
                }
            }
            if (w.elevationGain > 0 || x.has("maxAlt")) {
                item {
                    WorkoutStatCard(
                        label = "Elevation gain",
                        value = "${w.elevationGain.roundToInt()} m",
                        detail = if (x.has("maxAlt")) "${x.optInt("minAlt")}–${x.optInt("maxAlt")} m altitude" else null
                    )
                }
            }
            if (w.steps > 0 || x.has("avgCadence")) {
                item {
                    WorkoutPairCard(
                        "Steps", w.steps.roundToInt().toString(),
                        "Cadence", if (x.has("avgCadence")) "${x.optInt("avgCadence")} spm" else "–"
                    )
                }
            }
            if (measuring || x.has("recoveryHr")) {
                item {
                    WorkoutStatCard(
                        label = "Recovery heart rate",
                        value = if (x.has("recoveryHr")) "−${x.optInt("recoveryHr")} bpm" else "Measuring…",
                        detail = if (x.has("recoveryHr")) {
                            "${x.optInt("recoveryHrEnd")} → ${x.optInt("recoveryHr60")} bpm in 1 min"
                        } else {
                            "Keep the watch on for a minute"
                        }
                    )
                }
            }

            // Heart-rate zones
            val zones = x.optJSONArray("zones")
            if (zones != null && (0 until zones.length()).any { zones.optLong(it) > 0 }) {
                val maxHr = x.optInt("maxHrUsed", HealthProfile.current(context).maxHr)
                item { SectionTitle("Heart-rate zones") }
                item {
                    ZoneBars(
                        zoneMs = List(HrZones.COUNT) { zones.optLong(it) * 1000 },
                        maxHr = maxHr,
                        current = null,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    )
                }
            }

            // Splits per km
            val splits = x.optJSONArray("splits")
            if (splits != null && splits.length() > 0) {
                item { SectionTitle("Splits") }
                for (i in 0 until splits.length()) {
                    val s = splits.optJSONObject(i) ?: continue
                    item { SplitRow(s, type) }
                }
            }

            // Laps
            if (d.laps.isNotEmpty()) {
                item { SectionTitle("Laps") }
                for (lap in d.laps) {
                    item {
                        LapRow(
                            WorkoutState.Lap(lap.n, lap.start, lap.end, activeMs = lap.end - lap.start, distance = lap.distance, avgHr = lap.avgHr),
                            type
                        )
                    }
                }
            }

            // Route
            if (d.route.size > 1) {
                item { SectionTitle("Route") }
                item {
                    RouteCanvas(
                        d.route,
                        Modifier
                            .fillMaxWidth(0.8f)
                            .height(110.dp)
                            .background(SurfaceCard, RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    )
                }
            }

            // Asks for a second tap, like Finish; also removes it from what the iPhone will get
            item {
                Chip(
                    onClick = {
                        if (!confirmDelete) {
                            confirmDelete = true
                        } else {
                            scope.launch {
                                withContext(Dispatchers.IO) { HealthHistory.get(context).deleteWorkout(id) }
                                onDeleted()
                            }
                        }
                    },
                    label = { Text(if (confirmDelete) "Tap again to delete" else "Delete workout", maxLines = 1) },
                    colors = ChipDefaults.outlinedChipColors(),
                    border = ChipDefaults.outlinedChipBorder(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption1,
        color = MaterialTheme.colors.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** "1  5'12"  148 bpm  +3 m" — km, pace (or speed), average heart rate, climb. */
@Composable
private fun SplitRow(s: JSONObject, type: WorkoutType) {
    val d = s.optDouble("d", 1000.0)
    val secs = s.optDouble("s", 0.0)
    val speed = if (secs > 0) d / secs else null
    val effort = if (type.effort == WorkoutType.Effort.SPEED) "${WorkoutFormat.speedKmh(speed)} km/h"
    else WorkoutFormat.pace(WorkoutFormat.paceOf(speed))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (d >= 999) "${s.optInt("n")}" else "%.2f".format(d / 1000),
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.primary,
            modifier = Modifier.width(28.dp)
        )
        Text(text = effort, style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onSurface, modifier = Modifier.weight(1f))
        Text(
            text = if (s.has("hr")) "${s.optInt("hr")}" else "–",
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
            modifier = Modifier.weight(0.6f),
            textAlign = TextAlign.Center
        )
        Text(
            text = if (s.has("elev")) "%+d m".format(s.optInt("elev")) else "",
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.6f)
        )
    }
}
