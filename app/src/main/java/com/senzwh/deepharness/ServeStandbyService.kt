package com.senzwh.deepharness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** F3 (v4.37.0): foreground service standby serve :4197 (disetujui user, D3).
 *  Cara kerja: service foreground menjaga proses app tetap hidup — selama
 *  proses hidup, sesi proot + serve ikut hidup. Tiap 5 menit service
 *  memeriksa serve (health + PID) dan membangkitkannya bila mati.
 *  Notifikasi persisten "S&Harness standby" adalah harga yang disetujui
 *  user demi auto-start; mematikannya lewat Setup 53 menghentikan service.
 * TIDAK PERNAH menyentuh serve :4096/:4097. Watchdog ini ringan (satu
 *  F3-Tailscale (v4.48.4): tick yang sama juga menjaga tailscaled proot
 *  (via TailscaleHealth: launcher /root/ts/start-tailscaled.sh bila ada,
 *  fallback perintah persis /root/ts/tailscaled bila tidak ada) + serve
 *  :4197 tailnet-only (tanpa funnel, tanpa auto-login, tanpa hardcode IP).
 *  NeedsLogin = notify-only, bukan revive-loop.
 *  exec proot per 5 menit) dan tidak berjalan saat service dimatikan. */
class ServeStandbyService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val watch = object : Runnable {
        override fun run() {
            if (!running) return
            Thread {
                try {
                    // vc258 Fase 2: watchdog endpoint — pastikan :4198
                    // hidup selama service hidup (murah bila sudah jalan:
                    // hanya cek flag; start hanya bila mati).
                    // vc260 N-m13: nilai kembalian ensureStarted TIDAK BOLEH lagi dibuang diam-diam — kegagalan start dicatat (Logcat + EventDb) + diberitahukan ke user (notifikasi, throttle 1 jam). Bila jalan, tick juga memanaskan token + SQLite (warm-up berkala N-M1).
                    try {
                        val epMsg = WorkerEndpoint.ensureStarted(this@ServeStandbyService)
                        if (epMsg.contains("gagal start")) {
                            try { android.util.Log.e("WorkerEp", "tick endpoint gagal start: $epMsg") } catch (ignored: Exception) {}
                            try { EventDb.get(this@ServeStandbyService).add("worker-endpoint", this@ServeStandbyService.packageName, "Endpoint :4198 gagal start dari tick: $epMsg") } catch (ignored: Exception) {}
                            this@ServeStandbyService.notifyEndpointFailure(epMsg)
                        } else {
                            try { WorkerEndpoint.warmUp(this@ServeStandbyService) } catch (ignored: Exception) {}
                        }
                    } catch (e: Exception) {}
                    // vc281 Fase 3 (D4): janitor worker tiap tick 5 menit
                    // (sweep kuota + umur + rotasi log; notifikasi hanya
                    // bila ada yang dihapus/dirotasi). Gagal sweep tidak
                    // menggagalkan tick.
                    try { WorkerJanitor.sweepAll(this@ServeStandbyService) } catch (e: Exception) {}
                    // F3-Tailscale (v4.48.4): satu alur per tick, thread sama,
                    // urutan tailscaled -> opencode -> serve (serve-ensure di
                    // dalam ensureTailscale; di sini dipakai pecahannya agar
                    // tidak ada pemanggilan ganda yang balapan).
                    val tsDaemon = TailscaleHealth.ensureDaemon(this@ServeStandbyService)
                    val st = ServeHealth.ensureAlive(this@ServeStandbyService)
                    var serveTxt = ""
                    if (tsDaemon.kind == TailscaleHealth.Kind.RUNNING) {
                        serveTxt = if (TailscaleHealth.ensureServe(this@ServeStandbyService)) " • serve OK" else " • serve hilang"
                    }
                    val tsFinal = TailscaleHealth.check(this@ServeStandbyService)
                    val noted = if (tsFinal.kind == TailscaleHealth.Kind.RUNNING) tsFinal.text + serveTxt else tsFinal.text
                    TailscaleHealth.noteStatus(this@ServeStandbyService, noted)
                    val servePart = if (st.alive) "Serve :4197 hidup • PID ${st.pid}" else "Serve :4197 tidur — akan dicoba lagi 5 mnt"
                    val tsPart = when (tsFinal.kind) {
                        TailscaleHealth.Kind.RUNNING -> "Tailscale Running" + (tsFinal.ip?.let { " • IP $it" } ?: "") + serveTxt
                        TailscaleHealth.Kind.NEEDS_LOGIN -> "Tailscale perlu login ulang"
                        TailscaleHealth.Kind.DEAD -> "Tailscale mati"
                        TailscaleHealth.Kind.NO_PROOT -> "Tailscale: proot belum terpasang"
                        TailscaleHealth.Kind.UNKNOWN -> "Tailscale tidak diketahui"
                    }
                    updateNotification("$servePart • $tsPart")
                } catch (e: Exception) {
                }
            }.start()
            handler.postDelayed(this, 300_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("serve-standby", "S&Harness Standby", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification("S&Harness standby — memeriksa serve :4197…"))
        if (!running) {
            running = true
            handler.post(watch)
            // vc258 Fase 2: endpoint worker :4198 hidup selama service
            // standby hidup (thread sendiri — syncTokenFile memanggil
            // proot, JANGAN di main thread).
            // vc260 N-m13: seperti tick — kegagalan start saat service nyala dicatat + diberitahukan, bukan dibuang diam-diam.
            Thread {
                try {
                    val epMsg = WorkerEndpoint.ensureStarted(this@ServeStandbyService)
                    if (epMsg.contains("gagal start")) {
                        try { android.util.Log.e("WorkerEp", "onStart endpoint gagal start: $epMsg") } catch (ignored: Exception) {}
                        try { EventDb.get(this@ServeStandbyService).add("worker-endpoint", this@ServeStandbyService.packageName, "Endpoint :4198 gagal start saat service nyala: $epMsg") } catch (ignored: Exception) {}
                        this@ServeStandbyService.notifyEndpointFailure(epMsg)
                    }
                } catch (e: Exception) {}
            }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(watch)
        // vc258 Fase 2: service mati -> endpoint ikut mati (jujur: panel
        // menampilkan MATI, bisa mulai ulang dari panel).
        try { WorkerEndpoint.stop() } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // vc260 N-m13: notifikasi kegagalan start endpoint (throttle 1 jam — sama seperti notif serve-mati 7103). ID 4198 belum dipakai notifikasi lain (grep: hanya nomor port).
    private fun notifyEndpointFailure(msg: String) {
        try {
            val p = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val last = p.getLong("epFailNotifTs", 0)
            if (System.currentTimeMillis() - last < 3600_000) return
            p.edit().putLong("epFailNotifTs", System.currentTimeMillis()).apply()
            val open = PendingIntent.getActivity(this, 4198, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(4198, Notification.Builder(this, "serve-standby")
                .setContentTitle("⚠️ Endpoint worker :4198 gagal start")
                .setContentText(msg.take(120))
                .setStyle(Notification.BigTextStyle().bigText(msg.take(500)))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "serve-standby")
            .setContentTitle("S&Harness standby")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotification(text))
        } catch (e: Exception) {
        }
    }
}
