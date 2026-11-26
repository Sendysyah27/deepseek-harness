package com.senzwh.deepharness

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** v4.9.0 — WORLD STATE: potret keadaan hidup yang ditempel di akhir
 *  system prompt (dipakai chat utama, Otak Latar, dan ruang tim): waktu,
 *  baterai, akun aktif, pemakaian kuota gubernur hari ini, tujuan aktif +
 *  langkah berikutnya, dan misi tim terakhir. Dibangun murah dari prefs +
 *  query ringan; semua bagian di-guard agar tak pernah menggagalkan prompt. */
object WorldState {
    // vc240 M16: slim = true membuang tanggal/waktu, baterai, dan goal
    // teratas dari WorldState — ketiganya sudah ada di blok khusus prompt
    // awal 1-1 ([KONTEKS SISTEM] untuk tanggal/baterai, TUJUAN AKTIF
    // untuk goal). Blok yang dipertahankan di WorldState slim: akun,
    // kuota, misi tim. Jalur lain (room, grup, latar) tetap memakai
    // WorldState penuh karena tidak punya blok khusus tersebut.
    fun line(ctx: Context, slimVc240: Boolean = false, dropGoalTopVc241: Boolean = false): String {
        val parts = mutableListOf<String>()
        if (!slimVc240) try { parts.add(SimpleDateFormat("EEEE, dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date())) } catch (e: Exception) {}
        if (!slimVc240) try {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (level >= 0) {
                    val pct = level * 100 / maxOf(scale, 1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    parts.add("baterai $pct%" + if (charging) " (mengisi)" else "")
                }
            }
        } catch (e: Exception) {}
        try { parts.add("panggilan AI hari ini: " + Governor.count(ctx) + "/" + Governor.cap(ctx)) } catch (e: Exception) {}
        // vc241: goal-teratas dibuang bila pemanggil sudah punya blok TUJUAN AKTIF sendiri (grup vc240 M18, latar) — baterai/tanggal tidak ikut dibuang di mode ini.
        if (!slimVc240 && !dropGoalTopVc241) try {
            val mdb = MemoryDb.get(ctx)
            val gs = mdb.goals(true)
            if (gs.isNotEmpty()) {
                val g = gs.first()
                val next = mdb.goalSteps(g.id).firstOrNull { !it.done }
                parts.add("tujuan aktif: ${gs.size} (teratas '${g.title}'" + (if (next != null) ", langkah berikutnya: ${next.text.take(60)}" else "") + ")")
            }
        } catch (e: Exception) {}
        try {
            val tb = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getString("teamBoard", "") ?: ""
            if (tb.isNotBlank()) {
                val o = org.json.JSONObject(tb)
                parts.add("misi tim terakhir: '" + o.optString("goal").take(50) + "' (" + o.optString("status") + ")")
            }
        } catch (e: Exception) {}
        if (parts.isEmpty()) return ""
        return "\n\n[KEADAAN SEKARANG] " + parts.joinToString(" • ")
    }
}
