package com.senzwh.deepharness

import android.content.Context

// Gerbang flavor (E1.1). Nilai default = mainline.
// Build pure menimpa file ini di SALINAN build (bukan di tree):
// IS_PURE = true, APP_LABEL = "Harness".
object Flavor {
    const val IS_PURE = false
    const val APP_LABEL = "S&Harness"

    // E1.5: nama pemilik untuk pesan darurat (D1/D2). Dibaca dari pref
    // userName (first-run Kenalan, E1.3); kosong berarti pakai fallback.
    // fallbackPure berbeda per pesan (D1 "ini", D2 "Pengguna") — pemanggil
    // yang memilihnya. Di mainline fallback = "Senz" (perilaku lama).
    // CATATAN replacement map (agen flavor): map me-rewrite literal "Senz"
    // di bawah menjadi "Asisten" di salinan pure — cabang itu MATI di pure
    // (IS_PURE const true: pure selalu mengambil fallbackPure / userName),
    // jadi rewrite itu tidak berpengaruh ke perilaku.
    fun ownerNameOr(ctx: Context, fallbackPure: String): String {
        val n = try { ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getString("userName", "")?.trim().orEmpty() } catch (e: Exception) { "" }
        if (n.isNotEmpty()) return n
        return if (IS_PURE) fallbackPure else "Senz"
    }
}
