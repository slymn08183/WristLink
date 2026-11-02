package com.watchbridge.weather

import android.graphics.drawable.Icon
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
import java.time.Instant

/**
 * Current weather on the watch face: "18°" with an icon and today's high/low (short), or
 * "18° Partly cloudy" with the place (long).
 *
 * The data comes from the iPhone (WeatherStore). Rather than waking up every hour, it hands the
 * face a timeline with an entry for each coming hour of the forecast.
 */
class WeatherComplicationService : SuspendingTimelineComplicationDataSourceService() {

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val TIMELINE_HOURS = 12
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val type = request.complicationType
        val forecast = WeatherStore.current(this)
        val now = System.currentTimeMillis()
        val current = forecast?.let { data(type, it, now) }
            ?: return ComplicationDataTimeline(NoDataComplicationData(), emptyList())

        val entries = forecast.hoursAfter(now, TIMELINE_HOURS).mapNotNull { hour ->
            val data = data(type, forecast, hour.time) ?: return@mapNotNull null
            TimelineEntry(TimeInterval(Instant.ofEpochMilli(hour.time), Instant.ofEpochMilli(hour.time + HOUR_MS)), data)
        }
        return ComplicationDataTimeline(current, entries)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, temp = "18°", sky = "Partly cloudy", range = "21°/12°", place = "Your city",
            icon = Sky.icon(2, isDay = true))

    private fun data(type: ComplicationType, forecast: WeatherStore.Forecast, at: Long): ComplicationData? {
        val conditions = forecast.conditions(at) ?: return null
        val range = forecast.day(at)?.let { "${forecast.formatTemp(it.max)}/${forecast.formatTemp(it.min)}" }
        return build(
            type,
            temp = forecast.formatTemp(conditions.temp),
            sky = Sky.label(conditions.code, conditions.isDay),
            range = range,
            place = forecast.place,
            icon = Sky.icon(conditions.code, conditions.isDay)
        )
    }

    private fun build(
        type: ComplicationType,
        temp: String,
        sky: String,
        range: String?,
        place: String?,
        icon: Int
    ): ComplicationData? {
        val image = MonochromaticImage.Builder(Icon.createWithResource(this, icon)).build()
        val description = text("$temp, $sky")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(temp), description)
                .setMonochromaticImage(image)
                .apply { if (range != null) setTitle(text(range)) }
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("$temp $sky"), description)
                .setMonochromaticImage(image)
                .apply {
                    val title = listOfNotNull(place, range).joinToString(" · ")
                    if (title.isNotEmpty()) setTitle(text(title))
                }
                .build()
            else -> null
        }
    }

    private fun text(value: String) = PlainComplicationText.Builder(value).build()
}
