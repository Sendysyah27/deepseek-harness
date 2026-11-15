package com.senzwh.deepharness

import android.content.Context
import org.json.JSONArray

/** vc270 — Migrasi sekali jalan: bersihkan tag <ds_safety> dari riwayat
 *  tersimpan (idempotent; flag diset di AKHIR). Ikut preseden RedactMigration
 *  (v4.48.8): hanya baris agen/sistem yang ditulis ulang; ketikan user
 *  dihitung tapi DIBIARKAN utuh; laporan HANYA angka per sumber, tidak pernah
 *  nilai. Dijalankan di Thread dari onCreate.
 *  Cakupan: history (role != user), group_msgs (role != user), oc_tasks.result,
 *  pesan room autoMsgs_<id> + arsip laporanArchive_<id> (sender != user),
 *  coordReports (field result), jobReportUndelivered (field answer).
 *  TIDAK dicakup: facts, file otak, backup zip, deleted_archive, EventDb/log. */
object DsSafetyMigration {
    private const val FLAG = "dsSafetyMigration270"
    private const val REPORT = "dsSafetyMigrationReport"

    fun runOnce(ctx: Context) {
        val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
        if (prefs.getBoolean(FLAG, false)) return
        var hScan = 0
        var hClean = 0
        var hUser = 0
        var gScan = 0
        var gClean = 0
        var gUser = 0
        var oScan = 0
        var oClean = 0
        var rScan = 0
        var rClean = 0
        var rUser = 0
        var cScan = 0
        var cClean = 0
        var uScan = 0
        var uClean = 0
        try {
            val mdb = MemoryDb.get(ctx)
            val c = mdb.readableDatabase.rawQuery("SELECT id, role, text FROM history", null)
            val rows = ArrayList<Triple<Long, String, String>>()
            while (c.moveToNext()) rows.add(Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
            c.close()
            val w = mdb.writableDatabase
            for ((id, role, text) in rows) {
                hScan++
                if (role == "user") { hUser++; continue }
                val t = DsSafetyStrip.strip(text)
                if (t != text) { w.execSQL("UPDATE history SET text=? WHERE id=?", arrayOf<Any?>(t, id)); hClean++ }
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
                gScan++
                if (role == "user") { gUser++; continue }
                val t = DsSafetyStrip.strip(text)
                if (t != text) { w.execSQL("UPDATE group_msgs SET text=? WHERE id=?", arrayOf<Any?>(t, id)); gClean++ }
            }
        } catch (e: Exception) {}
        try {
            val mdb = MemoryDb.get(ctx)
            val c = mdb.readableDatabase.rawQuery("SELECT id, result FROM oc_tasks", null)
            val rows = ArrayList<Pair<Long, String>>()
            while (c.moveToNext()) rows.add(c.getLong(0) to (c.getString(1) ?: ""))
            c.close()
            val w = mdb.writableDatabase
            for ((id, result) in rows) {
                oScan++
                val t = DsSafetyStrip.strip(result)
                if (t != result) { w.execSQL("UPDATE oc_tasks SET result=? WHERE id=?", arrayOf<Any?>(t, id)); oClean++ }
            }
        } catch (e: Exception) {}
        try {
            val rooms = Automation.loadRooms(ctx)
            for (i in 0 until rooms.length()) {
                val rid = rooms.optJSONObject(i)?.optString("id") ?: continue
                if (rid.isBlank()) continue
                for (key in listOf("autoMsgs_" + rid, "laporanArchive_" + rid)) {
                    try {
                        val raw = prefs.getString(key, "[]") ?: "[]"
                        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
                        var changed = false
                        for (j in 0 until arr.length()) {
                            val msg = arr.optJSONObject(j) ?: continue
                            rScan++
                            if (msg.optString("sender") == "user") { rUser++; continue }
                            val text = msg.optString("text")
                            val t = DsSafetyStrip.strip(text)
                            if (t != text) { msg.put("text", t); rClean++; changed = true }
                        }
                        if (changed) prefs.edit().putString(key, arr.toString()).apply()
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
        try {
            val raw = prefs.getString("coordReports", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            var changed = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                cScan++
                val res = o.optString("result")
                val t = DsSafetyStrip.strip(res)
                if (t != res) { o.put("result", t); cClean++; changed = true }
            }
            if (changed) prefs.edit().putString("coordReports", arr.toString()).apply()
        } catch (e: Exception) {}
        try {
            val raw = prefs.getString("jobReportUndelivered", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            var changed = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                uScan++
                val ans = o.optString("answer")
                val t = DsSafetyStrip.strip(ans)
                if (t != ans) { o.put("answer", t); uClean++; changed = true }
            }
            if (changed) prefs.edit().putString("jobReportUndelivered", arr.toString()).apply()
        } catch (e: Exception) {}
        val report = "riwayat: $hClean dibersihkan dari $hScan dipindai, ketikan-user $hUser dibiarkan | grup: $gClean dari $gScan, ketikan-user $gUser dibiarkan | oc_tasks: $oClean dari $oScan | room: $rClean dari $rScan, ketikan-user $rUser dibiarkan | coordReports: $cClean dari $cScan | undelivered: $uClean dari $uScan"
        prefs.edit().putString(REPORT, report).putBoolean(FLAG, true).apply()
    }
}
