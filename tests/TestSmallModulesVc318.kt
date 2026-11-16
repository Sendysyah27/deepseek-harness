// TestSmallModulesVc318.kt — tes paritas JVM wrapper slice 4 (vc318).
//
// Yang diuji (235 cek):
//  1. NotifIds: 17 konstanta verbatim + all() (12, urutan) + checkDuplicates() kosong.
//  2. Vektor String.hashCode() Java (termasuk emoji/surrogate pair) —
//     ground truth JVM sendiri, bukan modul.
//  3. Alokator NotifIds: nilai Rust-test ("jadwal", "worker-1",
//     "tugas-abc", "worker-42") + basis string kosong + rentang +
//     tidak menabrak registry.
//  4. SystemBarSpecVc309: spec dark/light verbatim + silang token ChatTheme.
//  5. DsSafetyStrip: teks polos; blok penuh (Mandarin + Safety di dalam/di
//     luar tag, multiline, atribut, case-insensitive); close stray; open
//     tanpa tutup; blank-run; sebutan tanpa tag; idempoten; kelas kata
//     ASCII ala Java (café, <ds_safetyfoo>, <ds_safetyé>); trim() Kotlin
//     == isWhitespace (NBSP tepi DIBUANG, NBSP tengah dipertahankan).
//  6. McpHelper: SCRIPT_B64 verbatim (panjang 2352, prefix/suffix,
//     alfabet base64).
//  7. SessionBackgroundVc304: renderAllowed / shouldParkDecision (semua
//     kombinasi) + backgroundNotice() verbatim.
//  8. Behaviors: 6 pak verbatim (id/nama/desk) + byId fallback pak pertama
//     + block() ("" untuk senz/tak dikenal, +"\n\n" untuk sisanya).
//  9. ChatThemeVc306: 27 warna hex + 11 angka verbatim (+ 3 nilai i32
//     desimal independen + alias darkCodeBg) + rasio kontras (hitam-putih
//     21.0, sama 1.0, simetris, 4 pasangan tema) + isAaOk lolos/gagal.
// 10. Seam + fallback dipertahankan: 7 saklar default true,
//     RustCore.VERSION == 6, 7 external fun di RustCore.kt, fallback
//     *Kotlin dipertahankan per file, .so absen di JVM ->
//     RustCore.available false (jalur fallback teruji),
//     PrivPairNotif.NOTIF_ID getter (bukan const) + nilai 7708,
//     Flavor.kt TIDAK disentuh (tanpa Rust).
//
// Di JVM .so TIDAK ada, jadi yang diuji di sini = jalur fallback Kotlin
// wrapper == perilaku Kotlin lama. Jalur Rust diuji cargo test (174/174)
// + di perangkat (TES-VC318: rustCoreVersion()==2, 7 simbol bulk baru).
// Perilaku ASLI Kotlin yang direproduksi 1:1 (JANGAN "diperbaiki"):
// hashCode Java atas unit UTF-16 (emoji = 2 unit); trim() Kotlin tanpa
// argumen == `trim { it.isWhitespace() }` (TERBUKTI probe JVM kotlinc
// 1.9.20 + JDK 21: NBSP tepi DIBUANG — klaim PATCH-S4 §4.3 "<= 0x20"
// SALAH; Rust kotlin_trim diperbaiki integrator ke himpunan Java
// Character.isWhitespace); regex \w/\b Java = ASCII (café -> "caf"
// termakan, <ds_safetyé> dikenali sebagai tag, <ds_safetyfoo> bukan tag);
// Behaviors.block() menambah "\n\n" untuk pak berisi.
//
// Jalankan (dari vc317-work): kotlinc TestSmallModulesVc318.kt -cp
// work-impl/test-vc318-tree-integrated.jar -include-runtime -d
// work-impl/test-p4-vc318-coord.jar && java -cp ... TestSmallModulesVc318Kt
// work-impl/integrated
// Keluar 0 = semua lulus; selain itu daftar GAGAL + exit 1.
package com.senzwh.deepharness

import com.senzwh.deepharness.priv.PrivPairNotif
import harness.core.RustCore
import java.io.File
import kotlin.system.exitProcess

var passed = 0
var failed = 0
val failedNames = ArrayList<String>()

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
        println("LULUS  $name")
    } else {
        failed++
        failedNames.add(name)
        println("GAGAL  $name  $detail")
    }
}

fun closeTo(a: Double, b: Double, tol: Double = 1e-9): Boolean =
    kotlin.math.abs(a - b) < tol

fun main(args: Array<String>) {
    val workDir = if (args.isNotEmpty()) args[0] else "."
    fun src(name: String): String = File(workDir, name).readText()
    // RustCore.kt: tree FLAT lokal (work-impl/integrated/harness/...) vs
    // tree REPO di CI (app/src/main/java/harness/... = naik 3 level
    // dari .../java/com/senzwh/deepharness). Pakai yang ada.
    val rustCoreFile = listOf(
        File(workDir, "harness/core/RustCore.kt"),
        File(workDir, "../../../harness/core/RustCore.kt")
    ).firstOrNull { it.exists() } ?: File(workDir, "harness/core/RustCore.kt")
    val rustCoreSrc = rustCoreFile.readText()

    // ================= 1. NotifIds: 17 konstanta + all() + duplikat =================
    val consts = listOf(
        "SCHEDULE" to 7701, "PARK_QUEUE" to 7703, "SECOND_CONFIRM" to 7704,
        "MEMORY_PROPOSAL" to 7706, "PRIV_PAIR" to 7708,
        "BG_SUMMARY_BASE" to 7790, "KILL_SWITCH_CANCEL" to 7798,
        "GOVERNOR_DELAY" to 7799, "SERVE_DOWN" to 7103,
        "HEAL_DIAGNOSIS" to 7102, "GROUP_DONE" to 7900, "MITM_DOWN" to 7722,
        "BG_WATCHDOG" to 7723, "BG_WATCHDOG_BASE" to 8400,
        "WORKER_JANITOR_BASE" to 8800, "BG_RESULT_BASE" to 7800,
        "BG_FAIL_BASE" to 8100
    )
    val gotConsts = mapOf(
        "SCHEDULE" to NotifIds.SCHEDULE, "PARK_QUEUE" to NotifIds.PARK_QUEUE,
        "SECOND_CONFIRM" to NotifIds.SECOND_CONFIRM,
        "MEMORY_PROPOSAL" to NotifIds.MEMORY_PROPOSAL,
        "PRIV_PAIR" to NotifIds.PRIV_PAIR,
        "BG_SUMMARY_BASE" to NotifIds.BG_SUMMARY_BASE,
        "KILL_SWITCH_CANCEL" to NotifIds.KILL_SWITCH_CANCEL,
        "GOVERNOR_DELAY" to NotifIds.GOVERNOR_DELAY,
        "SERVE_DOWN" to NotifIds.SERVE_DOWN,
        "HEAL_DIAGNOSIS" to NotifIds.HEAL_DIAGNOSIS,
        "GROUP_DONE" to NotifIds.GROUP_DONE, "MITM_DOWN" to NotifIds.MITM_DOWN,
        "BG_WATCHDOG" to NotifIds.BG_WATCHDOG,
        "BG_WATCHDOG_BASE" to NotifIds.BG_WATCHDOG_BASE,
        "WORKER_JANITOR_BASE" to NotifIds.WORKER_JANITOR_BASE,
        "BG_RESULT_BASE" to NotifIds.BG_RESULT_BASE,
        "BG_FAIL_BASE" to NotifIds.BG_FAIL_BASE
    )
    for ((name, expect) in consts) {
        check("1. konstanta NotifIds.$name == $expect", gotConsts[name] == expect, "dapat=${gotConsts[name]}")
    }
    val all = NotifIds.all()
    check("1. all() berisi 12 entri", all.size == 12, "dapat=${all.size}")
    check("1. all()[0] == SCHEDULE/7701", all[0] == ("SCHEDULE" to 7701), "dapat=${all[0]}")
    check("1. all()[4] == PRIV_PAIR/7708", all[4] == ("PRIV_PAIR" to 7708), "dapat=${all[4]}")
    check("1. all()[11] == BG_WATCHDOG/7723", all[11] == ("BG_WATCHDOG" to 7723), "dapat=${all[11]}")
    check("1. checkDuplicates() kosong", NotifIds.checkDuplicates().isEmpty(), "dapat=${NotifIds.checkDuplicates()}")

    // ================= 2. Vektor hashCode Java =================
    check("2. hashCode(\"test\") == 3556498 (nilai Java dikenal)", "test".hashCode() == 3556498, "dapat=${"test".hashCode()}")
    check("2. hashCode(\"\") == 0", "".hashCode() == 0)
    check("2. hashCode(\"a\") == 97", "a".hashCode() == 97)
    check("2. hashCode(\"jadwal\") == -1167599179", "jadwal".hashCode() == -1167599179, "dapat=${"jadwal".hashCode()}")
    check("2. hashCode(\"worker-1\") == 35352994", "worker-1".hashCode() == 35352994, "dapat=${"worker-1".hashCode()}")
    check("2. hashCode(emoji+\"emoji\") == -1233692477 (surrogate pair = 2 unit UTF-16)", "\uD83D\uDE00emoji".hashCode() == -1233692477, "dapat=${"\uD83D\uDE00emoji".hashCode()}")

    // ================= 3. Alokator NotifIds =================
    check("3. bgResultId(\"jadwal\") == 7981", NotifIds.bgResultId("jadwal") == 7981, "dapat=${NotifIds.bgResultId("jadwal")}")
    check("3. bgFailId(\"jadwal\") == 8281", NotifIds.bgFailId("jadwal") == 8281, "dapat=${NotifIds.bgFailId("jadwal")}")
    check("3. bgSummaryId(\"jadwal\") == 7795", NotifIds.bgSummaryId("jadwal") == 7795, "dapat=${NotifIds.bgSummaryId("jadwal")}")
    check("3. bgResultId(\"worker-1\") == 7962", NotifIds.bgResultId("worker-1") == 7962, "dapat=${NotifIds.bgResultId("worker-1")}")
    check("3. bgFailId(\"worker-1\") == 8262", NotifIds.bgFailId("worker-1") == 8262, "dapat=${NotifIds.bgFailId("worker-1")}")
    check("3. bgSummaryId(\"worker-1\") == 7792", NotifIds.bgSummaryId("worker-1") == 7792, "dapat=${NotifIds.bgSummaryId("worker-1")}")
    check("3. bgWatchdogId(\"tugas-abc\") == 8605", NotifIds.bgWatchdogId("tugas-abc") == 8605, "dapat=${NotifIds.bgWatchdogId("tugas-abc")}")
    check("3. workerJanitorId(\"worker-1\") == 8962", NotifIds.workerJanitorId("worker-1") == 8962, "dapat=${NotifIds.workerJanitorId("worker-1")}")
    check("3. workerJanitorId(\"worker-42\") == 8845", NotifIds.workerJanitorId("worker-42") == 8845, "dapat=${NotifIds.workerJanitorId("worker-42")}")
    check("3. bgResultId(\"\") == basis 7800", NotifIds.bgResultId("") == 7800)
    check("3. bgFailId(\"\") == basis 8100", NotifIds.bgFailId("") == 8100)
    check("3. bgSummaryId(\"\") == basis 7790", NotifIds.bgSummaryId("") == 7790)
    check("3. bgWatchdogId(\"\") == basis 8400", NotifIds.bgWatchdogId("") == 8400)
    check("3. workerJanitorId(\"\") == basis 8800", NotifIds.workerJanitorId("") == 8800)
    for (origin in listOf("jadwal", "tugas-abc", "Tugas latar", "\uD83D\uDE00emoji")) {
        val r = NotifIds.bgResultId(origin)
        val s = NotifIds.bgSummaryId(origin)
        check("3. rentang bgResultId(\"$origin\") 7800..8055", r in 7800..8055, "dapat=$r")
        check("3. rentang bgSummaryId(\"$origin\") 7790..7797", s in 7790..7797, "dapat=$s")
    }
    val registry = all.map { it.second }.toSet()
    for (origin in listOf("jadwal", "BgBrain")) {
        check("3. bgResultId(\"$origin\") tidak menabrak registry", NotifIds.bgResultId(origin) !in registry)
        check("3. bgFailId(\"$origin\") tidak menabrak registry", NotifIds.bgFailId(origin) !in registry)
        check("3. bgSummaryId(\"$origin\") tidak menabrak registry", NotifIds.bgSummaryId(origin) !in registry)
        check("3. bgResultId(\"$origin\") != GROUP_DONE 7900", NotifIds.bgResultId(origin) != 7900)
        check("3. bgFailId(\"$origin\") != GROUP_DONE 7900", NotifIds.bgFailId(origin) != 7900)
        check("3. bgSummaryId(\"$origin\") != GROUP_DONE 7900", NotifIds.bgSummaryId(origin) != 7900)
    }

    // ================= 4. SystemBarSpecVc309 =================
    val dark = SystemBarSpecVc309.spec(true)
    val light = SystemBarSpecVc309.spec(false)
    check("4. spec gelap: statusColor == 0xFF1D222C", dark.statusColor == 0xFF1D222C.toInt(), "dapat=${dark.statusColor}")
    check("4. spec gelap: navColor == 0xFF1C1F28", dark.navColor == 0xFF1C1F28.toInt(), "dapat=${dark.navColor}")
    check("4. spec gelap: lightIcons == true", dark.lightIcons)
    check("4. spec cerah: statusColor == 0xFFEBF1FE", light.statusColor == 0xFFEBF1FE.toInt(), "dapat=${light.statusColor}")
    check("4. spec cerah: navColor == 0xFFFBFCFF", light.navColor == 0xFFFBFCFF.toInt(), "dapat=${light.navColor}")
    check("4. spec cerah: lightIcons == false", !light.lightIcons)
    check("4. spec gelap != spec cerah", dark != light)
    check("4. silang: status gelap == ChatTheme.darkBgTop", dark.statusColor == ChatThemeVc306.darkBgTop)
    check("4. silang: nav gelap == ChatTheme.darkSurface", dark.navColor == ChatThemeVc306.darkSurface)
    check("4. silang: status cerah == ChatTheme.lightBgTop", light.statusColor == ChatThemeVc306.lightBgTop)
    check("4. silang: nav cerah == ChatTheme.lightCard", light.navColor == ChatThemeVc306.lightCard)

    // ================= 5. DsSafetyStrip =================
    check("5. teks polos utuh", DsSafetyStrip.strip("Jawaban biasa tanpa tag.") == "Jawaban biasa tanpa tag.")
    check("5. strip(\"\") == \"\"", DsSafetyStrip.strip("") == "")
    check("5. strip(spasi saja) == \"\"", DsSafetyStrip.strip("   ") == "")
    check(
        "5. blok penuh: Mandarin + Safety di dalam tag",
        DsSafetyStrip.strip("Jawaban <ds_safety>[分类]色情 Safety: safe</ds_safety> lanjut") == "Jawaban  lanjut",
        "dapat=[${DsSafetyStrip.strip("Jawaban <ds_safety>[分类]色情 Safety: safe</ds_safety> lanjut")}]"
    )
    check(
        "5. blok penuh: Safety di luar tag",
        DsSafetyStrip.strip("Jawaban <ds_safety>[分类]色情</ds_safety>Safety: safe lanjut") == "Jawaban  lanjut",
        "dapat=[${DsSafetyStrip.strip("Jawaban <ds_safety>[分类]色情</ds_safety>Safety: safe lanjut")}]"
    )
    check("5. blok penuh di awal", DsSafetyStrip.strip("<ds_safety>xxx</ds_safety> Jawaban") == "Jawaban")
    check(
        "5. blok multiline + atribut",
        DsSafetyStrip.strip("A <ds_safety foo=\"bar\">\nbaris1\nbaris2\n</ds_safety> B") == "A  B",
        "dapat=[${DsSafetyStrip.strip("A <ds_safety foo=\"bar\">\nbaris1\nbaris2\n</ds_safety> B")}]"
    )
    check("5. blok case-insensitive (UPPER)", DsSafetyStrip.strip("A <DS_SAFETY>x</DS_SAFETY> B") == "A  B")
    check("5. blok case-insensitive (campur)", DsSafetyStrip.strip("A <Ds_Safety>x</Ds_Safety> B") == "A  B")
    check("5. close stray dibuang", DsSafetyStrip.strip("teks </ds_safety> lanjut") == "teks  lanjut")
    check("5. close stray + Safety dibuang", DsSafetyStrip.strip("teks </ds_safety>Safety: safe lanjut") == "teks  lanjut")
    check("5. open tanpa tutup: sisa dibuang", DsSafetyStrip.strip("awal <ds_safety foo=bar> sisa hilang") == "awal")
    check("5. open polos tanpa tutup: sisa dibuang", DsSafetyStrip.strip("awal <ds_safety> sisa hilang") == "awal")
    check("5. blank-run 4 newline -> 2", DsSafetyStrip.strip("a\n\n\n\nb") == "a\n\nb")
    check("5. blank-run campuran", DsSafetyStrip.strip("a\n\n\nb\n\n\n\n\nc") == "a\n\nb\n\nc")
    check("5. dua newline dipertahankan", DsSafetyStrip.strip("a\n\nb") == "a\n\nb")
    check("5. trim ujung", DsSafetyStrip.strip("\n\n  teks  \n\n") == "teks")
    check("5. sebutan ds_safety tanpa tag utuh", DsSafetyStrip.strip("ds_safety disebut di sini") == "ds_safety disebut di sini")
    check("5. Safety: safe tanpa tag utuh", DsSafetyStrip.strip("Safety: safe tanpa tag") == "Safety: safe tanpa tag")
    val idemCases = listOf(
        "Jawaban <ds_safety>[分类]色情</ds_safety>Safety: safe lanjut",
        "teks </ds_safety> lanjut",
        "awal <ds_safety> sisa hilang",
        "a\n\n\n\nb",
        "Jawaban biasa"
    )
    for ((i, c) in idemCases.withIndex()) {
        check("5. idempoten #${i + 1}", DsSafetyStrip.strip(DsSafetyStrip.strip(c)) == DsSafetyStrip.strip(c), "kasus=[$c]")
    }
    check(
        "5. kelas kata ASCII: Safety: café -> caf termakan, é tersisa",
        DsSafetyStrip.strip("x <ds_safety>t</ds_safety> Safety: café y") == "x é y",
        "dapat=[${DsSafetyStrip.strip("x <ds_safety>t</ds_safety> Safety: café y")}]"
    )
    check(
        "5. <ds_safetyfoo> BUKAN tag (tidak disentuh)",
        DsSafetyStrip.strip("A <ds_safetyfoo>x</ds_safetyfoo> B") == "A <ds_safetyfoo>x</ds_safetyfoo> B"
    )
    check(
        "5. <ds_safetyé> ADALAH tag (batas kata Java) -> sisa dibuang",
        DsSafetyStrip.strip("A <ds_safetyé>x</ds_safetyé> B") == "A",
        "dapat=[${DsSafetyStrip.strip("A <ds_safetyé>x</ds_safetyé> B")}]"
    )
    check(
        "5. trim() Kotlin: NBSP tepi DIBUANG (isWhitespace, bukan <= 0x20)",
        DsSafetyStrip.strip("\u00A0teks\u00A0") == "teks",
        "dapat=[${DsSafetyStrip.strip("\u00A0teks\u00A0")}]"
    )
    check(
        "5. trim() Kotlin: NBSP tengah dipertahankan",
        DsSafetyStrip.strip("a\u00A0b") == "a\u00A0b",
        "dapat=[${DsSafetyStrip.strip("a\u00A0b")}]"
    )
    check("5. trim() Kotlin: spasi biasa dibuang", DsSafetyStrip.strip("  teks  ") == "teks")

    // ================= 6. McpHelper =================
    check("6. SCRIPT_B64 panjang 2352", McpHelper.SCRIPT_B64.length == 2352, "dapat=${McpHelper.SCRIPT_B64.length}")
    check(
        "6. SCRIPT_B64 prefix",
        McpHelper.SCRIPT_B64.startsWith("aW1wb3J0IHN1YnByb2Nlc3MsIHN5cywganNvbiwgYmFzZTY0LCBzaGxleCwgdGhyZWFkaW5nLCBxdWV1")
    )
    check(
        "6. SCRIPT_B64 suffix",
        McpHelper.SCRIPT_B64.endsWith("XSBpZiB0ZXh0cyBlbHNlIGpzb24uZHVtcHMocmVzLmdldCgicmVzdWx0Iiwge30pKVs6NjAwMF0pCg==")
    )
    check(
        "6. SCRIPT_B64 hanya alfabet base64",
        McpHelper.SCRIPT_B64.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    )
    check("6. SCRIPT_B64 diakhiri padding =", McpHelper.SCRIPT_B64.endsWith("="))

    // ================= 7. SessionBackgroundVc304 =================
    check("7. renderAllowed(5, 5) == true", SessionBackgroundVc304.renderAllowed(5, 5))
    check("7. renderAllowed(5, 6) == false", !SessionBackgroundVc304.renderAllowed(5, 6))
    check("7. renderAllowed(6, 5) == false", !SessionBackgroundVc304.renderAllowed(6, 5))
    check("7. renderAllowed(0, 0) == true", SessionBackgroundVc304.renderAllowed(0, 0))
    check("7. renderAllowed(-1, -1) == true", SessionBackgroundVc304.renderAllowed(-1, -1))
    check("7. renderAllowed(MAX, MIN) == false", !SessionBackgroundVc304.renderAllowed(Long.MAX_VALUE, Long.MIN_VALUE))
    check("7. parkir: aktif di sesi lain", SessionBackgroundVc304.shouldParkDecision(true, 5, 6))
    check("7. parkir: aktif di sesi sendiri -> tampil", !SessionBackgroundVc304.shouldParkDecision(true, 5, 5))
    check("7. parkir: tidak aktif di sesi lain", !SessionBackgroundVc304.shouldParkDecision(false, 5, 6))
    check("7. parkir: tidak aktif di sesi sendiri", !SessionBackgroundVc304.shouldParkDecision(false, 5, 5))
    check(
        "7. backgroundNotice() verbatim",
        SessionBackgroundVc304.backgroundNotice() == "● Giliran sesi lain berjalan di background — kembali ke sesi itu untuk melihat progres.",
        "dapat=[${SessionBackgroundVc304.backgroundNotice()}]"
    )

    // ================= 8. Behaviors =================
    check("8. PACKS berisi 6 pak", Behaviors.PACKS.size == 6, "dapat=${Behaviors.PACKS.size}")
    val packIds = listOf("senz", "mandor", "trading", "kasir", "tutor", "agen4")
    val packNames = listOf("Senz (bawaan)", "Mandor Produksi", "Mentor Trading", "Asisten Kasir", "Tutor Sabar", "Agen 4 — Generalis")
    val packDescs = listOf(
        "Persona murni, tanpa tambahan gaya.",
        "Tegas, berbasis checkpoint & angka.",
        "Belajar trading spot, anti-leverage, disiplin jurnal.",
        "Catat transaksi cepat, format rapi.",
        "Mengajar bertahap dengan contoh konkret.",
        "Serbaguna: riset singkat + eksekusi langsung, laporan ringkas."
    )
    for (i in 0..5) {
        check("8. PACKS[$i].id == ${packIds[i]}", Behaviors.PACKS[i].id == packIds[i], "dapat=${Behaviors.PACKS[i].id}")
        check("8. PACKS[$i].name == ${packNames[i]}", Behaviors.PACKS[i].name == packNames[i], "dapat=${Behaviors.PACKS[i].name}")
        check("8. PACKS[$i].desc verbatim", Behaviors.PACKS[i].desc == packDescs[i], "dapat=${Behaviors.PACKS[i].desc}")
    }
    check("8. blok senz kosong", Behaviors.PACKS[0].block == "")
    val mandorBlock = "PAK PERILAKU — MANDOR PRODUKSI:\n" +
        "- Bekerja berbasis checkpoint: buat rencana dulu (make_plan), kerjakan per langkah, tandai selesai per langkah.\n" +
        "- Laporan selalu berisi ANGKA dan STATUS: apa yang selesai, apa yang sisa, apa yang gagal + alasannya.\n" +
        "- Dilarang mengklaim selesai tanpa bukti hasil tool.\n" +
        "- Nada: singkat, tegas, tanpa basa-basi."
    check(
        "8. block(\"mandor\") == blok verbatim + \"\\n\\n\"",
        Behaviors.block("mandor") == mandorBlock + "\n\n",
        "dapat=[${Behaviors.block("mandor")}]"
    )
    check("8. block(\"trading\") diawali header mentor", Behaviors.block("trading").startsWith("PAK PERILAKU — MENTOR TRADING:"))
    check("8. block(\"trading\") diakhiri \"\\n\\n\"", Behaviors.block("trading").endsWith("\n\n"))
    for (id in listOf("kasir", "tutor", "agen4")) {
        check("8. block(\"$id\") tidak kosong + diakhiri \"\\n\\n\"", Behaviors.block(id).isNotEmpty() && Behaviors.block(id).endsWith("\n\n"))
    }
    check("8. byId(\"mandor\").id == mandor", Behaviors.byId("mandor").id == "mandor")
    check("8. byId(\"trading\").name == Mentor Trading", Behaviors.byId("trading").name == "Mentor Trading")
    check("8. byId tak dikenal -> pak pertama (senz)", Behaviors.byId("tidak-ada").id == "senz")
    check("8. byId(\"\") -> pak pertama (senz)", Behaviors.byId("").id == "senz")
    check("8. block(\"senz\") == \"\"", Behaviors.block("senz") == "")
    check("8. block tak dikenal == \"\" (fallback senz)", Behaviors.block("tidak-ada") == "")

    // ================= 9. ChatThemeVc306 =================
    val colors = listOf(
        "darkBgTop" to 0xFF1D222C.toInt(), "darkBgBottom" to 0xFF1B1C23.toInt(),
        "darkSurface" to 0xFF1C1F28.toInt(), "darkContainerLow" to 0xFF14171E.toInt(),
        "darkContainerHigh" to 0xFF343944.toInt(), "darkText" to 0xFFE6E9F0.toInt(),
        "darkDim" to 0xFFB6BBC5.toInt(), "darkFaint" to 0xFF9A9EA9.toInt(),
        "darkStroke" to 0xFF343841.toInt(), "darkTileBg" to 0xFF232730.toInt(),
        "darkUserBubble" to 0xFF293144.toInt(), "darkAccentText" to 0xFF97B1F0.toInt(),
        "darkCodeText" to 0xFFBEE0FF.toInt(), "lightCodeText" to 0xFF173F65.toInt(),
        "darkCodeBg" to 0xFF14171E.toInt(), "lightCodeBg" to 0xFFEDF0F6.toInt(),
        "lightBgTop" to 0xFFEBF1FE.toInt(), "lightBgBottom" to 0xFFF6F7FC.toInt(),
        "lightSurface" to 0xFFF0F4FD.toInt(), "lightText" to 0xFF1E232E.toInt(),
        "lightDim" to 0xFF4F5562.toInt(), "lightFaint" to 0xFF646974.toInt(),
        "lightCard" to 0xFFFBFCFF.toInt(), "lightUserBubble" to 0xFFD9E4FD.toInt(),
        "accentSolid" to 0xFF4B67B4.toInt(), "accentPressed" to 0xFF3D569D.toInt()
    )
    val gotColors = mapOf(
        "darkBgTop" to ChatThemeVc306.darkBgTop, "darkBgBottom" to ChatThemeVc306.darkBgBottom,
        "darkSurface" to ChatThemeVc306.darkSurface, "darkContainerLow" to ChatThemeVc306.darkContainerLow,
        "darkContainerHigh" to ChatThemeVc306.darkContainerHigh, "darkText" to ChatThemeVc306.darkText,
        "darkDim" to ChatThemeVc306.darkDim, "darkFaint" to ChatThemeVc306.darkFaint,
        "darkStroke" to ChatThemeVc306.darkStroke, "darkTileBg" to ChatThemeVc306.darkTileBg,
        "darkUserBubble" to ChatThemeVc306.darkUserBubble, "darkAccentText" to ChatThemeVc306.darkAccentText,
        "darkCodeText" to ChatThemeVc306.darkCodeText, "lightCodeText" to ChatThemeVc306.lightCodeText,
        "darkCodeBg" to ChatThemeVc306.darkCodeBg, "lightCodeBg" to ChatThemeVc306.lightCodeBg,
        "lightBgTop" to ChatThemeVc306.lightBgTop, "lightBgBottom" to ChatThemeVc306.lightBgBottom,
        "lightSurface" to ChatThemeVc306.lightSurface, "lightText" to ChatThemeVc306.lightText,
        "lightDim" to ChatThemeVc306.lightDim, "lightFaint" to ChatThemeVc306.lightFaint,
        "lightCard" to ChatThemeVc306.lightCard, "lightUserBubble" to ChatThemeVc306.lightUserBubble,
        "accentSolid" to ChatThemeVc306.accentSolid, "accentPressed" to ChatThemeVc306.accentPressed
    )
    for ((name, expect) in colors) {
        check("9. warna ChatTheme.$name verbatim", gotColors[name] == expect, "dapat=${gotColors[name]} expect=$expect")
    }
    val numbers = listOf(
        "rXs" to 6, "rSm" to 10, "rMd" to 14, "rLg" to 20, "rXl" to 28,
        "iconXs" to 16, "iconSm" to 18, "iconMd" to 20, "iconLg" to 22,
        "iconXl" to 24, "touchTarget" to 48
    )
    val gotNumbers = mapOf(
        "rXs" to ChatThemeVc306.rXs, "rSm" to ChatThemeVc306.rSm,
        "rMd" to ChatThemeVc306.rMd, "rLg" to ChatThemeVc306.rLg,
        "rXl" to ChatThemeVc306.rXl, "iconXs" to ChatThemeVc306.iconXs,
        "iconSm" to ChatThemeVc306.iconSm, "iconMd" to ChatThemeVc306.iconMd,
        "iconLg" to ChatThemeVc306.iconLg, "iconXl" to ChatThemeVc306.iconXl,
        "touchTarget" to ChatThemeVc306.touchTarget
    )
    for ((name, expect) in numbers) {
        check("9. angka ChatTheme.$name == $expect", gotNumbers[name] == expect, "dapat=${gotNumbers[name]}")
    }
    check("9. approveBlue == 0xFF2E6BE6", ChatThemeVc306.approveBlue == 0xFF2E6BE6.toInt(), "dapat=${ChatThemeVc306.approveBlue}")
    check("9. DARK_BG_TOP i32 desimal == -14867924", ChatThemeVc306.darkBgTop == -14867924, "dapat=${ChatThemeVc306.darkBgTop}")
    check("9. DARK_TEXT i32 desimal == -1644048", ChatThemeVc306.darkText == -1644048, "dapat=${ChatThemeVc306.darkText}")
    check("9. LIGHT_BG_TOP i32 desimal == -1314306", ChatThemeVc306.lightBgTop == -1314306, "dapat=${ChatThemeVc306.lightBgTop}")
    check("9. alias darkCodeBg == darkContainerLow", ChatThemeVc306.darkCodeBg == ChatThemeVc306.darkContainerLow)
    check("9. kontras hitam-putih == 21.0", closeTo(ChatThemeVc306.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 21.0), "dapat=${ChatThemeVc306.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())}")
    check("9. kontras warna sama == 1.0", closeTo(ChatThemeVc306.contrastRatio(ChatThemeVc306.darkText, ChatThemeVc306.darkText), 1.0))
    val ab = ChatThemeVc306.contrastRatio(ChatThemeVc306.darkText, ChatThemeVc306.darkBgTop)
    val ba = ChatThemeVc306.contrastRatio(ChatThemeVc306.darkBgTop, ChatThemeVc306.darkText)
    check("9. kontras simetris (fg/bg tertukar)", kotlin.math.abs(ab - ba) < 1e-12, "a=$ab b=$ba")
    check("9. kontras darkText/darkBgTop == 13.115315986926786", closeTo(ab, 13.115315986926786), "dapat=$ab")
    check("9. kontras lightText/lightBgTop == 13.891206116589315", closeTo(ChatThemeVc306.contrastRatio(ChatThemeVc306.lightText, ChatThemeVc306.lightBgTop), 13.891206116589315), "dapat=${ChatThemeVc306.contrastRatio(ChatThemeVc306.lightText, ChatThemeVc306.lightBgTop)}")
    check("9. kontras darkText/darkSurface == 13.540972873182085", closeTo(ChatThemeVc306.contrastRatio(ChatThemeVc306.darkText, ChatThemeVc306.darkSurface), 13.540972873182085), "dapat=${ChatThemeVc306.contrastRatio(ChatThemeVc306.darkText, ChatThemeVc306.darkSurface)}")
    check("9. kontras lightText/lightCard == 15.332558737095383", closeTo(ChatThemeVc306.contrastRatio(ChatThemeVc306.lightText, ChatThemeVc306.lightCard), 15.332558737095383), "dapat=${ChatThemeVc306.contrastRatio(ChatThemeVc306.lightText, ChatThemeVc306.lightCard)}")
    check("9. isAaOk darkText/darkBgTop", ChatThemeVc306.isAaOk(ChatThemeVc306.darkText, ChatThemeVc306.darkBgTop))
    check("9. isAaOk darkDim/darkBgTop", ChatThemeVc306.isAaOk(ChatThemeVc306.darkDim, ChatThemeVc306.darkBgTop))
    check("9. isAaOk lightText/lightBgTop", ChatThemeVc306.isAaOk(ChatThemeVc306.lightText, ChatThemeVc306.lightBgTop))
    check("9. isAaOk lightDim/lightBgTop", ChatThemeVc306.isAaOk(ChatThemeVc306.lightDim, ChatThemeVc306.lightBgTop))
    check("9. isAaOk darkText/darkSurface", ChatThemeVc306.isAaOk(ChatThemeVc306.darkText, ChatThemeVc306.darkSurface))
    check("9. isAaOk lightText/lightCard", ChatThemeVc306.isAaOk(ChatThemeVc306.lightText, ChatThemeVc306.lightCard))
    check("9. isAaOk GAGAL darkFaint/containerHigh", !ChatThemeVc306.isAaOk(ChatThemeVc306.darkFaint, ChatThemeVc306.darkContainerHigh))
    check("9. isAaOk GAGAL userBubble/bgTop", !ChatThemeVc306.isAaOk(ChatThemeVc306.darkUserBubble, ChatThemeVc306.darkBgTop))

    // ================= 10. Seam + fallback dipertahankan =================
    check("10. rustNotifIds default true", NotifIds.rustNotifIds)
    check("10. rustSystemBarSpec default true", SystemBarSpecVc309.rustSystemBarSpec)
    check("10. rustDsSafetyStrip default true", DsSafetyStrip.rustDsSafetyStrip)
    check("10. rustMcpHelper default true", McpHelper.rustMcpHelper)
    check("10. rustSessionBackgroundVc304 default true", SessionBackgroundVc304.rustSessionBackgroundVc304)
    check("10. rustBehaviors default true", Behaviors.rustBehaviors)
    check("10. rustChatTheme default true", ChatThemeVc306.rustChatTheme)
    check("10. RustCore.VERSION == 6", RustCore.VERSION == 6, "VERSION=${RustCore.VERSION}")
    check("10. RustCore: external fun notifIdsJson", rustCoreSrc.contains("external fun notifIdsJson(origin: String, key: String, workerId: String): String"))
    check("10. RustCore: external fun systemBarSpecJson", rustCoreSrc.contains("external fun systemBarSpecJson(isDark: Boolean): String"))
    check("10. RustCore: external fun dsSafetyStripJson", rustCoreSrc.contains("external fun dsSafetyStripJson(text: String): String"))
    check("10. RustCore: external fun mcpHelperJson", rustCoreSrc.contains("external fun mcpHelperJson(): String"))
    check("10. RustCore: external fun sessionBackgroundJson", rustCoreSrc.contains("external fun sessionBackgroundJson(turnSessionId: Long, currentSessionId: Long, turnActive: Boolean): String"))
    check("10. RustCore: external fun behaviorsJson", rustCoreSrc.contains("external fun behaviorsJson(id: String): String"))
    check("10. RustCore: external fun chatThemeJson", rustCoreSrc.contains("external fun chatThemeJson(fg: Int, bg: Int): String"))
    check(
        "10. fallback NotifIds dipertahankan (*Kotlin + saklar)",
        src("NotifIds.kt").contains("private fun bgResultIdKotlin") && src("NotifIds.kt").contains("var rustNotifIds")
    )
    check(
        "10. fallback SystemBarSpec dipertahankan (specKotlin + saklar)",
        src("SystemBarSpecVc309.kt").contains("private fun specKotlin") && src("SystemBarSpecVc309.kt").contains("var rustSystemBarSpec")
    )
    check(
        "10. fallback DsSafetyStrip dipertahankan (stripKotlin + saklar)",
        src("DsSafetyStrip.kt").contains("private fun stripKotlin") && src("DsSafetyStrip.kt").contains("var rustDsSafetyStrip")
    )
    check(
        "10. fallback McpHelper dipertahankan (SCRIPT_B64_KOTLIN + saklar)",
        src("McpHelper.kt").contains("private const val SCRIPT_B64_KOTLIN") && src("McpHelper.kt").contains("var rustMcpHelper")
    )
    check(
        "10. fallback SessionBackground dipertahankan (*Kotlin + saklar)",
        src("SessionBackgroundVc304.kt").contains("private fun renderAllowedKotlin") &&
            src("SessionBackgroundVc304.kt").contains("private fun shouldParkDecisionKotlin") &&
            src("SessionBackgroundVc304.kt").contains("private fun backgroundNoticeKotlin") &&
            src("SessionBackgroundVc304.kt").contains("var rustSessionBackgroundVc304")
    )
    check(
        "10. fallback Behaviors dipertahankan (PACKS_KOTLIN + blockKotlin + saklar)",
        src("Behaviors.kt").contains("private val PACKS_KOTLIN") && src("Behaviors.kt").contains("private fun blockKotlin") &&
            src("Behaviors.kt").contains("var rustBehaviors")
    )
    check(
        "10. fallback ChatTheme dipertahankan (contrastRatioKotlin + saklar)",
        src("ChatThemeVc306.kt").contains("private fun contrastRatioKotlin") && src("ChatThemeVc306.kt").contains("var rustChatTheme")
    )
    check(
        "10. .so absen di JVM -> RustCore.available false (jalur fallback teruji)",
        !RustCore.available
    )
    check(
        "10. PrivPairNotif: NOTIF_ID getter (bukan const)",
        src("priv/PrivPairNotif.kt").contains("val NOTIF_ID: Int get() = NotifIds.PRIV_PAIR")
    )
    check(
        "10. PrivPairNotif: tidak ada lagi const val NOTIF_ID",
        !src("priv/PrivPairNotif.kt").contains("const val NOTIF_ID")
    )
    check("10. PrivPairNotif.NOTIF_ID == 7708 saat runtime", PrivPairNotif.NOTIF_ID == 7708, "dapat=${PrivPairNotif.NOTIF_ID}")
    check(
        "10. Flavor.kt TIDAK disentuh (tanpa Rust, IS_PURE tetap ada)",
        src("Flavor.kt").contains("IS_PURE") && !src("Flavor.kt").contains("RustCore") && !src("Flavor.kt").contains("rustFlavor")
    )

    println("\nTOTAL: $passed lulus, $failed gagal")
    if (failedNames.isNotEmpty()) println("GAGAL: " + failedNames.joinToString(", "))
    exitProcess(if (failed == 0) 0 else 1)
}
