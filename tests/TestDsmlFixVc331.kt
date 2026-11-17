// TestDsmlFixVc331 — FIX BOCOR DSML vc331 (SATU fix, perintah Sendy;
// bukti live 17 Nov 2026 23:37, vc330, TECNO POVA 5): chat "Utama" lane 1-1
// model DEEPSEEK V3, Harness Pure vc330 fresh install dari porto. Markup tool
// mentah DSML tampil sebagai teks di bubble chat dan TETAP NEMPEL sesudah
// turn selesai (bocor PERSISTEN, bukan transient streaming).
// AKAR (direproduksi cargo test + dibuktikan dari kode):
//  (1) strip hanya tahan ASCII: normalizeDsmlDelimiters hanya mengubah
//      ｜＜＞; spasi/pipe/sudut mirip-ASCII (U+3000 dkk) lolos; regex strip
//      memakai \s ASCII-only (Kotlin) / WS eksplisit ASCII (Rust) —
//      blok DSML ber-delimiter look-alike tidak cocok pola blok, hanya
//      tag-nya yang hilang (stray-tag) sehingga nilai parameter +
//      </parameter> tampil di bubble (direproduksi: varian U+3000 ->
//      "/sdcard/SHAREit/download</parameter>..." persis residu bocor).
//  (2) penutup parameter polos </parameter> (tanpa wrapper DSML, seperti
//      di bocoran POVA 5) tidak pernah cocok regex penutup lama yang
//      mewajibkan DSML di penutup — BAIK di strip tampil MAUPUN di parse
//      argumen (extractDsmlCall: nama tool terparse, args KOSONG).
//  (2b) extractToolCalls jalur DSML hanya mengambil invoke PERTAMA —
//      3 invoke sisanya hilang di lane room/tim/latar/grup (batch
//      vc273/vc274 tidak pernah melihatnya).
//  (3) sanitizePartial (tampilan STREAMING) tidak pernah memanggil strip
//      DSML — blok mentah tampil live selama giliran berjalan; DAN urutan
//      potong-sesudah-strip gagal (strip menghapus tag pembuka tapi
//      menyisakan nilai parameter telanjang).
// PERBAIKAN:
//  (1) normalizeDsmlDelimiters diperluas (Kotlin + Rust, cermin sama
//      persis): U+3000/U+00A0/U+2000–U+200A/U+202F/U+205F -> spasi;
//      ZWSP/BOM dihapus; │ U+2502 + ∣ U+2223 -> |; 〈〉‹› -> < >.
//  (2) penutup blok DSML boleh polos (wrapper DSML opsional) di ketiga
//      regex strip (calls/invoke/parameter), Kotlin + Rust; DAN di regex
//      parameter extractDsmlCall (parseDsmlParams) — args tidak lagi kosong.
//  (2b) extractDsmlCalls baru (plural, maks 4 = MAX_BATCH_CALLS_VC273):
//      semua invoke diparse per segmen; extractDsmlCall = pertama dari
//      daftar; extractToolCalls mengembalikan semuanya (id tc-<n>).
//  (3) helper baru AgentPrompt.stripForStreamingVc331 (Kotlin murni,
//      tanpa op Rust baru — tidak ada perubahan JNI) + dipakai di
//      sanitizePartial: POTONG DULU di pembuka DSML pertama yang belum
//      berpenutup, BARU strip final (blok lengkap tidak dipotong).
//  (4) RUST_CORE_VERSION tetap 10 (tidak ada perubahan JNI).
// CATATAN BATAS (jujur, di luar fix ini): lane 1-1 memakai
// ApiToolLayer.parseWithRepair yang TIDAK kenal DSML — di lane itu respons
// DSML tetap dieksekusi hanya invoke pertamanya (jalur tunggal
// extractToolCall); plural di atas berlaku untuk lane room/tim/latar/grup.
// Strip final juga masih memakai penjaga '{' untuk blok tak berpenutup
// (prosa contoh tidak boleh hilang) — blok DSML terpotong TANPA '{' di
// teks final masih bisa menyisakan nilai parameter telanjang.
// Argumen: [0] = direktori tree yang diuji. Keluar 0 = semua lulus.
package com.senzwh.deepharness

import java.io.File
import kotlin.system.exitProcess

private var passed = 0
private var failed = 0

private fun check(id: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
        println("LULUS  $id" + if (detail.isNotBlank()) " — $detail" else "")
    } else {
        failed++
        println("GAGAL  $id" + if (detail.isNotBlank()) " — $detail" else "")
    }
}

fun main(args: Array<String>) {
    val tree = File(if (args.isNotEmpty()) args[0] else ".").absoluteFile
    fun src(name: String): String = File(tree, name).readText()
    val apSrc = src("AgentPrompt.kt")
    val maSrc = src("MainActivity.kt")
    fun findUp(start: File, rel: String): String {
        var d: File? = start
        while (d != null) {
            val f = File(d, rel)
            if (f.exists()) return f.readText()
            d = d.parentFile
        }
        return ""
    }
    val jniSrc = findUp(tree, "harness-core/src/jni_glue.rs")

    // Teks persis bocoran POVA 5 (tanpa wrap baris — wrap "/sdcard/" +
    // "SHAREit/..." di screenshot hanya wrap tampilan).
    val leaked = "< | DSML | calls>\n< | DSML | invoke name=\"list_files\">\n< | DSML | parameter name=\"path\">/sdcard/SHAREit/download</parameter>\n</ | DSML | invoke>\n< | DSML | invoke name=\"list_files\">\n< | DSML | parameter name=\"path\">/sdcard/SHAREit/videos</parameter>\n</ | DSML | invoke>\n< | DSML | invoke name=\"storage_info\">\n< | DSML | parameter name=\"path\">/sdcard/SHAREit/download</parameter>\n</ | DSML | invoke>\n< | DSML | invoke name=\"storage_info\">\n< | DSML | parameter name=\"path\">/sdcard/SHAREit/videos</parameter>\n</ | DSML | invoke>\n</ | DSML | calls>"

    println("== §1. Strip final: teks bocoran POVA 5 hilang total ==")
    run {
        val clean = AgentPrompt.stripRawToolMarkupForDisplay(leaked)
        check("D1 strip blok POVA-5 ASCII utuh -> kosong", clean.isBlank() && !clean.contains("DSML") && !clean.contains("SHAREit") && !clean.contains("parameter"), "hasil=[$clean]")
    }
    run {
        val clean = AgentPrompt.stripRawToolMarkupForDisplay("cek dulu\n$leaked\nselesai")
        check("D2 strip blok ber-spasi di tengah prosa: prosa kept", clean.contains("cek dulu") && clean.contains("selesai") && !clean.contains("DSML") && !clean.contains("SHAREit"), "hasil=[$clean]")
    }
    run {
        val u3000 = leaked.replace(' ', '\u3000')
        val clean = AgentPrompt.stripRawToolMarkupForDisplay(u3000)
        check("D3 strip varian spasi fullwidth U+3000 -> kosong", clean.isBlank(), "hasil=[$clean]")
    }
    run {
        val u2502 = leaked.replace('|', '\u2502')
        val clean = AgentPrompt.stripRawToolMarkupForDisplay(u2502)
        check("D4 strip varian pipe U+2502 -> kosong", clean.isBlank(), "hasil=[$clean]")
    }
    run {
        val u2223 = leaked.replace('|', '\u2223')
        val clean = AgentPrompt.stripRawToolMarkupForDisplay(u2223)
        check("D5 strip varian pipe U+2223 -> kosong", clean.isBlank(), "hasil=[$clean]")
    }
    run {
        val nbsp = leaked.replace(' ', '\u00A0')
        val clean = AgentPrompt.stripRawToolMarkupForDisplay(nbsp)
        check("D6 strip varian NBSP U+00A0 -> kosong", clean.isBlank(), "hasil=[$clean]")
    }
    run {
        val angle = leaked.replace('<', '\u3008').replace('>', '\u3009')
        val clean = AgentPrompt.stripRawToolMarkupForDisplay(angle)
        check("D7 strip varian sudut U+3008/U+3009 -> kosong", clean.isBlank(), "hasil=[$clean]")
    }
    run {
        val norm = AgentPrompt.normalizeDsmlDelimiters("a\u3000b\u00A0c\u2003d\u2502e\u2223f\u3008g\u3009h")
        check("D8 normalize look-alike -> ASCII", norm == "a b c d|e|f<g>h", "hasil=[$norm]")
    }

    println("== §2. Jalur eksekusi tidak berubah ==")
    run {
        val calls = AgentPrompt.extractToolCalls(leaked)
        val ok = calls.size == 4 &&
            calls[0].optString("tool") == "list_files" && calls[0].optJSONObject("args")?.optString("path") == "/sdcard/SHAREit/download" &&
            calls[1].optString("tool") == "list_files" && calls[1].optJSONObject("args")?.optString("path") == "/sdcard/SHAREit/videos" &&
            calls[2].optString("tool") == "storage_info" && calls[2].optJSONObject("args")?.optString("path") == "/sdcard/SHAREit/download" &&
            calls[3].optString("tool") == "storage_info" && calls[3].optJSONObject("args")?.optString("path") == "/sdcard/SHAREit/videos"
        check("D9 extractToolCalls POVA-5: 4 call urut + argumen utuh", ok, "calls=$calls")
    }
    run {
        val (visible, obj) = AgentPrompt.extractToolCall(leaked)
        val u3000 = leaked.replace(' ', '\u3000')
        val parsed3000 = AgentPrompt.extractDsmlCall(u3000)
        check("D10 extractToolCall POVA-5: call pertama jalan + tampil kosong", obj != null && obj.optString("tool") == "list_files" && visible.isBlank(), "obj=$obj visible=[$visible]")
        check("D10b extractDsmlCall varian U+3000 ikut terparse", parsed3000 != null && parsed3000.optString("tool") == "list_files" && parsed3000.optJSONObject("args")?.optString("path") == "/sdcard/SHAREit/download", "parsed=$parsed3000")
    }

    println("== §3. Jalur streaming tidak pernah tampilkan DSML ==")
    run {
        val partial = "Proses dulu < | DSML | calls>\n< | DSML | invoke name=\"list_files\">\n< | DSML | parameter name=\"path\">/sdcard/SHAREit/download"
        val cut = AgentPrompt.stripForStreamingVc331(partial)
        check("D11 streaming blok terpotong (tanpa penutup, tanpa '{'): prosa kept, blok hilang", cut == "Proses dulu" && !cut.contains("DSML") && !cut.contains("sdcard"), "hasil=[$cut]")
    }
    run {
        val full = "cek $leaked selesai"
        val cut = AgentPrompt.stripForStreamingVc331(full)
        check("D12 streaming blok lengkap di tengah prosa: hilang, prosa kept", cut.contains("cek") && cut.contains("selesai") && !cut.contains("DSML") && !cut.contains("SHAREit"), "hasil=[$cut]")
    }
    run {
        val plain = "Halo biasa tanpa markup."
        check("D13a streaming prosa biasa tidak berubah", AgentPrompt.stripForStreamingVc331(plain) == plain, "")
        check("D13b sanitizePartial memakai stripForStreamingVc331", "AgentPrompt.stripForStreamingVc331(t)" in maSrc, "")
    }

    println("== §4. Pengaman: identitas + idempotence + tanpa perubahan JNI ==")
    run {
        val once = AgentPrompt.stripRawToolMarkupForDisplay(leaked)
        check("D14 strip idempotent", AgentPrompt.stripRawToolMarkupForDisplay(once) == once, "")
        val plain = "Halo, ini jawaban biasa tanpa markup."
        val prose = "Format DSML dipakai DeepSeek untuk tool call."
        check("D15 prosa biasa + sebutan DSML tanpa kurung tidak berubah", AgentPrompt.stripRawToolMarkupForDisplay(plain) == plain && AgentPrompt.stripRawToolMarkupForDisplay(prose) == prose, "")
    }
    run {
        check("D16 RUST_CORE_VERSION tetap 10 + helper streaming Kotlin murni (tanpa op Rust baru)", "RUST_CORE_VERSION: jint = 10" in jniSrc && "stripForStreaming" !in jniSrc, "")
    }

    println()
    println("HASIL: $passed lulus, $failed gagal (total ${passed + failed})")
    if (failed > 0) exitProcess(1)
}
