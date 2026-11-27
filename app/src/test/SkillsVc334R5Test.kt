package com.senzwh.deepharness

import java.io.File
import org.json.JSONObject

// Test JVM murni — vc334 Ronde 5: SKILLS (F1, F2, F3, F4).
// Dijalankan oleh tools/run_tests.sh.
//
// APA YANG DITES DI SINI (jujur):
//  1. MURNI (Vc334R5Pure): F1 (query gagal -> error TERBEDAKAN dari
//     list kosong; format "Gagal membaca tabel skills: ..."), F4
//     (semua nonaktif -> penanda, bukan "Belum ada skill."), F3
//     (ringkasan arsip skill sebelum wipe — hanya kind=skill, cap
//     baris, null bila tak ada entri skill).
//  2. MURNI (ConcatTools): F2 — requiresSpecialConfirmVc316
//     ("delete_skill", ...) == true; perilaku Concat TIDAK berubah;
//     describe menyebut id + Setup 66.
//  3. SUMBER (grep): wiring di MemoryDb / Tools / MainActivity /
//     BgBrain / GroupChat yang tidak bisa jalan di JVM.
//  4. REPRODUKSI: sebelum fix, tes ini GAGAL — Vc334R5Pure belum ada
//     (compile gagal) + requiresSpecialConfirm("delete_skill") false
//     + list_skills masih "Belum ada skill." untuk query gagal.
//
// APA YANG TIDAK DITES DI SINI:
//  - Perilaku runtime di HP (MemoryDb/EventDb nyata, dialog Ya/Batal,
//    Setup 66, wipeAll nyata): butuh tes HP oleh koordinator.

private var failedR5 = 0

private fun checkR5(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedR5++; println("  FAIL $name" + detailSuffix)
    }
}

private fun srcR5(name: String): String {
    val proj = System.getProperty("proj.dir") ?: "."
    return File(proj, "app/src/main/java/com/senzwh/deepharness/$name").readText()
}

private fun countR5(text: String, needle: String): Int {
    var n = 0
    var i = text.indexOf(needle)
    while (i >= 0) { n++; i = text.indexOf(needle, i + needle.length) }
    return n
}

fun main() {
    println("== SkillsVc334R5Test ==")

    // ---- F1 murni: query gagal TERBEDAKAN dari list kosong.
    val brokenRead: () -> List<Vc334R5Pure.SkillListRow> =
        { throw RuntimeException("no such table: skills") }
    val broken = Vc334R5Pure.skillsResultOf(brokenRead)
    checkR5("F1: reader yang melempar -> Result.failure, BUKAN list kosong",
        broken.isFailure)
    checkR5("F1: pesan error ikut terbawa (\"no such table: skills\")",
        (broken.exceptionOrNull()?.message ?: "") == "no such table: skills")
    checkR5("F1: format error -> \"Gagal membaca tabel skills: no such table: skills\"",
        Vc334R5Pure.formatListSkills(emptyList(), broken.exceptionOrNull()?.message) ==
            "Gagal membaca tabel skills: no such table: skills")
    checkR5("F1: error mengalahkan list kosong (bukan \"Belum ada skill.\")",
        Vc334R5Pure.formatListSkills(emptyList(), "no such table: skills") != "Belum ada skill.")
    val okRead: () -> List<Vc334R5Pure.SkillListRow> =
        { listOf(Vc334R5Pure.SkillListRow(1, "audit-hp", true, "audit HP")) }
    checkR5("F1: reader sukses -> Result.success berisi baris",
        Vc334R5Pure.skillsResultOf(okRead).getOrNull()?.size == 1)
    checkR5("F1: list kosong + tanpa error -> tetap \"Belum ada skill.\"",
        Vc334R5Pure.formatListSkills(emptyList(), null) == "Belum ada skill.")
    checkR5("F1: satu skill aktif -> baris \"- #1 audit-hp: audit HP\"",
        Vc334R5Pure.formatListSkills(
            listOf(Vc334R5Pure.SkillListRow(1, "audit-hp", true, "audit HP")), null
        ) == "- #1 audit-hp: audit HP")

    // ---- F2 murni: delete_skill masuk pengecualian konfirmasi.
    checkR5("F2: requiresSpecialConfirm(\"delete_skill\", {id:14}) == true",
        ConcatTools.requiresSpecialConfirmVc316("delete_skill", JSONObject().put("id", 14)))
    checkR5("F2: delete_skill tanpa argumen id tetap butuh konfirmasi",
        ConcatTools.requiresSpecialConfirmVc316("delete_skill", JSONObject()))
    val descDelete = ConcatTools.describeDestructiveConcatCallVc316(
        "delete_skill", JSONObject().put("id", 14))
    checkR5("F2: deskripsi delete_skill menyebut id #14",
        descDelete.contains("#14"), "dapat=[$descDelete]")
    checkR5("F2: deskripsi delete_skill menyebut arsip/Setup 66",
        descDelete.contains("Setup 66"), "dapat=[$descDelete]")
    // Perilaku Concat TIDAK berubah.
    checkR5("F2: concat_project_open tetap true",
        ConcatTools.requiresSpecialConfirmVc316("concat_project_open", JSONObject().put("project", "p")))
    checkR5("F2: concat_project_close save=false tetap true",
        ConcatTools.requiresSpecialConfirmVc316("concat_project_close", JSONObject().put("save", false)))
    checkR5("F2: concat_project_close save=true tetap false",
        !ConcatTools.requiresSpecialConfirmVc316("concat_project_close", JSONObject().put("save", true)))
    checkR5("F2: concat_edit_apply op destruktif tetap true",
        ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", JSONObject().put("op", "removeClips")))
    checkR5("F2: concat_edit_apply op aman tetap false",
        !ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", JSONObject().put("op", "addClip")))
    checkR5("F2: tool baca biasa tetap false (list_skills)",
        !ConcatTools.requiresSpecialConfirmVc316("list_skills", JSONObject()))
    checkR5("F2: create_skill tetap false",
        !ConcatTools.requiresSpecialConfirmVc316("create_skill", JSONObject()))

    // ---- F3 murni: ringkasan arsip skill sebelum wipe.
    val entries = listOf(
        Vc334R5Pure.SkillArchiveEntry(1, "skill", "#14",
            JSONObject().put("name", "audit-hp").put("orig_id", 14).toString(), 1795000000000L),
        Vc334R5Pure.SkillArchiveEntry(2, "skill", "#15",
            JSONObject().put("name", "motion-klip").put("orig_id", 15).toString(), 1795100000000L),
        Vc334R5Pure.SkillArchiveEntry(3, "fact", "fakta-1", "{}", 1795200000000L)
    )
    val summary = Vc334R5Pure.wipeSkillArchiveSummary(entries)
    checkR5("F3: ada entri skill -> ringkasan tidak null",
        summary != null)
    checkR5("F3: ringkasan menyebut nama skill #14 (audit-hp)",
        summary?.contains("audit-hp") == true, "dapat=[$summary]")
    checkR5("F3: ringkasan menyebut nama skill #15 (motion-klip)",
        summary?.contains("motion-klip") == true, "dapat=[$summary]")
    checkR5("F3: ringkasan menyebut jumlah 2 entri skill",
        summary?.contains("2 entri") == true, "dapat=[$summary]")
    checkR5("F3: entri kind=fact TIDAK ikut ringkasan",
        summary?.contains("fakta-1") != true, "dapat=[$summary]")
    checkR5("F3: tanpa entri skill -> null (tidak ada baris EventDb)",
        Vc334R5Pure.wipeSkillArchiveSummary(
            listOf(Vc334R5Pure.SkillArchiveEntry(9, "fact", "f", "{}", 1L))) == null)
    checkR5("F3: arsip kosong -> null",
        Vc334R5Pure.wipeSkillArchiveSummary(emptyList()) == null)
    checkR5("F3: payload tanpa nama -> fallback ke ref",
        Vc334R5Pure.wipeSkillArchiveSummary(
            listOf(Vc334R5Pure.SkillArchiveEntry(4, "skill", "#99", "{}", 1L))
        )?.contains("#99") == true)
    val many = (1..30).map {
        Vc334R5Pure.SkillArchiveEntry(it.toLong(), "skill", "#$it",
            JSONObject().put("name", "skill-$it").toString(), 1L)
    }
    val summaryMany = Vc334R5Pure.wipeSkillArchiveSummary(many) ?: ""
    checkR5("F3: 30 entri -> ringkasan di-cap (ada penanda sisa)",
        summaryMany.contains("lainnya"), "dapat=[$summaryMany]")
    checkR5("F3: 30 entri -> jumlah 30 tetap disebut",
        summaryMany.contains("30 entri"), "dapat=[$summaryMany]")

    // ---- F4 murni: "tidak ada" vs "semua nonaktif".
    val twoDisabled = listOf(
        Vc334R5Pure.SkillListRow(1, "audit-hp", false, "audit HP"),
        Vc334R5Pure.SkillListRow(2, "motion-klip", false, "klip motion")
    )
    val f4 = Vc334R5Pure.formatListSkills(twoDisabled, null)
    checkR5("F4: 2 skill nonaktif -> hasil memuat penanda nonaktif",
        f4.contains("Semua skill nonaktif"), "dapat=[$f4]")
    checkR5("F4: 2 skill nonaktif -> hasil memuat \"Settings > Skill Agen\"",
        f4.contains("Settings > Skill Agen"), "dapat=[$f4]")
    checkR5("F4: 2 skill nonaktif -> TIDAK menjawab \"Belum ada skill.\"",
        !f4.contains("Belum ada skill."), "dapat=[$f4]")
    checkR5("F4: 2 skill nonaktif -> kedua baris tetap tampil",
        f4.contains("- #1 audit-hp (nonaktif)") && f4.contains("- #2 motion-klip (nonaktif)"),
        "dapat=[$f4]")
    val mixed = listOf(
        Vc334R5Pure.SkillListRow(1, "audit-hp", true, "audit HP"),
        Vc334R5Pure.SkillListRow(2, "motion-klip", false, "klip motion")
    )
    checkR5("F4: campuran aktif+nonaktif -> tanpa penanda semua-nonaktif",
        !Vc334R5Pure.formatListSkills(mixed, null).contains("Semua skill nonaktif"))
    checkR5("F4: semua aktif -> tanpa penanda semua-nonaktif",
        !Vc334R5Pure.formatListSkills(
            listOf(Vc334R5Pure.SkillListRow(1, "audit-hp", true, "audit HP")), null
        ).contains("Semua skill nonaktif"))

    // ---- Wiring (sumber): situs Android yang tidak bisa jalan di JVM.
    val memoryDb = srcR5("MemoryDb.kt")
    checkR5("WIRING F1: MemoryDb punya skillsResult() error-aware",
        memoryDb.contains("fun skillsResult()"))
    checkR5("WIRING F1: skillsResult() log ke EventDb type 'db'",
        memoryDb.contains("EventDb.get(appCtx).add(\"db\""))
    checkR5("WIRING F1: skills() lama tetap ada (pemanggil lain tak berubah)",
        memoryDb.contains("fun skills(): List<Skill>"))
    checkR5("WIRING F3: wipeAll menyalin ringkasan arsip skill ke EventDb SEBELUM hapus deleted_archive",
        run {
            val w = memoryDb.indexOf("fun wipeAll()")
            val s = memoryDb.indexOf("wipeSkillArchiveSummary", w)
            val d = memoryDb.indexOf("delete(\"deleted_archive\"", w)
            w >= 0 && s > w && d > s
        })
    checkR5("WIRING F3: wipeAll tetap menghapus deleted_archive (semantik wipe tak berubah)",
        memoryDb.contains("db.delete(\"deleted_archive\", null, null)"))
    val tools = srcR5("Tools.kt")
    checkR5("WIRING F1+F4: list_skills pakai skillsResult()",
        tools.contains("skillsResult()"))
    checkR5("WIRING F1+F4: list_skills memformat lewat Vc334R5Pure.formatListSkills",
        tools.contains("Vc334R5Pure.formatListSkills"))
    checkR5("WIRING F1: tidak ada lagi jawaban \"Belum ada skill.\" mentah di list_skills",
        run {
            val i = tools.indexOf("\"list_skills\"")
            val seg = if (i >= 0) tools.substring(i, minOf(tools.length, i + 1200)) else ""
            !seg.contains("\"Belum ada skill.\"")
        })
    val main = srcR5("MainActivity.kt")
    checkR5("WIRING F2 1-1: proceedTool cek konfirmasi khusus SEBELUM early-return gerbang mati",
        run {
            val i = main.indexOf("requiresSpecialConfirmVc316(tool, args)")
            val g = main.indexOf("if (ToolGate.GATES_DISABLED) { execToolAndContinue(tool, args, callId); return }", i)
            i >= 0 && g > i
        })
    checkR5("WIRING F2 1-1: dialog konfirmasi punya judul khusus hapus skill",
        main.contains("Hapus skill?"))
    checkR5("WIRING F1 UI: Settings > Skill Agen (setupSkillsCard) pakai skillsResult()",
        main.contains("skillsResult()"))
    checkR5("WIRING F1 UI: kartu error baca skill ada (bukan \"Belum ada skill\" diam-diam)",
        main.contains("Gagal membaca tabel skills"))
    val bg = srcR5("BgBrain.kt")
    checkR5("WIRING F2 latar: BgBrain mencegat konfirmasi khusus sebelum rute tool",
        bg.contains("msg_special_confirm"))
    checkR5("WIRING F2 latar: BgBrain TIDAK mengeksekusi delete_skill diam-diam (cabang pesan, bukan execute)",
        run {
            val i = bg.indexOf("msg_special_confirm")
            i >= 0 && bg.indexOf("\"msg_special_confirm\" ->", i) >= 0
        })
    val group = srcR5("GroupChat.kt")
    checkR5("WIRING F2 grup: runGroupTool mengarahkan delete_skill ke dialog saat gerbang mati",
        group.contains("requiresSpecialConfirmVc316(tool, args)"))

    if (failedR5 > 0) {
        println("SkillsVc334R5Test: $failedR5 GAGAL")
        kotlin.system.exitProcess(1)
    }
    println("SkillsVc334R5Test: SEMUA LULUS")
}
