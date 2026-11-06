package com.wristlink.ui.health

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wristlink.health.HealthHistory
import com.wristlink.health.HealthMetrics
import com.wristlink.health.HealthProfile
import com.wristlink.health.LiveHealth
import com.wristlink.ui.components.WristLinkScaffold
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

/**
 * Heart rate: the latest reading with "measure now", today's hourly min/max against the resting
 * range for the wearer's age and sex, and resting heart rate over the week.
 */
@Composable
fun HeartScreen() {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val profile by HealthProfile.get(context).state.collectAsState()
    val reading by LiveHealth.heartRate.collectAsState()
    var measuring by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<Int?>(null) }
    val data by rememberHealth { ctx ->
        val now = System.currentTimeMillis()
        lastDays(7, now).map { HealthMetrics.day(ctx, it, now) }
    }

    if (measuring) {
        HeartMeasurement(
            onReading = { live = it },
            onDone = { measuring = false }
        )
    }

    val range = profile.restingRange
    WristLinkScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 48.dp),
            autoCentering = AutoCenteringParams(itemIndex = 0)
        ) {
            item { Title("Heart rate", HeartColor) }
            item {
                Card {
                    val bpm = if (measuring) live else reading?.bpm
                    Text(
                        text = bpm?.let { "$it" } ?: "--",
                        style = MaterialTheme.typography.title1,
                        color = MaterialTheme.colors.onSurface
                    )
                    Caption(
                        when {
                            measuring -> if (live == null) "Measuring… keep still" else "bpm · measuring"
                            reading != null -> "bpm · ${formatClock(context, reading!!.time)}"
                            else -> "No readings yet"
                        }
                    )
                    if (bpm != null && !measuring) {
                        Caption(
                            when {
                                bpm < range.first -> "Lower than your resting range (${range.first}–${range.last})"
                                bpm > range.last -> "Higher than your resting range (${range.first}–${range.last})"
                                else -> "Within your resting range (${range.first}–${range.last})"
                            }
                        )
                    }
                }
            }
            item {
                Chip(
                    onClick = { if (!measuring) { live = null; measuring = true } },
                    label = { Text(if (measuring) "Measuring…" else "Measure now") },
                    colors = if (measuring) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val today = data?.last()
            if (today != null) {
                item {
                    Card {
                        Caption("Today")
                        HeartChart(today, range, Modifier.fillMaxWidth().height(70.dp))
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            listOf("0", "6", "12", "18", "24").forEach { Caption(it) }
                        }
                        if (today.hrMin != null) StatRow("Range", "${today.hrMin}–${today.hrMax} bpm")
                        StatRow("Resting", today.restingHr?.let { "$it bpm" } ?: "Collecting")
                    }
                }
                item {
                    Card {
                        Caption("Resting heart rate · 7 days")
                        data!!.forEach { day ->
                            StatRow(
                                android.text.format.DateFormat.format("EEE d", day.start).toString(),
                                day.restingHr?.let { "$it bpm" } ?: "–",
                                if (day.restingHr != null && day.restingHr !in range) HeartColor else MaterialTheme.colors.onSurface
                            )
                        }
                    }
                }
            }
            item {
                Caption(
                    if (profile.hrAlerts) "Alerts above ${profile.hrHigh} or below ${profile.hrLow} bpm after 10 minutes at rest"
                    else "Heart rate alerts are off (Profile & goals)"
                )
            }
        }
    }
}

/** 24 hourly min–max bars over the resting-range band, with the resting HR as a dashed line. */
@Composable
private fun HeartChart(day: HealthMetrics.Day, range: IntRange, modifier: Modifier) {
    Canvas(modifier) {
        val top = maxOf(120, (day.hrMax ?: 0) + 10).coerceAtMost(220).toFloat()
        val bottom = 40f
        fun y(bpm: Float) = size.height * (1 - ((bpm - bottom) / (top - bottom)).coerceIn(0f, 1f))
        drawRect(
            color = TimeColor.copy(alpha = 0.18f),
            topLeft = Offset(0f, y(range.last.toFloat())),
            size = Size(size.width, y(range.first.toFloat()) - y(range.last.toFloat()))
        )
        day.restingHr?.let {
            drawLine(
                TimeColor, Offset(0f, y(it.toFloat())), Offset(size.width, y(it.toFloat())),
                strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            )
        }
        val slot = size.width / 24
        for (h in 0 until 24) {
            val lo = day.hourlyHrMin[h] ?: continue
            val hi = day.hourlyHrMax[h] ?: continue
            val x = h * slot + slot / 2
            drawLine(HeartColor, Offset(x, y(hi.toFloat())), Offset(x, maxOf(y(lo.toFloat()), y(hi.toFloat()) + 3f)), strokeWidth = slot * 0.55f)
        }
    }
}

/**
 * An on-demand measurement through Health Services' MeasureClient: runs until it has a few
 * steady readings (or 30 s), saves the last one and stops the sensor again.
 */
@Composable
private fun HeartMeasurement(onReading: (Int) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var readings by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val client = HealthServices.getClient(context).measureClient
        var last: Pair<Long, Double>? = null
        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit
            override fun onDataReceived(data: DataPointContainer) {
                val point = data.getData(DataType.HEART_RATE_BPM).lastOrNull { it.value > 0 } ?: return
                last = System.currentTimeMillis() to point.value
                onReading(point.value.toInt())
                readings++
            }
        }
        runCatching { client.registerMeasureCallback(DataType.HEART_RATE_BPM, callback) }
            .onFailure { Log.w("HeartScreen", "Couldn't measure", it); onDone() }
        onDispose {
            runCatching { client.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback) }
            last?.let { (t, bpm) -> save(context, t, bpm) }
        }
    }
    LaunchedEffect(readings) {
        if (readings >= 8) onDone()
    }
    LaunchedEffect(Unit) {
        delay(30_000)
        onDone()
    }
}

private fun save(context: Context, time: Long, bpm: Double) {
    Thread {
        HealthHistory.get(context).addHeartRate(time, bpm)
        com.wristlink.health.HealthStore.get(context).add(com.wristlink.health.HealthStore.HEART_RATE, time, time, bpm)
        LiveHealth.onHeartRate(context, time, bpm)
        LiveHealth.refresh(context, force = true)
    }.start()
}
