package com.senzwh.deepharness

// Test JVM murni — vc333: sistem verifikasi jawaban DIHAPUS.
//
// Perintah Sendy (27 Nov 11:08): "Hapus verifikasi ngga jelas. Buat
// harness yg proper." Gejala: "Tes skill list" -> jawaban agen dimangkas
// reviewer jadi "[KLAIM DIHAPUS — tidak didukung bukti]" berulang +
// bubble reviewer. Sesudah vc333 jawaban agen tampil apa adanya.
//
// APA YANG DITES DI SINI (jujur):
//  1. Refleksi atas AgentPrompt yang dikompilasi: 17 helper verifikasi
//     (§2 AUDIT-VERIFIKASI-VC333) TIDAK ADA lagi (metode maupun konstanta),
//     dan kelas VerifyVerdict TIDAK ADA lagi (file dihapus).
//  2. Helper yang DIPERTAHANKAN masih ada: deltaExtractPromptVc330
//     (ekstraksi latar), contentSnippet/editEvidenceSnippet,
//     stripRawToolMarkupForDisplay/stripSafetyTags.
//  3. Jalur tampil: jawaban "Tes skill list" (daftar skill utuh, format
//     persis hasil tool list_skills) melewati stripRawToolMarkupForDisplay
//     + stripSafetyTags TANPA berubah — tanpa "[KLAIM DIHAPUS]", tanpa
//     label klaim, tanpa bubble reviewer.
//  4. Sumber: tidak ada lagi pemicu ronde verifikasi di MainActivity /
//     BgBrain / GroupChat / TeamRun / OcCoord / AgentPrompt (string
//     terlarang), dan file VerifyVerdict.kt + ReviewerVc298.kt tidak ada.
//
// APA YANG TIDAK BISA DITES DI JVM (residu):
//  - handleAgentResponse itu sendiri: MainActivity (~18 rb baris,
//    Activity Android) tidak bisa dikompilasi/diinstansiasi di JVM.
//    Bahwa satu jawaban = selesai tanpa ronde verifikasi HANYA bisa
//    dibuktikan baca kode + tes di HP (cek asap §8 audit).
//  - LlmCallKind.REVIEWER + bucket Governor "reviewer" SENGAJA TETAP
//    HIDUP untuk verifikasi tugas room F2b (tombol manual, bukan filter
//    jawaban) — diuji ReviewerMigrationTest, bukan di sini.

private var failedVerificationRemoved = 0

private fun checkVR(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedVerificationRemoved++; println("  FAIL $name" + detailSuffix)
    }
}

// Jawaban "Tes skill list" — daftar skill utuh seperti yang dihasilkan
// tool list_skills (Tools.kt: "- #<id> <nama>: <deskripsi>"), dibungkus
// narasi model. Inilah jawaban yang dulu dimangkas reviewer.
private val SKILL_LIST_ANSWER = """
Berikut daftar skill yang aktif:
- #1 audit-hp: Audit keamanan HP Android via ADB
- #2 motion-klip: Potong klip motion graphics dari livestream
- #3 suka-tea-pos: Kasir SUKA TEA dengan sinkronisasi gudang
Total 3 skill aktif. Semua sudah dites dan siap dipakai.
""".trim()

fun main() {
    println("== VerificationRemovedTest ==")

    // 1. Refleksi: helper verifikasi TIDAK ADA di AgentPrompt.
    val apClass = Class.forName("com.senzwh.deepharness.AgentPrompt")
    val methodNames = apClass.declaredMethods.map { it.name }.toSet()
    val fieldNames = apClass.declaredFields.map { it.name }.toSet()
    val removedMethods = listOf(
        "splitClaims", "zeroCtxReviewPrompt", "parseZeroCtxReview",
        "reviewerRetryPrompt", "reviewerNoteText", "reviewerNotVerifiedNote",
        "evidenceMarkerPresent", "hasFactualClaim", "claimWarningForCorpus",
        "claimSupportedByMemory", "allClaimsSupportedByMemory",
        "filterUnsupportedByMemory", "applyDoublePass", "applyDoublePassKotlin",
        "unknownFooterVc288", "dropSystemBackedClaims", "deltaVerifyPromptVc330",
        "claimFingerprintVc303", "normalizeClaimVc303"
    )
    for (m in removedMethods) {
        checkVR("AgentPrompt.$m dihapus", m !in methodNames, "masih ada")
    }
    for (f in listOf("EVIDENCE_GATE_NUDGE", "REVIEWER_STRICT_RETRY_INSTRUCTION")) {
        checkVR("AgentPrompt.$f dihapus", f !in fieldNames, "masih ada")
    }
    var verdictGone = false
    try {
        Class.forName("com.senzwh.deepharness.VerifyVerdict")
    } catch (e: ClassNotFoundException) {
        verdictGone = true
    }
    checkVR("VerifyVerdict tidak ada (file dihapus)", verdictGone)

    // 2. Helper yang dipertahankan MASIH ADA.
    for (m in listOf("deltaExtractPromptVc330", "contentSnippet",
        "editEvidenceSnippet", "stripRawToolMarkupForDisplay", "stripSafetyTags")) {
        checkVR("AgentPrompt.$m dipertahankan", m in methodNames, "hilang")
    }

    // 3. Jalur tampil: jawaban skill list utuh, tanpa pemotongan.
    val shown1 = AgentPrompt.stripRawToolMarkupForDisplay(SKILL_LIST_ANSWER)
    checkVR("stripRawToolMarkupForDisplay: jawaban skill list tidak berubah",
        shown1 == SKILL_LIST_ANSWER, "berubah: $shown1")
    val shown2 = AgentPrompt.stripSafetyTags(SKILL_LIST_ANSWER)
    checkVR("stripSafetyTags: jawaban skill list tidak berubah",
        shown2 == SKILL_LIST_ANSWER, "berubah: $shown2")
    checkVR("jawaban tampil memuat daftar skill (- #1 audit-hp)",
        shown1.contains("- #1 audit-hp"))
    checkVR("jawaban tampil memuat daftar skill (- #2 motion-klip)",
        shown1.contains("- #2 motion-klip"))
    checkVR("jawaban tampil memuat daftar skill (- #3 suka-tea-pos)",
        shown1.contains("- #3 suka-tea-pos"))
    checkVR("jawaban tampil TIDAK mengandung [KLAIM DIHAPUS]",
        !shown1.contains("[KLAIM DIHAPUS"))
    checkVR("jawaban tampil TIDAK mengandung label Reviewer konteks-nol",
        !shown1.contains("Reviewer konteks-nol"))
    checkVR("jawaban tampil TIDAK mengandung 'belum terverifikasi'",
        !shown1.contains("belum terverifikasi"))
    checkVR("jawaban tampil TIDAK mengandung 'TIDAK DIDUKUNG'",
        !shown1.contains("TIDAK DIDUKUNG"))

    // 4. Sumber: string terlarang tidak ada di jalur chat manapun.
    val proj = System.getProperty("proj.dir", "")
    checkVR("proj.dir diteruskan run_tests.sh", proj.isNotBlank(), "kosong")
    if (proj.isNotBlank()) {
        val banned = listOf(
            "[KLAIM DIHAPUS", "Reviewer konteks-nol", "verifyTurn",
            "verifyCheck", "VerifyVerdict", "claimWarningVc228",
            "vc228HasFactualClaim", "vc228ClaimWarning", "vc228FullTrailText",
            "EVIDENCE_GATE_NUDGE", "zeroCtxReviewPrompt", "parseZeroCtxReview",
            "applyDoublePass", "splitClaims", "hasFactualClaim",
            "claimWarningForCorpus", "deltaVerifyPromptVc330",
            "reviewerNotVerifiedNote", "unknownFooterVc288", "holdRoomVc275",
            "gateRetryVc249", "recordClaimStopVc303", "claimFingerprintsBlockVc303",
            "dropSystemBackedClaims", "reviewerRetryPrompt", "evidenceMarkerPresent"
        )
        val files = listOf(
            "app/src/main/java/com/senzwh/deepharness/MainActivity.kt",
            "app/src/main/java/com/senzwh/deepharness/AgentPrompt.kt",
            "app/src/main/java/com/senzwh/deepharness/BgBrain.kt",
            "app/src/main/java/com/senzwh/deepharness/GroupChat.kt",
            "app/src/main/java/com/senzwh/deepharness/TeamRun.kt",
            "app/src/main/java/com/senzwh/deepharness/OcCoord.kt"
        )
        for (rel in files) {
            val text = try { java.io.File(proj, rel).readText() } catch (e: Exception) { "" }
            checkVR("sumber $rel terbaca", text.isNotBlank(), "tidak terbaca")
            val short = rel.substringAfterLast("/")
            for (b in banned) {
                checkVR("$short tanpa '$b'", !text.contains(b), "masih ada")
            }
        }
        checkVR("VerifyVerdict.kt tidak ada di source tree",
            !java.io.File(proj, "app/src/main/java/com/senzwh/deepharness/VerifyVerdict.kt").exists())
        checkVR("ReviewerVc298.kt tidak ada di source tree",
            !java.io.File(proj, "app/src/main/java/com/senzwh/deepharness/ReviewerVc298.kt").exists())
        // F2b (verifikasi tugas room, tombol manual) SENGAJA dipertahankan:
        // bucket reviewer + LlmCallKind.REVIEWER harus tetap dipakai di situ.
        val mainText = java.io.File(proj, "app/src/main/java/com/senzwh/deepharness/MainActivity.kt").readText()
        checkVR("F2b dipertahankan (setSessionTaskVerified masih ada)",
            mainText.contains("setSessionTaskVerified"))
        checkVR("F2b dipertahankan (LlmCallKind.REVIEWER masih dipakai F2b)",
            mainText.contains("LlmCallKind.REVIEWER"))
    }

    println(if (failedVerificationRemoved == 0) "SEMUA LULUS" else "GAGAL: $failedVerificationRemoved cek")
    if (failedVerificationRemoved != 0) kotlin.system.exitProcess(1)
}
