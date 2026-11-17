package com.senzwh.deepharness

import harness.core.RustCore

// vc304 B2 — Giliran jalan di background, output live-nya TIDAK bocor ke sesi lain.
//
// Syarat user (seperti Claude sessions): pindah sesi TIDAK menghentikan /
// membatalkan giliran yang sedang jalan. Yang di-gate HANYA render live-nya:
// bubble jawaban, baris aktivitas tool, kartu kerja, status "sedang bekerja",
// dialog +10 / approval — semuanya hanya tampil di sesi pemilik giliran
// (turnSessionId). Di sesi lain render live DILEWATI; isinya tidak hilang
// karena jawaban final + kartu kerja TETAP tersimpan ke histori DB sesi
// pemilik (MainActivity.handleAgentResponse memakai turnSessionId), dan
// switchSession memuat ulang histori itu saat user kembali ke sesi pemilik.
//
// Keputusan yang butuh user (dialog +10 langkah, approval tool) TIDAK
// ditampilkan di sesi lain dan TIDAK di-auto-approve: diparkir sampai user
// kembali ke sesi pemilik, lalu ditawarkan ulang dari switchSession.
//
// File ini SENGAJA murni Kotlin tanpa dependensi Android supaya gerbangnya
// bisa diuji di JVM (TestSessionBackgroundVc304.kt). MainActivity memanggil
// dua predikat di bawah dari addMsg/addBubble/addActivityRow (param
// `turn = true` = output giliran) dan dari titik-titik dialog/status.
object SessionBackgroundVc304 {
    /**
     * Saklar A/B slice 4 (vc317): bila core Rust tersedia, gerbang
     * jalan di Rust; bila tidak, fallback ke implementasi Kotlin lama
     * di bawah (fungsi `*Kotlin` — JANGAN dihapus).
     */
    var rustSessionBackgroundVc304: Boolean = true

    /** Output live giliran hanya dirender bila sesi pemilik giliran sedang aktif. */
    fun renderAllowed(turnSessionId: Long, currentSessionId: Long): Boolean =
        rustSessionBackground(turnSessionId, currentSessionId, false)?.renderAllowed
            ?: renderAllowedKotlin(turnSessionId, currentSessionId)

    /** Keputusan giliran (dialog +10 / approval tool) harus parkir bila
     *  giliran berjalan di background — JANGAN tampil di sesi lain,
     *  JANGAN auto-approve, JANGAN eksekusi HIGH tanpa PIN. */
    fun shouldParkDecision(turnActive: Boolean, turnSessionId: Long, currentSessionId: Long): Boolean =
        rustSessionBackground(turnSessionId, currentSessionId, turnActive)?.shouldParkDecision
            ?: shouldParkDecisionKotlin(turnActive, turnSessionId, currentSessionId)

    /** Teks penanda terpisah untuk indikator status saat giliran sesi lain
     *  berjalan di background — tanpa isi proses giliran itu. */
    fun backgroundNotice(): String =
        rustSessionBackground(0L, 0L, false)?.backgroundNotice
            ?: backgroundNoticeKotlin()

    // ---- Seam Rust (vc317) ----

    private class RustSessionBackground(
        val renderAllowed: Boolean,
        val shouldParkDecision: Boolean,
        val backgroundNotice: String
    )

    private fun rustSessionBackground(turnSessionId: Long, currentSessionId: Long, turnActive: Boolean): RustSessionBackground? {
        if (!rustSessionBackgroundVc304 || !RustCore.available) return null
        return try {
            val json = RustCore.sessionBackgroundJson(turnSessionId, currentSessionId, turnActive)
            if (!json.contains("\"ok\":true")) return null
            val raw = Regex("\"backgroundNotice\":\"((?:\\\\.|[^\"\\\\])*)\"").find(json)?.groupValues?.getOrNull(1) ?: return null
            RustSessionBackground(
                json.contains("\"renderAllowed\":true"),
                json.contains("\"shouldParkDecision\":true"),
                jsonUnescape(raw)
            )
        } catch (e: Throwable) {
            rustSessionBackgroundVc304 = false
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

    // ---- Implementasi Kotlin lama (fallback) — DIPERTAHANKAN ----

    private fun renderAllowedKotlin(turnSessionId: Long, currentSessionId: Long): Boolean =
        turnSessionId == currentSessionId

    private fun shouldParkDecisionKotlin(turnActive: Boolean, turnSessionId: Long, currentSessionId: Long): Boolean =
        turnActive && turnSessionId != currentSessionId

    private fun backgroundNoticeKotlin(): String =
        "● Giliran sesi lain berjalan di background — kembali ke sesi itu untuk melihat progres."
}
