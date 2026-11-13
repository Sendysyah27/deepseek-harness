package com.senzwh.deepharness

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

    /** Hasil pemetaan: warna status bar, warna nav bar, dan apakah ikon
     *  system bar harus terang (true = tema gelap, flag appearance MATI). */
    data class Spec(val statusColor: Int, val navColor: Int, val lightIcons: Boolean)

    fun spec(isDark: Boolean): Spec =
        if (isDark) Spec(ChatThemeVc306.darkBgTop, ChatThemeVc306.darkSurface, true)
        else Spec(ChatThemeVc306.lightBgTop, ChatThemeVc306.lightCard, false)
}
