// AndroidStubs.kt — STUB MINIMAL untuk kompilasi + jalan tes JVM Fase 0b.
// Test-support repo (app/src/test — TIDAK ikut APK).
// Hanya menyediakan tipe Android yang dipakai file Fase 0b supaya kotlinc
// bisa mengkompilasi Governor/TurnMetrics/LlmProviderStore/LlmEngineChat
// tanpa Android SDK. Semua method stub TIDAK PERNAH dipakai di HP.

package android.content

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor
    // Properti (bukan fun getAll) supaya akses `.all` seperti di kode
    // Android-Java tetap jalan dari Kotlin.
    val all: Map<String, *>

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

abstract class Context {
    companion object {
        const val MODE_PRIVATE: Int = 0
    }

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        throw UnsupportedOperationException("android stub: getSharedPreferences")
    }

    // Properti (bukan fun getApplicationContext) supaya akses
    // `.applicationContext` seperti di kode repo tetap jalan dari Kotlin.
    open val applicationContext: Context get() = this

    // F4a: askWithFiles menulis PDF sementara ke cacheDir.
    open val cacheDir: java.io.File
        get() = java.io.File(System.getProperty("java.io.tmpdir") ?: ".")

    open fun getDatabasePath(name: String): java.io.File = java.io.File(name)
}

class ContentValues {
    private val map = HashMap<String, Any?>()
    fun put(key: String, value: String?) {
        map[key] = value
    }

    fun put(key: String, value: Long) {
        map[key] = value
    }

    fun put(key: String, value: Int) {
        map[key] = value
    }

    fun put(key: String, value: Boolean) {
        map[key] = value
    }
}
