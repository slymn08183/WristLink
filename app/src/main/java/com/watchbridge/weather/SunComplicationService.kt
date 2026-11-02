package com.watchbridge.weather

import android.graphics.drawable.Icon
import android.text.format.DateFormat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import com.watchbridge.R
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * The next sunrise or sunset on the watch face: "17:05" under a sunset icon (short), "Sunset
 * 17:05" with the day's length (long), or an arc filling up from the last sunrise or sunset to
 * the next (ranged, the type Samsung's edge slots ask for). Times come with the forecast from the
 * iPhone.
 *
 * The face gets a timeline with an entry at each sunrise and sunset and every half hour between,
 * so it switches and the arc moves on time without the watch waking up for it.
 */
class SunComplicationService : SuspendingTimelineComplicationDataSourceService() {

    companion object {
        private const val STEP_MS = 30 * 60_000L
        private const val TIMELINE_MS = 24 * 60 * 60_000L
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val type = request.complicationType
        val forecast = WeatherStore.current(this)
        val now = System.currentTimeMillis()
        val current = forecast?.let { data(type, it, now) }
            ?: return ComplicationDataTimeline(NoDataComplicationData(), emptyList())

        // Half-hour steps (for the arc) plus every sunrise and sunset in the next day
        val starts = sortedSetOf<Long>()
        var step = (now / STEP_MS + 1) * STEP_MS
        while (step < now + TIMELINE_MS) {
            starts += step
            step += STEP_MS
        }
        var event = forecast.nextSunEvent(now)
        while (event != null && event.time < now + TIMELINE_MS) {
            starts += event.time
            event = forecast.nextSunEvent(event.time)
        }
        val ordered = starts.toList()
        val entries = ordered.mapIndexedNotNull { index, start ->
            val end = ordered.getOrNull(index + 1) ?: return@mapIndexedNotNull null
            val data = data(type, forecast, start) ?: return@mapIndexedNotNull null
            TimelineEntry(TimeInterval(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end)), data)
        }
        return ComplicationDataTimeline(current, entries)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, isSunrise = false, time = "17:05", detail = "Daylight 10h 13m", progress = 0.6f)

    private fun data(type: ComplicationType, forecast: WeatherStore.Forecast, at: Long): ComplicationData? {
        val next = forecast.nextSunEvent(at) ?: return null
        val detail = forecast.day(next.time)?.let {
            val minutes = (it.sunset - it.sunrise) / 60_000
            "Daylight ${minutes / 60}h ${minutes % 60}m"
        }
        return build(type, next.isSunrise, formatTime(next.time), detail, forecast.sunProgress(at) ?: 0f)
    }

    private fun build(
        type: ComplicationType,
        isSunrise: Boolean,
        time: String,
        detail: String?,
        progress: Float
    ): ComplicationData? {
        val image = MonochromaticImage.Builder(
            Icon.createWithResource(this, if (isSunrise) R.drawable.ic_sunrise else R.drawable.ic_sunset)
        ).build()
        val name = if (isSunrise) "Sunrise" else "Sunset"
        val description = text("$name $time")
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(progress, 0f, 1f, description)
                .setText(text(time))
                .setMonochromaticImage(image)
                .build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(time), description)
                .setMonochromaticImage(image)
                .setTitle(text(name))
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("$name $time"), description)
                .setMonochromaticImage(image)
                .apply { if (detail != null) setTitle(text(detail)) }
                .build()
            else -> null
        }
    }

    /** 24-hour "17:05", or "5:05" on a 12-hour watch: sunrise is morning and sunset evening anyway. */
    private fun formatTime(time: Long): String {
        val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
    }

    private fun text(value: String) = PlainComplicationText.Builder(value).build()
}
