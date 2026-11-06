package com.wristlink.health

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.wristlink.R
import com.wristlink.ui.HealthActivity

/**
 * Our own heart rate for any watch face, Samsung's included: the latest reading Health Services
 * delivered (LiveHealth). Pushed by LiveHealth when a new one arrives, so it never freezes the
 * way a face's own heart-rate slot can. Readings older than [STALE_MS] show as "--".
 */
class HeartRateComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val STALE_MS = 30 * 60 * 1000L
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val reading = LiveHealth.latest(this)
        val fresh = reading != null && System.currentTimeMillis() - reading.time < STALE_MS
        val bpm = if (fresh) reading!!.bpm else null
        return build(request.complicationType, bpm) ?: NoDataComplicationData()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, 72)

    private fun build(type: ComplicationType, bpm: Int?): ComplicationData? {
        val image = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_heart)).build()
        val value = bpm?.toString() ?: "--"
        val description = text(if (bpm != null) "Heart rate $bpm bpm" else "No recent heart rate")
        val tap = PendingIntent.getActivity(
            this, 0, HealthActivity.intent(this, HealthActivity.SCREEN_HEART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(value), description)
                .setMonochromaticImage(image)
                .setTitle(text("bpm"))
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("$value bpm"), description)
                .setMonochromaticImage(image)
                .setTitle(text("Heart rate"))
                .setTapAction(tap)
                .build()
            // The arc spans 40–200 bpm
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                (bpm ?: 40).toFloat().coerceIn(40f, 200f), 40f, 200f, description
            )
                .setText(text(value))
                .setMonochromaticImage(image)
                .setTapAction(tap)
                .build()
            else -> null
        }
    }

    private fun text(value: String) = PlainComplicationText.Builder(value).build()
}
