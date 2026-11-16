package com.senzwh.deepharness

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/** T1 migrasi native: renderer markdown mini -> Spannable (tanpa dependensi
 *  baru). Subset nyata jawaban agen: **bold**, *italic*, `kode`, blok kode
 *  berpagar, heading #..###, list (tanda hubung, bintang, angka), kutip >, link [teks](url),
 *  baris rencana ☐/☑ diwarnai; tabel dipecah jadi blok (splitBlocks)
 *  untuk dirender sebagai TableLayout di bubble (T11); render() tetap
 *  dipakai untuk segmen teks dan tampilan lipat. */
object MdMini {
    private val TOKEN = Regex("(\\*\\*[^*]+\\*\\*)|(\\*[^*\\n]+\\*)|(`[^`\\n]+`)|(\\[[^\\]]+\\]\\([^)\\s]+\\))")
    private val NUMBERED = Regex("^\\d+[.)]\\s+.*")

    // G11 vc310: warna kode dari tema (bukan hardcode). Default = token
    // dark supaya pemanggil lama (render histori) tampil sama seperti
    // vc309; pemanggil jalur blok baru (buildBlocksContentVc310) wajib
    // mengisi dari currentTheme() (terang/gelap).
    fun render(src: String, codeTextColor: Int = ChatThemeVc306.darkCodeText, codeInlineBgColor: Int = ChatThemeVc306.darkTileBg): CharSequence {
        val out = SpannableStringBuilder()
        val lines = src.replace("\r\n", "\n").split("\n")
        var inFence = false
        var first = true
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("```")) { inFence = !inFence; continue }
            if (!first) out.append("\n")
            first = false
            if (inFence) {
                val s = out.length
                out.append(line)
                out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(codeTextColor), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            when {
                t.startsWith("### ") -> appendInline(out, t.substring(4), 1.1f, true, codeTextColor = codeTextColor, codeInlineBgColor = codeInlineBgColor)
                t.startsWith("## ") -> appendInline(out, t.substring(3), 1.25f, true, codeTextColor = codeTextColor, codeInlineBgColor = codeInlineBgColor)
                t.startsWith("# ") -> appendInline(out, t.substring(2), 1.4f, true, codeTextColor = codeTextColor, codeInlineBgColor = codeInlineBgColor)
                t.startsWith("|") -> {
                    val s = out.length
                    out.append(line)
                    out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                t.startsWith("> ") -> appendInline(out, t.substring(2), 1f, false, dim = true, codeTextColor = codeTextColor, codeInlineBgColor = codeInlineBgColor)
                t.startsWith("☐") || t.startsWith("☑") -> appendPlanLine(out, t, codeTextColor, codeInlineBgColor)
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> { out.append("• "); appendInline(out, t.substring(2), 1f, false, codeTextColor = codeTextColor, codeInlineBgColor = codeInlineBgColor) }
                NUMBERED.matches(t) -> appendInline(out, t, 1f, false, codeTextColor = codeTextColor, codeInlineBgColor = codeInlineBgColor)
                else -> appendInline(out, line, 1f, false, codeTextColor = codeTextColor, codeInlineBgColor = codeInlineBgColor)
            }
        }
        return out
    }

    private fun appendPlanLine(out: SpannableStringBuilder, line: String, codeTextColor: Int = ChatThemeVc306.darkCodeText, codeInlineBgColor: Int = ChatThemeVc306.darkTileBg) {
        val s0 = out.length
        appendInline(out, line, 1f, false, codeTextColor = codeTextColor, codeInlineBgColor = codeInlineBgColor)
        val done = line.contains('☑')
        val cb = if (done) line.indexOf('☑') else line.indexOf('☐')
        if (cb >= 0) out.setSpan(
            ForegroundColorSpan(if (done) Color.rgb(80, 220, 140) else Color.rgb(240, 190, 90)),
            s0 + cb, s0 + cb + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // vc311 Worker D: kode inline kini SELALU dapat ForegroundColorSpan
    // eksplisit (codeTextColor) — sebelumnya hanya BackgroundColorSpan +
    // monospace, sehingga di mode cerah teks memakai warna default
    // TextView (gelap) di atas latar default darkTileBg (#232730, hampir
    // hitam) = blok hitam tak terbaca. Default tetap token dark supaya
    // pemanggil lama yang kelewat tidak berubah perilaku.
    private fun appendInline(out: SpannableStringBuilder, text: String, sizeMul: Float, bold: Boolean, dim: Boolean = false, codeTextColor: Int = ChatThemeVc306.darkCodeText, codeInlineBgColor: Int = ChatThemeVc306.darkTileBg) {
        val startAll = out.length
        var idx = 0
        for (m in TOKEN.findAll(text)) {
            if (m.range.first > idx) out.append(text.substring(idx, m.range.first))
            val tok = m.value
            val s = out.length
            when {
                tok.startsWith("**") -> {
                    out.append(tok.substring(2, tok.length - 2))
                    out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("`") -> {
                    out.append(tok.substring(1, tok.length - 1))
                    out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(codeInlineBgColor), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(codeTextColor), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("[") -> {
                    out.append(tok.substring(1, tok.indexOf(']')))
                    out.setSpan(UnderlineSpan(), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(Color.rgb(140, 190, 255)), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    out.append(tok.substring(1, tok.length - 1))
                    out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            idx = m.range.last + 1
        }
        if (idx < text.length) out.append(text.substring(idx))
        if (out.length > startAll) {
            if (sizeMul != 1f) out.setSpan(RelativeSizeSpan(sizeMul), startAll, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) out.setSpan(StyleSpan(Typeface.BOLD), startAll, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (dim) out.setSpan(ForegroundColorSpan(Color.rgb(170, 178, 195)), startAll, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** T11: inline markdown satu baris (untuk isi sel tabel).
     *  vc311 Worker D: teruskan codeTextColor (default token dark) supaya
     *  kode inline di sel tabel juga punya foreground eksplisit — sama
     *  seperti render(). */
    fun inline(text: String, codeTextColor: Int = ChatThemeVc306.darkCodeText, codeInlineBgColor: Int = ChatThemeVc306.darkTileBg): CharSequence {
        val b = SpannableStringBuilder()
        appendInline(b, text, 1f, false, codeTextColor = codeTextColor, codeInlineBgColor = codeInlineBgColor)
        return b
    }

    /** T11: blok isi pesan — segmen teks mentah, satu tabel utuh, atau
     *  satu blok kode berpagar (G11 vc310). */
    sealed interface Block {
        class Text(val raw: String) : Block
        class Table(val header: List<String>, val rows: List<List<String>>) : Block
        /** G11: blok kode fence — lang = kata pertama sesudah ``` (bisa
         *  kosong), raw = isi tanpa pagar fence. */
        class Code(val lang: String, val raw: String) : Block
    }

    private fun splitRow(line: String): List<String> {
        val t = line.trim().trimStart('|').trimEnd('|')
        return t.split("|").map { it.trim() }
    }

    private fun isSepRow(line: String): Boolean {
        val cells = splitRow(line)
        if (cells.isEmpty()) return false
        return cells.all { it.matches(Regex("^:?-+:?$")) }
    }

    /** T11: pecah pesan jadi blok teks/tabel. Fence-aware: baris pipa di
     *  dalam blok kode berpagar TIDAK pernah jadi tabel. Tabel tanpa
     *  baris pemisah yang sah jatuh balik ke segmen teks (render lama).
     *  G11 vc310: fence ```` ``` ```` menghasilkan blok Code tersendiri
     *  (lang + raw tanpa pagar); fence tanpa penutup tetap jadi Code
     *  dengan isi sampai akhir pesan (sama seperti render()); fence
     *  kosong (tanpa isi) tidak menghasilkan blok — perilakunya sama
     *  seperti render() yang membuang pagarnya. Syarat validasi tabel
     *  (isSepRow) TIDAK diubah. */
    fun splitBlocks(src: String): List<Block> {
        val lines = src.replace("\r\n", "\n").split("\n")
        val blocks = mutableListOf<Block>()
        val textBuf = StringBuilder()
        fun flushText() {
            if (textBuf.isNotBlank()) {
                blocks.add(Block.Text(textBuf.toString().trimEnd('\n')))
                textBuf.clear()
            }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.startsWith("```")) {
                // G11: buka fence — kumpulkan isi sampai pagar penutup.
                val lang = t.substring(3).trim().takeWhile { !it.isWhitespace() }
                flushText()
                val codeBuf = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (lines[j].trim().startsWith("```")) { closed = true; break }
                    codeBuf.append(lines[j]).append("\n")
                    j++
                }
                val raw = codeBuf.toString().trimEnd('\n')
                if (raw.isNotBlank()) blocks.add(Block.Code(lang, raw))
                i = if (closed) j + 1 else lines.size
                continue
            }
            if (t.startsWith("|") && i + 1 < lines.size && isSepRow(lines[i + 1].trim())) {
                val header = splitRow(t)
                if (header.isEmpty()) {
                    textBuf.append(line).append("\n")
                    i++
                    continue
                }
                flushText()
                val rows = mutableListOf<List<String>>()
                var j = i + 2
                while (j < lines.size && lines[j].trim().startsWith("|")) {
                    rows.add(splitRow(lines[j].trim()))
                    j++
                }
                val w = header.size
                val norm = rows.map { r -> if (r.size >= w) r.take(w) else r + List(w - r.size) { "" } }
                blocks.add(Block.Table(header, norm))
                i = j
                continue
            }
            textBuf.append(line).append("\n")
            i++
        }
        flushText()
        return blocks
    }

    fun hasTable(src: String): Boolean = splitBlocks(src).any { it is Block.Table }

    /** G11: true bila pesan mengandung blok kode fence (meniru hasTable). */
    fun hasCode(src: String): Boolean = splitBlocks(src).any { it is Block.Code }

    /** G11: susun ulang tabel menjadi teks markdown pipa asli (untuk
     *  tombol "Salin tabel" — ditempel ke tempat lain sebagai tabel utuh). */
    fun tableMarkdown(table: Block.Table): String {
        val sb = StringBuilder()
        sb.append("| ").append(table.header.joinToString(" | ")).append(" |\n")
        sb.append("| ").append(table.header.joinToString(" | ") { "---" }).append(" |\n")
        for (row in table.rows) sb.append("| ").append(row.joinToString(" | ")).append(" |\n")
        return sb.toString().trimEnd('\n')
    }
}
