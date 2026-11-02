package com.watchbridge.health

import kotlin.math.roundToInt

/** The three activity rings, each showing the metric picked in the profile. */
object Rings {

    data class Ring(val label: String, val value: Int, val goal: Int, val unit: String) {
        val fraction: Float get() = if (goal > 0) value.toFloat() / goal else 0f
        val met: Boolean get() = value >= goal
    }

    fun of(day: HealthMetrics.Day, p: HealthProfile.Profile): List<Ring> = listOf(
        when (p.moveRing) {
            HealthProfile.MoveRing.STEPS -> Ring("Steps", day.steps.roundToInt(), p.stepGoal, "steps")
            HealthProfile.MoveRing.FLOORS -> Ring("Floors", day.floors.roundToInt(), p.floorsGoal, "floors")
            HealthProfile.MoveRing.ACTIVE_HOURS -> Ring("Active hours", day.activeHours, p.activeHoursGoal, "h")
        },
        when (p.timeRing) {
            HealthProfile.TimeRing.ACTIVE_TIME -> Ring("Active time", day.activeMinutes, p.activeMinutesGoal, "min")
            HealthProfile.TimeRing.EXERCISE_TIME -> Ring("Exercise time", day.exerciseMinutes, p.exerciseMinutesGoal, "min")
            HealthProfile.TimeRing.RAISED_HR_TIME -> Ring("Raised HR time", day.raisedHrMinutes, p.raisedHrMinutesGoal, "min")
        },
        when (p.energyRing) {
            HealthProfile.EnergyRing.ACTIVE_CALORIES -> Ring("Active calories", day.activeCalories.roundToInt(), p.activeCaloriesGoal, "kcal")
            HealthProfile.EnergyRing.EXERCISE_CALORIES -> Ring("Exercise calories", day.exerciseCalories.roundToInt(), p.exerciseCaloriesGoal, "kcal")
        }
    )
}
