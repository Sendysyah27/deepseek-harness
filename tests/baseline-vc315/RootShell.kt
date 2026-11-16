package com.senzwh.deepharness

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** v4.2.0 — Mod Root (EKSPERIMENTAL): eksekusi lewat su (uid=0) untuk perangkat
 *  yang sudah di-root. Jalur prioritas tool run_privileged & terminal mode priv
 *  saat pref rootMode aktif. Gagal/ditolak/tidak ada su → null, dan penelepon
 *  jatuh ke Mode ADB seperti biasa. */
object RootShell {
    @Volatile var probed: Boolean = false
    @Volatile private var rootOk: Boolean = false

    fun available(): Boolean {
        if (!probed) probe()
        return rootOk
    }

    fun probe(): Boolean {
        synchronized(this) {
            try {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                var out = ""
                val t = Thread { out = readAll(p) }
                t.start()
                if (!p.waitFor(4, TimeUnit.SECONDS)) {
                    try { p.destroyForcibly() } catch (e: Exception) {}
                    probed = true
                    rootOk = false
                    return false
                }
                t.join(1200)
                rootOk = p.exitValue() == 0 && out.contains("uid=0")
            } catch (e: Exception) {
                rootOk = false
            }
            probed = true
            return rootOk
        }
    }

    private fun readAll(p: Process): String {
        val sb = StringBuilder()
        try {
            val r = BufferedReader(InputStreamReader(p.inputStream))
            val ch = CharArray(2048)
            while (true) {
                val n = r.read(ch)
                if (n < 0) break
                sb.append(ch, 0, n)
                if (sb.length > 20000) break
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    fun exec(cmd: String, timeoutSec: Long = 25): String? {
        if (!available()) return null
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            var out = ""
            val t = Thread { out = readAll(p) }
            t.start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                try { p.destroyForcibly() } catch (e: Exception) {}
                return "[root] timeout $timeoutSec dtk untuk: $cmd"
            }
            t.join(1500)
            val body = out.trim()
            "[root uid=0] $ " + cmd + "\n" + (if (body.isBlank()) "(tanpa output, exit " + p.exitValue() + ")" else body.take(6000))
        } catch (e: Exception) {
            null
        }
    }
}
