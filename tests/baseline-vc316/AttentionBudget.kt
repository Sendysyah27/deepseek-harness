package com.senzwh.deepharness

import android.content.Context

// vc262 (Fase C): Attention budget — panel VISIBILITAS "apa yang butuh
// manusia". Mengumpulkan sinyal yang butuh perhatian (item parkir legacy,
// kuota, flag ledger, kegagalan latar/koordinator, skill diblokir)
// TANPA memblokir eksekusi apa pun: panel hanya menampilkan info +
// tautan ke layar terkait; TIDAK ada aksi approval di dalamnya.
// Badge = jumlah sinyal (ditampilkan di judul seksi Monitor).
// Approval grup: tidak ada yang menggantung (gate vc247 nonaktif;
// eksekusi tool grup tercatat di ledger, bukan di panel approval).
object AttentionBudget {
    data class Signal(val title: String, val detail: String, val target: String)

    fun signals(ctx: Context): List<Signal> {
        val out = ArrayList<Signal>()
        try {
            val parked = BgBrain.parkedList(ctx)
            if (parked.isNotEmpty()) out.add(Signal("Item parkir legacy (" + parked.size + ")", "Antrean persetujuan pra-vc247 masih tampil — JANGAN diketuk: tap mengeksekusi tool HIGH tanpa PIN (H-K1, jendela 24 jam).", "Monitor > Antrean persetujuan Otak Latar"))
        } catch (e: Exception) {
        }
        try {
            val cap = Governor.cap(ctx)
            val used = Governor.count(ctx)
            if (cap > 0 && used >= cap) out.add(Signal("Kuota AI otonom habis", "Gubernur: " + used + "/" + cap + " panggilan hari ini — kerja latar/tim ditunda sampai besok; chat interaktif tetap jalan.", "Monitor > Gubernur"))
            else if (cap > 0 && used * 100 / cap >= 80) out.add(Signal("Kuota AI otonom hampir habis", "Gubernur: " + used + "/" + cap + " panggilan hari ini (di atas 80%).", "Monitor > Gubernur"))
        } catch (e: Exception) {
        }
        try {
            val flags = DecisionLedger.countFlags24h(ctx)
            if (flags > 0) out.add(Signal("Flag ledger 24 jam terakhir: " + flags, "Ada eksekusi berisiko yang dicatat ledger (timpa fakta terkonfirmasi, rescue thinking, tulis di luar folder kerja, otak_write SOUL/AGENTS).", "Monitor > Decision Ledger"))
        } catch (e: Exception) {
        }
        try {
            val cutoff = System.currentTimeMillis() - 24L * 3600_000
            val fails = EventDb.get(ctx).latest(100).filter { (it.type == "bgbrain" || it.type == "occoord") && (it.text.contains("GAGAL", ignoreCase = true) || it.text.contains("gagal", ignoreCase = true)) && it.ts >= cutoff }
            if (fails.isNotEmpty()) out.add(Signal("Kegagalan latar/koordinator 24 jam: " + fails.size, fails.first().text.take(140), "Monitor > LOG / tab Laporan"))
        } catch (e: Exception) {
        }
        try {
            val p = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val blocked = p.getInt("skillSpectorBlockedTotal", 0)
            if (blocked > 0) out.add(Signal("Skill diblokir SkillSpector: " + blocked, "Skill yang ditolak pemindai keamanan saat impor (skor risiko di atas 80).", "Setup > Skill Agen"))
        } catch (e: Exception) {
        }
        return out
    }

    fun count(ctx: Context): Int {
        return try {
            signals(ctx).size
        } catch (e: Exception) {
            0
        }
    }

    fun summaryText(ctx: Context): String {
        val sig = signals(ctx)
        val sb = StringBuilder()
        sb.append("👀 BUTUH MANUSIA (").append(sig.size).append(" sinyal) — panel visibilitas vc262, BUKAN antrean persetujuan: tidak ada yang diblokir di sini, eksekusi tetap jalan.\n")
        sb.append("Approval grup: tidak ada yang menggantung (gate vc247 nonaktif; eksekusi tool grup tercatat di ledger, bukan di panel approval).\n")
        if (sig.isEmpty()) sb.append("(tidak ada sinyal — semua tenang)\n")
        else for (s in sig) sb.append("- ").append(s.title).append("\n  ").append(s.detail).append("\n  Lihat: ").append(s.target).append("\n")
        sb.append("\nDecision ledger (5 entri terbaru):\n")
        val rec = try {
            DecisionLedger.recent(ctx, 5)
        } catch (e: Exception) {
            emptyList()
        }
        if (rec.isEmpty()) sb.append("(ledger kosong)\n") else for (r in rec) sb.append("- ").append(r.text.take(160)).append("\n")
        return sb.toString()
    }

    fun noteSkillBlocked(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            p.edit().putInt("skillSpectorBlockedTotal", p.getInt("skillSpectorBlockedTotal", 0) + 1).apply()
        } catch (e: Exception) {
        }
    }
}
