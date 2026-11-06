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
 * Draws the dashboard face on a 450-unit square scaled to the screen: the weather line at the
 * top (icon, temperature, today's high and low), the date, a big bold time, then the slot under it
 * and the four rim gauges, which draw themselves (BoxComplication, EdgeComplication).
 */
class DashboardRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    private val complicationSlotsManager: ComplicationSlotsManager,
    currentUserStyleRepository: CurrentUserStyleRepository,
    private val paletteSetting: UserStyleSetting.ListUserStyleSetting,
    private val style: DashboardStyle
) : Renderer.CanvasRenderer2<DashboardRenderer.Assets>(
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
        private const val FRAME_INTERVAL_MS = 1000L
        private const val UNIT = 450f
        private const val COLOR_RANGE = 0xFF9AA3B8.toInt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val bold = Typeface.create(Typeface.DEFAULT, 700, false)
    private val dayPaint = textPaint(bold, Paint.Align.LEFT)
    private val dateNumberPaint = textPaint(bold, Paint.Align.LEFT)
    private val timePaint = textPaint(Typeface.create(Typeface.DEFAULT, 800, false), Paint.Align.CENTER).apply {
        letterSpacing = -0.02f
    }
    private val tempPaint = textPaint(bold, Paint.Align.LEFT)
    private val rangePaint = textPaint(Typeface.create(Typeface.DEFAULT, 500, false), Paint.Align.LEFT)

    private val icons = mutableMapOf<Int, Drawable>()
    private val dayFormat = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

    init {
        scope.launch {
            currentUserStyleRepository.userStyle.collect { userStyle ->
                val option = userStyle[paletteSetting] as? UserStyleSetting.ListUserStyleSetting.ListOption
                val id = option?.id?.value?.let { String(it) }
                style.mono = if (id == null || id == DashboardStyle.COLORFUL_ID) null else FaceTheme.of(id).accent
                invalidate()
            }
        }
    }

    override suspend fun createSharedAssets(): Assets = Assets()

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        val u = bounds.width() / UNIT
        val cx = bounds.exactCenterX()

        canvas.drawColor(Color.BLACK)

        // A few pixels further each minute, so no pixel stays lit for hours (Ambient)
        canvas.save()
        Ambient.offset(zonedDateTime).let { (dx, dy) -> canvas.translate(dx * u, dy * u) }

        if (renderParameters.watchFaceLayers.contains(WatchFaceLayer.BASE)) {
            drawWeather(canvas, u, cx, zonedDateTime, ambient)
            drawDate(canvas, u, cx, zonedDateTime, ambient)

            // Always On draws the digits as outlines: a fraction of the pixels stay lit
            timePaint.style = if (ambient) Paint.Style.STROKE else Paint.Style.FILL
            timePaint.strokeWidth = 1.8f * u
            timePaint.color = if (ambient) Ambient.TEXT else Color.WHITE
            timePaint.textSize = 118 * u
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            canvas.drawText(DateTimeFormatter.ofPattern(pattern).format(zonedDateTime), cx, 270 * u, timePaint)
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

    /** "MON 02": the day in the accent color, the date in white. */
    private fun drawDate(canvas: Canvas, u: Float, cx: Float, now: ZonedDateTime, ambient: Boolean) {
        val day = dayFormat.format(now).uppercase(Locale.getDefault()).trimEnd('.')
        val number = "%02d".format(now.dayOfMonth)
        dayPaint.textSize = 36 * u
        dateNumberPaint.textSize = 36 * u
        dayPaint.color = if (ambient) Ambient.DIM else style.accent
        dateNumberPaint.color = if (ambient) Ambient.DIM else Color.WHITE
        val gap = 10 * u
        val width = dayPaint.measureText(day) + gap + dateNumberPaint.measureText(number)
        val left = cx - width / 2
        val baseline = 150 * u
        canvas.drawText(day, left, baseline, dayPaint)
        canvas.drawText(number, left + dayPaint.measureText(day) + gap, baseline, dateNumberPaint)
    }

    /** Icon, temperature and "↑18° ↓7°" in one line; nothing while there is no forecast. */
    private fun drawWeather(canvas: Canvas, u: Float, cx: Float, now: ZonedDateTime, ambient: Boolean) {
        val at = now.toInstant().toEpochMilli()
        val forecast = WeatherStore.current(context) ?: return
        val conditions = forecast.takeIf { at < it.validUntil }?.conditions(at) ?: return
        val today = forecast.day(at)

        val temp = forecast.formatTemp(conditions.temp)
        val range = today?.let { "↑${forecast.formatTemp(it.max)} ↓${forecast.formatTemp(it.min)}" }
        tempPaint.textSize = 28 * u
        tempPaint.color = if (ambient) Ambient.DIM else Color.WHITE
        rangePaint.textSize = 19 * u
        rangePaint.color = if (ambient) Ambient.DIM else COLOR_RANGE

        // No icon on Always On
        val iconSize = if (ambient) 0f else 30 * u
        val gap = 7 * u
        val rangeGap = 10 * u
        val width = iconSize + gap + tempPaint.measureText(temp) +
            (range?.let { rangeGap + rangePaint.measureText(it) } ?: 0f)
        var x = cx - width / 2
        val middle = 92 * u

        if (!ambient) {
            val res = Sky.icon(conditions.code, conditions.isDay)
            val icon = icons.getOrPut(res) { ContextCompat.getDrawable(context, res)!!.mutate() }
            icon.setTint(Sky.tint(conditions.code, conditions.isDay))
            icon.setBounds(x.toInt(), (middle - iconSize / 2).toInt(), (x + iconSize).toInt(), (middle + iconSize / 2).toInt())
            icon.draw(canvas)
            x += iconSize + gap
        }

        canvas.drawText(temp, x, middle + 10 * u, tempPaint)
        x += tempPaint.measureText(temp) + rangeGap
        range?.let { canvas.drawText(it, x, middle + 7 * u, rangePaint) }
    }

    private fun textPaint(typeface: Typeface, align: Paint.Align) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textAlign = align
    }
}
