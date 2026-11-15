package com.senzwh.deepharness

import java.io.File

// vc302 Worker A — Fix A (guard baca-ulang identik) + Fix B (header total
// terstruktur). File MURNI (tanpa import Android) supaya bisa dikompilasi +
// diuji di JVM tanpa stub Android — pola yang sama seperti SkillJudge.
// Satu-satunya implementasi baca segmen + guard + header; Tools.kt memakai
// primitif di sini (check/record/header/segmen), MainActivity/BgBrain/
// GroupChat memakai reset-nya. Jangan duplikasi logika ini di file lain.
//
// Latar (sesi 12 Nov 2026): restore skill #44 butuh 66 tool karena agen
// membaca ulang hasil tersimpan R5808 berulang-ulang sampai StuckDetector
// mengunci; file -16 = 8.085 byte = 8.015 char (beda satuan char vs byte);
// aritmetika agen dari potongan terpotong salah (6.093+3.093=9.186 vs total
// tool 8.015 char) karena total tidak dibaca dari header tool.

/** Guard baca-ulang identik per giliran (Fix A).
 *
 *  Peta kunci-argumen-efektif -> R-id hasil sukses PERTAMA di giliran ini.
 *  Hanya SUKSES yang dicatat (pemanggil — Tools.execute — yang memutuskan
 *  sukses via AgentPrompt.isRereadSuccessVc279; guard ini tidak
 *  mengklasifikasi). Error (file tidak ada, id tidak ditemukan, offset di
 *  luar total/file, tersimpan terpotong, pattern kosong) TIDAK PERNAH
 *  dicatat, jadi panggilan ulang error tetap dieksekusi lewat jalur error
 *  dan pengecualian SF-C1 vc301 (isPagingErrorVc301) tidak terganggu.
 *
 *  Instance dipakai BERSAMA semua lane (Tools adalah object singleton):
 *  TIDAK BOLEH ThreadLocal — Tools.execute() di lane 1-1 jalan di thread
 *  baru tiap tool (executeToolWithTimeoutVc278). Sinkronisasi di sini.
 *  Reset tiap giliran baru via resetVc302() (dipanggil Tools.
 *  resetRereadGuardVc302() dari onUserMessage / pindah sesi / runTask
 *  latar / sendRound grup). Cap 128 entri (evict tertua) supaya kebocoran
 *  antar ronde room (tanpa hook reset per-ronde — lihat BAGIAN-A-GUARD.md
 *  §5) tetap terbatas.
 */
class RereadGuardVc302 {
    private val seen = LinkedHashMap<String, String>()

    /** R-id hasil sukses sebelumnya untuk kunci ini, atau null bila belum ada. */
    @Synchronized
    fun checkVc302(key: String): String? = seen[key]

    /** Catat sukses. resultId HARUS R-id jujur (bukan ditebak): read_file =
     *  R-id baru dari ResultStore; get/grep_result = argumen id yang dibaca.
     *  resultId kosong = jangan catat (guard tidak boleh mengutip R-id yang
     *  tidak ada). */
    @Synchronized
    fun recordVc302(key: String, resultId: String) {
        if (resultId.isBlank()) return
        if (seen.size >= MAX_ENTRIES_VC302 && !seen.containsKey(key)) {
            val eldest = seen.keys.firstOrNull()
            if (eldest != null) seen.remove(eldest)
        }
        seen[key] = resultId
    }

    /** Awal giliran baru: buang semua catatan giliran lama. */
    @Synchronized
    fun resetVc302() {
        seen.clear()
    }

    @Synchronized
    fun sizeVc302(): Int = seen.size

    companion object {
        const val MAX_ENTRIES_VC302 = 128

        // Kunci = argumen EFEKTIF persis (sesudah default + coerce yang sama
        // dengan cabang tool di Tools.execute). Offset/path/R-id berbeda =
        // kunci berbeda = tetap jalan. Path dipakai apa adanya (tidak
        // dikanonikalisasi — ejaan berbeda = kunci berbeda; konservatif,
        // tidak pernah salah-blokir file yang berbeda).
        fun keyForReadFileVc302(path: String, off: Int, len: Int): String =
            "read_file|$path|$off|$len"

        fun keyForGetResultVc302(id: String, off: Int, limit: Int): String =
            "get_result|$id|$off|$limit"

        fun keyForGrepResultVc302(id: String, pattern: String, context: Int): String =
            "grep_result|$id|$pattern|$context"

        // Pesan guard — kalimat pertama VERBATIM sesuai brief vc302.
        // Dicek terhadap WorkLog.isFailed: tidak mengandung kata mark mana
        // pun ("gagal", "tidak …", "ditolak", …) dan tidak berawalan
        // "⚠️"/"Error", jadi WorkItem guard tetap ok=true (guard bukan
        // kegagalan tool). Kalimat kedua menjaga kejujuran lintas-agen
        // (room/grup multi-agen: hasil itu mungkin ada di konteks agen
        // LAIN dalam ronde yang sama) + memberi jalan keluar yang sah.
        fun guardMessageVc302(tool: String, resultId: String): String =
            "Hasil ini sudah ada di konteks giliran ini ($resultId) — pakai itu, jangan baca ulang." +
                " Bila belum ada di konteksmu, baca via get_result[$resultId] — jangan panggil" +
                " $tool dengan argumen yang sama persis."
    }
}

/** Hasil orkestrasi baca file lewat guard (dipakai tes JVM + calon pemanggil
 *  murni lain). Production Tools.execute memakai primitif check/record yang
 *  sama — R-id produksi baru diketahui sesudah ResultStore.put, jadi
 *  orkestrasi satu-panggilan ini tidak dipakai di sana. */
data class GuardedReadVc302(val text: String, val fromGuard: Boolean, val resultId: String)

object GuardHeaderVc302 {

    // vc302 Fix B — SATU pembangun header paging untuk read_file /
    // get_result / grep_result. Total SELALU char (bukan byte).
    // - read_file: totalBytes = byte file di disk (f.length()); ditulis
    //   "(N byte)" SELALU (eksplisit — menghilangkan ambiguitas satuan
    //   yang jadi akar insiden -16: 8085 byte = 8015 char).
    // - get_result: totalBytes = null (e.total adalah char hasil ASLI yang
    //   byte-nya tidak diketahui dari store — menulis byte teks tersimpan
    //   akan mencampur scope).
    // - grep_result: bukan rentang — header membawa total + hitungan match
    //   (matchCount wajib diisi).
    // - nextOffset == null = tampil tuntas (tanpa klausa "sisa:").
    // - storedTruncated (hanya get_result): hasil tersimpan terpotong dan
    //   sudah mentok di bagian tersimpan — header menyebutnya jujur.
    // Header tidak mengandung kata mark WorkLog.isFailed.
    fun pagingHeaderVc302(
        tool: String,
        shownStart: Int,
        shownEnd: Int,
        totalChars: Int,
        totalBytes: Int? = null,
        nextOffset: Int? = null,
        nextLength: Int? = null,
        storedTruncated: Boolean = false,
        storedChars: Int = 0,
        matchCount: Int? = null
    ): String {
        if (tool == "grep_result") {
            return "[PAGING vc302] total $totalChars char; pattern ditemukan ${matchCount ?: 0} kali"
        }
        val bytesPart = if (totalBytes != null) " ($totalBytes byte)" else ""
        val base = "[PAGING vc302] menampilkan $shownStart–$shownEnd dari $totalChars char$bytesPart"
        if (storedTruncated && nextOffset == null) {
            return base + " (tersimpan terpotong di $storedChars char — jalankan ulang tool aslinya untuk hasil penuh)"
        }
        if (nextOffset == null) return base
        return when (tool) {
            "read_file" -> base + "; sisa: read_file offset=$nextOffset length=${nextLength ?: 6000}"
            else -> base + "; sisa: get_result offset $nextOffset limit ${nextLength ?: 2000}"
        }
    }

    // vc302 Fix B — SATU-SATUNYA implementasi baca segmen file (algoritma
    // buffer 8192 persis readFileSegmentVc230 vc301, termasuk fix SF-D1:
    // argumen ke-3 append() adalah PANJANG (segEnd-segStart), bukan indeks
    // akhir — dan SF-B2: penyebab exception dikembalikan sebagai elemen
    // ketiga). Tools.readFileSegmentVc230 mendelegasikan ke sini supaya
    // tidak ada dua implementasi yang bisa divergen. total = CHAR (bukan
    // byte): file multi-byte (mis. "→" = 3 byte / 1 char) terhitung benar.
    fun readSegmentVc302(f: File, off: Int, len: Int): Triple<String, Int, String?> {
        val sb = StringBuilder()
        var total = 0
        try {
            java.io.BufferedReader(java.io.FileReader(f)).use { br ->
                val buf = CharArray(8192)
                while (true) {
                    val n = br.read(buf)
                    if (n <= 0) break
                    val segStart = maxOf(off, total)
                    val segEnd = minOf(off + len, total + n)
                    if (segEnd > segStart) sb.append(buf, segStart - total, segEnd - segStart)
                    total += n
                }
            }
        } catch (e: Exception) {
            val why = if (e is java.io.FileNotFoundException) "java.io.FileNotFoundException: ${e.message ?: "(tanpa pesan)"} — file lenyap di antara cek dan buka (exists() lolos, FileReader gagal)"
            else "${e.javaClass.name}: ${e.message ?: "(tanpa pesan)"}"
            return Triple("", 0, why)
        }
        return Triple(sb.toString(), total, null)
    }

    // Orkestrasi check → baca → record untuk pemakaian murni / tes JVM.
    // resultId = R-id yang akan dicatat bila sukses (tes memakai R-id
    // fiktif yang jujur di konteks tes, mis. "R5808"). Error (file tidak
    // ada / tidak terbaca / segmen di luar file) TIDAK dicatat — sama
    // seperti kontrak Tools.execute.
    fun readFileGuardedVc302(guard: RereadGuardVc302, f: File, off: Int, len: Int, resultId: String): GuardedReadVc302 {
        val key = RereadGuardVc302.keyForReadFileVc302(f.path, off, len)
        val prev = guard.checkVc302(key)
        if (prev != null) return GuardedReadVc302(RereadGuardVc302.guardMessageVc302("read_file", prev), true, prev)
        val (seg, total, readErr) = readSegmentVc302(f, off, len)
        if (total == 0 && f.length() > 0) return GuardedReadVc302("File tidak terbaca: ${f.path} (${f.length()} byte, sebab: ${readErr ?: "tidak ada exception tercatat saat baca"})", false, "")
        if (off >= total) return GuardedReadVc302("Segmen di luar file: offset $off >= panjang $total char.", false, "")
        guard.recordVc302(key, resultId)
        val end = off + seg.length
        val next = if (end < total) end else null
        val header = pagingHeaderVc302("read_file", off, end, total, f.length().toInt(), next, len)
        val tail = if (end < total) seg + "…(dipotong, sisa ${total - end} char; baca segmen berikutnya dengan read_file offset=$end length=$len)…" else seg
        return GuardedReadVc302(header + "\n" + tail, false, resultId)
    }
}
