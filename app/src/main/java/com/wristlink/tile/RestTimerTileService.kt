package com.wristlink.tile

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Chip
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.wristlink.timer.RestTimer
import com.wristlink.ui.RestTimerActivity

/**
 * Rest timer tile: one swipe from the watch face to the preset durations, so starting a
 * rest between sets never takes more than two taps. While a timer runs, the tile shows
 * roughly how much is left (tiles don't tick; the countdown lives in the app) and taps
 * open the full timer.
 */
class RestTimerTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"

        private const val COLOR_PRIMARY = 0xFF4FC3F7.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_DETAIL = 0xFFB0B0B0.toInt()

        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(RestTimerTileService::class.java)
        }
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val layout = PrimaryLayout.Builder(requestParams.deviceConfiguration)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(text("Rest Timer", Typography.TYPOGRAPHY_CAPTION1, COLOR_PRIMARY))
            .setContent(
                if (RestTimer.isRunning(this)) runningContent()
                else presetsContent(requestParams.deviceConfiguration)
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            // Re-render now and then so a finished timer's "running" face doesn't linger
            .setFreshnessIntervalMillis(30_000)
            .build()
        return immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun runningContent(): LayoutElementBuilders.LayoutElement {
        val remaining = RestTimer.remainingMs(this)
        val seconds = (remaining + 999) / 1000
        return LayoutElementBuilders.Column.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder().setClickable(openTimer(0)).build()
            )
            .addContent(text("~%d:%02d".format(seconds / 60, seconds % 60), Typography.TYPOGRAPHY_DISPLAY3, COLOR_TEXT))
            .addContent(text("Resting · tap to open", Typography.TYPOGRAPHY_CAPTION2, COLOR_DETAIL))
            .build()
    }

    private fun presetsContent(
        deviceParameters: androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
    ): LayoutElementBuilders.LayoutElement {
        fun presetChip(seconds: Int): Chip =
            Chip.Builder(this, openTimer(seconds), deviceParameters)
                .setPrimaryLabelContent("%d:%02d".format(seconds / 60, seconds % 60))
                .setWidth(dp(62f))
                .setChipColors(ChipColors(argb(0xFF202124.toInt()), argb(COLOR_TEXT)))
                .build()

        fun row(a: Int, b: Int) = LayoutElementBuilders.Row.Builder()
            .addContent(presetChip(a))
            .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
            .addContent(presetChip(b))
            .build()

        return LayoutElementBuilders.Column.Builder()
            .addContent(row(RestTimer.PRESETS_S[0], RestTimer.PRESETS_S[1]))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(row(RestTimer.PRESETS_S[2], RestTimer.PRESETS_S[3]))
            .build()
    }

    private fun text(value: String, typography: Int, color: Int): Text =
        Text.Builder(this, value)
            .setTypography(typography)
            .setColor(argb(color))
            .setMaxLines(1)
            .build()

    /** Opens the timer; a non-zero [durationSeconds] starts that timer on the way in. */
    private fun openTimer(durationSeconds: Int): ModifiersBuilders.Clickable {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(RestTimerActivity::class.java.name)
        if (durationSeconds > 0) {
            activity.addKeyToExtraMapping(
                RestTimerActivity.EXTRA_DURATION_S,
                ActionBuilders.AndroidIntExtra.Builder().setValue(durationSeconds).build()
            )
        }
        return ModifiersBuilders.Clickable.Builder()
            .setId(if (durationSeconds > 0) "start_$durationSeconds" else "open")
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
            .build()
    }

    private fun <T> immediateFuture(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "immediateFuture"
        }
}
