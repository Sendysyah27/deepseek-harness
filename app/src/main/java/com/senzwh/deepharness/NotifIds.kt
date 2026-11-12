package com.senzwh.deepharness

// vc246 GELOMBANG 5 (K-11): REGISTRY ID NOTIFIKASI TERPUSAT.
// Aturan: tiap jenis notifikasi punya SATU id tetap di sini; jangan pakai
// angka mentah di tempat lain untuk jenis yang sudah terdaftar.
// Riwayat tabrakan: 7703 (parkir vs pairing lama) -> pairing pindah 7704
// (vc171) -> 7704 ternyata dipakai konfirmasi kedua BgBrain -> pairing
// pindah 7708 (vc246). Uji tabrakan: checkDuplicates() harus kosong;
// verifikasi build memakai grep atas angka-angka ini di laporan vc246.
//
// Pemetaan saluran (NT-M2): persetujuan/parkir latar = "harness_high"
// (HIGH, BgBrain); pengingat/alert umum = "harness-alert" (HIGH,
// HarnessService); diagnosis self-healing = "harness_auto_heal"
// (MainActivity). Tidak ada saluran "harness_channel" di tree vc245.
object NotifIds {
    const val SCHEDULE = 7701
    const val PARK_QUEUE = 7703
    const val SECOND_CONFIRM = 7704
    const val MEMORY_PROPOSAL = 7706
    const val PRIV_PAIR = 7708
    const val BG_SUMMARY_BASE = 7790
    const val KILL_SWITCH_CANCEL = 7798
    const val GOVERNOR_DELAY = 7799
    const val SERVE_DOWN = 7103
    const val HEAL_DIAGNOSIS = 7102
    const val GROUP_DONE = 7900
    const val MITM_DOWN = 7722
    const val BG_WATCHDOG = 7723
    // vc265 V-m1: BG_WATCHDOG (7723) dipertahankan sebagai ID legacy
    // (terdaftar di all() supaya checkDuplicates tetap jalan), tapi
    // watchdog TIDAK lagi memakai satu ID bersama untuk semua tugas
    // macet — tiap tugas pakai ID sendiri dari bgWatchdogId(key).
    // Rentang 8400..8655: di atas BG_FAIL_BASE 8100 + 0xFF = 8355,
    // jadi tidak menabrak BG_RESULT_BASE/BG_FAIL_BASE/BG_SUMMARY_BASE.
    const val BG_WATCHDOG_BASE = 8400
    // vc281 Fase 3 (D4): id notifikasi janitor/reset per worker — satu
    // id stabil per worker (sweep/reset berikutnya menggantikan yang
    // lama). Rentang 8800..9055: di atas BG_WATCHDOG 8400..8655, jadi
    // tidak menabrak registry mana pun di atas.
    const val WORKER_JANITOR_BASE = 8800
    // vc263 (H-M18): rentang dinamis latar — ID hasil/gagal latar
    // dialokasikan dari sini (bukan millis acak) supaya tidak pernah
    // menabrak registry di atas. BG_SUMMARY_BASE 7790 + 0..7 = 7790..7797
    // (7798/7799 milik KILL_SWITCH_CANCEL/GOVERNOR_DELAY).
    const val BG_RESULT_BASE = 7800
    const val BG_FAIL_BASE = 8100

    fun all(): List<Pair<String, Int>> = listOf(
        "SCHEDULE" to SCHEDULE,
        "PARK_QUEUE" to PARK_QUEUE,
        "SECOND_CONFIRM" to SECOND_CONFIRM,
        "MEMORY_PROPOSAL" to MEMORY_PROPOSAL,
        "PRIV_PAIR" to PRIV_PAIR,
        "KILL_SWITCH_CANCEL" to KILL_SWITCH_CANCEL,
        "GOVERNOR_DELAY" to GOVERNOR_DELAY,
        "SERVE_DOWN" to SERVE_DOWN,
        "HEAL_DIAGNOSIS" to HEAL_DIAGNOSIS,
        "GROUP_DONE" to GROUP_DONE,
        "MITM_DOWN" to MITM_DOWN,
        "BG_WATCHDOG" to BG_WATCHDOG
    )

    // vc263 (H-M18): alokator ID notifikasi latar per origin.
    // and 0xFF = 0..255 (tidak pernah negatif); summary memakai sisa
    // bagi 8 dari nilai yang sama sehingga jatuh di 7790..7797.
    fun bgResultId(origin: String): Int = BG_RESULT_BASE + (origin.hashCode() and 0xFF)
    fun bgFailId(origin: String): Int = BG_FAIL_BASE + (origin.hashCode() and 0xFF)
    fun bgSummaryId(origin: String): Int = BG_SUMMARY_BASE + ((origin.hashCode() and 0xFF) % 8)
    // vc265 V-m1: ID notifikasi watchdog per tugas — key stabil per
    // task/job (kunci bgActive_), and 0xFF = 0..255, tidak pernah negatif.
    fun bgWatchdogId(key: String): Int = BG_WATCHDOG_BASE + (key.hashCode() and 0xFF)

    // vc281: id notifikasi janitor/reset untuk satu worker.
    fun workerJanitorId(workerId: String): Int = WORKER_JANITOR_BASE + (workerId.hashCode() and 0xFF)

    // Mengembalikan id yang dipakai lebih dari satu nama (harus kosong).
    fun checkDuplicates(): List<Int> {
        val seen = HashSet<Int>()
        val dup = ArrayList<Int>()
        for ((_, id) in all()) if (!seen.add(id)) dup.add(id)
        return dup
    }
}
