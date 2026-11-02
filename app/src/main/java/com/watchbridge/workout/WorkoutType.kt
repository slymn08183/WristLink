package com.watchbridge.workout

import androidx.health.services.client.data.ExerciseType

/**
 * The workouts the WristLink workout app offers, each mapped to a Health Services
 * [ExerciseType]. [key] is what the workout is stored and synced as (companion protocol,
 * `type`), so don't rename those.
 */
enum class WorkoutType(
    val key: String,
    val label: String,
    val exerciseType: ExerciseType,
    /** Uses GPS: a route, and distance/speed from the satellites. */
    val outdoor: Boolean,
    /** What the main screen shows under the distance. */
    val effort: Effort,
    /** Whether distance means anything for this type (outdoor, or the treadmill's estimate). */
    val hasDistance: Boolean,
    /** Default for the auto-pause switch on the warm-up screen. */
    val autoPauseByDefault: Boolean
) {
    RUN("run", "Run", ExerciseType.RUNNING, outdoor = true, Effort.PACE, hasDistance = true, autoPauseByDefault = true),
    WALK("walk", "Walk", ExerciseType.WALKING, outdoor = true, Effort.PACE, hasDistance = true, autoPauseByDefault = false),
    HIKE("hike", "Hike", ExerciseType.HIKING, outdoor = true, Effort.PACE, hasDistance = true, autoPauseByDefault = false),
    BIKE("bike", "Outdoor bike", ExerciseType.BIKING, outdoor = true, Effort.SPEED, hasDistance = true, autoPauseByDefault = true),
    BIKE_INDOOR("bike_indoor", "Indoor bike", ExerciseType.BIKING_STATIONARY, outdoor = false, Effort.NONE, hasDistance = false, autoPauseByDefault = false),
    TREADMILL("treadmill", "Treadmill", ExerciseType.RUNNING_TREADMILL, outdoor = false, Effort.PACE, hasDistance = true, autoPauseByDefault = false),
    ELLIPTICAL("elliptical", "Elliptical", ExerciseType.ELLIPTICAL, outdoor = false, Effort.NONE, hasDistance = false, autoPauseByDefault = false),
    ROWING("rowing", "Rowing", ExerciseType.ROWING_MACHINE, outdoor = false, Effort.NONE, hasDistance = false, autoPauseByDefault = false),
    OTHER("other", "Other", ExerciseType.WORKOUT, outdoor = false, Effort.NONE, hasDistance = false, autoPauseByDefault = false);

    enum class Effort { PACE, SPEED, NONE }

    companion object {
        fun fromKey(key: String?): WorkoutType = entries.firstOrNull { it.key == key } ?: OTHER

        fun fromExerciseType(type: ExerciseType): WorkoutType? = entries.firstOrNull { it.exerciseType == type }
    }
}
