package com.wristlink.workout

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseTypeCapabilities
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.WarmUpConfig
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.wristlink.R
import com.wristlink.health.HealthHistory
import com.wristlink.health.HealthProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Owns the Health Services exercise session, so a workout keeps going with the workout app
 * closed: warm-up ([prepare]), start, pause/resume, laps, finish. Runs in the foreground
 * (type health, plus location for outdoor workouts) with an Ongoing Activity, which puts the
 * workout on the watch face. Everything it learns goes into [WorkoutState]; the finished
 * workout goes into [HealthHistory].
 *
 * A minute after the finish it measures the heart rate once more for the recovery heart rate,
 * then stops.
 */
class WorkoutService : Service() {

    companion object {
        private const val TAG = "WorkoutService"
        const val CHANNEL_ID = "workout"
        private const val NOTIFICATION_ID = 4210

        private const val ACTION_PREPARE = "com.wristlink.workout.PREPARE"
        private const val ACTION_START = "com.wristlink.workout.START"
        private const val ACTION_PAUSE = "com.wristlink.workout.PAUSE"
        private const val ACTION_RESUME = "com.wristlink.workout.RESUME"
        private const val ACTION_LAP = "com.wristlink.workout.LAP"
        private const val ACTION_FINISH = "com.wristlink.workout.FINISH"
        private const val ACTION_CANCEL = "com.wristlink.workout.CANCEL"
        private const val ACTION_RECOVER = "com.wristlink.workout.RECOVER"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_AUTO_PAUSE = "auto_pause"

        /** How long after the finish the recovery heart rate is read. */
        private const val RECOVERY_MS = 60_000L

        /** Warm up the sensors (GPS, heart rate) for [type]. */
        fun prepare(context: Context, type: WorkoutType) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_PREPARE).putExtra(EXTRA_TYPE, type.key))
        }

        fun start(context: Context, type: WorkoutType, autoPause: Boolean) {
            ContextCompat.startForegroundService(
                context,
                intent(context, ACTION_START).putExtra(EXTRA_TYPE, type.key).putExtra(EXTRA_AUTO_PAUSE, autoPause)
            )
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun lap(context: Context) = send(context, ACTION_LAP)
        fun finish(context: Context) = send(context, ACTION_FINISH)

        /** Leave the warm-up without starting. */
        fun cancel(context: Context) = send(context, ACTION_CANCEL)

        /**
         * Pick up this app's own exercise that Health Services is still running, after the app
         * was killed mid-workout. The totals carry on; the track restarts from now.
         */
        fun recover(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_RECOVER))
        }

        /** Whether Health Services is running an exercise of ours that [WorkoutState] doesn't know about. */
        suspend fun orphanedExercise(context: Context): Boolean {
            if (WorkoutState.live.value.phase != WorkoutState.Phase.IDLE) return false
            return try {
                HealthServices.getClient(context).exerciseClient.getCurrentExerciseInfoAsync().await()
                    .exerciseTrackedStatus == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS
            } catch (e: Exception) {
                false
            }
        }

        /** What Health Services can record for [type], or null if it can't tell. */
        suspend fun capabilities(context: Context, type: WorkoutType): ExerciseTypeCapabilities? =
            try {
                HealthServices.getClient(context).exerciseClient.getCapabilitiesAsync().await().typeToCapabilities[type.exerciseType]
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read exercise capabilities", e)
                null
            }

        private fun send(context: Context, action: String) {
            try {
                context.startService(intent(context, action))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't send $action", e)
            }
        }

        private fun intent(context: Context, action: String) = Intent(context, WorkoutService::class.java).setAction(action)

        private fun hasPermission(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client by lazy { HealthServices.getClient(this).exerciseClient }

    private var type = WorkoutType.OTHER
    private var recorder: WorkoutRecorder? = null
    private var callbackSet = false
    private var recovering = false
    private var finalizing = false
    private var lastState: ExerciseState? = null
    private var lastKm = 0
    private var endTimeout: Job? = null
    private var recoveryJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                stopRecoveryMeasurement()
                type = WorkoutType.fromKey(intent.getStringExtra(EXTRA_TYPE))
                if (goForeground()) scope.launch { prepareExercise() }
            }
            ACTION_START -> {
                stopRecoveryMeasurement()
                type = WorkoutType.fromKey(intent.getStringExtra(EXTRA_TYPE))
                if (goForeground()) scope.launch { startExercise(intent.getBooleanExtra(EXTRA_AUTO_PAUSE, false)) }
            }
            ACTION_RECOVER -> {
                recovering = true
                if (goForeground()) ensureCallback()
            }
            ACTION_PAUSE -> scope.launch { call("pause") { client.pauseExerciseAsync().await() } }
            ACTION_RESUME -> scope.launch { call("resume") { client.resumeExerciseAsync().await() } }
            ACTION_LAP -> lap()
            ACTION_FINISH -> finishExercise()
            ACTION_CANCEL -> scope.launch { cancelWarmUp() }
            // Restarted by the system with no intent: nothing to pick up here
            else -> if (!WorkoutState.live.value.inProgress && recoveryJob == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- Session ----

    private val locationWanted: Boolean
        get() = type.outdoor && hasPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)

    private suspend fun prepareExercise() {
        ensureCallback()
        val otherApp = try {
            client.getCurrentExerciseInfoAsync().await().exerciseTrackedStatus == ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS
        } catch (e: Exception) {
            false
        }
        val caps = capabilities(this, type)
        val warmUp = buildSet<DeltaDataType<*, *>> {
            if (caps == null || DataType.HEART_RATE_BPM in caps.supportedDataTypes) add(DataType.HEART_RATE_BPM)
            if (locationWanted && (caps == null || DataType.LOCATION in caps.supportedDataTypes)) add(DataType.LOCATION)
        }
        WorkoutState.reset(
            WorkoutState.Live(
                phase = WorkoutState.Phase.PREPARING,
                type = type,
                gps = if (DataType.LOCATION in warmUp) WorkoutState.Signal.ACQUIRING else WorkoutState.Signal.NOT_USED,
                heartRate = if (DataType.HEART_RATE_BPM in warmUp) WorkoutState.Signal.ACQUIRING else WorkoutState.Signal.UNAVAILABLE,
                maxHr = HealthProfile.current(this).maxHr,
                otherAppActive = otherApp
            )
        )
        postNotification()
        try {
            client.prepareExerciseAsync(WarmUpConfig(type.exerciseType, warmUp)).await()
            Log.i(TAG, "Warming up ${type.key}: ${warmUp.map { it.name }}")
        } catch (e: Exception) {
            // Typically another app's workout; starting still works (it takes over)
            Log.w(TAG, "Couldn't prepare ${type.key}", e)
        }
    }

    private suspend fun startExercise(autoPause: Boolean) {
        ensureCallback()
        val caps = capabilities(this, type)
        val wanted: List<DataType<*, *>> = buildList {
            add(DataType.HEART_RATE_BPM)
            if (locationWanted) add(DataType.LOCATION)
            add(DataType.SPEED)
            add(DataType.PACE)
            add(DataType.DISTANCE)
            add(DataType.DISTANCE_TOTAL)
            add(DataType.CALORIES_TOTAL)
            add(DataType.STEPS_TOTAL)
            add(DataType.STEPS_PER_MINUTE)
            add(DataType.ELEVATION_GAIN_TOTAL)
        }
        // Asking for anything the watch can't do for this type fails the whole start
        val types = wanted.filter { caps == null || it in caps.supportedDataTypes }.toSet()
        val auto = autoPause && caps?.supportsAutoPauseAndResume == true
        val config = ExerciseConfig.builder(type.exerciseType)
            .setDataTypes(types)
            .setIsAutoPauseAndResumeEnabled(auto)
            .setIsGpsEnabled(DataType.LOCATION in types)
            .build()

        val now = System.currentTimeMillis()
        val maxHr = HealthProfile.current(this).maxHr
        recorder = WorkoutRecorder(type, now, maxHr)
        lastState = null
        lastKm = 0
        finalizing = false
        val before = WorkoutState.live.value
        WorkoutState.reset(
            WorkoutState.Live(
                phase = WorkoutState.Phase.STARTING,
                type = type,
                startMs = now,
                checkpointMs = now,
                autoPauseEnabled = auto,
                gps = if (DataType.LOCATION in types) before.gps.takeIf { before.type == type && it != WorkoutState.Signal.NOT_USED }
                    ?: WorkoutState.Signal.ACQUIRING else WorkoutState.Signal.NOT_USED,
                heartRate = before.heartRate,
                maxHr = maxHr,
                lapStart = now
            )
        )
        WorkoutState.setFinished(null)
        try {
            client.startExerciseAsync(config).await()
            Log.i(TAG, "Started ${type.key}: ${types.map { it.name }} autoPause=$auto")
            postNotification()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start ${type.key}", e)
            recorder = null
            WorkoutState.reset(WorkoutState.Live(type = type, error = "Couldn't start: ${e.message ?: e.javaClass.simpleName}"))
            stopService()
        }
    }

    private suspend fun cancelWarmUp() {
        if (WorkoutState.live.value.inProgress) return
        try {
            val status = client.getCurrentExerciseInfoAsync().await().exerciseTrackedStatus
            if (status == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS) client.endExerciseAsync().await()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't end the warm-up", e)
        }
        clearCallback()
        WorkoutState.reset()
        stopService()
    }

    private fun lap() {
        val r = recorder ?: return
        val live = WorkoutState.live.value
        if (live.phase != WorkoutState.Phase.ACTIVE && live.phase != WorkoutState.Phase.PAUSED) return
        val now = System.currentTimeMillis()
        r.lap(now)
        WorkoutState.update {
            it.copy(laps = r.currentLaps, lapStart = r.currentLapStart, lapStartDistance = r.currentLapStartDistance)
        }
        buzz(longArrayOf(0, 120))
        scope.launch { call("markLap") { client.markLapAsync().await() } }
    }

    private fun finishExercise() {
        if (recorder == null) {
            scope.launch { cancelWarmUp() }
            return
        }
        WorkoutState.update { it.copy(phase = WorkoutState.Phase.ENDING) }
        scope.launch {
            try {
                client.endExerciseAsync().await()
            } catch (e: Exception) {
                Log.w(TAG, "endExercise failed, saving what we have", e)
                finalizeWorkout()
            }
        }
        // The last update normally arrives within a second or two; don't hang if it doesn't
        endTimeout?.cancel()
        endTimeout = scope.launch {
            delay(10_000)
            finalizeWorkout()
        }
    }

    private suspend inline fun call(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
        }
    }

    // ---- Updates ----

    private val callback = object : ExerciseUpdateCallback {
        override fun onRegistered() = Unit

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.w(TAG, "Exercise callback registration failed", throwable)
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) = handleUpdate(update)

        // Laps are kept by the recorder, which also knows the heart rate and route per lap
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            when (availability) {
                is LocationAvailability -> {
                    val signal = when (availability) {
                        LocationAvailability.ACQUIRED_TETHERED, LocationAvailability.ACQUIRED_UNTETHERED -> WorkoutState.Signal.READY
                        LocationAvailability.ACQUIRING -> WorkoutState.Signal.ACQUIRING
                        LocationAvailability.NO_GNSS, LocationAvailability.UNAVAILABLE -> WorkoutState.Signal.UNAVAILABLE
                        else -> WorkoutState.Signal.UNKNOWN
                    }
                    WorkoutState.update { it.copy(gps = signal) }
                }
                is DataTypeAvailability -> if (dataType == DataType.HEART_RATE_BPM) {
                    val signal = when (availability) {
                        DataTypeAvailability.AVAILABLE -> WorkoutState.Signal.READY
                        DataTypeAvailability.ACQUIRING -> WorkoutState.Signal.ACQUIRING
                        DataTypeAvailability.UNAVAILABLE -> WorkoutState.Signal.UNAVAILABLE
                        DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> WorkoutState.Signal.OFF_BODY
                        else -> WorkoutState.Signal.UNKNOWN
                    }
                    WorkoutState.update { it.copy(heartRate = signal) }
                }
            }
        }
    }

    private fun ensureCallback() {
        if (callbackSet) return
        client.setUpdateCallback(callback)
        callbackSet = true
    }

    private fun clearCallback() {
        if (!callbackSet) return
        callbackSet = false
        scope.launch { call("clearUpdateCallback") { client.clearUpdateCallbackAsync(callback).await() } }
    }

    private fun handleUpdate(update: ExerciseUpdate) {
        val now = System.currentTimeMillis()
        val state = update.exerciseStateInfo.state
        if (recovering && (state == ExerciseState.PREPARING || state.isEnded)) {
            // What was left over was only a warm-up, or it ended meanwhile: nothing to pick up
            recovering = false
            scope.launch { cancelWarmUp() }
            return
        }
        if (state == ExerciseState.PREPARING) return

        // Picking up an exercise after the app was killed: start a fresh recorder from here
        if (recorder == null && recovering && !state.isEnded) {
            recovering = false
            val config = update.exerciseConfig
            type = config?.exerciseType?.let { WorkoutType.fromExerciseType(it) } ?: WorkoutType.OTHER
            val start = update.startTime?.toEpochMilli() ?: now
            val maxHr = HealthProfile.current(this).maxHr
            recorder = WorkoutRecorder(type, start, maxHr)
            WorkoutState.reset(
                WorkoutState.Live(
                    phase = WorkoutState.Phase.ACTIVE,
                    type = type,
                    startMs = start,
                    autoPauseEnabled = config?.isAutoPauseAndResumeEnabled == true,
                    gps = if (config?.isGpsEnabled == true) WorkoutState.Signal.ACQUIRING else WorkoutState.Signal.NOT_USED,
                    maxHr = maxHr,
                    lapStart = start
                )
            )
            Log.i(TAG, "Recovered a running ${type.key} workout")
        }
        val r = recorder ?: return
        if (finalizing) return

        val boot = Instant.ofEpochMilli(now - SystemClock.elapsedRealtime())
        val m: DataPointContainer = update.latestMetrics

        var hr: Double? = null
        m.getData(DataType.HEART_RATE_BPM).sortedBy { it.timeDurationFromBoot }.forEach {
            r.onHeartRate(it.getTimeInstant(boot).toEpochMilli(), it.value)
            hr = it.value
        }
        m.getData(DataType.LOCATION).sortedBy { it.timeDurationFromBoot }.forEach {
            val loc = it.value
            // Altitude is NaN when unavailable
            val alt = loc.altitude.takeIf { a -> !a.isNaN() }
            r.onLocation(it.getTimeInstant(boot).toEpochMilli(), loc.latitude, loc.longitude, alt)
            WorkoutState.addRoutePoint(loc.latitude, loc.longitude)
        }
        var speed: Double? = null
        m.getData(DataType.SPEED).sortedBy { it.timeDurationFromBoot }.forEach {
            speed = r.onSpeed(it.getTimeInstant(boot).toEpochMilli(), it.value)
        }
        if (speed == null) {
            // Pace is milliseconds per km
            m.getData(DataType.PACE).sortedBy { it.timeDurationFromBoot }.forEach {
                if (it.value > 0) speed = r.onSpeed(it.getTimeInstant(boot).toEpochMilli(), 1_000_000.0 / it.value)
            }
        }
        m.getData(DataType.DISTANCE).sortedBy { it.endDurationFromBoot }.forEach {
            r.onDistanceDelta(it.getEndInstant(boot).toEpochMilli(), it.value)
        }
        var cadence: Long? = null
        m.getData(DataType.STEPS_PER_MINUTE).sortedBy { it.timeDurationFromBoot }.forEach {
            r.onCadence(it.getTimeInstant(boot).toEpochMilli(), it.value)
            cadence = it.value
        }
        r.onTotals(
            now,
            distance = m.getData(DataType.DISTANCE_TOTAL)?.total,
            calories = m.getData(DataType.CALORIES_TOTAL)?.total,
            steps = m.getData(DataType.STEPS_TOTAL)?.total,
            elevationGain = m.getData(DataType.ELEVATION_GAIN_TOTAL)?.total
        )

        val wasPaused = lastState?.isPaused == true
        if (state.isPaused && !wasPaused) r.pause(now)
        if (!state.isPaused && wasPaused) r.resume(now)

        val phase = when {
            state.isEnded || state.isEnding -> WorkoutState.Phase.ENDING
            state.isPaused -> WorkoutState.Phase.PAUSED
            state == ExerciseState.USER_STARTING -> WorkoutState.Phase.STARTING
            else -> WorkoutState.Phase.ACTIVE
        }
        val autoPaused = state == ExerciseState.AUTO_PAUSED || state == ExerciseState.AUTO_PAUSING
        val checkpoint = update.activeDurationCheckpoint
        val before = WorkoutState.live.value
        WorkoutState.update {
            it.copy(
                phase = phase,
                startMs = update.startTime?.toEpochMilli() ?: it.startMs,
                checkpointMs = checkpoint?.time?.toEpochMilli() ?: it.checkpointMs,
                activeAtCheckpointMs = checkpoint?.activeDuration?.toMillis() ?: it.activeAtCheckpointMs,
                autoPaused = autoPaused,
                distance = r.distance,
                calories = r.calories,
                steps = r.steps,
                hr = hr ?: it.hr,
                speed = if (phase == WorkoutState.Phase.PAUSED) null else speed ?: it.speed,
                cadence = cadence ?: it.cadence,
                elevationGain = r.elevationGainTotal ?: it.elevationGain,
                zoneMs = r.zoneMs.toList()
            )
        }
        if (phase != before.phase || autoPaused != before.autoPaused) {
            postNotification()
            if (autoPaused != before.autoPaused) buzz(longArrayOf(0, 80, 80, 80))
        }

        // A double buzz at each kilometre
        val km = (r.distance / 1000).toInt()
        if (km > lastKm) {
            lastKm = km
            if (type.hasDistance) buzz(longArrayOf(0, 150, 100, 150))
        }

        lastState = state
        if (state.isEnded) scope.launch { finalizeWorkout() }
    }

    // ---- Finish ----

    private suspend fun finalizeWorkout() {
        if (finalizing) return
        finalizing = true
        endTimeout?.cancel()
        val r = recorder ?: run {
            WorkoutState.reset()
            stopService()
            return
        }
        recorder = null
        clearCallback()

        val live = WorkoutState.live.value
        val end = System.currentTimeMillis()
        // Health Services' final checkpoint, unless it never came
        val activeMs = (if (live.phase == WorkoutState.Phase.ACTIVE) live.activeMs(end) else live.activeAtCheckpointMs)
            .coerceIn(0, end - r.startMs)

        if (r.shouldDiscard(activeMs)) {
            Log.i(TAG, "Workout too short, not saved (${activeMs} ms, ${r.distance} m)")
            WorkoutState.setFinished(WorkoutState.Finished.TooShort)
            WorkoutState.reset()
            stopService()
            return
        }

        val result = r.finish(end, activeMs)
        val id = withContext(Dispatchers.IO) {
            HealthHistory.get(this@WorkoutService).addWorkout(result.workout, result.laps, result.track)
        }
        Log.i(TAG, "Saved workout $id: ${type.key} ${result.workout.distance.roundToInt()} m, ${activeMs / 1000} s, ${result.track.size} points")
        WorkoutState.setFinished(WorkoutState.Finished.Saved(id))
        WorkoutState.reset()

        val last = result.lastHr
        if (last != null && end - last.first < 20_000) {
            recoveryJob = scope.launch { measureRecovery(id, end, last.second, result.workout.summary) }
        } else {
            stopService()
        }
    }

    /**
     * Recovery heart rate: how far the heart rate fell in the minute after the finish. Reads a
     * few on-demand heart-rate samples around the one-minute mark and adds the drop to the
     * stored summary as `recoveryHr` (with `recoveryHrEnd` and `recoveryHr60`).
     */
    private suspend fun measureRecovery(id: Long, end: Long, endHr: Double, summary: String?) {
        WorkoutState.setRecovery(WorkoutState.Recovery(id, end + RECOVERY_MS))
        postRecoveryNotification()
        val measure = HealthServices.getClient(this).measureClient
        val samples = ArrayList<Pair<Long, Double>>()
        val cb = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit
            override fun onDataReceived(data: DataPointContainer) {
                val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
                data.getData(DataType.HEART_RATE_BPM).forEach {
                    if (it.value > 0) samples.add(it.getTimeInstant(boot).toEpochMilli() to it.value)
                }
            }
        }
        var registered = false
        try {
            measure.registerMeasureCallback(DataType.HEART_RATE_BPM, cb)
            registered = true
            delay((end + RECOVERY_MS + 8_000 - System.currentTimeMillis()).coerceAtLeast(0))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Couldn't measure recovery heart rate", e)
        } finally {
            if (registered) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    call("unregisterMeasureCallback") { measure.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, cb).await() }
                }
            }
            WorkoutState.setRecovery(null)
        }

        val around = samples.filter { it.first in (end + RECOVERY_MS - 5_000)..(end + RECOVERY_MS + 8_000) }.map { it.second }
        if (around.isNotEmpty()) {
            val hr60 = around.average()
            val json = runCatching { JSONObject(summary ?: "{}") }.getOrDefault(JSONObject())
                .put("recoveryHr", (endHr - hr60).roundToInt())
                .put("recoveryHrEnd", endHr.roundToInt())
                .put("recoveryHr60", hr60.roundToInt())
            withContext(Dispatchers.IO) { HealthHistory.get(this@WorkoutService).setWorkoutSummary(id, json.toString()) }
            Log.i(TAG, "Recovery heart rate for $id: ${endHr.roundToInt()} → ${hr60.roundToInt()}")
            // Let an open summary reload
            WorkoutState.setRecovery(null)
        } else {
            Log.i(TAG, "No heart rate a minute after the finish; no recovery heart rate")
        }
        recoveryJob = null
        if (!WorkoutState.live.value.inProgress && WorkoutState.live.value.phase != WorkoutState.Phase.PREPARING) stopService()
    }

    private fun stopRecoveryMeasurement() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    // ---- Foreground, notification ----

    private fun goForeground(): Boolean {
        val types = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                    (if (locationWanted) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            locationWanted -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else -> 0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
            true
        } catch (e: Exception) {
            // Missing the body sensors / activity recognition / location permission
            Log.w(TAG, "Couldn't go foreground", e)
            WorkoutState.reset(WorkoutState.Live(type = type, error = "Missing sensor permissions"))
            stopSelf()
            false
        }
    }

    private fun stopService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Workout", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The workout in progress"
                setShowBadge(false)
            }
        )
    }

    private fun openWorkout(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, WorkoutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun serviceAction(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(), intent(this, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(): Notification {
        ensureChannel()
        val live = WorkoutState.live.value
        val open = openWorkout()
        val phase = live.phase
        val text = when (phase) {
            WorkoutState.Phase.IDLE, WorkoutState.Phase.PREPARING -> "Getting ready"
            WorkoutState.Phase.PAUSED -> if (live.autoPaused) "Auto-paused" else "Paused"
            WorkoutState.Phase.ENDING -> "Finishing"
            else -> "In progress"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_workout)
            .setContentTitle(type.label)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (phase == WorkoutState.Phase.ACTIVE || phase == WorkoutState.Phase.PAUSED) {
            if (phase == WorkoutState.Phase.ACTIVE) {
                builder.addAction(R.drawable.ic_pause, "Pause", serviceAction(ACTION_PAUSE))
            } else {
                builder.addAction(R.drawable.ic_play, "Resume", serviceAction(ACTION_RESUME))
            }
            builder.addAction(R.drawable.ic_workout_lap, "Lap", serviceAction(ACTION_LAP))
        }

        // The workout's icon and running time at the bottom of the watch face
        val elapsedNow = SystemClock.elapsedRealtime()
        val active = live.activeMs()
        val status = if (live.inProgress) {
            val stopwatch = if (phase == WorkoutState.Phase.ACTIVE || phase == WorkoutState.Phase.STARTING) {
                Status.StopwatchPart(elapsedNow - active)
            } else {
                Status.StopwatchPart(elapsedNow - active, elapsedNow)
            }
            Status.Builder()
                .addTemplate("#type# #time#")
                .addPart("type", Status.TextPart(type.label))
                .addPart("time", stopwatch)
                .build()
        } else {
            Status.Builder().addTemplate("#type# · getting ready").addPart("type", Status.TextPart(type.label)).build()
        }
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_workout)
            .setTouchIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setStatus(status)
            .build()
            .apply(this)
        return builder.build()
    }

    private fun postNotification() {
        if (!WorkoutState.live.value.inProgress && WorkoutState.live.value.phase != WorkoutState.Phase.PREPARING) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    /** Plain notification (no Ongoing Activity) while the recovery heart rate is read. */
    private fun postRecoveryNotification() {
        ensureChannel()
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_workout)
            .setContentTitle("Workout saved")
            .setContentText("Measuring recovery heart rate")
            .setContentIntent(openWorkout())
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun buzz(pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            } ?: return
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't vibrate", e)
        }
    }
}
