package com.senzwh.deepharness

import java.io.File

/** vc256 Fase 1 Device-as-Server — antrean tugas generik berbasis file
 *  (Jalur A brief §3.4). Generalisasi pola ~/jhon-inbox/outbox ke folder
 *  per worker: inbox `task-<ts>.md`, outbox `<task>.result.md`.
 *  File-based murni (File in/out, tanpa Context) supaya bisa diuji di JVM.
 *  Canonical = filesDir/workers/<id>/ ; mirror = /sdcard/Harness-Workers/<id>/
 *  (K2 opsi b). Worker (proot) membaca/menulis LEWAT MIRROR — bind /sdcard
 *  sudah ada di proot, jadi tanpa perubahan bind. Agen menulis tugas ke
 *  canonical + mirror, dan mengumpulkan hasil dari mirror ke canonical.
 *  ask_jhon/check_jhon TIDAK disentuh — file lama tetap dibaca jalur lama
 *  (kompatibel mundur = tidak ada migrasi/rename file lama). */
object WorkerQueue {
    fun inboxDir(canonicalWorkerDir: File): File = File(canonicalWorkerDir, "inbox")
    fun outboxDir(canonicalWorkerDir: File): File = File(canonicalWorkerDir, "outbox")
    fun mirrorInboxDir(mirrorWorkerDir: File): File = File(mirrorWorkerDir, "inbox")
    fun mirrorOutboxDir(mirrorWorkerDir: File): File = File(mirrorWorkerDir, "outbox")

    fun ensureDirs(canonicalWorkerDir: File, mirrorWorkerDir: File) {
        try { inboxDir(canonicalWorkerDir).mkdirs() } catch (e: Exception) {}
        try { outboxDir(canonicalWorkerDir).mkdirs() } catch (e: Exception) {}
        try { mirrorInboxDir(mirrorWorkerDir).mkdirs() } catch (e: Exception) {}
        try { mirrorOutboxDir(mirrorWorkerDir).mkdirs() } catch (e: Exception) {}
    }

    /** Tulis tugas ke inbox canonical + mirror. Kembalikan nama file, atau
     *  "" bila gagal. Nama file tidak pernah mengandung "/" (aman path). */
    fun enqueue(canonicalWorkerDir: File, mirrorWorkerDir: File, text: String, ts: Long = System.currentTimeMillis(), signKey: String? = null): String {
        if (text.isBlank()) return ""
        // vc261 N-m7: backstop ukuran (dispatch sudah 413 lebih dulu).
        if (text.length > WorkerLogic.TASK_MAX_CHARS) return ""
        val name = WorkerLogic.taskFileName(ts)
        // vc281 Fase 3 (D7): tugas ditandatangani HMAC bila kunci antrean
        // diberikan — worker memverifikasi lewat endpoint
        // POST /workers/<id>/verify-task (kunci TIDAK PERNAH keluar dari
        // app sejak vc284 N4-M2; verifier proot verify-queue.py dihapus).
        // File tanpa tanda tangan tetap diterima sebagai DATA TIDAK
        // TEPERCAYA (kompatibel mundur).
        val rawBody = "# Tugas worker\nWaktu: $ts\n\n$text\n"
        val body = if (signKey != null) WorkerEndpointLogic.signQueueText(signKey, rawBody) else rawBody
        return try {
            ensureDirs(canonicalWorkerDir, mirrorWorkerDir)
            File(inboxDir(canonicalWorkerDir), name).writeText(body, Charsets.UTF_8)
            try { File(mirrorInboxDir(mirrorWorkerDir), name).writeText(body, Charsets.UTF_8) } catch (e: Exception) {}
            name
        } catch (e: Exception) { "" }
    }

    fun listPending(canonicalWorkerDir: File): List<String> {
        val inbox = inboxDir(canonicalWorkerDir).listFiles()?.map { it.name } ?: emptyList()
        val outbox = outboxDir(canonicalWorkerDir).listFiles()?.map { it.name } ?: emptyList()
        return WorkerLogic.pendingTasks(inbox, outbox)
    }

    fun listResults(canonicalWorkerDir: File): List<String> =
        outboxDir(canonicalWorkerDir).listFiles()?.map { it.name }
            ?.filter { WorkerLogic.isResultFile(it) }?.sorted() ?: emptyList()

    // vc261 N-m7: baca secukupnya — jangan muat file utuh ke memori
    // lalu memotong. Cap = READ_MAX_CHARS + 1 (sentinel: bila terbaca
    // 8001 char, pemanggil tahu file lebih panjang dari cap).
    fun readCapped(f: File, maxChars: Int): String {
        if (!f.isFile) return ""
        return try {
            val sb = StringBuilder()
            val buf = CharArray(4096)
            f.bufferedReader(Charsets.UTF_8).use { r ->
                while (sb.length < maxChars) {
                    val n = r.read(buf, 0, minOf(buf.size, maxChars - sb.length))
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
            }
            sb.toString()
        } catch (e: Exception) { "" }
    }

    fun readTask(canonicalWorkerDir: File, name: String): String {
        if (!WorkerLogic.isTaskFile(name) || name.contains("/") || name.contains("..")) return ""
        return readCapped(File(inboxDir(canonicalWorkerDir), name), WorkerLogic.READ_MAX_CHARS + 1)
    }

    fun readResult(canonicalWorkerDir: File, name: String): String {
        if (!WorkerLogic.isResultFile(name) || name.contains("/") || name.contains("..")) return ""
        return readCapped(File(outboxDir(canonicalWorkerDir), name), WorkerLogic.READ_MAX_CHARS + 1)
    }

    /** Tulis hasil sisi agen ke outbox canonical + mirror. Bila
     *  signKey diberikan (queue key), isi ditandatangani HMAC (N-M5)
     *  supaya collect bisa membedakan hasil agen asli dari palsu. */
    fun writeResult(canonicalWorkerDir: File, mirrorWorkerDir: File, taskName: String, text: String, signKey: String? = null): String {
        if (!WorkerLogic.isTaskFile(taskName)) return ""
        val name = WorkerLogic.resultFileName(taskName)
        val body = if (signKey != null) WorkerEndpointLogic.signQueueText(signKey, text) else text
        return try {
            ensureDirs(canonicalWorkerDir, mirrorWorkerDir)
            File(outboxDir(canonicalWorkerDir), name).writeText(body, Charsets.UTF_8)
            try { File(mirrorOutboxDir(mirrorWorkerDir), name).writeText(body, Charsets.UTF_8) } catch (e: Exception) {}
            name
        } catch (e: Exception) { "" }
    }

    /** Kumpulkan file yang ditulis WORKER ke mirror → canonical.
     *  vc261 N-m10: outbox HANYA file hasil (task-*.result.md) maks 1 MB;
     *  file lepas di folder kerja mirror HANYA *.md maks 256 KB (bukan
     *  script.py, bukan biner). vc261 N-M5: bila verifyKey diberikan dan
     *  file membawa tanda tangan HMAC yang TIDAK VALID, file DITOLAK
     *  (masuk daftar rejected, tidak disalin). File TANPA tanda tangan
     *  tetap diterima apa-adanya — worker arbitrer tidak wajib menanda-
     *  tangani — tapi hasilnya tetap dilabeli DATA TIDAK TEPERCAYA di
     *  preview (N-m14). Kembalikan (disalin, ditolak). */
    fun collectFromMirrorVerified(canonicalWorkerDir: File, mirrorWorkerDir: File, verifyKey: String?): Pair<List<String>, List<String>> {
        val copied = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        try {
            val canonOut = outboxDir(canonicalWorkerDir).apply { mkdirs() }
            mirrorOutboxDir(mirrorWorkerDir).listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                if (!WorkerLogic.isResultFile(f.name)) { rejected.add("outbox/${f.name} (bukan file hasil)"); return@forEach }
                if (f.length() > 1024 * 1024) { rejected.add("outbox/${f.name} (lebih dari 1 MB)"); return@forEach }
                if (File(canonOut, f.name).exists()) return@forEach
                try {
                    val content = f.readText(Charsets.UTF_8)
                    if (verifyKey != null && WorkerEndpointLogic.hasQueueSignature(content) && WorkerEndpointLogic.verifyQueueText(verifyKey, content) == null) {
                        rejected.add("outbox/${f.name} (tanda tangan HMAC tidak valid)")
                        return@forEach
                    }
                    val body = if (verifyKey != null) (WorkerEndpointLogic.verifyQueueText(verifyKey, content) ?: content) else content
                    File(canonOut, f.name).writeText(body, Charsets.UTF_8)
                    copied.add("outbox/${f.name}")
                } catch (e: Exception) { rejected.add("outbox/${f.name} (gagal baca: ${e.message})") }
            }
            mirrorWorkerDir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                if (f.name == "script.py" || !f.name.endsWith(".md")) return@forEach
                if (f.length() > 256 * 1024) { rejected.add("${f.name} (lebih dari 256 KB)"); return@forEach }
                if (File(canonicalWorkerDir, f.name).exists()) return@forEach
                try { f.copyTo(File(canonicalWorkerDir, f.name)); copied.add(f.name) } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        return Pair(copied, rejected)
    }

    fun collectFromMirror(canonicalWorkerDir: File, mirrorWorkerDir: File): List<String> =
        collectFromMirrorVerified(canonicalWorkerDir, mirrorWorkerDir, null).first
}
