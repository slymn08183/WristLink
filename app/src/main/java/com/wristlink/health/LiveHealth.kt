package com.wristlink.health

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.wristlink.tile.ActivityTileService
import com.wristlink.tile.HeartRateTileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The latest heart rate and when the wearer last moved, for the watch faces, complications and
 * tiles. Health Services hands data over in batches, so this is as live as passive data gets
 * (a few minutes at most) without measuring anything on its own.
 *
 * Complications and tiles are told to refresh when something changes, at most every
 * [REFRESH_INTERVAL_MS], so a dense batch doesn't wake every face several times.
 */
object LiveHealth {

    data class Reading(val bpm: Int, val time: Long)

    private const val PREFS = "live_health"
    private const val REFRESH_INTERVAL_MS = 60_000L

    private val _heartRate = MutableStateFlow<Reading?>(null)
    private var loaded = false
    private var lastRefresh = 0L

    /** The newest heart rate, measured passively or on the Heart rate screen. */
    val heartRate: StateFlow<Reading?> = _heartRate.asStateFlow()

    fun latest(context: Context): Reading? {
        load(context)
        return _heartRate.value
    }

    fun onHeartRate(context: Context, time: Long, bpm: Double) {
        load(context)
        val current = _heartRate.value
        if (current != null && current.time >= time) return
        val reading = Reading(bpm.toInt(), time)
        _heartRate.value = reading
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("hr.bpm", reading.bpm).putLong("hr.time", reading.time).apply()
        refresh(context)
    }

    /** New steps arrived; [lastMove] is the end of the newest stretch with steps. */
    fun onActivity(context: Context, lastMove: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (lastMove > prefs.getLong("lastMove", 0)) prefs.edit().putLong("lastMove", lastMove).apply()
        HealthAlerts.onMoved(context, lastMove)
        refresh(context)
    }

    fun lastMove(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("lastMove", 0)

    /** Ask our complications and tiles to redraw. */
    fun refresh(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < REFRESH_INTERVAL_MS) return
        lastRefresh = now
        for (service in listOf(HeartRateComplicationService::class.java, ActivityComplicationService::class.java)) {
            runCatching {
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, service)).requestUpdateAll()
            }
        }
        runCatching {
            val updater = TileService.getUpdater(context)
            updater.requestUpdate(ActivityTileService::class.java)
            updater.requestUpdate(HeartRateTileService::class.java)
        }
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains("hr.time") && _heartRate.value == null) {
            _heartRate.value = Reading(prefs.getInt("hr.bpm", 0), prefs.getLong("hr.time", 0))
        }
    }
}
