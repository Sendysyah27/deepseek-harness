package com.senzwh.deepharness

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** vc200 (4.48.9.34) Fondasi Proot Multi-Tangan — evolusi T9 (v4.33.0):
 *  dari SATU bash persisten menjadi 5 sesi independen: "serve" + worker1..4.
 *  Tiap sesi punya proses + writer + buffer + cmdLock + seq sendiri, argumen
 *  proot SAMA seperti sebelumnya (-r rootfs, --link2symlink --sysvipc
 *  --kill-on-exit --change-id=0:0, bind /dev /proc /sys + allowlist cermin worker (vc281),
 *  -w /root,
 *  env bersih, /bin/bash --norc). Selesai ditandai __H_DONE_<id>_<exit> per
 *  sesi; cd/export/variabel bertahan PER SESI (bukan global).
 *  Tangan serve HANYA untuk ServeHealth/TailscaleHealth/standby/F3 tick —
 *  run_terminal TIDAK PERNAH merutekan ke serve (lihat execFullSession).
 *  Timeout worker = bunuh worker itu saja (serve + worker lain tak tersentuh).
 *  Timeout serve = TIDAK dibunuh diam-diam — kembalikan error bermarker agar
 *  pemanggil (ensureAlive/revive) yang reset eksplisit (hindari rekursi).
 *  stdin sesi = pipe, bukan terminal — byte Ctrl-C (0x03) hanya data, bukan
 *  SIGINT (alasan tidak ada "hentikan perintah ini saja" — adanya bunuh sesi).
 *  ProotEngine.run tetap ada untuk tes one-shot dari dialog Setup 52. */
object ProotSession {
    const val SERVE_SESSION = "serve"
    val WORKER_SESSIONS = listOf("worker1", "worker2", "worker3", "worker4")
    val ALL_SESSIONS = listOf(SERVE_SESSION) + WORKER_SESSIONS
    const val SERVE_TIMEOUT_MARKER = "[proot-serve-timeout]"
    const val IDLE_MS = 30L * 60L * 1000L
    const val AUDIT_MAX_BYTES = 1024L * 1024L
    // vc278 O18(b): deadline akuisisi cmdLock — satu exec panjang (900 dtk)
    // tidak boleh membuat exec berikutnya menggantung tanpa batas di
    // belakang lock. Lewat 60 dtk -> kembalikan pesan sibuk (endpoint
    // :4200 sudah menjawab 503 lebih dulu via dispatch timeout 25 dtk
    // sejak vc260; deadline ini menolong jalur DALAM app — run_terminal
    // 1-1/latar — yang memanggil ProotSession langsung).
    const val CMD_LOCK_TIMEOUT_SEC = 60L
    const val CMD_LOCK_BUSY_MARKER = "[proot-cmdlock-busy]"

    private class SessionState {
        val cmdLock = java.util.concurrent.locks.ReentrantLock()
        val bufLock = Object()
        val buf = StringBuilder()
        @Volatile var proc: Process? = null
        @Volatile var writer: java.io.BufferedWriter? = null
        val seq = AtomicLong(0)
        @Volatile var lastUsedMs: Long = 0L
        // vc281 Fase 3 (D1/D2): id worker yang folder cerminnya di-bind
        // ke proses sesi ini (allowlist saat start). Kosong = sesi
        // dimulai sebelum vc281 / belum ada worker di sesi ini.
        @Volatile var bindIds: Set<String> = emptySet()

        fun isAlive(): Boolean = try { proc?.isAlive == true } catch (e: Exception) { false }

        fun kill() {
            try { writer?.close() } catch (e: Exception) {}
            try { proc?.destroyForcibly() } catch (e: Exception) {}
            proc = null
            writer = null
            bindIds = emptySet()
            synchronized(bufLock) { buf.setLength(0) }
        }
    }

    private val mapLock = Object()
    private val sessions = HashMap<String, SessionState>()
    private val auditLock = Object()

    private fun stateFor(id: String): SessionState =
        synchronized(mapLock) { sessions.getOrPut(id) { SessionState() } }

    fun isValidSession(id: String): Boolean = id in ALL_SESSIONS
    fun isWorkerSession(id: String): Boolean = id in WORKER_SESSIONS
    fun isSessionAlive(id: String): Boolean = if (id in ALL_SESSIONS) stateFor(id).isAlive() else false

    /** Kompatibilitas lama: ada sesi apa pun yang hidup. */
    fun isAlive(): Boolean = ALL_SESSIONS.any { stateFor(it).isAlive() }

    /** Bunuh SEMUA sesi (kompatibilitas lama). Reset UI memakai killWorkers. */
    fun kill() {
        ALL_SESSIONS.forEach { killSession(it) }
    }

    /** Bunuh HANYA worker — serve TIDAK ikut (keputusan Fondasi atas amanah
     *  user: serve hanya di-restart via BANGKITKAN/revive). */
    fun killWorkers() {
        WORKER_SESSIONS.forEach { killSession(it) }
    }

    /** Bunuh satu sesi (no-op untuk id tak dikenal). */
    fun killSession(id: String) {
        if (id !in ALL_SESSIONS) return
        try { stateFor(id).kill() } catch (e: Exception) {}
    }

    // vc281 Fase 3 (D1/D2) — bind allowlist per sesi.
    //  Sesi worker HANYA meng-bind: sistem (/dev /proc /sys) + subdir
    //  cermin /sdcard/Harness-Workers/<id> dari worker RUNNING di sesi
    //  itu (+ worker yang sedang di-spawn/restart ke sesi itu).
    //  Guest path TETAP /sdcard/Harness-Workers/<id> — launcher,
    //  pythonCommand, dan script.py tidak berubah. /sdcard di luar
    //  folder itu TIDAK TERLIHAT dari worker (ls /sdcard gagal /
    //  kosong). Sesi serve PERTAHANKAN bind /sdcard penuh (infra
    //  tepercaya — opencode :4199 milik harness; lihat BUILD-VC281.md).
    //  Batasan jujur (D2): dua worker RUNNING boleh berbagi satu sesi
    //  (K4: maks 5 worker, 4 sesi) — bind sesi = gabungan cermin mereka,
    //  jadi worker yang berbagi sesi bisa melihat folder cermin satu
    //  sama lain. ProotJobs ikut bind sesi tempat job-nya jalan; job
    //  tinggal di /root/.harness/jobs dan tidak memakai /sdcard.
    //  Bind dipasang saat proses sesi di-start (proot tidak bisa nambah
    //  bind ke proses hidup) — karena itu spawn/restart ke sesi hidup
    //  yang bind-nya belum mencakup worker itu mematikan dulu sesi
    //  tersebut (ensureSessionBinds); proses worker yang sudah jalan
    //  selamat karena setsid-detached, sesi baru start otomatis di
    //  exec berikutnya dengan bind gabungan yang benar.
    private val pendingBinds = HashMap<String, MutableSet<String>>()

    fun notePendingBind(session: String, workerId: String) {
        if (session !in WORKER_SESSIONS || workerId.isBlank()) return
        synchronized(mapLock) { pendingBinds.getOrPut(session) { HashSet() }.add(workerId) }
    }

    private fun sessionBindWorkerIds(ctx: Context, session: String): Set<String> {
        val ids = HashSet<String>()
        try {
            for (w in WorkerRegistry.list(ctx)) {
                if (w.session == session && w.status == "RUNNING") ids.add(w.id)
            }
        } catch (e: Exception) {}
        synchronized(mapLock) { pendingBinds[session]?.let { ids.addAll(it) } }
        return ids
    }

    private fun sessionBindArgs(ctx: Context, id: String, st: SessionState): List<String> {
        val args = mutableListOf("-b", "/dev", "-b", "/proc", "-b", "/sys")
        if (id == SERVE_SESSION) {
            args += listOf("-b", "/sdcard")
            st.bindIds = emptySet()
            return args
        }
        val ids = sessionBindWorkerIds(ctx, id)
        for (wid in ids) {
            val hostMirror = File("/sdcard/Harness-Workers", wid)
            try { hostMirror.mkdirs() } catch (e: Exception) {}
            args += listOf("-b", hostMirror.absolutePath)
        }
        st.bindIds = ids.toSet()
        synchronized(mapLock) { pendingBinds.remove(id) }
        return args
    }

    /** Pastikan bind sesi mencakup folder cermin worker ini. Sesi
     *  belum hidup -> tidak ada yang dilakukan (start berikutnya
     *  menghitung union dari registry + pending). Sesi hidup tapi
     *  bind-nya belum mencakup worker ini -> sesi di-kill; exec
     *  berikutnya start ulang dengan bind gabungan yang benar. */
    fun ensureSessionBinds(ctx: Context, session: String, workerId: String) {
        if (session !in WORKER_SESSIONS || workerId.isBlank()) return
        notePendingBind(session, workerId)
        val st = stateFor(session)
        if (st.isAlive() && workerId !in st.bindIds) {
            st.kill()
        }
    }

    private fun bashExists(ctx: Context): Boolean {
        val rootfs = File(ctx.filesDir, "proot/ubuntu")
        return File(rootfs, "usr/bin/bash").exists() || File(rootfs, "bin/bash").exists()
    }

    private fun ensureLibs(ctx: Context): Boolean {
        // Setara ProotEngine.ensureLibs: libtalloc butuh nama SONAME .so.2
        val libDir = File(ctx.filesDir, "proot-lib").apply { mkdirs() }
        val dst = File(libDir, "libtalloc.so.2")
        if (!dst.exists()) {
            val src = File(ctx.applicationInfo.nativeLibraryDir, "libtalloc.so")
            if (!src.exists()) return false
            try { src.copyTo(dst, overwrite = true) } catch (e: Exception) { return false }
        }
        return File(ctx.applicationInfo.nativeLibraryDir, "libproot.so").exists()
    }

    /** Mulai satu sesi. Mengembalikan null bila sukses, atau pesan error. */
    private fun startLocked(ctx: Context, id: String, st: SessionState): String? {
        if (!bashExists(ctx)) {
            try { ProotEngine.repairLayout(ctx) } catch (e: Exception) {}
            if (!bashExists(ctx)) return "Ubuntu proot belum terpasang — pasang dulu dari Setup tombol 52 (Unduh & Pasang, sekali saja)."
        }
        if (!ensureLibs(ctx)) return "Komponen proot (libproot/libtalloc) tidak ditemukan di paket app."
        val filesDir = ctx.filesDir
        val rootfs = File(filesDir, "proot/ubuntu")
        val libDir = File(filesDir, "proot-lib")
        val tmpDir = File(filesDir, "proot-tmp").apply { mkdirs() }
        val nativeLib = ctx.applicationInfo.nativeLibraryDir
        val args = listOf(
            "$nativeLib/libproot.so",
            "-r", rootfs.absolutePath,
            "--link2symlink", "--sysvipc", "--kill-on-exit",
            "--change-id=0:0"
        ) + sessionBindArgs(ctx, id, st) + listOf(
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "TERM=xterm",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash", "--norc"
        )
        return try {
            val pb = ProcessBuilder(args)
            pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
            pb.environment()["PROOT_LOADER"] = "$nativeLib/libprootloader.so"
            pb.environment()["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:$nativeLib"
            pb.environment()["HOME"] = filesDir.absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            st.proc = p
            st.writer = p.outputStream.bufferedWriter()
            synchronized(st.bufLock) { st.buf.setLength(0) }
            Thread {
                try {
                    val r = p.inputStream.bufferedReader()
                    val chunk = CharArray(4096)
                    while (true) {
                        val n = r.read(chunk)
                        if (n < 0) break
                        synchronized(st.bufLock) {
                            st.buf.append(chunk, 0, n)
                            if (st.buf.length > 400_000) st.buf.delete(0, st.buf.length - 400_000)
                        }
                    }
                } catch (e: Exception) {}
            }.apply { isDaemon = true }.start()
            // Work dir Fondasi: dibuat diam-diam di tiap sesi baru. Tidak ada
            // output (error dibuang) sehingga tidak mengotori buffer perintah.
            try {
                st.writer?.write("mkdir -p /root/work /root/.harness 2>/dev/null\n")
                st.writer?.flush()
            } catch (e: Exception) {}
            // vc209 L2: proxy env persisten untuk tangan worker — bila
            // setelan proxy env AKTIF, sesi worker baru langsung membawa
            // export ini (sumber tunggal: MitmProxy.proxyEnvLine).
            // Serve TIDAK PERNAH disentuh di sini. Export tanpa output,
            // tidak mengotori buffer perintah. Gagal baca pref = lewati.
            if (id in WORKER_SESSIONS) {
                try {
                    if (ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getBoolean(MitmProxy.PROXY_ENV_PREF, false)) {
                        st.writer?.write(MitmProxy.proxyEnvLine() + "\n")
                        st.writer?.flush()
                    }
                } catch (e: Exception) {}
            }
            st.lastUsedMs = System.currentTimeMillis()
            null
        } catch (e: Exception) {
            "Gagal memulai sesi proot: ${e.message}"
        }
    }

    /** 4.48.9: fungsi murni potong proot — byte-identik dengan blok lama di
     *  execFull (v4.48.5 P4: trimEnd + kepala 3000 + penanda + ekor 5000).
     *  Dipakai execFull + Uji Mandiri (Cek 2). JANGAN diubah logikanya. */
    fun truncateOutput(text: String): String {
        var res = text.trimEnd()
        if (res.length > 8000) {
            val orig = res
            val cut = orig.length - 8000
            res = orig.take(3000) + "\n…(tengah dipotong, $cut char)…\n" + orig.takeLast(5000)
        }
        return res
    }

    /** Audit trail Fondasi: filesDir/proot-audit.log (app-private, BUKAN
     *  /sdcard). Satu baris per exec: sesi, timestamp, exit, durasi, perintah
     *  terpotong 500 char (newline dilipat). Rotasi sederhana: >1 MB → .old.
     *  vc265 M1: perintah DISENSOR dulu via sanitizeForAudit — token /
     *  kunci HMAC TIDAK PERNAH masuk log ini. */
    private fun logAudit(ctx: Context, session: String, command: String, exitLabel: String, durMs: Long) {
        try {
            val dir = ctx.filesDir
            val f = File(dir, "proot-audit.log")
            synchronized(auditLock) {
                try {
                    if (f.exists() && f.length() > AUDIT_MAX_BYTES) {
                        try { File(dir, "proot-audit.log.old").delete() } catch (e: Exception) {}
                        try { f.renameTo(File(dir, "proot-audit.log.old")) } catch (e: Exception) {}
                    }
                } catch (e: Exception) {}
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val clean = sanitizeForAudit(command).replace('\n', ' ').replace('\r', ' ').take(500)
                try { f.appendText("$ts $session exit=$exitLabel durMs=$durMs cmd=$clean\n") } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    /** vc265 M1 — sensor secret dari perintah SEBELUM masuk audit log.
     *  Perintah sinkronisasi token membawa token endpoint (base64) +
     *  kunci HMAC antrean, dan token per-worker, sebagai payload
     *  printf '%s' '...' | base64 -d — token utuh muat dalam 500 char
     *  log lama. Payload base64 apa pun di posisi itu disensor: perintah
     *  asli tetap dipakai untuk eksekusi, yang dicatat hanya bentuknya
     *  (bukan isinya). Satu-satunya choke point: SEMUA pemanggil logAudit
     *  lewat sini (execSession: NOSTART / WRITEFAIL / DIED / exit /
     *  TIMEOUT / SERVETIMEOUT + idle-sleep), jadi tidak ada jalur log
     *  perintah lain yang bisa melewatkan secret. */
    private fun sanitizeForAudit(command: String): String {
        return command.replace(Regex("printf '%s' '[^']*'")) { "printf '%s' '[REDACTED-base64]'" }
    }

    /** Jalur kompatibilitas lama = worker1 (pemakaian sederhana tetap di
     *  worker1, sesuai janji sticky default Fondasi). */
    fun exec(ctx: Context, command: String, timeoutSec: Int = 900): String =
        execSession(ctx, "worker1", command, timeoutSec, true).first

    /** Jalur proot + buffer mentah pre-cut (untuk ResultStore), default worker1. */
    fun execFull(ctx: Context, command: String, timeoutSec: Int = 900): Pair<String, String> =
        execFullSession(ctx, "worker1", command, timeoutSec)

    /** Jalur run_terminal: HANYA worker1..4. "serve"/tak dikenal → error. */
    fun execFullSession(ctx: Context, session: String, command: String, timeoutSec: Int = 900): Pair<String, String> {
        if (session == SERVE_SESSION) {
            return Pair("Sesi 'serve' TERLARANG untuk run_terminal — serve hanya untuk ServeHealth/TailscaleHealth/standby. Sesi worker valid: worker1, worker2, worker3, worker4.", "")
        }
        if (session !in WORKER_SESSIONS) {
            return Pair("Sesi proot tak dikenal: '$session'. Sesi worker valid: worker1, worker2, worker3, worker4 (default worker1).", "")
        }
        return execSession(ctx, session, command, timeoutSec, true)
    }

    /** Jalur tangan serve: timeout TIDAK membunuh diam-diam (killOnTimeout =
     *  false) — error bermarker dikembalikan agar pemanggil revive. */
    fun execServe(ctx: Context, command: String, timeoutSec: Int = 900): String =
        execSession(ctx, SERVE_SESSION, command, timeoutSec, false).first

    private fun execSession(ctx: Context, id: String, command: String, timeoutSec: Int, killOnTimeout: Boolean): Pair<String, String> {
        if (id !in ALL_SESSIONS) {
            return Pair("Sesi proot tak dikenal: '$id'. Sesi valid: serve, worker1, worker2, worker3, worker4.", "")
        }
        val st = stateFor(id)
        val t0 = System.currentTimeMillis()
        // vc278 O18(b): deadline akuisisi cmdLock 60 dtk (dulu:
        // synchronized tanpa batas — exec di belakang exec 900 dtk bisa
        // menggantung sampai 900 dtk). Gagal akuisisi -> pesan sibuk,
        // BUKAN antre tanpa batas.
        val lockAcquiredVc278 = try { st.cmdLock.tryLock(CMD_LOCK_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { false }
        if (!lockAcquiredVc278) {
            logAudit(ctx, id, command, "LOCKBUSY", System.currentTimeMillis() - t0)
            return Pair("⛔ sesi proot $id sibuk — perintah lain sedang memakai sesi ini (batas tunggu ${CMD_LOCK_TIMEOUT_SEC} dtk terlampaui). Coba lagi beberapa detik. $CMD_LOCK_BUSY_MARKER", "")
        }
        try {
            // Supervisor tidur-idle Fondasi (worker saja, lazy tiap exec):
            // worker idle >30 menit dimatikan; cd/export-nya hilang. Serve
            // TIDAK PERNAH tidur-idle.
            if (id != SERVE_SESSION && st.isAlive() && st.lastUsedMs > 0L &&
                System.currentTimeMillis() - st.lastUsedMs > IDLE_MS &&
                !ProotJobs.hasRunningJobs(ctx, id) &&
                !WorkerRegistry.hasRunningWorker(ctx, id)
            ) {
                st.kill()
                logAudit(ctx, id, "[idle-sleep] worker dimatikan setelah idle >30 menit", "IDLE", 0L)
            }
            var restarted = false
            if (!st.isAlive()) {
                val err = startLocked(ctx, id, st)
                if (err != null) {
                    logAudit(ctx, id, command, "NOSTART", System.currentTimeMillis() - t0)
                    return Pair(err, "")
                }
                restarted = true
            }
            val seqId = st.seq.incrementAndGet()
            val marker = "__H_DONE_${seqId}_"
            val startLen = synchronized(st.bufLock) { st.buf.length }
            val w = st.writer
            if (w == null) {
                // Pipa tulis putus = sesi sudah rusak (bukan sesi sehat yang
                // dibunuh diam-diam) — reset sebagai pembersihan.
                st.kill()
                logAudit(ctx, id, command, "WRITEFAIL", System.currentTimeMillis() - t0)
                return Pair("Sesi proot $id tidak siap (penulis sesi kosong). Sesi di-reset; coba lagi.", "")
            }
            try {
                w.write(command)
                w.write("\necho $marker$?\n")
                w.flush()
            } catch (e: Exception) {
                st.kill()
                logAudit(ctx, id, command, "WRITEFAIL", System.currentTimeMillis() - t0)
                return Pair("Sesi proot $id putus saat mengirim perintah: ${e.message}. Sesi di-reset; coba lagi.", "")
            }
            st.lastUsedMs = System.currentTimeMillis()
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            var exitCode: Int? = null
            var outText = ""
            var diedMidway = false
            while (System.currentTimeMillis() < deadline) {
                synchronized(st.bufLock) {
                    // Guard: buffer rolling bisa memotong startLen (output
                    // >400 ribu) — jangan crash, anggap jendela kosong.
                    val s = if (startLen <= st.buf.length) st.buf.substring(startLen) else ""
                    val mi = s.indexOf(marker)
                    if (mi >= 0) {
                        val tail = s.substring(mi + marker.length)
                        val num = tail.takeWhile { it.isDigit() || it == '-' }
                        exitCode = num.toIntOrNull() ?: -1
                        outText = s.substring(0, mi)
                    } else {
                        outText = s
                    }
                }
                if (exitCode != null) break
                if (!st.isAlive()) { diedMidway = true; break }
                try { Thread.sleep(120) } catch (e: Exception) {}
            }
            st.lastUsedMs = System.currentTimeMillis()
            val durMs = System.currentTimeMillis() - t0
            val full = outText.trimEnd()
            // v4.48.9: potong lewat fungsi murni (byte-identik dengan blok lama) — dipakai Uji Mandiri.
            val res = truncateOutput(outText)
            val sb = StringBuilder()
            if (restarted) sb.append("[sesi proot $id dimulai]\n")
            sb.append(res)
            when {
                diedMidway -> {
                    logAudit(ctx, id, command, "DIED", durMs)
                    sb.append("\n[sesi proot $id berakhir di tengah perintah — sesi berikutnya dimulai ulang]")
                }
                exitCode != null -> {
                    logAudit(ctx, id, command, exitCode.toString(), durMs)
                    sb.append("\n[proot $id exit=$exitCode]")
                }
                killOnTimeout -> {
                    // T13 (worker): timeout = bunuh worker ini SAJA. Serve +
                    // worker lain TIDAK tersentuh (beda dengan era satu sesi).
                    // Ctrl-C tidak bisa dipakai: stdin sesi adalah pipe, bukan
                    // terminal — byte 0x03 hanya jadi data, bukan SIGINT.
                    try { st.kill() } catch (e: Exception) {}
                    logAudit(ctx, id, command, "TIMEOUT", durMs)
                    sb.append("\n[proot $id: perintah melewati batas ${timeoutSec} dtk — sesi $id DIMATIKAN PAKSA agar tidak nge-jam; perintah ini MATI, tidak berjalan lagi. Sesi serve + worker lain TIDAK tersentuh. Panggilan berikutnya memulai sesi $id baru otomatis. JANGAN kirim ulang perintah yang sama secara foreground; ulangi dengan pola background + file hasil]")
                }
                else -> {
                    // Serve: JANGAN bunuh diam-diam. Perintah nyangkut tetap
                    // mengantre di sesi serve; pemanggil (ensureAlive/revive)
                    // yang reset eksplisit lalu revive — hindari rekursi.
                    // Pesan ini SENGAJA tanpa "200" (agar check() tidak salah
                    // baca health) dan tanpa pola PID pgrep.
                    logAudit(ctx, id, command, "SERVETIMEOUT", durMs)
                    sb.append("\n$SERVE_TIMEOUT_MARKER Perintah serve melewati batas ${timeoutSec} dtk — sesi serve TIDAK dibunuh (revive yang menangani, bukan bunuh diam-diam). Pemanggil harus reset sesi serve lalu revive.")
                }
            }
            return Pair(sb.toString(), full)
        } finally {
            st.cmdLock.unlock()
        }
    }
}
