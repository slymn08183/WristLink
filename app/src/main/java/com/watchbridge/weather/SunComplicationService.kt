package com.watchbridge.weather

import android.graphics.drawable.Icon
import android.text.format.DateFormat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
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
 * The next sunrise or sunset on the watch face: "17:05" under a sunset icon (short), or
 * "Sunset 17:05" with the day's length (long). Times come with the forecast from the iPhone.
 *
 * Each event gets its own timeline entry, so the face switches from sunset to the next sunrise
 * the moment the sun goes down, without the watch waking up for it.
 */
class SunComplicationService : SuspendingTimelineComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val type = request.complicationType
        val forecast = WeatherStore.current(this)
        val now = System.currentTimeMillis()
        val next = forecast?.nextSunEvent(now)
            ?: return ComplicationDataTimeline(NoDataComplicationData(), emptyList())
        val current = data(type, forecast, next) ?: return null

        // From each event until the one after it, show the one after it
        val entries = mutableListOf<TimelineEntry>()
        var event = next
        while (true) {
            val following = forecast.nextSunEvent(event.time) ?: break
            val data = data(type, forecast, following) ?: break
            entries += TimelineEntry(TimeInterval(Instant.ofEpochMilli(event.time), Instant.ofEpochMilli(following.time)), data)
            event = following
        }
        return ComplicationDataTimeline(current, entries)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, isSunrise = false, time = "17:05", detail = "Daylight 10h 13m")

    private fun data(type: ComplicationType, forecast: WeatherStore.Forecast, event: WeatherStore.SunEvent): ComplicationData? {
        val day = forecast.day(event.time)
        val detail = day?.let {
            val minutes = (it.sunset - it.sunrise) / 60_000
            "Daylight ${minutes / 60}h ${minutes % 60}m"
        }
        return build(type, event.isSunrise, formatTime(event.time), detail)
    }

    private fun build(type: ComplicationType, isSunrise: Boolean, time: String, detail: String?): ComplicationData? {
        val image = MonochromaticImage.Builder(
            Icon.createWithResource(this, if (isSunrise) R.drawable.ic_sunrise else R.drawable.ic_sunset)
        ).build()
        val name = if (isSunrise) "Sunrise" else "Sunset"
        val description = text("$name $time")
        return when (type) {
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
