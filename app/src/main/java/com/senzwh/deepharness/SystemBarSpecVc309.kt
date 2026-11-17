package com.senzwh.deepharness

import harness.core.RustCore

// SystemBarSpecVc309.kt — pemetaan MURNI tema -> system bars (vc309 Worker A).
//
// File ini SENGAJA murni Kotlin tanpa import Android (pola ChatThemeVc306 /
// WalkBudget vc307) supaya pemetaannya bisa diuji di JVM: warna + flag ikon
// per tema, tanpa perlu emulator/HP.
//
// Sumber nilai (laporan REPORT-FULL-THEME-SYSTEM-BARS.md §2 — TIDAK ADA hex
// baru, semua token sudah ada di palet Mist):
//   status bar = atas gradasi halaman: darkBgTop #1D222C / lightBgTop #EBF1FE
//   nav bar    = warna tab bar (th.card): darkSurface #1C1F28 / lightCard #FBFCFF
//   ikon: tema gelap -> ikon TERANG (appearance flag MATI);
//         tema cerah -> ikon GELAP (appearance flag NYALA).
object SystemBarSpecVc309 {
    /**
     * Saklar A/B slice 4 (vc317): bila core Rust tersedia, pemetaan
     * jalan di Rust; bila tidak, fallback ke implementasi Kotlin lama
     * di bawah (specKotlin — JANGAN dihapus).
     */
    var rustSystemBarSpec: Boolean = true

    /** Hasil pemetaan: warna status bar, warna nav bar, dan apakah ikon
     *  system bar harus terang (true = tema gelap, flag appearance MATI). */
    data class Spec(val statusColor: Int, val navColor: Int, val lightIcons: Boolean)

    fun spec(isDark: Boolean): Spec {
        rustSpec(isDark)?.let { return it }
        return specKotlin(isDark)
    }

    // ---- Seam Rust (vc317) ----

    private fun rustSpec(isDark: Boolean): Spec? {
        if (!rustSystemBarSpec || !RustCore.available) return null
        return try {
            val json = RustCore.systemBarSpecJson(isDark)
            if (!json.contains("\"ok\":true")) return null
            fun i(k: String): Int? = Regex("\"" + k + "\":(-?\\d+)").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
            Spec(
                i("statusColor") ?: return null,
                i("navColor") ?: return null,
                json.contains("\"lightIcons\":true")
            )
        } catch (e: Throwable) {
            rustSystemBarSpec = false
            null
        }
    }

    /** Implementasi Kotlin lama (fallback) — DIPERTAHANKAN. */
    private fun specKotlin(isDark: Boolean): Spec =
        if (isDark) Spec(ChatThemeVc306.darkBgTop, ChatThemeVc306.darkSurface, true)
        else Spec(ChatThemeVc306.lightBgTop, ChatThemeVc306.lightCard, false)
}
