package com.senzwh.deepharness

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** vc200 Fondasi — Tools Proot "semua tools" + snapshot murah.
 *  ensureTools: apt update + install -y (DEBIAN_FRONTEND=noninteractive)
 *  python3, nodejs, git, ffmpeg, jq, ripgrep, curl BILA belum ada; flag prefs
 *  prootToolsInstalled; log /root/proot-tools-install.log; jalan di BACKGROUND
 *  (Thread, sekali saja — flag + guard running) tanpa memblokir perintah.
 *  mitmproxy TIDAK ikut (jatah build T2). Snapshot murah: /root/work +
 *  dpkg --get-selections → /sdcard/S&Harness/proot-snapshots/<nama>/;
 *  restore = ekstrak + dselect-upgrade. TIDAK PERNAH salin rootfs penuh
 *  (storage 93%). */
object ProotTools {
    const val FLAG = "prootToolsInstalled"
    const val LOG = "/root/proot-tools-install.log"
    const val RESULT_PREF = "prootToolsResult"
    const val API_FLAG = "apiToolsInstalled"
    const val API_LOG = "/root/api-tools-install.log"
    const val API_RESULT_PREF = "apiToolsResult"

    private val running = AtomicBoolean(false)
    private val BINS = listOf("python3", "node", "git", "ffmpeg", "jq", "rg", "curl")

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    /** Jalur latar sekali saja: no-op bila sudah terpasang / proot belum
     *  terpasang. Dipanggil HarnessService.onCreate. Tidak pernah memblokir
     *  pemanggil (pekerjaan jalan di Thread sendiri). */
    fun ensureToolsIfNeeded(ctx: Context) {
        try {
            if (prefs(ctx).getBoolean(FLAG, false)) return
            if (!ProotEngine.isReady(ctx)) return
            Thread {
                try { ensureToolsSync(ctx, false) } catch (e: Exception) {}
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {}
    }

    /** Jalur sinkron (dipanggil dari Thread — JANGAN dari main thread):
     *  pasang bila belum ada (force = pasang ulang/cek walau flag sudah true).
     *  Mengembalikan ringkasan untuk UI. Guard running mencegah dua apt
     *  berjalan bersamaan (apt lock). */
    fun ensureToolsSync(ctx: Context, force: Boolean): String {
        if (!ProotEngine.isReady(ctx)) return "proot belum terpasang (Setup 52)."
        if (!force && prefs(ctx).getBoolean(FLAG, false)) return "Tools proot sudah terpasang."
        if (!running.compareAndSet(false, true)) return "Pemasangan tools sedang berjalan…"
        try {
            writeSnapshotScripts(ctx)
            val installOut = ProotEngine.run(ctx, installCmd(), 900)
            if (installOut.contains("Timeout 900")) {
                prefs(ctx).edit().putString(RESULT_PREF, "timeout").apply()
                return "Pemasangan tools timeout (900 dtk) — log: $LOG. Coba lagi via tombol ini."
            }
            // Verifikasi TERPISAH (pendek, tidak terpotong output apt): sumber
            // kebenaran adalah verify, bukan body install yang terpotong 8000.
            val verifyOut = ProotEngine.run(ctx, verifyCmd(), 60)
            val missing = BINS.filter { !verifyOut.contains("OK $it") }
            return if (missing.isEmpty()) {
                prefs(ctx).edit().putBoolean(FLAG, true).putString(RESULT_PREF, "lengkap").apply()
                "Tools proot lengkap: python3, node, git, ffmpeg, jq, rg, curl. Log: $LOG."
            } else {
                prefs(ctx).edit().putBoolean(FLAG, false).putString(RESULT_PREF, "hilang: " + missing.joinToString(",")).apply()
                "Tools belum lengkap (hilang: " + missing.joinToString(", ") + "). Log: $LOG. Coba lagi via tombol ini."
            }
        } finally {
            running.set(false)
        }
    }

    /** vc201 T2: jalur latar sekali saja untuk mitmproxy (pola
     *  ensureToolsIfNeeded). Menunggu guard apt Fondasi bila sedang jalan
     *  (maks 30 menit, thread daemon) agar dua apt tidak balapan di lock. */
    fun ensureApiToolsIfNeeded(ctx: Context) {
        try {
            if (prefs(ctx).getBoolean(API_FLAG, false)) return
            if (!ProotEngine.isReady(ctx)) return
            Thread {
                try {
                    var waited = 0
                    while (running.get() && waited < 360) {
                        try { Thread.sleep(5000) } catch (e: Exception) {}
                        waited++
                    }
                    ensureApiToolsSync(ctx, false)
                } catch (e: Exception) {}
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {}
    }

    /** vc201 T2: jalur sinkron mitmproxy (dipanggil dari Thread — JANGAN dari
     *  main thread). Missing-only; bila apt tidak menyediakan mitmproxy,
     *  kembalikan pesan jelas + flag false — JANGAN fallback ke pip tanpa
     *  Gas (pip Ubuntu 24.04 butuh venv/break-system-packages, keputusan
     *  terpisah). Guard apt dipakai bersama installer Fondasi. */
    fun ensureApiToolsSync(ctx: Context, force: Boolean): String {
        if (!ProotEngine.isReady(ctx)) return "proot belum terpasang (Setup 52)."
        if (!force && prefs(ctx).getBoolean(API_FLAG, false)) return "Tools API-Buatan sudah terpasang (mitmproxy)."
        if (!running.compareAndSet(false, true)) return "Pemasangan tools proot sedang berjalan…"
        try {
            val installOut = ProotEngine.run(ctx, apiInstallCmd(), 900)
            if (installOut.contains("Timeout 900")) {
                prefs(ctx).edit().putString(API_RESULT_PREF, "timeout").apply()
                return "Pemasangan mitmproxy timeout (900 dtk) — log: $API_LOG. Coba lagi nanti."
            }
            if (installOut.contains("Unable to locate package") || installOut.contains("no installation candidate")) {
                prefs(ctx).edit().putBoolean(API_FLAG, false).putString(API_RESULT_PREF, "tidak-tersedia-apt").apply()
                return "mitmproxy tidak tersedia via apt di rootfs ini (log: $API_LOG)."
            }
            val verifyOut = ProotEngine.run(ctx, "command -v mitmdump >/dev/null 2>&1 && echo \"OK mitmdump\" || echo \"MISS mitmdump\"", 60)
            return if (verifyOut.contains("OK mitmdump")) {
                prefs(ctx).edit().putBoolean(API_FLAG, true).putString(API_RESULT_PREF, "lengkap").apply()
                "mitmproxy terpasang. Log: $API_LOG."
            } else {
                prefs(ctx).edit().putBoolean(API_FLAG, false).putString(API_RESULT_PREF, "hilang").apply()
                "mitmproxy belum terpasang (verifikasi gagal). Log: $API_LOG. Coba lagi nanti."
            }
        } finally {
            running.set(false)
        }
    }

    /** Perintah install mitmproxy satu-shot: hanya bila binernya belum ada
     *  (idempotent). Output apt di-tee ke log + tail ke stdout. */
    private fun apiInstallCmd(): String {
        return """
            export DEBIAN_FRONTEND=noninteractive
            if command -v mitmdump >/dev/null 2>&1; then echo MITMPROXY_SUDAH_ADA; else apt-get update 2>&1 | tee -a /root/api-tools-install.log | tail -2; apt-get install -y mitmproxy 2>&1 | tee -a /root/api-tools-install.log | tail -5; echo APT_MITMPROXY_DONE; fi
        """.trimIndent()
    }

    /** Perintah install satu-shot: hanya paket yang binernya belum ada yang
     *  di-install (idempotent). Output apt di-tee ke log + tail ke stdout. */
    private fun installCmd(): String {
        return """
            export DEBIAN_FRONTEND=noninteractive
            MISS=""
            for spec in python3:python3 node:nodejs git:git ffmpeg:ffmpeg jq:jq rg:ripgrep curl:curl; do bin="${'$'}{spec%%:*}"; pkg="${'$'}{spec##*:}"; command -v "${'$'}bin" >/dev/null 2>&1 || MISS="${'$'}MISS ${'$'}pkg"; done
            if [ -n "${'$'}MISS" ]; then apt-get update 2>&1 | tee -a /root/proot-tools-install.log | tail -2; apt-get install -y ${'$'}MISS 2>&1 | tee -a /root/proot-tools-install.log | tail -5; echo APT_INSTALL_DONE; fi
            echo INSTALL_PHASE_DONE
        """.trimIndent()
    }

    /** Perintah verifikasi pendek (tidak pernah terpotong): satu baris OK/MISS
     *  per biner. */
    private fun verifyCmd(): String {
        return "for b in python3 node git ffmpeg jq rg curl; do if command -v ${'$'}b >/dev/null 2>&1; then echo \"OK ${'$'}b\"; else echo \"MISS ${'$'}b\"; fi; done"
    }

    /** Versi tools + keberadaan log/skrip (untuk tombol Setup 52). */
    fun versions(ctx: Context): String {
        if (!ProotEngine.isReady(ctx)) return "proot belum terpasang (Setup 52)."
        return ProotEngine.run(ctx, "python3 --version 2>&1; node --version 2>&1; git --version 2>&1; ffmpeg -version 2>&1 | head -1; jq --version 2>&1; rg --version 2>&1 | head -1; curl --version 2>&1 | head -1; mitmdump --version 2>&1; ls -l /root/proot-tools-install.log /root/.harness/proot-snapshot.sh /root/.harness/proot-restore.sh 2>&1", 60)
    }

    /** Tulis skrip snapshot/restore ke rootfs (host-side: rootfs adalah
     *  direktori filesDir/proot/ubuntu, jadi guest /root/.harness sama dengan
     *  berkas ini). Idempotent: tulis ulang = hasil sama. */
    fun writeSnapshotScripts(ctx: Context): Boolean {
        return try {
            if (!ProotEngine.isReady(ctx)) return false
            val rootfs = File(ctx.filesDir, "proot/ubuntu")
            val dir = File(rootfs, "root/.harness").apply { mkdirs() }
            File(rootfs, "root/work").mkdirs()
            try { File("/sdcard/S&Harness/proot-snapshots").mkdirs() } catch (e: Exception) {}
            val snap = File(dir, "proot-snapshot.sh")
            val rest = File(dir, "proot-restore.sh")
            snap.writeText(SNAPSHOT_SCRIPT)
            rest.writeText(RESTORE_SCRIPT)
            snap.setExecutable(true)
            rest.setExecutable(true)
            snap.exists() && rest.exists()
        } catch (e: Exception) { false }
    }

    private const val SNAPSHOT_SCRIPT = """
        #!/bin/bash
        # Snapshot murah proot (vc200): /root/work + daftar paket -> /sdcard/S&Harness/proot-snapshots/<nama>/
        # TIDAK menyalin rootfs penuh (storage kritis).
        nama=$(echo "${'$'}1" | tr -cd 'A-Za-z0-9_-')
        [ -z "${'$'}nama" ] && { echo "pakai: proot-snapshot.sh <nama>"; exit 1; }
        mkdir -p /root/work "/sdcard/S&Harness/proot-snapshots/${'$'}nama"
        tar -czf "/sdcard/S&Harness/proot-snapshots/${'$'}nama/work.tar.gz" -C /root work
        dpkg --get-selections > "/sdcard/S&Harness/proot-snapshots/${'$'}nama/selections.txt"
        echo "Snapshot tersimpan: /sdcard/S&Harness/proot-snapshots/${'$'}nama/ (work.tar.gz + selections.txt)"
    """

    private const val RESTORE_SCRIPT = """
        #!/bin/bash
        # Restore snapshot proot (vc200). HATI-HATI: mengembalikan /root/work DAN daftar paket
        # (dselect-upgrade bisa menambah/menghapus paket agar sesuai snapshot).
        nama=$(echo "${'$'}1" | tr -cd 'A-Za-z0-9_-')
        dir="/sdcard/S&Harness/proot-snapshots/${'$'}nama"
        [ -z "${'$'}nama" ] || [ ! -f "${'$'}dir/work.tar.gz" ] || [ ! -f "${'$'}dir/selections.txt" ] && { echo "snapshot tidak ditemukan: ${'$'}dir"; exit 1; }
        export DEBIAN_FRONTEND=noninteractive
        tar -xzf "${'$'}dir/work.tar.gz" -C /root
        dpkg --set-selections < "${'$'}dir/selections.txt"
        apt-get dselect-upgrade -y
        echo "Restore selesai dari: ${'$'}dir"
    """
}
