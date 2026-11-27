package com.senzwh.deepharness

/** Parser verdict verifikasi diri — MILIK BERSAMA kedua overload
 *  handleAgentResponse (String + ChatResult native) supaya kedua jalur
 *  membaca verdict dengan parser yang SAMA.
 *
 *  Dipindah utuh dari MainActivity.cleanVerdict (vc228) pada F6 — pemindahan
 *  ini yang memungkinkan tes JVM menguji parser yang sama dengan yang
 *  dipakai handler di HP (MainActivity sendiri tidak bisa dikompilasi /
 *  diinstansiasi di JVM).
 *
 *  Murni, tanpa Context — bisa diuji di JVM. */
object VerifyVerdict {

    /** Saring hasil verifikasi diri sebelum tampil. Return "LULUS", teks koreksi bersih,
     *  atau null bila output model berupa bocoran proses mikir / tidak konklusif (vc228: null ditampilkan sebagai "verifikasi tidak konklusif", bukan didiamkan). */
    fun cleanVerdict(raw: String): String? {
        var t = raw.replace("**", "").trim()
        if (t.isEmpty()) return null
        val up = t.uppercase()
        if (up.startsWith("LULUS")) return "LULUS"
        val markers = listOf("need verify", "let's craft", "should we", "desired oververbosity",
            "the prompt says", "the instruction says", "final only", "ensure no tool",
            "need include correction", "need focus", "is that too much", "no greeting")
        val low = t.lowercase()
        val ki = low.lastIndexOf("koreksi")
        if (ki >= 0) {
            var k = t.substring(ki + "koreksi".length).trimStart(':', ' ', '\n')
            k = k.lineSequence().firstOrNull()?.trim() ?: ""
            if (k.length > 300) k = k.take(300).substringBeforeLast('.') + "."
            return if (k.length >= 10) k else null
        }
        if (markers.any { low.contains(it) }) return null
        if (t.length > 350) return null
        return t
    }
}
