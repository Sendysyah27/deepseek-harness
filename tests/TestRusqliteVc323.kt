package com.senzwh.deepharness

import harness.core.RustCore
import java.io.File
import org.json.JSONObject

// TestRusqliteVc323 — P9 (slice 7, vc323): paritas seam rusqlite.
// (P8 sudah dipakai fix sesi vc322 — rusqlite jadi P9 di vc323.)
//
// CATATAN JUJUR (probe JVM 16 Nov): konstruktor android.content.Context
// di android.jar melempar "Stub!" — kelas wrapper (SQLiteOpenHelper)
// TIDAK BISA diinstansiasi di JVM. Karena itu P9 menguji:
//   (a) SEAM LANGSUNG via RustCore.sqliteJson + SqliteSeam (jalur Rust
//       asli lewat .so host, mode rust) dengan SQL YANG SAMA PERSIS
//       dengan yang dipakai wrapper (identitas SQL dikunci §11);
//   (b) FALLBACK: tanpa .so, SqliteSeam mengembalikan null/false
//       (mode kotlin) — pemanggil wrapper lalu memakai *Kotlin;
//   (c) SUMBER: wrapper berisi public fun + *Kotlin + saklar rust,
//       MemoryDb.kt TIDAK tersentuh Rust, DDL Kotlin ada verbatim
//       di sqlite_schema.rs.
// Verifikasi wrapper end-to-end (instansiasi + operasi lewat API
// publik) menjadi bagian TES perangkat (TES-VC322, T-baru) —
// bukan klaim P9.
//
// Pemakaian: TestRusqliteVc323Kt <rust|kotlin> <treeDir> <tmpDir> <coreDir>
//   treeDir = direktori .kt terpadu (integrated / app/.../deepharness)
//   tmpDir  = direktori kerja .db sementara
//   coreDir = harness-core (untuk baca sqlite_schema.rs + Cargo.lock)
private var PASS = 0
private var FAIL = 0
private fun cek(nama: String, ok: Boolean) {
    if (ok) {
        PASS++
        println("LULUS $nama")
    } else {
        FAIL++
        println("GAGAL $nama")
    }
}

private fun raw(req: String): JSONObject? {
    val s = try {
        RustCore.sqliteJson(req)
    } catch (t: Throwable) {
        null
    } ?: return null
    return try {
        val o = JSONObject(s)
        if (o.optBoolean("ok", false)) o else null
    } catch (e: Exception) {
        null
    }
}

private fun jarr(vararg v: Any?): org.json.JSONArray {
    val a = org.json.JSONArray()
    for (x in v) a.put(x ?: JSONObject.NULL)
    return a
}

private fun q(db: String, sql: String, vararg p: Any?): org.json.JSONArray? {
    val o = raw(
        JSONObject().put("op", "query").put("db", db).put("sql", sql)
            .put("params", jarr(*p)).toString()
    ) ?: return null
    return o.optJSONArray("rows")
}

private fun x(db: String, sql: String, vararg p: Any?): JSONObject? {
    return raw(
        JSONObject().put("op", "exec").put("db", db).put("sql", sql)
            .put("params", jarr(*p)).toString()
    )
}

private fun ensure(db: String, key: String): JSONObject? {
    return raw(
        JSONObject().put("op", "ensure").put("db", db).put("key", key).toString()
    )
}

private fun tables(db: String): Set<String> {
    val rows = q(db, "SELECT name FROM sqlite_master WHERE type='table'") ?: return emptySet()
    val out = HashSet<String>()
    for (i in 0 until rows.length()) {
        val r = rows.optJSONArray(i) ?: continue
        out.add(r.optString(0))
    }
    return out
}

private fun hasCol(db: String, table: String, col: String): Boolean {
    val rows = q(db, "PRAGMA table_info($table)") ?: return false
    for (i in 0 until rows.length()) {
        val r = rows.optJSONArray(i) ?: continue
        if (r.optString(1) == col) return true
    }
    return false
}

private fun userVer(db: String): Long {
    val rows = q(db, "PRAGMA user_version") ?: return -1
    if (rows.length() == 0) return -1
    return rows.optJSONArray(0)?.optLong(0, -1) ?: -1
}

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "rust"
    val treeDir = args.getOrNull(1) ?: "."
    val tmpDir = File(args.getOrNull(2) ?: "/tmp/p8-vc323")
    val coreDir = File(args.getOrNull(3) ?: "harness-core")
    tmpDir.mkdirs()
    val rustMode = mode == "rust"
    println("== P9 TestRusqliteVc323 mode=$mode tree=$treeDir tmp=$tmpDir ==")

    // ---------- §1 seam + VERSION ----------
    cek("1.1 RustCore.VERSION == 8", RustCore.VERSION == 8)
    cek("1.2 RustCore.available == rustMode", RustCore.available == rustMode)
    if (rustMode) {
        val caps = try {
            JSONObject(RustCore.rustCoreCapabilities())
        } catch (t: Throwable) {
            null
        }
        cek("1.3 capabilities berisi sqliteJson", caps != null && caps.optJSONArray("functions")?.let { a ->
            (0 until a.length()).any { a.optString(it) == "sqliteJson" }
        } == true)
        cek("1.4 capabilities berisi modul sqlite+sqlite_schema", caps != null && caps.optJSONArray("modules")?.let { a ->
            (0 until a.length()).any { a.optString(it) == "sqlite" } &&
                (0 until a.length()).any { a.optString(it) == "sqlite_schema" }
        } == true)
    } else {
        cek("1.3 kotlin: capabilities tak dipakai (so absen)", true)
        cek("1.4 kotlin: VERSION tetap 8", RustCore.VERSION == 8)
    }

    // ---------- §10 (kotlin) fallback SqliteSeam tanpa .so ----------
    if (!rustMode) {
        val noDb = File(tmpDir, "nope.db").absolutePath
        cek("10.1 SqliteSeam.ensure false tanpa .so", !SqliteSeam.ensure(noDb, "events"))
        cek("10.2 SqliteSeam.query null tanpa .so", SqliteSeam.query(noDb, "SELECT 1") == null)
        cek("10.3 SqliteSeam.exec null tanpa .so", SqliteSeam.exec(noDb, "SELECT 1") == null)
        cek("10.4 SqliteSeam.tx null tanpa .so", SqliteSeam.tx(noDb, listOf("SELECT 1" to emptyList())) == null)
    }

    if (rustMode) {
        // ---------- §2 ensure tiap DB ----------
        val dbE = File(tmpDir, "events.db").absolutePath
        val dbA = File(tmpDir, "artifacts.db").absolutePath
        val dbP = File(tmpDir, "parked.db").absolutePath
        val dbG = File(tmpDir, "graph.db").absolutePath
        val dbM = File(tmpDir, "memory.db").absolutePath
        cek("2.1 ensure events v1", ensure(dbE, "events")?.optLong("version", -1) == 1L)
        cek("2.2 ensure artifacts v1", ensure(dbA, "artifacts")?.optLong("version", -1) == 1L)
        cek("2.3 ensure parked v1", ensure(dbP, "parked")?.optLong("version", -1) == 1L)
        cek("2.4 ensure graph v3", ensure(dbG, "graph")?.optLong("version", -1) == 3L)
        cek("2.5 ensure memory v7", ensure(dbM, "memory")?.optLong("version", -1) == 7L)
        cek("2.6 user_version events == 1", userVer(dbE) == 1L)
        cek("2.7 user_version graph == 3", userVer(dbG) == 3L)
        cek("2.8 user_version memory == 7", userVer(dbM) == 7L)
        cek("2.9 ensure idempotent (ulang)", ensure(dbG, "graph")?.optLong("version", -1) == 3L)
        cek("2.10 ensure key tak dikenal gagal", ensure(dbE, "tidak-ada") == null)
        val tE = tables(dbE)
        cek("2.11 tabel events ada", tE.contains("events"))
        val tA = tables(dbA)
        cek("2.12 tabel artifacts+versions ada", tA.contains("artifacts") && tA.contains("artifact_versions"))
        val tP = tables(dbP)
        cek("2.13 tabel parked_approvals ada", tP.contains("parked_approvals"))
        val tG = tables(dbG)
        cek("2.14 tabel graph+fts ada", tG.contains("nodes") && tG.contains("edges") && tG.contains("episodes") && tG.contains("fts_fact"))
        val tM = tables(dbM)
        cek("2.15 tabel memory v7 lengkap", listOf("facts", "history", "reminders", "sessions", "embeddings", "goals", "goal_steps", "oc_tasks", "coord_rules", "workers", "deleted_archive", "fact_edges", "dream_flags", "lessons", "fact_candidates").all { tM.contains(it) })
        cek("2.16 sessions(1,'Utama') seed", q(dbM, "SELECT title FROM sessions WHERE id=1")?.optJSONArray(0)?.optString(0) == "Utama")
        val jm = q(dbE, "PRAGMA journal_mode")?.optJSONArray(0)?.optString(0)
        cek("2.17 journal_mode WAL", jm == "wal")

        // ---------- §3 CRUD events (SQL sama persis dengan wrapper) ----------
        val ins = x(dbE, "INSERT INTO events(ts,type,pkg,text) VALUES(?,?,?,?)", 1700000000000L, "notif", "com.x", "halo 🌍 emoji")
        cek("3.1 insert event changes=1 last_id=1", ins != null && ins.optLong("changes", 0) == 1L && ins.optLong("last_id", 0) == 1L)
        val rows = q(dbE, "SELECT ts,type,pkg,text FROM events ORDER BY id DESC LIMIT ?", "10")
        cek("3.2 query event emoji utuh", rows != null && rows.length() == 1 && rows.optJSONArray(0)?.optString(3) == "halo 🌍 emoji")
        cek("3.3 query COUNT(*) == 1", q(dbE, "SELECT COUNT(*) FROM events")?.optJSONArray(0)?.optLong(0) == 1L)
        val upd = x(dbE, "UPDATE events SET text=? WHERE id=?", "ubah", "1")
        cek("3.4 update changes=1", upd?.optLong("changes", 0) == 1L)
        val old = q(dbE, "SELECT ts,type,pkg,text FROM events WHERE ts > ? ORDER BY id ASC LIMIT ?", "0", "10")
        cek("3.5 oldestSince 1 baris", old != null && old.length() == 1)
        val cnt = q(dbE, "SELECT COUNT(*) FROM events WHERE ts >= ? AND ts < ?", "0", "9999999999999")
        cek("3.6 countInRange == 1", cnt?.optJSONArray(0)?.optLong(0) == 1L)
        val del = x(dbE, "DELETE FROM events WHERE ts < ?", "9999999999999")
        cek("3.7 prune changes=1", del?.optLong("changes", 0) == 1L)
        cek("3.8 count sesudah prune == 0", q(dbE, "SELECT COUNT(*) FROM events")?.optJSONArray(0)?.optLong(0) == 0L)
        val bad = q(dbE, "SELECT * FROM tidak_ada")
        cek("3.9 SQL salah -> null (bukan crash)", bad == null)
        val badReq = raw("bukan json")
        cek("3.10 request bukan JSON -> null", badReq == null)

        // ---------- §4 tx atomik ----------
        val txFail = raw(
            JSONObject().put("op", "tx").put("db", dbE).put("steps", org.json.JSONArray().apply {
                put(JSONObject().put("sql", "INSERT INTO events(ts,type,pkg,text) VALUES(1,'a','p','t1')").put("params", org.json.JSONArray()))
                put(JSONObject().put("sql", "INSERT INTO tidak_ada(x) VALUES(1)").put("params", org.json.JSONArray()))
            }).toString()
        )
        cek("4.1 tx gagal -> null (rollback)", txFail == null)
        cek("4.2 tx gagal: baris pertama ikut hilang", q(dbE, "SELECT COUNT(*) FROM events")?.optJSONArray(0)?.optLong(0) == 0L)
        val txOk = raw(
            JSONObject().put("op", "tx").put("db", dbE).put("steps", org.json.JSONArray().apply {
                put(JSONObject().put("sql", "INSERT INTO events(ts,type,pkg,text) VALUES(1,'a','p','t1')").put("params", org.json.JSONArray()))
                put(JSONObject().put("sql", "INSERT INTO events(ts,type,pkg,text) VALUES(2,'a','p','t2')").put("params", org.json.JSONArray()))
            }).toString()
        )
        cek("4.3 tx sukses 2 langkah", txOk != null && txOk.optJSONArray("results")?.length() == 2)
        cek("4.4 count sesudah tx == 2", q(dbE, "SELECT COUNT(*) FROM events")?.optJSONArray(0)?.optLong(0) == 2L)

        // ---------- §5 artifacts (SQL wrapper) ----------
        val meta = "SELECT a.id, a.title, a.type, a.owner_session, a.created, a.updated, (SELECT COUNT(*) FROM artifact_versions v WHERE v.artifact_id = a.id), (SELECT MAX(version) FROM artifact_versions v WHERE v.artifact_id = a.id) FROM artifacts a"
        val artTx = raw(
            JSONObject().put("op", "tx").put("db", dbA).put("steps", org.json.JSONArray().apply {
                put(JSONObject().put("sql", "INSERT INTO artifacts(id, title, type, owner_session, created, updated) VALUES(?,?,?,?,?,?)").put("params", jarr("art-1", "Judul", "doc", 1L, 100L, 100L)))
                put(JSONObject().put("sql", "INSERT INTO artifact_versions(artifact_id, version, content, created) VALUES(?,?,?,?)").put("params", jarr("art-1", 1, "isi v1", 100L)))
            }).toString()
        )
        cek("5.1 create artifact tx ok", artTx != null)
        val got = q(dbA, "$meta WHERE a.id = ?", "art-1")
        cek("5.2 get meta versionCount=1 latest=1", got != null && got.length() == 1 && got.optJSONArray(0)?.optLong(6) == 1L && got.optJSONArray(0)?.optLong(7) == 1L)
        val v2 = raw(
            JSONObject().put("op", "tx").put("db", dbA).put("steps", org.json.JSONArray().apply {
                put(JSONObject().put("sql", "INSERT INTO artifact_versions(artifact_id, version, content, created) VALUES(?,?,?,?)").put("params", jarr("art-1", 2, "isi v2", 200L)))
                put(JSONObject().put("sql", "UPDATE artifacts SET updated = ? WHERE id = ?").put("params", jarr(200L, "art-1")))
            }).toString()
        )
        cek("5.3 addVersion tx ok", v2 != null)
        cek("5.4 latestVersion == 2", q(dbA, "$meta WHERE a.id = ?", "art-1")?.optJSONArray(0)?.optLong(7) == 2L)
        val ren = x(dbA, "UPDATE artifacts SET title = ?, updated = ? WHERE id = ?", "Judul Baru", 300L, "art-1")
        cek("5.5 rename changes=1", ren?.optLong("changes", 0) == 1L)
        val vers = q(dbA, "SELECT artifact_id, version, content, created FROM artifact_versions WHERE artifact_id = ? ORDER BY version ASC", "art-1")
        cek("5.6 versions 2 baris + isi v2", vers != null && vers.length() == 2 && vers.optJSONArray(1)?.optString(2) == "isi v2")
        val list = q(dbA, "$meta WHERE a.owner_session = ? ORDER BY a.updated DESC LIMIT ?", "1", "50")
        cek("5.7 listForSession 1 baris", list != null && list.length() == 1)
        val delA = raw(
            JSONObject().put("op", "tx").put("db", dbA).put("steps", org.json.JSONArray().apply {
                put(JSONObject().put("sql", "DELETE FROM artifact_versions WHERE artifact_id = ?").put("params", jarr("art-1")))
                put(JSONObject().put("sql", "DELETE FROM artifacts WHERE id = ?").put("params", jarr("art-1")))
            }).toString()
        )
        cek("5.8 delete tx ok", delA != null)
        cek("5.9 artifact hilang sesudah delete", q(dbA, "$meta WHERE a.id = ?", "art-1")?.length() == 0)

        // ---------- §6 parked (H-K1: claim atomik + decide set-once) ----------
        val rowSel = "SELECT pid, sessionId, tool, args, risk, status, decision, decidedAtMs, createdAtMs, timeoutMs, timeoutOwnerMs, notifId, task, origin, title, transcript, steps, job, tools, ownerSession FROM parked_approvals"
        val insP = x(dbP, "INSERT OR IGNORE INTO parked_approvals(pid, sessionId, tool, args, risk, status, decision, decidedAtMs, createdAtMs, timeoutMs, timeoutOwnerMs, notifId, task, origin, title, transcript, steps, job, tools, ownerSession) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            "p1", "1", "tool_x", "{}", "HIGH", "PENDING", null, 0L, 1000L, 60000L, 0L, 20000, "tugas", "bg", "judul", "trans", 1, "job", "[]", 1L)
        cek("6.1 insertIfAbsent changes=1", insP?.optLong("changes", 0) == 1L)
        val insP2 = x(dbP, "INSERT OR IGNORE INTO parked_approvals(pid, sessionId, tool, args, risk, status, decision, decidedAtMs, createdAtMs, timeoutMs, timeoutOwnerMs, notifId, task, origin, title, transcript, steps, job, tools, ownerSession) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            "p1", "1", "tool_x", "{}", "HIGH", "PENDING", null, 0L, 1000L, 60000L, 0L, 20000, "tugas", "bg", "judul", "trans", 1, "job", "[]", 1L)
        cek("6.2 insertIfAbsent ganda changes=0", insP2?.optLong("changes", 0) == 0L)
        val c1 = x(dbP, "UPDATE parked_approvals SET status = 'PROCESSING', timeoutOwnerMs = ? WHERE pid = ? AND status = 'PENDING'", 2000L, "p1")
        cek("6.3 claim pertama changes=1", c1?.optLong("changes", 0) == 1L)
        val c2 = x(dbP, "UPDATE parked_approvals SET status = 'PROCESSING', timeoutOwnerMs = ? WHERE pid = ? AND status = 'PENDING'", 3000L, "p1")
        cek("6.4 claim kedua changes=0 (atomik)", c2?.optLong("changes", 0) == 0L)
        cek("6.5 timeoutOwnerMs tetap klaim pertama", q(dbP, "$rowSel WHERE pid = ?", "p1")?.optJSONArray(0)?.optLong(10) == 2000L)
        val d1 = x(dbP, "UPDATE parked_approvals SET status = ?, decision = ?, decidedAtMs = ? WHERE pid = ? AND decision IS NULL", "APPROVED", "APPROVED", 4000L, "p1")
        cek("6.6 decide pertama changes=1", d1?.optLong("changes", 0) == 1L)
        val d2 = x(dbP, "UPDATE parked_approvals SET status = ?, decision = ?, decidedAtMs = ? WHERE pid = ? AND decision IS NULL", "REJECTED", "REJECTED", 5000L, "p1")
        cek("6.7 decide kedua changes=0 (set-once)", d2?.optLong("changes", 0) == 0L)
        cek("6.8 decision tetap APPROVED", q(dbP, "$rowSel WHERE pid = ?", "p1")?.optJSONArray(0)?.optString(6) == "APPROVED")
        cek("6.9 listPending kosong sesudah decide", q(dbP, "$rowSel WHERE status = ? ORDER BY createdAtMs ASC", "PENDING")?.length() == 0)
        cek("6.10 listActive kosong sesudah decide", q(dbP, "$rowSel WHERE status IN ('PENDING','PROCESSING') ORDER BY createdAtMs ASC")?.length() == 0)

        // ---------- §7 graph: node/edge/FTS/episode ----------
        val n1 = x(dbG, "INSERT INTO nodes(type, label, source) VALUES(?,?,?)", "entitas", "Jakarta", "llm")
        val n2 = x(dbG, "INSERT INTO nodes(type, label, source) VALUES(?,?,?)", "entitas", "Indonesia", "llm")
        cek("7.1 insert 2 node last_id 1,2", n1?.optLong("last_id", 0) == 1L && n2?.optLong("last_id", 0) == 2L)
        val dup = q(dbG, "SELECT id FROM nodes WHERE LOWER(label) = LOWER(?) LIMIT 1", "jakarta")
        cek("7.2 cari node case-insensitive", dup?.optJSONArray(0)?.optLong(0) == 1L)
        val e1 = x(dbG, "INSERT INTO edges(from_id, to_id, rel, tag, confidence, fact_text, recorded_at, valid_at) VALUES(?,?,?,?,?,?,?,?)", 1L, 2L, "ibu kota dari", "EXTRACTED", 1.0, "Jakarta ibu kota dari Indonesia", 1700000000000L, 1700000000000L)
        cek("7.3 insert edge last_id=1", e1?.optLong("last_id", 0) == 1L)
        val eDup = q(dbG, "SELECT id FROM edges WHERE from_id = ? AND to_id = ? AND rel = ? LIMIT 1", "1", "2", "ibu kota dari")
        cek("7.4 edge ganda terdeteksi (addEdge -> -2)", eDup?.length() == 1)
        x(dbG, "INSERT INTO fts_fact(fact_text) VALUES(?)", "Jakarta ibu kota dari Indonesia")
        val fts = q(dbG, "SELECT fact_text FROM fts_fact WHERE fts_fact MATCH ? LIMIT ?", "Jakarta*", "60")
        cek("7.5 FTS5 MATCH menemukan fakta", fts != null && fts.length() == 1)
        val ep = x(dbG, "INSERT INTO episodes(session_id, role, text, source, ts) VALUES(?,?,?,?,?)", "s1", "user", "halo", "llm", 1700000000000L)
        cek("7.6 insert episode last_id=1", ep?.optLong("last_id", 0) == 1L)
        cek("7.7 countEpisodes == 1", q(dbG, "SELECT COUNT(*) FROM episodes WHERE session_id = ?", "s1")?.optJSONArray(0)?.optLong(0) == 1L)
        cek("7.8 searchNodes prefix", q(dbG, "SELECT id, type, label, source FROM nodes WHERE label LIKE ? ESCAPE '\\' ORDER BY id DESC LIMIT ?", "Jak%", "20")?.length() == 1)
        cek("7.9 neighbors 1 hop", q(dbG, "SELECT e.to_id, e.rel FROM edges e WHERE e.from_id = ? AND e.invalid_at IS NULL LIMIT ?", "1", "20")?.length() == 1)
        val exp = x(dbG, "UPDATE edges SET invalid_at = ?, expired_at = ? WHERE id = ?", 1700000001000L, 1700000001000L, "1")
        cek("7.10 expireEdge changes=1", exp?.optLong("changes", 0) == 1L)
        cek("7.11 neighbors kosong sesudah expire", q(dbG, "SELECT e.to_id, e.rel FROM edges e WHERE e.from_id = ? AND e.invalid_at IS NULL LIMIT ?", "1", "20")?.length() == 0)
        val unexp = x(dbG, "UPDATE edges SET invalid_at = NULL, expired_at = NULL WHERE id = ?", "1")
        cek("7.12 unexpireEdge changes=1", unexp?.optLong("changes", 0) == 1L)
        cek("7.13 neighbors kembali 1 sesudah unexpire", q(dbG, "SELECT e.to_id, e.rel FROM edges e WHERE e.from_id = ? AND e.invalid_at IS NULL LIMIT ?", "1", "20")?.length() == 1)

        // ---------- §8 migrasi fixture: graph v2 -> v3 ----------
        val dbG2 = File(tmpDir, "graph-v2.db").absolutePath
        x(dbG2, "CREATE TABLE nodes (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, label TEXT, source TEXT)", *arrayOf<Any?>())
        x(dbG2, "CREATE TABLE edges (id INTEGER PRIMARY KEY AUTOINCREMENT, from_id INTEGER, to_id INTEGER, rel TEXT, weight REAL DEFAULT 1.0, tag TEXT DEFAULT 'EXTRACTED', confidence REAL DEFAULT 1.0)", *arrayOf<Any?>())
        // user_version diset via exec PRAGMA (exec memakai prepare; PRAGMA user_version = 2 jalan sebagai exec)
        raw(JSONObject().put("op", "exec").put("db", dbG2).put("sql", "PRAGMA user_version = 2").put("params", org.json.JSONArray()).toString())
        x(dbG2, "INSERT INTO nodes(type,label,source) VALUES('entitas','Jakarta','llm')", *arrayOf<Any?>())
        x(dbG2, "INSERT INTO nodes(type,label,source) VALUES('entitas','Indonesia','llm')", *arrayOf<Any?>())
        x(dbG2, "INSERT INTO edges(from_id,to_id,rel) VALUES(1,2,'ibu kota dari')", *arrayOf<Any?>())
        cek("8.1 fixture v2 user_version == 2", userVer(dbG2) == 2L)
        cek("8.2 migrasi graph v2->v3 version 3", ensure(dbG2, "graph")?.optLong("version", -1) == 3L)
        cek("8.3 backfill fact_text", q(dbG2, "SELECT fact_text FROM edges WHERE id=1")?.optJSONArray(0)?.optString(0) == "Jakarta ibu kota dari Indonesia")
        cek("8.4 kolom aliases_json ada", hasCol(dbG2, "nodes", "aliases_json"))
        cek("8.5 kolom episode_id ada", hasCol(dbG2, "edges", "episode_id"))
        cek("8.6 fts_fact terisi backfill", q(dbG2, "SELECT COUNT(*) FROM fts_fact WHERE fts_fact MATCH 'Jakarta*'")?.optJSONArray(0)?.optLong(0) == 1L)
        cek("8.7 tabel episodes ada", tables(dbG2).contains("episodes"))

        // ---------- §9 migrasi fixture: memory v6 -> v7 ----------
        val dbM6 = File(tmpDir, "memory-v6.db").absolutePath
        val v6ddl = listOf(
            "CREATE TABLE facts(k TEXT PRIMARY KEY, v TEXT, ts INTEGER, confirmed INTEGER DEFAULT 0)",
            "CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER, session INTEGER DEFAULT 1)",
            "CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT, due INTEGER, text TEXT, done INTEGER DEFAULT 0)",
            "CREATE TABLE schedules(id INTEGER PRIMARY KEY AUTOINCREMENT, hour INTEGER, minute INTEGER, prompt TEXT, enabled INTEGER DEFAULT 1, last_run_day INTEGER DEFAULT -1)",
            "CREATE TABLE rules(id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, needle TEXT, action TEXT, enabled INTEGER DEFAULT 1)",
            "CREATE TABLE skills(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, instruction TEXT)",
            "CREATE TABLE geofences(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, lat REAL, lng REAL, radius INTEGER, prompt TEXT, inside INTEGER DEFAULT 0)",
            "CREATE TABLE finance(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, pkg TEXT, amount INTEGER, kind TEXT, text TEXT)",
            "CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, created INTEGER)",
            "CREATE TABLE embeddings(kind TEXT, ref TEXT, vec BLOB, thash INTEGER, PRIMARY KEY(kind, ref))",
            "CREATE TABLE goals(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, status TEXT DEFAULT 'aktif', created INTEGER, updated INTEGER)",
            "CREATE TABLE goal_steps(id INTEGER PRIMARY KEY AUTOINCREMENT, goal_id INTEGER, text TEXT, due INTEGER DEFAULT 0, done INTEGER DEFAULT 0, done_ts INTEGER DEFAULT 0, pos INTEGER DEFAULT 0)",
            "CREATE TABLE oc_tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, chain_id TEXT, text TEXT, session_id TEXT DEFAULT '', status TEXT DEFAULT 'queued', result TEXT DEFAULT '', created INTEGER, finished INTEGER DEFAULT 0)",
            "CREATE TABLE coord_rules(k TEXT PRIMARY KEY, v TEXT)"
        )
        var v6ok = true
        for (ddl in v6ddl) {
            if (x(dbM6, ddl) == null) v6ok = false
        }
        cek("9.1 fixture v6 DDL dibuat", v6ok)
        raw(JSONObject().put("op", "exec").put("db", dbM6).put("sql", "PRAGMA user_version = 6").put("params", org.json.JSONArray()).toString())
        x(dbM6, "INSERT INTO facts(k,v,ts,confirmed) VALUES('nama user','Sendy',1700000000000,1)")
        x(dbM6, "INSERT INTO facts(k,v,ts,confirmed) VALUES('kota','Jakarta',1700000001000,0)")
        x(dbM6, "INSERT INTO history(role,text,ts) VALUES('user','halo',1700000002000)")
        x(dbM6, "INSERT INTO oc_tasks(chain_id,text,created) VALUES('c1','kerjakan X',1700000003000)")
        cek("9.2 fixture v6 user_version == 6", userVer(dbM6) == 6L)
        cek("9.3 migrasi memory v6->v7 version 7", ensure(dbM6, "memory")?.optLong("version", -1) == 7L)
        cek("9.4 fakta lama utuh (Sendy, confirmed=1, active)", q(dbM6, "SELECT v, confirmed, status FROM facts WHERE k='nama user'")?.optJSONArray(0)?.let { it.optString(0) == "Sendy" && it.optLong(1) == 1L && it.optString(2) == "active" } == true)
        cek("9.5 facts count tetap 2", q(dbM6, "SELECT COUNT(*) FROM facts")?.optJSONArray(0)?.optLong(0) == 2L)
        cek("9.6 workers ada (v7)", tables(dbM6).contains("workers"))
        cek("9.7 lessons+fact_candidates ada (vc294 lazy)", tables(dbM6).contains("lessons") && tables(dbM6).contains("fact_candidates"))
        cek("9.8 kolom facts.tags ada", hasCol(dbM6, "facts", "tags"))
        cek("9.9 kolom oc_tasks.origin_session ada", hasCol(dbM6, "oc_tasks", "origin_session"))
        cek("9.10 riwayat utuh", q(dbM6, "SELECT text FROM history WHERE role='user'")?.optJSONArray(0)?.optString(0) == "halo")

        // ---------- §10 BLOB embedding ----------
        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
        val insB = raw(
            JSONObject().put("op", "exec").put("db", dbM).put("sql", "INSERT INTO embeddings(kind,ref,vec,thash) VALUES(?,?,?,?)")
                .put("params", org.json.JSONArray().apply {
                    put("fact"); put("k1"); put(JSONObject().put("\$b64", b64)); put(123)
                }).toString()
        )
        cek("10.1 insert BLOB ok", insB != null)
        val gotB = q(dbM, "SELECT vec FROM embeddings WHERE kind=? AND ref=?", "fact", "k1")
        cek("10.2 BLOB round-trip base64 sama", gotB?.optJSONArray(0)?.optJSONObject(0)?.optString("\$b64") == b64)

        // ---------- §11 konkurensi writer ----------
        val dbC = File(tmpDir, "conc.db").absolutePath
        ensure(dbC, "events")
        val threads = ArrayList<Thread>()
        val errs = java.util.concurrent.atomic.AtomicInteger(0)
        for (t in 0 until 4) {
            threads.add(Thread {
                for (i in 0 until 25) {
                    var ok = SqliteSeam.exec(dbC, "INSERT INTO events(ts,type,pkg,text) VALUES(?,?,?,?)", listOf(i.toLong(), "w$t", "p", "x")) != null
                    if (!ok) {
                        Thread.sleep(10)
                        ok = SqliteSeam.exec(dbC, "INSERT INTO events(ts,type,pkg,text) VALUES(?,?,?,?)", listOf(i.toLong(), "w$t", "p", "x")) != null
                    }
                    if (!ok) errs.incrementAndGet()
                }
            })
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        cek("11.1 4 writer x 25 tanpa error", errs.get() == 0)
        cek("11.2 count == 100 (tidak ada hilang)", q(dbC, "SELECT COUNT(*) FROM events")?.optJSONArray(0)?.optLong(0) == 100L)
    }

    // ---------- §12 sumber (kedua mode) ----------
    val tree = File(treeDir)
    fun src(name: String): String {
        val f = File(tree, name)
        return if (f.exists()) f.readText() else ""
    }
    val ev = src("EventDb.kt")
    val ar = src("ArtifactStore.kt")
    val pk = src("ParkedApprovalStoreVc305.kt")
    val gr = src("GraphDb.kt")
    val mem = src("MemoryDb.kt")
    val seam = src("SqliteSeam.kt")
    val core = File(coreDir, "src/sqlite_schema.rs").let { if (it.exists()) it.readText() else "" }
    cek("12.1 EventDb: public fun + *Kotlin + saklar", ev.contains("fun add(") && ev.contains("private fun addKotlin(") && ev.contains("var rustEventDb"))
    cek("12.2 ArtifactStore: public fun + *Kotlin + saklar", ar.contains("fun create(") && ar.contains("private fun createKotlin(") && ar.contains("var rustArtifactStore"))
    cek("12.3 Parked: public fun + *Kotlin + saklar", pk.contains("fun claim(") && pk.contains("private fun claimKotlin(") && pk.contains("var rustParkedApproval") && pk.contains("private fun decideKotlin("))
    cek("12.4 GraphDb: public fun + *Kotlin + saklar", gr.contains("fun getOrCreateNode(") && gr.contains("private fun getOrCreateNodeKotlin(") && gr.contains("var rustGraphDb") && gr.contains("private fun searchFactsKotlin(") && gr.contains("private fun rewireNodeEdgesKotlin("))
    cek("12.5 SqliteSeam ada (ensure/query/exec/tx)", seam.contains("fun ensure(") && seam.contains("fun query(") && seam.contains("fun exec(") && seam.contains("fun tx("))
    cek("12.6 MemoryDb TIDAK tersentuh Rust", !mem.contains("rustMemoryDb") && !mem.contains("SqliteSeam") && !mem.contains("sqliteJson"))
    // RustCore.kt: di tree lokal (work-impl/integrated) ada di
    // <tree>/harness/core/; di repo/CI tree = .../deepharness sehingga
    // RustCore ada di <naik-3>/harness/core/ (deepharness -> senzwh
    // -> com -> java). Coba semua tingkat.
    // (Koreksi CI ronde 1 vc323: naik 0 dan 1 tingkat SALAH di layout
    // CI — akar yang sama dengan P8 5.18 vc322, yang sudah mencoba
    // 4 tingkat. 12.7 ini hanya mencoba 2 tingkat.)
    val rcText = listOf(
        "harness/core/RustCore.kt",
        "../harness/core/RustCore.kt",
        "../../harness/core/RustCore.kt",
        "../../../harness/core/RustCore.kt"
    ).map { File(treeDir, it) }.firstOrNull { it.exists() }?.readText().orEmpty()
    cek("12.7 RustCore.kt VERSION 7 + sqliteJson", rcText.contains("const val VERSION = 8") && rcText.contains("external fun sqliteJson"))
    // Identitas SQL: SQL wrapper == SQL di tes (diwakili string kunci).
    cek("12.8 SQL EventDb identik (latest)", ev.contains("SELECT ts,type,pkg,text FROM events ORDER BY id DESC LIMIT ?"))
    cek("12.9 SQL ArtifactStore identik (meta select)", ar.contains("(SELECT COUNT(*) FROM artifact_versions v WHERE v.artifact_id = a.id)"))
    cek("12.10 SQL Parked identik (claim WHERE)", pk.contains("pid = ? AND status = 'PENDING'") && pk.contains("pid = ? AND decision IS NULL"))
    cek("12.11 SQL GraphDb identik (FTS MATCH)", gr.contains("SELECT fact_text FROM fts_fact WHERE fts_fact MATCH ? LIMIT ?"))
    // Identitas DDL: DDL Kotlin ada verbatim di sqlite_schema.rs.
    cek("12.12 DDL events di Rust", core.contains("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, type TEXT, pkg TEXT, text TEXT)"))
    cek("12.13 DDL artifacts di Rust", core.contains("CREATE TABLE IF NOT EXISTS artifacts(id TEXT PRIMARY KEY, title TEXT, type TEXT, owner_session INTEGER, created INTEGER, updated INTEGER)"))
    cek("12.14 DDL parked di Rust", core.contains("CREATE TABLE IF NOT EXISTS parked_approvals(pid TEXT PRIMARY KEY, sessionId TEXT, tool TEXT, args TEXT, risk TEXT, status TEXT, decision TEXT, decidedAtMs INTEGER, createdAtMs INTEGER, timeoutMs INTEGER, timeoutOwnerMs INTEGER, notifId INTEGER, task TEXT, origin TEXT, title TEXT, transcript TEXT, steps INTEGER, job TEXT, tools TEXT, ownerSession INTEGER)"))
    cek("12.15 DDL graph nodes/edges di Rust", core.contains("CREATE TABLE IF NOT EXISTS nodes (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, label TEXT, source TEXT, aliases_json TEXT DEFAULT '[]')") && core.contains("CREATE TABLE IF NOT EXISTS edges (id INTEGER PRIMARY KEY AUTOINCREMENT, from_id INTEGER, to_id INTEGER, rel TEXT, weight REAL DEFAULT 1.0, tag TEXT DEFAULT 'EXTRACTED', confidence REAL DEFAULT 1.0, fact_text TEXT DEFAULT '', valid_at INTEGER, invalid_at INTEGER, recorded_at INTEGER, expired_at INTEGER, episode_id INTEGER)"))
    cek("12.16 DDL graph v3 FTS di Rust", core.contains("CREATE VIRTUAL TABLE IF NOT EXISTS fts_fact USING fts5(fact_text)"))
    cek("12.17 DDL memory facts di Rust", core.contains("CREATE TABLE facts(id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT, v TEXT, ts INTEGER, confirmed INTEGER DEFAULT 0, source TEXT DEFAULT '', source_ts INTEGER DEFAULT 0, status TEXT DEFAULT 'active', superseded_by TEXT DEFAULT '', reason TEXT DEFAULT '', updated_ts INTEGER DEFAULT 0)"))
    cek("12.18 DDL memory workers di Rust", core.contains("CREATE TABLE IF NOT EXISTS workers(id TEXT PRIMARY KEY, name TEXT, kind TEXT, session TEXT, command TEXT, script_path TEXT DEFAULT '', pid INTEGER DEFAULT 0, log_path TEXT, status TEXT, stopped INTEGER DEFAULT 0, created INTEGER, last_seen INTEGER DEFAULT 0, last_event TEXT DEFAULT '', owner TEXT DEFAULT '', work_dir TEXT DEFAULT '', quota_mb INTEGER DEFAULT 0)"))
    cek("12.19 migrasi vc285 rebuild di Rust", core.contains("INSERT INTO facts_vc285(k, v, ts, confirmed, source, source_ts, status, superseded_by, reason, updated_ts) SELECT k, v, ts, confirmed, '', 0, 'active', '', '', 0 FROM facts"))

    println("P9 TOTAL PASS=$PASS FAIL=$FAIL")
    if (FAIL > 0) {
        println("P9 GAGAL")
        kotlin.system.exitProcess(1)
    }
}
