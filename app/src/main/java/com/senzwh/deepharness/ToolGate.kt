package com.senzwh.deepharness

import android.content.Context
import org.json.JSONObject

/**
 * J2 (dari bedah Jarves) — ToolRequest + RiskLevel: setiap panggilan tool
 * dinilai risikonya dalam TIGA tingkat dan dibungkus objek permintaan yang
 * membawa pratinjau + penjelasan + sasaran, supaya dialog persetujuan
 * menunjukkan HAL YANG BENAR-BENAR akan terjadi, bukan sekadar nama tool.
 *
 *  - SAFE   : jalan tanpa tanya (tool baca; sejak vc228 tulis/hapus memori
 *    BUKAN lagi SAFE — remember_fact/learn_rule/forget_fact/delete_skill/
 *    cancel_schedule/cancel_reminder/delete_rule_trigger/delete_geofence/
 *    create_skill masuk REVIEW lewat RISKY_TOOLS).
 *  - REVIEW : dialog konfirmasi TANPA PIN (anggota RISKY_TOOLS non-HIGH).
 *  - HIGH   : dialog konfirmasi + PIN wajib bila PIN diset (kode arbitrer,
 *             hak shell, kirim keluar, hapus sesi, ekspor memori).
 *
 * Siapa yang ditanya tidak berubah dari perilaku lama (isRisky); yang berubah
 * hanya PIN: sejak J2 PIN hanya untuk HIGH.
 */
enum class RiskLevel { SAFE, REVIEW, HIGH }

data class ToolRequest(
    val tool: String,
    val args: JSONObject,
    val risk: RiskLevel,
    val preview: String,
    val explanation: String,
    val affected: List<String>
)

object ToolGate {
    // vc247 (perintah user 2026-11-02, dikonfirmasi setelah dijelaskan
    // konsekuensinya): SEMUA checkpoint persetujuan tool DIMATIKAN di
    // semua mode (1-1, room, grup, latar, tim, koordinator). Keputusan
    // akhir tetap di user, tapi TIDAK ADA lagi dialog, PIN, mandat,
    // autoAllow, atau antrean persetujuan yang menghalangi eksekusi tool.
    // Revert: set GATES_DISABLED = false, atau install ulang vc246.
    // Flag ini BUKAN pengaman lain: penolakan password, StuckDetector,
    // batas langkah, kuota Governor, label claim-check, larangan kamera,
    // dan prompt guard TETAP berlaku di situs masing-masing.
    const val GATES_DISABLED = true
    private val HIGH_TOOLS = setOf(
        "run_terminal", "run_privileged", "priv_settings", "priv_app",
        "delegate_session", "chain_tasks", "oc_checkpoint", "delete_session",
        "gmail_send", "send_sms", "sync_memory", "mcp_call", "http_request", "ask_jhon", "memory_forget", "api_write",
        "priv_input", "reply_notification"
    )

    private val EXPLANATIONS = mapOf(
        "run_terminal" to "Menjalankan perintah shell arbitrer di HP.",
        "run_privileged" to "Menjalankan perintah sebagai uid shell (privileged) — bisa mengubah sistem.",
        "priv_settings" to "Membaca/mengubah settings Android sebagai shell.",
        "priv_app" to "Mengubah izin / menghentikan aplikasi sebagai shell.",
        "priv_input" to "Mengirim sentuhan/teks ke layar sebagai shell.",
        "delegate_session" to "Menyerahkan tugas ke koordinator OpenCode (agen lain akan bekerja di HP).",
        "oc_checkpoint" to "Menetapkan nasib checkpoint tugas: accept membuang snapshot; undo MENIMPA file workspace dengan kondisi sebelum tugas jalan.",
        "otak_write" to "Menulis file otak agen: MEMORY.md = ingatan, SOUL.md = jiwa, AGENTS.md = aturan flow (dua terakhir setara mengubah agen itu sendiri).",
        "chain_tasks" to "Menyerahkan RANTAI tugas ke koordinator OpenCode.",
        "delegate_task" to "Menyerahkan tugas sekali jalan ke OpenCode (REVIEW, bukan HIGH: sekali tembak tanpa sesi persisten/workspace — berbeda dengan delegate_session/chain_tasks yang HIGH karena sesi + workspace persisten).",
        "run_team" to "Menjalankan tim agen paralel (pola ruang kerja) + verifikasi silang.",
        "use_skill" to "Memuat prosedur skill sebagai instruksi kerja.",
        "watch_screen" to "Memantau perubahan layar lewat popup izin melayang (persetujuan user + MediaProjection).",
        "delete_session" to "Menghapus sesi percakapan ini secara permanen.",
        "gmail_send" to "Mengirim email keluar atas nama user.",
        "send_sms" to "Mengirim SMS keluar atas nama user.",
        "sync_memory" to "Mengekspor memori harness keluar perangkat.",
        "mcp_call" to "Memanggil tool server MCP eksternal.",
        "mcp_list_servers" to "Mendaftar server MCP yang dikenal (lokal, tanpa jaringan).",
        "mcp_list_tools" to "Mengambil daftar tool dari server MCP (request jaringan ke server itu).",
        "list_tools" to "Melihat katalog lengkap semua tool beserta format argumennya (baca saja, tanpa aksi).",
        "http_request" to "Melakukan request HTTP arbitrer (bisa mengirim data keluar).",
        "ask_jhon" to "Mengirim pertanyaan/konten ke Jhon (agen di luar app).",
        "open_app" to "Membuka aplikasi lain di HP.",
        "tap" to "Menyentuh layar pada koordinat tertentu.",
        "browser_tap" to "Mengklik elemen di browser agen (bisa mengirim formulir / membuka tautan).",
        "browser_type" to "Mengetik teks ke kolom di browser agen.",
        "tap_ref" to "Menyentuh elemen layar berdasarkan nomor ref dari get_screen (lebih presisi daripada koordinat).",
        "set_text_ref" to "Mengisi teks ke elemen layar berdasarkan nomor ref dari get_screen.",
        "swipe" to "Menggeser layar.",
        "type_text" to "Mengetik teks ke kolom yang sedang fokus.",
        "reply_notification" to "Membalas notifikasi chat atas nama user.",
        "write_file" to "Menulis/menimpa file di penyimpanan.",
        "generate_document" to "Membuat dokumen .docx baru di folder dokumen app (tidak menimpa file lama).",
        "generate_presentation" to "Membuat presentasi .pptx baru di folder dokumen app (tidak menimpa file lama).",
        "edit_file" to "Mengubah isi file di penyimpanan.",
        "analyze_photo" to "Mengirim foto ke jalur web untuk dianalisis.",
        "memory_search" to "Mencari fakta aktif di memory graph (baca saja, FTS).",
        "memory_add" to "Menambah fakta eksplisit ke memory graph (EXTRACTED, sumber manual).",
        "memory_forget" to "Melupakan fakta di memory graph: SOFT-EXPIRE saja (arsip, baris tidak pernah dihapus).",
        "api_read" to "Membaca via klien API-Buatan (endpoint baca saja; template ditolak; token via dialog Setup).",
        "api_write" to "Menulis via klien API-Buatan (endpoint tulis saja; template ditolak; token via dialog Setup).",
        "remember_fact" to "Menyimpan fakta permanen tentang user (vc228: REVIEW — pratinjau key=value di atas; fakta baru berstatus BELUM DIKONFIRMASI sampai user menyetujuinya di UI memori).",
        "learn_rule" to "Menyimpan aturan permanen tentang user (vc228: REVIEW — fakta baru berstatus BELUM DIKONFIRMASI sampai user menyetujuinya di UI memori).",
        "forget_fact" to "Menghapus fakta permanen (vc228: REVIEW + arsip 30 hari — bisa dipulihkan lewat Setup 66).",
        "delete_skill" to "Menghapus skill agen (vc228: REVIEW + arsip 30 hari — bisa dipulihkan lewat Setup 66).",
        "cancel_schedule" to "Menghapus tugas AI terjadwal (vc228: REVIEW + arsip 30 hari — bisa dipulihkan lewat Setup 66).",
        "cancel_reminder" to "Membatalkan pengingat (vc228: REVIEW + arsip 30 hari — bisa dipulihkan lewat Setup 66).",
        "delete_rule_trigger" to "Menghapus aturan pemicu notifikasi (vc228: REVIEW + arsip 30 hari — bisa dipulihkan lewat Setup 66).",
        "delete_geofence" to "Menghapus geofence (vc228: REVIEW + arsip 30 hari — bisa dipulihkan lewat Setup 66).",
        "create_skill" to "Membuat/memperbarui skill agen (vc228: REVIEW — bila skill yang diubah sedang AKTIF, diff instruksinya ditampilkan di dialog; skill baru tetap NONAKTIF).",
        "update_skill_description" to "Mengubah deskripsi skill agen (vc311 B14: HANYA deskripsi — instruction tidak disentuh; simpan = versi baru, eval hangus bila deskripsi berubah)."
    )

    private val PREVIEW_KEYS = listOf("command", "task", "text", "message", "path", "url", "prompt", "q", "query", "title", "server", "tool")
    private val AFFECTED_KEYS = listOf("path", "package", "url", "file", "address", "number", "recipient", "to", "name")

    fun level(tool: String): RiskLevel = when {
        GATES_DISABLED -> RiskLevel.SAFE
        tool in HIGH_TOOLS -> RiskLevel.HIGH
        Tools.isRisky(tool) -> RiskLevel.REVIEW
        else -> RiskLevel.SAFE
    }

    // vc260 H-K1: klasifikasi risiko STATIS (tidak memakai GATES_DISABLED).
    // level() selalu SAFE sejak vc247, jadi batas staleness parkir legacy
    // dan label risiko tidak bisa lagi memakai level(). Helper ini meniru
    // level() pra-vc247 dari daftar HIGH + varian peka-argumen — BUKAN
    // gerbang: tidak menahan eksekusi apa pun, hanya dipakai untuk batas
    // staleness 15 menit (HIGH) vs 24 jam dan label risiko di parkir.
    fun isHighStatic(tool: String, args: JSONObject): Boolean = when {
        tool in HIGH_TOOLS -> true
        tool == "otak_write" && AgentBrain.isSoulOrAgents(args.optString("file", "")) -> true
        tool == "coord_rule" && args.optString("action", "list") == "set" && args.optString("key") == "auto_approve" && args.optString("value") == "*" -> true
        else -> false
    }

    fun isHighStatic(tool: String, args: JSONObject, ctx: Context): Boolean =
        if (isHighStatic(tool, args)) true else (tool == "write_file" || tool == "edit_file") && Tools.isOutsideWorkDir(ctx, args.optString("path"))

    // vc237 M10: level peka-argumen. otak_write untuk SOUL.md / AGENTS.md
    // naik ke HIGH (identitas dan aturan agen itu sendiri), sementara
    // otak_write untuk MEMORY.md tetap REVIEW seperti sebelumnya, dan
    // level tool lain tidak berubah. Penentu file memakai canonical
    // AgentBrain (case-insensitive, trim), jadi varian "soul.md" ikut HIGH.
    fun level(tool: String, args: JSONObject): RiskLevel = when {
        GATES_DISABLED -> RiskLevel.SAFE
        tool == "otak_write" && AgentBrain.isSoulOrAgents(args.optString("file", "")) -> RiskLevel.HIGH
        // vc242 K-02: coord_rule dipisah list vs set — set minimal REVIEW,
        // set auto_approve='*' minimal HIGH+PIN; list tetap SAFE.
        tool == "coord_rule" && args.optString("action", "list") == "set" && args.optString("key") == "auto_approve" && args.optString("value") == "*" -> RiskLevel.HIGH
        tool == "coord_rule" && args.optString("action", "list") == "set" -> RiskLevel.REVIEW
        else -> level(tool)
    }

    // vc242 TL-M7: varian peka-path — write_file/edit_file ke luar folder
    // kerja app naik ke HIGH; di dalam folder kerja tetap level lamanya.
    fun level(tool: String, args: JSONObject, ctx: Context): RiskLevel =
        if (GATES_DISABLED) RiskLevel.SAFE else if ((tool == "write_file" || tool == "edit_file") && Tools.isOutsideWorkDir(ctx, args.optString("path"))) RiskLevel.HIGH else level(tool, args)

    fun needsPin(tool: String, pinSet: Boolean, args: JSONObject? = null): Boolean =
        pinSet && (if (args != null) level(tool, args) else level(tool)) == RiskLevel.HIGH

    fun needsPin(tool: String, pinSet: Boolean, args: JSONObject?, ctx: Context): Boolean =
        pinSet && (if (args != null) level(tool, args, ctx) else level(tool)) == RiskLevel.HIGH

    // vc246 GELOMBANG 5 (TL-m8): daftar level gerbang untuk list_tools —
    // model jadi tahu tool mana yang butuh persetujuan sebelum memanggil.
    fun levelsText(): String {
        if (GATES_DISABLED) return "Gerbang persetujuan tool NONAKTIF (vc247, perintah user 2026-11-02): semua tool jalan langsung tanpa dialog, PIN, mandat, autoAllow, atau antrean persetujuan."
        val high = HIGH_TOOLS.sorted().joinToString(", ")
        val review = Tools.RISKY_TOOLS.filter { it !in HIGH_TOOLS }.sorted().joinToString(", ")
        return "HIGH (dialog + PIN bila PIN diset): $high\nREVIEW (dialog tanpa PIN): $review\nLainnya: SAFE (jalan tanpa dialog; tulis/hapus memori tetap REVIEW lewat RISKY_TOOLS)."
    }

    fun label(level: RiskLevel): String = when (level) {
        RiskLevel.HIGH -> "🔴 RISIKO TINGGI"
        RiskLevel.REVIEW -> "🟡 PERLU KONFIRMASI"
        RiskLevel.SAFE -> "🟢 AMAN"
    }

    fun describe(tool: String, args: JSONObject): ToolRequest {
        var preview = when (tool) {
            "remember_fact" -> (args.optString("key").trim() + " = " + args.optString("value").trim()).take(140)
            "learn_rule" -> args.optString("rule").trim().take(140)
            "create_skill" -> ("skill: " + args.optString("name").trim()).take(140)
            "update_skill_description" -> ("skill: " + args.optString("name").trim() + " — deskripsi baru: " + args.optString("description").trim()).take(140)
            "forget_fact" -> ("hapus fakta: " + args.optString("key").trim()).take(140)
            "delete_skill" -> ("hapus skill id=" + args.optLong("id", -1)).take(140)
            "cancel_schedule" -> ("hapus jadwal id=" + args.optLong("id", -1)).take(140)
            "cancel_reminder" -> ("hapus pengingat id=" + args.optLong("id", -1)).take(140)
            "delete_rule_trigger" -> ("hapus aturan id=" + args.optLong("id", -1)).take(140)
            "delete_geofence" -> ("hapus geofence id=" + args.optLong("id", -1)).take(140)
            else -> ""
        }
        if (preview.isBlank()) for (k in PREVIEW_KEYS) {
            val v = args.optString(k, "")
            if (v.isNotBlank()) { preview = v.replace('\n', ' ').take(140); break }
        }
        val affected = ArrayList<String>()
        for (k in AFFECTED_KEYS) {
            val v = args.optString(k, "")
            if (v.isNotBlank()) affected.add("$k: ${v.take(60)}")
            if (affected.size >= 4) break
        }
        return ToolRequest(
            tool, args, level(tool, args), preview,
            EXPLANATIONS[tool] ?: "Menjalankan tool $tool.",
            affected
        )
    }

    // vc242 TL-M7: describe peka-path — label risiko memakai level ctx
    // (write_file/edit_file luar folder kerja tampil HIGH di dialog).
    fun describe(tool: String, args: JSONObject, ctx: Context): ToolRequest {
        val base = describe(tool, args)
        return base.copy(risk = level(tool, args, ctx))
    }
}
