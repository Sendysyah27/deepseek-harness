package harness.core

/**
 * Seam JNI ke core Rust (`libharness_core.so`, crate `harness-core`).
 *
 * Package ini SENGAJA `harness.core` — BUKAN package aplikasi — dan file
 * ini TIDAK BOLEH mengandung string package aplikasi di mana pun
 * (komentar sekalipun): build flavor Pure me-rewrite string itu dan
 * akan merusak linkage JNI. Simbol JNI statis:
 * `Java_harness_core_RustCore_*`.
 *
 * `available` false bila `.so` absen/rusak atau versi seam tidak cocok
 * -> pemanggil WAJIB fallback ke implementasi Kotlin lama.
 * Jangan panggil fungsi Rust di main thread.
 */
object RustCore {
    /** Versi seam JNI — naikkan tiap kali signature berubah. */
    const val VERSION = 2

    val available: Boolean

    init {
        var ok = false
        try {
            System.loadLibrary("harness_core")
            ok = rustCoreVersion() == VERSION
        } catch (t: Throwable) {
            ok = false
        }
        available = ok
    }

    external fun rustCoreVersion(): Int
    external fun rustCoreCapabilities(): String
    external fun echo(input: String): String
    external fun classifyFailureJson(code: Int, body: String, exceptionMessage: String): String

    // ---- Slice 2: PowSolver (PoW DeepSeekHashV1) ----
    /** SHA3-256 sebagai 64 digit hex lowercase; "" / bukan-hex = gagal. */
    external fun powSha3Hex(input: String): String
    /** DeepSeekHashV1 (Keccak skip round 0) sebagai 64 digit hex. */
    external fun powDsHashV1Hex(input: String): String
    /** SHA3-256 mentah 32 byte; null = gagal (panic/JNI error). */
    external fun powSha3Bytes(input: ByteArray): ByteArray?
    /** DeepSeekHashV1 mentah 32 byte; null = gagal. */
    external fun powDsHashV1Bytes(input: ByteArray): ByteArray?
    /**
     * Solve PoW -> JSON: `{"ok":true,"found":true,"nonce":...,
     * "hash_hex":"...","tries":...,"elapsed_ms":...}` atau
     * `{"ok":true,"found":false}` bila nonce tak ketemu (sama seperti
     * `null` Kotlin); `{"ok":false,...}` = error -> fallback Kotlin.
     */
    external fun powSolveDeepSeekJson(challengeHex: String, salt: String, expireAt: Long, difficulty: Long): String
    external fun powSolveFastJson(challenge: String, salt: String, difficulty: Long, maxTries: Long): String
    external fun powSolveFmtJson(challenge: String, salt: String, difficulty: Long, format: String, expireAt: Long, maxTries: Long): String
    external fun powSolveJson(challenge: String, salt: String, difficulty: Long, maxTries: Long): String
}
