package com.senzwh.deepharness

import android.content.Context

// vc262 (Fase C): Decision ledger — jejak audit append-only untuk tiap
// langkah agen (tool call + ringkasan hasil + flag tata kelola) per lane.
// BUKAN gate: tidak ada satu pun eksekusi yang ditahan oleh ledger ini.
// Penyimpanan: tabel events yang sudah ada (EventDb, type "ledger") —
// tidak ada tabel/skema baru. Isi diredaksi (RedactSecrets) sebelum
// disimpan; tiap entri dibatasi 1200 char. Retensi: prune 30 hari yang
// sudah ada di EventDb, dipicu paling sering 1x/24 jam dari sini.
object DecisionLedger {
    const val TYPE = "ledger"
    private const val MAX_ENTRY_CHARS = 1200
    private const val PRUNE_INTERVAL_MS = 24L * 3600_000

    fun add(ctx: Context, lane: String, kind: String, text: String) {
        try {
            val clean = redactGenericSecrets(RedactSecrets.redact(text)).take(MAX_ENTRY_CHARS)
            EventDb.get(ctx).add(TYPE, ctx.packageName, "[$kind|$lane] $clean")
            maybePrune(ctx)
        } catch (e: Exception) {
        }
    }

    // vc265 N2-m8: redaksi generik TAMBAHAN di atas RedactSecrets (yang
    // hanya pola keyakinan tinggi): nilai parameter generik (?key= / ?token=
    // / &auth= / &secret=), token JWT, dan token panjang berentropi tinggi
    // (campuran huruf besar + kecil + digit, min 32 char). Hash hex murni
    // (MD5/SHA, huruf kecil saja) TIDAK kena karena syarat huruf besar.
    private val GENERIC_PARAM_RE = Regex("(?i)([?&](api[_-]?key|access[_-]?token|auth[_-]?token|auth|token|key|secret|password|pwd)[=:])[^&\\s\"']+")
    private val JWT_RE = Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")
    private val HIGH_ENTROPY_TOKEN_RE = Regex("(?=[A-Za-z0-9_\\-+/=]{0,31}[a-z])(?=[A-Za-z0-9_\\-+/=]{0,31}[A-Z])(?=[A-Za-z0-9_\\-+/=]{0,31}\\d)[A-Za-z0-9_\\-+/=]{32,}")

    private fun redactGenericSecrets(text: String): String {
        var out = GENERIC_PARAM_RE.replace(text) { m -> m.groupValues[1] + "[REDAKSI: nilai parameter sensitif]" }
        out = JWT_RE.replace(out, "[REDAKSI: token JWT]")
        out = HIGH_ENTROPY_TOKEN_RE.replace(out, "[REDAKSI: token berentropi tinggi]")
        return out
    }

    fun flag(ctx: Context, lane: String, text: String) {
        add(ctx, lane, "flag", text)
    }

    fun recent(ctx: Context, limit: Int = 50): List<HEvent> {
        return try {
            EventDb.get(ctx).latest(limit, TYPE)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun recentFlags(ctx: Context, limit: Int = 20): List<HEvent> {
        return try {
            recent(ctx, 200).filter { it.text.startsWith("[flag|") }.take(limit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun countFlags24h(ctx: Context): Int {
        val cutoff = System.currentTimeMillis() - 24L * 3600_000
        return try {
            recent(ctx, 200).count { it.text.startsWith("[flag|") && it.ts >= cutoff }
        } catch (e: Exception) {
            0
        }
    }

    fun exportText(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("# Decision Ledger — S&Harness (vc262)\n\n")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale("id", "ID"))
        sb.append("Diekspor: ").append(fmt.format(java.util.Date())).append("\n\n")
        val rows = try {
            recent(ctx, 200)
        } catch (e: Exception) {
            emptyList()
        }
        if (rows.isEmpty()) sb.append("(ledger kosong)\n") else {
            val fmt2 = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("id", "ID"))
            for (r in rows.asReversed()) sb.append("- [").append(fmt2.format(java.util.Date(r.ts))).append("] ").append(r.text).append("\n")
        }
        return sb.toString()
    }

    fun exportToFile(ctx: Context): String {
        val f = java.io.File(ctx.filesDir, "decision-ledger.md")
        f.writeText(exportText(ctx), Charsets.UTF_8)
        return f.absolutePath
    }

    private fun maybePrune(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val last = p.getLong("ledgerLastPruneTs", 0)
            val now = System.currentTimeMillis()
            if (now - last > PRUNE_INTERVAL_MS) {
                p.edit().putLong("ledgerLastPruneTs", now).apply()
                EventDb.get(ctx).pruneOlderThan30d()
            }
        } catch (e: Exception) {
        }
    }
}
