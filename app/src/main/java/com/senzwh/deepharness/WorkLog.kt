package com.senzwh.deepharness

import org.json.JSONArray
import org.json.JSONObject

/**
 * J3 (dari bedah Jarves) — WorkItem: jejak kerja tool yang NEMPEL pada pesan
 * asisten (bukan gelembung terpisah yang hilang konteksnya), lengkap dengan
 * waktu kerja (workedMillis ala Jarves). Disimpan sebagai JSON di kolom
 * history.work (MemoryDb v7) dan dikirim ke UI React sebagai event chat.work.
 */
data class WorkItem(val tool: String, val ms: Long, val ok: Boolean, val summary: String, val evidence: String = "", val args: String = "", val resultId: String = "", val callId: String = "", val totalChars: Int = 0) // VC303-P2-12 (CX-6): total char hasil tool (dari resultTotal/lastFullLengthTL) — dibawa ke formatLine WorkNotes; default 0 = tidak diketahui (baris lama tanpa "(N char)").

object WorkLog {
    /** JSON {"items":[{tool,ms,ok,summary}],"totalMs":N} — null bila tak ada tool. */
    fun turnJson(items: List<WorkItem>, totalMs: Long): String? {
        if (items.isEmpty()) return null
        val arr = JSONArray()
        for (w in items) {
            // vc254: simpan juga args + evidence (dipotong) agar baris
            // aktivitas hasil reload menampilkan detail asli, bukan cuma
            // ringkasan 120 char. Field aditif — baris lama tanpa field
            // ini tetap ter-render (optString default "").
            val o = JSONObject()
                .put("tool", w.tool)
                .put("ms", w.ms)
                .put("ok", w.ok)
                .put("summary", w.summary.take(120))
            if (w.args.isNotBlank()) o.put("args", redactArgs(w.args).take(300))
            if (w.callId.isNotBlank()) o.put("callId", w.callId)
            if (w.evidence.isNotBlank()) o.put("evidence", w.evidence.take(600))
            arr.put(o)
        }
        return JSONObject().put("items", arr).put("totalMs", totalMs).toString()
    }

    // vc265 m7: argumen mentah TIDAK PERNAH menetap di history JSON.
    // Bila args berupa JSON, nilai kunci sensitif diganti penanda per
    // kunci (struktur JSON dipertahankan); bila bukan JSON, pakai pola
    // keyakinan tinggi RedactSecrets. Kunci otp/pin hanya cocok bila
    // persis (bukan substring) supaya "mapping"/"shipping" tidak kena.
    private fun redactArgs(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return RedactSecrets.redact(raw)
        return try {
            val o = JSONObject(trimmed)
            val broad = listOf(
                "password", "passwd", "token", "secret", "api_key", "apikey",
                "api-key", "bearer", "credential", "private_key", "privatekey",
                "access_key", "sessionid", "cookie", "authorization",
                "client_secret", "hsurr"
            )
            val exact = setOf("pwd", "pass", "otp", "pin", "key")
            val keys = o.keys()
            var changed = false
            while (keys.hasNext()) {
                val k = keys.next()
                val low = k.lowercase()
                if (broad.any { low.contains(it) } || low in exact) {
                    o.put(k, "[REDAKSI]")
                    changed = true
                }
            }
            if (changed) o.toString() else RedactSecrets.redact(raw)
        } catch (e: Exception) {
            RedactSecrets.redact(raw)
        }
    }

    // vc228 R-02(b): SATU definisi hasil tool gagal, dipakai kartu (turnJson
    // membawa ok), footer (lewat WorkItem.ok), dan cabang retry di
    // MainActivity.execToolAndContinue. Daftar eksplisit — bukan heuristik
    // substring tersebar. Hasil lain (termasuk "Tool tidak dikenal" yang
    // sudah ditangani eskalasinya sendiri) dinilai gagal di sini juga.
    fun isFailed(result: String): Boolean {
        // vc282: penanda status terstruktur menang atas substring-scan —
        // hasil yang membawa [STATUS: ok] dinilai sukses walau teksnya
        // mengandung kata mark; [STATUS: failed/timeout/unknown] gagal.
        // Tanpa penanda, perilaku lama (substring-scan) berlaku penuh.
        // vc299 m16: [STATUS: simulasi-gagal] = gagal (simulasi rote yang
        // berhenti paksa — bukan sukses walau state tersimpan).
        if (result.contains("[STATUS: simulasi-gagal]")) return true
        if (result.contains("[STATUS: ok]")) return false
        if (result.contains("[STATUS: failed]") || result.contains("[STATUS: timeout]") || result.contains("[STATUS: unknown]")) return true
        if (result.startsWith("⚠️")) return true
        if (result.startsWith("Error")) return true
        val low = result.lowercase()
        val marks = listOf("gagal", "tidak terbaca", "exception", "tidak ditemukan", "tidak ada", "tool tidak dikenal", "ditolak", "tidak bisa", "tidak berhasil", "tidak tersimpan", "dibatalkan")
        return marks.any { low.contains(it) }
    }

    /** Baris ringkas untuk UI klasik di bawah jawaban final. */
    fun footer(items: List<WorkItem>, totalMs: Long): String {
        if (items.isEmpty()) return ""
        // vc254: footer meta memakai judul aktivitas (bukan nama tool mentah).
        val names = items.joinToString(", ") { ActivityTitle.title(it.tool, ActivityTitle.safeArgs(it.args)) }
        val secs = String.format(java.util.Locale.US, "%.1f", totalMs / 1000.0).replace('.', ',')
        return "🧰 ${items.size} tool ($names) • ⏱ kerja total $secs dtk"
    }

    fun secsText(ms: Long): String =
        String.format(java.util.Locale.US, "%.1f", ms / 1000.0).replace('.', ',')

    // ---- Status terstruktur hasil tool (pindahan dari ApiToolLayer.kt
    // vc282, Fase 6 K1 — lapisan itu dibubarkan; status hasil tool BUKAN
    // khusus jalur web lama, dipakai semua jalur Models API + lokal) ----
    // Penanda status yang sama dipakai isFailed di atas: "[STATUS: ok]"
    // = sukses walau teks mengandung kata mark; "[STATUS: failed/
    // timeout/unknown]" = gagal. Substring-scan hanya fallback bila
    // tidak ada penanda.
    enum class ToolStatus { OK, FAILED, TIMEOUT, UNKNOWN }

    fun statusTag(status: ToolStatus): String = when (status) {
        ToolStatus.OK -> "[STATUS: ok]"
        ToolStatus.FAILED -> "[STATUS: failed]"
        ToolStatus.TIMEOUT -> "[STATUS: timeout]"
        ToolStatus.UNKNOWN -> "[STATUS: unknown]"
    }

    fun statusOfTag(result: String): ToolStatus? {
        return when {
            // vc299 m16: simulasi gagal = GAGAL (dicek dulu; tag ini
            // tidak mengandung "[STATUS: ok]" tapi eksplisit lebih aman).
            result.contains("[STATUS: simulasi-gagal]") -> ToolStatus.FAILED
            result.contains("[STATUS: ok]") -> ToolStatus.OK
            result.contains("[STATUS: failed]") -> ToolStatus.FAILED
            result.contains("[STATUS: timeout]") -> ToolStatus.TIMEOUT
            result.contains("[STATUS: unknown]") -> ToolStatus.UNKNOWN
            else -> null
        }
    }

    // Klasifikasi deterministik hasil tool mentah -> status. Aturan SAMA
    // dengan isFailed (daftar mark disalin, bukan diubah) supaya keduanya
    // tidak pernah berpendapat beda: "id tidak ditemukan" TETAP gagal
    // (tes C vc280), timeout O18 TETAP timeout, "Tool tidak dikenal"
    // TETAP unknown (bukan ok).
    private val FAIL_MARKS = listOf(
        "gagal", "tidak terbaca", "exception", "tidak ditemukan", "tidak ada",
        "tool tidak dikenal", "ditolak", "tidak bisa", "tidak berhasil",
        "tidak tersimpan", "dibatalkan"
    )

    fun classifyResult(result: String): ToolStatus {
        statusOfTag(result)?.let { return it }
        if (result.startsWith("Tool tidak dikenal")) return ToolStatus.UNKNOWN
        val low = result.lowercase()
        if (result.startsWith("\u26a0\ufe0f") || result.startsWith("Error") || result.startsWith("Gagal")) {
            if (low.contains("melewati batas") && low.contains("dtk")) return ToolStatus.TIMEOUT
            return ToolStatus.FAILED
        }
        if (low.contains("melewati batas") && low.contains("dtk")) return ToolStatus.TIMEOUT
        if (FAIL_MARKS.any { low.contains(it) }) return ToolStatus.FAILED
        return ToolStatus.OK
    }

    fun isTimeoutResult(result: String): Boolean = classifyResult(result) == ToolStatus.TIMEOUT

    // Timeout batch (vc282, cermin tabel vc278 O18(a) jalur tunggal):
    // baca cepat 30 dtk, tulis/shell umum 120 dtk, run_terminal mengikuti
    // timeout yang sudah ada (proot 900 dtk, termux 90 dtk). Dipakai
    // Future.get di kelompok baca batch supaya satu call yang menggantung
    // tidak menggantung seluruh batch selamanya.
    val READ_FAST_TOOLS_VC282 = setOf(
        "read_file", "list_files", "recall_search", "otak_read",
        "storage_info", "list_tools", "get_result", "grep_result",
        "list_sessions", "list_goals", "list_rooms", "list_workers",
        "list_artifacts", "list_skills", "list_tasks",
        "list_skill_versions", "read_skill_version"
    )

    fun batchTimeoutSec(tool: String, args: JSONObject): Int {
        if (tool == "run_terminal") {
            return if (args.optString("mode", "") == "proot") 900 else 90
        }
        return if (tool in READ_FAST_TOOLS_VC282) 30 else 120
    }
}
