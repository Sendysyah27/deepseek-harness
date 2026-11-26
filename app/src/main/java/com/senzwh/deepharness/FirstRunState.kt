package com.senzwh.deepharness

/**
 * F5c — Logika keputusan first-run (JVM-murni, tanpa Context Android).
 *
 * Semua keputusan first-run wizard + NO_PROVIDER + banner + migrasi pref
 * §6.4 tinggal di sini sebagai fungsi pure, supaya bisa dites F5d
 * (FirstRunStateTest.kt, T6) tanpa perangkat. UI (MainActivity) hanya
 * membaca hasilnya dan me-render — tidak ada logika keputusan di UI.
 *
 * Delegasi: predikat yang sudah ada di LlmEngineChat (Fase 4 —
 * setupState / needsProviderSetup / skippedBannerVisible /
 * saveNeedsConfirmation) dipakai ulang lewat sini supaya TIDAK ada dua
 * definisi kebenaran yang bisa berpisah. Fungsi di file ini adalah
 * nama yang dipakai UI + tes F5c; isinya satu baris delegasi.
 *
 * State machine layar [2]→[3]→[4] (§5.3/§5.5/§5.6/§5.8):
 *   SELECT (layar [2] pilih preset) → CREDENTIALS (layar [3] isi
 *   kredensial + tes koneksi) → DONE (layar [4] selesai).
 *   Tanpa provider → NO_PROVIDER (fitur LLM terkunci §5.7).
 *   Skip di layar [3] → flowProviderSkipped=true + banner (§5.7).
 *   Simpan tanpa tes sukses → butuh konfirmasi, BUKAN blokir mentah (§5.6).
 */

/** Langkah wizard tahap provider (§5.3): [2] pilih → [3] kredensial → [4] selesai. */
enum class WizardProviderStep { SELECT, CREDENTIALS, DONE }

object FirstRunState {

    // ---- State machine layar [2]→[3]→[4] ----

    /**
     * Langkah wizard tahap provider dari keadaan sekarang.
     *  - providerSelected=false → SELECT (layar [2], Lanjut nonaktif).
     *  - providerSelected=true, belum simpan → CREDENTIALS (layar [3]).
     *  - saved=true (provider tersimpan + aktif) → DONE (layar [4]).
     */
    fun providerStep(providerSelected: Boolean, saved: Boolean): WizardProviderStep =
        when {
            saved -> WizardProviderStep.DONE
            providerSelected -> WizardProviderStep.CREDENTIALS
            else -> WizardProviderStep.SELECT
        }

    /** Layar [2]: tombol "Lanjut →" hanya aktif sesudah satu kartu dipilih. */
    fun canContinueFromSelect(providerSelected: Boolean): Boolean = providerSelected

    /**
     * Layar [3]: tombol "Simpan & Lanjut" hanya aktif bila label + baseUrl
     * + model terisi (key terisi, kecuali base URL lokal / Ollama).
     */
    fun canSave(
        label: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        baseUrlIsLocal: Boolean = false
    ): Boolean {
        if (label.isBlank() || baseUrl.isBlank() || model.isBlank()) return false
        if (apiKey.isBlank() && !baseUrlIsLocal) return false
        return true
    }

    // ---- NO_PROVIDER (§5.7) ----

    /**
     * True bila fitur LLM harus menolak (state NO_PROVIDER): engine
     * "openai" TAPI tidak ada provider Models API yang configured.
     * Engine "local" TIDAK pernah NO_PROVIDER (agen aturan offline tetap
     * jalan).
     */
    fun isNoProvider(engine: String, activeProvider: LlmProvider?): Boolean =
        engine == "openai" && LlmEngineChat.needsProviderSetup(activeProvider)

    /** Alias predikat mentah: tanpa provider configured → NO_PROVIDER. */
    fun isNoProvider(providerConfigured: Boolean): Boolean = !providerConfigured

    /** State setup provider (§5.7): CONFIGURED menang atas SKIPPED. */
    fun providerState(providerConfigured: Boolean, skipped: Boolean): ProviderSetupState =
        LlmEngineChat.setupState(providerConfigured, skipped)

    // ---- Banner (§5.7) ----

    /**
     * Banner persisten "🧠 Otak belum diatur — [Atur Provider]" tampil
     * bila user eksplisit skip (flowProviderSkipped=true) DAN belum ada
     * provider aktif yang configured. Hilang begitu provider aktif +
     * configured (tes sukses dicatat saat simpan/aktivasi).
     */
    fun shouldShowBanner(flowProviderSkipped: Boolean, providerConfigured: Boolean): Boolean =
        LlmEngineChat.skippedBannerVisible(flowProviderSkipped, providerConfigured)

    // ---- Simpan (§5.6) ----

    /** Simpan tanpa tes koneksi sukses → dialog konfirmasi dulu, bukan blokir mentah. */
    fun saveNeedsConfirmation(testSucceeded: Boolean): Boolean =
        LlmEngineChat.saveNeedsConfirmation(testSucceeded)

    // ---- Migrasi pref §6.4 ----

    /**
     * Migrasi pref `agentEngine` satu kali saat upgrade (§6.4):
     *  - "deepseek" | "qwen" | "kimi" | "bridge" → "openai"
     *  - id provider kustom (dikenal via isCustomProviderId) → "openai"
     *  - "local" dan "openai" tetap
     *  - nilai lain yang tidak dikenal dibiarkan apa adanya (jangan ditebak)
     *
     * Delegasi ke AgentEngines.migrateEnginePref (Fase 0/4 — satu definisi).
     */
    fun migrateAgentEngine(old: String, isCustomProviderId: (String) -> Boolean = { false }): String =
        AgentEngines.migrateEnginePref(old, isCustomProviderId)

    // ---- Preset (§5.5 + keputusan Sendy: preset DeepSeek platform BOLEH) ----

    /** Id preset layar [2], urut tampil. Termasuk "deepseek-platform" (K6/§8.2-1). */
    val PRESET_IDS: List<String> =
        listOf("openai", "openrouter", "groq", "ollama", "deepseek-platform")

    /** Deskripsi satu baris tiap kartu preset layar [2] (§5.5). */
    fun presetDescription(presetId: String): String = when (presetId) {
        "openai" -> "API key dari platform.openai.com"
        "openrouter" -> "API key dari openrouter.ai — banyak model, satu key"
        "groq" -> "API key dari console.groq.com — inferensi cepat"
        "ollama" -> "Ollama: tanpa key, jalan di HP/komputer sendiri"
        "deepseek-platform" -> "API key dari platform.deepseek.com (Models API)"
        else -> "Isi base URL, model, dan API key sendiri"
    }

    /** True bila preset ini tidak butuh API key (Ollama lokal). */
    fun presetNeedsNoKey(presetId: String): Boolean = presetId == "ollama"
}
