package com.watchbridge.tile

import android.text.format.DateFormat
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.watchbridge.R
import com.watchbridge.weather.Sky
import com.watchbridge.weather.WeatherStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Weather from the iPhone, one swipe from the watch face: now, today's range, the next hours,
 * and sunrise and sunset. The tile carries an entry for each coming hour, so it stays right
 * between forecasts without the watch waking up.
 */
class WeatherTileService : TileService() {

    companion object {
        // Bump when the images change, so the system fetches them again
        private const val RESOURCES_VERSION = "1"
        private const val HOUR_MS = 3_600_000L
        private const val TIMELINE_HOURS = 12
        private const val FORECAST_HOURS = 4

        private const val COLOR_PRIMARY = 0xFF4FC3F7.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_DETAIL = 0xFFB0B0B0.toInt()
        private const val COLOR_SUN = 0xFFFFD54F.toInt()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val device = requestParams.deviceConfiguration
        val forecast = WeatherStore.current(this)
        val now = System.currentTimeMillis()

        val timeline = TimelineBuilders.Timeline.Builder()
        if (forecast == null || now >= forecast.validUntil) {
            timeline.addTimelineEntry(entry(empty(device, forecast != null)))
        } else {
            // This hour until the next, then one entry per hour of the forecast
            val starts = listOf(now) + forecast.hoursAfter(now, TIMELINE_HOURS).map { it.time }
            starts.forEachIndexed { index, start ->
                val end = starts.getOrNull(index + 1) ?: (start + HOUR_MS)
                timeline.addTimelineEntry(entry(layout(device, forecast, start), start, end))
            }
        }

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline.build())
            .build()
        return immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION)
        for (icon in Sky.allIcons) {
            resources.addIdToImageMapping(
                imageId(icon),
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(icon).build()
                    )
                    .build()
            )
        }
        return immediateFuture(resources.build())
    }

    private fun entry(
        layout: LayoutElementBuilders.LayoutElement,
        start: Long? = null,
        end: Long? = null
    ): TimelineBuilders.TimelineEntry {
        val builder = TimelineBuilders.TimelineEntry.Builder()
            .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(layout).build())
        if (start != null && end != null) {
            builder.setValidity(
                TimelineBuilders.TimeInterval.Builder().setStartMillis(start).setEndMillis(end).build()
            )
        }
        return builder.build()
    }

    private fun layout(device: DeviceParameters, forecast: WeatherStore.Forecast, at: Long): LayoutElementBuilders.LayoutElement {
        val conditions = forecast.conditions(at) ?: return empty(device, stale = true)
        val today = forecast.day(at)

        val summary = listOfNotNull(
            Sky.label(conditions.code, conditions.isDay),
            today?.let { "${forecast.formatTemp(it.max)} / ${forecast.formatTemp(it.min)}" }
        ).joinToString(" · ")

        val content = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(icon(Sky.icon(conditions.code, conditions.isDay), 30f, COLOR_TEXT))
                    .addContent(spacer(width = 6f))
                    .addContent(text(forecast.formatTemp(conditions.temp), Typography.TYPOGRAPHY_DISPLAY3, COLOR_TEXT))
                    .build()
            )
            .addContent(text(summary, Typography.TYPOGRAPHY_CAPTION1, COLOR_DETAIL))
            .addContent(spacer(height = 6f))
            .addContent(hoursRow(forecast, at))
            .addContent(spacer(height = 6f))
            .addContent(sunRow(forecast, at))
            .build()

        return PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(text(forecast.place ?: "Weather", Typography.TYPOGRAPHY_CAPTION1, COLOR_PRIMARY))
            .setContent(content)
            .build()
    }

    private fun hoursRow(forecast: WeatherStore.Forecast, at: Long): LayoutElementBuilders.LayoutElement {
        val row = LayoutElementBuilders.Row.Builder()
        forecast.hoursAfter(at, FORECAST_HOURS).forEachIndexed { index, hour ->
            if (index > 0) row.addContent(spacer(width = 10f))
            row.addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(text(hourLabel(hour.time), Typography.TYPOGRAPHY_CAPTION3, COLOR_DETAIL))
                    .addContent(icon(Sky.icon(hour.code, forecast.isDay(hour.time)), 16f, COLOR_TEXT))
                    .addContent(text(forecast.formatTemp(hour.temp), Typography.TYPOGRAPHY_CAPTION2, COLOR_TEXT))
                    .build()
            )
        }
        return row.build()
    }

    /** Today's sunrise and sunset, or tomorrow's once today's has passed. */
    private fun sunRow(forecast: WeatherStore.Forecast, at: Long): LayoutElementBuilders.LayoutElement {
        val next = forecast.nextSunEvent(at) ?: return spacer(height = 0f)
        val following = forecast.nextSunEvent(next.time)
        val (sunrise, sunset) = if (next.isSunrise) next to following else following to next
        val row = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        sunrise?.let {
            row.addContent(icon(R.drawable.ic_sunrise, 14f, COLOR_SUN))
                .addContent(spacer(width = 3f))
                .addContent(text(clock(it.time), Typography.TYPOGRAPHY_CAPTION2, COLOR_TEXT))
        }
        if (sunrise != null && sunset != null) row.addContent(spacer(width = 12f))
        sunset?.let {
            row.addContent(icon(R.drawable.ic_sunset, 14f, COLOR_SUN))
                .addContent(spacer(width = 3f))
                .addContent(text(clock(it.time), Typography.TYPOGRAPHY_CAPTION2, COLOR_TEXT))
        }
        return row.build()
    }

    private fun empty(device: DeviceParameters, stale: Boolean): LayoutElementBuilders.LayoutElement =
        PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(text("Weather", Typography.TYPOGRAPHY_CAPTION1, COLOR_PRIMARY))
            .setContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        text(if (stale) "Out of date" else "No weather yet", Typography.TYPOGRAPHY_TITLE2, COLOR_TEXT)
                    )
                    .addContent(
                        text("Open WristLink on your iPhone to send it", Typography.TYPOGRAPHY_CAPTION2, COLOR_DETAIL, maxLines = 2)
                    )
                    .build()
            )
            .build()

    private fun hourLabel(time: Long): String =
        SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "HH" else "ha", Locale.getDefault()).format(Date(time))

    private fun clock(time: Long): String =
        SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(time))

    private fun text(value: String, typography: Int, color: Int, maxLines: Int = 1): Text =
        Text.Builder(this, value)
            .setTypography(typography)
            .setColor(argb(color))
            .setMaxLines(maxLines)
            .build()

    private fun icon(resId: Int, size: Float, color: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Image.Builder()
            .setResourceId(imageId(resId))
            .setWidth(dp(size))
            .setHeight(dp(size))
            .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(color)).build())
            .build()

    private fun spacer(width: Float = 0f, height: Float = 0f): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setWidth(dp(width)).setHeight(dp(height)).build()

    private fun imageId(resId: Int): String = resources.getResourceEntryName(resId)

    private fun <T> immediateFuture(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "immediateFuture"
        }
}
