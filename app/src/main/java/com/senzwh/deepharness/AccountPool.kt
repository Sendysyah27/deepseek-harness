package com.senzwh.deepharness

import android.content.Context

/** v4.7.0 — Pool akun DeepSeek: 5 slot sesi tersimpan (token+cookie di
 *  SecureStore/Keystore) + slot aktif. Slot 0 = sesi WebView live (bawaan).
 *  Dipakai DsApiClient untuk memilih token & failover saat rate-limit,
 *  dan nanti oleh tim multi-agen (satu slot per agen paralel). */
object AccountPool {
    /** vc298 BAGIAN 1 (1A): reviewer konteks-nol WAJIB memakai slot
     *  Browser 5 — JANGAN fallback ke slot lain. Latar: reviewer lama
     *  memakai slot terisi pertama yang bukan slot giliran + sesi server
     *  ephemeral baru tiap review, sehingga puluhan chat "Review klaim"
     *  menumpuk di Browser 1 dan akun itu kena SUSPEND DeepSeek 12 Nov
     *  2026. Slot 5 = akun Browser 5 (login ulang sendiri oleh user di
     *  HP, 11 Nov — bukan bagian kode ini). Nilai = ReviewerVc298.SLOT
     *  (satu sumber kebenaran; logika murni + tes di sana). */
    const val REVIEWER_SLOT: Int = ReviewerVc298.SLOT

    /** Slot reviewer (Browser 5) bila login, null bila slot 5 kosong /
     *  tidak ada auth. Pemanggil WAJIB melewati reviewer dengan penanda
     *  jujur bila null — JANGAN fallback diam-diam ke slot lain. */
    fun reviewerSlot(ctx: Context): Int? {
        return try { ReviewerVc298.pickReviewerSlot(filledSlots(ctx)) } catch (e: Exception) { null }
    }
    /** H1 (4.48.8.1): kill-switch failover antar slot — NONAKTIF sampai
     *  SlotManager dispek ulang. failover() mengembalikan false tanpa
     *  mengubah apa pun; chat() melempar error asli + akhiran eksplisit.
     *  Uji Mandiri 4.48.9 memeriksa konstanta ini + memanggil failover()
     *  dan menegaskan false + dsActiveSlot tak berubah. */
    const val FAILOVER_ENABLED = false
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    fun activeSlot(ctx: Context): Int = try { prefs(ctx).getInt("dsActiveSlot", 0) } catch (e: Exception) { 0 }

    fun setActive(ctx: Context, slot: Int) {
        try { prefs(ctx).edit().putInt("dsActiveSlot", slot).apply() } catch (e: Exception) {}
    }

    fun filledSlots(ctx: Context): List<Int> = (1..5).filter { slot ->
        try { !SecureStore.get(ctx, "dsSlot${slot}_token").isNullOrBlank() } catch (e: Exception) { false }
    }

    /** Token+cookie slot aktif; null bila slot 0 (live) atau slot kosong. */
    fun resolve(ctx: Context): Pair<String, String>? {
        val s = activeSlot(ctx)
        if (s !in 1..5) return null
        return try {
            val t = SecureStore.get(ctx, "dsSlot${s}_token")
            if (t.isNullOrBlank()) null else t to (SecureStore.get(ctx, "dsSlot${s}_cookie") ?: "")
        } catch (e: Exception) { null }
    }

    fun saveSlot(ctx: Context, slot: Int, token: String, cookie: String) {
        SecureStore.put(ctx, "dsSlot${slot}_token", token)
        SecureStore.put(ctx, "dsSlot${slot}_cookie", cookie)
    }

    /** Token+cookie satu slot tertentu (untuk jalur tim paralel). */
    fun slotAuth(ctx: Context, slot: Int): Pair<String, String>? {
        if (slot !in 1..5) return null
        return try {
            val t = SecureStore.get(ctx, "dsSlot${slot}_token")
            if (t.isNullOrBlank()) null else t to (SecureStore.get(ctx, "dsSlot${slot}_cookie") ?: "")
        } catch (e: Exception) { null }
    }

    /** v4.46.0 (F2) — Pref pilihan slot chat: 0 = otomatis (slot login
     *  pertama yang bebas), 1–5 = slot tetap. BERBEDA dari dsActiveSlot
     *  (0 = WebView live, dipakai jalur utama lama) — jangan disamakan. */
    fun slotPref(ctx: Context): Int = try { prefs(ctx).getInt("dsSlot", 0) } catch (e: Exception) { 0 }

    fun setSlotPref(ctx: Context, slot: Int) {
        try { prefs(ctx).edit().putInt("dsSlot", slot.coerceIn(0, 5)).apply() } catch (e: Exception) {}
    }

    /** Slot efektif untuk chat: pref tetap bila terisi; 0 = slot terisi
     *  pertama; null bila pref tetap kosong ATAU tidak ada slot login. */
    fun resolveSlot(ctx: Context): Int? {
        val pref = slotPref(ctx)
        if (pref in 1..5) return if (slotAuth(ctx, pref) != null) pref else null
        return filledSlots(ctx).firstOrNull()
    }

    /** Hapus satu slot pool (tombol Hapus di kartu 4 Browser). */
    fun clearSlot(ctx: Context, slot: Int) {
        if (slot !in 1..5) return
        try {
            SecureStore.delete(ctx, "dsSlot${slot}_token")
            SecureStore.delete(ctx, "dsSlot${slot}_cookie")
        } catch (e: Exception) {}
    }

    /** Pindah ke slot terisi lain (dipakai saat slot aktif kena rate-limit).
     *  v4.10.2: slot yang akunnya ditandai suspend DILEWATI. */
    fun failover(ctx: Context): Boolean {
        if (!FAILOVER_ENABLED) return false
        val cur = activeSlot(ctx)
        val next = filledSlots(ctx).firstOrNull { s ->
            s != cur && try {
                val a = slotAuth(ctx, s)
                a == null || !isDead(ctx, tokenFp(a.first))
            } catch (e: Exception) { true }
        } ?: return false
        setActive(ctx, next)
        return true
    }

    /** v4.10.2: sidik jari token (SHA-256 ringkas) — identitas sesi akun
     *  TANPA menyimpan token itu sendiri. Token baru (login ulang) = sidik
     *  jari baru = mulai bersih. */
    fun tokenFp(token: String): String {
        return try {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            d.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (e: Exception) { token.hashCode().toString(16) }
    }

    /** v4.10.2: tandai akun (per sidik jari token) ter-suspend; tanda berlaku
     *  4 hari lalu kedaluwarsa sendiri (masa suspend DeepSeek berjangka hari). */
    fun markDead(ctx: Context, fp: String) {
        try { prefs(ctx).edit().putLong("dsDead_" + fp, System.currentTimeMillis() + 4L * 24 * 3600 * 1000).apply() } catch (e: Exception) {}
    }

    fun isDead(ctx: Context, fp: String): Boolean {
        return try { prefs(ctx).getLong("dsDead_" + fp, 0) > System.currentTimeMillis() } catch (e: Exception) { false }
    }

    /** v4.14.2: suspend BENERAN dikenali dari kata keras di pesan server.
     *  40003/invalid_token POLOS (tanpa kata-kata ini) jauh lebih sering
     *  berarti token kedaluwarsa — akun sehat tidak boleh dihukum 4 hari
     *  karenanya (kejadian 2026-10-23: akun cadangan sehat terkunci
     *  lokal seharian). */
    fun isHardSuspend(msg: String?): Boolean {
        val m = (msg ?: "").lowercase()
        return m.contains("suspend") || m.contains("violation") || m.contains("banned")
    }

    /** Tandai sesuai jenisnya: keras = 4 hari (markDead lama); polos =
     *  tanda lunak 45 menit — cukup untuk berhenti menembak, cukup
     *  pendek untuk sembuh sendiri sesudah token disegarkan. */
    fun markDeadSmart(ctx: Context, fp: String, msg: String?) {
        if (isHardSuspend(msg)) { markDead(ctx, fp); return }
        try { prefs(ctx).edit().putLong("dsDead_" + fp, System.currentTimeMillis() + 45L * 60 * 1000).apply() } catch (e: Exception) {}
    }

    /** Hapus tanda satu token (dipakai jalur sukses: token terbukti hidup). */
    fun clearDead(ctx: Context, fp: String) {
        try { prefs(ctx).edit().remove("dsDead_" + fp).apply() } catch (e: Exception) {}
    }

    /** Hapus SEMUA tanda akun + flag sesi latar mati (tombol Reset di
     *  Pool Akun — jalan keluar manual dari tanda warisan yang keliru). */
    fun clearAllDead(ctx: Context) {
        try {
            val ed = prefs(ctx).edit()
            for (k in prefs(ctx).all.keys) if (k.startsWith("dsDead_")) ed.remove(k)
            ed.putBoolean("bgSessionDead", false)
            ed.apply()
        } catch (e: Exception) {}
    }
}
