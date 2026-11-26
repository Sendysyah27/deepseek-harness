package com.senzwh.deepharness

// Test JVM murni — Fase 3d (migrasi Models API): room / tim / graph.
//
// Menjaga invarian: kind ROOM dan GRAPH masuk bucket Governor "bg"
// (kerja otonom = bucket latar, plan §3.2), kind TEAM masuk bucket
// "team"; routing ROOM/TEAM/GRAPH mengikuti engine (openai → MODELS_API,
// legacy/tak dikenal → MODELS_API, local → LOCAL_OFFLINE); TIDAK ADA
// engine yang menyentuh WebView (K1); teks penolakan Governor menyebut
// provider + label bucket "latar"/"tim"; dan jalur room/tim/graph
// mengirim prompt teks biasa tanpa tools (toolsToJson null/kosong → null).
//
// Dijalankan oleh tools/run_tests.sh. Fungsi pure saja — JANGAN uji
// MainActivity/BgBrain/GroupChat/TeamRun/OcCoord di sini.
//
// CATATAN requiresWebView: signaturenya HANYA engine
// (LlmEngineChat.kt: requiresWebView(engine: String)) — tidak menerima
// kind — jadi diuji di level engine, bukan per kind.
//
// CATATAN toolsToJson: null/kosong → null SUDAH diuji penuh di
// ReviewerMigrationTest (Fase 2); di sini cukup 1 cek penanda bahwa
// aturan yang sama tetap berlaku untuk jalur Fase 3.
//
// CATATAN cek jawaban-kosong (D4): SUDAH tercakup di OpenAiCompatClientTest
// (T3: SSE non-JSON, choices:[], content:null, finish length, non-stream
// kosong, ask() kosong → throw, + pengecualian tool_calls) — TIDAK
// diduplikasi di file ini.
//
// CATATAN call site (temuan untuk koordinator, 26 Nov): dari seluruh
// main, baru GRAPH yang punya pemanggil nyata (BgBrain.kt:274, digerbang
// engine openai); ROOM dan TEAM baru ada di enum + fungsi murni
// bucketFor/routeFor — belum ada call site yang mengoper kind tersebut.
// File ini mengunci kontrak murninya supaya pemanggil Fase 3 nanti
// langsung konsisten.

private var failedRoomTeamGraph = 0

private fun checkRoomTeamGraph(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedRoomTeamGraph++; println("  FAIL $name" + detailSuffix)
    }
}

fun main() {
    println("== RoomTeamGraphMigrationTest ==")

    // 1. bucketFor: ROOM/GRAPH → "bg", TEAM → "team" (plan §3.2).
    checkRoomTeamGraph("bucketFor ROOM → bg",
        LlmEngineChat.bucketFor(LlmCallKind.ROOM) == "bg",
        "dapat=" + LlmEngineChat.bucketFor(LlmCallKind.ROOM))
    checkRoomTeamGraph("bucketFor GRAPH → bg",
        LlmEngineChat.bucketFor(LlmCallKind.GRAPH) == "bg",
        "dapat=" + LlmEngineChat.bucketFor(LlmCallKind.GRAPH))
    checkRoomTeamGraph("bucketFor TEAM → team",
        LlmEngineChat.bucketFor(LlmCallKind.TEAM) == Governor.MODELS_BUCKET_TEAM,
        "dapat=" + LlmEngineChat.bucketFor(LlmCallKind.TEAM))
    checkRoomTeamGraph("bucketFor BACKGROUND → bg (regresi)",
        LlmEngineChat.bucketFor(LlmCallKind.BACKGROUND) == "bg")
    checkRoomTeamGraph("bucketFor INTERACTIVE → interactive",
        LlmEngineChat.bucketFor(LlmCallKind.INTERACTIVE) == "interactive")

    // 2. routeFor ROOM/TEAM/GRAPH mengikuti engine.
    checkRoomTeamGraph("routeFor openai + ROOM → MODELS_API",
        LlmEngineChat.routeFor("openai", LlmCallKind.ROOM) == LlmRoute.MODELS_API,
        "dapat=" + LlmEngineChat.routeFor("openai", LlmCallKind.ROOM))
    checkRoomTeamGraph("routeFor openai + TEAM → MODELS_API",
        LlmEngineChat.routeFor("openai", LlmCallKind.TEAM) == LlmRoute.MODELS_API,
        "dapat=" + LlmEngineChat.routeFor("openai", LlmCallKind.TEAM))
    checkRoomTeamGraph("routeFor openai + GRAPH → MODELS_API",
        LlmEngineChat.routeFor("openai", LlmCallKind.GRAPH) == LlmRoute.MODELS_API,
        "dapat=" + LlmEngineChat.routeFor("openai", LlmCallKind.GRAPH))
    checkRoomTeamGraph("routeFor deepseek (legacy) + ROOM → MODELS_API",
        LlmEngineChat.routeFor("deepseek", LlmCallKind.ROOM) == LlmRoute.MODELS_API,
        "dapat=" + LlmEngineChat.routeFor("deepseek", LlmCallKind.ROOM))
    checkRoomTeamGraph("routeFor deepseek (legacy) + TEAM → MODELS_API",
        LlmEngineChat.routeFor("deepseek", LlmCallKind.TEAM) == LlmRoute.MODELS_API,
        "dapat=" + LlmEngineChat.routeFor("deepseek", LlmCallKind.TEAM))
    checkRoomTeamGraph("routeFor deepseek (legacy) + GRAPH → MODELS_API",
        LlmEngineChat.routeFor("deepseek", LlmCallKind.GRAPH) == LlmRoute.MODELS_API,
        "dapat=" + LlmEngineChat.routeFor("deepseek", LlmCallKind.GRAPH))
    checkRoomTeamGraph("routeFor local + ROOM → LOCAL_OFFLINE",
        LlmEngineChat.routeFor("local", LlmCallKind.ROOM) == LlmRoute.LOCAL_OFFLINE,
        "dapat=" + LlmEngineChat.routeFor("local", LlmCallKind.ROOM))
    checkRoomTeamGraph("routeFor local + TEAM → LOCAL_OFFLINE",
        LlmEngineChat.routeFor("local", LlmCallKind.TEAM) == LlmRoute.LOCAL_OFFLINE,
        "dapat=" + LlmEngineChat.routeFor("local", LlmCallKind.TEAM))
    checkRoomTeamGraph("routeFor local + GRAPH → LOCAL_OFFLINE",
        LlmEngineChat.routeFor("local", LlmCallKind.GRAPH) == LlmRoute.LOCAL_OFFLINE,
        "dapat=" + LlmEngineChat.routeFor("local", LlmCallKind.GRAPH))

    // 3. requiresWebView (K1): TIDAK ADA engine yang menyentuh WebView.
    checkRoomTeamGraph("requiresWebView openai → false",
        !LlmEngineChat.requiresWebView("openai"))
    checkRoomTeamGraph("requiresWebView legacy → false",
        !LlmEngineChat.requiresWebView("deepseek"))
    checkRoomTeamGraph("requiresWebView local → false",
        !LlmEngineChat.requiresWebView("local"))

    // 4. Governor murni — bucket baru bg (room/graph) dan team.
    val refusalBg = Governor.refusalModelsText("ProvX", "bg", 500, 500)
    checkRoomTeamGraph("refusalModelsText bg menyebut provider + 'latar'",
        refusalBg.contains("ProvX") && refusalBg.contains("latar"), "dapat=$refusalBg")
    val refusalTeam = Governor.refusalModelsText("ProvX", "team", 200, 200)
    checkRoomTeamGraph("refusalModelsText team menyebut provider + 'tim'",
        refusalTeam.contains("ProvX") && refusalTeam.contains("tim"), "dapat=$refusalTeam")
    checkRoomTeamGraph("overCapCount: cap 0 tanpa batas, di batas over, di bawah aman",
        !Governor.overCapCount(10, 0) && Governor.overCapCount(500, 500) &&
            !Governor.overCapCount(499, 500))
    checkRoomTeamGraph("resolveCapModels: null → global, terisi → provider menang",
        Governor.resolveCapModels(null, 500) == 500 &&
            Governor.resolveCapModels(200, 500) == 200)

    // 5. toolsToJson: penanda — jalur Fase 3 tanpa tools tetap null
    // (kasus penuh null/kosong sudah di ReviewerMigrationTest Fase 2).
    checkRoomTeamGraph("toolsToJson null/kosong → null (jalur Fase 3 tanpa tools)",
        LlmEngineChat.toolsToJson(null) == null &&
            LlmEngineChat.toolsToJson(emptyList()) == null)

    println(if (failedRoomTeamGraph == 0) "SEMUA LULUS" else "GAGAL: $failedRoomTeamGraph cek")
    if (failedRoomTeamGraph != 0) kotlin.system.exitProcess(1)
}
