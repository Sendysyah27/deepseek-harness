package com.senzwh.deepharness

import android.content.Context
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
 */
object LlmProviderStore {

    private const val PREFS = "llm_provider_cfg"
    private const val KEY_PREFS_ACTIVE = "llm_provider_active"
    private const val KEY_PREF = "harness"
    private const val CFG_PREFIX = "cfg_"
    private const val KEY_PREFIX = "llm_provider_key_"

    fun keyStorageKey(id: String): String = KEY_PREFIX + id

    fun apiKey(ctx: Context, id: String): String? =
        SecureStore.get(ctx, keyStorageKey(id))?.trim()?.ifBlank { null }

    fun setApiKey(ctx: Context, id: String, key: String) {
        SecureStore.put(ctx, keyStorageKey(id), key)
    }

    fun deleteApiKey(ctx: Context, id: String) {
        SecureStore.delete(ctx, keyStorageKey(id))
    }

    /** Daftar semua provider kustom yang tersimpan di prefs. */
    fun loadCustom(ctx: Context): List<LlmProvider> {
        val out = mutableListOf<LlmProvider>()
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            for ((k, v) in p.all) {
                if (!k.startsWith(CFG_PREFIX)) continue
                val json = v as? String ?: continue
                if (json.isBlank()) continue
                try {
                    val obj = JSONObject(json)
                    val id = obj.optString("id", "")
                    if (id.isBlank()) continue
                    val key = apiKey(ctx, id)
                    out.add(
                        LlmProvider(
                            id = id,
                            label = obj.optString("label", ""),
                            kind = LlmKind.OPENAI_COMPAT,
                            baseUrl = obj.optString("baseUrl", ""),
                            apiKey = key,
                            model = obj.optString("model", ""),
                            enabled = obj.optBoolean("enabled", true),
                            builtIn = false
                        )
                    )
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        return out
    }

    /** Simpan provider kustom (non-secret). API key disimpan terpisah di SecureStore. */
    fun save(ctx: Context, provider: LlmProvider) {
        try {
            val obj = JSONObject().apply {
                put("id", provider.id)
                put("label", provider.label)
                put("baseUrl", provider.baseUrl)
                put("model", provider.model)
                put("enabled", provider.enabled)
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(CFG_PREFIX + provider.id, obj.toString())
                .apply()
            if (!provider.apiKey.isNullOrBlank()) setApiKey(ctx, provider.id, provider.apiKey!!)
        } catch (e: Exception) {}
    }

    /** Hapus provider kustom + hapus API key-nya. */
    fun delete(ctx: Context, id: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CFG_PREFIX + id).apply()
            deleteApiKey(ctx, id)
        } catch (e: Exception) {}
    }

    /** Salin provider kustom tersimpan ke registry in-memory. */
    fun hydrate(ctx: Context) {
        for (p in loadCustom(ctx)) {
            try {
                LlmProviders.addCustom(p.id, p.label, p.baseUrl, p.apiKey, p.model, p.enabled)
            } catch (e: IllegalArgumentException) {}
        }
        try { LlmProviders.setActive(activeId(ctx)) } catch (e: IllegalArgumentException) {}
    }

    fun setActive(ctx: Context, id: String) {
        val trimmed = id.trim()
        val resolved = if (trimmed.isNotBlank() && LlmProviders.find(trimmed) != null) trimmed else LlmProviders.ID_DEEPSEEK
        ctx.getSharedPreferences(KEY_PREF, Context.MODE_PRIVATE).edit().putString(KEY_PREFS_ACTIVE, resolved).apply()
    }

    fun activeId(ctx: Context): String =
        ctx.getSharedPreferences(KEY_PREF, Context.MODE_PRIVATE).getString(KEY_PREFS_ACTIVE, null)
            ?: LlmProviders.ID_DEEPSEEK
}
