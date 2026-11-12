package com.senzwh.deepharness.priv

import android.os.Binder
import android.os.Parcel
import android.system.Os
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Layanan Binder server privileged Mode ADB (S1, v3.4.0).
 *
 * Berjalan di proses app_process ber-uid SHELL (dinyalakan PrivStarter lewat
 * start.sh + adb), BUKAN di proses aplikasi. Mengekspos transaksi mentah:
 *   kode 1 PING -> "OK uid=<uid> pid=<pid> v1"
 *   kode 2 EXEC -> baca String perintah sh, balas "exit=<n>\n<output>"
 * (output digabung stderr, dibatasi 9000 char + penanda sisa char,
 * timeout 30 detik — vc301 SF-D4: cap lama ±6000 char tanpa penanda
 * kini 9000, selaras cap Tools.execute, dan ekor yang dibuang selalu
 * dilaporkan jumlahnya; timeout 30 detik TIDAK berubah).
 * Pola meniru ShizukuService: satu Binder, descriptor milik sendiri,
 * kode transaksi kustom — tanpa AIDL.
 */
class PrivService : Binder() {

    companion object {
        const val DESCRIPTOR = "com.senzwh.deepharness.priv.IPrivService"
        const val TX_PING = 1
        const val TX_EXEC = 2
        // vc301 SF-D4: cap output exec server priv — selaras Tools.execute (9000).
        const val PRIV_OUTPUT_CAP_VC301 = 9000
    }

    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (reply == null) return super.onTransact(code, data, reply, flags)
        return try {
            when (code) {
                TX_PING -> {
                    data.enforceInterface(DESCRIPTOR)
                    reply.writeNoException()
                    reply.writeString("OK uid=" + Os.getuid() + " pid=" + Os.getpid() + " v1")
                    true
                }
                TX_EXEC -> {
                    data.enforceInterface(DESCRIPTOR)
                    val cmd = data.readString() ?: ""
                    reply.writeNoException()
                    reply.writeString(exec(cmd))
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        } catch (e: Exception) {
            try { reply.writeException(e) } catch (x: Exception) {}
            true
        }
    }

    private fun exec(cmd: String): String {
        return try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val sb = StringBuilder()
            // vc301 SF-D4: kuras + hitung char yang dibuang di atas cap,
            // supaya penanda menyebut sisa persis (dulu: buang diam-diam).
            val droppedVc301 = IntArray(1)
            val t = Thread {
                try {
                    val r = BufferedReader(InputStreamReader(p.inputStream))
                    val buf = CharArray(2048)
                    while (true) {
                        val n = r.read(buf)
                        if (n <= 0) break
                        synchronized(sb) {
                            val room = PRIV_OUTPUT_CAP_VC301 - sb.length
                            if (room > 0) sb.append(buf, 0, minOf(n, room))
                            if (n > room) droppedVc301[0] += n - maxOf(room, 0)
                        }
                    }
                } catch (e: Exception) {}
            }
            t.isDaemon = true
            t.start()
            val done = p.waitFor(30, TimeUnit.SECONDS)
            // vc301: hitung sisa SESUDAH reader selesai (lihat execShell).
            fun resultVc301(exit: String): String {
                val dropped = synchronized(sb) { droppedVc301[0] }
                val mark = if (dropped > 0) "\n…(output dipotong di $PRIV_OUTPUT_CAP_VC301 char oleh server priv, sisa $dropped char tidak ditampilkan)…" else ""
                return exit + "\n" + synchronized(sb) { sb.toString() } + mark
            }
            if (!done) {
                try { p.destroyForcibly() } catch (e: Exception) {}
                resultVc301("exit=timeout(30dtk)")
            } else {
                Thread.sleep(60)
                resultVc301("exit=" + p.exitValue())
            }
        } catch (e: Exception) {
            "exit=error\n" + (e.message ?: "gagal menjalankan")
        }
    }
}
