package com.watchbridge.weather

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.watchbridge.tile.WeatherTileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The forecast the iPhone app last sent. The watch has no internet of its own, so the iPhone
 * fetches it (Open-Meteo, for where the iPhone is) and sends it over the companion link:
 *
 * ```
 * {"op":"weather","at":S,"place":"Kadıköy","unit":"C",
 *  "now":{"t":18.4,"feel":17.2,"code":2,"hum":60,"wind":12.3},
 *  "hours":[[S,temp,code,precip%],...],                    24 hours from the current one
 *  "days":[[S,max,min,code,sunrise S,sunset S,precip%],...]} today and the next days
 * ```
 * Times are Unix seconds; days start at local midnight. Codes are WMO weather codes.
 *
 * The hourly and daily parts let the watch show the right hour, today's range and the next
 * sunrise or sunset for many hours without hearing from the iPhone again.
 */
object WeatherStore {

    private const val TAG = "WeatherStore"
    private const val FILE = "weather.json"
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    /** Past this age the reading for "now" is taken from the hourly forecast instead. */
    private const val NOW_FRESH_MS = 90 * 60_000L

    data class Hour(val time: Long, val temp: Double, val code: Int, val precip: Int)

    data class Day(
        val date: Long,
        val max: Double,
        val min: Double,
        val code: Int,
        val sunrise: Long,
        val sunset: Long,
        val precip: Int
    )

    data class Conditions(val temp: Double, val code: Int, val isDay: Boolean, val feelsLike: Double?)

    data class SunEvent(val time: Long, val isSunrise: Boolean)

    class Forecast(
        val fetchedAt: Long,
        val place: String?,
        val unit: String,
        private val now: Conditions?,
        val hours: List<Hour>,
        val days: List<Day>
    ) {
        fun day(at: Long): Day? = days.lastOrNull { at >= it.date && at < it.date + DAY_MS }

        fun isDay(at: Long): Boolean {
            val day = day(at) ?: return true
            return at in day.sunrise until day.sunset
        }

        /** Conditions at [at]: the reported ones while fresh, then the forecast for that hour. */
        fun conditions(at: Long): Conditions? {
            if (now != null && at - fetchedAt in 0 until NOW_FRESH_MS) return now.copy(isDay = isDay(at))
            val hour = hours.lastOrNull { it.time <= at && at < it.time + HOUR_MS } ?: return null
            return Conditions(hour.temp, hour.code, isDay(at), feelsLike = null)
        }

        /** The hours after the one containing [at], for a small forecast row. */
        fun hoursAfter(at: Long, count: Int): List<Hour> =
            hours.filter { it.time > at }.take(count)

        /** The next sunrise or sunset after [at]. */
        fun nextSunEvent(at: Long): SunEvent? =
            days.flatMap { listOf(SunEvent(it.sunrise, true), SunEvent(it.sunset, false)) }
                .filter { it.time > at }
                .minByOrNull { it.time }

        /** When this forecast stops saying anything useful about "now". */
        val validUntil: Long
            get() = maxOf(hours.lastOrNull()?.let { it.time + HOUR_MS } ?: 0L, fetchedAt + NOW_FRESH_MS)

        fun formatTemp(value: Double): String = "${Math.round(value)}°"
    }

    private val _forecast = MutableStateFlow<Forecast?>(null)
    val forecast: StateFlow<Forecast?> = _forecast.asStateFlow()

    @Volatile private var loaded = false

    /** The stored forecast, read from disk on first use. */
    fun current(context: Context): Forecast? {
        if (!loaded) {
            loaded = true
            _forecast.value = runCatching {
                File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.let { parse(JSONObject(it)) }
            }.getOrNull()
        }
        return _forecast.value
    }

    /** When the stored forecast was fetched, in Unix seconds; 0 when there is none. */
    fun fetchedAtSeconds(context: Context): Long = (current(context)?.fetchedAt ?: 0L) / 1000

    /** A new forecast arrived from the iPhone: keep it and refresh everything showing it. */
    fun save(context: Context, message: JSONObject) {
        val forecast = runCatching { parse(message) }
            .onFailure { Log.w(TAG, "Unreadable forecast", it) }
            .getOrNull() ?: return
        runCatching { File(context.filesDir, FILE).writeText(message.toString()) }
        loaded = true
        _forecast.value = forecast
        Log.i(TAG, "Forecast for ${forecast.place}: ${forecast.hours.size} hours, ${forecast.days.size} days")
        refreshSurfaces(context)
    }

    /** Ask the watch face complications and the tile to draw again. */
    fun refreshSurfaces(context: Context) {
        for (service in listOf(WeatherComplicationService::class.java, SunComplicationService::class.java)) {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, service))
                .requestUpdateAll()
        }
        TileService.getUpdater(context).requestUpdate(WeatherTileService::class.java)
    }

    private fun parse(json: JSONObject): Forecast {
        val now = json.optJSONObject("now")?.let {
            Conditions(
                temp = it.getDouble("t"),
                code = it.optInt("code"),
                isDay = true,
                feelsLike = if (it.has("feel")) it.getDouble("feel") else null
            )
        }
        return Forecast(
            fetchedAt = json.getLong("at") * 1000,
            place = json.optString("place").ifBlank { null },
            unit = json.optString("unit", "C"),
            now = now,
            hours = json.optJSONArray("hours").rows().map {
                Hour(it.getLong(0) * 1000, it.getDouble(1), it.getInt(2), it.optInt(3))
            },
            days = json.optJSONArray("days").rows().map {
                Day(
                    date = it.getLong(0) * 1000,
                    max = it.getDouble(1),
                    min = it.getDouble(2),
                    code = it.getInt(3),
                    sunrise = it.getLong(4) * 1000,
                    sunset = it.getLong(5) * 1000,
                    precip = it.optInt(6)
                )
            }
        )
    }

    private fun JSONArray?.rows(): List<JSONArray> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONArray(it) }
}
