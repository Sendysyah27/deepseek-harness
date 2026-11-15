package com.senzwh.deepharness

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

// vc305 H-K1 — Store DURABLE antrean parkir persetujuan (SQLite).
//
// Keputusan desain (mengikat): SQLite via android.database.sqlite.
// SQLiteOpenHelper — framework API, TANPA dependensi baru. Room
// DITOLAK: tidak ada annotation processing di build manual (kotlinc
// CLI + d8); Room akan memecahkan build. Sketsa laporan dsh-go §4.2
// #1 ("Room/SQLite") dipenuhi oleh varian SQLite ini.
//
// Sumber kebenaran parkir pindah dari SharedPreferences
// "bgPendingQueue" (JSONArray, tanpa field status) ke tabel ini.
// Migrasi legacy: saat pertama dibuka (loadParkQueue di BgBrain),
// entri prefs yang masih ada diimpor sekali — entri ts<=0 langsung
// EXPIRED (tidak diimpor sebagai PENDING), entri valid diimpor
// sebagai PENDING dengan createdAtMs = ts aslinya — lalu prefs
// dikosongkan agar tidak ganda.
//
// Aturan yang ditegakkan di SQL (bukan hanya di pemanggil):
//  - decision set-once: decide() hanya berhasil bila decision IS NULL
//    (UPDATE ... WHERE decision IS NULL); keputusan yang sudah ditulis
//    tidak pernah ditimpa.
//  - claim atomik: claim() hanya berhasil bila status = 'PENDING'
//    (UPDATE ... WHERE status = 'PENDING'); tap kedua / balapan antar
//    proses kalah dan me-render Processing, bukan mengeksekusi ulang.
//  - createdAtMs SELALU terisi (millis saat park; tidak pernah 0).
//  - timeout ownership: timeoutOwnerMs ditulis SEKALI saat klaim
//    pertama (decidedAtMs klaim); redelivery tidak menyentuhnya, jadi
//    timeout tidak pernah diperpanjang.
//  - notifId UNIK per pid: counter persisten "bgNotifSeq" di prefs
//    (bukan hashCode(pid) % 5000 yang bisa tabrakan + FLAG_UPDATE_CURRENT
//    menimpa ekstra pid lain).
//
// Kolom di luar daftar minimal spesifikasi (task/origin/title/
// transcript/steps/job/tools/ownerSession) WAJIB ada: resumeApproval
// harus bisa merekonstruksi loop() + deliver() dari baris DB saja,
// tanpa membaca prefs lagi.
class ParkedApprovalStoreVc305 private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "parked_approvals.db"
        const val DB_VERSION = 1
        const val TABLE = "parked_approvals"
        const val PARKED_APPROVAL_VC305 = "vc305"

        @Volatile
        private var instance: ParkedApprovalStoreVc305? = null

        fun get(ctx: Context): ParkedApprovalStoreVc305 {
            return instance ?: synchronized(this) {
                instance ?: ParkedApprovalStoreVc305(ctx).also { instance = it }
            }
        }

        /** Dipakai BrainBackup.restore: parkir hasil restore TIDAK BOLEH
         *  hidup kembali sebagai PENDING yang bisa dieksekusi. DB ini
         *  memang TIDAK ikut backup (bukan filesDir/otak*, bukan
         *  settings.json), jadi DB apa pun yang ada saat restore adalah
         *  state live pra-restore — dihapus total, bukan ditandai
         *  EXPIRED (tidak ada baris yang boleh bertahan dari restore). */
        fun deleteDatabase(ctx: Context) {
            try { instance?.close() } catch (e: Exception) {}
            instance = null
            try { ctx.deleteDatabase(DB_NAME) } catch (e: Exception) {}
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "pid TEXT PRIMARY KEY, " +
                "sessionId TEXT, " +
                "tool TEXT, " +
                "args TEXT, " +
                "risk TEXT, " +
                "status TEXT, " +
                "decision TEXT, " +
                "decidedAtMs INTEGER, " +
                "createdAtMs INTEGER, " +
                "timeoutMs INTEGER, " +
                "timeoutOwnerMs INTEGER, " +
                "notifId INTEGER, " +
                "task TEXT, " +
                "origin TEXT, " +
                "title TEXT, " +
                "transcript TEXT, " +
                "steps INTEGER, " +
                "job TEXT, " +
                "tools TEXT, " +
                "ownerSession INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parked_status ON $TABLE(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parked_notif ON $TABLE(notifId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 = versi pertama; tidak ada migrasi skema.
    }

    data class Row(
        val pid: String,
        val sessionId: String,
        val tool: String,
        val args: String,
        val risk: String,
        val status: String,
        val decision: String?,
        val decidedAtMs: Long,
        val createdAtMs: Long,
        val timeoutMs: Long,
        val timeoutOwnerMs: Long,
        val notifId: Int,
        val task: String,
        val origin: String,
        val title: String,
        val transcript: String,
        val steps: Int,
        val job: String,
        val tools: String,
        val ownerSession: Long
    ) {
        fun toState(): ParkedApprovalMachineVc305.ParkedState {
            val st = try {
                ParkedApprovalMachineVc305.Status.valueOf(status)
            } catch (e: Exception) {
                ParkedApprovalMachineVc305.Status.EXPIRED
            }
            return ParkedApprovalMachineVc305.ParkedState(
                pid, tool, risk, st, decision, createdAtMs,
                decidedAtMs, timeoutOwnerMs, notifId
            )
        }

        fun toJson(): JSONObject = JSONObject()
            .put("pid", pid).put("tool", tool).put("args", args)
            .put("risk", risk).put("task", task).put("origin", origin)
            .put("title", title).put("transcript", transcript)
            .put("steps", steps).put("job", job).put("tools", tools)
            .put("ts", createdAtMs).put("notifId", notifId)
            .put("ownerSession", ownerSession).put("status", status)
    }

    private fun rowFromCursor(c: android.database.Cursor): Row {
        fun s(name: String): String = try {
            c.getString(c.getColumnIndexOrThrow(name)) ?: ""
        } catch (e: Exception) { "" }
        fun l(name: String): Long = try {
            c.getLong(c.getColumnIndexOrThrow(name))
        } catch (e: Exception) { 0L }
        fun i(name: String): Int = try {
            c.getInt(c.getColumnIndexOrThrow(name))
        } catch (e: Exception) { 0 }
        val decIdx = try { c.getColumnIndexOrThrow("decision") } catch (e: Exception) { -1 }
        val decision = if (decIdx >= 0 && !c.isNull(decIdx)) c.getString(decIdx) else null
        return Row(
            pid = s("pid"), sessionId = s("sessionId"), tool = s("tool"),
            args = s("args"), risk = s("risk"), status = s("status"),
            decision = decision, decidedAtMs = l("decidedAtMs"),
            createdAtMs = l("createdAtMs"), timeoutMs = l("timeoutMs"),
            timeoutOwnerMs = l("timeoutOwnerMs"), notifId = i("notifId"),
            task = s("task"), origin = s("origin"), title = s("title"),
            transcript = s("transcript"), steps = i("steps"), job = s("job"),
            tools = s("tools"), ownerSession = l("ownerSession")
        )
    }

    @Synchronized
    fun insertIfAbsent(row: Row): Boolean {
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("pid", row.pid); put("sessionId", row.sessionId)
                put("tool", row.tool); put("args", row.args)
                put("risk", row.risk); put("status", row.status)
                put("decision", row.decision); put("decidedAtMs", row.decidedAtMs)
                put("createdAtMs", row.createdAtMs); put("timeoutMs", row.timeoutMs)
                put("timeoutOwnerMs", row.timeoutOwnerMs); put("notifId", row.notifId)
                put("task", row.task); put("origin", row.origin)
                put("title", row.title); put("transcript", row.transcript)
                put("steps", row.steps); put("job", row.job)
                put("tools", row.tools); put("ownerSession", row.ownerSession)
            }
            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE) >= 0
        } catch (e: Exception) { false }
    }

    /** Klaim atomik PENDING -> PROCESSING. Berhasil hanya bila baris
     *  masih PENDING (WHERE status='PENDING'); timeoutOwnerMs ditulis
     *  SEKALI di sini (= nowMs) dan tidak pernah diubah lagi. */
    @Synchronized
    fun claim(pid: String, nowMs: Long): Boolean {
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put("status", "PROCESSING")
                    put("timeoutOwnerMs", nowMs)
                }
                val n = db.update(
                    TABLE, cv, "pid = ? AND status = 'PENDING'",
                    arrayOf(pid)
                )
                db.setTransactionSuccessful()
                n == 1
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) { false }
    }

    /** Keputusan set-once: hanya berhasil bila decision masih NULL.
     *  Status akhir APPROVED / REJECTED / EXPIRED. Mengembalikan
     *  false bila baris tidak ada atau sudah diputuskan. */
    @Synchronized
    fun decide(pid: String, decision: String, nowMs: Long): Boolean {
        val terminal = when (decision) {
            "APPROVED" -> "APPROVED"
            "REJECTED" -> "REJECTED"
            "EXPIRED" -> "EXPIRED"
            else -> return false
        }
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put("status", terminal)
                    put("decision", decision)
                    put("decidedAtMs", nowMs)
                }
                val n = db.update(
                    TABLE, cv, "pid = ? AND decision IS NULL",
                    arrayOf(pid)
                )
                db.setTransactionSuccessful()
                n == 1
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) { false }
    }

    @Synchronized
    fun markExpired(pid: String, nowMs: Long): Boolean = decide(pid, "EXPIRED", nowMs)

    @Synchronized
    fun get(pid: String): Row? {
        return try {
            readableDatabase.query(
                TABLE, null, "pid = ?", arrayOf(pid),
                null, null, null
            ).use { c -> if (c.moveToFirst()) rowFromCursor(c) else null }
        } catch (e: Exception) { null }
    }

    /** PENDING saja (belum diklaim). */
    @Synchronized
    fun listPending(): List<Row> = listByStatus("PENDING")

    /** PENDING + PROCESSING — antrean aktif untuk UI (parkedList). */
    @Synchronized
    fun listActive(): List<Row> {
        return try {
            readableDatabase.query(
                TABLE, null, "status IN ('PENDING','PROCESSING')",
                null, null, null, "createdAtMs ASC"
            ).use { c ->
                val out = ArrayList<Row>()
                while (c.moveToNext()) out.add(rowFromCursor(c))
                out
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun listByStatus(status: String): List<Row> {
        return try {
            readableDatabase.query(
                TABLE, null, "status = ?", arrayOf(status),
                null, null, null, "createdAtMs ASC"
            ).use { c ->
                val out = ArrayList<Row>()
                while (c.moveToNext()) out.add(rowFromCursor(c))
                out
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Sapu kedaluwarsa (pemilik timeout). Mengembalikan snapshot
     *  baris yang di-EXPIRED-kan (untuk deliver + batalkan notif oleh
     *  pemanggil). Aturan per baris:
     *  - createdAtMs <= 0 -> EXPIRED (lubang abadi vc304 ditutup).
     *  - PENDING + now - createdAtMs > timeoutMs -> EXPIRED.
     *  - PROCESSING + now - timeoutOwnerMs > timeoutMs -> EXPIRED
     *    (klaim kedaluwarsa; redelivery tidak memperpanjang karena
     *    timeoutOwnerMs tidak pernah ditulis ulang). */
    @Synchronized
    fun sweepExpired(nowMs: Long): List<Row> {
        val expired = ArrayList<Row>()
        try {
            val actives = listActive()
            for (row in actives) {
                val stale = when {
                    row.createdAtMs <= 0 -> true
                    row.status == "PENDING" && nowMs - row.createdAtMs > row.timeoutMs -> true
                    row.status == "PROCESSING" && row.timeoutOwnerMs > 0 &&
                        nowMs - row.timeoutOwnerMs > row.timeoutMs -> true
                    else -> false
                }
                if (stale && markExpired(row.pid, nowMs)) expired.add(row)
            }
        } catch (e: Exception) {}
        return expired
    }

    /** Counter persisten notifId unik per parkir baru (prefs
     *  "bgNotifSeq", rentang 20000..24999, loncat bila sudah dipakai
     *  antrean aktif). Menggantikan notifIdForPid hashCode % 5000. */
    @Synchronized
    fun nextNotifId(ctx: Context): Int {
        return try {
            val p = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            var seq = p.getInt("bgNotifSeq", 20000)
            var tries = 0
            while (tries < 5000) {
                if (seq < 20000 || seq > 24999) seq = 20000
                val inUse = try {
                    readableDatabase.query(
                        TABLE, arrayOf("pid"), "notifId = ? AND status IN ('PENDING','PROCESSING')",
                        arrayOf(seq.toString()), null, null, null
                    ).use { c -> c.count > 0 }
                } catch (e: Exception) { false }
                if (!inUse) {
                    p.edit().putInt("bgNotifSeq", if (seq >= 24999) 20000 else seq + 1).apply()
                    return seq
                }
                seq = if (seq >= 24999) 20000 else seq + 1
                tries++
            }
            20000 + ((System.currentTimeMillis() % 5000).toInt())
        } catch (e: Exception) {
            20000 + ((System.currentTimeMillis() % 5000).toInt())
        }
    }

    /** Migrasi satu kali dari JSONArray prefs "bgPendingQueue".
     *  Mengembalikan Pair(diimpor, kedaluwarsa): entri ts<=0 TIDAK
     *  diimpor (langsung kedaluwarsa — pemanggil membatalkan
     *  notifikasinya); entri valid diimpor sebagai PENDING dengan
     *  createdAtMs = ts aslinya + timeoutMs dari risk. */
    @Synchronized
    fun migrateLegacy(arr: JSONArray): Pair<Int, List<JSONObject>> {
        var imported = 0
        val expired = ArrayList<JSONObject>()
        try {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pid = o.optString("pid")
                if (pid.isBlank()) continue
                val ts = o.optLong("ts", 0)
                if (ts <= 0) { expired.add(o); continue }
                if (get(pid) != null) continue
                val risk = o.optString("risk", "SAFE")
                val timeoutMs = if (risk == "HIGH") {
                    ParkedApprovalMachineVc305.HIGH_STALE_MS
                } else {
                    ParkedApprovalMachineVc305.OTHER_STALE_MS
                }
                val row = Row(
                    pid = pid, sessionId = o.optLong("ownerSession", 0).toString(),
                    tool = o.optString("tool"), args = o.optString("args", "{}"),
                    risk = risk, status = "PENDING", decision = null,
                    decidedAtMs = 0, createdAtMs = ts, timeoutMs = timeoutMs,
                    timeoutOwnerMs = 0, notifId = o.optInt("notifId", 0),
                    task = o.optString("task"), origin = o.optString("origin"),
                    title = o.optString("title"),
                    transcript = o.optString("transcript"),
                    steps = o.optInt("steps", 0), job = o.optString("job"),
                    tools = o.optString("tools"),
                    ownerSession = o.optLong("ownerSession", 0)
                )
                if (insertIfAbsent(row)) imported++
            }
        } catch (e: Exception) {}
        return Pair(imported, expired)
    }
}
