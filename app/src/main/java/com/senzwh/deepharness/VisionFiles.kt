package com.senzwh.deepharness

/**
 * §4.2/§4.4 (Fase 4a) — Helper murni file vision.
 *
 * JVM-MURNI (tanpa android.*, tanpa Context) supaya bisa diuji di
 * run_tests.sh. Berisi klasifikasi file, pesan-pesan eksak §4.4,
 * deteksi penolakan vision 400, penanda model unsupported di cache,
 * serta utilitas teks dokumen (truncate + strip XML DOCX).
 *
 * Semua pemrosesan berat (decode Bitmap, render PDF, unzip DOCX)
 * tinggal di LlmEngineChat.askWithFiles (Android) — file ini hanya
 * keputusan + teks.
 */
data class VisionFile(val bytes: ByteArray, val fileName: String, val mime: String)

enum class VisionFileKind { IMAGE, TEXT_DOC, PDF, DOCX, UNSUPPORTED }

class VisionUnsupportedException(message: String) : Exception(message)

object VisionFiles {

    /** Maks gambar per panggilan (§4.2 butir 1). */
    const val MAX_IMAGES = 4

    /** Maks ukuran satu gambar sesudah kompres (§4.2 butir 1). */
    const val MAX_IMAGE_BYTES_AFTER_COMPRESS = 1_000_000

    private val IMAGE_MIMES = setOf(
        "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif"
    )
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val TEXT_EXTS = setOf("txt", "md", "csv", "json", "log", "xml", "html")

    /** True bila mime adalah salah satu format gambar yang didukung. */
    fun isImageMime(mime: String): Boolean = cleanMime(mime) in IMAGE_MIMES

    /** Normalisasi mime: lowercase, buang parameter ("; charset=..."). */
    private fun cleanMime(mime: String): String =
        mime.trim().lowercase().substringBefore(";").trim()

    /** Ekstensi nama file, lowercase, tanpa titik ("foto.JPG" → "jpg"). */
    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    /**
     * Pilah file dari mime + nama file.
     *
     * Mime diprioritaskan; ekstensi dipakai bila mime kosong/generik
     * (application/octet-stream) atau bila mime tidak dikenal tapi nama
     * file jelas menunjukkan jenis dokumen. Mime image/ di luar
     * jpeg/png/webp/gif (mis. bmp, svg, tiff) → UNSUPPORTED.
     */
    fun classify(mime: String, fileName: String): VisionFileKind {
        val m = cleanMime(mime)
        val ext = extensionOf(fileName)
        if (m.isEmpty() || m == "application/octet-stream") {
            return when (ext) {
                in IMAGE_EXTS -> VisionFileKind.IMAGE
                in TEXT_EXTS -> VisionFileKind.TEXT_DOC
                "pdf" -> VisionFileKind.PDF
                "docx" -> VisionFileKind.DOCX
                else -> VisionFileKind.UNSUPPORTED
            }
        }
        if (m in IMAGE_MIMES) return VisionFileKind.IMAGE
        if (m == "application/pdf") return VisionFileKind.PDF
        if (m.contains("wordprocessingml")) return VisionFileKind.DOCX
        if (m.startsWith("text/") || m == "application/json" || m == "application/xml") {
            return VisionFileKind.TEXT_DOC
        }
        // Mime tak dikenal, nama file jadi penegas terakhir.
        return when (ext) {
            in TEXT_EXTS -> VisionFileKind.TEXT_DOC
            "pdf" -> VisionFileKind.PDF
            "docx" -> VisionFileKind.DOCX
            else -> VisionFileKind.UNSUPPORTED
        }
    }

    /** §4.2 butir 3 — teks eksak: file lain tidak dikirim. */
    fun unsupportedMessage(file: VisionFile): String {
        val tipe = cleanMime(file.mime)
            .ifBlank { extensionOf(file.fileName).ifBlank { "tidak diketahui" } }
        return "File ${file.fileName} ($tipe) belum didukung analisis di provider ini."
    }

    /**
     * §4.4 — gambar ditolak karena ukuran/format.
     * Gambar yang sudah dikompres tapi tetap >1 MB → "terlalu besar";
     * format tak didukung → "format <mime> belum didukung".
     */
    fun tooLargeMessage(file: VisionFile): String {
        val sebab = if (classify(file.mime, file.fileName) == VisionFileKind.IMAGE) {
            "terlalu besar"
        } else {
            "format ${cleanMime(file.mime).ifBlank { extensionOf(file.fileName) }} belum didukung"
        }
        return "📷 ${file.fileName} tidak bisa dianalisis: $sebab. " +
            "Coba gambar JPG/PNG yang lebih kecil."
    }

    /** §4.4 — TEKS EKSAK: model UNSUPPORTED, ditolak di depan. */
    fun modelUnsupportedMessage(model: String, providerLabel: String): String =
        "📷 Model '$model' di provider '$providerLabel' tidak mendukung analisis gambar. " +
            "Pilih model lain yang mendukung vision di Setup > 73. Provider LLM, " +
            "atau kirim gambar tanpa pertanyaan analisis."

    /**
     * §4.4 — TEKS EKSAK: server menolak gambar (UNKNOWN → 400).
     * Pesan server disaring via RedactSecrets.redact + cap 200 char.
     */
    fun serverRejectedMessage(providerLabel: String, model: String, serverMsg: String): String {
        val clean = RedactSecrets.redact(serverMsg).trim().take(200)
            .ifBlank { "tanpa rincian server" }
        return "📷 Provider '$providerLabel' menolak gambar untuk model '$model' ($clean). " +
            "Model ditandai tidak mendukung vision — pilih model lain di Setup > 73."
    }

    /**
     * True HANYA bila pesan mengandung "HTTP 400" DAN salah satu kata
     * (case-insensitive) image / vision / unsupported / multimodal.
     * "HTTP 400: bad request" tanpa kata itu → false; 429/500 → false.
     */
    fun isVisionRejection400(errorMessage: String?): Boolean {
        val msg = errorMessage ?: return false
        if (!msg.contains("HTTP 400", ignoreCase = true)) return false
        val lower = msg.lowercase()
        return listOf("image", "vision", "unsupported", "multimodal")
            .any { lower.contains(it) }
    }

    /**
     * Kembalikan salinan list dengan entri id == modelId (cocok eksak
     * ATAU lowercase) vision-nya jadi "unsupported". Bila id tidak ada
     * di list, tambahkan entri baru. Entri lain tidak diubah.
     */
    fun markUnsupported(
        models: List<ModelCaps.CachedModel>,
        modelId: String
    ): List<ModelCaps.CachedModel> {
        val target = modelId.lowercase()
        var found = false
        val out = models.map { e ->
            if (e.id == modelId || e.id.lowercase() == target) {
                found = true
                e.copy(vision = "unsupported")
            } else {
                e
            }
        }
        return if (found) out else out + ModelCaps.CachedModel(modelId, "unsupported")
    }

    /** Bila teks lebih panjang dari maxChars → take + penanda sisa char. */
    fun truncateDocText(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "…(dipotong, sisa ${text.length - maxChars} char)…"
    }

    /**
     * Strip XML word/document.xml → teks polos.
     * Pola yang sama dengan MainActivity.fallbackDocVc212: `<w:p[ >]`
     * jadi newline, hapus semua tag, unescape &lt; &gt; &amp;, trim.
     */
    fun stripDocxXml(xml: String): String =
        xml.replace(Regex("<w:p[ >]"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
}
