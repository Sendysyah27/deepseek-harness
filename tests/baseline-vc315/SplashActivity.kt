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
    /** vc309 Worker A — tema gelap Splash, pola sama seperti
     *  MainActivity.isDarkTheme(): prefs "themeMode" + night mode OS untuk
     *  mode "sistem". */
    private fun splashIsDarkVc309(): Boolean {
        val mode = try { getSharedPreferences("harness", MODE_PRIVATE).getString("themeMode", "gelap") } catch (e: Exception) { "gelap" }
        return when (mode) {
            "terang" -> false
            "sistem" -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
    }

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
        // vc309 Worker A — bar ikut tema app yang tersimpan (tanpa kilat
        // bar putih/hitam saat cold start). TANPA edge-to-edge: transient
        // 300ms, bitmap hitam FIT_CENTER, bar memakai warna opaque §2 —
        // decorFits(false) tidak mengubah satu piksel pun yang terlihat
        // (lihat AUDIT-COMPAT-VC309-A.md butir 15).
        try { SystemBarsVc309.applySystemBars(window, splashIsDarkVc309()) } catch (e: Exception) {}
        Handler(Looper.getMainLooper()).postDelayed({
            try { startActivity(Intent(this, MainActivity::class.java)) } catch (e: Exception) {}
            finish()
        }, 300)
    }
}
