package com.wristlink.tile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.wristlink.R
import com.wristlink.ams.AmsConstants
import com.wristlink.ams.AmsMediaManager
import com.wristlink.service.WristLinkService
import com.wristlink.ui.MediaActivity
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Tile with the iPhone's now playing track, previous / play-pause / next buttons and
 * volume down / up (the bezel only works in the full player; tiles only take taps).
 *
 * Tapping a button reloads the tile with that button's id, which is when the command is
 * sent to the iPhone (tiles can't run code on click otherwise).
 */
class MediaTileService : TileService() {

    companion object {
        // Bump when the images change, so the system fetches them again
        private const val RESOURCES_VERSION = "2"

        private const val ID_PREVIOUS = "previous"
        private const val ID_PLAY_PAUSE = "play_pause"
        private const val ID_NEXT = "next"
        private const val ID_VOLUME_DOWN = "volume_down"
        private const val ID_VOLUME_UP = "volume_up"

        private const val ICON_PLAY = "play"
        private const val ICON_PAUSE = "pause"
        private const val ICON_PREVIOUS = "previous"
        private const val ICON_NEXT = "next"
        private const val ICON_VOLUME_DOWN = "volume_down"
        private const val ICON_VOLUME_UP = "volume_up"
        private const val IMAGE_ART = "art"
        private const val ART_PX = 64

        /** iOS changes the volume in 16 steps. */
        private const val VOLUME_STEP = 1f / 16

        /** Below this screen height the artist line is left out to make room for volume. */
        private const val MIN_HEIGHT_FOR_ARTIST_DP = 200

        // Compact sizes, so the track, the play controls and the volume row all fit
        private const val SKIP_BUTTON_DP = 36f
        private const val PLAY_BUTTON_DP = 44f
        private const val VOLUME_BUTTON_DP = 28f

        private const val COLOR_PRIMARY = 0xFF4FC3F7.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_DETAIL = 0xFFB0B0B0.toInt()

        /** Ask the system to re-render the tile, e.g. when the track or play state changes. */
        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(MediaTileService::class.java)
        }
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val media = WristLinkService.mediaManager
        var state = media?.state?.value ?: AmsMediaManager.MediaState()

        // Nothing known yet: ask the phone again (iOS only sends changes); the tile is
        // refreshed when the answer arrives
        if (state.available && !state.hasPlayer) media?.refresh()

        // A button on the tile was tapped
        when (requestParams.currentState.lastClickableId) {
            ID_PREVIOUS -> media?.previousTrack()
            ID_NEXT -> media?.nextTrack()
            ID_PLAY_PAUSE -> {
                media?.togglePlayPause()
                // Show the new state right away; iOS confirms it a moment later
                state = state.copy(isPlaying = !state.isPlaying)
            }
            ID_VOLUME_DOWN -> {
                media?.volumeDown()
                state = state.copy(volume = state.volume?.let { (it - VOLUME_STEP).coerceAtLeast(0f) })
            }
            ID_VOLUME_UP -> {
                media?.volumeUp()
                state = state.copy(volume = state.volume?.let { (it + VOLUME_STEP).coerceAtMost(1f) })
            }
        }

        val deviceParameters = requestParams.deviceConfiguration
        val openApp = launchMediaApp()

        // Without a track the player's name moves down into the title
        val label = text(
            if (state.title.isNotEmpty()) state.playerName.ifEmpty { "Music Control" } else "Music Control",
            Typography.TYPOGRAPHY_CAPTION1,
            COLOR_PRIMARY
        )
        val hasArt = state.hasPlayer && WristLinkService.albumArt?.art?.value != null
        val layout = PrimaryLayout.Builder(deviceParameters)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(if (hasArt) labelWithArt(label) else label)
            .setContent(
                if (state.hasPlayer) {
                    playerContent(state, openApp, showArtist = deviceParameters.screenHeightDp >= MIN_HEIGHT_FOR_ARTIST_DP)
                } else {
                    emptyContent(state, openApp)
                }
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(resourcesVersion())
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(resourcesVersion())
                .apply { artImage()?.let { addIdToImageMapping(IMAGE_ART, it) } }
                .addIdToImageMapping(ICON_PLAY, image(R.drawable.ic_play))
                .addIdToImageMapping(ICON_PAUSE, image(R.drawable.ic_pause))
                .addIdToImageMapping(ICON_PREVIOUS, image(R.drawable.ic_skip_previous))
                .addIdToImageMapping(ICON_NEXT, image(R.drawable.ic_skip_next))
                .addIdToImageMapping(ICON_VOLUME_DOWN, image(R.drawable.ic_volume_down))
                .addIdToImageMapping(ICON_VOLUME_UP, image(R.drawable.ic_volume_up))
                .build()
        )

    private fun playerContent(
        state: AmsMediaManager.MediaState,
        openApp: ModifiersBuilders.Clickable,
        showArtist: Boolean
    ): LayoutElementBuilders.LayoutElement {
        // Until iOS says which commands the player supports, offer them all
        fun supported(command: Byte) = state.supportedCommands.isEmpty() || state.supports(command)

        val buttons = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        if (supported(AmsConstants.COMMAND_PREVIOUS_TRACK)) {
            buttons.addContent(button(ID_PREVIOUS, ICON_PREVIOUS, "Previous", primary = false))
            buttons.addContent(horizontalSpace(8f))
        }
        buttons.addContent(
            button(
                ID_PLAY_PAUSE,
                if (state.isPlaying) ICON_PAUSE else ICON_PLAY,
                if (state.isPlaying) "Pause" else "Play",
                primary = true
            )
        )
        if (supported(AmsConstants.COMMAND_NEXT_TRACK)) {
            buttons.addContent(horizontalSpace(8f))
            buttons.addContent(button(ID_NEXT, ICON_NEXT, "Next", primary = false))
        }

        return LayoutElementBuilders.Column.Builder()
            .addContent(
                // Tapping the track opens the full player (volume, progress)
                LayoutElementBuilders.Column.Builder()
                    .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
                    .addContent(text(state.title.ifEmpty { state.playerName }, Typography.TYPOGRAPHY_BODY1, COLOR_TEXT))
                    .apply {
                        if (showArtist && state.artist.isNotEmpty()) {
                            addContent(text(state.artist, Typography.TYPOGRAPHY_CAPTION2, COLOR_DETAIL))
                        }
                    }
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(buttons.build())
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(volumeRow(state, ::supported))
            .build()
    }

    /** The images change with the cover, so the version does too. */
    private fun resourcesVersion(): String =
        RESOURCES_VERSION + (WristLinkService.albumArt?.art?.value?.let { "-" + it.generationId } ?: "")

    /** The cover, small and rounded, in front of the player's name. */
    private fun labelWithArt(label: LayoutElementBuilders.LayoutElement): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(IMAGE_ART)
                    .setWidth(dp(20f))
                    .setHeight(dp(20f))
                    .build()
            )
            .addContent(horizontalSpace(6f))
            .addContent(label)
            .build()

    private fun artImage(): ResourceBuilders.ImageResource? {
        val art = WristLinkService.albumArt?.art?.value ?: return null
        // Rounded here: tile images can't clip themselves
        val rounded = Bitmap.createBitmap(ART_PX, ART_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rounded)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val size = ART_PX.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, size, size), size / 4, size / 4, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(art, null, RectF(0f, 0f, size, size), paint)
        val png = ByteArrayOutputStream().use {
            rounded.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        return ResourceBuilders.ImageResource.Builder()
            .setInlineResource(
                ResourceBuilders.InlineImageResource.Builder()
                    .setData(png)
                    .setWidthPx(ART_PX)
                    .setHeightPx(ART_PX)
                    .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                    .build()
            )
            .build()
    }

    /** Small volume down / up buttons with the current level between them. */
    private fun volumeRow(
        state: AmsMediaManager.MediaState,
        supported: (Byte) -> Boolean
    ): LayoutElementBuilders.LayoutElement {
        val row = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        if (supported(AmsConstants.COMMAND_VOLUME_DOWN)) {
            row.addContent(smallButton(ID_VOLUME_DOWN, ICON_VOLUME_DOWN, "Volume down"))
        }
        row.addContent(horizontalSpace(8f))
        row.addContent(
            text(
                state.volume?.let { "${(it * 100).roundToInt()}%" } ?: "Vol",
                Typography.TYPOGRAPHY_CAPTION2,
                COLOR_DETAIL
            )
        )
        row.addContent(horizontalSpace(8f))
        if (supported(AmsConstants.COMMAND_VOLUME_UP)) {
            row.addContent(smallButton(ID_VOLUME_UP, ICON_VOLUME_UP, "Volume up"))
        }
        return row.build()
    }

    private fun emptyContent(
        state: AmsMediaManager.MediaState,
        openApp: ModifiersBuilders.Clickable
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Column.Builder()
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
            .addContent(
                text(
                    if (state.available) "Nothing playing" else "Not connected",
                    Typography.TYPOGRAPHY_TITLE3,
                    COLOR_TEXT
                )
            )
            .addContent(
                text(
                    if (state.available) "Start music on your phone" else "Open WristLink to connect",
                    Typography.TYPOGRAPHY_CAPTION2,
                    COLOR_DETAIL
                )
            )
            .build()

    private fun text(value: String, typography: Int, color: Int): Text =
        Text.Builder(this, value)
            .setTypography(typography)
            .setColor(argb(color))
            .setMaxLines(1)
            .build()

    private fun button(id: String, icon: String, description: String, primary: Boolean): Button {
        val size = if (primary) PLAY_BUTTON_DP else SKIP_BUTTON_DP
        return Button.Builder(this, loadClickable(id))
            .setIconContent(icon, dp(size / 2))
            .setSize(dp(size))
            .setContentDescription(description)
            .setButtonColors(if (primary) ButtonDefaults.PRIMARY_COLORS else ButtonDefaults.SECONDARY_COLORS)
            .build()
    }

    private fun smallButton(id: String, icon: String, description: String): Button =
        Button.Builder(this, loadClickable(id))
            .setIconContent(icon, dp(14f))
            .setSize(dp(VOLUME_BUTTON_DP))
            .setContentDescription(description)
            .setButtonColors(ButtonDefaults.SECONDARY_COLORS)
            .build()

    private fun horizontalSpace(widthDp: Float): LayoutElementBuilders.Spacer =
        LayoutElementBuilders.Spacer.Builder().setWidth(dp(widthDp)).build()

    /** Tapping reloads the tile with [id] as the last clicked id; see [onTileRequest]. */
    private fun loadClickable(id: String): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(id)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()

    private fun launchMediaApp(): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MediaActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

    private fun image(resId: Int): ResourceBuilders.ImageResource =
        ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build()
            )
            .build()

    private fun <T> immediateFuture(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "immediateFuture"
        }
}
