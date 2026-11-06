package com.wristlink.workout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseTypeCapabilities
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.wristlink.R
import com.wristlink.health.HealthHistory
import com.wristlink.ui.components.WristLinkScaffold
import com.wristlink.ui.theme.WristLinkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "Workout" launcher icon. Pick a type, warm up (GPS and heart rate lock on), start; the
 * live screens follow the workout [WorkoutService] runs, so closing this and coming back
 * (or tapping the workout on the watch face) picks up where it was. After the finish it shows
 * the summary; "Past workouts" opens the same summary for older ones.
 */
class WorkoutActivity : ComponentActivity() {

    private var ambient by mutableStateOf(false)
    private var ambientTick by mutableLongStateOf(0L)

    private var afterPermissions: (() -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Go on either way: a workout without GPS (or heart rate) is still a workout
        afterPermissions?.invoke()
        afterPermissions = null
    }

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            ambient = true
            ambientTick = System.currentTimeMillis()
        }

        override fun onUpdateAmbient() {
            ambientTick = System.currentTimeMillis()
        }

        override fun onExitAmbient() {
            ambient = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

        setContent {
            WristLinkTheme {
                WorkoutApp(
                    ambient = ambient,
                    ambientTick = ambientTick,
                    withPermissions = ::withPermissions,
                    onLeave = { moveTaskToBack(true) }
                )
            }
        }
    }

    /** Ask for what a [type] workout needs that isn't granted yet, then run [then]. */
    private fun withPermissions(type: WorkoutType, then: () -> Unit) {
        val wanted = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (type.outdoor) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            then()
        } else {
            afterPermissions = then
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@Composable
private fun WorkoutApp(
    ambient: Boolean,
    ambientTick: Long,
    withPermissions: (WorkoutType, () -> Unit) -> Unit,
    onLeave: () -> Unit
) {
    val context = LocalContext.current
    val live by WorkoutState.live.collectAsState()

    // The app was killed mid-workout but Health Services kept it going: take it back
    LaunchedEffect(Unit) {
        if (WorkoutService.orphanedExercise(context)) WorkoutService.recover(context)
    }

    if (live.inProgress) {
        if (ambient) AmbientWorkoutScreen(live, ambientTick) else LiveWorkoutPager(live, onLeave)
    } else {
        WorkoutMenu(live, withPermissions)
    }
}

@Composable
private fun WorkoutMenu(live: WorkoutState.Live, withPermissions: (WorkoutType, () -> Unit) -> Unit) {
    val context = LocalContext.current
    val navController = rememberSwipeDismissableNavController()
    val finished by WorkoutState.finished.collectAsState()
    // Coming back to a warm-up left with the home button
    val start = remember {
        if (live.phase == WorkoutState.Phase.PREPARING && live.type != null) "warmup/${live.type.key}" else "types"
    }

    // A workout just ended: show what became of it
    LaunchedEffect(finished) {
        when (val f = finished) {
            is WorkoutState.Finished.Saved -> navController.navigate("summary/${f.id}") { popUpTo(start) }
            WorkoutState.Finished.TooShort -> navController.navigate("discarded") { popUpTo(start) }
            null -> return@LaunchedEffect
        }
        WorkoutState.consumeFinished()
    }

    SwipeDismissableNavHost(navController = navController, startDestination = start) {
        composable("types") {
            TypePickerScreen(
                error = live.error,
                onPick = { type ->
                    withPermissions(type) {
                        WorkoutService.prepare(context, type)
                        navController.navigate("warmup/${type.key}")
                    }
                },
                onHistory = { navController.navigate("history") }
            )
        }
        composable("warmup/{type}") { entry ->
            val type = WorkoutType.fromKey(entry.arguments?.getString("type"))
            WarmUpScreen(type)
        }
        composable("summary/{id}") { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
            WorkoutSummaryScreen(id, onDeleted = { navController.popBackStack() })
        }
        composable("history") {
            PastWorkoutsScreen(onOpen = { id -> navController.navigate("summary/$id") })
        }
        composable("discarded") {
            DiscardedScreen()
        }
    }
}

// ---- Type picker ----

@Composable
private fun TypePickerScreen(error: String?, onPick: (WorkoutType) -> Unit, onHistory: () -> Unit) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    // Types this watch can record; null until known (then everything is offered)
    var supported by remember { mutableStateOf<Set<ExerciseType>?>(null) }
    LaunchedEffect(Unit) {
        supported = try {
            HealthServices.getClient(context).exerciseClient.getCapabilitiesAsync().await().supportedExerciseTypes
        } catch (e: Exception) {
            null
        }
    }

    WristLinkScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 40.dp),
            autoCentering = AutoCenteringParams()
        ) {
            item {
                Text(
                    text = "Workout",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center
                )
            }
            if (error != null) {
                item {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
            items(WorkoutType.entries.toList()) { type ->
                val available = supported?.contains(type.exerciseType) ?: true
                Chip(
                    onClick = { onPick(type) },
                    enabled = available,
                    label = { Text(type.label, maxLines = 1) },
                    secondaryLabel = if (available) null else ({ Text("Not on this watch", maxLines = 1) }),
                    icon = {
                        Icon(painterResource(R.drawable.ic_workout), contentDescription = null, modifier = Modifier.width(20.dp))
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Chip(
                    onClick = onHistory,
                    label = { Text("Past workouts", maxLines = 1) },
                    colors = ChipDefaults.outlinedChipColors(),
                    border = ChipDefaults.outlinedChipBorder(),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
        }
    }
}

// ---- Warm-up ----

private const val PREFS = "workout"

@Composable
private fun WarmUpScreen(type: WorkoutType) {
    val context = LocalContext.current
    val live by WorkoutState.live.collectAsState()
    val listState = rememberScalingLazyListState()
    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    var autoPause by remember { mutableStateOf(prefs.getBoolean("autoPause.${type.key}", type.autoPauseByDefault)) }
    var caps by remember { mutableStateOf<ExerciseTypeCapabilities?>(null) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(type) { caps = WorkoutService.capabilities(context, type) }

    // Leaving without starting ends the warm-up (the sensors go back to sleep)
    DisposableEffect(Unit) {
        onDispose { if (!started) WorkoutService.cancel(context) }
    }

    val gpsReady = live.gps == WorkoutState.Signal.READY || live.gps == WorkoutState.Signal.NOT_USED
    WristLinkScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 40.dp),
            autoCentering = AutoCenteringParams()
        ) {
            item {
                Text(text = type.label, style = MaterialTheme.typography.title3, color = MaterialTheme.colors.primary)
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 4.dp)) {
                    if (type.outdoor) SignalRow("GPS", live.gps)
                    SignalRow("Heart rate", live.heartRate)
                }
            }
            if (live.error != null) {
                item {
                    Text(
                        text = live.error!!,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
            if (live.otherAppActive) {
                item {
                    Text(
                        text = "Another app's workout is running; starting stops it",
                        style = MaterialTheme.typography.caption3,
                        color = Color(0xFFFFD54F),
                        textAlign = TextAlign.Center
                    )
                }
            }
            item {
                Chip(
                    onClick = {
                        started = true
                        WorkoutService.start(context, type, autoPause)
                    },
                    label = { Text("Start", maxLines = 1) },
                    // Starting before the fix is fine; the route just begins once it's there
                    secondaryLabel = if (gpsReady) null else ({ Text("GPS still searching", maxLines = 1) }),
                    colors = if (gpsReady) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (caps?.supportsAutoPauseAndResume == true) {
                item {
                    ToggleChip(
                        checked = autoPause,
                        onCheckedChange = {
                            autoPause = it
                            prefs.edit().putBoolean("autoPause.${type.key}", it).apply()
                        },
                        label = { Text("Auto-pause", maxLines = 1) },
                        toggleControl = { androidx.wear.compose.material.Switch(checked = autoPause) },
                        colors = ToggleChipDefaults.toggleChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalRow(label: String, signal: WorkoutState.Signal) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        SignalDot(signal)
        Spacer(Modifier.width(6.dp))
        Text(text = "$label · ${signalText(signal)}", style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onSurface)
    }
}

// ---- Past workouts ----

@Composable
private fun PastWorkoutsScreen(onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    var workouts by remember { mutableStateOf<List<HealthHistory.Workout>?>(null) }
    LaunchedEffect(Unit) {
        workouts = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            HealthHistory.get(context).workouts(now - HealthHistory.MAX_AGE_DAYS * 2 * 86_400_000L, now + 86_400_000L)
        }
    }
    val dateFormat = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }

    WristLinkScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 40.dp),
            autoCentering = AutoCenteringParams()
        ) {
            item {
                Text(text = "Past workouts", style = MaterialTheme.typography.title3, color = MaterialTheme.colors.primary)
            }
            val list = workouts
            when {
                list == null -> item {
                    Text(text = "Loading…", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
                }
                list.isEmpty() -> item {
                    Text(text = "No workouts yet", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
                }
                else -> items(list) { w ->
                    val type = WorkoutType.fromKey(w.type)
                    val headline = if (w.distance > 0) "${type.label} · ${WorkoutFormat.distance(w.distance)}" else type.label
                    Chip(
                        onClick = { onOpen(w.id) },
                        label = { Text(headline, maxLines = 1) },
                        secondaryLabel = {
                            Text("${dateFormat.format(Date(w.start))} · ${WorkoutFormat.duration(w.activeMs)}", maxLines = 1)
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ---- Too short ----

@Composable
private fun DiscardedScreen() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Too short", style = MaterialTheme.typography.title3, color = MaterialTheme.colors.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Workouts under 10 seconds with no distance aren't saved",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
