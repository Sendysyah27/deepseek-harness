package com.senzwh.deepharness

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.FileOutputStream

/**
 * v4.10.0 — Layanan latar PANTAU LAYAR (lihat ScreenWatch untuk alur izin).
 * Foreground service bertipe mediaProjection: memegang sesi MediaProjection
 * dari persetujuan user, membuat VirtualDisplay kecil (1/3 resolusi), dan
 * membandingkan bingkai tiap ~1,2 detik. Perubahan >3% sampel dicatat lewat
 * ScreenWatch.recordChange + potret JPEG terakhir disimpan (maks tiap 4 dtk).
 * Batas keras 20 menit per sesi.
 */
class ScreenWatchService : Service() {

    companion object {
        const val ACTION_STOP = "com.senzwh.deepharness.WATCH_STOP"
        private const val NOTIF_ID = 7107
        private const val CHANNEL = "harness_watch"
        private const val MAX_MS = 20 * 60 * 1000L
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private var prevSamples: IntArray? = null
    private var lastFrameSaveMs = 0L
    private var deadlineMs = 0L
    private var reasonText: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Pantau Layar", NotificationManager.IMPORTANCE_LOW))
        } catch (e: Exception) {}
    }

    private fun notif(): Notification {
        val stopIntent = Intent(this, ScreenWatchService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 71, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 72, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle("👁 Pantau Layar aktif")
            .setContentText(reasonText.ifBlank { "Memantau perubahan layar (maks 20 menit)" })
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openPi)
            .setOngoing(true)
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Berhenti", stopPi)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWatch("berhenti manual")
            return START_NOT_STICKY
        }
        val rc = intent?.getIntExtra("rc", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("capData") as? Intent
        if (rc != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        reasonText = getSharedPreferences("harness", Context.MODE_PRIVATE).getString("watchReason", "") ?: ""
        try { startForeground(NOTIF_ID, notif()) } catch (e: Exception) {}
        startWatch(rc, data)
        return START_STICKY
    }

    private fun startWatch(rc: Int, data: Intent) {
        try {
            val metrics = resources.displayMetrics
            val w = (metrics.widthPixels / 3).coerceAtLeast(240)
            val h = (metrics.heightPixels / 3).coerceAtLeast(240)
            val dpi = (metrics.densityDpi / 3).coerceAtLeast(72)
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(rc, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopWatch("sesi dihentikan sistem") }
            }, Handler(Looper.getMainLooper()))
            display = projection?.createVirtualDisplay(
                "harness-watch", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, null
            )
            running = true
            deadlineMs = System.currentTimeMillis() + MAX_MS
            prevSamples = null
            ScreenWatch.onWatchStarted(this, reasonText)
            worker = Thread { loop(w, h) }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            ScreenWatch.onWatchStopped(this, "gagal mulai: ${e.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun loop(w: Int, h: Int) {
        while (running) {
            try {
                if (System.currentTimeMillis() > deadlineMs) { stopWatch("batas 20 menit tercapai"); break }
                ScreenWatch.tick(this)
                val img = try { reader?.acquireLatestImage() } catch (e: Exception) { null }
                if (img != null) {
                    try {
                        val samples = sampleHash(img, w, h)
                        val prev = prevSamples
                        if (prev != null && samples.size == prev.size) {
                            var changed = 0
                            for (i in samples.indices) {
                                val d = samples[i] - prev[i]
                                if (d > 14 || d < -14) changed++
                            }
                            val frac = changed.toDouble() / samples.size
                            if (frac > 0.03) {
                                ScreenWatch.recordChange(this, (frac * 100).toInt())
                                val now = System.currentTimeMillis()
                                if (now - lastFrameSaveMs > 4000) {
                                    lastFrameSaveMs = now
                                    saveFrame(img, w, h)
                                }
                            }
                        }
                        prevSamples = samples
                    } catch (e: Exception) {}
                    try { img.close() } catch (e: Exception) {}
                }
                Thread.sleep(1200)
            } catch (e: Exception) {
                try { Thread.sleep(1200) } catch (ie: Exception) {}
            }
        }
    }

    /** Sidik jari bingkai: luminansi 40x60 titik sampel dari buffer gambar. */
    private fun sampleHash(img: android.media.Image, w: Int, h: Int): IntArray {
        val plane = img.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val cols = 40
        val rows = 60
        val out = IntArray(cols * rows)
        var idx = 0
        for (gy in 0 until rows) {
            val y = (gy * h / rows).coerceAtMost(h - 1)
            for (gx in 0 until cols) {
                val x = (gx * w / cols).coerceAtMost(w - 1)
                val off = y * rowStride + x * pixelStride
                val r = if (off + 2 < buffer.capacity()) buffer.get(off).toInt() and 0xFF else 0
                val g = if (off + 2 < buffer.capacity()) buffer.get(off + 1).toInt() and 0xFF else 0
                val b = if (off + 2 < buffer.capacity()) buffer.get(off + 2).toInt() and 0xFF else 0
                out[idx++] = (r * 30 + g * 59 + b * 11) / 100
            }
        }
        return out
    }

    private fun saveFrame(img: android.media.Image, w: Int, h: Int) {
        try {
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            full.copyPixelsFromBuffer(buffer)
            // vc246 GELOMBANG 5 (TL-m2): clamp koordinat crop ke
            // ukuran bitmap (dulu tanpa clamp — koordinat di luar layar
            // melempar exception). Tidak ada tool crop mandiri di tree
            // ini; satu-satunya situs crop adalah frame pantau ini.
            val cropped = Bitmap.createBitmap(full, 0, 0, minOf(w, full.width), minOf(h, full.height))
            val scale = if (w > 540) 540f / w else 1f
            val outBmp = if (scale < 1f) Bitmap.createScaledBitmap(cropped, (w * scale).toInt(), (h * scale).toInt(), true) else cropped
            FileOutputStream(ScreenWatch.frameFile(this)).use { fos ->
                outBmp.compress(Bitmap.CompressFormat.JPEG, 72, fos)
            }
            if (outBmp !== cropped) outBmp.recycle()
            cropped.recycle()
            full.recycle()
        } catch (e: Exception) {}
    }

    private fun stopWatch(why: String) {
        if (!running && projection == null) {
            try { stopForeground(true) } catch (e: Exception) {}
            stopSelf()
            return
        }
        running = false
        try { worker?.interrupt() } catch (e: Exception) {}
        try { display?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        display = null; reader = null; projection = null
        ScreenWatch.onWatchStopped(this, why)
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        } catch (e: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try { display?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        super.onDestroy()
    }
}
