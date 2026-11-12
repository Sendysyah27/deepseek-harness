package com.senzwh.deepharness

import android.content.Context

/** F3-Tailscale (v4.48.4): watchdog tailscaled proot + serve :4199.
 *  Cerminan ServeHealth (F3 v4.37.0) untuk komponen yang belum punya watchdog:
 *  17:42-17:44 2026-10-25 sesi proot berakhir ([proot exit=0]) -> proot
 *  --kill-on-exit membunuh tailscaled + opencode; yang punya watchdog (opencode,
 *  PID baru 15149) pulih sendiri, yang tidak punya (tailscaled) tetap mati
 *  sampai revival manual. Watchdog ini menutup celah itu.
 *  JALUR START (urutan): (1) launcher /root/ts/start-tailscaled.sh bila ada
 *  (executable, berisi perintah terbukti + nohup di dalam script);
 *  (2) FALLBACK perintah persis dari process list agent 2026-10-25 17:54
 *  (BUKAN tebakan): /root/ts/tailscaled --tun=userspace-networking
 *  --socket=/tmp/ts.sock --statedir=/root/ts/state + nohup + log.
 *  TIDAK PERNAH: funnel (hanya serve tailnet-only), sentuh :4096/:4097,
 *  auto-login (tidak ada penanganan URL login / auth key di kode),
 *  hardcode IP (Running = status + IP 100.x apa pun yang terbaca).
 *  NeedsLogin = notify-only: JANGAN restart daemon berulang.
 *  Semua fungsi di sini MEMBLOKIR (ProotSession.execServe, sesi serve) — JANGAN pernah
 *  dipanggil dari main thread. Guard concurrent-revive mirip ServeHealth. */
object TailscaleHealth {
    const val SOCKET = "/tmp/ts.sock"
    const val LAUNCHER = "/root/ts/start-tailscaled.sh"
    const val TS_LOG = "/root/ts/tailscaled.log"
    const val TS_BIN = "/root/ts/tailscaled"
    const val TS_STATEDIR = "/root/ts/state"
    const val SERVE_PORT = 4199

    enum class Kind { DEAD, NEEDS_LOGIN, RUNNING, UNKNOWN, NO_PROOT }

    data class Status(val kind: Kind, val ip: String?, val pid: String?, val text: String)

    @Volatile private var reviving = false

    /** Satu exec proot: PID tailscaled (bracket trick anti self-match) +
     *  tailscale status dengan --socket eksplisit (JANGAN andalkan TS_SOCKET). */
    fun check(ctx: Context): Status {
        if (!ServeHealth.prootInstalled(ctx)) return Status(Kind.NO_PROOT, null, null, "proot belum terpasang (Setup 52)")
        val out = try {
            ProotSession.execServe(ctx, "pgrep -af 'tailscale[d]' | head -5; echo '--- tsstatus ---'; tailscale --socket=" + SOCKET + " status 2>&1", 60)
        } catch (e: Exception) {
            return Status(Kind.UNKNOWN, null, null, "cek gagal: ${e.message}")
        }
        if (out.contains("command not found")) return Status(Kind.UNKNOWN, null, null, "tailscale CLI tidak ditemukan")
        val pid = Regex("""^(\d+)\s+.*tailscaled""", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)
        if (out.contains("Logged out")) return Status(Kind.NEEDS_LOGIN, null, pid, "perlu login ulang")
        if (pid == null) return Status(Kind.DEAD, null, null, "mati")
        val ip = Regex("""100\.\d{1,3}\.\d{1,3}\.\d{1,3}""").find(out)?.value
        if (ip != null) return Status(Kind.RUNNING, ip, pid, "Running • IP $ip")
        return Status(Kind.UNKNOWN, null, pid, "tidak diketahui")
    }

    /** Bangkitkan daemon: rotasi log bila > 5 MB (cerminan ServeHealth) ->
     *  launcher bila executable, bila tidak ada FALLBACK perintah persis
     *  (process list agent, bukan tebakan) -> tunggu -> verifikasi.
     *  Mengembalikan ringkasan untuk UI (bukan output mentah). */
    fun revive(ctx: Context): String {
        if (!ServeHealth.prootInstalled(ctx)) return "proot belum terpasang — pasang dulu dari Setup 52."
        synchronized(this) {
            if (reviving) return "pembangkian tailscale sudah berjalan…"
            reviving = true
        }
        try {
            val cmd = "log=" + TS_LOG + "; [ -f \$log ] && [ \$(stat -c%s \$log 2>/dev/null || echo 0) -gt 5242880 ] && mv \$log \$log.old; " +
                "if [ -x " + LAUNCHER + " ]; then echo '--- path:launcher'; nohup sh " + LAUNCHER + " >/dev/null 2>&1 & " +
                "else echo '--- path:fallback'; nohup " + TS_BIN + " --tun=userspace-networking --socket=" + SOCKET + " --statedir=" + TS_STATEDIR + " > " + TS_LOG + " 2>&1 & fi; sleep 6"
            var startOut = try {
                ProotSession.execServe(ctx, cmd, 120)
            } catch (e: Exception) {
                noteStatus(ctx, "mati (bangkit gagal)")
                return "bangkit gagal: ${e.message}"
            }
            // vc200 Fondasi: timeout serve tidak membunuh diam-diam — bila
            // sesi serve nyangkut, reset eksplisit lalu coba sekali lagi.
            if (startOut.contains(ProotSession.SERVE_TIMEOUT_MARKER)) {
                try { ProotSession.killSession("serve") } catch (e: Exception) {}
                startOut = try {
                    ProotSession.execServe(ctx, cmd, 120)
                } catch (e: Exception) {
                    noteStatus(ctx, "mati (bangkit gagal)")
                    return "bangkit gagal: ${e.message}"
                }
            }
            val path = if (startOut.contains("path:launcher")) "launcher" else "fallback (launcher belum ada — minta agent buat)"
            val st = check(ctx)
            return when (st.kind) {
                Kind.RUNNING -> {
                    noteStatus(ctx, st.text)
                    "Tailscale bangkit • Running • IP ${st.ip} ($path)"
                }
                Kind.NEEDS_LOGIN -> {
                    noteStatus(ctx, st.text)
                    "Tailscale hidup tapi perlu login ulang ($path)"
                }
                else -> {
                    noteStatus(ctx, "mati (bangkit gagal)")
                    "bangkit gagal — tailscaled tidak jalan ($path)"
                }
            }
        } finally {
            synchronized(this) { reviving = false }
        }
    }

    /** Cek, bila DEAD bangkitkan, lalu cek ulang. TIDAK revive untuk
     *  NEEDS_LOGIN / UNKNOWN / NO_PROOT (notify-only / bukan urusan revive). */
    fun ensureDaemon(ctx: Context): Status {
        val st = check(ctx)
        if (st.kind != Kind.DEAD) return st
        revive(ctx)
        return check(ctx)
    }

    /** true bila serve status mengandung :4199. */
    fun serveOk(ctx: Context): Boolean {
        return try {
            val out = ProotSession.execServe(ctx, "tailscale --socket=" + SOCKET + " serve status 2>&1", 60)
            out.contains(":4199")
        } catch (e: Exception) {
            false
        }
    }

    /** Pasang serve BILA hilang (cek dulu). "already exists" dari daemon
     *  = SUKSES: recheck yang memutuskan (config tersimpan di state daemon
     *  dan pulih sendiri saat daemon restart — bukti 2026-10-25 17:54). */
    fun ensureServe(ctx: Context): Boolean {
        if (serveOk(ctx)) return true
        try {
            ProotSession.execServe(ctx, "tailscale --socket=" + SOCKET + " serve --bg --tcp 4199 2>&1", 60)
        } catch (e: Exception) {
            return false
        }
        return serveOk(ctx)
    }

    /** Alur lengkap: daemon-ensure -> serve-ensure bila RUNNING ->
     *  cek final + simpan status. Satu-satunya jalan "pastikan tailscale
     *  hidup" yang boleh dipakai service/UI. */
    fun ensureTailscale(ctx: Context): Status {
        val d = ensureDaemon(ctx)
        val serveResult = if (d.kind == Kind.RUNNING) ensureServe(ctx) else null
        val fin = check(ctx)
        val final = if (fin.kind == Kind.RUNNING && serveResult != null) {
            fin.copy(text = fin.text + if (serveResult) " • serve :4199 OK" else " • serve :4199 hilang")
        } else fin
        noteStatus(ctx, final.text)
        return final
    }

    fun noteStatus(ctx: Context, text: String) {
        try {
            ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit()
                .putString("tailscaleLastStatus", text)
                .putLong("tailscaleLastCheckTs", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
        }
    }

    fun lastStatus(ctx: Context): String {
        return try {
            ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
                .getString("tailscaleLastStatus", "belum pernah dicek") ?: "belum pernah dicek"
        } catch (e: Exception) {
            "belum pernah dicek"
        }
    }

    fun lastCheckText(ctx: Context): String {
        return try {
            val ts = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
                .getLong("tailscaleLastCheckTs", 0)
            if (ts == 0L) "belum pernah dicek"
            else {
                val m = (System.currentTimeMillis() - ts) / 60000
                if (m <= 0) "baru saja" else "$m mnt lalu"
            }
        } catch (e: Exception) {
            "belum pernah dicek"
        }
    }
}
