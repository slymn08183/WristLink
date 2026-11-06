package com.wristlink.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wristlink.health.HealthMetrics
import com.wristlink.health.HealthProfile
import com.wristlink.health.Rings
import com.wristlink.ui.components.WristLinkScaffold
import java.text.NumberFormat
import java.util.Calendar
import kotlin.math.roundToInt

/** Daily activity, laid out like Samsung's: rings, activity by hour, the week, totals. */
@Composable
fun TodayScreen() {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val profile by HealthProfile.get(context).state.collectAsState()
    val data by rememberHealth { ctx ->
        val now = System.currentTimeMillis()
        val week = lastDays(7, now).map { HealthMetrics.day(ctx, it, now) }
        week to HealthMetrics.streak(ctx, now)
    }
    val format = NumberFormat.getIntegerInstance()

    WristLinkScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 24.dp, start = 10.dp, end = 10.dp, bottom = 48.dp),
            autoCentering = AutoCenteringParams(itemIndex = 0)
        ) {
            val week = data?.first
            val today = week?.last()
            val rings = today?.let { Rings.of(it, profile) }
            val colors = listOf(StepsColor, TimeColor, EnergyColor)

            item {
                Box(contentAlignment = Alignment.Center) {
                    RingStack(
                        fractions = rings?.map { it.fraction } ?: listOf(0f, 0f, 0f),
                        colors = colors,
                        size = 150.dp,
                        thickness = 11.dp
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = rings?.let { format.format(it[0].value) } ?: "–",
                            style = MaterialTheme.typography.title1,
                            color = MaterialTheme.colors.onSurface
                        )
                        Caption(rings?.let { "/ ${format.format(it[0].goal)} ${it[0].unit}" } ?: "Loading")
                    }
                }
            }

            if (rings != null) {
                item {
                    Card {
                        rings.forEachIndexed { i, ring ->
                            StatRow(ring.label, "${format.format(ring.value)} / ${format.format(ring.goal)} ${ring.unit}", colors[i])
                        }
                    }
                }
            }

            if (today != null) {
                item {
                    Card {
                        Caption("Activity by time of day")
                        HourlyBars(today.hourlySteps, Modifier.fillMaxWidth().height(48.dp))
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            listOf("0", "6", "12", "18", "24").forEach { Caption(it) }
                        }
                    }
                }
                item {
                    Card {
                        StatRow("Total burned", "${today.totalCalories.roundToInt()} kcal")
                        StatRow("Active calories", "${today.activeCalories.roundToInt()} kcal")
                        StatRow("Distance", formatDistance(today.distance))
                        StatRow("Floors", "${today.floors.roundToInt()}")
                        StatRow("Active hours", "${today.activeHours} / ${profile.activeHoursGoal}")
                        StatRow("Exercise", "${today.exerciseMinutes} min")
                        StatRow("Raised heart rate", "${today.raisedHrMinutes} min")
                    }
                }
            }

            if (week != null) {
                item {
                    Card {
                        Caption("This week · all goals met")
                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            week.forEach { day ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val met = day.allGoalsMet(profile)
                                    val stepsMet = day.stepGoalMet(profile)
                                    Box(
                                        Modifier.size(12.dp).background(
                                            when {
                                                met -> TimeColor
                                                stepsMet -> StepsColor
                                                else -> TrackColor
                                            },
                                            CircleShape
                                        )
                                    )
                                    Caption(weekdayLetter(day.start))
                                }
                            }
                        }
                        val streak = data?.second ?: 0
                        Caption(if (streak > 0) "$streak day${if (streak == 1) "" else "s"} in a row at your step goal" else "Reach your step goal to start a streak")
                    }
                }
            }
        }
    }
}

@Composable
fun HourlyBars(values: DoubleArray, modifier: Modifier, color: Color = StepsColor) {
    Canvas(modifier) {
        val max = maxOf(values.maxOrNull() ?: 0.0, 100.0)
        val slot = size.width / values.size
        val barWidth = slot * 0.6f
        values.forEachIndexed { i, v ->
            val h = (v / max).toFloat() * size.height
            drawRoundRect(
                color = if (v > 0) color else TrackColor,
                topLeft = Offset(i * slot + (slot - barWidth) / 2, size.height - maxOf(h, 2f)),
                size = Size(barWidth, maxOf(h, 2f)),
                cornerRadius = CornerRadius(barWidth / 2)
            )
        }
    }
}

private fun weekdayLetter(dayStart: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = dayStart }
    return c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.getDefault())?.take(1) ?: ""
}
