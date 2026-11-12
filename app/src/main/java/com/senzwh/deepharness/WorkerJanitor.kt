package com.senzwh.deepharness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.RandomAccessFile

/** vc281 Fase 3 Device-as-Server — janitor worker (Android).
 *  Menjalankan rencana dari WorkerJanitorLogic: hapus hasil tertua /
 *  tua + (vc284 N4-M4) file kerja lama >7 hari bila masih over-kuota
 *  + rotasi log 1 MB. Dipanggil dari tick 5 menit
 *  ServeStandbyService + healthCheck (saat panel Worker dibuka) +
 *  sebelum enqueue (bersihkan dulu) + sebelum restart (bersihkan
 *  dulu). Q2: SETIAP sweep yang menghapus apa pun (atau merotasi log)
 *  WAJIB memberi tahu user — SATU notifikasi per sweep-per-worker
 *  (bukan satu per file), channel "harness-alert", id stabil per
 *  worker (NotifIds.workerJanitorId), + catatan ke EventDb.
 *  Semua fungsi menelan exception sendiri (janitor TIDAK BOLEH
 *  menggagalkan tick/panel/enqueue/restart bila ada yang gagal). */
object WorkerJanitor {
    data class SweepReport(
        val workerId: String,
        val workerName: String,
        val deleted: List<String>,
        val bytesFreed: Long,
        val rotatedLog: Boolean,
        val totalBytes: Long,
        val quotaBytes: Long,
        val overQuota: Boolean
    )

    fun folderSizeBytes(ctx: Context, workerId: String): Long = try {
        WorkerJanitorLogic.folderSizeBytes(WorkerRegistry.canonicalDir(ctx, workerId), WorkerRegistry.mirrorDir(workerId))
    } catch (e: Exception) { 0L }

    fun logFile(ctx: Context, workerId: String): File =
        File(File(File(File(ctx.filesDir, "proot"), "ubuntu"), "root/.harness/workers"), "$workerId.log")

    /** Rotasi log copytruncate: isi lama disalin ke <id>.log.old
     *  (menimpa .old lama), lalu file log dipotong ke 0 byte DI TEMPAT
     *  (inode sama — proses worker yang masih menulis ke log tidak
     *  kehilangan pegangannya; rename biasa justru bikin tulisannya
     *  lanjut ke .old). Gagal di langkah apa pun = tidak dirotasi. */
    fun rotateLogFile(ctx: Context, workerId: String): Boolean {
        return try {
            val log = logFile(ctx, workerId)
            if (!log.isFile) return false
            if (!WorkerJanitorLogic.logRotateNeeded(log.length())) return false
            val old = File(log.parentFile, "${workerId}.log.old")
            try { log.copyTo(old, overwrite = true) } catch (e: Exception) { return false }
            try { RandomAccessFile(log, "rw").use { it.setLength(0) } } catch (e: Exception) { return false }
            true
        } catch (e: Exception) { false }
    }

    /** Satu notifikasi per worker (id stabil — notifikasi sweep
     *  berikutnya untuk worker yang sama menggantikan yang lama).
     *  Dipakai janitor + reset worker (pesan reset juga lewat sini). */
    fun notifyWorker(ctx: Context, workerId: String, workerName: String, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("harness-alert", "Pengingat & briefing", NotificationManager.IMPORTANCE_HIGH))
            }
            val id = NotifIds.workerJanitorId(workerId)
            val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, "harness-alert")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(text.take(120))
                .setStyle(Notification.BigTextStyle().bigText(text.take(800)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        } catch (e: Exception) {}
    }

    /** Sweep satu worker: rencanakan (murni) -> hapus file hasil ->
     *  rotasi log -> beri tahu bila ada yang dihapus/dirotasi ->
     *  catat ke EventDb. Kembalikan laporan (null bila worker tidak
     *  ditemukan / gagal total). */
    fun sweepWorker(ctx: Context, w: WorkerRegistry.Worker): SweepReport? {
        return try {
            val canon = WorkerRegistry.canonicalDir(ctx, w.id)
            val mirror = WorkerRegistry.mirrorDir(w.id)
            val files = WorkerJanitorLogic.listJanitorFiles(canon, mirror)
            val logSize = try { val lf = logFile(ctx, w.id); if (lf.isFile) lf.length() else -1L } catch (e: Exception) { -1L }
            // vc284 N4-M4: rencana dihitung dari ukuran folder SEBENARNYA
            // (rekursif), bukan dari jumlah file terdaftar saja — dulu
            // overQuota=false palsu bila kelebihan ada di file tak terdaftar.
            val actualTotal = WorkerJanitorLogic.folderSizeBytes(canon, mirror)
            val plan = WorkerJanitorLogic.planSweep(files, w.quotaMb, System.currentTimeMillis(), logSize, actualTotal)
            val deletedNames = mutableListOf<String>()
            var freed = 0L
            for (d in plan.delete) {
                var okAny = false
                for (root in listOf(canon, mirror)) {
                    try {
                        val f = File(root, d.file.relPath)
                        if (f.isFile && f.delete()) okAny = true
                    } catch (e: Exception) {}
                }
                if (okAny) {
                    deletedNames.add("${d.file.relPath} (${WorkerJanitorLogic.formatBytes(d.file.sizeBytes)}, ${d.reason})")
                    freed += d.file.sizeBytes
                }
            }
            val rotated = if (plan.rotateLog) rotateLogFile(ctx, w.id) else false
            val report = SweepReport(w.id, w.name, deletedNames, freed, rotated, plan.totalBytes, plan.quotaBytes, plan.overQuota)
            if (deletedNames.isNotEmpty() || rotated) {
                val parts = StringBuilder()
                if (deletedNames.isNotEmpty()) {
                    parts.append("Dihapus ${deletedNames.size} file (${WorkerJanitorLogic.formatBytes(freed)}):\n")
                    for (n in deletedNames) parts.append("- $n\n")
                }
                if (rotated) parts.append("Log worker dirotasi (melewati 1 MB; isi lama di ${w.id}.log.old).\n")
                parts.append("Alasan: ${if (plan.overQuota) "folder melewati kuota ${WorkerJanitorLogic.formatBytes(plan.quotaBytes)}" else "hasil lebih tua dari 7 hari"} (kuota worker: ${WorkerJanitorLogic.formatBytes(plan.quotaBytes)}; ukuran folder saat sweep: ${WorkerJanitorLogic.formatBytes(plan.totalBytes)}). Tugas yang belum selesai + script.py + file kerja baru TIDAK dihapus.")
                val text = parts.toString()
                notifyWorker(ctx, w.id, w.name, "Worker '${w.name}': file lama dihapus otomatis", text)
                try { EventDb.get(ctx).add("worker-janitor", ctx.packageName, "Worker '${w.name}' (${w.id}): $text") } catch (ignored: Exception) {}
            }
            report
        } catch (e: Exception) { null }
    }

    /** Sweep antrean Operator (vc284 N4-M3): operator bukan baris registry,
     *  jadi sweepAll tidak pernah menyentuhnya. Baris sintetis dengan
     *  kuota default (quotaMb=0 -> 1024 MB); notifikasi + EventDb ikut
     *  pola yang sama dengan worker biasa (di dalam sweepWorker). */
    fun sweepOperator(ctx: Context): SweepReport? {
        val w = WorkerRegistry.Worker(
            id = "operator", name = "Operator", kind = "operator", session = "worker1",
            command = "", scriptPath = "", pid = 0L, logPath = "",
            status = "STOPPED", stoppedExplicit = true, createdMs = 0L,
            lastSeenMs = 0L, lastEvent = "", owner = "",
            workDir = "/sdcard/Harness-Workers/operator", quotaMb = 0
        )
        return sweepWorker(ctx, w)
    }

    /** Sweep semua worker terdaftar (tick 5 menit + panel dibuka).
     *  Kembalikan laporan per worker yang di-sweep. */
    fun sweepAll(ctx: Context): List<SweepReport> {
        val out = mutableListOf<SweepReport>()
        try {
            for (w in WorkerRegistry.list(ctx)) {
                try { sweepWorker(ctx, w)?.let { out.add(it) } } catch (e: Exception) {}
            }
            // vc284 N4-M3: antrean Operator ikut di-sweep (bukan baris registry).
            try { sweepOperator(ctx)?.let { out.add(it) } } catch (e: Exception) {}
        } catch (e: Exception) {}
        return out
    }
}
