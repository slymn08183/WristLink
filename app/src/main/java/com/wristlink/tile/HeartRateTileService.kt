package com.wristlink.tile

import android.text.format.DateUtils
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import com.wristlink.R
import com.wristlink.health.HealthMetrics
import com.wristlink.health.HealthProfile
import com.wristlink.health.LiveHealth
import com.wristlink.ui.HealthActivity

/** The latest heart rate, today's range and resting HR, and a small bar per hour. */
class HeartRateTileService : HealthTileBase() {

    override val screen = HealthActivity.SCREEN_HEART
    override val icons = listOf(R.drawable.ic_heart)
    override val freshnessMs = 10 * 60 * 1000L

    override fun layout(device: DeviceParameters): LayoutElementBuilders.LayoutElement {
        val now = System.currentTimeMillis()
        val reading = LiveHealth.latest(this)
        val day = HealthMetrics.day(this, HealthMetrics.startOfDay(now), now)
        val range = HealthProfile.current(this).restingRange

        val ago = reading?.let {
            DateUtils.getRelativeTimeSpanString(it.time, now, DateUtils.MINUTE_IN_MILLIS).toString()
        } ?: "No readings yet"
        val details = listOfNotNull(
            if (day.hrMin != null && day.hrMax != null) "${day.hrMin}–${day.hrMax} today" else null,
            day.restingHr?.let { "rest $it" }
        ).joinToString(" · ").ifEmpty { "Resting range ${range.first}–${range.last}" }

        val content = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(icon(R.drawable.ic_heart, 22f, COLOR_HEART))
                    .addContent(spacer(width = 6f))
                    .addContent(text(reading?.bpm?.toString() ?: "--", Typography.TYPOGRAPHY_DISPLAY2, COLOR_TEXT))
                    .addContent(spacer(width = 3f))
                    .addContent(text("bpm", Typography.TYPOGRAPHY_CAPTION2, COLOR_DETAIL))
                    .build()
            )
            .addContent(text(ago, Typography.TYPOGRAPHY_CAPTION2, COLOR_DETAIL))
            .addContent(spacer(height = 6f))
            .addContent(bars(day))
            .addContent(spacer(height = 4f))
            .addContent(text(details, Typography.TYPOGRAPHY_CAPTION2, COLOR_TEXT))
            .build()

        return PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(text("Heart rate", Typography.TYPOGRAPHY_CAPTION1, COLOR_HEART))
            .setContent(content)
            .build()
    }

    /** Hourly min–max as floating bars, 40–180 bpm over 36 dp. */
    private fun bars(day: HealthMetrics.Day): LayoutElementBuilders.LayoutElement {
        val height = 36f
        val row = LayoutElementBuilders.Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
        fun y(bpm: Int) = ((bpm - 40).coerceIn(0, 140) / 140f) * height
        for (h in 0 until 24) {
            val lo = day.hourlyHrMin[h]
            val hi = day.hourlyHrMax[h]
            val column = LayoutElementBuilders.Column.Builder()
            if (lo != null && hi != null) {
                val top = height - y(hi)
                val bar = (y(hi) - y(lo)).coerceAtLeast(2f)
                column.addContent(spacer(height = top))
                column.addContent(
                    LayoutElementBuilders.Box.Builder()
                        .setWidth(dp(3f))
                        .setHeight(dp(bar))
                        .setModifiers(
                            ModifiersBuilders.Modifiers.Builder()
                                .setBackground(
                                    ModifiersBuilders.Background.Builder()
                                        .setColor(argb(COLOR_HEART))
                                        .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(1.5f)).build())
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                column.addContent(spacer(height = height - top - bar))
            } else {
                column.addContent(spacer(width = 3f, height = height))
            }
            row.addContent(column.build())
            if (h < 23) row.addContent(spacer(width = 1.5f))
        }
        return row.build()
    }
}
