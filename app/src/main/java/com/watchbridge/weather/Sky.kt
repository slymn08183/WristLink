package com.watchbridge.weather

import androidx.annotation.DrawableRes
import com.watchbridge.R

/** WMO weather codes (as Open-Meteo reports them) turned into a word and an icon. */
object Sky {

    fun label(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "Sunny" else "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 77 -> "Snow"
        75 -> "Heavy snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Storm, hail"
        else -> "–"
    }

    @DrawableRes
    fun icon(code: Int, isDay: Boolean): Int = when (code) {
        0 -> if (isDay) R.drawable.ic_weather_sun else R.drawable.ic_weather_moon
        1, 2 -> if (isDay) R.drawable.ic_weather_partly_day else R.drawable.ic_weather_partly_night
        3 -> R.drawable.ic_weather_cloud
        45, 48 -> R.drawable.ic_weather_fog
        in 51..67, 80, 81, 82 -> R.drawable.ic_weather_rain
        in 71..77, 85, 86 -> R.drawable.ic_weather_snow
        in 95..99 -> R.drawable.ic_weather_storm
        else -> R.drawable.ic_weather_cloud
    }

    /** A color for the icon where it can have one: warm sun, pale cloud, blue rain. */
    fun tint(code: Int, isDay: Boolean): Int = when (code) {
        0, 1, 2 -> if (isDay) 0xFFFFC857.toInt() else 0xFFD6DEFF.toInt()
        3, 45, 48 -> 0xFFB8D4FF.toInt()
        in 51..67, 80, 81, 82 -> 0xFF6EC6FF.toInt()
        in 71..77, 85, 86 -> 0xFFFFFFFF.toInt()
        in 95..99 -> 0xFFFFC857.toInt()
        else -> 0xFFB8D4FF.toInt()
    }

    /** Every icon, for tiles that must list their images up front. */
    val allIcons: List<Int> = listOf(
        R.drawable.ic_weather_sun, R.drawable.ic_weather_moon,
        R.drawable.ic_weather_partly_day, R.drawable.ic_weather_partly_night,
        R.drawable.ic_weather_cloud, R.drawable.ic_weather_fog, R.drawable.ic_weather_rain,
        R.drawable.ic_weather_snow, R.drawable.ic_weather_storm,
        R.drawable.ic_sunrise, R.drawable.ic_sunset
    )
}
