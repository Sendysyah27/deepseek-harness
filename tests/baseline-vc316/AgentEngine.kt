package com.senzwh.deepharness

import android.content.Context

/**
 * J1 (dari bedah Jarves) — Registry mesin agen: setiap "otak" harness
 * dideklarasikan SATU kali dengan capability-nya; UI & dispatcher membaca
 * dari sini, bukan hardcode tersebar. Tool registry tetap tunggal (Tools.kt);
 * yang berbeda antar-mesin hanyalah DIALEK pemanggilan tool:
 *  - deepseek: dialek teks (blok json) — jalur bawaan sejak v1.
 *  - local:    agen aturan offline (LocalAgent) — fallback saat semua jalur
 *              online mati; tidak menjalankan loop chat umum.
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
        )
    )

    fun info(id: String): EngineInfo = ALL.firstOrNull { it.id == id } ?: ALL[0]

    fun current(ctx: Context): String {
        val raw = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getString("agentEngine", "deepseek") ?: "deepseek"
        return if (raw == "bridge" || raw == "qwen" || raw == "kimi") "deepseek" else raw
    }

    fun setCurrent(ctx: Context, id: String) {
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putString("agentEngine", id).apply()
    }

    fun capsText(info: EngineInfo): String = info.caps.joinToString(", ") { it.name }
}
