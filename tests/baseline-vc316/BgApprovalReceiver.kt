package com.senzwh.deepharness

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Aksi Setujui/Tolak pada notifikasi persetujuan tool berisiko Otak Latar (Tahap C).
 *  vc305 H-K1: SEBELUM resumeApproval, baca status durable. Bila pid
 *  sudah diputuskan / sedang diproses / kedaluwarsa / tidak ada,
 *  render ulang notifikasi jadi "Processing" TANPA tombol (atau
 *  batalkan bila expired) + return TANPA memanggil resumeApproval —
 *  ketukan notifikasi lama sesudah restart proses tidak pernah
 *  mengeksekusi ulang. */
class BgApprovalReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_APPROVE = "com.senzwh.deepharness.BG_APPROVE"
        const val ACTION_REJECT = "com.senzwh.deepharness.BG_REJECT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pid = intent.getStringExtra("pid")
        val notifId = intent.getIntExtra("notifId", BgBrain.APPROVAL_NOTIF_ID)
        // vc305 H-K1: gerbang durable SEBELUM resumeApproval.
        if (pid != null) {
            try {
                val row = ParkedApprovalStoreVc305.get(app).get(pid)
                if (row == null) {
                    try {
                        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(notifId)
                    } catch (e: Exception) {}
                    return
                }
                if (row.status == "EXPIRED") {
                    try {
                        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(notifId)
                    } catch (e: Exception) {}
                    return
                }
                if (row.status != "PENDING") {
                    // Sudah diputuskan / sedang diproses: render ulang
                    // "Processing" tanpa tombol, jangan panggil resume.
                    BgBrain.renderParkedProcessing(app, pid)
                    return
                }
                // PENDING: lanjut ke resumeApproval (klaim atomik di sana).
            } catch (e: Exception) {
                // Bila store tidak bisa dibaca, JANGAN eksekusi buta:
                // batalkan notif + return (fail-closed).
                try {
                    val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(notifId)
                } catch (ignored: Exception) {}
                return
            }
        }
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        } catch (e: Exception) {}
        val approved = intent.action == ACTION_APPROVE
        BgBrain.resumeApproval(app, approved, pid)
    }
}
