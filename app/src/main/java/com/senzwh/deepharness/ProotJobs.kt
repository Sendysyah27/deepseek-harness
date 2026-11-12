package com.senzwh.deepharness

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

// vc208 (4.48.9.42) L1 — Supervisor + registry job proot, tangan WORKER saja.
// Serve (serve:4199 + tailscaled) TIDAK PERNAH disentuh di sini: bukan
// dikelola, bukan dibunuh, bukan di-restart. Itu milik ServeHealth/Standby.
// Supervisor TIDAK mengganti job registry prompt-only vc200 (jobs bash
// builtin + file hasil + poll) — ia menggeneralisirnya menjadi registry
// persisten + tick otomatis + panel UI.
// SEMUA fungsi yang memanggil execSession bersifat BLOKIR — panggil dari
// Thread, jangan pernah dari main thread (pelajaran ANR vc193).
// Supervisor TIDAK menemukan job sendiri: job nohup dari run_terminal TIDAK
// otomatis terdaftar. Pendaftaran lewat panel Jobs (Mulai Job Baru atau
// Daftarkan Job Lama). Ini batas L1 yang disengaja — L2 yang membuat
// mitmproxy menjadi resident terkelola otomatis.
// Aturan restart: HANYA bila job mati di luar stop eksplisit. Stop eksplisit
// selalu pindah ke STOPPED dan tidak pernah di-restart otomatis. Cap: maks
// 3x restart dalam jendela 1 jam, lalu FAILED + wajib restart manual.
// Kematian sesi (SESI_MATI) BUKAN kematian job: tidak di-restart otomatis,
// karena kematian sesi adalah peristiwa tingkat supervisor (mis. Reset Sesi
// yang disengaja user) — restart manual dari panel yang menghidupkan lagi.
object ProotJobs {
    const val PREF_KEY = "prootJobs"
    const val MAX_RESTARTS = 3
    const val RESTART_WINDOW_MS = 3600_000L
    const val TICK_MS = 60_000L
    const val SCRIPT_DIR = "/root/.harness/jobs"

    data class Job(
        val id: String,
        val name: String,
        val session: String,
        val pid: Long,
        val logPath: String,
        val command: String,
        val policy: String,
        val status: String,
        val stoppedExplicit: Boolean,
        val restartCount: Int,
        val windowStartMs: Long,
        val createdMs: Long,
        val lastSeenMs: Long,
        val lastEvent: String
    )

    fun statusLabel(status: String): String = when (status) {
        "RUNNING" -> "JALAN"
        "STOPPED" -> "STOP"
        "FAILED" -> "GAGAL"
        "SESI_MATI" -> "SESI MATI"
        else -> status
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    private fun jobToJson(j: Job): JSONObject = JSONObject().apply {
        put("id", j.id)
        put("name", j.name)
        put("session", j.session)
        put("pid", j.pid)
        put("logPath", j.logPath)
        put("command", j.command)
        put("policy", j.policy)
        put("status", j.status)
        put("stoppedExplicit", j.stoppedExplicit)
        put("restartCount", j.restartCount)
        put("windowStartMs", j.windowStartMs)
        put("createdMs", j.createdMs)
        put("lastSeenMs", j.lastSeenMs)
        put("lastEvent", j.lastEvent)
    }

    private fun jsonToJob(o: JSONObject): Job? = try {
        Job(
            id = o.optString("id"),
            name = o.optString("name"),
            session = o.optString("session"),
            pid = o.optLong("pid"),
            logPath = o.optString("logPath"),
            command = o.optString("command"),
            policy = o.optString("policy", "MANUAL"),
            status = o.optString("status", "FAILED"),
            stoppedExplicit = o.optBoolean("stoppedExplicit"),
            restartCount = o.optInt("restartCount"),
            windowStartMs = o.optLong("windowStartMs"),
            createdMs = o.optLong("createdMs"),
            lastSeenMs = o.optLong("lastSeenMs"),
            lastEvent = o.optString("lastEvent")
        ).takeIf { it.id.isNotBlank() && it.session in ProotSession.WORKER_SESSIONS }
    } catch (e: Exception) {
        null
    }

    fun load(ctx: Context): List<Job> {
        val raw = try { prefs(ctx).getString(PREF_KEY, "[]") ?: "[]" } catch (e: Exception) { "[]" }
        val out = mutableListOf<Job>()
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                jsonToJob(o)?.let { out.add(it) }
            }
        } catch (e: Exception) {}
        return out
    }

    fun save(ctx: Context, jobs: List<Job>) {
        try {
            val a = JSONArray()
            for (j in jobs) a.put(jobToJson(j))
            prefs(ctx).edit().putString(PREF_KEY, a.toString()).apply()
        } catch (e: Exception) {}
    }

    // Dipakai guard idle-sleep ProotSession: sesi yang memegang job RUNNING
    // tidak boleh tidur-idle (job-nya ikut mati). Sesi tanpa job tetap tidur.
    fun hasRunningJobs(ctx: Context, session: String): Boolean =
        try { load(ctx).any { it.session == session && it.status == "RUNNING" } } catch (e: Exception) { false }

    private fun upsert(ctx: Context, job: Job) {
        val jobs = load(ctx).toMutableList()
        val idx = jobs.indexOfFirst { it.id == job.id }
        if (idx >= 0) jobs[idx] = job else jobs.add(job)
        save(ctx, jobs)
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, load(ctx).filter { it.id != id })
    }

    private fun exitOf(display: String): Int? {
        val m = Regex("exit=(-?\\d+)").find(display)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun runWorker(ctx: Context, session: String, cmd: String, timeoutSec: Int): Pair<String, String> {
        return try { ProotSession.execFullSession(ctx, session, cmd, timeoutSec) } catch (e: Exception) { Pair("error: ${e.message}", "") }
    }

    private fun pidAlive(ctx: Context, session: String, pid: Long): Boolean {
        if (pid <= 0L) return false
        val (_, full) = runWorker(ctx, session, "kill -0 $pid 2>/dev/null && echo SUP_ALIVE || echo SUP_DEAD", 30)
        return full.contains("SUP_ALIVE")
    }

    // Perintah user TIDAK PERNAH disuntik mentah ke shell: perintah ditulis
    // ke file skrip lewat base64 (aman dari petik apa pun), lalu skrip yang
    // diluncurkan. Path log dikutip tunggal + petik tunggal ditolak.
    private fun writeScript(ctx: Context, session: String, job: Job): Boolean {
        val b64 = Base64.encodeToString(job.command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = "mkdir -p $SCRIPT_DIR && printf '%s' '$b64' | base64 -d > $SCRIPT_DIR/${job.id}.sh && chmod +x $SCRIPT_DIR/${job.id}.sh && echo SUP_SCRIPT_OK"
        val (display, full) = runWorker(ctx, session, cmd, 60)
        return full.contains("SUP_SCRIPT_OK") && exitOf(display) == 0
    }

    // Pola peluncuran yang terbukti di lingkungan ini (dipakai agen sejak
    // lama): setsid nohup + background + echo $!. $! adalah PID job
    // (setsid tidak fork di bash non-interaktif karena job background sudah
    // punya grup proses sendiri).
    private fun launchScript(ctx: Context, session: String, job: Job): Long? {
        val cmd = "setsid nohup $SCRIPT_DIR/${job.id}.sh > '${job.logPath}' 2>&1 & echo SUP_PID_\$!"
        val (_, full) = runWorker(ctx, session, cmd, 60)
        val m = Regex("SUP_PID_(\\d+)").find(full)
        return m?.groupValues?.get(1)?.toLongOrNull()
    }

    fun startJob(ctx: Context, name: String, session: String, command: String, logPathRaw: String, policyRaw: String): String {
        if (session !in ProotSession.WORKER_SESSIONS) return "Sesi '$session' bukan worker (worker1..worker4). Tangan serve tidak dikelola supervisor."
        if (name.isBlank()) return "Nama job tidak boleh kosong."
        if (command.isBlank()) return "Perintah tidak boleh kosong (pakai Daftarkan Job Lama bila perintah tidak diketahui — kebijakannya jadi MANUAL)."
        if (logPathRaw.contains("'")) return "Path log tidak boleh mengandung petik tunggal."
        val now = System.currentTimeMillis()
        val id = "job_" + now + "_" + (System.nanoTime() % 100000)
        val logPath = if (logPathRaw.isBlank()) "$SCRIPT_DIR/$id.log" else logPathRaw
        if (!logPath.startsWith("/")) return "Path log harus absolut di dalam proot (mis. /root/.harness/jobs/nama.log)."
        val policy = if (policyRaw == "AUTO") "AUTO" else "MANUAL"
        var job = Job(id, name.trim(), session, 0L, logPath, command, policy, "FAILED", false, 0, now, now, 0L, "dibuat")
        if (!writeScript(ctx, session, job)) {
            upsert(ctx, job.copy(lastEvent = "gagal menulis skrip peluncur"))
            return "Gagal menulis skrip peluncur di $session — job dicatat GAGAL, cek sesi proot."
        }
        val pid = launchScript(ctx, session, job)
        if (pid == null || pid <= 0L) {
            upsert(ctx, job.copy(lastEvent = "gagal meluncurkan — PID tidak didapat"))
            return "Gagal meluncurkan job — PID tidak didapat. Job dicatat GAGAL."
        }
        val alive = pidAlive(ctx, session, pid)
        job = if (alive) job.copy(pid = pid, status = "RUNNING", lastSeenMs = now, lastEvent = "jalan (PID $pid)")
        else job.copy(pid = pid, status = "FAILED", lastEvent = "mati seketika setelah luncur — cek ekor log")
        upsert(ctx, job)
        return if (alive) "Job '${job.name}' JALAN di $session (PID $pid, log ${job.logPath})."
        else "Job '${job.name}' MATI seketika setelah luncur (PID $pid) — dicatat GAGAL, cek ekor log."
    }

    fun registerExisting(ctx: Context, name: String, session: String, pid: Long, logPath: String, command: String, policyRaw: String): String {
        if (session !in ProotSession.WORKER_SESSIONS) return "Sesi '$session' bukan worker."
        if (name.isBlank()) return "Nama job tidak boleh kosong."
        if (pid <= 0L) return "PID tidak valid."
        val now = System.currentTimeMillis()
        val id = "job_" + now + "_" + (System.nanoTime() % 100000)
        val policy = if (command.isNotBlank() && policyRaw == "AUTO") "AUTO" else "MANUAL"
        val alive = if (ProotSession.isSessionAlive(session)) pidAlive(ctx, session, pid) else false
        val job = Job(
            id, name.trim(), session, pid, logPath, command, policy,
            if (alive) "RUNNING" else "FAILED", false, 0, now, now,
            if (alive) now else 0L,
            if (alive) "didaftarkan (PID $pid hidup)" else "didaftarkan tapi PID tidak hidup — status GAGAL"
        )
        upsert(ctx, job)
        return if (alive) "Job '${job.name}' terdaftar JALAN di $session (PID $pid)."
        else "Job '${job.name}' terdaftar tapi PID $pid tidak hidup — status GAGAL (restart manual butuh perintah)."
    }

    fun killJob(ctx: Context, id: String): String {
        val cur = load(ctx).firstOrNull { it.id == id } ?: return "Job tidak ditemukan."
        var note = "di-stop"
        if (cur.pid > 0L && ProotSession.isSessionAlive(cur.session)) {
            val (_, full) = runWorker(ctx, cur.session, "kill ${cur.pid} 2>/dev/null; sleep 1; kill -0 ${cur.pid} 2>/dev/null && echo SUP_STILL || echo SUP_GONE", 30)
            note = if (full.contains("SUP_GONE")) "di-stop (proses mati)" else "di-stop (proses masih terlihat — mungkin menolak kill, cek PID)"
        } else if (cur.pid > 0L) {
            note = "di-stop (sesi sudah mati)"
        }
        upsert(ctx, cur.copy(status = "STOPPED", stoppedExplicit = true, lastEvent = note))
        return "Job '${cur.name}' STOP — tidak akan di-restart otomatis."
    }

    fun restartJob(ctx: Context, id: String): String {
        val cur = load(ctx).firstOrNull { it.id == id } ?: return "Job tidak ditemukan."
        if (cur.session !in ProotSession.WORKER_SESSIONS) return "Sesi job bukan worker."
        if (cur.pid > 0L && ProotSession.isSessionAlive(cur.session) && pidAlive(ctx, cur.session, cur.pid)) {
            runWorker(ctx, cur.session, "kill ${cur.pid} 2>/dev/null; sleep 1", 30)
        }
        var job = cur.copy(stoppedExplicit = false, restartCount = 0, windowStartMs = System.currentTimeMillis(), status = "FAILED", lastEvent = "restart manual dimulai")
        if (job.command.isBlank()) {
            upsert(ctx, job.copy(lastEvent = "tidak bisa restart — perintah tidak tercatat (daftar ulang dengan perintah)"))
            return "Job '${job.name}' tidak punya perintah tercatat — restart tidak bisa. Hapus lalu daftarkan ulang dengan perintah."
        }
        if (!writeScript(ctx, job.session, job)) {
            upsert(ctx, job.copy(lastEvent = "restart gagal — skrip tidak bisa ditulis"))
            return "Restart gagal — skrip peluncur tidak bisa ditulis."
        }
        val pid = launchScript(ctx, job.session, job)
        val now = System.currentTimeMillis()
        job = if (pid != null && pid > 0L && pidAlive(ctx, job.session, pid))
            job.copy(pid = pid, status = "RUNNING", lastSeenMs = now, lastEvent = "restart manual (PID $pid)")
        else
            job.copy(pid = pid ?: 0L, status = "FAILED", lastEvent = "restart gagal — PID tidak didapat / mati seketika")
        upsert(ctx, job)
        return if (job.status == "RUNNING") "Job '${job.name}' JALAN lagi di ${job.session} (PID ${job.pid})."
        else "Restart job '${job.name}' GAGAL — cek ekor log."
    }

    fun logTail(ctx: Context, session: String, logPath: String, lines: Int = 80): String {
        if (session !in ProotSession.WORKER_SESSIONS) return "Sesi bukan worker."
        if (!ProotSession.isSessionAlive(session)) return "Sesi $session mati — ekor log tidak bisa dibaca (log ada di dalam proot)."
        if (logPath.contains("'")) return "Path log tidak valid."
        val (_, full) = runWorker(ctx, session, "tail -n $lines '$logPath' 2>&1", 60)
        val body = full.trimEnd()
        return if (body.isBlank()) "(log kosong / file belum ada: $logPath)" else body
    }

    // Tick supervisor. Hanya job RUNNING yang diperiksa; hanya sesi yang
    // hidup yang disentuh (sesi mati TIDAK dihidupkan ulang oleh tick —
    // menghidupkan sesi mati adalah wewenang restart manual / jalur app).
    // Tick berjalan di satu thread daemon: bila satu worker sibuk perintah
    // panjang, tick itu menunggu giliran cmdLock — tidak menumpuk, tidak
    // memblokir UI, tidak mengganggu agen.
    fun checkAll(ctx: Context) {
        val jobs = load(ctx).toMutableList()
        var changed = false
        val now = System.currentTimeMillis()
        for (idx in jobs.indices) {
            val j = jobs[idx]
            if (j.status != "RUNNING") continue
            if (!ProotSession.isSessionAlive(j.session)) {
                jobs[idx] = j.copy(status = "SESI_MATI", lastEvent = "sesi proot berakhir — tidak di-restart otomatis")
                changed = true
                continue
            }
            if (pidAlive(ctx, j.session, j.pid)) {
                if (j.lastSeenMs != now) {
                    jobs[idx] = j.copy(lastSeenMs = now)
                    changed = true
                }
                continue
            }
            if (j.stoppedExplicit) {
                jobs[idx] = j.copy(status = "STOPPED", lastEvent = "di-stop")
                changed = true
                continue
            }
            if (j.policy != "AUTO") {
                jobs[idx] = j.copy(status = "FAILED", lastEvent = "mati — kebijakan MANUAL, perlu restart manual")
                changed = true
                continue
            }
            var count = j.restartCount
            var winStart = j.windowStartMs
            if (winStart <= 0L || now - winStart > RESTART_WINDOW_MS) {
                count = 0
                winStart = now
            }
            if (count >= MAX_RESTARTS) {
                jobs[idx] = j.copy(status = "FAILED", restartCount = count, windowStartMs = winStart, lastEvent = "restart habis (3x dalam 1 jam) — perlu restart manual")
                changed = true
                continue
            }
            val probe = runWorker(ctx, j.session, "test -x $SCRIPT_DIR/${j.id}.sh && echo SUP_HAS_SCRIPT || echo SUP_NO_SCRIPT", 30)
            if (probe.second.contains("SUP_NO_SCRIPT")) {
                if (!writeScript(ctx, j.session, j)) {
                    jobs[idx] = j.copy(status = "FAILED", restartCount = count, windowStartMs = winStart, lastEvent = "restart gagal — skrip peluncur tidak bisa ditulis")
                    changed = true
                    continue
                }
            }
            val pid = launchScript(ctx, j.session, j)
            if (pid == null || pid <= 0L || !pidAlive(ctx, j.session, pid)) {
                jobs[idx] = j.copy(status = "FAILED", restartCount = count, windowStartMs = winStart, lastEvent = "restart gagal — PID tidak didapat / mati seketika")
                changed = true
                continue
            }
            jobs[idx] = j.copy(pid = pid, status = "RUNNING", restartCount = count + 1, windowStartMs = winStart, lastSeenMs = now, lastEvent = "restart otomatis ke-${count + 1} (PID $pid)")
            changed = true
        }
        if (changed) save(ctx, jobs)
    }

    private val supervisorStarted = AtomicBoolean(false)

    // Idempotent: service boleh restart berkali-kali, thread tick hanya satu.
    // Tick hidup selama proses app hidup; bila service dimatikan sistem,
    // pengawasan berhenti sampai service restart (START_STICKY) — panel
    // Jobs tetap bisa memeriksa on-demand lewat showProotJobsDialog.
    fun startSupervisor(ctx: Context) {
        if (!supervisorStarted.compareAndSet(false, true)) return
        val appCtx = ctx.applicationContext
        Thread {
            while (true) {
                try { checkAll(appCtx) } catch (e: Exception) {}
                try { Thread.sleep(TICK_MS) } catch (e: Exception) {}
            }
        }.apply { isDaemon = true; name = "proot-jobs-supervisor" }.start()
    }
}
