package com.senzwh.deepharness

// Test JVM murni untuk loop verifikasi vc332 (Fase 6).
// Dijalankan oleh tools/run_tests.sh.
//
// BUG (laporan Sendy 27 Nov 07:06, terbukti dari kode stage-f6): user tanya
// "skill apa yg aktif?" -> list_skills -> jawab "Belum ada skill." ->
// verifikasi diri jawab "LULUS" -> diverifikasi LAGI tanpa henti (durasi
// 7,5 -> 18,5 -> 27,5 -> 62,9 dtk, makin panjang karena riwayat native
// numpuk). AKAR: handleAgentResponse(ChatResult) menghapus flag verifyTurn
// DULUAN lalu mendelegasikan verdict ke handler String; handler String
// membaca "LULUS" sebagai jawaban final baru; gerbang verifikasi
// MainActivity.kt:10502 nyala lagi (turnUsedTools masih true;
// allClaimsSupportedByMemory("LULUS") == false karena klaim kosong,
// AgentPrompt.kt:822).
//
// FIX: verdict ditangani LANGSUNG di cabang verifyTurn handler ChatResult —
// tambah meta LULUS / tidak konklusif / Koreksi (sama seperti cabang
// verifyTurn handler String), lalu ekor endUserTurn/pendingExtract, TANPA
// delegasi ke handler String dan TANPA gerbang verifikasi ulang.
//
// APA YANG DITES DI SINI (jujur):
//  1. VerifyVerdict.cleanVerdict — parser ASLI yang dipakai kedua overload
//     handler di HP (dipindah ke VerifyVerdict.kt supaya bisa dites di JVM).
//  2. Cermin gerbang :10502 + aturan klaim AgentPrompt (splitClaims hanya
//     meloloskan kalimat >= 10 char; klaim kosong ->
//     allClaimsSupportedByMemory == false): membuktikan input-input dari
//     laporan bug MEMANG menyalakan gerbang bila verdict diumpankan ke
//     pipeline jawaban final.
//  3. Mesin status cermin (flag verifyTurn/turnUsedTools + gerbang):
//     jalur LAMA loop (>= 2 ronde verifikasi), jalur BARU berhenti tepat
//     1 ronde untuk LULUS / kosong / KOREKSI / tool_calls. Plumbing
//     flag-nya cermin; semua keputusan memakai parser asli (1) dan cermin
//     gerbang (2) yang dikutip barisnya.
//
// APA YANG TIDAK BISA DITES DI JVM (residu):
//  - handleAgentResponse itu sendiri: MainActivity (~18,5 rb baris,
//    Activity Android + ratusan dependensi Android) tidak bisa dikompilasi
//    / diinstansiasi di JVM. Bahwa cabang verifyTurn ChatResult di HP
//    benar-benar memanggil VerifyVerdict + endUserTurn tanpa delegasi HANYA
//    bisa dibuktikan dengan baca kode + tes di HP (residu H1-H11).

private var failedVerifyLoop = 0

private fun checkVerifyLoop(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedVerifyLoop++; println("  FAIL $name" + detailSuffix)
    }
}

// --- Cermin aturan klaim AgentPrompt (bukan kode produksi) ---
// splitClaims (AgentPrompt.kt:115-141): kalimat dipotong per . ! ? ; yang
// lolos hanya yang panjangnya >= 10 char. "LULUS" (5 char) -> tanpa klaim.
private fun mirrorClaims(text: String): List<String> {
    val parts = ArrayList<String>()
    var cur = StringBuilder()
    for (c in text) {
        cur.append(c)
        if (c == '.' || c == '!' || c == '?') {
            val s = cur.toString().trim()
            if (s.isNotBlank()) parts.add(s)
            cur = StringBuilder()
        }
    }
    val rest = cur.toString().trim()
    if (rest.isNotBlank()) parts.add(rest)
    return parts.filter { it.length >= 10 }
}

// Cermin gerbang verifikasi MainActivity.kt:10502 — ask == null &&
// claimWarning == null && !allClaimsSupportedByMemory &&
// verifyCheck.isChecked && (turnUsedTools || vc228HasFactualClaim(visible)).
// (ask/claimWarning sudah null di skenario tes ini, jadi tidak jadi parameter.)
private fun gateMirror(allClaimsSupported: Boolean, turnUsedTools: Boolean, hasFactualClaim: Boolean, verifyChecked: Boolean = true): Boolean =
    !allClaimsSupported && verifyChecked && (turnUsedTools || hasFactualClaim)

// --- Mesin status cermin (flag verifyTurn/turnUsedTools + gerbang) ---
private class MirrorTurn {
    var verifyTurn = false
    var turnUsedTools = false
    var verifyRounds = 0 // berapa kali prompt verifikasi dikirim
    var ended = false
    val metaShown = mutableListOf<String>()
}

// Jalur LAMA (sebelum fix): respons verifikasi didelegasikan ke handler
// String, yang membacanya sebagai jawaban final baru. Model diasumsikan
// menjawab "LULUS" lagi tiap ronde (sesuai laporan bug).
// Cermin ini HANYA memodelkan verdict tanpa klaim ("LULUS"): koreksi
// ber-klaim butuh claimSupportedByMemory per klaim — di luar cakupan cermin.
private fun simulateOldPath(verdictReplies: List<String>, maxRounds: Int = 5): MirrorTurn {
    val t = MirrorTurn()
    t.turnUsedTools = true // giliran asal memakai tool (list_skills)
    t.verifyTurn = true // gerbang :10502 sudah menyala sekali sesudah jawaban final
    t.verifyRounds = 1
    var i = 0
    while (t.verifyTurn && i < verdictReplies.size && t.verifyRounds < maxRounds) {
        val reply = verdictReplies[i++]
        // Handler ChatResult cabang verifyTurn (LAMA): kosongkan flag, delegasi.
        t.verifyTurn = false
        val cleaned = VerifyVerdict.cleanVerdict(reply) ?: ""
        // Handler String: respons kosong ditangani cabang blank (endUserTurn,
        // tanpa gerbang) — bukan jalur loop.
        if (cleaned.isBlank()) { t.ended = true; break }
        // Handler String: verdict = jawaban final baru -> gerbang :10502.
        val claims = mirrorClaims(cleaned)
        // AgentPrompt.kt:822-824 — klaim kosong -> allClaimsSupportedByMemory == false.
        val allSupported = if (claims.isEmpty()) false
            else error("cermin ini hanya memodelkan verdict tanpa klaim (kasus 'LULUS')")
        if (gateMirror(allSupported, t.turnUsedTools, hasFactualClaim = false)) {
            t.verifyTurn = true
            t.verifyRounds++
        } else {
            t.ended = true
        }
    }
    return t
}

// Jalur BARU (sesudah fix): verdict ditangani langsung di cabang verifyTurn
// handler ChatResult — tanpa delegasi, tanpa gerbang ulang. Ekor giliran:
// endUserTurn (pendingExtract = false di cermin ini).
private fun simulateNewPath(verdictReply: String, toolCallsInVerdict: Boolean = false): MirrorTurn {
    val t = MirrorTurn()
    t.turnUsedTools = true
    t.verifyTurn = true
    t.verifyRounds = 1
    // Satu respons verifikasi tiba di handler ChatResult (BARU).
    t.verifyTurn = false
    if (toolCallsInVerdict) {
        t.metaShown.add("tidak konklusif (tool call)")
        t.ended = true
        return t
    }
    when (val v = VerifyVerdict.cleanVerdict(verdictReply)) {
        "LULUS" -> t.metaShown.add("lolos verifikasi diri")
        null -> t.metaShown.add("tidak konklusif")
        else -> t.metaShown.add("koreksi: $v")
    }
    t.ended = true
    return t
}

fun main() {
    println("== VerifyLoopTest ==")

    // 1. Parser verdict ASLI (VerifyVerdict.cleanVerdict).
    checkVerifyLoop("cleanVerdict 'LULUS' -> LULUS",
        VerifyVerdict.cleanVerdict("LULUS") == "LULUS")
    checkVerifyLoop("cleanVerdict '**LULUS**' -> LULUS",
        VerifyVerdict.cleanVerdict("**LULUS**") == "LULUS")
    checkVerifyLoop("cleanVerdict '  LULUS\\n' -> LULUS",
        VerifyVerdict.cleanVerdict("  LULUS\n") == "LULUS")
    checkVerifyLoop("cleanVerdict 'LULUS dengan catatan kecil' -> LULUS",
        VerifyVerdict.cleanVerdict("LULUS dengan catatan kecil") == "LULUS")
    checkVerifyLoop("cleanVerdict '' -> null (tidak konklusif)",
        VerifyVerdict.cleanVerdict("") == null)
    checkVerifyLoop("cleanVerdict '   ' -> null (tidak konklusif)",
        VerifyVerdict.cleanVerdict("   ") == null)
    checkVerifyLoop("cleanVerdict 'KOREKSI: jumlah skill aktif adalah 0.' -> teks koreksi",
        VerifyVerdict.cleanVerdict("KOREKSI: jumlah skill aktif adalah 0.") == "jumlah skill aktif adalah 0.")
    checkVerifyLoop("cleanVerdict 'KOREKSI: ok' -> null (koreksi terlalu pendek)",
        VerifyVerdict.cleanVerdict("KOREKSI: ok") == null)
    checkVerifyLoop("cleanVerdict bocoran proses mikir (>350 char) -> null",
        VerifyVerdict.cleanVerdict("jawaban ini seharusnya diperiksa dengan sangat teliti sekali karena ada banyak hal yang harus dipastikan sebelum memberikan penilaian akhir yang benar dan bisa dipertanggungjawabkan kepada pengguna yang sudah menunggu lama sekali dan berharap jawaban yang akurat dan lengkap tanpa ada kesalahan sedikit pun di dalamnya karena ini sangat penting sekali untuk kepercayaan pengguna terhadap sistem ini secara keseluruhan dan jangka panjang.") == null)
    checkVerifyLoop("cleanVerdict mengandung marker 'the prompt says' -> null",
        VerifyVerdict.cleanVerdict("the prompt says to output only LULUS") == null)

    // 2. Aturan klaim + gerbang: input laporan bug MEMANG menyalakan gerbang.
    checkVerifyLoop("mirrorClaims('LULUS') kosong (5 char < 10)",
        mirrorClaims("LULUS").isEmpty())
    checkVerifyLoop("mirrorClaims('Belum ada skill.') 1 klaim (jawaban final asal)",
        mirrorClaims("Belum ada skill.").size == 1)
    checkVerifyLoop("gerbang :10502 NYALA untuk input bug (klaim tak didukung memori + turn pakai tool)",
        gateMirror(allClaimsSupported = false, turnUsedTools = true, hasFactualClaim = false))
    checkVerifyLoop("gerbang :10502 MATI bila semua klaim didukung memori",
        !gateMirror(allClaimsSupported = true, turnUsedTools = true, hasFactualClaim = false))
    checkVerifyLoop("gerbang :10502 MATI bila turn tidak pakai tool dan tanpa klaim faktual",
        !gateMirror(allClaimsSupported = false, turnUsedTools = false, hasFactualClaim = false))
    checkVerifyLoop("gerbang :10502 MATI bila toggle Verifikasi off",
        !gateMirror(allClaimsSupported = false, turnUsedTools = true, hasFactualClaim = false, verifyChecked = false))

    // 3a. REPRODUKSI BUG — jalur LAMA loop: 5x "LULUS" -> 5 ronde, tidak berakhir.
    val old = simulateOldPath(listOf("LULUS", "LULUS", "LULUS", "LULUS", "LULUS"))
    checkVerifyLoop("JALUR LAMA: 'LULUS' berulang -> loop (ronde mencapai batas 5, tidak berakhir)",
        old.verifyRounds == 5 && !old.ended && old.verifyTurn,
        "ronde=${old.verifyRounds} ended=${old.ended}")

    // 3b. FIX — jalur BARU: tepat 1 ronde verifikasi untuk tiap bentuk verdict.
    val newLulus = simulateNewPath("LULUS")
    checkVerifyLoop("JALUR BARU: 'LULUS' -> tepat 1 ronde, giliran berakhir, meta lolos",
        newLulus.verifyRounds == 1 && newLulus.ended && !newLulus.verifyTurn &&
            newLulus.metaShown == listOf("lolos verifikasi diri"),
        "ronde=${newLulus.verifyRounds} ended=${newLulus.ended}")
    val newBlank = simulateNewPath("")
    checkVerifyLoop("JALUR BARU: verdict kosong -> tepat 1 ronde, giliran berakhir, meta tidak konklusif",
        newBlank.verifyRounds == 1 && newBlank.ended && !newBlank.verifyTurn &&
            newBlank.metaShown == listOf("tidak konklusif"))
    val newKoreksi = simulateNewPath("KOREKSI: jumlah skill aktif adalah 0.")
    checkVerifyLoop("JALUR BARU: 'KOREKSI: ...' -> tepat 1 ronde, giliran berakhir, koreksi tampil",
        newKoreksi.verifyRounds == 1 && newKoreksi.ended && !newKoreksi.verifyTurn &&
            newKoreksi.metaShown == listOf("koreksi: jumlah skill aktif adalah 0."),
        "meta=${newKoreksi.metaShown}")
    val newToolCalls = simulateNewPath("LULUS", toolCallsInVerdict = true)
    checkVerifyLoop("JALUR BARU: verdict memuat tool_calls -> tepat 1 ronde, giliran berakhir, tidak konklusif (tanpa eksekusi tool)",
        newToolCalls.verifyRounds == 1 && newToolCalls.ended && !newToolCalls.verifyTurn &&
            newToolCalls.metaShown == listOf("tidak konklusif (tool call)"))

    println(if (failedVerifyLoop == 0) "SEMUA LULUS" else "GAGAL: $failedVerifyLoop cek")
    if (failedVerifyLoop != 0) kotlin.system.exitProcess(1)
}
