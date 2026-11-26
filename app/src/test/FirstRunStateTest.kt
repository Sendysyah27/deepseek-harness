package com.senzwh.deepharness

// Test JVM murni untuk logika first-run (T6, §5.7 + §6.4).
// Dijalankan manual dengan kotlinc — JANGAN edit tools/run_tests.sh.
//
// Menjaga invarian: tanpa provider → fitur terkunci (NO_PROVIDER);
// skip → banner + flowProviderSkipped; simpan tanpa tes sukses →
// butuh konfirmasi; migrasi pref memetakan deepseek/id kustom → openai.

private var failedFirst = 0

private fun checkFirst(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedFirst++; println("  FAIL $name" + detailSuffix)
    }
}

private fun configuredProvider(id: String): LlmProvider =
    LlmProvider(id, "L", LlmKind.OPENAI_COMPAT, "https://a.b/v1", "kunci", "m")

private fun unconfiguredProvider(id: String): LlmProvider =
    LlmProvider(id, "L", LlmKind.OPENAI_COMPAT, "https://a.b/v1", null, "m")

fun main() {
    println("== FirstRunStateTest ==")

    // NO_PROVIDER → fitur terkunci (§5.7)
    checkFirst("tanpa provider → NO_PROVIDER",
        LlmEngineChat.setupState(false, false) == ProviderSetupState.NO_PROVIDER)
    checkFirst("needsProviderSetup null → true",
        LlmEngineChat.needsProviderSetup(null))
    checkFirst("needsProviderSetup preset deepseek-platform tanpa key → true",
        LlmEngineChat.needsProviderSetup(LlmProviders.preset("deepseek-platform")))
    checkFirst("needsProviderSetup custom tanpa key → true",
        LlmEngineChat.needsProviderSetup(unconfiguredProvider("nokey")))
    checkFirst("needsProviderSetup custom configured → false",
        !LlmEngineChat.needsProviderSetup(configuredProvider("ok1")))
    checkFirst("provider configured → CONFIGURED",
        LlmEngineChat.setupState(true, false) == ProviderSetupState.CONFIGURED)
    checkFirst("provider configured menang atas skip",
        LlmEngineChat.setupState(true, true) == ProviderSetupState.CONFIGURED)

    // Skip → flowProviderSkipped + banner (§5.7)
    checkFirst("skip tanpa provider → SKIPPED",
        LlmEngineChat.setupState(false, true) == ProviderSetupState.SKIPPED)
    checkFirst("banner tampil saat skip + belum configured",
        LlmEngineChat.skippedBannerVisible(true, false))
    checkFirst("banner hilang sesudah provider configured",
        !LlmEngineChat.skippedBannerVisible(true, true))
    checkFirst("banner tidak tampil bila tidak skip",
        !LlmEngineChat.skippedBannerVisible(false, false))

    // Simpan tanpa tes sukses → butuh konfirmasi (§5.6)
    checkFirst("simpan tanpa tes sukses → butuh konfirmasi",
        LlmEngineChat.saveNeedsConfirmation(false))
    checkFirst("simpan sesudah tes sukses → tanpa konfirmasi",
        !LlmEngineChat.saveNeedsConfirmation(true))

    // Migrasi pref (§6.4, T6)
    checkFirst("migrasi deepseek → openai",
        AgentEngines.migrateEnginePref("deepseek") == "openai")
    checkFirst("migrasi bridge → openai",
        AgentEngines.migrateEnginePref("bridge") == "openai")
    checkFirst("migrasi qwen → openai",
        AgentEngines.migrateEnginePref("qwen") == "openai")
    checkFirst("migrasi kimi → openai",
        AgentEngines.migrateEnginePref("kimi") == "openai")
    checkFirst("migrasi local tetap local",
        AgentEngines.migrateEnginePref("local") == "local")
    checkFirst("migrasi openai tetap openai",
        AgentEngines.migrateEnginePref("openai") == "openai")
    LlmProviders.addCustom("migprov", "M", "https://a.b/v1", "k", "m", true)
    checkFirst("migrasi id kustom → openai",
        AgentEngines.migrateEnginePref("migprov") == "openai")
    checkFirst("migrasi id tak dikenal dibiarkan",
        AgentEngines.migrateEnginePref("zzz-tak-dikenal") == "zzz-tak-dikenal")
    LlmProviders.removeCustom("migprov")

    // ------------------------------------------- T6: state machine [2]→[3]→[4]
    //  + migrateAgentEngine (Fase 5, FirstRunState.kt dari F5c).
    //  API final F5c: providerStep(providerSelected, saved) + canContinueFromSelect
    //  (bukan nextStep Int — disesuaikan koordinator saat merge).
    checkFirst("langkah setup 2 → 3 (pilih → kredensial)",
        FirstRunState.providerStep(true, false) == WizardProviderStep.CREDENTIALS)
    checkFirst("langkah setup 3 → 4 (simpan → selesai)",
        FirstRunState.providerStep(true, true) == WizardProviderStep.DONE)
    checkFirst("langkah setup 2 belum pilih → tetap pilih",
        FirstRunState.providerStep(false, false) == WizardProviderStep.SELECT)
    checkFirst("canContinueFromSelect true/false",
        FirstRunState.canContinueFromSelect(true) && !FirstRunState.canContinueFromSelect(false))
    checkFirst("migrateAgentEngine deepseek → openai",
        FirstRunState.migrateAgentEngine("deepseek") == "openai")
    LlmProviders.addCustom("migprov2", "M", "https://a.b/v1", "k", "m", true)
    checkFirst("migrateAgentEngine id kustom → openai",
        FirstRunState.migrateAgentEngine("migprov2") { it == "migprov2" } == "openai")
    LlmProviders.removeCustom("migprov2")
    checkFirst("migrateAgentEngine local tetap local",
        FirstRunState.migrateAgentEngine("local") == "local")
    checkFirst("migrateAgentEngine openai tetap openai",
        FirstRunState.migrateAgentEngine("openai") == "openai")

    println(if (failedFirst == 0) "SEMUA LULUS" else "GAGAL: $failedFirst cek")
    if (failedFirst != 0) kotlin.system.exitProcess(1)
}
