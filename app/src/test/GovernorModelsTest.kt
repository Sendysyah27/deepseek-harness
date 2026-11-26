package com.senzwh.deepharness

// Test guard per-provider (T9, §3.2 + §3.3).
// Dijalankan manual dengan kotlinc — JANGAN edit tools/run_tests.sh.
// Memakai FakeContext (test-support-f0b/FakeAndroid.kt — prefs in-memory)
// untuk fungsi Governor/TurnMetrics yang butuh Context.
//
// Menjaga invarian: dailyCapUnits menang atas global; null → global;
// counter terpisah per provider; bobot weightFor; promptCapFor;
// bucket interaktif dicatat tapi TIDAK PERNAH digerbang.

private var failedGov = 0

private fun checkGov(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedGov++; println("  FAIL $name" + detailSuffix)
    }
}

private fun providerCap(id: String, dailyCap: Int?): LlmProvider =
    LlmProvider(id, id, LlmKind.OPENAI_COMPAT, "https://a.b/v1", "k", "m",
        dailyCapUnits = dailyCap)

private fun providerPromptCap(id: String, maxPrompt: Int?): LlmProvider =
    LlmProvider(id, id, LlmKind.OPENAI_COMPAT, "https://a.b/v1", "k", "m",
        maxPromptChars = maxPrompt)

fun main() {
    println("== GovernorModelsTest ==")

    // Resolusi cap (murni)
    checkGov("dailyCapUnits terisi menang atas global",
        Governor.resolveCapModels(100, 500) == 100)
    checkGov("dailyCapUnits null → global",
        Governor.resolveCapModels(null, 500) == 500)
    checkGov("dailyCapUnits 0 → 0 (tanpa batas)",
        Governor.resolveCapModels(0, 500) == 0)
    checkGov("overCapCount cap 0 tidak pernah over",
        !Governor.overCapCount(9999, 0))
    checkGov("overCapCount di batas → over",
        Governor.overCapCount(500, 500))
    checkGov("overCapCount di bawah batas → aman",
        !Governor.overCapCount(499, 500))

    // Bobot weightFor (murni)
    checkGov("weightFor 0 → 1", Governor.weightFor(0) == 1)
    checkGov("weightFor 49.999 → 1", Governor.weightFor(49999) == 1)
    checkGov("weightFor 50.000 → 2", Governor.weightFor(50000) == 2)
    checkGov("weightFor 100.000 → 3", Governor.weightFor(100000) == 3)

    // promptCapFor (§3.3, murni)
    checkGov("promptCapFor terisi menang",
        TurnMetrics.promptCapFor(providerPromptCap("p", 30000)) == 30000)
    checkGov("promptCapFor null → DEFAULT_CAP 200.000",
        TurnMetrics.promptCapFor(providerPromptCap("p", null)) == TurnMetrics.DEFAULT_CAP)
    checkGov("proactiveThreshold tetap 80%",
        TurnMetrics.proactiveThreshold(200000) == 160000)

    // Counter per provider (butuh Context — FakeContext)
    val ctx = FakeContext()
    val provA = providerCap("prov_a", 2)
    val provB = providerCap("prov_b", null)

    checkGov("countModels awal 0", Governor.countModels(ctx, "prov_a", "bg") == 0)
    Governor.bumpModels(ctx, "prov_a", "bg", 1)
    checkGov("bumpModels bg +1", Governor.countModels(ctx, "prov_a", "bg") == 1)
    Governor.bumpModels(ctx, "prov_a", "bg", 1)
    checkGov("bumpModels bg +1 lagi → 2", Governor.countModels(ctx, "prov_a", "bg") == 2)
    checkGov("counter terpisah per provider (B tetap 0)",
        Governor.countModels(ctx, "prov_b", "bg") == 0)
    checkGov("counter terpisah per bucket (team tetap 0)",
        Governor.countModels(ctx, "prov_a", "team") == 0)
    Governor.bumpModels(ctx, "prov_a", "team", 3)
    checkGov("bumpModels team +3", Governor.countModels(ctx, "prov_a", "team") == 3)
    checkGov("counter bg tidak ikut naik", Governor.countModels(ctx, "prov_a", "bg") == 2)

    // Cap + gerbang per provider
    checkGov("capModels dailyCapUnits=2 → 2", Governor.capModels(ctx, provA) == 2)
    checkGov("capModels null → global 500", Governor.capModels(ctx, provB) == 500)
    checkGov("overCapModels bg 2/2 → true", Governor.overCapModels(ctx, provA, "bg"))
    checkGov("overCapModels team 3/2 → true", Governor.overCapModels(ctx, provA, "team"))
    checkGov("overCapModels provider B 0/500 → false", !Governor.overCapModels(ctx, provB, "bg"))
    val refusal = Governor.refusalModels(ctx, provA, "bg")
    checkGov("refusalModels menyebut provider + angka",
        refusal != null && refusal.contains("prov_a") && refusal.contains("2/2"),
        "dapat=$refusal")
    checkGov("refusalModels null bila aman",
        Governor.refusalModels(ctx, provB, "bg") == null)

    // Bucket interaktif: dicatat, TIDAK PERNAH digerbang
    Governor.bumpModels(ctx, "prov_a", "interactive", 999)
    checkGov("interactive tercatat",
        Governor.countModels(ctx, "prov_a", "interactive") == 999)
    checkGov("interactive TIDAK PERNAH over-cap",
        !Governor.overCapModels(ctx, provA, "interactive"))
    checkGov("interactive refusal selalu null",
        Governor.refusalModels(ctx, provA, "interactive") == null)

    // Cap global dari prefs (govDailyCap)
    ctx.getSharedPreferences("harness", 0).edit().putInt("govDailyCap", 100).apply()
    checkGov("capModels ikut govDailyCap prefs bila dailyCapUnits null",
        Governor.capModels(ctx, provB) == 100)

    // clearModels (dipakai delete provider)
    Governor.clearModels(ctx, "prov_a")
    checkGov("clearModels hapus counter bg",
        Governor.countModels(ctx, "prov_a", "bg") == 0)
    checkGov("clearModels hapus counter team",
        Governor.countModels(ctx, "prov_a", "team") == 0)
    checkGov("clearModels hapus counter interactive",
        Governor.countModels(ctx, "prov_a", "interactive") == 0)
    checkGov("clearModels tidak menyentuh provider lain",
        Governor.countModels(ctx, "prov_b", "bg") == 0)

    // TurnMetrics snapshot: field provider + engine (§3.3)
    val ctx2 = FakeContext()
    TurnMetrics.beginTurn(ctx2)
    TurnMetrics.recordProvider("openrouter", "openai")
    TurnMetrics.recordPromptTotal(123)
    TurnMetrics.finish(ctx2, "ok")
    val snap = TurnMetrics.loadSnapshot(ctx2) ?: ""
    checkGov("snapshot memuat provider",
        snap.contains("\"provider\":\"openrouter\""), "snap=$snap")
    checkGov("snapshot memuat engine",
        snap.contains("\"engine\":\"openai\""), "snap=$snap")
    checkGov("snapshot memuat total_prompt_char",
        snap.contains("\"total_prompt_char\":123"), "snap=$snap")

    println(if (failedGov == 0) "SEMUA LULUS" else "GAGAL: $failedGov cek")
    if (failedGov != 0) kotlin.system.exitProcess(1)
}
