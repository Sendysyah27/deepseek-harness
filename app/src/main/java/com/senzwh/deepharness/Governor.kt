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

    // ---- §3.2 (Fase 0b): guard per-provider, Models API (K4) ----
    // Satu counter per (provider, bucket); counter DeepSeek lama
    // (govCount/govCountBg/govCountTeam/govCountReviewer) TETAP ADA selama
    // transisi dan TIDAK digabung/dimigrasi angkanya.
    // Kunci prefs (file "harness"):
    //   govModelsDay_<providerId>_<bucket> : "yyyyMMdd"
    //   govModelsCount_<providerId>_<bucket> : Int
    // Bucket "interactive" DICATAT tapi TIDAK PERNAH digerbang (chat user
    // tidak pernah diblokir). ROOM/GRAPH masuk bucket "bg".
    const val MODELS_BUCKET_BG = "bg"
    const val MODELS_BUCKET_TEAM = "team"
    const val MODELS_BUCKET_REVIEWER = "reviewer"
    const val MODELS_BUCKET_INTERACTIVE = "interactive"
    private val MODELS_BUCKETS = setOf(MODELS_BUCKET_BG, MODELS_BUCKET_TEAM, MODELS_BUCKET_REVIEWER, MODELS_BUCKET_INTERACTIVE)

    /** Normalisasi bucket ke salah satu dari 4 nilai resmi (lainnya → "bg"). Murni. */
    fun normalizeModelsBucket(bucket: String): String =
        if (bucket in MODELS_BUCKETS) bucket else MODELS_BUCKET_BG

    /** Resolusi cap: dailyCapUnits provider menang atas cap global. Murni. */
    fun resolveCapModels(providerDailyCap: Int?, globalCap: Int): Int = providerDailyCap ?: globalCap

    /** Keputusan over-cap dari angka mentah (cap <= 0 = tanpa batas). Murni. */
    fun overCapCount(count: Int, cap: Int): Boolean = cap > 0 && count >= cap

    fun modelsBucketLabel(bucket: String): String = when (normalizeModelsBucket(bucket)) {
        MODELS_BUCKET_BG -> "latar"
        MODELS_BUCKET_TEAM -> "tim"
        MODELS_BUCKET_REVIEWER -> "reviewer"
        else -> "interaktif"
    }

    /** Teks penolakan menyebut provider + angka (murni, tanpa Context). */
    fun refusalModelsText(providerLabel: String, bucket: String, count: Int, cap: Int): String {
        val b = modelsBucketLabel(bucket)
        return "Kuota panggilan AI $b provider '$providerLabel' hari ini habis ($count/$cap unit — Gubernur Sumber Daya). Kerja $b ditunda sampai besok; chat interaktif tetap jalan."
    }

    fun bumpModels(ctx: Context, providerId: String, bucket: String, weight: Int = 1) {
        try {
            val b = normalizeModelsBucket(bucket)
            val p = prefs(ctx)
            val today = dayKey()
            val dayKey = "govModelsDay_${providerId}_$b"
            val countKey = "govModelsCount_${providerId}_$b"
            val cur = if (p.getString(dayKey, "") == today) p.getInt(countKey, 0) else 0
            p.edit().putString(dayKey, today).putInt(countKey, cur + weight.coerceAtLeast(1)).apply()
        } catch (e: Exception) {}
    }

    fun countModels(ctx: Context, providerId: String, bucket: String): Int {
        return try {
            val b = normalizeModelsBucket(bucket)
            val p = prefs(ctx)
            if (p.getString("govModelsDay_${providerId}_$b", "") != dayKey()) 0
            else p.getInt("govModelsCount_${providerId}_$b", 0)
        } catch (e: Exception) { 0 }
    }

    /** Cap provider ini: dailyCapUnits provider, atau cap global bila null. */
    fun capModels(ctx: Context, provider: LlmProvider): Int = resolveCapModels(provider.dailyCapUnits, cap(ctx))

    /**
     * True bila kuota bucket provider ini habis. Bucket "interactive"
     * TIDAK PERNAH over-cap (hanya dicatat).
     */
    fun overCapModels(ctx: Context, provider: LlmProvider, bucket: String): Boolean {
        val b = normalizeModelsBucket(bucket)
        if (b == MODELS_BUCKET_INTERACTIVE) return false
        return overCapCount(countModels(ctx, provider.id, b), capModels(ctx, provider))
    }

    /** Teks penolakan bila over-cap; null bila aman. */
    fun refusalModels(ctx: Context, provider: LlmProvider, bucket: String): String? {
        val b = normalizeModelsBucket(bucket)
        if (!overCapModels(ctx, provider, b)) return null
        return refusalModelsText(provider.label, b, countModels(ctx, provider.id, b), capModels(ctx, provider))
    }

    /** Hapus SEMUA counter Models API milik satu provider (dipakai delete provider). */
    fun clearModels(ctx: Context, providerId: String) {
        try {
            val p = prefs(ctx)
            val ed = p.edit()
            for (k in p.all.keys) {
                if (k.startsWith("govModelsDay_${providerId}_") || k.startsWith("govModelsCount_${providerId}_")) {
                    ed.remove(k)
                }
            }
            ed.apply()
        } catch (e: Exception) {}
    }

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
