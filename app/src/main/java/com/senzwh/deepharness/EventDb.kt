package com.senzwh.deepharness

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import harness.core.RustCore

data class HEvent(val ts: Long, val type: String, val pkg: String, val text: String)

class EventDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "harness.db", null, 1) {

    // ---- Slice 7: seam rusqlite (vc322) ----
    // Path .db dilewat sebagai String ke Rust; Rust TIDAK PERNAH
    // menerima Context. ensureRust() sekali per instance (flag
    // volatile); gagal -> saklar mati -> jalur Kotlin lama.
    @Volatile
    var rustEventDb: Boolean = true
    private val dbPath: String =
        ctx.applicationContext.getDatabasePath("harness.db").absolutePath
    @Volatile
    private var ensuredRust = false

    private fun ensureRust(): Boolean {
        if (!rustEventDb || !RustCore.available) return false
        if (ensuredRust) return true
        val ok = try {
            SqliteSeam.ensure(dbPath, "events")
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            rustEventDb = false
            return false
        }
        ensuredRust = true
        return true
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, type TEXT, pkg TEXT, text TEXT)")
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    @Synchronized
    fun add(type: String, pkg: String, text: String) {
        if (ensureRust()) {
            val ts = System.currentTimeMillis()
            val r = SqliteSeam.exec(
                dbPath,
                "INSERT INTO events(ts,type,pkg,text) VALUES(?,?,?,?)",
                listOf(ts, type, pkg, text.take(2000))
            )
            if (r != null) return
            rustEventDb = false
        }
        addKotlin(type, pkg, text)
    }

    @Synchronized
    private fun addKotlin(type: String, pkg: String, text: String) {
        try {
            val v = ContentValues()
            v.put("ts", System.currentTimeMillis())
            v.put("type", type)
            v.put("pkg", pkg)
            v.put("text", text.take(2000))
            writableDatabase.insert("events", null, v)
        } catch (e: Exception) { /* sensor tidak boleh crash app */ }
    }

    fun latest(limit: Int, type: String? = null): List<HEvent> {
        if (ensureRust()) {
            val rows = if (type == null) {
                SqliteSeam.query(
                    dbPath,
                    "SELECT ts,type,pkg,text FROM events ORDER BY id DESC LIMIT ?",
                    listOf(limit.toString())
                )
            } else {
                SqliteSeam.query(
                    dbPath,
                    "SELECT ts,type,pkg,text FROM events WHERE type=? ORDER BY id DESC LIMIT ?",
                    listOf(type, limit.toString())
                )
            }
            if (rows != null) {
                return rows.map {
                    HEvent(
                        SqliteSeam.rowLong(it, 0),
                        SqliteSeam.rowStr(it, 1),
                        SqliteSeam.rowStr(it, 2),
                        SqliteSeam.rowStr(it, 3)
                    )
                }
            }
            rustEventDb = false
        }
        return latestKotlin(limit, type)
    }

    private fun latestKotlin(limit: Int, type: String? = null): List<HEvent> {
        val out = ArrayList<HEvent>()
        try {
            val c = if (type == null)
                readableDatabase.rawQuery("SELECT ts,type,pkg,text FROM events ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
            else
                readableDatabase.rawQuery("SELECT ts,type,pkg,text FROM events WHERE type=? ORDER BY id DESC LIMIT ?", arrayOf(type, limit.toString()))
            while (c.moveToNext()) out.add(HEvent(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: ""))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    /** vc299 m20 (N6-m20): hitung peristiwa dalam rentang — untuk
     *  mencatat jujur bila latest(300) memotong jendela kemarin. */
    fun countInRange(startMs: Long, endMs: Long): Int {
        if (ensureRust()) {
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT COUNT(*) FROM events WHERE ts >= ? AND ts < ?",
                listOf(startMs.toString(), endMs.toString())
            )
            if (rows != null && rows.isNotEmpty()) {
                return SqliteSeam.rowInt(rows[0], 0)
            }
            rustEventDb = false
        }
        return countInRangeKotlin(startMs, endMs)
    }

    private fun countInRangeKotlin(startMs: Long, endMs: Long): Int {
        return try {
            val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE ts >= ? AND ts < ?", arrayOf(startMs.toString(), endMs.toString()))
            val n = if (c.moveToFirst()) c.getInt(0) else 0
            c.close(); n
        } catch (e: Exception) { 0 }
    }

    /** vc299 m13 (N6-m13): notifikasi TERLAMA setelah ts — khusus fetch
     *  Rote (terlama-dulu supaya tidak ada yang terlewat saat baru >
     *  FETCH_CAP). latest() TIDAK diubah: pemakai lain tetap butuh yang
     *  terbaru. */
    fun oldestSince(limit: Int, afterTs: Long, type: String? = null): List<HEvent> {
        if (ensureRust()) {
            val rows = if (type == null) {
                SqliteSeam.query(
                    dbPath,
                    "SELECT ts,type,pkg,text FROM events WHERE ts > ? ORDER BY id ASC LIMIT ?",
                    listOf(afterTs.toString(), limit.toString())
                )
            } else {
                SqliteSeam.query(
                    dbPath,
                    "SELECT ts,type,pkg,text FROM events WHERE type=? AND ts > ? ORDER BY id ASC LIMIT ?",
                    listOf(type, afterTs.toString(), limit.toString())
                )
            }
            if (rows != null) {
                return rows.map {
                    HEvent(
                        SqliteSeam.rowLong(it, 0),
                        SqliteSeam.rowStr(it, 1),
                        SqliteSeam.rowStr(it, 2),
                        SqliteSeam.rowStr(it, 3)
                    )
                }
            }
            rustEventDb = false
        }
        return oldestSinceKotlin(limit, afterTs, type)
    }

    private fun oldestSinceKotlin(limit: Int, afterTs: Long, type: String? = null): List<HEvent> {
        val out = ArrayList<HEvent>()
        try {
            val c = if (type == null)
                readableDatabase.rawQuery("SELECT ts,type,pkg,text FROM events WHERE ts > ? ORDER BY id ASC LIMIT ?", arrayOf(afterTs.toString(), limit.toString()))
            else
                readableDatabase.rawQuery("SELECT ts,type,pkg,text FROM events WHERE type=? AND ts > ? ORDER BY id ASC LIMIT ?", arrayOf(type, afterTs.toString(), limit.toString()))
            while (c.moveToNext()) out.add(HEvent(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: ""))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    // vc246 GELOMBANG 5 (NT-M4): retensi 30 hari + tombol hapus log lama
    // (UI memanggil pruneOlderThan30d / clearAll). Dulu tidak ada DELETE
    // sama sekali sehingga log tumbuh tanpa batas di HP yang penuh.
    fun pruneOlderThan30d(): Int {
        if (ensureRust()) {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 3600_000
            val r = SqliteSeam.exec(
                dbPath,
                "DELETE FROM events WHERE ts < ?",
                listOf(cutoff.toString())
            )
            if (r != null) return r.changes.toInt()
            rustEventDb = false
        }
        return pruneOlderThan30dKotlin()
    }

    private fun pruneOlderThan30dKotlin(): Int {
        return try {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 3600_000
            writableDatabase.delete("events", "ts < ?", arrayOf(cutoff.toString()))
        } catch (e: Exception) { 0 }
    }

    fun clearAll(): Int {
        if (ensureRust()) {
            val r = SqliteSeam.exec(dbPath, "DELETE FROM events", emptyList())
            if (r != null) return r.changes.toInt()
            rustEventDb = false
        }
        return clearAllKotlin()
    }

    private fun clearAllKotlin(): Int {
        return try { writableDatabase.delete("events", null, null) } catch (e: Exception) { 0 }
    }

    fun count(): Int {
        if (ensureRust()) {
            val rows = SqliteSeam.query(dbPath, "SELECT COUNT(*) FROM events", emptyList())
            if (rows != null && rows.isNotEmpty()) {
                return SqliteSeam.rowInt(rows[0], 0)
            }
            rustEventDb = false
        }
        return countKotlin()
    }

    private fun countKotlin(): Int {
        return try {
            val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null)
            val n = if (c.moveToFirst()) c.getInt(0) else 0
            c.close(); n
        } catch (e: Exception) { 0 }
    }

    companion object {
        @Volatile private var inst: EventDb? = null
        fun get(ctx: Context): EventDb =
            inst ?: synchronized(this) { inst ?: EventDb(ctx).also { inst = it } }
    }
}
