package com.senzwh.deepharness

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * v4.13.0 — Impor SKILL.md dari folder Download: parse frontmatter
 * (name/description) + badan prosedur, simpan VERBATIM ke tabel skills
 * (nama sama = perbarui). Tanpa perantara model — tidak ada parafrase,
 * tidak ada potongan. Format yang didukung: berkas .md dengan atau
 * tanpa frontmatter YAML sederhana (name:, description:).
 *
 * vc242 K-08 + FT-M1: impor TIDAK LAGI langsung menyimpan semua .md.
 * Alur baru: scanFromDownload hanya mengembalikan kandidat (nama file
 * SKILL-*.md, atau .md yang frontmatter-nya valid sebagai skill), UI
 * menampilkan pratinjau nama + deskripsi dengan centang per file, dan
 * importSelected menyimpan yang dicentang lewat forgeUpsert — impor ulang
 * TIDAK mengubah status aktif yang sudah ada, skill baru tersimpan
 * NONAKTIF, dan slug sistem ditolak (pagar yang sama dengan forge).
 */
object SkillImport {

    data class Candidate(
        val fileName: String,
        val name: String,
        val descr: String,
        val body: String,
        val existed: Boolean,
        val systemBlocked: Boolean
    )

    data class ScanResult(
        val candidates: List<Candidate>,
        val skippedNonSkill: Int,
        val skippedInvalid: Int,
        val skippedOversize: Int
    )

    private data class Parsed(val name: String, val descr: String, val body: String, val frontmatterSkill: Boolean)

    private fun parse(file: File, text: String): Parsed {
        val t = text.trimStart('\uFEFF', '\n', '\r', ' ')
        if (t.startsWith("---")) {
            val end = t.indexOf("\n---", 3)
            if (end > 0) {
                val yaml = t.substring(3, end)
                var name = ""
                var descr = ""
                for (line in yaml.lines()) {
                    val lt = line.trim()
                    when {
                        lt.startsWith("name:") -> name = lt.substring(5).trim().trim('"', '\'')
                        lt.startsWith("description:") -> descr = lt.substring(12).trim().trim('"', '\'')
                    }
                }
                val body = t.substring(end + 4).trim()
                if (body.isNotEmpty()) {
                    return Parsed(
                        if (name.isNotBlank()) name else file.nameWithoutExtension,
                        descr,
                        body,
                        name.isNotBlank() && descr.isNotBlank()
                    )
                }
            }
        }
        val firstLine = t.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: ""
        return Parsed(file.nameWithoutExtension, firstLine.take(200), t.trim(), false)
    }

    fun scanFromDownload(ctx: Context): ScanResult {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = try {
            dir.listFiles()?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }?.sortedBy { it.name } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        val candidates = ArrayList<Candidate>()
        var skippedNonSkill = 0
        var skippedInvalid = 0
        var skippedOversize = 0
        val mdb = MemoryDb.get(ctx)
        for (f in files) {
            if (candidates.size >= 20) break
            try {
                if (f.length() > 256_000) { skippedOversize++; continue }
                val text = f.readText(Charsets.UTF_8)
                val p = parse(f, text)
                val isSkillFile = f.name.startsWith("SKILL-", ignoreCase = true)
                if (!isSkillFile && !p.frontmatterSkill) { skippedNonSkill++; continue }
                // vc230 R-10(b): validasi disamakan dengan Skill Forge —
                // deskripsi kosong diganti baris pertama isi; bila tetap
                // kosong, berkas dilewati (tidak disimpan tanpa deskripsi).
                val descrVc230 = if (p.descr.isNotBlank()) p.descr else p.body.lines().firstOrNull { it.isNotBlank() }?.take(200) ?: ""
                if (p.body.isBlank() || p.name.isBlank() || descrVc230.isBlank()) { skippedInvalid++; continue }
                val slug = Tools.slugifySkillName(p.name)
                candidates.add(Candidate(
                    f.name, p.name, descrVc230, p.body,
                    mdb.skillByName(p.name) != null,
                    slug in Tools.SYSTEM_SKILL_SLUGS
                ))
            } catch (e: Exception) { skippedInvalid++ }
        }
        return ScanResult(candidates, skippedNonSkill, skippedInvalid, skippedOversize)
    }

    fun importSelected(ctx: Context, selected: List<Candidate>): String {
        val mdb = MemoryDb.get(ctx)
        var baru = 0
        var diperbarui = 0
        var ditolak = 0
        var blockedVc262 = 0
        var blockedWhyVc262 = ""
        for (c in selected) {
            if (c.systemBlocked) { ditolak++; continue }
            // vc262 SkillSpector: pindai SEBELUM simpan — skor >= 80
            // = instalasi DITOLAK (pemindai keamanan saat impor, bukan
            // checkpoint approval eksekusi tool).
            val specVc262 = try { SkillSpector.scan(c.name, c.descr, c.body) } catch (e: Exception) { null }
            if (specVc262 != null && specVc262.blocked) {
                blockedVc262++
                ditolak++
                if (blockedWhyVc262.isBlank()) blockedWhyVc262 = c.name + " (skor " + specVc262.score + "/100: " + specVc262.findings.joinToString("; ") + ")"
                try { AttentionBudget.noteSkillBlocked(ctx) } catch (e: Exception) {}
                continue
            }
            // vc300 BAGIAN-C: teruskan source="import" — versi yang lahir dari
            // impor tercatat sumbernya (param opsional worker A, default "forge").
            val res = mdb.forgeUpsert(c.name, c.body, c.descr, "import")
            if (res.first <= 0) { ditolak++; continue }
            if (res.second) diperbarui++ else baru++
        }
        return "Impor skill selesai: $baru baru (NONAKTIF — aktifkan di Settings > Skill Agen), $diperbarui diperbarui (status aktif tidak diubah), $ditolak ditolak." + (if (blockedVc262 > 0) " SkillSpector memblokir $blockedVc262 skill (skor risiko >= 80): $blockedWhyVc262." else "")
    }

    // vc301 SF-B6/SF-B5: impor file export lama dari folder PRIVAT
    // skill-export ke skill_versions (source='file-import'). Dipanggil
    // HANYA lewat tool import_skill_file — TIDAK ada auto-migrasi saat
    // startup (file export bisa hilang kapan saja; migrasi diam-diam
    // akan menyembunyikan kehilangan itu). Tanpa argumen file = impor
    // SEMUA file SKILL-*.md yang masih ada (migrasi satu kali, manual).
    // Gate sama dengan impor Download: slug sistem ditolak, SkillSpector
    // skor >= 80 ditolak, skill baru NONAKTIF, impor ulang tak ubah
    // status aktif. JUJUR: file export pra-vc300 BUKAN arsip resmi dan
    // tidak dijamin isinya — yang terselamatkan di sini hanya file yang
    // masih ada & terbaca; arsip resmi ke depan adalah skill_versions
    // di DB (vc300+), bukan file .md.
    fun importFromSkillExport(ctx: Context, fileName: String): String {
        val dir = File(ctx.filesDir, "skill-export")
        val files: List<File>
        if (fileName.isBlank()) {
            if (!dir.isDirectory) return "Folder skill-export kosong/tidak ada — tidak ada file export lama untuk diimpor ke skill_versions."
            files = try { dir.listFiles()?.filter { it.isFile && it.name.startsWith("SKILL-", ignoreCase = true) && it.extension.equals("md", ignoreCase = true) }?.sortedBy { it.name } ?: emptyList() } catch (e: Exception) { emptyList() }
            if (files.isEmpty()) return "Folder skill-export tidak berisi file SKILL-*.md — tidak ada yang diimpor."
        } else {
            // Penjaga jalur: nama file saja, tanpa traversal, wajib .md.
            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\") || !fileName.endsWith(".md", ignoreCase = true)) return "import_skill_file ditolak: nama file tidak valid ('$fileName' — pakai nama file saja, mis. SKILL-audit-hp-16.md)."
            val f = File(dir, fileName)
            if (!f.isFile) return "File '$fileName' tidak ditemukan di folder skill-export."
            files = listOf(f)
        }
        val mdb = MemoryDb.get(ctx)
        var baru = 0
        var diperbarui = 0
        var ditolak = 0
        var blockedVc301 = 0
        var blockedWhyVc301 = ""
        for (f in files) {
            try {
                if (f.length() > 256_000) { ditolak++; continue }
                val text = f.readText(Charsets.UTF_8)
                val p = parse(f, text)
                val descrVc230 = if (p.descr.isNotBlank()) p.descr else p.body.lines().firstOrNull { it.isNotBlank() }?.take(200) ?: ""
                if (p.body.isBlank() || p.name.isBlank() || descrVc230.isBlank()) { ditolak++; continue }
                val slug = Tools.slugifySkillName(p.name)
                if (slug in Tools.SYSTEM_SKILL_SLUGS) { ditolak++; continue }
                val specVc301 = try { SkillSpector.scan(p.name, descrVc230, p.body) } catch (e: Exception) { null }
                if (specVc301 != null && specVc301.blocked) {
                    blockedVc301++
                    ditolak++
                    if (blockedWhyVc301.isBlank()) blockedWhyVc301 = p.name + " (skor " + specVc301.score + "/100: " + specVc301.findings.joinToString("; ") + ")"
                    try { AttentionBudget.noteSkillBlocked(ctx) } catch (e: Exception) {}
                    continue
                }
                // vc301: source='file-import' — versi yang lahir dari
                // file export lama tercatat sumbernya.
                val res = mdb.forgeUpsert(p.name, p.body, descrVc230, "file-import")
                if (res.first <= 0) { ditolak++; continue }
                if (res.second) diperbarui++ else baru++
            } catch (e: Exception) { ditolak++ }
        }
        return "Impor dari skill-export selesai: $baru baru (NONAKTIF — aktifkan di Settings > Skill Agen), $diperbarui diperbarui (status aktif tidak diubah), $ditolak ditolak." + (if (blockedVc301 > 0) " SkillSpector memblokir $blockedVc301 skill (skor risiko >= 80): $blockedWhyVc301." else "") + " Catatan: file export pra-vc300 BUKAN arsip resmi — yang terselamatkan hanya file yang masih ada & terbaca; arsip resmi ke depan adalah skill_versions di DB."
    }
}
