package com.senzwh.deepharness.priv

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// vc170 — Penerima balasan kode pairing dari notifikasi. Pairing berjalan
// di thread (blocking: NSD + TLS + SPAKE2), hasil ditulis ke notifikasi
// yang sama. Kode tidak dicatat ke log/prefs; goAsync menjaga proses
// tetap hidup sampai pairing selesai.
class PrivPairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appCtx = context.applicationContext
        Thread {
            try {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(PrivPairNotif.KEY_CODE)?.toString()?.trim() ?: ""
                if (code.length != 6 || !code.all { it.isDigit() }) {
                    PrivPairNotif.updateResult(
                        appCtx,
                        "Kode harus 6 digit — tampilkan dialog kode di Wireless debugging, lalu balas lagi.",
                        true
                    )
                    return@Thread
                }
                PrivPairNotif.updateResult(appCtx, "Memasangkan… jangan tutup dialog kode.", false, true)
                val (nsdHost, port) = AdbDiscovery.findService(appCtx, AdbDiscovery.TYPE_PAIRING, 9000)
                if (port <= 0) {
                    PrivPairNotif.updateResult(
                        appCtx,
                        "Port pairing tidak ketemu — pastikan dialog kode sedang TAMPIL di Wireless debugging, lalu balas lagi.",
                        true
                    )
                    return@Thread
                }
                val r = AdbPairing.pairAuto(appCtx, port, code, nsdHost)
                val ok = r.startsWith("PAIRING BERHASIL")
                PrivPairNotif.updateResult(appCtx, r, !ok)
            } catch (e: Exception) {
                try {
                    PrivPairNotif.updateResult(
                        appCtx,
                        "Pairing gagal: ${e.javaClass.simpleName}. Balas lagi dengan kode baru.",
                        true
                    )
                } catch (_: Exception) {}
            } finally {
                pending.finish()
            }
        }.start()
    }
}
