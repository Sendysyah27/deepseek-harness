package com.senzwh.deepharness

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** v4.48.8 — Migrasi redaksi sekali jalan (idempotent: redaksi/dedup/strip
 *  semuanya idempotent, jadi crash lalu jalan ulang aman; flag diset di AKHIR).
 *  Pindai: fakta DB, brain.md, riwayat, grup_msgs, events, backup zip, dump lama.
 *  Tulis ulang HANYA baris agen/sistem; ketikan user dihitung tapi DIBIARKAN
 *  utuh (sesuai scope: riwayat ketikan user tidak diredaksi). Laporan HANYA
 *  angka per sumber, tidak pernah nilai. Dijalankan di Thread dari onCreate. */
object RedactMigration {
    private const val FLAG = "redactMigration4488"
    private const val REPORT = "redactMigrationReport"

    private fun dedupKeepLast(text: String): String {
        val lines = text.lines()
        val lastIdx = HashMap<String, Int>()
        for (i in lines.indices) {
            val ln = lines[i]
            if (ln.isNotBlank() && !ln.startsWith("#") && ln.length >= 8) lastIdx[ln] = i
        }
        val out = ArrayList<String>()
        for (i in lines.indices) {
            val ln = lines[i]
            if (ln.isNotBlank() && !ln.startsWith("#") && ln.length >= 8 && lastIdx[ln] != i) continue
            out.add(ln)
        }
        return out.joinToString("\n")
    }

    fun runOnce(ctx: Context) {
        val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
        if (prefs.getBoolean(FLAG, false)) return
        var fFacts = 0
        var fBrain = 0
        var hAgent = 0
        var hUser = 0
        var gAgent = 0
        var gUser = 0
        var ev = 0
        var bak = 0
        var dumpDel = false
        val persona = try { prefs.getString("persona", "")?.trim().orEmpty() } catch (e: Exception) { "" }
        try {
            val db = MemoryDb.get(ctx)
            // vc261 N-M19: pool PENUH (factsAllWithStatus) + status
            // confirmed asal dipertahankan (dulu: hanya 40 terbaru +
            // selalu dikonfirmasi ulang).
            for ((k, v, confirmed) in db.factsAllWithStatus()) {
                val n = RedactSecrets.countMatches(v)
                if (n > 0) { db.putFact(k, RedactSecrets.redact(v), confirmed, "system:vc285"); fFacts += n }
            }
        } catch (e: Exception) {}
        try {
            ctx.filesDir.listFiles()?.forEach { d ->
                if (d.isDirectory && (d.name == "otak" || d.name.startsWith("otak-"))) {
                    d.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.forEach { f ->
                        try {
                            val orig = f.readText(Charsets.UTF_8)
                            var t = orig
                            if (f.name == "SOUL.md" && d.name == "otak") t = AgentBrain.stripLegacyPersonaText(t, persona)
                            if (f.name == "MEMORY.md") t = dedupKeepLast(t)
                            val n = RedactSecrets.countMatches(t)
                            if (n > 0) t = RedactSecrets.redact(t)
                            if (t != orig) { f.writeText(t, Charsets.UTF_8); fBrain += n }
                        } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {}
        try {
            val mdb = MemoryDb.get(ctx)
            val c = mdb.readableDatabase.rawQuery("SELECT id, role, text FROM history", null)
            val rows = ArrayList<Triple<Long, String, String>>()
            while (c.moveToNext()) rows.add(Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
            c.close()
            val w = mdb.writableDatabase
            for ((id, role, text) in rows) {
                val n = RedactSecrets.countMatches(text)
                if (n == 0) continue
                if (role == "user") hUser += n
                else { w.execSQL("UPDATE history SET text=? WHERE id=?", arrayOf<Any?>(RedactSecrets.redact(text), id)); hAgent += n }
            }
        } catch (e: Exception) {}
        try {
            val mdb = MemoryDb.get(ctx)
            try { mdb.groupMessages(0) } catch (e: Exception) {}
            val c = mdb.readableDatabase.rawQuery("SELECT id, role, text FROM group_msgs", null)
            val rows = ArrayList<Triple<Long, String, String>>()
            while (c.moveToNext()) rows.add(Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
            c.close()
            val w = mdb.writableDatabase
            for ((id, role, text) in rows) {
                val n = RedactSecrets.countMatches(text)
                if (n == 0) continue
                if (role == "user") gUser += n
                else { w.execSQL("UPDATE group_msgs SET text=? WHERE id=?", arrayOf<Any?>(RedactSecrets.redact(text), id)); gAgent += n }
            }
        } catch (e: Exception) {}
        try {
            val edb = EventDb.get(ctx)
            val c = edb.readableDatabase.rawQuery("SELECT id, text FROM events", null)
            val rows = ArrayList<Pair<Long, String>>()
            while (c.moveToNext()) rows.add(c.getLong(0) to (c.getString(1) ?: ""))
            c.close()
            val w = edb.writableDatabase
            for ((id, text) in rows) {
                val n = RedactSecrets.countMatches(text)
                if (n > 0) { w.execSQL("UPDATE events SET text=? WHERE id=?", arrayOf<Any?>(RedactSecrets.redact(text), id)); ev += n }
            }
        } catch (e: Exception) {}
        try {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dl.listFiles()?.forEach { zf ->
                if (!zf.isFile || !((zf.name.startsWith("HarnessBackup-") && zf.name.endsWith(".zip")) || zf.name == "harness-restore.zip")) return@forEach
                try {
                    val zin = ZipFile(zf)
                    var changed = false
                    val tmp = File(zf.parent, zf.name + ".tmp4488")
                    val zos = ZipOutputStream(FileOutputStream(tmp))
                    val en = zin.entries()
                    while (en.hasMoreElements()) {
                        val e = en.nextElement()
                        zos.putNextEntry(ZipEntry(e.name))
                        if (!e.isDirectory && (e.name.endsWith(".md") || e.name == "settings.json")) {
                            val txt = zin.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                            val n = RedactSecrets.countMatches(txt)
                            if (n > 0) { zos.write(RedactSecrets.redact(txt).toByteArray(Charsets.UTF_8)); changed = true; bak += n }
                            else zos.write(txt.toByteArray(Charsets.UTF_8))
                        } else if (!e.isDirectory) {
                            zin.getInputStream(e).use { it.copyTo(zos) }
                        }
                        zos.closeEntry()
                    }
                    zos.close(); zin.close()
                    if (changed) { zf.delete(); tmp.renameTo(zf) } else tmp.delete()
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        try { if (File("/sdcard/Download/harness-prompt-last.txt").delete()) dumpDel = true } catch (e: Exception) {}
        val report = "fakta:$fFacts teredaksi | otak:$fBrain | riwayat: agen $hAgent teredaksi, ketikan-user $hUser ditemukan (dibiarkan) | grup: agen $gAgent teredaksi, ketikan-user $gUser ditemukan (dibiarkan) | events:$ev | backup:$bak | dump-lama:" + (if (dumpDel) "dihapus" else "tidak-ada")
        prefs.edit().putString(REPORT, report).putBoolean(FLAG, true).apply()
    }
}
