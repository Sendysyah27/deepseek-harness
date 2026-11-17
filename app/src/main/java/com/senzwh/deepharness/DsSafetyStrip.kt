package com.senzwh.deepharness

import harness.core.RustCore

/** vc270 — Hapus blok tag safety internal DeepSeek (<ds_safety>...</ds_safety>)
 *  dari teks jawaban model. Tag ini adalah metadata internal model (klasifikasi
 *  + verdict Mandarin, mis. [分类]色情 + "Safety: safe") yang kadang bocor ke
 *  jawaban tampil (instance 2026-11-08 11:44 dan 17:43). Bukan refusal/blokir.
 *  Kotlin murni tanpa import Android (bisa diuji standalone pola char-tests/).
 *  Idempotent: strip(strip(x)) == strip(x). Hanya pola TAG yang dihapus;
 *  sebutan "ds_safety" tanpa kurung sudut dan "Safety: safe" tanpa tag utuh. */
object DsSafetyStrip {
    /**
     * Saklar A/B slice 4 (vc317): bila core Rust tersedia, strip jalan
     * di Rust; bila tidak, fallback ke implementasi Kotlin lama di bawah
     * (stripKotlin — JANGAN dihapus).
     */
    var rustDsSafetyStrip: Boolean = true

    private val BLOCK = Regex("""(?i)<ds_safety\b[^>]*>.*?</ds_safety>(\s*Safety:\s*\w+)?""", RegexOption.DOT_MATCHES_ALL)
    private val CLOSE_STRAY = Regex("""(?i)</ds_safety>(\s*Safety:\s*\w+)?""")
    private val OPEN_TAG = Regex("""(?i)<ds_safety\b[^>]*>""")
    private val BLANK_RUN = Regex("\n{3,}")

    fun strip(text: String): String {
        rustStrip(text)?.let { return it }
        return stripKotlin(text)
    }

    // ---- Seam Rust (vc317) ----

    private fun rustStrip(text: String): String? {
        if (!rustDsSafetyStrip || !RustCore.available) return null
        return try {
            val json = RustCore.dsSafetyStripJson(text)
            if (!json.contains("\"ok\":true")) return null
            val raw = Regex("\"stripped\":\"((?:\\\\.|[^\"\\\\])*)\"").find(json)?.groupValues?.getOrNull(1) ?: return null
            jsonUnescape(raw)
        } catch (e: Throwable) {
            rustDsSafetyStrip = false
            null
        }
    }

    /** Unescape satu string JSON (\" \\ \/ \n \r \t \b \f \uXXXX). */
    private fun jsonUnescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c)
                i++
                continue
            }
            when (val e = s[i + 1]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                    i += 4
                }
                else -> sb.append(e)
            }
            i += 2
        }
        return sb.toString()
    }

    /** Implementasi Kotlin lama (fallback) — DIPERTAHANKAN. */
    private fun stripKotlin(text: String): String {
        var t = text
        t = BLOCK.replace(t, "")
        t = CLOSE_STRAY.replace(t, "")
        val open = OPEN_TAG.find(t)
        if (open != null) t = t.substring(0, open.range.first)
        t = BLANK_RUN.replace(t, "\n\n")
        return t.trim()
    }
}
