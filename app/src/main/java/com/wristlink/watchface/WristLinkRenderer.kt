package com.wristlink.watchface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationStyle
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import com.wristlink.weather.Sky
import com.wristlink.weather.WeatherStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Draws the WristLink face on a 450-unit square scaled to the screen: the date, the time, then the
 * weather (icon and temperature, conditions and today's range) and the two complication slots.
 * Pure black background and no seconds, so the screen and the redraws stay cheap.
 */
class WristLinkRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    private val complicationSlotsManager: ComplicationSlotsManager,
    currentUserStyleRepository: CurrentUserStyleRepository,
    private val themeSetting: UserStyleSetting.ListUserStyleSetting
) : Renderer.CanvasRenderer2<WristLinkRenderer.Assets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    CanvasType.HARDWARE,
    FRAME_INTERVAL_MS,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false
) {

    class Assets : SharedAssets {
        override fun onDestroy() = Unit
    }

    companion object {
        /** Minutes are the smallest unit shown, but a frame a second keeps the minute change prompt. */
        private const val FRAME_INTERVAL_MS = 1000L
        private const val UNIT = 450f

        private const val COLOR_DETAIL = 0xFFB4BAC8.toInt()
        private const val COLOR_HINT = 0xFF7D8396.toInt()
        private const val COLOR_CARD = 0xFF151A2B.toInt()
        private const val COLOR_TRACK = 0xFF2A3047.toInt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var theme = FaceTheme.BLUE

    private val datePaint = textPaint(Typeface.create(Typeface.DEFAULT, 600, false), Paint.Align.CENTER).apply {
        letterSpacing = 0.12f
    }
    private val timePaint = textPaint(Typeface.create(Typeface.DEFAULT, 300, false), Paint.Align.CENTER)
    private val tempPaint = textPaint(Typeface.create(Typeface.DEFAULT, 500, false), Paint.Align.LEFT)
    private val detailPaint = textPaint(Typeface.DEFAULT, Paint.Align.CENTER)

    private val icons = mutableMapOf<Int, Drawable>()
    private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    init {
        scope.launch {
            currentUserStyleRepository.userStyle.collect { style ->
                val option = style[themeSetting] as? UserStyleSetting.ListUserStyleSetting.ListOption
                theme = FaceTheme.of(option?.id?.value?.let { String(it) })
                styleComplications()
                invalidate()
            }
        }
    }

    override suspend fun createSharedAssets(): Assets = Assets()

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        val scale = bounds.width() / UNIT
        val cx = bounds.exactCenterX()
        val accent = if (ambient) Ambient.DIM else theme.accent

        canvas.drawColor(Color.BLACK)

        // A few pixels further each minute, so no pixel stays lit for hours (Ambient)
        canvas.save()
        Ambient.offset(zonedDateTime).let { (dx, dy) -> canvas.translate(dx * scale, dy * scale) }

        if (renderParameters.watchFaceLayers.contains(WatchFaceLayer.BASE)) {
            datePaint.color = if (ambient) Ambient.DIM else accent
            datePaint.textSize = 24 * scale
            canvas.drawText(dateFormat.format(zonedDateTime).uppercase(Locale.getDefault()), cx, 92 * scale, datePaint)

            // Always On draws the digits as outlines: a fraction of the pixels stay lit
            timePaint.style = if (ambient) Paint.Style.STROKE else Paint.Style.FILL
            timePaint.strokeWidth = 1.6f * scale
            timePaint.color = if (ambient) Ambient.TEXT else Color.WHITE
            timePaint.textSize = 108 * scale
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            canvas.drawText(DateTimeFormatter.ofPattern(pattern).format(zonedDateTime), cx, 196 * scale, timePaint)

            drawWeather(canvas, scale, cx, zonedDateTime, ambient, accent)
        }

        if (renderParameters.watchFaceLayers.contains(WatchFaceLayer.COMPLICATIONS)) {
            for (slot in complicationSlotsManager.complicationSlots.values) {
                if (slot.enabled) slot.render(canvas, zonedDateTime, renderParameters)
            }
        }
        canvas.restore()
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        renderParameters.highlightLayer?.let { canvas.drawColor(it.backgroundTint) }
        for (slot in complicationSlotsManager.complicationSlots.values) {
            if (slot.enabled) slot.renderHighlightLayer(canvas, zonedDateTime, renderParameters)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Icon and temperature as one centered row, then the conditions and today's range. */
    private fun drawWeather(canvas: Canvas, scale: Float, cx: Float, now: ZonedDateTime, ambient: Boolean, accent: Int) {
        val at = now.toInstant().toEpochMilli()
        val forecast = WeatherStore.current(context)
        val conditions = forecast?.takeIf { at < it.validUntil }?.conditions(at)
        detailPaint.textSize = 22 * scale
        if (forecast == null || conditions == null) {
            detailPaint.color = COLOR_HINT
            val hint = if (forecast == null) "Weather comes from your iPhone" else "Weather out of date"
            canvas.drawText(hint, cx, 262 * scale, detailPaint)
            return
        }

        val temp = forecast.formatTemp(conditions.temp)
        tempPaint.color = if (ambient) Ambient.DIM else Color.WHITE
        tempPaint.textSize = (if (ambient) 40 else 60) * scale
        // Always On: the temperature alone, smaller (see Ambient)
        val iconSize = if (ambient) 0f else 52 * scale
        val gap = if (ambient) 0f else 12 * scale
        val left = cx - (iconSize + gap + tempPaint.measureText(temp)) / 2
        val iconTop = 212 * scale

        val icon = icons.getOrPut(Sky.icon(conditions.code, conditions.isDay)) {
            ContextCompat.getDrawable(context, Sky.icon(conditions.code, conditions.isDay))!!.mutate()
        }
        if (!ambient) {
            icon.setTint(accent)
            icon.setBounds(left.toInt(), iconTop.toInt(), (left + iconSize).toInt(), (iconTop + iconSize).toInt())
            icon.draw(canvas)
        }
        canvas.drawText(temp, left + iconSize + gap, 212 * scale + 26 * scale + 21 * scale, tempPaint)
        if (ambient) return

        val today = forecast.day(at)
        val line = listOfNotNull(
            Sky.label(conditions.code, conditions.isDay),
            today?.let { "${forecast.formatTemp(it.max)} / ${forecast.formatTemp(it.min)}" }
        ).joinToString("  ·  ")
        detailPaint.color = if (ambient) Ambient.DIM else COLOR_DETAIL
        canvas.drawText(line, cx, 300 * scale, detailPaint)
    }

    /** Complications follow the accent color; ambient mode drops the card and color. */
    private fun styleComplications() {
        val ring = (3.5f * context.resources.displayMetrics.density).toInt()
        for (slot in complicationSlotsManager.complicationSlots.values) {
            val drawable = (slot.renderer as? CanvasComplicationDrawable)?.drawable ?: continue
            drawable.activeStyle.apply {
                backgroundColor = COLOR_CARD
                borderStyle = ComplicationStyle.BORDER_STYLE_NONE
                rangedValuePrimaryColor = theme.accent
                rangedValueSecondaryColor = COLOR_TRACK
                rangedValueRingWidth = ring
                iconColor = theme.accent
                textColor = Color.WHITE
                titleColor = COLOR_DETAIL
                highlightColor = theme.accent
            }
            // Always On: dim text only, no ring or icon (see Ambient)
            drawable.ambientStyle.apply {
                backgroundColor = Color.BLACK
                borderStyle = ComplicationStyle.BORDER_STYLE_NONE
                rangedValuePrimaryColor = Color.BLACK
                rangedValueSecondaryColor = Color.BLACK
                rangedValueRingWidth = ring
                iconColor = Color.BLACK
                textColor = Ambient.DIM
                titleColor = Ambient.DIM
            }
        }
    }

    private fun textPaint(typeface: Typeface, align: Paint.Align) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textAlign = align
    }
}
