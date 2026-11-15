package com.senzwh.deepharness

/** vc270 — Hapus blok tag safety internal DeepSeek (<ds_safety>...</ds_safety>)
 *  dari teks jawaban model. Tag ini adalah metadata internal model (klasifikasi
 *  + verdict Mandarin, mis. [分类]色情 + "Safety: safe") yang kadang bocor ke
 *  jawaban tampil (instance 2026-11-08 11:44 dan 17:43). Bukan refusal/blokir.
 *  Kotlin murni tanpa import Android (bisa diuji standalone pola char-tests/).
 *  Idempotent: strip(strip(x)) == strip(x). Hanya pola TAG yang dihapus;
 *  sebutan "ds_safety" tanpa kurung sudut dan "Safety: safe" tanpa tag utuh. */
object DsSafetyStrip {
    private val BLOCK = Regex("""(?i)<ds_safety\b[^>]*>.*?</ds_safety>(\s*Safety:\s*\w+)?""", RegexOption.DOT_MATCHES_ALL)
    private val CLOSE_STRAY = Regex("""(?i)</ds_safety>(\s*Safety:\s*\w+)?""")
    private val OPEN_TAG = Regex("""(?i)<ds_safety\b[^>]*>""")
    private val BLANK_RUN = Regex("\n{3,}")

    fun strip(text: String): String {
        var t = text
        t = BLOCK.replace(t, "")
        t = CLOSE_STRAY.replace(t, "")
        val open = OPEN_TAG.find(t)
        if (open != null) t = t.substring(0, open.range.first)
        t = BLANK_RUN.replace(t, "\n\n")
        return t.trim()
    }
}
