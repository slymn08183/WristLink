package com.wristlink.watchface

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
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import com.wristlink.R
import com.wristlink.health.ActivityComplicationService
import com.wristlink.health.HeartRateComplicationService
import com.wristlink.weather.SunComplicationService

/**
 * The WristLink Dashboard face, laid out like Samsung's Digital dashboard: four quarter gauges
 * around the rim (steps, battery, heart rate and blood oxygen to start with, any complication
 * after), the weather from the iPhone at the top, the date, a big time, and a slot under it
 * that starts out as the next sunrise or sunset. Ships inside the app next to the plain face.
 */
class DashboardWatchFaceService : WatchFaceService() {

    companion object {
        // Ids start at 30: earlier builds used 0-3 and 10-13 with other defaults (Samsung Health's
        // heart rate, which freezes), and the watch keeps a slot's data source by id
        private const val EDGE_SLOT_BASE = 30
        private const val BOX_SLOT = 20
        private const val SHEALTH = "com.samsung.android.wear.shealth"
        private const val SHEALTH_COMPLICATIONS = "$SHEALTH.complications"

        // Tap areas along each quarter, and under the time
        private val BOUNDS = mapOf(
            EdgeComplication.Corner.TOP_LEFT to RectF(0.02f, 0.10f, 0.22f, 0.45f),
            EdgeComplication.Corner.TOP_RIGHT to RectF(0.55f, 0.02f, 0.90f, 0.22f),
            EdgeComplication.Corner.BOTTOM_RIGHT to RectF(0.78f, 0.55f, 0.98f, 0.90f),
            EdgeComplication.Corner.BOTTOM_LEFT to RectF(0.10f, 0.78f, 0.45f, 0.98f)
        )
        private val BOX_BOUNDS = RectF(0.27f, 0.66f, 0.73f, 0.78f)

        private val EDGE_TYPES = listOf(
            ComplicationType.RANGED_VALUE,
            ComplicationType.GOAL_PROGRESS,
            ComplicationType.SHORT_TEXT,
            ComplicationType.MONOCHROMATIC_IMAGE,
            ComplicationType.SMALL_IMAGE
        )
        private val BOX_TYPES = listOf(
            ComplicationType.SHORT_TEXT,
            ComplicationType.RANGED_VALUE,
            ComplicationType.LONG_TEXT,
            ComplicationType.MONOCHROMATIC_IMAGE
        )
    }

    private val style = DashboardStyle()

    private val paletteSetting by lazy {
        UserStyleSetting.ListUserStyleSetting(
            UserStyleSetting.Id(FaceTheme.SETTING_ID),
            resources,
            R.string.watch_face_theme,
            R.string.watch_face_theme_description,
            null,
            listOf(
                UserStyleSetting.ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id(DashboardStyle.COLORFUL_ID), resources, R.string.watch_face_theme_colorful, null
                )
            ) + FaceTheme.entries.map {
                UserStyleSetting.ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id(it.id), resources, it.label, null
                )
            },
            listOf(WatchFaceLayer.BASE, WatchFaceLayer.COMPLICATIONS)
        )
    }

    override fun createUserStyleSchema(): UserStyleSchema = UserStyleSchema(listOf(paletteSetting))

    override fun createComplicationSlotsManager(
        currentUserStyleRepository: CurrentUserStyleRepository
    ): ComplicationSlotsManager {
        // Samsung Health's own sources where the watch accepts them as defaults (it does on a
        // Galaxy Watch); they only offer text, which EdgeComplication turns into a gauge
        fun shealth(name: String) = DefaultComplicationDataSourcePolicy(
            ComponentName(SHEALTH, "$SHEALTH_COMPLICATIONS.$name"),
            ComplicationType.SHORT_TEXT,
            SystemDataSources.NO_DATA_SOURCE,
            ComplicationType.NOT_CONFIGURED
        )
        val defaults = mapOf(
            EdgeComplication.Corner.TOP_LEFT to DefaultComplicationDataSourcePolicy(
                ComponentName(this, ActivityComplicationService::class.java),
                ComplicationType.RANGED_VALUE,
                SystemDataSources.DATA_SOURCE_STEP_COUNT,
                ComplicationType.GOAL_PROGRESS
            ),
            EdgeComplication.Corner.TOP_RIGHT to DefaultComplicationDataSourcePolicy(
                SystemDataSources.DATA_SOURCE_WATCH_BATTERY, ComplicationType.RANGED_VALUE
            ),
            EdgeComplication.Corner.BOTTOM_RIGHT to shealth("spo2.BloodOxygenComplicationProviderService"),
            // Our own heart rate (LiveHealth) is pushed on every new reading; Samsung's isn't
            EdgeComplication.Corner.BOTTOM_LEFT to DefaultComplicationDataSourcePolicy(
                ComponentName(this, HeartRateComplicationService::class.java),
                ComplicationType.SHORT_TEXT,
                SystemDataSources.NO_DATA_SOURCE,
                ComplicationType.NOT_CONFIGURED
            )
        )
        val edges = EdgeComplication.Corner.entries.map { corner ->
            ComplicationSlot.createRoundRectComplicationSlotBuilder(
                EDGE_SLOT_BASE + corner.ordinal,
                CanvasComplicationFactory { _, listener -> EdgeComplication(this, corner, style, listener) },
                EDGE_TYPES,
                defaults.getValue(corner),
                ComplicationSlotBounds(BOUNDS.getValue(corner))
            ).build()
        }
        // Our own data source is trusted as a default because it ships in the same app
        val box = ComplicationSlot.createRoundRectComplicationSlotBuilder(
            BOX_SLOT,
            CanvasComplicationFactory { _, listener -> BoxComplication(this, style, listener) },
            BOX_TYPES,
            DefaultComplicationDataSourcePolicy(
                ComponentName(this, SunComplicationService::class.java),
                ComplicationType.SHORT_TEXT,
                SystemDataSources.DATA_SOURCE_SUNRISE_SUNSET,
                ComplicationType.SHORT_TEXT
            ),
            ComplicationSlotBounds(BOX_BOUNDS)
        ).build()
        return ComplicationSlotsManager(edges + box, currentUserStyleRepository)
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace = WatchFace(
        WatchFaceType.DIGITAL,
        DashboardRenderer(
            context = this,
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            complicationSlotsManager = complicationSlotsManager,
            currentUserStyleRepository = currentUserStyleRepository,
            paletteSetting = paletteSetting,
            style = style
        )
    )
}
