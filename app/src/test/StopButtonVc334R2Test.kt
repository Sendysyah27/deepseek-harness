package com.senzwh.deepharness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

// Test JVM murni — vc334 Ronde 2: RESUME + PENUTUP + PLANNER + BATCH
// (B8, B11, B9, B4, A3, A5). Dijalankan oleh tools/run_tests.sh.
//
// APA YANG DITES DI SINI (jujur):
//  1. B9 (live, HTTP lokal): call penutup tools=null BENAR-BENAR
//     tiba di provider tanpa kunci "tools" dan tanpa "tool_choice"
//     (body request ditangkap server mock), sementara call biasa
//     tetap membawa "tools" + tool_choice auto. Ini membuktikan
//     mekanisme penutup di lapisan HTTP, bukan cuma di parameter.
//  2. B8/B11/B9/B4/A3/A5 (sumber): MainActivity (~18 rb baris,
//     Activity Android) tidak bisa dikompilasi/diinstansiasi di JVM —
//     perilaku runtime (turnActive resume, ■ berfungsi, planner
//     kena cap, batch berhenti, pill sesudah konfirmasi) HANYA bisa
//     dibuktikan baca kode (cek sumber di bawah) + tes di HP. Yang
//     dicek di sini adalah WIRING-nya: satu fungsi reset bersama,
//     tidak ada blok reset kedua yang bisa drift; flag penutup
//     diteruskan sampai callNativeVc5b; planner dihitung + kena cap;
//     loop batch mengecek state stop R1 tiap iterasi; pill
//     "Dihentikan" user-stop hanya dipancarkan endTurnStopped().
//
// APA YANG TIDAK DITES DI SINI:
//  - Perilaku runtime di HP (resume benar-benar membuka giliran +
//    ■ tampil, make_plan ke-3 mengunci StuckDetector, dst) —
//    butuh tes HP oleh koordinator.

private var failedR2 = 0

private fun checkR2(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedR2++; println("  FAIL $name" + detailSuffix)
    }
}

private const val FAKE_KEY_R2 = "<redacted>"

/** Potongan MainActivity di antara dua penanda (start inklusif). */
private fun regionR2(text: String, startMarker: String, endMarker: String): String {
    val s = text.indexOf(startMarker)
    if (s < 0) return ""
    val e = text.indexOf(endMarker, s + startMarker.length)
    return if (e < 0) text.substring(s) else text.substring(s, e)
}

private fun startServerR2(handler: (HttpExchange) -> Unit): HttpServer {
    val server = HttpServer.create(InetSocketAddress("::1", 0), 0)
    server.createContext("/") { ex ->
        try {
            handler(ex)
        } catch (e: Exception) {
            try { ex.sendResponseHeaders(500, -1) } catch (_: Exception) {}
        } finally {
            try { ex.responseBody.close() } catch (_: Exception) {}
        }
    }
    server.executor = null
    server.start()
    return server
}

private fun baseUrlR2(server: HttpServer): String =
    "http://[::1]:${server.address.port}"

/** Satu panggilan chatMessages ke server mock; kembalikan body request yang ditangkap. */
private fun captureBodyR2(tools: JSONArray?, toolChoice: Any? = null): Pair<String?, String?> {
    val captured = AtomicReference<String?>(null)
    val server = startServerR2 { ex ->
        captured.set(ex.requestBody.readAllBytes().toString(Charsets.UTF_8))
        ex.responseHeaders.set("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { out ->
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n".toByteArray(Charsets.UTF_8))
            out.flush()
            out.write("data: [DONE]\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }
    try {
        var err: String? = null
        val finished = CountDownLatch(1)
        val worker = Thread {
            try {
                OpenAiCompatClient.chatMessages(baseUrlR2(server), FAKE_KEY_R2, "m1",
                    listOf(ChatMessage("user", "hai")), tools, false,
                    onPartial = {}, onDeltaToolCall = {},
                    onDone = { finished.countDown() },
                    onError = { err = it; finished.countDown() },
                    toolChoice = toolChoice)
            } catch (e: Exception) {
                err = "THREW: ${e.message}"; finished.countDown()
            }
        }.apply { isDaemon = true }
        worker.start()
        val ok = finished.await(10, TimeUnit.SECONDS)
        if (!ok) return Pair(captured.get(), "timeout")
        return Pair(captured.get(), err)
    } finally {
        server.stop(0)
    }
}

fun main() {
    println("== StopButtonVc334R2Test ==")

    // ------------------------------------------------------- B9 live: body request penutup
    run {
        val (bodyNull, errNull) = captureBodyR2(null, null)
        val objNull = try { if (bodyNull != null) JSONObject(bodyNull) else null } catch (e: Exception) { null }
        checkR2("B9 live: call tools=null sampai ke onDone (bukan error)",
            errNull == null, "err=$errNull body=$bodyNull")
        checkR2("B9 live: body tools=null TIDAK membawa kunci tools",
            objNull != null && !objNull.has("tools"), "body=$bodyNull")
        checkR2("B9 live: body tools=null TIDAK membawa tool_choice",
            objNull != null && !objNull.has("tool_choice"), "body=$bodyNull")

        val tools = JSONArray().put(
            JSONObject().put("type", "function").put("function",
                JSONObject().put("name", "read_file").put("description", "baca file")
                    .put("parameters", JSONObject().put("type", "object")))
        )
        val (bodyTools, errTools) = captureBodyR2(tools, null)
        val objTools = try { if (bodyTools != null) JSONObject(bodyTools) else null } catch (e: Exception) { null }
        checkR2("B9 live: call tools=ALL sampai ke onDone (bukan error)",
            errTools == null, "err=$errTools body=$bodyTools")
        checkR2("B9 live: body tools=ALL membawa 1 tools + tool_choice auto",
            objTools != null && objTools.has("tools") &&
                objTools.optJSONArray("tools")?.length() == 1 &&
                objTools.optString("tool_choice") == "auto",
            "body=$bodyTools")
    }

    // ------------------------------------------------------- cek sumber MainActivity
    val proj = System.getProperty("proj.dir", "")
    checkR2("proj.dir diteruskan run_tests.sh", proj.isNotBlank(), "kosong")
    if (proj.isBlank()) {
        println("GAGAL: $failedR2 cek")
        kotlin.system.exitProcess(1)
    }
    val main = File(proj,
        "app/src/main/java/com/senzwh/deepharness/MainActivity.kt").readText()

    val resume = regionR2(main, "private fun maybeResumeTurn()",
        "\n    private fun ")
    val onUserFn = regionR2(main, "private fun onUserMessage(",
        "\n    private fun ")
    val reset = regionR2(main, "private fun resetTurnStateVc334()",
        "private fun onUserMessage(")
    val cont = regionR2(main, "private fun continueOpenaiVc5b(",
        "private fun continueOpenaiTextResultsVc5b(")
    val contText = regionR2(main, "private fun continueOpenaiTextResultsVc5b(",
        "private fun sendToolResultVc5b(")
    val sendCont = regionR2(main, "private fun sendContinuation(",
        "private fun callNativeVc5b(")
    val batchFn = regionR2(main, "private fun execBatchAndContinueVc273(",
        "private fun resolveBatchArgsVc273(")
    val planner = regionR2(main, "private fun handlePlannerTool(",
        "private fun enqueueFollowUp(")
    val reqStop = regionR2(main, "private fun requestStopTurnVc306()",
        "private fun endTurnStopped(")
    val stoppedFn = regionR2(main, "private fun endTurnStopped(",
        "private fun endTurnErrorVc334(")

    // ---- B8 + B11: satu fungsi reset bersama, dipakai kedua tempat.
    checkR2("B8/B11 resetTurnStateVc334() ada",
        main.contains("private fun resetTurnStateVc334()"))
    checkR2("B11 onUserMessage memakai resetTurnStateVc334()",
        onUserFn.contains("resetTurnStateVc334()"), "blok reset lama masih inline?")
    checkR2("B8 maybeResumeTurn memakai resetTurnStateVc334()",
        resume.contains("resetTurnStateVc334()"), "resume masih reset sendiri?")
    checkR2("B11 onUserMessage: TIDAK ada blok reset kedua (stuckMap)",
        !onUserFn.contains("stuckMap.clear()"), "duplikasi blok reset")
    checkR2("B11 onUserMessage: TIDAK ada blok reset kedua (capStopSent)",
        !onUserFn.contains("capStopSentVc277 = false"), "duplikasi blok reset")
    checkR2("B11 onUserMessage: TIDAK ada blok reset kedua (openai state)",
        !onUserFn.contains("openaiTurnMessagesVc5b.clear()"), "duplikasi blok reset")
    checkR2("B11 onUserMessage: TIDAK ada blok reset kedua (beginTurn)",
        !onUserFn.contains("TurnMetrics.beginTurn(this)"), "duplikasi blok reset")
    checkR2("B11 onUserMessage: TIDAK ada blok reset kedua (stepBudget)",
        !onUserFn.contains("loadStepBudgetVc304()"), "duplikasi blok reset")
    checkR2("B11 maybeResumeTurn: TIDAK ada blok reset kedua (agentSteps)",
        !resume.contains("agentSteps = 0"), "duplikasi blok reset di resume")
    checkR2("B11 maybeResumeTurn: TIDAK ada blok reset kedua (stuckMap)",
        !resume.contains("stuckMap.clear()"), "duplikasi blok reset di resume")
    checkR2("B11 maybeResumeTurn: TIDAK ada blok reset kedua (capStopSent)",
        !resume.contains("capStopSentVc277 = false"), "duplikasi blok reset di resume")
    checkR2("B11 maybeResumeTurn: TIDAK ada blok reset kedua (token R1)",
        !resume.contains("turnTokenVc334 = turnGenerationVc334"), "satu baris R1 masih tersisa")
    checkR2("B8 maybeResumeTurn: lastUserText diisi dari checkpoint",
        resume.contains("lastUserText = user"), "lastUserText resume masih basi")
    // Isi reset bersama: pembuka giliran + semua penghitung sekali-per-giliran.
    val resetKeys = listOf(
        "turnActive = true", "userTurnActive = true",
        "turnGenerationVc334++", "turnTokenVc334 = turnGenerationVc334",
        "stopRequestedVc306 = false", "endTurnStoppedInFlightVc334 = false",
        "turnSessionId = currentSessionId", "refreshComposerButtonsVc306()",
        "refreshLiveCard()", "requestStartMs = System.currentTimeMillis()",
        "autoContinueCount = 0", "malformedRetry = 0", "refusalRetryVc251 = 0",
        "pendingAskVc229 = null", "agentSteps = 0", "rejectRetryCount = 0",
        "stuckMap.clear()", "extendCountVc303 = 0", "extendOkMarkVc303 = 0",
        "lockedRefusalsVc303 = 0", "stuckFuzzySetsVc303.clear()",
        "Tools.resetRereadGuardVc302()", "turnStuckBlocked = false",
        "capStopSentVc277 = false", "resumeOfferShownVc303 = false",
        "unknownToolCount = 0", "consecInvalid = 0", "invalidNames.clear()",
        "pendingEndReason = null", "turnStoppedReason = null",
        "forcedToolVc282 = null", "toolChoiceRepairVc282 = 0",
        "openaiTurnMessagesVc5b.clear()", "openaiTextFallbackVc5b = false",
        "openaiToolRepairVc5b = 0", "openaiRoleFallbackArmedVc5b = false",
        "openaiToolNamesVc5b.clear()", "turnSource = \"\"",
        "turnProcessViewsVc306.clear()", "thinkPillsVc306.clear()",
        "turnToolRowsVc306 = 0", "turnMsgCountVc306 = 0",
        "turnThinkBlocksVc306 = 0", "lastTurnContainerVc306 = null",
        "lastTurnExpandedContentVc306 = null", "lastTurnToggleVc306 = null",
        "TurnMetrics.beginTurn(this)", "blockedAttempts = 0",
        "loadStepBudgetVc304()", "pendingTool = null",
        "turnUsedTools = false", "retriedTools.clear()", "autoRetryTools.clear()"
    )
    val missingReset = resetKeys.filter { !reset.contains(it) }
    checkR2("B11 resetTurnStateVc334: semua ${resetKeys.size} reset per-giliran ada",
        missingReset.isEmpty(), "hilang: ${missingReset.joinToString(", ")}")
    checkR2("B11 resetTurnStateVc334: planItems/turnWork TIDAK direset (resume memulihkan)",
        !reset.contains("planItems.clear()") && !reset.contains("turnWork.clear()"),
        "reset bersama menghapus rencana/kerja resume")

    // ---- B9: call penutup tanpa tools.
    checkR2("B9 continueOpenaiVc5b: parameter closingVc334 ada",
        main.contains("private fun continueOpenaiVc5b(toolMsgs: List<ChatMessage>, instruction: String, closingVc334: Boolean = false)"))
    checkR2("B9 continueOpenaiVc5b: closing -> tools null (bukan ToolRegistry.ALL)",
        cont.contains("if (closingVc334) null else ToolRegistry.ALL"),
        "call penutup masih menawarkan semua tools")
    checkR2("B9 continueOpenaiVc5b: closing -> tool_choice forced juga null",
        cont.contains("if (closingVc334) null else if (forcedToolVc282"),
        "forced-tool masih dipaksa di call penutup")
    checkR2("B9 continueOpenaiVc5b: fallback teks mewarisi closing",
        cont.contains("continueOpenaiTextResultsVc5b(textMsgs, instruction, closingVc334)"),
        "fallback 400 role/tool menghidupkan lagi tools di call penutup")
    checkR2("B9 continueOpenaiTextResultsVc5b: parameter closingVc334 ada",
        main.contains("private fun continueOpenaiTextResultsVc5b(textMsgs: List<ChatMessage>, instruction: String, closingVc334: Boolean = false)"))
    checkR2("B9 continueOpenaiTextResultsVc5b: closing -> tools null",
        contText.contains("if (closingVc334) null else ToolRegistry.ALL"),
        "call penutup teks masih menawarkan semua tools")
    checkR2("B9 sendContinuation: parameter closingVc334 ada + diteruskan",
        sendCont.contains("closingVc334: Boolean = false") &&
            sendCont.contains("continueOpenaiVc5b(emptyList(), text, closingVc334)"),
        "flag penutup tidak sampai ke lanjutan native")
    checkR2("B9 cap-stop: lanjutan penutup dikirim closing",
        main.contains("handleStepCapVc334") &&
            regionR2(main, "private fun handleStepCapVc334(",
                "private fun proceedTool(").contains("closingVc334 = true"),
        "cap-stop masih mengundang tool di call penutup")
    checkR2("B9 StuckDetector terkunci: lanjutan penutup dikirim closing",
        regionR2(main, "private fun proceedTool(",
            "private fun handlePlannerTool(").contains("closingVc334 = true"),
        "lanjutan kunci-Stuck masih mengundang tool")
    checkR2("B9 kill switch F1: kedua lanjutan penutup dikirim closing",
        main.split("closingVc334 = true").size - 1 >= 4,
        "F1 (tunggal/batch) masih mengundang tool di call penutup")
    checkR2("B9 batch cap: sendBatchResultsVc5b mendeteksi skipped-cap + meneruskan closing",
        regionR2(main, "private fun sendBatchResultsVc5b(",
            "private fun batchInstructionVc5b(").contains("Tidak dieksekusi — batas ") &&
            regionR2(main, "private fun sendBatchResultsVc5b(",
                "private fun batchInstructionVc5b(").contains("closingVc334"),
        "batch kehabisan cap masih mengundang tool")
    checkR2("B9 NEEDS-DECISION: tool_choice none TIDAK ditambahkan di lanjutan",
        !cont.contains("\"none\"") && !contText.contains("\"none\""),
        "keputusan D7 belum diambil — jangan tambah tool_choice none")

    // ---- B4 (vc335 R2 D8): planner TIDAK LAGI 1 langkah penuh di bawah
    // plafon — anggaran SENDIRI maks 3 panggilan/giliran
    // (PlannerBudgetVc335R2). Blok vc334 R2 ini DIGANTI: D8 membatalkan
    // keputusan "planner = 1 langkah penuh" secara eksplisit, jadi cek
    // lama (agentSteps++ / handleStepCapVc334 di penghitung planner)
    // kini menegaskan KETIADAANNYA, bukan keberadaannya.
    checkR2("B4/D8 consumePlannerBudgetVc335R2() ada (pengganti countPlannerStepVc334)",
        main.contains("private fun consumePlannerBudgetVc335R2(") &&
            !main.contains("private fun countPlannerStepVc334("))
    val consumePlanner = regionR2(main, "private fun consumePlannerBudgetVc335R2(",
        "private fun handlePlannerTool(")
    checkR2("B4/D8 planner: TIDAK agentSteps++ (bukan 1 langkah penuh di bawah plafon)",
        !consumePlanner.contains("agentSteps++"), "planner masih dihitung ke plafon")
    checkR2("B4/D8 planner: TIDAK kena cap via handleStepCapVc334 (anggaran sendiri)",
        !consumePlanner.contains("handleStepCapVc334"),
        "planner masih lewat alur cap langkah")
    checkR2("B4/D8 planner: anggaran 3/giliran dipakai (tryConsume) + penolakan jelas",
        consumePlanner.contains("plannerBudgetVc335R2.tryConsume(tool)") &&
            consumePlanner.contains("plannerBudgetVc335R2.denialMessage()"),
        "anggaran planner tidak dipakai / tanpa pesan penolakan")
    checkR2("B4/D8 planner: penolakan berulang menutup giliran (tanpa loop continuation)",
        consumePlanner.contains("Outcome.DeniedRepeat") &&
            consumePlanner.contains("pendingEndReason = \"anggaran-planner\""),
        "model bandel bisa loop tolak-lanjut tanpa batas")
    checkR2("B4/D8 planner: TurnMetrics.recordStep TETAP dicatat (metrik, bukan cap)",
        consumePlanner.contains("TurnMetrics.recordStep(tool, false, 0, argsHash(args), false)"),
        "planner tidak masuk metrik langkah")
    checkR2("B4/D8 planner: masuk StuckDetector (stuckSuffix + kunci)",
        consumePlanner.contains("stuckSuffix(tool, args, result)") &&
            consumePlanner.contains("turnStuckBlocked = true"),
        "planner lolos StuckDetector")
    checkR2("B4/D8 handlePlannerTool: kedua cabang memakai penghitung yang sama",
        planner.split("consumePlannerBudgetVc335R2(tool, args,").size - 1 == 2,
        "make_plan/update_plan tidak sama-sama dihitung")
    checkR2("B4/D8 handlePlannerTool: TIDAK ada agentSteps++ kedua (satu penghitung)",
        !planner.contains("agentSteps++"), "penghitung planner ganda")
    checkR2("B4/D8 handlePlannerTool: guard T15 memakai canRemakePlan (hidup di native)",
        planner.contains("canRemakePlan(planItems.isNotEmpty())"),
        "guard T15 masih mati (autoContinueCount)")
    checkR2("B4 handleStepCapVc334: satu-satunya alur cap, dipakai proceedTool",
        regionR2(main, "private fun proceedTool(",
            "private fun handlePlannerTool(").contains("handleStepCapVc334(tool, args, callId)") &&
            !regionR2(main, "private fun proceedTool(",
                "private fun handlePlannerTool(").contains("pendingTool = tool to args"),
        "alur cap masih inline di proceedTool / ganda")
    checkR2("B4 planner dipanggil dari kedua overload handleAgentResponse",
        main.contains("handlePlannerTool(toolVc5b, argsVc5b)") &&
            main.contains("handlePlannerTool(tool, args)"),
        "planner jalur teks/native tidak sama-sama dihitung")

    // ---- A3: stop dicek di tengah batch.
    val loopHead = regionR2(batchFn, "while (i < batch.size)", "if (stopBatch)")
    checkR2("A3 loop batch: cek state stop R1 SEBELUM cabang stopBatch",
        loopHead.contains("if (stopRequestedVc306)"),
        "loop batch tidak mengecek stop tiap iterasi")
    checkR2("A3 loop batch: sisa call = skipped tanpa eksekusi",
        batchFn.contains("Tidak dieksekusi — dihentikan user.") &&
            batchFn.contains("\"skipped\""),
        "sisa call batch tidak ditandai skipped")
    checkR2("A3 loop batch: stop tengah batch masuk jalur akhir-stop tunggal",
        batchFn.contains("endTurnStopped()"),
        "batch berhenti tanpa endTurnStopped")
    checkR2("A3 loop batch: cek stop mendahului runReadGroupVc273",
        batchFn.indexOf("if (stopRequestedVc306)") < batchFn.indexOf("runReadGroupVc273"),
        "kelompok baca jalan tanpa cek stop")

    // ---- A5: pill jujur — hanya sesudah abort terkonfirmasi.
    checkR2("A5 requestStopTurnVc306: TIDAK ada pill Dihentikan langsung",
        !reqStop.contains("addMsg(\"⛔ Dihentikan") &&
            !reqStop.contains("addBubble(\"⛔ Dihentikan"),
        "pill tampil saat stop baru diminta (sebelum konfirmasi)")
    checkR2("A5 endTurnStopped: pill Dihentikan dipancarkan di sini",
        stoppedFn.contains("addMsg(\"⛔ Dihentikan: \$turnStoppedReason\""),
        "pill akhir-stop hilang dari endTurnStopped")
    checkR2("A5 alasan user-stop hanya disetel di endTurnStopped (sekali)",
        main.split("turnStoppedReason = if (forced)").size - 1 == 1,
        "alasan stop disetel di luar jalur akhir-stop tunggal")
    checkR2("A5 continuation STOP DIMINTA (R1) tidak kembali",
        !main.contains("sendContinuation(\"⛔ STOP DIMINTA"), "instruksi sopan stop muncul lagi")

    println(if (failedR2 == 0) "SEMUA LULUS" else "GAGAL: $failedR2 cek")
    if (failedR2 != 0) kotlin.system.exitProcess(1)
}
