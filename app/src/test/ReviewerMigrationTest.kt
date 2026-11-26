package com.senzwh.deepharness

// Test JVM murni — Fase 2d (migrasi Models API): reviewer independen.
//
// Menjaga invarian: kind REVIEWER memakai bucket Governor "reviewer"
// (bukan "bg"), routing REVIEWER mengikuti engine (openai → MODELS_API,
// deepseek → DEEPSEEK_WEB, local → LOCAL_OFFLINE), reviewer di engine
// openai INDEPENDEN dari sesi DeepSeek (reviewerIndependent), dan
// reviewer mengirim panggilan teks biasa tanpa tools (toolsToJson null /
// kosong → null, native tool_calls hanya bila tools dipasok).
//
// Dijalankan oleh tools/run_tests.sh. Fungsi pure saja — JANGAN uji
// MainActivity/BgBrain/GroupChat/TeamRun/OcCoord di sini.
//
// CATATAN cek jawaban-kosong (D4): SUDAH tercakup di OpenAiCompatClientTest
// (T3: SSE non-JSON, choices:[], content:null, finish length, non-stream
// kosong, ask() kosong → throw, + pengecualian tool_calls) — TIDAK
// diduplikasi di file ini.

private var failedReviewer = 0

private fun checkReviewer(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedReviewer++; println("  FAIL $name" + detailSuffix)
    }
}

fun main() {
    println("== ReviewerMigrationTest ==")

    // 1. bucketFor: REVIEWER → "reviewer"; BACKGROUND/GRAPH/ROOM tetap "bg".
    checkReviewer("bucketFor REVIEWER → reviewer",
        LlmEngineChat.bucketFor(LlmCallKind.REVIEWER) == Governor.MODELS_BUCKET_REVIEWER,
        "dapat=" + LlmEngineChat.bucketFor(LlmCallKind.REVIEWER))
    checkReviewer("bucketFor BACKGROUND → bg (regresi)",
        LlmEngineChat.bucketFor(LlmCallKind.BACKGROUND) == "bg")
    checkReviewer("bucketFor GRAPH → bg (regresi)",
        LlmEngineChat.bucketFor(LlmCallKind.GRAPH) == "bg")
    checkReviewer("bucketFor ROOM → bg (regresi)",
        LlmEngineChat.bucketFor(LlmCallKind.ROOM) == "bg")

    // 2. routeFor REVIEWER mengikuti engine.
    checkReviewer("routeFor openai + REVIEWER → MODELS_API",
        LlmEngineChat.routeFor("openai", LlmCallKind.REVIEWER) == LlmRoute.MODELS_API,
        "dapat=" + LlmEngineChat.routeFor("openai", LlmCallKind.REVIEWER))
    checkReviewer("routeFor deepseek + REVIEWER → DEEPSEEK_WEB",
        LlmEngineChat.routeFor("deepseek", LlmCallKind.REVIEWER) == LlmRoute.DEEPSEEK_WEB,
        "dapat=" + LlmEngineChat.routeFor("deepseek", LlmCallKind.REVIEWER))
    checkReviewer("routeFor local + REVIEWER → LOCAL_OFFLINE",
        LlmEngineChat.routeFor("local", LlmCallKind.REVIEWER) == LlmRoute.LOCAL_OFFLINE,
        "dapat=" + LlmEngineChat.routeFor("local", LlmCallKind.REVIEWER))

    // 3. requiresWebView: openai tidak menyentuh WebView, deepseek ya.
    checkReviewer("requiresWebView openai → false",
        !LlmEngineChat.requiresWebView("openai"))
    checkReviewer("requiresWebView deepseek → true",
        LlmEngineChat.requiresWebView("deepseek"))

    // 4. reviewerIndependent: hanya openai yang independen.
    checkReviewer("reviewerIndependent openai → true",
        LlmEngineChat.reviewerIndependent("openai"))
    checkReviewer("reviewerIndependent deepseek → false",
        !LlmEngineChat.reviewerIndependent("deepseek"))
    checkReviewer("reviewerIndependent local → false",
        !LlmEngineChat.reviewerIndependent("local"))

    // 5. toolsToJson: reviewer tanpa tools → null (panggilan teks biasa).
    checkReviewer("toolsToJson null → null (reviewer tanpa tools)",
        LlmEngineChat.toolsToJson(null) == null)
    checkReviewer("toolsToJson kosong → null (reviewer tanpa tools)",
        LlmEngineChat.toolsToJson(emptyList()) == null)

    // 6. Governor murni — bucket reviewer.
    val refusal = Governor.refusalModelsText("ProvX", "reviewer", 500, 500)
    checkReviewer("refusalModelsText menyebut provider",
        refusal.contains("ProvX"), "dapat=$refusal")
    checkReviewer("refusalModelsText menyebut bucket reviewer",
        refusal.contains("reviewer"), "dapat=$refusal")
    checkReviewer("normalizeModelsBucket reviewer → reviewer",
        Governor.normalizeModelsBucket("reviewer") == "reviewer")
    checkReviewer("normalizeModelsBucket ngawur → bg",
        Governor.normalizeModelsBucket("ngawur") == "bg")
    checkReviewer("overCapCount 500/500 → true",
        Governor.overCapCount(500, 500))
    checkReviewer("overCapCount 0/500 → false",
        !Governor.overCapCount(0, 500))
    checkReviewer("overCapCount 10/0 → false (cap 0 = tanpa batas)",
        !Governor.overCapCount(10, 0))
    checkReviewer("resolveCapModels null → global 500",
        Governor.resolveCapModels(null, 500) == 500)
    checkReviewer("resolveCapModels 200 → 200 (provider menang)",
        Governor.resolveCapModels(200, 500) == 200)

    println(if (failedReviewer == 0) "SEMUA LULUS" else "GAGAL: $failedReviewer cek")
    if (failedReviewer != 0) kotlin.system.exitProcess(1)
}
