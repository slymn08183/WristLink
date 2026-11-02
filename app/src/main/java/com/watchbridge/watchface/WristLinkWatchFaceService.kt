package com.watchbridge.watchface

import android.content.ComponentName
import android.graphics.RectF
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasComplicationFactory
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.ComplicationSlotBounds
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy
import androidx.wear.watchface.complications.SystemDataSources
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import com.watchbridge.R
import com.watchbridge.weather.SunComplicationService

/**
 * The WristLink watch face: date, time, the weather from the iPhone across the middle (read
 * straight from WeatherStore, no complication needed), and two round slots below that start out
 * as sunrise/sunset and watch battery and can hold anything.
 *
 * It ships inside the app, so it installs and updates with it. Pick it in the watch face list;
 * touch and hold it to change the accent color or the two slots.
 */
class WristLinkWatchFaceService : WatchFaceService() {

    companion object {
        const val LEFT_SLOT = 0
        const val RIGHT_SLOT = 1

        // Slot bounds on a 0..1 square: two circles side by side below the weather
        private val LEFT_BOUNDS = RectF(0.255f, 0.705f, 0.455f, 0.905f)
        private val RIGHT_BOUNDS = RectF(0.545f, 0.705f, 0.745f, 0.905f)

        private val SLOT_TYPES = listOf(
            ComplicationType.RANGED_VALUE,
            ComplicationType.SHORT_TEXT,
            ComplicationType.MONOCHROMATIC_IMAGE,
            ComplicationType.SMALL_IMAGE
        )
    }

    private val themeSetting by lazy {
        UserStyleSetting.ListUserStyleSetting(
            UserStyleSetting.Id(FaceTheme.SETTING_ID),
            resources,
            R.string.watch_face_theme,
            R.string.watch_face_theme_description,
            null,
            FaceTheme.entries.map {
                UserStyleSetting.ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id(it.id), resources, it.label, null
                )
            },
            listOf(WatchFaceLayer.BASE, WatchFaceLayer.COMPLICATIONS)
        )
    }

    override fun createUserStyleSchema(): UserStyleSchema = UserStyleSchema(listOf(themeSetting))

    override fun createComplicationSlotsManager(
        currentUserStyleRepository: CurrentUserStyleRepository
    ): ComplicationSlotsManager {
        val factory = CanvasComplicationFactory { watchState: WatchState, listener ->
            CanvasComplicationDrawable(ComplicationDrawable(this), watchState, listener)
        }
        val left = ComplicationSlot.createRoundRectComplicationSlotBuilder(
            LEFT_SLOT,
            factory,
            SLOT_TYPES,
            // Our own data sources are trusted as defaults because they ship in the same app
            DefaultComplicationDataSourcePolicy(
                ComponentName(this, SunComplicationService::class.java),
                ComplicationType.RANGED_VALUE,
                SystemDataSources.DATA_SOURCE_SUNRISE_SUNSET,
                ComplicationType.SHORT_TEXT
            ),
            ComplicationSlotBounds(LEFT_BOUNDS)
        ).build()
        val right = ComplicationSlot.createRoundRectComplicationSlotBuilder(
            RIGHT_SLOT,
            factory,
            SLOT_TYPES,
            DefaultComplicationDataSourcePolicy(
                SystemDataSources.DATA_SOURCE_WATCH_BATTERY,
                ComplicationType.RANGED_VALUE
            ),
            ComplicationSlotBounds(RIGHT_BOUNDS)
        ).build()
        return ComplicationSlotsManager(listOf(left, right), currentUserStyleRepository)
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        val renderer = WristLinkRenderer(
            context = this,
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            complicationSlotsManager = complicationSlotsManager,
            currentUserStyleRepository = currentUserStyleRepository,
            themeSetting = themeSetting
        )
        return WatchFace(WatchFaceType.DIGITAL, renderer)
    }
}

/** Accent colors to choose from in the face editor. */
enum class FaceTheme(val id: String, val label: Int, val accent: Int) {
    BLUE("blue", R.string.watch_face_theme_blue, 0xFF4FC3F7.toInt()),
    VIOLET("violet", R.string.watch_face_theme_violet, 0xFFA48BFF.toInt()),
    MINT("mint", R.string.watch_face_theme_mint, 0xFF3FE0C0.toInt()),
    AMBER("amber", R.string.watch_face_theme_amber, 0xFFFFC857.toInt()),
    CORAL("coral", R.string.watch_face_theme_coral, 0xFFFF8A80.toInt()),
    WHITE("white", R.string.watch_face_theme_white, 0xFFE8ECF5.toInt());

    companion object {
        const val SETTING_ID = "theme"
        fun of(id: String?): FaceTheme = entries.firstOrNull { it.id == id } ?: BLUE
    }
}
