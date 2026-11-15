package com.senzwh.deepharness

/** vc256 Fase 1 Device-as-Server / Agent-as-Storage — logika MURNI worker.
 *  Tanpa import Android: dipakai WorkerRegistry/WorkerQueue + diuji kotlinc
 *  di JVM (tanpa emulator). Daftar sesi worker diduplikasi dari
 *  ProotSession.WORKER_SESSIONS (sumber kebenaran) supaya file ini tetap
 *  kompilabel di JVM.
 *  Kontrak: BRIEF-DEVICE-SERVER.md §4 Fase 1 + keputusan terkunci K1–K5,
 *  Q1–Q3 (2026-11-07). K6 (endpoint :4198) = Fase 2 (vc257), BUKAN di sini. */
object WorkerLogic {
    const val MAX_RUNNING = 5
    const val SCRIPT_DIR = "/root/.harness/workers"
    const val MIRROR_ROOT_NAME = "Harness-Workers"
    const val QUOTA_MB_DEFAULT = 0

    // vc261: batas ukuran teks tugas antrean (N-m7). 256 KB — enqueue
    // yang lebih besar ditolak 413 di endpoint, ditolak diam-diam di
    // WorkerQueue.enqueue (kembalikan "").
    const val TASK_MAX_CHARS = 262144

    // vc258 Fase 2: cap preview hasil worker (dipakai endpoint :4198 +
    // tool preview_worker_result). Preview TIDAK PERNAH mengembalikan
    // isi penuh di luar cap.
    const val PREVIEW_DEFAULT_CHARS = 2000
    const val PREVIEW_MAX_CHARS = 8000
    const val READ_MAX_CHARS = 8000

    fun previewChars(requested: Int): Int =
        if (requested <= 0) PREVIEW_DEFAULT_CHARS else requested.coerceIn(1, PREVIEW_MAX_CHARS)

    /** Potong teks ke maks char; kembalikan (potongan, terpotong?). */
    fun slicePreview(text: String, max: Int): Pair<String, Boolean> =
        if (text.length <= max) Pair(text, false) else Pair(text.take(max), true)

    val WORKER_SESSIONS = listOf("worker1", "worker2", "worker3", "worker4")

    fun statusLabel(status: String): String = when (status) {
        "RUNNING" -> "JALAN"
        "STOPPED" -> "STOP"
        "FAILED" -> "GAGAL"
        "SESI_MATI" -> "SESI MATI"
        else -> status
    }

    /** Status tampil: RUNNING tersimpan tapi sesinya mati = TIDAK DIKETAHUI
     *  (jujur pasca-restart HP — bukan pura-pura hidup, bukan langsung GAGAL).
     *  Dipakai saat render tanpa healthCheck dulu; healthCheck sendiri
     *  menandai SESI_MATI. */
    fun displayStatus(storedStatus: String, sessionAlive: Boolean): String =
        if (storedStatus == "RUNNING" && !sessionAlive) "TIDAK DIKETAHUI" else statusLabel(storedStatus)

    /** Validasi spawn murni: kembalikan pesan error, atau null bila OK.
     *  K4: maks 5 worker JALAN bersamaan; TANPA batasan baterai (tidak dicek
     *  di sini — worker yang di-spawn tidak dihentikan gerbang baterai).
     *  vc265 N2-M3: cek duplikat nama — nama worker yang sudah JALAN
     *  (daftar existingRunningNames dari registry, hanya status RUNNING)
     *  tidak boleh di-spawn lagi; retry spawn identik ditangani endpoint
     *  (200 + worker yang ada), sementara nama sama + isi beda ditolak
     *  di sini supaya tidak ada dua worker JALAN bernama sama. Nama
     *  worker STOPPED/FAILED boleh dipakai lagi. */
    fun validateSpawn(name: String, kind: String, session: String, command: String, runningCount: Int, existingRunningNames: List<String> = emptyList()): String? {
        if (name.isBlank()) return "Nama worker tidak boleh kosong."
        // vc261 N-m5: "spawn" dicadangkan — GET /workers/spawn dibayangi
        // rute spawn (POST), dan nama ini tidak bisa dijangkau lewat
        // detail worker. ID worker selalu w_<ts> jadi tidak kena.
        if (name.trim().equals("spawn", ignoreCase = true)) return "Nama 'spawn' dicadangkan (bentrok rute endpoint /workers/spawn) — pakai nama lain."
        if (existingRunningNames.any { it.equals(name.trim(), ignoreCase = true) }) return "Worker dengan nama '${name.trim()}' sudah JALAN — pakai nama lain, atau stop dulu worker itu."
        if (kind != "proot" && kind != "python") return "Jenis worker tidak dikenal: '$kind'."
        if (session !in WORKER_SESSIONS) return "Sesi '$session' bukan worker (worker1..worker4)."
        if (command.isBlank()) return "Perintah tidak boleh kosong."
        if (runningCount >= MAX_RUNNING) return "Sudah ada $MAX_RUNNING worker JALAN (maks K4) — stop satu dulu."
        return null
    }

    // vc261 N-m6: nama unik — ts saja (milidetik) bisa tabrakan bila
    // dua enqueue bersamaan; tambah 8 hex acak. isTaskFile tetap cocok
    // (awalan task-, akhiran .md).
    fun taskFileName(ts: Long): String =
        "task-$ts-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8) + ".md"
    fun resultFileName(taskName: String): String = "$taskName.result.md"
    fun isTaskFile(name: String): Boolean =
        name.startsWith("task-") && name.endsWith(".md") && !name.endsWith(".result.md")
    fun isResultFile(name: String): Boolean =
        name.startsWith("task-") && name.endsWith(".result.md")

    /** Tugas pending = ada di inbox tapi belum ada hasil di outbox. */
    fun pendingTasks(inboxNames: List<String>, outboxNames: List<String>): List<String> {
        val results = outboxNames.filter { isResultFile(it) }.toSet()
        return inboxNames.filter { isTaskFile(it) && !results.contains(resultFileName(it)) }.sorted()
    }

    /** Skrip peluncur worker: cd ke folder kerja (mirror /sdcard, terlihat
     *  dari dalam proot lewat bind yang sudah ada) lalu perintah.
     *  Ditulis via base64 seperti ProotJobs — perintah tidak pernah
     *  disuntik mentah ke shell. */
    fun launcherScript(workDirProot: String, command: String): String =
        "cd \"$workDirProot\" || exit 1\n$command\n"

    fun pythonCommand(workDirProot: String, scriptName: String = "script.py"): String =
        "python3 -u \"$workDirProot/$scriptName\""

    fun kindLabel(kind: String): String = when (kind) {
        "proot" -> "Proot (perintah)"
        "python" -> "Script Python"
        else -> kind
    }
}
