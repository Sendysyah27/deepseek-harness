package com.senzwh.deepharness

/**
 * Registry provider LLM — otak model alternatif selain DeepSeek.
 *
 * Modul ini TIDAK bergantung Android (tidak ada Context/Android API), agar
 * bisa diuji dengan `tools/run_tests.sh` (JVM murni). Persistensi ke prefs/
 * SecureStore hidup di `LlmProviderStore` (Android-only).
 *
 * Provider bawaan DeepSeek tetap menjadi default; provider kustom bertipe
 * OpenAI-compatible (OpenAI, OpenRouter, Groq, Ollama, vLLM, dll).
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
    val builtIn: Boolean = false
) {
    val hasKey: Boolean get() = !apiKey.isNullOrBlank()
    val configured: Boolean
        get() = when (kind) {
            LlmKind.DEEPSEEK -> true
            LlmKind.OPENAI_COMPAT -> enabled && baseUrl.isNotBlank() && model.isNotBlank() && hasKey
        }
}

object LlmProviders {
    const val ID_DEEPSEEK = "deepseek"
    const val ID_LOCAL = "local"
    const val ID_OPENAI = "openai"

    private val deepseek = LlmProvider(
        ID_DEEPSEEK, "DeepSeek (API Internal / Web)", LlmKind.DEEPSEEK,
        "https://chat.deepseek.com/api/v0", null, "deepseek-chat",
        enabled = true, builtIn = true
    )

    private val custom = mutableListOf<LlmProvider>()
    private var activeId: String = ID_DEEPSEEK

    private val idRegex = Regex("[a-z0-9_]+")

    fun all(): List<LlmProvider> = listOf(deepseek) + custom

    fun find(id: String): LlmProvider? = all().firstOrNull { it.id == id }

    fun validateId(id: String): Boolean = id.isNotBlank() && idRegex.matches(id)

    @Throws(IllegalArgumentException::class)
    fun addCustom(
        id: String,
        label: String,
        baseUrl: String,
        apiKey: String?,
        model: String,
        enabled: Boolean = true
    ): LlmProvider {
        val trimmedId = id.trim()
        if (!validateId(trimmedId)) {
            throw IllegalArgumentException("id provider tidak valid: '$id' (huruf kecil/angka/underscore, tanpa spasi)")
        }
        if (trimmedId == ID_DEEPSEEK || trimmedId == ID_LOCAL) {
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
            enabled = enabled, builtIn = false
        )
        val existing = custom.indexOfFirst { it.id == trimmedId }
        if (existing >= 0) custom[existing] = provider else custom.add(provider)
        return provider
    }

    fun removeCustom(id: String): Boolean {
        val trimmed = id.trim()
        val existing = custom.indexOfFirst { it.id == trimmed }
        if (existing < 0) return false
        custom.removeAt(existing)
        if (activeId == trimmed) activeId = ID_DEEPSEEK
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
        if (key.isNullOrBlank()) { onError("API key provider '${provider.id}' belum ada."); return }
        try {
            OpenAiCompatClient.chat(
                provider.baseUrl, key, provider.model, prompt, thinkingEnabled = thinking,
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