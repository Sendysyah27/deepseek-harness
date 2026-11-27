package com.senzwh.deepharness

/**
 * Registry provider LLM — otak Models API (OpenAI-compatible).
 *
 * Modul ini TIDAK bergantung Android (tidak ada Context/Android API), agar
 * bisa diuji dengan `tools/run_tests.sh` (JVM murni). Persistensi ke prefs/
 * SecureStore hidup di `LlmProviderStore` (Android-only).
 *
 * K1 (Fase 6): TIDAK ADA provider bawaan jalur web/API internal lama lagi.
 * Semua provider adalah OpenAI-compatible (OpenAI, OpenRouter, Groq,
 * Ollama, vLLM, dll — termasuk preset "deepseek-platform" yang memakai
 * Models API standar https://api.deepseek.com/v1, §8.2-1). Id provider
 * kustom BUKAN engine — engine hanya "openai" | "local" (lihat
 * AgentEngines). Tanpa provider aktif yang configured → belum ada
 * provider aktif (activeId ""), BUKAN fallback ke provider bawaan.
 *
 * §3.1 (Fase 0b): field guard per-provider di LlmProvider (null = pakai
 * default global) + daftar PRESETS (template, BUKAN provider aktif —
 * memilih preset hanya mengisi field; key tetap diisi user sendiri).
 */
enum class LlmKind { OPENAI_COMPAT }

data class LlmProvider(
    val id: String,
    val label: String,
    val kind: LlmKind,
    val baseUrl: String,
    val apiKey: String?,
    val model: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    // §3.1 — guard per-provider; null = pakai default global.
    val dailyCapUnits: Int? = null,
    val maxPromptChars: Int? = null,
    val maxOutputTokens: Int? = null,
    val thinkingParam: String = "",
    val toolsSupport: String = "unknown",
    val vision: String = "unknown",
    val toolRoleFallback: Boolean = false,
    // vc335 R3 (D5): opt-in prompt caching per provider — default
    // false (cache_control TIDAK dipasang global; hanya provider yang
    // record-nya menyatakan mendukung yang mendapatkannya).
    val supportsCacheControl: Boolean = false
) {
    val hasKey: Boolean get() = !apiKey.isNullOrBlank()
    val configured: Boolean
        get() = when (kind) {
            // Provider lokal (Ollama di HP sendiri) boleh tanpa key —
            // key hanya wajib bila base URL bukan lokal.
            LlmKind.OPENAI_COMPAT -> enabled && baseUrl.isNotBlank() && model.isNotBlank() &&
                (hasKey || LlmProviders.isLocalBaseUrl(baseUrl))
        }
}

object LlmProviders {
    const val ID_OPENAI = "openai"

    /**
     * §3.1 — Preset bawaan (template pengisi field, BUKAN provider aktif).
     * Termasuk "deepseek-platform" (keputusan §8.2(1): BOLEH — endpoint ini
     * Models API standar https://api.deepseek.com/v1, bukan web/API
     * internal yang dihapus K1).
     */
    val PRESETS: List<LlmProvider> = listOf(
        LlmProvider(
            "openai", "OpenAI", LlmKind.OPENAI_COMPAT,
            "https://api.openai.com/v1", null, "gpt-4o-mini",
            maxPromptChars = 120000, thinkingParam = "", toolsSupport = "native"
        ),
        LlmProvider(
            "openrouter", "OpenRouter", LlmKind.OPENAI_COMPAT,
            "https://openrouter.ai/api/v1", null, "openai/gpt-4o-mini",
            maxPromptChars = 120000, thinkingParam = "", toolsSupport = "native"
        ),
        LlmProvider(
            "groq", "Groq", LlmKind.OPENAI_COMPAT,
            "https://api.groq.com/openai/v1", null, "llama-3.3-70b-versatile",
            maxPromptChars = 60000, thinkingParam = "", toolsSupport = "native"
        ),
        LlmProvider(
            "ollama", "Ollama (lokal)", LlmKind.OPENAI_COMPAT,
            "http://localhost:11434/v1", null, "llama3.1",
            maxPromptChars = 60000, thinkingParam = "", toolsSupport = "unknown"
        ),
        LlmProvider(
            "deepseek-platform", "DeepSeek (API platform)", LlmKind.OPENAI_COMPAT,
            "https://api.deepseek.com/v1", null, "deepseek-chat",
            maxPromptChars = 120000, thinkingParam = "thinking_enabled", toolsSupport = "native"
        )
    )

    fun preset(id: String): LlmProvider? = PRESETS.firstOrNull { it.id == id.trim() }

    private val custom = mutableListOf<LlmProvider>()
    // "" = belum ada provider aktif (K1, Fase 6 — bukan fallback bawaan).
    private var activeId: String = ""

    private val idRegex = Regex("[a-z0-9_]+")

    fun all(): List<LlmProvider> = custom.toList()

    fun find(id: String): LlmProvider? = all().firstOrNull { it.id == id }

    fun validateId(id: String): Boolean = id.isNotBlank() && idRegex.matches(id)

    /** True bila base URL menunjuk server lokal di HP ini (tanpa key). */
    fun isLocalBaseUrl(baseUrl: String): Boolean {
        val u = baseUrl.trim().lowercase()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        val host = u.substringAfter("://").substringBefore("/").substringBefore(":")
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    }

    /** Normalisasi nilai toolsSupport ke {native, unknown, unsupported,
     *  text_fallback} (§2.5 + Fase 5a): "text_fallback" = provider
     *  menolak native tools — pemanggil memakai jalur teks (§2.4,
     *  dipakai shouldUseNativeTools). Nilai lain → "unknown". */
    fun normalizeToolsSupport(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "native", "unsupported", "text_fallback" -> raw.trim().lowercase()
        else -> "unknown"
    }

    /** Normalisasi nilai vision ke {supported, unknown, unsupported}. */
    fun normalizeVision(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "supported", "unsupported" -> raw.trim().lowercase()
        else -> "unknown"
    }

    @Throws(IllegalArgumentException::class)
    fun addCustom(
        id: String,
        label: String,
        baseUrl: String,
        apiKey: String?,
        model: String,
        enabled: Boolean = true,
        dailyCapUnits: Int? = null,
        maxPromptChars: Int? = null,
        maxOutputTokens: Int? = null,
        thinkingParam: String = "",
        toolsSupport: String = "unknown",
        vision: String = "unknown",
        toolRoleFallback: Boolean = false,
        supportsCacheControl: Boolean = false
    ): LlmProvider {
        val trimmedId = id.trim()
        if (!validateId(trimmedId)) {
            throw IllegalArgumentException("id provider tidak valid: '$id' (huruf kecil/angka/underscore, tanpa spasi)")
        }
        // §3.1/C4: yang dicadangkan hanya "local" (id itu nama engine
        // offline — provider kustom tidak boleh memakainya). "openai"
        // TIDAK dicadangkan — id itu dipakai dialog provider untuk
        // provider kustom pertama (MainActivity:12747). "deepseek" juga
        // TIDAK dicadangkan lagi (K1, Fase 6): provider bawaan internal
        // sudah dihapus, jadi id itu bebas dipakai provider kustom.
        if (trimmedId == "local") {
            throw IllegalArgumentException("id '$trimmedId' dicadangkan untuk provider bawaan")
        }
        val trimmedLabel = label.trim()
        val trimmedBase = baseUrl.trim()
        val trimmedModel = model.trim()
        if (trimmedLabel.isBlank()) throw IllegalArgumentException("label provider tidak boleh kosong")
        if (trimmedBase.isBlank()) throw IllegalArgumentException("baseUrl provider tidak boleh kosong")
        if (trimmedModel.isBlank()) throw IllegalArgumentException("model provider tidak boleh kosong")

        val provider = LlmProvider(
            trimmedId, trimmedLabel, LlmKind.OPENAI_COMPAT,
            trimmedBase, apiKey?.trim()?.ifBlank { null }, trimmedModel,
            enabled = enabled, builtIn = false,
            dailyCapUnits = dailyCapUnits,
            maxPromptChars = maxPromptChars,
            maxOutputTokens = maxOutputTokens,
            thinkingParam = thinkingParam.trim(),
            toolsSupport = normalizeToolsSupport(toolsSupport),
            vision = normalizeVision(vision),
            toolRoleFallback = toolRoleFallback,
            supportsCacheControl = supportsCacheControl
        )
        val existing = custom.indexOfFirst { it.id == trimmedId }
        if (existing >= 0) custom[existing] = provider else custom.add(provider)
        return provider
    }

    /**
     * §1 C3: hapus provider kustom. Bila yang dihapus adalah provider
     * AKTIF, aktif pindah ke provider kustom pertama yang configured;
     * bila tidak ada, aktif menjadi "" (belum ada provider aktif —
     * §6.4, bukan fallback ke provider bawaan).
     */
    fun removeCustom(id: String): Boolean {
        val trimmed = id.trim()
        val existing = custom.indexOfFirst { it.id == trimmed }
        if (existing < 0) return false
        custom.removeAt(existing)
        if (activeId == trimmed) {
            activeId = custom.firstOrNull { it.configured }?.id ?: ""
        }
        return true
    }

    @Throws(IllegalArgumentException::class)
    fun setActive(id: String): String {
        val trimmed = id.trim()
        if (find(trimmed) == null) {
            throw IllegalArgumentException("provider '$trimmed' tidak dikenal")
        }
        activeId = trimmed
        return activeId
    }

    fun activeId(): String = activeId

    /** Provider aktif, atau null bila belum ada provider aktif (K1, Fase 6). */
    fun activeProvider(): LlmProvider? = find(activeId)
}
