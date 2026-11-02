package com.watchbridge.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchbridge.health.HealthMetrics
import com.watchbridge.health.HealthProfile
import com.watchbridge.ui.components.WatchBridgeScaffold
import kotlin.math.abs

/**
 * Last night and the week. The watch's own sleep detection gives asleep and awake, not stages,
 * so the score is WristLink's own (duration, efficiency, regularity, timing) and says so.
 */
@Composable
fun SleepScreen() {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val profile by HealthProfile.get(context).state.collectAsState()
    val data by rememberHealth { ctx ->
        val nights = HealthMetrics.nights(ctx, 7)
        nights to HealthMetrics.animal(ctx, nights)
    }
    val today = HealthMetrics.startOfDay(System.currentTimeMillis())

    WatchBridgeScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 48.dp),
            autoCentering = AutoCenteringParams(itemIndex = 0)
        ) {
            item { Title("Sleep", SleepColor) }
            val nights = data?.first
            val night = nights?.lastOrNull()?.takeIf { it.day == today }

            if (nights != null && night == null) {
                item {
                    Card {
                        Text("No sleep last night", style = MaterialTheme.typography.body1)
                        Caption("Wear the watch to bed; it detects sleep by itself")
                    }
                }
            }
            if (night != null) {
                item {
                    Card {
                        Text(formatDuration(night.asleepMs), style = MaterialTheme.typography.title1, color = MaterialTheme.colors.onSurface)
                        Caption("${formatClock(context, night.start)} – ${if (night.ongoing) "now" else formatClock(context, night.end)}")
                        Text(
                            text = night.score?.let { "$it · ${night.band}" } ?: if (night.ongoing) "Sleeping" else "Too short to score",
                            style = MaterialTheme.typography.title3,
                            color = SleepColor
                        )
                        Caption("WristLink sleep score (no sleep stages)")
                    }
                }
                item {
                    Card {
                        StatRow("Time in bed", formatDuration(night.inBedMs))
                        StatRow("Asleep", formatDuration(night.asleepMs))
                        StatRow("Awake", formatDuration(night.awakeMs))
                        StatRow("Efficiency", "${(night.efficiency * 100).toInt()}%")
                        night.regularity?.let { StatRow("Same as the night before", "${(it * 100).toInt()}%") }
                        StatRow(
                            "Bedtime vs goal",
                            if (abs(night.bedtimeOffsetMin) < 5) "On time"
                            else "${abs(night.bedtimeOffsetMin)} min ${if (night.bedtimeOffsetMin > 0) "late" else "early"}"
                        )
                        if (night.napMinutes > 0) StatRow("Naps", "${night.napMinutes} min")
                    }
                }
                item {
                    Card {
                        Caption("Movement and awake times")
                        MovementChart(night, Modifier.fillMaxWidth().height(44.dp))
                        Caption("Sleep stages aren't measured on this watch", maxLines = 2)
                    }
                }
                if (night.avgHr != null) {
                    item {
                        Card {
                            Caption("Heart rate while asleep")
                            StatRow("Average", "${night.avgHr} bpm", HeartColor)
                            night.minHr?.let { StatRow("Lowest", "$it bpm", HeartColor) }
                        }
                    }
                }
            }

            if (!nights.isNullOrEmpty()) {
                item {
                    Card {
                        val goal = profile.sleepGoalMinutes * 60_000L
                        val within = nights.count { !it.ongoing && abs(it.asleepMs - goal) <= 60 * 60_000L }
                        Caption("This week · goal ${formatDuration(goal)}")
                        WeekBars(nights.filter { !it.ongoing }, goal, Modifier.fillMaxWidth().height(50.dp))
                        Caption("$within of ${nights.size} nights within an hour of your goal")
                    }
                }
            }

            val animal = data?.second
            item {
                Card {
                    Caption("Sleep type")
                    if (animal == null) {
                        Caption("Needs 3 nights of sleep")
                    } else {
                        Text(animal.name, style = MaterialTheme.typography.title2, color = SleepColor)
                        Caption(animal.description)
                    }
                }
            }
        }
    }
}

/** Steps per 10 minutes across the night, awake gaps shaded. */
@Composable
private fun MovementChart(night: HealthMetrics.Night, modifier: Modifier) {
    Canvas(modifier) {
        val span = (night.end - night.start).coerceAtLeast(1).toFloat()
        for ((s, e) in night.gaps) {
            val x0 = (s - night.start) / span * size.width
            val x1 = (e - night.start) / span * size.width
            drawRect(HeartColor.copy(alpha = 0.3f), Offset(x0, 0f), Size(maxOf(x1 - x0, 2f), size.height))
        }
        val buckets = night.movement
        val max = maxOf(buckets.maxOrNull() ?: 0, 30).toFloat()
        val slot = size.width / buckets.size.coerceAtLeast(1)
        buckets.forEachIndexed { i, v ->
            val h = maxOf(v / max * size.height, 1.5f)
            drawRect(SleepColor, Offset(i * slot, size.height - h), Size(maxOf(slot - 1f, 1f), h))
        }
    }
}

/** Asleep time per night with the goal as a dashed line. */
@Composable
private fun WeekBars(nights: List<HealthMetrics.Night>, goal: Long, modifier: Modifier) {
    Canvas(modifier) {
        val max = maxOf(nights.maxOfOrNull { it.asleepMs } ?: 0L, goal + 3_600_000L).toFloat()
        val slot = size.width / 7
        val width = slot * 0.55f
        nights.takeLast(7).forEachIndexed { i, n ->
            val h = n.asleepMs / max * size.height
            drawRoundRect(SleepColor, Offset(i * slot + (slot - width) / 2, size.height - h), Size(width, h), CornerRadius(width / 3))
        }
        val gy = size.height - goal / max * size.height
        drawLine(TimeColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
    }
}
