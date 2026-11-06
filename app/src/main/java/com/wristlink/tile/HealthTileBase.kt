package com.wristlink.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.wristlink.ui.HealthActivity
import java.util.concurrent.Executors

/**
 * Shared plumbing for the health tiles: the layout is built off the main thread (it reads the
 * history database), tapping opens the matching Health screen, and icons come from drawables.
 */
abstract class HealthTileBase : TileService() {

    companion object {
        const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        const val COLOR_DETAIL = 0xFFB0B0B0.toInt()
        const val COLOR_TRACK = 0xFF2A2A40.toInt()
        const val COLOR_STEPS = 0xFF4FC3F7.toInt()
        const val COLOR_TIME = 0xFF7CE38B.toInt()
        const val COLOR_ENERGY = 0xFFFF8A65.toInt()
        const val COLOR_HEART = 0xFFFF6B81.toInt()
        const val COLOR_SLEEP = 0xFFA48BFF.toInt()

        private const val RESOURCES_VERSION = "1"
        private val worker = Executors.newSingleThreadExecutor()
    }

    /** Health screen the tile opens. */
    protected abstract val screen: String
    /** Drawables the layout uses. */
    protected abstract val icons: List<Int>
    /** How long the tile stays right, so the system asks again (ms). */
    protected open val freshnessMs: Long = 15 * 60 * 1000L

    protected abstract fun layout(device: DeviceParameters): LayoutElementBuilders.LayoutElement

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            worker.execute {
                runCatching {
                    val root = LayoutElementBuilders.Box.Builder()
                        .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
                        .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
                        .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openClickable()).build())
                        .addContent(layout(requestParams.deviceConfiguration))
                        .build()
                    TileBuilders.Tile.Builder()
                        .setResourcesVersion(RESOURCES_VERSION)
                        .setFreshnessIntervalMillis(freshnessMs)
                        .setTileTimeline(
                            TimelineBuilders.Timeline.Builder().addTimelineEntry(
                                TimelineBuilders.TimelineEntry.Builder()
                                    .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root).build())
                                    .build()
                            ).build()
                        )
                        .build()
                }.onSuccess { completer.set(it) }.onFailure { completer.setException(it) }
            }
            "healthTile"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION)
        for (icon in icons) {
            resources.addIdToImageMapping(
                imageId(icon),
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(icon).build()
                    )
                    .build()
            )
        }
        return CallbackToFutureAdapter.getFuture { it.set(resources.build()); "resources" }
    }

    private fun openClickable(): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId("open_$screen")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(HealthActivity::class.java.name)
                            .addKeyToExtraMapping(
                                HealthActivity.EXTRA_SCREEN,
                                ActionBuilders.AndroidStringExtra.Builder().setValue(screen).build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    protected fun text(value: String, typography: Int, color: Int, maxLines: Int = 1): Text =
        Text.Builder(this, value)
            .setTypography(typography)
            .setColor(argb(color))
            .setMaxLines(maxLines)
            .build()

    protected fun icon(resId: Int, size: Float, color: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Image.Builder()
            .setResourceId(imageId(resId))
            .setWidth(dp(size))
            .setHeight(dp(size))
            .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(color)).build())
            .build()

    protected fun spacer(width: Float = 0f, height: Float = 0f): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setWidth(dp(width)).setHeight(dp(height)).build()

    /** A ring of [diameter] dp: a dim track with [fraction] of it filled in [color]. */
    protected fun ring(diameter: Float, thickness: Float, fraction: Float, color: Int): LayoutElementBuilders.LayoutElement {
        fun arc(degrees: Float, c: Int) = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(androidx.wear.protolayout.DimensionBuilders.degrees(0f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(androidx.wear.protolayout.DimensionBuilders.degrees(degrees))
                    .setThickness(dp(thickness))
                    .setColor(argb(c))
                    .build()
            )
            .build()
        val box = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(diameter))
            .setHeight(dp(diameter))
            .addContent(arc(360f, COLOR_TRACK))
        if (fraction > 0f) box.addContent(arc(360f * fraction.coerceIn(0.01f, 1f), color))
        return box.build()
    }

    protected fun imageId(resId: Int): String = resources.getResourceEntryName(resId)
}
