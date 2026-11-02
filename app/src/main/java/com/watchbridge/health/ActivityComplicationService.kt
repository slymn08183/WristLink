package com.watchbridge.health

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.watchbridge.R
import com.watchbridge.ui.HealthActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Today's steps against the step goal, from WristLink's own history (the same numbers as the
 * Today screen). Goal progress where the face supports it, else an arc or text.
 */
class ActivityComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val now = System.currentTimeMillis()
        val steps = withContext(Dispatchers.IO) {
            val today = HealthMetrics.startOfDay(now)
            HealthHistory.get(this@ActivityComplicationService).minutes(today, now + 1).sumOf { it.steps }
        }
        return build(request.complicationType, steps.roundToInt(), HealthProfile.current(this).stepGoal)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, 4312, 6000)

    private fun build(type: ComplicationType, steps: Int, goal: Int): ComplicationData? {
        val image = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_steps)).build()
        val value = text(formatSteps(steps))
        val description = text("$steps of $goal steps")
        val tap = PendingIntent.getActivity(
            this, 1, HealthActivity.intent(this, HealthActivity.SCREEN_TODAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return when (type) {
            ComplicationType.GOAL_PROGRESS -> if (android.os.Build.VERSION.SDK_INT < 33) null else GoalProgressComplicationData.Builder(steps.toFloat(), goal.toFloat(), description)
                .setText(value).setMonochromaticImage(image).setTapAction(tap).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                steps.coerceAtMost(goal).toFloat(), 0f, goal.toFloat(), description
            ).setText(value).setMonochromaticImage(image).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(value, description)
                .setMonochromaticImage(image).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("$steps / $goal steps"), description)
                .setMonochromaticImage(image).setTapAction(tap).build()
            else -> null
        }
    }

    private fun formatSteps(steps: Int): String =
        if (steps >= 10_000) "${steps / 1000}.${steps % 1000 / 100}k" else steps.toString()

    private fun text(value: String) = PlainComplicationText.Builder(value).build()
}
