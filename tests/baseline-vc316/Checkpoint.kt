package com.senzwh.deepharness

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * J5 (dari bedah Jarves: WorkspaceCheckpoints) — snapshot direktori kerja
 * SEBELUM tugas koordinator jalan, ringkasan perubahan (diff) sesudahnya,
 * lalu user memutuskan: accept (snapshot dibuang, perubahan dipertahankan)
 * atau undo (file dikembalikan ke kondisi sebelum tugas).
 *
 * Batas meniru Jarves: file yang disalin maks 1 MB, maks 400 file, diff
 * diringkas. Snapshot disimpan di filesDir/checkpoints/<taskId>/
 * (salinan file di files/ + manifest.json). Target WAJIB direktori nyata di
 * bawah penyimpanan bersama dan BUKAN akarnya — undo mengosongkan isi
 * target, jadi validasi ini garis keras keamanan.
 */
object Checkpoint {
    private const val MAX_FILE = 1_000_000L
    private const val MAX_FILES = 400
    private const val MAX_DIFF_LINES = 2000

    private fun store(ctx: Context, taskId: Long) = File(ctx.filesDir, "checkpoints/$taskId")
    private fun manifestFile(ctx: Context, taskId: Long) = File(store(ctx, taskId), "manifest.json")

    fun has(ctx: Context, taskId: Long): Boolean = manifestFile(ctx, taskId).exists()

    private fun validTarget(dirPath: String): File? {
        val f = File(dirPath)
        if (!f.isDirectory) return null
        val sd = Environment.getExternalStorageDirectory()
        val canon = try { f.canonicalFile } catch (e: Exception) { return null }
        val sdCanon = try { sd.canonicalFile } catch (e: Exception) { return null }
        if (canon.path == sdCanon.path) return null
        if (!canon.path.startsWith(sdCanon.path + "/")) return null
        return canon
    }

    private fun rel(base: File, f: File): String = f.relativeTo(base).path

    fun snapshot(ctx: Context, taskId: Long, dirPath: String): Boolean {
        val target = validTarget(dirPath) ?: return false
        val st = store(ctx, taskId)
        return try {
            st.deleteRecursively()
            val filesDir = File(st, "files")
            filesDir.mkdirs()
            val entries = JSONArray()
            val skipped = JSONArray()
            val all = ArrayList<File>()
            target.walkTopDown().maxDepth(12).forEach { f -> if (f.isFile) all.add(f) }
            var count = 0
            for (f in all) {
                val r = rel(target, f)
                if (count >= MAX_FILES) { skipped.put("$r (batas jumlah file)"); continue }
                if (f.length() > MAX_FILE) { skipped.put("$r (>${MAX_FILE} B)"); continue }
                val dest = File(filesDir, r)
                dest.parentFile?.mkdirs()
                f.copyTo(dest, overwrite = true)
                entries.put(JSONObject().put("rel", r).put("size", f.length()).put("mtime", f.lastModified()))
                count++
            }
            val man = JSONObject().put("target", target.path).put("files", entries).put("skipped", skipped)
            manifestFile(ctx, taskId).writeText(man.toString())
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Ringkasan perubahan sejak snapshot; null bila tidak ada perubahan. */
    fun diff(ctx: Context, taskId: Long): String? {
        val man = readManifest(ctx, taskId) ?: return null
        val targetPath = man.optString("target")
        val target = File(targetPath)
        if (!target.isDirectory) return "📸 Checkpoint: direktori target ($targetPath) hilang setelah tugas jalan."
        val before = HashMap<String, JSONObject>()
        val arr = man.optJSONArray("files") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            before[o.optString("rel")] = o
        }
        val now = HashMap<String, File>()
        target.walkTopDown().maxDepth(12).forEach { f -> if (f.isFile) now[rel(target, f)] = f }
        val added = now.keys - before.keys
        val deleted = before.keys - now.keys
        val changed = ArrayList<String>()
        var plus = 0
        var minus = 0
        for ((r, f) in now) {
            val b = before[r] ?: continue
            if (b.optLong("size") != f.length() || b.optLong("mtime") != f.lastModified()) {
                changed.add(r)
                if (f.length() <= MAX_FILE && isText(f)) {
                    val (p, m) = lineDelta(File(store(ctx, taskId), "files/$r"), f)
                    plus += p; minus += m
                }
            }
        }
        if (added.isEmpty() && deleted.isEmpty() && changed.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("📸 Checkpoint ${target.name}: ${changed.size} berubah, ${added.size} baru, ${deleted.size} terhapus")
        if (plus + minus > 0) sb.append(" (+$plus/−$minus baris)")
        if (plus + minus > MAX_DIFF_LINES) sb.append(" (diff diringkas)")
        sb.append(".")
        val detail = ArrayList<String>()
        for (r in changed.take(12)) detail.add("~ $r")
        for (r in added.take(8)) detail.add("+ $r")
        for (r in deleted.take(8)) detail.add("− $r")
        if (detail.isNotEmpty()) sb.append("\n").append(detail.joinToString("\n"))
        val skipped = man.optJSONArray("skipped")
        if (skipped != null && skipped.length() > 0) {
            sb.append("\n(${skipped.length()} file tidak disalin saat snapshot — terlalu besar/berlebih; undo tidak memulihkan file itu)")
        }
        return sb.toString()
    }

    /** Pulihkan target ke kondisi snapshot, lalu buang snapshot. */
    fun restore(ctx: Context, taskId: Long): String {
        val man = readManifest(ctx, taskId) ?: return "Tidak ada checkpoint aktif untuk tugas #$taskId."
        val target = validTarget(man.optString("target"))
            ?: return "Direktori target checkpoint tidak valid/hilang — undo dibatalkan demi keamanan."
        // vc261 N-m26: verifikasi snapshot lengkap SEBELUM menghapus
        // target — bila ada file snapshot hilang/rusak, undo DIBATALKAN
        // dan target tidak disentuh (dulu: target dihapus dulu, gagal di
        // tengah salin = target kosong/setengah).
        val filesDir0 = File(store(ctx, taskId), "files")
        val entries0 = man.optJSONArray("files") ?: JSONArray()
        for (i in 0 until entries0.length()) {
            val o = entries0.optJSONObject(i) ?: return "Checkpoint #$taskId tidak lengkap (manifest rusak) — undo dibatalkan, target tidak diubah."
            val snap = File(filesDir0, o.optString("rel"))
            if (!snap.isFile || snap.length() != o.optLong("size")) return "Checkpoint #$taskId tidak lengkap (snapshot '${o.optString("rel")}' hilang/rusak) — undo dibatalkan, target tidak diubah. Coba restore manual dari filesDir/checkpoints/$taskId/files/."
        }
        return try {
            target.listFiles()?.forEach { it.deleteRecursively() }
            val filesDir = File(store(ctx, taskId), "files")
            if (filesDir.isDirectory) {
                filesDir.walkTopDown().forEach { f ->
                    if (f.isFile) {
                        val dest = File(target, f.relativeTo(filesDir).path)
                        dest.parentFile?.mkdirs()
                        f.copyTo(dest, overwrite = true)
                    }
                }
            }
            discard(ctx, taskId)
            "↩ Checkpoint tugas #$taskId DIURUNGKAN — ${target.path} dikembalikan ke kondisi sebelum tugas jalan."
        } catch (e: Exception) {
            "❌ Undo checkpoint #$taskId gagal: ${e.message}"
        }
    }

    /** Buang snapshot (accept). true bila memang ada snapshot. */
    fun discard(ctx: Context, taskId: Long): Boolean {
        val had = has(ctx, taskId)
        try { store(ctx, taskId).deleteRecursively() } catch (e: Exception) {}
        return had
    }

    private fun readManifest(ctx: Context, taskId: Long): JSONObject? {
        val mf = manifestFile(ctx, taskId)
        if (!mf.exists()) return null
        return try { JSONObject(mf.readText()) } catch (e: Exception) { null }
    }

    private fun isText(f: File): Boolean {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        return ext !in setOf("png", "jpg", "jpeg", "gif", "zip", "apk", "so", "o", "class", "dex", "mp3", "mp4", "webp", "ttf", "otf", "jar")
    }

    /** Selisih baris multiset antara dua file teks (kasar tapi jujur: hitung ±). */
    private fun lineDelta(a: File, b: File): Pair<Int, Int> {
        return try {
            if (!a.isFile) return Pair(0, 0)
            val ca = HashMap<String, Int>()
            a.bufferedReader().forEachLine { ca[it] = (ca[it] ?: 0) + 1 }
            val cb = HashMap<String, Int>()
            b.bufferedReader().forEachLine { cb[it] = (cb[it] ?: 0) + 1 }
            var p = 0
            var m = 0
            for ((k, v) in cb) { val d = v - (ca[k] ?: 0); if (d > 0) p += d }
            for ((k, v) in ca) { val d = v - (cb[k] ?: 0); if (d > 0) m += d }
            Pair(p, m)
        } catch (e: Exception) {
            Pair(0, 0)
        }
    }
}
