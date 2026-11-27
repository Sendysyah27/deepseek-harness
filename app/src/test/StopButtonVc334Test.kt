package com.senzwh.deepharness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.BufferedReader
import java.io.StringReader
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Test JVM murni — vc334 Ronde 1: tombol Hentikan inti + akhir giliran
// (A1, A2, A4, B1, B2, B3). Dijalankan oleh tools/run_tests.sh.
//
// APA YANG DITES DI SINI (jujur):
//  1. A1/A4 (live, HTTP lokal): cancel di tengah streaming SSE ->
//     onError USER_CANCELLED, onDone TIDAK dipanggil, dan TIDAK ADA
//     request kedua (fallback non-stream tidak jalan sesudah batal).
//     Server mock mengirim chunk 1 lalu menahan chunk 2 selama 5 dtk —
//     tanpa cancel, chatMessages akan menunggu; dengan cancel ia
//     kembali dalam < 5 dtk.
//  2. A4 (unit): parseSse dengan cancelRequested=true melempar
//     UserCancelledException di baris data: berikut (cek kooperatif).
//  3. A1 (unit): UserCancelledException ada sebagai tipe sendiri, dan
//     chatMessages mengangkatnya sebagai USER_CANCELLED (bukan pesan
//     error generik yang bisa disangka error jaringan).
//  4. A2/B1/B2/B3 (sumber): MainActivity tidak lagi clear flag stop di
//     titik cek tool, ■ selalu tampil saat giliran aktif, ke-7 jalur
//     terminal memanggil endUserTurn(), endUserTurn() membersihkan
//     checkpoint, dan "user-stop" dikecualikan dari tawaran resume.
//
// APA YANG TIDAK BISA DITES DI JVM:
//  - MainActivity (~18 rb baris, Activity Android) tidak bisa
//    dikompilasi/diinstansiasi di JVM — perilaku runtime endTurnStopped()
//    (pill sesudah konfirmasi, bubble terpotong, tanpa auto-lanjut)
//    HANYA bisa dibuktikan baca kode (cek sumber di bawah) + tes di HP.

private var failedStopVc334 = 0

private fun checkStop(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedStopVc334++; println("  FAIL $name" + detailSuffix)
    }
}

private const val FAKE_KEY_STOP = "sk-test-FAKEKEY123456"

private fun startServerStop(handler: (HttpExchange) -> Unit): HttpServer {
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

private fun baseUrlStop(server: HttpServer): String =
    "http://[::1]:${server.address.port}"

/** Potongan MainActivity di antara dua penanda (start inklusif). */
private fun regionStop(text: String, startMarker: String, endMarker: String): String {
    val s = text.indexOf(startMarker)
    if (s < 0) return ""
    val e = text.indexOf(endMarker, s + startMarker.length)
    return if (e < 0) text.substring(s) else text.substring(s, e)
}

fun main() {
    println("== StopButtonVc334Test ==")

    // ------------------------------------------------------- A1/A4 live: cancel saat streaming
    run {
        val requests = AtomicInteger(0)
        val server = startServerStop { ex ->
            requests.incrementAndGet()
            // Chunk 1 lalu TAHAN 5 dtk sebelum chunk 2 — tanpa cancel,
            // chatMessages baru kembali sesudah chunk 2 + [DONE].
            ex.responseHeaders.set("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out ->
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n".toByteArray(Charsets.UTF_8))
                out.flush()
                Thread.sleep(5000)
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n".toByteArray(Charsets.UTF_8))
                out.flush()
                out.write("data: [DONE]\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
        try {
            var done: ChatResult? = null
            var err: String? = null
            val finished = CountDownLatch(1)
            val t0 = System.currentTimeMillis()
            val worker = Thread {
                try {
                    OpenAiCompatClient.chatMessages(baseUrlStop(server), FAKE_KEY_STOP, "m1",
                        listOf(ChatMessage("user", "hai")), null, false,
                        onPartial = {}, onDeltaToolCall = {},
                        onDone = { done = it; finished.countDown() },
                        onError = { err = it; finished.countDown() })
                } catch (e: Exception) {
                    err = "THREW: ${e.message}"; finished.countDown()
                }
            }.apply { isDaemon = true }
            worker.start()
            // Tunggu chunk 1 diterima server-side (request masuk), lalu batal.
            Thread.sleep(700)
            OpenAiCompatClient.cancelCurrentChat()
            val ok = finished.await(9, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - t0
            // CATATAN PLATFORM (probe 27 Nov): di JVM, disconnect() TIDAK
            // menginterupsi readLine chunked yang blokir — klien baru
            // kembali saat server selesai (5 dtk). Yang dibuktikan di
            // sini: cancel TIDAK menunggu read-timeout 120 dtk, dan
            // hasilnya USER_CANCELLED tanpa fallback. Promptness di HP
            // dijamin penutupan giliran segera di requestStopTurnVc306
            // + token guard (cek sumber di bawah), bukan interupsi socket.
            checkStop("A1 cancel streaming: chatMessages kembali jauh sebelum read-timeout 120 dtk",
                ok && elapsed < 10000, "ok=$ok elapsed=${elapsed}ms err=$err")
            checkStop("A1 cancel streaming: onDone TIDAK dipanggil",
                done == null, "done=$done")
            checkStop("A1 cancel streaming: onError USER_CANCELLED (bukan error jaringan generik)",
                err != null && err!!.startsWith(OpenAiCompatClient.USER_CANCELLED_MARKER),
                "err=$err")
            checkStop("A1 cancel streaming: TIDAK ada request kedua (fallback non-stream tidak jalan)",
                requests.get() == 1, "requests=${requests.get()}")
            checkStop("A1 cancel streaming: flag cancel terangkat",
                OpenAiCompatClient.isChatCancelled(), "cancelRequested=false")
        } finally {
            server.stop(0)
            OpenAiCompatClient.resetCancelFlag()
        }
    }

    // --------------------------------------- A4 unit: cek kooperatif di parseSse
    run {
        val sb = StringBuilder()
        for (i in 1..20) {
            sb.appendLine("""data: {"choices":[{"delta":{"content":"x$i"}}]}""")
        }
        sb.appendLine("data: [DONE]")
        OpenAiCompatClient.resetCancelFlag()
        // Tanpa cancel: parse penuh, 20 fragmen.
        val partials = mutableListOf<String>()
        val outcome = OpenAiCompatClient.parseSse(
            BufferedReader(StringReader(sb.toString())), { partials.add(it) }, {})
        checkStop("A4 parseSse tanpa cancel: semua fragmen dibaca",
            partials.size == 20 && outcome.result.content == (1..20).joinToString("") { "x$it" },
            "partials=${partials.size} content=${outcome.result.content}")
        // Dengan cancel SEBELUM parse: abort di baris data: pertama.
        OpenAiCompatClient.cancelCurrentChat()
        var threw: Exception? = null
        try {
            OpenAiCompatClient.parseSse(BufferedReader(StringReader(sb.toString())), {}, {})
        } catch (e: Exception) {
            threw = e
        } finally {
            OpenAiCompatClient.resetCancelFlag()
        }
        checkStop("A4 parseSse dengan cancel: melempar UserCancelledException",
            threw is UserCancelledException, "threw=${threw?.javaClass?.name}: ${threw?.message}")
    }

    // --------------------------------------- A1 unit: tipe + marker
    run {
        var clsOk = false
        try {
            val c = Class.forName("com.senzwh.deepharness.UserCancelledException")
            clsOk = Exception::class.java.isAssignableFrom(c)
        } catch (e: ClassNotFoundException) {
            clsOk = false
        }
        checkStop("A1 UserCancelledException ada sebagai subclass Exception", clsOk)
        checkStop("A1 USER_CANCELLED_MARKER terdefinisi",
            OpenAiCompatClient.USER_CANCELLED_MARKER.isNotBlank())
        // LlmEngineChat meneruskan user-cancel sebagai UserCancelledException,
        // bukan RuntimeException("timeout…") — cek sumber (helper butuh Context).
        val proj = System.getProperty("proj.dir", "")
        checkStop("proj.dir diteruskan run_tests.sh", proj.isNotBlank(), "kosong")
        if (proj.isNotBlank()) {
            val eng = java.io.File(proj,
                "app/src/main/java/com/senzwh/deepharness/LlmEngineChat.kt").readText()
            checkStop("A1 LlmEngineChat: user-cancel dilempar sebagai UserCancelledException",
                eng.contains("throw UserCancelledException"),
                "tidak ada throw UserCancelledException di LlmEngineChat")
            checkStop("A1 LlmEngineChat: tidak melempar timeout menyesatkan sesudah cancel",
                eng.contains("USER_CANCELLED") || eng.contains("isChatCancelled"),
                "tidak ada pengecekan cancel sebelum pesan timeout")
        }
    }

    // --------------------------------------- A2/B1/B2/B3: cek sumber MainActivity
    val proj = System.getProperty("proj.dir", "")
    if (proj.isNotBlank()) {
        val main = java.io.File(proj,
            "app/src/main/java/com/senzwh/deepharness/MainActivity.kt").readText()
        checkStop("sumber MainActivity terbaca", main.isNotBlank())

        // A2: flag stop TIDAK di-clear di titik cek tool.
        val execTool = regionStop(main, "private fun execToolAndContinue",
            "private fun askConcatDestructiveConfirmVc316")
        checkStop("A2 execToolAndContinue: tidak ada 'stopRequestedVc306 = false'",
            !execTool.contains("stopRequestedVc306 = false"),
            "flag masih di-clear di pintu tool")
        checkStop("A2 execToolAndContinue: pintu stop memanggil endTurnStopped()",
            execTool.contains("endTurnStopped()"),
            "pintu stop tidak masuk jalur akhir-stop tunggal")
        val execBatch = regionStop(main, "private fun execBatchAndContinueVc273",
            "private fun resolveBatchArgsVc273")
        checkStop("A2 execBatchAndContinueVc273: tidak ada 'stopRequestedVc306 = false'",
            !execBatch.contains("stopRequestedVc306 = false"),
            "flag masih di-clear di pintu batch")
        checkStop("A2 execBatchAndContinueVc273: pintu stop memanggil endTurnStopped()",
            execBatch.contains("endTurnStopped()"),
            "pintu stop batch tidak masuk jalur akhir-stop tunggal")
        // proceedTool: gerbang stop paling atas.
        val proceed = regionStop(main, "private fun proceedTool",
            "private fun offerExtendStepsVc304")
        checkStop("A2 proceedTool: cek stopRequestedVc306 di gerbang",
            proceed.contains("stopRequestedVc306"),
            "proceedTool tidak mengecek state stop")
        checkStop("A2 proceedTool: gerbang stop memanggil endTurnStopped()",
            proceed.contains("endTurnStopped()"),
            "gerbang stop proceedTool tidak masuk jalur akhir-stop")

        // endUserTurn: reset state HANYA di sini + clear checkpoint.
        val endTurn = regionStop(main, "private fun endUserTurn()",
            "private fun drainFollowQueue()")
        checkStop("A2 endUserTurn: reset stopRequestedVc306 di sini",
            endTurn.contains("stopRequestedVc306 = false"),
            "reset tidak ada di endUserTurn")
        checkStop("B3 endUserTurn: memanggil clearTurnCheckpoint()",
            endTurn.contains("clearTurnCheckpoint()"),
            "checkpoint tidak dibersihkan di semua pintu keluar")

        // B3: user-stop dikecualikan dari alasan abnormal / tawaran resume.
        val abnormal = regionStop(main, "private fun abnormalStopReasonVc303()",
            "private fun flushTurnWork")
        checkStop("B3 abnormalStopReasonVc303: kecualikan 'dihentikan user dari composer'",
            abnormal.contains("dihentikan user dari composer"),
            "user-stop masih dianggap abnormal (tawaran resume akan muncul)")
        checkStop("B3 abnormalStopReasonVc303: kecualikan 'dihentikan paksa user'",
            abnormal.contains("dihentikan paksa user"),
            "force-stop masih dianggap abnormal")

        // endTurnStopped: jalur akhir-stop tunggal.
        checkStop("endTurnStopped() ada", main.contains("private fun endTurnStopped("))
        val stopped = regionStop(main, "private fun endTurnStopped(",
            "private fun drainFollowQueue()")
        // region di atas bisa menelan endUserTurn bila berurutan; batasi ke fungsi berikut.
        val stoppedFn = stopped.substring(0, minOf(stopped.length, 4000))
        checkStop("endTurnStopped: putus koneksi via cancelCurrentChat()",
            stoppedFn.contains("cancelCurrentChat()"),
            "tidak memutus koneksi")
        checkStop("endTurnStopped: memanggil clearTurnCheckpoint()",
            stoppedFn.contains("clearTurnCheckpoint()"),
            "tidak membersihkan checkpoint")
        checkStop("endTurnStopped: memanggil endUserTurn()",
            stoppedFn.contains("endUserTurn()"),
            "tidak menutup giliran")
        checkStop("endTurnStopped: pill '⛔ Dihentikan' ada",
            stoppedFn.contains("⛔ Dihentikan"),
            "pill akhir-stop tidak ada")
        checkStop("endTurnStopped: bubble terpotong diberi penanda",
            stoppedFn.contains("jawaban terpotong — dihentikan user"),
            "bubble stream terpotong tanpa penanda")
        checkStop("endTurnStopped: TIDAK ada sendContinuation (tanpa auto-lanjut)",
            !stoppedFn.contains("sendContinuation("),
            "masih ada continuation sesudah stop")
        checkStop("endTurnStopped: pendingExtract dilewati dengan sengaja + dicatat",
            stoppedFn.contains("pendingExtract = false") &&
                (stoppedFn.contains("EventDb") || stoppedFn.contains("dilewati")),
            "pendingExtract tidak ditangani eksplisit")

        // B1: ■ selalu tampil saat giliran aktif + klik selalu stop.
        val refresh = regionStop(main, "internal fun refreshComposerButtonsVc306()",
            "private fun requestStopTurnVc306()")
        checkStop("B1 refreshComposerButtons: cabang running memakai ikon stop",
            refresh.contains("pathIconVc306(\"stop\""),
            "ikon stop tidak ada di cabang running")
        checkStop("B1 refreshComposerButtons: ikon stop TIDAK digerbang draft/lampiran",
            !refresh.contains("if (!hasDraft && !hasAttach)"),
            "■ masih hilang saat ada draft")
        checkStop("B1 klik kirim: turnActive langsung requestStopTurnVc306()",
            main.contains("if (turnActive) {\n                requestStopTurnVc306()"),
            "klik saat giliran aktif tidak langsung stop")
        val reqStop = regionStop(main, "private fun requestStopTurnVc306()",
            "private fun readStripDataVc306")
        checkStop("B1 requestStopTurnVc306: memanggil cancelCurrentChat()",
            reqStop.contains("cancelCurrentChat()"),
            "■ tidak terhubung ke lapisan HTTP")
        checkStop("B1 requestStopTurnVc306: tap kedua saat 'Menghentikan…' = force-stop",
            reqStop.contains("endTurnStopped(true)") || reqStop.contains("endTurnStopped(forced = true)"),
            "tidak ada eskalasi force-stop")

        // B2: ke-7 jalur terminal memanggil endUserTurn().
        val sendToAgent = regionStop(main, "private fun sendToAgent(",
            "private fun turnPrefs()")
        checkStop("B2 sendToAgent catch: endTurnStopped/endTurnError dipanggil",
            sendToAgent.contains("endTurnStopped(") && sendToAgent.contains("endTurnErrorVc334("),
            "catch sendToAgent masih menggantung")
        checkStop("B2 sendToAgent: jalur lokal (offline) menutup giliran",
            sendToAgent.contains("endTurnErrorVc334("),
            "jalur lokal tanpa penutup giliran")
        val cont = regionStop(main, "private fun continueOpenaiVc5b(",
            "private fun continueOpenaiTextResultsVc5b(")
        checkStop("B2 continueOpenaiVc5b catch: endTurnStopped/endTurnError dipanggil",
            cont.contains("endTurnStopped(") && cont.contains("endTurnErrorVc334("),
            "catch continueOpenaiVc5b masih menggantung")
        val contText = regionStop(main, "private fun continueOpenaiTextResultsVc5b(",
            "private fun sendToolResultVc5b(")
        checkStop("B2 continueOpenaiTextResultsVc5b catch: endTurnStopped/endTurnError dipanggil",
            contText.contains("endTurnStopped(") && contText.contains("endTurnErrorVc334("),
            "catch continueOpenaiTextResultsVc5b masih menggantung")
        val retry = regionStop(main, "private fun retryTextFallbackVc5b(",
            "private fun openaiTurnContextVc5b()")
        checkStop("B2 retryTextFallbackVc5b catch: endTurnStopped/endTurnError dipanggil",
            retry.contains("endTurnStopped(") && retry.contains("endTurnErrorVc334("),
            "catch retryTextFallbackVc5b masih menggantung")
        // Fallback: cari cabang cap-stop lewat penanda uniknya.
        val capIdx = main.indexOf("app tidak di depan. Hasil sementara ada di kartu-kartu tool di atas.")
        val capRegion = if (capIdx >= 0) main.substring(capIdx, minOf(main.length, capIdx + 600)) else ""
        checkStop("B2 cap-stop cabang kedua: endTurnErrorVc334() dipanggil",
            capRegion.contains("endTurnErrorVc334("),
            "cap-stop masih menggantung (hanya TurnMetrics.finish)")
        val offer = regionStop(main, "private fun offerExtendStepsVc304()",
            "private fun proceedTool(")
        val offerEnds = offer.split("endTurnErrorVc334(").size - 1
        checkStop("B2 offerExtendStepsVc304: ketiga cabang tolak menutup giliran",
            offerEnds >= 3, "hanya $offerEnds endTurnErrorVc334() di offerExtendStepsVc304")

        // Generation guard: thread basi tidak boleh menutup giliran baru.
        checkStop("gen guard: onUserMessage menaikkan turnGenerationVc334",
            main.contains("turnGenerationVc334++"),
            "generasi giliran tidak naik saat giliran dibuka")
        checkStop("gen guard: endTurnStopped menerima genVc334",
            main.contains("private fun endTurnStopped(forced: Boolean = false, genVc334: Int"),
            "endTurnStopped tanpa parameter generasi")
        checkStop("gen guard: endTurnErrorVc334 ada",
            main.contains("private fun endTurnErrorVc334(genVc334: Int, reason: String)"),
            "helper akhir-error bergenerasi tidak ada")
        checkStop("gen guard: catch sendToAgent menangkap generasi thread",
            sendToAgent.contains("val genVc334 = turnGenerationVc334"),
            "catch sendToAgent tanpa generasi thread")

        // Token giliran: hasil telat dibuang, giliran ditutup segera.
        checkStop("token: turnTokenVc334 ada",
            main.contains("private var turnTokenVc334 = 0"),
            "token giliran tidak ada")
        checkStop("token: onUserMessage menyetel turnTokenVc334",
            main.contains("turnTokenVc334 = turnGenerationVc334"),
            "token tidak disetel saat giliran dibuka")
        checkStop("token: endUserTurn mengosongkan turnTokenVc334",
            endTurn.contains("turnTokenVc334 = 0"),
            "token tidak dikosongkan saat giliran berakhir")
        checkStop("token: thread sendToAgent menangkap token + membuang hasil telat",
            sendToAgent.contains("val tokenVc334 = turnTokenVc334") &&
                sendToAgent.contains("if (tokenVc334 != turnTokenVc334) return@Thread"),
            "hasil telat thread sendToAgent tidak dibuang")
        checkStop("token: continueOpenaiVc5b membuang hasil telat",
            cont.contains("if (tokenVc334 != turnTokenVc334) return@Thread"),
            "hasil telat lanjutan tidak dibuang")
        checkStop("token: execToolAndContinue membuang hasil tool telat",
            execTool.contains("val tokenVc334 = turnTokenVc334") &&
                execTool.contains("if (tokenVc334 != turnTokenVc334) return@runOnUiThread"),
            "hasil tool telat tidak dibuang")
        checkStop("token: execBatchAndContinueVc273 membuang hasil batch telat",
            execBatch.contains("val tokenVc334 = turnTokenVc334") &&
                execBatch.contains("if (tokenVc334 != turnTokenVc334) return@runOnUiThread"),
            "hasil batch telat tidak dibuang")
        checkStop("token: analyzePhotoAndContinue memakai sendContinuationIfTurnAlive",
            main.contains("private fun sendContinuationIfTurnAlive(tokenVc334: Int, text: String") &&
                main.contains("sendContinuationIfTurnAlive(tokenPhotoVc334, "),
            "hasil analyze_photo telat tidak dibuang")
        checkStop("token: handleAgentResponse (ChatResult) menolak hasil sesudah stop",
            main.contains("private fun handleAgentResponse(resp: ChatResult)") &&
                regionStop(main, "private fun handleAgentResponse(resp: ChatResult)",
                    "private fun handleAgentResponse(respRaw: String)").contains("if (stopRequestedVc306) { endTurnStopped(); return }"),
            "hasil model sesudah stop masih bisa dieksekusi")
        val reqStopEnds = reqStop.split("endTurnStopped(").size - 1
        checkStop("B1 requestStopTurnVc306: giliran ditutup SEGERA saat ■ ditekan (bukan menunggu jaringan)",
            reqStopEnds >= 2,
            "hanya $reqStopEnds endTurnStopped() di requestStopTurnVc306 (tap pertama tidak menutup giliran)")
    }

    println(if (failedStopVc334 == 0) "SEMUA LULUS" else "GAGAL: $failedStopVc334 cek")
    if (failedStopVc334 != 0) kotlin.system.exitProcess(1)
}
