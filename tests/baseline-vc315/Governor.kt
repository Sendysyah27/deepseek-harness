package com.senzwh.deepharness

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** v4.9.0 — GUBERNUR SUMBER DAYA: menghitung panggilan API DeepSeek per
 *  hari (semua jalur DsApiClient: chat, Otak Latar, ruang tim) dan memberi
 *  batas untuk kerja OTONOM. Chat interaktif user tidak pernah digerbang.
 *  Batas harian: pref govDailyCap (default 500; 0 = tanpa batas).
 *  vc245 GELOMBANG 4 (XL-Kuota, pilihan pisah kuota): hitungan latar
 *  terpisah (govCountBg) dari hitungan interaktif/slot (govCount);
 *  gerbang latar memakai overCapBg, jadi chat interaktif tidak pernah
 *  lagi memakan kuota lalu memblokir job latar. count() = total keduanya
 *  (tampilan tidak berubah).
 *  vc262 (XL-Kuota): kuota TIM terpisah (govCountTeam) + counter
 *  REVIEWER terpisah (govCountReviewer). refusalTeam() hanya melihat
 *  hitungan tim, jadi chat interaktif yang boros (termasuk panggilan
 *  reviewer yang tidak terlihat user) tidak lagi memblokir run_team.
 *  Panggilan reviewer bump counter reviewer, bukan interaktif/tim.
 *  vc277 O16: hitungan BERBOBOT ukuran — tiap panggilan dihitung
 *  1 unit + 1 unit per kelipatan 50.000 char prompt yang dikirim
 *  (weightFor, bucket char — konsisten dengan cap char app).
 *  Tampilan di Setup: "500 unit/hari". Bobot hanya untuk panggilan
 *  BARU (tidak retroaktif — hitungan lama tetap apa adanya).
 *  overCapReviewer(): bila kuota reviewer habis, reviewer DILEWATI
 *  dengan penanda "tidak terverifikasi — kuota reviewer habis"
 *  (bukan diam-diam lolos, bukan memblokir jawaban).
 *  refusal(ctx, teamOnly=true) hanya melihat hitungan tim (residu
 *  XL-Kuota: refusal() berbasis TOTAL tidak boleh memblokir run_team).
 */
object Governor {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
    private fun dayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun cap(ctx: Context): Int = try { prefs(ctx).getInt("govDailyCap", 500) } catch (e: Exception) { 500 }

    // vc277 O16(a): bobot ukuran panggilan — 1 unit dasar + 1 unit per
    // kelipatan 50.000 char prompt yang dikirim. Prompt reviewer
    // (maks 16.000 char) selalu berbobot 1.
    const val WEIGHT_BUCKET_CHARS = 50000
    fun weightFor(promptChars: Int): Int = 1 + (if (promptChars > 0) promptChars else 0) / WEIGHT_BUCKET_CHARS

    fun count(ctx: Context): Int {
        return try {
            val p = prefs(ctx)
            val inter = if (p.getString("govDay", "") != dayKey()) 0 else p.getInt("govCount", 0)
            inter + countBg(ctx)
        } catch (e: Exception) { 0 }
    }

    fun countBg(ctx: Context): Int {
        return try {
            val p = prefs(ctx)
            if (p.getString("govDayBg", "") != dayKey()) 0 else p.getInt("govCountBg", 0)
        } catch (e: Exception) { 0 }
    }

    fun bumpBg(ctx: Context, weight: Int = 1) {
        try {
            val p = prefs(ctx)
            val today = dayKey()
            val cur = if (p.getString("govDayBg", "") == today) p.getInt("govCountBg", 0) else 0
            p.edit().putString("govDayBg", today).putInt("govCountBg", cur + weight.coerceAtLeast(1)).apply()
        } catch (e: Exception) {}
    }

    fun overCapBg(ctx: Context): Boolean {
        val c = cap(ctx)
        return c > 0 && countBg(ctx) >= c
    }

    fun countTeam(ctx: Context): Int {
        return try {
            val p = prefs(ctx)
            if (p.getString("govDayTeam", "") != dayKey()) 0 else p.getInt("govCountTeam", 0)
        } catch (e: Exception) { 0 }
    }

    fun bumpTeam(ctx: Context, weight: Int = 1) {
        try {
            val p = prefs(ctx)
            val today = dayKey()
            val cur = if (p.getString("govDayTeam", "") == today) p.getInt("govCountTeam", 0) else 0
            p.edit().putString("govDayTeam", today).putInt("govCountTeam", cur + weight.coerceAtLeast(1)).apply()
        } catch (e: Exception) {}
    }

    fun overCapTeam(ctx: Context): Boolean {
        val c = cap(ctx)
        return c > 0 && countTeam(ctx) >= c
    }

    // vc277 O16(b): reviewer kehabisan kuota — reviewer dilewati,
    // jawaban HARUS membawa penanda belum terverifikasi (O11).
    fun overCapReviewer(ctx: Context): Boolean {
        val c = cap(ctx)
        return c > 0 && countReviewer(ctx) >= c
    }

    fun reviewerOverCapText(ctx: Context): String =
        "⚠️ Jawaban ini belum terverifikasi: kuota reviewer habis (${countReviewer(ctx)}/${cap(ctx)} unit hari ini) — reviewer konteks-nol dilewati, klaim di jawaban ini belum diperiksa, jangan jadikan fakta."

    fun refusalTeam(ctx: Context): String? =
        if (overCapTeam(ctx)) "Kuota panggilan AI tim hari ini habis (${countTeam(ctx)}/${cap(ctx)} unit — Gubernur Sumber Daya, hitungan tim terpisah vc262). run_team ditunda sampai besok; chat interaktif tetap jalan." else null

    fun countReviewer(ctx: Context): Int {
        return try {
            val p = prefs(ctx)
            if (p.getString("govDayReviewer", "") != dayKey()) 0 else p.getInt("govCountReviewer", 0)
        } catch (e: Exception) { 0 }
    }

    fun bumpReviewer(ctx: Context, weight: Int = 1) {
        try {
            val p = prefs(ctx)
            val today = dayKey()
            val cur = if (p.getString("govDayReviewer", "") == today) p.getInt("govCountReviewer", 0) else 0
            p.edit().putString("govDayReviewer", today).putInt("govCountReviewer", cur + weight.coerceAtLeast(1)).apply()
        } catch (e: Exception) {}
    }

    fun bump(ctx: Context, weight: Int = 1) {
        try {
            val p = prefs(ctx)
            val today = dayKey()
            val cur = if (p.getString("govDay", "") == today) p.getInt("govCount", 0) else 0
            p.edit().putString("govDay", today).putInt("govCount", cur + weight.coerceAtLeast(1)).apply()
        } catch (e: Exception) {}
    }

    fun overCap(ctx: Context): Boolean {
        val c = cap(ctx)
        return c > 0 && count(ctx) >= c
    }

    /** Teks penolakan untuk kerja otonom saat kuota habis; null bila aman.
     *  vc277 O16(c): teamOnly=true (untuk run_team) hanya melihat
     *  hitungan tim — seperti refusalTeam() — bukan total. */
    fun refusal(ctx: Context, teamOnly: Boolean = false): String? {
        if (teamOnly) return refusalTeam(ctx)
        return if (overCap(ctx)) "Kuota panggilan AI otonom hari ini habis (${count(ctx)}/${cap(ctx)} unit — Gubernur Sumber Daya). Kerja latar ditunda sampai besok; chat interaktif tetap jalan." else null
    }
}
