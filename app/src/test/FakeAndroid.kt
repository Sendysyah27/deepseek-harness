// FakeAndroid.kt — Context + SharedPreferences in-memory untuk tes JVM.
// Test-support repo (app/src/test — TIDAK ikut APK).
// Dipakai GovernorModelsTest + LlmProvidersTest (T11).

package com.senzwh.deepharness

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class FakeSharedPreferences : SharedPreferences {
    val data = HashMap<String, Any?>()

    override fun getString(key: String, defValue: String?): String? =
        (data[key] as? String) ?: defValue

    override fun getInt(key: String, defValue: Int): Int =
        (data[key] as? Int) ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (data[key] as? Long) ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (data[key] as? Boolean) ?: defValue

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    override val all: Map<String, *>
        get() = HashMap(data)
}

class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
    private val pending = HashMap<String, Any?>()
    private val removals = HashSet<String>()
    private var clearAll = false

    override fun putString(key: String, value: String?): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putInt(key: String, value: Int): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putLong(key: String, value: Long): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    // vc332 G7: stub putFloat (lihat AndroidStubs.kt) — dipakai
    // PrefsSafe.putRestoreNumber untuk nilai Double.
    override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun remove(key: String): SharedPreferences.Editor {
        removals.add(key)
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        clearAll = true
        return this
    }

    override fun apply() {
        commit()
    }

    override fun commit(): Boolean {
        if (clearAll) prefs.data.clear()
        for (k in removals) prefs.data.remove(k)
        prefs.data.putAll(pending)
        return true
    }
}

open class FakeContext : Context() {
    val prefsByName = HashMap<String, FakeSharedPreferences>()

    /** Bila true, getSharedPreferences melempar (untuk tes "save gagal → false"). */
    var throwOnPrefs: Boolean = false

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        if (throwOnPrefs) throw RuntimeException("fake prefs failure")
        return prefsByName.getOrPut(name) { FakeSharedPreferences() }
    }

    override val applicationContext: Context get() = this

    override fun getDatabasePath(name: String): File = File("/tmp/fake-$name")
}
