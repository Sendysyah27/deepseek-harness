package com.senzwh.deepharness

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mesin perintah lokal (mode offline darurat): pola bahasa Indonesia sederhana yang
 *  dieksekusi TANPA internet/DeepSeek — pengingat, fakta, baterai, notif, keuangan,
 *  lokasi, buka app, jam. Mengembalikan teks jawaban bila perintah dipahami, null bila tidak. */
object LocalAgent {

    fun tryHandle(ctx: Context, raw: String): String? {
        val text = raw.trim()
        val low = text.lowercase()
        if (low.isBlank()) return null
        return try {
            when {
                low.contains("jam berapa") || low == "jam" ->
                    "Sekarang jam " + SimpleDateFormat("HH:mm", Locale("id", "ID")).format(Date()) +
                        ", " + SimpleDateFormat("EEEE d MMMM yyyy", Locale("id", "ID")).format(Date()) + "."

                low.contains("baterai") ->
                    Tools.execute(ctx, "get_battery", JSONObject())

                low.contains("notif") && (low.contains("apa") || low.contains("terbaru") || low.contains("cek")) ->
                    Tools.execute(ctx, "get_notifications", JSONObject())

                low.contains("keuangan") || low.contains("pengeluaran") || low.contains("pemasukan") ->
                    Tools.execute(ctx, "finance_summary", JSONObject().put("days", 7))

                low.contains("lokasi") || low.contains("saya di mana") || low.contains("aku di mana") ->
                    Tools.execute(ctx, "get_location", JSONObject())

                low.startsWith("ingetin") || low.startsWith("ingatkan") || low.startsWith("pengingat") ->
                    parseReminder(ctx, text, low)

                low.startsWith("ingat ") || low.startsWith("catat ") ->
                    parseFact(ctx, text)

                low.startsWith("buka ") ->
                    parseOpenApp(ctx, text, low)

                low.contains("pengingat apa") || low.contains("pengingat aktif") ->
                    Tools.execute(ctx, "list_reminders", JSONObject())

                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun parseReminder(ctx: Context, text: String, low: String): String? {
        val m = Regex("(\\d+)\\s*(menit|jam)").find(low) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val ms = if (m.groupValues[2] == "jam") n * 3600_000 else n * 60_000
        var label = text.substring(m.range.last + 1).trim()
        label = label.removePrefix("lagi").trim()
            .removePrefix("buat").trim()
            .removePrefix("untuk").trim()
        if (label.isBlank()) label = "pengingat"
        val id = MemoryDb.get(ctx).addReminder(System.currentTimeMillis() + ms, label)
        if (id < 0) return null
        return "Siap — pengingat '$label' dipasang $n ${m.groupValues[2]} dari sekarang (mode offline, tersimpan lokal)."
    }

    private fun parseFact(ctx: Context, text: String): String? {
        var body = text
        for (p in listOf("ingat bahwa ", "ingat ", "catat bahwa ", "catat ")) {
            if (body.lowercase().startsWith(p)) { body = body.substring(p.length); break }
        }
        body = body.trim()
        if (body.length < 4) return null
        val key = body.split(Regex("\\s+")).take(3).joinToString(" ").lowercase()
        MemoryDb.get(ctx).putFact(key, body, source = "user:chat")
        return "Tercatat di memori lokal: $body"
    }

    private fun parseOpenApp(ctx: Context, text: String, low: String): String? {
        val query = low.removePrefix("buka aplikasi ").removePrefix("buka app ").removePrefix("buka ").trim()
        if (query.isBlank()) return null
        val apps = Tools.execute(ctx, "list_apps", JSONObject())
        for (line in apps.lines()) {
            // format list_apps: "- <nama> (<paket>)" — cocokkan nama mengandung query
            val ln = line.lowercase()
            if (ln.contains(query)) {
                val pkg = Regex("\\(([a-z0-9_.]+)\\)").find(line)?.groupValues?.get(1) ?: continue
                return Tools.execute(ctx, "open_app", JSONObject().put("package", pkg))
            }
        }
        return "App '$query' tidak ditemukan di daftar aplikasi (mode offline)."
    }
}
