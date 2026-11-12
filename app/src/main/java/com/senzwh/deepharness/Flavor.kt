package com.senzwh.deepharness

import android.content.Context

// Gerbang flavor (E1.1). Nilai default = mainline.
// Build pure menimpa file ini di SALINAN build (bukan di tree):
// IS_PURE = true, APP_LABEL = "Harness".
object Flavor {
    const val IS_PURE = false
    const val APP_LABEL = "Harness"

    // E1.5: nama pemilik untuk pesan darurat (D1/D2). Dibaca dari pref
    // userName (first-run Kenalan, E1.3); kosong berarti pakai fallback.
    // fallbackPure berbeda per pesan (D1 "ini", D2 "Pengguna") — pemanggil
    // yang memilihnya. Di mainline fallback = "Asisten" (perilaku lama).
    // CATATAN (tree publik): fallback nama pemilik sudah netral ("Asisten").
    // Di salinan build pure, build.py tetap menimpa IS_PURE = true — cabang
    // itu MATI di pure (IS_PURE const true: pure selalu mengambil
    // fallbackPure / userName), jadi nilai di bawah tidak berpengaruh ke pure.
    fun ownerNameOr(ctx: Context, fallbackPure: String): String {
        val n = try { ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getString("userName", "")?.trim().orEmpty() } catch (e: Exception) { "" }
        if (n.isNotEmpty()) return n
        return if (IS_PURE) fallbackPure else "Asisten"
    }
}
