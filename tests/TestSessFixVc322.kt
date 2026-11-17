// TestSessFixVc322 — FIX BOCOR SESI SERVER vc322 (keputusan koordinator
// 16 Nov 2026, URGENT). Bukti lapangan: 1 sesi Harness "Utama", 4 perintah
// di Browser 2 (akun Eneng Sunarsih) -> 4 SESI BARU di web DeepSeek —
// 1 sesi baru per giliran; pola yang dulu bikin akun kena suspend
// (ReviewerVc298: reviewer ephemeral tiap review -> suspend 12 Nov 2026).
// Mekanisme (terverifikasi dari kode + probe JVM UKURAN-SESI-VC322.md):
// guard reset vc321 benar, tapi prompt PENUH dikirim ulang tiap giliran;
// begitu prompt nyata >100.000 char (ambang terukur persis: P=100.000 ->
// 2 sesi / 4 giliran, P=100.001 -> 4 sesi / 4 giliran), tiap giliran =
// reset = sesi server baru. Sesi lama yang ditinggal TIDAK PERNAH dihapus
// di server: TIDAK ADA endpoint hapus di seluruh permukaan API app
// (hanya /api/v0/chat_session/create, /api/v0/chat/completion,
// /api/v0/chat/create_pow_challenge, /api/v0/file/*) dan
// clearServerSession hanya hapus catatan lokal di prefs.
// Perbaikan (Kotlin saja, tanpa JNI baru): giliran 1-1 lanjutan dikirim
// sebagai DELTA (jangkar vc316 + perintah user verbatim + work-notes
// segar) ke wadah server yang sudah primed — sesi server menyimpan
// konteks antar POST (mekanisme yang sama seperti lanjutan tool vc316 +
// delta room vc321). Prompt PENUH hanya untuk wadah baru / reset /
// pengganti / forceFresh. Ditambah penghitung sesi per (akun, sesi
// harness) + hitungan di tiap baris kelahiran JEJAK SESI SERVER.
// Argumen: [0] = direktori tree yang diuji (salinan terpadu).
// TurnMetrics counter/primed memakai FakeCtxVc322 (prefs in-memory).
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

// ---- Mesin status runChat (DsApiClient/SlotClient) untuk simulasi §4 ----
// Memakai fungsi ASLI dari tree: shouldResetServerSessionVc321 +
// shouldSendDeltaVc322. Primed = wadah sudah menerima satu prompt penuh
// sukses; create/reset/invalid/forceFresh membuat wadah baru (primed
// false) yang giliran itu juga menerima prompt penuh -> primed true.
private class SessSim(val deltaLen: Int) {
    var stored = false; var primed = false; var accum = 0
    var sessions = 0; var resets = 0; var forceFreshN = 0; var invalidN = 0
    var firstSentKind = ""; var lastSentKind = ""; var fullPosts = 0; var deltaPosts = 0
    fun post(fullLen: Int, useDeltaArg: Boolean, forceFresh: Boolean = false, invalid: Boolean = false) {
        if (forceFresh) {
            forceFreshN++; sessions++; stored = true; primed = false
            accum = fullLen; primed = true; note("penuh"); return
        }
        if (invalid && stored) {
            invalidN++; sessions++; stored = true; primed = false
            accum = fullLen; primed = true; note("penuh"); return
        }
        val useDelta = useDeltaArg && TurnMetrics.shouldSendDeltaVc322(stored, true, false, false, false, primed, accum, deltaLen)
        if (!useDelta && stored && TurnMetrics.shouldResetServerSessionVc321(accum, fullLen)) {
            resets++; sessions++; stored = true; primed = false; accum = 0
            accum += fullLen; primed = true; note("penuh"); return
        }
        if (!stored) { sessions++; stored = true; primed = false }
        if (useDelta) { accum += deltaLen; note("delta") } else { accum += fullLen; primed = true; note("penuh") }
    }
    // Lanjutan tool (sendContinuation): prompt kecil, tanpa delta giliran.
    fun postCont(len: Int) {
        if (!stored) { sessions++; stored = true; primed = false }
        accum += len; primed = true; note("penuh")
    }
    private fun note(kind: String) {
        if (firstSentKind.isEmpty()) firstSentKind = kind
        lastSentKind = kind
        if (kind == "penuh") fullPosts++ else deltaPosts++
    }
}

fun main(args: Array<String>) {
    val tree = File(if (args.isNotEmpty()) args[0] else ".")
    fun src(name: String): String = File(tree, name).readText()
    val tmSrc = src("TurnMetrics.kt")
    val apSrc = src("AgentPrompt.kt")
    val dsSrc = src("DsApiClient.kt")
    val slSrc = src("SlotClient.kt")
    val maSrc = src("MainActivity.kt")
    val gcSrc = src("GroupChat.kt")
    val bgSrc = src("BgBrain.kt")
    val flSrc = src("Flavor.kt")

    println("== §1. Builder delta (runtime) ==")
    val anchor = AgentPrompt.continuationAnchorVc316("cek skill lists")
    check("1.1 jangkar 'cek skill lists' 503 char", anchor.length == 503, "dapat ${anchor.length}")
    check("1.2 jangkar 'bro?' 492 char", AgentPrompt.continuationAnchorVc316("bro?").length == 492)
    check("1.3 jangkar perintah 600 char dicap 500 (988 char)", AgentPrompt.continuationAnchorVc316("x".repeat(600)).length == 988)
    val d1 = AgentPrompt.deltaTurnPromptVc322("cek skill lists", "")
    check("1.4 delta memuat jangkar vc316", "[LANJUTAN SISTEM — vc316 delta" in d1)
    check("1.5 delta memuat perintah user verbatim", "[USER — TUGAS 1-1 VERBATIM]:\ncek skill lists\n[/USER]" in d1)
    check("1.6 delta perintah pendek < 1200 char", d1.length < 1200, "dapat ${d1.length}")
    check("1.7 delta tanpa work-notes TIDAK memuat [RIWAYAT]/SKILL AKTIF", "[RIWAYAT]" !in d1 && "SKILL AKTIF" !in d1)
    val d2 = AgentPrompt.deltaTurnPromptVc322("cek skill lists", "CATATAN KERJA: jangan ulangi X")
    check("1.8 delta memuat work-notes segar", "CATATAN KERJA: jangan ulangi X" in d2)
    check("1.9 delta + work-notes lebih panjang dari delta kosong", d2.length == d1.length + "\n\nCATATAN KERJA: jangan ulangi X".length, "d1=${d1.length} d2=${d2.length}")
    val d3 = AgentPrompt.deltaTurnPromptVc322("x".repeat(600), "")
    check("1.10 blok [USER] delta membawa perintah 600 char UTUH (verbatim, tidak dicap)", "x".repeat(600) in d3)
    val d4 = AgentPrompt.deltaTurnPromptVc322("halo", "y".repeat(6000))
    val dEmptyHalo = AgentPrompt.deltaTurnPromptVc322("halo", "")
    check("1.11 work-notes > 5000 char dipotong + penanda sisa", "…(dipotong, sisa 1000 char)…" in d4 && d4.length == dEmptyHalo.length + 2 + 5000 + "…(dipotong, sisa 1000 char)…".length, "d4=${d4.length} harap=${dEmptyHalo.length + 2 + 5000 + 28}")

    println("== §2. Aturan kirim delta (murni) ==")
    val deltaLen = d1.length
    fun sel(stored: Boolean, hasDelta: Boolean, ff: Boolean, fe: Boolean, rev: Boolean, primed: Boolean, accum: Int, dl: Int): Boolean =
        TurnMetrics.shouldSendDeltaVc322(stored, hasDelta, ff, fe, rev, primed, accum, dl)
    check("2.1 normal: tersimpan + primed + delta -> delta", sel(true, true, false, false, false, true, 90500, deltaLen))
    check("2.2 wadah belum ada -> penuh", !sel(false, true, false, false, false, true, 0, deltaLen))
    check("2.3 tanpa prompt delta -> penuh", !sel(true, false, false, false, false, true, 90500, deltaLen))
    check("2.4 forceFresh -> penuh", !sel(true, true, true, false, false, true, 90500, deltaLen))
    check("2.5 freshEphemeral -> penuh", !sel(true, true, false, true, false, true, 90500, deltaLen))
    check("2.6 reviewer -> penuh", !sel(true, true, false, false, true, true, 90500, deltaLen))
    check("2.7 belum primed -> penuh", !sel(true, true, false, false, false, false, 90500, deltaLen))
    check("2.8 accum + delta > cap -> penuh", !sel(true, true, false, false, false, true, 199500, deltaLen))
    check("2.9 accum + delta == cap persis -> delta", sel(true, true, false, false, false, true, 200000 - deltaLen, deltaLen))
    check("2.10 accum 0 + tersimpan + primed -> delta", sel(true, true, false, false, false, true, 0, deltaLen))
    check("2.11 delta 0 char -> penuh", !sel(true, true, false, false, false, true, 90500, 0))

    println("== §3. Penghitung sesi + primed (FakeCtx) ==")
    val ctx = FakeCtxVc322(File(System.getProperty("java.io.tmpdir") ?: "."))
    val key = "dsSlotSrv_2_1"
    check("3.1 hitungan awal 0", TurnMetrics.serverSessionCountVc322(ctx, key) == 0)
    check("3.2 note ke-1 mengembalikan 1", TurnMetrics.noteServerSessionCreatedVc322(ctx, key) == 1)
    check("3.3 hitungan sesudah 1x = 1", TurnMetrics.serverSessionCountVc322(ctx, key) == 1)
    TurnMetrics.noteServerSessionCreatedVc322(ctx, key)
    TurnMetrics.noteServerSessionCreatedVc322(ctx, key)
    check("3.4 hitungan sesudah 3x = 3", TurnMetrics.serverSessionCountVc322(ctx, key) == 3)
    check("3.5 kunci lain independen (0)", TurnMetrics.serverSessionCountVc322(ctx, "dsSrvSessId_fpX_1") == 0)
    check("3.6 primed awal false", !TurnMetrics.isServerSessionPrimedVc322(ctx, key))
    TurnMetrics.setServerSessionPrimedVc322(ctx, key, true)
    check("3.7 primed true sesudah set", TurnMetrics.isServerSessionPrimedVc322(ctx, key))
    TurnMetrics.setServerSessionPrimedVc322(ctx, key, false)
    check("3.8 primed false sesudah clear", !TurnMetrics.isServerSessionPrimedVc322(ctx, key))
    check("3.9 akumulasi vc321 tetap jalan (tambah 90500)", run { TurnMetrics.addServerAccumVc321(ctx, key, 90500); TurnMetrics.serverAccumVc321(ctx, key) == 90500 })
    check("3.10 reset akumulasi -> 0, hitungan tetap 3", run { TurnMetrics.resetServerAccumVc321(ctx, key); TurnMetrics.serverAccumVc321(ctx, key) == 0 && TurnMetrics.serverSessionCountVc322(ctx, key) == 3 })

    println("== §4. Angka sebelum -> sesudah (simulasi mesin runChat, fungsi asli) ==")
    fun simFull(turns: Int, full: (Int) -> Int): SessSim {
        val s = SessSim(deltaLen)
        for (t in 1..turns) s.post(full(t), false)
        return s
    }
    fun simDelta(turns: Int, full: (Int) -> Int, dLen: Int = deltaLen, ffAt: Set<Int> = emptySet(), invAt: Set<Int> = emptySet()): SessSim {
        val s = SessSim(dLen)
        for (t in 1..turns) s.post(full(t), true, forceFresh = t in ffAt, invalid = t in invAt)
        return s
    }
    val b4 = simFull(4) { 90500 }
    check("4.1 SEBELUM B: P=90.500, 4 giliran -> 2 sesi", b4.sessions == 2, "dapat ${b4.sessions}")
    val c4 = simFull(4) { 110000 }
    check("4.2 SEBELUM C (reproduksi Sendy): P=110.000, 4 giliran -> 4 sesi", c4.sessions == 4, "dapat ${c4.sessions}")
    check("4.3 ambang: P=100.000 -> 2 sesi / 4 giliran", simFull(4) { 100000 }.sessions == 2)
    check("4.4 ambang: P=100.001 -> 4 sesi / 4 giliran", simFull(4) { 100001 }.sessions == 4)
    val h10 = simFull(10) { 75500 }
    check("4.5 SEBELUM H (pangkas saja): P=75.500, 10 giliran -> 5 sesi", h10.sessions == 5, "dapat ${h10.sessions}")
    check("4.6 SEBELUM B 10 giliran -> 5 sesi", simFull(10) { 90500 }.sessions == 5)
    val i1 = simDelta(10, { 90500 })
    check("4.7 SESUDAH I1: DELTA P=90.500, 10 giliran -> 1 sesi, 0 reset", i1.sessions == 1 && i1.resets == 0, "sesi=${i1.sessions} reset=${i1.resets}")
    check("4.8 SESUDAH I2: DELTA P=90.500, 30 giliran -> 1 sesi", simDelta(30, { 90500 }).sessions == 1)
    check("4.9 SESUDAH I4: DELTA P=150.000, 30 giliran -> 1 sesi", simDelta(30, { 150000 }).sessions == 1)
    check("4.10 SESUDAH J2: DELTA-8k P=90.500, 30 giliran -> 3 sesi", simDelta(30, { 90500 }, dLen = 8000).sessions == 3)
    check("4.11 SESUDAH I5: DELTA + forceFresh 1x -> 2 sesi", simDelta(10, { 90500 }, ffAt = setOf(5)).sessions == 2)
    check("4.12 SESUDAH I6: DELTA + invalid 1x -> 2 sesi", simDelta(10, { 90500 }, invAt = setOf(8)).sessions == 2)
    check("4.13 giliran pertama SELALU penuh (wadah belum ada)", i1.firstSentKind == "penuh" && i1.fullPosts >= 1)
    check("4.14 giliran lanjutan DELTA (9 delta / 10 giliran)", i1.deltaPosts == 9 && i1.fullPosts == 1, "delta=${i1.deltaPosts} penuh=${i1.fullPosts}")
    // Di rezim delta, giliran ke-2 TIDAK me-reset (150.000 + delta kecil
    // masih di bawah cap) — wadah tetap 1 dan tetap primed.
    val r = SessSim(deltaLen)
    r.post(150000, true) // giliran 1: penuh, primed
    r.post(150000, true) // giliran 2: DELTA (150.000 + delta <= 200.000)
    check("4.15 rezim delta: 2 giliran P=150.000 -> tetap 1 sesi, primed, accum=150.000+delta", r.sessions == 1 && r.primed && r.accum == 150000 + deltaLen, "sesi=${r.sessions} accum=${r.accum}")
    // Gerbang primed murni sudah dikunci §2.7 (belum primed -> penuh);
    // di sini: pengganti invalid langsung primed sesudah POST penuh sukses.
    val r2 = SessSim(deltaLen)
    r2.post(90500, true)
    r2.post(90500, true, invalid = true)
    check("4.15b sesudah invalid + POST penuh sukses, wadah pengganti primed", r2.sessions == 2 && r2.primed && r2.invalidN == 1)
    // Guard IXL tetap: accum 190k + penuh 60.526 -> reset; + delta 1k -> tidak.
    check("4.16 guard IXL: accum 190.000 + penuh 60.526 -> reset", TurnMetrics.shouldResetServerSessionVc321(190000, 60526))
    check("4.17 guard IXL: accum 190.000 + delta -> tidak reset", !TurnMetrics.shouldResetServerSessionVc321(190000, deltaLen))

    println("== §5. Struktur (source) ==")
    check("5.1 TurnMetrics: shouldSendDeltaVc322 + counter + primed ada",
        "fun shouldSendDeltaVc322" in tmSrc && "fun noteServerSessionCreatedVc322" in tmSrc &&
        "fun serverSessionCountVc322" in tmSrc && "fun isServerSessionPrimedVc322" in tmSrc &&
        "fun setServerSessionPrimedVc322" in tmSrc)
    check("5.2 AgentPrompt: deltaTurnPromptVc322 memakai continuationAnchorVc316",
        "fun deltaTurnPromptVc322" in apSrc && "continuationAnchorVc316(userCommand)" in apSrc)
    check("5.3 MainActivity dispatchTurn membangun deltaVc322 + kirim ke sendToAgent",
        "val deltaVc322" in maSrc && "sendToAgent(toSendVc240, deltaVc322)" in maSrc)
    check("5.4 MainActivity: auto-save vc298 KINI delta vc327 (blok ringkas ikut delta, bukan penuh — diubah vc327, preseden IXL 7.5/7.6 vc321)",
        "deltaTurnPromptVc327" in maSrc && "autoSaveBlockVc327(promptNoUser)" in maSrc && "sendToAgent(toSendVc240, deltaVc322)" in maSrc)
    check("5.5 MainActivity sendToAgent menerima deltaPrompt + teruskan ke kedua klien",
        "private fun sendToAgent(prompt: String, deltaPrompt: String? = null)" in maSrc &&
        "deltaPrompt = deltaPrompt" in maSrc)
    // CATATAN vc330: cek 5.6/5.7 diperbarui — chat/runChat kini membawa
    // param tambahan di ujung `silentKindVc330: String? = null` (katup
    // pengaman giliran senyap vc330). Perilaku deltaPrompt TIDAK berubah
    // (threading delta identik, param baru default null); premis
    // "tanda tangan berakhir di deltaPrompt" sudah tidak berlaku.
    // (Preseden: CATATAN vc323/vc324 memperbarui cek 5.18 yang sama
    // alasannya — VERSION naik karena JNI baru.)
    check("5.6 DsApiClient chat/runChat menerima deltaPrompt",
        "forceFresh: Boolean = false, deltaPrompt: String? = null" in dsSrc &&
        "private fun runChat(prompt: String, thinking: Boolean, search: Boolean, onPartial: (String) -> Unit, fileIds: List<String> = emptyList(), forceFresh: Boolean = false, bgMode: Boolean = false, deltaPrompt: String? = null, silentKindVc330: String? = null)" in dsSrc)
    check("5.7 SlotClient chat/runChat menerima deltaPrompt",
        "reviewer: Boolean = false, deltaPrompt: String? = null" in slSrc &&
        "forceFresh: Boolean = false, freshEphemeral: Boolean = false, reviewer: Boolean = false, deltaPrompt: String? = null, silentKindVc330: String? = null)" in slSrc)
    check("5.8 DsApiClient: keputusan delta + POST delta + akumulasi panjang terkirim",
        "val useDeltaVc322 = TurnMetrics.shouldSendDeltaVc322" in dsSrc &&
        "completeWithSession(sid, sendPromptVc322" in dsSrc &&
        "addServerAccumVc321(ctx, accumKeyVc321, sendPromptVc322.length)" in dsSrc)
    check("5.9 DsApiClient: jalur pengganti kirim ulang PENUH (bukan delta)",
        "val res2 = completeWithSession(fresh, prompt, thinking, search, token, cookie, ua, onPartial, fileIds)" in dsSrc)
    check("5.10 SlotClient: keputusan delta + POST delta + pengganti penuh",
        "val useDeltaVc322 = TurnMetrics.shouldSendDeltaVc322" in slSrc &&
        "completeWithSession(sid, sendPromptVc322" in slSrc &&
        "val res2 = completeWithSession(fresh, prompt, thinking, search, token, cookie, ua, onPartial)" in slSrc)
    check("5.11 SlotClient: ephemeral + reviewer dikecualikan dari delta",
        "TurnMetrics.shouldSendDeltaVc322(stored != null, deltaPrompt != null, forceFresh, freshEphemeral, reviewer," in slSrc)
    check("5.12 Guard vc321 + cap TIDAK berubah (DEFAULT_CAP 200.000, shouldReset dipakai kedua klien)",
        "const val DEFAULT_CAP = 200000" in tmSrc &&
        "shouldResetServerSessionVc321(accumVc321, prompt.length)" in dsSrc &&
        "shouldResetServerSessionVc321(accumVc321, prompt.length)" in slSrc)
    check("5.13 Penghitung dicatat di 4 situs lahir (2 normal + 2 pengganti)",
        dsSrc.split("noteServerSessionCreatedVc322").size - 1 == 2 &&
        slSrc.split("noteServerSessionCreatedVc322").size - 1 == 2)
    check("5.14 JEJAK memuat hitungan sesi (Sesi server ke-)",
        "Sesi server ke-" in dsSrc && "Sesi server ke-" in slSrc)
    check("5.15 TIDAK ADA hsurr: di 5 file fix", !("hsurr:" in tmSrc || "hsurr:" in apSrc || "hsurr:" in dsSrc || "hsurr:" in slSrc || "hsurr:" in maSrc))
    check("5.16 Flavor.kt tanpa referensi vc322", "Vc322" !in flSrc && "vc322" !in flSrc)
    check("5.17 GroupChat + BgBrain TIDAK tersentuh vc322 (room/latar di luar cakupan)",
        "Vc322" !in gcSrc && "vc322" !in gcSrc && "Vc322" !in bgSrc && "vc322" !in bgSrc)
    // RustCore.kt: di tree lokal (work-impl/integrated) ia ada di
    // <tree>/harness/core/; di repo/CI tree = .../deepharness sehingga
    // RustCore ada di <naik-3>/harness/core/ (deepharness -> senzwh
    // -> com -> java). Coba semua tingkat.
    // (Koreksi CI ronde 1+2: naik 1 dan 2 tingkat SALAH — parent tree CI
    // adalah .../com/senzwh lalu .../com, bukan .../java — 5.18 gagal
    // 2x di CI karena jalur ini.)
    val rcSrc = listOf(File(tree, "harness/core/RustCore.kt"), File(tree.parentFile, "harness/core/RustCore.kt"), File(tree.parentFile?.parentFile, "harness/core/RustCore.kt"), File(tree.parentFile?.parentFile?.parentFile, "harness/core/RustCore.kt")).firstOrNull { it.exists() }?.readText().orEmpty()
    check("5.18 RUST_CORE_VERSION 10 (slice 12 vc329 menambah 4 JNI bgRoom)", "const val VERSION = 10" in rcSrc)

    println("== §6. Sesi pendek + jalur lain tidak berubah ==")
    check("6.1 anggaran riwayat vc321 tetap (60/60000 normal, 30/30000 panjang)",
        TurnMetrics.historyBudgetVc321(0) == Pair(60, 60000) && TurnMetrics.historyBudgetVc321(39) == Pair(60, 60000) &&
        TurnMetrics.historyBudgetVc321(40) == Pair(30, 30000) && TurnMetrics.historyBudgetVc321(100) == Pair(30, 30000))
    check("6.2 sendContinuation jalur non-API kirim teks mentah; jalur API kirim delta (vc327: deltaPrompt = finalTxt)",
        "sendToAgent(text)" in maSrc && "sendToAgent(finalTxt, finalTxt)" in maSrc && "private fun sendToAgent(prompt: String, deltaPrompt: String? = null)" in maSrc)
    check("6.3 chatSync reviewer TIDAK membawa delta (tanda tangan lama)",
        "fun chatSync(prompt: String, thinking: Boolean, search: Boolean, freshEphemeral: Boolean = false, reviewer: Boolean = false)" in slSrc)
    check("6.4 parent_message_id tetap NULL di kedua klien (protokol tidak berubah)",
        dsSrc.split("parent_message_id").size - 1 >= 1 && slSrc.split("parent_message_id").size - 1 >= 1)
    check("6.5 forceFresh tetap dihormati (stream putus berulang -> sesi baru penuh, vc327: digate cooldown)",
        "forceFresh = true, bgMode = bgMode, deltaPrompt = deltaPrompt" in dsSrc &&
        "forceFresh = true, freshEphemeral = freshEphemeral, reviewer = reviewer, deltaPrompt = deltaPrompt" in slSrc)

    println()
    println("HASIL: $passed lulus, $failed gagal (total ${passed + failed})")
    if (failed > 0) exitProcess(1)
}
