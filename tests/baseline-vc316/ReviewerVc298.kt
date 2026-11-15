package com.senzwh.deepharness

/** vc298 BAGIAN 1 — logika MURNI reviewer (tanpa Android, tanpa DB,
 *  tanpa jaringan; bisa unit-test JVM standalone seperti
 *  ArtifactStyleVc297 / SkillJudge / MemoryLoopVc294).
 *
 *  Dua keputusan vc298 yang dipusatkan di sini (satu sumber kebenaran,
 *  jangan duplikasi angka/teks ini di tempat lain):
 *  1A. Reviewer konteks-nol WAJIB slot Browser 5 (SLOT) + sesi server
 *      TETAP reviewer (reviewerSessionKey) yang dipakai ulang antar
 *      review. Latar: reviewer lama memakai slot terisi pertama yang
 *      bukan slot giliran + sesi server ephemeral BARU tiap review
 *      (SlotClient freshEphemeral=true), sehingga puluhan chat
 *      "Review klaim" menumpuk di Browser 1 dan akun itu kena SUSPEND
 *      DeepSeek 12 Nov 2026. Bila slot 5 tidak login, reviewer
 *      DILEWATI dengan SKIP_NOTE — JANGAN fallback ke slot lain.
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
    /** Slot reviewer — Browser 5. AccountPool.REVIEWER_SLOT = nilai ini. */
    const val SLOT = 5

    /** Penanda jujur bila reviewer dilewati karena slot 5 tidak login.
     *  Menyebut slot 5 secara eksplisit; dipakai semua jalur reviewer. */
    const val SKIP_NOTE = "🔍 Reviewer konteks-nol dilewati: slot Browser 5 tidak login — login Browser 5 di Setup agar reviewer jalan (reviewer tidak fallback ke slot lain)."

    /** Pemilihan slot reviewer: 5 bila slot 5 terisi, null bila tidak.
     *  TIDAK ADA fallback — daftar terisi yang tidak memuat 5 = null,
     *  sekalipun slot 1-4 terisi. */
    fun pickReviewerSlot(filledSlots: List<Int>): Int? {
        return if (SLOT in filledSlots) SLOT else null
    }

    /** Kunci sesi server TETAP reviewer per (slot, sesi harness) —
     *  SENGAJA berbeda dari kunci giliran agen ("dsSlotSrv_<slot>_<n>")
     *  supaya sesi reviewer tidak pernah membaca/menimpa sesi giliran
     *  agen di slot yang sama. */
    fun reviewerSessionKey(slot: Int, harnessNo: Long): String {
        return "dsSlotSrvReviewer_" + slot + "_" + harnessNo
    }

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
