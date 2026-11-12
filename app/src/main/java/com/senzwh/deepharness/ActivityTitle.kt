package com.senzwh.deepharness

import org.json.JSONObject

// vc254: tampilan aktivitas agen gaya Claude — tiap langkah tool jadi baris
// lipat berjudul bahasa Indonesia (bukan nama tool mentah), ketuk untuk
// buka detail (argumen + hasil + durasi + status). File ini MURNI
// (tanpa Android): pemetaan judul, format teks kanonis antar-lane, dan
// parser-nya — diuji unit via kotlinc, bukan lewat giliran agen.
object ActivityTitle {

    data class ActivityData(
        val tool: String,
        val title: String,
        val ms: Long,
        val ok: Boolean,
        val args: String,
        val result: String
    )

    private fun base(path: String): String {
        val p = path.trim().trim('"')
        if (p.isBlank()) return ""
        val cut = p.substringAfterLast('/').substringAfterLast('\\')
        return cut.ifBlank { p }
    }

    private fun host(url: String): String {
        val u = url.trim()
        if (u.isBlank()) return ""
        val noScheme = u.substringAfter("://", u)
        return noScheme.substringBefore('/').substringBefore('?').take(40)
    }

    private fun lastSeg(s: String): String {
        val t = s.trim()
        if (t.isBlank()) return ""
        return t.substringAfterLast('.').ifBlank { t }
    }

    private fun q(args: JSONObject, key: String, n: Int = 40): String =
        args.optString(key, "").trim().take(n)

    // vc255: cuplikan pembeda di judul — dua baris tool yang sama tapi
    // argumen berbeda harus punya judul berbeda (bukti perangkat vc254:
    // dua run_terminal sama-sama "Perintah dijalankan"). Cuplikan = satu
    // baris, maks 48 char isi + "…" bila dipotong. AMAN: cuplikan yang
    // mengandung pola sensitif (password/token/kunci/dll) DITOLAK -> judul
    // kembali ke bentuk tanpa cuplikan. Tool yang argumennya memang
    // sensitif (type_text, set_text_ref, priv_input, set_clipboard,
    // reply_notification) TIDAK PERNAH diberi cuplikan di title().
    private const val SNIP_MAX = 48

    private val SENSITIVE_SUBS = listOf(
        "password", "passwd", "token", "secret", "api_key", "apikey",
        "api-key", "bearer", "credential", "private_key", "privatekey",
        "hsurr", "client_secret", "access_key", "sessionid", "cookie",
        "authorization", "id_rsa", "id_ed25519", ".pem"
    )

    private val SENSITIVE_WORD = Regex("\\b(auth|pwd|pin|otp|key)\\b")

    // vc265 m7: tool yang argumennya memang sensitif (cerminan daftar
    // tanpa-cuplikan di title()) — isinya tidak pernah tampil di detail.
    private val SENSITIVE_TEXT_TOOLS = setOf(
        "type_text", "set_text_ref", "priv_input", "set_clipboard",
        "reply_notification"
    )

    private fun cleanSnippet(raw: String): String {
        return raw.replace(Regex("[\\r\\n\\t]+"), " ")
            .replace("—", "-")
            .replace(Regex("  +"), " ").trim()
    }

    private fun isSensitive(s: String): Boolean {
        val low = s.lowercase()
        if (SENSITIVE_SUBS.any { low.contains(it) }) return true
        if (SENSITIVE_WORD.containsMatchIn(low)) return true
        if (Regex("://[^/\\s]*:[^/\\s]*@").containsMatchIn(s)) return true
        // Kredensial user:pass di luar URL: curl -u user:pass, user:pass@host.
        if (Regex("(-u|--user)(=|\\s+)\\S+:\\S+").containsMatchIn(s)) return true
        if (Regex("\\S+:\\S+@").containsMatchIn(s)) return true
        return false
    }

    // Cuplikan aman dari teks mentah. null = kosong atau sensitif.
    private fun snipText(raw: String, max: Int = SNIP_MAX): String? {
        val clean = cleanSnippet(raw)
        if (clean.isBlank() || isSensitive(clean)) return null
        if (clean.length <= max) return clean
        var cut = clean.take(max).trimEnd()
        if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return cut + "…"
    }

    // Cuplikan aman dari satu kunci argumen. null = kosong atau sensitif.
    private fun snip(args: JSONObject, key: String, max: Int = SNIP_MAX): String? =
        snipText(args.optString(key, ""), max)

    // Cuplikan baris pertama perintah (run_terminal / run_privileged).
    private fun cmdSnip(args: JSONObject): String? {
        val first = args.optString("command", "").lineSequence().firstOrNull()?.trim() ?: ""
        return snipText(first)
    }

    // Cuplikan elemen pertama array (make_plan steps, chain_tasks tasks).
    private fun firstItemSnip(args: JSONObject, key: String): String? {
        val arr = args.optJSONArray(key) ?: return null
        if (arr.length() == 0) return null
        return snipText(arr.optString(0))
    }

    // vc265 m7: tampilkan satu nilai argumen secara aman untuk detail
    // baris. null = kosong atau sensitif dan tidak bisa disamarkan.
    // Urutan: pola kredensial keyakinan tinggi disamarkan dulu lewat
    // RedactSecrets (tampilan versi redact); bila redact tidak mengubah
    // apa pun tapi nilai mengandung pola sensitif judul (isSensitive),
    // nilai disembunyikan seluruhnya, bukan ditampilkan mentah.
    private fun safeShow(raw: String, max: Int): String? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        val redacted = RedactSecrets.redact(clean)
        if (redacted != clean) return redacted.take(max)
        if (isSensitive(clean)) return null
        return clean.take(max)
    }

    // Id numerik untuk judul (cancel/delete/delegate_*): " #12" atau "".
    private fun idSnip(args: JSONObject, key: String): String {
        val id = args.optLong(key, -1)
        return if (id >= 0) " #$id" else ""
    }

    // Judul per tool. Aturan: TIDAK PERNAH nama tool mentah — tool tanpa
    // pemetaan eksplisit jatuh ke "Menjalankan langkah" (generik).
    // vc255: judul menyertakan cuplikan singkat argumen pembeda (maks 48
    // char, diredact bila sensitif) agar dua baris tool yang sama tidak
    // berjudul identik; tanpa cuplikan aman, judul kembali ke bentuk
    // tanpa cuplikan (bukan fallback generik).
    fun title(tool: String, args: JSONObject): String {
        return when (tool) {
            "read_file" -> { val b = base(args.optString("path", "")); if (b.isBlank()) "Membaca berkas" else "Lihat $b" }
            "write_file" -> { val b = base(args.optString("path", "")); if (b.isBlank()) "Menulis berkas" else "Menulis $b" }
            "edit_file" -> { val b = base(args.optString("path", "")); if (b.isBlank()) "Mengedit berkas" else "Mengedit $b" }
            "list_files" -> { val p = args.optString("path", "").trim(); if (p.isBlank()) "Melihat isi folder" else "Melihat isi $p" }
            "storage_info" -> { val p = args.optString("path", "").trim(); if (p.isBlank()) "Memeriksa penyimpanan" else "Memeriksa penyimpanan $p" }
            "otak_read" -> { val b = base(args.optString("file", "")); if (b.isBlank()) "Membaca file otak" else "Membaca $b" }
            "otak_write" -> { val b = base(args.optString("file", "")); if (b.isBlank()) "Menulis file otak" else "Menulis $b" }
            "analyze_photo" -> "Menganalisis foto"
            "transcribe_media" -> { val b = base(args.optString("path", "")); if (b.isBlank()) "Mentranskrip media" else "Mentranskrip $b" }
            "open_app" -> { val p = lastSeg(args.optString("package", "")); if (p.isBlank()) "Membuka aplikasi" else "Membuka $p" }
            "run_terminal" -> { val s = cmdSnip(args); if (s == null) "Perintah dijalankan" else "Perintah dijalankan: $s" }
            "run_privileged" -> { val s = cmdSnip(args); if (s == null) "Perintah dijalankan (Mode ADB)" else "Perintah dijalankan (Mode ADB): $s" }
            "get_screen" -> "Melihat layar"
            "get_current_app" -> "Melihat aplikasi aktif"
            "get_app_usage" -> "Melihat pemakaian aplikasi"
            "get_battery" -> "Memeriksa baterai"
            "get_location" -> "Memeriksa lokasi"
            "get_device_info" -> "Memeriksa info perangkat"
            "get_notifications" -> "Membaca notifikasi"
            "get_timeline" -> "Membaca linimasa"
            "get_calendar_today" -> "Membaca kalender hari ini"
            "get_clipboard" -> "Membaca clipboard"
            "set_clipboard" -> "Menyalin ke clipboard"
            "list_apps" -> "Melihat daftar aplikasi"
            "tap" -> "Mengetuk layar"
            "swipe" -> "Menggeser layar"
            "type_text" -> "Mengetik teks"
            "tap_ref" -> "Mengetuk elemen layar"
            "set_text_ref" -> "Mengisi teks di layar"
            "go_home" -> "Kembali ke beranda"
            "go_back" -> "Kembali"
            "wait_change" -> "Menunggu layar berubah"
            "wait_for_app" -> { val p = lastSeg(args.optString("package", "")); if (p.isBlank()) "Menunggu aplikasi dibuka" else "Menunggu $p dibuka" }
            "wait_confirm" -> "Menunggu konfirmasi"
            "take_screenshot" -> "Mengambil tangkapan layar"
            "priv_capture" -> "Mengambil tangkapan layar (Mode ADB)"
            "priv_input" -> "Mengirim input (Mode ADB)"
            "priv_app" -> "Mengelola aplikasi (Mode ADB)"
            "priv_settings" -> "Mengatur pengaturan (Mode ADB)"
            "media_control" -> when (args.optString("action", "").trim()) {
                "play" -> "Memutar media"
                "pause" -> "Menjeda media"
                "next" -> "Media berikutnya"
                "prev" -> "Media sebelumnya"
                else -> "Mengontrol media"
            }
            "watch_screen" -> when (args.optString("action", "").trim()) {
                "start" -> "Mulai memantau layar"
                "stop" -> "Berhenti memantau layar"
                "status" -> "Memeriksa pantauan layar"
                "frame" -> "Melihat potret layar terakhir"
                else -> "Memantau layar"
            }
            "web_fetch" -> { val h = host(args.optString("url", "")); if (h.isBlank()) "Mengambil isi web" else "Mengambil isi $h" }
            "web_search" -> { val s = q(args, "query"); if (s.isBlank()) "Mencari di web" else "Mencari di web: $s" }
            "search_youtube" -> { val s = q(args, "query"); if (s.isBlank()) "Mencari video YouTube" else "Mencari video: $s" }
            "memory_search" -> { val s = q(args, "query"); if (s.isBlank()) "Mencari di memori" else "Mencari di memori: $s" }
            "graph_search" -> { val s = q(args, "query"); if (s.isBlank()) "Mencari di graf memori" else "Mencari di graf: $s" }
            "recall_search" -> { val s = q(args, "query"); if (s.isBlank()) "Mencari di memori" else "Mencari di memori: $s" }
            "gmail_search" -> { val s = q(args, "query"); if (s.isBlank()) "Mencari email" else "Mencari email: $s" }
            "http_request" -> { val h = host(args.optString("url", "")); if (h.isBlank()) "Memanggil API" else "Memanggil $h" }
            "api_read" -> "Memanggil API (baca)"
            "api_write" -> "Memanggil API (tulis)"
            "browser_open" -> { val h = host(args.optString("url", "")); if (h.isBlank()) "Membuka halaman web" else "Membuka $h" }
            "browser_read" -> "Membaca halaman browser"
            "browser_tap" -> "Mengklik di browser"
            "browser_type" -> "Mengetik di browser"
            "browser_tabs" -> "Mengelola tab browser"
            "browser_shot" -> "Menangkap halaman browser"
            "send_email" -> { val s = snip(args, "subject"); if (s == null) "Menyiapkan draf email" else "Menyiapkan draf email: $s" }
            "gmail_send" -> { val t = q(args, "to", 40); if (t.isBlank()) "Mengirim email" else "Mengirim email ke $t" }
            "gmail_unread" -> "Membaca email belum dibaca"
            "send_sms" -> { val t = q(args, "number", 20); if (t.isBlank()) "Mengirim SMS" else "Mengirim SMS ke $t" }
            "read_sms" -> "Membaca SMS"
            "read_call_log" -> "Membaca log panggilan"
            "add_calendar_event" -> { val s = snip(args, "title"); if (s == null) "Menambah acara kalender" else "Menambah acara kalender: $s" }
            "get_result" -> { val s = q(args, "id", 12); if (s.isBlank()) "Membaca hasil tersimpan" else "Membaca hasil tersimpan $s" }
            "grep_result" -> { val s = snip(args, "pattern"); if (s == null) "Mencari di hasil tersimpan" else "Mencari di hasil tersimpan: $s" }
            "remember_fact" -> { val s = snip(args, "key"); if (s == null) "Menyimpan fakta" else "Menyimpan fakta: $s" }
            "recall_facts" -> "Mengingat fakta tersimpan"
            "forget_fact" -> { val s = snip(args, "key"); if (s == null) "Melupakan fakta" else "Melupakan fakta: $s" }
            "memory_add" -> { val s = snip(args, "fact"); if (s == null) "Menambah fakta ke memori" else "Menambah fakta ke memori: $s" }
            "memory_forget" -> { val id = args.optLong("id", -1); if (id > 0) "Melupakan fakta memori #$id" else { val s = snip(args, "query"); if (s == null) "Melupakan fakta memori" else "Melupakan fakta memori: $s" } }
            "learn_rule" -> { val s = snip(args, "rule"); if (s == null) "Menyimpan aturan baru" else "Menyimpan aturan baru: $s" }
            "make_plan" -> { val s = firstItemSnip(args, "steps"); if (s == null) "Menyusun rencana" else "Menyusun rencana: $s" }
            "update_plan" -> { val idx = args.optInt("index", -1); if (idx < 0) "Menandai langkah rencana selesai" else "Menandai langkah rencana selesai: langkah ${idx + 1}" }
            "make_goal" -> { val s = snip(args, "title"); if (s == null) "Membuat tujuan" else "Membuat tujuan: $s" }
            "update_goal" -> when (args.optString("action", "").trim()) {
                "complete_step" -> { val sid = args.optLong("step_id", 0); if (sid > 0) "Menyelesaikan langkah tujuan #$sid" else "Menyelesaikan langkah tujuan" }
                "add_step" -> { val s = snip(args, "text"); if (s == null) "Menambah langkah tujuan" else "Menambah langkah tujuan: $s" }
                "complete_goal" -> { val gid = args.optLong("goal_id", 0); if (gid > 0) "Menyelesaikan tujuan #$gid" else "Menyelesaikan tujuan" }
                "cancel_goal" -> { val gid = args.optLong("goal_id", 0); if (gid > 0) "Membatalkan tujuan #$gid" else "Membatalkan tujuan" }
                else -> "Memperbarui tujuan"
            }
            "list_goals" -> "Melihat daftar tujuan"
            "list_reminders" -> "Melihat daftar pengingat"
            "set_reminder" -> { val s = snip(args, "text"); if (s == null) "Mengatur pengingat" else "Mengatur pengingat: $s" }
            "cancel_reminder" -> "Membatalkan pengingat" + idSnip(args, "id")
            "set_alarm" -> { val s = snip(args, "label"); val h = args.optInt("hour", -1); val m = args.optInt("minute", 0); if (s != null && args.optString("label", "").trim() != "Pengingat dari DeepSeek") "Mengatur alarm: $s" else if (h in 0..23) "Mengatur alarm %02d:%02d".format(h, m) else "Mengatur alarm" }
            "schedule_task" -> { val s = snip(args, "prompt"); if (s == null) "Menjadwalkan tugas AI" else "Menjadwalkan tugas AI: $s" }
            "list_schedules" -> "Melihat daftar tugas terjadwal"
            "cancel_schedule" -> "Membatalkan tugas terjadwal" + idSnip(args, "id")
            "reply_notification" -> "Membalas notifikasi"
            "finance_summary" -> "Meringkas keuangan: ${args.optInt("days", 7)} hari"
            "geofence_here" -> { val s = snip(args, "name"); if (s == null) "Membuat geofence di sini" else "Membuat geofence di sini: $s" }
            "add_geofence" -> { val s = q(args, "name", 30); if (s.isBlank()) "Menambah geofence" else "Menambah geofence $s" }
            "list_geofences" -> "Melihat daftar geofence"
            "delete_geofence" -> "Menghapus geofence" + idSnip(args, "id")
            "add_rule_trigger" -> { val s = snip(args, "package"); if (s == null) "Menambah pemicu aturan" else "Menambah pemicu aturan: $s" }
            "list_rule_triggers" -> "Melihat daftar pemicu aturan"
            "delete_rule_trigger" -> "Menghapus pemicu aturan" + idSnip(args, "id")
            "create_skill" -> { val s = q(args, "name", 30); if (s.isBlank()) "Membuat skill" else "Membuat skill $s" }
            "delete_skill" -> { val s = q(args, "name", 30); if (s.isBlank()) "Menghapus skill" else "Menghapus skill $s" }
            "export_skill" -> { val s = q(args, "name", 30); if (s.isBlank()) "Mengekspor skill" else "Mengekspor skill $s" }
            "list_skills" -> "Melihat daftar skill"
            "use_skill" -> { val s = q(args, "name", 30); if (s.isBlank()) "Memuat skill" else "Memuat skill $s" }
            "create_subroom" -> { val s = snip(args, "task"); if (s == null) "Membuat sub-room" else "Membuat sub-room: $s" }
            "delete_session" -> "Menghapus sesi"
            "delegate_task" -> { val s = snip(args, "task"); if (s == null) "Mendelegasikan tugas kilat" else "Mendelegasikan tugas kilat: $s" }
            "delegate_session" -> { val s = snip(args, "task"); if (s == null) "Mendelegasikan ke koordinator" else "Mendelegasikan ke koordinator: $s" }
            "delegate_result" -> "Mengambil hasil koordinator" + idSnip(args, "task_id")
            "delegate_wait" -> "Menunggu tugas koordinator" + idSnip(args, "task_id")
            "delegate_status" -> "Memeriksa status koordinator"
            "chain_tasks" -> { val s = firstItemSnip(args, "tasks"); if (s == null) "Merangkai tugas koordinator" else "Merangkai tugas koordinator: $s" }
            "oc_checkpoint" -> { val tid = args.optLong("task_id", -1); val a = args.optString("action", "").trim().lowercase(); if (tid <= 0) "Memutuskan checkpoint" else "Memutuskan checkpoint #$tid" + (if (a == "accept" || a == "undo") " ($a)" else "") }
            "coord_rule" -> "Mengatur aturan koordinator"
            "run_team" -> { val s = snip(args, "goal"); if (s == null) "Menjalankan tim agen" else "Menjalankan tim agen: $s" }
            "ask_operator" -> { val s = snip(args, "task"); if (s == null) "Melempar tugas ke Operator" else "Melempar tugas ke Operator: $s" }
            "check_operator" -> "Memeriksa hasil Operator"
            "sync_memory" -> { val s = snip(args, "direction"); if (s == null) "Menyinkronkan memori" else "Menyinkronkan memori: $s" }
            "mcp_setup" -> "Memasang helper MCP"
            "mcp_list_servers" -> "Melihat daftar server MCP"
            "mcp_list_tools" -> "Melihat tool server MCP"
            "mcp_call" -> { val sv = q(args, "server", 20); val tl = q(args, "tool", 30); if (sv.isBlank() || tl.isBlank()) "Memanggil tool MCP" else "Memanggil $tl di $sv" }
            "generate_document" -> { val s = snip(args, "title"); if (s == null) "Membuat dokumen" else "Membuat dokumen: $s" }
            "generate_presentation" -> { val s = snip(args, "title"); if (s == null) "Membuat presentasi" else "Membuat presentasi: $s" }
            "create_artifact" -> { val s = snip(args, "title"); if (s == null) "Membuat artifact" else "Membuat artifact: $s" }
            "edit_artifact" -> "Mengubah artifact"
            "list_artifacts" -> "Melihat daftar artifact"
            "delete_artifact" -> "Menghapus artifact"
            "list_tools" -> "Melihat daftar tool"
            "graph_neighbors" -> { val s = snip(args, "label"); val id = args.optLong("id", -1); if (s != null) "Melihat hubungan memori: $s" else if (id > 0) "Melihat hubungan memori #$id" else "Melihat hubungan memori" }
            "graph_overview" -> "Melihat ringkasan graf memori"
            "graph_path" -> { val f = snipText(args.optString("from", ""), 20); val t = snipText(args.optString("to", ""), 20); if (f == null && t == null) "Mencari jalur di graf memori" else if (f != null && t != null) "Mencari jalur di graf memori: $f → $t" else "Mencari jalur di graf memori: ${f ?: t}" }
            else -> "Menjalankan langkah"
        }
    }

    // Ringkasan argumen satu baris untuk detail baris (bukan judul).
    // Prioritas kunci paling informatif; jatuh ke JSON datar bila tak ada.
    // vc265 m7: SEMUA cabang lewat safeShow / RedactSecrets — nilai
    // sensitif tidak pernah tampil mentah di detail baris, history JSON
    // (WorkLog.turnJson memakai shortArgs untuk detail + redaksi sendiri
    // untuk args mentah), maupun transkrip room/grup (formatText di bawah
    // memanggil fungsi ini; MainActivity 11482/11525/11694 dan
    // GroupChat 342 semua lewat formatText).
    fun shortArgs(tool: String, args: JSONObject): String {
        if (args.length() == 0) return ""
        val path = args.optString("path", "").trim()
        if (path.isNotBlank()) {
            val label = if (tool == "list_files" || tool == "storage_info") "Lokasi" else "File"
            return "$label: " + (safeShow(path, 120) ?: "[disembunyikan]")
        }
        val url = args.optString("url", "").trim()
        if (url.isNotBlank()) {
            val scrubbed = url.replace(Regex("://[^/\\s]*:[^/\\s]*@"), "://[REDAKSI]@")
            return "URL: " + (safeShow(scrubbed, 100) ?: "[disembunyikan]")
        }
        val cmd = args.optString("command", "").trim()
        if (cmd.isNotBlank()) {
            val mode = args.optString("mode", "").trim()
            val first = cmd.lineSequence().firstOrNull()?.trim() ?: ""
            val shown = safeShow(first, 100) ?: "[argumen sensitif disembunyikan]"
            return "Perintah: " + shown + (if (mode.isNotBlank()) " • Mode: $mode" else "")
        }
        val query = args.optString("query", "").trim()
        if (query.isNotBlank()) return "Kueri: " + (safeShow(query, 100) ?: "[disembunyikan]")
        val text = args.optString("text", "").trim()
        if (text.isNotBlank()) {
            if (tool in SENSITIVE_TEXT_TOOLS) return "Teks: [disembunyikan]"
            return "Teks: " + (safeShow(text, 80) ?: "[disembunyikan]")
        }
        val title = args.optString("title", "").trim()
        if (title.isNotBlank()) return "Judul: " + (safeShow(title, 80) ?: "[disembunyikan]")
        val task = args.optString("task", "").trim()
        if (task.isNotBlank()) return "Tugas: " + (safeShow(task, 100) ?: "[disembunyikan]")
        val name = args.optString("name", "").trim()
        if (name.isNotBlank()) return "Nama: " + (safeShow(name, 60) ?: "[disembunyikan]")
        val pkg = args.optString("package", "").trim()
        if (pkg.isNotBlank()) return "Paket: $pkg"
        val flat = args.toString().replace("\n", " ").replace("\r", " ").replace("  ", " ").trim()
        val redactedFlat = RedactSecrets.redact(flat)
        if (redactedFlat != flat) return redactedFlat.take(200)
        if (isSensitive(flat)) return "[argumen sensitif disembunyikan]"
        return flat.take(200)
    }

    fun statusText(ms: Long, ok: Boolean): String =
        (if (ok) "✓ selesai" else "✗ gagal") + " dalam " + WorkLog.secsText(ms) + " dtk"

    fun resultTail(result: String, cap: Int = 1500): String {
        if (result.length <= cap) return result
        return result.take(cap) + "…(dipotong, sisa " + (result.length - cap) + " char)…"
    }

    // Teks kanonis satu langkah — disimpan sebagai pesan room/grup (tetap
    // terbaca manusia + bisa di-parse balik jadi baris lipat). Dipakai
    // juga korpus bukti reviewer room (vc249) sebagai pengganti "🔧 …".
    fun formatText(tool: String, args: JSONObject, result: String, ms: Long, ok: Boolean): String {
        val sb = StringBuilder()
        sb.append("▸ ").append(title(tool, args)).append(" — ").append(statusText(ms, ok)).append("\n")
        sb.append("Tool: ").append(tool).append("\n")
        val sa = shortArgs(tool, args)
        if (sa.isNotBlank()) sb.append("Argumen: ").append(sa).append("\n")
        sb.append("Hasil: ").append(resultTail(result))
        return sb.toString()
    }

    private fun parseMs(status: String): Long {
        val m = Regex("([0-9]+,[0-9]+) dtk").find(status) ?: return 0L
        val secs = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        return (secs * 1000).toLong()
    }

    // Parser format kanonis di atas. null = bukan teks aktivitas (pesan
    // lama / pesan biasa) — pemanggil WAJIB fallback ke render biasa.
    fun parseText(text: String): ActivityData? {
        val lines = text.split("\n")
        if (lines.isEmpty()) return null
        val head = lines[0]
        if (!head.startsWith("▸ ")) return null
        val dash = head.indexOf(" — ")
        if (dash < 0) return null
        val title = head.substring(2, dash).trim()
        val status = head.substring(dash + 3).trim()
        if (title.isBlank() || status.isBlank()) return null
        var tool = ""
        var args = ""
        var resultStart = -1
        for (i in 1 until lines.size) {
            val l = lines[i]
            if (l.startsWith("Tool: ")) tool = l.removePrefix("Tool: ").trim()
            else if (l.startsWith("Argumen: ")) args = l.removePrefix("Argumen: ").trim()
            else if (l.startsWith("Hasil: ")) { resultStart = i; break }
        }
        if (tool.isBlank() || resultStart < 0) return null
        val result = lines.subList(resultStart, lines.size).joinToString("\n").removePrefix("Hasil: ")
        val ok = status.contains("✓")
        return ActivityData(tool, title, parseMs(status), ok, args, result)
    }

    fun safeArgs(s: String): JSONObject {
        return try { JSONObject(s) } catch (e: Exception) { JSONObject() }
    }
}
