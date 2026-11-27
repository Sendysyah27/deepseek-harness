package com.senzwh.deepharness

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * vc334 Ronde 4 — helper MURNI (JVM, tanpa android.*, tanpa Context)
 * untuk fix vision + jalur (V-1, V-2, V-4, S-1, R-1, G-2).
 *
 * Semua keputusan yang bisa diuji tanpa HP tinggal di sini; situs
 * Android (MainActivity / LlmEngineChat / TeamRun / GroupChat) hanya
 * memanggil helper ini. Tes: VisionPathsVc334R4Test.kt.
 *
 * Yang SENGAJA tidak ada di sini: G-5 (retry room delta — dilewati,
 * lihat BUILD-VC334.md § Ronde 4).
 */
object Vc334R4Pure {

    /** Batas lampiran (§4.2 / S-1): 12 MB. */
    const val MAX_ATTACH_BYTES = 12_000_000L

    // ================= R-1: parsing STATUS F2b =================

    /**
     * Regex kanonik STATUS F2b (audit W4 R-1): baris pertama jawaban
     * harus PERSIS `STATUS: <salah satu>`. `contains()` dilarang —
     * "tugas belum TERVERIFIKASI sepenuhnya" mengandung substring
     * TERVERIFIKASI tapi statusnya SEBAGIAN.
     * Tidak cocok / kosong / tanpa prefix → SEBAGIAN (fail-closed,
     * bukan TERVERIFIKASI dan bukan GAGAL).
     */
    private val REVIEW_STATUS_REGEX =
        Regex("^STATUS:\\s*(TERVERIFIKASI|SEBAGIAN|GAGAL)\\b")

    fun parseReviewStatus(answer: String): String {
        val m = REVIEW_STATUS_REGEX.find(answer.trim())
        return m?.groupValues?.getOrNull(1) ?: "SEBAGIAN"
    }

    // ================= V-1: gagal provider-level vs per-file =================

    /**
     * True bila penyebab gagal ada di tingkat PROVIDER / MODEL — satu
     * akar masalah untuk semua berkas, sehingga fallback per-berkas
     * hanya akan mengulang kegagalan yang sama (banjir bubble + biaya
     * N×). Pemanggil: tampilkan SATU bubble error, JANGAN fallback
     * per-berkas.
     *
     * Provider-level: model tidak mendukung vision, NO_PROVIDER /
     * provider belum siap, auth (401/403/API key), kuota/rate-limit
     * (429), penolakan vision HTTP 400, error HTTP 4xx/5xx lain,
     * jaringan/timeout/unreachable. Cause-chain ikut diperiksa.
     *
     * Per-file (false — BOLEH fallback per-berkas): gagal baca file,
     * gagal render PDF, gambar terlalu besar / tak terbaca, format
     * tak didukung, DOCX gagal diekstrak.
     */
    fun isProviderLevelFailure(e: Throwable?): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is VisionUnsupportedException) return true
            if (isProviderLevelFailureMessage(cur.message)) return true
            cur = cur.cause
        }
        return false
    }

    fun isProviderLevelFailureMessage(msg: String?): Boolean {
        val m = msg ?: return false
        val low = m.lowercase()
        // Pesan kanonik helper (LlmEngineChat): NO_PROVIDER / belum siap.
        if (low.contains("otak belum diatur")) return true
        if (low.contains("belum siap")) return true
        // Penolakan vision 400 (model menolak gambar) — provider-level.
        if (VisionFiles.isVisionRejection400(m)) return true
        // Error HTTP deterministik: retry berkas yang sama gagal sama.
        if (Regex("http\\s+\\d{3}").containsMatchIn(low)) return true
        // Auth / kuota / jaringan — kata kunci dari pesan client.
        val providerKeywords = listOf(
            "unauthorized", "api key", "authentication", "forbidden",
            "quota", "kuota", "rate limit", "rate_limit",
            "insufficient_quota", "too many requests",
            "unreachable", "tidak dapat dijangkau", "gagal terhubung",
            "timeout", "timed out", "unknownhost", "connection refused",
            "tidak ada jawaban", "jawaban kosong"
        )
        return providerKeywords.any { low.contains(it) }
    }

    // ================= V-2: target render PDF =================

    /**
     * Ukuran bitmap render halaman PDF: sisi panjang maks `maxSide`
     * (default 1280), aspek dipertahankan, tanpa upscale.
     * A4 300 dpi (2480×3508, ~35 MB ARGB_8888) → 906×1280 (~4,6 MB).
     * Dimensi tak valid (<=0, mis. stub tes) dikembalikan apa adanya.
     */
    fun pdfTargetSize(width: Int, height: Int, maxSide: Int = 1280): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return Pair(width, height)
        val longSide = maxOf(width, height)
        if (longSide <= maxSide) return Pair(width, height)
        val scale = maxSide.toDouble() / longSide
        return Pair(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
    }

    // ================= V-4: hitung calon gambar =================

    /**
     * Jumlah calon gambar SEBELUM render apa pun: IMAGE + PDF
     * (PDF-jadi-gambar). TEXT_DOC / DOCX / UNSUPPORTED bukan gambar.
     * Pemanggil menolak bila > VisionFiles.MAX_IMAGES sebelum satu
     * pun PDF dirender.
     */
    fun prospectiveImageCount(files: List<VisionFile>): Int =
        files.count {
            when (VisionFiles.classify(it.mime, it.fileName)) {
                VisionFileKind.IMAGE, VisionFileKind.PDF -> true
                else -> false
            }
        }

    // ================= S-1: baca ber-cap =================

    /**
     * Baca InputStream dengan cap keras: berhenti + throw begitu total
     * melewati `maxBytes` — file besar dari picker TIDAK PERNAH dibaca
     * utuh ke memori. Throw: IllegalStateException (pesan menyebut
     * batas MB). File <= cap dibaca utuh seperti biasa.
     */
    @Throws(IllegalStateException::class)
    fun readCappedBytes(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw IllegalStateException(
                    "File terlalu besar (lebih dari ${maxBytes / 1_000_000} MB) — tidak dibaca utuh."
                )
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ================= G-2: prompt delta lane tim =================

    /**
     * Prompt LANJUTAN lane tim (delta, pola grup vc321 / 1-1 vc316):
     * peran + sub-tugas VERBATIM + ekor transkrip (hasil tool
     * terakhir) + instruksi lanjut — TANPA system prompt, TANPA blok
     * memori (dihitung sekali di awal lane), TANPA transkrip penuh.
     * Prompt PENUH hanya dikirim sekali di awal lane (runLane).
     */
    fun teamLaneDeltaPrompt(
        agentName: String, role: String, task: String, transcriptTail: String
    ): String {
        return "[MODE TIM — ruang $agentName] $role\n" +
            "Kamu mengerjakan SATU sub-tugas dari tujuan tim yang lebih besar. " +
            "Fokus hanya pada sub-tugasmu; agen lain mengerjakan bagian lain secara paralel.\n" +
            "[USER — SUB-TUGAS VERBATIM]:\n$task\n[/USER]\n" +
            "[CATATAN GILIRAN — hasil terakhir]:\n$transcriptTail\n" +
            "Lanjutkan sub-tugasmu: bila butuh tool lain keluarkan blok json lagi, " +
            "bila selesai jawab final tanpa blok json."
    }
}
