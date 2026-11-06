package com.wristlink.health

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Calendar

/**
 * The body profile and goals the health screens are built on: BMR, resting range, heart-rate
 * zones and the rings all need age, sex, height and weight. Edited on the watch (Health,
 * Profile & goals) or from the iPhone app (settings key `profile.<field>`).
 */
class HealthProfile private constructor(context: Context) {

    enum class Sex { MALE, FEMALE }

    /** What each activity ring shows. */
    enum class MoveRing { STEPS, FLOORS, ACTIVE_HOURS }
    enum class TimeRing { ACTIVE_TIME, EXERCISE_TIME, RAISED_HR_TIME }
    enum class EnergyRing { ACTIVE_CALORIES, EXERCISE_CALORIES }

    data class Profile(
        /** False until the user has looked at the profile once; screens nudge until then. */
        val configured: Boolean = false,
        val sex: Sex = Sex.MALE,
        val birthYear: Int = 1995,
        val heightCm: Int = 175,
        val weightKg: Int = 75,
        /** Null means 220 − age. */
        val maxHrOverride: Int? = null,
        val stepGoal: Int = 6000,
        val activeMinutesGoal: Int = 90,
        /** Null means the default for the sex (500 / 300). */
        val activeCaloriesGoalOverride: Int? = null,
        val exerciseMinutesGoal: Int = 30,
        val raisedHrMinutesGoal: Int = 30,
        val floorsGoal: Int = 10,
        val activeHoursGoal: Int = 8,
        val sleepGoalMinutes: Int = 8 * 60,
        /** Goal bedtime, minutes after midnight (23:00 by default). */
        val bedtimeMinutes: Int = 23 * 60,
        val moveRing: MoveRing = MoveRing.STEPS,
        val timeRing: TimeRing = TimeRing.ACTIVE_TIME,
        val energyRing: EnergyRing = EnergyRing.ACTIVE_CALORIES,
        val hrAlerts: Boolean = true,
        val hrHigh: Int = 120,
        val hrLow: Int = 40,
        val inactivityAlert: Boolean = true,
        val goalNudges: Boolean = true
    ) {
        val age: Int get() = (Calendar.getInstance().get(Calendar.YEAR) - birthYear).coerceIn(5, 110)
        val maxHr: Int get() = maxHrOverride ?: (220 - age)
        val activeCaloriesGoal: Int get() = activeCaloriesGoalOverride ?: if (sex == Sex.MALE) 500 else 300
        val exerciseCaloriesGoal: Int get() = if (sex == Sex.MALE) 300 else 200

        /** Revised Harris–Benedict, kcal per whole day. */
        val bmrPerDay: Double
            get() = if (sex == Sex.MALE) {
                13.397 * weightKg + 4.799 * heightCm + 88.362 - 5.677 * age
            } else {
                9.247 * weightKg + 3.098 * heightCm + 447.593 - 4.33 * age
            }

        /** "Raised heart rate" starts at the bottom of zone 2 (61 % of max). */
        val raisedHrThreshold: Int get() = (maxHr * 0.61).toInt()

        /** Resting range for the age and sex, bpm (Samsung's table). */
        val restingRange: IntRange
            get() {
                val male = sex == Sex.MALE
                return when {
                    age <= 8 -> if (male) 76..92 else 79..94
                    age <= 11 -> if (male) 70..86 else 76..91
                    age <= 15 -> if (male) 66..83 else 70..87
                    age <= 19 -> if (male) 61..78 else 69..85
                    age <= 39 -> if (male) 61..76 else 66..82
                    age <= 59 -> if (male) 61..77 else 64..79
                    age <= 79 -> if (male) 60..75 else 64..78
                    else -> if (male) 61..78 else 64..77
                }
            }

        fun toJson(): JSONObject = JSONObject()
            .put("configured", configured)
            .put("sex", sex.name.lowercase())
            .put("birthYear", birthYear)
            .put("heightCm", heightCm)
            .put("weightKg", weightKg)
            .put("maxHr", maxHr)
            .put("maxHrOverride", maxHrOverride ?: JSONObject.NULL)
            .put("stepGoal", stepGoal)
            .put("activeMinutesGoal", activeMinutesGoal)
            .put("activeCaloriesGoal", activeCaloriesGoal)
            .put("exerciseMinutesGoal", exerciseMinutesGoal)
            .put("sleepGoalMinutes", sleepGoalMinutes)
            .put("bedtimeMinutes", bedtimeMinutes)
            .put("hrAlerts", hrAlerts)
            .put("hrHigh", hrHigh)
            .put("hrLow", hrLow)
            .put("inactivityAlert", inactivityAlert)
            .put("goalNudges", goalNudges)
    }

    companion object {
        private const val PREFS = "health_profile"

        @Volatile private var instance: HealthProfile? = null
        fun get(context: Context): HealthProfile =
            instance ?: synchronized(this) {
                instance ?: HealthProfile(context.applicationContext).also { instance = it }
            }

        fun current(context: Context): Profile = get(context).state.value
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Profile> = _state.asStateFlow()

    fun update(transform: (Profile) -> Profile) {
        val next = transform(_state.value).copy(configured = true)
        save(next)
        _state.value = next
    }

    /** One field from the iPhone app; returns an error message or null. */
    fun apply(field: String, value: Any?): String? {
        fun int() = (value as? Number)?.toInt()
        fun bool() = value == true
        val p = _state.value
        val next = run {
            when (field) {
                "sex" -> p.copy(sex = if (value.toString().lowercase() == "female") Sex.FEMALE else Sex.MALE)
                "birthYear" -> p.copy(birthYear = int()?.coerceIn(1900, 2025) ?: return "Bad value")
                "heightCm" -> p.copy(heightCm = int()?.coerceIn(100, 250) ?: return "Bad value")
                "weightKg" -> p.copy(weightKg = int()?.coerceIn(30, 250) ?: return "Bad value")
                "maxHrOverride" -> p.copy(maxHrOverride = int()?.coerceIn(120, 230))
                "stepGoal" -> p.copy(stepGoal = int()?.coerceIn(1000, 50000) ?: return "Bad value")
                "activeMinutesGoal" -> p.copy(activeMinutesGoal = int()?.coerceIn(10, 600) ?: return "Bad value")
                "activeCaloriesGoal" -> p.copy(activeCaloriesGoalOverride = int()?.coerceIn(50, 3000))
                "exerciseMinutesGoal" -> p.copy(exerciseMinutesGoal = int()?.coerceIn(5, 300) ?: return "Bad value")
                "sleepGoalMinutes" -> p.copy(sleepGoalMinutes = int()?.coerceIn(240, 720) ?: return "Bad value")
                "bedtimeMinutes" -> p.copy(bedtimeMinutes = int()?.mod(24 * 60) ?: return "Bad value")
                "hrAlerts" -> p.copy(hrAlerts = bool())
                "hrHigh" -> p.copy(hrHigh = int()?.coerceIn(100, 150) ?: return "Bad value")
                "hrLow" -> p.copy(hrLow = int()?.coerceIn(40, 60) ?: return "Bad value")
                "inactivityAlert" -> p.copy(inactivityAlert = bool())
                "goalNudges" -> p.copy(goalNudges = bool())
                else -> return "Unknown profile field $field"
            }
        }
        update { next }
        return null
    }

    private fun load(): Profile {
        val d = Profile()
        fun <E : Enum<E>> enum(key: String, default: E, values: Array<E>): E =
            values.firstOrNull { it.name == prefs.getString(key, null) } ?: default
        fun optInt(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null
        return Profile(
            configured = prefs.getBoolean("configured", false),
            sex = enum("sex", d.sex, Sex.values()),
            birthYear = prefs.getInt("birthYear", d.birthYear),
            heightCm = prefs.getInt("heightCm", d.heightCm),
            weightKg = prefs.getInt("weightKg", d.weightKg),
            maxHrOverride = optInt("maxHrOverride"),
            stepGoal = prefs.getInt("stepGoal", d.stepGoal),
            activeMinutesGoal = prefs.getInt("activeMinutesGoal", d.activeMinutesGoal),
            activeCaloriesGoalOverride = optInt("activeCaloriesGoal"),
            exerciseMinutesGoal = prefs.getInt("exerciseMinutesGoal", d.exerciseMinutesGoal),
            raisedHrMinutesGoal = prefs.getInt("raisedHrMinutesGoal", d.raisedHrMinutesGoal),
            floorsGoal = prefs.getInt("floorsGoal", d.floorsGoal),
            activeHoursGoal = prefs.getInt("activeHoursGoal", d.activeHoursGoal),
            sleepGoalMinutes = prefs.getInt("sleepGoalMinutes", d.sleepGoalMinutes),
            bedtimeMinutes = prefs.getInt("bedtimeMinutes", d.bedtimeMinutes),
            moveRing = enum("moveRing", d.moveRing, MoveRing.values()),
            timeRing = enum("timeRing", d.timeRing, TimeRing.values()),
            energyRing = enum("energyRing", d.energyRing, EnergyRing.values()),
            hrAlerts = prefs.getBoolean("hrAlerts", d.hrAlerts),
            hrHigh = prefs.getInt("hrHigh", d.hrHigh),
            hrLow = prefs.getInt("hrLow", d.hrLow),
            inactivityAlert = prefs.getBoolean("inactivityAlert", d.inactivityAlert),
            goalNudges = prefs.getBoolean("goalNudges", d.goalNudges)
        )
    }

    private fun save(p: Profile) {
        prefs.edit().apply {
            putBoolean("configured", p.configured)
            putString("sex", p.sex.name)
            putInt("birthYear", p.birthYear)
            putInt("heightCm", p.heightCm)
            putInt("weightKg", p.weightKg)
            if (p.maxHrOverride != null) putInt("maxHrOverride", p.maxHrOverride) else remove("maxHrOverride")
            putInt("stepGoal", p.stepGoal)
            putInt("activeMinutesGoal", p.activeMinutesGoal)
            if (p.activeCaloriesGoalOverride != null) putInt("activeCaloriesGoal", p.activeCaloriesGoalOverride) else remove("activeCaloriesGoal")
            putInt("exerciseMinutesGoal", p.exerciseMinutesGoal)
            putInt("raisedHrMinutesGoal", p.raisedHrMinutesGoal)
            putInt("floorsGoal", p.floorsGoal)
            putInt("activeHoursGoal", p.activeHoursGoal)
            putInt("sleepGoalMinutes", p.sleepGoalMinutes)
            putInt("bedtimeMinutes", p.bedtimeMinutes)
            putString("moveRing", p.moveRing.name)
            putString("timeRing", p.timeRing.name)
            putString("energyRing", p.energyRing.name)
            putBoolean("hrAlerts", p.hrAlerts)
            putInt("hrHigh", p.hrHigh)
            putInt("hrLow", p.hrLow)
            putBoolean("inactivityAlert", p.inactivityAlert)
            putBoolean("goalNudges", p.goalNudges)
        }.apply()
    }
}
