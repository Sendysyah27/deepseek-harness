package com.senzwh.deepharness

/** vc298 BAGIAN 1 — logika MURNI reviewer (tanpa Android, tanpa DB,
 *  tanpa jaringan; bisa unit-test JVM standalone seperti
 *  ArtifactStyleVc297 / SkillJudge / MemoryLoopVc294).
 *
 *  K1 (Fase 6): keputusan 1A (reviewer WAJIB slot Browser 5 + sesi
 *  server tetap) DIHAPUS — reviewer kini panggilan stateless via helper
 *  Models API, tanpa slot. Yang tersisa di file ini:
 *  1B. Lane run_team WAJIB membawa JEJAK TOOL LANE terstruktur ke
 *      reviewer (formatTraceStep + buildLaneEvidence): satu baris per
 *      langkah tool, format "- <tool> [ok|gagal] <hasil ringkas>" —
 *      SAMA seperti jejak 1-1 (vc228FullTrailText) supaya aturan JEJAK
 *      TOOL vc257 di prompt reviewer menyala untuk lane. Bukti lane
 *      dulu = transcript mentah (.take 16000 dari kepala) tanpa penanda
 *      [ok]/[gagal] dan tanpa label sumber yang dikenal prompt, dengan
 *      hasil tool AKHIR terpotong — klaim berbukti tool jadi dihapus
 *      reviewer.
 */
object ReviewerVc298 {
    /** Satu baris jejak tool lane: "- <tool> [ok|gagal] <hasil>".
     *  Hasil dipangkas 1500 char per langkah agar jejak banyak langkah
     *  tetap muat di anggaran bukti 16000 char. */
    fun formatTraceStep(tool: String, ok: Boolean, result: String): String {
        val r = result
        return "- " + tool + (if (ok) " [ok] " else " [gagal] ") +
            (if (r.length > 1500) r.take(1500) + "…(dipotong, sisa " + (r.length - 1500) + " char)…" else r) + "\n"
    }

    /** Bukti reviewer lane: JEJAK TOOL LANE didahulukan (bukan transcript
     *  mentah), lalu jawaban akhir lane, lalu FAKTA PIN. Total maks
     *  16000 char (cap yang sama seperti bukti reviewer lainnya). */
    fun buildLaneEvidence(task: String, toolTrace: String, laneResult: String, pinned: String): String {
        val trace = if (toolTrace.isBlank()) "(tidak ada tool yang dijalankan lane ini)" else toolTrace
        return ("SUB-TUGAS:\n" + task + "\nJEJAK TOOL LANE:\n" + trace +
            "\nHASIL LANE (jawaban akhir lane):\n" + laneResult.take(4000) +
            "\nFAKTA PIN:\n" + pinned).take(16000)
    }
}
