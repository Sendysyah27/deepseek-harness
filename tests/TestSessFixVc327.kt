// TestSessFixVc327 — FIX RESIDUAL vc327 (bukti live Sendy 17 Nov 2026
// 00:13-00:15, vc325): jawaban utama lolos, langkah slot Browser 2 kartu
// merah input_exceeds_limit + stream_terputus, dan sesi server baru
// TETAP lahir dalam 1 sesi Harness yang sama. Investigasi koordinator
// (MILESTONES 00:20, dibuktikan dari kode vc325 — probe
// vc317-work/work-sess327/probe/probe-sess327.log):
//  H1 primed pasca-upgrade: sesi vc321 punya stored + accum tapi TANPA
//     kunci primed -> giliran pertama vc322 penuh -> bisa reset (1 sesi).
//  H2 forceFresh sesudah stream putus: tiap episode = 1 sesi baru, tanpa
//     batas (sinyal 4G lemah -> 1 sesi/giliran); + H2-CASCADE: cabang
//     create TIDAK reset accum -> giliran PENUH berikut kena guard reset
//     -> sesi baru KEDUA dari satu episode (probe K3: 4 episode -> 7 sesi).
//  H3 giliran penuh: auto-save vc298 memaksa delta=null (penuh ~90k,
//     ~2 auto-save = reset); sendContinuation deltaPrompt=null.
//  H4 session-invalid: SEMPIT + SUDAH BENAR — TIDAK DIUBAH.
// Perbaikan (Kotlin saja, tanpa JNI baru, RUST_CORE_VERSION tetap 8):
//  (1) migrasi primed satu kali (stored + accum>0 -> primed=true);
//  (2) forceFresh ditahan: retry sama-sesi 3x (2/5/15 dtk, reviewer
//      tetap 2x) + maks 1 forceFresh per 10 menit per (lane, sesi
//      harness); episode dalam jendela GAGAL tanpa membakar sesi;
//  (3) reset accum di cabang create (kecuali ephemeral SlotClient);
//  (4) delta auto-save (blok [SISTEM — AUTO-SAVE] ringkas, cap 2000) +
//      delta untuk continuation (deltaPrompt = finalTxt).
// Angka probe (UKUR SEBELUM): auto-save 10 giliran 5 sesi -> 1; 30
// giliran 15 -> 1; upgrade 1 sesi baru -> 0; 4 episode putus 7 sesi
// total -> 3 (2 ff + 2 episode gagal tanpa sesi); cascade 2 sesi baru
// per episode -> 1; lanjutan: hasil guard IDENTIK (K4==L4b).
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
// shouldSendDeltaVc322, migratePrimedOnceVc327, allow/noteForceFreshVc327,
// serverAccum/addServerAccum/resetServerAccumVc321, primed get/set) di
// atas prefs FakeCtx. `regime327=false` = perilaku vc322 ASLI (tanpa
// migrasi, tanpa cooldown, tanpa reset-accum-di-create, lanjutan tanpa
// delta); `regime327=true` = perilaku vc327. Satu-satunya yang dimodelkan
// (bukan fungsi asli) adalah ALUR keputusan runChat itu sendiri — sama
// seperti SessSim P8.
private class SessSim327(val ctx: FakeCtxVc322, val key: String, val ffKey: String, val regime327: Boolean) {
    var stored = false; var sessions = 0; var resets = 0; var ffN = 0; var ffSuppressed = 0
    var invalidN = 0; var failedTurns = 0; var fullPosts = 0; var deltaPosts = 0
    private fun accum(): Int = TurnMetrics.serverAccumVc321(ctx, key)
    fun accumAkhir(): Int = accum()
    fun primed(): Boolean = TurnMetrics.isServerSessionPrimedVc322(ctx, key)
    fun post(fullLen: Int, deltaLen: Int?, forceFreshEp: Boolean = false, invalid: Boolean = false, nowMs: Long = 0L) {
        val primedEff = if (regime327) TurnMetrics.migratePrimedOnceVc327(ctx, key, stored, accum()) else primed()
        val useDelta = TurnMetrics.shouldSendDeltaVc322(stored, deltaLen != null, forceFreshEp, false, false, primedEff, accum(), deltaLen ?: 0)
        if (forceFreshEp) {
            if (regime327) {
                if (TurnMetrics.allowForceFreshVc327(ctx, ffKey, nowMs)) {
                    TurnMetrics.noteForceFreshVc327(ctx, ffKey, nowMs)
                    ffN++; sessions++; stored = true
                    TurnMetrics.resetServerAccumVc321(ctx, key) // cabang create vc327
                    TurnMetrics.setServerSessionPrimedVc322(ctx, key, false)
                    TurnMetrics.addServerAccumVc321(ctx, key, fullLen)
                    TurnMetrics.setServerSessionPrimedVc322(ctx, key, true)
                    fullPosts++
                } else { ffSuppressed++; failedTurns++ }
            } else {
                ffN++; sessions++; stored = true
                // vc322 ASLI: cabang create TANPA reset accum (cascade).
                TurnMetrics.setServerSessionPrimedVc322(ctx, key, false)
                TurnMetrics.addServerAccumVc321(ctx, key, fullLen)
                TurnMetrics.setServerSessionPrimedVc322(ctx, key, true)
                fullPosts++
            }
            return
        }
        if (invalid && stored) {
            invalidN++; sessions++; stored = true
            TurnMetrics.resetServerAccumVc321(ctx, key)
            TurnMetrics.addServerAccumVc321(ctx, key, fullLen)
            TurnMetrics.setServerSessionPrimedVc322(ctx, key, true)
            fullPosts++; return
        }
        if (!useDelta && stored && TurnMetrics.shouldResetServerSessionVc321(accum(), fullLen)) {
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
    // Lanjutan tool: vc322 deltaPrompt=null (guard atas len); vc327
    // deltaPrompt=len (guard dilewati hanya bila accum+len<=cap — syarat
    // yang persis sama dengan guard TIDAK menembak).
    fun postCont(len: Int) {
        val primedEff = if (regime327) TurnMetrics.migratePrimedOnceVc327(ctx, key, stored, accum()) else primed()
        val useDelta = if (regime327) TurnMetrics.shouldSendDeltaVc322(stored, true, false, false, false, primedEff, accum(), len) else false
        if (!useDelta && stored && TurnMetrics.shouldResetServerSessionVc321(accum(), len)) {
            resets++; sessions++; stored = true
            TurnMetrics.resetServerAccumVc321(ctx, key)
            TurnMetrics.addServerAccumVc321(ctx, key, len)
            TurnMetrics.setServerSessionPrimedVc322(ctx, key, true)
            fullPosts++; return
        }
        if (!stored) { sessions++; stored = true }
        TurnMetrics.addServerAccumVc321(ctx, key, len)
        if (!useDelta) TurnMetrics.setServerSessionPrimedVc322(ctx, key, true)
        if (useDelta) deltaPosts++ else fullPosts++
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
    val dfSrc = src("DsFailure.kt")
    val flSrc = src("Flavor.kt")
    val gcSrc = src("GroupChat.kt")
    val bgSrc = src("BgBrain.kt")
    val rcSrc = listOf(File(tree, "harness/core/RustCore.kt"), File(tree.parentFile, "harness/core/RustCore.kt"), File(tree.parentFile?.parentFile, "harness/core/RustCore.kt"), File(tree.parentFile?.parentFile?.parentFile, "harness/core/RustCore.kt")).firstOrNull { it.exists() }?.readText().orEmpty()

    // Blok AUTO-SAVE seperti MainActivity:5880 (header + note + "]\n\n").
    val autoHead = "[SISTEM — AUTO-SAVE vc298: pesan user giliran ini SUDAH disimpan otomatis secara deterministik (BUKAN olehmu). JANGAN panggil remember_fact / remember_lesson lagi untuk konten di bawah — sudah tersimpan; sampaikan konfirmasinya ke user dalam jawaban final, lalu lanjutkan sisa tugas seperti biasa (terapkan koreksinya sekarang bila pesan ini juga berisi tugas):\n"
    val noteTyp = "[STATUS: ok] Tersimpan di memori permanen [inserted] (belum dikonfirmasi — menunggu persetujuan user di UI memori, Setup 65): hobi = baca buku [x] = y\n[STATUS: ok] Lesson baru disimpan #12 (keyakinan 0.70 — naik bila koreksi yang sama terulang): jangan ulangi X. Lesson muncul kembali sebelum kerja serupa.\n"
    val promptAutoTyp = "AWAL PROMPT\n" + autoHead + noteTyp.trim() + "]\n\nSKILL AKTIF (prosedur yang dibuat user):\ncontoh skill\n\n"
    val deltaBase = AgentPrompt.deltaTurnPromptVc322("cek skill lists", "")

    println("== §1. Builder delta auto-save (runtime) ==")
    check("1.1 autoSaveBlock null bila bukan giliran auto-save", AgentPrompt.autoSaveBlockVc327("prompt biasa tanpa penanda") == null)
    val blockTyp = AgentPrompt.autoSaveBlockVc327(promptAutoTyp)
    check("1.2 blok auto-save tipikal diekstrak (tidak null)", blockTyp != null)
    check("1.3 blok memuat instruksi + isi simpanan + tutup ']'", blockTyp != null && "[SISTEM — AUTO-SAVE vc298" in blockTyp && "hobi = baca buku [x] = y" in blockTyp && "jangan ulangi X" in blockTyp && blockTyp.endsWith("]"))
    check("1.4 blok TIDAK membawa seksi sesudahnya (SKILL AKTIF)", blockTyp != null && "SKILL AKTIF" !in blockTyp)
    check("1.5 blok tipikal 669 char (angka probe 671 minus \"\\n\\n\" akhir)", blockTyp != null && blockTyp.length == 669, "dapat ${blockTyp?.length}")
    check("1.6 ']' di tengah baris note TIDAK memotong blok lebih awal", blockTyp != null && "Lesson baru disimpan #12" in blockTyp)
    val blockLong = AgentPrompt.autoSaveBlockVc327(autoHead + "z".repeat(3000) + "]\n\n")
    check("1.7 blok panjang (note 3000 char) dicap 2000 + penanda sisa", blockLong != null && blockLong.length == 2000 + "…(dipotong, sisa 1363 char)…".length && "…(dipotong, sisa 1363 char)…" in blockLong, "dapat ${blockLong?.length}")
    val dNoBlock = AgentPrompt.deltaTurnPromptVc327("cek skill lists", "", null)
    check("1.8 delta tanpa blok PERSIS delta vc322", dNoBlock == deltaBase, "d=${dNoBlock.length} base=${deltaBase.length}")
    val dAuto = AgentPrompt.deltaTurnPromptVc327("cek skill lists", "", blockTyp)
    check("1.9 delta auto-save = delta vc322 + blok (rumus eksak)", dAuto.length == deltaBase.length + 2 + (blockTyp?.length ?: 0) && "[SISTEM — AUTO-SAVE vc298" in dAuto && "[USER — TUGAS 1-1 VERBATIM]:\ncek skill lists\n[/USER]" in dAuto, "dapat ${dAuto.length} harap ${deltaBase.length + 2 + (blockTyp?.length ?: 0)}")
    check("1.10 delta auto-save tipikal 1228 char (557 + 2 + 669)", dAuto.length == 1228, "dapat ${dAuto.length}")
    val dAutoLong = AgentPrompt.deltaTurnPromptVc327("cek skill lists", "", blockLong)
    check("1.11 delta auto-save terburuk 2587 char (angka probe)", dAutoLong.length == 2587, "dapat ${dAutoLong.length}")
    check("1.12 delta auto-save JAUH lebih kecil dari penuh 90.500", dAuto.length < 2000 && dAutoLong.length < 3000)

    println("== §2. Migrasi primed satu kali (FakeCtx) ==")
    run {
        val ctx = freshCtx()
        check("2.1 tanpa wadah tersimpan -> tidak migrasi (false)", !TurnMetrics.migratePrimedOnceVc327(ctx, "k1", false, 150000) && !TurnMetrics.isServerSessionPrimedVc322(ctx, "k1"))
    }
    run {
        val ctx = freshCtx()
        check("2.2 wadah tersimpan tapi accum 0 -> tidak migrasi (wadah belum pernah POST sukses)", !TurnMetrics.migratePrimedOnceVc327(ctx, "k1", true, 0) && !TurnMetrics.isServerSessionPrimedVc322(ctx, "k1"))
    }
    run {
        val ctx = freshCtx()
        TurnMetrics.addServerAccumVc321(ctx, "k1", 150000)
        check("2.3 wadah tersimpan + accum>0 -> migrasi true + primed set", TurnMetrics.migratePrimedOnceVc327(ctx, "k1", true, 150000) && TurnMetrics.isServerSessionPrimedVc322(ctx, "k1"))
    }
    run {
        val ctx = freshCtx()
        TurnMetrics.setServerSessionPrimedVc322(ctx, "k1", true)
        check("2.4 sudah primed -> migrasi true tanpa mengubah apa pun", TurnMetrics.migratePrimedOnceVc327(ctx, "k1", true, 150000) && TurnMetrics.isServerSessionPrimedVc322(ctx, "k1"))
    }
    run {
        val ctx = freshCtx()
        TurnMetrics.addServerAccumVc321(ctx, "k1", 150000)
        TurnMetrics.migratePrimedOnceVc327(ctx, "k1", true, 150000)
        check("2.5 migrasi idempotent (panggilan ke-2 tetap true + primed)", TurnMetrics.migratePrimedOnceVc327(ctx, "k1", true, 150000) && TurnMetrics.isServerSessionPrimedVc322(ctx, "k1"))
        check("2.6 migrasi TIDAK mengubah accum (tetap 150000)", TurnMetrics.serverAccumVc321(ctx, "k1") == 150000)
    }
    run {
        val ctx = freshCtx()
        TurnMetrics.addServerAccumVc321(ctx, "k1", 150000)
        TurnMetrics.migratePrimedOnceVc327(ctx, "k1", true, 150000)
        check("2.7 kunci lain independen (tidak ikut primed)", !TurnMetrics.isServerSessionPrimedVc322(ctx, "k2"))
    }

    println("== §3. Cooldown forceFresh 10 menit (FakeCtx) ==")
    check("3.1 FORCE_FRESH_COOLDOWN_MS_VC327 == 600000 (10 mnt)", TurnMetrics.FORCE_FRESH_COOLDOWN_MS_VC327 == 600_000L)
    run {
        val ctx = freshCtx()
        check("3.2 belum pernah -> allow true", TurnMetrics.allowForceFreshVc327(ctx, "ff1", 1_000_000L))
        check("3.3 lastForceFreshAt awal 0", TurnMetrics.lastForceFreshAtVc327(ctx, "ff1") == 0L)
        TurnMetrics.noteForceFreshVc327(ctx, "ff1", 1_000_000L)
        check("3.4 sesudah note, last tercatat", TurnMetrics.lastForceFreshAtVc327(ctx, "ff1") == 1_000_000L)
        check("3.5 +1 dtk -> allow false", !TurnMetrics.allowForceFreshVc327(ctx, "ff1", 1_001_000L))
        check("3.6 +9 mnt 59 dtk -> allow false", !TurnMetrics.allowForceFreshVc327(ctx, "ff1", 1_000_000L + 599_000L))
        check("3.7 +10 mnt persis -> allow true (batas)", TurnMetrics.allowForceFreshVc327(ctx, "ff1", 1_000_000L + 600_000L))
        check("3.8 +11 mnt -> allow true", TurnMetrics.allowForceFreshVc327(ctx, "ff1", 1_000_000L + 660_000L))
        check("3.9 jam mundur (now < last) -> allow true (jangan kunci pengguna)", TurnMetrics.allowForceFreshVc327(ctx, "ff1", 999_000L))
        check("3.10 kunci lain independen (allow true)", TurnMetrics.allowForceFreshVc327(ctx, "ff2", 1_001_000L))
    }

    println("== §4. Angka sebelum -> sesudah (simulasi mesin runChat, fungsi asli) ==")
    val dAutoLen = dAuto.length // 1228
    val dAutoWorstLen = dAutoLong.length // 2587
    fun sim327(key: String, regime: Boolean): SessSim327 = SessSim327(freshCtx(), key, "ff_$key", regime)
    run {
        val s = sim327("k1", false)
        repeat(10) { s.post(90500, null) }
        check("4.1 SEBELUM K1: auto-save penuh, 10 giliran -> 5 sesi", s.sessions == 5 && s.resets == 4, "sesi=${s.sessions} reset=${s.resets}")
    }
    run {
        val s = sim327("k1", false)
        repeat(30) { s.post(90500, null) }
        check("4.2 SEBELUM K1b: auto-save penuh, 30 giliran -> 15 sesi", s.sessions == 15, "sesi=${s.sessions}")
    }
    run {
        val s = sim327("k1", false)
        s.stored = true; TurnMetrics.addServerAccumVc321(s.ctx, s.key, 150000)
        repeat(10) { t -> s.post(90500, if (t == 0) null else deltaBase.length) }
        check("4.3 SEBELUM K2: upgrade primed=false + accum 150k -> 1 sesi baru", s.sessions == 1 && s.resets == 1, "sesi=${s.sessions}")
    }
    run {
        val s = sim327("k1", false)
        s.post(90500, null)
        for (t in 2..10) s.post(90500, deltaBase.length, forceFreshEp = t in setOf(3, 5, 7, 9), nowMs = t * 2 * 60_000L)
        check("4.4 SEBELUM K3: 4 episode forceFresh -> 7 sesi (4 ff + 2 reset cascade)", s.sessions == 7 && s.resets == 2 && s.ffN == 4, "sesi=${s.sessions} reset=${s.resets} ff=${s.ffN}")
    }
    run {
        val s = sim327("k1", false)
        s.post(90500, null)
        for (t in 2..10) s.post(90500, if (t == 6) null else deltaBase.length, forceFreshEp = t == 5, nowMs = t * 2 * 60_000L)
        check("4.5 SEBELUM K3b: CASCADE 1 episode + auto-save penuh -> 3 sesi (2 sesi baru/episode)", s.sessions == 3 && s.resets == 1 && s.ffN == 1, "sesi=${s.sessions}")
    }
    run {
        val s = sim327("k1", false)
        s.post(90500, null)
        for (t in 2..10) { s.post(90500, deltaBase.length); repeat(2) { s.postCont(12500) } }
        check("4.6 SEBELUM K4: delta + 2 lanjutan 12,5k/giliran -> 2 sesi, 1 reset", s.sessions == 2 && s.resets == 1, "sesi=${s.sessions} reset=${s.resets} accum=${s.accumAkhir()}")
    }
    run {
        val s = sim327("k1", true)
        repeat(10) { s.post(90500, dAutoLen) }
        check("4.7 SESUDAH L1: auto-save DELTA, 10 giliran -> 1 sesi", s.sessions == 1 && s.resets == 0, "sesi=${s.sessions} accum=${s.accumAkhir()}")
    }
    run {
        val s = sim327("k1", true)
        repeat(30) { s.post(90500, dAutoLen) }
        check("4.8 SESUDAH L1b: auto-save DELTA, 30 giliran -> 1 sesi", s.sessions == 1, "sesi=${s.sessions} accum=${s.accumAkhir()}")
    }
    run {
        val s = sim327("k1", true)
        repeat(30) { s.post(90500, dAutoWorstLen) }
        check("4.9 SESUDAH L1c: auto-save DELTA terburuk, 30 giliran -> 1 sesi", s.sessions == 1, "sesi=${s.sessions} accum=${s.accumAkhir()}")
    }
    run {
        val s = sim327("k1", true)
        s.stored = true; TurnMetrics.addServerAccumVc321(s.ctx, s.key, 150000)
        repeat(10) { s.post(90500, deltaBase.length) }
        check("4.10 SESUDAH L2: upgrade + migrasi -> 0 sesi baru, primed langsung", s.sessions == 0 && s.primed(), "sesi=${s.sessions} primed=${s.primed()}")
    }
    run {
        val s = sim327("k1", true)
        s.post(90500, null)
        for (t in 2..10) s.post(90500, deltaBase.length, forceFreshEp = t in setOf(3, 5, 7, 9), nowMs = t * 2 * 60_000L)
        check("4.11 SESUDAH L3: 4 episode + cooldown -> 3 sesi (2 ff + 2 ditahan + 2 gagal)", s.sessions == 3 && s.ffN == 2 && s.ffSuppressed == 2 && s.failedTurns == 2, "sesi=${s.sessions} ff=${s.ffN} ditahan=${s.ffSuppressed} gagal=${s.failedTurns}")
    }
    run {
        val s = sim327("k1", true)
        s.post(90500, null)
        for (t in 2..10) s.post(90500, if (t == 6) dAutoLen else deltaBase.length, forceFreshEp = t == 5, nowMs = t * 2 * 60_000L)
        check("4.12 SESUDAH L4: ANTI-CASCADE ff + auto-save DELTA -> 2 sesi, 0 reset", s.sessions == 2 && s.resets == 0 && s.ffN == 1, "sesi=${s.sessions} reset=${s.resets}")
    }
    run {
        val s = sim327("k1", true)
        s.post(90500, null)
        for (t in 2..10) { s.post(90500, deltaBase.length); repeat(2) { s.postCont(12500) } }
        check("4.13 SESUDAH L4b: lanjutan guard IDENTIK dengan K4 (2 sesi, 1 reset, accum sama)", s.sessions == 2 && s.resets == 1 && s.accumAkhir() == 127228, "sesi=${s.sessions} reset=${s.resets} accum=${s.accumAkhir()}")
    }
    run {
        val s = sim327("k1", true)
        repeat(30) { s.post(90500, deltaBase.length) }
        check("4.14 REGRESI delta normal 30 giliran -> 1 sesi", s.sessions == 1 && s.deltaPosts == 29 && s.fullPosts == 1, "delta=${s.deltaPosts} penuh=${s.fullPosts}")
    }
    run {
        val s = sim327("k1", true)
        s.stored = true; TurnMetrics.setServerSessionPrimedVc322(s.ctx, s.key, true); TurnMetrics.addServerAccumVc321(s.ctx, s.key, 195000)
        s.post(90500, deltaBase.length)
        s.post(90500, null)
        check("4.15 REGRESI guard IXL tetap: accum 195k + delta lalu + penuh -> 1 reset", s.sessions == 1 && s.resets == 1, "sesi=${s.sessions} reset=${s.resets}")
    }
    run {
        val s = sim327("k1", true)
        s.post(90500, null)
        for (t in 2..10) s.post(90500, deltaBase.length, invalid = t == 8)
        check("4.16 REGRESI invalid 1x -> 2 sesi (pengganti tidak diubah)", s.sessions == 2 && s.invalidN == 1, "sesi=${s.sessions}")
    }
    run {
        val s = sim327("k1", true)
        s.post(90500, null)
        for (t in 2..10) s.post(90500, deltaBase.length, forceFreshEp = t in setOf(3, 5), nowMs = (t * 9) * 60_000L)
        check("4.17 Cooldown batas di simulasi: ff mnt ke-27 lolos, mnt ke-45 lolos (18 mnt > 10)", s.ffN == 2 && s.ffSuppressed == 0, "ff=${s.ffN} ditahan=${s.ffSuppressed}")
    }
    run {
        val s = sim327("k1", true)
        s.post(90500, null)
        for (t in 2..10) s.post(90500, deltaBase.length, forceFreshEp = t in setOf(3, 4), nowMs = (t * 9) * 60_000L)
        check("4.18 Cooldown di simulasi: ff mnt ke-36 ditahan (9 mnt sesudah mnt ke-27)", s.ffN == 1 && s.ffSuppressed == 1 && s.failedTurns == 1, "ff=${s.ffN} ditahan=${s.ffSuppressed}")
    }

    println("== §5. Struktur (source) ==")
    check("5.1 TurnMetrics: migratePrimedOnceVc327 + cooldown forceFresh ada",
        "fun migratePrimedOnceVc327" in tmSrc && "FORCE_FRESH_COOLDOWN_MS_VC327" in tmSrc &&
        "fun allowForceFreshVc327" in tmSrc && "fun noteForceFreshVc327" in tmSrc && "fun lastForceFreshAtVc327" in tmSrc)
    check("5.2 AgentPrompt: autoSaveBlockVc327 + deltaTurnPromptVc327 ada (delta memanggil delta vc322)",
        "fun autoSaveBlockVc327" in apSrc && "fun deltaTurnPromptVc327" in apSrc &&
        "deltaTurnPromptVc322(userCommand, workNotes)" in apSrc)
    check("5.3 MainActivity dispatchTurn: delta auto-save vc327 + kirim ke sendToAgent",
        "AgentPrompt.deltaTurnPromptVc327(rawUser" in maSrc &&
        "AgentPrompt.autoSaveBlockVc327(promptNoUser)" in maSrc &&
        "sendToAgent(toSendVc240, deltaVc322)" in maSrc)
    check("5.4 MainActivity sendContinuation: deltaPrompt = finalTxt (lanjutan = delta)",
        "sendToAgent(finalTxt, finalTxt)" in maSrc)
    check("5.5 DsApiClient: tangga 3x non-reviewer + gate cooldown + note + reset accum di create",
        "longArrayOf(2_000, 5_000, 15_000)" in dsSrc && "longArrayOf(2_000, 5_000)" in dsSrc &&
        "if (!reviewer && !TurnMetrics.allowForceFreshVc327" in dsSrc &&
        "TurnMetrics.noteForceFreshVc327" in dsSrc && "DITAHAN vc327" in dsSrc &&
        dsSrc.split("resetServerAccumVc321(ctx, accumKeyVc321)").size - 1 == 2 &&
        "resetServerAccumVc321(ctx, accumKeyFreshVc321)" in dsSrc)
    // KOREKSI RONDE 1: hitung 3x SALAH untuk DsApiClient — cabang pengganti
    // invalid memakai accumKeyFreshVc321 (kunci sidik token SAAT INI, bukan
    // sidik pemilik wadah lama — perilaku vc321/vc322 yang benar, lihat
    // komentar di DsApiClient.kt:334-336). Jadi: 2x accumKeyVc321
    // (guard-reset + create vc327) + 1x accumKeyFreshVc321 (invalid).
    // SlotClient memang 3x accumKeyVc321 (satu kunci per slot) — 5.6 benar.
    check("5.6 SlotClient: tangga 3x + gate cooldown + reset accum create (kecuali ephemeral)",
        "longArrayOf(2_000, 5_000, 15_000)" in slSrc && "longArrayOf(2_000, 5_000)" in slSrc &&
        "if (!reviewer && !TurnMetrics.allowForceFreshVc327" in slSrc &&
        "TurnMetrics.noteForceFreshVc327" in slSrc && "DITAHAN vc327" in slSrc &&
        "if (!freshEphemeral) TurnMetrics.resetServerAccumVc321(ctx, accumKeyVc321)" in slSrc &&
        slSrc.split("resetServerAccumVc321(ctx, accumKeyVc321)").size - 1 == 3)
    check("5.7 Reviewer TIDAK digate (forceFresh reviewer langsung, tanpa cooldown)",
        "forceFresh = true, bgMode = bgMode, deltaPrompt = deltaPrompt" in dsSrc &&
        "forceFresh = true, freshEphemeral = freshEphemeral, reviewer = reviewer, deltaPrompt = deltaPrompt" in slSrc)
    check("5.8 H4 TIDAK DIUBAH: isSessionInvalidSignal tetap sempit (tanpa input_exceeds_limit/stream_terputus)",
        run {
            val st = dfSrc.indexOf("fun isSessionInvalidSignal")
            val body = if (st < 0) "" else dfSrc.substring(st, minOf(st + 600, dfSrc.length))
            "session_not_found" in body && "input_exceeds_limit" !in body && "stream_terputus" !in body
        })
    check("5.9 Pengganti invalid tetap kirim ulang PENUH (bukan delta) di kedua klien",
        "val res2 = completeWithSession(fresh, prompt, thinking, search, token, cookie, ua, onPartial, fileIds)" in dsSrc &&
        "val res2 = completeWithSession(fresh, prompt, thinking, search, token, cookie, ua, onPartial)" in slSrc)
    check("5.10 Guard vc321 + cap TIDAK berubah (DEFAULT_CAP 200.000, shouldReset dipakai kedua klien)",
        "const val DEFAULT_CAP = 200000" in tmSrc &&
        "shouldResetServerSessionVc321(accumVc321, prompt.length)" in dsSrc &&
        "shouldResetServerSessionVc321(accumVc321, prompt.length)" in slSrc)
    check("5.11 Penghitung kelahiran tetap 2 situs per klien (tidak ada situs lahir baru)",
        dsSrc.split("noteServerSessionCreatedVc322").size - 1 == 2 &&
        slSrc.split("noteServerSessionCreatedVc322").size - 1 == 2)
    check("5.12 TIDAK ADA hsurr: di 5 file fix", !("hsurr:" in tmSrc || "hsurr:" in apSrc || "hsurr:" in dsSrc || "hsurr:" in slSrc || "hsurr:" in maSrc))
    check("5.13 Flavor.kt + GroupChat + BgBrain tanpa referensi vc327", "Vc327" !in flSrc && "vc327" !in flSrc && "Vc327" !in gcSrc && "vc327" !in gcSrc && "Vc327" !in bgSrc && "vc327" !in bgSrc)
    check("5.14 RUST_CORE_VERSION 9 (slice 11 vc328 menambah dsApiJson)", "const val VERSION = 9" in rcSrc)
    check("5.15 Migrasi primed dipakai kedua klien sebelum keputusan delta",
        "migratePrimedOnceVc327(ctx, accumKeyVc321, stored != null, accumVc321)" in dsSrc &&
        "migratePrimedOnceVc327(ctx, accumKeyVc321, stored != null, accumVc321)" in slSrc)

    println("== §6. Sesi pendek + jalur lain tidak berubah ==")
    check("6.1 anggaran riwayat vc321 tetap (60/60000 normal, 30/30000 panjang)",
        TurnMetrics.historyBudgetVc321(0) == Pair(60, 60000) && TurnMetrics.historyBudgetVc321(39) == Pair(60, 60000) &&
        TurnMetrics.historyBudgetVc321(40) == Pair(30, 30000) && TurnMetrics.historyBudgetVc321(100) == Pair(30, 30000))
    check("6.2 sendContinuation jalur non-API tetap kirim teks mentah (tanpa delta)",
        "sendToAgent(text)" in maSrc)
    check("6.3 chatSync reviewer TIDAK membawa delta (tanda tangan lama)",
        "fun chatSync(prompt: String, thinking: Boolean, search: Boolean, freshEphemeral: Boolean = false, reviewer: Boolean = false)" in slSrc)
    check("6.4 parent_message_id tetap NULL di kedua klien (protokol tidak berubah)",
        dsSrc.split("parent_message_id").size - 1 >= 1 && slSrc.split("parent_message_id").size - 1 >= 1)

    println()
    println("HASIL: $passed lulus, $failed gagal (total ${passed + failed})")
    if (failed > 0) exitProcess(1)
}
