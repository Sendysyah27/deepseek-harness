package com.senzwh.deepharness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.net.InetSocketAddress
import org.json.JSONArray
import org.json.JSONObject

// Test JVM murni — vc334 Ronde 3: PERF MEKANIS
// (B7, B5, L3, L4, L5, L7, L8, L9, P-1, P-4).
// Dijalankan oleh tools/run_tests.sh.
//
// APA YANG DITES DI SINI (jujur):
//  1. LIVE (HTTP lokal / JVM murni): P-1 (4xx tanpa POST kedua,
//     5xx tetap fallback sekali), L9 (redact sekali per fragmen —
//     hitung panggilan redact + isi final), L5 (toolsJson dibangun
//     sekali — instans sama + counter build), L8 (hydrate sekali —
//     hitung baca prefs + counter parse), L4 (akumulator stream —
//     StreamAccumVc334 murni), L7 (prompt retry teks dari katalog
//     ringkas — hitung char, pure), P-4 (timeout diteruskan ke
//     socket — server lambat + timeout kecil).
//  2. SUMBER: B7 (param deltaPrompt hilang), B5 (cabang JSON-teks
//     memakai finalisasi bersama), L3 (prompt dibangun sekali +
//     checkpoint tersimpan sinkron), L4/L5/L8/P-4 (wiring).
//
// APA YANG TIDAK DITES DI SINI:
//  - Perilaku runtime di HP (MainActivity tidak bisa jalan di JVM):
//    bubble stream benar-benar tumbuh di layar, retry teks sampai
//    ke model, hydrate di HP — butuh tes HP oleh koordinator.

private var failedR3 = 0

private fun checkR3(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedR3++; println("  FAIL $name" + detailSuffix)
    }
}

private const val FAKE_KEY_R3 = "sk-test-FAKEKEY123456"

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

private fun requestBodyR3(ex: HttpExchange): String =
    ex.requestBody.bufferedReader(Charsets.UTF_8).readText()

fun main() {
    println("== PerfMechanicsVc334R3Test ==")

    // ------------------------------------------------------- P-1 live: 4xx tanpa POST kedua
    run {
        var requests = 0
        val server = startServerR3 { ex ->
            requests++
            requestBodyR3(ex)
            respondR3(ex, 400, """{"error":{"message":"tool_calls tidak didukung model ini"}}""")
        }
        try {
            var err: String? = null
            var done = false
            OpenAiCompatClient.chatMessages(baseUrlR3(server), FAKE_KEY_R3, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = true }, onError = { err = it })
            checkR3("P-1 live: HTTP 400 -> onError (bukan onDone)",
                !done && err != null, "err=$err done=$done")
            checkR3("P-1 live: HTTP 400 menyebut kode 400",
                err?.contains("400") == true, "err=$err")
            checkR3("P-1 live: HTTP 400 HANYA 1 POST (tanpa fallback non-stream)",
                requests == 1, "requests=$requests")
        } finally {
            server.stop(0)
        }
    }
    run {
        // 429 juga 4xx deterministik -> tanpa fallback.
        var requests = 0
        val server = startServerR3 { ex ->
            requests++
            requestBodyR3(ex)
            respondR3(ex, 429, """{"error":{"message":"rate limit terlampaui"}}""")
        }
        try {
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrlR3(server), FAKE_KEY_R3, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = {}, onError = { err = it })
            checkR3("P-1 live: HTTP 429 HANYA 1 POST",
                requests == 1, "requests=$requests err=$err")
        } finally {
            server.stop(0)
        }
    }
    run {
        // 500 BUKAN 4xx -> fallback non-stream sekali tetap jalan (ruang lingkup TERBATAS).
        var requests = 0
        val server = startServerR3 { ex ->
            requests++
            val body = requestBodyR3(ex)
            if (body.contains("\"stream\":true")) {
                respondR3(ex, 500, """{"error":{"message":"kesalahan server internal"}}""")
            } else {
                respondR3(ex, 200, """{"choices":[{"message":{"content":"ok-fallback","finish_reason":"stop"}}]}""")
            }
        }
        try {
            var done: ChatResult? = null
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrlR3(server), FAKE_KEY_R3, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = it }, onError = { err = it })
            checkR3("P-1 live: HTTP 500 stream -> fallback non-stream sampai onDone",
                done?.content == "ok-fallback", "done=$done err=$err")
            checkR3("P-1 live: HTTP 500 = 2 POST (fallback dipertahankan untuk 5xx)",
                requests == 2, "requests=$requests")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------- L9 live: redact sekali
    run {
        // 3 fragmen, satu membawa kredensial utuh dalam satu fragmen.
        val sse = """
            data: {"choices":[{"delta":{"content":"Halo "}}]}
            data: {"choices":[{"delta":{"content":"Bearer abcdef123456"}}]}
            data: {"choices":[{"delta":{"content":" selesai"}}]}
            data: {"choices":[{"finish_reason":"stop","delta":{}}]}
            data: [DONE]
        """.trimIndent()
        val before = RedactSecrets.redactCallsVc334
        val partials = mutableListOf<String>()
        val outcome = OpenAiCompatClient.parseSse(
            BufferedReader(StringReader(sse)), { partials.add(it) }, {})
        val redactDelta = RedactSecrets.redactCallsVc334 - before
        checkR3("L9 live: onPartial fragmen kredensial ter-redact",
            partials.getOrNull(1) == "Bearer [REDAKSI: bearer token]",
            "partials=$partials")
        checkR3("L9 live: konten final = gabungan fragmen ter-redact (sekali)",
            outcome.result.content == "Halo Bearer [REDAKSI: bearer token] selesai",
            "dapat=${outcome.result.content}")
        checkR3("L9 live: redact dipanggil 3x (sekali per fragmen, tanpa ulang atas konten penuh)",
            redactDelta == 3, "redactDelta=$redactDelta")
        checkR3("L9 live: konten final TIDAK mangling marker ([REDAKSI: bearer token] utuh)",
            !outcome.result.content.contains("bearer token] bearer token]"),
            "dapat=${outcome.result.content}")
    }

    // ------------------------------------------------------- L5 live: cache toolsJson
    run {
        ToolRegistry.invalidateToolsJsonCache()
        val buildsBefore = ToolRegistry.toolsJsonBuildsVc334
        val first = ToolRegistry.toolsJson()
        val second = ToolRegistry.toolsJson()
        val third = ToolRegistry.toolsJson(ToolRegistry.ALL)
        checkR3("L5 live: toolsJson() 3x mengembalikan instans YANG SAMA (cache)",
            first === second && second === third,
            "identitas berbeda — dibangun ulang tiap panggilan")
        checkR3("L5 live: toolsJson dibangun 1x untuk 3 panggilan",
            ToolRegistry.toolsJsonBuildsVc334 - buildsBefore == 1,
            "builds=${ToolRegistry.toolsJsonBuildsVc334 - buildsBefore}")
        checkR3("L5 live: isi cache tetap lengkap (tanpa excluded)",
            first.length() == ToolRegistry.ALL.count { it.name !in ToolRegistry.NATIVE_TOOLS_EXCLUDED },
            "len=${first.length()}")
        ToolRegistry.invalidateToolsJsonCache()
        val buildsBefore2 = ToolRegistry.toolsJsonBuildsVc334
        val rebuilt = ToolRegistry.toolsJson()
        checkR3("L5 live: invalidate -> dibangun ulang sekali (instans baru, isi sama)",
            rebuilt !== first && rebuilt.toString() == first.toString() &&
                ToolRegistry.toolsJsonBuildsVc334 - buildsBefore2 == 1,
            "builds=${ToolRegistry.toolsJsonBuildsVc334 - buildsBefore2}")
        // Subset (jalur internalTurn) ikut ter-cache per daftar.
        val subset = ToolRegistry.ALL.filter { it.name == "remember_fact" || it.name == "learn_rule" }
        ToolRegistry.invalidateToolsJsonCache()
        val b3 = ToolRegistry.toolsJsonBuildsVc334
        val s1 = ToolRegistry.toolsJson(subset)
        val s2 = ToolRegistry.toolsJson(subset)
        checkR3("L5 live: toolsJson(subset) 2x juga dari cache",
            s1 === s2 && ToolRegistry.toolsJsonBuildsVc334 - b3 == 1,
            "builds=${ToolRegistry.toolsJsonBuildsVc334 - b3}")
        checkR3("L5 live: subset hanya berisi remember_fact + learn_rule",
            s1.length() == 2, "len=${s1.length()}")
        ToolRegistry.invalidateToolsJsonCache()
    }

    // ------------------------------------------------------- L8 live: cache hydrate
    run {
        val ctx = FakeContext()
        ctx.getSharedPreferences("llm_provider_cfg", 0).edit()
            .putString("cfg_r3a", """{"id":"r3a","label":"R3A","baseUrl":"https://a.b/v1","model":"m","enabled":true}""")
            .putString("cfg_r3b", """{"id":"r3b","label":"R3B","baseUrl":"https://c.d/v1","model":"m","enabled":true}""")
            .apply()
        LlmProviderStore.invalidateHydrateCache()
        val parsesBefore = LlmProviderStore.hydrateParsesVc334
        val first = LlmProviderStore.hydrate(ctx)
        LlmProviderStore.hydrate(ctx)
        LlmProviderStore.hydrate(ctx)
        checkR3("L8 live: hydrate 3x hanya parse 1x (cache)",
            LlmProviderStore.hydrateParsesVc334 - parsesBefore == 1,
            "parses=${LlmProviderStore.hydrateParsesVc334 - parsesBefore}")
        checkR3("L8 live: hasil cache sama dengan hydrate pertama",
            LlmProviderStore.hydrate(ctx) == first, "first=$first")
        checkR3("L8 live: provider dari cache tetap di registry",
            LlmProviders.find("r3a") != null && LlmProviders.find("r3b") != null,
            "find r3a=${LlmProviders.find("r3a")} r3b=${LlmProviders.find("r3b")}")
        // save() mengotori cache -> hydrate berikut parse ulang.
        val p = LlmProviders.addCustom("r3c", "R3C", "https://e.f/v1", null, "m", true)
        val saved = LlmProviderStore.save(ctx, p)
        val parsesMid = LlmProviderStore.hydrateParsesVc334
        val afterSave = LlmProviderStore.hydrate(ctx)
        checkR3("L8 live: save() mengotori cache (hydrate berikut parse ulang)",
            saved && LlmProviderStore.hydrateParsesVc334 - parsesMid == 1 &&
                LlmProviders.find("r3c") != null,
            "saved=$saved parses=${LlmProviderStore.hydrateParsesVc334 - parsesMid} afterSave=$afterSave")
        // delete() mengotori cache juga.
        LlmProviderStore.delete(ctx, "r3c")
        LlmProviders.removeCustom("r3c")
        val parsesMid2 = LlmProviderStore.hydrateParsesVc334
        LlmProviderStore.hydrate(ctx)
        checkR3("L8 live: delete() mengotori cache (hydrate berikut parse ulang)",
            LlmProviderStore.hydrateParsesVc334 - parsesMid2 == 1,
            "parses=${LlmProviderStore.hydrateParsesVc334 - parsesMid2}")
        // setActive() mengotori cache (activeId dibaca ulang di hydrate).
        LlmProviderStore.setActive(ctx, "r3a")
        val parsesMid3 = LlmProviderStore.hydrateParsesVc334
        LlmProviderStore.hydrate(ctx)
        checkR3("L8 live: setActive() mengotori cache (hydrate berikut parse ulang)",
            LlmProviderStore.hydrateParsesVc334 - parsesMid3 == 1,
            "parses=${LlmProviderStore.hydrateParsesVc334 - parsesMid3}")
        LlmProviders.removeCustom("r3a")
        LlmProviders.removeCustom("r3b")
        LlmProviderStore.invalidateHydrateCache()
    }

    // ------------------------------------------------------- L4 live: akumulator stream
    run {
        val acc = StreamAccumVc334()
        acc.append("Hel")
        acc.append("lo")
        acc.append(" world")
        checkR3("L4 live: akumulator menggabung fragmen utuh",
            acc.snapshot() == "Hello world", "dapat=${acc.snapshot()}")
        acc.append("!")
        checkR3("L4 live: akumulator tumbuh per fragmen (bukan ganti)",
            acc.snapshot() == "Hello world!", "dapat=${acc.snapshot()}")
        acc.reset()
        checkR3("L4 live: reset mengosongkan untuk giliran berikut",
            acc.snapshot() == "", "dapat=${acc.snapshot()}")
        acc.append("baru")
        checkR3("L4 live: sesudah reset, akumulasi mulai dari nol",
            acc.snapshot() == "baru", "dapat=${acc.snapshot()}")
    }

    // ------------------------------------------------------- L7 live (pure): prompt retry ringkas
    run {
        val nativeCat = "<<KATALOG-NATIVE>>"
        val textCat = "<<katalog teks ringkas: list_tools, read_file, get_battery>>"
        val fullCatalog = "<<KATALOG TEKS PENUH " + "x".repeat(94817) + ">>"
        val firstPrompt = "PERSONA-dan-aturan-sistem " + nativeCat +
            " FAKTA-pinned SKILL-fakta-riwayat-digest-worknotes [USER]cek file[/USER]"
        val rebuilt = AgentPrompt.textRetryPromptVc334(firstPrompt, nativeCat, textCat)
        checkR3("L7 live: retry dibangun dari prompt teks (bukan null)",
            rebuilt != null, "rebuilt=null")
        val rb = rebuilt ?: ""
        checkR3("L7 live: katalog native diganti katalog teks ringkas",
            rb.contains(textCat) && !rb.contains(nativeCat), "rb=${rb.take(200)}")
        checkR3("L7 live: blok lain prompt awal dipertahankan (fakta/riwayat/work-notes)",
            rb.contains("FAKTA-pinned") && rb.contains("riwayat-digest-worknotes") &&
                rb.contains("[USER]cek file[/USER]"),
            "rb=${rb.take(300)}")
        checkR3("L7 live: SATU pesan (tanpa pesan katalog kedua)",
            !rb.contains("SISTEM (dialek teks") || rb.indexOf("SISTEM (dialek teks") == rb.lastIndexOf("SISTEM (dialek teks"),
            "rb=${rb.take(300)}")
        // Ukur penghematan vs retry lama (prompt penuh + katalog penuh).
        val oldRetryChars = firstPrompt.length + fullCatalog.length
        val savedChars = oldRetryChars - rb.length
        checkR3("L7 live: retry baru JAUH lebih pendek dari retry lama (hemat >60rb char pada contoh ini)",
            savedChars > 60000,
            "lama=$oldRetryChars baru=${rb.length} hemat=$savedChars")
        println("  info L7: retry lama=$oldRetryChars char, retry baru=${rb.length} char, hemat=$savedChars char")
        checkR3("L7 live: prompt tanpa katalog native -> null (pemanggil pakai fallback bangun-ulang)",
            AgentPrompt.textRetryPromptVc334("prompt tanpa katalog", nativeCat, textCat) == null,
            "seharusnya null bila katalog native tidak ditemukan")
    }

    // ------------------------------------------------------- L3 live (pure): prompt sekali
    run {
        val ctx = FakeContext()
        val (sys, sysLen, catLen) = AgentPrompt.buildSystemPromptWithLengths(ctx, true, "cek file", nativeTools = true)
        val sysAgain = AgentPrompt.buildSystemPrompt(ctx, true, "cek file", nativeTools = true)
        checkR3("L3 live: buildSystemPromptWithLengths == buildSystemPrompt (satu komposisi)",
            sys == sysAgain, "panjang ${sys.length} vs ${sysAgain.length}")
        checkR3("L3 live: sysLen = panjang sistem yang sama",
            sysLen == sys.length, "sysLen=$sysLen len=${sys.length}")
        checkR3("L3 live: catLen = panjang katalog native yang sama",
            catLen == AgentPrompt.catalogForTask(ctx, "cek file", nativeTools = true).length,
            "catLen=$catLen")
        val (_, _, catLenText) = AgentPrompt.buildSystemPromptWithLengths(ctx, true, "cek file", nativeTools = false)
        checkR3("L3 live: catLen native < catLen teks (varian diukur dari parameter yang SAMA)",
            catLen < catLenText, "native=$catLen teks=$catLenText")
        checkR3("L3 live: buildSystemPromptWithLengths TIDAK membangun katalog ganda (catLen konsisten)",
            AgentPrompt.buildSystemPromptWithLengths(ctx, true, "cek file", nativeTools = true).third == catLen,
            "tidak stabil")
    }

    // ------------------------------------------------------- P-4 live: timeout ke socket
    run {
        // Server lambat: terima request, diam 5 dtk, baru jawab SSE.
        // timeoutMs kecil (1000) harus memutus dalam hitungan detik —
        // BUKAN 120 dtk (default lama) x2 (fallback).
        val server = startServerR3 { ex ->
            requestBodyR3(ex)
            Thread.sleep(5000)
            try {
                ex.responseHeaders.set("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { out ->
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"telat\"}}]}\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.write("data: [DONE]\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
        try {
            val t0 = System.currentTimeMillis()
            var err: String? = null
            var done = false
            OpenAiCompatClient.chatMessages(baseUrlR3(server), FAKE_KEY_R3, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = true }, onError = { err = it },
                timeoutMs = 1000)
            val elapsed = System.currentTimeMillis() - t0
            checkR3("P-4 live: socket timeout 1000ms memutus server lambat (bukan 120 dtk)",
                !done && err != null && elapsed < 15000,
                "elapsed=${elapsed}ms done=$done err=$err")
            checkR3("P-4 live: pesan error menyebut timeout",
                err?.contains("timeout", ignoreCase = true) == true ||
                    err?.contains("timed out", ignoreCase = true) == true,
                "err=$err")
            println("  info P-4: server diam 5 dtk, timeoutMs=1000 -> error dalam ${elapsed}ms")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------- cek sumber
    val proj = System.getProperty("proj.dir", "")
    checkR3("proj.dir diteruskan run_tests.sh", proj.isNotBlank(), "kosong")
    if (proj.isBlank()) {
        println("GAGAL: $failedR3 cek")
        kotlin.system.exitProcess(1)
    }
    val main = File(proj,
        "app/src/main/java/com/senzwh/deepharness/MainActivity.kt").readText()
    val client = File(proj,
        "app/src/main/java/com/senzwh/deepharness/OpenAiCompatClient.kt").readText()
    val engine = File(proj,
        "app/src/main/java/com/senzwh/deepharness/LlmEngineChat.kt").readText()
    val registry = File(proj,
        "app/src/main/java/com/senzwh/deepharness/ToolRegistry.kt").readText()
    val store = File(proj,
        "app/src/main/java/com/senzwh/deepharness/LlmProviderStore.kt").readText()
    val agentPrompt = File(proj,
        "app/src/main/java/com/senzwh/deepharness/AgentPrompt.kt").readText()

    val onUserFn = regionR3(main, "private fun onUserMessage(",
        "\n    private fun ")
    val sendToAgentFn = regionR3(main, "private fun sendToAgent(",
        "\n    private fun turnPrefs()")
    val dispatchFn = regionR3(main, "private fun dispatchTurn(",
        "\n    private fun extractTool(")
    val jsonBranch = regionR3(main, "Blok JSON teks (bukan tool_calls)",
        "handleAgentResponse(cleanedVc5b)")
    val stringFinal = regionR3(main, "private fun handleAgentResponse(respRaw: String)",
        "\n    private fun handleAgentResponse(")
    val updateBubble = regionR3(main, "private fun updateStreamBubble(",
        "\n    private fun endStreamBubble()")
    val beginBubble = regionR3(main, "private fun beginStreamBubble()",
        "\n    /** Potong teks mentah tool")
    val resetFn = regionR3(main, "private fun resetTurnStateVc334()",
        "private fun onUserMessage(")
    val retryFn = regionR3(main, "private fun retryTextFallbackVc5b(",
        "\n    /** F5b: konteks ringkas")

    // ---- B7: parameter mati deltaPrompt hilang.
    // vc335 R1 (D1): B7 DICABUT — keputusan D1 yang ditunggu B7 sudah
    // diambil: messages dipersist per sesi (SessionMessagesVc335), jadi
    // delta HIDUP kembali. Dua cek di bawah diperbarui dari "delta TIDAK
    // dipakai" menjadi penegak kontrak D1 yang baru (tanda tangan +
    // call-site delta); cek lain B7 tetap berlaku apa adanya.
    checkR3("B7 MainActivity: TIDAK ADA lagi kata deltaPrompt",
        !main.contains("deltaPrompt"),
        "parameter mati masih ada / komentar mengklaimnya hidup")
    checkR3("B7 (vc335 R1 D1: B7 dicabut — delta HIDUP) sendToAgent: tanda tangan (prompt, deltaVc335?)",
        main.contains("private fun sendToAgent(prompt: String, deltaVc335: String? = null)"),
        "tanda tangan bukan (prompt, deltaVc335? = null)")
    checkR3("B7 (vc335 R1 D1) call-site dispatchTurn: sendToAgent(toSendVc240, deltaVc335)",
        dispatchFn.contains("sendToAgent(toSendVc240, deltaVc335)"),
        "call-site tidak mengoper delta")
    checkR3("B7 (vc335 R1 D1) dispatchTurn membangun delta via deltaTurnPromptVc327",
        dispatchFn.contains("AgentPrompt.deltaTurnPromptVc327("),
        "delta tidak dibangun di dispatchTurn")
    checkR3("B7 (vc335 R1 D1) sendToAgent memakai messages terpersist (nextTurnMessages + canUseDelta)",
        sendToAgentFn.contains("SessionMessagesVc335.nextTurnMessages(") &&
            sendToAgentFn.contains("SessionMessagesVc335.canUseDelta("),
        "sendToAgent tidak memakai messages terpersist")
    checkR3("B7 call-site sendContinuation: sendToAgent(finalTxt) tanpa delta",
        main.contains("sendToAgent(finalTxt)"),
        "call-site masih mengoper delta")
    // vc335 R2 (D4): call-site ekstraksi via sendToAgent DICABUT —
    // ekstraksi LEPAS dari endUserTurn dan jalan sebagai job latar
    // terpisah (bucket BACKGROUND), bukan giliran internalTurn.
    checkR3("B7 (vc335 R2 D4) call-site ekstraksi: TIDAK LAGI sendToAgent(fullExtractVc330) — job latar terpisah",
        !main.contains("sendToAgent(fullExtractVc330)") &&
            main.contains("private fun startExtractionJobVc335R2(") &&
            main.contains("LlmCallKind.BACKGROUND"),
        "ekstraksi masih lewat sendToAgent / job latar tidak ada")
    checkR3("B7 mesin delta dipertahankan (fungsi AgentPrompt TIDAK dihapus — keputusan D1)",
        agentPrompt.contains("fun deltaTurnPromptVc327(") &&
            agentPrompt.contains("fun deltaExtractPromptVc330("),
        "mesin delta ikut terhapus — di luar scope R3")

    // ---- B5: cabang JSON-teks memakai finalisasi bersama.
    checkR3("B5 cabang JSON-teks: memakai finishFinalTurnVc334 (bukan endUserTurn langsung)",
        jsonBranch.contains("finishFinalTurnVc334(cleanedVc5b)"),
        "cabang JSON-teks masih finalisasi sendiri tanpa ekstraksi")
    checkR3("B5 cabang JSON-teks: TIDAK ada endUserTurn() langsung di cabang",
        !jsonBranch.contains("endUserTurn()"),
        "cabang masih menutup giliran sendiri")
    checkR3("B5 helper finishFinalTurnVc334 ada (satu definisi)",
        main.split("private fun finishFinalTurnVc334(").size - 1 == 1,
        "helper tidak ada / ganda")
    val helper = regionR3(main, "private fun finishFinalTurnVc334(",
        "\n    private fun ")
    checkR3("B5 helper: menjalankan keputusan pendingExtract yang sama (vc335 R2 D4: gate + job latar)",
        helper.contains("pendingExtract") && helper.contains("extractHasFactSignalVc241(histVc335R2)") &&
            helper.contains("startExtractionJobVc335R2(histVc335R2)"),
        "helper tidak menangani ekstraksi latar")
    checkR3("B5 helper (vc335 R2 D4): endUserTurn jalan SEBELUM job ekstraksi (follow-up tidak antre)",
        helper.indexOf("endUserTurn()") < helper.indexOf("startExtractionJobVc335R2(") &&
            helper.indexOf("endUserTurn()") >= 0,
        "ekstraksi masih menahan akhir giliran")
    checkR3("B5 helper: menutup giliran via endUserTurn (checkpoint ikut bersih via R1)",
        helper.contains("endUserTurn()"),
        "helper tidak menutup giliran")
    checkR3("B5 overload String: blok pendingExtract inline diganti helper yang sama",
        !stringFinal.contains("extractHasFactSignalVc241(hist)") &&
            stringFinal.contains("finishFinalTurnVc334(visible)"),
        "duplikasi blok ekstraksi (pelajaran R2: satu fungsi bersama)")
    checkR3("B5 keputusan pendingExtract hanya di SATU tempat (helper)",
        main.split("extractHasFactSignalVc241(histVc335R2)").size - 1 == 1,
        "keputusan ekstraksi tersebar di >1 tempat")

    // ---- L3: prompt dibangun sekali + checkpoint sinkron.
    checkR3("L3 onUserMessage: buildSystemPrompt SEKALI via buildSystemPromptWithLengths",
        onUserFn.split("buildSystemPromptWithLengths(").size - 1 == 1,
        "build pertama tidak memakai WithLengths / masih >1")
    checkR3("L3 onUserMessage: TIDAK ADA buildSystemPrompt( kedua (ukur ulang)",
        !onUserFn.contains("AgentPrompt.buildSystemPrompt(this"),
        "build ukur masih ada")
    checkR3("L3 onUserMessage: TIDAK ADA catalogForTask( kedua (ukur ulang katalog)",
        !onUserFn.contains("AgentPrompt.catalogForTask(this"),
        "catalog diukur ulang (selectCatalog atas katalog penuh)")
    checkR3("L3 onUserMessage: hasil ukur dipakai ulang (sysLenVc334/catLenVc334)",
        onUserFn.split("sysLenVc334").size - 1 >= 4 &&
            onUserFn.split("catLenVc334").size - 1 >= 4,
        "panjang ukur tidak dipakai ulang di semua pass guard/semantik")
    checkR3("L3 dispatchTurn: checkpoint tersimpan sinkron (bukan di thread latar)",
        dispatchFn.contains("saveTurnCheckpoint(rawUser)") &&
            !dispatchFn.contains("Thread {"),
        "checkpoint pindah ke latar — rotasi/proses-mati bisa kehilangan giliran")
    checkR3("L3 onUserMessage: build prompt tetap sinkron SEBELUM thread semantik (bukan di latar)",
        onUserFn.indexOf("buildSystemPromptWithLengths(this, true, text") >= 0 &&
            onUserFn.indexOf("buildSystemPromptWithLengths(this, true, text") <
            onUserFn.indexOf("Thread {"),
        "build prompt dipindah ke latar tanpa sinkronisasi")

    // ---- L4: akumulator stream.
    checkR3("L4 updateStreamBubble: mengakumulasi ke streamAccumVc334",
        updateBubble.contains("streamAccumVc334.append(partial)"),
        "masih assignment fragmen terakhir")
    checkR3("L4 updateStreamBubble: streamTv menampilkan clean (akumulasi yang disanitasi)",
        updateBubble.contains("streamTv?.text = clean") &&
            (updateBubble.contains("snapshot") || updateBubble.contains("streamAccumVc334.snapshot()")),
        "UI tidak menampilkan akumulasi")
    checkR3("L4 updateStreamBubble: tampilkan akumulasi (snapshot), bukan fragmen",
        updateBubble.contains("snapshot") || updateBubble.contains("streamAccumVc334.snapshot()"),
        "UI masih memakai fragmen mentah")
    checkR3("L4 beginStreamBubble: reset akumulator tiap giliran",
        beginBubble.contains("streamAccumVc334.reset()"),
        "akumulator tidak direset — bocor antar giliran")
    checkR3("L4 StreamAccumVc334: file kelas murni ada",
        File(proj, "app/src/main/java/com/senzwh/deepharness/StreamAccumVc334.kt").exists(),
        "kelas akumulator tidak ada")

    // ---- L5 wiring: invalidasi per giliran.
    checkR3("L5 ToolRegistry: cache + invalidateToolsJsonCache ada",
        registry.contains("fun invalidateToolsJsonCache()") &&
            registry.contains("cachedToolsJson"),
        "cache toolsJson tidak ada")
    checkR3("L5 resetTurnStateVc334: invalidasi cache toolsJson per giliran",
        resetFn.contains("ToolRegistry.invalidateToolsJsonCache()"),
        "cache tidak pernah diinvalidasi per giliran")

    // ---- L7 wiring: retry dari prompt ringkas.
    checkR3("L7 retry: TIDAK ADA lagi toolCatalogText penuh di retry",
        !retryFn.contains("AgentPrompt.toolCatalogText(this)"),
        "retry masih mengirim katalog teks penuh ±95rb char")
    checkR3("L7 retry: memakai textRetryPromptVc334 (ganti katalog native -> ringkas)",
        retryFn.contains("textRetryPromptVc334("),
        "retry tidak membangun ulang prompt teks")
    checkR3("L7 retry: SATU pesan (retryMessages satu elemen)",
        retryFn.contains("listOf(ChatMessage(\"user\", retryPromptVc334))") ||
            (retryFn.contains("retryPromptVc334") && !retryFn.contains("ChatMessage(\"user\", firstPrompt)")),
        "retry masih dua pesan (prompt penuh + katalog)")

    // ---- L8 wiring: dirty-flag di semua titik mutasi.
    checkR3("L8 store: hydrate memakai cache + dirty-flag",
        store.contains("hydrateDirtyVc334") && store.contains("hydrateSkippedCacheVc334"),
        "cache hydrate tidak ada")
    checkR3("L8 store: saveDetailed mengotori cache",
        regionR3(store, "fun saveDetailed(", "fun markToolsSupport(").contains("invalidateHydrateCache()"),
        "save tidak mengotori cache")
    checkR3("L8 store: delete mengotori cache",
        regionR3(store, "fun delete(", "fun hydrate(").contains("invalidateHydrateCache()"),
        "delete tidak mengotori cache")
    checkR3("L8 store: setActive mengotori cache",
        regionR3(store, "fun setActive(", "fun activeId(").contains("invalidateHydrateCache()"),
        "setActive tidak mengotori cache")
    checkR3("L8 store: setApiKey/deleteApiKey mengotori cache",
        store.contains("fun setApiKey(") && regionR3(store, "fun setApiKey(", "fun deleteApiKey(").contains("invalidateHydrateCache()") &&
            regionR3(store, "fun deleteApiKey(", "fun parseConfigEntry(").contains("invalidateHydrateCache()"),
        "perubahan key tidak mengotori cache")

    // ---- P-1 wiring: 4xx fail-fast di client.
    checkR3("P-1 client: HttpStatusException 4xx langsung onError tanpa fallback",
        client.contains("in 400..499") && client.contains("HttpStatusException"),
        "fallback non-stream masih jalan untuk 4xx")

    // ---- P-4 wiring: timeout diteruskan + pesan jujur.
    checkR3("P-4 client: chatMessages menerima timeoutMs + teruskan ke chatInternal",
        client.contains("timeoutMs: Int = CHAT_READ_TIMEOUT_MS") &&
            client.contains("timeoutMs = timeoutMs"),
        "timeout helper tidak diteruskan ke socket")
    checkR3("P-4 engine: chatMessagesForEngine meneruskan timeoutMs ke client",
        engine.contains("timeoutMs = timeoutMs"),
        "helper tidak meneruskan timeoutMs")
    checkR3("P-4 engine: pesan timeout menyebut waktu tunggu sebenarnya (timeoutMs + 30 dtk latch)",
        engine.contains("timeoutMs + 30_000"),
        "pesan timeout masih mengklaim timeoutMs padahal latch menunggu +30 dtk")

    println(if (failedR3 == 0) "SEMUA LULUS" else "GAGAL: $failedR3 cek")
    if (failedR3 != 0) kotlin.system.exitProcess(1)
}
