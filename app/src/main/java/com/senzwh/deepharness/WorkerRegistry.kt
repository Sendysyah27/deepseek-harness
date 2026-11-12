package com.senzwh.deepharness

import android.content.ContentValues
import android.content.Context
import android.util.Base64
import java.io.File

/** vc256 Fase 1 Device-as-Server / Agent-as-Storage — WorkerRegistry.
 *  Daftar worker persisten (SQLite memory.db, tabel `workers`) + lifecycle
 *  spawn/stop/restart/health/log + folder shared + antrean generik.
 *  Kontrak: BRIEF-DEVICE-SERVER.md §4 Fase 1. Keputusan terkunci:
 *  K1 W1+W4 (proot + script Python); K2 opsi (b) filesDir/workers/<id>/
 *  + cermin /sdcard/Harness-Workers/<id>/ (kuota DICATAT di kolom quota_mb
 *  bila di-set via POST /workers/<id>/quota — default 0 = belum di-set;
 *  penegakan + hapus otomatis = Fase 3 (vc281: default 1024 MB bila 0,
 *  janitor hapus hasil tertua + beri tahu lewat notifikasi); K3 localhost-only
 *  (tailnet = Fase 4); K4 TANPA batasan baterai untuk worker, maks 5 worker
 *  JALAN bersamaan; K5 nama UI "Worker"; Q2/Q3 lihat bawah.
 *  K6 (endpoint :4200 + token) = Fase 2 (vc258) — DIBANGUN di
 *  WorkerEndpoint.kt; registry ini hanya dipakai endpoint (list/spawn/
 *  stop/log/antrean) + tool preview.
 *  Batasan: TIDAK ada auto-restart (kebijakan MANUAL saja di Fase 1 —
 *  restart hanya manual dari panel); TIDAK ada perubahan perilaku agen,
 *  prompt, parser, ToolGate, Governor, claim-check, kill switch, kamera,
 *  bank/e-wallet, password; TIDAK menyentuh serve Termux :4096/:4097 dan
 *  crontab; bind /sdcard penuh DICABUT dari sesi worker di vc281
 *  (Fase 3 — isolasi, allowlist cermin; serve tetap /sdcard penuh);
 *  TIDAK membuka port (semua permukaan baru localhost-only — malah tidak
 *  ada permukaan jaringan baru sama sekali di Fase 1).
 *  Semua fungsi yang memanggil execSession bersifat BLOKIR — panggil dari
 *  Thread, jangan pernah dari main thread (pelajaran ANR vc193).
 *  Pola peluncuran mengikuti ProotJobs (base64 script + setsid nohup +
 *  echo $! + kill -0) — pola yang terbukti di lingkungan ini. */
object WorkerRegistry {
    const val TABLE = "workers"
    const val MAX_RUNNING = WorkerLogic.MAX_RUNNING

    // vc261 N-M3: hasil operasi terstruktur — endpoint menjawab ok:false +
    // kode HTTP yang benar dari sini, bukan dari parsing teks Indonesia.
    // httpCode: 200 ok; 400 validasi; 404 tidak ditemukan; 409 bentrok
    // (maks 5 / proses menolak kill); 500 gagal luncur/eksekusi.
    data class OpResult(val ok: Boolean, val message: String, val httpCode: Int, val workerId: String = "")

    val CREATE_SQL = "CREATE TABLE IF NOT EXISTS workers(id TEXT PRIMARY KEY, name TEXT, kind TEXT, session TEXT, command TEXT, script_path TEXT DEFAULT '', pid INTEGER DEFAULT 0, log_path TEXT, status TEXT, stopped INTEGER DEFAULT 0, created INTEGER, last_seen INTEGER DEFAULT 0, last_event TEXT DEFAULT '', owner TEXT DEFAULT '', work_dir TEXT DEFAULT '', quota_mb INTEGER DEFAULT 0)"

    data class Worker(
        val id: String,
        val name: String,
        val kind: String,
        val session: String,
        val command: String,
        val scriptPath: String,
        val pid: Long,
        val logPath: String,
        val status: String,
        val stoppedExplicit: Boolean,
        val createdMs: Long,
        val lastSeenMs: Long,
        val lastEvent: String,
        val owner: String,
        val workDir: String,
        val quotaMb: Int
    )

    fun statusLabel(status: String): String = WorkerLogic.statusLabel(status)

    fun displayStatus(ctx: Context, w: Worker): String =
        WorkerLogic.displayStatus(w.status, ProotSession.isSessionAlive(w.session))

    /** Folder kanonis agen (app-private) + cermin (terlihat user + terlihat
     *  proot lewat bind /sdcard yang sudah ada). work_dir worker = cermin. */
    fun canonicalDir(ctx: Context, id: String): File = File(File(ctx.filesDir, "workers"), id)
    fun mirrorDir(id: String): File = File("/sdcard/Harness-Workers", id)
    fun mirrorProotPath(id: String): String = "/sdcard/Harness-Workers/$id"

    fun ensureDirs(ctx: Context, id: String) {
        try { WorkerQueue.ensureDirs(canonicalDir(ctx, id), mirrorDir(id)) } catch (e: Exception) {}
    }

    // vc260 N-M1(f): CREATE_SQL dulu jalan di SETIAP panggilan list()/get()/upsert() — boros + memperbesar jendela lock DB. Sekali per proses cukup (CREATE TABLE IF NOT EXISTS idempotent; flag hanya diset bila execSQL sukses, jadi gagal transient dicoba lagi di panggilan berikut).
    @Volatile private var tableReady = false

    private fun ensureTable(ctx: Context) {
        if (tableReady) return
        synchronized(this) {
            if (tableReady) return
            try { MemoryDb.get(ctx).writableDatabase.execSQL(CREATE_SQL); tableReady = true } catch (e: Exception) {}
        }
    }

    private fun workerFrom(c: android.database.Cursor): Worker? = try {
        Worker(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            name = c.getString(c.getColumnIndexOrThrow("name")) ?: "",
            kind = c.getString(c.getColumnIndexOrThrow("kind")) ?: "proot",
            session = c.getString(c.getColumnIndexOrThrow("session")) ?: "worker1",
            command = c.getString(c.getColumnIndexOrThrow("command")) ?: "",
            scriptPath = c.getString(c.getColumnIndexOrThrow("script_path")) ?: "",
            pid = c.getLong(c.getColumnIndexOrThrow("pid")),
            logPath = c.getString(c.getColumnIndexOrThrow("log_path")) ?: "",
            status = c.getString(c.getColumnIndexOrThrow("status")) ?: "FAILED",
            stoppedExplicit = c.getInt(c.getColumnIndexOrThrow("stopped")) != 0,
            createdMs = c.getLong(c.getColumnIndexOrThrow("created")),
            lastSeenMs = c.getLong(c.getColumnIndexOrThrow("last_seen")),
            lastEvent = c.getString(c.getColumnIndexOrThrow("last_event")) ?: "",
            owner = c.getString(c.getColumnIndexOrThrow("owner")) ?: "",
            workDir = c.getString(c.getColumnIndexOrThrow("work_dir")) ?: "",
            quotaMb = c.getInt(c.getColumnIndexOrThrow("quota_mb"))
        ).takeIf { it.id.isNotBlank() && it.session in WorkerLogic.WORKER_SESSIONS }
    } catch (e: Exception) {
        null
    }

    fun list(ctx: Context): List<Worker> {
        ensureTable(ctx)
        val out = mutableListOf<Worker>()
        try {
            val c = MemoryDb.get(ctx).readableDatabase.rawQuery("SELECT * FROM workers ORDER BY created DESC", null)
            while (c.moveToNext()) { workerFrom(c)?.let { out.add(it) } }
            c.close()
        } catch (e: Exception) {}
        return out
    }

    fun get(ctx: Context, id: String): Worker? {
        ensureTable(ctx)
        return try {
            val c = MemoryDb.get(ctx).readableDatabase.rawQuery("SELECT * FROM workers WHERE id=?", arrayOf(id))
            val w = if (c.moveToFirst()) workerFrom(c) else null
            c.close()
            w
        } catch (e: Exception) { null }
    }

    // vc261 N-M8: hanya hitung RUNNING yang sesinya masih hidup —
    // baris RUNNING basi (sesi mati pasca-crash) tidak lagi memblokir
    // spawn baru sampai panel dibuka. Cek sesi murah (state, tanpa exec).
    fun runningCount(ctx: Context): Int =
        try { list(ctx).count { it.status == "RUNNING" && ProotSession.isSessionAlive(it.session) } } catch (e: Exception) { 0 }

    // vc265 N2-M3: nama worker yang sedang JALAN — dipakai cek duplikat
    // di WorkerLogic.validateSpawn (hanya RUNNING; nama STOPPED/FAILED
    // boleh dipakai lagi).
    private fun runningNames(ctx: Context): List<String> =
        try { list(ctx).filter { it.status == "RUNNING" }.map { it.name } } catch (e: Exception) { emptyList() }

    // vc261 N-M8: refresh status murah + throttled (dipakai endpoint
    // listWorkers/spawn). Sesi mati -> SESI_MATI langsung; PID hanya
    // diperiksa bila lastSeen lebih tua dari 30 detik (hemat exec proot).
    fun refreshRunningStatus(ctx: Context) {
        val now = System.currentTimeMillis()
        for (w in list(ctx)) {
            if (w.status != "RUNNING") continue
            if (!ProotSession.isSessionAlive(w.session)) {
                upsert(ctx, w.copy(status = "SESI_MATI", lastEvent = "sesi proot berakhir — tidak di-restart otomatis"))
                continue
            }
            if (now - w.lastSeenMs > 30_000L) {
                if (pidAlive(ctx, w.session, w.pid)) upsert(ctx, w.copy(lastSeenMs = now))
                else upsert(ctx, w.copy(status = "FAILED", lastEvent = "mati — perlu restart manual dari panel"))
            }
        }
    }

    /** Dipakai guard idle-sleep ProotSession: sesi yang memegang worker
     *  RUNNING tidak boleh tidur-idle (worker-nya ikut mati). */
    fun hasRunningWorker(ctx: Context, session: String): Boolean =
        try { list(ctx).any { it.session == session && it.status == "RUNNING" } } catch (e: Exception) { false }

    private fun upsert(ctx: Context, w: Worker) {
        ensureTable(ctx)
        try {
            val cv = ContentValues()
            cv.put("id", w.id)
            cv.put("name", w.name)
            cv.put("kind", w.kind)
            cv.put("session", w.session)
            cv.put("command", w.command)
            cv.put("script_path", w.scriptPath)
            cv.put("pid", w.pid)
            cv.put("log_path", w.logPath)
            cv.put("status", w.status)
            cv.put("stopped", if (w.stoppedExplicit) 1 else 0)
            cv.put("created", w.createdMs)
            cv.put("last_seen", w.lastSeenMs)
            cv.put("last_event", w.lastEvent)
            cv.put("owner", w.owner)
            cv.put("work_dir", w.workDir)
            cv.put("quota_mb", w.quotaMb)
            MemoryDb.get(ctx).writableDatabase.insertWithOnConflict(TABLE, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {}
    }

    fun remove(ctx: Context, id: String): String {
        ensureTable(ctx)
        return try {
            MemoryDb.get(ctx).writableDatabase.delete(TABLE, "id=?", arrayOf(id))
            "Catatan worker dihapus (proses TIDAK dibunuh, folder TIDAK dihapus — pakai Stop dulu bila prosesnya harus mati)."
        } catch (e: Exception) { "Gagal menghapus catatan worker: ${e.message}" }
    }

    private fun runWorker(ctx: Context, session: String, cmd: String, timeoutSec: Int): Pair<String, String> {
        return try { ProotSession.execFullSession(ctx, session, cmd, timeoutSec) } catch (e: Exception) { Pair("error: ${e.message}", "") }
    }

    private fun pidAlive(ctx: Context, session: String, pid: Long): Boolean {
        if (pid <= 0L) return false
        val (_, full) = runWorker(ctx, session, "kill -0 $pid 2>/dev/null && echo SUP_ALIVE || echo SUP_DEAD", 30)
        return full.contains("SUP_ALIVE")
    }

    private fun exitOf(display: String): Int? {
        val m = Regex("exit=(-?\\d+)").find(display)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun writeScript(ctx: Context, session: String, w: Worker): Boolean {
        val content = WorkerLogic.launcherScript(w.workDir, w.command)
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = "mkdir -p ${WorkerLogic.SCRIPT_DIR} && printf '%s' '$b64' | base64 -d > ${WorkerLogic.SCRIPT_DIR}/${w.id}.sh && chmod +x ${WorkerLogic.SCRIPT_DIR}/${w.id}.sh && echo SUP_SCRIPT_OK"
        val (display, full) = runWorker(ctx, session, cmd, 60)
        return full.contains("SUP_SCRIPT_OK") && exitOf(display) == 0
    }

    private fun launchScript(ctx: Context, session: String, w: Worker): Long? {
        val cmd = "setsid nohup ${WorkerLogic.SCRIPT_DIR}/${w.id}.sh > '${w.logPath}' 2>&1 & echo SUP_PID_\$!"
        val (_, full) = runWorker(ctx, session, cmd, 60)
        val m = Regex("SUP_PID_(\\d+)").find(full)
        return m?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun newId(): String = "w_" + System.currentTimeMillis() + "_" + (System.nanoTime() % 100000)

    /** Spawn worker proot (W1): perintah shell penuh di dalam proot. */
    fun spawnProot(ctx: Context, name: String, session: String, command: String, owner: String = "panel"): String =
        spawnProotOp(ctx, name, session, command, owner).message

    // vc261 N-M3/N-M7: versi terstruktur (@Synchronized — cek runningCount
    // + luncur atomik terhadap spawn/restart lain di objek ini).
    // vc265 m11 (verifikasi): cek maks-5 + upsert RUNNING ATOMIK —
    // seluruh method ini jalan di bawah monitor registry (@Synchronized
    // pada objek ini), jadi dua spawn bersamaan tidak bisa sama-sama
    // lolos cek lalu sama-sama insert (cek+insert satu blok synchronized
    // yang sama; spawnPythonOp + restartWorkerOp memakai monitor yang
    // sama, jadi restart pun tidak bisa menyelinap di tengah cek+insert
    // spawn). Referensi baris audit (192-216) sudah kedaluwarsa — itu
    // sebelum vc261 menambahkan @Synchronized; tidak ada perubahan
    // perilaku di sini, hanya penegasan invarian supaya tidak ada yang
    // melepas @Synchronized di refactor berikut.
    @Synchronized
    fun spawnProotOp(ctx: Context, name: String, session: String, command: String, owner: String = "panel"): OpResult {
        val err = WorkerLogic.validateSpawn(name, "proot", session, command, runningCount(ctx), runningNames(ctx))
        if (err != null) return OpResult(false, err, if (err.contains("maks K4") || err.contains("sudah JALAN")) 409 else 400)
        val now = System.currentTimeMillis()
        val id = newId()
        ensureDirs(ctx, id)
        // vc281 Fase 3 (D1): pastikan bind sesi mencakup folder cermin
        // worker baru ini SEBELUM skrip peluncur ditulis (cd ke folder
        // cermin butuh bind itu).
        ProotSession.ensureSessionBinds(ctx, session, id)
        var w = Worker(id, name.trim(), "proot", session, command, "", 0L, "${WorkerLogic.SCRIPT_DIR}/$id.log", "FAILED", false, now, 0L, "dibuat", owner, mirrorProotPath(id), WorkerLogic.QUOTA_MB_DEFAULT)
        if (!writeScript(ctx, session, w)) {
            upsert(ctx, w.copy(lastEvent = "gagal menulis skrip peluncur"))
            return OpResult(false, "Gagal menulis skrip peluncur di $session — worker dicatat GAGAL, cek sesi proot.", 500, id)
        }
        val pid = launchScript(ctx, session, w)
        if (pid == null || pid <= 0L) {
            upsert(ctx, w.copy(lastEvent = "gagal meluncurkan — PID tidak didapat"))
            return OpResult(false, "Gagal meluncurkan worker — PID tidak didapat. Worker dicatat GAGAL.", 500, id)
        }
        val alive = pidAlive(ctx, session, pid)
        w = if (alive) w.copy(pid = pid, status = "RUNNING", lastSeenMs = now, lastEvent = "jalan (PID $pid)")
        else w.copy(pid = pid, status = "FAILED", lastEvent = "mati seketika setelah luncur — cek ekor log")
        upsert(ctx, w)
        return if (alive) OpResult(true, "Worker '${w.name}' JALAN di $session (PID $pid, log ${w.logPath}, folder ${w.workDir}).", 200, id)
        else OpResult(false, "Worker '${w.name}' MATI seketika setelah luncur (PID $pid) — dicatat GAGAL, cek ekor log.", 500, id)
    }

    /** Spawn script Python (W4): kode ditulis ke script.py (mirror + kanonis),
     *  lalu dijalankan python3 -u dari dalam proot. */
    fun spawnPython(ctx: Context, name: String, session: String, code: String, owner: String = "panel"): String =
        spawnPythonOp(ctx, name, session, code, owner).message

    // vc261 N-m3/N-M3/N-M7: validasi DULU (nama/sesi/maks-5) baru tulis
    // script.py + folder — validasi gagal tidak lagi meninggalkan folder
    // + script.py yatim. Terstruktur + @Synchronized seperti proot.
    @Synchronized
    fun spawnPythonOp(ctx: Context, name: String, session: String, code: String, owner: String = "panel"): OpResult {
        if (code.isBlank()) return OpResult(false, "Kode Python tidak boleh kosong.", 400)
        val now = System.currentTimeMillis()
        val id = newId()
        val command = WorkerLogic.pythonCommand(mirrorProotPath(id))
        val err = WorkerLogic.validateSpawn(name, "python", session, command, runningCount(ctx), runningNames(ctx))
        if (err != null) return OpResult(false, err, if (err.contains("maks K4") || err.contains("sudah JALAN")) 409 else 400)
        ensureDirs(ctx, id)
        // vc281 Fase 3 (D1): pastikan bind sesi mencakup folder cermin
        // worker baru ini (lihat spawnProotOp).
        ProotSession.ensureSessionBinds(ctx, session, id)
        try { File(mirrorDir(id), "script.py").writeText(code, Charsets.UTF_8) } catch (e: Exception) {
            return OpResult(false, "Gagal menulis script.py ke folder cermin ${mirrorDir(id)}: ${e.message}", 500, id)
        }
        try { File(canonicalDir(ctx, id), "script.py").writeText(code, Charsets.UTF_8) } catch (e: Exception) {}
        var w = Worker(id, name.trim(), "python", session, command, mirrorProotPath(id) + "/script.py", 0L, "${WorkerLogic.SCRIPT_DIR}/$id.log", "FAILED", false, now, 0L, "dibuat", owner, mirrorProotPath(id), WorkerLogic.QUOTA_MB_DEFAULT)
        if (!writeScript(ctx, session, w)) {
            upsert(ctx, w.copy(lastEvent = "gagal menulis skrip peluncur"))
            return OpResult(false, "Gagal menulis skrip peluncur di $session — worker dicatat GAGAL, cek sesi proot.", 500, id)
        }
        val pid = launchScript(ctx, session, w)
        if (pid == null || pid <= 0L) {
            upsert(ctx, w.copy(lastEvent = "gagal meluncurkan — PID tidak didapat"))
            return OpResult(false, "Gagal meluncurkan worker — PID tidak didapat. Worker dicatat GAGAL.", 500, id)
        }
        val alive = pidAlive(ctx, session, pid)
        w = if (alive) w.copy(pid = pid, status = "RUNNING", lastSeenMs = now, lastEvent = "jalan (PID $pid)")
        else w.copy(pid = pid, status = "FAILED", lastEvent = "mati seketika setelah luncur — cek ekor log")
        upsert(ctx, w)
        return if (alive) OpResult(true, "Worker '${w.name}' (Python) JALAN di $session (PID $pid, log ${w.logPath}, folder ${w.workDir}).", 200, id)
        else OpResult(false, "Worker '${w.name}' (Python) MATI seketika setelah luncur (PID $pid) — dicatat GAGAL, cek ekor log.", 500, id)
    }

    fun stopWorker(ctx: Context, id: String): String =
        stopWorkerOp(ctx, id).message

    // vc261 N-m9/N-M3: bila kill gagal (proses masih terlihat hidup),
    // status TETAP RUNNING + ok:false 409 — slot maks-5 tidak dibebaskan
    // untuk proses yang masih jalan. Terstruktur + @Synchronized.
    @Synchronized
    fun stopWorkerOp(ctx: Context, id: String): OpResult {
        val cur = get(ctx, id) ?: return OpResult(false, "Worker tidak ditemukan.", 404)
        if (cur.pid > 0L && ProotSession.isSessionAlive(cur.session)) {
            val (_, full) = runWorker(ctx, cur.session, "kill ${cur.pid} 2>/dev/null; sleep 1; kill -0 ${cur.pid} 2>/dev/null && echo SUP_STILL || echo SUP_GONE", 30)
            if (full.contains("SUP_STILL")) {
                upsert(ctx, cur.copy(lastEvent = "stop gagal — proses masih terlihat hidup (PID ${cur.pid} menolak kill)"))
                return OpResult(false, "Worker '${cur.name}' MASIH JALAN — proses (PID ${cur.pid}) menolak kill, status tetap RUNNING.", 409, cur.id)
            }
            upsert(ctx, cur.copy(status = "STOPPED", stoppedExplicit = true, lastEvent = "di-stop (proses mati)"))
            return OpResult(true, "Worker '${cur.name}' STOP — tidak akan di-restart otomatis (Fase 1: restart hanya manual).", 200, cur.id)
        }
        val note = if (cur.pid > 0L) "di-stop (sesi sudah mati)" else "di-stop"
        upsert(ctx, cur.copy(status = "STOPPED", stoppedExplicit = true, lastEvent = note))
        return OpResult(true, "Worker '${cur.name}' STOP — tidak akan di-restart otomatis (Fase 1: restart hanya manual).", 200, cur.id)
    }

    fun restartWorker(ctx: Context, id: String): String =
        restartWorkerOp(ctx, id).message

    @Synchronized
    fun restartWorkerOp(ctx: Context, id: String): OpResult {
        val cur = get(ctx, id) ?: return OpResult(false, "Worker tidak ditemukan.", 404)
        if (cur.session !in WorkerLogic.WORKER_SESSIONS) return OpResult(false, "Sesi worker bukan worker.", 400)
        // vc261 N-M7: restart FAILED ke-6 saat 5 sudah JALAN = 409.
        // (Restart worker yang SUDAH RUNNING tidak menambah hitungan.)
        if (cur.status != "RUNNING" && runningCount(ctx) >= MAX_RUNNING) return OpResult(false, "Sudah ada $MAX_RUNNING worker JALAN (maks K4) — stop satu dulu sebelum restart.", 409)
        // vc281 Fase 3 (D3): restart worker yang foldernya over-kuota —
        // janitor bersihkan dulu (hasil tertua + notifikasi), restart tetap jalan.
        try { WorkerJanitor.sweepWorker(ctx, cur) } catch (e: Exception) {}
        // vc281 Fase 3 (D1): pastikan bind sesi mencakup folder cermin worker ini.
        ProotSession.ensureSessionBinds(ctx, cur.session, cur.id)
        if (cur.pid > 0L && ProotSession.isSessionAlive(cur.session) && pidAlive(ctx, cur.session, cur.pid)) {
            runWorker(ctx, cur.session, "kill ${cur.pid} 2>/dev/null; sleep 1", 30)
        }
        var w = cur.copy(stoppedExplicit = false, status = "FAILED", lastEvent = "restart manual dimulai")
        if (w.command.isBlank()) {
            upsert(ctx, w.copy(lastEvent = "tidak bisa restart — perintah tidak tercatat"))
            return OpResult(false, "Worker '${w.name}' tidak punya perintah tercatat — restart tidak bisa.", 400, cur.id)
        }
        if (!writeScript(ctx, w.session, w)) {
            upsert(ctx, w.copy(lastEvent = "restart gagal — skrip tidak bisa ditulis"))
            return OpResult(false, "Restart gagal — skrip peluncur tidak bisa ditulis.", 500, cur.id)
        }
        val pid = launchScript(ctx, w.session, w)
        val now = System.currentTimeMillis()
        w = if (pid != null && pid > 0L && pidAlive(ctx, w.session, pid))
            w.copy(pid = pid, status = "RUNNING", lastSeenMs = now, lastEvent = "restart manual (PID $pid)")
        else
            w.copy(pid = pid ?: 0L, status = "FAILED", lastEvent = "restart gagal — PID tidak didapat / mati seketika")
        upsert(ctx, w)
        return if (w.status == "RUNNING") OpResult(true, "Worker '${w.name}' JALAN lagi di ${w.session} (PID ${w.pid}).", 200, cur.id)
        else OpResult(false, "Restart worker '${w.name}' GAGAL — cek ekor log.", 500, cur.id)
    }

    // vc281 Fase 3 (D6): reset per worker — stop (kill PID; bila sudah
    // mati tetap lanjut), hapus folder kanonis + cermin + file log, hapus
    // token per-worker, catat lastEvent, beri tahu user via notifikasi.
    // Baris registry TETAP ADA (status STOP) supaya lastEvent terbaca;
    // hapus catatan sepenuhnya tetap lewat tombol Hapus di panel.
    // Rute endpoint POST /workers/<id>/reset (token MASTER saja).
    // vc284 N4-M5: kill DIVERIFIKASI seperti stopWorkerOp (SUP_GONE),
    // lalu coba kill grup proses (setsid -> PGID == PID) bila proses
    // menolak mati. Bila MASIH hidup: folder TIDAK dihapus, status
    // tetap RUNNING, jawab 409 + pesan jujur (jangan klaim sukses).
    // Sukses: hapus juga skrip peluncur (SCRIPT_DIR/<id>.sh) + log.
    // Entri ResultStore worker TIDAK dihapus: ResultStore tidak punya
    // API hapus per-tool (hanya wipe total) dan entri berkunci R-id
    // dengan TTL 48 jam + cap 10 MB — tidak ada referensi hidup ke
    // worker yang direset.
    @Synchronized
    fun resetWorkerOp(ctx: Context, id: String): OpResult {
        val cur = get(ctx, id) ?: return OpResult(false, "Worker tidak ditemukan.", 404)
        if (cur.pid > 0L && ProotSession.isSessionAlive(cur.session)) {
            // Kill biasa dulu, verifikasi SUP_GONE (pola stopWorkerOp).
            val (_, full) = runWorker(ctx, cur.session, "kill ${cur.pid} 2>/dev/null; sleep 1; kill -0 ${cur.pid} 2>/dev/null && echo SUP_STILL || echo SUP_GONE", 30)
            var stillAlive = full.contains("SUP_STILL")
            if (stillAlive) {
                // launchScript memakai setsid nohup — PID tercatat adalah
                // session leader, jadi PGID-nya == PID; kill grup proses
                // menangkap anak-cucu detached dalam grup yang sama.
                // (Cucu yang memanggil setsid sendiri tetap lolos dari
                // grup ini — verifikasi di bawah hanya memeriksa PID
                // tercatat, bukan seluruh pohon proses.)
                val (_, full2) = runWorker(ctx, cur.session, "kill -TERM -${cur.pid} 2>/dev/null; sleep 1; kill -0 ${cur.pid} 2>/dev/null && echo SUP_STILL || echo SUP_GONE", 30)
                stillAlive = full2.contains("SUP_STILL")
            }
            if (stillAlive) {
                val msg = "Reset worker '${cur.name}' GAGAL — proses (PID ${cur.pid}) menolak kill (kill biasa + kill grup proses sudah dicoba), status tetap RUNNING dan folder TIDAK dihapus. Bunuh proses manual dari sesi ${cur.session} dulu, lalu reset lagi."
                upsert(ctx, cur.copy(lastEvent = "reset gagal — proses (PID ${cur.pid}) menolak kill, folder tidak dihapus"))
                try { WorkerJanitor.notifyWorker(ctx, id, cur.name, "Reset worker '${cur.name}' gagal", msg) } catch (e: Exception) {}
                try { EventDb.get(ctx).add("worker-reset", ctx.packageName, msg) } catch (e: Exception) {}
                return OpResult(false, msg, 409, cur.id)
            }
        }
        try { canonicalDir(ctx, id).deleteRecursively() } catch (e: Exception) {}
        try { mirrorDir(id).deleteRecursively() } catch (e: Exception) {}
        try {
            val log = WorkerJanitor.logFile(ctx, id)
            try { log.delete() } catch (e: Exception) {}
            try { File(log.parentFile, "$id.log.old").delete() } catch (e: Exception) {}
            // vc284 N4-M5: skrip peluncur worker ikut dihapus (dulu
            // tertinggal di SCRIPT_DIR dan bisa meluncurkan ulang
            // perintah lama bila dipanggil manual).
            try { File(log.parentFile, "$id.sh").delete() } catch (e: Exception) {}
        } catch (e: Exception) {}
        try { WorkerEndpoint.deleteWorkerToken(ctx, id) } catch (e: Exception) {}
        upsert(ctx, cur.copy(status = "STOPPED", stoppedExplicit = true, pid = 0L, lastEvent = "direset: proses di-stop (mati terverifikasi), folder kerja + folder cermin + log + skrip peluncur dihapus"))
        val text = "Worker '${cur.name}' direset: proses di-stop (mati terverifikasi), folder kerja (filesDir/workers/$id) + folder cermin /sdcard/Harness-Workers/$id + file log + skrip peluncur dihapus. Catatan worker tetap ada (status STOP) — spawn ulang bila perlu."
        try { WorkerJanitor.notifyWorker(ctx, id, cur.name, "Worker '${cur.name}' direset", text) } catch (e: Exception) {}
        try { EventDb.get(ctx).add("worker-reset", ctx.packageName, text) } catch (e: Exception) {}
        return OpResult(true, "Worker '${cur.name}' DIRESET — proses di-stop (mati terverifikasi), folder kerja + folder cermin /sdcard/Harness-Workers/$id + log + skrip peluncur dihapus. Catatan worker tetap ada (status STOP).", 200, cur.id)
    }

    // vc261 N-m19: jalur setter kuota — POST /workers/<id>/quota.
    // 0 = belum di-set (default lama); 1..102400 MB dicatat di kolom
    // quota_mb. PENEGAKAN kuota tetap Fase 3, bukan di sini.
    fun setQuotaMb(ctx: Context, id: String, mb: Int): String {
        val cur = get(ctx, id) ?: return "Worker tidak ditemukan."
        if (mb < 0 || mb > 102400) return "Kuota harus 0 (belum di-set) sampai 102400 MB."
        upsert(ctx, cur.copy(quotaMb = mb, lastEvent = "kuota dicatat ${mb} MB (ditegakkan janitor vc281)"))
        return "Kuota worker '${cur.name}' dicatat: ${mb} MB (0 = pakai default penegakan 1024 MB). Janitor menghapus hasil tertua bila melewati kuota + memberi tahu lewat notifikasi."
    }

    fun logTail(ctx: Context, id: String, lines: Int = 80): String {
        val w = get(ctx, id) ?: return "Worker tidak ditemukan."
        if (w.session !in WorkerLogic.WORKER_SESSIONS) return "Sesi bukan worker."
        if (!ProotSession.isSessionAlive(w.session)) return "Sesi ${w.session} mati — ekor log tidak bisa dibaca (log ada di dalam proot: ${w.logPath})."
        if (w.logPath.contains("'")) return "Path log tidak valid."
        return try { ProotJobs.logTail(ctx, w.session, w.logPath, lines) } catch (e: Exception) { "error: ${e.message}" }
    }

    /** Health check Fase 1: TANPA auto-restart (kebijakan MANUAL saja).
     *  RUNNING + sesi mati → SESI_MATI; RUNNING + PID mati → FAILED
     *  ("mati — perlu restart manual"); RUNNING + PID hidup → lastSeen
     *  diperbarui. Dipanggil saat panel Worker dibuka + on-demand. */
    fun healthCheck(ctx: Context) {
        val workers = list(ctx).toMutableList()
        // vc261 N-m8: hanya upsert baris yang berubah (dulu: semua baris
        // ditulis ulang tiap buka panel karena lastSeen != now selalu benar).
        val changedIdx = HashSet<Int>()
        val now = System.currentTimeMillis()
        for (idx in workers.indices) {
            val w = workers[idx]
            if (w.status != "RUNNING") continue
            if (!ProotSession.isSessionAlive(w.session)) {
                workers[idx] = w.copy(status = "SESI_MATI", lastEvent = "sesi proot berakhir — tidak di-restart otomatis")
                changedIdx.add(idx)
                continue
            }
            if (pidAlive(ctx, w.session, w.pid)) {
                if (w.lastSeenMs != now) {
                    workers[idx] = w.copy(lastSeenMs = now)
                    changedIdx.add(idx)
                }
                continue
            }
            workers[idx] = w.copy(status = "FAILED", lastEvent = "mati — perlu restart manual dari panel")
            changedIdx.add(idx)
        }
        for (idx in changedIdx) upsert(ctx, workers[idx])
        // vc281 Fase 3 (D4): panel Worker dibuka = janitor ikut jalan
        // (sweep kuota + umur + rotasi log; notifikasi hanya bila ada
        // yang dihapus/dirotasi). Gagal sweep tidak menggagalkan
        // health check.
        try { WorkerJanitor.sweepAll(ctx) } catch (e: Exception) {}
    }

    // ---------- Antrean Operator (generik, vc258 Fase 2) ----------
    // ask_operator/check_operator dulu khusus Termux ~/operator-inbox/outbox; kini
    // antrean generik (WorkerQueue) jadi jalur utama, dengan folder
    // kanonis filesDir/workers/operator/ + cermin /sdcard/Harness-Workers/operator/.
    // Tulis legacy ~/operator-inbox/ tetap dilakukan Tools.ask_operator supaya
    // cron Operator yang memantau ~/operator-inbox/ tetap melihat tugas baru
    // (kompatibel mundur — hapus tulis legacy bila cron sudah pindah).
    fun operatorCanonicalDir(ctx: Context): File = File(File(ctx.filesDir, "workers"), "operator")
    fun operatorMirrorDir(): File = File("/sdcard/Harness-Workers", "operator")

    fun enqueueOperatorTask(ctx: Context, text: String): String {
        // vc284 N4-M3: antrean Operator ikut kuota + janitor (dulu tanpa
        // gate — folder operator tumbuh tanpa batas). Pola sama dengan
        // enqueueTask worker biasa: janitor bersihkan dulu, bila MASIH
        // over-kuota tugas ditolak ("") + dicatat ke EventDb supaya
        // alasan penolakan tidak hilang diam-diam (ask_operator hanya
        // melihat "" sebagai gagal generik).
        if (text.length > WorkerLogic.TASK_MAX_CHARS) return ""
        try { WorkerJanitor.sweepOperator(ctx) } catch (e: Exception) {}
        val quota = WorkerJanitorLogic.quotaBytes(0)
        val size = WorkerJanitor.folderSizeBytes(ctx, "operator")
        if (size >= quota) {
            try { EventDb.get(ctx).add("worker-janitor", ctx.packageName, "Antrean Operator menolak tugas: folder operator sudah ${WorkerJanitorLogic.formatBytes(size)} dari kuota ${WorkerJanitorLogic.formatBytes(quota)} sesudah janitor membersihkan. Hapus file manual dari /sdcard/Harness-Workers/operator/ lalu coba lagi.") } catch (ignored: Exception) {}
            return ""
        }
        return try {
            // vc281 Fase 3 (D7): tugas ditandatangani HMAC (kunci antrean)
            // supaya worker bisa memverifikasi sebelum mengeksekusi.
            // Kunci gagal dibaca -> tugas tetap ditulis tanpa tanda tangan
            // (diterima sebagai DATA TIDAK TEPERCAYA, bukan gagal total).
            val key = try { WorkerEndpoint.queueKey(ctx) } catch (e: Exception) { null }
            WorkerQueue.enqueue(operatorCanonicalDir(ctx), operatorMirrorDir(), text, signKey = key)
        } catch (e: Exception) { "" }
    }

    fun listOperatorPending(ctx: Context): List<String> =
        try { WorkerQueue.listPending(operatorCanonicalDir(ctx)) } catch (e: Exception) { emptyList() }

    fun listOperatorResults(ctx: Context): List<String> =
        try { WorkerQueue.listResults(operatorCanonicalDir(ctx)) } catch (e: Exception) { emptyList() }

    fun readOperatorResult(ctx: Context, name: String): String =
        try { WorkerQueue.readResult(operatorCanonicalDir(ctx), name) } catch (e: Exception) { "" }

    fun readOperatorTask(ctx: Context, name: String): String =
        try { WorkerQueue.readTask(operatorCanonicalDir(ctx), name) } catch (e: Exception) { "" }

    fun writeOperatorResult(ctx: Context, taskName: String, text: String): String =
        try { WorkerQueue.writeResult(operatorCanonicalDir(ctx), operatorMirrorDir(), taskName, text) } catch (e: Exception) { "" }

    /** Cari worker by id persis, lalu by nama (case-insensitive). */
    fun resolveWorker(ctx: Context, idOrName: String): Worker? {
        val t = idOrName.trim()
        if (t.isBlank()) return null
        get(ctx, t)?.let { return it }
        return try { list(ctx).firstOrNull { it.name.equals(t, ignoreCase = true) } } catch (e: Exception) { null }
    }

    /** vc258: preview hasil worker dengan cap — kumpulkan dulu dari
     *  cermin, lalu baca hasil terbaru (atau yang disebut namanya).
     *  TIDAK PERNAH mengembalikan isi penuh di luar cap. */
    fun previewResult(ctx: Context, idOrName: String, resultName: String = "", maxChars: Int = WorkerLogic.PREVIEW_DEFAULT_CHARS): String {
        val w = if (idOrName.isBlank()) {
            try { list(ctx).firstOrNull { listResults(ctx, it.id).isNotEmpty() } } catch (e: Exception) { null }
        } else resolveWorker(ctx, idOrName)
        if (w == null) return "Worker tidak ditemukan (id/nama: '$idOrName')."
        try { collectResults(ctx, w.id) } catch (e: Exception) {}
        val results = try { listResults(ctx, w.id) } catch (e: Exception) { emptyList() }
        if (results.isEmpty()) return "Worker '${w.name}' belum punya hasil (outbox kosong)."
        if (resultName.isNotBlank() && !WorkerLogic.isResultFile(resultName)) return "Nama hasil tidak valid: '$resultName' (harus bentuk task-*.result.md)."
        val name = if (resultName.isBlank()) results.last() else resultName
        if (resultName.isNotBlank() && name !in results) return "Hasil '$resultName' tidak ada di worker '${w.name}'. Hasil yang ada: ${results.joinToString(", ")}"
        val text = try { readResult(ctx, w.id, name) } catch (e: Exception) { "" }
        if (text.isBlank()) return "Hasil '$name' ada tapi kosong / tidak terbaca (file ada, isi kosong)."
        val max = WorkerLogic.previewChars(maxChars)
        val (slice, cut) = WorkerLogic.slicePreview(text, max)
        val head = "Preview hasil worker '${w.name}' — file $name (${text.length} karakter, menampilkan ${slice.length}" + (if (cut) " — CUPLIKAN, dipotong di $max karakter, bukan isi penuh" else " — lengkap, dalam batas cap") + "):\n"
        return head + slice
    }

    // ---------- Antrean tugas generik (Jalur A) ----------

    fun enqueueTask(ctx: Context, id: String, text: String): String {
        val w = get(ctx, id) ?: return "Worker tidak ditemukan."
        if (text.length > WorkerLogic.TASK_MAX_CHARS) return "Teks tugas terlalu besar (maks 256 KB) — tidak ditulis."
        // vc281 Fase 3 (D3): janitor bersihkan dulu; bila folder MASIH
        // melewati kuota sesudah dibersihkan, tugas ditolak (di endpoint
        // dijawab 409 — pola OpResult yang sama). vc284 N4-M4: janitor
        // kini bisa membebaskan dari file tak terdaftar juga (hasil +
        // file kerja lama >7 hari); bila tetap over-kuota, yang tersisa
        // hanya tugas pending + script.py + file kerja baru yang TIDAK
        // PERNAH dihapus janitor — pesan menjelaskan cara pulih supaya
        // 409 tidak jadi jalan buntu permanen.
        try { WorkerJanitor.sweepWorker(ctx, w) } catch (e: Exception) {}
        val quota = WorkerJanitorLogic.quotaBytes(w.quotaMb)
        val size = WorkerJanitor.folderSizeBytes(ctx, w.id)
        if (size >= quota) return "Folder worker '${w.name}' sudah melewati kuota (${WorkerJanitorLogic.formatBytes(size)} dari ${WorkerJanitorLogic.formatBytes(quota)}) — tugas ditolak. Janitor sudah menghapus hasil + file kerja lama (>7 hari) yang bisa dihapus; yang tersisa hanya tugas pending + script.py + file kerja baru yang tidak pernah dihapus janitor — hapus file manual dari folder cermin /sdcard/Harness-Workers/${w.id}/ atau naikkan kuota, lalu coba lagi."
        // vc281 Fase 3 (D7): tugas ditandatangani HMAC (lihat enqueueOperatorTask).
        val key = try { WorkerEndpoint.queueKey(ctx) } catch (e: Exception) { null }
        val name = WorkerQueue.enqueue(canonicalDir(ctx, id), mirrorDir(id), text, signKey = key)
        return if (name.isBlank()) "Gagal menulis tugas — folder worker tidak bisa ditulis."
        else "Tugas ditulis ke antrean worker '${w.name}' ($name) — worker membacanya dari ${w.workDir}/inbox/."
    }

    fun listPending(ctx: Context, id: String): List<String> =
        try { WorkerQueue.listPending(canonicalDir(ctx, id)) } catch (e: Exception) { emptyList() }

    fun listResults(ctx: Context, id: String): List<String> =
        try { WorkerQueue.listResults(canonicalDir(ctx, id)) } catch (e: Exception) { emptyList() }

    fun readResult(ctx: Context, id: String, name: String): String =
        try { WorkerQueue.readResult(canonicalDir(ctx, id), name) } catch (e: Exception) { "" }

    /** Kumpulkan hasil worker: salin file baru dari cermin ke kanonis +
     *  simpan tiap hasil baru ke ResultStore ("worker:<id>").
     *  Kembalikan pesan ringkas untuk panel. */
    fun collectResults(ctx: Context, id: String): String {
        val w = get(ctx, id) ?: return "Worker tidak ditemukan."
        val key = try { WorkerEndpoint.queueKey(ctx) } catch (e: Exception) { null }
        val (copied, rejected) = try { WorkerQueue.collectFromMirrorVerified(canonicalDir(ctx, id), mirrorDir(id), key) } catch (e: Exception) { Pair(emptyList<String>(), emptyList<String>()) }
        if (copied.isEmpty() && rejected.isEmpty()) return "Tidak ada file baru dari worker '${w.name}'."
        val rIds = mutableListOf<String>()
        for (name in copied) {
            if (!name.startsWith("outbox/")) continue
            val base = name.removePrefix("outbox/")
            if (!WorkerLogic.isResultFile(base)) continue
            val text = readResult(ctx, id, base)
            if (text.isNotBlank()) {
                val rId = try { ResultStore.put(ctx, "worker:${w.id}", text) } catch (e: Exception) { "" }
                if (rId.isNotBlank()) rIds.add("$base → $rId")
            }
        }
        return "Terkumpul ${copied.size} file baru dari '${w.name}': ${copied.joinToString(", ")}" +
            (if (rIds.isNotEmpty()) "\nHasil masuk ResultStore: ${rIds.joinToString(", ")}" else "") +
            (if (rejected.isNotEmpty()) "\nDitolak ${rejected.size} file dari cermin: ${rejected.joinToString("; ")}" else "")
    }
}
