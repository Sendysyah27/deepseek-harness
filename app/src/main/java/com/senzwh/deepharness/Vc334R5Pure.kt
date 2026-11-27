package com.senzwh.deepharness

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * vc334 Ronde 5 — helper MURNI (JVM, tanpa android.*, tanpa Context)
 * untuk fix skills (F1, F3, F4). F2 tinggal di ConcatTools
 * (requiresSpecialConfirmVc316 + describeDestructiveConcatCallVc316).
 *
 * Semua keputusan yang bisa diuji tanpa HP tinggal di sini; situs
 * Android (MemoryDb / Tools / MainActivity / BgBrain / GroupChat)
 * hanya memanggil helper ini. Tes: SkillsVc334R5Test.kt.
 *
 * Yang SENGAJA tidak ada di sini: penyebab hilangnya skill (H-1..H-8
 * — tidak ada fix di ronde ini, hanya F1–F4); forgeUpsert /
 * importSkillVersion / restore (tidak disentuh).
 */
object Vc334R5Pure {

    /** Satu baris daftar skill untuk list_skills (F1/F4). */
    data class SkillListRow(val id: Long, val name: String, val enabled: Boolean, val detail: String)

    /** Satu entri deleted_archive untuk ringkasan pra-wipe (F3). */
    data class SkillArchiveEntry(val id: Long, val kind: String, val ref: String, val payload: String, val deletedTs: Long)

    /** Penanda penutup F4 — baris terakhir list_skills bila semua nonaktif. */
    const val ALL_DISABLED_FOOTER =
        "Semua skill nonaktif — tidak ada yang dipakai agen (aktifkan di Settings > Skill Agen)."

    /** Maks baris entri dalam ringkasan F3 (EventDb memotong teks di 2000 char). */
    const val WIPE_SUMMARY_MAX_LINES = 20

    // ================= F1: baca skill yang error-aware =================

    /**
     * Kontrak F1: pembaca yang melempar HARUS jadi Result.failure —
     * TIDAK PERNAH jadi list kosong. List kosong hanya berarti tabel
     * benar-benar kosong. Pemakaian produksi: MemoryDb.skillsResult()
     * memakai pola yang sama atas query SQLite nyata (helper ini hanya
     * menjepit kontraknya supaya bisa dites di JVM dengan reader mock).
     */
    fun skillsResultOf(read: () -> List<SkillListRow>): kotlin.Result<List<SkillListRow>> =
        try {
            kotlin.Result.success(read())
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }

    /**
     * Format hasil list_skills (F1 + F4):
     *  - error != null -> "Gagal membaca tabel skills: <pesan>" (BUKAN
     *    "Belum ada skill." — kegagalan baca terbedakan dari kosong).
     *  - rows kosong + tanpa error -> "Belum ada skill."
     *  - rows tak kosong tapi semua enabled=false -> daftar + baris
     *    penutup ALL_DISABLED_FOOTER (F4).
     */
    fun formatListSkills(rows: List<SkillListRow>, error: String?): String {
        if (error != null) return "Gagal membaca tabel skills: $error"
        if (rows.isEmpty()) return "Belum ada skill."
        val body = rows.joinToString("\n") {
            "- #${it.id} ${it.name}${if (it.enabled) "" else " (nonaktif)"}: ${it.detail}"
        }
        return if (rows.all { !it.enabled }) "$body\n$ALL_DISABLED_FOOTER" else body
    }

    // ================= F3: ringkasan arsip skill sebelum wipe =================

    /**
     * Ringkasan entri arsip skill (kind="skill") untuk disalin ke
     * EventDb SEBELUM wipeAll menghapus deleted_archive. Hanya
     * kind/nama/ref/waktu hapus — BUKAN isi instruksi (payload utuh
     * tidak ikut; ringkasan jejak, bukan backup).
     *  - Tak ada entri skill -> null (pemanggil tidak menulis EventDb).
     *  - Nama diambil dari payload JSON ("name"); bila kosong, pakai ref.
     *  - Di-cap WIPE_SUMMARY_MAX_LINES baris + penanda "(+N lainnya...)".
     */
    fun wipeSkillArchiveSummary(entries: List<SkillArchiveEntry>): String? {
        val skills = entries.filter { it.kind == "skill" }
        if (skills.isEmpty()) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder()
        sb.append("wipeAll: ${skills.size} entri arsip skill ikut terhapus — ringkasan (nama/ref/waktu hapus):")
        for (e in skills.take(WIPE_SUMMARY_MAX_LINES)) {
            val name = try {
                JSONObject(e.payload).optString("name").trim()
            } catch (ex: Exception) {
                ""
            }.ifBlank { e.ref }
            sb.append("\n- [skill] ${e.ref} $name — dihapus ${fmt.format(Date(e.deletedTs))}")
        }
        val rest = skills.size - WIPE_SUMMARY_MAX_LINES
        if (rest > 0) sb.append("\n(+$rest entri lainnya tidak ditampilkan)")
        return sb.toString()
    }
}
