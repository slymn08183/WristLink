package com.wristlink.workout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wristlink.ui.theme.SurfaceCard
import kotlin.math.cos
import kotlin.math.max

/** Green / amber / red dot for the GPS and heart-rate signal. */
@Composable
fun SignalDot(signal: WorkoutState.Signal, modifier: Modifier = Modifier) {
    val color = when (signal) {
        WorkoutState.Signal.READY -> Color(0xFF81C784)
        WorkoutState.Signal.ACQUIRING, WorkoutState.Signal.UNKNOWN -> Color(0xFFFFD54F)
        WorkoutState.Signal.UNAVAILABLE, WorkoutState.Signal.OFF_BODY -> Color(0xFFEF5350)
        WorkoutState.Signal.NOT_USED -> Color(0xFF757575)
    }
    Box(modifier.size(8.dp).background(color, CircleShape))
}

fun signalText(signal: WorkoutState.Signal): String = when (signal) {
    WorkoutState.Signal.READY -> "Ready"
    WorkoutState.Signal.ACQUIRING, WorkoutState.Signal.UNKNOWN -> "Searching…"
    WorkoutState.Signal.UNAVAILABLE -> "Unavailable"
    WorkoutState.Signal.OFF_BODY -> "Not on wrist"
    WorkoutState.Signal.NOT_USED -> "Not used"
}

/**
 * Time in each heart-rate zone as five bars, Z5 on top. [current] (1..5) is highlighted;
 * pass null when nothing is live.
 */
@Composable
fun ZoneBars(zoneMs: List<Long>, maxHr: Int, current: Int?, modifier: Modifier = Modifier) {
    val longest = max(1L, zoneMs.maxOrNull() ?: 0L)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (z in HrZones.COUNT downTo 1) {
            val ms = zoneMs.getOrElse(z - 1) { 0L }
            val highlighted = current == z
            val range = HrZones.range(z, maxHr)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Z$z",
                    style = MaterialTheme.typography.caption2,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                    color = if (highlighted) HrZones.colors[z - 1] else MaterialTheme.colors.onSurfaceVariant,
                    modifier = Modifier.width(22.dp)
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(if (highlighted) 12.dp else 8.dp)
                        .background(SurfaceCard, RoundedCornerShape(4.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((ms.toFloat() / longest).coerceIn(0f, 1f))
                            .background(HrZones.colors[z - 1].copy(alpha = if (current == null || highlighted) 1f else 0.6f), RoundedCornerShape(4.dp))
                    )
                }
                Text(
                    text = if (ms > 0) WorkoutFormat.duration(ms) else "${range.first}+",
                    style = MaterialTheme.typography.caption3,
                    color = if (highlighted) MaterialTheme.colors.onSurface else MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(40.dp)
                )
            }
        }
    }
}

/**
 * The GPS track as a line, no map tiles: projected flat (fine at workout scale), scaled to fit,
 * north up. Green dot at the start, [endColor] dot at the last point.
 */
@Composable
fun RouteCanvas(points: List<DoubleArray>, modifier: Modifier = Modifier, endColor: Color = MaterialTheme.colors.primary) {
    val lineColor = MaterialTheme.colors.primary
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val lat0 = Math.toRadians(points.sumOf { it[0] } / points.size)
        val k = cos(lat0)
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in points) {
            val x = p[1] * k
            val y = p[0]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        // At least ~50 m across so a standing start isn't blown up into noise
        val span = max(max(maxX - minX, maxY - minY), 0.0005)
        val scale = minOf(size.width, size.height) / span
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        fun at(p: DoubleArray) = Offset(
            (size.width / 2 + (p[1] * k - cx) * scale).toFloat(),
            (size.height / 2 - (p[0] - cy) * scale).toFloat()
        )
        if (points.size > 1) {
            val path = Path()
            points.forEachIndexed { i, p ->
                val o = at(p)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawCircle(Color(0xFF81C784), radius = 4.dp.toPx(), center = at(points.first()))
        drawCircle(endColor, radius = 4.dp.toPx(), center = at(points.last()))
    }
}

/** Label, big value, optional detail — the summary's cards, like the Connection stats. */
@Composable
fun WorkoutStatCard(label: String, value: String, detail: String? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.title2, color = MaterialTheme.colors.onSurface)
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/** Two numbers side by side inside one card. */
@Composable
fun WorkoutPairCard(leftLabel: String, left: String, rightLabel: String, right: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = leftLabel, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
            Text(text = left, style = MaterialTheme.typography.title3, color = MaterialTheme.colors.onSurface)
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = rightLabel, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
            Text(text = right, style = MaterialTheme.typography.title3, color = MaterialTheme.colors.onSurface)
        }
    }
}
