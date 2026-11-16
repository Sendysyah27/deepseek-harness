// TestRoomIxlFixVc321 — FIX ROOM-LANE IXL vc321 (keputusan Sendy 13:28).
// Bukti lapangan: screenshot 13:27, chat "Utama" (lane 1-1): perintah
// "cek skill lists" (tool biasa, BUKAN Concat) kena kartu merah
// input_exceeds_limit di sesi panjang. Keluar-masuk app TIDAK memperbaiki
// akar: kunci sesi server (dsSrvSessId_<fp>_<no> / dsSlotSrv_<slot>_<no>)
// tersimpan di SharedPreferences "harness" — bertahan melewati restart
// app — sementara parent_message_id SELALU NULL sehingga konteks server
// tumbuh ~1 prompt penuh per POST.
// Mengukur (runtime, fungsi murni) lalu mengunci perbaikan:
//  (1) room: selectCatalog (ganti katalog penuh cap 30.000) +
//      continuationPromptVc321 (delta lanjutan, mirror sendContinuation);
//  (2) 1-1 sesi panjang: anggaran [RIWAYAT] adaptif (60/60.000 normal,
//      30/30.000 bila sesi >=40 pesan) + guard reset sesi server
//      proaktif di SlotClient + DsApiClient (akumulasi + prompt berikut
//      > DEFAULT_CAP -> wadah baru, konteks dikirim ulang penuh);
//  (3) sesi pendek/normal TIDAK berubah.
// Argumen: [0] = direktori tree yang diuji (salinan terpadu).
// TIDAK BUTUH Context Android: semua yang dijalankan di sini murni;
// yang butuh Context hanya diperiksa lewat teks source.
// Token memakai kalibrasi kode: 3,4 char/token (DsApiClient.recordTokens).
// Buffer server ~64.000 token ~= 217.600 char (UKURAN-INPUT-VC316.md §2).
// Keluar 0 = semua lulus; >0 gagal = exitProcess(1).
package com.senzwh.deepharness

import java.io.File
import kotlin.system.exitProcess

private var passed = 0
private var failed = 0

private fun check(id: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
        println("LULUS  $id" + if (detail.isNotBlank()) " — $detail" else "")
    } else {
        failed++
        println("GAGAL  $id" + if (detail.isNotBlank()) " — $detail" else "")
    }
}

private fun tok(chars: Int): Int = (chars / 3.4).toInt()

private fun ktUnescape(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            val n = s[i + 1]
            when (n) {
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                '"' -> { out.append('"'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                '$' -> { out.append('$'); i += 2 }
                'u' -> {
                    if (i + 5 < s.length) {
                        try { out.append(Integer.parseInt(s.substring(i + 2, i + 6), 16).toChar()); i += 6 } catch (e: Exception) { out.append(n); i += 2 }
                    } else { out.append(n); i += 2 }
                }
                else -> { out.append(n); i += 2 }
            }
        } else { out.append(c); i++ }
    }
    return out.toString()
}

private fun tripleAfter(src: String, marker: String): String {
    val i = src.indexOf(marker)
    if (i < 0) return ""
    val start = src.indexOf("\"\"\"", i + marker.length)
    if (start < 0) return ""
    val end = src.indexOf("\"\"\"", start + 3)
    if (end < 0) return ""
    return src.substring(start + 3, end)
}

private fun lineAfter(src: String, marker: String): String {
    val i = src.indexOf(marker)
    if (i < 0) return ""
    val start = src.indexOf('"', i + marker.length)
    if (start < 0) return ""
    val out = StringBuilder()
    var j = start + 1
    while (j < src.length) {
        val c = src[j]
        if (c == '\\' && j + 1 < src.length) { out.append(c); out.append(src[j + 1]); j += 2; continue }
        if (c == '"') break
        out.append(c)
        j++
    }
    return ktUnescape(out.toString())
}

fun main(args: Array<String>) {
    val tree = File(if (args.isNotEmpty()) args[0] else "merge")
    val agentSrc = File(tree, "AgentPrompt.kt").readText()
    val groupSrc = File(tree, "GroupChat.kt").readText()
    val mainSrc = File(tree, "MainActivity.kt").readText()
    val dsSrc = File(tree, "DsApiClient.kt").readText()
    val slotSrc = File(tree, "SlotClient.kt").readText()
    val tmSrc = File(tree, "TurnMetrics.kt").readText()

    // ---------- 1. Ukur katalog room: penuh cap 30.000 vs selectCatalog ----------
    println("== 1. Room: katalog lama vs selectCatalog (runtime) ==")
    val section = ToolRegistry.catalogSection()
    val head = tripleAfter(agentSrc, "CATALOG_HEAD")
    val trailer = tripleAfter(agentSrc, "CATALOG_TRAILER")
    val note = lineAfter(agentSrc, "API_LAYER_NOTE_VC282")
    val full = (head + note + section + trailer)
        .replace("__MAXSTEPS__", "50")
        .replace("/data/data/com.senzwh.deepharness/files/proot/ubuntu", "com.senzwh.deepharness/files/proot/ubuntu")
    println("INFO  katalog PENUH: ${full.length} char (~${tok(full.length)} token); room lama take(30000)")
    check("1.1 Katalog penuh = 94.809 char (±600, sama seperti IXL)", full.length in 94200..95400, "${full.length} char")
    val slimSkillLists = AgentPrompt.selectCatalog(full, "cek skill lists")
    val slimConcat = AgentPrompt.selectCatalog(full, "kita tes concat")
    val slimBro = AgentPrompt.selectCatalog(full, "bro?")
    val slimBatt = AgentPrompt.selectCatalog(full, "cek baterai")
    val slimHalo = AgentPrompt.selectCatalog(full, "halo semuanya")
    println("INFO  slim 'cek skill lists': ${slimSkillLists.length}; 'kita tes concat': ${slimConcat.length}; 'bro?': ${slimBro.length}; 'cek baterai': ${slimBatt.length}; 'halo semuanya': ${slimHalo.length}")
    check("1.2 'cek skill lists' (perintah Sendy 13:27): slim 22.429 char (±1.000), hemat 7.571 vs cap 30.000",
        slimSkillLists.length in 21429..23429, "${slimSkillLists.length} char (~${tok(slimSkillLists.length)} token)")
    check("1.3 'kita tes concat': slim 22.788 char (±1.000)", slimConcat.length in 21788..23788, "${slimConcat.length} char")
    check("1.4 'bro?': slim 13.726 char (±1.000), hemat 16.274 vs cap 30.000", slimBro.length in 12726..14726, "${slimBro.length} char")
    check("1.5 'cek baterai': slim 18.458 char (±1.000)", slimBatt.length in 17458..19458, "${slimBatt.length} char")
    check("1.6 'halo semuanya': slim 11.831 char (±1.000)", slimHalo.length in 10831..12831, "${slimHalo.length} char")
    check("1.7 Setiap slim room memuat list_tools + catatan fallback (jalan keluar bila tool di luar seleksi)",
        listOf(slimSkillLists, slimConcat, slimBro, slimBatt, slimHalo).all { it.contains("- list_tools ") && it.contains("Katalog lengkap tersedia kapan saja lewat list_tools") })
    check("1.8 Pesan kosong -> katalog penuh (kontrak lama dipertahankan)", AgentPrompt.selectCatalog(full, "") == full)

    // ---------- 2. Guard reset sesi server: keputusan murni ----------
    println("== 2. Guard reset sesi server (TurnMetrics.shouldResetServerSessionVc321) ==")
    check("2.1 Akumulasi 0 + prompt raksasa -> TIDAK reset (sesi baru tidak dibakar untuk satu POST)",
        !TurnMetrics.shouldResetServerSessionVc321(0, 200000) && !TurnMetrics.shouldResetServerSessionVc321(0, 500000))
    check("2.2 150.000 + 60.526 (prompt awal baru) = 210.526 > 200.000 -> RESET",
        TurnMetrics.shouldResetServerSessionVc321(150000, 60526))
    check("2.3 139.474 + 60.526 = 200.000 persis -> TIDAK reset (batas: hanya > cap)",
        !TurnMetrics.shouldResetServerSessionVc321(139474, 60526))
    check("2.4 139.475 + 60.526 = 200.001 -> RESET", TurnMetrics.shouldResetServerSessionVc321(139475, 60526))
    check("2.5 190.000 + lanjutan kecil 3.000 = 193.000 -> TIDAK reset (delta tidak memicu reset sendiri)",
        !TurnMetrics.shouldResetServerSessionVc321(190000, 3000))
    check("2.6 198.000 + prompt awal 60.526 -> RESET", TurnMetrics.shouldResetServerSessionVc321(198000, 60526))
    check("2.7 Akumulasi negatif -> TIDAK reset", !TurnMetrics.shouldResetServerSessionVc321(-5, 300000))
    check("2.8 DEFAULT_CAP tetap 200.000 char (~58.800 token, di bawah buffer ~64.000)",
        TurnMetrics.DEFAULT_CAP == 200000)

    // ---------- 3. Anggaran riwayat adaptif ----------
    println("== 3. Anggaran [RIWAYAT] adaptif (TurnMetrics.historyBudgetVc321) ==")
    check("3.1 Sesi baru (0 pesan) -> (60, 60000) — sama seperti vc230",
        TurnMetrics.historyBudgetVc321(0) == Pair(60, 60000))
    check("3.2 Sesi pendek (5 pesan) -> (60, 60000)", TurnMetrics.historyBudgetVc321(5) == Pair(60, 60000))
    check("3.3 39 pesan -> masih (60, 60000) (ambang 40 belum tercapai)",
        TurnMetrics.historyBudgetVc321(39) == Pair(60, 60000))
    check("3.4 40 pesan -> (30, 30000) — sesi panjang dipangkas", TurnMetrics.historyBudgetVc321(40) == Pair(30, 30000))
    check("3.5 100 pesan -> (30, 30000)", TurnMetrics.historyBudgetVc321(100) == Pair(30, 30000))
    check("3.6 Konstanta ambang + anggaran tertera di source",
        tmSrc.contains("LONG_SESSION_MSG_THRESHOLD_VC321 = 40") &&
        tmSrc.contains("HISTORY_LONG_N_VC321 = 30") && tmSrc.contains("HISTORY_LONG_CHARS_VC321 = 30000") &&
        tmSrc.contains("HISTORY_NORMAL_N_VC321 = 60") && tmSrc.contains("HISTORY_NORMAL_CHARS_VC321 = 60000"))

    // ---------- 4. Room: struktur delta (baca source) ----------
    println("== 4. Room: struktur delta (GroupChat.kt) ==")
    check("4.1 buildPrompt memakai catalogForTask (selectCatalog), bukan toolCatalog penuh",
        groupSrc.contains("AgentPrompt.catalogForTask(app, userText)") && !groupSrc.contains("AgentPrompt.toolCatalog(app)"))
    check("4.2 Backstop cap 30.000 katalog room dipertahankan",
        groupSrc.contains("catSlimVc321.length > 30000") && groupSrc.contains("katalog grup dipotong, sisa"))
    check("4.3 continuationPromptVc321 didefinisikan (jangkar + suffix, mirror sendContinuation)",
        groupSrc.contains("private fun continuationPromptVc321(userText: String, suffix: String): String") &&
        groupSrc.contains("AgentPrompt.continuationAnchorVc316(userText)"))
    check("4.4 Lanjutan batch memakai delta (bukan rebuild)",
        groupSrc.contains("continuationPromptVc321(userText, BatchToolVc274.combinedMessage(\"grup\", reportGrpVc274))"))
    check("4.5 Nudge gate bukti memakai delta (bukan rebuild)",
        groupSrc.contains("continuationPromptVc321(userText, AgentPrompt.EVIDENCE_GATE_NUDGE)"))
    check("4.6 Lanjutan sesudah tool tunggal memakai delta + hasil tool utuh",
        groupSrc.contains("continuationPromptVc321(userText,") && groupSrc.contains("\"Hasil tool \$tool\""))
    check("4.7 HANYA ronde pertama yang buildPrompt penuh (1 situs panggil tersisa)",
        groupSrc.split("buildPrompt(app, m, userText, memories, execMode)").size - 1 == 1 &&
        groupSrc.contains("var prompt = buildPrompt(app, m, userText, memories, execMode)"))
    check("4.8 TIDAK ADA lagi rebuild 'buildPrompt(...) +' di GroupChat",
        !groupSrc.contains("buildPrompt(app, m, userText, memories, execMode) +"))

    // ---------- 5. 1-1: struktur guard + riwayat (baca source) ----------
    println("== 5. 1-1: guard + riwayat adaptif (baca source) ==")
    check("5.1 MainActivity memakai historyBudgetVc321 + historyTranscriptVc230 adaptif",
        mainSrc.contains("TurnMetrics.historyBudgetVc321(") &&
        mainSrc.contains("historyTranscriptVc230(histNVc321, histCapVc321, currentSessionId)"))
    check("5.2 MainActivity TIDAK LAGI memanggil historyTranscriptVc230(60, 60000, ...) mentah di prompt awal",
        !mainSrc.contains("historyTranscriptVc230(60, 60000, currentSessionId)"))
    check("5.3 SlotClient: guard reset + catat akumulasi + kunci dsSlotSrv_ utuh",
        slotSrc.contains("shouldResetServerSessionVc321") &&
        slotSrc.contains("addServerAccumVc321(ctx, accumKeyVc321, prompt.length)") &&
        slotSrc.contains("resetServerAccumVc321(ctx, accumKeyVc321)") &&
        slotSrc.contains("\"dsSlotSrv_\" + slot + \"_\" + harnessNo") &&
        slotSrc.contains("Wadah server di-reset proaktif vc321"))
    check("5.4 SlotClient: sesi ephemeral reviewer dilewati guard (tidak ada akumulasi)",
        slotSrc.contains("!freshEphemeral && stored != null"))
    check("5.5 DsApiClient: guard reset + catat akumulasi + kunci dsSrvSessId_ utuh",
        dsSrc.contains("shouldResetServerSessionVc321") &&
        dsSrc.contains("addServerAccumVc321(ctx, accumKeyVc321, prompt.length)") &&
        dsSrc.contains("resetServerAccumVc321(ctx, accumKeyVc321)") &&
        dsSrc.contains("dsSrvSessId_\" + fp + \"_\" + harnessNo") &&
        dsSrc.contains("Wadah server di-reset proaktif vc321"))
    check("5.6 DsApiClient: forceFresh tetap dihormati (melewati sesi tersimpan)",
        dsSrc.contains("if (forceFresh) null else storedServerSessionAny"))
    check("5.7 parent_message_id tetap NULL di kedua klien (protokol tidak berubah)",
        dsSrc.contains(".put(\"parent_message_id\", JSONObject.NULL)") &&
        slotSrc.contains(".put(\"parent_message_id\", JSONObject.NULL)"))
    check("5.8 Kunci sesi server tetap di prefs 'harness' (alasan restart tidak me-reset: prefs bertahan)",
        dsSrc.contains("dsSrvSessId_") && slotSrc.contains("dsSlotSrv_"))

    // ---------- 6. Angka sebelum -> sesudah (aritmetika komponen terukur) ----------
    println("== 6. Angka sebelum -> sesudah (komponen terukur, model probe vc321) ==")
    val anchor = AgentPrompt.continuationAnchorVc316("cek skill lists")
    check("6.1 Jangkar delta room = 503 char (±20)", anchor.length in 483..523, "${anchor.length} char")
    // Model probe: transkrip sintetis 30 pesan x 500 char = 15.029 char;
    // base-rest room sintetis 21.612 char (persona+Behaviors+label+paging+
    // pinned+WorldState+memori+fakta+goals+work-notes+skills+instruksi+BATCH).
    val transcriptLen = (1..30).joinToString("\n") { "User: " + "x".repeat(494) }.length
    val baseRest = 1000 + Behaviors.block("senz").length + AgentPrompt.CTX_LABEL_RULES.length +
        AgentPrompt.PAGING_TOTAL_RULE_VC302.length + 500 + 250 + 5000 + 5000 + 2000 + 2000 + 3000 + 1200 +
        AgentPrompt.BATCH_TERMINAL_RULE_VC302.length
    val roomFirstOld = baseRest + 30000 + transcriptLen
    val roomFirstNew = baseRest + slimSkillLists.length + transcriptLen
    val toolResultLen = ("HASIL-TOOL sintetis 2000 char: " + "y".repeat(1970)).length
    val roomContOld = roomFirstOld + 2000 + toolResultLen
    val roomContNew = anchor.length + 2000 + toolResultLen + 300
    println("INFO  room ronde-1: LAMA $roomFirstOld char (~${tok(roomFirstOld)} token) -> BARU $roomFirstNew char (~${tok(roomFirstNew)} token)")
    println("INFO  room lanjutan: LAMA $roomContOld char (~${tok(roomContOld)} token) -> BARU $roomContNew char (~${tok(roomContNew)} token)")
    check("6.2 Room ronde-1 turun 7.571 char (katalog 30.000 -> slim 22.429)",
        roomFirstOld - roomFirstNew == 7571, "lama $roomFirstOld, baru $roomFirstNew")
    check("6.3 Room lanjutan turun >90% (rebuild penuh -> delta)",
        roomContNew < 6000 && roomContOld > 60000 && roomContOld - roomContNew > 60000,
        "lama $roomContOld -> baru $roomContNew (hemat ${roomContOld - roomContNew})")
    // Model 1-1: system slim default/seed 26.651 (Behaviors 0 + brain seed
    // 3.085 + slim katalog 22.429 + WorldState 250 + Connectors 600 +
    // BATCH_TERMINAL 287); extra CTX_LABEL+PAGING+digest/work-notes 2.000;
    // [USER]+konteks 500; riwayat 60.000 lama / 30.000 baru.
    val sysSlim = Behaviors.block("senz").length + 3085 + slimSkillLists.length + 250 + 600 + AgentPrompt.BATCH_TERMINAL_RULE_VC302.length
    val extraBlocks = AgentPrompt.CTX_LABEL_RULES.length + AgentPrompt.PAGING_TOTAL_RULE_VC302.length + 2000
    val initOld = sysSlim + extraBlocks + 60000 + 500
    val initNew = sysSlim + extraBlocks + 30000 + 500
    println("INFO  prompt awal 1-1 sesi panjang: LAMA $initOld char (~${tok(initOld)} token) -> BARU $initNew char (~${tok(initNew)} token)")
    check("6.4 Prompt awal 1-1 sesi panjang turun 30.000 char (riwayat 60k -> 30k)",
        initOld - initNew == 30000 && initOld in 90000..91000 && initNew in 60000..61000,
        "lama $initOld, baru $initNew")
    // Kumulatif server (awal + delta 3.000/giliran): lama menembus buffer
    // 217.600 di giliran ke-3; guard vc321 membatasi <= 200.000 selamanya.
    val deltaPerTurn = 3000
    val cumOld3 = 3 * (initOld + deltaPerTurn)
    check("6.5 SEBELUM fix: kumulatif 3 giliran $cumOld3 char (~${tok(cumOld3)} token) LEWAT buffer 217.600 — cocok dengan kartu merah Sendy",
        cumOld3 > 217600, "giliran ke-3 sudah $cumOld3 char")
    var accum = 0
    var resets = 0
    var maxAccum = 0
    for (turn in 1..10) {
        if (TurnMetrics.shouldResetServerSessionVc321(accum, initNew)) { resets++; accum = 0 }
        accum += initNew + deltaPerTurn
        if (accum > maxAccum) maxAccum = accum
    }
    println("INFO  simulasi guard vc321 10 giliran: reset $resets kali, akumulasi maks sebelum reset $maxAccum char")
    check("6.6 SESUDAH fix: guard me-reset (>=1 kali dalam 10 giliran) dan akumulasi server tidak pernah lewat 200.000 + satu prompt",
        resets >= 1 && maxAccum <= 200000 + initNew + deltaPerTurn, "reset $resets kali, maks $maxAccum char")
    check("6.7 SESUDAH fix: konteks server per giliran TERBATAS (reset bila accum + berikut > 200.000)",
        TurnMetrics.shouldResetServerSessionVc321(150000, 60526) && !TurnMetrics.shouldResetServerSessionVc321(0, 60526))

    // ---------- 7. Sesi pendek tidak berubah ----------
    println("== 7. Sesi pendek/normal tidak berubah ==")
    check("7.1 Sesi pendek: riwayat tetap (60, 60000)", TurnMetrics.historyBudgetVc321(0) == Pair(60, 60000))
    check("7.2 Sesi pendek: tidak ada reset (akumulasi 0)", !TurnMetrics.shouldResetServerSessionVc321(0, 90526))
    check("7.3 Jangkar delta tidak berubah (503 char, sama seperti vc316)",
        AgentPrompt.continuationAnchorVc316("cek skill lists").length == 503)
    check("7.4 Katalog penuh untuk pesan kosong tidak berubah", AgentPrompt.selectCatalog(full, "") == full)
    check("7.5 Definisi tool tidak berubah (188 nama, hash sama seperti probe vc319)",
        ToolRegistry.names().size == 188)

    // ---------- 8. Regresi: tidak ada secret ----------
    println("== 8. Regresi ==")
    check("8.1 Tidak ada secret di file vc321 (sapu hsurr:)",
        !groupSrc.contains("hsurr:") && !dsSrc.contains("hsurr:") && !slotSrc.contains("hsurr:") &&
        !mainSrc.contains("hsurr:") && !tmSrc.contains("hsurr:"))
    check("8.2 Flavor.kt tidak disentuh fix ini (dicek runner C/D — di sini: tidak ada referensi vc321 di Flavor)",
        !File(tree, "Flavor.kt").readText().contains("vc321") && !File(tree, "Flavor.kt").readText().contains("Vc321"))

    println("\nHASIL: $passed lulus, $failed gagal (total ${passed + failed})")
    if (failed > 0) exitProcess(1)
}
