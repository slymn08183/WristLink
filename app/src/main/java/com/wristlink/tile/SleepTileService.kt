package com.wristlink.tile

import android.text.format.DateFormat
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import com.wristlink.R
import com.wristlink.health.HealthMetrics
import com.wristlink.ui.HealthActivity
import java.util.Date

/** Last night: time asleep, bed and wake-up time, and our sleep score. */
class SleepTileService : HealthTileBase() {

    override val screen = HealthActivity.SCREEN_SLEEP
    override val icons = listOf(R.drawable.ic_sleep)
    override val freshnessMs = 60 * 60 * 1000L

    override fun layout(device: DeviceParameters): LayoutElementBuilders.LayoutElement {
        val now = System.currentTimeMillis()
        val night = HealthMetrics.night(this, HealthMetrics.startOfDay(now), now)
        val time = DateFormat.getTimeFormat(this)

        val content = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        if (night == null) {
            content.addContent(icon(R.drawable.ic_sleep, 24f, COLOR_SLEEP))
                .addContent(spacer(height = 4f))
                .addContent(text("No sleep yet", Typography.TYPOGRAPHY_TITLE3, COLOR_TEXT))
                .addContent(text("Wear the watch to bed", Typography.TYPOGRAPHY_CAPTION2, COLOR_DETAIL))
        } else {
            val minutes = night.asleepMs / 60_000
            content.addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(icon(R.drawable.ic_sleep, 18f, COLOR_SLEEP))
                    .addContent(spacer(width = 6f))
                    .addContent(text("${minutes / 60}h ${minutes % 60}m", Typography.TYPOGRAPHY_DISPLAY3, COLOR_TEXT))
                    .build()
            )
                .addContent(
                    text("${time.format(Date(night.start))} – ${if (night.ongoing) "now" else time.format(Date(night.end))}",
                        Typography.TYPOGRAPHY_CAPTION1, COLOR_DETAIL)
                )
                .addContent(spacer(height = 6f))
                .addContent(
                    text(
                        night.score?.let { "Score $it · ${night.band}" } ?: if (night.ongoing) "Sleeping" else "Too short to score",
                        Typography.TYPOGRAPHY_TITLE3, COLOR_SLEEP
                    )
                )
                .addContent(text("${(night.efficiency * 100).toInt()}% asleep in bed", Typography.TYPOGRAPHY_CAPTION2, COLOR_DETAIL))
        }

        return PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(text("Sleep", Typography.TYPOGRAPHY_CAPTION1, COLOR_SLEEP))
            .setContent(content.build())
            .build()
    }
}
