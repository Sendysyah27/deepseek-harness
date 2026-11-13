package com.senzwh.deepharness

// vc305 H-K1 — State machine parkir persetujuan (DURABLE).
//
// Logika MURNI tanpa dependensi Android (JVM-testable), pola seperti
// SessionBackgroundVc304.kt. Pola diambil dari dsh-go (§4.2 laporan:
// state machine approval yang eksplisit + durable) — IMPLEMENTASI
// ditulis sendiri dari pola, TANPA menyalin kode AGPL dsh-go.
//
// Kontrak (dipakai BgBrain.resumeApproval / BgApprovalReceiver /
// MainActivity.showBgSecondConfirmVc229):
//  - HIGH dari tap notifikasi TIDAK PERNAH dieksekusi langsung:
//    onTap(PENDING, HIGH) = REQUIRE_SECOND_CONFIRM.
//  - SAFE (non-HIGH) yang masih PENDING boleh dieksekusi langsung
//    sesudah klaim: onTap = EXECUTE_SAFE.
//  - Apa pun yang sudah diputuskan / sedang diproses / kedaluwarsa /
//    tidak ada HANYA me-render ulang kartu "Processing" TANPA tombol
//    aksi — tidak pernah mengeksekusi ulang.
//  - createdAtMs <= 0 SELALU expired (menutup lubang abadi vc304 di
//    mana entri ts==0 lolos staleness selamanya).
//  - Timeout dimiliki keputusan PERTAMA (timeoutOwnerMs): redelivery
//    TIDAK me-reset / memperpanjang timeout.
object ParkedApprovalMachineVc305 {

    /** Penanda versi state machine (untuk log / diagnosa). */
    const val PARKED_APPROVAL_VC305 = "vc305"

    const val HIGH_STALE_MS = 15 * 60_000L
    const val OTHER_STALE_MS = 24 * 3600_000L

    enum class Status { PENDING, PROCESSING, APPROVED, REJECTED, EXPIRED }

    enum class TapOutcome {
        REQUIRE_SECOND_CONFIRM,
        EXECUTE_SAFE,
        RENDER_PROCESSING,
        EXPIRED_NOOP,
        NOT_FOUND_NOOP
    }

    enum class RedeliveryOutcome {
        RENDER_PROCESSING,
        RENDER_PENDING,
        EXPIRED_NOOP,
        NOT_FOUND_NOOP
    }

    enum class CancelOutcome {
        /** Kartu masih PENDING: boleh ditolak (status -> REJECTED). */
        ALLOW_REJECT,
        /** Kartu sudah diputuskan / sedang diproses: JANGAN dihapus
         *  diam-diam — kartu dipertahankan, tampil sebagai Processing. */
        KEEP_CARD,
        /** Kartu sudah EXPIRED: tidak ada lagi yang bisa dihapus. */
        ALREADY_GONE,
        NOT_FOUND_NOOP
    }

    /** Snapshot status durable satu parkir (diisi dari SQLite store). */
    data class ParkedState(
        val pid: String,
        val tool: String,
        val risk: String,
        val status: Status,
        val decision: String?,
        val createdAtMs: Long,
        val decidedAtMs: Long,
        val timeoutOwnerMs: Long,
        val notifId: Int
    )

    fun isHighRisk(risk: String): Boolean = risk == "HIGH"

    /** Batas staleness per risiko: HIGH 15 menit, lainnya 24 jam. */
    fun staleMsFor(risk: String): Long =
        if (isHighRisk(risk)) HIGH_STALE_MS else OTHER_STALE_MS

    /** createdAtMs <= 0 SELALU expired — entri legacy ts==0 tidak
     *  pernah abadi lagi (lubang vc304: `if (parkTs > 0 && ...)`). */
    fun isExpired(createdAtMs: Long, risk: String, nowMs: Long): Boolean {
        if (createdAtMs <= 0) return true
        if (nowMs < createdAtMs) return false
        return nowMs - createdAtMs > staleMsFor(risk)
    }

    /** Klaim PROCESSING kedaluwarsa bila sudah lewat timeoutMs sejak
     *  klaim pertama (timeoutOwnerMs). Redelivery tidak mengubah
     *  timeoutOwnerMs, jadi klaim tidak bisa diperpanjang. */
    fun isClaimExpired(state: ParkedState, nowMs: Long): Boolean {
        if (state.status != Status.PROCESSING) return false
        if (state.timeoutOwnerMs <= 0) return false
        if (nowMs < state.timeoutOwnerMs) return false
        return nowMs - state.timeoutOwnerMs > staleMsFor(state.risk)
    }

    /** Keputusan user mengetuk notifikasi / kartu parkir.
     *  `pinSet` TIDAK mengubah hasil: HIGH selalu butuh konfirmasi
     *  kedua (dialog argumen lengkap; field PIN tampil di dialog bila
     *  PIN diset) — PIN memengaruhi isi dialog, bukan kebutuhan
     *  konfirmasi kedua. */
    fun onTap(state: ParkedState?, risk: String, pinSet: Boolean, nowMs: Long): TapOutcome {
        if (state == null) return TapOutcome.NOT_FOUND_NOOP
        if (state.status == Status.EXPIRED) return TapOutcome.EXPIRED_NOOP
        if (isExpired(state.createdAtMs, state.risk, nowMs)) return TapOutcome.EXPIRED_NOOP
        if (isClaimExpired(state, nowMs)) return TapOutcome.EXPIRED_NOOP
        return when (state.status) {
            Status.PENDING ->
                if (isHighRisk(state.risk) || isHighRisk(risk)) TapOutcome.REQUIRE_SECOND_CONFIRM
                else TapOutcome.EXECUTE_SAFE
            Status.PROCESSING, Status.APPROVED, Status.REJECTED ->
                TapOutcome.RENDER_PROCESSING
            Status.EXPIRED -> TapOutcome.EXPIRED_NOOP
        }
    }

    /** Pengiriman ulang notifikasi / ketukan kedua pada kartu yang
     *  sama: tidak pernah menambah tombol aksi, tidak pernah
     *  memperpanjang timeout. */
    fun onRedelivery(state: ParkedState?): RedeliveryOutcome {
        if (state == null) return RedeliveryOutcome.NOT_FOUND_NOOP
        return when (state.status) {
            Status.EXPIRED -> RedeliveryOutcome.EXPIRED_NOOP
            Status.PROCESSING, Status.APPROVED, Status.REJECTED ->
                RedeliveryOutcome.RENDER_PROCESSING
            Status.PENDING -> RedeliveryOutcome.RENDER_PENDING
        }
    }

    /** Pembatalan / dismiss kartu: kartu yang sudah diputuskan TIDAK
     *  boleh dihapus diam-diam. */
    fun onCancel(state: ParkedState?): CancelOutcome {
        if (state == null) return CancelOutcome.NOT_FOUND_NOOP
        return when (state.status) {
            Status.PENDING -> CancelOutcome.ALLOW_REJECT
            Status.PROCESSING, Status.APPROVED, Status.REJECTED ->
                CancelOutcome.KEEP_CARD
            Status.EXPIRED -> CancelOutcome.ALREADY_GONE
        }
    }
}
