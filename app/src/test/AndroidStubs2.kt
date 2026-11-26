// AndroidStubs2.kt — stub android.util / android.database / keystore.
// Test-support repo. Lihat AndroidStubs.kt.

package android.util

object Log {
    fun v(tag: String, msg: String): Int = 0
    fun d(tag: String, msg: String): Int = 0
    fun i(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String, tr: Throwable): Int = 0
}

object Base64 {
    const val NO_WRAP: Int = 2
    const val DEFAULT: Int = 0

    fun encodeToString(input: ByteArray, flags: Int): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(input)

    fun decode(str: String, flags: Int): ByteArray =
        java.util.Base64.getDecoder().decode(str)
}
