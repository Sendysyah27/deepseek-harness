// TestSessFixVc330 — FIX BOCOR SESI vc330 (SATU fix, perintah Sendy;
// bukti live 17 Nov 2026 09:07, vc329): chat "Utama" lane 1-1 slot
// Browser 2 akun Eneng Sunarsih, vc329 terinstall (base.apk MD5
// 06aa0b0d…). "say hello bro" AMAN; "cek skill lists" (pakai tool) ->
// notifikasi "sesi browser 2 membuat wadah baru" padahal sesi Harness
// tetap "Utama".
// AKAR (dari kode, diverifikasi sebelum patch):
//  (1) MainActivity verifikasi diri memanggil sendToAgent(teks) TANPA
//      deltaPrompt — ctxHist 20.000 char ikut dikirim ulang.
//  (2) MainActivity tugas latar (ekstraksi) juga tanpa deltaPrompt —
//      hist 40 pesan / 40.000 char dikirim ulang (komentar vc241 O7
//      menyebut niat jendela 13 pesan = 10 baru + overlap 3, tapi kode
//      memakai 40/40.000).
//  (3) SlotClient/DsApiClient runChat: useDelta=false karena delta
//      null -> kirim prompt PENUH -> guard TurnMetrics
//      .shouldResetServerSessionVc321(accum + prompt > 200.000)
//      menembak -> clearServerSession + createServerSession = wadah BARU.
//  (4) Fix vc327 H3 (delta auto-save) HANYA mencakup giliran auto-save.
// PERBAIKAN (Kotlin saja, tanpa JNI baru, RUST_CORE_VERSION tetap 10):
//  (1) delta verifikasi (AgentPrompt.deltaVerifyPromptVc330): jangkar
//      vc316 + instruksi verifikasi yang sama + JEJAK TOOL + WorldState
//      + JAWABAN FINAL — TANPA ctxHist 20.000 char.
//  (2) delta ekstraksi (AgentPrompt.deltaExtractPromptVc330): jangkar
//      + instruksi tugas latar + jendela 13 pesan terakhir (cap 8.000
//      char) — BUKAN 40/40.000.
//  (3) katup pengaman: parameter silentKindVc330 ("verify"/"extract"/
//      null) di SlotClient.chat->runChat DAN DsApiClient.chat->runChat
//      (semua situs runChat di dalam chat()); di runChat, TEPAT di guard
//      reset, bila giliran senyap + tanpa delta + wadah ada + guard akan
//      menembak -> DILEWATI tanpa POST (log JEJAK + respons netral:
//      verify -> sentinel VERIFY_SKIPPED_VC330, extract -> "TIDAK ADA").
//      Keputusan murni di TurnMetrics.shouldSkipSilentVc330.
//      MainActivity mengecek sentinel SEBELUM cleanVerdict (cleanVerdict
//      akan memetakannya jadi "koreksi" bila lolos — cabang else <350).
// ANGKA PROBE (string nyata, input representatif — trail 2.000,
// visible 500, wsLine 22, ctxHist 20.000 / hist40 36.024):
//  verifikasi PENUH 23.417 -> delta 4009 (17%);
//  ekstraksi PENUH 36.578 -> delta 8434 (23%, jendela 7.224).
// Argumen: [0] = direktori tree yang diuji. TurnMetrics prefs memakai
// FakeCtxVc322 (in-memory per instance). Keluar 0 = semua lulus.
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

private fun freshCtx(): FakeCtxVc322 = FakeCtxVc322(File(System.getProperty("java.io.tmpdir") ?: "."))

// ---- Mesin status runChat (DsApiClient/SlotClient) untuk simulasi §4 ----
// Memakai fungsi ASLI dari tree (shouldResetServerSessionVc321,
// shouldSendDeltaVc322, migratePrimedOnceVc327, shouldSkipSilentVc330,
// serverAccum/addServerAccum/resetServerAccumVc321, primed get/set) di
// atas prefs FakeCtx. `regime330=false` = perilaku vc329 ASLI (giliran
// senyap TANPA delta + TANPA katup); `regime330=true` = perilaku vc330.
// Satu-satunya yang dimodelkan (bukan fungsi asli) adalah ALUR keputusan
// runChat itu sendiri — sama seperti SessSim P8/P13. `silent` = jenis
// giliran ("verify"/"extract"/null). Catatan katup: wadah tersimpan
// + accum>0 SELALU termigrasi primed oleh migratePrimedOnceVc327, jadi
// !useDelta pada giliran senyap vc330 hanya terjadi bila accum + delta
// > cap.
private class SessSim330(val ctx: FakeCtxVc322, val key: String, val regime330: Boolean) {
    var stored = false; var sessions = 0; var resets = 0; var skipped = 0
    var fullPosts = 0; var deltaPosts = 0
    private fun accum(): Int = TurnMetrics.serverAccumVc321(ctx, key)
    fun accumAkhir(): Int = accum()
    fun primed(): Boolean = TurnMetrics.isServerSessionPrimedVc322(ctx, key)
    fun post(fullLen: Int, deltaLen: Int?, silent: String? = null) {
        val primedEff = TurnMetrics.migratePrimedOnceVc327(ctx, key, stored, accum())
        // vc329 ASLI: giliran SENYAP tanpa delta (deltaLen dipaksa null);
        // giliran user tetap memakai delta vc322 seperti biasa.
        val effDeltaLen = if (!regime330 && silent != null) null else deltaLen
        val useDelta = TurnMetrics.shouldSendDeltaVc322(stored, effDeltaLen != null, false, false, false, primedEff, accum(), effDeltaLen ?: 0)
        val wouldReset = TurnMetrics.shouldResetServerSessionVc321(accum(), fullLen)
        if (regime330 && TurnMetrics.shouldSkipSilentVc330(silent, useDelta, stored, wouldReset, false)) {
            skipped++; return
        }
        if (!useDelta && stored && wouldReset) {
            resets++; sessions++; stored = true
            TurnMetrics.resetServerAccumVc321(ctx, key)
            TurnMetrics.addServerAccumVc321(ctx, key, fullLen)
            TurnMetrics.setServerSessionPrimedVc322(ctx, key, true)
            fullPosts++; return
        }
        if (!stored) { sessions++; stored = true; TurnMetrics.setServerSessionPrimedVc322(ctx, key, false) }
        if (useDelta) { TurnMetrics.addServerAccumVc321(ctx, key, deltaLen!!); deltaPosts++ }
        else { TurnMetrics.addServerAccumVc321(ctx, key, fullLen); TurnMetrics.setServerSessionPrimedVc322(ctx, key, true); fullPosts++ }
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
    val flSrc = src("Flavor.kt")
    val mdbSrc = src("MemoryDb.kt")
    val gcSrc = src("GroupChat.kt")
    val bgSrc = src("BgBrain.kt")
    // Cari file naik dari tree sampai ketemu (lokal 0-3 tingkat, CI 3-7).
    fun findUp(start: File, rel: String): String {
        var d: File? = start
        while (d != null) {
            val f = File(d, rel)
            if (f.exists()) return f.readText()
            d = d.parentFile
        }
        return ""
    }
    // RustCore.kt: lokal di <tree>/harness/core/, di CI di
    // <repo>/app/src/main/java/harness/core/ (3 tingkat di atas tree).
    val rcSrc = findUp(tree, "harness/core/RustCore.kt")
    // jni_glue.rs: <repo>/harness-core/src/jni_glue.rs.
    val jniSrc = findUp(tree, "harness-core/src/jni_glue.rs")

    // Input representatif (string nyata bentuknya, ukuran tipikal).
    val user = "cek skill lists"
    val trailTip = ("[HASIL TOOL tc-1] list_tools {\"tool\":\"list_tools\",\"args\":{}} -> 42 tool tersedia; " .repeat(25)).take(2000)
    val wsTip = "Baterai 78% • 09:07 WIB"
    val visTip = ("Skill yang terpasang: 15 skill aktif. ".repeat(14)).take(500)
    val ctxHistTip = ("CTXHIST-MARKER-UNIK-7f3a " + "riwayat percakapan lama. ".repeat(800)).take(20000)
    val histOldTip = ("HIST40-MARKER-LAMA-9c1e " + "pesan lama. ".repeat(3000)).take(36024)
    val histNewTip = ("HIST13-MARKER-BARU-4d2f " + "pesan baru. ".repeat(600)).take(7224)
    // Replika prompt PENUH (literal disalin verbatim dari MainActivity —
    // §5.3/5.4 mengunci literal produksi tetap sama; bila literal berubah,
    // cek panjang di bawah gagal dan replika harus diselaraskan).
    val fullVerifyTip = "VERIFIKASI DIRI (pemeriksaan oleh model yang sama — bukan independen; jangan menyapa, JANGAN pakai tool): Periksa JAWABAN FINAL di bawah terhadap JEJAK TOOL PENUH & RIWAYAT di bawah ini. Klaim yang cocok dengan JEJAK TOOL = DIDUKUNG (hasil tool memang tidak selalu tercatat sebagai baris riwayat terpisah). Klaim soal kondisi HP saat ini (persentase baterai, jam) HANYA didukung bila cocok persis dengan nilai WorldState giliran ini: " + wsTip + " — bila tidak cocok, itu TIDAK didukung. Ada klaim/angka yang TIDAK didukung hasil tool?\nATURAN OUTPUT KETAT: WAJIB Bahasa Indonesia. DILARANG menulis proses berpikir, draf, analisis, atau catatan meta dalam bahasa apa pun. Output HANYA SATU baris: 'LULUS' bila semua klaim didukung, atau 'KOREKSI: <satu kalimat koreksi yang jelas untuk user>' bila ada yang menyimpang. Tidak ada teks lain.\n\nJEJAK TOOL GILIRAN INI:\n$trailTip\n\nRIWAYAT:\n$ctxHistTip\n\nJAWABAN FINAL YANG DIPERIKSA:\n$visTip"
    val fullExtractTip = "TUGAS LATAR (jangan menyapa user, jangan menjawab pertanyaan apa pun): Berikut riwayat percakapan terakhir user dengan asisten. Ekstrak fakta BARU tentang user yang layak disimpan permanen (nama/panggilan, preferensi, jadwal rutin, orang penting, keputusan). Untuk SETIAP fakta panggil tool remember_fact (key singkat, value jelas). Koreksi gaya/aturan pakai learn_rule. Usulanmu DIKUMPULKAN untuk persetujuan batch user sebelum tersimpan — jadi usulkan semua fakta, jangan berhenti di satu. Kalau tidak ada yang baru, jawab persis: TIDAK ADA.\n\nRIWAYAT:\n$histOldTip"
    val dVerify = AgentPrompt.deltaVerifyPromptVc330(user, trailTip, wsTip, visTip)
    val dExtract = AgentPrompt.deltaExtractPromptVc330(user, histNewTip)

    println("== §1. Builder delta verifikasi (runtime) ==")
    check("1.1 delta verifikasi memuat jangkar vc316 + perintah user", "vc316 delta" in dVerify && "cek skill lists" in dVerify)
    check("1.2 delta verifikasi memuat JEJAK TOOL + WorldState + JAWABAN FINAL", "JEJAK TOOL GILIRAN INI:\n$trailTip" in dVerify && wsTip in dVerify && "JAWABAN FINAL YANG DIPERIKSA:\n$visTip" in dVerify)
    check("1.3 delta verifikasi TIDAK memuat marker ctxHist (riwayat tidak dikirim ulang)", "CTXHIST-MARKER-UNIK-7f3a" !in dVerify)
    check("1.4 delta verifikasi TIDAK memuat seksi RIWAYAT penuh (header 'RIWAYAT:' tanpa 'BARU')", "RIWAYAT:\n" !in dVerify)
    check("1.5 delta verifikasi memuat aturan output ketat verbatim (LULUS/KOREKSI satu baris)", "ATURAN OUTPUT KETAT" in dVerify && "'LULUS'" in dVerify && "'KOREKSI:" in dVerify && "Output HANYA SATU baris" in dVerify)
    check("1.6 delta verifikasi menyatakan riwayat SUDAH ada di sesi server (jangan minta ulang)", "SUDAH ada di konteks sesi server" in dVerify && "JANGAN minta dikirim ulang" in dVerify)
    check("1.7 delta verifikasi tipikal 4009 char (angka probe)", dVerify.length == 4009, "dapat ${dVerify.length}")
    check("1.8 delta verifikasi JAUH lebih kecil dari penuh 23417 (rasio < 25%)", dVerify.length * 4 < fullVerifyTip.length, "delta=${dVerify.length} penuh=${fullVerifyTip.length}")
    check("1.9 penuh verifikasi tipikal 23417 char (replika literal)", fullVerifyTip.length == 23417, "dapat ${fullVerifyTip.length}")
    check("1.10 delta verifikasi byte-identik untuk input tetap", AgentPrompt.deltaVerifyPromptVc330(user, trailTip, wsTip, visTip) == dVerify)
    check("1.11 delta verifikasi tanpa jejak/ws/jawaban tetap terbentuk (header ada)", run {
        val d0 = AgentPrompt.deltaVerifyPromptVc330(user, "", "", "")
        "JEJAK TOOL GILIRAN INI:\n" in d0 && "JAWABAN FINAL YANG DIPERIKSA:\n" in d0 && "WorldState giliran ini: " in d0
    })
    check("1.12 marker ctxHist 1x di penuh, 0x di delta", fullVerifyTip.split("CTXHIST-MARKER-UNIK-7f3a").size - 1 == 1 && dVerify.split("CTXHIST-MARKER-UNIK-7f3a").size - 1 == 0)

    println("== §2. Builder delta ekstraksi (runtime) ==")
    check("2.1 delta ekstraksi memuat jangkar vc316 + perintah user + penanda vc330", "vc316 delta" in dExtract && "cek skill lists" in dExtract && "TUGAS LATAR — vc330 delta" in dExtract)
    check("2.2 delta ekstraksi memuat instruksi tugas latar (remember_fact + TIDAK ADA)", "remember_fact" in dExtract && "jawab persis: TIDAK ADA" in dExtract)
    check("2.3 delta ekstraksi memuat marker jendela BARU, TIDAK memuat marker lama", "HIST13-MARKER-BARU-4d2f" in dExtract && "HIST40-MARKER-LAMA-9c1e" !in dExtract)
    check("2.4 delta ekstraksi memuat jendela 13 pesan terakhir (bukan 40)", "13 pesan terakhir" in dExtract && "RIWAYAT BARU (13 pesan terakhir):\n$histNewTip" in dExtract)
    check("2.5 konstanta jendela: N=13, CHARS=8000", AgentPrompt.EXTRACT_WINDOW_N_VC330 == 13 && AgentPrompt.EXTRACT_WINDOW_CHARS_VC330 == 8000)
    check("2.6 delta ekstraksi tipikal 8434 char (angka probe)", dExtract.length == 8434, "dapat ${dExtract.length}")
    check("2.7 delta ekstraksi JAUH lebih kecil dari penuh 36578 (rasio < 30%)", dExtract.length * 10 < fullExtractTip.length * 3, "delta=${dExtract.length} penuh=${fullExtractTip.length}")
    check("2.8 penuh ekstraksi tipikal 36578 char (replika literal)", fullExtractTip.length == 36578, "dapat ${fullExtractTip.length}")
    check("2.9 delta ekstraksi byte-identik untuk input tetap", AgentPrompt.deltaExtractPromptVc330(user, histNewTip) == dExtract)
    check("2.10 delta ekstraksi tanpa riwayat tetap terbentuk (header + jendela kosong)", run {
        val d0 = AgentPrompt.deltaExtractPromptVc330(user, "")
        "RIWAYAT BARU (13 pesan terakhir):\n" in d0 && "TUGAS LATAR — vc330 delta" in d0
    })

    println("== §3. Katup pengaman (keputusan murni TurnMetrics) ==")
    check("3.1 silent verify + tanpa delta + wadah ada + guard menembak -> LEWATI", TurnMetrics.shouldSkipSilentVc330("verify", false, true, true, false))
    check("3.2 silent extract + tanpa delta + wadah ada + guard menembak -> LEWATI", TurnMetrics.shouldSkipSilentVc330("extract", false, true, true, false))
    check("3.3 silent + DENGAN delta -> JALAN (delta tidak pernah memicu guard)", !TurnMetrics.shouldSkipSilentVc330("verify", true, true, true, false) && !TurnMetrics.shouldSkipSilentVc330("extract", true, true, true, false))
    check("3.4 user-visible (null) + guard menembak -> JALAN (reset seperti biasa)", !TurnMetrics.shouldSkipSilentVc330(null, false, true, true, false))
    check("3.5 silent + tanpa wadah tersimpan -> JALAN (tidak ada yang dibakar)", !TurnMetrics.shouldSkipSilentVc330("verify", false, false, true, false))
    check("3.6 silent + guard TIDAK menembak -> JALAN", !TurnMetrics.shouldSkipSilentVc330("verify", false, true, false, false))
    check("3.7 silent + ephemeral -> JALAN (wadah ephemeral memang dibuang tiap panggilan)", !TurnMetrics.shouldSkipSilentVc330("verify", false, true, true, true))
    check("3.8 silent + accum 0 -> JALAN (guard tidak pernah menembak di accum 0)", !TurnMetrics.shouldResetServerSessionVc321(0, 25000) && !TurnMetrics.shouldSkipSilentVc330("verify", false, true, TurnMetrics.shouldResetServerSessionVc321(0, 25000), false))
    check("3.9 guard vc321 TIDAK berubah (batas 200.000)", TurnMetrics.shouldResetServerSessionVc321(150000, 60000) && !TurnMetrics.shouldResetServerSessionVc321(150000, 40000) && !TurnMetrics.shouldResetServerSessionVc321(0, 250000) && TurnMetrics.DEFAULT_CAP == 200000)
    check("3.10 respons netral katup: verify -> sentinel, extract -> TIDAK ADA, sentinel bukan LULUS/koreksi", AgentPrompt.silentSkippedResponseVc330("verify") == AgentPrompt.VERIFY_SKIPPED_VC330 && AgentPrompt.silentSkippedResponseVc330("extract") == "TIDAK ADA" && AgentPrompt.silentSkippedResponseVc330(null) == AgentPrompt.VERIFY_SKIPPED_VC330 && !AgentPrompt.VERIFY_SKIPPED_VC330.startsWith("LULUS") && "koreksi" !in AgentPrompt.VERIFY_SKIPPED_VC330.lowercase())

    println("== §4. Angka sebelum -> sesudah (simulasi mesin runChat, fungsi asli) ==")
    fun sim330(key: String, regime: Boolean): SessSim330 = SessSim330(freshCtx(), key, regime)
    run {
        val s = sim330("k1", false)
        s.stored = true; TurnMetrics.setServerSessionPrimedVc322(s.ctx, s.key, true); TurnMetrics.addServerAccumVc321(s.ctx, s.key, 180000)
        s.post(25000, null, "verify")
        check("4.1 SEBELUM V1: verify penuh 25k di accum 180k -> 1 sesi baru (reset)", s.sessions == 1 && s.resets == 1 && s.skipped == 0, "sesi=${s.sessions} reset=${s.resets}")
    }
    run {
        val s = sim330("k1", true)
        s.stored = true; TurnMetrics.setServerSessionPrimedVc322(s.ctx, s.key, true); TurnMetrics.addServerAccumVc321(s.ctx, s.key, 180000)
        s.post(25000, 4009, "verify")
        check("4.2 SESUDAH V1: verify DELTA 4009 di accum 180k -> 0 sesi baru", s.sessions == 0 && s.resets == 0 && s.deltaPosts == 1 && s.accumAkhir() == 184009, "sesi=${s.sessions} accum=${s.accumAkhir()}")
    }
    run {
        val s = sim330("k1", true)
        s.stored = true; TurnMetrics.setServerSessionPrimedVc322(s.ctx, s.key, true); TurnMetrics.addServerAccumVc321(s.ctx, s.key, 196000)
        s.post(25000, 4009, "verify")
        check("4.3 SESUDAH V2 (katup): verify delta 4009 TAPI accum 196k + delta > cap -> DILEWATI, 0 sesi, accum tetap", s.sessions == 0 && s.resets == 0 && s.skipped == 1 && s.accumAkhir() == 196000, "sesi=${s.sessions} dilewati=${s.skipped} accum=${s.accumAkhir()}")
    }
    run {
        val s = sim330("k1", false)
        s.stored = true; TurnMetrics.setServerSessionPrimedVc322(s.ctx, s.key, true); TurnMetrics.addServerAccumVc321(s.ctx, s.key, 170000)
        s.post(36578, null, "extract")
        check("4.4 SEBELUM E1: extract penuh 36578 di accum 170k -> 1 sesi baru (reset)", s.sessions == 1 && s.resets == 1 && s.skipped == 0, "sesi=${s.sessions}")
    }
    run {
        val s = sim330("k1", true)
        s.stored = true; TurnMetrics.setServerSessionPrimedVc322(s.ctx, s.key, true); TurnMetrics.addServerAccumVc321(s.ctx, s.key, 170000)
        s.post(36578, 8434, "extract")
        check("4.5 SESUDAH E1: extract DELTA 8434 di accum 170k -> 0 sesi baru", s.sessions == 0 && s.resets == 0 && s.deltaPosts == 1 && s.accumAkhir() == 178434, "sesi=${s.sessions} accum=${s.accumAkhir()}")
    }
    run {
        val s = sim330("k1", true)
        s.stored = true; TurnMetrics.setServerSessionPrimedVc322(s.ctx, s.key, true); TurnMetrics.addServerAccumVc321(s.ctx, s.key, 196000)
        s.post(36578, 8434, "extract")
        check("4.6 SESUDAH E2 (katup): extract delta 8434 TAPI accum 196k + delta > cap -> DILEWATI, 0 sesi", s.sessions == 0 && s.resets == 0 && s.skipped == 1 && s.accumAkhir() == 196000, "sesi=${s.sessions} dilewati=${s.skipped}")
    }
    run {
        val s = sim330("k1", true)
        s.stored = true; TurnMetrics.setServerSessionPrimedVc322(s.ctx, s.key, true); TurnMetrics.addServerAccumVc321(s.ctx, s.key, 196000)
        s.post(25000, 4009, null)
        check("4.7 REGRESI user-visible: kondisi katup yang sama TAPI giliran user -> RESET 1 sesi (tidak dilewati)", s.sessions == 1 && s.resets == 1 && s.skipped == 0, "sesi=${s.sessions}")
    }
    run {
        val s = sim330("k1", true)
        s.stored = true // wadah tersimpan tapi accum 0 + belum primed (POST pertama belum sukses)
        s.post(25000, 4009, "verify")
        check("4.8 REGRESI silent + accum 0 -> JALAN (post penuh pertama, 0 sesi baru)", s.sessions == 0 && s.skipped == 0 && s.fullPosts == 1 && s.accumAkhir() == 25000, "penuh=${s.fullPosts} accum=${s.accumAkhir()}")
    }
    run {
        val s = sim330("k1", false)
        s.post(150000, null, null)
        s.post(557, 557, null)
        s.post(60000, null, "verify")
        check("4.9 REPRO SEBELUM (17 Nov 09:07): penuh 150k + delta + verify penuh 60k -> 2 sesi", s.sessions == 2 && s.resets == 1, "sesi=${s.sessions} reset=${s.resets}")
    }
    run {
        val s = sim330("k1", true)
        s.post(150000, null, null)
        s.post(557, 557, null)
        s.post(60000, 22009, "verify")
        check("4.10 REPRO SESUDAH: penuh 150k + delta + verify DELTA 22009 (trail 20k) -> 1 sesi", s.sessions == 1 && s.resets == 0 && s.deltaPosts == 2, "sesi=${s.sessions} delta=${s.deltaPosts} accum=${s.accumAkhir()}")
    }
    run {
        val s = sim330("k1", true)
        s.post(150000, null, null)
        s.post(557, 557, null)
        TurnMetrics.addServerAccumVc321(s.ctx, s.key, 34436) // dorong accum ke 184993 agar delta verify > cap
        s.post(60000, 22009, "verify")
        check("4.11 REPRO KATUP: accum 184993 + verify delta 22009 > cap -> DILEWATI, tetap 1 sesi", s.sessions == 1 && s.resets == 0 && s.skipped == 1, "sesi=${s.sessions} dilewati=${s.skipped} accum=${s.accumAkhir()}")
    }
    run {
        val s = sim330("k1", true)
        repeat(30) { s.post(90500, 557, null) }
        check("4.12 REGRESI delta normal 30 giliran -> 1 sesi", s.sessions == 1 && s.deltaPosts == 29 && s.fullPosts == 1, "delta=${s.deltaPosts} penuh=${s.fullPosts}")
    }
    run {
        val s = sim330("k1", true)
        s.stored = true; TurnMetrics.setServerSessionPrimedVc322(s.ctx, s.key, true); TurnMetrics.addServerAccumVc321(s.ctx, s.key, 195000)
        s.post(90500, 557, null)
        s.post(90500, null, null)
        check("4.13 REGRESI guard IXL tetap: accum 195k + delta lalu + penuh -> 1 reset", s.sessions == 1 && s.resets == 1, "sesi=${s.sessions} reset=${s.resets}")
    }

    println("== §5. Struktur (source) ==")
    check("5.1 AgentPrompt: deltaVerify + deltaExtract + sentinel + respons netral + konstanta jendela ada",
        "fun deltaVerifyPromptVc330" in apSrc && "fun deltaExtractPromptVc330" in apSrc &&
        "const val VERIFY_SKIPPED_VC330" in apSrc && "fun silentSkippedResponseVc330" in apSrc &&
        "const val EXTRACT_WINDOW_N_VC330 = 13" in apSrc && "const val EXTRACT_WINDOW_CHARS_VC330 = 8000" in apSrc &&
        "deltaVerifyPromptVc330" in apSrc && "continuationAnchorVc316(userCommand)" in apSrc)
    check("5.2 TurnMetrics: shouldSkipSilentVc330 ada + DEFAULT_CAP 200.000 tidak berubah",
        "fun shouldSkipSilentVc330" in tmSrc && "const val DEFAULT_CAP = 200000" in tmSrc)
    check("5.3 MainActivity verifikasi: fullVerifyVc330 + deltaVerifyPromptVc330(currentTurnUser + kirim delta",
        "val fullVerifyVc330" in maSrc && "AgentPrompt.deltaVerifyPromptVc330(currentTurnUser, trailTxt, wsLineVc228, visible)" in maSrc &&
        "sendToAgent(fullVerifyVc330, deltaVerifyVc330)" in maSrc)
    check("5.4 MainActivity ekstraksi: fullExtractVc330 + jendela 13/8000 + kirim delta",
        "val fullExtractVc330" in maSrc && "historyTranscriptVc230(AgentPrompt.EXTRACT_WINDOW_N_VC330, AgentPrompt.EXTRACT_WINDOW_CHARS_VC330, turnSessionId)" in maSrc &&
        "sendToAgent(fullExtractVc330, AgentPrompt.deltaExtractPromptVc330(currentTurnUser, histRecentVc330))" in maSrc)
    check("5.5 MainActivity sendToAgent: silentKindVc330 dari flag + diteruskan ke kedua klien",
        "val silentKindVc330: String? = when" in maSrc && "verifyTurn -> \"verify\"" in maSrc && "internalTurn -> \"extract\"" in maSrc &&
        "deltaPrompt = deltaPrompt, silentKindVc330 = silentKindVc330" in maSrc &&
        maSrc.split("silentKindVc330 = silentKindVc330").size - 1 == 2)
    check("5.6 MainActivity cabang verifyTurn: cek sentinel SEBELUM cleanVerdict + teks 'dilewati'",
        run {
            val si = maSrc.indexOf("AgentPrompt.VERIFY_SKIPPED_VC330")
            val ci = maSrc.indexOf("cleanVerdict(visible)", si)
            si >= 0 && ci > si && "Verifikasi diri dilewati vc330" in maSrc
        })
    check("5.7 SlotClient: param silentKindVc330 di chat + 6 situs runChat + katup + guard reset tidak berubah",
        "deltaPrompt: String? = null, silentKindVc330: String? = null" in slSrc &&
        slSrc.split("silentKindVc330 = silentKindVc330").size - 1 == 6 &&
        "TurnMetrics.shouldSkipSilentVc330(silentKindVc330, useDeltaVc322, stored != null" in slSrc &&
        "DILEWATI vc330 (wadah penuh: akumulasi" in slSrc &&
        "AgentPrompt.silentSkippedResponseVc330(silentKindVc330)" in slSrc &&
        "if (!freshEphemeral && !useDeltaVc322 && stored != null && TurnMetrics.shouldResetServerSessionVc321(accumVc321, prompt.length))" in slSrc)
    check("5.8 DsApiClient: param silentKindVc330 di chat + 8 situs runChat + katup + guard reset tidak berubah",
        "deltaPrompt: String? = null, silentKindVc330: String? = null" in dsSrc &&
        dsSrc.split("silentKindVc330 = silentKindVc330").size - 1 == 8 &&
        "TurnMetrics.shouldSkipSilentVc330(silentKindVc330, useDeltaVc322, stored != null" in dsSrc &&
        "DILEWATI vc330 (wadah penuh: akumulasi" in dsSrc &&
        "AgentPrompt.silentSkippedResponseVc330(silentKindVc330)" in dsSrc &&
        "if (!useDeltaVc322 && stored != null && TurnMetrics.shouldResetServerSessionVc321(accumVc321, prompt.length))" in dsSrc)
    check("5.9 chatSync TeamRun TIDAK membawa silentKind (tanda tangan lama)",
        "fun chatSync(prompt: String, thinking: Boolean, search: Boolean, freshEphemeral: Boolean = false, reviewer: Boolean = false)" in slSrc)
    check("5.10 TIDAK ADA hsurr: di 5 file fix", !("hsurr:" in tmSrc || "hsurr:" in apSrc || "hsurr:" in dsSrc || "hsurr:" in slSrc || "hsurr:" in maSrc))
    check("5.11 Batas file: Vc330 HANYA di 5 file fix (Flavor/MemoryDb/GroupChat/BgBrain/Rust tanpa Vc330)",
        "Vc330" !in flSrc && "vc330" !in flSrc && "Vc330" !in mdbSrc && "vc330" !in mdbSrc &&
        "Vc330" !in gcSrc && "vc330" !in gcSrc && "Vc330" !in bgSrc && "vc330" !in bgSrc &&
        "Vc330" !in jniSrc && "vc330" !in jniSrc)
    check("5.12 RUST_CORE_VERSION tetap 10 (tidak ada perubahan Rust)", "const val VERSION = 10" in rcSrc && "RUST_CORE_VERSION: jint = 10" in jniSrc)
    check("5.13 Governor tetap hitung prompt penuh di kedua klien", "Governor.weightFor(prompt.length)" in dsSrc && "Governor.weightFor(prompt.length)" in slSrc)
    check("5.14 forceFresh + cooldown vc327 TIDAK berubah", "FORCE_FRESH_COOLDOWN_MS_VC327" in tmSrc && "allowForceFreshVc327" in dsSrc && "allowForceFreshVc327" in slSrc)
    check("5.15 Delta vc322/vc327 DIPERTAHANKAN (auto-save + continuation + dispatchTurn)",
        "fun deltaTurnPromptVc327" in apSrc && "fun autoSaveBlockVc327" in apSrc &&
        "sendToAgent(toSendVc240, deltaVc322)" in maSrc && "sendToAgent(finalTxt, finalTxt)" in maSrc)

    println("== §6. Sesi pendek + jalur lain tidak berubah ==")
    check("6.1 anggaran riwayat vc321 tetap (60/60000 normal, 30/30000 panjang)",
        TurnMetrics.historyBudgetVc321(0) == Pair(60, 60000) && TurnMetrics.historyBudgetVc321(39) == Pair(60, 60000) &&
        TurnMetrics.historyBudgetVc321(40) == Pair(30, 30000) && TurnMetrics.historyBudgetVc321(100) == Pair(30, 30000))
    check("6.2 sendContinuation jalur non-API tetap kirim teks mentah (tanpa delta)",
        "sendToAgent(text)" in maSrc)
    check("6.3 WebView tetap jalur penuh (sendViaWebView tanpa silentKind/delta)",
        "sendViaWebView(prompt)" in maSrc && "fun sendViaWebView(prompt: String)" in maSrc)
    check("6.4 parent_message_id tetap NULL di kedua klien (protokol tidak berubah)",
        dsSrc.split("parent_message_id").size - 1 >= 1 && slSrc.split("parent_message_id").size - 1 >= 1)

    println()
    println("HASIL: $passed lulus, $failed gagal (total ${passed + failed})")
    if (failed > 0) exitProcess(1)
}
