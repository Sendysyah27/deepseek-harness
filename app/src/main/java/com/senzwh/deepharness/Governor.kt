package com.senzwh.deepharness

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** v4.9.0 — GUBERNUR SUMBER DAYA: menghitung panggilan Models API per
 *  hari per (provider, bucket) dan memberi batas untuk kerja OTONOM.
 *  Chat interaktif user tidak pernah digerbang.
 *  Batas harian: pref govDailyCap (default 500; 0 = tanpa batas), atau
 *  dailyCapUnits per-provider bila terisi (menang atas cap global).
 *  vc245 GELOMBANG 4 (XL-Kuota, pilihan pisah kuota): hitungan latar
 *  terpisah (bucket "bg") dari hitungan interaktif (bucket "interactive",
 *  DICATAT tapi TIDAK PERNAH digerbang); gerbang latar memakai
 *  overCapModels(bucket bg), jadi chat interaktif tidak pernah lagi
 *  memakan kuota lalu memblokir job latar. count() = total semua
 *  provider + bucket (tampilan tidak berubah).
 *  vc262 (XL-Kuota): kuota TIM terpisah (bucket "team") + counter
 *  REVIEWER terpisah (bucket "reviewer"). refusalModels(bucket team)
 *  hanya melihat hitungan tim, jadi chat interaktif yang boros
 *  (termasuk panggilan reviewer yang tidak terlihat user) tidak lagi
 *  memblokir run_team. Panggilan reviewer bump counter reviewer,
 *  bukan interaktif/tim.
 *  vc277 O16: hitungan BERBOBOT ukuran — tiap panggilan dihitung
 *  1 unit + 1 unit per kelipatan 50.000 char prompt yang dikirim
 *  (weightFor, bucket char — konsisten dengan cap char app).
 *  Tampilan di Setup: "500 unit/hari". Bobot hanya untuk panggilan
 *  BARU (tidak retroaktif — hitungan lama tetap apa adanya).
 *  overCapModels(bucket reviewer): bila kuota reviewer habis, reviewer
 *  DILEWATI dengan penanda "tidak terverifikasi — kuota reviewer habis"
 *  (bukan diam-diam lolos, bukan memblokir jawaban).
 *  K1 (Fase 6): counter lama (govCount/govCountBg/govCountTeam/
 *  govCountReviewer + govDay*) DIHAPUS — hanya counter Models API
 *  (govModelsDay_/govModelsCount_) yang hidup. Pref lama ikut dibersihkan
 *  migrasi upgrade (§6.4).
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
    // Satu counter per (provider, bucket). Counter lama
    // (govCount/govCountBg/govCountTeam/govCountReviewer) DIHAPUS
    // K1 (Fase 6) — tidak digabung/dimigrasi angkanya.
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

    /** Total panggilan Models API hari ini di SEMUA provider + bucket
     *  (pengganti hitungan lama govCount/govCountBg/govCountTeam/
     *  govCountReviewer — DIHAPUS K1 Fase 6). Dipakai tampilan
     *  WorldState / AttentionBudget / dump diagnostik. Provider
     *  di-hydrate dulu supaya provider kustom ikut terhitung. */
    fun count(ctx: Context): Int {
        return try {
            LlmProviderStore.hydrate(ctx)
            var total = 0
            for (prov in LlmProviders.all()) {
                for (b in MODELS_BUCKETS) total += countModels(ctx, prov.id, b)
            }
            total
        } catch (e: Exception) { 0 }
    }

}
