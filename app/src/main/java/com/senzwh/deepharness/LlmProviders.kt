package com.senzwh.deepharness

/**
 * Registry provider LLM — otak model alternatif selain DeepSeek.
 *
 * Modul ini TIDAK bergantung Android (tidak ada Context/Android API), agar
 * bisa diuji dengan `tools/run_tests.sh` (JVM murni). Persistensi ke prefs/
 * SecureStore hidup di `LlmProviderStore` (Android-only).
 *
 * Provider bawaan DeepSeek tetap menjadi default selama transisi; provider
 * kustom bertipe OpenAI-compatible (OpenAI, OpenRouter, Groq, Ollama, vLLM,
 * dll). Id provider kustom BUKAN engine — engine hanya
 * "openai" | "local" | "deepseek" (lihat AgentEngines).
 *
 * §3.1 (Fase 0b): field guard per-provider di LlmProvider (null = pakai
 * default global) + daftar PRESETS (template, BUKAN provider aktif —
 * memilih preset hanya mengisi field; key tetap diisi user sendiri).
 */
enum class LlmKind { DEEPSEEK, OPENAI_COMPAT }

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
    val toolRoleFallback: Boolean = false
) {
    val hasKey: Boolean get() = !apiKey.isNullOrBlank()
    val configured: Boolean
        get() = when (kind) {
            LlmKind.DEEPSEEK -> true
            // Provider lokal (Ollama di HP sendiri) boleh tanpa key —
            // key hanya wajib bila base URL bukan lokal.
            LlmKind.OPENAI_COMPAT -> enabled && baseUrl.isNotBlank() && model.isNotBlank() &&
                (hasKey || LlmProviders.isLocalBaseUrl(baseUrl))
        }
}

object LlmProviders {
    const val ID_DEEPSEEK = "deepseek"
    const val ID_OPENAI = "openai"

    private val deepseek = LlmProvider(
        ID_DEEPSEEK, "DeepSeek (API Internal / Web)", LlmKind.DEEPSEEK,
        "https://chat.deepseek.com/api/v0", null, "deepseek-chat",
        enabled = true, builtIn = true
    )

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
    private var activeId: String = ID_DEEPSEEK

    private val idRegex = Regex("[a-z0-9_]+")

    fun all(): List<LlmProvider> = listOf(deepseek) + custom

    fun find(id: String): LlmProvider? = all().firstOrNull { it.id == id }

    fun validateId(id: String): Boolean = id.isNotBlank() && idRegex.matches(id)

    /** True bila base URL menunjuk server lokal di HP ini (tanpa key). */
    fun isLocalBaseUrl(baseUrl: String): Boolean {
        val u = baseUrl.trim().lowercase()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        val host = u.substringAfter("://").substringBefore("/").substringBefore(":")
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    }

    /** Normalisasi nilai toolsSupport ke {native, unknown, unsupported}. */
    fun normalizeToolsSupport(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "native", "unsupported" -> raw.trim().lowercase()
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
        toolRoleFallback: Boolean = false
    ): LlmProvider {
        val trimmedId = id.trim()
        if (!validateId(trimmedId)) {
            throw IllegalArgumentException("id provider tidak valid: '$id' (huruf kecil/angka/underscore, tanpa spasi)")
        }
        // §3.1/C4: yang dicadangkan hanya "deepseek" dan "local".
        // "openai" TIDAK dicadangkan — id itu dipakai dialog provider
        // untuk provider kustom pertama (MainActivity:12747).
        if (trimmedId == ID_DEEPSEEK || trimmedId == "local") {
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
            toolRoleFallback = toolRoleFallback
        )
        val existing = custom.indexOfFirst { it.id == trimmedId }
        if (existing >= 0) custom[existing] = provider else custom.add(provider)
        return provider
    }

    /**
     * §1 C3: hapus provider kustom. Bila yang dihapus adalah provider
     * AKTIF, aktif pindah ke provider kustom pertama yang configured;
     * bila tidak ada, jatuh ke deepseek (default masa transisi — sesudah
     * §6.4 perilaku ini diganti "belum ada provider aktif").
     */
    fun removeCustom(id: String): Boolean {
        val trimmed = id.trim()
        val existing = custom.indexOfFirst { it.id == trimmed }
        if (existing < 0) return false
        custom.removeAt(existing)
        if (activeId == trimmed) {
            activeId = custom.firstOrNull { it.configured }?.id ?: ID_DEEPSEEK
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

    fun activeProvider(): LlmProvider = find(activeId) ?: deepseek

    /** Memanggil provider aktif (OpenAI-compatible) via OpenAiCompatClient.
     *  onPartial dipanggil tiap ada fragmen jawaban baru (streaming).
     *  onDone dipanggil sekali saat selesai: (content, thinking).
     *  onError dipanggil bila gagal. */
    fun chat(apiKey: String?, provider: LlmProvider?, prompt: String,
             thinking: Boolean,
             onPartial: (String) -> Unit,
             onDone: (String, String) -> Unit,
             onError: (String) -> Unit) {
        if (provider == null) { onError("Provider LLM aktif belum dikonfigurasi."); return }
        if (provider.kind != LlmKind.OPENAI_COMPAT) { onError("Provider aktif bukan OpenAI-compatible."); return }
        val key = apiKey?.takeIf { it.isNotBlank() }
        if (key.isNullOrBlank() && !isLocalBaseUrl(provider.baseUrl)) { onError("API key provider '${provider.id}' belum ada."); return }
        try {
            OpenAiCompatClient.chat(
                provider.baseUrl, key ?: "", provider.model, prompt, thinkingEnabled = thinking,
                onPartial = onPartial,
                onDone = { content, think -> onDone(content, think) },
                onError = onError
            )
        } catch (e: Exception) {
            onError(sanitize(e.message ?: e.toString()))
        }
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("(?i)Bearer\\s+\\S+"), "Bearer <redacted>")
}
