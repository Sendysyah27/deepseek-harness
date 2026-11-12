package com.senzwh.deepharness.priv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Menerima binder server privileged dari proses app_process (PrivStarter).
 * Exported karena pengirimnya proses lain (uid shell), TAPI binder hanya
 * diterima bila token di dalam Bundle cocok dengan token sesi aplikasi
 * (lihat PrivServer.attach) — broadcast palsu tanpa token ditolak.
 */
class PrivBinderReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        val data = intent.getBundleExtra("data") ?: return
        val b = data.getBinder("binder") ?: return
        val t = data.getString("token") ?: ""
        PrivServer.attach(b, t, ctx.applicationContext)
    }
}
