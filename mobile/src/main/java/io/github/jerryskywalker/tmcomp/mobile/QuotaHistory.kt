package io.github.jerryskywalker.tmcomp.mobile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.jerryskywalker.tmcomp.core.QuotaObservation
import io.github.jerryskywalker.tmcomp.core.Snapshot
import io.github.jerryskywalker.tmcomp.core.observeQuota
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class QuotaHistoryView(val points: List<QuotaObservation>, val days: Map<String, Double>)

internal class QuotaHistory(context: Context) : SQLiteOpenHelper(context, "quota-history.sqlite", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE observations (scope TEXT NOT NULL, reading_id TEXT NOT NULL, at INTEGER NOT NULL,
                day TEXT NOT NULL, value REAL NOT NULL, unit TEXT NOT NULL, allowance REAL, deadline INTEGER,
                spend INTEGER NOT NULL, PRIMARY KEY (scope, reading_id, at)) WITHOUT ROWID
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE daily (scope TEXT NOT NULL, reading_id TEXT NOT NULL, day TEXT NOT NULL, unit TEXT NOT NULL,
                used REAL NOT NULL, PRIMARY KEY (scope, reading_id, day, unit)) WITHOUT ROWID
        """.trimIndent())
        db.execSQL("CREATE INDEX observations_by_day ON observations(day)")
        db.execSQL("CREATE INDEX daily_by_day ON daily(day)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    suspend fun record(scope: String, snapshot: Snapshot) = withContext(Dispatchers.IO) {
        val now = Instant.parse(snapshot.retrievedAt)
        val cutoff = now.atZone(ZoneId.systemDefault()).toLocalDate().minusDays(44).toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (reading in snapshot.readings) {
                val point = reading.observeQuota(now) ?: continue
                val key = arrayOf(scope, reading.id)
                val previous = db.rawQuery("SELECT $columns FROM observations WHERE scope=? AND reading_id=? ORDER BY at DESC LIMIT 1", key)
                    .use { cursor -> if (cursor.moveToFirst()) cursor.observation() else null }
                if (previous != null && point.at <= previous.at) continue
                val priorDeadline = previous?.resetAt
                val deadline = point.resetAt
                if (priorDeadline != null && deadline != null && deadline + 1000 < priorDeadline) continue
                db.insertOrThrow("observations", null, ContentValues().apply {
                    put("scope", scope)
                    put("reading_id", reading.id)
                    put("at", point.at)
                    put("day", point.day)
                    put("value", point.value)
                    put("unit", point.unit)
                    put("allowance", point.allowance)
                    put("deadline", point.resetAt)
                    put("spend", point.increasesWithUsage)
                })
                if (previous?.day == point.day) point.changeSince(previous)?.let { delta ->
                    db.execSQL("""
                        INSERT INTO daily (scope, reading_id, day, unit, used) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (scope, reading_id, day, unit) DO UPDATE SET used = used + excluded.used
                    """.trimIndent(), arrayOf<Any>(scope, reading.id, point.day, point.unit, delta))
                }
            }
            db.delete("observations", "day < ?", arrayOf(cutoff))
            db.delete("daily", "day < ?", arrayOf(cutoff))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun view(scope: String, window: String, today: LocalDate): QuotaHistoryView = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val points = db.rawQuery("SELECT $columns FROM observations WHERE scope=? AND reading_id=? ORDER BY at DESC LIMIT 2500",
            arrayOf(scope, window)).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.observation()) } }
            .asReversed()
        val unit = points.lastOrNull()?.unit.orEmpty()
        val days = db.rawQuery("SELECT day, used FROM daily WHERE scope=? AND reading_id=? AND unit=? AND day>=?",
            arrayOf(scope, window, unit, today.minusDays(13).toString())).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getDouble(1)) }
        }
        QuotaHistoryView(points, days)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("observations", null, null)
            db.delete("daily", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.observation() = QuotaObservation(getLong(0), getString(1), getDouble(2), getString(3),
        getDouble(4).takeUnless { isNull(4) }, getLong(5).takeUnless { isNull(5) }, getInt(6) != 0)

    companion object {
        private const val columns = "at, day, value, unit, allowance, deadline, spend"
    }
}
