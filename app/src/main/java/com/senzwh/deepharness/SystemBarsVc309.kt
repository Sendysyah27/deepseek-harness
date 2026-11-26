package com.senzwh.deepharness

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController

// SystemBarsVc309.kt — penerapan system bars + edge-to-edge (vc309 Worker A).
//
// API framework SAJA (tanpa androidx — build.py:230 tidak punya androidx):
//   warna        : Window.statusBarColor / navigationBarColor (API 21+)
//   appearance   : WindowInsetsController.setSystemBarsAppearance (API 30+),
//                  fallback flag SYSTEM_UI_FLAG_LIGHT_STATUS_BAR (23+) /
//                  LIGHT_NAVIGATION_BAR (27+) untuk API 26-29
//   edge-to-edge : Window.setDecorFitsSystemWindows(false) (API 30+),
//                  fallback flag LAYOUT_STABLE|LAYOUT_FULLSCREEN|
//                  LAYOUT_HIDE_NAVIGATION untuk API 26-29
//   inset        : WindowInsets.Type.systemBars()/ime() (API 30+),
//                  fallback systemWindowInset* (deprecated) untuk API 26-29
//
// Semua fungsi dibungkus try/catch: gagal apply (mis. perilaku MIUI yang
// tidak terdokumentasi) tidak pernah crash app — konten tetap tampil dengan
// bar default sistem.
object SystemBarsVc309 {

    /** Terapkan warna + appearance ikon system bars mengikuti tema app.
     *  Aman dipanggil berulang (onCreate + onResume); idempotent.
     *  @Suppress DEPRECATION: cabang API 26-29 SENGAJA memakai flag
     *  SYSTEM_UI_FLAG_* yang deprecated di API 30+ (penggantinya,
     *  WindowInsetsController, hanya ada di API 30+). */
    @Suppress("DEPRECATION")
    fun applySystemBars(window: Window, isDark: Boolean) {
        try {
            val spec = SystemBarSpecVc309.spec(isDark)
            window.statusBarColor = spec.statusColor
            window.navigationBarColor = spec.navColor
            if (Build.VERSION.SDK_INT >= 30) {
                val controller = window.insetsController ?: return
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                // Ikon terang (tema gelap) -> flag MATI (value 0);
                // ikon gelap (tema cerah) -> flag NYALA (value mask).
                controller.setSystemBarsAppearance(if (spec.lightIcons) 0 else mask, mask)
            } else {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                if (Build.VERSION.SDK_INT >= 23) {
                    flags = if (spec.lightIcons) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                if (Build.VERSION.SDK_INT >= 27) {
                    flags = if (spec.lightIcons) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
        } catch (e: Exception) {
            // Gagal apply bar tidak boleh crash app.
        }
    }

    /** Aktifkan edge-to-edge penuh di root view: konten menggambar di bawah
     *  system bars, dan listener mengembalikan padding yang benar supaya
     *  konten tidak tertutup bar/keyboard.
     *
     *  Padding: kiri/kanan/atas = system bars; bawah = max(nav bar, IME).
     *  max() menutupi kedua perilaku sistem terhadap adjustResize: bila
     *  window sudah di-resize oleh adjustResize, ime.bottom = 0 (tidak ada
     *  hitung ganda); bila tidak di-resize, ime.bottom = tinggi keyboard
     *  sehingga composer terangkat di atas keyboard. Kriteria: keyboard
     *  TIDAK BOLEH menutupi composer — lihat AUDIT-COMPAT-VC309-A.md butir 9
     *  (risiko hitung ganda harus diverifikasi Tes T7 di HP).
     *
     *  Insets TIDAK di-consume (listener mengembalikan `insets` apa adanya)
     *  supaya turunan (ScrollView, WebView) tetap menerima inset yang sama.
     *  Root diasumsikan tanpa padding awal — benar untuk root MainActivity
     *  (buildClassicUi, tanpa setPadding) maupun root activity lain. */
    fun installEdgeToEdge(window: Window, root: View) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                val decor = window.decorView
                @Suppress("DEPRECATION")
                decor.systemUiVisibility = decor.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
            root.setOnApplyWindowInsetsListener { v, insets ->
                try {
                    val left: Int
                    val top: Int
                    val right: Int
                    val bottom: Int
                    if (Build.VERSION.SDK_INT >= 30) {
                        val sys = insets.getInsets(WindowInsets.Type.systemBars())
                        val ime = insets.getInsets(WindowInsets.Type.ime())
                        left = sys.left
                        top = sys.top
                        right = sys.right
                        bottom = maxOf(sys.bottom, ime.bottom)
                    } else {
                        @Suppress("DEPRECATION")
                        left = insets.systemWindowInsetLeft
                        @Suppress("DEPRECATION")
                        top = insets.systemWindowInsetTop
                        @Suppress("DEPRECATION")
                        right = insets.systemWindowInsetRight
                        @Suppress("DEPRECATION")
                        bottom = insets.systemWindowInsetBottom
                    }
                    v.setPadding(left, top, right, bottom)
                } catch (e: Exception) {
                    // Gagal hitung padding: biarkan padding lama.
                }
                insets
            }
            root.requestApplyInsets()
        } catch (e: Exception) {
            // Gagal aktifkan edge-to-edge: app tetap jalan non-edge-to-edge.
        }
    }
}
