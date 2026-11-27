package com.senzwh.deepharness

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android adapter untuk registry `LlmProviders`.
 *
 * Konfigurasi non-secret (id, label, baseUrl, model, enabled) disimpan di
 * SharedPreferences "llm_provider_cfg". API key TIDAK PERNAH disimpan di
 * prefs biasa — disimpan di `SecureStore` (AES-256/GCM + Android Keystore)
 * dengan kunci `llm_provider_key_<id>`. Key tidak pernah ditampilkan di
 * output tool/log; hanya status hasKey.
 *
 * Provider kustom dipersisten ke prefs agar hidup lintas restart; `hydrate`
 * menyalinnya kembali ke registry in-memory `LlmProviders`.
 *
 * §0.5 (Fase 0b, D10): kegagalan simpan/muat TIDAK LAGI diam — save()
 * mengembalikan Boolean + mencatat ke EventDb/Log; provider yang gagal
 * parse dikumpulkan id-nya dan hydrate() mengembalikan daftar id yang
 * dilewat. Skema JSON cfg_<id> diperluas (kompatibel mundur — opt* dengan
 * default). Cache daftar model tinggal di key terpisah models_<id>.
 */
object LlmProviderStore {

    private const val PREFS = "llm_provider_cfg"
    private const val KEY_PREFS_ACTIVE = "llm_provider_active"
    private const val KEY_PREF = "harness"
    private const val CFG_PREFIX = "cfg_"
    private const val MODELS_PREFIX = "models_"
    private const val KEY_PREFIX = "llm_provider_key_"
    private const val TAG = "LlmProviderStore"

    fun keyStorageKey(id: String): String = KEY_PREFIX + id

    fun apiKey(ctx: Context, id: String): String? =
        SecureStore.get(ctx, keyStorageKey(id))?.trim()?.ifBlank { null }

    fun setApiKey(ctx: Context, id: String, key: String) {
        SecureStore.put(ctx, keyStorageKey(id), key)
        // vc334 R3 (L8): key ikut dibaca hydrate — perubahan key mengotori cache.
        invalidateHydrateCache()
    }

    fun deleteApiKey(ctx: Context, id: String) {
        SecureStore.delete(ctx, keyStorageKey(id))
        // vc334 R3 (L8): lihat setApiKey.
        invalidateHydrateCache()
    }

    // Entri cache model memakai ModelCaps.CachedModel (JVM-murni) supaya
    // ModelCaps tidak bergantung ke store Android.

    /**
     * Parse SATU entri cfg_ (murni JSON, tanpa Context — bisa diuji JVM).
     * Mengembalikan null bila JSON rusak / id kosong. Tidak memvalidasi
     * label/baseUrl/model (validasi milik addCustom saat hydrate).
     */
    fun parseConfigEntry(storedId: String, json: String, apiKey: String?): LlmProvider? {
        return try {
            val obj = JSONObject(json)
            val id = obj.optString("id", "").ifBlank { storedId }
            if (id.isBlank()) return null
            LlmProvider(
                id = id,
                label = obj.optString("label", ""),
                kind = LlmKind.OPENAI_COMPAT,
                baseUrl = obj.optString("baseUrl", ""),
                apiKey = apiKey,
                model = obj.optString("model", ""),
                enabled = obj.optBoolean("enabled", true),
                builtIn = false,
                dailyCapUnits = optIntOrNull(obj, "dailyCapUnits"),
                maxPromptChars = optIntOrNull(obj, "maxPromptChars"),
                maxOutputTokens = optIntOrNull(obj, "maxOutputTokens"),
                thinkingParam = obj.optString("thinkingParam", ""),
                toolsSupport = LlmProviders.normalizeToolsSupport(obj.optString("toolsSupport", "unknown")),
                vision = LlmProviders.normalizeVision(obj.optString("vision", "unknown")),
                toolRoleFallback = obj.optBoolean("toolRoleFallback", false)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun optIntOrNull(obj: JSONObject, key: String): Int? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.optInt(key)

    /**
     * Parse banyak entri cfg_ (murni, tanpa Context). Mengembalikan
     * (provider yang lolos parse, id yang dilewati karena rusak).
     * Dipakai hydrate() dan tes JVM.
     */
    fun parseConfigs(
        entries: Map<String, String>,
        apiKeyFor: (String) -> String?
    ): Pair<List<LlmProvider>, List<String>> {
        val out = mutableListOf<LlmProvider>()
        val skipped = mutableListOf<String>()
        for ((k, json) in entries) {
            if (!k.startsWith(CFG_PREFIX)) continue
            val storedId = k.removePrefix(CFG_PREFIX)
            if (json.isBlank()) { skipped.add(storedId); continue }
            val parsed = parseConfigEntry(storedId, json, apiKeyFor(storedId))
            if (parsed == null) skipped.add(storedId) else out.add(parsed)
        }
        return Pair(out, skipped)
    }

    /** Daftar semua provider kustom yang tersimpan di prefs. */
    fun loadCustom(ctx: Context): List<LlmProvider> = loadCustomWithSkipped(ctx).first

    /**
     * Seperti loadCustom, tapi mengembalikan juga id provider yang gagal
     * parse (tidak hilang diam-diam — dialog provider menampilkannya
     * sebagai "⚠️ <id>: konfigurasi rusak, tidak dimuat").
     */
    fun loadCustomWithSkipped(ctx: Context): Pair<List<LlmProvider>, List<String>> {
        return try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val entries = LinkedHashMap<String, String>()
            val skippedNonString = mutableListOf<String>()
            for ((k, v) in p.all) {
                if (!k.startsWith(CFG_PREFIX)) continue
                val json = v as? String
                if (json == null) skippedNonString.add(k.removePrefix(CFG_PREFIX))
                else entries[k] = json
            }
            val (parsed, skipped) = parseConfigs(entries) { id -> try { apiKey(ctx, id) } catch (e: Exception) { null } }
            Pair(parsed, skipped + skippedNonString)
        } catch (e: Exception) {
            Log.e(TAG, "loadCustom gagal: ${e.message}")
            Pair(emptyList(), emptyList())
        }
    }

    /**
     * Simpan provider kustom (non-secret). API key disimpan terpisah di
     * SecureStore. Mengembalikan false bila gagal (dicatat EventDb + Log)
     * — pemanggil JANGAN menampilkan toast "aktif" palsu bila false.
     */
    fun save(ctx: Context, provider: LlmProvider): Boolean = saveDetailed(ctx, provider) == null

    /**
     * Seperti save(), tapi mengembalikan pesan sebab bila gagal (null bila
     * sukses) — D6 butuh sebab itu di pesan tool ("⚠️ Gagal menyimpan
     * provider: <sebab>"), bukan sekadar false yang menelan sebabnya.
     */
    fun saveDetailed(ctx: Context, provider: LlmProvider): String? {
        return try {
            val obj = JSONObject().apply {
                put("id", provider.id)
                put("label", provider.label)
                put("baseUrl", provider.baseUrl)
                put("model", provider.model)
                put("enabled", provider.enabled)
                provider.dailyCapUnits?.let { put("dailyCapUnits", it) }
                provider.maxPromptChars?.let { put("maxPromptChars", it) }
                provider.maxOutputTokens?.let { put("maxOutputTokens", it) }
                put("thinkingParam", provider.thinkingParam)
                put("toolsSupport", provider.toolsSupport)
                put("vision", provider.vision)
                put("toolRoleFallback", provider.toolRoleFallback)
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(CFG_PREFIX + provider.id, obj.toString())
                .apply()
            if (!provider.apiKey.isNullOrBlank()) setApiKey(ctx, provider.id, provider.apiKey!!)
            // vc334 R3 (L8): cfg_ berubah — hydrate berikut wajib parse ulang.
            invalidateHydrateCache()
            null
        } catch (e: Exception) {
            val reason = e.message ?: e.toString()
            Log.e(TAG, "save provider '${provider.id}' gagal: $reason")
            try { EventDb.get(ctx).add("llm_provider", provider.id, "save gagal: $reason") } catch (ignored: Exception) { }
            reason
        }
    }

    /** Tandai dukungan tools provider (§2.5, Fase 5a): simpan
     *  toolsSupport baru ke cfg_<id> + registry in-memory. Hanya untuk
     *  provider OPENAI_COMPAT; false bila provider tidak ditemukan /
     *  bukan openai-compat / gagal simpan. */
    fun markToolsSupport(ctx: Context, id: String, support: String): Boolean {
        val current = LlmProviders.find(id) ?: return false
        if (current.kind != LlmKind.OPENAI_COMPAT) return false
        return updateProviderRecord(ctx, current.copy(
            toolsSupport = LlmProviders.normalizeToolsSupport(support)))
    }

    /** Tandai fallback role:tool (§2.5.3): panggilan berikut langsung
     *  memakai format teks hasil-tool, tanpa mengulang 400. */
    fun markToolRoleFallback(ctx: Context, id: String, value: Boolean = true): Boolean {
        val current = LlmProviders.find(id) ?: return false
        if (current.kind != LlmKind.OPENAI_COMPAT) return false
        return updateProviderRecord(ctx, current.copy(toolRoleFallback = value))
    }

    /** Perbarui satu record provider: registry in-memory (addCustom
     *  menimpa id yang sama) + cfg_<id> di prefs. */
    private fun updateProviderRecord(ctx: Context, updated: LlmProvider): Boolean {
        return try {
            LlmProviders.addCustom(
                updated.id, updated.label, updated.baseUrl, updated.apiKey,
                updated.model, updated.enabled, updated.dailyCapUnits,
                updated.maxPromptChars, updated.maxOutputTokens,
                updated.thinkingParam, updated.toolsSupport, updated.vision,
                updated.toolRoleFallback
            )
            val ok = save(ctx, updated)
            if (!ok) Log.w(TAG, "mark provider '${updated.id}': save gagal")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "mark provider '${updated.id}' gagal: ${e.message}")
            false
        }
    }

    /** Hapus provider kustom + API key + cache model + counter Governor. */
    fun delete(ctx: Context, id: String) {
        // vc334 R3 (L8): cfg_ berubah — hydrate berikut wajib parse ulang.
        invalidateHydrateCache()
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(CFG_PREFIX + id)
                .remove(MODELS_PREFIX + id)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "delete provider '$id' gagal: ${e.message}")
        }
        try { deleteApiKey(ctx, id) } catch (e: Exception) { }
        try { Governor.clearModels(ctx, id) } catch (e: Exception) { }
    }

    /**
     * Salin provider kustom tersimpan ke registry in-memory.
     * Mengembalikan daftar id provider yang dilewati (gagal parse atau
     * gagal addCustom — mis. id tidak valid) supaya pemanggil bisa
     * menampilkannya, bukan menghilangkan diam-diam.
     *
     * vc334 R3 (L8): hasil di-CACHE (daftar id dilewati) + dirty-flag —
     * dulu tiap panggilan mengiterasi seluruh prefs + JSON-parse semua
     * provider + baca SecureStore per provider (6-10x per giliran,
     * sebagian di UI thread). Cache aman karena: (a) SEMUA mutasi
     * prefs cfg_ lewat store ini (save/delete/setActive/setApiKey/
     * deleteApiKey mengotori cache — tidak ada penulis langsung
     * "llm_provider_cfg" di luar store); (b) hydrate TIDAK PERNAH
     * menghapus registry (hanya add) — mutasi registry langsung
     * (addCustom) tetap terlihat walau cache hidup; (c) setActive
     * prefs ikut mengotori (activeId dibaca ulang di hydrate).
     */
    @Volatile private var hydrateDirtyVc334 = true
    @Volatile private var hydrateSkippedCacheVc334: List<String>? = null
    /** vc334 R3 (L8, diagnostik tes): berapa kali hydrate benar-benar parse. */
    @Volatile var hydrateParsesVc334 = 0
        private set

    /** Kosongkan cache [hydrate] (dipanggil semua titik mutasi — lihat L8). */
    fun invalidateHydrateCache() {
        hydrateDirtyVc334 = true
        hydrateSkippedCacheVc334 = null
    }

    fun hydrate(ctx: Context): List<String> {
        if (!hydrateDirtyVc334) {
            hydrateSkippedCacheVc334?.let { return it }
        }
        synchronized(this) {
            if (!hydrateDirtyVc334) {
                hydrateSkippedCacheVc334?.let { return it }
            }
            hydrateParsesVc334++
            val skipped = mutableListOf<String>()
            val (loaded, skippedParse) = loadCustomWithSkipped(ctx)
            skipped.addAll(skippedParse)
            for (p in loaded) {
                try {
                    LlmProviders.addCustom(
                        p.id, p.label, p.baseUrl, p.apiKey, p.model, p.enabled,
                        p.dailyCapUnits, p.maxPromptChars, p.maxOutputTokens,
                        p.thinkingParam, p.toolsSupport, p.vision, p.toolRoleFallback
                    )
                } catch (e: IllegalArgumentException) {
                    skipped.add(p.id)
                }
            }
            try { LlmProviders.setActive(activeId(ctx)) } catch (e: IllegalArgumentException) {}
            hydrateSkippedCacheVc334 = skipped
            hydrateDirtyVc334 = false
            return skipped
        }
    }

    /**
     * Simpan cache daftar model hasil fetch /v1/models (§5.10) di key
     * terpisah models_<id> (bukan di cfg_<id>, supaya cfg_ tetap kecil).
     * Mengembalikan false bila gagal simpan.
     */
    fun saveModels(ctx: Context, id: String, models: List<ModelCaps.CachedModel>, fetchedAtMs: Long = System.currentTimeMillis()): Boolean {
        return try {
            val arr = JSONArray()
            for (m in models) {
                if (m.id.isBlank()) continue
                arr.put(JSONObject().apply { put("id", m.id); put("vision", m.vision) })
            }
            val obj = JSONObject().apply {
                put("fetchedAt", fetchedAtMs)
                put("models", arr)
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(MODELS_PREFIX + id, obj.toString()).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveModels '$id' gagal: ${e.message}")
            false
        }
    }

    /** Muat cache daftar model; list kosong bila belum pernah fetch / rusak. */
    fun loadModels(ctx: Context, id: String): List<ModelCaps.CachedModel> {
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(MODELS_PREFIX + id, null) ?: return emptyList()
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("models") ?: return emptyList()
            val out = mutableListOf<ModelCaps.CachedModel>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mid = o.optString("id", "")
                if (mid.isBlank()) continue
                out.add(ModelCaps.CachedModel(mid, o.optString("vision", "unknown")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Epoch ms fetch terakhir cache model (0 bila belum pernah). */
    fun loadModelsFetchedAt(ctx: Context, id: String): Long {
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(MODELS_PREFIX + id, null) ?: return 0L
            JSONObject(raw).optLong("fetchedAt", 0L)
        } catch (e: Exception) {
            0L
        }
    }

    fun setActive(ctx: Context, id: String) {
        val trimmed = id.trim()
        // K1 (Fase 6): id tak dikenal → "" (belum ada provider aktif),
        // BUKAN fallback ke provider bawaan.
        val resolved = if (trimmed.isNotBlank() && LlmProviders.find(trimmed) != null) trimmed else ""
        ctx.getSharedPreferences(KEY_PREF, Context.MODE_PRIVATE).edit().putString(KEY_PREFS_ACTIVE, resolved).apply()
        // vc334 R3 (L8): activeId dibaca ulang di hydrate — perubahan aktif mengotori cache.
        invalidateHydrateCache()
    }

    fun activeId(ctx: Context): String =
        ctx.getSharedPreferences(KEY_PREF, Context.MODE_PRIVATE).getString(KEY_PREFS_ACTIVE, null)
            ?: ""
}
