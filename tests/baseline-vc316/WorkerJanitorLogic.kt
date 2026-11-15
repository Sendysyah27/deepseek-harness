package com.senzwh.deepharness

import java.io.File

/** vc281 Fase 3 Device-as-Server — logika MURNI janitor worker (kuota +
 *  hapus-otomatis + rotasi log). Tanpa import Android: dipakai
 *  WorkerJanitor + diuji kotlinc di JVM (tanpa emulator).
 *  Kontrak: BRIEF-DEVICE-SERVER.md §3.5/§3.6 + keputusan terkunci
 *  K2 (kuota folder), Q2 (hapus otomatis BOLEH + WAJIB beri tahu user
 *  tiap penghapusan), D3/D4/D5 (koordinator vc281).
 *  Kuota = TOTAL folder kanonis (filesDir/workers/<id>/) + cermin
 *  (/sdcard/Harness-Workers/<id>/). quota_mb==0 (belum di-set) = pakai
 *  default penegakan 1024 MB. Urutan hapus (D3): rotasi log dulu, lalu
 *  hasil completed lebih tua dari 7 hari, lalu hasil tertua sampai di
 *  bawah kuota, lalu (vc284 N4-M4) file kerja lain yang lebih tua dari
 *  7 hari bila MASIH over-kuota. YANG TIDAK PERNAH DIHAPUS: tugas
 *  pending (inbox tanpa hasil), script.py, dan file tugas apa pun
 *  (task-*.md).
 *  Batasan jujur (vc284): janitor menghapus HASIL (task-*.result.md)
 *  + file kerja lama (>7 hari, hanya bila masih over-kuota sesudah
 *  semua hasil dihapus). File kerja BARU tidak pernah dihapus janitor
 *  — bila kelebihan kuota hanya datang dari tugas pending / script.py
 *  / file kerja baru, janitor tidak bisa membebaskan; pulih hanya
 *  lewat hapus manual atau naikkan kuota (pesan 409 menjelaskannya). */
object WorkerJanitorLogic {
    const val DEFAULT_QUOTA_MB = 1024
    const val LOG_MAX_BYTES = 1024L * 1024L
    const val RESULT_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    /** quota_mb==0 (belum di-set) = default penegakan 1024 MB. */
    fun effectiveQuotaMb(quotaMb: Int): Int = if (quotaMb > 0) quotaMb else DEFAULT_QUOTA_MB

    fun quotaBytes(quotaMb: Int): Long = effectiveQuotaMb(quotaMb).toLong() * 1024L * 1024L

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> "%.1f MB".format(bytes / 1048576.0)
    }

    /** Satu file di folder worker, dikenali dari path relatifnya
     *  (mis. "outbox/task-1.result.md" atau "script.py"). Bila file
     *  yang sama ada di kanonis + cermin, sizeBytes = jumlah keduanya
     *  (kuota menghitung TOTAL keduanya) dan lastModifiedMs = yang
     *  terbaru — penghapusan menghapus kedua salinannya. */
    data class JanitorFile(
        val relPath: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val isResult: Boolean = false,
        val isProtected: Boolean = false,
        // vc284 N4-M4: file kerja biasa (bukan hasil, bukan tugas,
        // bukan script.py) — hanya jadi kandidat hapus bila lebih tua
        // dari 7 hari DAN folder masih over-kuota sesudah semua hasil
        // dihapus (tier 3 planSweep).
        val isStaleDeletable: Boolean = false
    )

    data class DeleteDecision(val file: JanitorFile, val reason: String)

    data class SweepPlan(
        val totalBytes: Long,
        val quotaBytes: Long,
        val overQuota: Boolean,
        val rotateLog: Boolean,
        val delete: List<DeleteDecision>,
        val bytesFreed: Long
    )

    /** Ukuran total folder kanonis + cermin (rekursif, tahan gagal
     *  baca per file). Dipakai status kuota + gerbang enqueue. */
    fun folderSizeBytes(canonicalWorkerDir: File, mirrorWorkerDir: File): Long {
        var total = 0L
        for (root in listOf(canonicalWorkerDir, mirrorWorkerDir)) {
            try {
                val stack = ArrayDeque<File>()
                stack.add(root)
                while (stack.isNotEmpty()) {
                    val f = stack.removeLast()
                    try {
                        if (f.isFile) total += f.length()
                        else if (f.isDirectory) f.listFiles()?.let { stack.addAll(it) }
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
        }
        return total
    }

    /** Daftarkan file folder worker untuk perencanaan sweep — REKURSIF
     *  (vc284 N4-M4: dulu hanya top-level inbox/outbox + *.md root +
     *  script.py, jadi file di subfolder / non-md dihitung ukurannya
     *  tapi tidak pernah jadi kandidat hapus dan kuota berlubang).
     *  Klasifikasi per file: tugas (task-*.md, di mana saja) +
     *  script.py = TERLINDUNGI (tidak pernah dihapus); hasil
     *  (task-*.result.md) = kandidat hapus biasa; file kerja lain =
     *  kandidat hapus HANYA bila lebih tua dari 7 hari DAN folder
     *  masih over-kuota sesudah semua hasil dihapus (isStaleDeletable
     *  — janitor tidak menghapus file kerja worker yang masih baru /
     *  aktif). Jelajah dibatasi 6 tingkat + 5000 entri supaya daftar
     *  tetap bounded. */
    fun listJanitorFiles(canonicalWorkerDir: File, mirrorWorkerDir: File): List<JanitorFile> {
        val byRel = LinkedHashMap<String, JanitorFile>()
        fun putOne(dir: File, rel: String, isResult: Boolean, isProtected: Boolean, isStaleDeletable: Boolean = false) {
            try {
                val f = File(dir, rel)
                if (!f.isFile) return
                val prev = byRel[rel]
                if (prev == null) {
                    byRel[rel] = JanitorFile(rel, f.length(), f.lastModified(), isResult, isProtected, isStaleDeletable)
                } else {
                    byRel[rel] = prev.copy(
                        sizeBytes = prev.sizeBytes + f.length(),
                        lastModifiedMs = maxOf(prev.lastModifiedMs, f.lastModified())
                    )
                }
            } catch (e: Exception) {}
        }
        for (root in listOf(canonicalWorkerDir, mirrorWorkerDir)) {
            try {
                val stack = ArrayDeque<Pair<File, Int>>()
                stack.add(root to 0)
                while (stack.isNotEmpty() && byRel.size < 5000) {
                    val (dir, depth) = stack.removeLast()
                    val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
                    for (f in kids) {
                        try {
                            if (f.isDirectory) {
                                if (depth < 6) stack.add(f to depth + 1)
                                continue
                            }
                            if (!f.isFile) continue
                            val rel = f.absolutePath.removePrefix(root.absolutePath + File.separator)
                            val name = f.name
                            when {
                                name == "script.py" -> putOne(root, rel, isResult = false, isProtected = true)
                                WorkerLogic.isTaskFile(name) -> putOne(root, rel, isResult = false, isProtected = true)
                                WorkerLogic.isResultFile(name) -> putOne(root, rel, isResult = true, isProtected = false)
                                else -> putOne(root, rel, isResult = false, isProtected = false, isStaleDeletable = true)
                            }
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
        }
        return byRel.values.toList()
    }

    /** Log worker perlu dirotasi bila melewati 1 MB (D5). -1 = log
     *  tidak ada / tidak terbaca (tidak dirotasi). */
    fun logRotateNeeded(logSizeBytes: Long): Boolean =
        logSizeBytes >= 0L && logSizeBytes > LOG_MAX_BYTES

    /** Rencana sweep murni: tanpa efek samping, hanya hitung.
     *  (1) hasil >7 hari selalu dihapus; (2) lalu hasil tertua sampai
     *  di bawah kuota; (3) vc284 N4-M4: bila MASIH over-kuota, file
     *  kerja lain yang lebih tua dari 7 hari (paling tua dulu).
     *  Tugas pending / script.py / file tugas TIDAK PERNAH masuk daftar
     *  hapus. totalBytes = ukuran folder sebenarnya bila
     *  folderTotalBytes diberikan (>= 0), kalau tidak jumlah file
     *  terdaftar — overQuota dihitung dari total itu, jadi kelebihan
     *  yang datang dari file tak terdaftar tetap terdeteksi (dulu
     *  overQuota=false palsu bila kelebihan ada di file tak terdaftar). */
    fun planSweep(files: List<JanitorFile>, quotaMb: Int, nowMs: Long, logSizeBytes: Long = -1L, folderTotalBytes: Long = -1L): SweepPlan {
        val quota = quotaBytes(quotaMb)
        val total = if (folderTotalBytes >= 0L) folderTotalBytes else files.sumOf { it.sizeBytes }
        val rotateLog = logRotateNeeded(logSizeBytes)
        val deletable = files.filter { it.isResult && !it.isProtected }.sortedBy { it.lastModifiedMs }
        val toDelete = mutableListOf<DeleteDecision>()
        val deletedNames = HashSet<String>()
        var freed = 0L
        for (f in deletable) {
            if (nowMs - f.lastModifiedMs > RESULT_MAX_AGE_MS) {
                toDelete.add(DeleteDecision(f, "lebih tua dari 7 hari"))
                deletedNames.add(f.relPath)
                freed += f.sizeBytes
            }
        }
        var remaining = total - freed
        for (f in deletable) {
            if (remaining <= quota) break
            if (f.relPath in deletedNames) continue
            toDelete.add(DeleteDecision(f, "melewati kuota ${formatBytes(quota)}"))
            deletedNames.add(f.relPath)
            freed += f.sizeBytes
            remaining -= f.sizeBytes
        }
        // vc284 N4-M4 (tier 3): bila MASIH over-kuota sesudah semua
        // hasil dihapus, hapus file kerja lain yang lebih tua dari 7
        // hari (paling tua dulu) — file kerja BARU tidak pernah
        // disentuh di tier ini, supaya worker yang sedang jalan tidak
        // kehilangan file aktifnya.
        if (remaining > quota) {
            val stale = files.filter { it.isStaleDeletable && !it.isProtected && !it.isResult && it.relPath !in deletedNames }.sortedBy { it.lastModifiedMs }
            for (f in stale) {
                if (remaining <= quota) break
                if (nowMs - f.lastModifiedMs <= RESULT_MAX_AGE_MS) continue
                toDelete.add(DeleteDecision(f, "lebih tua dari 7 hari (file kerja lama)"))
                deletedNames.add(f.relPath)
                freed += f.sizeBytes
                remaining -= f.sizeBytes
            }
        }
        return SweepPlan(total, quota, total > quota, rotateLog, toDelete, freed)
    }
}
