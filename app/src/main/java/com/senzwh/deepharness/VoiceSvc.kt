package com.senzwh.deepharness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/** Layanan asisten default Android: saat user memanggil asisten
 *  (tahan tombol home / power / gesture asisten), yang dibuka adalah
 *  chat DeepSeek Harness. Daftarkan lewat Pengaturan > Aplikasi >
 *  Aplikasi default > Asisten digital. */
class HarnessVoiceService : VoiceInteractionService() {
    override fun onReady() { super.onReady() }
}

class HarnessVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return object : VoiceInteractionSession(this) {
            override fun onShow(args: Bundle?, showFlags: Int) {
                try {
                    try {
                        val f = Intent(context, FloatingAgentActivity::class.java)
                        f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(f)
                    } catch (e2: Exception) {
                        // v4.10.1: panel gagal dari sesi asisten TIDAK lagi membuka
                        // app penuh — tawarkan panel lewat notifikasi.
                        try {
                            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            try { nm.createNotificationChannel(NotificationChannel("harness", "Harness aktif", NotificationManager.IMPORTANCE_LOW)) } catch (e3: Exception) {}
                            val pi = PendingIntent.getActivity(context, 74, Intent(context, FloatingAgentActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            nm.notify(7109, Notification.Builder(context, "harness").setContentTitle("Panel asisten gagal dibuka langsung").setContentText("Ketuk untuk membuka panel asisten.").setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(pi).setAutoCancel(true).build())
                        } catch (e3: Exception) {}
                    }
                } catch (e: Exception) {}
                try { finish() } catch (e: Exception) {}
            }
        }
    }
}

/** RecognitionService minimal agar komponen voice-interaction valid. */
class HarnessRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
