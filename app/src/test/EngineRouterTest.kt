package com.senzwh.deepharness

// Test JVM murni untuk routing engine (T10 — regresi D1/D2) + fungsi pure
// LlmEngineChat lainnya. Dijalankan manual dengan kotlinc — JANGAN edit
// tools/run_tests.sh.
//
// Menjaga invarian: engine openai TIDAK PERNAH menyentuh WebView dan
// TIDAK ADA kind yang jatuh ke DeepSeek saat engine openai.

private var failedRouter = 0

private fun checkRouter(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedRouter++; println("  FAIL $name" + detailSuffix)
    }
}

fun main() {
    println("== EngineRouterTest ==")

    // requiresWebView (T10)
    checkRouter("requiresWebView openai → false", !LlmEngineChat.requiresWebView("openai"))
    checkRouter("requiresWebView deepseek → true", LlmEngineChat.requiresWebView("deepseek"))
    checkRouter("requiresWebView local → false", !LlmEngineChat.requiresWebView("local"))
    checkRouter("requiresWebView tak dikenal → true (default aman)",
        LlmEngineChat.requiresWebView("qwen"))

    // routeFor semua kind di engine openai (T10)
    for (kind in LlmCallKind.values()) {
        checkRouter("routeFor openai + $kind → MODELS_API",
            LlmEngineChat.routeFor("openai", kind) == LlmRoute.MODELS_API,
            "dapat=" + LlmEngineChat.routeFor("openai", kind))
    }
    checkRouter("routeFor deepseek + INTERACTIVE → DEEPSEEK_WEB",
        LlmEngineChat.routeFor("deepseek", LlmCallKind.INTERACTIVE) == LlmRoute.DEEPSEEK_WEB)
    checkRouter("routeFor deepseek + BACKGROUND → DEEPSEEK_WEB",
        LlmEngineChat.routeFor("deepseek", LlmCallKind.BACKGROUND) == LlmRoute.DEEPSEEK_WEB)
    checkRouter("routeFor local + INTERACTIVE → LOCAL_OFFLINE",
        LlmEngineChat.routeFor("local", LlmCallKind.INTERACTIVE) == LlmRoute.LOCAL_OFFLINE)
    checkRouter("routeFor local + BACKGROUND → LOCAL_OFFLINE",
        LlmEngineChat.routeFor("local", LlmCallKind.BACKGROUND) == LlmRoute.LOCAL_OFFLINE)

    // bucketFor (§3.2)
    checkRouter("bucketFor INTERACTIVE → interactive",
        LlmEngineChat.bucketFor(LlmCallKind.INTERACTIVE) == "interactive")
    checkRouter("bucketFor BACKGROUND → bg",
        LlmEngineChat.bucketFor(LlmCallKind.BACKGROUND) == "bg")
    checkRouter("bucketFor REVIEWER → reviewer",
        LlmEngineChat.bucketFor(LlmCallKind.REVIEWER) == "reviewer")
    checkRouter("bucketFor TEAM → team",
        LlmEngineChat.bucketFor(LlmCallKind.TEAM) == "team")
    checkRouter("bucketFor ROOM → bg",
        LlmEngineChat.bucketFor(LlmCallKind.ROOM) == "bg")
    checkRouter("bucketFor GRAPH → bg",
        LlmEngineChat.bucketFor(LlmCallKind.GRAPH) == "bg")

    // thinkingAllowed (§2.5): hanya bila record provider mengizinkan
    val pThink = LlmProvider("t1", "T", LlmKind.OPENAI_COMPAT, "https://a.b/v1", "k", "m",
        thinkingParam = "thinking_enabled")
    val pNoThink = LlmProvider("t2", "T", LlmKind.OPENAI_COMPAT, "https://a.b/v1", "k", "m",
        thinkingParam = "")
    checkRouter("thinking diizinkan bila thinkingParam terisi",
        LlmEngineChat.thinkingAllowed(pThink, true))
    checkRouter("thinking TIDAK diteruskan bila thinkingParam kosong",
        !LlmEngineChat.thinkingAllowed(pNoThink, true))
    checkRouter("thinking false tetap false walau param terisi",
        !LlmEngineChat.thinkingAllowed(pThink, false))

    // Pesan kanonik (§0.1)
    checkRouter("pesan NO_PROVIDER eksak",
        LlmEngineChat.NO_PROVIDER_MESSAGE ==
            "Otak belum diatur — atur provider dulu di Setup > 73. Provider LLM.",
        "dapat=" + LlmEngineChat.NO_PROVIDER_MESSAGE)
    checkRouter("pesan belum-siap eksak",
        LlmEngineChat.providerNotReadyMessage("openai") ==
            "Provider 'openai' belum siap (butuh OpenAI-compatible + API key). Atur via Setup > 73. Provider LLM.",
        "dapat=" + LlmEngineChat.providerNotReadyMessage("openai"))
    checkRouter("pesan jawaban-kosong menyebut provider",
        LlmEngineChat.emptyResultError("groq").contains("groq"))

    // toolsToJson (§2.2 — konversi List<ToolDef> → JSONArray skema OpenAI)
    checkRouter("toolsToJson null → null", LlmEngineChat.toolsToJson(null) == null)
    checkRouter("toolsToJson kosong → null", LlmEngineChat.toolsToJson(emptyList()) == null)
    val toolJson = LlmEngineChat.toolsToJson(
        listOf(
            ToolRegistry.ToolDef(
                "t1", "s", "{}", "deskripsi t1", ToolRegistry.Kind.READ,
                listOf(
                    ToolRegistry.ArgSpec("path", "string", true),
                    ToolRegistry.ArgSpec("mode", "string", false, listOf("a", "b"))
                )
            )
        )
    )!!
    checkRouter("toolsToJson 1 tool", toolJson.length() == 1)
    val fn0 = toolJson.getJSONObject(0).getJSONObject("function")
    checkRouter("toolsToJson type=function",
        toolJson.getJSONObject(0).getString("type") == "function")
    checkRouter("toolsToJson nama", fn0.getString("name") == "t1")
    checkRouter("toolsToJson deskripsi", fn0.getString("description") == "deskripsi t1")
    val params0 = fn0.getJSONObject("parameters")
    checkRouter("toolsToJson parameters type=object",
        params0.getString("type") == "object")
    checkRouter("toolsToJson required hanya arg wajib",
        params0.getJSONArray("required").length() == 1 &&
            params0.getJSONArray("required").getString(0) == "path")
    checkRouter("toolsToJson enum terbawa",
        params0.getJSONObject("properties").getJSONObject("mode")
            .getJSONArray("enum").length() == 2)
    checkRouter("toolsToJson tipe arg string",
        params0.getJSONObject("properties").getJSONObject("path")
            .getString("type") == "string")

    println(if (failedRouter == 0) "SEMUA LULUS" else "GAGAL: $failedRouter cek")
    if (failedRouter != 0) kotlin.system.exitProcess(1)
}
