package com.senzwh.deepharness

import android.content.Context
import android.util.Log

/**
 * J1 (dari bedah Jarves) — Registry mesin agen: setiap "otak" harness
 * dideklarasikan SATU kali dengan capability-nya; UI & dispatcher membaca
 * dari sini, bukan hardcode tersebar. Tool registry tetap tunggal (Tools.kt);
 * yang berbeda antar-mesin hanyalah DIALEK pemanggilan tool:
 *  - openai:   provider OpenAI-compatible kustom (LlmProviders) — SATU-SATUNYA
 *              mesin online (K1: jalur web/API internal lama DIHAPUS TOTAL,
 *              Fase 6). Tool dipanggil native (tool_calls) atau teks fallback.
 *  - local:    agen aturan offline (LocalAgent) — fallback darurat saat semua jalur
 *              online mati; tidak menjalankan loop chat umum.
 *
 * §0.2 (Fase 0b): engine HANYA "openai" | "local". Id provider kustom
 * BUKAN engine — provider kustom dipilih lewat LlmProviderStore.activeId(),
 * bukan lewat pref agentEngine. current() meng-hydrate dulu (D7) supaya id
 * kustom tidak jatuh ke nilai tak dikenal sesudah restart.
 * Nilai legacy ("deepseek" | "bridge" | "qwen" | "kimi" | id provider kustom
 * lama) dipetakan ke "openai" (migrateEnginePref, §6.4).
 */
enum class Cap { NATIVE_TOOLS, TEXT_TOOLS, STREAMING, THINKING, OFFLINE }

data class EngineInfo(
    val id: String,
    val label: String,
    val caps: Set<Cap>,
    val desc: String
)

object AgentEngines {
    val ALL: List<EngineInfo> = listOf(
        EngineInfo(
            "openai", "OpenAI-compatible (Models API)",
            // §0.2/K2: engine openai memakai NATIVE_TOOLS (tool_calls),
            // bukan lagi TEXT_TOOLS.
            setOf(Cap.NATIVE_TOOLS, Cap.STREAMING, Cap.THINKING),
            "Provider LLM OpenAI-compatible (OpenAI/OpenRouter/Groq/Ollama/vLLM/custom base URL, termasuk preset DeepSeek platform api.deepseek.com/v1). Diarahkan via llm_provider_set, llm_provider_list, llm_provider_ask. SATU-SATUNYA mesin online."
        ),
        EngineInfo(
            "local", "Lokal (aturan, offline)",
            setOf(Cap.OFFLINE),
            "Agen aturan tanpa LLM (LocalAgent): fallback darurat saat internet mati. Bukan mesin chat umum."
        )
    )

    /**
     * §0.2: id tidak dikenal → ALL[0] (openai) + catat ke EventDb
     * (bukan diam). Cabang LlmProviders.find(id) untuk id kustom DIHAPUS —
     * id kustom bukan engine.
     */
    fun info(ctx: Context, id: String): EngineInfo {
        ALL.firstOrNull { it.id == id }?.let { return it }
        try {
            EventDb.get(ctx).add("agent_engine", id, "info() id engine tidak dikenal '$id' — jatuh ke openai")
        } catch (e: Exception) {
            Log.w("AgentEngines", "info() id tidak dikenal: $id")
        }
        return ALL[0]
    }

    /** Overload tanpa Context (kompatibilitas) — mencatat via Log saja. */
    fun info(id: String): EngineInfo {
        ALL.firstOrNull { it.id == id }?.let { return it }
        Log.w("AgentEngines", "info() id engine tidak dikenal: $id — jatuh ke openai")
        return ALL[0]
    }

    /**
     * §0.2: hydrate dulu, lalu kembalikan HANYA "openai" | "local".
     * Nilai legacy ("deepseek" | "bridge" | "qwen" | "kimi"), id provider
     * kustom lama, atau nilai tak dikenal → "openai" (satu-satunya mesin
     * online sesudah K1, Fase 6).
     */
    fun current(ctx: Context): String {
        try { LlmProviderStore.hydrate(ctx) } catch (e: Exception) { }
        val raw = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getString("agentEngine", "openai") ?: "openai"
        return when (raw) {
            "local", "openai" -> raw
            else -> "openai"
        }
    }

    /**
     * §6.4 — migrasi pref agentEngine satu kali (murni, tanpa Context):
     * "deepseek" dan nilai legacy ("bridge"/"qwen"/"kimi") → "openai";
     * id provider kustom → "openai"; "local" dan "openai" tetap;
     * nilai lain yang tidak dikenal dibiarkan apa adanya (jangan ditebak).
     * "deepseek" di sini HANYA pemetaan nilai legacy saat upgrade —
     * BUKAN jalur hidup (K1).
     */
    fun migrateEnginePref(raw: String, isCustomId: (String) -> Boolean = { LlmProviders.find(it) != null }): String =
        when (raw) {
            "deepseek", "bridge", "qwen", "kimi" -> "openai"
            "local", "openai" -> raw
            else -> if (isCustomId(raw)) "openai" else raw
        }

    fun setCurrent(ctx: Context, id: String) {
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putString("agentEngine", id).apply()
    }

    fun capsText(info: EngineInfo): String = info.caps.joinToString(", ") { it.name }
}
