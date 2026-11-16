package com.senzwh.deepharness

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * v4.10.0 — Aktivitas transparan sekali-lewat untuk menjalankan dialog izin
 * MediaProjection bawaan Android (satu-satunya jalan resmi menangkap layar).
 * Dipanggil dari popup persetujuan ScreenWatch; hasil diteruskan ke
 * ScreenWatch.onConsentGranted/onConsentDenied lalu aktivitas selesai.
 */
class CaptureConsentActivity : Activity() {

    companion object { private const val REQ = 9021 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            try {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
            } catch (e: Exception) {
                ScreenWatch.noteConsent(this, false)
                ScreenWatch.onConsentDenied()
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            ScreenWatch.onConsentGranted(this, resultCode, data)
        } else {
            ScreenWatch.noteConsent(this, false)
            ScreenWatch.onConsentDenied()
        }
        finish()
    }
}
