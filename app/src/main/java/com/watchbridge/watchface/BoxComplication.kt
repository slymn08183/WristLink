package com.watchbridge.watchface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.wear.watchface.CanvasComplication
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.RenderParameters
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import java.time.ZonedDateTime

/**
 * The slot under the time on the dashboard face: an icon and a value side by side, no frame.
 * Starts out as the next sunrise or sunset.
 */
class BoxComplication(
    private val context: Context,
    private val style: DashboardStyle,
    private val invalidateCallback: CanvasComplication.InvalidateCallback
) : CanvasComplication {

    private var data: ComplicationData = NoDataComplicationData()
    private var icon: Drawable? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        textAlign = Paint.Align.LEFT
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun getData(): ComplicationData = data

    override fun loadData(complicationData: ComplicationData, loadDrawablesAsynchronous: Boolean) {
        data = complicationData
        val image = when (complicationData) {
            is ShortTextComplicationData -> complicationData.monochromaticImage
            is LongTextComplicationData -> complicationData.monochromaticImage
            is RangedValueComplicationData -> complicationData.monochromaticImage
            is MonochromaticImageComplicationData -> complicationData.monochromaticImage
            else -> null
        }
        icon = image?.image?.loadDrawable(context)?.mutate()
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
        val text = when (val d = data) {
            is ShortTextComplicationData -> d.text
            is LongTextComplicationData -> d.text
            is RangedValueComplicationData -> d.text
            else -> null
        }?.getTextAt(context.resources, zonedDateTime.toInstant())?.toString().orEmpty()

        textPaint.textSize = 28 * u
        textPaint.color = if (ambient) Ambient.DIM else Color.WHITE
        // Always On: the value alone (see Ambient)
        val icon = if (ambient) null else icon
        val iconSize = 28 * u
        val gap = if (icon != null && text.isNotEmpty()) 8 * u else 0f
        val width = (if (icon != null) iconSize else 0f) + gap + textPaint.measureText(text)
        var x = bounds.exactCenterX() - width / 2
        val cy = bounds.exactCenterY()

        icon?.let {
            it.setTint(style.boxIconColor)
            it.setBounds(x.toInt(), (cy - iconSize / 2).toInt(), (x + iconSize).toInt(), (cy + iconSize / 2).toInt())
            it.draw(canvas)
            x += iconSize + gap
        }
        if (text.isNotEmpty()) canvas.drawText(text, x, cy + 10 * u, textPaint)
    }

    override fun drawHighlight(canvas: Canvas, bounds: Rect, boundsType: Int, zonedDateTime: ZonedDateTime, color: Int) {
        val u = canvas.width / 450f
        highlightPaint.color = color
        highlightPaint.strokeWidth = 2 * u
        canvas.drawRoundRect(RectF(bounds), 18 * u, 18 * u, highlightPaint)
    }
}
