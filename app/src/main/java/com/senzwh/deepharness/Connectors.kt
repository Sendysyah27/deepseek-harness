package com.senzwh.deepharness

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.senzwh.deepharness.priv.PrivServer
import org.json.JSONArray
import org.json.JSONObject

/** v4.18.0 — Sistem Konektor (adaptasi Connectors ala Muse atas
 *  permintaan user): registri layanan yang memberi kemampuan ke agen.
 *  Panel Konektor di Settings menampilkan status jujur tiap konektor;
 *  saklar user (pref conn_<id>, bawaan AKTIF) menjadi gerbang tool di
 *  Tools.execute; katalog ringkas disuntik ke prompt sistem supaya agen
 *  sadar kemampuan & batasnya. Status tidak pernah mengarang: "ok" =
 *  siap pakai, "perm" = perlu izin/atur dulu, "no" = tidak tersedia. */
object Connectors {

    data class Def(
        val id: String, val name: String, val icon: String, val desc: String,
        val tools: Set<String>, val canToggle: Boolean = true
    )

    val DEFS: List<Def> = listOf(
        Def("deepseek", "DeepSeek", "🐳", "Otak agen — sesi login DeepSeek (Web / API Internal).", emptySet(), canToggle = false),
        Def("termux", "Termux", "⌨️", "Terminal Linux di HP + jembatan Operator & MCP.", setOf("ask_operator", "check_operator", "sync_memory", "mcp_call", "mcp_setup")),
        Def("opencode", "Koordinator OpenCode", "🧭", "Delegasi tugas berat ke agen OpenCode di Termux.", setOf("delegate_session", "delegate_task", "delegate_status", "delegate_wait", "delegate_result", "chain_tasks", "coord_rule")),
        Def("gmail", "Gmail", "✉️", "Baca & kirim email via IMAP/SMTP (App Password Google).", setOf("gmail_unread", "gmail_search", "gmail_send")),
        Def("notifikasi", "Notifikasi HP", "🔔", "Baca & balas notifikasi yang masuk ke HP.", setOf("get_notifications", "reply_notification")),
        Def("kalender", "Kalender", "📅", "Lihat agenda hari ini & tambah acara.", setOf("get_calendar_today", "add_calendar_event")),
        Def("sms", "SMS & Telepon", "💬", "Baca SMS, kirim SMS, riwayat panggilan.", setOf("read_sms", "send_sms", "read_call_log")),
        Def("lokasi", "Lokasi", "📍", "Posisi HP + pagar lokasi (geofence).", setOf("get_location", "add_geofence", "delete_geofence", "list_geofences", "geofence_here")),
        Def("clipboard", "Clipboard", "📋", "Baca & tulis papan klip HP.", setOf("get_clipboard", "set_clipboard")),
        Def("adb", "Mode ADB", "🛠️", "Shell privileged uid 2000 dari server harness sendiri.", setOf("run_privileged")),
        Def("web", "Web", "🌐", "Cari & ambil halaman web + HTTP request + browser agen.", setOf("web_search", "web_fetch", "http_request", "browser_open", "browser_read", "browser_tap", "browser_type", "browser_tabs", "browser_shot")),
        Def("penyimpanan", "Penyimpanan", "🗂️", "Baca/tulis file & info storage HP.", setOf("list_files", "read_file", "write_file", "edit_file", "storage_info"))
    )

    private val TOOL_OWNER: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (d in DEFS) for (t in d.tools) m[t] = d.id
        m
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    fun enabled(ctx: Context, id: String): Boolean =
        try { prefs(ctx).getBoolean("conn_" + id, true) } catch (e: Exception) { true }

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        try { prefs(ctx).edit().putBoolean("conn_" + id, on).apply() } catch (e: Exception) {}
    }

    private fun pkgInstalled(ctx: Context, pkg: String): Boolean =
        try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun perm(ctx: Context, p: String): Boolean =
        try { ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED } catch (e: Exception) { false }

    /** Status jujur satu konektor: "ok" | "perm" | "no". */
    fun status(ctx: Context, id: String, dsLogin: Boolean): String = try {
        when (id) {
            "deepseek" -> if (dsLogin) "ok" else "perm"
            "termux" -> when {
                !pkgInstalled(ctx, "com.termux") -> "no"
                perm(ctx, "com.termux.permission.RUN_COMMAND") -> "ok"
                else -> "perm"
            }
            "opencode" -> if (pkgInstalled(ctx, "com.termux")) "ok" else "no"
            "gmail" -> {
                val e = SecureStore.get(ctx, "gmail_email")
                val p = SecureStore.get(ctx, "gmail_app_pass")
                if (!e.isNullOrBlank() && !p.isNullOrBlank()) "ok" else "perm"
            }
            "notifikasi" -> {
                val nl = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
                if (nl.contains(ctx.packageName)) "ok" else "perm"
            }
            "kalender" -> if (perm(ctx, Manifest.permission.READ_CALENDAR)) "ok" else "perm"
            "sms" -> if (perm(ctx, Manifest.permission.READ_SMS)) "ok" else "perm"
            "lokasi" -> if (perm(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || perm(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) "ok" else "perm"
            "adb" -> if (PrivServer.isAlive()) "ok" else "perm"
            "penyimpanan" -> {
                val ok = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
                else perm(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
                if (ok) "ok" else "perm"
            }
            else -> "ok"
        }
    } catch (e: Exception) { "perm" }

    fun statusJson(ctx: Context, dsLogin: Boolean): JSONArray {
        val arr = JSONArray()
        for (d in DEFS) {
            val o = JSONObject()
            o.put("id", d.id); o.put("name", d.name); o.put("icon", d.icon); o.put("desc", d.desc)
            o.put("status", status(ctx, d.id, dsLogin))
            o.put("enabled", enabled(ctx, d.id))
            o.put("canToggle", d.canToggle)
            arr.put(o)
        }
        return arr
    }

    /** Pemilik konektor sebuah tool (run_terminal dinilai dari mode-nya). */
    fun ownerOf(name: String, args: JSONObject): String? {
        if (name == "run_terminal") {
            return when (args.optString("mode")) {
                "termux" -> "termux"
                "priv" -> "adb"
                else -> null
            }
        }
        return TOOL_OWNER[name]
    }

    /** Gerbang Tools.execute: teks penolakan bila konektor pemilik tool
     *  sedang dinonaktifkan user; null = silakan jalan. */
    fun gate(ctx: Context, name: String, args: JSONObject): String? {
        val id = ownerOf(name, args) ?: return null
        if (enabled(ctx, id)) return null
        val d = DEFS.firstOrNull { it.id == id } ?: return null
        return "⛔ KONEKTOR NONAKTIF: tool '$name' milik konektor ${d.name} yang sedang DINONAKTIFKAN user. " +
            "Jangan mengakali lewat tool lain. Sampaikan ke user: aktifkan konektor ${d.name} di Panel Konektor (tab Settings) bila kemampuan ini ingin dipakai."
    }

    /** Satu-dua baris katalog buat prompt sistem. */
    fun catalogLine(ctx: Context): String {
        val sb = StringBuilder("\nKONEKTOR (layanan pemberi kemampuan): ")
        sb.append(DEFS.joinToString("; ") { d -> (if (enabled(ctx, d.id)) "✅ " else "⛔ ") + d.name })
        sb.append(". Tool milik konektor ⛔ DITOLAK sistem — jangan dicoba; arahkan user ke Panel Konektor di Settings bila perlu.\n")
        return sb.toString()
    }
}
