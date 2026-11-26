package com.senzwh.deepharness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Profil Agen (Brief v4 Rel 3, v3.13.0) — agen sebagai DATA:
 * {id, name, accent(hex), engine, pack}. Mengganti agen mengganti namespace
 * otak (AgentBrain membaca pref activeAgent), mesin (AgentEngines), pak
 * perilaku (Behaviors), dan warna aksen tab Chat. Satu desain untuk semua
 * agen — beda di warna & otak saja.
 */
object Agents {
    data class Profile(
        val id: String, val name: String, val accent: String,
        val engine: String, val pack: String, val builtin: Boolean
    )

    private val PALETTE = listOf("#4d8cff", "#e8a33d", "#2fbf9a", "#9a6bff", "#ef6aa5", "#7d8aa0")

    val BUILTINS = listOf(
        Profile("asisten", "Asisten", "#4d8cff", "openai", "asisten", true),
        Profile("mandor", "Mandor", "#e8a33d", "openai", "mandor", true),
        Profile("mentor", "Mentor", "#2fbf9a", "openai", "tutor", true),
        Profile("agen4", "Agen 4", "#9a6bff", "openai", "agen4", true)
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    // vc181: override Edit Agen (nama/pak) dari pref agentName_/agentPack_.
    // Diterapkan di all() agar Beranda, Profil, dialog agen, dan apply()
    // otomatis memakai nilai hasil edit tanpa mengubah skema agentsJson.
    fun all(ctx: Context): List<Profile> {
        val p = prefs(ctx)
        fun ov(id: String, key: String): String? = p.getString(key + id, "")?.takeIf { it.isNotBlank() }
        val out = ArrayList(BUILTINS.map { it.copy(name = ov(it.id, "agentName_") ?: it.name, pack = ov(it.id, "agentPack_") ?: it.pack) })
        try {
            val raw = prefs(ctx).getString("agentsJson", "") ?: ""
            if (raw.isNotBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    // K1 (Fase 6): nilai engine legacy ("deepseek" | "bridge" |
                    // "qwen" | "kimi") → "openai"; nilai lain dibiarkan.
                    val engRaw = o.optString("engine", "openai")
                    val eng = if (engRaw == "deepseek" || engRaw == "bridge" || engRaw == "qwen" || engRaw == "kimi") "openai" else engRaw
                    val cid = o.optString("id")
                    out.add(
                        Profile(
                            cid, ov(cid, "agentName_") ?: o.optString("name"),
                            o.optString("accent", "#4d8cff"),
                            eng,
                            ov(cid, "agentPack_") ?: o.optString("pack", "asisten"), false
                        )
                    )
                }
            }
        } catch (e: Exception) {}
        return out
    }

    fun active(ctx: Context): Profile {
        val id = prefs(ctx).getString("activeAgent", "asisten") ?: "asisten"
        return all(ctx).firstOrNull { it.id == id } ?: BUILTINS[0]
    }

    /** Terapkan profil ke pref mesin + pak yang sudah ada; namespace otak
     *  mengikuti pref activeAgent secara otomatis di AgentBrain. */
    fun apply(ctx: Context, id: String): Profile? {
        val p = all(ctx).firstOrNull { it.id == id } ?: return null
        prefs(ctx).edit().putString("activeAgent", p.id).apply()
        AgentEngines.setCurrent(ctx, p.engine)
        prefs(ctx).edit().putString("behaviorPack", p.pack).apply()
        return p
    }

    fun create(ctx: Context, name: String): Profile {
        val customs = all(ctx).filter { !it.builtin }
        val id = "agen" + System.currentTimeMillis()
        val accent = PALETTE[(BUILTINS.size + customs.size) % PALETTE.size]
        val p = Profile(id, name.take(24), accent, "openai", "asisten", false)
        saveCustoms(ctx, customs + p)
        return p
    }

    fun delete(ctx: Context, id: String): Boolean {
        val customs = all(ctx).filter { !it.builtin }
        if (customs.none { it.id == id }) return false
        val wasActive = active(ctx).id == id
        saveCustoms(ctx, customs.filter { it.id != id })
        if (wasActive) apply(ctx, "asisten")
        return true
    }

    private fun saveCustoms(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("accent", p.accent)
                    .put("engine", p.engine).put("pack", p.pack)
            )
        }
        prefs(ctx).edit().putString("agentsJson", arr.toString()).apply()
    }
}
