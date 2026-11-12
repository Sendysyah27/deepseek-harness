package com.senzwh.deepharness

import android.content.Context
import org.json.JSONArray

/**
 * vc245 GELOMBANG 4 (XL-Rencana, pilihan fungsi baca-tulis bersama):
 * SATU fungsi baca-tulis rencana untuk jalur 1-1 (kartu Rencana +
 * persistPlan/restorePlan + Beranda) dan jalur tool (make_plan /
 * update_plan), berkunci per sesi: planJson_<sessionId>. Baca memakai
 * fallback kunci global lama (planJson) lalu kunci tool lama
 * (planJson_tools) supaya rencana lama tidak hilang mendadak; tulis
 * SELALU ke kunci sesi, jadi tulis pertama memigrasikan rencana lama
 * ke kunci sesi itu. PENYEMPITAN YANG DISENGAJA: planJson_group TIDAK
 * digabung — skemanya berbeda (daftar objek GroupPlan title/body/ts
 * untuk UI grup, bukan daftar langkah "☐ ..." 1-1) dan sudah tampil
 * di UI grup sendiri; menggabungkannya akan merusak kedua sisi.
 * Jalur tool memakai sesi 1-1 AKTIF saat tool dipanggil (satu-satunya
 * sesi yang diketahui Tools.execute tanpa mengubah signature-nya);
 * itu dicatat eksplisit di pesan hasil make_plan.
 */
object PlanStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    fun key(sessionId: Long): String = "planJson_" + sessionId

    fun currentSession(ctx: Context): Long = try { prefs(ctx).getLong("currentSession", 1L) } catch (e: Exception) { 1L }

    fun read(ctx: Context, sessionId: Long): JSONArray {
        return try {
            var raw = prefs(ctx).getString(key(sessionId), "") ?: ""
            if (raw.isBlank()) raw = prefs(ctx).getString("planJson", "") ?: ""
            if (raw.isBlank()) raw = prefs(ctx).getString("planJson_tools", "") ?: ""
            if (raw.isBlank()) JSONArray() else JSONArray(raw)
        } catch (e: Exception) { JSONArray() }
    }

    fun readCurrent(ctx: Context): JSONArray = read(ctx, currentSession(ctx))

    fun write(ctx: Context, sessionId: Long, arr: JSONArray) {
        try { prefs(ctx).edit().putString(key(sessionId), arr.toString()).apply() } catch (e: Exception) {}
    }

    fun writeCurrent(ctx: Context, arr: JSONArray) = write(ctx, currentSession(ctx), arr)
}
