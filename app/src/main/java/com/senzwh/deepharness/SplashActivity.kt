package com.senzwh.deepharness

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ImageView

/**
 * Splash vc215: gambar full-screen sh_splash (wordmark + tagline sudah di
 * dalam gambar, jadi tidak ada lagi ikon/teks/progress terpisah agar tidak
 * dobel). Alur dan durasi dipertahankan: 300ms lalu ke MainActivity.
 */
class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ImageView(this).apply {
            setImageResource(R.drawable.sh_splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(0, 0, 0))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(view)
        Handler(Looper.getMainLooper()).postDelayed({
            try { startActivity(Intent(this, MainActivity::class.java)) } catch (e: Exception) {}
            finish()
        }, 300)
    }
}
