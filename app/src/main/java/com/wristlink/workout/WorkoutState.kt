package com.wristlink.workout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The workout in progress, as the rest of the app sees it. [WorkoutService] owns the Health
 * Services session and is the only writer; screens, tiles and the inactivity alert read.
 *
 * - [active]: a workout is running or paused (not while warming up). Inactivity alerts and
 *   anything else that shouldn't nag mid-workout check this.
 * - [live]: the numbers on the live screens.
 * - [finished]: what became of the last workout, so the workout app can open its summary.
 */
object WorkoutState {

    enum class Phase {
        IDLE,
        /** Warm-up: sensors locking on, nothing recorded yet. */
        PREPARING,
        STARTING,
        ACTIVE,
        PAUSED,
        /** Finish pressed, waiting for Health Services' last numbers. */
        ENDING
    }

    /** GPS or heart-rate sensor state, for the warm-up screen and the live screens' icons. */
    enum class Signal { NOT_USED, UNKNOWN, ACQUIRING, READY, UNAVAILABLE, OFF_BODY }

    data class Lap(
        val n: Int,
        val start: Long,
        val end: Long,
        val activeMs: Long,
        val distance: Double,
        val avgHr: Double?
    )

    data class Live(
        val phase: Phase = Phase.IDLE,
        val type: WorkoutType? = null,
        /** Wall-clock start of the workout. */
        val startMs: Long = 0,
        /**
         * Health Services' active-duration checkpoint: [activeAtCheckpointMs] of moving time at
         * wall-clock [checkpointMs]. Screens extrapolate from it while active.
         */
        val checkpointMs: Long = 0,
        val activeAtCheckpointMs: Long = 0,
        val autoPaused: Boolean = false,
        val autoPauseEnabled: Boolean = false,
        val distance: Double = 0.0,
        val calories: Double = 0.0,
        val steps: Long = 0,
        val hr: Double? = null,
        /** m/s, smoothed a little. */
        val speed: Double? = null,
        /** Steps per minute. */
        val cadence: Long? = null,
        val elevationGain: Double? = null,
        val gps: Signal = Signal.NOT_USED,
        val heartRate: Signal = Signal.UNKNOWN,
        /** Max HR the zones are computed from. */
        val maxHr: Int = 190,
        /** Milliseconds spent in zones 1..5. */
        val zoneMs: List<Long> = List(HrZones.COUNT) { 0L },
        /** Football: sprints so far, top speed (m/s), distance at 15 km/h or faster. */
        val sprints: Int = 0,
        val maxSpeed: Double = 0.0,
        val highIntensityDistance: Double = 0.0,
        /** Milliseconds walking, jogging, running and sprinting (SprintTracker's bands). */
        val speedBandMs: List<Long> = List(4) { 0L },
        val laps: List<Lap> = emptyList(),
        /** Start and distance of the lap in progress. */
        val lapStart: Long = 0,
        val lapStartDistance: Double = 0.0,
        /** Another app's workout (Samsung Health) is running and would be stopped by starting. */
        val otherAppActive: Boolean = false,
        /** Shown on the warm-up screen when something went wrong. */
        val error: String? = null
    ) {
        /** Moving time so far, extrapolated to [now] while active. */
        fun activeMs(now: Long = System.currentTimeMillis()): Long =
            if (phase == Phase.ACTIVE && checkpointMs > 0) {
                activeAtCheckpointMs + (now - checkpointMs).coerceAtLeast(0)
            } else {
                activeAtCheckpointMs
            }

        val inProgress: Boolean get() = phase == Phase.STARTING || phase == Phase.ACTIVE || phase == Phase.PAUSED || phase == Phase.ENDING
    }

    /** What happened to the workout that just ended. */
    sealed interface Finished {
        data class Saved(val id: Long) : Finished
        /** Discarded: no distance and under 10 s. */
        data object TooShort : Finished
    }

    /** Recovery heart rate being measured for workout [workoutId] until [untilMs]. */
    data class Recovery(val workoutId: Long, val untilMs: Long)

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    private val _active = MutableStateFlow(false)
    /** True while a workout is running or paused. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _finished = MutableStateFlow<Finished?>(null)
    val finished: StateFlow<Finished?> = _finished.asStateFlow()

    private val _recovery = MutableStateFlow<Recovery?>(null)
    val recovery: StateFlow<Recovery?> = _recovery.asStateFlow()

    /** Bumped whenever the route gains points; read the points with [route]. */
    private val _routeVersion = MutableStateFlow(0)
    val routeVersion: StateFlow<Int> = _routeVersion.asStateFlow()

    private val routePoints = ArrayList<DoubleArray>()

    /** The GPS track so far as [lat, lon] pairs (a copy). */
    fun route(): List<DoubleArray> = synchronized(routePoints) { ArrayList(routePoints) }

    // ---- Writers (WorkoutService only) ----

    internal fun update(transform: (Live) -> Live) {
        val next = transform(_live.value)
        _live.value = next
        _active.value = next.inProgress
    }

    internal fun reset(live: Live = Live()) {
        synchronized(routePoints) { routePoints.clear() }
        _routeVersion.value = 0
        update { live }
    }

    internal fun addRoutePoint(lat: Double, lon: Double) {
        synchronized(routePoints) { routePoints.add(doubleArrayOf(lat, lon)) }
        _routeVersion.value++
    }

    internal fun setFinished(result: Finished?) {
        _finished.value = result
    }

    internal fun setRecovery(recovery: Recovery?) {
        _recovery.value = recovery
    }

    /** The workout app has shown the result; don't open it again next launch. */
    fun consumeFinished() {
        _finished.value = null
    }
}
