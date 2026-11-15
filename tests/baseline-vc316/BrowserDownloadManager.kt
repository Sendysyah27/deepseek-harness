package com.senzwh.deepharness

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * vc313 Worker B — antrean + status unduhan browser agen (Spek §3.6).
 *
 * Dipakai MainActivity.ensureBrowserTab lewat DownloadListener tiap tab agen.
 * TANPA batas jenis file (prinsip #1): semua MIME/ekstensi diizinkan.
 * File TIDAK PERNAH dieksekusi otomatis — hasil unduhan hanya tercatat di
 * browser_downloads dan dibaca/dianalisa lewat tool file yang sudah ada
 * (membuka APK hasil unduhan tetap lewat user, bukan agen).
 *
 * Butuh Context (DownloadManager system service): MainActivity memanggil
 * init(this) sebelum DownloadListener pertama dipasang. list() memakai
 * DownloadManager.Query untuk status live; bila context belum ada, list()
 * mengembalikan status yang tercatat saat enqueue.
 */
object BrowserDownloadManager {

    data class DownloadItem(val name: String, val sizeBytes: Long, val status: String, val path: String)

    private data class Record(val name: String, val sizeBytes: Long, val path: String, val downloadId: Long)

    private val records = mutableListOf<Record>()

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** Folder unduhan browser agen (K-04 default spek): /sdcard/Download/Harness-Browser/ */
    fun downloadDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Harness-Browser")

    /**
     * Nama file dari Content-Disposition lalu URL (pure, JVM-testable).
     * Urutan: filename*=UTF-8''... (RFC 5987) > filename="..." > filename=...
     * > segmen terakhir path URL > "unduhan". Karakter ilegal file diganti '_'.
     */
    fun parseFileName(contentDisposition: String?, url: String): String {
        val cd = contentDisposition ?: ""
        // RFC 5987: filename*=UTF-8''nama%20file.ext (atau charset lain).
        val star = Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(cd)
        if (star != null) {
            val decoded = try {
                java.net.URLDecoder.decode(star.groupValues[1].trim(), "UTF-8")
            } catch (e: Exception) {
                star.groupValues[1].trim()
            }
            val clean = sanitizeFileName(decoded)
            if (clean.isNotBlank()) return clean
        }
        val quoted = Regex("filename\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(cd)
        if (quoted != null) {
            val clean = sanitizeFileName(quoted.groupValues[1].trim())
            if (clean.isNotBlank()) return clean
        }
        val bare = Regex("filename\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE).find(cd)
        if (bare != null) {
            val clean = sanitizeFileName(bare.groupValues[1].trim())
            if (clean.isNotBlank()) return clean
        }
        // Fallback URL: segmen terakhir path (parsing manual murni-Java —
        // JANGAN android.net.Uri: stub di JVM sehingga helper ini tetap
        // bisa dites di JVM tanpa perangkat).
        val seg = try {
            val noQuery = url.substringBefore('?').substringBefore('#')
            if (noQuery.endsWith("/")) "" else noQuery.substringAfterLast('/')
        } catch (e: Exception) {
            ""
        }
        val decodedSeg = try {
            java.net.URLDecoder.decode(seg, "UTF-8")
        } catch (e: Exception) {
            seg
        }
        val cleanSeg = sanitizeFileName(decodedSeg.trim())
        return if (cleanSeg.isNotBlank()) cleanSeg else "unduhan"
    }

    private fun sanitizeFileName(name: String): String {
        var out = name.trim().trim('"')
        out = out.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        // Jangan biarkan nama "." / ".." / kosong lolos.
        if (out.isBlank() || out == "." || out == "..") return ""
        return out
    }

    /**
     * Nama unik anti-timpa (pure, JVM-testable): bila `wanted` sudah ada di
     * `existing`, tambah " (n)" sebelum ekstensi. Bila wanted sudah berangka
     * ("a (1).txt"), angka itu dinaikkan ("a (2).txt"), bukan ditumpuk.
     */
    fun uniqueFileName(existing: Set<String>, wanted: String): String {
        if (wanted !in existing) return wanted
        val dot = wanted.lastIndexOf('.')
        val (base, ext) = if (dot > 0) wanted.substring(0, dot) to wanted.substring(dot) else wanted to ""
        val stemMatch = Regex("^(.*) \\((\\d+)\\)$").find(base)
        val stem = stemMatch?.groupValues?.get(1) ?: base
        var n = stemMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate !in existing) return candidate
            n++
        }
    }

    /**
     * Antrekan unduhan via android.app.DownloadManager. Notifikasi selesai
     * aktif (VISIBILITY_VISIBLE_NOTIFY_COMPLETED) supaya user melihatnya.
     * Bentrok nama -> tambah angka (tidak pernah menimpa diam-diam).
     */
    fun enqueue(url: String, contentDisposition: String?, mimetype: String?, contentLength: Long): DownloadItem {
        val ctx = appContext
            ?: throw IllegalStateException("BrowserDownloadManager belum di-init (MainActivity memanggil init).")
        val dir = downloadDir()
        if (!dir.exists()) dir.mkdirs()
        val wanted = parseFileName(contentDisposition, url)
        val onDisk = try {
            dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        val known = synchronized(records) { records.map { it.name }.toSet() } + onDisk
        val unique = uniqueFileName(known, wanted)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(unique)
            setDescription("Unduhan browser agen Harness")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "Harness-Browser/$unique"
            )
            if (!mimetype.isNullOrBlank()) setMimeType(mimetype)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        val path = File(dir, unique).absolutePath
        val item = DownloadItem(unique, contentLength, "menunggu", path)
        synchronized(records) { records.add(Record(unique, contentLength, path, id)) }
        return item
    }

    /** Daftar unduhan sesi ini + status live dari DownloadManager.Query. */
    fun list(): List<DownloadItem> {
        val snapshot: List<Record> = synchronized(records) { records.toList() }
        if (snapshot.isEmpty()) return emptyList()
        val ctx = appContext
        if (ctx == null) return snapshot.map { DownloadItem(it.name, it.sizeBytes, it.statusFallback(), it.path) }
        val dm = try {
            ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        } catch (e: Exception) {
            return snapshot.map { DownloadItem(it.name, it.sizeBytes, it.statusFallback(), it.path) }
        }
        return snapshot.map { rec ->
            var status = rec.statusFallback()
            var size = rec.sizeBytes
            try {
                val q = DownloadManager.Query().setFilterById(rec.downloadId)
                dm.query(q)?.use { c ->
                    if (c.moveToFirst()) {
                        val stIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val szIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        if (stIdx >= 0) {
                            status = when (c.getInt(stIdx)) {
                                DownloadManager.STATUS_SUCCESSFUL -> "selesai"
                                DownloadManager.STATUS_RUNNING -> "mengunduh"
                                DownloadManager.STATUS_PAUSED -> "jeda"
                                DownloadManager.STATUS_PENDING -> "menunggu"
                                DownloadManager.STATUS_FAILED -> "gagal"
                                else -> status
                            }
                        }
                        if (szIdx >= 0) {
                            val total = c.getLong(szIdx)
                            if (total > 0) size = total
                        }
                    }
                }
            } catch (e: Exception) {
                // Status live gagal dibaca — pakai status tercatat.
            }
            DownloadItem(rec.name, size, status, rec.path)
        }
    }

    private fun Record.statusFallback(): String = "menunggu"
}
