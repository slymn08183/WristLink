package com.watchbridge.health

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * Health samples waiting to go to the iPhone (and from there to Apple Health).
 *
 * Rows are kept until the iPhone app acknowledges them, so nothing is lost while the iPhone is
 * away or the app isn't running. Each row is one Apple Health sample: an interval with a value
 * (steps, meters, kcal), an instant (heart rate) or a sleep stretch.
 */
class HealthStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "health.db", null, 1) {

    companion object {
        const val STEPS = "steps"
        const val DISTANCE = "distance"
        const val CALORIES = "calories"
        const val HEART_RATE = "hr"
        const val SLEEP = "sleep"

        /** Kept at most this long when the iPhone never picks them up. */
        private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

        @Volatile private var instance: HealthStore? = null
        fun get(context: Context): HealthStore =
            instance ?: synchronized(this) {
                instance ?: HealthStore(context.applicationContext).also { instance = it }
            }
    }

    data class Sample(val id: Long, val type: String, val start: Long, val end: Long, val value: Double) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("t", type).put("s", start).put("e", end).put("v", value)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, " +
                "start INTEGER NOT NULL, end INTEGER NOT NULL, value REAL NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(type: String, start: Long, end: Long, value: Double) {
        writableDatabase.insert("samples", null, ContentValues().apply {
            put("type", type)
            put("start", start)
            put("end", end)
            put("value", value)
        })
    }

    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** The oldest [max] samples with an id above [afterId]. */
    fun batch(afterId: Long, max: Int): List<Sample> =
        readableDatabase.rawQuery(
            "SELECT id, type, start, end, value FROM samples WHERE id > ? ORDER BY id LIMIT ?",
            arrayOf(afterId.toString(), max.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Sample(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getDouble(4)))
                }
            }
        }

    /** The iPhone saved everything up to [id]; also drops anything too old to matter. */
    fun acknowledge(id: Long) {
        writableDatabase.delete("samples", "id <= ?", arrayOf(id.toString()))
        writableDatabase.delete(
            "samples", "end < ?", arrayOf((System.currentTimeMillis() - MAX_AGE_MS).toString())
        )
    }
}
