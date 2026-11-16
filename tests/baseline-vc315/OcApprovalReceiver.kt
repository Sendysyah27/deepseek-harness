package com.senzwh.deepharness

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Tombol Setujui/Tolak pada notifikasi izin Koordinator OpenCode (Tahap D).
 * Keputusan diteruskan ke OcCoord.resolvePermission — mesin yang sedang
 * memarkir izin akan membalas ke serve; bila mesin tidak menunggu
 * (proses sempat mati), receiver membalas langsung lewat OcCoord.
 */
class OcApprovalReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_APPROVE = "com.senzwh.deepharness.OC_APPROVE"
        const val ACTION_REJECT = "com.senzwh.deepharness.OC_REJECT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val approved = intent.action == ACTION_APPROVE
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(OcCoord.APPROVAL_NOTIF_ID)
        } catch (e: Exception) {}
        OcCoord.resolvePermission(context.applicationContext, approved)
    }
}
