// AndroidStubs5.kt — stub android.security.keystore.
// Test-support repo. Lihat AndroidStubs.kt.

package android.security.keystore

object KeyProperties {
    const val KEY_ALGORITHM_AES: String = "AES"
    const val PURPOSE_ENCRYPT: Int = 1
    const val PURPOSE_DECRYPT: Int = 2
    const val BLOCK_MODE_GCM: String = "GCM"
    const val ENCRYPTION_PADDING_NONE: String = "NoPadding"
}

class KeyGenParameterSpec private constructor() : java.security.spec.AlgorithmParameterSpec {
    class Builder(val alias: String, val purposes: Int) {
        fun setBlockModes(vararg modes: String): Builder = this
        fun setEncryptionPaddings(vararg paddings: String): Builder = this
        fun setKeySize(size: Int): Builder = this
        fun build(): KeyGenParameterSpec = KeyGenParameterSpec()
    }
}
