package com.senzwh.deepharness

/**
 * vc335 Ronde 3 — helper MURNI (JVM, tanpa android.*, tanpa Context)
 * untuk keputusan NEEDS-DECISION D3/D5/D6/D7/D9/D10/D11/D13/D14/D15.
 *
 * Semua keputusan yang bisa diuji tanpa HP tinggal di sini; situs
 * Android (OpenAiCompatClient / LlmEngineChat / MainActivity / TeamRun)
 * hanya memanggil helper ini. Tes: Vc335R3Test.kt.
 *
 * D3  — fallback non-stream TERBATAS (dipertahankan, bukan dihapus):
 *       fail-fast 4xx + hanya bila belum ada byte diterima.
 * D5  — cache_control TIDAK global: hanya bila record provider
 *       menyatakan mendukung (LlmProvider.supportsCacheControl).
 * D6  — tool yang sedang berjalan saat ■ ditekan: biarkan selesai,
 *       lalu BUANG hasilnya (jangan batalkan kooperatif, jangan
 *       append hasil ke riwayat). Keputusan buang-atau-pakai tinggal
 *       di [shouldDiscardToolResult].
 * D7  — call penutup pakai tools=null (bukan tool_choice:"none"):
 *       ditegakkan di OpenAiCompatClient.buildChatBody (cabang
 *       tool_choice:"none" tanpa tools DICABUT) — di sini tidak ada
 *       helper, hanya tes live di Vc335R3Test + OpenAiCompatClientTest.
 * D9  — TIDAK ada watchdog lapis kedua: tidak ada kode baru di sini;
 *       tes di Vc335R3Test memindai sumber dan menegaskan daftar
 *       watchdog yang diizinkan tidak bertambah.
 * D10 — TeamRun.verify pakai bucket Governor REVIEWER (bukan TEAM):
 *       pemetaan kind→bucket tinggal di LlmEngineChat.bucketFor;
 *       di sini hanya penanda bahwa verify memakai REVIEWER.
 * D11 — cap teks dokumen 60.000 char (lihat VisionFiles.DOC_TEXT_CAP).
 * D13 — cap total request vision maks 4 per giliran.
 * D14 — lewati verify tim bila semua lane gagal (log saja).
 * D15 — varian EventDb (R5 vc334): arsip skill tetap TIDAK
 *       dipertahankan dari wipe — hanya ringkasan jejak (nama/ref/
 *       waktu hapus) yang disalin ke EventDb sebelum wipe; isi
 *       instruksi TIDAK ikut. Di sini tidak ada helper baru; tes di
 *       Vc335R3Test menegaskan semantik wipe tidak berubah.
 */
object Vc335R3Pure {

    // ================= D3: fallback non-stream terbatas =================

    /**
     * Keputusan fallback non-stream (D3, dipertahankan versi terbatas).
     * True = BOLEH fallback sekali ke non-stream; false = langsung error.
     *  - dibatalkan user → TIDAK PERNAH fallback (itu akan menembak
     *    request penuh kedua sesudah user minta berhenti);
     *  - sudah ada byte diterima (fragmen content / tool delta sudah
     *    sampai ke pemanggil) → TIDAK fallback (mengulang request akan
     *    menduplikasi jawaban yang sudah mengalir);
     *  - HTTP 4xx deterministik → TIDAK fallback (mengulang request
     *    yang sama akan gagal sama);
     *  - selain itu (5xx / gagal transport / status tak dikenal, tanpa
     *    byte) → fallback sekali.
     * `statusCode` null = gagal transport (tanpa status HTTP).
     */
    fun shouldFallbackNonStreamVc335R3(
        statusCode: Int?,
        receivedAny: Boolean,
        cancelled: Boolean
    ): Boolean {
        if (cancelled) return false
        if (receivedAny) return false
        if (statusCode != null && statusCode in 400..499) return false
        return true
    }

    // ================= D6: buang hasil tool sesudah giliran berakhir =================

    /**
     * True bila hasil tool yang baru selesai HARUS DIBUANG (D6):
     * token giliran saat tool dimulai != token giliran sekarang —
     * giliran sudah berakhir (user menekan ■ → endUserTurn menolkan
     * token, atau giliran baru sudah dimulai). Tool-nya sendiri
     * dibiarkan selesai (tidak dibatalkan kooperatif); yang dibuang
     * hanya hasilnya: tanpa continuation, tanpa append ke riwayat
     * (turnWork / openaiTurnMessagesVc5b / journal).
     */
    fun shouldDiscardToolResult(capturedToken: Int, currentToken: Int): Boolean =
        capturedToken != currentToken

    // ================= D13: anggaran vision per giliran =================

    /**
     * Anggaran request vision per giliran (D13): maks 4 gambar/PDF-
     * jadi-gambar per giliran, dihitung kumulatif lintas panggilan
     * askWithFiles dalam giliran yang sama. Kunci = nomor giliran
     * TurnMetrics.currentTurnNo() (naik tiap beginTurn, tidak pernah
     * di-reset) — ganti nomor giliran = reset otomatis, tanpa perlu
     * hook reset yang bisa terpanggil sesudah consume (hazard
     * urutan reset-di-beginTurn vs consume-di-askWithFiles).
     * Catatan jujur: bila satu panggilan vision jalan SEBELUM beginTurn
     * giliran itu (bukan alur 1-1 normal), ia memakai nomor giliran
     * sebelumnya — fail-closed (lebih ketat, bukan lebih longgar).
     */
    object VisionTurnBudgetVc335R3 {
        /** Maks total request vision (gambar) per giliran. */
        const val MAX_VISION_PER_TURN = 4

        private var turnKey = -1
        private var used = 0

        /** Coba pakai `count` slot di giliran `turnNo`. False = anggaran
         *  habis — pemanggil menolak dengan pesan jujur, tanpa render /
         *  kirim apa pun. count <= 0 selalu lolos tanpa memakai slot. */
        @Synchronized
        fun tryConsume(turnNo: Int, count: Int): Boolean {
            if (count <= 0) return true
            if (turnNo != turnKey) {
                turnKey = turnNo
                used = 0
            }
            if (used + count > MAX_VISION_PER_TURN) return false
            used += count
            return true
        }

        /** Slot terpakai di giliran `turnNo` (0 bila giliran lain). */
        @Synchronized
        fun usedInTurn(turnNo: Int): Int = if (turnNo == turnKey) used else 0

        /** Sisa slot di giliran `turnNo`. */
        @Synchronized
        fun remainingInTurn(turnNo: Int): Int =
            MAX_VISION_PER_TURN - usedInTurn(turnNo)

        /** Reset eksplisit (dipakai tes; produksi memakai kunci turnNo). */
        @Synchronized
        fun resetForTest() {
            turnKey = -1
            used = 0
        }
    }

    // ================= D14: lewati verify bila semua lane gagal =================

    /**
     * True bila satu hasil lane tim adalah kegagalan (D14 — predikat
     * yang SAMA dengan status papan jujur TeamRun.honestBoardStatus,
     * supaya "gagal" berarti sama di papan dan di gerbang verify).
     * Frasa gagal berasal dari string hasil lane yang sudah ada:
     * "gagal", "belum selesai", "tidak ada akun", "berhenti:",
     * "dihentikan:" (case-insensitive).
     */
    fun isLaneFailedVc335R3(result: String): Boolean {
        val t = result.lowercase()
        return t.contains("gagal") || t.contains("belum selesai") ||
            t.contains("tidak ada akun") || t.contains("berhenti:") ||
            t.contains("dihentikan:")
    }

    /**
     * True bila SEMUA lane gagal (dan ada minimal satu lane) — verify
     * tim dilewati, cukup log, giliran tidak diblokir. Daftar kosong
     * → false (bukan "semua gagal"; run() sudah menolak daftar kosong
     * lebih dulu).
     */
    fun allLanesFailedVc335R3(results: List<String>): Boolean =
        results.isNotEmpty() && results.all { isLaneFailedVc335R3(it) }
}
