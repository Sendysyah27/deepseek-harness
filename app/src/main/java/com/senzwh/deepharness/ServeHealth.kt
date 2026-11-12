package com.senzwh.deepharness

import android.content.Context
import java.io.File

/** F3 (v4.37.0): detektor + pembangkit serve proot :4199.
 *  Status SELALU gabungan health HTTP + PID pgrep — satu saja tidak cukup
 *  (aturan SKILL lama: health 200 tanpa PID = klaim kosong, PID tanpa health
 *  = proses zombie). Semua fungsi di sini MEMBLOKIR (ProotSession.execServe, sesi serve) —
 *  JANGAN pernah dipanggil dari main thread.
 *  TIDAK PERNAH menyentuh serve :4096/:4097 — hanya :4199 milik harness. */
object ServeHealth {
    const val PORT = 4199
    const val LOG = "/root/opencode-serve-4199.log"

    data class Status(val alive: Boolean, val pid: String?, val text: String, val timeout: Boolean = false)

    fun prootInstalled(ctx: Context): Boolean {
        val rootfs = File(ctx.filesDir, "proot/ubuntu")
        return File(rootfs, "usr/bin/bash").exists() || File(rootfs, "bin/bash").exists()
    }

    @Volatile private var reviving = false

    /** vc202 T6: hitungan timeout serve BERUNTUN (in-memory; reset ke 0
     *  pada cek sehat atau mati-biasa). Satu timeout ditoleransi. */
    @Volatile private var serveTimeoutStreak = 0

    /** Cek ganda: health endpoint + PID proses. */
    fun check(ctx: Context): Status {
        if (!prootInstalled(ctx)) return Status(false, null, "proot belum terpasang (Setup 52)")
        val out = try {
            ProotSession.execServe(ctx, "curl --max-time 10 -s -o /dev/null -w '%{http_code}' http://127.0.0.1:4199/global/health; echo; pgrep -af 'opencode serve.*--port 4199' | head -3", 60)
        } catch (e: Exception) {
            serveTimeoutStreak = 0
            return Status(false, null, "cek gagal: ${e.message}")
        }
        // vc202 T6: timeout tangan serve terdeteksi eksplisit — BUKAN
        // "tidur" biasa. Satu timeout ditoleransi (lihat ensureAlive).
        if (out.contains(ProotSession.SERVE_TIMEOUT_MARKER)) {
            serveTimeoutStreak++
            return Status(false, null, "tidur (timeout serve)", true)
        }
        val healthOk = out.contains("200")
        val pid = Regex("""^(\d+) opencode serve""", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)
        if (healthOk && pid != null) {
            serveTimeoutStreak = 0
            return Status(true, pid, "hidup • PID $pid • health 200")
        }
        serveTimeoutStreak = 0
        return Status(false, pid, "tidur")
    }

    /** Bangkitkan serve: rotasi log bila > 5 MB → bunuh sisa lama by pola
     *  (bukan kill massal) → nohup start → tunggu → verifikasi ganda.
     *  Mengembalikan ringkasan untuk UI (bukan output mentah). */
    fun revive(ctx: Context): String {
        if (!prootInstalled(ctx)) return "proot belum terpasang — pasang dulu dari Setup 52."
        synchronized(this) {
            if (reviving) return "pembangkian sudah berjalan…"
            reviving = true
        }
        try {
            // vc200 Fondasi: reset sesi serve dulu — sesi lama mungkin nyangkut
            // (timeout serve tidak membunuh diam-diam, jadi revive yang reset
            // eksplisit). Serve lama ikut mati via kill-on-exit; pkill di bawah
            // tetap dipertahankan untuk sisa orphan.
            try { ProotSession.killSession("serve") } catch (e: Exception) {}
            val cmd = "log=" + LOG + "; [ -f \$log ] && [ \$(stat -c%s \$log 2>/dev/null || echo 0) -gt 5242880 ] && mv \$log \$log.old; " +
                "pkill -f 'opencode serve.*--port 4199' 2>/dev/null; sleep 1; " +
                "cd /root && nohup opencode serve --hostname 127.0.0.1 --port 4199 > " + LOG + " 2>&1 & sleep 6; " +
                "echo '--- health ---'; curl --max-time 10 -s -o /dev/null -w '%{http_code}' http://127.0.0.1:4199/global/health; echo; " +
                "echo '--- pid ---'; pgrep -af 'opencode serve.*--port 4199' | head -3"
            try {
                ProotSession.execServe(ctx, cmd, 120)
            } catch (e: Exception) {
                noteStatus(ctx, "tidur (bangkit gagal)")
                return "bangkit gagal: ${e.message}"
            }
            val st = check(ctx)
            noteStatus(ctx, if (st.alive) "hidup • PID ${st.pid}" else "tidur (bangkit gagal)")
            return if (st.alive) "Serve :4199 bangkit • PID ${st.pid} • health 200"
            else "bangkit gagal — serve tidak lolos verifikasi ganda"
        } finally {
            synchronized(this) { reviving = false }
        }
    }

    /** Cek, bila mati bangkitkan, lalu cek ulang. Satu-satunya jalan
     *  "pastikan serve hidup" yang boleh dipakai service/agen/UI.
     *  vc202 T6: SATU timeout serve DITOLERANSI (transient) — reset+revive
     *  baru jalan pada timeout beruntun ke-2, atau langsung bila matinya
     *  bukan timeout (curl jalan tapi serve tidur). */
    fun ensureAlive(ctx: Context): Status {
        val st = check(ctx)
        if (st.alive) {
            noteStatus(ctx, "hidup • PID ${st.pid}")
            return st
        }
        // vc202 T6: timeout serve pertama = transient, JANGAN reset/revive
        // dulu (serve + tailscaled tidak dibunuh untuk satu cek lambat).
        if (st.timeout && serveTimeoutStreak < 2) {
            noteStatus(ctx, "tidur (timeout serve ${serveTimeoutStreak}x — ditoleransi, reset+revive setelah 2x beruntun)")
            return st
        }
        // Timeout beruntun ke-2 ATAU mati biasa → reset eksplisit sesi
        // serve lalu revive normal. (revive sendiri juga me-reset di awal;
        // reset di sini eksplisit per keputusan T6, bukan andalan implisit.)
        try { ProotSession.killSession("serve") } catch (e: Exception) {}
        revive(ctx)
        val st2 = check(ctx)
        noteStatus(ctx, if (st2.alive) "hidup • PID ${st2.pid}" else "tidur")
        return st2
    }

    fun noteStatus(ctx: Context, text: String) {
        try {
            ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit()
                .putString("serveLastStatus", text)
                .putLong("serveLastCheckTs", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
        }
    }

    fun lastStatus(ctx: Context): String {
        return try {
            ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
                .getString("serveLastStatus", "belum pernah dicek") ?: "belum pernah dicek"
        } catch (e: Exception) {
            "belum pernah dicek"
        }
    }
}
