package com.watchbridge.ui.health

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Stepper
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.watchbridge.health.HealthProfile
import com.watchbridge.health.HealthProfile.Profile
import com.watchbridge.ui.components.WatchBridgeScaffold

/** A number in the profile: how it reads, its range and step, and how it's stored. */
private class Field(
    val key: String,
    val label: String,
    val range: IntProgression,
    val get: (Profile) -> Int,
    val set: (Profile, Int) -> Profile,
    val format: (Int) -> String = { it.toString() }
)

private val fields = listOf(
    Field("birthYear", "Birth year", 1930..2020, { it.birthYear }, { p, v -> p.copy(birthYear = v) }),
    Field("heightCm", "Height", 120..220, { it.heightCm }, { p, v -> p.copy(heightCm = v) }, { "$it cm" }),
    Field("weightKg", "Weight", 35..200, { it.weightKg }, { p, v -> p.copy(weightKg = v) }, { "$it kg" }),
    Field("stepGoal", "Step goal", 1000..30000 step 500, { it.stepGoal }, { p, v -> p.copy(stepGoal = v) }, { "$it steps" }),
    Field("activeMinutesGoal", "Active time goal", 10..300 step 5, { it.activeMinutesGoal }, { p, v -> p.copy(activeMinutesGoal = v) }, { "$it min" }),
    Field("activeCaloriesGoal", "Active calories goal", 100..2000 step 50, { it.activeCaloriesGoal }, { p, v -> p.copy(activeCaloriesGoalOverride = v) }, { "$it kcal" }),
    Field("sleepGoalMinutes", "Sleep goal", 300..660 step 15, { it.sleepGoalMinutes }, { p, v -> p.copy(sleepGoalMinutes = v) }, { formatDuration(it * 60_000L) }),
    Field("bedtimeMinutes", "Bedtime", 0..1425 step 15, { it.bedtimeMinutes }, { p, v -> p.copy(bedtimeMinutes = v) }, ::formatMinutesOfDay),
    Field("maxHr", "Max heart rate", 120..220, { it.maxHr }, { p, v -> p.copy(maxHrOverride = v) }, { "$it bpm" }),
    Field("hrHigh", "High HR alert", 100..150 step 5, { it.hrHigh }, { p, v -> p.copy(hrHigh = v) }, { "$it bpm" }),
    Field("hrLow", "Low HR alert", 40..60 step 5, { it.hrLow }, { p, v -> p.copy(hrLow = v) }, { "$it bpm" })
)

/** Body profile, goals, ring choices and alerts. Also editable from the iPhone app. */
@Composable
fun ProfileScreen(onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val store = HealthProfile.get(context)
    val profile by store.state.collectAsState()
    val listState = rememberScalingLazyListState()

    WatchBridgeScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 48.dp),
            autoCentering = AutoCenteringParams()
        ) {
            item { Title("Profile & goals") }
            item {
                ValueChip("Sex", if (profile.sex == HealthProfile.Sex.MALE) "Male" else "Female") {
                    store.update { it.copy(sex = if (it.sex == HealthProfile.Sex.MALE) HealthProfile.Sex.FEMALE else HealthProfile.Sex.MALE) }
                }
            }
            fields.take(3).forEach { f -> item { ValueChip(f.label, f.format(f.get(profile))) { onEdit(f.key) } } }

            item { Caption("Rings · tap to change") }
            item {
                ValueChip("Move ring", ringLabel(profile.moveRing.name)) {
                    store.update { it.copy(moveRing = next(it.moveRing, HealthProfile.MoveRing.values())) }
                }
            }
            item {
                ValueChip("Time ring", ringLabel(profile.timeRing.name)) {
                    store.update { it.copy(timeRing = next(it.timeRing, HealthProfile.TimeRing.values())) }
                }
            }
            item {
                ValueChip("Energy ring", ringLabel(profile.energyRing.name)) {
                    store.update { it.copy(energyRing = next(it.energyRing, HealthProfile.EnergyRing.values())) }
                }
            }

            item { Caption("Goals") }
            fields.drop(3).take(5).forEach { f -> item { ValueChip(f.label, f.format(f.get(profile))) { onEdit(f.key) } } }

            item { Caption("Heart rate") }
            item { ValueChip("Max heart rate", "${profile.maxHr} bpm${if (profile.maxHrOverride == null) " (220 − age)" else ""}") { onEdit("maxHr") } }
            item { Toggle("Heart rate alerts", profile.hrAlerts) { v -> store.update { it.copy(hrAlerts = v) } } }
            if (profile.hrAlerts) {
                fields.takeLast(2).forEach { f -> item { ValueChip(f.label, f.format(f.get(profile))) { onEdit(f.key) } } }
            }

            item { Caption("Reminders") }
            item { Toggle("Move reminders", profile.inactivityAlert, "50 min still, 9–17 weekdays") { v -> store.update { it.copy(inactivityAlert = v) } } }
            item { Toggle("Goal nudges", profile.goalNudges, "Halfway, almost, done, badges") { v -> store.update { it.copy(goalNudges = v) } } }
        }
    }
}

/** One number, with − and + (or the rotary crown). */
@Composable
fun EditFieldScreen(field: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = HealthProfile.get(context)
    val f = fields.firstOrNull { it.key == field } ?: run { onDone(); return }
    var value by remember { mutableStateOf(f.get(store.state.value).coerceIn(f.range.first, f.range.last)) }

    Stepper(
        value = value,
        onValueChange = { value = it },
        valueProgression = f.range,
        decreaseIcon = { Text("−", style = MaterialTheme.typography.title1) },
        increaseIcon = { Text("+", style = MaterialTheme.typography.title1) }
    ) {
        Chip(
            onClick = {
                store.update { f.set(it, value) }
                onDone()
            },
            label = { Text(f.format(value), maxLines = 1) },
            secondaryLabel = { Text("${f.label} · tap to save", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = ChipDefaults.primaryChipColors()
        )
    }
}

@Composable
private fun ValueChip(label: String, value: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        secondaryLabel = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    ToggleChip(
        checked = checked,
        onCheckedChange = onChange,
        label = { Text(label, maxLines = 1) },
        secondaryLabel = detail?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        toggleControl = { androidx.wear.compose.material.Switch(checked = checked) },
        colors = ToggleChipDefaults.toggleChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun <E : Enum<E>> next(current: E, values: Array<E>): E = values[(current.ordinal + 1) % values.size]

private fun ringLabel(name: String): String =
    name.lowercase().split('_').joinToString(" ").replaceFirstChar { it.uppercase() }.replace("hr", "HR")
