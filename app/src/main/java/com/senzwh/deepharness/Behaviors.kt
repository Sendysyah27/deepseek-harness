package com.senzwh.deepharness

import harness.core.RustCore

/**
 * J6 (dari bedah Jarves: agen didefinisikan sebagai DATA) — Pak Perilaku:
 * preset gaya kerja agen yang bisa diganti user tanpa mengubah kode. Blok pak
 * terpilih (pref "behaviorPack") ditempel ke prompt sistem oleh
 * AgentPrompt.buildSystemPrompt, SETELAH persona bebas user — persona tetap
 * menang HANYA untuk gaya bahasa (vc247: persona TIDAK PERNAH menang atas aturan tool; gerbang persetujuan nonaktif).
 */
object Behaviors {
    /**
     * Saklar A/B slice 4 (vc317): bila core Rust tersedia, daftar pak +
     * blok jalan di Rust; bila tidak, fallback ke implementasi Kotlin
     * lama di bawah (PACKS_KOTLIN + blockKotlin — JANGAN dihapus).
     */
    var rustBehaviors: Boolean = true

    data class Pack(val id: String, val name: String, val desc: String, val block: String)

    val PACKS: List<Pack> by lazy { rustPacks() ?: PACKS_KOTLIN }

    private val PACKS_KOTLIN = listOf(
        Pack("asisten", "Asisten (bawaan)", "Persona murni, tanpa tambahan gaya.", ""),
        Pack(
            "mandor", "Mandor Produksi", "Tegas, berbasis checkpoint & angka.",
            "PAK PERILAKU — MANDOR PRODUKSI:\n" +
                "- Bekerja berbasis checkpoint: buat rencana dulu (make_plan), kerjakan per langkah, tandai selesai per langkah.\n" +
                "- Laporan selalu berisi ANGKA dan STATUS: apa yang selesai, apa yang sisa, apa yang gagal + alasannya.\n" +
                "- Dilarang mengklaim selesai tanpa bukti hasil tool.\n" +
                "- Nada: singkat, tegas, tanpa basa-basi."
        ),
        Pack(
            "trading", "Mentor Trading", "Belajar trading spot, anti-leverage, disiplin jurnal.",
            "PAK PERILAKU — MENTOR TRADING:\n" +
                "- User PEMULA trading crypto: jelaskan istilah dengan bahasa sederhana + contoh angka.\n" +
                "- FOKUS SPOT SAJA: leverage/futures DILARANG selama fase belajar — bila user memintanya, ingatkan aturan ini sekali, lalu tawarkan bahasan spot.\n" +
                "- Setiap bahasan analisis diakhiri pengingat risiko + saran mencatat di jurnal (harga masuk, alasan, rencana keluar).\n" +
                "- Jangan pernah menyarankan uang asli tanpa persetujuan eksplisit user; desk paper adalah lab belajar."
        ),
        Pack(
            "kasir", "Asisten Kasir", "Catat transaksi cepat, format rapi.",
            "PAK PERILAKU — ASISTEN KASIR:\n" +
                "- Prioritas: mencatat transaksi/operasional CEPAT dan AKURAT; konfirmasi selalu ringkas: item, jumlah, total.\n" +
                "- Nominal tidak jelas: tebak dari konteks hanya bila aman; selain itu tanya SATU pertanyaan singkat.\n" +
                "- Jawaban pendek; penjelasan panjang hanya bila diminta rekap."
        ),
        Pack(
            "tutor", "Tutor Sabar", "Mengajar bertahap dengan contoh konkret.",
            "PAK PERILAKU — TUTOR SABAR:\n" +
                "- Jelaskan BERTAHAP: konsep dulu, contoh konkret, baru cek paham.\n" +
                "- Satu konsep per bagian; pakai analogi keseharian Indonesia.\n" +
                "- Akhiri penjelasan materi dengan SATU pertanyaan cek pemahaman.\n" +
                "- User salah paham: ulangi dari sudut lain, jangan mengulang kalimat yang sama."
        ),
        Pack(
            "agen4", "Agen 4 — Generalis", "Serbaguna: riset singkat + eksekusi langsung, laporan ringkas.",
            "PAK PERILAKU — AGEN 4 GENERALIS:\n" +
                "- Serbaguna: kerjakan tugas apa pun secara praktis — riset singkat, eksekusi langsung, laporan ringkas.\n" +
                "- Jawaban padat: hasil dulu, detail seperlunya; jangan bertele-tele.\n" +
                "- Bila tugas punya bagian berisiko, kerjakan bagian amannya dulu dan tandai bagian yang butuh persetujuan user.\n" +
                "- Nada: santai tapi sigap, bahasa Indonesia sehari-hari."
        )
    )

    fun byId(id: String): Pack = PACKS.firstOrNull { it.id == id } ?: PACKS[0]

    /** Blok siap tempel ke prompt (string kosong untuk pak bawaan). */
    fun block(id: String): String {
        rustBlock(id)?.let { return it }
        return blockKotlin(id)
    }

    // ---- Seam Rust (vc317) ----

    private fun rustBlock(id: String): String? {
        if (!rustBehaviors || !RustCore.available) return null
        return try {
            val json = RustCore.behaviorsJson(id)
            if (!json.contains("\"ok\":true")) return null
            val raw = Regex("\"block\":\"((?:\\\\.|[^\"\\\\])*)\"").find(json)?.groupValues?.getOrNull(1) ?: return null
            jsonUnescape(raw)
        } catch (e: Throwable) {
            rustBehaviors = false
            null
        }
    }

    private fun rustPacks(): List<Pack>? {
        if (!rustBehaviors || !RustCore.available) return null
        return try {
            val json = RustCore.behaviorsJson("asisten")
            if (!json.contains("\"ok\":true")) return null
            val arrStart = json.indexOf("\"packs\":[")
            if (arrStart < 0) return null
            val packRe = Regex("\\{\"id\":\"((?:\\\\.|[^\"\\\\])*)\",\"name\":\"((?:\\\\.|[^\"\\\\])*)\",\"desc\":\"((?:\\\\.|[^\"\\\\])*)\",\"block\":\"((?:\\\\.|[^\"\\\\])*)\"\\}")
            val packs = packRe.findAll(json.substring(arrStart)).map {
                Pack(
                    jsonUnescape(it.groupValues[1]),
                    jsonUnescape(it.groupValues[2]),
                    jsonUnescape(it.groupValues[3]),
                    jsonUnescape(it.groupValues[4])
                )
            }.toList()
            if (packs.size != 6) return null
            packs
        } catch (e: Throwable) {
            rustBehaviors = false
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
    private fun blockKotlin(id: String): String {
        val p = byId(id)
        return if (p.block.isBlank()) "" else p.block + "\n\n"
    }
}
