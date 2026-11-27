package com.senzwh.deepharness

// Test JVM murni untuk vc335 R2 — D2 evict, D4 ekstraksi lepas, D8 anggaran
// planner, D12 ResultStore 2 giliran. Dijalankan oleh tools/run_tests.sh
// (butuh -Dproj.dir untuk cek wiring grep, seperti VerificationRemovedTest).
//
// APA YANG DITES DI SINI (logika murni):
//  D2. trimD2 (reuse R1): anchor + 40 terakhir; pasangan assistant<->tool
//      TIDAK PERNAH dipisah — potongan di tengah pasangan ikut menyertakan
//      assistant pemiliknya; hasil tool yatim di kepala jendela dibuang.
//  D8. PlannerBudgetVc335R2: maks 3 panggilan/giliran; ke-4 = Denied,
//      ke-5 = DeniedRepeat (tutup giliran, tanpa continuation lagi);
//      penolakan tidak memakan anggaran; guard T15 (canRemakePlan) hidup
//      di jalur native via counter make_plan giliran ini.
//  D4. ExtractJobGateVc335R2 (single-flight) + ExtractProposalsVc335R2
//      (parse tool call -> proposal, cermin cabang internalTurn lama).
//  D12. PrevTurnIndexVc335R2: jendela harfiah 2 giliran, sesi lain membuang
//      jendela, id kosong disaring, contains/allIds/snapshot.
//  WIRING (grep atas source staging — MainActivity/Tools/ToolRegistry
//  tidak bisa diinstansiasi di JVM, seperti pola R1).
//
// APA YANG TIDAK BISA DITES DI JVM (residu):
//  - MainActivity.sendToAgent/continueOpenai*/handleAgentResponse/
//    finishFinalTurnVc334/startExtractionJobVc335R2 ITU SENDIRI, dan
//    Tools.execute/readPrevTurnResultVc335R2 (butuh Context/ResultStore
//    Android) — hanya bisa dibuktikan build APK + tes di POVA 5.
//  - Dialog batch vc228 tampil saat ekstraksi selesai; follow-up tidak
//    antre; perilaku anggaran habis di HP.

import java.io.File

private var failedVc335R2 = 0

private fun checkVc335R2(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("LULUS: $name")
    else {
        failedVc335R2++
        println("GAGAL: $name" + (if (detail.isNotBlank()) " — $detail" else ""))
    }
}

private fun msgVc335R2(role: String, content: String, toolCalls: List<NativeToolCall> = emptyList(), toolCallId: String? = null) =
    ChatMessage(role, content, null, toolCallId, toolCalls)

/** Anchor user + [pairs] pasangan (assistant tool_calls + hasil tool). */
private fun sampleTurnVc335R2(pairs: Int): List<ChatMessage> {
    val out = ArrayList<ChatMessage>()
    out.add(msgVc335R2("user", "anchor prompt penuh"))
    for (i in 1..pairs) {
        out.add(msgVc335R2("assistant", "jawab $i", listOf(NativeToolCall("tc-$i", "read_file", "{}"))))
        out.add(msgVc335R2("tool", "hasil $i", toolCallId = "tc-$i"))
    }
    return out
}

/** Invarian pasangan: tiap pesan tool didahului assistant pemilik (dengan
 *  toolCalls) atau pesan tool lain dari pasangan yang sama. */
private fun pairsIntactVc335R2(msgs: List<ChatMessage>): Boolean {
    for (i in msgs.indices) {
        if (msgs[i].role != "tool") continue
        if (i == 0) return false
        val prev = msgs[i - 1]
        if (prev.role == "tool") continue
        if (prev.role == "assistant" && prev.toolCalls.isNotEmpty()) continue
        return false
    }
    return true
}

fun main() {
    println("== Vc335R2Test ==")

    // --------------------------------------- D2: trimD2
    run {
        val msgs = sampleTurnVc335R2(25) // 51 pesan
        val t = SessionMessagesVc335.trimD2(msgs)
        checkVc335R2("D2a: 51 pesan -> anchor + 40 terakhir (41)",
            t.size == 41 && t[0].content == "anchor prompt penuh", "dapat ${t.size}")
        checkVc335R2("D2b: pasangan assistant<->tool utuh sesudah trim", pairsIntactVc335R2(t))
        checkVc335R2("D2c: pesan terakhir (instruksi/hasil) dipertahankan",
            t.last().role == "tool" && t.last().content == "hasil 25")
    }
    run {
        // Potongan jatuh TEPAT di tengah pasangan: start = tool, assistant
        // pemiliknya tepat di luar jendela -> assistant ikut disertakan.
        val msgs = ArrayList<ChatMessage>()
        msgs.add(msgVc335R2("user", "anchor"))
        msgs.add(msgVc335R2("user", "f1"))
        msgs.add(msgVc335R2("user", "f2"))
        msgs.add(msgVc335R2("user", "f3"))
        msgs.add(msgVc335R2("assistant", "pemilik", listOf(NativeToolCall("tc-x", "read_file", "{}"))))
        msgs.add(msgVc335R2("tool", "hasil-x", toolCallId = "tc-x"))
        msgs.add(msgVc335R2("tool", "hasil-x2", toolCallId = "tc-x"))
        repeat(38) { msgs.add(msgVc335R2("user", "filler $it")) }
        checkVc335R2("D2d: fixture 45 pesan", msgs.size == 45, "dapat ${msgs.size}")
        val t = SessionMessagesVc335.trimD2(msgs)
        checkVc335R2("D2e: assistant pemilik pasangan terpotong ikut disertakan (42 pesan)",
            t.size == 42 && t[1].role == "assistant" && t[1].toolCalls.isNotEmpty(), "dapat ${t.size}")
        checkVc335R2("D2f: pasangan utuh (straddle)", pairsIntactVc335R2(t))
    }
    run {
        // Hasil tool YATIM di kepala jendela (pemiliknya sudah tidak ada —
        // daftar malformed/legacy) dibuang, bukan disimpan yatim.
        val msgs = ArrayList<ChatMessage>()
        msgs.add(msgVc335R2("user", "anchor"))
        repeat(4) { msgs.add(msgVc335R2("user", "filler $it")) }
        msgs.add(msgVc335R2("tool", "yatim-1", toolCallId = "tc-y"))
        msgs.add(msgVc335R2("tool", "yatim-2", toolCallId = "tc-y"))
        repeat(38) { msgs.add(msgVc335R2("user", "filler-b $it")) }
        checkVc335R2("D2g: fixture yatim 45 pesan", msgs.size == 45, "dapat ${msgs.size}")
        val t = SessionMessagesVc335.trimD2(msgs)
        checkVc335R2("D2h: tool yatim di kepala dibuang (39 pesan, tanpa tool)",
            t.size == 39 && t.none { it.role == "tool" }, "dapat ${t.size}, tool=${t.count { it.role == "tool" }}")
        checkVc335R2("D2i: anchor tetap ada (yatim)", t[0].content == "anchor")
    }
    run {
        val msgs = sampleTurnVc335R2(20) // 41 pesan persis
        val t = SessionMessagesVc335.trimD2(msgs)
        checkVc335R2("D2j: 41 pesan persis -> tidak berubah", t.size == 41 && t == msgs)
        // Anchor BUKAN di indeks 0 (daftar diawali pesan lain) tetap dipertahankan.
        val weird = ArrayList<ChatMessage>()
        weird.add(msgVc335R2("assistant", "sapa"))
        weird.add(msgVc335R2("assistant", "sapa2"))
        weird.add(msgVc335R2("user", "anchor-ke-3"))
        repeat(47) { weird.add(msgVc335R2("user", "f $it")) }
        val tw = SessionMessagesVc335.trimD2(weird)
        checkVc335R2("D2k: anchor di indeks 2 tetap dipertahankan",
            tw.any { it.role == "user" && it.content == "anchor-ke-3" } && tw.size == 41, "dapat ${tw.size}")
    }

    // --------------------------------------- D8: anggaran planner
    run {
        val b = PlannerBudgetVc335R2()
        checkVc335R2("D8a: 3 panggilan pertama Allowed",
            b.tryConsume("make_plan") is PlannerBudgetVc335R2.Outcome.Allowed &&
                b.tryConsume("update_plan") is PlannerBudgetVc335R2.Outcome.Allowed &&
                b.tryConsume("update_plan") is PlannerBudgetVc335R2.Outcome.Allowed &&
                b.calls == 3, "calls=${b.calls}")
        checkVc335R2("D8b: panggilan ke-4 = Denied (bukan diam-diam ke plafon)",
            b.tryConsume("make_plan") is PlannerBudgetVc335R2.Outcome.Denied && b.calls == 3 && b.denials == 1,
            "calls=${b.calls} denials=${b.denials}")
        checkVc335R2("D8c: panggilan ke-5 = DeniedRepeat (tutup giliran, tanpa continuation lagi)",
            b.tryConsume("update_plan") is PlannerBudgetVc335R2.Outcome.DeniedRepeat && b.denials == 2,
            "denials=${b.denials}")
        checkVc335R2("D8d: pesan penolakan menyebut 3 + make_plan/update_plan",
            b.denialMessage().contains("3 dari 3") && b.denialMessage().contains("make_plan") && b.denialMessage().contains("update_plan"))
        b.reset()
        checkVc335R2("D8e: reset mengosongkan anggaran + denials",
            b.calls == 0 && b.makePlanCalls == 0 && b.denials == 0 &&
                b.tryConsume("make_plan") is PlannerBudgetVc335R2.Outcome.Allowed)
    }
    run {
        val b = PlannerBudgetVc335R2()
        b.tryConsume("update_plan")
        b.tryConsume("make_plan")
        b.tryConsume("update_plan")
        checkVc335R2("D8f: makePlanCalls hanya menghitung make_plan",
            b.calls == 3 && b.makePlanCalls == 1, "calls=${b.calls} make=${b.makePlanCalls}")
    }
    run {
        val b = PlannerBudgetVc335R2()
        checkVc335R2("D8g: T15 — rencana aktif + belum ada make_plan giliran ini = boleh",
            b.canRemakePlan(true))
        b.tryConsume("make_plan")
        checkVc335R2("D8h: T15 — rencana aktif + sudah ada make_plan giliran ini = JANGAN buat ulang",
            !b.canRemakePlan(true))
        checkVc335R2("D8i: T15 — tanpa rencana aktif tetap boleh (rencana baru)",
            b.canRemakePlan(false))
        b.reset()
        checkVc335R2("D8j: T15 — giliran baru (reset) boleh buat rencana lagi",
            b.canRemakePlan(true))
    }

    // --------------------------------------- D4: gate + parse proposal
    run {
        val g = ExtractJobGateVc335R2()
        checkVc335R2("D4a: gate — start pertama true, kedua false (single-flight)",
            g.tryStart() && !g.tryStart() && g.isRunning())
        g.finish()
        checkVc335R2("D4b: gate — sesudah finish bisa start lagi",
            g.tryStart() && g.isRunning())
        g.finish()
        checkVc335R2("D4c: gate — sesudah finish kedua tidak running", !g.isRunning())
    }
    run {
        val calls = listOf(
            NativeToolCall("c1", "remember_fact", "{\"key\":\"nama\",\"value\":\"Sendy\"}"),
            NativeToolCall("c2", "learn_rule", "{\"rule\":\"Jangan bangunkan sebelum jam 7\"}"),
            NativeToolCall("c3", "read_file", "{\"path\":\"/x\"}"),
            NativeToolCall("c4", "remember_fact", "{\"key\":\"\",\"value\":\"kosong\"}"),
            NativeToolCall("c5", "remember_fact", "{json rusak"),
            NativeToolCall("c6", "learn_rule", "{\"rule\":\"\"}")
        )
        val props = ExtractProposalsVc335R2.fromToolCalls(calls)
        checkVc335R2("D4d: parse proposal — 2 valid (fact + rule), sisanya dilewati",
            props.size == 2 && props[0] == ("nama" to "Sendy") &&
                props[1].first.startsWith("aturan:") && props[1].second == "Jangan bangunkan sebelum jam 7",
            "dapat $props")
        checkVc335R2("D4e: parse kosong -> kosong",
            ExtractProposalsVc335R2.fromToolCalls(emptyList()).isEmpty())
    }

    // --------------------------------------- D12: indeks 2 giliran
    run {
        val idx = PrevTurnIndexVc335R2
        idx.clear()
        idx.pushTurn(listOf("R1", "R2"), 7L, 7L)
        idx.pushTurn(listOf("R3"), 7L, 7L)
        checkVc335R2("D12a: 2 giliran menumpuk, contains + allIds",
            idx.contains("R1") && idx.contains("R3") && idx.allIds() == listOf("R1", "R2", "R3") &&
                idx.sessionId == 7L, "dapat ${idx.allIds()}")
        idx.pushTurn(listOf("R4", "R5"), 7L, 7L)
        checkVc335R2("D12b: giliran ke-3 menggeser yang tertua keluar (jendela 2)",
            !idx.contains("R1") && !idx.contains("R2") && idx.contains("R4") &&
                idx.snapshot().size == 2, "dapat ${idx.allIds()}")
        checkVc335R2("D12c: contains kosong/false aman",
            !idx.contains("") && !idx.contains("R99"))
        idx.pushTurn(listOf("R6"), 8L, 7L)
        checkVc335R2("D12d: giliran sesi lain MEMBUANG jendela (tanpa menambah)",
            idx.allIds().isEmpty() && idx.sessionId == -1L, "dapat ${idx.allIds()}")
        idx.pushTurn(listOf("R7", "", "R8"), 7L, 7L)
        checkVc335R2("D12e: id kosong disaring saat push",
            idx.allIds() == listOf("R7", "R8"), "dapat ${idx.allIds()}")
        idx.clear()
        checkVc335R2("D12f: clear mengosongkan jendela + sesi",
            idx.allIds().isEmpty() && idx.sessionId == -1L)
    }
    run {
        // Tool terdaftar sebagai READ dengan argumen id wajib (kontrak
        // ToolRegistryTest ikut memverifikasi katalog lengkap).
        val def = ToolRegistry.BY_NAME["read_previous_result"]
        checkVc335R2("D12g: read_previous_result terdaftar (READ, id wajib)",
            def != null && def.kind == ToolRegistry.Kind.READ &&
                def.args.any { it.name == "id" && it.required },
            "dapat $def")
    }

    // --------------------------------------- WIRING (grep source staging)
    val proj = System.getProperty("proj.dir", "")
    checkVc335R2("W0: proj.dir diteruskan run_tests.sh", proj.isNotBlank(), "kosong")
    if (proj.isNotBlank()) {
        fun src(rel: String) = File(proj, rel).readText(Charsets.UTF_8)
        val main = src("app/src/main/java/com/senzwh/deepharness/MainActivity.kt")
        val tools = src("app/src/main/java/com/senzwh/deepharness/Tools.kt")
        val setup = src("app/src/main/java/com/senzwh/deepharness/SetupController.kt")
        val registry = src("app/src/main/java/com/senzwh/deepharness/ToolRegistry.kt")
        val agentPrompt = src("app/src/main/java/com/senzwh/deepharness/AgentPrompt.kt")
        checkVc335R2("W-D2a: continueOpenaiVc5b + TextResults memakai evictWindowVc335R2 sebelum kirim",
            main.split("evictWindowVc335R2(msgs)").size - 1 >= 2, "kemunculan=${main.split("evictWindowVc335R2(msgs)").size - 1}")
        checkVc335R2("W-D2b: handleAgentResponse memangkas daftar in-memory (trimD2)",
            main.contains("SessionMessagesVc335.trimD2(openaiTurnMessagesVc5b)"))
        checkVc335R2("W-D2c: reset per-giliran membersihkan flag meta evict",
            main.contains("evictNoticeShownVc335R2 = false"))
        checkVc335R2("W-D4a: finishFinalTurnVc334 endUserTurn DULU lalu startExtractionJobVc335R2",
            main.indexOf("endUserTurn()", main.indexOf("private fun finishFinalTurnVc334")) <
                main.indexOf("startExtractionJobVc335R2(", main.indexOf("private fun finishFinalTurnVc334")) &&
                main.contains("startExtractionJobVc335R2(histVc335R2)"))
        checkVc335R2("W-D4b: job ekstraksi memakai bucket BACKGROUND + gate single-flight",
            main.contains("LlmCallKind.BACKGROUND") && main.contains("extractGateVc335R2.tryStart()"))
        checkVc335R2("W-D4c: TIDAK ADA lagi internalTurn = true (jalur internalTurn mati — ekstraksi lepas)",
            !main.contains("internalTurn = true"))
        // Ambil SATU badan fungsi (dari deklarasi sampai "private fun"
        // berikutnya) — substring sampai akhir file akan ikut fungsi lain.
        fun funBody(src: String, decl: String): String {
            val s = src.indexOf(decl)
            if (s < 0) return ""
            val e = src.indexOf("\n    private fun ", s + decl.length)
            return if (e > s) src.substring(s, e) else src.substring(s)
        }
        checkVc335R2("W-D4d: job ekstraksi TIDAK memanggil endUserTurn (tidak menutup giliran baru)",
            !funBody(main, "private fun startExtractionJobVc335R2").contains("endUserTurn()"))
        checkVc335R2("W-D8a: handlePlannerTool memakai consumePlannerBudgetVc335R2 (bukan countPlannerStepVc334)",
            main.contains("consumePlannerBudgetVc335R2(tool, args") && !main.contains("countPlannerStepVc334"))
        checkVc335R2("W-D8b: guard T15 memakai canRemakePlan (bukan autoContinueCount)",
            main.contains("canRemakePlan(planItems.isNotEmpty())") && !main.contains("planItems.isNotEmpty() && autoContinueCount > 0"))
        checkVc335R2("W-D8c: reset per-giliran mereset anggaran planner",
            main.contains("plannerBudgetVc335R2.reset()"))
        checkVc335R2("W-D8d: planner TIDAK LAGI agentSteps++ di penghitung planner",
            !funBody(main, "private fun consumePlannerBudgetVc335R2").contains("agentSteps++"))
        checkVc335R2("W-D12a: Tools.execute punya cabang read_previous_result + helper",
            tools.contains("\"read_previous_result\" -> {") && tools.contains("fun readPrevTurnResultVc335R2"))
        checkVc335R2("W-D12b: get_result + read_previous_result SATU definisi baca (readStoredResultVc335R2)",
            tools.contains("readStoredResultVc335R2(ctx, \"get_result\"") && tools.contains("readStoredResultVc335R2(ctx, \"read_previous_result\""))
        checkVc335R2("W-D12c: hasil baca-ulang tidak disimpan sebagai R-id baru",
            tools.contains("name != \"read_previous_result\""))
        checkVc335R2("W-D12d: onUserMessage mendorong ke PrevTurnIndexVc335R2",
            main.contains("PrevTurnIndexVc335R2.pushTurn(prevIdsVc286"))
        checkVc335R2("W-D12e: pindah sesi + wipe menghapus jendela",
            main.contains("PrevTurnIndexVc335R2.clear()") && setup.contains("PrevTurnIndexVc335R2.clear()"))
        checkVc335R2("W-D12f: tidak ada lagi field deque lama di MainActivity",
            !main.contains("prevTurnResultIdsVc286") && !main.contains("prevTurnResultIdsSessionVc286"))
        checkVc335R2("W-D12g: registry + AgentPrompt mengenali read_previous_result",
            registry.contains("\"read_previous_result\"") && agentPrompt.contains("read_previous_result"))
    }

    println(if (failedVc335R2 == 0) "SEMUA LULUS" else "GAGAL: $failedVc335R2 cek")
    if (failedVc335R2 != 0) kotlin.system.exitProcess(1)
}
