package com.senzwh.deepharness

import android.content.Context
import android.util.Log

/**
 * J1 (dari bedah Jarves) — Registry mesin agen: setiap "otak" harness
 * dideklarasikan SATU kali dengan capability-nya; UI & dispatcher membaca
 * dari sini, bukan hardcode tersebar. Tool registry tetap tunggal (Tools.kt);
 * yang berbeda antar-mesin hanyalah DIALEK pemanggilan tool:
 *  - deepseek: dialek teks (blok json) — jalur bawaan sejak v1.
 *  - local:    agen aturan offline (LocalAgent) — fallback darurat saat semua jalur
 *              online mati; tidak menjalankan loop chat umum.
 *  - openai:   provider OpenAI-compatible kustom (LlmProviders) — otak model
 *              eksternal alternatif. Jalur bawaan tetap DeepSeek untuk agen
 *              utama; ini mesin yang memakai provider kustom.
 *
 * §0.2 (Fase 0b): engine HANYA "openai" | "local" | "deepseek". Id provider
 * kustom BUKAN engine — provider kustom dipilih lewat
 * LlmProviderStore.activeId(), bukan lewat pref agentEngine. current()
 * meng-hydrate dulu (D7) supaya id kustom tidak jatuh ke "deepseek"
 * sesudah restart.
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
            "deepseek", "DeepSeek (API Internal / Web)",
            setOf(Cap.TEXT_TOOLS, Cap.STREAMING, Cap.THINKING),
            "Mesin bawaan: sesi login DeepSeek user sendiri (PoW + API internal, fallback WebView). Tool dipanggil via blok json teks."
        ),
        EngineInfo(
            "local", "Lokal (aturan, offline)",
            setOf(Cap.OFFLINE),
            "Agen aturan tanpa LLM (LocalAgent): fallback darurat saat internet/DeepSeek mati. Bukan mesin chat umum."
        ),
        EngineInfo(
            "openai", "OpenAI-compatible (custom)",
            // §0.2/K2: engine openai memakai NATIVE_TOOLS (tool_calls),
            // bukan lagi TEXT_TOOLS.
            setOf(Cap.NATIVE_TOOLS, Cap.STREAMING, Cap.THINKING),
            "Provider LLM OpenAI-compatible (OpenAI/OpenRouter/Groq/Ollama/vLLM/custom base URL). Diarahkan via llm_provider_set, llm_provider_list, llm_provider_ask. DeepSeek tetap default untuk agen utama."
        )
    )

    /**
     * §0.2: id tidak dikenal → ALL[0] (deepseek) + catat ke EventDb
     * (bukan diam). Cabang LlmProviders.find(id) untuk id kustom DIHAPUS —
     * id kustom bukan engine.
     */
    fun info(ctx: Context, id: String): EngineInfo {
        ALL.firstOrNull { it.id == id }?.let { return it }
        try {
            EventDb.get(ctx).add("agent_engine", id, "info() id engine tidak dikenal '$id' — jatuh ke deepseek")
        } catch (e: Exception) {
            Log.w("AgentEngines", "info() id tidak dikenal: $id")
        }
        return ALL[0]
    }

    /** Overload tanpa Context (kompatibilitas) — mencatat via Log saja. */
    fun info(id: String): EngineInfo {
        ALL.firstOrNull { it.id == id }?.let { return it }
        Log.w("AgentEngines", "info() id engine tidak dikenal: $id — jatuh ke deepseek")
        return ALL[0]
    }

    /**
     * §0.2: hydrate dulu, lalu kembalikan HANYA "openai" | "local" |
     * "deepseek". Nilai legacy "bridge" | "qwen" | "kimi" tetap dipetakan
     * ke "deepseek". Id provider kustom (dikenal sesudah hydrate)
     * dipetakan ke "openai" — providernya dipilih via activeId().
     */
    fun current(ctx: Context): String {
        try { LlmProviderStore.hydrate(ctx) } catch (e: Exception) { }
        val raw = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getString("agentEngine", "deepseek") ?: "deepseek"
        return when (raw) {
            "deepseek", "local", "openai" -> raw
            "bridge", "qwen", "kimi" -> "deepseek"
            else -> if (LlmProviders.find(raw) != null) "openai" else "deepseek"
        }
    }

    /**
     * §6.4 — migrasi pref agentEngine satu kali (murni, tanpa Context):
     * "deepseek" dan nilai legacy ("bridge"/"qwen"/"kimi") → "openai";
     * id provider kustom → "openai"; "local" dan "openai" tetap;
     * nilai lain yang tidak dikenal dibiarkan apa adanya (jangan ditebak).
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
