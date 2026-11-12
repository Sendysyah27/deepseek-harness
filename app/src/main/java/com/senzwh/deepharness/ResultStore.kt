package com.senzwh.deepharness

import android.content.Context
import org.json.JSONObject
import java.io.File

/** v4.48.8 — Result store: output tool UTUH (mentah, pra-cap) di filesDir privat.
 *  Redaksi TIDAK di sini — redaksi terjadi saat disajikan (live/get_result/
 *  grep_result), supaya satu pola berlaku konsisten. ID "R<n>" dari counter
 *  prefs, monoton, TIDAK pernah dipakai ulang (termasuk sesudah wipe). Total
 *  cap 10 MB (LRU, item yang baru ditambah tidak pernah dieviksi), TTL default
 *  48 jam (pref resultTtlHours), per-hasil cap 400.000 char (lebih dari itu
 *  disimpan terpotong + flag truncated, total = panjang ASLI). TIDAK ikut
 *  BrainBackup (backup hanya zip otak-dirs + settings.json).
 *  4.48.9: logika yang sama dijalankan lewat Backend (direktori + prefs +
 *  kunci counter) — produksi memakai backend default (perilaku tak berubah),
 *  Uji Mandiri memakai backend uji (direktori + prefs terpisah) lewat
 *  putIn/getIn/testBackend/wipeBackend. API publik put/get/wipe tak berubah. */
object ResultStore {
    private const val PREFS = "harness"
    private const val SEQ_KEY = "resultSeq"
    private const val MAX_TOTAL_BYTES = 10L * 1024 * 1024
    private const val MAX_CHARS = 400000

    data class Entry(val id: String, val tool: String, val ts: Long, val total: Int, val truncated: Boolean, val text: String)

    private val lock = Any()

    /** Backend penyimpanan. HANYA Uji Mandiri yang memakai backend uji;
     *  semua jalur produksi memakai prodBackend (identik dengan perilaku lama). */
    class Backend(val dir: File, val prefs: android.content.SharedPreferences, val seqKey: String)

    private fun prodBackend(ctx: Context): Backend {
        val d = File(ctx.filesDir, "result-store").apply { mkdirs() }
        return Backend(d, ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE), SEQ_KEY)
    }

    /** Backend uji Uji Mandiri: direktori filesDir/test-store-<stamp>/ +
     *  prefs "ujiMandiri" (counter "ujiResultSeq") — terpisah dari produksi. */
    fun testBackend(ctx: Context, stamp: String): Backend {
        val d = File(ctx.filesDir, "test-store-$stamp").apply { mkdirs() }
        return Backend(d, ctx.getSharedPreferences("ujiMandiri", Context.MODE_PRIVATE), "ujiResultSeq")
    }

    private fun indexFile(b: Backend): File = File(b.dir, "index.json")
    private fun dataFile(b: Backend, id: String): File = File(b.dir, "$id.txt")

    private fun readIndex(b: Backend): JSONObject {
        return try { JSONObject(indexFile(b).readText(Charsets.UTF_8)) } catch (e: Exception) { JSONObject() }
    }

    private fun writeIndex(b: Backend, idx: JSONObject) {
        try { indexFile(b).writeText(idx.toString(), Charsets.UTF_8) } catch (e: Exception) {}
    }

    private fun ttlMs(b: Backend): Long {
        val h = try { b.prefs.getInt("resultTtlHours", 48) } catch (e: Exception) { 48 }
        return (if (h <= 0) 48 else h).toLong() * 60 * 60 * 1000
    }

    private fun sweep(b: Backend, idx: JSONObject, now: Long, keepId: String? = null) {
        val ttl = ttlMs(b)
        val keys = ArrayList<String>()
        val it = idx.keys()
        while (it.hasNext()) keys.add(it.next())
        for (k in keys) {
            val o = try { idx.getJSONObject(k) } catch (e: Exception) { null } ?: continue
            if (now - o.optLong("ts", 0) > ttl && k != keepId) {
                idx.remove(k)
                try { dataFile(b, k).delete() } catch (e: Exception) {}
            }
        }
    }

    private fun enforceTotal(b: Backend, idx: JSONObject, keepId: String) {
        var total = 0L
        val items = ArrayList<Pair<String, Long>>()
        val it = idx.keys()
        while (it.hasNext()) {
            val k = it.next()
            val o = try { idx.getJSONObject(k) } catch (e: Exception) { null } ?: continue
            val sz = try { dataFile(b, k).length() } catch (e: Exception) { 0L }
            total += sz
            if (k != keepId) items.add(k to o.optLong("ts", 0))
        }
        items.sortBy { it.second }
        var i = 0
        while (total > MAX_TOTAL_BYTES && i < items.size) {
            val k = items[i].first
            val sz = try { dataFile(b, k).length() } catch (e: Exception) { 0L }
            idx.remove(k)
            try { dataFile(b, k).delete() } catch (e: Exception) {}
            total -= sz
            i++
        }
    }

    fun put(ctx: Context, tool: String, text: String): String = putIn(prodBackend(ctx), tool, text)

    fun putIn(b: Backend, tool: String, text: String): String {
        if (text.isBlank()) return ""
        return try {
            synchronized(lock) {
                val seq = b.prefs.getInt(b.seqKey, 0) + 1
                b.prefs.edit().putInt(b.seqKey, seq).apply()
                val id = "R$seq"
                val truncated = text.length > MAX_CHARS
                val stored = if (truncated) text.take(MAX_CHARS) else text
                dataFile(b, id).writeText(stored, Charsets.UTF_8)
                val idx = readIndex(b)
                val now = System.currentTimeMillis()
                sweep(b, idx, now, id)
                idx.put(id, JSONObject().put("tool", tool).put("ts", now).put("total", text.length).put("truncated", truncated))
                enforceTotal(b, idx, id)
                writeIndex(b, idx)
                id
            }
        } catch (e: Exception) { "" }
    }

    fun get(ctx: Context, id: String): Entry? = getIn(prodBackend(ctx), id)

    fun getIn(b: Backend, id: String): Entry? {
        if (id.isBlank()) return null
        return try {
            synchronized(lock) {
                val idx = readIndex(b)
                val o = try { idx.getJSONObject(id) } catch (e: Exception) { return null }
                val now = System.currentTimeMillis()
                if (now - o.optLong("ts", 0) > ttlMs(b)) {
                    idx.remove(id); writeIndex(b, idx)
                    try { dataFile(b, id).delete() } catch (e: Exception) {}
                    return null
                }
                val f = dataFile(b, id)
                if (!f.exists()) { idx.remove(id); writeIndex(b, idx); return null }
                Entry(id, o.optString("tool", ""), o.optLong("ts", 0), o.optInt("total", 0), o.optBoolean("truncated", false), f.readText(Charsets.UTF_8))
            }
        } catch (e: Exception) { null }
    }

    fun wipe(ctx: Context) {
        try {
            synchronized(lock) {
                val d = File(ctx.filesDir, "result-store")
                if (d.exists()) d.deleteRecursively()
            }
        } catch (e: Exception) {}
    }

    /** Pembersihan backend uji (direktori + prefs uji) — dipakai blok finally
     *  Uji Mandiri, termasuk bila ada cek FAIL. */
    fun wipeBackend(b: Backend) {
        try {
            synchronized(lock) {
                if (b.dir.exists()) b.dir.deleteRecursively()
                try { b.prefs.edit().clear().apply() } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }
}
