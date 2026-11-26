// AndroidStubs4.kt — stub android.database.sqlite + keystore.
// Test-support repo. Lihat AndroidStubs.kt.

package android.database.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor

private class EmptyCursor : Cursor {
    override fun moveToNext(): Boolean = false
    override fun moveToFirst(): Boolean = false
    override fun getLong(columnIndex: Int): Long = 0L
    override fun getString(columnIndex: Int): String? = null
    override fun getInt(columnIndex: Int): Int = 0
    override fun close() {}
}

class SQLiteDatabase {
    fun execSQL(sql: String) {}
    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long = 1L
    fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor = EmptyCursor()
    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int = 0
    fun close() {}
}

abstract class SQLiteOpenHelper(
    val context: Context?,
    val name: String?,
    val factory: CursorFactory?,
    val version: Int
) {
    open fun onCreate(db: SQLiteDatabase) {}
    open fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    open val writableDatabase: SQLiteDatabase get() = SQLiteDatabase()
    open val readableDatabase: SQLiteDatabase get() = SQLiteDatabase()

    class CursorFactory
}
