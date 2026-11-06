package com.wristlink.ui.health

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wristlink.health.HealthMetrics
import com.wristlink.health.HealthProfile
import com.wristlink.health.LiveHealth
import com.wristlink.ui.theme.SurfaceCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val StepsColor = Color(0xFF4FC3F7)
val TimeColor = Color(0xFF7CE38B)
val EnergyColor = Color(0xFFFF8A65)
val HeartColor = Color(0xFFFF6B81)
val SleepColor = Color(0xFFA48BFF)
val TrackColor = Color(0xFF2A2A40)

/**
 * Loads [load] on a background thread, again whenever the profile or the latest heart rate
 * changes (new data has usually arrived with it). Null while loading.
 */
@Composable
fun <T> rememberHealth(load: (Context) -> T): State<T?> {
    val context = LocalContext.current
    val profile by HealthProfile.get(context).state.collectAsState()
    val reading by LiveHealth.heartRate.collectAsState()
    return produceState<T?>(initialValue = null, profile, reading) {
        value = withContext(Dispatchers.IO) { load(context) }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        content = content
    )
}

@Composable
fun Title(text: String, color: Color = MaterialTheme.colors.primary) {
    Text(text = text, style = MaterialTheme.typography.title3, color = color, textAlign = TextAlign.Center)
}

@Composable
fun Caption(text: String, color: Color = MaterialTheme.colors.onSurfaceVariant, maxLines: Int = 2) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption3,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = maxLines
    )
}

/** Label on the left, value on the right. */
@Composable
fun StatRow(label: String, value: String, color: Color = MaterialTheme.colors.onSurface) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
    ) {
        Text(label, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.caption1, color = color)
    }
}

/** Concentric rings, outermost first. */
@Composable
fun RingStack(fractions: List<Float>, colors: List<Color>, size: Dp, thickness: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val stroke = thickness.toPx()
        fractions.forEachIndexed { i, fraction ->
            val inset = stroke / 2 + i * (stroke * 1.35f)
            val arcSize = Size(this.size.minDimension - inset * 2, this.size.minDimension - inset * 2)
            val topLeft = Offset((this.size.width - arcSize.width) / 2, (this.size.height - arcSize.height) / 2)
            drawArc(TrackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(
                    colors[i], -90f, 360f * fraction.coerceAtMost(1f), false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

fun formatDistance(meters: Double): String =
    if (meters >= 1000) "%.2f km".format(meters / 1000) else "${meters.toInt()} m"

fun formatClock(context: Context, time: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time))

fun formatMinutesOfDay(minutes: Int): String = "%02d:%02d".format(minutes / 60 % 24, minutes % 60)

/** Days back from today, oldest first; [HealthMetrics.startOfDay] handles DST. */
fun lastDays(count: Int, now: Long = System.currentTimeMillis()): List<Long> {
    val today = HealthMetrics.startOfDay(now)
    return (count - 1 downTo 0).map { HealthMetrics.startOfDay(today - it * HealthMetrics.DAY_MS + 12 * 3600 * 1000L) }
}
