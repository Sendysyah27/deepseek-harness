package com.senzwh.deepharness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Nyalakan lagi layanan harness (pantau + proaktif + overlay) setelah HP restart,
 *  selama user pernah mengaktifkan mode proaktif. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("proactiveOn", true)) return
            val i = Intent(ctx, HarnessService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {}
        // F3 (v4.37.0): bangkitkan serve standby pasca-boot bila user
        // mengaktifkan (D3). Bila sistem menahan start ini, serve tetap
        // kembali saat app pertama dibuka (serveF3Tick) — bukan kegagalan diam.
        try {
            val prefsS = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            if (prefsS.getBoolean("serveStandby", false)) {
                val s = Intent(ctx, ServeStandbyService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s) else ctx.startService(s)
            }
        } catch (e: Exception) {}
    }
}
