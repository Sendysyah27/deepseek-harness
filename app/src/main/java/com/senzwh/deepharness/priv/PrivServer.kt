package com.senzwh.deepharness.priv

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.util.UUID

/**
 * Sisi APLIKASI dari Mode ADB: memegang binder server privileged (bila hidup),
 * token sesi, pembuatan start.sh (S2), dan pemanggilan transaksi.
 * Binder hanya diterima dari broadcast yang membawa token sesi aplikasi —
 * token digenerate sekali per install dan tertanam di start.sh.
 */
object PrivServer {

    @Volatile
    private var binder: IBinder? = null

    private val deathRecipient = IBinder.DeathRecipient { binder = null }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    /** Token sesi Mode ADB. vc262 H-M14: token kini DIROTASI tiap kali
     *  start.sh ditulis ulang (rotateToken) — token lama mati saat server
     *  lama dibunuh pidfile oleh skrip yang sama. */
    fun token(ctx: Context): String {
        val p = prefs(ctx)
        var t = p.getString("privToken", "") ?: ""
        if (t.isBlank()) {
            t = UUID.randomUUID().toString()
            p.edit().putString("privToken", t).apply()
        }
        return t
    }

    // vc262 H-M14: rotasi token tiap start server (dipakai writeStartScript).
    fun rotateToken(ctx: Context): String {
        val t = UUID.randomUUID().toString()
        prefs(ctx).edit().putString("privToken", t).apply()
        return t
    }

    /** Dipanggil PrivBinderReceiver. Token salah = binder ditolak. */
    fun attach(b: IBinder?, token: String, ctx: Context): Boolean {
        if (b == null) return false
        if (token != token(ctx)) return false
        binder = b
        try { prefs(ctx).edit().putBoolean("privEverOn", true).apply() } catch (e: Exception) {}
        try { b.linkToDeath(deathRecipient, 0) } catch (e: Exception) {}
        return true
    }

    fun isAlive(): Boolean {
        val b = binder ?: return false
        return try { b.isBinderAlive } catch (e: Exception) { false }
    }

    // ---- vc301 SF-D6: sumber kebenaran kedua selain binder in-memory ----
    // Gerbang run_privileged dulu hanya membaca binder (isAlive): bila
    // proses app mati/restart sementara server harness_priv (uid shell)
    // tetap hidup, binder kembali null dan server yang hidup dilaporkan
    // "TIDAK aktif". Pidfile yang ditulis start.sh + /proc/<pid>/cmdline
    // adalah bukti independen bahwa proses server masih hidup.
    // Parameter File ber-default supaya call site Android tetap tanpa
    // argumen, sementara logikanya bisa diuji JVM dengan direktori temp.
    const val PRIV_PIDFILE_PATH = "/data/local/tmp/harness_priv.pid"

    /** true bila pid di pidfile masih hidup DAN cmdline-nya proses server priv. */
    fun serverAliveByPidfile(pidFile: File = File(PRIV_PIDFILE_PATH), procRoot: File = File("/proc")): Boolean {
        return try {
            val pid = pidFile.readText().trim().toIntOrNull() ?: return false
            if (pid <= 0) return false
            pidAlive(pid, procRoot)
        } catch (e: Exception) { false }
    }

    /** Murni + testable: /proc/<pid> ada dan cmdline-nya milik server priv.
     *  Cmdline tak terbaca = false (konservatif: jangan klaim hidup). */
    fun pidAlive(pid: Int, procRoot: File): Boolean {
        return try {
            val dir = File(procRoot, pid.toString())
            if (!dir.isDirectory) return false
            val cmd = try { File(dir, "cmdline").readBytes().toString(Charsets.UTF_8) } catch (e: Exception) { return false }
            cmd.contains("harness_priv") || cmd.contains("PrivStarter")
        } catch (e: Exception) { false }
    }

    /** true bila server (pidfile) hidup tapi binder app putus — kasus khas
     *  sesudah app restart; S7 (HarnessService) tetap penjaga auto-start. */
    fun binderDownButServerAlive(): Boolean = !isAlive() && serverAliveByPidfile()

    /** Pesan gerbang yang membedakan dua keadaan mati itu. Bila server
     *  hidup tapi binder putus, arahkan ke Setup 38 (sambung ulang, tanpa
     *  perlu pairing ulang) — bukan Setup 35 (nyalakan dari nol). */
    fun privGateMessage(includeRootHint: Boolean = false): String {
        if (binderDownButServerAlive()) return "Server Mode ADB masih hidup tapi koneksi binder app terputus (biasanya sesudah app restart) — sambungkan ulang via Setup 38 (tanpa perlu pairing ulang)."
        if (includeRootHint) return "Mode ADB TIDAK aktif dan Mod Root tidak tersedia — minta user menyalakannya dari Setup tombol 35 (Mode ADB), atau aktifkan Mod Root (Setup 45) bila HP sudah di-root."
        return "Mode ADB TIDAK aktif — minta user menyalakannya dari Setup tombol 35."
    }

    /** Minta server (bila sudah vc301) mengirim ulang binder-nya.
     *  Best-effort: server pra-vc301 tidak punya penerima rebind, broadcast
     *  ini jadi no-op; token sesi dicek server sebelum mengirim ulang.
     *  Tanpa setPackage: proses server bukan paket app biasa, penerimanya
     *  receiver dinamis di proses app_process. */
    fun requestRebind(ctx: Context): Boolean {
        return try {
            val i = Intent(PrivStarter.ACTION_REBIND).putExtra("token", token(ctx))
            ctx.sendBroadcast(i)
            true
        } catch (e: Exception) { false }
    }

    /** Dipanggil saat app start (MainActivity.onCreate): bila server pernah
     *  hidup (privEverOn) tapi binder saat ini mati, minta kirim ulang. */
    fun rebindIfNeeded(ctx: Context): Boolean {
        return try {
            if (isAlive()) return false
            if (!prefs(ctx).getBoolean("privEverOn", false)) return false
            requestRebind(ctx)
        } catch (e: Exception) { false }
    }

    private fun transact(code: Int, write: (Parcel) -> Unit): String? {
        val b = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PrivService.DESCRIPTOR)
            write(data)
            if (!b.transact(code, data, reply, 0)) null
            else {
                reply.readException()
                reply.readString()
            }
        } catch (e: Exception) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun ping(): String? = transact(PrivService.TX_PING) {}

    /** Jalankan perintah sebagai uid shell di server. Server mati = pesan petunjuk. */
    fun exec(cmd: String): String =
        transact(PrivService.TX_EXEC) { it.writeString(cmd) }
            ?: "Mode ADB tidak aktif — minta user menyalakan server dari Setup tombol 35 (Mode ADB)."

    /**
     * Tulis start.sh (S2) ke external files dir aplikasi — lokasi yang bisa
     * dibaca & dijalankan adb shell, pola yang sama seperti start.sh Shizuku.
     * Skrip membunuh server lama (pidfile), lalu meluncurkan app_process
     * dengan classpath APK harness sendiri + token sesi sebagai argumen.
     */
    fun writeStartScript(ctx: Context): String {
        val dir = ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "priv")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "start.sh")
        val apk = ctx.applicationInfo.sourceDir
        val tk = rotateToken(ctx)
        // vc173: app_process telanjang tidak ada di Android 13+ (hanya 32/64) — pilih dari ABI.
        val appProcess = if (android.os.Build.SUPPORTED_ABIS.any { it.contains("64") }) "/system/bin/app_process64" else "/system/bin/app_process32"
        val script = """#!/system/bin/sh
# Mode ADB Harness — starter server privileged (ditulis otomatis oleh Setup harness; token sesi tertanam)
APK="$apk"
TOKEN="$tk"
PIDF=/data/local/tmp/harness_priv.pid
LOGF=/data/local/tmp/harness_priv.log
if [ -f ${'$'}PIDF ]; then kill ${'$'}(cat ${'$'}PIDF) 2>/dev/null; sleep 1; fi
nohup $appProcess -Djava.class.path="${'$'}APK" /system/bin --nice-name=harness_priv com.senzwh.deepharness.priv.PrivStarter "${'$'}TOKEN" com.senzwh.deepharness >> ${'$'}LOGF 2>&1 &
echo ${'$'}! > ${'$'}PIDF
echo HARNESS-PRIV-START-DIKIRIM pid=${'$'}!
"""
        f.writeText(script)
        // vc262 H-M14: start.sh owner-only (0600), bukan world-readable.
        try {
            f.setReadable(true, true)
            f.setWritable(true, true)
            f.setExecutable(true, true)
        } catch (e: Exception) {}
        return f.absolutePath
    }

    // vc170 — diagnostik percobaan start terakhir (untuk pesan gagal Setup 38).
    @Volatile var lastDiagNsd: Int = -1
    @Volatile var lastDiagHost: String = ""
    @Volatile var lastDiagPort: Int = -1
    // vc174 — hasil mentah AdbClient per-host, diakumulasi tiap attempt (untuk pesan gagal Setup 38).
    @Volatile var lastDiagAdb: String = ""

    // vc170 — IP tempelan Setup 35 ("" bila tidak ada).
    fun fallbackHost(ctx: Context): String =
        prefs(ctx).getString("privAdbHost", "") ?: ""

    /** S3: nyalakan server lewat klien ADB tertanam (tanpa Termux). Teks hasil siap tampil. */
    fun startViaEmbedded(ctx: Context): String {
        if (!AdbPairing.isPaired(ctx)) return "Belum pairing — jalankan Setup 37 dulu (sekali saja, pakai kode pairing dari Wireless debugging)."
        if (isAlive()) return "✅ Server Mode ADB sudah aktif (" + (ping() ?: "?") + ")."
        if (tryAutoStartEmbedded(ctx)) return "✅ MODE ADB AKTIF via klien tertanam — " + (ping() ?: "") + " Tanpa Termux, tanpa Shizuku."
        return "❌ Klien tertanam gagal menyalakan server. Cek: Wireless debugging AKTIF (port connect berubah tiap dinyalakan ulang — harness mencarinya via NSD), pairing masih terdaftar (Setup 37). Cadangan terakhir: Setup 35 (Termux). [diag: pairing=" + (if (AdbPairing.isPaired(ctx)) "ya" else "tidak") + "; port dicoba=$lastDiagPort; NSD connect=" + (if (lastDiagNsd > 0) lastDiagNsd.toString() else "tidak ketemu") + "; host terakhir=$lastDiagHost; adb=" + lastDiagAdb.take(600) + "]"
    }

    /** Percobaan senyap untuk penjaga S7 & Setup 38: true bila server hidup sesudahnya. Blocking. */
    fun tryAutoStartEmbedded(ctx: Context): Boolean {
        if (isAlive()) return true
        if (!AdbPairing.isPaired(ctx)) return false
        lastDiagNsd = -1
        lastDiagAdb = ""
        val path = writeStartScript(ctx)
        val fb = fallbackHost(ctx)
        // vc171: satu host per attempt, tanpa rekursi — pemanggil yang
        // mengulang kandidat (NSD → tersimpan → loopback).
        fun attempt(port: Int, host: String): Boolean {
            if (port !in 1..65535) return false
            lastDiagPort = port; lastDiagHost = host
            val out = AdbClient.shell(ctx, port, "sh \"$path\"", 25000, host)
            lastDiagAdb += "$host=" + out.take(120).replace("\n", " ") + "; "
            if (!out.contains("HARNESS-PRIV-START-DIKIRIM")) return false
            var n = 0
            while (n < 12) { if (isAlive()) return true; Thread.sleep(1000); n++ }
            return isAlive()
        }
        fun tryHosts(port: Int, nsdHost: String): Boolean {
            val candidates = listOf(nsdHost, fb, "127.0.0.1").filter { it.isNotBlank() }.distinct()
            for (h in candidates) if (attempt(port, h)) return true
            return false
        }
        val saved = prefs(ctx).getInt("privConnectPort", -1)
        if (tryHosts(saved, "")) return true
        val (nsdHost, found) = AdbDiscovery.findService(ctx, AdbDiscovery.TYPE_CONNECT, 9000)
        lastDiagNsd = found
        if (found > 0 && found != saved) {
            prefs(ctx).edit().putInt("privConnectPort", found).apply()
            if (tryHosts(found, nsdHost)) return true
        }
        return false
    }
}
