package com.senzwh.deepharness

import harness.core.RustCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Seam Kotlin -> rusqlite (slice 7, vc322). Satu-satunya tempat yang
 * memanggil `RustCore.sqliteJson`; wrapper DB (EventDb, ArtifactStore,
 * ParkedApprovalStoreVc305, GraphDb) memakai helper ini, bukan JNI
 * langsung.
 *
 * Nilai baris: Long (INTEGER), Double (REAL), String (TEXT),
 * ByteArray (BLOB, dari {"$b64":...}), null (NULL).
 * Parameter: Long/Int/Double/String/Boolean/null/ByteArray.
 * Tiap fungsi mengembalikan null bila Rust gagal -> pemanggil WAJIB
 * fallback ke implementasi Kotlin lama + matikan saklar rust-nya.
 */
object SqliteSeam {
    /** Hasil exec: (rowsChanged, lastInsertRowId). */
    data class ExecResult(val changes: Long, val lastId: Long)

    private fun paramJson(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Boolean -> if (v) 1L else 0L
        is ByteArray -> JSONObject().put("\$b64", java.util.Base64.getEncoder().encodeToString(v))
        is Long, is Int, is Double, is Float, is String -> v
        else -> v.toString()
    }

    private fun call(request: JSONObject): JSONObject? {
        if (!RustCore.available) return null
        val raw = try {
            RustCore.sqliteJson(request.toString())
        } catch (t: Throwable) {
            null
        } ?: return null
        return try {
            val o = JSONObject(raw)
            if (!o.optBoolean("ok", false)) null else o
        } catch (e: Exception) {
            null
        }
    }

    /** Replikasi open-helper: skema + migrasi untuk `key` di `dbPath`. */
    fun ensure(dbPath: String, key: String): Boolean {
        val o = call(JSONObject().put("op", "ensure").put("db", dbPath).put("key", key))
            ?: return false
        return o.optLong("version", -1) >= 0
    }

    private fun rowsOf(o: JSONObject): List<List<Any?>>? {
        val arr = o.optJSONArray("rows") ?: return null
        val out = ArrayList<List<Any?>>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: return null
            val row = ArrayList<Any?>()
            for (j in 0 until r.length()) {
                row.add(cellOf(r.get(j)))
            }
            out.add(row)
        }
        return out
    }

    private fun cellOf(v: Any?): Any? {
        if (v == null || v === JSONObject.NULL) return null
        if (v is JSONObject) {
            val b = v.optString("\$b64", "")
            if (b.isNotEmpty()) {
                return try {
                    java.util.Base64.getDecoder().decode(b)
                } catch (e: Exception) {
                    null
                }
            }
            return null
        }
        return v // Long / Double / String dari org.json
    }

    fun query(dbPath: String, sql: String, args: List<Any?> = emptyList()): List<List<Any?>>? {
        val pa = JSONArray()
        for (a in args) pa.put(paramJson(a))
        val o = call(
            JSONObject().put("op", "query").put("db", dbPath).put("sql", sql).put("params", pa)
        ) ?: return null
        return rowsOf(o)
    }

    fun exec(dbPath: String, sql: String, args: List<Any?> = emptyList()): ExecResult? {
        val pa = JSONArray()
        for (a in args) pa.put(paramJson(a))
        val o = call(
            JSONObject().put("op", "exec").put("db", dbPath).put("sql", sql).put("params", pa)
        ) ?: return null
        return ExecResult(o.optLong("changes", 0), o.optLong("last_id", 0))
    }

    /** Transaksi atomik; null = gagal (rollback di Rust) -> fallback. */
    fun tx(dbPath: String, steps: List<Pair<String, List<Any?>>>): List<ExecResult>? {
        val sa = JSONArray()
        for ((sql, args) in steps) {
            val pa = JSONArray()
            for (a in args) pa.put(paramJson(a))
            sa.put(JSONObject().put("sql", sql).put("params", pa))
        }
        val o = call(
            JSONObject().put("op", "tx").put("db", dbPath).put("steps", sa)
        ) ?: return null
        val res = o.optJSONArray("results") ?: return null
        val out = ArrayList<ExecResult>()
        for (i in 0 until res.length()) {
            val r = res.optJSONObject(i) ?: return null
            out.add(ExecResult(r.optLong("changes", 0), r.optLong("last_id", 0)))
        }
        return out
    }

    // ---- Pemetaan baris (dipakai wrapper; null-tolerant seperti Cursor) ----
    fun rowLong(row: List<Any?>, i: Int): Long = when (val v = row.getOrNull(i)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    fun rowLongOrNull(row: List<Any?>, i: Int): Long? = when (val v = row.getOrNull(i)) {
        null -> null
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    fun rowInt(row: List<Any?>, i: Int): Int = rowLong(row, i).toInt()

    fun rowDouble(row: List<Any?>, i: Int): Double = when (val v = row.getOrNull(i)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    fun rowDoubleOrNull(row: List<Any?>, i: Int): Double? = when (val v = row.getOrNull(i)) {
        null -> null
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    fun rowStr(row: List<Any?>, i: Int): String = when (val v = row.getOrNull(i)) {
        is String -> v
        is Number -> v.toString()
        else -> ""
    }

    fun rowBytes(row: List<Any?>, i: Int): ByteArray? = row.getOrNull(i) as? ByteArray
}
