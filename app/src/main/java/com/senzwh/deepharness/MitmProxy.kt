package com.senzwh.deepharness

import android.content.Context
import org.json.JSONObject

// vc209 (4.48.9.43) L2 — mitmproxy resident terkelola + lapisan network
// sadar-namespace. Mengelola mitmproxy LEWAT supervisor L1 (ProotJobs):
// job bernama mitmproxy di tangan worker2, kebijakan AUTO, restart maks
// 3x per jam bila PID mati (aturan L1). File ini TIDAK menduplikasi
// supervisor: start/stop/restart/kill semua lewat ProotJobs.
// Tangan serve TIDAK PERNAH disentuh. Route/iptables TIDAK PERNAH diubah
// (diagnosis read-only). Tailscaled TIDAK PERNAH dinyalakan dari sini.
// Token/cookie TIDAK PERNAH dipanen: probe serving hanya GET polos ke
// example.com tanpa header auth apa pun.
// SEMUA fungsi yang memanggil execSession bersifat BLOKIR — panggil dari
// Thread, jangan pernah dari main thread (pelajaran ANR vc193).
// Proxy env default TIDAK AKTIF: HTTPS di proot tidak percaya CA mitm,
// sehingga routing otomatis akan merusak curl HTTPS worker. Toggle manual
// dari dialog yang sama; sesi worker baru ikut setelan ini otomatis lewat
// hook startLocked ProotSession (hanya worker, serve tidak pernah).
object MitmProxy {
    const val JOB_NAME = "mitmproxy"
    const val SESSION = "worker2"
    const val VENV_BIN = "/root/mitmvenv312/bin/mitmdump"
    const val LISTEN_HOST = "127.0.0.1"
    const val LISTEN_PORT = 8080
    const val LOG_PATH = "/root/.harness/jobs/mitmproxy.log"
    const val OLD_LOG_PATH = "/root/mitm8080"
    const val PROXY_ADDR = "127.0.0.1:8080"
    const val PROXY_ENV_PREF = "prootProxyEnabled"
    const val NET_DETECT_PREF = "mitmNetDetect"
    const val LAST_SERVING_PREF = "mitmLastServing"

    fun mitmCommand(): String = "$VENV_BIN --listen-host $LISTEN_HOST --listen-port $LISTEN_PORT"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    fun proxyEnvLine(): String =
        "export HTTP_PROXY=http://$PROXY_ADDR HTTPS_PROXY=http://$PROXY_ADDR " +
        "http_proxy=http://$PROXY_ADDR https_proxy=http://$PROXY_ADDR " +
        "ALL_PROXY=http://$PROXY_ADDR all_proxy=http://$PROXY_ADDR " +
        "NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost"

    fun proxyUnsetLine(): String =
        "unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy NO_PROXY no_proxy"

    fun proxyEnabled(ctx: Context): Boolean = try { prefs(ctx).getBoolean(PROXY_ENV_PREF, false) } catch (e: Exception) { false }

    fun findJob(ctx: Context): ProotJobs.Job? =
        try { ProotJobs.load(ctx).firstOrNull { it.name == JOB_NAME && it.session == SESSION } } catch (e: Exception) { null }

    private fun runWorker(ctx: Context, session: String, cmd: String, timeoutSec: Int): Pair<String, String> {
        return try { ProotSession.execFullSession(ctx, session, cmd, timeoutSec) } catch (e: Exception) { Pair("error: ${e.message}", "") }
    }

    // Adopsi instance mitmdump manual yang sudah jalan di worker2 (pola
    // saga tangkap: instance manual di port 8080). Mengembalikan pesan
    // bila ada percobaan adopsi (berhasil atau gagal), null bila tidak ada
    // proses mitmdump sama sekali — pemanggil boleh lanjut ke fresh start.
    // Hanya instance port 8080 yang diadopsi: instance port lain TIDAK
    // disentuh (catatannya akan menyesatkan dialog yang menampilkan 8080).
    private fun tryAdoptRunning(ctx: Context): String? {
        val (_, pgOut) = runWorker(ctx, SESSION, "pgrep -af 'mitmdum[p]' 2>/dev/null", 60)
        val lines = pgOut.lines().map { it.trim() }.filter { it.isNotBlank() && it.contains("mitmdump") }
        if (lines.isEmpty()) return null
        val on8080 = lines.firstOrNull { it.contains("8080") || !it.contains("--listen-port") }
        if (on8080 == null) {
            return "Ditemukan mitmdump jalan tapi BUKAN di port 8080 — tidak diadopsi (dialog ini menampilkan 8080). Stop manual dulu bila ingin resident terkelola di 8080."
        }
        val pid = on8080.substringBefore(" ").trim().toLongOrNull()
        if (pid == null || pid <= 0L) return "Ditemukan mitmdump jalan tapi PID tidak terbaca — tidak diadopsi."
        val cmdline = on8080.substringAfter(" ").trim()
        val (_, logProbe) = runWorker(ctx, SESSION, "test -f $OLD_LOG_PATH && echo MITM_OLDLOG || echo MITM_NOLOG", 60)
        val logPath = if (logProbe.contains("MITM_OLDLOG")) OLD_LOG_PATH else LOG_PATH
        val msg = try { ProotJobs.registerExisting(ctx, JOB_NAME, SESSION, pid, logPath, cmdline, "AUTO") } catch (e: Exception) { "error: ${e.message}" }
        val after = findJob(ctx)
        if (after != null && after.status == "RUNNING") {
            val serving = checkServing(ctx)
            return "Mitmproxy manual diadopsi jadi job terkelola (PID $pid, log $logPath).\n$msg\n$serving"
        }
        return "Adopsi mitmproxy manual gagal — $msg Tap Mulai lagi untuk restart dari perintah yang tercatat."
    }

    fun start(ctx: Context): String {
        val existing = findJob(ctx)
        if (existing != null) {
            if (existing.command.isNotBlank() && !existing.command.contains("mitmdump")) {
                return "Ada job lain bernama mitmproxy di worker2 (perintah bukan mitmdump) — ganti namanya dulu lewat panel Jobs, lalu coba lagi."
            }
            if (existing.status == "RUNNING") {
                return "Mitmproxy sudah jalan (PID ${existing.pid}, worker2, port 8080). Pakai Periksa Melayani untuk bar kesehatan."
            }
            if (existing.command.isBlank()) {
                return "Job mitmproxy tercatat tanpa perintah — hapus lewat panel Jobs lalu mulai lagi."
            }
            val msg = try { ProotJobs.restartJob(ctx, existing.id) } catch (e: Exception) { "error: ${e.message}" }
            val after = findJob(ctx)
            if (after != null && after.status == "RUNNING") {
                return msg + "\n" + checkServing(ctx)
            }
            return msg
        }
        val adopted = tryAdoptRunning(ctx)
        if (adopted != null) return adopted
        val (_, venvOut) = runWorker(ctx, SESSION, "test -x $VENV_BIN && echo MITM_VENV_OK || echo MITM_VENV_MISS", 60)
        if (!venvOut.contains("MITM_VENV_OK")) {
            return "Mitmproxy belum terpasang di proot (venv mitmvenv312 tidak ditemukan). Tunggu pemasangan latar selesai atau jalankan Pasang Tools Proot di Setup 52, lalu coba lagi."
        }
        val route = detectRoutes(ctx)
        val msg = try { ProotJobs.startJob(ctx, JOB_NAME, SESSION, mitmCommand(), LOG_PATH, "AUTO") } catch (e: Exception) { "error: ${e.message}" }
        val after = findJob(ctx)
        if (after != null && after.status == "RUNNING") {
            return msg + "\nRoute: " + route + "\n" + checkServing(ctx)
        }
        return msg + "\nRoute: " + route
    }

    fun stop(ctx: Context): String {
        val job = findJob(ctx) ?: return "Belum ada job mitmproxy terkelola — tidak ada yang di-stop."
        return try { ProotJobs.killJob(ctx, job.id) } catch (e: Exception) { "error: ${e.message}" }
    }

    // Bar kesehatan: MELAYANI = curl via proxy ke example.com dapat 200
    // DAN flow tercatat di ekor log. PID hidup saja BUKAN melayani.
    // Gagal serving TIDAK memicu restart otomatis: penyebabnya bisa di
    // hulu (internet mati) — restart buta bisa loop. Yang ditawarkan:
    // laporan + tombol Restart manual. Restart otomatis L1 hanya untuk
    // kematian PID, bukan untuk gagal serving.
    fun checkServing(ctx: Context): String {
        val job = findJob(ctx)
        if (job == null) return "Belum ada job mitmproxy terkelola — mulai dulu dari tombol Mulai."
        if (job.status != "RUNNING") return "Job mitmproxy tidak jalan (status ${ProotJobs.statusLabel(job.status)}) — Periksa Melayani butuh proses jalan."
        if (!ProotSession.isSessionAlive(SESSION)) return "Sesi worker2 mati — Periksa Melayani tidak bisa jalan. Restart job dari panel Jobs."
        val (_, curlOut) = runWorker(ctx, SESSION, "curl -s -o /dev/null -w 'MITM_HTTP_%{http_code}' --proxy http://$PROXY_ADDR --max-time 20 http://example.com/", 60)
        val code = Regex("MITM_HTTP_(\\d+)").find(curlOut)?.groupValues?.get(1)
        var flow = ""
        if (!job.logPath.contains("'")) {
            val (_, tailOut) = runWorker(ctx, SESSION, "tail -n 200 '${job.logPath}' 2>/dev/null | grep -F 'example.com' | tail -3", 60)
            flow = tailOut.trim()
        }
        val result = when {
            code == "200" && flow.isNotBlank() -> "MELAYANI (curl via proxy 200 + flow tercatat di log)"
            code == "200" -> "HIDUP + curl 200, tapi flow belum terlihat di ekor log (mungkin buffering log — cek Ekor Log)"
            code != null -> "TIDAK MELAYANI (curl via proxy HTTP $code)"
            else -> "TIDAK MELAYANI (curl via proxy gagal/timeout 20 dtk — bisa juga internet HP mati, bukan proxy)"
        }
        try { prefs(ctx).edit().putString(LAST_SERVING_PREF, "" + System.currentTimeMillis() + "|" + result).apply() } catch (e: Exception) {}
        return "Bar kesehatan mitmproxy: $result."
    }

    // Deteksi route AKTIF: main table kosong bukan berarti tidak ada
    // default (pelajaran table 1033). ip rule yang kosong/tidak terbaca
    // berarti netlink terbatas, BUKAN bukti tidak ada rule. Hasil dicatat
    // di prefs (bukan sekadar dipakai diam-diam). Read-only: tidak ada
    // perintah yang mengubah route/iptables di sini.
    fun detectRoutes(ctx: Context): String {
        var session = ""
        for (s in ProotSession.WORKER_SESSIONS) {
            if (ProotSession.isSessionAlive(s)) { session = s; break }
        }
        if (session.isBlank()) session = "worker1"
        val (_, out) = runWorker(ctx, session, "ip route show table all 2>&1; echo ---MITM_RULE---; ip rule 2>&1; echo ---MITM_MAIN---; ip route show 2>&1", 60)
        val summary = summarizeRoutes(out)
        try {
            val j = JSONObject()
            j.put("ts", System.currentTimeMillis())
            j.put("session", session)
            j.put("summary", summary)
            j.put("raw", out.take(2000))
            prefs(ctx).edit().putString(NET_DETECT_PREF, j.toString()).apply()
        } catch (e: Exception) {}
        return summary
    }

    private fun summarizeRoutes(out: String): String {
        if (out.contains("ip: command not found") || out.contains("command not found")) return "Perintah ip tidak ada di proot — deteksi route gagal."
        if (out.isBlank()) return "Deteksi route gagal (tidak ada output)."
        val defaults = mutableListOf<String>()
        var tun1Routes = 0
        var exitViaTun1 = false
        val ruleLines = mutableListOf<String>()
        var ruleBlocked = false
        var inRule = false
        for (rawLine in out.lines()) {
            val line = rawLine.trim()
            if (line == "---MITM_MAIN---") break
            if (line == "---MITM_RULE---") { inRule = true; continue }
            if (inRule) {
                if (line.isNotBlank()) ruleLines.add(line)
                if (line.contains("Permission denied") || line.contains("Netlink")) ruleBlocked = true
                continue
            }
            if (line.startsWith("default ")) {
                val m = Regex("default via (\\S+) dev (\\S+)(?: table (\\S+))?").find(line)
                if (m != null) {
                    val via = m.groupValues[1]
                    val dev = m.groupValues[2]
                    val table = m.groupValues[3].ifBlank { "main" }
                    defaults.add("via $via dev $dev (table $table)")
                    if (dev == "tun1") exitViaTun1 = true
                } else {
                    defaults.add(line)
                }
            }
            if (line.contains(" dev tun1")) tun1Routes++
        }
        val defText = if (defaults.isEmpty()) "TIDAK ADA default di semua table" else "Default: " + defaults.joinToString("; ")
        val tunText = if (tun1Routes > 0) "tun1: $tun1Routes rute" else "tun1: tidak ada rute"
        val exitText = if (exitViaTun1) "Exit-node: AKTIF (default lewat tun1)" else "Exit-node: TIDAK AKTIF (tidak ada default via tun1)"
        val ruleText = when {
            ruleBlocked -> "ip rule: tidak terbaca (netlink terbatas)"
            ruleLines.isEmpty() -> "ip rule: kosong (tidak terbaca dari proot — bukan bukti tidak ada rule)"
            else -> "ip rule: " + ruleLines.size + " baris terbaca"
        }
        return "$defText. $tunText. $exitText. $ruleText."
    }

    // Terapkan setelan proxy env saat ini ke semua tangan worker yang
    // hidup. Sesi yang restart belakangan ikut setelan ini otomatis lewat
    // hook startLocked (bukan lewat fungsi ini). Hanya worker — serve
    // tidak pernah disentuh.
    fun setProxyEnv(ctx: Context, enabled: Boolean): String {
        try { prefs(ctx).edit().putBoolean(PROXY_ENV_PREF, enabled).apply() } catch (e: Exception) {}
        val cmd = if (enabled) proxyEnvLine() else proxyUnsetLine()
        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (s in ProotSession.WORKER_SESSIONS) {
            if (!ProotSession.isSessionAlive(s)) continue
            val (display, _) = runWorker(ctx, s, cmd, 30)
            if (display.contains("exit=0")) applied.add(s) else failed.add(s)
        }
        val state = if (enabled) "AKTIF" else "TIDAK AKTIF"
        val appliedText = if (applied.isEmpty()) "-" else applied.joinToString(", ")
        val failNote = if (failed.isNotEmpty()) " Gagal di: " + failed.joinToString(", ") + "." else ""
        val note = if (applied.isEmpty()) " (tidak ada sesi worker hidup saat ini — sesi baru ikut setelan ini otomatis)" else ""
        return "Proxy env semua tangan worker: $state. Diterapkan ke: $appliedText.$failNote$note"
    }

    // Ringkasan non-blokir untuk dialog: registry + prefs saja, tanpa
    // exec proot. Bar kesehatan fresh tetap lewat checkServing.
    fun statusSummary(ctx: Context): String {
        val job = findJob(ctx)
        val jobText = if (job == null) {
            "Job mitmproxy: BELUM ADA (belum pernah dimulai terkelola)."
        } else {
            "Job mitmproxy: " + ProotJobs.statusLabel(job.status) + " • " + job.session + " • PID " + job.pid + " • port " + LISTEN_PORT + " • kebijakan " + job.policy + (if (job.restartCount > 0) " • restart " + job.restartCount + "x" else "") + "\nLog: " + job.logPath + "\nPeristiwa: " + job.lastEvent
        }
        val proxyText = "Proxy env semua tangan worker: " + (if (proxyEnabled(ctx)) "AKTIF" else "TIDAK AKTIF (default — HTTPS di proot tidak percaya CA mitm)") + "."
        val routeText = try {
            val raw = prefs(ctx).getString(NET_DETECT_PREF, "") ?: ""
            if (raw.isBlank()) "Route terakhir: BELUM PERNAH dideteksi."
            else {
                val j = JSONObject(raw)
                "Route terakhir (" + j.optString("session") + "): " + j.optString("summary")
            }
        } catch (e: Exception) { "Route terakhir: (tidak terbaca)" }
        val servingText = try {
            val raw = prefs(ctx).getString(LAST_SERVING_PREF, "") ?: ""
            if (raw.isBlank()) "Periksa Melayani terakhir: BELUM PERNAH."
            else {
                val parts = raw.split("|", limit = 2)
                "Periksa Melayani terakhir: " + (if (parts.size > 1) parts[1] else raw)
            }
        } catch (e: Exception) { "Periksa Melayani terakhir: (tidak terbaca)" }
        return jobText + "\n" + proxyText + "\n" + routeText + "\n" + servingText
    }
}
