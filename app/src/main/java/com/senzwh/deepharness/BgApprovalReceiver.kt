package com.senzwh.deepharness

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Aksi Setujui/Tolak pada notifikasi persetujuan tool berisiko Otak Latar (Tahap C). */
class BgApprovalReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_APPROVE = "com.senzwh.deepharness.BG_APPROVE"
        const val ACTION_REJECT = "com.senzwh.deepharness.BG_REJECT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // vc242 K-03: batalkan notifikasi parkir ini (per ID) + teruskan
        // pid-nya agar resumeApproval menyelesaikan parkir yang tepat.
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(intent.getIntExtra("notifId", BgBrain.APPROVAL_NOTIF_ID))
        } catch (e: Exception) {}
        val approved = intent.action == ACTION_APPROVE
        BgBrain.resumeApproval(context.applicationContext, approved, intent.getStringExtra("pid"))
    }
}
