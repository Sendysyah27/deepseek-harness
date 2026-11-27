package com.senzwh.deepharness

import java.io.ByteArrayInputStream
import java.io.File

// Test JVM murni — vc334 Ronde 4: VISION + JALUR
// (V-1, V-2, V-3, V-4, S-1, S-3, R-1, G-2, G-4; G-5 SENGAJA dilewati —
// lihat BUILD-VC334.md § Ronde 4).
// Dijalankan oleh tools/run_tests.sh.
//
// APA YANG DITES DI SINI (jujur):
//  1. MURNI (Vc334R4Pure): R-1 (regex STATUS — "tidak TERVERIFIKASI"
//     bukan TERVERIFIKASI), V-1 (klasifikasi gagal provider-level vs
//     per-file), V-2 (target render PDF ≤1280 px), V-4 (hitung calon
//     gambar sebelum render), S-1 (baca ber-cap — file > cap ditolak
//     TANPA dibaca utuh), G-2 (prompt delta lane — tugas verbatim +
//     ekor transkrip, tanpa system prompt / blok memori).
//  2. SUMBER (grep): wiring di MainActivity / LlmEngineChat / TeamRun /
//     GroupChat yang tidak bisa jalan di JVM.
//  3. REPRODUKSI: sebelum fix, tes ini GAGAL — Vc334R4Pure belum ada
//     (compile gagal) + wiring lama masih buta / contains() / render
//     resolusi penuh / readBytes() dulu.
//
// APA YANG TIDAK DITES DI SINI:
//  - Perilaku runtime di HP (MainActivity/TeamRun/GroupChat tidak bisa
//    jalan di JVM): render PDF nyata di HP, fallback per-berkas di HP,
//    lane tim nyata — butuh tes HP oleh koordinator.

private var failedR4 = 0

private fun checkR4(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedR4++; println("  FAIL $name" + detailSuffix)
    }
}

private fun srcR4(name: String): String {
    val proj = System.getProperty("proj.dir") ?: "."
    return File(proj, "app/src/main/java/com/senzwh/deepharness/$name").readText()
}

private fun regionR4(text: String, startMarker: String, endMarker: String): String {
    val s = text.indexOf(startMarker)
    if (s < 0) return ""
    val e = text.indexOf(endMarker, s + startMarker.length)
    return if (e < 0) text.substring(s) else text.substring(s, e)
}

private fun countR4(text: String, needle: String): Int {
    var n = 0
    var i = text.indexOf(needle)
    while (i >= 0) { n++; i = text.indexOf(needle, i + needle.length) }
    return n
}

fun main() {
    println("== VisionPathsVc334R4Test ==")

    // ---- R-1 murni: parsing STATUS F2b pakai regex, bukan contains().
    checkR4("R-1: \"STATUS: TERVERIFIKASI\" -> TERVERIFIKASI",
        Vc334R4Pure.parseReviewStatus("STATUS: TERVERIFIKASI\nBUKTI: tugas selesai.") == "TERVERIFIKASI")
    checkR4("R-1: \"tidak TERVERIFIKASI\" BUKAN TERVERIFIKASI",
        Vc334R4Pure.parseReviewStatus("STATUS: SEBAGIAN\nBUKTI: tugas belum TERVERIFIKASI sepenuhnya.") == "SEBAGIAN")
    checkR4("R-1: \"STATUS: SEBAGIAN\" -> SEBAGIAN",
        Vc334R4Pure.parseReviewStatus("STATUS: SEBAGIAN\nBUKTI: separuh selesai.") == "SEBAGIAN")
    checkR4("R-1: \"STATUS: GAGAL\" -> GAGAL",
        Vc334R4Pure.parseReviewStatus("STATUS: GAGAL\nBUKTI: tidak bisa diverifikasi.") == "GAGAL")
    checkR4("R-1: \"tidak GAGAL\" BUKAN GAGAL",
        Vc334R4Pure.parseReviewStatus("STATUS: SEBAGIAN\nBUKTI: tugas tidak GAGAL, hanya belum selesai.") == "SEBAGIAN")
    checkR4("R-1: tanpa prefix STATUS -> SEBAGIAN (fail-closed)",
        Vc334R4Pure.parseReviewStatus("Tugas ini TERVERIFIKASI menurut saya.") == "SEBAGIAN")
    checkR4("R-1: jawaban kosong -> SEBAGIAN",
        Vc334R4Pure.parseReviewStatus("") == "SEBAGIAN")
    checkR4("R-1: \"STATUS:TERVERIFIKASI\" tanpa spasi -> TERVERIFIKASI",
        Vc334R4Pure.parseReviewStatus("STATUS:TERVERIFIKASI\nBUKTI: ok.") == "TERVERIFIKASI")

    // ---- V-1 murni: bedakan gagal provider-level vs per-file.
    checkR4("V-1: VisionUnsupportedException = provider-level",
        Vc334R4Pure.isProviderLevelFailure(VisionUnsupportedException("model tidak mendukung vision")))
    checkR4("V-1: NO_PROVIDER = provider-level",
        Vc334R4Pure.isProviderLevelFailure(IllegalStateException(LlmEngineChat.NO_PROVIDER_MESSAGE)))
    checkR4("V-1: provider belum siap = provider-level",
        Vc334R4Pure.isProviderLevelFailure(IllegalStateException(LlmEngineChat.providerNotReadyMessage("openai-1"))))
    checkR4("V-1: HTTP 401 auth = provider-level",
        Vc334R4Pure.isProviderLevelFailure(RuntimeException("HTTP 401: Unauthorized — API key salah")))
    checkR4("V-1: HTTP 429 kuota = provider-level",
        Vc334R4Pure.isProviderLevelFailure(RuntimeException("HTTP 429: kuota habis")))
    checkR4("V-1: HTTP 400 penolakan vision = provider-level",
        Vc334R4Pure.isProviderLevelFailure(RuntimeException("HTTP 400: image unsupported for this model")))
    checkR4("V-1: UNREACHABLE/timeout = provider-level",
        Vc334R4Pure.isProviderLevelFailure(RuntimeException("Provider tidak dapat dijangkau (timeout 120000 ms)")))
    checkR4("V-1: gagal baca file = PER-FILE (boleh fallback)",
        !Vc334R4Pure.isProviderLevelFailure(Exception("File tidak terbaca.")))
    checkR4("V-1: PDF gagal render = PER-FILE (boleh fallback)",
        !Vc334R4Pure.isProviderLevelFailure(RuntimeException("PDF 'x.pdf' gagal dirender (halaman 1): rusak")))
    checkR4("V-1: gambar terlalu besar = PER-FILE (boleh fallback)",
        !Vc334R4Pure.isProviderLevelFailure(IllegalArgumentException("foto.jpg tidak bisa dianalisis: terlalu besar.")))
    checkR4("V-1: format tak didukung = PER-FILE (boleh fallback)",
        !Vc334R4Pure.isProviderLevelFailure(IllegalArgumentException("File a.bmp (image/bmp) belum didukung analisis di provider ini.")))
    checkR4("V-1: null = bukan provider-level",
        !Vc334R4Pure.isProviderLevelFailure(null))
    checkR4("V-1: penyebab di cause-chain ikut terdeteksi",
        Vc334R4Pure.isProviderLevelFailure(RuntimeException("askWithFiles gagal", RuntimeException("HTTP 429: rate limit"))))

    // ---- V-2 murni: target render PDF ≤1280 px sisi panjang.
    checkR4("V-2: A4 300dpi 2480x3508 -> sisi panjang 1280",
        Vc334R4Pure.pdfTargetSize(2480, 3508) == Pair(904, 1280),
        "dapat=${Vc334R4Pure.pdfTargetSize(2480, 3508)}")
    checkR4("V-2: A4 300dpi TIDAK pernah >1280 px",
        Vc334R4Pure.pdfTargetSize(2480, 3508).let { maxOf(it.first, it.second) <= 1280 })
    checkR4("V-2: halaman kecil 800x600 tidak di-upscale",
        Vc334R4Pure.pdfTargetSize(800, 600) == Pair(800, 600))
    checkR4("V-2: tepat 1280 tidak berubah",
        Vc334R4Pure.pdfTargetSize(1280, 900) == Pair(1280, 900))
    checkR4("V-2: memori bitmap target << 35 MB (A4 300dpi)",
        Vc334R4Pure.pdfTargetSize(2480, 3508).let { it.first * it.second * 4L } < 10_000_000L,
        "byte=${Vc334R4Pure.pdfTargetSize(2480, 3508).let { it.first * it.second * 4L }} vs asli ~35 MB")

    // ---- V-4 murni: hitung calon gambar SEBELUM render.
    val fivePdfs = (1..5).map { VisionFile(ByteArray(10), "doc$it.pdf", "application/pdf") }
    val fourMixed = listOf(
        VisionFile(ByteArray(10), "a.jpg", "image/jpeg"),
        VisionFile(ByteArray(10), "b.pdf", "application/pdf"),
        VisionFile(ByteArray(10), "c.txt", "text/plain"),
        VisionFile(ByteArray(10), "d.png", "image/png"),
        VisionFile(ByteArray(10), "e.pdf", "application/pdf")
    )
    checkR4("V-4: 5 PDF = 5 calon gambar (>4, tolak sebelum render)",
        Vc334R4Pure.prospectiveImageCount(fivePdfs) == 5)
    checkR4("V-4: campuran 2 gambar + 2 PDF + 1 teks = 4 calon gambar",
        Vc334R4Pure.prospectiveImageCount(fourMixed) == 4)
    checkR4("V-4: hanya teks = 0 calon gambar",
        Vc334R4Pure.prospectiveImageCount(listOf(VisionFile(ByteArray(10), "a.txt", "text/plain"))) == 0)

    // ---- S-1 murni: baca ber-cap — tolak SEBELUM dibaca utuh.
    run {
        // Mock: InputStream yang mencatat berapa byte yang DIBACA.
        // File "besar" 100 KB dengan cap 10 KB: baca harus berhenti
        // di ~cap, bukan menelan 100 KB utuh.
        var bytesRead = 0L
        val big = ByteArray(100_000) { 7 }
        val counting = object : java.io.InputStream() {
            private val inner = ByteArrayInputStream(big)
            override fun read(): Int {
                val r = inner.read()
                if (r >= 0) bytesRead++
                return r
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val r = inner.read(b, off, len)
                if (r > 0) bytesRead += r
                return r
            }
        }
        var threw = false
        try {
            Vc334R4Pure.readCappedBytes(counting, 10_000L)
        } catch (e: IllegalStateException) {
            threw = true
        }
        checkR4("S-1: file > cap DITOLAK (throw)",
            threw, "tidak throw — file besar lolos dibaca utuh")
        checkR4("S-1: file > cap TIDAK dibaca utuh (baca berhenti di sekitar cap)",
            bytesRead <= 10_000L + 8192L, "bytesRead=$bytesRead dari 100000")
        val small = Vc334R4Pure.readCappedBytes(ByteArrayInputStream(ByteArray(5000) { 1 }), 10_000L)
        checkR4("S-1: file <= cap dibaca utuh",
            small.size == 5000)
    }

    // ---- G-2 murni: prompt delta lane (tugas + ekor transkrip saja).
    run {
        val delta = Vc334R4Pure.teamLaneDeltaPrompt(
            "Mandor", "Kamu berperan sebagai Mandor.",
            "Beli kopi 1 kg", "[HASIL TOOL] list_files: kopi.pdf\nAsisten: sudah dicek"
        )
        checkR4("G-2 delta: membawa sub-tugas verbatim",
            delta.contains("Beli kopi 1 kg"))
        checkR4("G-2 delta: membawa ekor transkrip (hasil tool terakhir)",
            delta.contains("kopi.pdf"))
        checkR4("G-2 delta: TIDAK membawa blok memori (dihitung sekali di awal lane)",
            !delta.contains("SKILL AKTIF") && !delta.contains("MEMORI PERSISTEN") && !delta.contains("FAKTA PIN"))
        checkR4("G-2 delta: jauh lebih pendek dari prompt penuh (tanpa system prompt)",
            delta.length < 2000, "len=${delta.length}")
    }

    // ================= SUMBER (wiring) =================
    val main = srcR4("MainActivity.kt")
    val engine = srcR4("LlmEngineChat.kt")
    val team = srcR4("TeamRun.kt")
    val group = srcR4("GroupChat.kt")

    // ---- V-1 wiring: fallback per-file hanya untuk gagal per-file.
    val sendRegion = regionR4(main, "private fun sendPendingAttachesVc212", "private fun fallbackDocVc212")
    checkR4("V-1 wiring: sendPendingAttaches membedakan penyebab gagal",
        sendRegion.contains("isProviderLevelFailure"),
        "catch masih buta — semua gagal jatuh ke fallback per-berkas")
    checkR4("V-1 wiring: gagal provider-level = SATU bubble, tanpa retry per-berkas",
        sendRegion.contains("tidak dicoba ulang per berkas"),
        "tidak ada pesan error tunggal provider-level")

    // ---- V-2 wiring: render langsung ≤1280 + satu jalur render.
    checkR4("V-2 wiring: renderPdfFirstPage memakai pdfTargetSize",
        engine.contains("pdfTargetSize("),
        "render masih createBitmap(page.width, page.height)")
    checkR4("V-2 wiring: tidak ada lagi createBitmap ukuran halaman asli di LlmEngineChat",
        !regionR4(engine, "private fun renderPdfFirstPage", "private fun isJpegBytes")
            .contains("page.width, page.height"),
        "bitmap resolusi asli masih dibuat")
    checkR4("V-2 wiring: fallbackDocVc212 memakai jalur render bersama",
        regionR4(main, "private fun fallbackDocVc212", "private fun analyzeFramesCombinedVc212")
            .contains("renderPdfFirstPageToFile"),
        "fallback masih render PDF sendiri (duplikasi)")
    checkR4("V-2 wiring: fallbackDocVc212 tidak lagi createBitmap halaman penuh",
        !regionR4(main, "private fun fallbackDocVc212", "private fun analyzeFramesCombinedVc212")
            .contains("Bitmap.createBitmap(page.width"),
        "duplikasi render resolusi penuh masih ada di fallback")

    // ---- V-3 wiring: b64 tak terpakai dihapus.
    checkR4("V-3 wiring: baris b64 di analyzePhotoAndContinue sudah dihapus",
        countR4(main, "val b64 = ") == 0,
        "b64 masih dihitung tapi tak dipakai")

    // ---- V-4 wiring: cek maks gambar SEBELUM render di askWithFiles.
    val askRegion = regionR4(engine, "fun askWithFiles(", "private fun extractDocxText")
    checkR4("V-4 wiring: askWithFiles menghitung calon gambar dulu",
        askRegion.contains("prospectiveImageCount"),
        "cek maks gambar masih sesudah render PDF")
    checkR4("V-4 wiring: cek calon gambar muncul SEBELUM renderPdfFirstPage",
        askRegion.indexOf("prospectiveImageCount") in 0 until maxOf(0, askRegion.indexOf("renderPdfFirstPage")),
        "urutan masih: render dulu, tolak belakangan")

    // ---- S-1 wiring: cek ukuran SEBELUM readBytes di dua tempat.
    checkR4("S-1 wiring: sendPendingAttaches memakai baca ber-cap",
        sendRegion.contains("readAttachBytesCappedVc334") || sendRegion.contains("readCappedBytes"),
        "masih readBytes() utuh sebelum cek 12 MB")
    checkR4("S-1 wiring: handleAttachment memakai baca ber-cap",
        regionR4(main, "private fun handleAttachment", "private val H_SKILL")
            .contains("readAttachBytesCappedVc334") ||
            regionR4(main, "private fun handleAttachment", "private val H_SKILL")
                .contains("readCappedBytes"),
        "handleAttachment masih readBytes() utuh sebelum cek 12 MB")
    checkR4("S-1 wiring: tidak ada lagi pola readBytes-lalu-cek-12MB di dua tempat itu",
        countR4(sendRegion, "?.readBytes()") == 0 &&
            countR4(regionR4(main, "private fun handleAttachment", "private val H_SKILL"), "openInputStream(uri)?.readBytes()") == 0,
        "pola baca-utuh-dulu masih ada")

    // ---- S-3 wiring: fallback PDF recycle + finally (via jalur bersama).
    checkR4("S-3 wiring: fallbackDocVc212 tidak lagi kelola PdfRenderer sendiri",
        !regionR4(main, "private fun fallbackDocVc212", "private fun analyzeFramesCombinedVc212")
            .contains("PdfRenderer(pfd)"),
        "fallback masih buka PdfRenderer sendiri tanpa finally/recycle")

    // ---- R-1 wiring: parsing STATUS pakai helper regex, bukan contains().
    val verifyRegion = regionR4(main, "promptVerifyVcF2b", "onError = { err -> runOnUiThread {")
    checkR4("R-1 wiring: verifikasi F2b memakai parseReviewStatus",
        verifyRegion.contains("parseReviewStatus"),
        "masih answer.contains(\"TERVERIFIKASI\")")
    checkR4("R-1 wiring: tidak ada lagi contains(TERVERIFIKASI) di jalur F2b",
        !verifyRegion.contains("contains(\"TERVERIFIKASI\")"),
        "false-positive \"belum TERVERIFIKASI\" masih bisa terjadi")

    // ---- G-2 wiring: runLane delta + memori sekali di awal lane.
    checkR4("G-2 wiring: runLane memakai prompt delta lanjutan",
        team.contains("teamLaneDeltaPrompt") || team.contains("continuationPromptVc334"),
        "runLane masih rebuild prompt penuh tiap langkah")
    checkR4("G-2 wiring: memoryBlockForTask(lane.task) HANYA sekali per lane",
        countR4(team, "memoryBlockForTask(app, lane.task)") == 1,
        "memori masih di-query tiap langkah (count=${countR4(team, "memoryBlockForTask(app, lane.task)")})")
    checkR4("G-2 wiring: rebuildPrompt penuh sudah tidak dipakai",
        !team.contains("rebuildPrompt("),
        "rebuildPrompt (system prompt + memori + transkrip penuh) masih dipanggil tiap langkah")

    // ---- G-4 wiring: buildPrompt grup — blok bersama sekali per ronde.
    checkR4("G-4 wiring: startRound menghitung blok bersama sekali per ronde",
        group.contains("sharedBlockVc334") || group.contains("GroupSharedVc334"),
        "buildPrompt masih dihitung ulang per anggota termasuk query MemoryDb yang sama")
    checkR4("G-4 wiring: buildPrompt menerima blok bersama (bukan hitung sendiri tiap anggota)",
        group.contains("shared"),
        "tidak ada parameter blok bersama di buildPrompt")

    // ---- G-5: SENGAJA dilewati (lihat BUILD-VC334.md § Ronde 4) —
    // healSolo/healMulti TIDAK diubah; tes ini menegaskan tidak ada
    // delta setengah jalan di sana (retry tetap prompt penuh).
    checkR4("G-5 wiring: healSolo/healMulti TIDAK diubah (dilewati — alasan di BUILD-VC334.md)",
        main.contains("fun healSolo") && main.contains("fun healMulti"),
        "fungsi heal hilang/berubah tanpa keputusan")

    println(if (failedR4 == 0) "SEMUA LULUS" else "GAGAL: $failedR4 cek")
    if (failedR4 != 0) kotlin.system.exitProcess(1)
}
