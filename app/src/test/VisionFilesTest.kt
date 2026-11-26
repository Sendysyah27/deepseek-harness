package com.senzwh.deepharness

// Test JVM murni (tanpa Android) untuk utilitas file vision (§4.2/§4.4, F4c).
// Dijalankan manual dengan kotlinc — JANGAN edit tools/run_tests.sh.
//
// Menjaga invarian: klasifikasi dari mime dulu, ekstensi bila mime kosong;
// pesan penolakan vision berteks eksak; penolakan HTTP 400 vision HANYA
// bila ada kata kunci image/vision/unsupported/multimodal; markUnsupported
// tidak pernah mengubah entri model lain.

private var failedVision = 0

private fun checkVision(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedVision++; println("  FAIL $name" + detailSuffix)
    }
}

private fun visionFile(name: String, mime: String) =
    VisionFile(byteArrayOf(1, 2, 3), name, mime)

fun main() {
    println("== VisionFilesTest ==")

    // Konstanta (§4.2)
    checkVision("MAX_IMAGES == 4", VisionFiles.MAX_IMAGES == 4)
    checkVision("MAX_IMAGE_BYTES_AFTER_COMPRESS == 1_000_000",
        VisionFiles.MAX_IMAGE_BYTES_AFTER_COMPRESS == 1_000_000)

    // classify — gambar
    checkVision("classify image/jpeg → IMAGE",
        VisionFiles.classify("image/jpeg", "foto.jpg") == VisionFileKind.IMAGE)
    checkVision("classify image/png → IMAGE",
        VisionFiles.classify("image/png", "foto.png") == VisionFileKind.IMAGE)
    checkVision("classify image/webp → IMAGE",
        VisionFiles.classify("image/webp", "foto.webp") == VisionFileKind.IMAGE)
    checkVision("classify image/gif → IMAGE",
        VisionFiles.classify("image/gif", "foto.gif") == VisionFileKind.IMAGE)

    // classify — dokumen teks (mime text/* atau ekstensi)
    checkVision("classify text/plain → TEXT_DOC",
        VisionFiles.classify("text/plain", "catatan") == VisionFileKind.TEXT_DOC)
    checkVision("classify mime kosong + .txt → TEXT_DOC",
        VisionFiles.classify("", "notes.txt") == VisionFileKind.TEXT_DOC)
    checkVision("classify mime kosong + .md → TEXT_DOC",
        VisionFiles.classify("", "notes.md") == VisionFileKind.TEXT_DOC)
    checkVision("classify application/json + .json → TEXT_DOC",
        VisionFiles.classify("application/json", "data.json") == VisionFileKind.TEXT_DOC)
    checkVision("classify mime kosong + .csv → TEXT_DOC",
        VisionFiles.classify("", "data.csv") == VisionFileKind.TEXT_DOC)
    checkVision("classify mime kosong + .log → TEXT_DOC",
        VisionFiles.classify("", "app.log") == VisionFileKind.TEXT_DOC)

    // classify — PDF dan DOCX
    checkVision("classify application/pdf → PDF",
        VisionFiles.classify("application/pdf", "laporan.pdf") == VisionFileKind.PDF)
    checkVision("classify mime kosong + .pdf → PDF",
        VisionFiles.classify("", "laporan.pdf") == VisionFileKind.PDF)
    checkVision("classify mime docx → DOCX",
        VisionFiles.classify(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "skripsi.docx"
        ) == VisionFileKind.DOCX)
    checkVision("classify mime kosong + .docx → DOCX",
        VisionFiles.classify("", "skripsi.docx") == VisionFileKind.DOCX)

    // classify — tidak didukung
    checkVision("classify video/mp4 → UNSUPPORTED",
        VisionFiles.classify("video/mp4", "klip.mp4") == VisionFileKind.UNSUPPORTED)
    checkVision("classify audio/mpeg → UNSUPPORTED",
        VisionFiles.classify("audio/mpeg", "lagu.mp3") == VisionFileKind.UNSUPPORTED)
    checkVision("classify application/zip → UNSUPPORTED",
        VisionFiles.classify("application/zip", "arsip.zip") == VisionFileKind.UNSUPPORTED)
    checkVision("classify application/octet-stream → UNSUPPORTED",
        VisionFiles.classify("application/octet-stream", "biner.bin") == VisionFileKind.UNSUPPORTED)

    // isImageMime
    checkVision("isImageMime image/jpeg → true", VisionFiles.isImageMime("image/jpeg"))
    checkVision("isImageMime image/png → true", VisionFiles.isImageMime("image/png"))
    checkVision("isImageMime text/plain → false", !VisionFiles.isImageMime("text/plain"))
    checkVision("isImageMime kosong → false", !VisionFiles.isImageMime(""))

    // Pesan eksak (§4.4)
    checkVision("modelUnsupportedMessage teks eksak",
        VisionFiles.modelUnsupportedMessage("gpt-4o-mini", "OpenRouter") ==
            "📷 Model 'gpt-4o-mini' di provider 'OpenRouter' tidak mendukung analisis gambar. " +
            "Pilih model lain yang mendukung vision di Setup > 73. Provider LLM, " +
            "atau kirim gambar tanpa pertanyaan analisis.")
    checkVision("serverRejectedMessage teks eksak",
        VisionFiles.serverRejectedMessage("OpenRouter", "gpt-4o-mini", "image not supported") ==
            "📷 Provider 'OpenRouter' menolak gambar untuk model 'gpt-4o-mini' " +
            "(image not supported). Model ditandai tidak mendukung vision — " +
            "pilih model lain di Setup > 73.")
    checkVision("unsupportedMessage teks eksak",
        VisionFiles.unsupportedMessage(visionFile("klip.mp4", "video/mp4")) ==
            "File klip.mp4 (video/mp4) belum didukung analisis di provider ini.")

    // tooLargeMessage — mengandung nama file + "tidak bisa dianalisis" + "JPG/PNG"
    val tooLarge = VisionFiles.tooLargeMessage(visionFile("foto.png", "image/png"))
    checkVision("tooLargeMessage menyebut nama file", tooLarge.contains("foto.png"), tooLarge)
    checkVision("tooLargeMessage menyebut tidak bisa dianalisis",
        tooLarge.contains("tidak bisa dianalisis"), tooLarge)
    checkVision("tooLargeMessage menyebut JPG/PNG", tooLarge.contains("JPG/PNG"), tooLarge)

    // isVisionRejection400 — positif
    checkVision("400 + image → true",
        VisionFiles.isVisionRejection400("HTTP 400: image not supported"))
    checkVision("400 + vision → true",
        VisionFiles.isVisionRejection400("HTTP 400: model does not support vision"))
    checkVision("400 + unsupported → true",
        VisionFiles.isVisionRejection400("HTTP 400: unsupported media type"))
    checkVision("400 + MULTIMODAL (case-insensitive) → true",
        VisionFiles.isVisionRejection400("HTTP 400: MULTIMODAL input rejected"))

    // isVisionRejection400 — negatif
    checkVision("400 tanpa kata kunci → false",
        !VisionFiles.isVisionRejection400("HTTP 400: bad request"))
    checkVision("429 → false",
        !VisionFiles.isVisionRejection400("HTTP 429: rate limit"))
    checkVision("500 → false",
        !VisionFiles.isVisionRejection400("HTTP 500: internal server error"))
    checkVision("null → false", !VisionFiles.isVisionRejection400(null))
    checkVision("kosong → false", !VisionFiles.isVisionRejection400(""))

    // markUnsupported
    val catalog = listOf(
        ModelCaps.CachedModel("gpt-4o", "supported"),
        ModelCaps.CachedModel("llama-3", "unknown")
    )
    val marked = VisionFiles.markUnsupported(catalog, "gpt-4o")
    checkVision("markUnsupported cocok eksak → unsupported",
        marked.first { it.id == "gpt-4o" }.vision == "unsupported")
    checkVision("markUnsupported entri lain tidak berubah",
        marked.first { it.id == "llama-3" }.vision == "unknown" && marked.size == 2)
    val markedLower = VisionFiles.markUnsupported(catalog, "GPT-4O")
    checkVision("markUnsupported cocok lowercase → unsupported",
        markedLower.first { it.id == "gpt-4o" }.vision == "unsupported")
    val markedNew = VisionFiles.markUnsupported(catalog, "model-baru-9")
    checkVision("markUnsupported id tak ada → ditambah sebagai entri baru",
        markedNew.size == 3 &&
            markedNew.last() == ModelCaps.CachedModel("model-baru-9", "unsupported"))
    val markedEmpty = VisionFiles.markUnsupported(emptyList(), "model-pertama")
    checkVision("markUnsupported list kosong → list 1 entri",
        markedEmpty == listOf(ModelCaps.CachedModel("model-pertama", "unsupported")))

    // truncateDocText
    checkVision("truncate ≤ maxChars → utuh",
        VisionFiles.truncateDocText("abc", 10) == "abc")
    val long = VisionFiles.truncateDocText("abcdefghij", 4)
    checkVision("truncate > maxChars → take + penanda sisa char",
        long.startsWith("abcd") && long.contains("sisa 6 char"), long)

    // stripDocxXml
    val docx = "<w:document><w:body><w:p><w:r><w:t>Halo</w:t></w:r></w:p>" +
        "<w:p><w:r><w:t>Dunia &amp; kawan</w:t></w:r></w:p></w:body></w:document>"
    val stripped = VisionFiles.stripDocxXml(docx)
    checkVision("stripDocxXml <w:p> jadi newline, tag hilang",
        stripped == "Halo\nDunia & kawan", stripped)
    checkVision("stripDocxXml unescape &lt;/&gt;/&amp; + trim",
        VisionFiles.stripDocxXml("<w:p>a &lt;b&gt; &amp; c</w:p>") == "a <b> & c")

    println(if (failedVision == 0) "SEMUA LULUS" else "GAGAL: $failedVision cek")
    if (failedVision != 0) kotlin.system.exitProcess(1)
}
