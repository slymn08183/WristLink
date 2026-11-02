package com.watchbridge.health

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * What the watch remembers for its own health screens, independent of whether the iPhone has
 * picked anything up (that's [HealthStore], the outbox).
 *
 * - `minute`: one row per minute that had any data: steps, distance (m), calories (kcal, as
 *   Health Services reports them), floors, and the heart rate seen in it (min/max/sum/count).
 *   Daily running totals arrive as deltas over an interval; [addSpread] shares them out evenly
 *   over the minutes of that interval. Hours and days are sums over minutes.
 * - `sleep`: stretches the watch's own sleep detection called asleep. A night is the stretches
 *   with less than an hour between them; the time between is awake.
 * - `workout`, `lap`, `track`: workouts recorded by the WristLink workout app.
 *
 * Everything is epoch milliseconds. Kept for [MAX_AGE_DAYS].
 */
class HealthHistory private constructor(context: Context) :
    SQLiteOpenHelper(context, "history.db", null, 1) {

    companion object {
        const val MINUTE_MS = 60_000L
        const val MAX_AGE_DAYS = 90

        @Volatile private var instance: HealthHistory? = null
        fun get(context: Context): HealthHistory =
            instance ?: synchronized(this) {
                instance ?: HealthHistory(context.applicationContext).also { instance = it }
            }
    }

    data class Minute(
        val t: Long,
        val steps: Double,
        val distance: Double,
        val calories: Double,
        val floors: Double,
        val hrMin: Double?,
        val hrMax: Double?,
        val hrAvg: Double?
    )

    data class SleepStretch(val start: Long, val end: Long)

    data class Workout(
        val id: Long,
        val type: String,
        val start: Long,
        val end: Long,
        /** Time actually moving, without pauses. */
        val activeMs: Long,
        val distance: Double,
        val calories: Double,
        val steps: Double,
        val avgHr: Double?,
        val maxHr: Double?,
        val elevationGain: Double,
        /** Extra summary fields as JSON (paces, cadence, zones, recovery HR, …). */
        val summary: String?,
        val synced: Boolean
    )

    data class Lap(val workoutId: Long, val n: Int, val start: Long, val end: Long, val distance: Double, val avgHr: Double?)

    data class TrackPoint(
        val t: Long,
        val lat: Double?,
        val lon: Double?,
        val alt: Double?,
        val hr: Double?,
        /** m/s */
        val speed: Double?,
        /** Total distance so far, m. */
        val distance: Double?
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE minute (t INTEGER PRIMARY KEY, steps REAL NOT NULL DEFAULT 0, " +
                "distance REAL NOT NULL DEFAULT 0, calories REAL NOT NULL DEFAULT 0, floors REAL NOT NULL DEFAULT 0, " +
                "hr_min REAL, hr_max REAL, hr_sum REAL NOT NULL DEFAULT 0, hr_n INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE sleep (start INTEGER PRIMARY KEY, end INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE workout (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, " +
                "start INTEGER NOT NULL, end INTEGER NOT NULL, active_ms INTEGER NOT NULL, " +
                "distance REAL NOT NULL DEFAULT 0, calories REAL NOT NULL DEFAULT 0, steps REAL NOT NULL DEFAULT 0, " +
                "avg_hr REAL, max_hr REAL, elevation_gain REAL NOT NULL DEFAULT 0, summary TEXT, " +
                "synced INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE lap (workout INTEGER NOT NULL, n INTEGER NOT NULL, start INTEGER NOT NULL, " +
                "end INTEGER NOT NULL, distance REAL NOT NULL DEFAULT 0, avg_hr REAL, PRIMARY KEY (workout, n))"
        )
        db.execSQL(
            "CREATE TABLE track (workout INTEGER NOT NULL, t INTEGER NOT NULL, lat REAL, lon REAL, alt REAL, " +
                "hr REAL, speed REAL, distance REAL, PRIMARY KEY (workout, t))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- Minutes ----

    /**
     * Share [amount] of [column] out over the minutes of [from]..[to] in proportion to how much
     * of each minute the interval covers.
     */
    fun addSpread(column: String, from: Long, to: Long, amount: Double) {
        require(column in setOf("steps", "distance", "calories", "floors"))
        if (amount <= 0) return
        val start = minOf(from, to)
        val end = maxOf(from, to)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (end - start < 1000) {
                addToMinute(db, column, floorMinute(end), amount)
            } else {
                var m = floorMinute(start)
                val span = (end - start).toDouble()
                while (m < end) {
                    val overlap = minOf(end, m + MINUTE_MS) - maxOf(start, m)
                    if (overlap > 0) addToMinute(db, column, m, amount * overlap / span)
                    m += MINUTE_MS
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun addToMinute(db: SQLiteDatabase, column: String, minute: Long, amount: Double) {
        db.execSQL(
            "INSERT INTO minute (t, $column) VALUES (?, ?) " +
                "ON CONFLICT(t) DO UPDATE SET $column = $column + excluded.$column",
            arrayOf<Any>(minute, amount)
        )
    }

    fun addHeartRate(t: Long, bpm: Double) {
        writableDatabase.execSQL(
            "INSERT INTO minute (t, hr_min, hr_max, hr_sum, hr_n) VALUES (?, ?, ?, ?, 1) " +
                "ON CONFLICT(t) DO UPDATE SET hr_min = min(coalesce(hr_min, excluded.hr_min), excluded.hr_min), " +
                "hr_max = max(coalesce(hr_max, excluded.hr_max), excluded.hr_max), " +
                "hr_sum = hr_sum + excluded.hr_sum, hr_n = hr_n + 1",
            arrayOf<Any>(floorMinute(t), bpm, bpm, bpm)
        )
    }

    /** Minutes with data in [from, to), oldest first. */
    fun minutes(from: Long, to: Long): List<Minute> =
        readableDatabase.rawQuery(
            "SELECT t, steps, distance, calories, floors, hr_min, hr_max, hr_sum, hr_n FROM minute " +
                "WHERE t >= ? AND t < ? ORDER BY t",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val n = c.getInt(8)
                    add(
                        Minute(
                            t = c.getLong(0),
                            steps = c.getDouble(1),
                            distance = c.getDouble(2),
                            calories = c.getDouble(3),
                            floors = c.getDouble(4),
                            hrMin = if (c.isNull(5)) null else c.getDouble(5),
                            hrMax = if (c.isNull(6)) null else c.getDouble(6),
                            hrAvg = if (n > 0) c.getDouble(7) / n else null
                        )
                    )
                }
            }
        }

    // ---- Sleep ----

    fun addSleep(start: Long, end: Long) {
        if (end <= start) return
        writableDatabase.insertWithOnConflict("sleep", null, ContentValues().apply {
            put("start", start)
            put("end", end)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Asleep stretches that overlap [from, to), oldest first. */
    fun sleep(from: Long, to: Long): List<SleepStretch> =
        readableDatabase.rawQuery(
            "SELECT start, end FROM sleep WHERE end > ? AND start < ? ORDER BY start",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(SleepStretch(c.getLong(0), c.getLong(1))) }
        }

    // ---- Workouts ----

    /** Save a finished workout with its laps and track; returns its id. */
    fun addWorkout(workout: Workout, laps: List<Lap>, track: List<TrackPoint>): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insert("workout", null, ContentValues().apply {
                put("type", workout.type)
                put("start", workout.start)
                put("end", workout.end)
                put("active_ms", workout.activeMs)
                put("distance", workout.distance)
                put("calories", workout.calories)
                put("steps", workout.steps)
                workout.avgHr?.let { put("avg_hr", it) }
                workout.maxHr?.let { put("max_hr", it) }
                put("elevation_gain", workout.elevationGain)
                put("summary", workout.summary)
                put("synced", 0)
            })
            for (lap in laps) {
                db.insertWithOnConflict("lap", null, ContentValues().apply {
                    put("workout", id)
                    put("n", lap.n)
                    put("start", lap.start)
                    put("end", lap.end)
                    put("distance", lap.distance)
                    lap.avgHr?.let { put("avg_hr", it) }
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (p in track) {
                db.insertWithOnConflict("track", null, ContentValues().apply {
                    put("workout", id)
                    put("t", p.t)
                    p.lat?.let { put("lat", it) }
                    p.lon?.let { put("lon", it) }
                    p.alt?.let { put("alt", it) }
                    p.hr?.let { put("hr", it) }
                    p.speed?.let { put("speed", it) }
                    p.distance?.let { put("distance", it) }
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /** Workouts that overlap [from, to), newest first. */
    fun workouts(from: Long, to: Long): List<Workout> = queryWorkouts("end > ? AND start < ?", from, to, order = "start DESC")

    fun workout(id: Long): Workout? = queryWorkouts("id = ?", id, order = "id").firstOrNull()

    /** The oldest workout the iPhone hasn't confirmed yet. */
    fun nextUnsyncedWorkout(): Workout? = queryWorkouts("synced = 0", order = "id LIMIT 1").firstOrNull()

    /** Replace a workout's summary JSON (the workout app adds the recovery heart rate a minute after the end). */
    fun setWorkoutSummary(id: Long, summary: String) {
        writableDatabase.update("workout", ContentValues().apply { put("summary", summary) }, "id = ?", arrayOf(id.toString()))
    }

    fun markWorkoutSynced(id: Long) {
        writableDatabase.update("workout", ContentValues().apply { put("synced", 1) }, "id = ?", arrayOf(id.toString()))
    }

    fun unsyncedWorkoutCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM workout WHERE synced = 0", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun laps(workoutId: Long): List<Lap> =
        readableDatabase.rawQuery(
            "SELECT workout, n, start, end, distance, avg_hr FROM lap WHERE workout = ? ORDER BY n",
            arrayOf(workoutId.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Lap(c.getLong(0), c.getInt(1), c.getLong(2), c.getLong(3), c.getDouble(4),
                        if (c.isNull(5)) null else c.getDouble(5))
                )
            }
        }

    fun track(workoutId: Long): List<TrackPoint> =
        readableDatabase.rawQuery(
            "SELECT t, lat, lon, alt, hr, speed, distance FROM track WHERE workout = ? ORDER BY t",
            arrayOf(workoutId.toString())
        ).use { c ->
            fun d(i: Int) = if (c.isNull(i)) null else c.getDouble(i)
            buildList { while (c.moveToNext()) add(TrackPoint(c.getLong(0), d(1), d(2), d(3), d(4), d(5), d(6))) }
        }

    fun deleteWorkout(id: Long) {
        val db = writableDatabase
        val args = arrayOf(id.toString())
        db.delete("track", "workout = ?", args)
        db.delete("lap", "workout = ?", args)
        db.delete("workout", "id = ?", args)
    }

    private fun queryWorkouts(where: String, vararg args: Any, order: String): List<Workout> =
        readableDatabase.rawQuery(
            "SELECT id, type, start, end, active_ms, distance, calories, steps, avg_hr, max_hr, " +
                "elevation_gain, summary, synced FROM workout WHERE $where ORDER BY $order",
            args.map { it.toString() }.toTypedArray()
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Workout(
                        id = c.getLong(0), type = c.getString(1), start = c.getLong(2), end = c.getLong(3),
                        activeMs = c.getLong(4), distance = c.getDouble(5), calories = c.getDouble(6),
                        steps = c.getDouble(7),
                        avgHr = if (c.isNull(8)) null else c.getDouble(8),
                        maxHr = if (c.isNull(9)) null else c.getDouble(9),
                        elevationGain = c.getDouble(10),
                        summary = if (c.isNull(11)) null else c.getString(11),
                        synced = c.getInt(12) != 0
                    )
                )
            }
        }

    // ---- Housekeeping ----

    fun prune(now: Long = System.currentTimeMillis()) {
        val cutoff = (now - MAX_AGE_DAYS * 24L * 3600 * 1000).toString()
        val db = writableDatabase
        db.delete("minute", "t < ?", arrayOf(cutoff))
        db.delete("sleep", "end < ?", arrayOf(cutoff))
        // Workouts stay longer than minutes would, but only once the iPhone has them
        db.execSQL("DELETE FROM track WHERE workout IN (SELECT id FROM workout WHERE synced = 1 AND end < ?)", arrayOf(cutoff))
        db.execSQL("DELETE FROM lap WHERE workout IN (SELECT id FROM workout WHERE synced = 1 AND end < ?)", arrayOf(cutoff))
        db.delete("workout", "synced = 1 AND end < ?", arrayOf(cutoff))
    }

    private fun floorMinute(t: Long) = t - Math.floorMod(t, MINUTE_MS)
}
