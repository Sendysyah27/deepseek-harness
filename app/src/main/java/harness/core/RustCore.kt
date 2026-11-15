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
    const val VERSION = 1

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
}
