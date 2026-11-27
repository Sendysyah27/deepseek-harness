package com.senzwh.deepharness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

// Test JVM murni — vc335 Ronde 3 (TERAKHIR): D3/D5/D6/D7/D9/D10/D11/D13/
// D14/D15. Dijalankan oleh tools/run_tests.sh.
//
// APA YANG DITES DI SINI (jujur):
//  1. D3/D5/D7 (live, HTTP lokal): body request + perilaku fallback
//     benar-benar di server mock (bukan cuma parameter).
//  2. D3/D5/D6/D10/D11/D13/D14/D15 (murni): helper Vc335R3Pure +
//     ModelCaps + VisionFiles + LlmProviderStore.parseConfigEntry.
//  3. D5/D6/D9/D10/D11/D13/D14/D15 (sumber): wiring di MainActivity /
//     TeamRun / LlmEngineChat / MemoryDb — file-file itu (Activity /
//     Context Android) tidak bisa dikompilasi/diinstansiasi di JVM,
//     jadi yang dicek adalah wiring-nya (pola yang sama seperti
//     StopButtonVc334R2Test / Vc335R2Test).
//
// APA YANG TIDAK DITES DI SINI:
//  - Perilaku runtime di HP (■ benar-benar membuang hasil tool yang
//    sedang berjalan, verify tim memakai bucket reviewer di kuota
//    nyata, vision ke-5 dalam satu giliran ditolak di UI, dst) —
//    butuh tes POVA 5 oleh Jhon.

private var failedR3 = 0

private fun checkR3(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedR3++; println("  FAIL $name" + detailSuffix)
    }
}

private const val FAKE_KEY_R3 = "<redacted>"

/** Potongan sumber di antara dua penanda (start inklusif). */
private fun regionR3(text: String, startMarker: String, endMarker: String): String {
    val s = text.indexOf(startMarker)
    if (s < 0) return ""
    val e = text.indexOf(endMarker, s + startMarker.length)
    return if (e < 0) text.substring(s) else text.substring(s, e)
}

private fun startServerR3(handler: (HttpExchange) -> Unit): HttpServer {
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

private fun baseUrlR3(server: HttpServer): String =
    "http://[::1]:${server.address.port}"

private fun respondR3(ex: HttpExchange, code: Int, body: String, contentType: String = "application/json") {
    val bytes = body.toByteArray(Charsets.UTF_8)
    ex.responseHeaders.set("Content-Type", contentType)
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

private fun respondSseR3(ex: HttpExchange, chunks: List<String>) {
    ex.responseHeaders.set("Content-Type", "text/event-stream")
    ex.sendResponseHeaders(200, 0)
    ex.responseBody.use { out ->
        for (c in chunks) {
            out.write(c.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }
}

private data class ChatOutcomeR3(val done: ChatResult?, val err: String?)

private fun runChatR3(
    server: HttpServer,
    messages: List<ChatMessage>,
    tools: JSONArray? = null,
    toolChoice: Any? = null,
    supportsCacheControl: Boolean = false,
    onPartial: (String) -> Unit = {}
): ChatOutcomeR3 {
    val doneRef = AtomicReference<ChatResult?>(null)
    val errRef = AtomicReference<String?>(null)
    val finished = CountDownLatch(1)
    val worker = Thread {
        try {
            OpenAiCompatClient.chatMessages(baseUrlR3(server), FAKE_KEY_R3, "m1",
                messages, tools, false,
                onPartial = onPartial, onDeltaToolCall = {},
                onDone = { doneRef.set(it); finished.countDown() },
                onError = { errRef.set(it); finished.countDown() },
                toolChoice = toolChoice,
                supportsCacheControl = supportsCacheControl)
        } catch (e: Exception) {
            errRef.set("THREW: ${e.message}"); finished.countDown()
        }
    }.apply { isDaemon = true }
    worker.start()
    finished.await(10, TimeUnit.SECONDS)
    return ChatOutcomeR3(doneRef.get(), errRef.get())
}

private fun sysUserMessages(): List<ChatMessage> = listOf(
    ChatMessage("system", "kamu asisten"),
    ChatMessage("user", "hai")
)

fun main() {
    println("== Vc335R3Test ==")

    // ------------------------------------------------------- D3 murni: tabel keputusan fallback
    run {
        val f = Vc335R3Pure::shouldFallbackNonStreamVc335R3
        checkR3("D3: dibatalkan user -> TIDAK fallback (walau 500, tanpa byte)",
            !f(500, false, true))
        checkR3("D3: sudah ada byte -> TIDAK fallback (walau 500)",
            !f(500, true, false))
        checkR3("D3: sudah ada byte -> TIDAK fallback (gagal transport)",
            !f(null, true, false))
        checkR3("D3: 4xx tanpa byte -> TIDAK fallback (fail-fast)",
            !f(400, false, false) && !f(404, false, false) && !f(429, false, false))
        checkR3("D3: 5xx tanpa byte -> fallback sekali",
            f(500, false, false) && f(503, false, false))
        checkR3("D3: gagal transport tanpa byte -> fallback sekali",
            f(null, false, false))
    }

    // ------------------------------------------------------- D3 live: 500 tanpa byte -> fallback
    run {
        val requests = AtomicInteger(0)
        val server = startServerR3 { ex ->
            if (requests.incrementAndGet() == 1) {
                respondR3(ex, 500, "boom-server")
            } else {
                respondR3(ex, 200, """{"choices":[{"message":{"content":"ok-fallback","finish_reason":"stop"}}]}""")
            }
        }
        try {
            val out = runChatR3(server, listOf(ChatMessage("user", "hai")))
            checkR3("D3 live: 500 tanpa byte -> fallback sampai onDone",
                out.done?.content == "ok-fallback", "done=${out.done} err=${out.err}")
            checkR3("D3 live: 500 tanpa byte = 2 POST (stream + non-stream)",
                requests.get() == 2, "requests=${requests.get()}")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------- D3 live: byte sudah diterima -> TANPA fallback
    run {
        val requests = AtomicInteger(0)
        val server = startServerR3 { ex ->
            requests.incrementAndGet()
            respondSseR3(ex, listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"sebagian\"}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            // onPartial melempar sesudah fragmen pertama = simulasi
            // gagal sesudah byte diterima (mis. koneksi putus di tengah
            // stream) — keputusan fallback harus melihat receivedAny.
            val out = runChatR3(server, listOf(ChatMessage("user", "hai")),
                onPartial = { throw RuntimeException("boom-partial") })
            checkR3("D3 live: byte sudah diterima -> onError (bukan onDone ganda)",
                out.done == null && out.err?.contains("boom-partial") == true,
                "done=${out.done} err=${out.err}")
            checkR3("D3 live: byte sudah diterima = 1 POST (TANPA fallback non-stream)",
                requests.get() == 1, "requests=${requests.get()}")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------- D5 murni: opt-in per provider
    run {
        val base = LlmProvider("p-r3", "P", LlmKind.OPENAI_COMPAT,
            "https://x.example/v1", null, "m1")
        checkR3("D5: provider baru default TIDAK mendukung cache_control",
            !ModelCaps.supportsCacheControl(base) && !base.supportsCacheControl)
        checkR3("D5: copy supportsCacheControl=true -> didukung",
            ModelCaps.supportsCacheControl(base.copy(supportsCacheControl = true)))
        val parsed = LlmProviderStore.parseConfigEntry("p-r3",
            """{"id":"p-r3","label":"P","baseUrl":"https://x.example/v1","model":"m1","supportsCacheControl":true}""",
            null)
        checkR3("D5: parseConfigEntry mempertahankan supportsCacheControl=true",
            parsed?.supportsCacheControl == true, "parsed=$parsed")
        val parsedOld = LlmProviderStore.parseConfigEntry("p-r3",
            """{"id":"p-r3","label":"P","baseUrl":"https://x.example/v1","model":"m1"}""",
            null)
        checkR3("D5: cfg lama tanpa kunci -> default false (kompatibel mundur)",
            parsedOld?.supportsCacheControl == false, "parsed=$parsedOld")
        val added = LlmProviders.addCustom("r3cc", "R3 CC", "https://x.example/v1", null, "m1",
            supportsCacheControl = true)
        checkR3("D5: addCustom meneruskan supportsCacheControl",
            added.supportsCacheControl)
    }

    // ------------------------------------------------------- D5 live: default TIDAK kirim cache_control
    run {
        val captured = AtomicReference<String?>(null)
        val server = startServerR3 { ex ->
            captured.set(ex.requestBody.readAllBytes().toString(Charsets.UTF_8))
            respondSseR3(ex, listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            val out = runChatR3(server, sysUserMessages())
            val body = try { JSONObject(captured.get() ?: "{}") } catch (e: Exception) { JSONObject() }
            checkR3("D5 live default: request sampai onDone",
                out.done?.content == "ok", "err=${out.err}")
            checkR3("D5 live default: TIDAK ada kunci cache_control di body",
                !body.toString().contains("cache_control"), "body=${captured.get()}")
            val sysContent = body.optJSONArray("messages")?.optJSONObject(0)?.opt("content")
            checkR3("D5 live default: pesan system tetap string polos",
                sysContent is String && sysContent == "kamu asisten", "body=${captured.get()}")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------- D5 live: opt-in -> system saja
    run {
        val captured = AtomicReference<String?>(null)
        val server = startServerR3 { ex ->
            captured.set(ex.requestBody.readAllBytes().toString(Charsets.UTF_8))
            respondSseR3(ex, listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            val out = runChatR3(server, sysUserMessages(), supportsCacheControl = true)
            val body = try { JSONObject(captured.get() ?: "{}") } catch (e: Exception) { JSONObject() }
            val msgs = body.optJSONArray("messages")
            val sysParts = msgs?.optJSONObject(0)?.opt("content")
            val sysOk = sysParts is JSONArray && sysParts.length() == 1 &&
                sysParts.optJSONObject(0)?.optString("type") == "text" &&
                sysParts.optJSONObject(0)?.optString("text") == "kamu asisten" &&
                sysParts.optJSONObject(0)?.optJSONObject("cache_control")?.optString("type") == "ephemeral"
            checkR3("D5 live opt-in: request sampai onDone",
                out.done?.content == "ok", "err=${out.err}")
            checkR3("D5 live opt-in: pesan system jadi parts + cache_control ephemeral",
                sysOk, "body=${captured.get()}")
            val userContent = msgs?.optJSONObject(1)?.opt("content")
            checkR3("D5 live opt-in: pesan user TETAP string (bukan parts)",
                userContent is String && userContent == "hai", "body=${captured.get()}")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------- D6 murni: buang hasil
    run {
        val d = Vc335R3Pure::shouldDiscardToolResult
        checkR3("D6: token sama -> hasil DIPAKAI (tidak dibuang)",
            !d(5, 5))
        checkR3("D6: token 0 (endUserTurn sesudah ■) -> hasil DIBUANG",
            d(5, 0))
        checkR3("D6: token berubah (giliran baru) -> hasil DIBUANG",
            d(5, 6))
        checkR3("D6: token 0 vs 0 (tidak ada giliran) -> tidak dibuang",
            !d(0, 0))
    }

    // ------------------------------------------------------- D7 live: tools=null + "none" -> tanpa tool_choice
    run {
        val captured = AtomicReference<String?>(null)
        val server = startServerR3 { ex ->
            captured.set(ex.requestBody.readAllBytes().toString(Charsets.UTF_8))
            respondSseR3(ex, listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            val out = runChatR3(server, listOf(ChatMessage("user", "hai")),
                tools = null, toolChoice = "none")
            val body = try { JSONObject(captured.get() ?: "{}") } catch (e: Exception) { JSONObject() }
            checkR3("D7 live: call penutup sampai onDone",
                out.done?.content == "ok", "err=${out.err}")
            checkR3("D7 live: body tools=null TIDAK membawa kunci tools",
                !body.has("tools"), "body=${captured.get()}")
            checkR3("D7 live: tool_choice \"none\" TANPA tools TIDAK dikirim",
                !body.has("tool_choice"), "body=${captured.get()}")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------- D10 murni: bucket reviewer
    run {
        checkR3("D10: bucketFor(REVIEWER) = reviewer",
            LlmEngineChat.bucketFor(LlmCallKind.REVIEWER) == Governor.MODELS_BUCKET_REVIEWER)
        checkR3("D10: bucketFor(TEAM) tetap team (lane tidak berubah)",
            LlmEngineChat.bucketFor(LlmCallKind.TEAM) == Governor.MODELS_BUCKET_TEAM)
    }

    // ------------------------------------------------------- D11 murni: cap dokumen 60.000
    run {
        checkR3("D11: DOC_TEXT_CAP = 60.000",
            VisionFiles.DOC_TEXT_CAP == 60_000)
        val long = "x".repeat(100_000)
        val cut = VisionFiles.truncateDocText(long, VisionFiles.DOC_TEXT_CAP)
        checkR3("D11: teks 100.000 char dipotong ke 60.000 + penanda sisa 40.000",
            cut == "x".repeat(60_000) + "…(dipotong, sisa 40000 char)…",
            "panjang=${cut.length}")
        checkR3("D11: teks 60.000 char TIDAK dipotong",
            VisionFiles.truncateDocText("y".repeat(60_000), VisionFiles.DOC_TEXT_CAP).length == 60_000)
    }

    // ------------------------------------------------------- D13 murni: anggaran vision per giliran
    run {
        val b = Vc335R3Pure.VisionTurnBudgetVc335R3
        checkR3("D13: MAX_VISION_PER_TURN = 4", b.MAX_VISION_PER_TURN == 4)
        b.resetForTest()
        checkR3("D13: giliran 7: pakai 3 -> lolos, sisa 1",
            b.tryConsume(7, 3) && b.usedInTurn(7) == 3 && b.remainingInTurn(7) == 1)
        checkR3("D13: giliran 7: tambah 2 -> DITOLAK (3+2 > 4), pemakaian tetap 3",
            !b.tryConsume(7, 2) && b.usedInTurn(7) == 3)
        checkR3("D13: giliran 7: tambah 1 -> lolos (3+1 = 4)",
            b.tryConsume(7, 1) && b.usedInTurn(7) == 4)
        checkR3("D13: giliran 7: tambah 1 lagi -> DITOLAK (sudah 4)",
            !b.tryConsume(7, 1))
        checkR3("D13: giliran 8 (baru): anggaran reset, pakai 4 langsung lolos",
            b.tryConsume(8, 4) && b.usedInTurn(8) == 4 && b.usedInTurn(7) == 0)
        checkR3("D13: count 0 selalu lolos tanpa memakai slot",
            b.tryConsume(8, 0) && b.usedInTurn(8) == 4)
        b.resetForTest()
    }

    // ------------------------------------------------------- D14 murni: semua lane gagal
    run {
        val f = Vc335R3Pure::isLaneFailedVc335R3
        checkR3("D14: \"Ruang gagal jalan\" = gagal", f("Ruang gagal jalan: timeout"))
        checkR3("D14: \"Ruang X berhenti:\" = gagal", f("Ruang Mentor berhenti: kuota habis"))
        checkR3("D14: \"Ruang X dihentikan:\" = gagal", f("Ruang Agen 4 dihentikan: StuckDetector"))
        checkR3("D14: \"belum selesai dalam batas 7 menit\" = gagal",
            f("Ruang Mandor: belum selesai dalam batas 7 menit — dicatat belum tuntas."))
        checkR3("D14: hasil normal BUKAN gagal",
            !f("Ruang Mandor: selesai — 3 file ditulis, 2 diverifikasi."))
        checkR3("D14: \"tanpa hasil\" BUKAN gagal (bukan frasa kegagalan)",
            !f("Ruang Agen 4: tanpa hasil."))
        val all = Vc335R3Pure::allLanesFailedVc335R3
        checkR3("D14: semua lane gagal -> lewati verify",
            all(listOf("Ruang A gagal jalan: x", "Ruang B berhenti: y")))
        checkR3("D14: satu lane berhasil -> JANGAN lewati verify",
            !all(listOf("Ruang A gagal jalan: x", "Ruang B: selesai — ok.")))
        checkR3("D14: daftar kosong -> JANGAN lewati (bukan semua-gagal)",
            !all(emptyList()))
    }

    // ------------------------------------------------------- D15 murni: ringkasan wipe tanpa isi instruksi
    run {
        val entries = listOf(
            Vc334R5Pure.SkillArchiveEntry(1, "skill", "s1",
                """{"name":"audit-hp","instructions":"RAHASIA-INSTRUKSI-123"}""", 1_700_000_000_000L),
            Vc334R5Pure.SkillArchiveEntry(2, "fact", "f1",
                """{"name":"fakta-x"}""", 1_700_000_000_000L)
        )
        val summary = Vc334R5Pure.wipeSkillArchiveSummary(entries) ?: ""
        checkR3("D15: ringkasan wipe menyebut nama skill",
            summary.contains("audit-hp"), "summary=$summary")
        checkR3("D15: ringkasan wipe TIDAK memuat isi instruksi (bukan backup)",
            !summary.contains("RAHASIA-INSTRUKSI-123"), "summary=$summary")
        checkR3("D15: ringkasan wipe TIDAK memuat kata 'instructions'",
            !summary.lowercase().contains("instructions"), "summary=$summary")
    }

    // ------------------------------------------------------- cek sumber (butuh proj.dir)
    val proj = System.getProperty("proj.dir", "")
    checkR3("proj.dir diteruskan run_tests.sh", proj.isNotBlank(), "kosong")
    if (proj.isBlank()) {
        println("GAGAL: $failedR3 cek")
        kotlin.system.exitProcess(1)
    }
    val main = File(proj,
        "app/src/main/java/com/senzwh/deepharness/MainActivity.kt").readText()
    val teamRun = File(proj,
        "app/src/main/java/com/senzwh/deepharness/TeamRun.kt").readText()
    val engine = File(proj,
        "app/src/main/java/com/senzwh/deepharness/LlmEngineChat.kt").readText()
    val memoryDb = File(proj,
        "app/src/main/java/com/senzwh/deepharness/MemoryDb.kt").readText()
    val eventDb = File(proj,
        "app/src/main/java/com/senzwh/deepharness/EventDb.kt").readText()
    val store = File(proj,
        "app/src/main/java/com/senzwh/deepharness/LlmProviderStore.kt").readText()
    val client = File(proj,
        "app/src/main/java/com/senzwh/deepharness/OpenAiCompatClient.kt").readText()

    // ---- D5 sumber: field bertahan lewat save/update/hydrate + helper memakai.
    checkR3("D5 sumber: saveDetailed menyimpan supportsCacheControl",
        store.contains("put(\"supportsCacheControl\", provider.supportsCacheControl)"),
        "field tidak dipersisten — opt-in hilang sesudah restart")
    val updateRegion = regionR3(store, "private fun updateProviderRecord(",
        "fun delete(ctx: Context, id: String)")
    checkR3("D5 sumber: updateProviderRecord meneruskan supportsCacheControl",
        updateRegion.contains("updated.supportsCacheControl"),
        "mark*() akan menghapus opt-in saat rebuild record")
    val hydrateRegion = regionR3(store, "fun hydrate(ctx: Context): List<String> {",
        "fun saveModels(")
    checkR3("D5 sumber: hydrate meneruskan supportsCacheControl",
        hydrateRegion.contains("p.supportsCacheControl"),
        "hydrate akan menghapus opt-in saat muat ulang")
    checkR3("D5 sumber: chatMessagesForEngine memakai ModelCaps.supportsCacheControl(provider)",
        engine.contains("supportsCacheControl = ModelCaps.supportsCacheControl(provider)"),
        "flag tidak sampai ke client")
    checkR3("D5 sumber: buildChatBody hanya pasang cache_control bila opt-in",
        client.contains("supportsCacheControl && m.role == \"system\""),
        "cache_control tanpa gate = global")
    checkR3("D5 sumber: HANYA SATU titik emisi cache_control di body request",
        client.split("put(\"cache_control\"").size - 1 == 1,
        "cache_control dipasang di lebih dari satu tempat")

    // ---- D6 sumber: 5 titik buang-hasil.
    val execTool = regionR3(main, "private fun execToolAndContinue(",
        "\n    private fun ")
    checkR3("D6 sumber: execToolAndContinue menolak mulai bila token 0 (giliran mati)",
        execTool.contains("if (stopRequestedVc306 || turnTokenVc334 == 0)"),
        "tool bisa mulai sesudah endUserTurn membersihkan flag stop")
    checkR3("D6 sumber: hasil tool ditandai dibuang via shouldDiscardToolResult",
        execTool.contains("Vc335R3Pure.shouldDiscardToolResult(tokenVc334, turnTokenVc334)"),
        "tidak ada keputusan buang eksplisit")
    checkR3("D6 sumber: turnWork.add HANYA bila tidak dibuang",
        execTool.contains("if (!discardedVc335R3) turnWork.add"),
        "WorkItem bocor ke WorkNotes giliran berikutnya")
    checkR3("D6 sumber: pesan skill JANGAN tampil bila hasil dibuang",
        execTool.contains("if (!discardedVc335R3 && tool == \"use_skill\""),
        "meta skill bocor dari hasil yang dibuang")
    checkR3("D6 sumber: guard token di runOnUiThread SEBELUM journalTrace",
        execTool.indexOf("if (tokenVc334 != turnTokenVc334) return@runOnUiThread") >= 0 &&
            execTool.indexOf("if (tokenVc334 != turnTokenVc334) return@runOnUiThread") <
            execTool.indexOf("journalTrace("),
        "journal/kartu artifact memakai hasil yang dibuang")
    val recordBatch = regionR3(main, "private fun recordBatchOutcomeVc273(",
        "\n    // vc278 O18(a)")
    checkR3("D6 sumber: recordBatchOutcomeVc273 menerima token giliran",
        main.contains("private fun recordBatchOutcomeVc273(oc: BatchOutcomeVc273, tokenVc334: Int)"),
        "batch tidak tahu token awalnya")
    checkR3("D6 sumber: recordBatchOutcomeVc273 membuang hasil bila token berubah",
        recordBatch.contains("shouldDiscardToolResult(tokenVc334, turnTokenVc334)") &&
            recordBatch.contains("(hasil dibuang — giliran berakhir saat tool berjalan)"),
        "WorkItem batch bocor ke giliran berikutnya")
    checkR3("D6 sumber: kedua call-site recordBatchOutcomeVc273 meneruskan token",
        main.split("recordBatchOutcomeVc273(oc, tokenVc334)").size - 1 == 2,
        "satu jalur batch tidak meneruskan token")
    val batchFn = regionR3(main, "private fun execBatchAndContinueVc273(",
        "private fun resolveBatchArgsVc273(")
    checkR3("D6 sumber: loop batch mengecek token JUGA (bukan hanya flag stop)",
        batchFn.contains("if (stopRequestedVc306 || tokenVc334 != turnTokenVc334)"),
        "call baru bisa mulai sesudah giliran berakhir")
    checkR3("D6 sumber: sisa call batch sesudah giliran berakhir ditandai skipped jujur",
        batchFn.contains("Tidak dieksekusi — giliran sudah berakhir."),
        "pesan skipped menyesatkan (bukan user-stop)")
    // Cek nyata D6: tidak ada pemanggilan interrupt di jalur tool.
    checkR3("D6 sumber: jalur tunggal tidak meng-interrupt thread tool",
        !execTool.contains("Thread.currentThread().interrupt()"),
        "tool dibatalkan kooperatif — melanggar D6")

    // ---- D7 sumber: cabang tool_choice none sudah dicabut.
    checkR3("D7 sumber: buildChatBody TIDAK LAGI mengirim tool_choice none tanpa tools",
        !client.contains("else if (toolChoice == \"none\")"),
        "cabang lama masih ada — call penutup bisa mengirim tool_choice none")
    checkR3("D7 sumber: TIDAK ada pemanggil yang mengoper toolChoice \"none\"",
        !engine.contains("toolChoice = \"none\"") && !main.contains("toolChoice = \"none\"") &&
            !main.contains("\"none\" as Any") ,
        "satu call-site masih memakai none")

    // ---- D9 sumber: TIDAK ada watchdog lapis kedua.
    val mainRoot = File(proj, "app/src/main/java")
    val watchdogFiles = ArrayList<String>()
    val watchdogFuns = ArrayList<String>()
    val funRe = Regex("""fun\s+(\w*[Ww]atchdog\w*)\s*\(""")
    mainRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
        val rel = f.relativeTo(mainRoot).path
        val text = f.readText()
        if (text.contains("watchdog", ignoreCase = true)) watchdogFiles.add(rel)
        funRe.findAll(text).forEach { watchdogFuns.add("$rel:${it.groupValues[1]}") }
    }
    val allowedWatchdogFiles = setOf(
        "com/senzwh/deepharness/BgBrain.kt",
        "com/senzwh/deepharness/BgRoomSeam.kt",
        "com/senzwh/deepharness/HarnessService.kt",
        "com/senzwh/deepharness/MainActivity.kt",
        "com/senzwh/deepharness/NotifIds.kt",
        "com/senzwh/deepharness/ServeStandbyService.kt",
        "com/senzwh/deepharness/TailscaleHealth.kt",
        "com/senzwh/deepharness/TerminalController.kt",
        "com/senzwh/deepharness/ToolRegistry.kt",
        "com/senzwh/deepharness/Vc335R3Pure.kt",
        "com/senzwh/deepharness/WorkerEndpoint.kt",
        "harness/core/RustCore.kt"
    )
    checkR3("D9: file yang menyebut watchdog = daftar izin yang sudah ada (tidak bertambah)",
        watchdogFiles.toSet() == allowedWatchdogFiles,
        "tambah/hilang: ${(watchdogFiles.toSet() - allowedWatchdogFiles) + (allowedWatchdogFiles - watchdogFiles.toSet())}")
    val allowedWatchdogFuns = setOf(
        "com/senzwh/deepharness/BgBrain.kt:watchdogTick",
        "com/senzwh/deepharness/BgBrain.kt:watchdogDecisionKey",
        "com/senzwh/deepharness/BgBrain.kt:watchdogDecisionKotlin",
        "com/senzwh/deepharness/BgRoomSeam.kt:watchdogDecision",
        "com/senzwh/deepharness/NotifIds.kt:bgWatchdogId",
        "com/senzwh/deepharness/NotifIds.kt:bgWatchdogIdKotlin"
    )
    checkR3("D9: TIDAK ada fungsi watchdog baru (hanya 6 yang sudah ada)",
        watchdogFuns.toSet() == allowedWatchdogFuns,
        "fungsi watchdog: $watchdogFuns")
    checkR3("D9: MainActivity TIDAK mendefinisikan watchdog (hanya teks dialog lama)",
        !funRe.containsMatchIn(main),
        "watchdog baru di MainActivity")
    checkR3("D9: LlmEngineChat TIDAK menyebut watchdog sama sekali",
        !engine.contains("watchdog", ignoreCase = true),
        "watchdog di jalur LLM")

    // ---- D10 sumber: verify pakai REVIEWER, lane tetap TEAM.
    val verifyRegion = regionR3(teamRun, "private fun verify(", "fun boardText(")
    checkR3("D10 sumber: verify memakai LlmCallKind.REVIEWER",
        verifyRegion.contains("LlmCallKind.REVIEWER"),
        "verify masih TEAM")
    checkR3("D10 sumber: verify TIDAK LAGI memakai LlmCallKind.TEAM",
        !verifyRegion.contains("LlmCallKind.TEAM"),
        "bucket verify masih team")
    val laneRegion = regionR3(teamRun, "private fun runLane(",
        "private fun continuationPromptVc334(")
    checkR3("D10 sumber: lane tim TETAP LlmCallKind.TEAM (hanya verify yang pindah)",
        laneRegion.contains("LlmCallKind.TEAM"),
        "lane ikut pindah bucket — bukan keputusan D10")

    // ---- D11 sumber: askWithFiles pakai DOC_TEXT_CAP, bukan DEFAULT_CAP.
    val askRegion = regionR3(engine, "fun askWithFiles(", "private fun extractDocxText(")
    checkR3("D11 sumber: askWithFiles memakai VisionFiles.DOC_TEXT_CAP",
        askRegion.contains("VisionFiles.DOC_TEXT_CAP"),
        "cap dokumen masih 200.000")
    checkR3("D11 sumber: askWithFiles TIDAK LAGI memakai TurnMetrics.DEFAULT_CAP untuk dokumen",
        !askRegion.contains("TurnMetrics.DEFAULT_CAP"),
        "DEFAULT_CAP 200.000 masih dipakai untuk dokumen")
    checkR3("D11 sumber: DEFAULT_CAP 200.000 dipertahankan untuk cap prompt umum",
        File(proj, "app/src/main/java/com/senzwh/deepharness/TurnMetrics.kt").readText()
            .contains("const val DEFAULT_CAP = 200000"),
        "cap prompt umum ikut berubah — di luar D11")

    // ---- D13 sumber: askWithFiles memakai anggaran per giliran.
    checkR3("D13 sumber: askWithFiles mengonsumsi anggaran vision per giliran",
        askRegion.contains("VisionTurnBudgetVc335R3.tryConsume"),
        "anggaran per giliran tidak dipakai")
    checkR3("D13 sumber: anggaran dikunci nomor giliran berjalan",
        askRegion.contains("TurnMetrics.currentTurnNo()"),
        "anggaran tidak terikat giliran")
    checkR3("D13 sumber: penolakan anggaran menyebut batas 4/giliran",
        askRegion.contains("Maks \${Vc335R3Pure.VisionTurnBudgetVc335R3.MAX_VISION_PER_TURN} request vision per giliran"),
        "pesan penolakan tidak menyebut batas")

    // ---- D14 sumber: gerbang lewati-verify di run().
    val runRegion = regionR3(teamRun, "fun run(ctx: Context, goal: String, lanes: List<Lane>): String {",
        "private fun runLane(")
    checkR3("D14 sumber: run() memakai allLanesFailedVc335R3 sebelum verify",
        runRegion.contains("Vc335R3Pure.allLanesFailedVc335R3(done)"),
        "gerbang lewati-verify tidak ada")
    checkR3("D14 sumber: semua-gagal dicatat ke EventDb (log saja)",
        runRegion.contains("Verifikasi tim dilewati: semua") &&
            runRegion.contains("EventDb.get(app).add(\"team\""),
        "lewat tanpa jejak log")
    checkR3("D14 sumber: teks verify pengganti jujur (dilewati, giliran tidak diblokir)",
        runRegion.contains("dilewati — semua ruang gagal"),
        "hasil run_team menyembunyikan verify yang dilewati")
    val boardRegion = regionR3(teamRun, "private fun honestBoardStatus(",
        "private fun writeBoard(")
    checkR3("D14 sumber: honestBoardStatus memakai predikat gagal yang sama",
        boardRegion.contains("Vc335R3Pure.isLaneFailedVc335R3"),
        "dua definisi 'gagal' bisa drift")

    // ---- D15 sumber: semantik wipe TIDAK berubah.
    val wipeRegion = regionR3(memoryDb, "fun wipeAll() {",
        "// ---------- Jadwal tugas AI ----------")
    checkR3("D15 sumber: wipeAll tetap menghapus deleted_archive",
        wipeRegion.contains("db.delete(\"deleted_archive\", null, null)"),
        "arsip skill dipertahankan dari wipe — melanggar D15")
    checkR3("D15 sumber: wipeAll tetap menghapus skills + skill_versions",
        wipeRegion.contains("db.delete(\"skills\", null, null)") &&
            wipeRegion.contains("db.delete(\"skill_versions\", null, null)"),
        "tabel skill tidak ikut wipe")
    checkR3("D15 sumber: ringkasan ditulis ke EventDb SEBELUM baris arsip dihapus",
        wipeRegion.indexOf("wipeSkillArchiveSummary") >= 0 &&
            wipeRegion.indexOf("wipeSkillArchiveSummary") <
            wipeRegion.indexOf("db.delete(\"deleted_archive\""),
        "urutan ringkasan-vs-hapus terbalik")
    checkR3("D15 sumber: wipeAll TIDAK memulihkan/menyalin arsip (tanpa INSERT/restore)",
        !wipeRegion.contains("INSERT") && !wipeRegion.contains("restore", ignoreCase = true),
        "ada penyalinan arsip — bukan ringkasan jejak")
    checkR3("D15 sumber: EventDb file terpisah (harness.db) dari MemoryDb (memory.db)",
        eventDb.contains("\"harness.db\"") && memoryDb.contains("\"memory.db\""),
        "EventDb satu file dengan memori — ikut ter-wipe")

    println(if (failedR3 == 0) "SEMUA LULUS" else "GAGAL: $failedR3 cek")
    if (failedR3 != 0) kotlin.system.exitProcess(1)
}
