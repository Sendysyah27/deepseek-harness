package com.senzwh.deepharness

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

// vc267 ARTIFACTS: penyimpanan artifact persisten (SQLite, pola
// MemoryDb/EventDb: SQLiteOpenHelper sendiri + singleton get()).
// Dipilih SQLite, BUKAN file JSON di filesDir, karena artifact butuh
// query per sesi pemilik + riwayat versi per artifact + baca versi
// tertentu tanpa memuat seluruh koleksi ke memori; SQLite memberi
// ketiganya dengan pola yang sudah dipakai codebase ini, dan data
// bertahan setelah app restart (DB di filesDir app, bukan cache).
// Skema: artifacts (satu baris per artifact) + artifact_versions
// (satu baris per versi; edit = INSERT versi baru, versi lama utuh).
// Konten artifact = DATA TAMPIL saja: tidak pernah dieksekusi sebagai
// perintah agen dan tidak pernah disuntik ke prompt sebagai [USER]
// atau [HASIL TOOL] — panel hanya menampilkannya, tool hanya
// mengembalikan ringkasan + penanda ARTIFACT:<id> untuk kartu chat.
data class ArtifactMeta(
    val id: String,
    val title: String,
    val type: String,
    val ownerSession: Long,
    val createdTs: Long,
    val updatedTs: Long,
    val versionCount: Int,
    val latestVersion: Int
)

data class ArtifactVersion(
    val artifactId: String,
    val version: Int,
    val content: String,
    val createdTs: Long
)

class ArtifactStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "artifacts.db", null, 1) {

    private val appCtx: Context = ctx.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS artifacts(id TEXT PRIMARY KEY, title TEXT, type TEXT, owner_session INTEGER, created INTEGER, updated INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS artifact_versions(artifact_id TEXT, version INTEGER, content TEXT, created INTEGER, PRIMARY KEY(artifact_id, version))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_artifacts_owner ON artifacts(owner_session, updated DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    companion object {
        @Volatile
        private var instance: ArtifactStore? = null

        fun get(ctx: Context): ArtifactStore {
            return instance ?: synchronized(this) {
                instance ?: ArtifactStore(ctx.applicationContext).also { instance = it }
            }
        }

        val TYPES = setOf("code", "doc", "html", "svg", "app")

        fun normalizeType(raw: String): String {
            val t = raw.trim().lowercase()
            return if (t in TYPES) t else "doc"
        }

        fun typeLabel(type: String): String {
            return when (normalizeType(type)) {
                "code" -> "Kode"
                "html" -> "HTML"
                "svg" -> "SVG"
                "app" -> "Mini-app"
                else -> "Dokumen"
            }
        }

        fun extensionFor(type: String): String {
            return when (normalizeType(type)) {
                "code" -> "txt"
                "html", "app" -> "html"
                "svg" -> "svg"
                else -> "md"
            }
        }

        fun newId(): String {
            val rnd = (0 until 6).map { "0123456789abcdef".random() }.joinToString("")
            return "art-" + System.currentTimeMillis().toString(36) + "-" + rnd
        }
    }

    fun create(title: String, type: String, content: String, ownerSession: Long): ArtifactMeta {
        val db = writableDatabase
        val id = newId()
        val now = System.currentTimeMillis()
        val cleanTitle = title.trim().take(120).ifBlank { "Tanpa judul" }
        val cleanType = normalizeType(type)
        db.beginTransaction()
        try {
            val cv = ContentValues()
            cv.put("id", id)
            cv.put("title", cleanTitle)
            cv.put("type", cleanType)
            cv.put("owner_session", ownerSession)
            cv.put("created", now)
            cv.put("updated", now)
            db.insertOrThrow("artifacts", null, cv)
            val vv = ContentValues()
            vv.put("artifact_id", id)
            vv.put("version", 1)
            vv.put("content", content)
            vv.put("created", now)
            db.insertOrThrow("artifact_versions", null, vv)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ArtifactMeta(id, cleanTitle, cleanType, ownerSession, now, now, 1, 1)
    }

    // Edit = versi baru (versi lama tetap bisa dibuka lewat panel).
    // Mengembalikan meta terbaru, atau null bila id tidak ada.
    fun addVersion(id: String, content: String): ArtifactMeta? {
        val cur = get(id) ?: return null
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val next = cur.latestVersion + 1
        db.beginTransaction()
        try {
            val vv = ContentValues()
            vv.put("artifact_id", id)
            vv.put("version", next)
            vv.put("content", content)
            vv.put("created", now)
            db.insertOrThrow("artifact_versions", null, vv)
            val cv = ContentValues()
            cv.put("updated", now)
            db.update("artifacts", cv, "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return cur.copy(updatedTs = now, versionCount = cur.versionCount + 1, latestVersion = next)
    }

    fun rename(id: String, title: String): Boolean {
        val clean = title.trim().take(120)
        if (clean.isBlank()) return false
        val cv = ContentValues()
        cv.put("title", clean)
        cv.put("updated", System.currentTimeMillis())
        return try { writableDatabase.update("artifacts", cv, "id = ?", arrayOf(id)) > 0 } catch (e: Exception) { false }
    }

    fun get(id: String): ArtifactMeta? {
        return try {
            readableDatabase.rawQuery(
                "SELECT a.id, a.title, a.type, a.owner_session, a.created, a.updated, " +
                    "(SELECT COUNT(*) FROM artifact_versions v WHERE v.artifact_id = a.id), " +
                    "(SELECT MAX(version) FROM artifact_versions v WHERE v.artifact_id = a.id) " +
                    "FROM artifacts a WHERE a.id = ?",
                arrayOf(id)
            ).use { c ->
                if (!c.moveToFirst()) null
                else ArtifactMeta(
                    c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
                    c.getLong(4), c.getLong(5), c.getInt(6), c.getInt(7)
                )
            }
        } catch (e: Exception) { null }
    }

    fun listForSession(ownerSession: Long, limit: Int = 50): List<ArtifactMeta> {
        val out = ArrayList<ArtifactMeta>()
        try {
            readableDatabase.rawQuery(
                "SELECT a.id, a.title, a.type, a.owner_session, a.created, a.updated, " +
                    "(SELECT COUNT(*) FROM artifact_versions v WHERE v.artifact_id = a.id), " +
                    "(SELECT MAX(version) FROM artifact_versions v WHERE v.artifact_id = a.id) " +
                    "FROM artifacts a WHERE a.owner_session = ? ORDER BY a.updated DESC LIMIT ?",
                arrayOf(ownerSession.toString(), limit.coerceIn(1, 200).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ArtifactMeta(
                            c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
                            c.getLong(4), c.getLong(5), c.getInt(6), c.getInt(7)
                        )
                    )
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun getVersion(id: String, version: Int): ArtifactVersion? {
        return try {
            readableDatabase.rawQuery(
                "SELECT artifact_id, version, content, created FROM artifact_versions " +
                    "WHERE artifact_id = ? AND version = ?",
                arrayOf(id, version.toString())
            ).use { c ->
                if (!c.moveToFirst()) null
                else ArtifactVersion(c.getString(0), c.getInt(1), c.getString(2), c.getLong(3))
            }
        } catch (e: Exception) { null }
    }

    fun latestContent(id: String): ArtifactVersion? {
        val meta = get(id) ?: return null
        return getVersion(id, meta.latestVersion)
    }

    fun versions(id: String): List<ArtifactVersion> {
        val out = ArrayList<ArtifactVersion>()
        try {
            readableDatabase.rawQuery(
                "SELECT artifact_id, version, content, created FROM artifact_versions " +
                    "WHERE artifact_id = ? ORDER BY version ASC",
                arrayOf(id)
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(ArtifactVersion(c.getString(0), c.getInt(1), c.getString(2), c.getLong(3)))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    // vc268 HAPUS ARTIFACT: hapus baris artifact + SEMUA baris
    // versinya dalam satu transaksi (skema tanpa FK cascade,
    // jadi kedua DELETE dilakukan manual). Kembalikan jumlah
    // versi yang ikut terhapus, atau -1 bila artifact tidak
    // ditemukan. Hapus bersifat PERMANEN: tanpa arsip/pulihkan
    // (ArtifactStore tidak punya arsip seperti MemoryDb).
    fun delete(id: String): Int {
        val meta = get(id) ?: return -1
        val n = meta.versionCount
        try {
            writableDatabase.beginTransaction()
            try {
                writableDatabase.delete("artifact_versions", "artifact_id = ?", arrayOf(id))
                writableDatabase.delete("artifacts", "id = ?", arrayOf(id))
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        } catch (e: Exception) { return -1 }
        return n
    }

    // Simpan artifact sebagai file ke folder Download (nama tidak pernah
    // menimpa: digeser ke -2, -3, ... mengikuti pola DocGen.uniqueFile).
    fun exportToDownload(meta: ArtifactMeta, version: Int): File? {
        val ver = getVersion(meta.id, version) ?: return null
        return try {
            val dir = File("/sdcard/Download")
            dir.mkdirs()
            val base = DocGen.sanitizeBase(meta.title, "artifact")
            val ext = extensionFor(meta.type)
            val f = DocGen.uniqueFile(dir, base, ext)
            f.writeText(ver.content, Charsets.UTF_8)
            f
        } catch (e: Exception) { null }
    }
}
