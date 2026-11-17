package com.senzwh.deepharness

import harness.core.RustCore

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
    /**
     * Saklar A/B slice 4 (vc317): bila core Rust tersedia (.so ke-load
     * + versi seam cocok), konstanta + alokator jalan di Rust; bila
     * tidak, fallback ke implementasi Kotlin lama di bawah (nilai
     * literal + fungsi `*Kotlin` — JANGAN dihapus).
     *
     * PERUBAHAN BENTUK (bukan perilaku): `const val` menjadi `val`
     * dengan getter — konstanta Rust dibaca sekali (lazy, di-cache)
     * lewat satu panggilan JSON bulk. Satu-satunya pemakai posisi
     * `const` di tree (`PrivPairNotif.NOTIF_ID`) ikut diubah jadi
     * `val` dengan getter (dipakai hanya saat runtime).
     */
    var rustNotifIds: Boolean = true

    // ---- Seam Rust (vc317) ----

    private val notifNames = listOf(
        "SCHEDULE", "PARK_QUEUE", "SECOND_CONFIRM", "MEMORY_PROPOSAL",
        "PRIV_PAIR", "BG_SUMMARY_BASE", "KILL_SWITCH_CANCEL", "GOVERNOR_DELAY",
        "SERVE_DOWN", "HEAL_DIAGNOSIS", "GROUP_DONE", "MITM_DOWN",
        "BG_WATCHDOG", "BG_WATCHDOG_BASE", "WORKER_JANITOR_BASE",
        "BG_RESULT_BASE", "BG_FAIL_BASE"
    )

    /** Konstanta dari Rust (sekali baca, di-cache); null = fallback Kotlin. */
    private val rustConsts: Map<String, Int>? by lazy { rustConstsOrNull() }

    private fun rustConstsOrNull(): Map<String, Int>? {
        if (!rustNotifIds || !RustCore.available) return null
        return try {
            parseRustNotifIdsJson(RustCore.notifIdsJson("", "", ""))
        } catch (e: Throwable) {
            rustNotifIds = false
            null
        }
    }

    private fun parseRustNotifIdsJson(json: String): Map<String, Int>? {
        if (!json.contains("\"ok\":true")) return null
        val out = HashMap<String, Int>()
        for (name in notifNames) {
            val v = Regex("\"" + name + "\":(-?\\d+)").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
            out[name] = v
        }
        return out
    }

    private fun constOr(name: String, fallback: Int): Int = rustConsts?.get(name) ?: fallback

    /** Hasil satu panggilan bulk `notifIdsJson` (lima alokator). */
    private class RustNotifIds(
        val bgResultId: Int,
        val bgFailId: Int,
        val bgSummaryId: Int,
        val bgWatchdogId: Int,
        val workerJanitorId: Int
    )

    private fun rustNotifIdsBulk(origin: String, key: String, workerId: String): RustNotifIds? {
        if (!rustNotifIds || !RustCore.available) return null
        return try {
            val json = RustCore.notifIdsJson(origin, key, workerId)
            if (!json.contains("\"ok\":true")) return null
            fun i(k: String): Int? = Regex("\"" + k + "\":(-?\\d+)").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
            RustNotifIds(
                i("bgResultId") ?: return null,
                i("bgFailId") ?: return null,
                i("bgSummaryId") ?: return null,
                i("bgWatchdogId") ?: return null,
                i("workerJanitorId") ?: return null
            )
        } catch (e: Throwable) {
            rustNotifIds = false
            null
        }
    }

    val SCHEDULE: Int get() = constOr("SCHEDULE", 7701)
    val PARK_QUEUE: Int get() = constOr("PARK_QUEUE", 7703)
    val SECOND_CONFIRM: Int get() = constOr("SECOND_CONFIRM", 7704)
    val MEMORY_PROPOSAL: Int get() = constOr("MEMORY_PROPOSAL", 7706)
    val PRIV_PAIR: Int get() = constOr("PRIV_PAIR", 7708)
    val BG_SUMMARY_BASE: Int get() = constOr("BG_SUMMARY_BASE", 7790)
    val KILL_SWITCH_CANCEL: Int get() = constOr("KILL_SWITCH_CANCEL", 7798)
    val GOVERNOR_DELAY: Int get() = constOr("GOVERNOR_DELAY", 7799)
    val SERVE_DOWN: Int get() = constOr("SERVE_DOWN", 7103)
    val HEAL_DIAGNOSIS: Int get() = constOr("HEAL_DIAGNOSIS", 7102)
    val GROUP_DONE: Int get() = constOr("GROUP_DONE", 7900)
    val MITM_DOWN: Int get() = constOr("MITM_DOWN", 7722)
    val BG_WATCHDOG: Int get() = constOr("BG_WATCHDOG", 7723)
    // vc265 V-m1: BG_WATCHDOG (7723) dipertahankan sebagai ID legacy
    // (terdaftar di all() supaya checkDuplicates tetap jalan), tapi
    // watchdog TIDAK lagi memakai satu ID bersama untuk semua tugas
    // macet — tiap tugas pakai ID sendiri dari bgWatchdogId(key).
    // Rentang 8400..8655: di atas BG_FAIL_BASE 8100 + 0xFF = 8355,
    // jadi tidak menabrak BG_RESULT_BASE/BG_FAIL_BASE/BG_SUMMARY_BASE.
    val BG_WATCHDOG_BASE: Int get() = constOr("BG_WATCHDOG_BASE", 8400)
    // vc281 Fase 3 (D4): id notifikasi janitor/reset per worker — satu
    // id stabil per worker (sweep/reset berikutnya menggantikan yang
    // lama). Rentang 8800..9055: di atas BG_WATCHDOG 8400..8655, jadi
    // tidak menabrak registry mana pun di atas.
    val WORKER_JANITOR_BASE: Int get() = constOr("WORKER_JANITOR_BASE", 8800)
    // vc263 (H-M18): rentang dinamis latar — ID hasil/gagal latar
    // dialokasikan dari sini (bukan millis acak) supaya tidak pernah
    // menabrak registry di atas. BG_SUMMARY_BASE 7790 + 0..7 = 7790..7797
    // (7798/7799 milik KILL_SWITCH_CANCEL/GOVERNOR_DELAY).
    val BG_RESULT_BASE: Int get() = constOr("BG_RESULT_BASE", 7800)
    val BG_FAIL_BASE: Int get() = constOr("BG_FAIL_BASE", 8100)

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
    fun bgResultId(origin: String): Int =
        rustNotifIdsBulk(origin, "", "")?.bgResultId ?: bgResultIdKotlin(origin)
    fun bgFailId(origin: String): Int =
        rustNotifIdsBulk(origin, "", "")?.bgFailId ?: bgFailIdKotlin(origin)
    fun bgSummaryId(origin: String): Int =
        rustNotifIdsBulk(origin, "", "")?.bgSummaryId ?: bgSummaryIdKotlin(origin)
    // vc265 V-m1: ID notifikasi watchdog per tugas — key stabil per
    // task/job (kunci bgActive_), and 0xFF = 0..255, tidak pernah negatif.
    fun bgWatchdogId(key: String): Int =
        rustNotifIdsBulk("", key, "")?.bgWatchdogId ?: bgWatchdogIdKotlin(key)

    // vc281: id notifikasi janitor/reset untuk satu worker.
    fun workerJanitorId(workerId: String): Int =
        rustNotifIdsBulk("", "", workerId)?.workerJanitorId ?: workerJanitorIdKotlin(workerId)

    // Mengembalikan id yang dipakai lebih dari satu nama (harus kosong).
    // Dihitung dari all() — yang konstanta-konstantanya sudah Rust-backed
    // bila Rust tersedia (via getter di atas).
    fun checkDuplicates(): List<Int> {
        val seen = HashSet<Int>()
        val dup = ArrayList<Int>()
        for ((_, id) in all()) if (!seen.add(id)) dup.add(id)
        return dup
    }

    // ---- Implementasi Kotlin lama (fallback) — DIPERTAHANKAN ----

    private fun bgResultIdKotlin(origin: String): Int = BG_RESULT_BASE + (origin.hashCode() and 0xFF)
    private fun bgFailIdKotlin(origin: String): Int = BG_FAIL_BASE + (origin.hashCode() and 0xFF)
    private fun bgSummaryIdKotlin(origin: String): Int = BG_SUMMARY_BASE + ((origin.hashCode() and 0xFF) % 8)
    private fun bgWatchdogIdKotlin(key: String): Int = BG_WATCHDOG_BASE + (key.hashCode() and 0xFF)
    private fun workerJanitorIdKotlin(workerId: String): Int = WORKER_JANITOR_BASE + (workerId.hashCode() and 0xFF)
}
