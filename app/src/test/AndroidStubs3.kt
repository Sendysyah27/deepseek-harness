// AndroidStubs3.kt — stub android.database.sqlite + keystore.
// Test-support repo. Lihat AndroidStubs.kt.

package android.database

interface Cursor {
    fun moveToNext(): Boolean
    fun moveToFirst(): Boolean
    fun getLong(columnIndex: Int): Long
    fun getString(columnIndex: Int): String?
    fun getInt(columnIndex: Int): Int
    fun close()
}
