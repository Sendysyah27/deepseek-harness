package com.senzwh.deepharness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// vc301 SF-D2 + SF-C6 — catatan kerja deterministik antar giliran ("catatan
// giliran") — ditulis DETERMINISTIK (bukan oleh model) dari turnWork,
// disimpan di prefs "harness" (pola PlanStore.planJson_<sessionId>).
// Tiap baris: tool, R-id, status ok/gagal, argumen ringkas (path bila
// ada). TIDAK menyimpan hasil tool utuh — hanya ringkasan + path + R-id
// + status, cap blok prompt 4000 char (sesudah digest, sebelum riwayat).
// Status giliran terakhir (SF-C6) hanya disimpan bila giliran berakhir
// abnormal (cap/kunci/auto-lanjut habis); giliran normal MENGHAPUS status
// lama agar status basi tidak bocor ke giliran berikut. Yang TIDAK
// dibangun: resume/checkpoint otomatis antar giliran normal, dan
// maybeResumeTurn (crash-only) tidak disentuh.
//
// VC303-P2-9 (CX-2): object ini PINDAH dari private object di MainActivity
// (vc301: MainActivity.kt:6580-6656) ke file sendiri dengan nama dan
// package yang sama, supaya bisa dipanggil dari luar MainActivity
// (BgBrain, GroupChat, TeamRun, MemoryDb). Semua referensi
// `WorkNotesVc301.xxx` di MainActivity tetap jalan tanpa perubahan.
// Isi vc301 dipertahankan kecuali yang ditandai VC303-P2 di bawah.
object WorkNotesVc301 {
    const val MAX_LINES = 30
    const val BLOCK_CAP = 4000
    fun notesKey(sessionId: Long): String = "workNotesVc301_" + sessionId
    fun statusKey(sessionId: Long): String = "turnStatusVc301_" + sessionId

    // VC303-P2-9 (CX-2), P2-10 (CX-3), P2-11 (CX-4): kunci ber-namespace
    // per lane — JANGAN campur ke kunci 1-1 (isolasi antar room/sesi/
    // origin/grup). Room: satu kunci per (room, sesi); latar: satu kunci
    // per origin; grup: satu kunci untuk seluruh grup (grup tidak punya
    // sessionId).
    fun roomNotesKey(roomId: String, sessId: String): String = "workNotesVc301_room:" + roomId + ":" + sessId
    fun bgNotesKey(origin: String): String = "workNotesVc301_bg:" + origin
    fun groupNotesKey(): String = "workNotesVc301_group"

    // VC303-P2-12 (CX-6, OPSI A — keputusan user di Gas vc303): baris
    // membawa ringkasan hasil deterministik `evidence.take(120)` + total
    // char hasil (`totalChars`, dari lastFullLengthTL / resultTotal),
    // mis. `- forge_eval_save [R5808] [ok] <args120> :: <evidence120>
    // (8015 char)`. Ini MENGUBAH semantik tulis vc301 yang dikunci
    // byte-identik di vc302 — dijalankan karena user memilih opsi ini
    // secara eksplisit (bukan patch diam-diam). Parameter baru punya
    // default, jadi pemanggil lama 4-argumen tetap jalan (baris lama
    // tanpa `::` bila evidence kosong / total 0).
    fun formatLine(tool: String, argsJson: String, resultId: String, ok: Boolean, evidence: String = "", totalChars: Int = 0): String {
        val argSummary = try {
            val o = JSONObject(argsJson)
            val p = o.optString("path", "")
            if (p.isNotBlank()) p else argsJson.replace("\n", " ").trim()
        } catch (e: Exception) { argsJson.replace("\n", " ").trim() }
        val rid = if (resultId.isBlank()) "-" else resultId
        val base = "- " + tool + " [" + rid + "] " + (if (ok) "[ok]" else "[gagal]") + " " + argSummary.take(120)
        val ev = evidence.replace("\n", " ").trim().take(120)
        val tail = StringBuilder()
        if (ev.isNotBlank()) tail.append(" :: ").append(ev)
        if (totalChars > 0) tail.append(" (").append(totalChars).append(" char)")
        return base + tail.toString()
    }

    fun appendTurnNotes(ctx: Context, sessionId: Long, items: List<WorkItem>, redact: (String) -> String) {
        appendToKey(ctx, notesKey(sessionId), items, redact)
    }

    // VC303-P2-9/10/11 (CX-2/3/4): penulis generik ke kunci namespace
    // mana pun (room/latar/grup). Semantik sama seperti appendTurnNotes
    // vc301: tambah baris, buang tertua bila > MAX_LINES, redact saat
    // tulis. Kunci kosong / items kosong = no-op.
    fun appendToKey(ctx: Context, key: String, items: List<WorkItem>, redact: (String) -> String) {
        try {
            if (items.isEmpty() || key.isBlank()) return
            val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val prev = try { JSONArray(prefs.getString(key, "") ?: "") } catch (e: Exception) { JSONArray() }
            val lines = ArrayList<String>()
            for (i in 0 until prev.length()) lines.add(prev.optString(i))
            for (w in items) lines.add(redact(formatLine(w.tool, w.args, w.resultId, w.ok, w.evidence, w.totalChars)))
            while (lines.size > MAX_LINES) lines.removeAt(0)
            val arr = JSONArray()
            for (ln in lines) arr.put(ln)
            prefs.edit().putString(key, arr.toString()).apply()
        } catch (e: Exception) {}
    }

    // VC303-P2-13 (CX-7a): bila reason null TAPI masih ada item rencana
    // ☐ terbuka, simpan status "lanjut" (bukan hapus) — giliran normal
    // tidak lagi membuang jejak tugas yang belum tuntas.
    // VC303-P2-15 (CX-11): reason dipotong 300 char saat simpan.
    // VC303-P2-13 (CX-7b): Artefak diambil dari GABUNGAN baris tersimpan
    // (bukan hanya giliran terakhir) — R-id run lama tidak hilang dari
    // status walau giliran ini tidak menyentuhnya. MAX_LINES tetap 30
    // (opsi naikkan 30→60 TIDAK dipilih).
    fun saveTurnStatus(ctx: Context, sessionId: Long, reason: String?, userText: String, items: List<WorkItem>, planItems: List<String>) {
        try {
            val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            // VC303-P2-13 (CX-7a) + P2-15 (CX-11).
            val effReason = if (reason.isNullOrBlank()) {
                if (planItems.none { it.startsWith("☐") }) { prefs.edit().remove(statusKey(sessionId)).apply(); return }
                "lanjut — giliran normal, masih ada langkah rencana ☐ terbuka"
            } else reason.take(300)
            val done = items.count { it.ok }
            val open = planItems.filter { it.startsWith("☐") }
            val storedRids = readRidsFromStored(prefs.getString(notesKey(sessionId), "") ?: "")
            val rids = (storedRids + items.mapNotNull { it.resultId.takeIf { r -> r.isNotBlank() } }).distinct().take(5)
            val sb = StringBuilder()
            sb.append("STATUS GILIRAN TERAKHIR (berakhir: ").append(effReason).append("):\n")
            sb.append("Tugas: ").append(userText.replace("\n", " ").trim().take(300)).append("\n")
            sb.append("Langkah selesai: ").append(done).append("/").append(items.size).append(" (tool ok/total).\n")
            sb.append("Langkah belum: ").append(if (open.isEmpty()) "tidak ada item rencana ☐ terbuka — lanjutkan dari jawaban final giliran lalu" else open.joinToString("; ").take(600)).append("\n")
            sb.append("Artefak: ").append(if (rids.isEmpty()) "-" else rids.joinToString(", ")).append(" (baca via get_result bila butuh isi penuh).")
            prefs.edit().putString(statusKey(sessionId), sb.toString()).apply()
        } catch (e: Exception) {}
    }

    // VC303-P2-13 (CX-7b): R-id pertama `[R<n>]` tiap baris tersimpan,
    // urutan simpan (tertua dulu) — digabung dengan R-id giliran ini di
    // saveTurnStatus, distinct, lalu take(5).
    private fun readRidsFromStored(rawJson: String): List<String> {
        return try {
            val arr = JSONArray(rawJson)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val m = Regex("\\[(R\\d+)\\]").find(arr.optString(i))
                if (m != null) out.add(m.groupValues[1])
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    fun workNotesBlock(ctx: Context, sessionId: Long): String {
        return try {
            val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val raw = prefs.getString(notesKey(sessionId), "") ?: ""
            if (raw.isBlank()) return ""
            val arr = try { JSONArray(raw) } catch (e: Exception) { return "" }
            val lines = ArrayList<String>()
            for (i in 0 until arr.length()) lines.add(arr.optString(i))
            if (lines.isEmpty()) return ""
            val status = prefs.getString(statusKey(sessionId), "") ?: ""
            val body = StringBuilder()
            body.append("CATATAN KERJA GILIRAN SEBELUMNYA (deterministik — ditulis sistem dari jejak tool, bukan ringkasan model; path/R-id di sini SUDAH dikerjakan, jangan dicari ulang):\n")
            // Retensi terbaru dulu (cermin digestEvidence): baris terbaru
            // dipertahankan bila blok melebihi cap.
            var acc = ""
            for (ln in lines.asReversed()) {
                if (acc.isNotEmpty() && acc.length + ln.length + 1 > BLOCK_CAP) break
                acc = if (acc.isEmpty()) ln else ln + "\n" + acc
            }
            body.append(acc)
            if (status.isNotBlank()) body.append("\n").append(status)
            body.toString()
        } catch (e: Exception) { "" }
    }

    // VC303-P2-9/10/11 (CX-2/3/4): baca catatan ber-namespace (room/
    // latar/grup) lewat pembaca vc302 yang sama (cap blok 4000 + aturan
    // jangan-ulangi + langkah berikut). "" bila kunci belum ada. Status
    // tidak ada untuk kunci namespace (""), jadi hanya baris yang tampil.
    fun blockForKey(ctx: Context, key: String): String {
        return try {
            if (key.isBlank()) return ""
            val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val raw = prefs.getString(key, "") ?: ""
            if (raw.isBlank()) "" else AgentPrompt.workNotesBlockFromRawVc302(raw, "")
        } catch (e: Exception) { "" }
    }

    fun clearSession(ctx: Context, sessionId: Long) {
        try { ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().remove(notesKey(sessionId)).remove(statusKey(sessionId)).apply() } catch (e: Exception) {}
    }
}
