package com.watchbridge.watchface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.wear.watchface.CanvasComplication
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.RenderParameters
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.NoPermissionComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One quarter of the dashboard face's rim: the complication's icon in the gap at 12, 3, 6 or 9
 * o'clock, then a thick arc running clockwise from it, filled to the value with a dot at the
 * end, and the value written along the arc next to the dot. Any complication works; ranged and
 * goal values fill the arc, plain text leaves just the track.
 *
 * Geometry is on the face's 450-unit square, in clock degrees (0 at 12, clockwise).
 */
class EdgeComplication(
    private val context: Context,
    private val corner: Corner,
    private val style: DashboardStyle,
    private val invalidateCallback: CanvasComplication.InvalidateCallback
) : CanvasComplication {

    /** Each quarter starts at its icon's clock position and runs a quarter turn clockwise. */
    enum class Corner(val cardinal: Float) {
        TOP_LEFT(270f),
        TOP_RIGHT(0f),
        BOTTOM_RIGHT(90f),
        BOTTOM_LEFT(180f);

        val start: Float get() = cardinal + GAP
        val end: Float get() = cardinal + 90f - GAP
    }

    private companion object {
        /** Room at each quarter's ends for the icons. */
        const val GAP = 11f
        const val RADIUS = 203f
        const val STROKE = 16f
        const val ICON = 22f
        const val TEXT_RADIUS = 172f
        val NUMBER = Regex("""\d[\d,]*(\.\d+)?""")
    }

    private var data: ComplicationData = NoDataComplicationData()
    private var icon: Drawable? = null
    private var iconTintable = true

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        textAlign = Paint.Align.CENTER
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()

    override fun getData(): ComplicationData = data

    override fun loadData(complicationData: ComplicationData, loadDrawablesAsynchronous: Boolean) {
        data = complicationData
        val small = (complicationData as? SmallImageComplicationData)?.smallImage?.image
        iconTintable = small == null
        icon = (monochromaticImage(complicationData)?.image ?: small)?.loadDrawable(context)?.mutate()
        invalidateCallback.onInvalidate()
    }

    override fun render(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        renderParameters: RenderParameters,
        slotId: Int
    ) {
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        val u = canvas.width / 450f
        val center = canvas.width / 2f
        val (startColor, endColor) = if (ambient) DashboardStyle.AMBIENT to DashboardStyle.AMBIENT else style.cornerColors(corner)

        val radius = RADIUS * u
        oval.set(center - radius, center - radius, center + radius, center + radius)
        val sweep = corner.end - corner.start
        if (!ambient) {
            trackPaint.color = DashboardStyle.TRACK
            trackPaint.strokeWidth = STROKE * u
            canvas.drawArc(oval, corner.start - 90, sweep, false, trackPaint)
        }

        // The value: a gradient running along the arc, and a white dot where it ends
        val progress = progress(data)
        var labelAngle = corner.start + sweep / 2
        if (progress != null && progress > 0f) {
            val filled = sweep * progress
            fillPaint.strokeWidth = (if (ambient) 4f else STROKE) * u
            fillPaint.shader = if (ambient) null else gradient(center, startColor, endColor)
            fillPaint.color = endColor
            canvas.drawArc(oval, corner.start - 90, filled, false, fillPaint)
            val endAngle = corner.start + filled
            if (!ambient) {
                val (dx, dy) = point(center, radius, endAngle)
                canvas.drawCircle(dx, dy, 5.5f * u, dotPaint)
            }
            labelAngle = endAngle.coerceIn(corner.start + 14, corner.end - 14)
        }

        // Icon in the gap at the quarter's start
        icon?.let {
            val (ix, iy) = point(center, radius, corner.cardinal)
            val half = ICON / 2 * u
            if (iconTintable) it.setTint(startColor) else it.setTintList(null)
            it.setBounds((ix - half).toInt(), (iy - half).toInt(), (ix + half).toInt(), (iy + half).toInt())
            it.draw(canvas)
        }

        // The value along the arc, turned to follow it and kept upright
        val text = text(data, zonedDateTime.toInstant()) ?: return
        textPaint.color = if (ambient) DashboardStyle.AMBIENT else endColor
        textPaint.textSize = 22 * u
        val (tx, ty) = point(center, TEXT_RADIUS * u, labelAngle)
        val rotation = if (labelAngle % 360 in 90f..270f) labelAngle - 180 else labelAngle
        canvas.save()
        canvas.rotate(rotation, tx, ty)
        canvas.drawText(text, tx, ty + 8 * u, textPaint)
        canvas.restore()
    }

    override fun drawHighlight(canvas: Canvas, bounds: Rect, boundsType: Int, zonedDateTime: ZonedDateTime, color: Int) {
        val u = canvas.width / 450f
        highlightPaint.color = color
        highlightPaint.strokeWidth = 2 * u
        canvas.drawRoundRect(RectF(bounds), 18 * u, 18 * u, highlightPaint)
    }

    private fun point(center: Float, radius: Float, clockDegrees: Float): Pair<Float, Float> {
        val angle = Math.toRadians(clockDegrees.toDouble())
        return (center + radius * sin(angle)).toFloat() to (center - radius * cos(angle)).toFloat()
    }

    /** A sweep gradient from the quarter's start color to its end color. */
    private fun gradient(center: Float, from: Int, to: Int): SweepGradient {
        // Sweep gradients start at 3 o'clock; clock angles start at 12
        val a = ((corner.start - 90 + 360) % 360) / 360f
        val b = ((corner.end - 90 + 360) % 360) / 360f
        return SweepGradient(center, center, intArrayOf(from, from, to, to), floatArrayOf(0f, a, b, 1f))
    }

    private fun monochromaticImage(data: ComplicationData): MonochromaticImage? = when (data) {
        is RangedValueComplicationData -> data.monochromaticImage
        is ShortTextComplicationData -> data.monochromaticImage
        is MonochromaticImageComplicationData -> data.monochromaticImage
        is NoPermissionComplicationData -> data.monochromaticImage
        else -> if (Build.VERSION.SDK_INT >= 33 && data is GoalProgressComplicationData) data.monochromaticImage else null
    }

    private fun progress(data: ComplicationData): Float? = when {
        data is RangedValueComplicationData && data.max > data.min && data.value != RangedValueComplicationData.PLACEHOLDER ->
            ((data.value - data.min) / (data.max - data.min)).coerceIn(0f, 1f)
        Build.VERSION.SDK_INT >= 33 && data is GoalProgressComplicationData && data.targetValue > 0 ->
            (data.value / data.targetValue).coerceIn(0f, 1f)
        data is ShortTextComplicationData -> textProgress(data)
        else -> null
    }

    /**
     * Samsung Health's heart rate and blood oxygen only come as text ("72", "96%"), so the arc is
     * filled from the number: percentages as they are, heart rate across 40 to 200 bpm.
     */
    private fun textProgress(data: ShortTextComplicationData): Float? {
        val text = data.text.getTextAt(context.resources, Instant.now()).toString().trim()
        val number = NUMBER.find(text)?.value?.replace(",", "")?.toFloatOrNull() ?: return null
        return when {
            text.endsWith("%") -> number / 100f
            data.dataSource?.className?.contains("heart", ignoreCase = true) == true -> (number - 40f) / 160f
            else -> null
        }?.coerceIn(0f, 1f)
    }

    /** Short values only: words like "Heart rate" before a first reading are left out. */
    private fun text(data: ComplicationData, now: Instant): String? {
        val text: ComplicationText? = when (data) {
            is ShortTextComplicationData -> data.text
            is RangedValueComplicationData -> data.text
            else -> if (Build.VERSION.SDK_INT >= 33 && data is GoalProgressComplicationData) data.text else null
        }
        val value = text?.getTextAt(context.resources, now)?.toString()
            ?: when {
                data is RangedValueComplicationData && data.value != RangedValueComplicationData.PLACEHOLDER ->
                    data.value.roundToInt().let { if (data.min == 0f && data.max == 100f) "$it%" else "$it" }
                Build.VERSION.SDK_INT >= 33 && data is GoalProgressComplicationData -> "%,d".format(data.value.roundToInt())
                else -> null
            }
        return value?.takeIf { it.any(Char::isDigit) && it.length <= 7 }
    }
}

/** Colors shared by the dashboard face and its quarters; the face updates it from the user style. */
class DashboardStyle {
    /** Null: each quarter keeps its own colors. */
    @Volatile var mono: Int? = null

    val accent: Int get() = mono ?: ACCENT

    /** The sunrise/sunset slot under the time: warm unless a single color is chosen. */
    val boxIconColor: Int get() = mono ?: 0xFFFFC857.toInt()

    /** Start and end of a quarter's gradient. */
    fun cornerColors(corner: EdgeComplication.Corner): Pair<Int, Int> =
        mono?.let { darker(it) to it } ?: COLORFUL.getValue(corner)

    private fun darker(color: Int): Int = Color.rgb(
        (Color.red(color) * 0.55f).toInt(), (Color.green(color) * 0.55f).toInt(), (Color.blue(color) * 0.55f).toInt()
    )

    companion object {
        const val COLORFUL_ID = "colorful"
        const val TRACK = 0xFF262A36.toInt()
        const val AMBIENT = 0xFFA0A0A0.toInt()
        const val ACCENT = 0xFF29A9E0.toInt()

        private val COLORFUL = mapOf(
            EdgeComplication.Corner.TOP_LEFT to (0xFF1DB954.toInt() to 0xFF5BE38C.toInt()),
            EdgeComplication.Corner.TOP_RIGHT to (0xFF1E88E5.toInt() to 0xFF4FC3F7.toInt()),
            EdgeComplication.Corner.BOTTOM_RIGHT to (0xFF8FD3FF.toInt() to 0xFF2F6BFF.toInt()),
            EdgeComplication.Corner.BOTTOM_LEFT to (0xFFFF8A3D.toInt() to 0xFFFF3B3B.toInt())
        )
    }
}
