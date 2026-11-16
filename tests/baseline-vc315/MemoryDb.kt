package com.senzwh.deepharness

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Reminder(val id: Long, val dueTs: Long, val text: String)
data class Schedule(val id: Long, val hour: Int, val minute: Int, val prompt: String, val enabled: Boolean, val ownerSession: Long = 0)
data class AutoRule(val id: Long, val pkgContains: String, val textContains: String, val actionPrompt: String)
data class Skill(val id: Long, val name: String, val instruction: String, val descr: String = "", val enabled: Boolean = true, val evalJson: String = "")
// vc300 BAGIAN A: satu baris = satu draf skill yang pernah tersimpan.
// Append-only — teks lama tidak pernah hilang walau forgeUpsert menimpa
// baris skills; restore membuat VERSI BARU, bukan memutasi history.
data class SkillVersion(val id: Long, val skillId: Long, val versionNo: Int, val instruction: String, val descr: String, val judgeTotal: Int, val judgeGrade: String, val evalJson: String, val source: String, val createdTs: Long)
// vc301 BAGIAN B (SF-B3): satu baris ledger export skill — jejak tiap
// export_skill (nama file, byte, hash isi, waktu) supaya hilangnya file
// export bisa dideteksi, bukan disadari belakangan.
data class SkillExportRow(val id: Long, val skillId: Long, val skillName: String, val fileName: String, val bytes: Long, val hash: String, val createdTs: Long)
// VC303-P1-1 (EV-1/2/5): satu run forge_trigger_eval yang tersimpan —
// sumber kebenaran save-by-hash. PRIMARY KEY (skill_id, hash): satu hash =
// satu run per skill. descr_hash + skill_version_no = invalidasi bila draf
// berubah sesudah eval (run milik draf lama tidak boleh ditempel ke baru).
data class EvalRun(val skillId: Long, val hash: String, val queriesJson: String, val votesJson: String, val triggerTrain: Int, val triggerTest: Int, val descrHash: String, val skillVersionNo: Int, val createdTs: Long)
data class Geofence(val id: Long, val name: String, val lat: Double, val lng: Double, val radiusM: Int, val prompt: String, val inside: Boolean, val ownerSession: Long = 0)
data class FinEntry(val id: Long, val ts: Long, val pkg: String, val amount: Long, val kind: String, val text: String)
data class ChatSession(val id: Long, val title: String, val createdTs: Long, val pinned: Boolean = false)
data class Goal(val id: Long, val title: String, val status: String, val createdTs: Long, val updatedTs: Long)
data class GoalStep(val id: Long, val goalId: Long, val text: String, val dueTs: Long, val done: Boolean, val doneTs: Long)

/** Memori persisten harness: fakta tentang user, riwayat percakapan, pengingat.
 *  Semua lokal di HP (SQLite), tidak pernah keluar perangkat selain disuntikkan
 *  sebagai konteks ke prompt DeepSeek atas kendali user. */
class MemoryDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "memory.db", null, 7) {

    private val appCtx: Context = ctx.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        // vc285 Fase 0: skema facts baru — PK id (bukan k) supaya baris
        // superseded/withdrawn dengan key sama BISA koeksis dengan baris
        // aktif; kolom provenance source/source_ts/status/superseded_by/
        // reason/updated_ts. DB lama dimigrasi lazy di onOpen.
        db.execSQL("CREATE TABLE facts(id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT, v TEXT, ts INTEGER, confirmed INTEGER DEFAULT 0, source TEXT DEFAULT '', source_ts INTEGER DEFAULT 0, status TEXT DEFAULT 'active', superseded_by TEXT DEFAULT '', reason TEXT DEFAULT '', updated_ts INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER)")
        db.execSQL("CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT, due INTEGER, text TEXT, done INTEGER DEFAULT 0)")
        createV2(db)
        createV3(db)
        createV4(db)
        createV5(db)
        createV6(db)
        createV7(db)
        createPerfIndexes(db)
    }

    private fun createV6(db: SQLiteDatabase) {
        // Tahap D: antrean tugas Koordinator OpenCode + aturan koordinasi milik harness.
        db.execSQL("CREATE TABLE IF NOT EXISTS oc_tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, chain_id TEXT, text TEXT, session_id TEXT DEFAULT '', status TEXT DEFAULT 'queued', result TEXT DEFAULT '', created INTEGER, finished INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS coord_rules(k TEXT PRIMARY KEY, v TEXT)")
    }

    // vc256 Fase 1 Device-as-Server: registry worker persisten.
    // Pola sama dengan createV2..V6 (IF NOT EXISTS, aman diulang).
    private fun createV7(db: SQLiteDatabase) {
        db.execSQL(WorkerRegistry.CREATE_SQL)
    }

    // vc256: lazy ensure tabel workers (pola deleted_archive) — onOpen jalan
    // tiap DB dibuka, jadi DB lama ikut mendapat tabel walau onUpgrade
    // sudah lewat.
    private fun ensureWorkerTable(db: SQLiteDatabase) {
        try { db.execSQL(WorkerRegistry.CREATE_SQL) } catch (e: Exception) {}
    }

    private fun createV5(db: SQLiteDatabase) {
        // Tahap C: tujuan multi-hari + langkah-langkahnya.
        db.execSQL("CREATE TABLE IF NOT EXISTS goals(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, status TEXT DEFAULT 'aktif', created INTEGER, updated INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS goal_steps(id INTEGER PRIMARY KEY AUTOINCREMENT, goal_id INTEGER, text TEXT, due INTEGER DEFAULT 0, done INTEGER DEFAULT 0, done_ts INTEGER DEFAULT 0, pos INTEGER DEFAULT 0)")
    }

    private fun createV4(db: SQLiteDatabase) {
        // Tahap B: vektor embedding (float32 384-dim, little-endian) per entri memori.
        db.execSQL("CREATE TABLE IF NOT EXISTS embeddings(kind TEXT, ref TEXT, vec BLOB, thash INTEGER, PRIMARY KEY(kind, ref))")
    }

    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, created INTEGER)")
        try { db.execSQL("ALTER TABLE history ADD COLUMN session INTEGER DEFAULT 1") } catch (e: Exception) {}
        db.execSQL("INSERT OR IGNORE INTO sessions(id,title,created) VALUES(1,'Utama',${System.currentTimeMillis()})")
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS schedules(id INTEGER PRIMARY KEY AUTOINCREMENT, hour INTEGER, minute INTEGER, prompt TEXT, enabled INTEGER DEFAULT 1, last_run_day INTEGER DEFAULT -1)")
        db.execSQL("CREATE TABLE IF NOT EXISTS rules(id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, needle TEXT, action TEXT, enabled INTEGER DEFAULT 1)")
        db.execSQL("CREATE TABLE IF NOT EXISTS skills(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, instruction TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS geofences(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, lat REAL, lng REAL, radius INTEGER, prompt TEXT, inside INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS finance(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, pkg TEXT, amount INTEGER, kind TEXT, text TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) createV2(db)
        if (o < 3) createV3(db)
        if (o < 4) createV4(db)
        if (o < 5) createV5(db)
        if (o < 6) createV6(db)
        if (o < 7) createV7(db)
    }

    // vc239 M7: indeks kinerja minimal. Dibuat dengan IF NOT EXISTS agar
    // aman diulang. onOpen menjamin DB LAMA (sudah versi 6, sehingga
    // onUpgrade tidak jalan lagi) ikut mendapat indeks, bukan hanya DB
    // baru dari onCreate. deleted_archive dibuat dulu karena tabelnya
    // lazy (ensureArchiveTable). history(session,ts): kolom session ada
    // sejak v3; bila kolom hilang, pernyataan itu gagal sendiri dan
    // ditangkap, indeks lain tetap dibuat. Tiap pernyataan dibungkus
    // try sendiri agar satu kegagalan tidak menggagalkan sisanya.
    private fun createPerfIndexes(db: SQLiteDatabase) {
        try { db.execSQL("CREATE TABLE IF NOT EXISTS deleted_archive(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, ref TEXT, payload TEXT, deleted_ts INTEGER)") } catch (e: Exception) {}
        val idx = listOf(
            "CREATE INDEX IF NOT EXISTS idx_facts_ts ON facts(ts)",
            "CREATE INDEX IF NOT EXISTS idx_facts_k_status ON facts(k, status)",
            "CREATE INDEX IF NOT EXISTS idx_goal_steps_goal_pos ON goal_steps(goal_id, pos)",
            "CREATE INDEX IF NOT EXISTS idx_finance_ts ON finance(ts)",
            "CREATE INDEX IF NOT EXISTS idx_deleted_archive_ts ON deleted_archive(deleted_ts)",
            "CREATE INDEX IF NOT EXISTS idx_history_session_ts ON history(session, ts)"
        )
        for (s in idx) { try { db.execSQL(s) } catch (e: Exception) {} }
    }

    // vc243 GELOMBANG 2: kolom ikatan sesi (lazy ALTER, aman diulang).
    // onOpen jalan tiap DB dibuka, jadi kolom ada sebelum query baca apa pun.
    private fun ensureWave2Columns(db: SQLiteDatabase) {
        try { db.execSQL("ALTER TABLE oc_tasks ADD COLUMN origin_session INTEGER DEFAULT 0") } catch (e: Exception) {}
        try { db.execSQL("ALTER TABLE schedules ADD COLUMN owner_session INTEGER DEFAULT 0") } catch (e: Exception) {}
        try { db.execSQL("ALTER TABLE geofences ADD COLUMN owner_session INTEGER DEFAULT 0") } catch (e: Exception) {}
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureFactProvenanceVc285(db)
        // vc294: kolom tags/ref_path + tabel lessons + fact_candidates
        // (lazy, pola vc285/vc289 — tanpa bump versi DB).
        ensureVc294SchemaVc294(db)
        // vc289 Fase 3: tabel turunan graph mini + dream cycle (lazy, aman
        // diulang; tanpa bump versi DB, pola vc285). Bukan tabel fakta.
        try { db.execSQL("CREATE TABLE IF NOT EXISTS fact_edges(from_k TEXT, to_k TEXT, rel TEXT, created_ts INTEGER, PRIMARY KEY(from_k, to_k, rel))") } catch (e: Exception) {}
        try { db.execSQL("CREATE TABLE IF NOT EXISTS dream_flags(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, fact_a TEXT, fact_b TEXT, detail TEXT, status TEXT DEFAULT 'open', created_ts INTEGER, resolved_ts INTEGER DEFAULT 0)") } catch (e: Exception) {}
        createPerfIndexes(db)
        ensureWave2Columns(db)
        ensureWorkerTable(db)
    }

    // vc285 Fase 0: migrasi lazy skema facts ke PK id + kolom provenance.
    // Alasan migrasi (bukan sekadar ALTER): PK lama k TEXT PRIMARY KEY
    // membuat baris superseded dengan key sama TIDAK BISA koeksis dengan
    // baris aktif yang baru — supersesi tinggal-di-tabel butuh PK baru.
    // Satu kali, transaksional, idempotent (dicek via PRAGMA table_info:
    // kolom id ada = sudah migrasi; tabel baru dari onCreate sudah
    // berskema baru). Tanpa bump versi DB (pola vc228 R-03(a)).
    private fun ensureFactProvenanceVc285(db: SQLiteDatabase) {
        try {
            var hasId = false
            var hasStatus = false
            var hasConfirmed = false
            val c = db.rawQuery("PRAGMA table_info(facts)", null)
            while (c.moveToNext()) {
                val n = c.getString(1) ?: ""
                if (n == "id") hasId = true
                if (n == "status") hasStatus = true
                if (n == "confirmed") hasConfirmed = true
            }
            c.close()
            if (hasId) {
                if (!hasStatus) {
                    try { db.execSQL("ALTER TABLE facts ADD COLUMN source TEXT DEFAULT ''") } catch (e: Exception) {}
                    try { db.execSQL("ALTER TABLE facts ADD COLUMN source_ts INTEGER DEFAULT 0") } catch (e: Exception) {}
                    try { db.execSQL("ALTER TABLE facts ADD COLUMN status TEXT DEFAULT 'active'") } catch (e: Exception) {}
                    try { db.execSQL("ALTER TABLE facts ADD COLUMN superseded_by TEXT DEFAULT ''") } catch (e: Exception) {}
                    try { db.execSQL("ALTER TABLE facts ADD COLUMN reason TEXT DEFAULT ''") } catch (e: Exception) {}
                    try { db.execSQL("ALTER TABLE facts ADD COLUMN updated_ts INTEGER DEFAULT 0") } catch (e: Exception) {}
                }
                return
            }
            if (!hasConfirmed) {
                try { db.execSQL("ALTER TABLE facts ADD COLUMN confirmed INTEGER DEFAULT 0") } catch (e: Exception) {}
            }
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS facts_vc285(id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT, v TEXT, ts INTEGER, confirmed INTEGER DEFAULT 0, source TEXT DEFAULT '', source_ts INTEGER DEFAULT 0, status TEXT DEFAULT 'active', superseded_by TEXT DEFAULT '', reason TEXT DEFAULT '', updated_ts INTEGER DEFAULT 0)")
                db.execSQL("INSERT INTO facts_vc285(k, v, ts, confirmed, source, source_ts, status, superseded_by, reason, updated_ts) SELECT k, v, ts, confirmed, '', 0, 'active', '', '', 0 FROM facts")
                db.execSQL("DROP TABLE facts")
                db.execSQL("ALTER TABLE facts_vc285 RENAME TO facts")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {}
    }

    // vc294: skema loop memori — kolom tags/ref_path di facts (lazy
    // ALTER, pola vc285) + tabel lessons (confidence + penguatan +
    // jalur hapus sendiri) + tabel fact_candidates (review queue
    // candidate/promoted/archived). Versi db-argumen (dipanggil onOpen)
    // dan lazy (dipanggil tiap fungsi publik vc294).
    private fun ensureVc294SchemaVc294(db: SQLiteDatabase) {
        try { db.execSQL("ALTER TABLE facts ADD COLUMN tags TEXT DEFAULT ''") } catch (e: Exception) {}
        try { db.execSQL("ALTER TABLE facts ADD COLUMN ref_path TEXT DEFAULT ''") } catch (e: Exception) {}
        try { db.execSQL("CREATE TABLE IF NOT EXISTS lessons(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, confidence REAL DEFAULT 0.7, hits INTEGER DEFAULT 1, status TEXT DEFAULT 'active', source TEXT DEFAULT '', reason TEXT DEFAULT '', ts INTEGER, updated_ts INTEGER)") } catch (e: Exception) {}
        try { db.execSQL("CREATE TABLE IF NOT EXISTS fact_candidates(id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT, v TEXT, tags TEXT DEFAULT '', ref_path TEXT DEFAULT '', source TEXT DEFAULT '', status TEXT DEFAULT 'candidate', reason TEXT DEFAULT '', ts INTEGER, decided_ts INTEGER DEFAULT 0)") } catch (e: Exception) {}
        try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_lessons_status ON lessons(status)") } catch (e: Exception) {}
        try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_candidates_status ON fact_candidates(status)") } catch (e: Exception) {}
    }

    private fun ensureVc294SchemaLazyVc294() {
        try { ensureVc294SchemaVc294(writableDatabase) } catch (e: Exception) {}
    }

    // vc285 Fase 0: pengaman lazy ALTER kolom provenance untuk jalur yang
    // memanggil putFact sebelum onOpen sempat migrasi (jaga-jaga; onOpen
    // selalu jalan tiap DB dibuka, jadi normalnya sudah termigrasi).
    private fun ensureFactProvenanceColsVc285() {
        try { writableDatabase.execSQL("ALTER TABLE facts ADD COLUMN source TEXT DEFAULT ''") } catch (e: Exception) {}
        try { writableDatabase.execSQL("ALTER TABLE facts ADD COLUMN source_ts INTEGER DEFAULT 0") } catch (e: Exception) {}
        try { writableDatabase.execSQL("ALTER TABLE facts ADD COLUMN status TEXT DEFAULT 'active'") } catch (e: Exception) {}
        try { writableDatabase.execSQL("ALTER TABLE facts ADD COLUMN superseded_by TEXT DEFAULT ''") } catch (e: Exception) {}
        try { writableDatabase.execSQL("ALTER TABLE facts ADD COLUMN reason TEXT DEFAULT ''") } catch (e: Exception) {}
        try { writableDatabase.execSQL("ALTER TABLE facts ADD COLUMN updated_ts INTEGER DEFAULT 0") } catch (e: Exception) {}
    }

    // ---------- Koordinator OpenCode (Tahap D) ----------
    // vc243 GELOMBANG 2 (K-04): originSession = sesi 1-1 aktif SAAT ENQUEUE.
    // Berbeda dari sessionId (sesi OpenCode serve, ditulis runTask).
    data class OcTask(val id: Long, val chainId: String, val text: String, val sessionId: String,
                      val status: String, val result: String, val created: Long, val finished: Long,
                      val dir: String = "", val originSession: Long = 0)

    @Synchronized
    fun addOcTask(chainId: String, text: String, dir: String = "", originSession: Long = 0): Long {
        val cv = ContentValues()
        cv.put("chain_id", chainId); cv.put("text", text.take(4000)); cv.put("created", System.currentTimeMillis())
        if (originSession > 0) cv.put("origin_session", originSession)
        val db = writableDatabase
        ensureOcDirColumn(db)
        ensureWave2Columns(db)
        if (dir.isNotBlank()) cv.put("dir", dir)
        return try { db.insert("oc_tasks", null, cv) } catch (e: Exception) { -1 }
    }

    /** Kolom dir oc_tasks (J5) — lazy ALTER, pola yang sama dengan history.work. */
    private fun ensureOcDirColumn(db: SQLiteDatabase) {
        try { db.execSQL("ALTER TABLE oc_tasks ADD COLUMN dir TEXT DEFAULT ''") } catch (e: Exception) {}
        try { db.execSQL("ALTER TABLE oc_tasks ADD COLUMN origin_session INTEGER DEFAULT 0") } catch (e: Exception) {}
    }

    private fun ocFrom(c: android.database.Cursor): OcTask = OcTask(
        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
        c.getString(4) ?: "queued", c.getString(5) ?: "", c.getLong(6), c.getLong(7),
        if (c.columnCount > 8) c.getString(8) ?: "" else "",
        if (c.columnCount > 9) c.getLong(9) else 0)

    @Synchronized
    fun nextQueuedOcTask(): OcTask? {
        return try {
            val c = readableDatabase.rawQuery(
                "SELECT id, chain_id, text, session_id, status, result, created, finished, dir, origin_session FROM oc_tasks WHERE status='queued' ORDER BY id ASC LIMIT 1", null)
            val r = if (c.moveToFirst()) ocFrom(c) else null
            c.close(); r
        } catch (e: Exception) { null }
    }

    @Synchronized
    fun ocTask(id: Long): OcTask? {
        return try {
            val c = readableDatabase.rawQuery(
                "SELECT id, chain_id, text, session_id, status, result, created, finished, dir, origin_session FROM oc_tasks WHERE id=?", arrayOf(id.toString()))
            val r = if (c.moveToFirst()) ocFrom(c) else null
            c.close(); r
        } catch (e: Exception) { null }
    }

    @Synchronized
    fun recentOcTasks(limit: Int): List<OcTask> {
        val out = mutableListOf<OcTask>()
        try {
            val c = readableDatabase.rawQuery(
                "SELECT id, chain_id, text, session_id, status, result, created, finished, dir, origin_session FROM oc_tasks ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
            while (c.moveToNext()) out.add(ocFrom(c))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    @Synchronized
    fun countOcTasks(status: String): Int {
        return try {
            val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM oc_tasks WHERE status=?", arrayOf(status))
            val r = if (c.moveToFirst()) c.getInt(0) else 0
            c.close(); r
        } catch (e: Exception) { 0 }
    }

    /** Jumlah tugas SELESAI dalam satu rantai (gerbang kelanjutan rantai). */
    @Synchronized
    fun chainDoneCount(chainId: String): Int {
        return try {
            val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM oc_tasks WHERE chain_id=? AND status IN ('done','failed')", arrayOf(chainId))
            val r = if (c.moveToFirst()) c.getInt(0) else 0
            c.close(); r
        } catch (e: Exception) { 0 }
    }

    @Synchronized
    fun setOcTask(id: Long, status: String? = null, sessionId: String? = null, result: String? = null) {
        try {
            val cv = ContentValues()
            if (status != null) cv.put("status", status)
            if (sessionId != null) cv.put("session_id", sessionId)
            if (result != null) cv.put("result", result.take(6000))
            if (status == "done" || status == "failed" || status == "cancelled") cv.put("finished", System.currentTimeMillis())
            if (cv.size() > 0) writableDatabase.update("oc_tasks", cv, "id=?", arrayOf(id.toString()))
        } catch (e: Exception) {}
    }

    // ---------- Aturan koordinasi (Tahap D — milik harness, bisa diedit user) ----------
    @Synchronized
    fun coordRule(key: String, def: String): String {
        return try {
            val c = readableDatabase.rawQuery("SELECT v FROM coord_rules WHERE k=?", arrayOf(key))
            val r = if (c.moveToFirst()) (c.getString(0) ?: def) else def
            c.close(); r
        } catch (e: Exception) { def }
    }

    @Synchronized
    fun setCoordRule(key: String, value: String) {
        try {
            val cv = ContentValues()
            cv.put("k", key.take(60)); cv.put("v", value.take(1000))
            writableDatabase.insertWithOnConflict("coord_rules", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {}
    }

    @Synchronized
    fun allCoordRules(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, def) in OcCoord.RULE_DEFAULTS) out[k] = def
        try {
            val c = readableDatabase.rawQuery("SELECT k, v FROM coord_rules", null)
            while (c.moveToNext()) { val k = c.getString(0); if (k != null) out[k] = c.getString(1) ?: "" }
            c.close()
        } catch (e: Exception) {}
        return out
    }

    // ---------- Fakta ----------
    @Synchronized
    // vc228 R-03(a): kolom confirmed via lazy ALTER (pola lama, tanpa bump
    // versi). Fakta baru dari model = confirmed 0 (belum dikonfirmasi).
    // Fakta impor/backup/migrasi lama memanggil putFact(..., true).
    private fun ensureFactConfirmedCol() {
        try { writableDatabase.execSQL("ALTER TABLE facts ADD COLUMN confirmed INTEGER DEFAULT 0") } catch (e: Exception) {}
    }

    // vc285 Fase 0: baris fakta lengkap ber-provenance + hasil tulis.
    data class FactRowVc285(val id: Long, val k: String, val v: String, val ts: Long, val confirmed: Boolean, val source: String, val sourceTs: Long, val status: String, val supersededBy: String, val reason: String, val updatedTs: Long, val tags: String = "", val refPath: String = "")
    data class PutFactResultVc285(val status: String, val oldValue: String, val conflicts: List<String> = emptyList())

    // vc285 Fase 0: kosakata sumber TERTUTUP (ditulis tool layer, bukan
    // model bebas). Di luar kosakata ini = model:asserted.
    fun sanitizeFactSourceVc285(s: String): String {
        val t = s.trim()
        if (t == "user:chat" || t == "user:setup65" || t == "import:MEMORY.md" || t == "import:graph" || t == "restore:backup" || t == "model:asserted") return t
        if (t.startsWith("tool:") && t.drop(5).isNotBlank() && t.drop(5).none { it.isWhitespace() }) return t
        if (t.startsWith("turn:") && t.drop(5).isNotBlank() && t.drop(5).none { it.isWhitespace() }) return t
        if (t.startsWith("system:vc") && t.drop(9).isNotBlank() && t.drop(9).none { it.isWhitespace() }) return t
        return "model:asserted"
    }

    private fun factRowFromVc285(c: android.database.Cursor): FactRowVc285 {
        return FactRowVc285(
            if (c.getColumnIndex("id") >= 0) c.getLong(c.getColumnIndex("id")) else 0L,
            c.getString(c.getColumnIndex("k")) ?: "",
            c.getString(c.getColumnIndex("v")) ?: "",
            c.getLong(c.getColumnIndex("ts")),
            c.getInt(c.getColumnIndex("confirmed")) != 0,
            if (c.getColumnIndex("source") >= 0) c.getString(c.getColumnIndex("source")) ?: "" else "",
            if (c.getColumnIndex("source_ts") >= 0) c.getLong(c.getColumnIndex("source_ts")) else 0L,
            if (c.getColumnIndex("status") >= 0) c.getString(c.getColumnIndex("status")) ?: "active" else "active",
            if (c.getColumnIndex("superseded_by") >= 0) c.getString(c.getColumnIndex("superseded_by")) ?: "" else "",
            if (c.getColumnIndex("reason") >= 0) c.getString(c.getColumnIndex("reason")) ?: "" else "",
            if (c.getColumnIndex("updated_ts") >= 0) c.getLong(c.getColumnIndex("updated_ts")) else 0L,
            if (c.getColumnIndex("tags") >= 0) c.getString(c.getColumnIndex("tags")) ?: "" else "",
            if (c.getColumnIndex("ref_path") >= 0) c.getString(c.getColumnIndex("ref_path")) ?: "" else ""
        )
    }

    // vc285 Fase 0: SEMUA baris fakta (aktif + superseded + withdrawn),
    // terbaru dulu. Jalur retrieval / PIN / claim-matching TIDAK memakai
    // ini langsung — mereka memakai factsAllWithStatus (aktif saja).
    fun factsAllFullVc285(): List<FactRowVc285> {
        val out = ArrayList<FactRowVc285>()
        try {
            ensureFactConfirmedCol()
            ensureFactProvenanceColsVc285()
            val c = readableDatabase.rawQuery("SELECT id,k,v,ts,confirmed,source,source_ts,status,superseded_by,reason,updated_ts,tags,ref_path FROM facts ORDER BY ts DESC", null)
            while (c.moveToNext()) out.add(factRowFromVc285(c))
            c.close()
        } catch (e: Exception) {
            try {
                val c = readableDatabase.rawQuery("SELECT k,v,confirmed FROM facts ORDER BY ts DESC", null)
                while (c.moveToNext()) out.add(FactRowVc285(0L, c.getString(0) ?: "", c.getString(1) ?: "", 0L, c.getInt(2) != 0, "model:asserted", 0L, "active", "", "", 0L, "", ""))
                c.close()
            } catch (e2: Exception) {}
        }
        return out
    }

    // vc285 Fase 0: cari satu fakta berdasar key (normalisasi sama dengan
    // putFact: trim+lowercase + varian tanpa pemisah). Baris aktif
    // didahulukan; bila tidak ada, baris non-aktif terbaru.
    fun factByKeyVc285(k: String): FactRowVc285? {
        val nk = k.trim().lowercase()
        if (nk.isBlank()) return null
        val flat = { s: String -> s.filter { ch -> ch != '_' && ch != '-' && ch != ' ' } }
        val flatNk = flat(nk)
        val cands = factsAllFullVc285().filter { val ok = it.k.trim().lowercase(); ok == nk || flat(ok) == flatNk }
        if (cands.isEmpty()) return null
        return cands.firstOrNull { it.status == "active" } ?: cands.maxByOrNull { it.updatedTs }
    }

    // vc285 Fase 0: riwayat fakta — baris superseded + withdrawn, terbaru
    // dulu (berdasar updated_ts). Dipakai panel riwayat fakta Setup 65
    // dan recall_facts(status=...).
    fun factsHistoryVc285(limit: Int = 50): List<FactRowVc285> {
        return factsAllFullVc285().filter { it.status == "superseded" || it.status == "withdrawn" }.sortedByDescending { it.updatedTs }.take(limit.coerceAtLeast(1))
    }

    // vc285 Fase 0: tarik fakta aktif menjadi withdrawn (BUKAN hapus).
    // Mengembalikan (status, nilai lama, apakah dulu terkonfirmasi).
    // status: withdrawn | already_withdrawn | not_found. Baris superseded
    // bukan baris aktif — forget atas key itu = not_found.
    @Synchronized
    fun withdrawFactVc285(k: String, reason: String): Triple<String, String, Boolean> {
        try {
            ensureFactConfirmedCol()
            ensureFactProvenanceColsVc285()
            val row = factByKeyVc285(k) ?: return Triple("not_found", "", false)
            if (row.status == "withdrawn") return Triple("already_withdrawn", row.v, row.confirmed)
            if (row.status != "active") return Triple("not_found", row.v, row.confirmed)
            try {
                archiveDelete("fact", row.k, JSONObject().put("k", row.k).put("v", row.v).put("confirmed", row.confirmed).put("source", row.source).put("withdrawn_reason", reason.take(200)).put("withdrawn_ts", System.currentTimeMillis()).toString())
            } catch (e: Exception) {}
            val cv = ContentValues()
            cv.put("status", "withdrawn")
            cv.put("reason", reason.take(200))
            cv.put("updated_ts", System.currentTimeMillis())
            val n = if (row.id > 0) writableDatabase.update("facts", cv, "id=?", arrayOf(row.id.toString())) else writableDatabase.update("facts", cv, "k=? AND (status IS NULL OR status='active')", arrayOf(row.k))
            if (n <= 0) return Triple("not_found", row.v, row.confirmed)
            try { deleteEmbedding("fact", row.k) } catch (e: Exception) {}
            return Triple("withdrawn", row.v, row.confirmed)
        } catch (e: Exception) {
            return Triple("not_found", "", false)
        }
    }

    // vc285 Fase 0: pulihkan satu baris non-aktif (withdrawn/superseded)
    // menjadi aktif, berdasar id baris. Gagal (false) bila baris tidak ada,
    // sudah aktif, atau sudah ada baris aktif lain dengan key sama.
    @Synchronized
    fun restoreFactByIdVc285(id: Long): Boolean {
        try {
            ensureFactConfirmedCol()
            ensureFactProvenanceColsVc285()
            val row = factsAllFullVc285().firstOrNull { it.id == id } ?: return false
            if (row.status == "active") return false
            val nk = row.k.trim().lowercase()
            val flat = { s: String -> s.filter { ch -> ch != '_' && ch != '-' && ch != ' ' } }
            val clash = factsAllFullVc285().firstOrNull { cand ->
                if (cand.status != "active" || cand.id == id) false
                else { val ok = cand.k.trim().lowercase(); ok == nk || flat(ok) == flat(nk) }
            }
            if (clash != null) return false
            val cv = ContentValues()
            cv.put("status", "active")
            cv.put("reason", "")
            cv.put("updated_ts", System.currentTimeMillis())
            val n = writableDatabase.update("facts", cv, "id=?", arrayOf(id.toString()))
            if (n > 0) {
                try { Embedder.embedAsync(appCtx, "fact", row.k, "${row.k}: ${row.v}") } catch (e: Exception) {}
                return true
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    // vc285 Fase 0: pulihkan berdasar key (baris non-aktif terbaru).
    fun restoreFactVc285(k: String): Boolean {
        val row = factByKeyVc285(k) ?: return false
        if (row.status == "active") return false
        return restoreFactByIdVc285(row.id)
    }

    @Synchronized
    fun putFact(k: String, v: String, confirmed: Boolean = false, source: String = "model:asserted", tags: String = "", refPath: String = ""): PutFactResultVc285 {
        try {
            ensureFactConfirmedCol()
            ensureFactProvenanceColsVc285()
            // vc233 K1a: normalisasi key (trim + lowercase, minor m14) dan
            // resolusi kontradiksi untuk SEMUA jalur tulis fakta. Key lama
            // vs baru yang hanya beda normalisasi diperlakukan sebagai key
            // yang sama; varian tanpa pemisah (_/-spasi) juga disamakan.
            // vc285 Fase 0: bila nilai lama BERTENTANGAN dengan nilai baru,
            // arsipkan nilai lama + status confirmed lama ke deleted_archive
            // SEBELUM timpa, lalu baris lama menjadi status='superseded'
            // (superseded_by = key baru, updated_ts = sekarang) — TIDAK
            // LAGI dihapus dari facts, jadi nilai lama tetap bisa ditanya.
            // Bila nilainya SAMA: tidak ada arsip, status confirmed lama
            // dipertahankan (tidak di-reset ke false) — hasil 'duplicate'.
            val nk = k.trim().lowercase().take(80)
            val nv = v.take(500)
            if (nk.isBlank()) return PutFactResultVc285("inserted", "")
            val srcVc285 = sanitizeFactSourceVc285(source)
            // vc294: concept tag dinormalisasi (2-5 tag spesifik lowercase;
            // generik/terlalu pendek dibuang — lihat MemoryLoopVc294).
            val tagsVc294 = MemoryLoopVc294.tagsCanonical(tags)
            val refVc294 = refPath.trim().take(200)
            val nowVc285 = System.currentTimeMillis()
            val flat = { s: String -> s.filter { ch -> ch != '_' && ch != '-' && ch != ' ' } }
            val flatNk = flat(nk)
            val olds = factsAllFullVc285().filter { it.status == "active" }.filter { val nok = it.k.trim().lowercase(); nok == nk || flat(nok) == flatNk }
            val sameVc285 = olds.firstOrNull { it.v == nv }
            if (sameVc285 != null) {
                if (confirmed && !sameVc285.confirmed) {
                    try {
                        val ccv = ContentValues(); ccv.put("confirmed", 1)
                        if (sameVc285.id > 0) writableDatabase.update("facts", ccv, "id=?", arrayOf(sameVc285.id.toString()))
                        else writableDatabase.update("facts", ccv, "k=? AND (status IS NULL OR status='active')", arrayOf(sameVc285.k))
                    } catch (e: Exception) {}
                }
                return PutFactResultVc285("duplicate", sameVc285.v)
            }
            var oldValueVc285 = ""
            for (old in olds) {
                if (oldValueVc285.isEmpty()) oldValueVc285 = old.v
                try {
                    archiveDelete("fact", old.k, JSONObject().put("k", old.k).put("v", old.v).put("confirmed", old.confirmed).put("source", old.source).put("superseded_by", nk).put("superseded_ts", nowVc285).toString())
                } catch (e: Exception) {}
                try {
                    val scv = ContentValues()
                    scv.put("status", "superseded")
                    scv.put("superseded_by", nk)
                    scv.put("updated_ts", nowVc285)
                    if (old.id > 0) writableDatabase.update("facts", scv, "id=?", arrayOf(old.id.toString()))
                    else writableDatabase.update("facts", scv, "k=? AND (status IS NULL OR status='active')", arrayOf(old.k))
                } catch (e: Exception) {}
                try { deleteEmbedding("fact", old.k) } catch (e: Exception) {}
            }
            val cv = ContentValues()
            cv.put("k", nk); cv.put("v", nv); cv.put("ts", nowVc285)
            cv.put("confirmed", if (confirmed) 1 else 0)
            cv.put("source", srcVc285); cv.put("source_ts", nowVc285); cv.put("status", "active")
            cv.put("superseded_by", ""); cv.put("reason", ""); cv.put("updated_ts", nowVc285)
            cv.put("tags", tagsVc294); cv.put("ref_path", refVc294)
            writableDatabase.insert("facts", null, cv)
            // vc289 Fase 3: tautkan fakta baru ke fakta aktif lain
            // (graph mini, pencocokan pola — hanya menulis fact_edges).
            try { linkFactVc289(nk) } catch (e: Exception) {}
            // vc294: deteksi konflik SAAT TULIS — fakta baru vs fakta aktif
            // lain (flag ke dream_flags, HANYA penanda — tidak ada fakta
            // yang diubah/dihapus di sini).
            val conflictsVc294 = try { flagConflictsForVc294(nk, nv) } catch (e: Exception) { emptyList<String>() }
            Embedder.embedAsync(appCtx, "fact", nk, "$nk: $nv")
            return if (olds.isEmpty()) PutFactResultVc285("inserted", "", conflictsVc294) else PutFactResultVc285("superseded", oldValueVc285, conflictsVc294)
        } catch (e: Exception) {
            return PutFactResultVc285("inserted", "")
        }
    }

    // ---------- vc289 Fase 3: graph mini ber-type + dream cycle ----------
    // Relasi = TIPE EDGE (bukan tipe fakta — keputusan Sendy spek §(f) Q2
    // tetap: fakta hanya punya source + status, tanpa tipe). Kosakata relasi:
    // "bagian-dari" (key anak diawali key induk + pemisah), "menyebut"
    // (nilai fakta A menyebut key fakta B utuh), "terkait" (key berbagi
    // >=1 kata bermakna). Semua diturunkan pencocokan pola deterministik
    // (tanpa LLM, tanpa model) atas fakta AKTIF saja. Edge disimpan di
    // tabel fact_edges; retrieval hanya menampilkan edge yang kedua
    // ujungnya masih aktif. TIDAK ADA penghapusan fakta di sini — edge
    // adalah data turunan, bukan data sumber. Dream cycle HANYA menandai
    // (duplikat/konflik) ke tabel dream_flags — tidak pernah mengubah
    // atau menghapus fakta; perbaikan tetap lewat remember_fact /
    // forget_fact yang sudah ada. Tidak ada daemon, tidak ada auto-fix.
    data class FactEdgeVc289(val fromK: String, val toK: String, val rel: String)
    data class DreamFlagDraftVc289(val kind: String, val factA: String, val factB: String, val detail: String)
    data class DreamFlagVc289(val id: Long, val kind: String, val factA: String, val factB: String, val detail: String, val status: String, val createdTs: Long)

    private fun ensureFactEdgesVc289() {
        try { writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS fact_edges(from_k TEXT, to_k TEXT, rel TEXT, created_ts INTEGER, PRIMARY KEY(from_k, to_k, rel))") } catch (e: Exception) {}
        try { writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_edges_from ON fact_edges(from_k)") } catch (e: Exception) {}
        try { writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_edges_to ON fact_edges(to_k)") } catch (e: Exception) {}
    }

    private fun ensureDreamFlagsVc289() {
        try { writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS dream_flags(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, fact_a TEXT, fact_b TEXT, detail TEXT, status TEXT DEFAULT 'open', created_ts INTEGER, resolved_ts INTEGER DEFAULT 0)") } catch (e: Exception) {}
    }

    // vc289: derivasi edge murni (tanpa Android, bisa unit-test standalone).
    // Masukan = daftar (key, value) fakta AKTIF (key sudah normalisasi
    // lowercase). Prioritas per pasangan: bagian-dari > menyebut > terkait.
    // Cap: maks 10 edge per from-key, total maks 200 (urutan stabil).
    fun deriveFactEdgesVc289(facts: List<Pair<String, String>>): List<FactEdgeVc289> {
        val stopVc289 = setOf("yang", "dan", "di", "ke", "dari", "untuk", "adalah", "ini", "itu", "dengan", "pada", "dalam", "atau", "juga", "sudah", "belum", "akan", "bisa", "saja", "punya", "ada", "tidak", "bukan", "sangat", "lebih", "paling", "sebuah", "oleh", "saat", "jika", "kalau", "karena", "supaya", "agar", "sebagai", "tentang", "antara", "setelah", "sebelum", "sampai", "serta", "tapi", "tetapi", "namun", "maka", "lalu", "hari", "tanggal", "bulan", "tahun", "jam", "nomor", "nama", "suka", "saya", "kamu", "dia", "kami", "kita", "mereka")
        val normVc289 = { s: String -> s.trim().lowercase() }
        val wordsVc289 = { s: String -> normVc289(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in stopVc289 }.toSet() }
        val keysVc289 = facts.map { normVc289(it.first) }.distinct().filter { it.isNotBlank() }
        val valsVc289 = HashMap<String, String>()
        for (f in facts) { val nk = normVc289(f.first); if (nk.isNotBlank() && !valsVc289.containsKey(nk)) valsVc289[nk] = f.second ?: "" }
        val sepsVc289 = listOf(".", "/", " ", "_", "-")
        val outVc289 = LinkedHashSet<FactEdgeVc289>()
        for (a in keysVc289) {
            for (b in keysVc289) {
                if (a == b) continue
                if (sepsVc289.any { b.startsWith(a + it) }) { outVc289.add(FactEdgeVc289(a, b, "bagian-dari")); continue }
                if (b.length >= 3) {
                    val vbVc289 = (valsVc289[a] ?: "").lowercase()
                    if (Regex("(^|[^a-z0-9])" + Regex.escape(b) + "([^a-z0-9]|$)").containsMatchIn(vbVc289)) { outVc289.add(FactEdgeVc289(a, b, "menyebut")); continue }
                }
                if (a < b && wordsVc289(a).intersect(wordsVc289(b)).isNotEmpty()) outVc289.add(FactEdgeVc289(a, b, "terkait"))
            }
        }
        val perFromVc289 = HashMap<String, Int>()
        val cappedVc289 = ArrayList<FactEdgeVc289>()
        val relRankVc289 = mapOf("bagian-dari" to 0, "menyebut" to 1, "terkait" to 2)
        for (e in outVc289.sortedWith(compareBy({ relRankVc289[it.rel] ?: 9 }, { it.fromK }, { it.toK }))) {
            val n = perFromVc289[e.fromK] ?: 0
            if (n >= 10 || cappedVc289.size >= 200) continue
            perFromVc289[e.fromK] = n + 1
            cappedVc289.add(e)
        }
        return cappedVc289
    }

    // vc289: pemindaian dream cycle murni (tanpa Android). Mengembalikan
    // draf flag (kind, factA, factB, detail), urutan stabil, maks 50.
    // "duplikat": dua key berbeda, nilai sama persis (normalisasi).
    // "konflik": satu key hierarki dari yang lain + nilai berbeda +
    // tepat satu nilai mengandung kata negasi — HANYA flag kemungkinan,
    // bukan verdict; user yang memutuskan lewat jalur fakta biasa.
    fun scanDreamFlagsVc289(active: List<Pair<String, String>>): List<DreamFlagDraftVc289> {
        val normVc289 = { s: String -> s.trim().lowercase() }
        // vc299 N6-m5: pakai hasNegation yang sama dengan MemoryLoopVc294
        // (tahan tanda baca akhir kata) — satu pola, dua situs.
        val hasNegVc289 = { s: String -> MemoryLoopVc294.hasNegation(normVc289(s)) }
        val sepsVc289 = listOf(".", "/", " ", "_", "-")
        val keysVc289 = active.map { normVc289(it.first) }.distinct().filter { it.isNotBlank() }
        val valByKeyVc289 = HashMap<String, String>()
        for (f in active) { val nk = normVc289(f.first); if (nk.isNotBlank() && !valByKeyVc289.containsKey(nk)) valByKeyVc289[nk] = f.second ?: "" }
        val outVc289 = ArrayList<DreamFlagDraftVc289>()
        for (i in keysVc289.indices) {
            for (j in i + 1 until keysVc289.size) {
                val a = keysVc289[i]; val b = keysVc289[j]
                val va = valByKeyVc289[a] ?: ""; val vb = valByKeyVc289[b] ?: ""
                if (normVc289(va).length >= 3 && normVc289(va) == normVc289(vb)) {
                    outVc289.add(DreamFlagDraftVc289("duplikat", a, b, "nilai sama: '" + va.take(80) + "'"))
                    continue
                }
                val hierVc289 = sepsVc289.any { b.startsWith(a + it) || a.startsWith(b + it) }
                if (hierVc289 && normVc289(va) != normVc289(vb) && hasNegVc289(va) != hasNegVc289(vb)) {
                    outVc289.add(DreamFlagDraftVc289("konflik", a, b, "nilai bertentangan (satu mengandung negasi): '" + va.take(60) + "' vs '" + vb.take(60) + "'"))
                }
            }
        }
        return outVc289.sortedWith(compareBy({ it.kind }, { it.factA }, { it.factB })).take(50)
    }

    // vc289: tautkan satu fakta baru ke fakta aktif lain (dipanggil dari
    // putFact setelah insert berhasil). Hanya menulis tabel fact_edges.
    @Synchronized
    fun linkFactVc289(k: String) {
        try {
            ensureFactEdgesVc289()
            val nk = k.trim().lowercase()
            if (nk.isBlank()) return
            val active = factsAllFullVc285().filter { it.status == "active" }.map { it.k.trim().lowercase() to it.v }
            val edges = deriveFactEdgesVc289(active).filter { it.fromK == nk || it.toK == nk }
            val now = System.currentTimeMillis()
            for (e in edges) {
                try {
                    val cv = ContentValues()
                    cv.put("from_k", e.fromK); cv.put("to_k", e.toK); cv.put("rel", e.rel); cv.put("created_ts", now)
                    writableDatabase.insertWithOnConflict("fact_edges", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                } catch (e2: Exception) {}
            }
        } catch (e: Exception) {}
    }

    // vc289: bangun ulang SEMUA edge dari fakta aktif (hapus + hitung
    // ulang, transaksional). Mengembalikan jumlah edge, -1 bila gagal.
    // Tidak menyentuh tabel facts.
    @Synchronized
    fun rebuildFactEdgesVc289(): Int {
        return try {
            ensureFactEdgesVc289()
            val active = factsAllFullVc285().filter { it.status == "active" }.map { it.k.trim().lowercase() to it.v }
            val edges = deriveFactEdgesVc289(active)
            writableDatabase.beginTransaction()
            try {
                writableDatabase.delete("fact_edges", null, null)
                val now = System.currentTimeMillis()
                for (e in edges) {
                    val cv = ContentValues()
                    cv.put("from_k", e.fromK); cv.put("to_k", e.toK); cv.put("rel", e.rel); cv.put("created_ts", now)
                    writableDatabase.insertWithOnConflict("fact_edges", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                }
                writableDatabase.setTransactionSuccessful()
            } finally { writableDatabase.endTransaction() }
            edges.size
        } catch (e: Exception) { -1 }
    }

    // vc289: edge untuk satu key — hanya yang kedua ujungnya masih aktif.
    fun factEdgesForVc289(k: String): List<FactEdgeVc289> {
        return try {
            ensureFactEdgesVc289()
            val nk = k.trim().lowercase()
            val activeKeys = factsAllFullVc285().filter { it.status == "active" }.map { it.k.trim().lowercase() }.toSet()
            val out = ArrayList<FactEdgeVc289>()
            val c = readableDatabase.rawQuery("SELECT from_k, to_k, rel FROM fact_edges WHERE from_k = ? OR to_k = ? ORDER BY rel, to_k", arrayOf(nk, nk))
            while (c.moveToNext()) {
                val f = c.getString(0) ?: ""; val t = c.getString(1) ?: ""; val r = c.getString(2) ?: ""
                if (f in activeKeys && t in activeKeys) out.add(FactEdgeVc289(f, t, r))
            }
            c.close()
            out.take(20)
        } catch (e: Exception) { emptyList() }
    }

    // vc289: teks fakta tertaut 1 hop untuk recall (maks 5 baris).
    fun linkedFactsTextVc289(k: String): String {
        val edges = factEdgesForVc289(k)
        if (edges.isEmpty()) return ""
        val byKey = factsAllFullVc285().filter { it.status == "active" }.associate { it.k.trim().lowercase() to it.v }
        val lines = edges.take(5).map { e ->
            val other = if (e.fromK == k.trim().lowercase()) e.toK else e.fromK
            "- " + other + ": " + (byKey[other] ?: "").take(120) + " [relasi: " + e.rel + " — " + e.fromK + " → " + e.toK + "]"
        }
        return lines.joinToString("\n")
    }

    // vc289: JALANKAN dream cycle (pemicu eksplisit via tool dream_cycle,
    // BUKAN daemon / BUKAN otomatis). Memindai fakta aktif, menulis flag
    // BARU ke dream_flags (dedupe terhadap flag open yang sama), dan
    // menandai resolved flag open yang salah satu faktanya sudah tidak
    // aktif. TIDAK PERNAH mengubah/menghapus fakta. Mengembalikan laporan.
    @Synchronized
    fun runDreamCycleVc289(charging: Boolean): String {
        return try {
            ensureDreamFlagsVc289()
            val active = factsAllFullVc285().filter { it.status == "active" }.map { it.k.trim().lowercase() to it.v }
            val activeKeys = active.map { it.first }.toSet()
            val found = scanDreamFlagsVc289(active)
            val now = System.currentTimeMillis()
            var baru = 0
            for (f in found) {
                val exists = try {
                    val c = readableDatabase.rawQuery("SELECT id FROM dream_flags WHERE kind = ? AND fact_a = ? AND fact_b = ? AND status = 'open'", arrayOf(f.kind, f.factA, f.factB))
                    val has = c.moveToFirst(); c.close(); has
                } catch (e: Exception) { false }
                if (!exists) {
                    val cv = ContentValues()
                    cv.put("kind", f.kind); cv.put("fact_a", f.factA); cv.put("fact_b", f.factB); cv.put("detail", f.detail); cv.put("status", "open"); cv.put("created_ts", now); cv.put("resolved_ts", 0)
                    writableDatabase.insert("dream_flags", null, cv)
                    baru++
                }
            }
            var selesai = 0
            try {
                val c = readableDatabase.rawQuery("SELECT id, fact_a, fact_b FROM dream_flags WHERE status = 'open'", null)
                val ids = ArrayList<Triple<Long, String, String>>()
                while (c.moveToNext()) ids.add(Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
                c.close()
                for ((id, fa, fb) in ids) {
                    if (fa !in activeKeys || fb !in activeKeys) {
                        val cv = ContentValues(); cv.put("status", "resolved"); cv.put("resolved_ts", now)
                        if (writableDatabase.update("dream_flags", cv, "id = ?", arrayOf(id.toString())) > 0) selesai++
                    }
                }
            } catch (e: Exception) {}
            val open = listDreamFlagsVc289()
            val sb = StringBuilder()
            sb.append("Dream cycle selesai (kondisi: " + (if (charging) "charging" else "tidak charging — tetap jalan atas perintah eksplisit, bukan otomatis") + "; fakta aktif diperiksa: " + active.size + "; pasangan ditandai: " + found.size + " (" + baru + " baru, " + selesai + " lama terselesaikan karena faktanya sudah tidak aktif)).")
            if (open.isEmpty()) sb.append("\nTidak ada flag terbuka — tidak ada duplikat/konflik yang terdeteksi. TIDAK ADA fakta yang diubah atau dihapus.")
            else {
                sb.append("\nFlag terbuka (" + open.size + "):")
                for (f in open.take(20)) sb.append("\n- [" + f.kind + "] " + f.factA + " ↔ " + f.factB + ": " + f.detail)
                sb.append("\nSemua ini HANYA penanda — tidak ada fakta yang diubah/dihapus. Perbaiki lewat remember_fact (koreksi) atau forget_fact (tarik).")
            }
            "[STATUS: ok] " + sb.toString()
        } catch (e: Exception) { "Dream cycle gagal: " + e.message }
    }

    // vc289: daftar flag dream cycle yang masih terbuka — hanya yang
    // kedua faktanya masih aktif (lazy filter, tanpa mengubah tabel).
    fun listDreamFlagsVc289(): List<DreamFlagVc289> {
        return try {
            ensureDreamFlagsVc289()
            val activeKeys = factsAllFullVc285().filter { it.status == "active" }.map { it.k.trim().lowercase() }.toSet()
            val out = ArrayList<DreamFlagVc289>()
            val c = readableDatabase.rawQuery("SELECT id, kind, fact_a, fact_b, detail, status, created_ts FROM dream_flags WHERE status = 'open' ORDER BY created_ts DESC LIMIT 50", null)
            while (c.moveToNext()) {
                val a = c.getString(2) ?: ""; val b = c.getString(3) ?: ""
                if (a in activeKeys && b in activeKeys) out.add(DreamFlagVc289(c.getLong(0), c.getString(1) ?: "", a, b, c.getString(4) ?: "", c.getString(5) ?: "open", c.getLong(6)))
            }
            c.close()
            out
        } catch (e: Exception) { emptyList() }
    }

    // ---------- vc294: Loop memori gbrain ----------
    // Lesson = tabel TERPISAH (keputusan arsitektur, lihat BUILD-VC294.md):
    // lesson itu perilaku ("jangan X karena Y"), bukan fakta k/v — punya
    // confidence + penguatan + jalur hapus sendiri, dan TIDAK masuk FAKTA
    // PIN / claim-check / backup fakta. Satu-satunya tipe klaim baru yang
    // diizinkan (pengecualian Gas Sendy 11 Nov); tipe fakta lain TIDAK
    // ditambah (keputusan Sendy 10 Nov tetap).
    data class LessonVc294(val id: Long, val text: String, val confidence: Double, val hits: Int, val status: String, val source: String, val reason: String, val ts: Long, val updatedTs: Long)
    data class FactCandidateVc294(val id: Long, val k: String, val v: String, val tags: String, val refPath: String, val source: String, val status: String, val reason: String, val ts: Long, val decidedTs: Long)

    private fun lessonFromVc294(c: android.database.Cursor): LessonVc294 {
        return LessonVc294(c.getLong(0), c.getString(1) ?: "", c.getDouble(2), c.getInt(3),
            c.getString(4) ?: "active", c.getString(5) ?: "", c.getString(6) ?: "", c.getLong(7), c.getLong(8))
    }

    private fun candidateFromVc294(c: android.database.Cursor): FactCandidateVc294 {
        return FactCandidateVc294(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "",
            c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "",
            c.getString(6) ?: "candidate", c.getString(7) ?: "", c.getLong(8), c.getLong(9))
    }

    /** vc294: simpan lesson. Koreksi berulang yang sama = MEMPERKUAT
     *  (confidence +0.1, cap 0.95, hits+1), BUKAN duplikat baris.
     *  Mengembalikan (status, id, confidence): inserted | strengthened. */
    @Synchronized
    fun saveLessonVc294(text: String, source: String = "model:asserted"): Triple<String, Long, Double> {
        ensureVc294SchemaLazyVc294()
        val clean = text.trim().take(500)
        if (clean.isBlank()) return Triple("empty", -1, 0.0)
        val key = MemoryLoopVc294.lessonKey(clean)
        val src = sanitizeFactSourceVc285(source)
        val now = System.currentTimeMillis()
        return try {
            val c = readableDatabase.rawQuery("SELECT id,text,confidence,hits,status,source,reason,ts,updated_ts FROM lessons WHERE status='active'", null)
            var found: LessonVc294? = null
            while (c.moveToNext()) { val l = lessonFromVc294(c); if (MemoryLoopVc294.lessonKey(l.text) == key) { found = l; break } }
            c.close()
            if (found != null) {
                val nc = MemoryLoopVc294.strengthenConfidence(found.confidence)
                val cv = ContentValues(); cv.put("confidence", nc); cv.put("hits", found.hits + 1); cv.put("updated_ts", now)
                writableDatabase.update("lessons", cv, "id=?", arrayOf(found.id.toString()))
                Triple("strengthened", found.id, nc)
            } else {
                val cv = ContentValues()
                cv.put("text", clean); cv.put("confidence", 0.7); cv.put("hits", 1)
                cv.put("status", "active"); cv.put("source", src); cv.put("reason", "")
                cv.put("ts", now); cv.put("updated_ts", now)
                val id = writableDatabase.insert("lessons", null, cv)
                Triple("inserted", id, 0.7)
            }
        } catch (e: Exception) { Triple("empty", -1, 0.0) }
    }

    /** vc299 N6-m9: cari lesson by-text di SEMUA baris (tanpa cap 200) —
     *  lesson ke-201+ tetap ditemukan via text. Tanpa LIMIT di SQL. */
    private fun findLessonByKeyVc299(key: String): LessonVc294? {
        return try {
            val c = readableDatabase.rawQuery("SELECT id,text,confidence,hits,status,source,reason,ts,updated_ts FROM lessons ORDER BY updated_ts DESC", null)
            var found: LessonVc294? = null
            while (c.moveToNext()) { val l = lessonFromVc294(c); if (MemoryLoopVc294.lessonKey(l.text) == key) { found = l; break } }
            c.close(); found
        } catch (e: Exception) { null }
    }

    /** vc294: hapus lesson — jalur TERPISAH dari hapus memori (forget_fact).
     *  Status jadi withdrawn, baris tetap ada (bisa dipulihkan).
     *  Idempotent: tarik ulang = already_withdrawn. */
    @Synchronized
    fun withdrawLessonVc294(id: Long = -1, text: String = "", reason: String = ""): Triple<String, Long, Double> {
        ensureVc294SchemaLazyVc294()
        return try {
            val target: LessonVc294? = if (id > 0) {
                val c = readableDatabase.rawQuery("SELECT id,text,confidence,hits,status,source,reason,ts,updated_ts FROM lessons WHERE id=?", arrayOf(id.toString()))
                val r = if (c.moveToFirst()) lessonFromVc294(c) else null; c.close(); r
            } else if (text.isNotBlank()) {
                findLessonByKeyVc299(MemoryLoopVc294.lessonKey(text))
            } else null
            if (target == null) Triple("not_found", -1, 0.0)
            else if (target.status == "withdrawn") Triple("already_withdrawn", target.id, target.confidence)
            else {
                val cv = ContentValues(); cv.put("status", "withdrawn"); cv.put("reason", reason.take(200)); cv.put("updated_ts", System.currentTimeMillis())
                writableDatabase.update("lessons", cv, "id=?", arrayOf(target.id.toString()))
                Triple("withdrawn", target.id, target.confidence)
            }
        } catch (e: Exception) { Triple("not_found", -1, 0.0) }
    }

    fun restoreLessonVc294(id: Long): Boolean {
        ensureVc294SchemaLazyVc294()
        return try {
            val cv = ContentValues(); cv.put("status", "active"); cv.put("reason", ""); cv.put("updated_ts", System.currentTimeMillis())
            writableDatabase.update("lessons", cv, "id=? AND status='withdrawn'", arrayOf(id.toString())) > 0
        } catch (e: Exception) { false }
    }

    fun listLessonsVc294(status: String = "active", limit: Int = 50): List<LessonVc294> {
        ensureVc294SchemaLazyVc294()
        val out = ArrayList<LessonVc294>()
        return try {
            val sel = if (status == "all") null else status
            val c = if (sel == null) readableDatabase.rawQuery("SELECT id,text,confidence,hits,status,source,reason,ts,updated_ts FROM lessons ORDER BY updated_ts DESC LIMIT ?", arrayOf(limit.coerceAtLeast(1).toString()))
                else readableDatabase.rawQuery("SELECT id,text,confidence,hits,status,source,reason,ts,updated_ts FROM lessons WHERE status=? ORDER BY updated_ts DESC LIMIT ?", arrayOf(sel, limit.coerceAtLeast(1).toString()))
            while (c.moveToNext()) out.add(lessonFromVc294(c))
            c.close(); out
        } catch (e: Exception) { out }
    }

    /** vc294: lesson relevan untuk satu pesan — top 3 aktif, diranking
     *  kata kunci (tanpa skor di output: confidence tampil sebagai
     *  angka kepercayaan lesson, BUKAN skor retrieval). Cap 600 char.
     *  Dipakai injeksi prompt 1-1 (MainActivity) + recall_lessons. */
    fun lessonsTextVc294(query: String): String {
        val act = listLessonsVc294("active", 50)
        if (act.isEmpty()) return ""
        val toks = relevanceTokens(query)
        val ranked = if (toks.isEmpty()) act.sortedByDescending { it.updatedTs }
            else act.sortedWith(compareByDescending<LessonVc294> { relevanceScore(toks, it.text.lowercase()) }.thenByDescending { it.confidence })
        val top = ranked.filter { toks.isEmpty() || relevanceScore(toks, it.text.lowercase()) > 0.0 }.take(3)
        if (top.isEmpty()) return ""
        val lines = top.map { l -> "- [lesson, keyakinan " + String.format("%.2f", l.confidence) + ", dipakai " + l.hits + "x] " + l.text.take(120) }
        val out = lines.joinToString("\n")
        return if (out.length > 600) out.take(600) + "…" else out
    }

    /** vc294: usulkan fakta kandidat — MASUK antrean review, BELUM masuk
     *  store aktif. Duplikat kandidat terbuka yang sama = kembalikan id
     *  lama (bukan baris baru). */
    @Synchronized
    fun proposeFactVc294(k: String, v: String, tags: String = "", refPath: String = "", source: String = "model:asserted"): Triple<Long, String, String> {
        ensureVc294SchemaLazyVc294()
        val nk = k.trim().lowercase().take(80)
        val nv = v.trim().take(500)
        if (nk.isBlank() || nv.isBlank()) return Triple(-1, "empty", "key dan value wajib diisi.")
        val src = sanitizeFactSourceVc285(source)
        val now = System.currentTimeMillis()
        return try {
            val open = listCandidatesVc294("candidate", 200)
            val dup = open.firstOrNull { it.k == nk && it.v == nv }
            if (dup != null) return Triple(dup.id, "duplicate", "Kandidat yang sama sudah menunggu (#${dup.id}) — tidak dibuat duplikat.")
            val cv = ContentValues()
            cv.put("k", nk); cv.put("v", nv); cv.put("tags", MemoryLoopVc294.tagsCanonical(tags))
            cv.put("ref_path", refPath.trim().take(200)); cv.put("source", src)
            cv.put("status", "candidate"); cv.put("reason", ""); cv.put("ts", now); cv.put("decided_ts", 0)
            val id = writableDatabase.insert("fact_candidates", null, cv)
            Triple(id, "candidate", "Kandidat #$id menunggu persetujuan — BELUM masuk store aktif.")
        } catch (e: Exception) { Triple(-1, "empty", "Gagal menyimpan kandidat: " + (e.message ?: "?")) }
    }

    fun listCandidatesVc294(status: String = "candidate", limit: Int = 50): List<FactCandidateVc294> {
        ensureVc294SchemaLazyVc294()
        val out = ArrayList<FactCandidateVc294>()
        return try {
            val c = if (status == "all") readableDatabase.rawQuery("SELECT id,k,v,tags,ref_path,source,status,reason,ts,decided_ts FROM fact_candidates ORDER BY ts DESC LIMIT ?", arrayOf(limit.coerceAtLeast(1).toString()))
                else readableDatabase.rawQuery("SELECT id,k,v,tags,ref_path,source,status,reason,ts,decided_ts FROM fact_candidates WHERE status=? ORDER BY ts DESC LIMIT ?", arrayOf(status, limit.coerceAtLeast(1).toString()))
            while (c.moveToNext()) out.add(candidateFromVc294(c))
            c.close(); out
        } catch (e: Exception) { out }
    }

    /** vc294: promosikan kandidat -> store aktif via putFact (status tulis
     *  gbrain berlaku: inserted/duplicate/superseded). Kandidat jadi
     *  promoted — HANYA fakta yang di-approve yang masuk store aktif. */
    @Synchronized
    fun promoteCandidateVc294(id: Long): Triple<String, String, List<String>> {
        ensureVc294SchemaLazyVc294()
        return try {
            val row = listCandidatesVc294("all", 500).firstOrNull { it.id == id } ?: return Triple("not_found", "", emptyList())
            if (row.status != "candidate") return Triple(row.status, "", emptyList())
            val res = putFact(row.k, row.v, source = row.source, tags = row.tags, refPath = row.refPath)
            val cv = ContentValues(); cv.put("status", "promoted"); cv.put("decided_ts", System.currentTimeMillis())
            writableDatabase.update("fact_candidates", cv, "id=?", arrayOf(id.toString()))
            Triple(res.status, res.oldValue, res.conflicts)
        } catch (e: Exception) { Triple("not_found", "", emptyList()) }
    }

    /** vc294: arsipkan kandidat — status archived, BARIS TETAP ADA (bukan
     *  dihapus). Yang ditolak tidak hilang, bisa ditinjau lagi. */
    @Synchronized
    fun archiveCandidateVc294(id: Long, reason: String = ""): Boolean {
        ensureVc294SchemaLazyVc294()
        return try {
            val cv = ContentValues(); cv.put("status", "archived"); cv.put("reason", reason.take(200)); cv.put("decided_ts", System.currentTimeMillis())
            writableDatabase.update("fact_candidates", cv, "id=? AND status='candidate'", arrayOf(id.toString())) > 0
        } catch (e: Exception) { false }
    }

    /** vc294: recall hemat konteks — ringkasan ber-ID. Tanpa query =
     *  terbaru dulu; dengan query = ranking relevansi (kata + tag).
     *  Penelepon (recall_facts) menampilkan ringkasan + expand by id. */
    fun recallSummaryVc294(query: String, limit: Int = 10): List<FactRowVc285> {
        val act = factsAllFullVc285().filter { it.status == "active" }
        if (act.isEmpty()) return emptyList()
        val toks = relevanceTokens(query)
        return if (toks.isEmpty()) act.sortedByDescending { it.ts }.take(limit.coerceAtLeast(1))
        else act.sortedWith(compareByDescending<FactRowVc285> { relevanceScore(toks, (it.k + " " + it.v).lowercase(), it.source, it.tags) * if (it.confirmed) 1.5 else 1.0 }.thenByDescending { it.ts }).take(limit.coerceAtLeast(1))
    }

    fun factByIdVc294(id: Long): FactRowVc285? {
        return try { factsAllFullVc285().firstOrNull { it.id == id } } catch (e: Exception) { null }
    }

    /** vc294: deteksi konflik fakta baru vs fakta aktif — tulis flag ke
     *  dream_flags (dedupe vs flag open yang sama, dua arah), kembalikan
     *  daftar tampil "kind: otherKey". HANYA penanda. */
    @Synchronized
    fun flagConflictsForVc294(nk: String, nv: String): List<String> {
        ensureDreamFlagsVc289()
        val active = factsAllFullVc285().filter { it.status == "active" }.map { it.k.trim().lowercase() to it.v }
        val found = MemoryLoopVc294.detectConflicts(nk, nv, active)
        if (found.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<String>()
        for (f in found) {
            val a = nk; val b = f.otherKey
            val exists = try {
                val c = readableDatabase.rawQuery("SELECT id FROM dream_flags WHERE kind=? AND ((fact_a=? AND fact_b=?) OR (fact_a=? AND fact_b=?)) AND status='open'", arrayOf(f.kind, a, b, b, a))
                val has = c.moveToFirst(); c.close(); has
            } catch (e: Exception) { false }
            if (!exists) {
                try {
                    val cv = ContentValues()
                    cv.put("kind", f.kind); cv.put("fact_a", a); cv.put("fact_b", b); cv.put("detail", f.detail)
                    cv.put("status", "open"); cv.put("created_ts", now); cv.put("resolved_ts", 0)
                    writableDatabase.insert("dream_flags", null, cv)
                } catch (e: Exception) {}
            }
            out.add(f.kind + ": " + b)
        }
        return out
    }

    /** vc294: graph blast sederhana untuk satu key (adaptasi swarmvault
     *  `graph blast` + git-context-controller commit.md bernomor):
     *  (1) rantai versi key itu (aktif + superseded + withdrawn, bernomor
     *  [H01] dst.), (2) edge 1 hop (graph mini vc289), (3) flag konflik
     *  terbuka yang melibatkan key itu. Teks saja — tanpa dashboard web. */
    fun factBlastVc294(k: String): String {
        val nk = k.trim().lowercase()
        if (nk.isBlank()) return "fact_blast butuh key."
        val sb = StringBuilder()
        sb.append("GRAPH BLAST — '").append(nk).append("' (vc294)\n")
        val chain = try { factsAllFullVc285().filter { it.k.trim().lowercase() == nk } } catch (e: Exception) { emptyList() }
        if (chain.isEmpty()) sb.append("Rantai versi: key tidak ditemukan di memori (status apa pun).\n")
        else {
            sb.append("Rantai versi (").append(chain.size).append(" baris):\n")
            for ((i, r) in chain.sortedByDescending { it.updatedTs }.withIndex()) {
                sb.append("[H").append(String.format("%02d", i + 1)).append("] [").append(r.status).append("] ")
                    .append(r.k).append(" = ").append(r.v.take(100))
                if (r.status == "superseded" && r.supersededBy.isNotBlank()) sb.append(" — digantikan '").append(r.supersededBy).append("'")
                if (r.reason.isNotBlank()) sb.append(" — ").append(r.reason.take(60))
                sb.append("\n")
            }
        }
        val edges = try { factEdgesForVc289(nk) } catch (e: Exception) { emptyList() }
        if (edges.isEmpty()) sb.append("Relasi 1 hop: tidak ada edge tercatat.\n")
        else {
            sb.append("Relasi 1 hop (").append(edges.size).append("):\n")
            for (e in edges) sb.append("- ").append(e.fromK).append(" --[").append(e.rel).append("]--> ").append(e.toK).append("\n")
        }
        val flags = try { listDreamFlagsVc289().filter { it.factA == nk || it.factB == nk } } catch (e: Exception) { emptyList() }
        if (flags.isEmpty()) sb.append("Flag konflik terbuka: tidak ada — tidak ada klaim yang bergantung pada key ini yang sedang bertentangan.\n")
        else {
            sb.append("Flag konflik terbuka (").append(flags.size).append(") — klaim yang terpengaruh bila key ini berubah:\n")
            for (f in flags) sb.append("- [").append(f.kind).append("] ").append(f.factA).append(" ↔ ").append(f.factB).append(": ").append(f.detail).append("\n")
        }
        sb.append("Blast ini HANYA tampilan — tidak ada fakta yang diubah/dihapus.")
        return sb.toString()
    }

    // vc240 m10: penanda pemotongan tulis fakta. putFact memotong key
    // 80 char dan value 500 char tanpa peringatan; penanda ini ditempel
    // ke pesan sukses tool (bukan penolakan) agar perilaku tulis model
    // tidak berubah mendadak. String kosong = tidak ada pemotongan.
    fun factTruncNote(k: String, v: String): String {
        val kn = if (k.trim().length > 80) "key dipotong 80 char" else ""
        val vn = if (v.length > 500) "value dipotong 500 char" else ""
        return listOf(kn, vn).filter { it.isNotBlank() }.joinToString(", ").let {
            if (it.isBlank()) "" else " (catatan: $it)"
        }
    }

    fun confirmFact(k: String) {
        try {
            ensureFactConfirmedCol()
            val cv = ContentValues(); cv.put("confirmed", 1)
            writableDatabase.update("facts", cv, "k=? AND (status IS NULL OR status='active')", arrayOf(k))
        } catch (e: Exception) {}
    }

    // vc236 M4: LIMIT 40 di sini dipertahankan HANYA untuk daftar UI
    // (dialog batch Setup 65 via unconfirmedFacts) — BUKAN retrieval
    // prompt. Retrieval prompt memakai factsAllWithStatus di bawah.
    fun factsWithStatus(): List<Triple<String, String, Boolean>> {
        return factsAllWithStatus().take(40)
    }

    // vc236 M4: pool kandidat retrieval TANPA LIMIT. Fakta ke-41+ ikut
    // di-ranking (dulu: factsWithStatus memotong ke 40 terbaru dulu,
    // sehingga fakta lama tidak pernah muncul walau query cocok persis).
    // Tabel fakta jauh lebih kecil dari tabel embedding, jadi pemindaian
    // penuh per giliran wajar tanpa indeks baru (indeks = kelompok 7).
    // Tidak ada FTS atas tabel facts (fts_fact hanya untuk edge graph),
    // jadi kandidat = seluruh tabel, baru dipotong cap 4000 char.
    // vc285 Fase 0: pool kandidat retrieval — HANYA baris aktif. Baris
    // superseded/withdrawn dikecualikan dari retrieval, FAKTA PIN, dan
    // pencocokan klaim; mereka dibaca lewat factsAllFullVc285 /
    // factsHistoryVc285 / factByKeyVc285.
    fun factsAllWithStatus(): List<Triple<String, String, Boolean>> {
        return factsAllFullVc285().filter { it.status == "active" }.map { Triple(it.k, it.v, it.confirmed) }
    }

    // vc240 m11: Setup 65 memakai pool PENUH (factsAllWithStatus),
    // bukan LIMIT 40 — fakta ke-41+ yang belum dikonfirmasi kini tampil
    // dan bisa disetujui/dihapus dari Setup 65. Retrieval prompt sudah
    // memakai pool penuh sejak kelompok 4; recall_search (searchMemory)
    // juga sudah memakai factsAll.
    fun unconfirmedFacts(): List<Pair<String, String>> {
        return factsAllWithStatus().filter { !it.third }.map { it.first to it.second }
    }

    // vc237 M9: deleteFact mentah — penelepon WAJIB mengarsip dulu lewat
    // archiveDelete (Setup 65 sudah begitu). vc285: forget_fact TIDAK LAGI
    // memakai deleteFact (menarik menjadi withdrawn); putFact TIDAK LAGI
    // menghapus baris lama (menjadi superseded). deleteFact hanya untuk
    // hapus permanen via UI (Setup 65/17), dan HANYA baris aktif —
    // baris riwayat (superseded/withdrawn) tidak ikut terhapus.
    fun deleteFact(k: String) {
        try {
            writableDatabase.delete("facts", "k=? AND (status IS NULL OR status='active')", arrayOf(k))
            deleteEmbedding("fact", k)
        } catch (e: Exception) {}
    }

    // vc236 M4: LIMIT 40 di sini dipertahankan HANYA untuk daftar UI
    // (Monitor, ekspor snapshot) — BUKAN retrieval. Retrieval memakai
    // factsText (pool factsAllWithStatus) dan jalur pasangan-mirip
    // memakai factsAll di bawah.
    fun facts(): List<Pair<String, String>> {
        return factsAll().take(40)
    }

    // vc236 M4: pasangan (k, v) TANPA LIMIT untuk jalur retrieval non-UI
    // (pasangan-mirip konsolidasi malam, lookup arsip forget_fact,
    // searchMemory). Daftar UI tetap memakai facts() LIMIT 40.
    fun factsAll(): List<Pair<String, String>> {
        return factsAllWithStatus().map { it.first to it.second }
    }

    // vc236 M5: skoring relevansi bersama untuk factsText dan goalsText.
    // Token query: lowercase, split non-alnum, panjang minimal 3, unik,
    // dikurangi stopword Indonesia dasar (kata fungsi yang tidak membedakan
    // fakta — "yang", "saya", "ini", "tapi" dan sejenisnya; daftar eksplisit
    // di bawah). Skor per token query: 2.0 bila cocok sebagai KATA UTUH di
    // teks kandidat, 1.0 bila hanya substring (menekan false positive
    // seperti "kopi" yang cocok di "fotokopi"). Fakta terkonfirmasi dikali
    // 1.5. Seri diputus urutan terbaru (fakta: ORDER BY ts DESC; goal: id
    // DESC). Cap vc248: fakta 20000, goals 10000.
    private val relevanceStopwords = setOf("yang", "saya", "ini", "itu", "tapi", "juga", "sudah", "akan", "bisa", "kamu", "kita", "mereka", "dia", "saja", "lagi", "masih", "kalau", "jika", "bila", "saat", "ketika", "dengan", "untuk", "pada", "adalah", "atau", "dari", "oleh", "agar", "supaya", "karena", "seperti", "antara", "setiap", "semua", "sangat", "jangan", "tolong", "mohon", "nanti", "kemarin", "besok", "sini", "situ", "sana", "mana", "apa", "siapa", "kenapa", "bagaimana", "berapa")

    private fun relevanceTokens(query: String): List<String> {
        return query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in relevanceStopwords }.distinct()
    }

    // vc285 Fase 0: bobot sumber di relevanceScore — tool:* dan user:*
    // +0,5; model:asserted -0,5. Bonus HANYA bila ada kecocokan kata
    // (s > 0): fakta yang tidak cocok query tetap skor 0 walau sumbernya
    // kuat, supaya ranking tidak dipenuhi fakta tak relevan.
    private fun relevanceScore(queryToks: List<String>, hayLower: String, source: String = "", tags: String = ""): Double {
        if (queryToks.isEmpty()) return 0.0
        val hayWords = hayLower.split(Regex("[^a-z0-9]+")).toSet()
        var s = 0.0
        for (t in queryToks) {
            if (t in hayWords) s += 2.0
            else if (hayLower.contains(t)) s += 1.0
        }
        if (s <= 0.0) return 0.0
        val bonusVc285 = when {
            source.startsWith("tool:") || source.startsWith("user:") -> 0.5
            source == "model:asserted" -> -0.5
            else -> 0.0
        }
        // vc294: bonus tag — token query yang sama persis dengan satu
        // concept tag spesifik = +1.5 per tag (cap +3.0). Tag generik
        // sudah dibuang saat tulis, jadi yang cocok di sini spesifik.
        val tagBonusVc294 = if (tags.isBlank()) 0.0 else {
            val tagSetVc294 = tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            (queryToks.count { it in tagSetVc294 } * 1.5).coerceAtMost(3.0)
        }
        return (s + bonusVc285 + tagBonusVc294).coerceAtLeast(0.0)
    }

    // vc228 R-03(a): fakta tak terkonfirmasi TIDAK disuntikkan dengan label
    // yang sama kuatnya — tiap barisnya bertanda BELUM DIKONFIRMASI +
    // sumber model, supaya model dan user bisa membedakannya dari fakta
    // terkonfirmasi.
    // vc248: blok PIN — semua fakta TERKONFIRMSI tanpa cap dan tanpa
    // ranking relevansi. Dipakai di awal prompt semua jalur; guard TIDAK
    // pernah memangkas blok ini (H_PIN tidak ada di daftar trim guard).
    // vc263 (Fakta PIN tanpa cap): nuansa vc248 yang diluruskan —
    // "tidak pernah dipotong" maksudnya TIDAK PERNAH DIBUANG DIAM-DIAM,
    // bukan tanpa batas ukuran. Blok ini kini ber-cap total 20000 char
    // (sama seperti factsText/skillsText) DENGAN penanda jujur yang
    // menyebut jumlah fakta yang tidak ditampilkan; fakta tetap tersimpan
    // penuh di DB dan tetap bisa dibaca lewat recall/Setup 65. Potong di
    // batas baris (fakta tidak pernah terbelah; satu fakta raksasa ikut
    // pola factsText: diambil 20000 char pertama + penanda sisa).
    // vc285 Fase 0: tanggal ringkas "10 Nov 2026" untuk tag sumber PIN.
    private fun factDateVc285(ts: Long): String {
        return try { SimpleDateFormat("dd MMM yyyy", Locale("id", "ID")).format(Date(ts)) } catch (e: Exception) { "" }
    }

    // vc285 Fase 0: baris PIN membawa sumber + tanggal:
    // "- key: value [sumber: <source>, <tanggal>]".
    fun pinnedFactsText(): String {
        val f = factsAllFullVc285().filter { it.status == "active" && it.confirmed }
        if (f.isEmpty()) return ""
        val lines = f.map { t -> "- ${t.k}: ${t.v} [sumber: ${t.source.ifBlank { "model:asserted" }}, ${factDateVc285(if (t.sourceTs > 0) t.sourceTs else t.ts)}]" }
        val out = ArrayList<String>()
        var used = 0
        for (ln in lines) {
            if (used + ln.length + 1 > 20000) {
                if (out.isEmpty() && ln.length > 4000) out.add(ln.take(20000) + "…(dipotong, sisa " + (ln.length - 20000) + " char)…")
                break
            }
            out.add(ln); used += ln.length + 1
        }
        val hidden = lines.size - out.size
        if (hidden > 0) out.add("…($hidden fakta PIN tidak ditampilkan — total melebihi 20000 char; fakta tetap tersimpan, bukan dihapus)…")
        return out.joinToString("\n")
    }

    // vc248: hapus baris fakta dari teks ber-cap yang sudah tampil di blok
    // PIN, agar prompt tidak mengirim fakta yang sama dua kali.
    fun dedupFactsVsPinned(cappedText: String, pinnedText: String): String {
        if (cappedText.isBlank() || pinnedText.isBlank()) return cappedText
        val pinnedLines = pinnedText.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val kept = cappedText.lines().filter { ln ->
            val t = ln.trim()
            if (t.isBlank()) true else !pinnedLines.contains(t)
        }
        return kept.joinToString("\n")
    }

    // vc248: cap fakta 20000 char (dari 4000), pemangkasan berbasis relevansi
    // terhadap pesan berjalan (bukan baris tertua); potong di batas baris.
    // vc236 M4+M5: ranking berjalan atas SEMUA fakta (factsAllWithStatus,
    // tanpa LIMIT 40) dengan skoring kata-utuh > substring, stopword, dan
    // bobot confirmed 1.5x; seri = urutan terbaru (ts DESC). Penilaian
    // memakai teks mentah "k: v" (bukan baris tampil, agar label
    // [belum dikonfirmasi] tidak ikut mencemari skor).
    fun factsText(query: String = ""): String {
        val f = factsAllFullVc285().filter { it.status == "active" }
        if (f.isEmpty()) return ""
        val toks = relevanceTokens(query)
        val scored = f.withIndex().sortedWith(compareByDescending<IndexedValue<FactRowVc285>> { relevanceScore(toks, (it.value.k + " " + it.value.v).lowercase(), it.value.source, it.value.tags) * if (it.value.confirmed) 1.5 else 1.0 }.thenBy { it.index })
        val lines = scored.map { iv ->
            val t = iv.value
            // vc285: baris terkonfirmasi memakai format tag sumber yang
            // SAMA dengan pinnedFactsText, supaya dedupFactsVsPinned tetap
            // membuang duplikatnya dari blok ini. Label baris belum
            // dikonfirmasi TIDAK berubah.
            if (t.confirmed) "- ${t.k}: ${t.v} [sumber: ${t.source.ifBlank { "model:asserted" }}, ${factDateVc285(if (t.sourceTs > 0) t.sourceTs else t.ts)}]" else "- ${t.k}: ${t.v} [belum dikonfirmasi — sumber: model, belum disetujui user]"
        }
        val out = ArrayList<String>()
        var used = 0
        for (ln in lines) {
            if (used + ln.length + 1 > 20000) {
                if (out.isEmpty() && ln.length > 4000) out.add(ln.take(20000) + "…(dipotong, sisa " + (ln.length - 20000) + " char)…")
                break
            }
            out.add(ln); used += ln.length + 1
        }
        return out.joinToString("\n")
    }

    // ---------- Arsip hapusan vc228 R-03(d) ----------
    // Satu tabel arsip generik (kind/ref/payload JSON/deleted_ts) untuk
    // semua hapusan model: fakta, skill, jadwal, pengingat, aturan, geofence.
    // Retensi 30 hari (pola yang sama dengan memory_forget graph yang sudah
    // HIGH + soft-expire); listArchived membersihkan yang kedaluwarsa.
    data class ArchivedItem(val id: Long, val kind: String, val ref: String, val payload: String, val deletedTs: Long)

    private fun ensureArchiveTable() {
        try { writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS deleted_archive(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, ref TEXT, payload TEXT, deleted_ts INTEGER)") } catch (e: Exception) {}
    }

    // vc237 M9 ATURAN ARSIP TUNGGAL: archiveDelete adalah SATU-SATUNYA fungsi
    // arsip hapusan (tidak ada archiveThenDelete kedua yang bersaing).
    // Semua jalur hapus/timpa memori WAJIB memanggilnya SEBELUM delete:
    // tool forget_fact/delete_skill/cancel_*/delete_rule/geofence (Tools.kt),
    // uiSkillDelete + pembatalan jadwal UI (MainActivity.kt), Setup 65
    // Hapus semua (MainActivity.kt), dan timpa fakta via putFact kelompok 1
    // (arsip superseded_by + confirmed lama). Jalur yang TIDAK lewat arsip
    // dan sengaja permanen, masing-masing dengan alasannya:
    // - deleteSession dan wipeAll: sengaja permanen + konfirmasi kedua di UI
    //   (dialog sesi dan Setup 17 sama-sama menulis "dihapus permanen" dan
    //   meminta ketuk Hapus eksplisit; fakta jangka panjang tidak ikut
    //   terhapus oleh deleteSession).
    // - prune addHistory (cap 300 per sesi) dan purge listArchived (>30 hari):
    //   retensi otomatis, bukan hapusan user/model — sengaja tidak diarsipkan
    //   ulang, karena mengarsipkan prune/purge akan menumbuhkan arsip tanpa
    //   batas persis seperti yang dicegah retensi itu sendiri.
    // - hapus embedding (deleteEmbedding): data turunan; baris sumbernya yang
    //   dihapus sudah ditangani di atas, embedding yatim dibersihkan proaktif.
    fun archiveDelete(kind: String, ref: String, payload: String): Long {
        return try {
            ensureArchiveTable()
            // vc239 M7b + minor m1: purge malas — tiap ada arsip baru,
            // hapus dulu yang sudah lewat 30 hari, jadi arsip tidak lagi
            // hanya dibersihkan saat user membuka Setup 66 (listArchived).
            // Cutoff sama dengan listArchived; bukan hapusan user/model.
            val cutoffM1 = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            try { writableDatabase.delete("deleted_archive", "deleted_ts<?", arrayOf(cutoffM1.toString())) } catch (e: Exception) {}
            val cv = ContentValues()
            cv.put("kind", kind); cv.put("ref", ref); cv.put("payload", archivePayloadJsonVc240(payload)); cv.put("deleted_ts", System.currentTimeMillis())
            writableDatabase.insert("deleted_archive", null, cv)
        } catch (e: Exception) { -1 }
    }

    // vc240 m3: potong payload arsip di batas JSON yang valid. Bila
    // payload JSON melebihi 60000 char, nilai string terpanjang dipangkas
    // berulang (dengan penanda) sampai muat — JSON tetap bisa di-parse
    // saat restore, bukan gagal diam-diam di tengah string.
    fun archivePayloadJsonVc240(payload: String): String {
        if (payload.length <= 60000) return payload
        return try {
            val o = JSONObject(payload)
            val marker = "…(dipotong saat pengarsipan)"
            while (o.toString().length > 60000) {
                var longestKey: String? = null
                var longestLen = 0
                for (key in o.keys()) {
                    val v = o.optString(key)
                    if (v.length > longestLen) { longestLen = v.length; longestKey = key }
                }
                if (longestKey == null || longestLen <= marker.length + 1) break
                val cur = o.optString(longestKey)
                val over = o.toString().length - 60000
                val cut = (cur.length - over - marker.length).coerceAtLeast(0)
                o.put(longestKey, cur.take(cut) + marker)
            }
            o.toString()
        } catch (e: Exception) { payload.take(60000) }
    }

    // vc240 m3: alasan restore terakhir, ditampilkan dialog Setup 66 bila
    // restore gagal (bukan sekadar "Gagal memulihkan" tanpa alasan).
    var lastRestoreErrorVc240: String = ""

    fun listArchived(): List<ArchivedItem> {
        val out = ArrayList<ArchivedItem>()
        try {
            ensureArchiveTable()
            val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            // vc237 M9: purge retensi otomatis (>30 hari) — lihat aturan arsip
            // tunggal di atas archiveDelete; bukan hapusan user/model.
            writableDatabase.delete("deleted_archive", "deleted_ts<?", arrayOf(cutoff.toString()))
            // vc239 M7: LIMIT 500 — dialog Setup 66 hanya menampilkan
            // arsip terbaru; tanpa LIMIT, daftar ini tumbuh tanpa batas
            // sampai user membuka Setup 66. Restore item di luar 500
            // teratas tetap bisa lewat query by-id di restoreArchived.
            val c = readableDatabase.rawQuery("SELECT id,kind,ref,payload,deleted_ts FROM deleted_archive ORDER BY deleted_ts DESC LIMIT 500", null)
            while (c.moveToNext()) out.add(ArchivedItem(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4)))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    fun deleteArchived(id: Long) {
        try { ensureArchiveTable(); writableDatabase.delete("deleted_archive", "id=?", arrayOf(id.toString())) } catch (e: Exception) {}
    }

    fun restoreArchived(id: Long): Boolean {
        return try {
            ensureArchiveTable()
            // vc239 M7: ambil langsung by id — listArchived kini
            // ber-LIMIT 500, restore item lama di luar 500 teratas harus
            // tetap bisa dan tidak bergantung pada daftar tampilan.
            val row = try {
                val rc = readableDatabase.rawQuery("SELECT id,kind,ref,payload,deleted_ts FROM deleted_archive WHERE id=?", arrayOf(id.toString()))
                val r = if (rc.moveToFirst()) ArchivedItem(rc.getLong(0), rc.getString(1) ?: "", rc.getString(2) ?: "", rc.getString(3) ?: "", rc.getLong(4)) else null
                rc.close(); r
            } catch (e: Exception) { null } ?: return false
            val o = JSONObject(row.payload)
            when (row.kind) {
                // vc240 m2: fakta dipulihkan dengan status confirmed yang
                // tersimpan di payload (bukan selalu true); arsip lama tanpa
                // kunci confirmed tetap dipulihkan sebagai terkonfirmasi
                // (perilaku lama) agar tidak merusak arsip yang sudah ada.
                // Id asal (bila ada di payload) disimpan untuk audit, tetapi
                // restore memakai id baru karena PK autoincrement.
                "fact" -> putFact(o.optString("k"), o.optString("v"), if (o.has("confirmed")) o.optBoolean("confirmed") else true, "restore:backup")
                "skill" -> {
                    val res = forgeUpsert(o.optString("name"), o.optString("instruction"), o.optString("descr"))
                    val nid = res.first
                    if (nid > 0 && !o.optBoolean("enabled", true)) setSkillEnabled(nid, false)
                    // vc301 SF-A2c: eval ikut pulih dari payload arsip bila
                    // ada (forgeUpsert menghanguskannya bila isi berubah —
                    // pulihkan sesudahnya, pola restoreSkillVersion).
                    // VC303-P3-21 (EV-9): eval impor arsip divalidasi; bila tidak valid DITANDAI (bukan dibuang).
                    if (nid > 0 && o.optString("eval").isNotBlank()) setSkillEval(nid, SkillJudge.markImportEvalVc303(o.optString("eval")))
                }
                "schedule" -> addSchedule(o.optInt("hour"), o.optInt("minute"), o.optString("prompt"))
                "reminder" -> {
                    val cv = ContentValues()
                    cv.put("due", o.optLong("due")); cv.put("text", o.optString("text")); cv.put("done", 0)
                    writableDatabase.insert("reminders", null, cv)
                }
                "rule" -> addRule(o.optString("pkg"), o.optString("needle"), o.optString("action"))
                "geofence" -> addGeofence(o.optString("name"), o.optDouble("lat"), o.optDouble("lng"), o.optInt("radius"), o.optString("prompt"))
                // vc237 M13: arsip riwayat dari archiveOldHistory bisa dipulihkan
                // sebagai baris history baru dengan role/text/ts/session aslinya.
                "history" -> {
                    val cv = ContentValues()
                    cv.put("role", o.optString("role")); cv.put("text", o.optString("text")); cv.put("ts", o.optLong("ts")); cv.put("session", o.optLong("session", 1L))
                    writableDatabase.insert("history", null, cv)
                }
                else -> { lastRestoreErrorVc240 = "jenis arsip tidak dikenal: " + row.kind; return false }
            }
            deleteArchived(id)
            true
        } catch (e: Exception) { lastRestoreErrorVc240 = e.message ?: "gagal parse payload arsip"; false }
    }

    // ---------- Riwayat percakapan ----------
    @Synchronized
    /** Kolom work (J3, v3.8.0): JSON WorkItem giliran — lazy ALTER meniru pola kolom session. */
    private fun ensureWorkColumn(db: SQLiteDatabase) {
        try { db.execSQL("ALTER TABLE history ADD COLUMN work TEXT") } catch (e: Exception) {}
    }

    /** v4.17.0: baca kolom work satu baris riwayat (buat mutasi ask.chosen). */
    fun historyWork(id: Long): String? {
        val c = writableDatabase.rawQuery("SELECT work FROM history WHERE id = ?", arrayOf(id.toString()))
        var out: String? = null
        if (c.moveToFirst()) out = c.getString(0)
        c.close()
        return out
    }

    /** v4.17.0: tulis ulang kolom work satu baris riwayat. */
    fun updateHistoryWork(id: Long, work: String) {
        val cv = ContentValues()
        cv.put("work", work)
        writableDatabase.update("history", cv, "id = ?", arrayOf(id.toString()))
    }

    // vc301 SF-D3: riwayat disimpan UTUH (dulu text.take(2000) tanpa
    // penanda — path/R-id/angka di ekor jawaban panjang hilang dari
    // giliran berikut). Transkrip yang dibaca model sudah ber-cap 60000
    // char (MainActivity.kt:3915), jadi menyimpan utuh tidak menjebol
    // prompt. Prune 300 baris/sesi di bawah tidak berubah.
    fun addHistory(role: String, text: String, sessionId: Long = 1, work: String? = null) {
        try {
            val cv = ContentValues()
            cv.put("role", role); cv.put("text", text); cv.put("ts", System.currentTimeMillis())
            cv.put("session", sessionId)
            val db = writableDatabase
            ensureWorkColumn(db)
            if (work != null) cv.put("work", work)
            val newId = db.insert("history", null, cv)
            if (newId > 0 && text.length >= 12) {
                Embedder.embedAsync(appCtx, "hist", "hist:$newId", text.take(400))
            }
            // F4 (v4.38.0): hapus embedding baris yang AKAN di-prune (proaktif,
            // bukan menunggu pembersihan malas di semanticSearch).
            try {
                val pc = db.rawQuery("SELECT id FROM history WHERE session=? AND id NOT IN (SELECT id FROM history WHERE session=? ORDER BY id DESC LIMIT 300)", arrayOf(sessionId.toString(), sessionId.toString()))
                val pruned = ArrayList<Long>()
                while (pc.moveToNext()) pruned.add(pc.getLong(0))
                pc.close()
                for (pid in pruned) deleteEmbedding("hist", "hist:$pid")
            } catch (e: Exception) {}
            db.execSQL(
                "DELETE FROM history WHERE session=? AND id NOT IN (SELECT id FROM history WHERE session=? ORDER BY id DESC LIMIT 300)",
                arrayOf(sessionId, sessionId))
            // vc198 Fase 1: hook episode NON-BLOCKING untuk turn chat 1-1.
            val epRole = role
            val epText = text
            Thread {
                try { GraphDb.get(appCtx).getOrCreateEpisode("chat1-1", epRole, epText, "chat1-1") } catch (e: Exception) {}
            }.start()
        } catch (e: Exception) {}
    }

    /** Transkrip N pesan terakhir satu sesi (kronologis), dibatasi karakter dari belakang. */
    fun historyTranscript(n: Int, maxChars: Int = 3500, sessionId: Long = 1): String {
        val rows = ArrayList<Pair<String, String>>()
        try {
            val c = readableDatabase.rawQuery("SELECT role,text FROM history WHERE session=? ORDER BY id DESC LIMIT ?", arrayOf(sessionId.toString(), n.toString()))
            while (c.moveToNext()) rows.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
            c.close()
        } catch (e: Exception) {}
        rows.reverse()
        var s = rows.joinToString("\n") { if (it.first == "user") "User: " + it.second else "Asisten: " + RedactSecrets.redact(it.second) }
        if (s.length > maxChars) s = "…" + s.takeLast(maxChars)
        return s
    }

    // vc230 R-07(b)(c): transkrip batas-pesan + label peran + provenance.
    // Potong di batas pesan (bukan tengah karakter); satu pesan yang
    // melebihi sisa anggaran disimpan kepala + ekor dengan penanda
    // eksplisit. Catatan sistem dirender "Sistem:", bukan "Asisten:".
    fun historyCountVc230(sessionId: Long = 1): Int {
        return try {
            val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM history WHERE session=?", arrayOf(sessionId.toString()))
            var n = 0
            if (c.moveToFirst()) n = c.getInt(0)
            c.close(); n
        } catch (e: Exception) { 0 }
    }

    fun sessionInfoVc230(sessionId: Long = 1): Pair<Int, Long> {
        return try {
            val c = readableDatabase.rawQuery("SELECT COUNT(*), MIN(ts) FROM history WHERE session=?", arrayOf(sessionId.toString()))
            var p = Pair(0, 0L)
            if (c.moveToFirst()) p = Pair(c.getInt(0), c.getLong(1))
            c.close(); p
        } catch (e: Exception) { Pair(0, 0L) }
    }

    fun historyTranscriptVc230(n: Int, maxChars: Int = 6000, sessionId: Long = 1): String {
        val rows = ArrayList<Pair<String, String>>()
        try {
            val c = readableDatabase.rawQuery("SELECT role,text FROM history WHERE session=? ORDER BY id DESC LIMIT ?", arrayOf(sessionId.toString(), n.toString()))
            while (c.moveToNext()) rows.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
            c.close()
        } catch (e: Exception) {}
        rows.reverse()
        val total = historyCountVc230(sessionId)
        val rendered = rows.map { (role, txt) ->
            val label = when (role) { "user" -> "User:"; "asisten", "assistant" -> "Asisten:"; else -> "Sistem:" }
            val body = if (role == "user") txt else RedactSecrets.redact(txt)
            "$label $body"
        }
        val picked = ArrayList<String>()
        var budget = maxChars
        for (line in rendered.asReversed()) {
            if (line.length + 1 <= budget) { picked.add(0, line); budget -= line.length + 1 }
            else if (budget > 60) {
                val head = budget / 2
                val tail = budget - head
                picked.add(0, line.take(head) + "\n…(pesan ini dipotong karena melebihi sisa anggaran riwayat; kepala + ekor dipertahankan, tengahnya tidak disertakan)…\n" + line.takeLast(tail))
                budget = 0; break
            } else break
        }
        val shown = picked.size
        // vc240 m15: header menyebut char yang hilang — char pesan yang
        // tidak ditampilkan sama sekali ditambah char yang dipotong di
        // dalam pesan head/tail yang ditampilkan (total render dikurangi
        // total yang tampil).
        val totalCharsVc240 = rendered.sumOf { it.length + 1 }
        val shownCharsVc240 = picked.sumOf { it.length + 1 }
        val lostCharsVc240 = (totalCharsVc240 - shownCharsVc240).coerceAtLeast(0)
        val header = if (shown < total) "Menampilkan $shown dari $total pesan sesi ini; ${total - shown} pesan sebelum ini tidak disertakan; $lostCharsVc240 char terpotong/tidak disertakan." else if (lostCharsVc240 > 0) "Menampilkan $shown dari $total pesan sesi ini; $lostCharsVc240 char terpotong di dalam pesan." else "Menampilkan $shown dari $total pesan sesi ini."
        return header + "\n" + picked.joinToString("\n")
    }

    // ---------- Sesi percakapan ----------
    private fun ensurePinnedCol() {
        try { writableDatabase.execSQL("ALTER TABLE sessions ADD COLUMN pinned INTEGER DEFAULT 0") } catch (e: Exception) {}
    }

    fun sessions(): List<ChatSession> {
        val out = ArrayList<ChatSession>()
        try {
            ensurePinnedCol()
            val c = readableDatabase.rawQuery("SELECT id,title,created,pinned FROM sessions ORDER BY pinned DESC, id", null)
            while (c.moveToNext()) out.add(ChatSession(c.getLong(0), c.getString(1) ?: "Sesi", c.getLong(2), c.getInt(3) != 0))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    /** v4.11.0: pin sesi (yang ter-pin selalu di atas daftar). */
    fun setPinned(id: Long, pinned: Boolean) {
        try {
            ensurePinnedCol()
            val cv = ContentValues()
            cv.put("pinned", if (pinned) 1 else 0)
            writableDatabase.update("sessions", cv, "id=?", arrayOf(id.toString()))
        } catch (e: Exception) {}
    }

    fun historyCount(session: Long): Int {
        return try {
            val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM history WHERE session=?", arrayOf(session.toString()))
            val n = if (c.moveToFirst()) c.getInt(0) else 0
            c.close()
            n
        } catch (e: Exception) { 0 }
    }

    fun createSession(title: String): Long {
        return try {
            val cv = ContentValues()
            cv.put("title", title.take(60)); cv.put("created", System.currentTimeMillis())
            writableDatabase.insert("sessions", null, cv)
        } catch (e: Exception) { 1 }
    }

    fun renameSession(id: Long, title: String) {
        try {
            val cv = ContentValues(); cv.put("title", title.take(60))
            writableDatabase.update("sessions", cv, "id=?", arrayOf(id.toString()))
        } catch (e: Exception) {}
    }

    fun sessionTitle(id: Long): String {
        return try {
            var t = "Utama"
            val c = readableDatabase.rawQuery("SELECT title FROM sessions WHERE id=?", arrayOf(id.toString()))
            if (c.moveToFirst()) t = c.getString(0) ?: t
            c.close()
            t
        } catch (e: Exception) { "Utama" }
    }

    /**
     * Hapus satu sesi + seluruh riwayatnya. Mengembalikan id sesi tujuan berikutnya:
     * - Bila masih ada sesi lain: sesi tertua yang tersisa.
     * - Bila ini satu-satunya sesi: TIDAK dihapus total, hanya dikosongkan isinya
     *   dan judulnya direset ke "Utama" (app wajib selalu punya >= 1 sesi).
     */
    // vc237 M9: deleteSession sengaja permanen + konfirmasi kedua di UI
    // (dialog daftar sesi dan confirmDeleteSession sama-sama memperingatkan
    // "dihapus permanen" sebelum deleteSession dipanggil). Embedding sesi
    // ikut dihapus proaktif dengan pola deleteSession yang sama seperti
    // archiveOldHistory vc237. Fakta jangka panjang TIDAK ikut terhapus.
    fun deleteSession(id: Long): Long {
        // F4 (v4.38.0): tangkap id riwayat dulu — embedding sesi ini harus
        // ikut terhapus proaktif, bukan menunggu pembersihan malas.
        val histIds = ArrayList<Long>()
        try {
            val c = readableDatabase.rawQuery("SELECT id FROM history WHERE session=?", arrayOf(id.toString()))
            while (c.moveToNext()) histIds.add(c.getLong(0))
            c.close()
        } catch (e: Exception) {}
        return try {
            val db = writableDatabase
            val r = if (sessions().size <= 1) {
                db.delete("history", "session=?", arrayOf(id.toString()))
                renameSession(id, "Utama")
                id
            } else {
                db.delete("history", "session=?", arrayOf(id.toString()))
                db.delete("sessions", "id=?", arrayOf(id.toString()))
                sessions().firstOrNull()?.id ?: 1L
            }
            for (hid in histIds) deleteEmbedding("hist", "hist:$hid")
            // VC303-P2-15 (CX-12): hapus juga catatan kerja yatim sesi ini
            // (WorkNotes + status + rencana) — tanpa ini kunci prefs
            // workNotesVc301_<id>/turnStatusVc301_<id>/planJson_<id> hidup
            // selamanya walau riwayat + sesi sudah dihapus. Berlaku juga
            // bila sesi di-reset jadi "Utama" (riwayatnya ikut dihapus).
            try {
                WorkNotesVc301.clearSession(appCtx, id)
                appCtx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().remove("planJson_" + id).apply()
            } catch (e: Exception) {}
            r
        } catch (e: Exception) { id }
    }

    /** F4 (v4.38.0): remediasi satu kali — hapus embedding yang teks sumber
     *  LENGKAP-nya sensitif (dibuat sebelum filter tulis F4 ada). Mengembalikan
     *  jumlah yang dihapus. Jenis goal dilewati (judul/status jarang sensitif;
     *  pembersihan malas tetap berlaku bila sumbernya hilang). */
    fun purgeSensitiveEmbeddings(): Int {
        var n = 0
        try {
            for (r in allEmbeddings()) {
                val full = when (r.kind) {
                    "hist" -> {
                        val c = readableDatabase.rawQuery("SELECT text FROM history WHERE id=?", arrayOf(r.ref.removePrefix("hist:")))
                        val t = if (c.moveToFirst()) c.getString(0) ?: "" else ""
                        c.close(); t
                    }
                    "fin" -> {
                        val c = readableDatabase.rawQuery("SELECT text FROM finance WHERE id=?", arrayOf(r.ref.removePrefix("fin:")))
                        val t = if (c.moveToFirst()) c.getString(0) ?: "" else ""
                        c.close(); t
                    }
                    "fact" -> facts().firstOrNull { it.first == r.ref }?.second ?: ""
                    "skill" -> skills().firstOrNull { it.id.toString() == r.ref.removePrefix("skill:") }?.instruction ?: ""
                    else -> ""
                }
                if (full.isNotBlank() && Embedder.isSensitive(full)) {
                    deleteEmbedding(r.kind, r.ref)
                    n++
                }
            }
        } catch (e: Exception) {}
        return n
    }

    // T1: baris riwayat lengkap (id + kolom work) untuk renderer native —
    // kartu Ringkasan Kerja & kartu ask klasik dibaca dari sini.
    data class HistRow(val id: Long, val role: String, val text: String, val work: String?, val ts: Long = 0)

    fun historyRich(sessionId: Long, limit: Int, beforeId: Long = Long.MAX_VALUE): List<HistRow> {
        val rows = ArrayList<HistRow>()
        try {
            val c = readableDatabase.rawQuery("SELECT id,role,text,work,ts FROM history WHERE session=? AND id<? ORDER BY id DESC LIMIT ?", arrayOf(sessionId.toString(), beforeId.toString(), limit.toString()))
            while (c.moveToNext()) rows.add(HistRow(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3), try { c.getLong(4) } catch (e: Exception) { 0 }))
            c.close()
        } catch (e: Exception) {}
        rows.reverse()
        return rows
    }

    /** Aktivitas terbaru lintas sesi (untuk Beranda native). */
    fun recentActivity(limit: Int): List<HistRow> {
        val rows = ArrayList<HistRow>()
        try {
            val c = readableDatabase.rawQuery("SELECT id,role,text,work,ts FROM history ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
            while (c.moveToNext()) rows.add(HistRow(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3), try { c.getLong(4) } catch (e: Exception) { 0 }))
            c.close()
        } catch (e: Exception) {}
        return rows
    }

    fun lastHistoryId(sessionId: Long): Long {
        return try {
            val c = readableDatabase.rawQuery("SELECT MAX(id) FROM history WHERE session=?", arrayOf(sessionId.toString()))
            val v = if (c.moveToFirst()) c.getLong(0) else 0L
            c.close(); v
        } catch (e: Exception) { 0L }
    }

    fun historyCountBefore(sessionId: Long, beforeId: Long): Int {
        return try {
            val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM history WHERE session=? AND id<?", arrayOf(sessionId.toString(), beforeId.toString()))
            val v = if (c.moveToFirst()) c.getInt(0) else 0
            c.close(); v
        } catch (e: Exception) { 0 }
    }

    // vc283: id histori tertua yang masih ada setelah id tertentu — dipakai
    // windowing daftar chat untuk menggeser batas tombol "Muat …" supaya
    // baris yang terpotong tetap bisa dimuat ulang.
    fun oldestHistoryIdAfter(sessionId: Long, afterId: Long): Long {
        return try {
            val c = readableDatabase.rawQuery("SELECT MIN(id) FROM history WHERE session=? AND id>?", arrayOf(sessionId.toString(), afterId.toString()))
            val v = if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            c.close(); v
        } catch (e: Exception) { -1L }
    }

    /** Pesan satu sesi untuk diputar ulang ke UI (kronologis). */
    fun historyForSession(sessionId: Long, limit: Int = 40): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        try {
            val c = readableDatabase.rawQuery("SELECT role,text FROM history WHERE session=? ORDER BY id DESC LIMIT ?", arrayOf(sessionId.toString(), limit.toString()))
            while (c.moveToNext()) rows.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
            c.close()
        } catch (e: Exception) {}
        rows.reverse()
        return rows
    }

    // ---------- Pengingat ----------
    @Synchronized
    fun addReminder(dueTs: Long, text: String): Long {
        return try {
            val cv = ContentValues()
            cv.put("due", dueTs); cv.put("text", text.take(300)); cv.put("done", 0)
            writableDatabase.insert("reminders", null, cv)
        } catch (e: Exception) { -1 }
    }

    fun dueReminders(now: Long): List<Reminder> {
        val out = ArrayList<Reminder>()
        try {
            val c = readableDatabase.rawQuery("SELECT id,due,text FROM reminders WHERE done=0 AND due<=? ORDER BY due", arrayOf(now.toString()))
            while (c.moveToNext()) out.add(Reminder(c.getLong(0), c.getLong(1), c.getString(2) ?: ""))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    fun upcoming(limit: Int = 20): List<Reminder> {
        val out = ArrayList<Reminder>()
        try {
            val c = readableDatabase.rawQuery("SELECT id,due,text FROM reminders WHERE done=0 ORDER BY due LIMIT ?", arrayOf(limit.toString()))
            while (c.moveToNext()) out.add(Reminder(c.getLong(0), c.getLong(1), c.getString(2) ?: ""))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    fun markReminderDone(id: Long) {
        try {
            val cv = ContentValues(); cv.put("done", 1)
            writableDatabase.update("reminders", cv, "id=?", arrayOf(id.toString()))
        } catch (e: Exception) {}
    }

    fun deleteReminder(id: Long) {
        try { writableDatabase.delete("reminders", "id=?", arrayOf(id.toString())) } catch (e: Exception) {}
    }

    // vc237 M9: wipeAll sengaja permanen + konfirmasi kedua di UI (dialog
    // Setup 17 "Hapus memori?" menulis "dihapus permanen" sebelum wipeAll
    // dipanggil). Tidak ada arsip — ini reset asisten yang diminta eksplisit.
    fun wipeAll() {
        try {
            val db = writableDatabase
            db.delete("facts", null, null); db.delete("history", null, null); db.delete("reminders", null, null)
            db.delete("schedules", null, null); db.delete("rules", null, null); db.delete("skills", null, null)
            db.delete("geofences", null, null); db.delete("finance", null, null)
            // vc301 SF-A6: versi skill ikut terhapus (dulu yatim).
            try { db.delete("skill_versions", null, null) } catch (e: Exception) {}
            try { db.delete("deleted_archive", null, null) } catch (e: Exception) {}
        } catch (e: Exception) {}
    }

    // ---------- Jadwal tugas AI ----------
    // vc243 GELOMBANG 2 (GR-M5): ownerSession = sesi 1-1 saat schedule_task dibuat.
    fun addSchedule(hour: Int, minute: Int, prompt: String, ownerSession: Long = 0): Long {
        return try {
            val cv = ContentValues()
            cv.put("hour", hour); cv.put("minute", minute); cv.put("prompt", prompt.take(500)); cv.put("enabled", 1); cv.put("last_run_day", -1)
            if (ownerSession > 0) cv.put("owner_session", ownerSession)
            try { ensureWave2Columns(writableDatabase) } catch (e: Exception) {}
            writableDatabase.insert("schedules", null, cv)
        } catch (e: Exception) { -1 }
    }

    fun schedules(): List<Schedule> {
        val out = ArrayList<Schedule>()
        try {
            val c = readableDatabase.rawQuery("SELECT id,hour,minute,prompt,enabled,owner_session FROM schedules ORDER BY hour,minute", null)
            val ownIdx = c.getColumnIndex("owner_session")
            while (c.moveToNext()) out.add(Schedule(c.getLong(0), c.getInt(1), c.getInt(2), c.getString(3) ?: "", c.getInt(4) == 1, if (ownIdx >= 0) c.getLong(ownIdx) else 0))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    // ---------- Log job latar (F2, v4.39.0) ----------
    data class JobLogRow(val job: String, val ts: Long, val status: String, val detail: String)

    fun logJob(job: String, status: String, detail: String) {
        try {
            val db = writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS joblog(job TEXT, ts INTEGER, status TEXT, detail TEXT)")
            val cv = ContentValues()
            cv.put("job", job); cv.put("ts", System.currentTimeMillis()); cv.put("status", status); cv.put("detail", detail.take(200))
            db.insert("joblog", null, cv)
            db.execSQL("DELETE FROM joblog WHERE rowid NOT IN (SELECT rowid FROM joblog ORDER BY ts DESC LIMIT 500)")
        } catch (e: Exception) {}
    }

    fun jobHistory(limit: Int = 30): List<JobLogRow> {
        val out = ArrayList<JobLogRow>()
        try {
            val db = writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS joblog(job TEXT, ts INTEGER, status TEXT, detail TEXT)")
            val c = db.rawQuery("SELECT job,ts,status,detail FROM joblog ORDER BY ts DESC LIMIT ?", arrayOf(limit.toString()))
            while (c.moveToNext()) out.add(JobLogRow(c.getString(0) ?: "", c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: ""))
            c.close()
        } catch (e: Exception) {}
        return out
    }


    // ---------- Pesan grup (G1, v4.47.0) ----------
    // Tabel KHUSUS grup — tidak pernah menyentuh baris history 1-1.
    data class GroupRow(val role: String, val name: String, val text: String)

    fun addGroupMsg(role: String, name: String, text: String) {
        try {
            val db = writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS group_msgs(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, name TEXT, text TEXT, ts INTEGER)")
            val cv = ContentValues()
            cv.put("role", role); cv.put("name", name); cv.put("text", text.take(4000)); cv.put("ts", System.currentTimeMillis())
            db.insert("group_msgs", null, cv)
            // vc246 GELOMBANG 5 (GR-m1): cap pesan grup disamakan ke
            // 300 seperti room (dulu 1000 di sini vs 300 di room).
            db.execSQL("DELETE FROM group_msgs WHERE rowid NOT IN (SELECT rowid FROM group_msgs ORDER BY ts DESC LIMIT 300)")
        } catch (e: Exception) {}
    }

    /** N pesan TERAKHIR dalam urutan kronologis (tertua -> terbaru). */
    fun groupMessages(limit: Int = 100): List<GroupRow> {
        val out = ArrayList<GroupRow>()
        try {
            val db = writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS group_msgs(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, name TEXT, text TEXT, ts INTEGER)")
            val c = db.rawQuery("SELECT role,name,text FROM group_msgs ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
            while (c.moveToNext()) out.add(GroupRow(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: ""))
            c.close()
            out.reverse()
        } catch (e: Exception) {}
        return out
    }

    fun dueSchedules(hour: Int, minute: Int, dayOfYear: Int): List<Schedule> {
        val out = ArrayList<Schedule>()
        try {
            val c = readableDatabase.rawQuery(
                "SELECT id,hour,minute,prompt,enabled,owner_session FROM schedules WHERE enabled=1 AND hour=? AND minute=? AND last_run_day!=?",
                arrayOf(hour.toString(), minute.toString(), dayOfYear.toString()))
            val ownIdx = c.getColumnIndex("owner_session")
            while (c.moveToNext()) out.add(Schedule(c.getLong(0), c.getInt(1), c.getInt(2), c.getString(3) ?: "", true, if (ownIdx >= 0) c.getLong(ownIdx) else 0))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    // vc243 GELOMBANG 2 (AG-M3): catch-up di HARI YANG SAMA, samakan rutinitas F2.
    // Jadwal menit-persis yang tick-nya terlewat (service mati, HP tidur,
    // gerbang) tetap jalan sekali hari itu begitu tick pertama sesudah jamnya
    // tiba; jadwal yang jamnya belum tiba tidak ikut; besok tidak di-catch-up.
    // last_run_day tetap jadi pengunci sekali-sehari (ditandai pemanggil).
    fun dueSchedulesCatchUp(hour: Int, minute: Int, dayOfYear: Int): List<Schedule> {
        val out = ArrayList<Schedule>()
        val nowMin = hour * 60 + minute
        try {
            val c = readableDatabase.rawQuery(
                "SELECT id,hour,minute,prompt,enabled,owner_session FROM schedules WHERE enabled=1 AND last_run_day!=? AND (hour * 60 + minute) <= ?",
                arrayOf(dayOfYear.toString(), nowMin.toString()))
            val ownIdx = c.getColumnIndex("owner_session")
            while (c.moveToNext()) out.add(Schedule(c.getLong(0), c.getInt(1), c.getInt(2), c.getString(3) ?: "", true, if (ownIdx >= 0) c.getLong(ownIdx) else 0))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    fun markScheduleRun(id: Long, dayOfYear: Int) {
        try {
            val cv = ContentValues(); cv.put("last_run_day", dayOfYear)
            writableDatabase.update("schedules", cv, "id=?", arrayOf(id.toString()))
        } catch (e: Exception) {}
    }

    fun deleteSchedule(id: Long) {
        try { writableDatabase.delete("schedules", "id=?", arrayOf(id.toString())) } catch (e: Exception) {}
    }

    // ---------- Aturan pemicu (rules) ----------
    fun addRule(pkg: String, needle: String, action: String): Long {
        return try {
            val cv = ContentValues()
            cv.put("pkg", pkg.take(120)); cv.put("needle", needle.take(200)); cv.put("action", action.take(500)); cv.put("enabled", 1)
            writableDatabase.insert("rules", null, cv)
        } catch (e: Exception) { -1 }
    }

    fun rules(): List<AutoRule> {
        val out = ArrayList<AutoRule>()
        try {
            val c = readableDatabase.rawQuery("SELECT id,pkg,needle,action FROM rules WHERE enabled=1", null)
            while (c.moveToNext()) out.add(AutoRule(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: ""))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    fun deleteRule(id: Long) {
        try { writableDatabase.delete("rules", "id=?", arrayOf(id.toString())) } catch (e: Exception) {}
    }

    // ---------- Skill (prosedur dari bahasa natural) ----------
    /** v4.13.0: kolom descr/enabled via lazy ALTER (pola lama, tanpa bump versi). */
    private fun ensureSkillCols() {
        try { writableDatabase.execSQL("ALTER TABLE skills ADD COLUMN descr TEXT DEFAULT ''") } catch (e: Exception) {}
        try { writableDatabase.execSQL("ALTER TABLE skills ADD COLUMN enabled INTEGER DEFAULT 1") } catch (e: Exception) {}
        // vc293: kolom eval — benchmark JSON milik draf itu (judge, A/B,
        // TDD, trigger-eval). Diubah isi = benchmark dihapus (lihat
        // forgeUpsert/updateSkillMeta), evaluasi wajib diulang.
        try { writableDatabase.execSQL("ALTER TABLE skills ADD COLUMN eval TEXT DEFAULT ''") } catch (e: Exception) {}
    }

    // ---------- Skill versions (vc300 BAGIAN A) ----------
    // Tabel append-only: tiap simpan via forgeUpsert menyisakan satu baris
    // berisi teks LENGKAP draf itu (instruction + descr + eval). Teks lama
    // tidak pernah hilang walau baris skills ditimpa. TIDAK PERNAH
    // di-update/di-delete jalur normal — satu-satunya UPDATE adalah
    // attachJudgeToLatestVersion yang MENGISI kolom judge pada baris versi
    // terbaru (bukan memutasi isi draf). Lazy seperti ensureSkillCols
    // (tanpa bump versi DB); dipanggil dari tiap fungsi versi + forgeUpsert.
    private fun ensureSkillVersionTable() {
        try { writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS skill_versions(id INTEGER PRIMARY KEY AUTOINCREMENT, skill_id INTEGER, version_no INTEGER, instruction TEXT, descr TEXT, judge_total INTEGER DEFAULT -1, judge_grade TEXT DEFAULT '', eval_json TEXT DEFAULT '', source TEXT DEFAULT 'forge', created_ts INTEGER)") } catch (e: Exception) {}
    }

    // vc300: parse judge_total/grade dari JSON eval (forge_eval_save).
    // (-1, "") bila eval kosong / bukan JSON / kuncinya tidak ada.
    private fun judgeFromEvalVc300(evalJson: String): Pair<Int, String> {
        if (evalJson.isBlank()) return Pair(-1, "")
        return try {
            val o = JSONObject(evalJson)
            Pair(o.optInt("judge_total", -1), o.optString("grade", ""))
        } catch (e: Exception) { Pair(-1, "") }
    }

    /** vc300: simpan satu versi draf skill. version_no = max+1 untuk skill
     *  itu (v1 = draf pertama yang tersnap). Return id baris, -1 bila gagal.
     *  Tidak pernah throw — kegagalan versioning tidak boleh merusak
     *  upsert inti pemanggil. */
    fun snapshotSkillVersion(skillId: Long, instruction: String, descr: String, source: String, judgeTotal: Int = -1, judgeGrade: String = "", evalJson: String = ""): Long {
        return try {
            ensureSkillVersionTable()
            val nextNo = try {
                val c = readableDatabase.rawQuery("SELECT COALESCE(MAX(version_no), 0) FROM skill_versions WHERE skill_id=?", arrayOf(skillId.toString()))
                val n = if (c.moveToFirst()) c.getInt(0) + 1 else 1
                c.close(); n
            } catch (e: Exception) { 1 }
            val cv = ContentValues()
            cv.put("skill_id", skillId); cv.put("version_no", nextNo)
            cv.put("instruction", instruction); cv.put("descr", descr)
            cv.put("judge_total", judgeTotal); cv.put("judge_grade", judgeGrade)
            cv.put("eval_json", evalJson); cv.put("source", source)
            cv.put("created_ts", System.currentTimeMillis())
            writableDatabase.insert("skill_versions", null, cv)
        } catch (e: Exception) { -1 }
    }

    /** vc301 SF-A2: backfill EAGER. Bila skill ada tapi belum punya versi
     *  apa pun, snapshot keadaan live saat itu sebagai v1 source='backfill'
     *  beserta eval+judge-nya. Idempotent — hanya bila kosong. Return true
     *  bila skill itu punya versi sesudah panggilan ini. */
    fun ensureSkillBackfilled(skillId: Long): Boolean {
        return try {
            ensureSkillVersionTable()
            if (skillVersionsRaw(skillId).isNotEmpty()) return true
            val sk = skills().firstOrNull { it.id == skillId } ?: return false
            val (tVc301, gVc301) = judgeFromEvalVc300(sk.evalJson)
            snapshotSkillVersion(skillId, sk.instruction, sk.descr, "backfill", tVc301, gVc301, sk.evalJson) > 0
        } catch (e: Exception) { false }
    }

    fun skillVersions(skillId: Long): List<SkillVersion> {
        // vc301 SF-A2: tiap pembacaan daftar versi mem-backfill dulu —
        // skill lama yang belum pernah di-overwrite sesudah vc300 kini
        // terlindungi saat pertama kali dibaca/dinilai, bukan saat
        // di-overwrite. Tulis di jalur baca ini disengaja (satu tempat).
        try { ensureSkillBackfilled(skillId) } catch (e: Exception) {}
        return skillVersionsRaw(skillId)
    }

    private fun skillVersionsRaw(skillId: Long): List<SkillVersion> {
        val out = ArrayList<SkillVersion>()
        try {
            ensureSkillVersionTable()
            val c = readableDatabase.rawQuery("SELECT id,skill_id,version_no,instruction,descr,judge_total,judge_grade,eval_json,source,created_ts FROM skill_versions WHERE skill_id=? ORDER BY version_no ASC", arrayOf(skillId.toString()))
            while (c.moveToNext()) out.add(SkillVersion(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3) ?: "", c.getString(4) ?: "", c.getInt(5), c.getString(6) ?: "", c.getString(7) ?: "", c.getString(8) ?: "", c.getLong(9)))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    /** vc301 SF-A1c: impor satu baris versi dari backup (exportJson /
     *  sync-restore) dengan nomor + stempel ASLI. Insert hanya bila
     *  (skill_id, version_no) belum ada — tabel tanpa UNIQUE, jadi cek
     *  manual. Return true bila baris baru dimasukkan. */
    fun importSkillVersion(skillId: Long, versionNo: Int, instruction: String, descr: String, judgeTotal: Int, judgeGrade: String, evalJson: String, source: String, createdTs: Long): Boolean {
        return try {
            ensureSkillVersionTable()
            val exists = readSkillVersion(skillId, versionNo) != null
            if (exists) return false
            val cv = ContentValues()
            cv.put("skill_id", skillId); cv.put("version_no", versionNo)
            cv.put("instruction", instruction); cv.put("descr", descr)
            cv.put("judge_total", judgeTotal); cv.put("judge_grade", judgeGrade)
            cv.put("eval_json", evalJson); cv.put("source", source)
            cv.put("created_ts", if (createdTs > 0) createdTs else System.currentTimeMillis())
            writableDatabase.insert("skill_versions", null, cv) > 0
        } catch (e: Exception) { false }
    }

    /** vc301 SF-A1c: hapus SATU baris versi — HANYA untuk dedup saat
     *  restore backup (snapshot yang baru dibuat forgeUpsert di tengah
     *  restore, bila skill itu sebelumnya tidak punya versi, dihapus
     *  supaya penomoran asli dari backup pulih utuh). Bukan jalur hapus
     *  umum: skill_versions tetap append-only di semua jalur lain. */
    fun deleteSkillVersionRow(id: Long): Boolean {
        return try {
            ensureSkillVersionTable()
            writableDatabase.delete("skill_versions", "id=?", arrayOf(id.toString())) > 0
        } catch (e: Exception) { false }
    }

    /** vc301 SF-B3: semua versi semua skill (untuk exportJson) — urut
     *  per skill lalu nomor versi. */
    fun allSkillVersions(): List<SkillVersion> {
        val out = ArrayList<SkillVersion>()
        try {
            ensureSkillVersionTable()
            val c = readableDatabase.rawQuery("SELECT id,skill_id,version_no,instruction,descr,judge_total,judge_grade,eval_json,source,created_ts FROM skill_versions ORDER BY skill_id ASC, version_no ASC", null)
            while (c.moveToNext()) out.add(SkillVersion(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3) ?: "", c.getString(4) ?: "", c.getInt(5), c.getString(6) ?: "", c.getString(7) ?: "", c.getString(8) ?: "", c.getLong(9)))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    // vc301 SF-B3: ledger export skill (pola lazy seperti
    // ensureSkillVersionTable, tanpa bump versi DB).
    private fun ensureSkillExportLedgerTable() {
        try { writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS skill_export_ledger(id INTEGER PRIMARY KEY AUTOINCREMENT, skill_id INTEGER, skill_name TEXT, file_name TEXT, bytes INTEGER, hash TEXT, created_ts INTEGER)") } catch (e: Exception) {}
    }

    /** vc301 SF-B3: catat satu export skill ke ledger. Return id baris, -1 bila gagal. */
    fun recordSkillExport(skillId: Long, skillName: String, fileName: String, bytes: Long, hash: String): Long {
        return try {
            ensureSkillExportLedgerTable()
            val cv = ContentValues()
            cv.put("skill_id", skillId); cv.put("skill_name", skillName)
            cv.put("file_name", fileName); cv.put("bytes", bytes); cv.put("hash", hash)
            cv.put("created_ts", System.currentTimeMillis())
            writableDatabase.insert("skill_export_ledger", null, cv)
        } catch (e: Exception) { -1 }
    }

    /** vc301 SF-B3: jejak export terakhir-dulu (default 50 baris). */
    fun skillExportLedger(limit: Int = 50): List<SkillExportRow> {
        val out = ArrayList<SkillExportRow>()
        try {
            ensureSkillExportLedgerTable()
            val c = readableDatabase.rawQuery("SELECT id,skill_id,skill_name,file_name,bytes,hash,created_ts FROM skill_export_ledger ORDER BY id DESC LIMIT ?", arrayOf(limit.coerceAtLeast(1).toString()))
            while (c.moveToNext()) out.add(SkillExportRow(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4), c.getString(5) ?: "", c.getLong(6)))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    fun readSkillVersion(skillId: Long, versionNo: Int): SkillVersion? {
        return try {
            ensureSkillVersionTable()
            val c = readableDatabase.rawQuery("SELECT id,skill_id,version_no,instruction,descr,judge_total,judge_grade,eval_json,source,created_ts FROM skill_versions WHERE skill_id=? AND version_no=?", arrayOf(skillId.toString(), versionNo.toString()))
            val r = if (c.moveToFirst()) SkillVersion(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3) ?: "", c.getString(4) ?: "", c.getInt(5), c.getString(6) ?: "", c.getString(7) ?: "", c.getString(8) ?: "", c.getLong(9)) else null
            c.close(); r
        } catch (e: Exception) { null }
    }

    fun latestSkillVersion(skillId: Long): SkillVersion? {
        return try {
            ensureSkillVersionTable()
            val c = readableDatabase.rawQuery("SELECT id,skill_id,version_no,instruction,descr,judge_total,judge_grade,eval_json,source,created_ts FROM skill_versions WHERE skill_id=? ORDER BY version_no DESC LIMIT 1", arrayOf(skillId.toString()))
            val r = if (c.moveToFirst()) SkillVersion(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3) ?: "", c.getString(4) ?: "", c.getInt(5), c.getString(6) ?: "", c.getString(7) ?: "", c.getString(8) ?: "", c.getLong(9)) else null
            c.close(); r
        } catch (e: Exception) { null }
    }

    /** vc300: pulihkan draf versi lama. Hasil restore disimpan lewat
     *  forgeUpsert(source='restore') sehingga menjadi VERSI BARU — history
     *  tidak dimutasi. Eval versi itu ikut pulih via setSkillEval SETELAH
     *  forgeUpsert (forgeUpsert menghanguskan eval bila isi berubah,
     *  aturan vc299 N6-m8, jadi restore eval harus sesudahnya).
     *  Return false bila skill/versi tidak ditemukan atau upsert gagal. */
    fun restoreSkillVersion(skillId: Long, versionNo: Int): Boolean {
        return try {
            ensureSkillVersionTable()
            val ver = readSkillVersion(skillId, versionNo) ?: return false
            val skill = skills().firstOrNull { it.id == skillId } ?: return false
            val (nid, _) = forgeUpsert(skill.name, ver.instruction, ver.descr, "restore")
            if (nid <= 0) return false
            // VC303-P3-21 (EV-9): eval versi lama divalidasi saat restore;
            // bila tidak valid DITANDAI (bukan dibuang) — flag yang sama
            // ikut ke baris versi terbaru supaya labelnya konsisten.
            val evalRestoredVc303 = SkillJudge.markImportEvalVc303(ver.evalJson)
            if (ver.evalJson.isNotBlank()) setSkillEval(skillId, evalRestoredVc303)
            // vc300 koordinator: baris versi BARU hasil forgeUpsert di atas
            // tersnap TANPA eval/judge (isi berubah -> wipe N6-m8), padahal
            // eval live sudah pulih — tempelkan judge+eval versi SUMBER ke
            // baris versi terbaru supaya list_skill_versions tidak menampilkannya
            // "belum dinilai" padahal eval-nya pulih.
            if (ver.evalJson.isNotBlank()) attachJudgeToLatestVersion(skillId, ver.judgeTotal, ver.judgeGrade, evalRestoredVc303)
            else if (ver.judgeTotal >= 0) attachJudgeToLatestVersion(skillId, ver.judgeTotal, ver.judgeGrade)
            true
        } catch (e: Exception) { false }
    }

    /** vc300: tempel hasil judge ke baris versi terbaru skill itu
     *  (dipakai forge_eval_save setelah setSkillEval). eval_json hanya
     *  diisi bila evalJson tidak kosong. No-op aman (return false) bila
     *  skill itu belum punya versi apa pun. Ini pengisian kolom judge,
     *  bukan mutasi isi draf — teks versi tetap append-only. */
    fun attachJudgeToLatestVersion(skillId: Long, total: Int, grade: String, evalJson: String = ""): Boolean {
        return try {
            ensureSkillVersionTable()
            val latest = latestSkillVersion(skillId) ?: return false
            val cv = ContentValues()
            cv.put("judge_total", total); cv.put("judge_grade", grade)
            if (evalJson.isNotBlank()) cv.put("eval_json", evalJson)
            writableDatabase.update("skill_versions", cv, "id=?", arrayOf(latest.id.toString())) > 0
        } catch (e: Exception) { false }
    }

    // vc301 SF-A5: addSkill DIHAPUS — dead code (0 pemanggil di seluruh
    // tree; dulu masih memotong deskripsi ke 1000 karakter, tanpa snapshot
    // versi). Semua jalur simpan skill memakai forgeUpsert (lihat di bawah).

    fun skillByName(name: String): Skill? {
        return try { skills().firstOrNull { it.name.equals(name, ignoreCase = true) } } catch (e: Exception) { null }
    }

    /** vc216 Skill Forge: nama sama = perbarui isi TANPA mengubah status aktif;
     *  nama baru = simpan dalam keadaan NONAKTIF (enabled=0). Berbeda dari
     *  impor lama yang memaksa enabled=1 — perilaku impor TIDAK diubah.
     *  vc261 N-m28: upsertSkill (pemaksa enabled=1) DIHAPUS — dead code,
     *  0 pemanggil; semua jalur impor/restore memakai forgeUpsert.
     *  vc300 BAGIAN A: tiap simpan menyisakan versi di skill_versions —
     *  sebelum overwrite pertama skill tanpa versi, teks LAMA disnap
     *  sebagai v1 source='backfill'; sesudah simpan, teks BARU disnap
     *  dengan source param ('forge' default, 'restore' dari
     *  restoreSkillVersion). Eval snapshot mengikuti aturan vc299 N6-m8:
     *  hangus -> tanpa eval/judge; identik -> eval dipertahankan. */
    fun forgeUpsert(name: String, instruction: String, descr: String, source: String = "forge"): Pair<Long, Boolean> {
        // vc230 R-10(b): validasi disamakan dengan impor Setup 51 — nama dan
        // isi wajib tidak kosong; deskripsi kosong diganti baris pertama isi.
        if (name.isBlank() || instruction.isBlank()) return Pair(-1, false)
        val descrVc230 = if (descr.isNotBlank()) descr else instruction.lines().firstOrNull { it.isNotBlank() }?.take(200) ?: ""
        if (descrVc230.isBlank()) return Pair(-1, false)
        val ex = skillByName(name)
        if (ex != null) {
            return try {
                ensureSkillCols()
                ensureSkillVersionTable()
                val cv = ContentValues()
                // vc293 + vc299 N6-m8: isi berubah = benchmark milik draf
                // lama HANGUS; simpan-ulang IDENTIK mempertahankan eval.
                val newInstrVc299 = instruction.take(60000)
                // vc300: cap descr 1000 char DIHAPUS (Sendy 12 Nov) —
                // deskripsi disimpan utuh berapa pun panjangnya.
                val newDescrVc299 = descrVc230
                val wipeVc300 = SkillJudge.evalWipeNeededVc299(listOf(ex.instruction, ex.descr), listOf(newInstrVc299, newDescrVc299))
                cv.put("instruction", newInstrVc299); cv.put("descr", newDescrVc299)
                if (wipeVc300) cv.put("eval", "")
                // vc301 SF-A3 + SF-A4: update + SEMUA snapshot dalam SATU
                // transaksi. SEBELUM overwrite: bila belum ada versi, teks
                // LAMA disnap sebagai v1 'backfill' (vc300); bila sudah ada
                // versi tapi isinya berbeda dari live, teks live LAMA disnap
                // sebagai 'pre-overwrite' — pasangan live hasil edit
                // Settings (mis. meta-edit) tidak lagi musnah tanpa jejak.
                // Simpan-ulang IDENTIK dilewati (tidak menggandakan baris).
                // Snapshot return -1 = batalkan transaksi (rollback) —
                // upsert GAGAL jujur (Pair(-1, …)) ketimbang teks baru
                // tersimpan tanpa teks lama terselamatkan.
                val dbVc301 = writableDatabase
                dbVc301.beginTransaction()
                try {
                    val latestVc301 = latestSkillVersion(ex.id)
                    if (latestVc301 == null) {
                        val (oldTotalVc300, oldGradeVc300) = judgeFromEvalVc300(ex.evalJson)
                        if (snapshotSkillVersion(ex.id, ex.instruction, ex.descr, "backfill", oldTotalVc300, oldGradeVc300, ex.evalJson) < 0) throw IllegalStateException("snapshot backfill gagal")
                    } else if (latestVc301.instruction != ex.instruction || latestVc301.descr != ex.descr) {
                        val (oldTotalVc301, oldGradeVc301) = judgeFromEvalVc300(ex.evalJson)
                        if (snapshotSkillVersion(ex.id, ex.instruction, ex.descr, "pre-overwrite", oldTotalVc301, oldGradeVc301, ex.evalJson) < 0) throw IllegalStateException("snapshot pre-overwrite gagal")
                    }
                    dbVc301.update("skills", cv, "id=?", arrayOf(ex.id.toString()))
                    // vc300 BAGIAN A: SESUDAH overwrite, snapshot teks BARU.
                    // Eval hangus -> snapshot tanpa eval/judge; simpan-ulang
                    // identik -> snapshot membawa eval + judge yang dipertahankan.
                    if (wipeVc300) {
                        if (snapshotSkillVersion(ex.id, newInstrVc299, newDescrVc299, source) < 0) throw IllegalStateException("snapshot pasca-overwrite gagal")
                    } else {
                        val (keptTotalVc300, keptGradeVc300) = judgeFromEvalVc300(ex.evalJson)
                        if (snapshotSkillVersion(ex.id, newInstrVc299, newDescrVc299, source, keptTotalVc300, keptGradeVc300, ex.evalJson) < 0) throw IllegalStateException("snapshot pasca-overwrite gagal")
                    }
                    dbVc301.setTransactionSuccessful()
                } finally {
                    dbVc301.endTransaction()
                }
                try { Embedder.embedAsync(appCtx, "skill", "skill:${ex.id}", "${name.take(80)}: ${instruction.take(2000)}") } catch (e: Exception) {}
                Pair(ex.id, true)
            } catch (e: Exception) { Pair(-1, true) }
        }
        return try {
            ensureSkillCols()
            ensureSkillVersionTable()
            val cv = ContentValues()
            // vc300: cap descr 1000 char DIHAPUS (Sendy 12 Nov).
            cv.put("name", name.take(80)); cv.put("instruction", instruction.take(60000)); cv.put("descr", descrVc230); cv.put("enabled", 0); cv.put("eval", "")
            // vc301 SF-A4: insert + snapshot v1 dalam satu transaksi —
            // bila snapshot gagal, skill tidak lahir tanpa v1.
            val dbVc301 = writableDatabase
            var idVc301 = -1L
            dbVc301.beginTransaction()
            try {
                idVc301 = dbVc301.insert("skills", null, cv)
                if (idVc301 > 0) {
                    // vc300 BAGIAN A: skill baru langsung punya v1 (source param).
                    if (snapshotSkillVersion(idVc301, instruction.take(60000), descrVc230, source) < 0) throw IllegalStateException("snapshot v1 gagal")
                }
                dbVc301.setTransactionSuccessful()
            } finally {
                dbVc301.endTransaction()
            }
            val id = idVc301
            if (id > 0) Embedder.embedAsync(appCtx, "skill", "skill:$id", "${name.take(80)}: ${instruction.take(2000)}")
            Pair(id, false)
        } catch (e: Exception) { Pair(-1, false) }
    }

    fun setSkillEnabled(id: Long, on: Boolean) {
        try {
            ensureSkillCols()
            val cv = ContentValues(); cv.put("enabled", if (on) 1 else 0)
            writableDatabase.update("skills", cv, "id=?", arrayOf(id.toString()))
        } catch (e: Exception) {}
    }

    /** vc293: simpan benchmark evaluasi skill (JSON <=16000 char, sudah
     *  divalidasi pemanggil). Benchmark milik draf itu — JANGAN panggil
     *  untuk skill yang isinya baru diubah tanpa evaluasi ulang.
     *  VC303-P1-7 (EV-7): cap naik 8000 -> 16000 BERSAMAAN dengan
     *  Tools.kt:2150 — votes tidak boleh dipersingkat (meringkas query
     *  mengubah hash, EV-3); JSON tersimpan terpotong diam-diam = korup. */
    fun setSkillEval(id: Long, evalJson: String) {
        try {
            ensureSkillCols()
            val cv = ContentValues(); cv.put("eval", evalJson.take(16000))
            writableDatabase.update("skills", cv, "id=?", arrayOf(id.toString()))
        } catch (e: Exception) {}
    }

    // VC303-P1-1 (EV-1/2/5): run store eval — tabel lazy mengikuti pola
    // ensureSkillVersionTable (tanpa bump versi DB).
    private fun ensureEvalRunTable() {
        try { writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS eval_runs(skill_id INTEGER, trigger_query_hash TEXT, queries_json TEXT, votes_json TEXT, trigger_train INTEGER, trigger_test INTEGER, descr_hash TEXT, skill_version_no INTEGER, created_ts INTEGER, PRIMARY KEY(skill_id, trigger_query_hash))") } catch (e: Exception) {}
    }

    /** VC303-P1-1: simpan satu run eval (dipanggil Tools.kt sesudah
     *  forge_trigger_eval sukses dengan agent_votes). Insert-or-replace
     *  per (skill_id, hash); sesudah simpan langsung prune (maks 5
     *  run/skill + TTL 7 hari). Return false bila gagal — kegagalan simpan
     *  run TIDAK boleh menggagalkan hasil eval (pemanggil mengabaikan). */
    fun saveEvalRun(skillId: Long, hash: String, queriesJson: String, votesJson: String, triggerTrain: Int, triggerTest: Int, descrHash: String, skillVersionNo: Int): Boolean {
        return try {
            ensureEvalRunTable()
            val cv = ContentValues()
            cv.put("skill_id", skillId); cv.put("trigger_query_hash", hash)
            cv.put("queries_json", queriesJson); cv.put("votes_json", votesJson)
            cv.put("trigger_train", triggerTrain); cv.put("trigger_test", triggerTest)
            cv.put("descr_hash", descrHash); cv.put("skill_version_no", skillVersionNo)
            cv.put("created_ts", System.currentTimeMillis())
            writableDatabase.insertWithOnConflict("eval_runs", null, cv, SQLiteDatabase.CONFLICT_REPLACE) > 0
            pruneEvalRuns(skillId)
            true
        } catch (e: Exception) { false }
    }

    /** VC303-P1-1: muat run eval berdasarkan hash (save-by-hash di
     *  forge_eval_save). Return null bila tidak ada run untuk hash itu. */
    fun getEvalRun(skillId: Long, hash: String): EvalRun? {
        return try {
            ensureEvalRunTable()
            val c = readableDatabase.rawQuery("SELECT skill_id,trigger_query_hash,queries_json,votes_json,trigger_train,trigger_test,descr_hash,skill_version_no,created_ts FROM eval_runs WHERE skill_id=? AND trigger_query_hash=?", arrayOf(skillId.toString(), hash))
            val r = if (c.moveToFirst()) EvalRun(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getInt(4), c.getInt(5), c.getString(6) ?: "", c.getInt(7), c.getLong(8)) else null
            c.close(); r
        } catch (e: Exception) { null }
    }

    /** VC303-P1-1: prune run eval satu skill — hapus yang lebih tua dari
     *  TTL 7 hari, lalu sisakan maks 5 run terbaru. Dipanggil tiap
     *  saveEvalRun; tidak pernah throw. */
    fun pruneEvalRuns(skillId: Long) {
        try {
            ensureEvalRunTable()
            val cutoff = System.currentTimeMillis() - EvalLoopGuardVc303.EVAL_RUN_TTL_DAYS_VC303 * 24L * 3600L * 1000L
            writableDatabase.delete("eval_runs", "skill_id=? AND created_ts<?", arrayOf(skillId.toString(), cutoff.toString()))
            val keep = EvalLoopGuardVc303.MAX_EVAL_RUNS_PER_SKILL_VC303
            val c = readableDatabase.rawQuery("SELECT trigger_query_hash FROM eval_runs WHERE skill_id=? ORDER BY created_ts DESC, rowid DESC LIMIT -1 OFFSET $keep", arrayOf(skillId.toString()))
            val old = ArrayList<String>()
            while (c.moveToNext()) old.add(c.getString(0) ?: "")
            c.close()
            for (h in old) {
                if (h.isNotEmpty()) writableDatabase.delete("eval_runs", "skill_id=? AND trigger_query_hash=?", arrayOf(skillId.toString(), h))
            }
        } catch (e: Exception) {}
    }

    fun skillEval(id: Long): String {
        return try { skills().firstOrNull { it.id == id }?.evalJson ?: "" } catch (e: Exception) { "" }
    }

    fun updateSkillMeta(id: Long, name: String, descr: String) {
        try {
            ensureSkillCols()
            ensureSkillVersionTable()
            val cur = skills().firstOrNull { it.id == id }
            // vc293 + vc299 N6-m8: benchmark hangus HANYA bila nama/deskripsi
            // benar-benar berubah; simpan tanpa perubahan isi mempertahankan eval.
            // vc300: cap descr 1000 char DIHAPUS (Sendy 12 Nov) — deskripsi
            // disimpan utuh; pembanding eval memakai nilai yang sama dengan
            // yang disimpan (tanpa take) supaya simpan-ulang identik atas
            // descr >1000 char tidak salah menghanguskan eval.
            val cv = ContentValues(); cv.put("name", name.take(80)); cv.put("descr", descr)
            val changedVc301 = cur == null || SkillJudge.evalWipeNeededVc299(listOf(cur.name, cur.descr), listOf(name.take(80), descr))
            if (changedVc301) cv.put("eval", "")
            // vc301 SF-A3 + SF-A4: pra-snapshot + update + pasca-snapshot
            // dalam SATU transaksi. Pra-edit: snapshot keadaan LAMA
            // (vc300, hanya bila benar-benar berubah). Pasca-edit: snapshot
            // keadaan BARU source='meta' bila (instruction, descr) hasil
            // edit berbeda dari versi terbaru — pasangan hasil edit
            // Settings kini tersimpan, tidak lagi hanya hidup di baris
            // skills. Snapshot gagal (-1) = rollback: edit tidak
            // diterapkan, teks lama utuh (trade-off dicatat di
            // BAGIAN-B-BACKUP.md — gagalnya edit terlihat karena nilai
            // lama tetap tampil; kehilangan teks diam-diam dicegah).
            val dbVc301 = writableDatabase
            dbVc301.beginTransaction()
            try {
                if (cur != null && changedVc301) {
                    val (oldTotalVc300, oldGradeVc300) = judgeFromEvalVc300(cur.evalJson)
                    if (snapshotSkillVersion(id, cur.instruction, cur.descr, "meta", oldTotalVc300, oldGradeVc300, cur.evalJson) < 0) throw IllegalStateException("snapshot meta pra-edit gagal")
                }
                dbVc301.update("skills", cv, "id=?", arrayOf(id.toString()))
                if (cur != null && changedVc301) {
                    val latestVc301 = latestSkillVersion(id)
                    if (latestVc301 == null || latestVc301.instruction != cur.instruction || latestVc301.descr != descr) {
                        // Eval hangus bila isi berubah (N6-m8) — snapshot
                        // pasca-edit tanpa eval/judge, seperti snapshot
                        // pasca-overwrite di forgeUpsert.
                        if (snapshotSkillVersion(id, cur.instruction, descr, "meta") < 0) throw IllegalStateException("snapshot meta pasca-edit gagal")
                    }
                }
                dbVc301.setTransactionSuccessful()
            } finally {
                dbVc301.endTransaction()
            }
            if (cur != null) Embedder.embedAsync(appCtx, "skill", "skill:$id", "${name.take(80)}: ${cur.instruction.take(2000)}")
        } catch (e: Exception) {}
    }

    fun skills(): List<Skill> {
        val out = ArrayList<Skill>()
        try {
            ensureSkillCols()
            val c = readableDatabase.rawQuery("SELECT id,name,instruction,descr,enabled,eval FROM skills ORDER BY id", null)
            while (c.moveToNext()) out.add(Skill(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getInt(4) != 0, c.getString(5) ?: ""))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    // vc237 M9: deleteSkill mentah — penelepon WAJIB mengarsip dulu lewat
    // archiveDelete (delete_skill tool dan uiSkillDelete sudah begitu).
    // vc301 SF-A6: versi-versi skill itu ikut dihapus (sesudah arsip) —
    // dulu tertinggal yatim di skill_versions, tak terjangkau tool apa pun.
    fun deleteSkill(id: Long) {
        try {
            writableDatabase.delete("skills", "id=?", arrayOf(id.toString()))
            try { writableDatabase.delete("skill_versions", "skill_id=?", arrayOf(id.toString())) } catch (e: Exception) {}
            // VC303-P1-1: run eval skill itu ikut dihapus (tidak yatim).
            try { writableDatabase.delete("eval_runs", "skill_id=?", arrayOf(id.toString())) } catch (e: Exception) {}
            deleteEmbedding("skill", "skill:$id")
        } catch (e: Exception) {}
    }

    /** v4.13.0: KATALOG hemat — prompt hanya membawa nama + deskripsi
     * pemicu. Isi prosedur dimuat use_skill saat tugasnya relevan, jadi
     * skill besar tidak membebani setiap giliran.
     * vc248: cap total 20000 char seperti fakta + ranking relevansi
     * query (relevanceScore yang sama dengan factsText dan goalsText),
     * bukan potong urutan DB. Tanpa query, semua skor 0 dan urutan kembali
     * ke id (perilaku lama). Bila ada skill yang tak muat, penanda sisa
     * menyebut jumlahnya. Cap berlaku untuk baris skill; baris penanda
     * sisa adalah meta di luar cap, seperti penanda potong fakta. */
    fun skillsText(query: String = ""): String {
        val sk = skills().filter { it.enabled }
        if (sk.isEmpty()) return ""
        val toks = relevanceTokens(query)
        val lines = sk.sortedWith(compareByDescending<Skill> { relevanceScore(toks, (it.name + " " + if (it.descr.isNotBlank()) it.descr else it.instruction.take(160)).lowercase()) }.thenBy { it.id }).map { s ->
            val d = if (s.descr.isNotBlank()) s.descr else s.instruction.take(160)
            "- SKILL '${s.name}': $d"
        }
        val out = ArrayList<String>()
        var used = 0
        for (ln in lines) {
            if (used + ln.length + 1 > 20000) {
                if (out.isEmpty() && ln.length > 4000) out.add(ln.take(20000) + "…(dipotong, sisa " + (ln.length - 20000) + " char)…")
                break
            }
            out.add(ln); used += ln.length + 1
        }
        val hidden = lines.size - out.size
        if (hidden > 0) out.add("…($hidden skill tidak ditampilkan — pakai use_skill untuk prosedurnya)…")
        return out.joinToString("\n")
    }

    // ---------- Geofence ----------
    // vc243 GELOMBANG 2 (GR-M5): ownerSession = sesi 1-1 saat geofence dibuat.
    fun addGeofence(name: String, lat: Double, lng: Double, radiusM: Int, prompt: String, ownerSession: Long = 0): Long {
        return try {
            val cv = ContentValues()
            cv.put("name", name.take(80)); cv.put("lat", lat); cv.put("lng", lng); cv.put("radius", radiusM)
            cv.put("prompt", prompt.take(500)); cv.put("inside", 0)
            if (ownerSession > 0) cv.put("owner_session", ownerSession)
            try { ensureWave2Columns(writableDatabase) } catch (e: Exception) {}
            writableDatabase.insert("geofences", null, cv)
        } catch (e: Exception) { -1 }
    }

    fun geofences(): List<Geofence> {
        val out = ArrayList<Geofence>()
        try {
            val c = readableDatabase.rawQuery("SELECT id,name,lat,lng,radius,prompt,inside,owner_session FROM geofences", null)
            val ownIdx = c.getColumnIndex("owner_session")
            while (c.moveToNext()) out.add(Geofence(c.getLong(0), c.getString(1) ?: "", c.getDouble(2), c.getDouble(3), c.getInt(4), c.getString(5) ?: "", c.getInt(6) == 1, if (ownIdx >= 0) c.getLong(ownIdx) else 0))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    fun setGeofenceInside(id: Long, inside: Boolean) {
        try {
            val cv = ContentValues(); cv.put("inside", if (inside) 1 else 0)
            writableDatabase.update("geofences", cv, "id=?", arrayOf(id.toString()))
        } catch (e: Exception) {}
    }

    fun deleteGeofence(id: Long) {
        try { writableDatabase.delete("geofences", "id=?", arrayOf(id.toString())) } catch (e: Exception) {}
    }

    // ---------- Keuangan (dari notifikasi bank/e-wallet) ----------
    fun addFinance(pkg: String, amount: Long, kind: String, text: String) {
        try {
            val cv = ContentValues()
            cv.put("ts", System.currentTimeMillis()); cv.put("pkg", pkg); cv.put("amount", amount)
            cv.put("kind", kind); cv.put("text", text.take(300))
            val id = writableDatabase.insert("finance", null, cv)
            if (id > 0) Embedder.embedAsync(appCtx, "fin", "fin:$id", text.take(300))
        } catch (e: Exception) {}
    }

    fun financeSince(sinceTs: Long): List<FinEntry> {
        val out = ArrayList<FinEntry>()
        try {
            val c = readableDatabase.rawQuery("SELECT id,ts,pkg,amount,kind,text FROM finance WHERE ts>=? ORDER BY ts DESC LIMIT 200", arrayOf(sinceTs.toString()))
            while (c.moveToNext()) out.add(FinEntry(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getLong(3), c.getString(4) ?: "", c.getString(5) ?: ""))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    // ---------- Embedding (Tahap B: memori vektor) ----------
    data class EmbRow(val kind: String, val ref: String, val vec: FloatArray)

    // vc239 M8: cache decode embedding per proses (MemoryDb singleton).
    // allEmbeddings dulu membaca dan men-decode SELURUH tabel tiap
    // panggilan (tiap giliran + tiap recall_search). Cache diinvalidasi
    // di putEmbedding dan deleteEmbedding — satu-satunya penulis tabel
    // embeddings di seluruh kode — sehingga tidak ada jalur tulis yang
    // luput. Pemanggil menerima salinan daftar, bukan daftar cache itu
    // sendiri, agar iterasi tetap aman walau ada delete di tengah jalan.
    @Volatile private var embCache: List<EmbRow>? = null

    fun putEmbedding(kind: String, ref: String, thash: Int, vec: FloatArray) {
        try {
            val buf = java.nio.ByteBuffer.allocate(vec.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (f in vec) buf.putFloat(f)
            val cv = ContentValues()
            cv.put("kind", kind); cv.put("ref", ref); cv.put("vec", buf.array()); cv.put("thash", thash)
            writableDatabase.insertWithOnConflict("embeddings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            embCache = null
        } catch (e: Exception) {}
    }

    fun deleteEmbedding(kind: String, ref: String) {
        try { writableDatabase.delete("embeddings", "kind=? AND ref=?", arrayOf(kind, ref)); embCache = null } catch (e: Exception) {}
    }

    fun embeddingHash(kind: String, ref: String): Int? {
        return try {
            val c = readableDatabase.rawQuery("SELECT thash FROM embeddings WHERE kind=? AND ref=?", arrayOf(kind, ref))
            val r = if (c.moveToFirst()) c.getInt(0) else null
            c.close(); r
        } catch (e: Exception) { null }
    }

    fun embeddingCount(): Int {
        return try {
            val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM embeddings", null)
            val r = if (c.moveToFirst()) c.getInt(0) else 0
            c.close(); r
        } catch (e: Exception) { 0 }
    }

    @Synchronized
    fun allEmbeddings(): List<EmbRow> {
        embCache?.let { return it.toList() }
        val out = ArrayList<EmbRow>()
        try {
            val c = readableDatabase.rawQuery("SELECT kind,ref,vec FROM embeddings", null)
            while (c.moveToNext()) {
                val blob = c.getBlob(2) ?: continue
                val buf = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val vec = FloatArray(blob.size / 4)
                for (i in vec.indices) vec[i] = buf.getFloat(i * 4)
                out.add(EmbRow(c.getString(0) ?: "", c.getString(1) ?: "", vec))
            }
            c.close()
        } catch (e: Exception) {}
        embCache = out
        return out.toList()
    }

    // vc239 M8: display kandidat dalam SATU query per jenis (IN-list
    // di-chunk 200), bukan satu query per kandidat (pola N+1 lama di
    // embeddingDisplay). Format string SAMA PERSIS dengan yang lama, dan
    // kandidat yang sumbernya hilang tetap tidak ada di peta (pemanggil
    // menghapus embedding yatimnya). Kunci peta: kind + "|" + ref.
    private fun embeddingDisplays(cands: List<EmbRow>): Map<String, String> {
        val out = HashMap<String, String>()
        fun key(kind: String, ref: String) = kind + "|" + ref
        fun inQuery(table: String, cols: String, ids: List<String>): ArrayList<Array<String>> {
            val rows = ArrayList<Array<String>>()
            for (chunk in ids.chunked(200)) {
                if (chunk.isEmpty()) continue
                try {
                    val ph = chunk.joinToString(",") { "?" }
                    val c = readableDatabase.rawQuery("SELECT " + cols + " FROM " + table + " WHERE id IN (" + ph + ")", chunk.toTypedArray())
                    while (c.moveToNext()) {
                        val r = Array<String>(c.columnCount) { i -> c.getString(i) ?: "" }
                        rows.add(r)
                    }
                    c.close()
                } catch (e: Exception) {}
            }
            return rows
        }
        try {
            val factRefs = cands.filter { it.kind == "fact" }.map { it.ref }
            for (chunk in factRefs.chunked(200)) {
                if (chunk.isEmpty()) continue
                try {
                    val ph = chunk.joinToString(",") { "?" }
                    val c = readableDatabase.rawQuery("SELECT k,v FROM facts WHERE (status IS NULL OR status='active') AND k IN (" + ph + ")", chunk.toTypedArray())
                    while (c.moveToNext()) out[key("fact", c.getString(0) ?: "")] = "Fakta — ${c.getString(0)}: ${c.getString(1)}"
                    c.close()
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        val idOf: (EmbRow) -> Long? = { it.ref.substringAfter(":", "").toLongOrNull() }
        for (r in inQuery("skills", "id,name,instruction", cands.filter { it.kind == "skill" }.mapNotNull { idOf(it)?.toString() })) {
            out[key("skill", "skill:" + r[0])] = "Skill — ${r[1]}: ${r[2].take(220)}"
        }
        for (r in inQuery("history", "id,role,text,ts", cands.filter { it.kind == "hist" }.mapNotNull { idOf(it)?.toString() })) {
            val ts = r[3].toLongOrNull() ?: 0L
            out[key("hist", "hist:" + r[0])] = "Riwayat [${fmtTs(ts)}] ${r[1]}: ${r[2].take(220)}"
        }
        for (r in inQuery("finance", "id,kind,amount,text,ts", cands.filter { it.kind == "fin" }.mapNotNull { idOf(it)?.toString() })) {
            val amt = r[2].toLongOrNull() ?: 0L
            val ts = r[4].toLongOrNull() ?: 0L
            out[key("fin", "fin:" + r[0])] = "Keuangan [${fmtTs(ts)}] ${r[1]} Rp$amt: ${r[3].take(180)}"
        }
        for (r in inQuery("goals", "id,title,status", cands.filter { it.kind == "goal" }.mapNotNull { idOf(it)?.toString() })) {
            out[key("goal", "goal:" + r[0])] = "Tujuan — ${r[1]} (${r[2]})"
        }
        return out
    }

    /** Pencarian kosinus beneran atas semua embedding. "" bila tidak ada yang melewati ambang. */
    // vc239 M8: ambang kosinus 0.30 TIDAK diubah (audit tegas: itu
    // mengubah hasil, bukan hanya kecepatan). Yang berubah hanya biaya:
    // decode embedding kini dari cache proses (allEmbeddings) dan display
    // kandidat diambil batch per jenis (embeddingDisplays), bukan satu
    // query per kandidat. Pemisahan jenis (mis. hist vs fact) SENGAJA
    // tidak dilakukan karena akan mengubah hasil pencarian yang sudah ada.
    fun semanticSearch(queryVec: FloatArray, limit: Int = 8, minScore: Double = 0.30): String {
        val rows = allEmbeddings()
        if (rows.isEmpty()) return ""
        val scored = ArrayList<Pair<Double, EmbRow>>()
        for (r in rows) {
            if (r.vec.size != queryVec.size) continue
            var s = 0.0
            for (i in queryVec.indices) s += queryVec[i] * r.vec[i]
            if (s >= minScore) scored.add(s to r)
        }
        if (scored.isEmpty()) return ""
        val ordered = scored.sortedByDescending { it.first }
        val displays = embeddingDisplays(ordered.map { it.second })
        val lines = ArrayList<String>()
        for ((_, r) in ordered) {
            val disp = displays[r.kind + "|" + r.ref]
            if (disp == null) { deleteEmbedding(r.kind, r.ref); continue }
            lines.add(disp)
            if (lines.size >= limit) break
        }
        return lines.joinToString("\n")
    }

    /** Entri yang belum punya embedding (untuk backfill migrasi Tahap B). */
    fun missingEmbeddings(limit: Int = 400): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        fun have(kind: String, ref: String) = embeddingHash(kind, ref) != null
        try {
            for ((k, v) in facts()) {
                if (!have("fact", k)) out.add(Triple("fact", k, "$k: $v"))
            }
            for (sk in skills()) {
                val ref = "skill:${sk.id}"
                if (!have("skill", ref)) out.add(Triple("skill", ref, "${sk.name}: ${sk.instruction}"))
            }
            val c = readableDatabase.rawQuery("SELECT id,text FROM history ORDER BY id DESC LIMIT 300", null)
            while (c.moveToNext()) {
                val id = c.getLong(0); val t = c.getString(1) ?: ""
                val ref = "hist:$id"
                if (t.length >= 12 && !have("hist", ref)) out.add(Triple("hist", ref, t.take(400)))
            }
            c.close()
            val cf = readableDatabase.rawQuery("SELECT id,text FROM finance ORDER BY id DESC LIMIT 100", null)
            while (cf.moveToNext()) {
                val id = cf.getLong(0); val ref = "fin:$id"
                if (!have("fin", ref)) out.add(Triple("fin", ref, cf.getString(1) ?: ""))
            }
            cf.close()
        } catch (e: Exception) {}
        return out.take(limit)
    }

    // ---------- Tujuan multi-hari (Tahap C) ----------
    fun createGoal(title: String, steps: List<Pair<String, Long>>): Long {
        var gid = -1L
        try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val cv = ContentValues()
            cv.put("title", title.take(200)); cv.put("status", "aktif"); cv.put("created", now); cv.put("updated", now)
            gid = db.insert("goals", null, cv)
            var pos = 0
            for ((text, due) in steps) {
                val sc = ContentValues()
                sc.put("goal_id", gid); sc.put("text", text.take(300)); sc.put("due", due)
                sc.put("done", 0); sc.put("done_ts", 0); sc.put("pos", pos++)
                db.insert("goal_steps", null, sc)
            }
            if (gid > 0) {
                val allText = title + " — " + steps.joinToString("; ") { it.first }
                Embedder.embedAsync(appCtx, "goal", "goal:$gid", allText.take(400))
            }
        } catch (e: Exception) {}
        return gid
    }

    fun goals(activeOnly: Boolean = true): List<Goal> {
        val out = ArrayList<Goal>()
        try {
            val q = if (activeOnly)
                "SELECT id,title,status,created,updated FROM goals WHERE status='aktif' ORDER BY id"
            else "SELECT id,title,status,created,updated FROM goals ORDER BY id"
            val c = readableDatabase.rawQuery(q, null)
            while (c.moveToNext()) out.add(Goal(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "aktif", c.getLong(3), c.getLong(4)))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    fun goalSteps(goalId: Long): List<GoalStep> {
        val out = ArrayList<GoalStep>()
        try {
            val c = readableDatabase.rawQuery("SELECT id,goal_id,text,due,done,done_ts FROM goal_steps WHERE goal_id=? ORDER BY pos,id", arrayOf(goalId.toString()))
            while (c.moveToNext()) out.add(GoalStep(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getLong(3), c.getInt(4) != 0, c.getLong(5)))
            c.close()
        } catch (e: Exception) {}
        return out
    }

    fun addGoalStep(goalId: Long, text: String, due: Long): Long {
        var id = -1L
        try {
            val c = readableDatabase.rawQuery("SELECT COALESCE(MAX(pos),-1) FROM goal_steps WHERE goal_id=?", arrayOf(goalId.toString()))
            val pos = if (c.moveToFirst()) c.getInt(0) + 1 else 0
            c.close()
            val cv = ContentValues()
            cv.put("goal_id", goalId); cv.put("text", text.take(300)); cv.put("due", due)
            cv.put("done", 0); cv.put("done_ts", 0); cv.put("pos", pos)
            id = writableDatabase.insert("goal_steps", null, cv)
            touchGoal(goalId)
        } catch (e: Exception) {}
        return id
    }

    fun setStepDone(stepId: Long, done: Boolean = true) {
        try {
            val cv = ContentValues()
            cv.put("done", if (done) 1 else 0); cv.put("done_ts", if (done) System.currentTimeMillis() else 0)
            writableDatabase.update("goal_steps", cv, "id=?", arrayOf(stepId.toString()))
            val c = readableDatabase.rawQuery("SELECT goal_id FROM goal_steps WHERE id=?", arrayOf(stepId.toString()))
            if (c.moveToFirst()) touchGoal(c.getLong(0))
            c.close()
        } catch (e: Exception) {}
    }

    fun setGoalStatus(goalId: Long, status: String) {
        try {
            val cv = ContentValues()
            cv.put("status", status); cv.put("updated", System.currentTimeMillis())
            writableDatabase.update("goals", cv, "id=?", arrayOf(goalId.toString()))
        } catch (e: Exception) {}
    }

    private fun touchGoal(goalId: Long) {
        try {
            val cv = ContentValues(); cv.put("updated", System.currentTimeMillis())
            writableDatabase.update("goals", cv, "id=?", arrayOf(goalId.toString()))
        } catch (e: Exception) {}
    }

    /** Langkah goal yang jatuh tempo & belum selesai (goal masih aktif). */
    fun dueGoalSteps(now: Long): List<Pair<Goal, GoalStep>> {
        val out = ArrayList<Pair<Goal, GoalStep>>()
        try {
            val c = readableDatabase.rawQuery(
                "SELECT s.id,s.goal_id,s.text,s.due,g.title FROM goal_steps s JOIN goals g ON g.id=s.goal_id WHERE s.done=0 AND s.due>0 AND s.due<=? AND g.status='aktif' ORDER BY s.due",
                arrayOf(now.toString()))
            while (c.moveToNext()) {
                val gid = c.getLong(1)
                out.add(Goal(gid, c.getString(4) ?: "", "aktif", 0, 0) to GoalStep(c.getLong(0), gid, c.getString(2) ?: "", c.getLong(3), false, 0))
            }
            c.close()
        } catch (e: Exception) {}
        return out
    }

    /** Ringkasan tujuan aktif untuk disuntikkan ke prompt (chat & Otak Latar). */
    // vc248: cap goal 10000 char (dari 2000), pemangkasan berbasis relevansi
    // terhadap pesan berjalan (bukan baris tertua); potong di batas blok goal.
    // vc236 M5: skoring sama dengan fakta (kata-utuh 2.0 > substring 1.0,
    // stopword); goal tidak punya flag confirmed sehingga tanpa bobot 1.5;
    // seri diputus id DESC (goal terbaru dulu). Cap vc248: 10000.
    // Catatan interaksi M23: pemanggil tanpa query (latar) kini mendapat
    // urutan seri terbaru-dulu, bukan id ASC lama — kelompok M23 harap
    // memeriksa ulang bila mengubah urutan dasar goals().
    // vc240 M23 (verifikasi konsistensi kelompok 4): mode tanpa query
    // BUKAN "relevan" — semua skor 0, seri diputus id DESC (terbaru dulu),
    // konsisten dengan fakta (ts DESC). Pemanggil latar kini mengoper teks
    // pemicu (BgBrain) agar mode relevan hidup di sana; pemanggil tanpa
    // pemicu apa pun tetap mendapat urutan terbaru, bukan terlama.
    fun goalsText(query: String = ""): String {
        val gs = goals(true)
        if (gs.isEmpty()) return ""
        val toks = relevanceTokens(query)
        val blocks = ArrayList<Pair<Long, String>>()
        for (g in gs) {
            val steps = goalSteps(g.id)
            val doneN = steps.count { it.done }
            val b = StringBuilder()
            b.append("🎯 #${g.id} ${g.title} (progres $doneN/${steps.size})\n")
            // vc241 O9: langkah SELESAI tidak ditampilkan per baris — diganti hitungan progres di header blok di atas. Langkah selesai hanya tampil bila query menyebutnya (token query cocok di teks langkah).
            var hiddenDoneVc241 = 0
            for (s in steps) {
                if (s.done) {
                    val mentionedVc241 = toks.isNotEmpty() && toks.any { s.text.lowercase().contains(it) }
                    if (mentionedVc241) b.append("   ✓ [step ${s.id}] ${s.text}\n") else hiddenDoneVc241++
                } else {
                    b.append("   • [step ${s.id}] ${s.text}")
                    if (s.dueTs > 0) b.append(" (jatuh tempo ${fmtTs(s.dueTs)})")
                    b.append("\n")
                }
            }
            if (hiddenDoneVc241 > 0) b.append("   …($hiddenDoneVc241 langkah selesai disembunyikan — sebutkan langkahnya bila perlu ditampilkan)…\n")
            blocks.add(g.id to b.toString().trim())
        }
        val scored = blocks.sortedWith(compareByDescending<Pair<Long, String>> { relevanceScore(toks, it.second.lowercase()) }.thenByDescending { it.first })
        val out = ArrayList<String>()
        var used = 0
        for ((_, bl) in scored) {
            if (used + bl.length + 1 > 10000) {
                if (out.isEmpty() && bl.length > 2000) out.add(bl.take(10000) + "…(dipotong, sisa " + (bl.length - 10000) + " char)…")
                break
            }
            out.add(bl); used += bl.length + 1
        }
        return out.joinToString("\n\n")
    }

    // vc237 M13 (pilihan a audit: benar-benar mengarsip dulu): nama "arsip"
    // kini benar — baris riwayat disalin dulu ke deleted_archive (kind
    // history, payload role/text/ts/session) SEBELUM dihapus, jadi bisa
    // dilihat dan dipulihkan lewat Setup 66 (restoreArchived kind history).
    // Embedding hist ikut dihapus proaktif dengan pola yang sama seperti
    // deleteSession (bukan dibiarkan yatim untuk pembersihan malas).
    // Episode chat1-1 yang lebih tua dari cutoff ikut dihapus supaya
    // provenance tidak menggantung tanpa sumber; episode room/kamera punya
    // lifecycle sendiri dan tidak dihapus di sini — hanya session_id chat1-1
    // yang ditulis addHistory dari baris history.
    fun archiveOldHistory(beforeTs: Long): Int {
        return try {
            val ids = ArrayList<Long>()
            val c = readableDatabase.rawQuery("SELECT id, role, text, ts, session FROM history WHERE ts<?", arrayOf(beforeTs.toString()))
            while (c.moveToNext()) {
                val hid = c.getLong(0)
                ids.add(hid)
                try { archiveDelete("history", "#$hid", JSONObject().put("role", c.getString(1) ?: "").put("text", c.getString(2) ?: "").put("ts", c.getLong(3)).put("session", c.getLong(4)).toString()) } catch (e: Exception) {}
            }
            c.close()
            val n = writableDatabase.delete("history", "ts<?", arrayOf(beforeTs.toString()))
            for (hid in ids) { try { deleteEmbedding("hist", "hist:$hid") } catch (e: Exception) {} }
            try { GraphDb.get(appCtx).deleteEpisodesBefore("chat1-1", beforeTs) } catch (e: Exception) {}
            n
        } catch (e: Exception) { 0 }
    }

    // vc240 M16: pencarian semantik dengan dedup lintas blok. Baris
    // hasil yang duplikat fakta tabel (kunci fakta sudah tampil di blok
    // MEMORI PERSISTEN/graph) dibuang, dan cuplikan riwayat dari sesi ini
    // dibuang bila sesi ini sudah tampil di blok riwayat/ringkasan —
    // sehingga guard yang memangkas riwayat tidak dihidupkan lagi oleh
    // blok semantik di giliran yang sama. Aturan prioritas per giliran:
    // MEMORY.md terkurasi menang, lalu tabel fakta, lalu graph, lalu
    // semantik (yang difilter di sini). Ambang kosinus 0,30 tidak diubah.
    fun semanticSearchVc240(queryVec: FloatArray, limit: Int = 8, minScore: Double = 0.30, excludeHistSession: Long = -1L, excludeFactKeys: List<String> = emptyList()): String {
        val rows = allEmbeddings()
        if (rows.isEmpty()) return ""
        val scored = ArrayList<Pair<Double, EmbRow>>()
        for (r in rows) {
            if (r.vec.size != queryVec.size) continue
            var s = 0.0
            for (i in queryVec.indices) s += queryVec[i] * r.vec[i]
            if (s >= minScore) scored.add(s to r)
        }
        if (scored.isEmpty()) return ""
        val ordered = scored.sortedByDescending { it.first }
        val displays = embeddingDisplays(ordered.map { it.second })
        val keys = excludeFactKeys.map { it.lowercase() }.filter { it.length >= 3 }
        val lines = ArrayList<String>()
        for ((_, r) in ordered) {
            val disp = displays[r.kind + "|" + r.ref] ?: continue
            if (r.kind == "hist" && excludeHistSession > 0) {
                val hid = r.ref.removePrefix("hist:").toLongOrNull()
                if (hid != null) {
                    var sess = -1L
                    try {
                        val c = readableDatabase.rawQuery("SELECT session FROM history WHERE id = ?", arrayOf(hid.toString()))
                        if (c.moveToFirst()) sess = c.getLong(0)
                        c.close()
                    } catch (e: Exception) {}
                    if (sess == excludeHistSession) continue
                }
            }
            val low = disp.lowercase()
            if (keys.any { low.contains(it) }) continue
            lines.add(disp)
            if (lines.size >= limit) break
        }
        return lines.joinToString("\n")
    }

    // vc240 m6: backfill bertahap berkelanjutan per jenis dengan cursor
    // (offset SQL), bukan sekali + pool tetap. Mengembalikan pasangan
    // (item yang belum punya embedding, jumlah baris yang dipindai halaman
    // ini) agar pemanggil bisa memajukan cursor sebesar halaman yang
    // dipindai, bukan sebesar item yang hilang.
    data class PagedMissing(val items: List<Triple<String, String, String>>, val scanned: Int)

    fun missingEmbeddingsPaged(kind: String, offset: Int, limit: Int): PagedMissing {
        val items = ArrayList<Triple<String, String, String>>()
        var scanned = 0
        fun have(k: String, ref: String) = embeddingHash(k, ref) != null
        try {
            when (kind) {
                "fact" -> {
                    val c = readableDatabase.rawQuery("SELECT k,v FROM facts WHERE status IS NULL OR status='active' ORDER BY ts DESC LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString()))
                    while (c.moveToNext()) {
                        scanned++
                        val k = c.getString(0) ?: ""
                        if (!have("fact", k)) items.add(Triple("fact", k, "$k: ${c.getString(1) ?: ""}"))
                    }
                    c.close()
                }
                "skill" -> {
                    val c = readableDatabase.rawQuery("SELECT id,name,instruction FROM skills ORDER BY id LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString()))
                    while (c.moveToNext()) {
                        scanned++
                        val ref = "skill:" + c.getLong(0)
                        if (!have("skill", ref)) items.add(Triple("skill", ref, "${c.getString(1) ?: ""}: ${c.getString(2) ?: ""}"))
                    }
                    c.close()
                }
                "hist" -> {
                    val c = readableDatabase.rawQuery("SELECT id,text FROM history ORDER BY id DESC LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString()))
                    while (c.moveToNext()) {
                        scanned++
                        val id = c.getLong(0); val t = c.getString(1) ?: ""
                        val ref = "hist:$id"
                        if (t.length >= 12 && !have("hist", ref)) items.add(Triple("hist", ref, t.take(400)))
                    }
                    c.close()
                }
                "fin" -> {
                    val c = readableDatabase.rawQuery("SELECT id,text FROM finance ORDER BY id DESC LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString()))
                    while (c.moveToNext()) {
                        scanned++
                        val ref = "fin:" + c.getLong(0)
                        if (!have("fin", ref)) items.add(Triple("fin", ref, c.getString(1) ?: ""))
                    }
                    c.close()
                }
            }
        } catch (e: Exception) {}
        return PagedMissing(items, scanned)
    }

    // vc240 M22: dedup deterministik pra-dialog ekstraksi (dipakai dialog
    // batch, bukan saat penampungan — penampungan tetap mentah agar tidak
    // ada proposal yang hilang diam-diam sebelum user melihat antrean).
    // Aturan: key ternormalisasi sama persis dengan fakta yang sudah ada
    // dan nilainya sama -> buang; nilai beda atau key mirip (flat sama
    // atau saling mengandung + token bersama) -> gabung jadi satu proposal
    // "perbarui" memakai key lama (putFact kelompok 1 mengarsip nilai lama)
    // dengan catatan diff untuk dialog. Hanya proposal lolos yang masuk dialog.
    data class DedupExtractResult(val kept: List<Pair<String, String>>, val diffNotes: List<String>, val dropped: Int)

    fun dedupExtractProposals(props: List<Pair<String, String>>): DedupExtractResult {
        val flat = { s: String -> s.trim().lowercase().filter { ch -> ch != '_' && ch != '-' && ch != ' ' } }
        val existing = factsAllWithStatus().map { t ->
            Triple(t.first, t.second, flat(t.first))
        }
        val kept = ArrayList<Pair<String, String>>()
        val notes = ArrayList<String>()
        var dropped = 0
        for ((k, v) in props) {
            val nk = k.trim()
            if (nk.isBlank() || v.isBlank()) { dropped++; continue }
            val fk = flat(nk)
            val exact = existing.firstOrNull { it.third == fk || it.first.trim().lowercase() == nk.lowercase() }
            if (exact != null) {
                if (exact.second.trim() == v.trim()) { dropped++; continue }
                kept.add(exact.first to v.trim())
                notes.add("Perbarui '" + exact.first + "': '" + exact.second.take(120) + "' -> '" + v.trim().take(120) + "'")
                continue
            }
            val toks = nk.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
            val similar = existing.firstOrNull { e ->
                val et = e.first.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
                toks.isNotEmpty() && et.isNotEmpty() && (e.first.lowercase().contains(nk.lowercase()) || nk.lowercase().contains(e.first.lowercase())) && toks.intersect(et).isNotEmpty()
            }
            if (similar != null) {
                kept.add(similar.first to v.trim())
                notes.add("Perbarui '" + similar.first + "' (key mirip '" + nk + "'): '" + similar.second.take(120) + "' -> '" + v.trim().take(120) + "'")
                continue
            }
            kept.add(nk to v.trim())
        }
        return DedupExtractResult(kept, notes, dropped)
    }

    // ---------- Pencarian memori (skor kata kunci = semantic-lite) ----------
    fun searchMemory(query: String, limit: Int = 8): String {
        val tokens = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.distinct()
        if (tokens.isEmpty()) return "Kata kunci terlalu pendek."
        val scored = ArrayList<Pair<Int, String>>()
        // vc236 M4: jalur retrieval memakai pool penuh (factsAll), bukan
        // 40 terbaru — sama seperti factsText.
        for ((k, v) in factsAll()) {
            val hay = (k + " " + v).lowercase()
            val sc = tokens.count { hay.contains(it) }
            if (sc > 0) scored.add(sc to "Fakta — $k: $v")
        }
        try {
            val c = readableDatabase.rawQuery("SELECT role,text,ts FROM history ORDER BY id DESC LIMIT 300", null)
            while (c.moveToNext()) {
                val t = c.getString(1) ?: ""
                val hay = t.lowercase()
                val sc = tokens.count { hay.contains(it) }
                if (sc >= 2) scored.add(sc to "Riwayat [${fmtTs(c.getLong(2))}] ${c.getString(0)}: ${t.take(220)}")
            }
            c.close()
        } catch (e: Exception) {}
        if (scored.isEmpty()) return "Tidak ada memori yang cocok dengan '$query'."
        return scored.sortedByDescending { it.first }.take(limit).joinToString("\n") { it.second }
    }

    fun exportJson(): String {
        // vc261 N-M15/N-M16: fakta dari pool PENUH (factsAllWithStatus,
        // tanpa LIMIT 40) + kolom "confirmed" — restore tidak lagi
        // memotong fakta ke-41+ atau mempromosikan yang belum dikonfirmasi.
        // facts() LIMIT 40 tetap untuk daftar UI (tidak diubah).
        val sb = StringBuilder("{\"facts\":[")
        sb.append(factsAllWithStatus().joinToString(",") {
            "{\"k\":\"${esc(it.first)}\",\"v\":\"${esc(it.second)}\",\"confirmed\":${it.third}}"
        })
        sb.append("],\"skills\":[")
        // vc261 N-M17: skill ikut descr + enabled supaya restore bisa
        // memulihkan status aktif/nonaktif tanpa duplikat (forgeUpsert).
        // vc301 SF-A1b: skill ikut eval — restore memulihkan benchmark
        // via setSkillEval sesudah forgeUpsert (pola restoreSkillVersion).
        sb.append(skills().joinToString(",") {
            "{\"name\":\"${esc(it.name)}\",\"instruction\":\"${esc(it.instruction)}\",\"descr\":\"${esc(it.descr)}\",\"enabled\":${it.enabled},\"eval\":\"${esc(it.evalJson)}\"}"
        })
        sb.append("],\"schedules\":[")
        // vc262 FT-M4: jadwal ikut ekspor LENGKAP (ownerSession + enabled)
        // agar restore bisa memulihkan pemilik sesi jadwal.
        sb.append(schedules().joinToString(",") {
            "{\"hour\":${it.hour},\"minute\":${it.minute},\"prompt\":\"${esc(it.prompt)}\",\"ownerSession\":${it.ownerSession},\"enabled\":${it.enabled}}"
        })
        sb.append("],\"rules\":[")
        sb.append(rules().joinToString(",") {
            "{\"pkg\":\"${esc(it.pkgContains)}\",\"needle\":\"${esc(it.textContains)}\",\"action\":\"${esc(it.actionPrompt)}\"}"
        })
        // vc301 SF-A1c: riwayat versi skill ikut backup — tiap baris
        // {skill_id, skill_name, version_no, instruction, descr,
        // judge_total, judge_grade, eval_json, source, created_ts}.
        // Restore mengembalikannya dengan nomor asli (importSkillVersion).
        sb.append("],\"skill_versions\":[")
        sb.append(skillVersionsExportJson())
        sb.append("]}")
        return sb.toString()
    }

    /** vc301 SF-A1c: isi array "skill_versions" untuk exportJson —
     *  dipisah supaya mudah diuji; skill_name dicari dari baris skills
     *  (versi yatim pra-vc301 — bila ada — bernama "(skill terhapus)"). */
    private fun skillVersionsExportJson(): String {
        val names = try { skills().associate { it.id to it.name } } catch (e: Exception) { emptyMap<Long, String>() }
        return try {
            allSkillVersions().joinToString(",") {
                "{\"skill_id\":${it.skillId},\"skill_name\":\"${esc(names[it.skillId] ?: "(skill terhapus)")}\",\"version_no\":${it.versionNo},\"instruction\":\"${esc(it.instruction)}\",\"descr\":\"${esc(it.descr)}\",\"judge_total\":${it.judgeTotal},\"judge_grade\":\"${esc(it.judgeGrade)}\",\"eval_json\":\"${esc(it.evalJson)}\",\"source\":\"${esc(it.source)}\",\"created_ts\":${it.createdTs}}"
            }
        } catch (e: Exception) { "" }
    }

    /** vc301 SF-A1a: escaper JSON yang BENAR. Dulu newline diratakan
     *  jadi spasi (replace ke " ") — tiap restore mengubah
     *  instruction multi-baris menjadi satu baris panjang (dan fakta
     *  bernilai multi-baris ikut rusak — satu akar). Kini \n→\\n (dan
     *  \r, \t, karakter kontrol) sehingga JSONObject di sisi restore
     *  mengembalikan teks byte-identik. Satu-satunya pemakai esc() adalah
     *  exportJson di atas — tidak ada kode yang bergantung pada perilaku
     *  lama (grep: esc() hanya di 4 call site exportJson). */
    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    companion object {
        // Antrean perintah otomatis (jadwal / aturan pemicu / geofence) yang menunggu
        // dijalankan MainActivity saat app dibuka. Disimpan di SharedPreferences.
        private const val QUEUE_KEY = "pendingPrompts"

        fun enqueuePrompt(ctx: Context, prompt: String) {
            try {
                val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
                val cur = prefs.getString(QUEUE_KEY, "") ?: ""
                if (cur.isBlank()) { prefs.edit().putString(QUEUE_KEY, prompt).apply(); return }
                // F2.1: dedupe persis — prompt yang sama tidak diantre 2x.
                // Tanpa ini, sesi latar yang mati membuat antrean tumbuh
                // 2 entri per 30 detik tanpa batas (parade turn berulang).
                if (prompt in cur.split("\u0001")) return
                prefs.edit().putString(QUEUE_KEY, cur + "\u0001" + prompt).apply()
            } catch (e: Exception) {}
        }

        fun drainPrompts(ctx: Context): List<String> {
            return try {
                val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
                val cur = prefs.getString(QUEUE_KEY, "") ?: ""
                if (cur.isBlank()) emptyList()
                else {
                    prefs.edit().remove(QUEUE_KEY).apply()
                    cur.split("\u0001").filter { it.isNotBlank() }
                }
            } catch (e: Exception) { emptyList() }
        }

        @Volatile private var inst: MemoryDb? = null
        fun get(ctx: Context): MemoryDb =
            inst ?: synchronized(this) { inst ?: MemoryDb(ctx).also { inst = it } }

        fun fmtTs(ts: Long): String =
            SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
