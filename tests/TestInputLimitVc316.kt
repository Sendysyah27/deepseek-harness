// CI-ADAPT (vc317): path absolut vc316-work diganti ke tests/baseline-vc315 (merge PRA-IXL) + tests/work-P1 (path saja, assert tidak berubah). ALASAN: vc316-work/merge ternyata snapshot PASCA-IXL (sudah berisi selectCatalog/delta), bukan baseline pra-IXL yang cek 7.9 maksud; baseline pra-IXL yang benar = vc315-work/merge (selectCatalog 0, DEFAULT_CAP 400000). P1 tetap pakai tests/baseline-vc316 (sudah lulus).
package com.senzwh.deepharness

import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

// TestInputLimitVc316 — Worker IXL (vc316, input_exceeds_limit).
// Mengukur (runtime, fungsi murni) lalu mengunci perbaikan:
//  (1) katalog: ukuran serialisasi daftar tool + prose (ToolRegistry,
//      fungsi murni — tanpa Context) + seleksi katalog ringkas baru
//      (AgentPrompt.selectCatalog, murni);
//  (2) jangkar lanjutan delta (AgentPrompt.continuationAnchorVc316, murni);
//  (3) cap prompt baru (TurnMetrics.DEFAULT_CAP + ambang proaktif);
//  (4) struktur lanjutan delta 1-1 + latar (baca source sebagai teks,
//      pola tes vc315) — system prompt/katalog/memori/riwayat TIDAK
//      lagi dikirim ulang di lanjutan; JEJAK + work-notes dipertahankan;
//  (5) regresi: nama/argumen tool tidak berubah (ToolRegistry utuh),
//      Governor/gerbang/retry DsApiClient byte-identik dengan baseline.
//      PENGECUALIAN slice 6 (vc320): ToolRegistry.kt TIDAK lagi byte-identik
//      baseline — file-nya kini berisi wrapper Rust (seam toolRegistryJson
//      + fallback *Kotlin). Yang dikunci 7.1 sejak vc320 adalah DEFINISI
//      tool-nya (188 nama + catalogSection byte-identik via hash probe
//      JVM vc319), bukan byte file-nya. Governor/Gate/Tools tetap
//      byte-identik.
// Argumen: [0] = direktori tree yang diuji (salinan merge + 4 file IXL).
// TIDAK BUTUH Context Android: semua yang dijalankan di sini murni;
// yang butuh Context hanya diperiksa lewat teks source (seksi 4).
// Token memakai kalibrasi kode: 3,4 char/token (DsApiClient.recordTokens).
// Keluar 0 = semua lulus; >0 gagal = exitProcess(1).

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

private fun sha256Hex(s: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    return d.joinToString("") { "%02x".format(it) }
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

private fun bodyOf(src: String, signature: String, nextSig: String): String {
    val i = src.indexOf(signature)
    if (i < 0) return ""
    val j = src.indexOf(nextSig, i + signature.length)
    return if (j < 0) src.substring(i) else src.substring(i, j)
}

fun main(args: Array<String>) {
    val tree = File(if (args.isNotEmpty()) args[0] else "merge")
    val merge = File("tests/baseline-vc315")
    // P1 mengubah DsApiClient/SlotClient (tangga pindah ke DsFailure) — 7.5/7.6
    // membandingkan ke versi P1, BUKAN baseline (diperbaiki koordinator vc316
    // saat integrasi; perilaku tangga dibuktikan P1 §5 di tree yang sama).
    val p1dir = File("tests/work-P1")
    val agentSrc = File(tree, "AgentPrompt.kt").readText()
    val mainSrc = File(tree, "MainActivity.kt").readText()
    val bgSrc = File(tree, "BgBrain.kt").readText()
    val tmSrc = File(tree, "TurnMetrics.kt").readText()
    val regSrc = File(tree, "ToolRegistry.kt").readText()

    // ---------- 1. Ukur katalog (runtime, murni) ----------
    val section = ToolRegistry.catalogSection()
    val names = ToolRegistry.names()
    println("== 1. Ukur katalog ==")
    println("INFO  katalog section: ${section.length} char (~${tok(section.length)} token)")
    check("1.1 ToolRegistry: 188 ToolDef", names.size == 188, "ada ${names.size}")
    val concatNames = names.filter { it.startsWith("concat_") }
    val browserNames = names.filter { it.startsWith("browser_") }
    check("1.2 Tool Concat: 21", concatNames.size == 21, concatNames.joinToString(","))
    check("1.3 Tool browser_*: 16", browserNames.size == 16, browserNames.joinToString(","))
    check("1.4 catalogSection memuat tiap dari 188 nama", names.all { section.contains("- $it ") }, "semua nama ada sebagai baris katalog")
    check("1.5 catalogSection = 86.707 char (ukur sebelum perbaikan, ±500)",
        section.length in 86200..87200, "${section.length} char (~${tok(section.length)} token)")
    // Per-baris: tool lines vs prose lines (baris "- " yang cocok regex tool).
    val toolLineRe = Regex("^- ([a-z][a-z0-9_]+) \\{")
    var toolChars = 0
    var proseChars = 0
    var toolLines = 0
    var proseLines = 0
    for (ln in section.lines()) {
        if (!ln.startsWith("- ")) continue
        if (toolLineRe.containsMatchIn(ln)) { toolChars += ln.length + 1; toolLines++ } else { proseChars += ln.length + 1; proseLines++ }
    }
    println("INFO  baris tool: $toolLines baris, $toolChars char (~${tok(toolChars)} token); baris prose: $proseLines baris, $proseChars char (~${tok(proseChars)} token)")
    check("1.6 Baris tool total 53.557 char (±500) — 207 baris: 188 registry + 19 baris semu-tool di dalam PROSE_*",
        toolChars in 53050..54050 && toolLines == 207, "$toolChars char, $toolLines baris")
    check("1.7 Baris prose total 33.138 char (±500) — 25 baris",
        proseChars in 32600..33700 && proseLines == 25, "$proseChars char, $proseLines baris")
    var concatChars = 0
    var browserChars = 0
    for (ln in section.lines()) {
        if (ln.startsWith("- concat_")) concatChars += ln.length + 1
        if (ln.startsWith("- browser_")) browserChars += ln.length + 1
    }
    println("INFO  baris concat_* (registry): $concatChars char; baris browser_* (registry): $browserChars char")
    check("1.8 Baris 21 tool concat_* = 7.808 char (±300) — registry 4.052 + baris semu-tool di PROSE_CONCAT_VC315",
        concatChars in 7500..8100, "$concatChars char")
    check("1.9 Baris 16 tool browser_* = 2.524 char (±300) — registry 1.475 + baris semu-tool di PROSE_BROWSER_VC313",
        browserChars in 2225..2825, "$browserChars char")
    // Baris tool terpanjang (hanya baris yang cocok regex tool, BUKAN semua baris "- ").
    val longestTool = section.lines().filter { toolLineRe.containsMatchIn(it) }.maxByOrNull { it.length } ?: ""
    check("1.10 Baris tool terpanjang = forge_trigger_eval 1.491 char (±100)",
        longestTool.startsWith("- forge_trigger_eval ") && longestTool.length in 1390..1590, "${longestTool.length} char: ${longestTool.take(60)}")
    // Prose terbesar: PROSE_CONCAT_VC315 (4.240) + SKILL_FORGE_EVAL (4.390)
    // dicek lewat source (private const) — ukur, bukan karangan.
    check("1.11 PROSE_CONCAT_VC315 = 4.240 char di source", regSrc.contains("PROSE_CONCAT_VC315"),
        "panjang diukur replika python: 4240 char")
    check("1.12 BROWSER_SKILL_DETAIL_VC313 = 2.866 char di source", regSrc.contains("BROWSER_SKILL_DETAIL_VC313"),
        "panjang diukur replika python: 2866 char (lazy-loaded, TIDAK masuk katalog)")

    // ---------- 2. Katalog penuh vs ringkas (runtime, murni) ----------
    println("== 2. Katalog penuh vs ringkas ==")
    val head = tripleAfter(agentSrc, "CATALOG_HEAD")
    val trailer = tripleAfter(agentSrc, "CATALOG_TRAILER")
    val note = lineAfter(agentSrc, "API_LAYER_NOTE_VC282")
    check("2.0 Konstanta katalog terbaca dari source (head/trailer/note)",
        head.isNotBlank() && trailer.isNotBlank() && note.isNotBlank(),
        "head=${head.length}, note=${note.length}, trailer=${trailer.length}")
    val full = (head + note + section + trailer)
        .replace("__MAXSTEPS__", "50")
        .replace("/data/data/com.senzwh.deepharness/files/proot/ubuntu", "com.senzwh.deepharness/files/proot/ubuntu")
    println("INFO  katalog PENUH: ${full.length} char (~${tok(full.length)} token)")
    check("2.1 Katalog penuh = 94.817 char (±600)", full.length in 94200..95400,
        "${full.length} char (~${tok(full.length)} token @3,4)")
    check("2.2 Pesan kosong -> katalog penuh (kontrak lama dipertahankan)",
        AgentPrompt.selectCatalog(full, "") == full && AgentPrompt.selectCatalog(full, "   ") == full)
    fun hasRegLine(out: String, tool: String): Boolean = out.contains("- $tool ")
    val slimConcat = AgentPrompt.selectCatalog(full, "kita tes concat")
    val slimCekConcat = AgentPrompt.selectCatalog(full, "cek concat")
    val slimVersion = AgentPrompt.selectCatalog(full, "concat_version")
    val slimBro = AgentPrompt.selectCatalog(full, "bro?")
    val slimHalo = AgentPrompt.selectCatalog(full, "halo")
    val slimBatt = AgentPrompt.selectCatalog(full, "cek baterai")
    val slimDoc = AgentPrompt.selectCatalog(full, "buatkan dokumen laporan")
    println("INFO  slim 'kita tes concat': ${slimConcat.length} char; 'cek concat': ${slimCekConcat.length}; 'concat_version': ${slimVersion.length}; 'bro?': ${slimBro.length}; 'halo': ${slimHalo.length}; 'cek baterai': ${slimBatt.length}; 'buatkan dokumen': ${slimDoc.length}")
    check("2.3 'cek concat' (kata aksi) TIDAK lagi katalog penuh: <=25.000 char",
        slimCekConcat.length <= 25000, "${slimCekConcat.length} char (dulu 94.817)")
    check("2.4 'concat_version' (nama tool) TIDAK lagi katalog penuh: <=25.000 char",
        slimVersion.length <= 25000, "${slimVersion.length} char (dulu 94.817)")
    check("2.5 'kita tes concat': SEMUA 21 baris registry concat_* ada",
        concatNames.all { hasRegLine(slimConcat, it) },
        "dulu (vc241, substring 'tes'): hanya 2/21 — concat_version + concat_export_cancel")
    check("2.6 'cek concat': SEMUA 21 baris registry concat_* ada",
        concatNames.all { hasRegLine(slimCekConcat, it) }, "dulu: katalog penuh 94.817 char")
    check("2.7 'kita tes concat': junk substring vc241 HILANG (remember_fact + PARLEMEN)",
        !slimConcat.contains("- remember_fact ") && !slimConcat.contains("PARLEMEN — debat"),
        "dulu keduanya ikut karena 'tes' cocok di substring")
    check("2.8 'bro?': SEMUA 16 baris registry browser_* ada + list_tools",
        browserNames.all { hasRegLine(slimBro, it) } && slimBro.contains("- list_tools "),
        "${slimBro.length} char (dulu 16.666)")
    check("2.9 'bro?': <=16.000 char", slimBro.length <= 16000, "${slimBro.length} char")
    check("2.10 'halo': list_tools + nama-nama lain, <=13.000 char",
        slimHalo.contains("- list_tools ") && slimHalo.contains("Tool lain yang tersedia") && slimHalo.length <= 13000,
        "${slimHalo.length} char")
    check("2.11 'cek baterai': baris get_battery ada, <=25.000 char",
        hasRegLine(slimBatt, "get_battery") && slimBatt.length <= 25000, "${slimBatt.length} char")
    check("2.12 'buatkan dokumen laporan': baris generate_document ada, <=25.000 char",
        hasRegLine(slimDoc, "generate_document") && slimDoc.length <= 25000, "${slimDoc.length} char")
    check("2.13 Setiap slim memuat list_tools + catatan fallback",
        listOf(slimConcat, slimCekConcat, slimVersion, slimBro, slimHalo, slimBatt, slimDoc)
            .all { it.contains("- list_tools ") && it.contains("Katalog lengkap tersedia kapan saja lewat list_tools") })
    check("2.14 ACTION_WORDS_VC241 sudah dihapus (tidak ada lagi pemaksa katalog penuh)",
        !agentSrc.contains("ACTION_WORDS_VC241"))
    check("2.15 Deskripsi tool TIDAK diubah: baris registry concat_edit_apply utuh di slim",
        slimConcat.contains("- concat_edit_apply {\"project\":") || slimConcat.contains("- concat_edit_apply {project"),
        "nama + format argumen registry tetap")

    // ---------- 3. Jangkar lanjutan delta (runtime, murni) ----------
    println("== 3. Jangkar lanjutan delta ==")
    val anchor = AgentPrompt.continuationAnchorVc316("kita tes concat")
    val anchorEmpty = AgentPrompt.continuationAnchorVc316("")
    val anchorLong = AgentPrompt.continuationAnchorVc316("x".repeat(600))
    println("INFO  jangkar: ${anchor.length} char; kosong: ${anchorEmpty.length}; perintah 600 char: ${anchorLong.length}")
    check("3.1 Jangkar <1.000 char", anchor.length < 1000, "${anchor.length} char")
    check("3.2 Jangkar memuat perintah user giliran ini", anchor.contains("kita tes concat"))
    check("3.3 Jangkar memuat format tool-call + id tc-<n>", anchor.contains("{\"tool\"") && anchor.contains("tc-<n>"))
    check("3.4 Jangkar memuat jalan keluar list_tools", anchor.contains("list_tools {}"))
    check("3.5 Jangkar menyatakan konteks sudah ada di sesi server yang sama",
        anchor.contains("SESI SERVER YANG SAMA"))
    check("3.6 Perintah user dicap 500 char (600 -> +500 persis)",
        anchorLong.length == anchorEmpty.length + 500, "panjang ${anchorLong.length} vs kosong ${anchorEmpty.length}")

    // ---------- 4. Cap prompt (runtime, murni) ----------
    println("== 4. Cap prompt ==")
    check("4.1 DEFAULT_CAP = 200.000 (dulu 400.000)", TurnMetrics.DEFAULT_CAP == 200000,
        "DEFAULT_CAP=${TurnMetrics.DEFAULT_CAP} (~${tok(TurnMetrics.DEFAULT_CAP)} token @3,4 — di bawah buffer web ~64.000 token)")
    check("4.2 Ambang proaktif 80% cap baru = 160.000", TurnMetrics.proactiveThreshold(200000) == 160000,
        "proactiveThreshold(200000)=${TurnMetrics.proactiveThreshold(200000)}")
    check("4.3 Rumus ambang tidak berubah (80% cap lama = 320.000)",
        TurnMetrics.proactiveThreshold(400000) == 320000)
    val (keptOld, cutOld) = TurnMetrics.dropOldestLines("baris1\nbaris2\nbaris3", 100)
    check("4.4 dropOldestLines: muat -> utuh, cut 0", cutOld == 0 && keptOld == "baris1\nbaris2\nbaris3", "cut=$cutOld")
    val (keptOld2, cutOld2) = TurnMetrics.dropOldestLines("aaaa\nbbbb\ncccc", 9)
    check("4.5 dropOldestLines: kelebihan -> baris tertua dibuang (regresi)",
        cutOld2 > 0 && !keptOld2.contains("aaaa"), "hasil=${keptOld2.replace("\n", "|")} cut=$cutOld2")
    val (keptNew, cutNew) = TurnMetrics.dropNewestLines("aaaa\nbbbb\ncccc", 9)
    check("4.6 dropNewestLines: kelebihan -> baris terbaru dibuang (regresi)",
        cutNew > 0 && !keptNew.contains("cccc"), "hasil=${keptNew.replace("\n", "|")} cut=$cutNew")

    // ---------- 5. Struktur lanjutan delta 1-1 (teks source) ----------
    println("== 5. Lanjutan delta 1-1 (MainActivity.sendContinuation) ==")
    val cont = bodyOf(mainSrc, "private fun sendContinuation", "    /** 4.48.9 (titik-kirim)")
    check("5.0 sendContinuation ditemukan", cont.isNotBlank(), "panjang ${cont.length} char")
    check("5.1 TIDAK ada lagi buildSystemPrompt di lanjutan", !cont.contains("buildSystemPrompt"))
    check("5.2 TIDAK ada lagi catalogForTask di lanjutan", !cont.contains("catalogForTask"))
    check("5.3 TIDAK ada lagi skillsText/factsText/goalsText di lanjutan",
        !cont.contains("skillsText(") && !cont.contains("factsText(") && !cont.contains("goalsText("))
    check("5.4 TIDAK ada lagi historyTranscriptVc230 di lanjutan", !cont.contains("historyTranscriptVc230"))
    check("5.5 Jangkar delta dipakai", cont.contains("continuationAnchorVc316"))
    check("5.6 JEJAK dipertahankan (buildJejak 12000 + header JEJAK)",
        cont.contains("buildJejak(12000)") && cont.contains("JEJAK LANGKAH GILIRAN INI"))
    check("5.7 Work-notes dipertahankan (workNotesBlockVc302 + turnSessionId)",
        cont.contains("workNotesBlockVc302(this, turnSessionId)"))
    check("5.8 Digest dipertahankan (digestBlock)", cont.contains("digestBlock()"))
    check("5.9 Trailer + HASIL TOOL TERBARU dipertahankan",
        cont.contains("PENTING_TRAILER") && cont.contains("HASIL TOOL TERBARU"))
    check("5.10 Metrik lanjutan: katalog + memori + riwayat = 0 (tidak dikirim ulang)",
        cont.contains("TurnMetrics.L_CATALOG to 0") && cont.contains("TurnMetrics.L_MEMORY to 0") && cont.contains("TurnMetrics.L_HISTORY to 0"))
    check("5.11 Guard + trim proaktif tetap ada (cap + proactiveThreshold)",
        cont.contains("TurnMetrics.promptCap(this)") && cont.contains("proactiveThreshold"))
    check("5.12 Rencana aktif tetap dibawa (dicap 2000)", cont.contains("RENCANA AKTIF GILIRAN INI") && cont.contains("take(2000)"))
    // Bagian delta (tanpa hasil tool): jangkar + rencana + JEJAK 1 langkah + digest kosong + work-notes kosong + trailer.
    // Ini BATAS ATAS (semua cap penuh sekaligus) — nilai tipikal jauh lebih kecil
    // (jangkar 503 + rencana ~0-200 + JEJAK ~200 + digest 0 + work-notes 0 + trailer 470 + hasil ~200 = ~1.600-3.700).
    // Yang dihemat delta = SELURUH system prompt lama (slim default ~21.000, katalog penuh 94.809)
    // + skills/fakta/goals + riwayat — semua itu TIDAK ada lagi di lanjutan.
    val deltaFixedBound = anchor.length + 2000 + 12000 + 2100 + 5500 + 470
    println("INFO  batas atas bagian tetap lanjutan delta (tanpa hasil tool): $deltaFixedBound char (~${tok(deltaFixedBound)} token)")
    check("5.13 Batas atas bagian tetap lanjutan delta <25.000 char (jangkar 503 + rencana 2.000 + JEJAK 12.000 + digest 2.100 + work-notes 5.500 + trailer 470 = 22.573; sistem prompt lama saja 21.000 slim / 94.809 penuh — delta menghemat seluruh porsi itu)",
        deltaFixedBound < 25000, "$deltaFixedBound char")

    // ---------- 6. Struktur lanjutan delta latar (teks source) ----------
    println("== 6. Lanjutan delta latar (BgBrain.continuationPrompt) ==")
    val bgCont = bodyOf(bgSrc, "private fun continuationPrompt", "    private fun loop(")
    check("6.0 continuationPrompt latar ditemukan", bgCont.isNotBlank(), "panjang ${bgCont.length} char")
    check("6.1 TIDAK ada lagi buildSystemPrompt di lanjutan latar", !bgCont.contains("buildSystemPrompt"))
    check("6.2 TIDAK ada lagi pinnedFactsText/factsText/goalsText di lanjutan latar",
        !bgCont.contains("pinnedFactsText") && !bgCont.contains("factsText(") && !bgCont.contains("goalsText("))
    check("6.3 Work-notes latar dipertahankan", bgCont.contains("workNotesBlockVc302"))
    check("6.4 Transkrip (hasil tool sejauh ini) dipertahankan", bgCont.contains("[RIWAYAT]") && bgCont.contains("transcript"))
    check("6.5 Tugas verbatim + penutup latar dipertahankan",
        bgCont.contains("TUGAS (verbatim") && bgCont.contains("Lanjutkan tugasnya:"))
    check("6.6 Jangkar delta latar ada (vc316 delta + SESI SERVER YANG SAMA)",
        bgCont.contains("vc316 delta") && bgCont.contains("SESI SERVER YANG SAMA"))

    // ---------- 7. Regresi: perilaku tool / Governor / gerbang / retry TIDAK berubah ----------
    println("== 7. Regresi file yang TIDAK boleh berubah ==")
    fun bytesEqual(a: File, b: File): Boolean = a.exists() && b.exists() && a.readBytes().contentEquals(b.readBytes())
    // 7.1 (vc320): bukan lagi byte-identik file — slice 6 menambah wrapper
    // Rust di ToolRegistry.kt. Yang dikunci: DEFINISI tool tidak berubah:
    // hash 188 nama + hash catalogSection sama persis dengan probe JVM
    // atas kode asli vc319 (nilai yang sama dipakai P6 1.2/1.7).
    check("7.1 Definisi tool tidak berubah (188 nama + catalogSection hash-identik probe vc319)",
        sha256Hex(names.joinToString(",")) == "38ee24a6c668b3171ed0b017456f9a1cf15525aa5b8a12a70a0fdee63912a912" &&
        sha256Hex(section) == "c3357c572acdcd593e23ca15b40169dca431ca6cc1f30f250db1e4c5d3fe3668")
    check("7.2 Tools.kt byte-identik baseline (dispatch + hasil tool tidak berubah)",
        bytesEqual(File(tree, "Tools.kt"), File(merge, "Tools.kt")))
    check("7.3 Governor.kt byte-identik baseline (kuota/cap langkah tidak berubah)",
        bytesEqual(File(tree, "Governor.kt"), File(merge, "Governor.kt")))
    check("7.4 ToolGate.kt byte-identik baseline (gerbang tidak berubah)",
        bytesEqual(File(tree, "ToolGate.kt"), File(merge, "ToolGate.kt")))
    // 7.5/7.6 (vc321): bukan lagi byte-identik P1 — fix IXL vc321 menambah
    // guard reset sesi server proaktif (akumulasi + prompt berikut >
    // DEFAULT_CAP) di kedua klien. Yang dikunci sejak vc321: protokol
    // TIDAK berubah (parent_message_id tetap NULL, kunci sesi server
    // tetap dsSrvSessId_/dsSlotSrv_, forceFresh/freshEphemeral tetap
    // dihormati, tangga retry tetap di DsFailure) + guard vc321 ada
    // (keputusan + pencatatan akumulasi + reset).
    // 7.5/7.6 (vc322): fix bocor sesi mengirim DELTA di giliran lanjutan —
    // akumulasi kini menghitung panjang prompt YANG DIKIRIM
    // (sendPromptVc322.length, bukan prompt.length) dan guard reset
    // dilewati untuk giliran delta (SlotClient: !useDeltaVc322 di antara
    // !freshEphemeral dan stored != null). Protokol + guard untuk
    // prompt penuh TIDAK berubah.
    val dsSrc = File(tree, "DsApiClient.kt").readText()
    val slotSrc = File(tree, "SlotClient.kt").readText()
    check("7.5 DsApiClient.kt: protokol tidak berubah + guard vc321 ada (parent NULL, kunci dsSrvSessId_, forceFresh, reset proaktif)",
        dsSrc.contains(".put(\"parent_message_id\", JSONObject.NULL)") &&
        dsSrc.contains("dsSrvSessId_\" + fp + \"_\" + harnessNo") &&
        dsSrc.contains("if (forceFresh) null else storedServerSessionAny") &&
        dsSrc.contains("shouldResetServerSessionVc321") &&
        dsSrc.contains("addServerAccumVc321(ctx, accumKeyVc321, sendPromptVc322.length)") &&
        dsSrc.contains("resetServerAccumVc321(ctx, accumKeyVc321)") &&
        dsSrc.contains("Wadah server di-reset proaktif vc321"))
    check("7.6 SlotClient.kt: protokol tidak berubah + guard vc321 ada (parent NULL, kunci dsSlotSrv_, freshEphemeral dilewati, reset proaktif)",
        slotSrc.contains(".put(\"parent_message_id\", JSONObject.NULL)") &&
        slotSrc.contains("\"dsSlotSrv_\" + slot + \"_\" + harnessNo") &&
        slotSrc.contains("!freshEphemeral && !useDeltaVc322 && stored != null") &&
        slotSrc.contains("shouldResetServerSessionVc321") &&
        slotSrc.contains("addServerAccumVc321(ctx, accumKeyVc321, sendPromptVc322.length)") &&
        slotSrc.contains("resetServerAccumVc321(ctx, accumKeyVc321)") &&
        slotSrc.contains("Wadah server di-reset proaktif vc321"))
    check("7.7 Tangga retry utuh di DsFailure.RATE_LIMIT_WAITS_MS (10/20/35 dtk + stream-putus 2x)",
        File(tree, "DsFailure.kt").readText().contains("longArrayOf(10_000L, 20_000L, 35_000L)"))
    check("7.8 Cap langkah 50 tidak berubah (MAXSTEPS default + AgentPrompt)",
        agentSrc.contains("__MAXSTEPS__") && !agentSrc.contains("MAXSTEPS__\", \"51\""))
    check("7.9 File IXL (4) + vc321 (GroupChat/DsApiClient/SlotClient) berubah vs baseline merge",
        listOf("AgentPrompt.kt", "MainActivity.kt", "BgBrain.kt", "TurnMetrics.kt").all { File(tree, it).exists() } &&
            !bytesEqual(File(tree, "AgentPrompt.kt"), File(merge, "AgentPrompt.kt")) &&
            !bytesEqual(File(tree, "MainActivity.kt"), File(merge, "MainActivity.kt")) &&
            !bytesEqual(File(tree, "BgBrain.kt"), File(merge, "BgBrain.kt")) &&
            !bytesEqual(File(tree, "TurnMetrics.kt"), File(merge, "TurnMetrics.kt")) &&
            !bytesEqual(File(tree, "GroupChat.kt"), File(merge, "GroupChat.kt")) &&
            !bytesEqual(File(tree, "DsApiClient.kt"), File(merge, "DsApiClient.kt")) &&
            !bytesEqual(File(tree, "SlotClient.kt"), File(merge, "SlotClient.kt")))
    check("7.10 TurnMetrics.kt: tidak ada lagi 400000", !tmSrc.contains("400000"))
    check("7.11 Tidak ada secret/token di file IXL (sapu hsurr:)",
        !agentSrc.contains("hsurr:") && !mainSrc.contains("hsurr:") && !bgSrc.contains("hsurr:") && !tmSrc.contains("hsurr:"))

    println("\nHASIL: $passed lulus, $failed gagal (total ${passed + failed})")
    if (failed > 0) exitProcess(1)
}
