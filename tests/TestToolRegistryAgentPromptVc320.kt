// TestToolRegistryAgentPromptVc320.kt — tes paritas slice 6
// (ToolRegistry + AgentPrompt fungsi murni + SkillJudge trigger scoring -> Rust, vc320)
//
// Dua mode dalam satu file; `main(args)`: args[0] = "rust" | "kotlin",
// args[1] = treeDir (direktori sumber .kt, untuk baca konstanta katalog gaya IXL).
//
// MODE "rust" — jalur Rust ASLI via `.so` host
// (`-Djava.library.path=<harness-core/target/debug>`): semua wrapper
// Rust-first; di akhir dicek ketiga saklar masih true (tidak ada fallback
// diam-diam) + capabilities memuat 3 fungsi/seam baru + VERSION 5.
// MODE "kotlin" — jalur fallback Kotlin lama (`*Kotlin`): ketiga saklar
// dimatikan dulu; nilai HARAP SAMA PERSIS seperti mode rust (paritas byte).
//
// Nilai harap = probe JVM atas kode asli vc319 (/tmp/probe6/probe6.log +
// probe6b.log, 16 Nov 2026). Panjang selectCatalog = angka probe (+21 vs
// UKURAN-INPUT-VC316.md §6 karena `full` gaya IXL menyimpan escape \"
// di API_LAYER_NOTE apa adanya — kedua mode memakai `full` yang sama,
// jadi paritas tetap eksak).
//
// Jalankan: via run-tests-vc320-coord.sh (P6) / tests/run-suite-ci.sh P6.
// Keluar 0 = semua lulus.
package com.senzwh.deepharness

import harness.core.RustCore
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
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

fun sha256(s: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    return d.joinToString("") { "%02x".format(it) }
}

private fun tripleAfter(src: String, marker: String): String {
    val i = src.indexOf(marker)
    if (i < 0) return ""
    val start = src.indexOf("\"\"\"", i + marker.length)
    if (start < 0) return ""
    val end = src.indexOf("\"\"\"", start + 3)
    if (end < 0) return ""
    return src.substring(start + 3, end)
}

private fun lineAfter(src: String, marker: String): String {
    val i = src.indexOf(marker)
    if (i < 0) return ""
    val start = src.indexOf('"', i + marker.length)
    if (start < 0) return ""
    val out = StringBuilder()
    var j = start + 1
    while (j < src.length) {
        val c = src[j]
        if (c == '\\' && j + 1 < src.length) { out.append(c); out.append(src[j + 1]); j += 2; continue }
        if (c == '"') break
        out.append(c)
        j++
    }
    return out.toString()
}

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "kotlin"
    val treeDir = args.getOrNull(1) ?: "."
    val useRust = mode == "rust"
    if (!useRust) {
        ToolRegistry.rustToolRegistry = false
        AgentPrompt.rustAgentPrompt = false
        SkillJudge.rustSkillTrigger = false
    }
    println("== P6 mode=$mode tree=$treeDir ==")

    // ================= 1. ToolRegistry =================
    println("== 1. ToolRegistry (data + scoring) ==")
    val section = ToolRegistry.catalogSection()
    check("1.1 catalogSection panjang 86710", section.length == 86710, "${section.length}")
    check("1.2 catalogSection hash byte-identik", sha256(section) == "c3357c572acdcd593e23ca15b40169dca431ca6cc1f30f250db1e4c5d3fe3668")
    val detail = ToolRegistry.browserSkillDetail()
    check("1.3 browserSkillDetail panjang 2866", detail.length == 2866, "${detail.length}")
    check("1.4 browserSkillDetail hash byte-identik", sha256(detail) == "bac16af6ef7d01e5ee99394790f3a36fd66afe0f1054b23c9874a101780c31d6")
    val names = ToolRegistry.names()
    check("1.5 names 188 tool", names.size == 188, "${names.size}")
    check("1.6 names pertama list_tools + terakhir list_goals", names.firstOrNull() == "list_tools" && names.lastOrNull() == "list_goals", "${names.firstOrNull()} / ${names.lastOrNull()}")
    check("1.7 names hash byte-identik", sha256(names.joinToString(",")) == "38ee24a6c668b3171ed0b017456f9a1cf15525aa5b8a12a70a0fdee63912a912")
    val counts = ToolRegistry.sectionCounts()
    check("1.8 sectionCounts utama 53 + lanjutan 135", counts["utama"] == 53 && counts["lanjutan"] == 135, "$counts")
    check("1.9 namesByKind READ 84 / WRITE 82 / MEMORY 22",
        ToolRegistry.namesByKind(ToolRegistry.Kind.READ).size == 84 &&
        ToolRegistry.namesByKind(ToolRegistry.Kind.WRITE).size == 82 &&
        ToolRegistry.namesByKind(ToolRegistry.Kind.MEMORY).size == 22)
    check("1.10 nearestNames get_batery", ToolRegistry.nearestNames("get_batery") == listOf("get_battery", "get_screen", "set_alarm"), "${ToolRegistry.nearestNames("get_batery")}")
    check("1.11 nearestNames concat_versio", ToolRegistry.nearestNames("concat_versio") == listOf("concat_version", "concat_edit_redo", "concat_edit_undo"))
    check("1.12 nearestNames xyz", ToolRegistry.nearestNames("xyz") == listOf("tap", "swipe", "go_back"))
    check("1.13 nearestNames nama benar di urutan pertama", ToolRegistry.nearestNames("get_battery").firstOrNull() == "get_battery")
    check("1.14 catalogSection bentuk: list_tools + header + prose",
        section.startsWith("- list_tools {}") && section.contains("Tool lanjutan:\n") &&
        section.contains("- PROOT (run_terminal") && section.contains("- CONCAT (vc315)") &&
        section.endsWith("\n"))

    // ================= 2. selectCatalog =================
    println("== 2. selectCatalog (IXL, katalog penuh gaya IXL) ==")
    val agentSrc = File(treeDir, "AgentPrompt.kt").readText()
    val head = tripleAfter(agentSrc, "CATALOG_HEAD")
    val trailer = tripleAfter(agentSrc, "CATALOG_TRAILER")
    val note = lineAfter(agentSrc, "API_LAYER_NOTE_VC282")
    val full = (head + note + section + trailer)
        .replace("__MAXSTEPS__", "50")
        .replace("/data/data/com.senzwh.deepharness/files/proot/ubuntu", "com.senzwh.deepharness/files/proot/ubuntu")
    check("2.0 katalog penuh 94818 char", full.length == 94818, "${full.length}")
    check("2.1 pesan kosong -> katalog penuh", AgentPrompt.selectCatalog(full, "") == full && AgentPrompt.selectCatalog(full, "   ") == full)
    check("2.2 'kita tes concat' 22809", AgentPrompt.selectCatalog(full, "kita tes concat").length == 22809, "${AgentPrompt.selectCatalog(full, "kita tes concat").length}")
    check("2.3 'cek concat' 21122", AgentPrompt.selectCatalog(full, "cek concat").length == 21122, "${AgentPrompt.selectCatalog(full, "cek concat").length}")
    check("2.4 'concat_version' 18417", AgentPrompt.selectCatalog(full, "concat_version").length == 18417, "${AgentPrompt.selectCatalog(full, "concat_version").length}")
    check("2.5 'bro?' 13747", AgentPrompt.selectCatalog(full, "bro?").length == 13747, "${AgentPrompt.selectCatalog(full, "bro?").length}")
    check("2.6 'halo' 11459", AgentPrompt.selectCatalog(full, "halo").length == 11459, "${AgentPrompt.selectCatalog(full, "halo").length}")
    check("2.7 'cek baterai' 18479", AgentPrompt.selectCatalog(full, "cek baterai").length == 18479, "${AgentPrompt.selectCatalog(full, "cek baterai").length}")
    check("2.8 'buatkan dokumen laporan' 21963", AgentPrompt.selectCatalog(full, "buatkan dokumen laporan").length == 21963, "${AgentPrompt.selectCatalog(full, "buatkan dokumen laporan").length}")
    val slimConcat = AgentPrompt.selectCatalog(full, "kita tes concat")
    val concatNames = listOf("concat_version", "concat_project_list", "concat_project_get", "concat_project_open", "concat_project_create", "concat_project_close", "concat_project_save", "concat_project_set_video", "concat_edit_apply", "concat_edit_undo", "concat_edit_redo", "concat_media_probe", "concat_media_import", "concat_catalogue_list", "concat_template_list", "concat_template_instantiate", "concat_template_save", "concat_export_run", "concat_export_cancel", "concat_export_status", "concat_preview_frame")
    check("2.9 'kita tes concat': 21/21 concat_* ada", concatNames.all { slimConcat.contains("- $it ") }, "hilang: ${concatNames.filter { !slimConcat.contains("- $it ") }}")
    check("2.10 junk substring vc241 hilang", !slimConcat.contains("- remember_fact ") && !slimConcat.contains("PARLEMEN — debat"))
    check("2.11 list_tools selalu dipertahankan + catatan fallback", slimConcat.contains("- list_tools ") && slimConcat.contains("Tool lain yang tersedia (nama saja"))
    val slimBro = AgentPrompt.selectCatalog(full, "bro?")
    val browserNames = listOf("browser_open", "browser_read", "browser_tap", "browser_type", "browser_tabs", "browser_shot", "browser_back", "browser_forward", "browser_reload", "browser_find", "browser_ua", "browser_downloads", "browser_console", "browser_network", "browser_shot_full", "browser_snapshot_full")
    check("2.12 'bro?': 16/16 browser_* ada", browserNames.all { slimBro.contains("- $it ") })
    check("2.13 hanya stopwords -> 11459 (list_tools + fallback)", AgentPrompt.selectCatalog(full, "yang dan atau").length == 11459, "${AgentPrompt.selectCatalog(full, "yang dan atau").length}")
    check("2.14 NBSP tepi di-trim: 21122", AgentPrompt.selectCatalog(full, "\u00a0cek concat\u00a0").length == 21122, "${AgentPrompt.selectCatalog(full, "\u00a0cek concat\u00a0").length}")
    check("2.15 kapital sama: CEK CONCAT 21122", AgentPrompt.selectCatalog(full, "CEK CONCAT").length == 21122)
    check("2.16 prefix nama 'conc' 16857", AgentPrompt.selectCatalog(full, "conc").length == 16857, "${AgentPrompt.selectCatalog(full, "conc").length}")
    check("2.17 tanpa marker Daftar tool -> penuh kembali", AgentPrompt.selectCatalog("tanpa Daftar tool di sini", "cek concat") == "tanpa Daftar tool di sini")

    // ================= 3. continuationAnchor =================
    println("== 3. continuationAnchorVc316 ==")
    val anchorShort = AgentPrompt.continuationAnchorVc316("halo")
    check("3.1 'halo' 492 char", anchorShort.length == 492, "${anchorShort.length}")
    check("3.2 perintah 600 char dicap 500 -> 988", AgentPrompt.continuationAnchorVc316("x".repeat(600)).length == 988, "${AgentPrompt.continuationAnchorVc316("x".repeat(600)).length}")
    check("3.3 isi jangkar: awalan + perintah + list_tools + akhir", anchorShort.startsWith("[LANJUTAN SISTEM — vc316 delta:") && anchorShort.contains("Perintah user giliran ini: \"halo\".") && anchorShort.contains("panggil list_tools {} dulu") && anchorShort.endsWith("]"))
    check("3.4 spasi tepi perintah di-trim", AgentPrompt.continuationAnchorVc316("  halo  ").contains("\"halo\"."))

    // ================= 4. DSML stripper =================
    println("== 4. stripRawToolMarkupForDisplay + normalizeDsmlDelimiters ==")
    val dsmlCases = listOf(
        "halo biasa" to "halo biasa",
        "teks <|DSML|calls>{\"tool\":\"get_battery\"}</|DSML|calls> lanjut" to "teks  lanjut",
        "sebelum <|DSML|invoke name=\"get_battery\">{\"a\":1}</|DSML|invoke> sesudah" to "sebelum  sesudah",
        "pikir <|DSML|invoke name=\"x\">{\"a\":1}" to "pikir",
        "contoh DSML tanpa kurung" to "contoh DSML tanpa kurung",
        "a <tool=get_battery>{\"x\":1} b" to "a  b",
        "a <tool=get_battery> b tanpa json" to "a  b tanpa json",
        "x\n\n\n\ny <|DSML|calls>z</|DSML|calls>\n\n\n\nz" to "x\n\ny \n\nz",
        "campur ｜＜|DSML|invoke name=\"a\"＞{\"k\":1}＜/|DSML|invoke＞ akhir" to "campur | akhir",
        "teks <|DSML|invoke name=\"x\"> prosa sesudah" to "teks  prosa sesudah",
        "teks <|DSML|invoke name=\"x\"> {\"a\":1} prosa" to "teks",
        "a <tool=abc>{\"k\":\"v} b" to "a {\"k\":\"v} b",
        "a <tool=abc>{\"k\":\"v with } brace\"} b" to "a  b"
    )
    for ((i, c) in dsmlCases.withIndex()) {
        check("4.${i + 1} DSML strip #${i + 1}", AgentPrompt.stripRawToolMarkupForDisplay(c.first) == c.second, "dapat <${AgentPrompt.stripRawToolMarkupForDisplay(c.first)}>, harap <${c.second}>")
    }
    check("4.14 normalizeDsmlDelimiters fullwidth", AgentPrompt.normalizeDsmlDelimiters("a｜b＜c＞d") == "a|b<c>d")

    // ================= 5. applyDoublePass =================
    println("== 5. applyDoublePass ([KLAIM DIHAPUS]) ==")
    val ans = "Hasilnya sudah dibuat dan sudah dikirim. File sudah dibuat kemarin."
    val claims = listOf("sudah dibuat", "sudah dikirim", "tidak ada")
    check("5.1 hapus klaim 1+2 (semua kemunculan)", AgentPrompt.applyDoublePass(ans, claims, listOf(1, 2)) == "Hasilnya [KLAIM DIHAPUS — tidak didukung bukti] dan [KLAIM DIHAPUS — tidak didukung bukti]. File [KLAIM DIHAPUS — tidak didukung bukti] kemarin.")
    check("5.2 indeks di luar jangkauan di-skip", AgentPrompt.applyDoublePass(ans, claims, listOf(5, 0, -1)) == ans)
    check("5.3 duplikat indeks + klaim tak ada di jawaban", AgentPrompt.applyDoublePass(ans, claims, listOf(1, 1, 3)) == "Hasilnya [KLAIM DIHAPUS — tidak didukung bukti] dan sudah dikirim. File [KLAIM DIHAPUS — tidak didukung bukti] kemarin.")
    check("5.4 klaim blank di-skip", AgentPrompt.applyDoublePass(ans, listOf("   "), listOf(1)) == ans)
    check("5.5 jawaban kosong tetap kosong", AgentPrompt.applyDoublePass("", claims, listOf(1)) == "")

    // ================= 6. Trigger scoring (SkillJudge) =================
    println("== 6. Trigger scoring vc312 (JANGAN PICU:) ==")
    val d1 = "Skill notifikasi WhatsApp. JANGAN PICU: Discord, Slack, WhatsApp"
    val s1 = SkillJudge.splitDescrSections(d1)
    check("6.1 marker tengah baris TIDAK dikenali", !s1.markerFound && s1.positive == d1 && s1.negative == "")
    check("6.2 tanpa marker negWords kosong", SkillJudge.negWordsOf(d1).isEmpty())
    check("6.3 tanpa marker: kata larangan ikut positif", SkillJudge.triggerScore(d1, "cek notifikasi whatsapp") == SkillJudge.TriggerScore(true, 2, 0, 2))
    check("6.4 tanpa marker: slack+discord = positif", SkillJudge.triggerScore(d1, "kirim slack dan discord").trigger)
    val d4 = "Skill pengingat jadwal.\nJANGAN PICU: Discord, Slack\n"
    val s4 = SkillJudge.splitDescrSections(d4)
    check("6.5 split dasar: pos + neg + marker", s4.markerFound && s4.positive == "Skill pengingat jadwal." && s4.negative == "Discord, Slack")
    check("6.6 negWords dasar", SkillJudge.negWordsOf(d4) == setOf("discord", "slack"))
    check("6.7 skor: discord saja -> TIDAK (net 0)", SkillJudge.triggerScore(d4, "buka discord") == SkillJudge.TriggerScore(false, 0, 1, 0))
    check("6.8 skor: 2 pos - 1 neg = 1 -> TIDAK", SkillJudge.triggerScore(d4, "pengingat jadwal discord") == SkillJudge.TriggerScore(false, 2, 1, 1))
    check("6.9 skor: 2 pos 0 neg -> TRIGGER", SkillJudge.triggerScore(d4, "pengingat jadwal harian") == SkillJudge.TriggerScore(true, 2, 0, 2))
    check("6.10 skor: 1 pos - 1 neg = 0 -> TIDAK", SkillJudge.triggerScore(d4, "jadwal slack") == SkillJudge.TriggerScore(false, 1, 1, 0))
    val d5 = "Skill musik.\n  Jangan   Picu  :  Spotify, YouTube\nlanjut larangan baris dua\n\nSesudah kosong."
    val s5 = SkillJudge.splitDescrSections(d5)
    check("6.11 marker case-insensitive + toleran spasi; larangan multi-baris; baris kosong pembatas bukan positif",
        s5.markerFound && s5.positive == "Skill musik.\nSesudah kosong." && s5.negative == "Spotify, YouTube\nlanjut larangan baris dua")
    check("6.12 negWords multi-baris", SkillJudge.negWordsOf(d5) == setOf("baris", "lanjut", "larangan", "spotify", "youtube"))
    check("6.13 skor multi-baris: spotify/youtube terpenalti", !SkillJudge.triggerScore(d5, "putar spotify").trigger && !SkillJudge.triggerScore(d5, "musik youtube").trigger)
    val d3 = "Berita Telegram harian.\nJANGAN PICU: berita Telegram, SMS\n\nBaris sesudah kosong."
    check("6.14 MINUS-POSITIF: telegram di kedua bagian -> negWords kosong", SkillJudge.negWordsOf(d3).isEmpty())
    check("6.15 MINUS-POSITIF: skor tetap TRIGGER", SkillJudge.triggerScore(d3, "berita telegram hari ini").trigger)
    val d7 = "JANGAN PICU: Discord\nJANGAN PICU: Slack"
    val s7 = SkillJudge.splitDescrSections(d7)
    check("6.16 hanya marker PERTAMA: marker kedua jadi isi larangan", s7.markerFound && s7.positive == "" && s7.negative == "Discord\nJANGAN PICU: Slack")
    check("6.17 marker kedua: 'picu' ikut jadi negWord", SkillJudge.negWordsOf(d7) == setOf("discord", "picu", "slack"))
    val d6 = "Tanpa marker sama sekali, kata discord ada."
    check("6.18 rumus lama tanpa marker", SkillJudge.triggerScore(d6, "buka discord") == SkillJudge.TriggerScore(false, 1, 0, 1))

    // ================= 7. Seam =================
    println("== 7. Seam JNI (VERSION 8 + capabilities + saklar) ==")
    check("7.1 RustCore.VERSION == 8", RustCore.VERSION == 8, "VERSION=${RustCore.VERSION}")
    if (useRust) {
        check("7.2 RustCore.available true (mode rust)", RustCore.available)
        val caps = JSONObject(RustCore.rustCoreCapabilities())
        check("7.3 capabilities version 8", caps.getInt("version") == 8, "version=${caps.optInt("version")}")
        val fns = caps.getJSONArray("functions")
        val fnNames = (0 until fns.length()).map { fns.getString(it) }.toSet()
        check("7.4 capabilities memuat 3 fungsi baru", "toolRegistryJson" in fnNames && "agentPromptJson" in fnNames && "skillTriggerJson" in fnNames)
        val mods = caps.getJSONArray("modules")
        val modNames = (0 until mods.length()).map { mods.getString(it) }.toSet()
        check("7.5 capabilities memuat 3 modul baru", "tool_registry" in modNames && "agent_prompt" in modNames && "skill_trigger" in modNames)
        val direct = JSONObject(RustCore.toolRegistryJson(JSONObject().put("op", "names").toString()))
        check("7.6 seam langsung toolRegistryJson names ok", direct.optBoolean("ok", false) && direct.getJSONArray("value").length() == 188)
        check("7.7 saklar masih true (tidak ada fallback diam-diam)", ToolRegistry.rustToolRegistry && AgentPrompt.rustAgentPrompt && SkillJudge.rustSkillTrigger)
    } else {
        check("7.2 mode kotlin: ketiga saklar false", !ToolRegistry.rustToolRegistry && !AgentPrompt.rustAgentPrompt && !SkillJudge.rustSkillTrigger)
    }

    println("HASIL P6 mode=$mode: $passed lulus, $failed gagal (total ${passed + failed})")
    if (failedNames.isNotEmpty()) println("GAGAL: ${failedNames.joinToString(", ")}")
    if (failed > 0) exitProcess(1)
}
