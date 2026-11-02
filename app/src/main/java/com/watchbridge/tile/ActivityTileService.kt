package com.watchbridge.tile

import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.material.Typography
import com.watchbridge.R
import com.watchbridge.health.HealthMetrics
import com.watchbridge.health.HealthProfile
import com.watchbridge.health.Rings
import com.watchbridge.ui.HealthActivity
import java.text.NumberFormat

/** Today's three activity rings with the step count in the middle. */
class ActivityTileService : HealthTileBase() {

    override val screen = HealthActivity.SCREEN_TODAY
    override val icons = listOf(R.drawable.ic_steps)

    override fun layout(device: DeviceParameters): LayoutElementBuilders.LayoutElement {
        val profile = HealthProfile.current(this)
        val now = System.currentTimeMillis()
        val day = HealthMetrics.day(this, HealthMetrics.startOfDay(now), now)
        val rings = Rings.of(day, profile)
        val colors = listOf(COLOR_STEPS, COLOR_TIME, COLOR_ENERGY)
        val size = minOf(device.screenWidthDp, device.screenHeightDp) * 0.86f

        val stack = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
        rings.forEachIndexed { i, ring ->
            stack.addContent(ring(size - i * 26f, 10f, ring.fraction, colors[i]))
        }
        val first = rings[0]
        val format = NumberFormat.getIntegerInstance()
        stack.addContent(
            LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(icon(R.drawable.ic_steps, 16f, COLOR_STEPS))
                .addContent(text(format.format(first.value), Typography.TYPOGRAPHY_TITLE1, COLOR_TEXT))
                .addContent(text("/ ${format.format(first.goal)} ${first.unit}", Typography.TYPOGRAPHY_CAPTION2, COLOR_DETAIL))
                .addContent(spacer(height = 4f))
                .addContent(text("${rings[1].value} ${rings[1].unit}", Typography.TYPOGRAPHY_CAPTION2, COLOR_TIME))
                .addContent(text("${rings[2].value} ${rings[2].unit}", Typography.TYPOGRAPHY_CAPTION2, COLOR_ENERGY))
                .build()
        )
        return stack.build()
    }
}
