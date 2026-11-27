package com.wristlink.tile

import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.material.Typography
import com.wristlink.R
import com.wristlink.health.HealthHistory
import com.wristlink.health.HealthMetrics
import com.wristlink.health.HealthProfile
import com.wristlink.health.Rings
import com.wristlink.ui.HealthActivity
import java.text.NumberFormat

/** Today's three activity rings with the move ring's count in the middle. */
class ActivityTileService : HealthTileBase() {

    override val screen = HealthActivity.SCREEN_TODAY
    override val icons = listOf(R.drawable.ic_steps, R.drawable.ic_floors, R.drawable.ic_timer)

    override fun layout(device: DeviceParameters): LayoutElementBuilders.LayoutElement {
        val profile = HealthProfile.current(this)
        val now = System.currentTimeMillis()
        val dayStart = HealthMetrics.startOfDay(now)
        val day = HealthMetrics.day(this, dayStart, now)
        val rings = Rings.of(day, profile)
        // No samples yet today (watch just set up, or off the wrist since midnight): "0" would
        // read as a measured nothing
        val noData = HealthHistory.get(this).minutes(dayStart, now).isEmpty()
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
        fun value(v: Int) = if (noData) "–" else format.format(v)
        val center = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(icon(moveIcon(profile.moveRing), 16f, COLOR_STEPS))
            .addContent(text(value(first.value), Typography.TYPOGRAPHY_TITLE1, COLOR_TEXT))
            .addContent(text("/ ${format.format(first.goal)} ${first.unit}", Typography.TYPOGRAPHY_CAPTION2, COLOR_DETAIL))
            .addContent(spacer(height = 4f))
            .addContent(text("${value(rings[1].value)} ${rings[1].unit}", Typography.TYPOGRAPHY_CAPTION2, COLOR_TIME))
        // At large font sizes the inner ring can't hold the last line; the energy ring still shows
        if (!isLargeFont(device)) {
            center.addContent(text("${value(rings[2].value)} ${rings[2].unit}", Typography.TYPOGRAPHY_CAPTION2, COLOR_ENERGY))
        }
        stack.addContent(center.build())
        return stack.build()
    }

    private fun moveIcon(ring: HealthProfile.MoveRing): Int = when (ring) {
        HealthProfile.MoveRing.STEPS -> R.drawable.ic_steps
        HealthProfile.MoveRing.FLOORS -> R.drawable.ic_floors
        HealthProfile.MoveRing.ACTIVE_HOURS -> R.drawable.ic_timer
    }
}
