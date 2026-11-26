package com.senzwh.deepharness

import harness.core.RustCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * vc293 — Skill Forge upgrade: auditor skill-judge 120 poin (adaptasi
 * skill-judge claude-code-templates) + validator 500 baris + evaluasi
 * trigger deskripsi. Yang diadopsi POLA + rubrik + workflow-nya,
 * diterjemahkan ke tool Harness — bukan impor mentah, bukan script
 * Python Claude (tidak ada `claude -p` di HP; otak = Models API,
 * eksekusi test prompt via run_team / room yang sudah ada).
 *
 * CARA KERJA (jujur soal batasnya):
 * - Penilaian ini HEURISTIK DETERMINISTIK atas teks skill (pola kata
 *   kunci + struktur), BUKAN penilaian LLM. Dimensi yang butuh
 *   pertimbangan ahli (D2 pola pikir, D6 kalibrasi kebebasan, D7 pola
 *   desain) hanya diukur lewat sinyal proksi — agen (Models API)
 *   tetap penilai utama untuk ketiganya saat membaca draf, dan laporan
 *   judge selalu menyebut mana skor yang heuristik.
 * - D1 Knowledge Delta adalah dimensi inti ("Good Skill = Expert-only
 *   Knowledge - What the Model Already Knows"). D1 <= 6 = DITOLAK
 *   (vc299 N6-m2: ambang naik 5 -> 6 — contoh audit 4 penanda expert
 *   murah + 4 redundan = 6 kini DITOLAK): draf menjelaskan hal dasar
 *   yang sudah diketahui model / tutorial generik — tampilkan alasannya,
 *   jangan diam-diam. create_skill menolak simpan bila D1 <= 6.
 * - Aturan 500 baris (skill-developer / Anthropic): inti SKILL.md
 *   <= 500 baris; detail ke bagian REFERENSI (progressive disclosure).
 *   create_skill MENOLAK instruction > 500 baris.
 * - forge_trigger_eval: sinyal keyword-overlap deterministik sebagai
 *   CROSS-CHECK + penilaian agen 3x per query (mayoritas) yang DIHITUNG
 *   tool dari agent_votes (vc301 SF-A10) — karena trigger sungguhan adalah
 *   keputusan model (apakah ia mau memuat skill), bukan hitungan kata.
 *   Tanpa agent_votes, hasil hanyalah sinyal tanpa vote dan angka trigger
 *   TIDAK boleh disimpan (forge_eval_save menolak trigger_* tanpa vote).
 *   Split 60% train / 40% test deterministik (urut hash query) supaya
 *   iterasi deskripsi bisa dibandingkan apel-dengan-apel; hash SHA-256 set
 *   query disimpan bersama eval agar TEST bisa dihitung-ulang.
 * - BUKAN checkpoint approval eksekusi tool (seperti SkillSpector
 *   vc262): ini auditor kualitas draf skill. Gate per-tool tetap
 *   dihapus (vc250/vc251) — tidak ada yang dikembalikan di sini.
 */
object SkillJudge {
    const val MAX_LINES = 500
    const val D1_REJECT_MAX = 6
    const val PASS_TOTAL = 72

    data class Dim(val id: String, val name: String, val score: Int, val max: Int, val note: String)
    data class JudgeResult(val total: Int, val grade: String, val dims: List<Dim>, val verdict: String, val reasons: List<String>, val improvements: List<String>) {
        val d1: Int get() = dims.firstOrNull { it.id == "D1" }?.score ?: 0
    }

    private data class TrigQuery(val query: String, val should: Boolean)

    /** vc299 N6-m6 — segmen kosong AKHIR akibat newline penutup BUKAN
     *  baris isi (instruction 500 baris berakhir \n = 500, bukan 501);
     *  baris kosong di TENGAH tetap dihitung. Dipakai d5 + create_skill. */
    fun lineCount(instruction: String): Int {
        val n = instruction.lines().size
        return if (instruction.endsWith("\n") && n > 1) n - 1 else n
    }

    private fun has(text: String, pattern: String): Boolean {
        return try { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text) } catch (e: Exception) { false }
    }

    private fun countMatches(text: String, pattern: String): Int {
        return try { Regex(pattern, RegexOption.IGNORE_CASE).findAll(text).count() } catch (e: Exception) { 0 }
    }

    private fun distinctHits(text: String, patterns: List<String>): List<String> {
        return patterns.filter { has(text, it) }
    }

    // ---------- D1 Knowledge Delta (20) — dimensi inti ----------
    private val REDUNDANT_PATTERNS = listOf(
        "apa itu ", "pengertian ", "definisi ", "tutorial",
        "apa yang dimaksud", "secara umum, .* adalah",
        "cara menulis (sebuah )?(fungsi|for|loop|kode)",
        "langkah 1: buka", "langkah 2: (edit|ubah)", "langkah 3: simpan"
    )
    private val EXPERT_PATTERNS = listOf(
        "jangan pernah .* karena", "trade-?off", "edge case",
        "bila .* gagal", "keputusan:", "hindari .* karena",
        "alasan:", "jebakan", "kesalahan umum .* karena",
        "bila .* maka .* karena", "coba .* karena"
    )

    private fun d1(instruction: String): Dim {
        val expert = distinctHits(instruction, EXPERT_PATTERNS)
        val redundant = distinctHits(instruction, REDUNDANT_PATTERNS)
        var s = 10 + expert.size * 2 - redundant.size * 3
        if (s > 20) s = 20
        if (s < 0) s = 0
        // vc301 SF-A9 — cantumkan hitungan agar perubahan skor bisa dilacak
        // per frasa (rumus TIDAK berubah: 10 + 2*expert - 3*redundan).
        val note = "expert (" + expert.size + "): " + (if (expert.isEmpty()) "tidak ada" else expert.joinToString(", ")) +
            " | redundan (" + redundant.size + "): " + (if (redundant.isEmpty()) "tidak ada" else redundant.joinToString(", ")) +
            " | hitung: 10 + 2*" + expert.size + " - 3*" + redundant.size + " = " + s + " (clamp 0..20)"
        return Dim("D1", "Knowledge Delta", s, 20, note)
    }

    // ---------- D2 Mindset + Prosedur (15) — heuristik ----------
    private val THINKING_PATTERNS = listOf(
        "tanyakan (pada |ke )?(diri|dirimu)", "sebelum .* (tanya|tanyakan|pertimbangkan)",
        "pola pikir", "kerangka berpikir", "pertimbangkan"
    )
    // vc301 SF-A7 — daftar tool D2 DITURUNKAN dari katalog nyata (bukan daftar
    // manual 19 nama yang basi): 154 ToolDef di ToolRegistry.ALL. Nama <4 huruf
    // dikecualikan — satu-satunya adalah "tap"; pencocokan substring akan
    // false-positive pada kata Indonesia umum ("tetap", "menetapkan" mengandung
    // "tap"). Kemampuan tap tetap terwakili via "tap_ref"/"set_text_ref".
    // SkillJudge tetap fungsi murni: ToolRegistry tanpa dependensi Android.
    private val HARNESS_TOOLS: List<String> =
        ToolRegistry.ALL.map { it.name }.filter { it.length >= 4 }
    private val GENERIC_STEPS = listOf("langkah \\d+: buka", "langkah \\d+: edit", "langkah \\d+: simpan")

    private fun d2(instruction: String): Dim {
        var s = 5
        val thinking = THINKING_PATTERNS.any { has(instruction, it) }
        if (thinking) s += 2
        if (countMatches(instruction, "bila .* maka") >= 2) s += 2
        val tools = HARNESS_TOOLS.filter { instruction.contains(it, ignoreCase = true) }
        if (tools.size >= 3) s += 3
        if (tools.isNotEmpty() && has(instruction, "dulu|kemudian|setelah itu|urutan:")) s += 3
        if (GENERIC_STEPS.count { has(instruction, it) } >= 2) s -= 3
        if (s > 15) s = 15
        if (s < 0) s = 0
        return Dim("D2", "Mindset+Prosedur", s, 15,
            "tool Harness disebut: " + tools.size + " (dari " + HARNESS_TOOLS.size + " nama katalog vc301)" + (if (thinking) "; ada kerangka berpikir" else "; tanpa kerangka berpikir") + " (heuristik)")
    }

    // ---------- D3 Anti-Pattern (15) ----------
    private fun d3(instruction: String): Dim {
        val never = listOf("jangan pernah", "\\bnever\\b", "dilarang", "jangan .* karena", "pantang", "hindari .* karena")
        var n = 0
        var withReason = 0
        for (line in instruction.lines()) {
            if (never.any { has(line, it) }) {
                n++
                if (line.contains("karena", ignoreCase = true)) withReason++
            }
        }
        var s = when { n == 0 -> 2; n <= 2 -> 6; n <= 4 -> 10; else -> 13 }
        if (withReason >= 3) s += 2
        if (s > 15) s = 15
        return Dim("D3", "Anti-Pattern", s, 15, "$n larangan eksplisit, $withReason disertai alasan")
    }

    // ---------- D4 Spec Compliance (15) — deskripsi adalah yang utama ----------
    private fun d4(name: String, descr: String, instruction: String): Dim {
        var s = 0
        val notes = ArrayList<String>()
        if (name.matches(Regex("^[a-z0-9][a-z0-9-]{0,63}$"))) s += 3 else notes.add("nama bukan slug valid/<=64")
        if (descr.isNotBlank()) s += 2
        if (descr.length >= 40) s += 2
        if (has(descr, "kapan|bila|saat|ketika|use when|pakai .* bila|gunakan .* (saat|ketika|bila)")) s += 3
        else notes.add("deskripsi belum menyebut KAPAN dipakai")
        val shared = contentWords(descr).intersect(contentWords(instruction)).size
        if (shared >= 2) s += 3 else notes.add("kata kunci deskripsi tidak nyambung ke isi")
        if (has(descr + "\n" + instruction, "jangan picu|jangan .* bila|bukan untuk|tidak untuk")) s += 2
        else notes.add("belum ada daftar should-not-trigger")
        if (s > 15) s = 15
        return Dim("D4", "Spec Compliance", s, 15,
            if (notes.isEmpty()) "nama valid; deskripsi menjawab APA + KAPAN + kata kunci; ada should-not" else notes.joinToString("; "))
    }

    // ---------- D5 Progressive Disclosure (15) ----------
    private fun d5(instruction: String): Dim {
        val lines = lineCount(instruction)
        if (lines > MAX_LINES) {
            val s = maxOf(0, 5 - (lines - MAX_LINES) / 100)
            return Dim("D5", "Progressive Disclosure", s, 15,
                "$lines baris — MELEBIHI batas 500 (create_skill menolak; pindah detail ke REFERENSI)")
        }
        var s = 10
        if (lines <= 300) s += 2
        if (has(instruction, "referensi|baca hanya bila|lihat bagian|detail:")) s += 3
        if (s > 15) s = 15
        return Dim("D5", "Progressive Disclosure", s, 15, "$lines baris (maks 500)" + if (lines <= 300) "; ringkas" else "")
    }

    // ---------- D6 Freedom Calibration (15) — heuristik ----------
    private fun d6(instruction: String): Dim {
        val rigid = countMatches(instruction, "\\b(wajib|harus|selalu|dilarang)\\b")
        val flex = countMatches(instruction, "\\b(boleh|bila perlu|pilih|sesuaikan|opsional|alternatif)\\b")
        val why = countMatches(instruction, "\\b(karena|alasan|supaya|agar)\\b")
        val fallback = has(instruction, "bila gagal|fallback|alternatif|bila tidak")
        var s = 8
        if (why >= 3) s += 3
        if (flex >= 2) s += 2
        if (fallback) s += 2
        if (rigid >= 10 && flex == 0) s -= 4
        if (s > 15) s = 15
        if (s < 0) s = 0
        return Dim("D6", "Freedom Calibration", s, 15,
            "kaku:$rigid fleksibel:$flex alasan:$why" + (if (fallback) " +fallback" else "") + " (heuristik — agen meninjau)")
    }

    // ---------- D7 Pattern Recognition (10) — heuristik ----------
    private fun d7(instruction: String): Dim {
        val els = listOf(
            "## .*overview|## gambaran|## tujuan|## purpose" to "gambaran",
            "kapan dipakai|when to use|## kapan" to "kapan dipakai",
            "## langkah|## cara|## alur|## workflow|## prosedur" to "alur",
            "kesalahan|common mistakes|jebakan|## .*salah" to "kesalahan umum",
            "contoh|example" to "contoh"
        )
        val hit = els.filter { has(instruction, it.first) }
        return Dim("D7", "Pattern Recognition", minOf(10, hit.size * 2), 10,
            "elemen struktur: " + (if (hit.isEmpty()) "tidak ada" else hit.joinToString(", ") { it.second }) + " (heuristik)")
    }

    // ---------- D8 Practical Usability (15) ----------
    private fun d8(instruction: String): Dim {
        var s = 0
        val notes = ArrayList<String>()
        if (has(instruction, "contoh")) { s += 4; notes.add("contoh") }
        if (countMatches(instruction, "bila .* maka") >= 1) { s += 4; notes.add("panduan keputusan") }
        if (has(instruction, "bila gagal|error|fallback|bila tidak|bila .* kosong")) { s += 4; notes.add("fallback") }
        if (has(instruction, "edge case|kasus tepi|bila .* tidak ada")) { s += 3; notes.add("kasus tepi") }
        return Dim("D8", "Practical Usability", minOf(15, s), 15,
            if (notes.isEmpty()) "tanpa contoh/panduan/fallback" else notes.joinToString(", "))
    }

    private fun improvementFor(id: String): String {
        return when (id) {
            "D1" -> "Hapus penjelasan dasar (apa itu / pengertian / tutorial generik); tambah keputusan, trade-off, dan edge case yang hanya diketahui dari pengalaman."
            "D2" -> "Tambah kerangka berpikir ('Sebelum ..., tanyakan pada diri: ...') + prosedur domain spesifik Harness (nama tool + urutan yang tidak generik)."
            "D3" -> "Tambah daftar JANGAN yang spesifik beserta ALASANNYA ('JANGAN ... karena ...') — setengah ilmu ahli adalah tahu apa yang tidak boleh dilakukan."
            "D4" -> "Perbaiki deskripsi: jawab APA + KAPAN + kata kunci pemicu; tambah contoh pemicu konkret + kasus yang TIDAK boleh memicu."
            "D5" -> "Pangkas inti <=500 baris (ideal <=300); pindah detail ke bagian REFERENSI dengan penanda 'baca hanya bila ...'."
            "D6" -> "Kalibrasi kebebasan: jelaskan ALASAN di balik aturan kaku; beri alternatif/fallback untuk kasus yang tidak kritis."
            "D7" -> "Rapikan struktur mengikuti satu pola yang jelas (mis. Proses: gambaran -> kapan dipakai -> alur -> contoh -> kesalahan umum)."
            else -> "Tambah 1 contoh konkret yang bisa dijalankan + apa yang dilakukan bila langkah utama gagal (fallback) + kasus tepi."
        }
    }

    fun score(name: String, descr: String, instruction: String): JudgeResult {
        val dims = listOf(
            d1(instruction), d2(instruction), d3(instruction), d4(name, descr, instruction),
            d5(instruction), d6(instruction), d7(instruction), d8(instruction)
        )
        val total = dims.sumOf { it.score }
        val grade = when { total >= 108 -> "A"; total >= 96 -> "B"; total >= 84 -> "C"; total >= 72 -> "D"; else -> "F" }
        val d1v = dims[0].score
        val verdict = when { d1v <= D1_REJECT_MAX -> "DITOLAK"; total < PASS_TOTAL -> "PERBAIKI"; else -> "LULUS" }
        val reasons = ArrayList<String>()
        if (d1v <= D1_REJECT_MAX) {
            reasons.add("D1 Knowledge Delta $d1v/20 — draf menjelaskan hal dasar yang sudah diketahui model (penanda redundan: " +
                distinctHits(instruction, REDUNDANT_PATTERNS).joinToString(", ").ifBlank { "—" } +
                ") dan minim ilmu khas ahli (penanda expert: " +
                distinctHits(instruction, EXPERT_PATTERNS).joinToString(", ").ifBlank { "tidak ada" } + ")")
            reasons.add("Skill seperti ini membuang konteks tanpa menambah kemampuan — hapus penjelasan dasar, tambah keputusan/trade-off/edge case dari pengalaman")
        }
        if (total < PASS_TOTAL) reasons.add("Total $total/120 di bawah ambang lulus $PASS_TOTAL (grade F) — perbaiki dimensi terlemah dulu sebelum aktivasi")
        val improvements = ArrayList<String>()
        for (d in dims) {
            if (d.score * 5 < d.max * 3) improvements.add(improvementFor(d.id))
        }
        return JudgeResult(total, grade, dims, verdict, reasons, improvements)
    }

    fun verdictText(name: String, r: JudgeResult): String {
        val sb = StringBuilder()
        sb.append("# Penilaian Skill: ").append(name).append(" — Skill Judge vc293 (120 poin)\n")
        sb.append("- Skor total: ").append(r.total).append("/120 (").append(r.total * 100 / 120).append("%) — Grade ").append(r.grade).append("\n")
        sb.append("- Verdict: ").append(r.verdict)
        if (r.verdict == "DITOLAK") sb.append(" — Knowledge Delta D1 = ").append(r.d1).append("/20 (ambang tolak <=").append(D1_REJECT_MAX).append(")")
        sb.append("\n")
        sb.append("| Dimensi | Skor | Maks | Catatan |\n")
        for (d in r.dims) sb.append("| ").append(d.id).append(" ").append(d.name).append(" | ").append(d.score).append(" | ").append(d.max).append(" | ").append(d.note).append(" |\n")
        if (r.reasons.isNotEmpty()) {
            sb.append("## Masalah kritis\n")
            for (x in r.reasons) sb.append("- ").append(x).append("\n")
        }
        if (r.improvements.isNotEmpty()) {
            sb.append("## Perbaikan teratas\n")
            r.improvements.take(3).forEachIndexed { i, x -> sb.append(i + 1).append(". ").append(x).append("\n") }
        }
        sb.append("Catatan: skor D2/D6/D7 heuristik (sinyal proksi atas teks) — agen tetap penilai utama untuk ketiganya saat meninjau draf. D1 <=").append(D1_REJECT_MAX).append(" = DITOLAK (create_skill menolak simpan + tampilkan alasan).")
        sb.append(" Daftar tool D2 diperbarui vc301: ").append(HARNESS_TOOLS.size).append(" nama dari katalog ToolRegistry (termasuk tool perangkat seperti get_battery, storage_info).")
        return sb.toString()
    }

    fun summaryLine(r: JudgeResult): String {
        val sb = StringBuilder()
        sb.append("Penilaian Skill Judge vc293: ").append(r.total).append("/120 (grade ").append(r.grade)
            .append(", D1 ").append(r.d1).append("/20) — verdict ").append(r.verdict).append(".")
        if (r.verdict == "PERBAIKI" && r.improvements.isNotEmpty()) sb.append(" Perbaiki dulu: ").append(r.improvements.first())
        sb.append(" Lanjut EVAL LOOP vc293 (uji A/B + skenario tekanan + optimasi deskripsi) sebelum minta user mengaktifkan skill.")
        return sb.toString()
    }

    // ---------- Evaluasi trigger deskripsi (forge_trigger_eval) ----------
    private val STOPWORDS = setOf(
        "yang", "dengan", "untuk", "adalah", "saya", "kamu", "tolong", "buatkan", "bagaimana",
        "cara", "bisa", "atau", "dari", "mau", "ingin", "agar", "supaya", "jika", "bila",
        "saat", "ketika", "sebuah", "dalam", "pada", "oleh", "juga", "sudah", "belum", "akan",
        "saja", "sangat", "lagi", "masih", "itu", "ini", "apa", "siapa", "dimana", "berapa",
        "the", "and", "for", "with", "how", "what", "please", "when", "this", "that", "your",
        "skill", "pakai", "guna", "harus", "jangan"
    )

    private fun contentWords(text: String): Set<String> {
        return text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in STOPWORDS }.toSet()
    }

    // ---------- vc312 Opsi A — sinyal negatif dari bagian "JANGAN PICU:" ----------
    // S1=A: bagian larangan dikenali via marker eksplisit di deskripsi.
    // Baris yang diawali marker (case-insensitive, toleran spasi) membuka
    // bagian larangan; sisa baris itu + baris-baris berikut sampai baris
    // kosong berikutnya / akhir deskripsi = bagian larangan, sisanya =
    // bagian positif. Tanpa marker -> bagian larangan kosong (no-op:
    // perilaku sinyal persis seperti sebelum vc312).
    private val NEG_MARKER_REGEX = Regex("^\\s*jangan\\s+picu\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)

    // S2=A: bobot negatif w=1 (lunak). Skor bersih = pos − w×neg.
    private const val NEG_WEIGHT_VC312 = 1

    /** Hasil pemecahan deskripsi: bagian positif, bagian larangan, dan
     *  apakah marker ditemukan. */
    data class DescrSections(val positive: String, val negative: String, val markerFound: Boolean)

    /** Skor sinyal trigger satu query (vc312 Opsi A). */
    data class TriggerScore(val trigger: Boolean, val posOverlap: Int, val negOverlap: Int, val netScore: Int)

    // ---- Seam Rust (slice 6, vc320) ----
    // Scoring trigger vc312 jalan di Rust bila core tersedia; bila tidak,
    // fallback ke implementasi Kotlin lama (*Kotlin — JANGAN dihapus).
    // Sekali Rust gagal, saklar dimatikan.
    /** Saklar A/B slice 6: false = pakai Kotlin lama. */
    var rustSkillTrigger: Boolean = true

    /** Panggil seam JSON; null = Rust gagal / respons bukan ok:true. */
    private fun rustTriggerValue(request: JSONObject): JSONObject? {
        if (!rustSkillTrigger || !RustCore.available) return null
        return try {
            val obj = JSONObject(RustCore.skillTriggerJson(request.toString()))
            if (!obj.optBoolean("ok", false)) {
                rustSkillTrigger = false
                null
            } else obj
        } catch (e: Throwable) {
            rustSkillTrigger = false
            null
        }
    }

    /** Pecah deskripsi jadi bagian positif + bagian larangan (S1=A).
     *  Hanya marker PERTAMA yang dikenali; kata markernya sendiri
     *  ("JANGAN PICU:") BUKAN kata konten larangan — hanya sisa baris
     *  sesudah ":" yang masuk bagian larangan. */
    fun splitDescrSections(descr: String): DescrSections {
        try {
            val v = rustTriggerValue(JSONObject().put("op", "splitDescrSections").put("descr", descr))?.getJSONObject("value")
            if (v != null) return DescrSections(v.getString("positive"), v.getString("negative"), v.getBoolean("markerFound"))
        } catch (e: Throwable) {
            rustSkillTrigger = false
        }
        return splitDescrSectionsKotlin(descr)
    }

    /** Implementasi Kotlin lama (fallback) — DIPERTAHANKAN. */
    private fun splitDescrSectionsKotlin(descr: String): DescrSections {
        val lines = descr.lines()
        val markerIdx = lines.indexOfFirst { NEG_MARKER_REGEX.matches(it) }
        if (markerIdx < 0) return DescrSections(descr, "", false)
        val markerMatch = NEG_MARKER_REGEX.find(lines[markerIdx])!!
        val negLines = ArrayList<String>()
        negLines.add(markerMatch.groupValues[1])
        var end = markerIdx + 1
        while (end < lines.size && lines[end].isNotBlank()) {
            negLines.add(lines[end])
            end++
        }
        val posLines = ArrayList<String>()
        for (i in lines.indices) {
            if (i < markerIdx || i > end) posLines.add(lines[i])
        }
        return DescrSections(posLines.joinToString("\n"), negLines.joinToString("\n"), true)
    }

    /** Kata negatif sinyal trigger (vc312 Opsi A).
     *  ATURAN MINUS-POSITIF (WAJIB): negWords = contentWords(bagian larangan)
     *  MINUS contentWords(bagian positif). Kata yang ada di KEDUA bagian
     *  (mis. "telegram" dalam "berita/artikel Telegram" di daftar larangan)
     *  TIDAK masuk negWords dan TIDAK terpenalti — bila aturan ini lupa,
     *  kata pemicu utama ikut terpenalti. */
    fun negWordsOf(descr: String): Set<String> {
        try {
            val arr: JSONArray? = rustTriggerValue(JSONObject().put("op", "negWordsOf").put("descr", descr))?.getJSONArray("value")
            if (arr != null) {
                val out = HashSet<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i))
                return out
            }
        } catch (e: Throwable) {
            rustSkillTrigger = false
        }
        return negWordsOfKotlin(descr)
    }

    /** Implementasi Kotlin lama (fallback) — DIPERTAHANKAN. */
    private fun negWordsOfKotlin(descr: String): Set<String> {
        val sections = splitDescrSections(descr)
        if (!sections.markerFound) return emptySet()
        return contentWords(sections.negative) - contentWords(sections.positive)
    }

    /** Skor sinyal trigger satu query (vc312 Opsi A, S2=A w=1, S5=A ambang
     *  tetap >=2 atas skor bersih). Skor dijepit minimum 0 (negatif -> 0,
     *  tetap TIDAK). Tanpa marker (negWords kosong) skor = overlap lama —
     *  perilaku identik sebelum vc312. */
    fun triggerScore(descr: String, query: String): TriggerScore {
        try {
            val v = rustTriggerValue(JSONObject().put("op", "triggerScore").put("descr", descr).put("query", query))?.getJSONObject("value")
            if (v != null) return TriggerScore(v.getBoolean("trigger"), v.getInt("posOverlap"), v.getInt("negOverlap"), v.getInt("netScore"))
        } catch (e: Throwable) {
            rustSkillTrigger = false
        }
        return triggerScoreKotlin(descr, query)
    }

    /** Implementasi Kotlin lama (fallback) — DIPERTAHANKAN. */
    private fun triggerScoreKotlin(descr: String, query: String): TriggerScore {
        val sections = splitDescrSections(descr)
        val posWords = contentWords(sections.positive)
        val negWords = if (sections.markerFound) contentWords(sections.negative) - posWords else emptySet()
        return triggerScore(posWords, negWords, query)
    }

    private fun triggerScore(posWords: Set<String>, negWords: Set<String>, query: String): TriggerScore {
        val qWords = contentWords(query)
        val pos = qWords.intersect(posWords).size
        val neg = qWords.intersect(negWords).size
        val net = (pos - NEG_WEIGHT_VC312 * neg).coerceAtLeast(0)
        return TriggerScore(net >= 2, pos, neg, net)
    }

    // ---------- Evaluasi trigger deskripsi (forge_trigger_eval) ----------
    // vc301 SF-A10 — agent_votes: 3 vote TRIGGER/TIDAK per query; mayoritas
    // dihitung tool (bukan diklaim di chat). Tanpa vote = sinyal saja.
    // vc301 SF-A11 — penegakan di kode: minimal 8 should-trigger + 8
    // should-not-trigger, tolak duplikat (lowercase+trim), tolak query tanpa
    // kata konten (semua kata <4 huruf / stopword).
    private data class TrigVote(val query: String, val should: Boolean, val votes: List<Boolean>)

    private fun normalizeQuery(q: String): String = q.trim().lowercase()

    /** Satu suara: boolean, atau string TRIGGER/TIDAK (juga "true"/"false"). */
    private fun parseVote(v: Any?): Boolean? {
        return when (v) {
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "trigger", "true" -> true
                "tidak", "false" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun majorityTrigger(votes: List<Boolean>): Boolean = votes.count { it } >= 2

    /** Kanonik set query untuk hash: "query|should" per baris, terurut. */
    private fun canonicalQuerySet(pairs: List<Pair<String, Boolean>>): String {
        return pairs.map { (q, s) -> normalizeQuery(q) + "|" + s }
            .sorted().joinToString("\n")
    }

    private fun sha256Hex(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Hash SHA-256 set query eval — disimpan bersama eval agar trigger_test
     *  bisa dihitung-ulang seperti judge. Satu fungsi kanonik dipakai baik
     *  untuk argumen queries (forge_trigger_eval) maupun agent_votes
     *  (forge_eval_save) sehingga keduanya selalu sepakat. */
    fun triggerQuerySetHash(queries: JSONArray): String {
        val pairs = ArrayList<Pair<String, Boolean>>()
        for (i in 0 until queries.length()) {
            val o = queries.optJSONObject(i) ?: continue
            val q = o.optString("query").trim()
            if (q.isNotEmpty()) pairs.add(Pair(q, o.optBoolean("should_trigger", false)))
        }
        return sha256Hex(canonicalQuerySet(pairs))
    }

    private fun querySetHashFromVotes(votes: List<TrigVote>): String {
        return sha256Hex(canonicalQuerySet(votes.map { Pair(it.query, it.should) }))
    }

    /** Akurasi TRAIN/TEST dari mayoritas vote atas split 60/40 yang sama
     *  dengan sinyal (urut String.hashCode query). */
    private fun voteAccuracies(votes: List<TrigVote>): Pair<Int, Int> {
        // VC303-P1-6 (EV-4): sort memakai query TERNORMALISASI (sama seperti
        // hash kanonik :344-346) — hash dan partisi sepakat selalu; dua set
        // yang hanya beda kapitalisasi tidak lagi menghasilkan split beda.
        val sorted = votes.sortedBy { normalizeQuery(it.query).hashCode() }
        val nTrain = (sorted.size * 60) / 100
        fun acc(rows: List<TrigVote>): Int {
            if (rows.isEmpty()) return 0
            var ok = 0
            for (r in rows) if (majorityTrigger(r.votes) == r.should) ok++
            return ok * 100 / rows.size
        }
        return Pair(acc(sorted.subList(0, nTrain)), acc(sorted.subList(nTrain, sorted.size)))
    }

    fun triggerEvalText(descr: String, queries: JSONArray, agentVotes: JSONArray? = null): String {
        val list = ArrayList<TrigQuery>()
        for (i in 0 until queries.length()) {
            val o = queries.optJSONObject(i) ?: continue
            val q = o.optString("query").trim()
            if (q.isNotEmpty()) list.add(TrigQuery(q, o.optBoolean("should_trigger", false)))
        }
        // SF-A11: minimal 8 + 8 (menggantikan ambang lama >=10 + kedua kelas ada).
        val nShould = list.count { it.should }
        val nNot = list.size - nShould
        if (list.size < 16 || nShould < 8 || nNot < 8)
            return "forge_trigger_eval: butuh minimal 8 should-trigger + 8 should-not-trigger (total >=16; ideal 20: 8–10 should-trigger termasuk near-miss & skill bersaing, 8–10 should-not-trigger tricky). Dapat: ${list.size} query ($nShould should-trigger, $nNot should-not-trigger)."
        // SF-A11: tolak duplikat (normalisasi lowercase+trim).
        val seen = HashSet<String>()
        val dups = ArrayList<String>()
        for (t in list) if (!seen.add(normalizeQuery(t.query))) dups.add(t.query)
        if (dups.isNotEmpty())
            return "forge_trigger_eval: query duplikat ditolak (normalisasi lowercase+trim): " + dups.distinct().joinToString("; ") { "\"$it\"" } + ". Tiap query dihitung sekali — duplikat menggandakan bobotnya."
        // SF-A11: tolak query tanpa kata konten (semua kata <4 huruf / stopword).
        val noContent = list.filter { contentWords(it.query).isEmpty() }
        if (noContent.isNotEmpty())
            return "forge_trigger_eval: query tanpa kata konten ditolak (semua kata <4 huruf atau stopword, tidak terlihat sinyal): " + noContent.joinToString("; ") { "\"${it.query}\"" } + ". Tulis ulang query dengan kata kunci >=4 huruf."
        // VC303-P1-6 (EV-4): lihat voteAccuracies di atas — sort ternormalisasi.
        val sorted = list.sortedBy { normalizeQuery(it.query).hashCode() }
        val nTrain = (sorted.size * 60) / 100
        val train = sorted.subList(0, nTrain)
        val test = sorted.subList(nTrain, sorted.size)
        val sections = splitDescrSections(descr)
        val posWords = contentWords(sections.positive)
        // ATURAN MINUS-POSITIF (WAJIB, vc312 Opsi A): kata yang ada di kedua
        // bagian TIDAK masuk negWords — lihat negWordsOf di atas.
        val negWords = if (sections.markerFound) contentWords(sections.negative) - posWords else emptySet()
        fun predict(q: String): TriggerScore = triggerScore(posWords, negWords, q)
        // Ambang tetap >=2 atas skor bersih (vc312 Opsi A, S5=A). Usulan
        // proporsional (overlap >=2 ATAU >=50% kata konten query) dicatat
        // sebagai KEPUTUSAN TERTUNDA — jangan diubah diam-diam: mengubah
        // sensitivitas semua eval lama.
        fun acc(rows: List<TrigQuery>): Int {
            if (rows.isEmpty()) return 0
            var ok = 0
            for (r in rows) if (predict(r.query).trigger == r.should) ok++
            return ok * 100 / rows.size
        }
        val trainAcc = acc(train)
        val testAcc = acc(test)
        val setHash = triggerQuerySetHash(queries)
        // SF-A10: vote agen 3x — validasi + mayoritas dihitung tool.
        var voteList: List<TrigVote>? = null
        var voteTrain = -1
        var voteTest = -1
        if (agentVotes != null) {
            val parsed = ArrayList<TrigVote>()
            for (i in 0 until agentVotes.length()) {
                val o = agentVotes.optJSONObject(i)
                    ?: return "forge_trigger_eval: agent_votes[$i] bukan objek {query, should_trigger, votes}."
                val q = o.optString("query").trim()
                if (q.isEmpty()) return "forge_trigger_eval: agent_votes[$i] tanpa query."
                if (!o.has("should_trigger")) return "forge_trigger_eval: vote untuk query \"$q\" tanpa should_trigger — sertakan should_trigger yang sama dengan queries."
                val arr = o.optJSONArray("votes")
                if (arr == null || arr.length() != 3) return "forge_trigger_eval: vote untuk query \"$q\" harus tepat 3 suara (dapat ${arr?.length() ?: 0})."
                val vs = ArrayList<Boolean>()
                for (j in 0 until 3) {
                    val pv = parseVote(arr.opt(j))
                        ?: return "forge_trigger_eval: suara ke-${j + 1} untuk query \"$q\" tidak bisa dibaca (pakai true/false atau \"TRIGGER\"/\"TIDAK\")."
                    vs.add(pv)
                }
                parsed.add(TrigVote(q, o.optBoolean("should_trigger", false), vs))
            }
            val byQuery = list.associateBy { normalizeQuery(it.query) }
            for (v in parsed) {
                val t = byQuery[normalizeQuery(v.query)]
                    ?: return "forge_trigger_eval: vote untuk query \"${v.query}\" tidak ada di queries eval — vote harus untuk tiap query eval, tidak lebih."
                if (v.should != t.should) return "forge_trigger_eval: should_trigger vote untuk query \"${v.query}\" (${v.should}) tidak cocok dengan queries (${t.should})."
            }
            val missingQs = list.filter { tq -> parsed.none { p -> normalizeQuery(p.query) == normalizeQuery(tq.query) } }
            if (missingQs.isNotEmpty())
                return "forge_trigger_eval: agent_votes tidak lengkap — query tanpa vote: " + missingQs.joinToString("; ") { "\"${it.query}\"" } + ". Tiap query eval butuh tepat 3 vote."
            val dupVotes = parsed.groupingBy { normalizeQuery(it.query) }.eachCount().filter { it.value > 1 }.keys
            if (dupVotes.isNotEmpty())
                return "forge_trigger_eval: agent_votes duplikat untuk query: " + dupVotes.joinToString("; ") { "\"$it\"" } + "."
            voteList = parsed
            val (vt, vs2) = voteAccuracies(parsed)
            voteTrain = vt
            voteTest = vs2
        }
        val sb = StringBuilder()
        sb.append("Optimasi deskripsi vc293 — sinyal trigger (keyword-overlap, cross-check deterministik)\n")
        sb.append("Eval set: ").append(list.size).append(" query (train ").append(train.size).append(" / test ").append(test.size).append(", split 60/40 by hash query).\n")
        sb.append("Query set hash (SHA-256, simpan bersama eval sebagai trigger_query_hash): ").append(setHash).append("\n")
        sb.append("TRAIN: ").append(trainAcc).append("% — TEST: ").append(testAcc).append("% (ambang lolos: TEST >=80% DAN TRAIN >=80%).\n")
        sb.append("Prediksi sinyal = TRIGGER bila skor bersih >=2 (skor bersih = overlap positif − overlap negatif, dijepit min 0; tanpa bagian JANGAN PICU, skor = overlap lama).\n")
        // vc312 Opsi A (R1): sebut apakah bagian larangan terdeteksi — bila 0,
        // agen tahu marker belum ada di deskripsi.
        if (sections.markerFound) {
            sb.append("negWords: ").append(negWords.size).append(" kata dari bagian JANGAN PICU (skor bersih = overlap positif − overlap negatif, TRIGGER bila skor >=2).\n")
        } else {
            sb.append("negWords: 0 kata dari bagian JANGAN PICU — marker \"JANGAN PICU:\" tidak ditemukan di deskripsi, sinyal memakai rumus lama (overlap >=2).\n")
        }
        if (voteList == null) {
            sb.append("Hasil ini SINYAL (tanpa vote): penilaian agen 3x per query belum dicatat. Angka sinyal di atas TIDAK boleh disimpan sebagai trigger_train/trigger_test — forge_eval_save menolak trigger_* tanpa agent_votes. Untuk menyimpan benchmark trigger, panggil lagi dengan agent_votes (3 vote TRIGGER/TIDAK per query).\n")
        } else {
            val vl = voteList
            sb.append("Vote agen 3x (mayoritas, dihitung tool): TRAIN ").append(voteTrain).append("% — TEST ").append(voteTest).append("% (ambang lolos sama: TEST >=80% DAN TRAIN >=80%).\n")
            // VC303-P1-2 (EV-2 opsi b): blok JSON verbatim di AWAL output
            // (disisipkan saat return, sebelum daftar gagal) — salin apa
            // adanya ke forge_eval_save, jangan ketik ulang. Run yang sama
            // juga tersimpan di DB (vc303 opsi a); blok ini jalan verifikasi
            // manual + transparansi.
            sb.append("Simpan ke forge_eval_save: trigger_train=").append(voteTrain).append(" trigger_test=").append(voteTest).append(" + agent_votes (apa adanya) + trigger_query_hash=").append(setHash).append(". ATAU cukup kirim trigger_query_hash saja — votes diambil dari run eval tersimpan (vc303); bila draf berubah sejak eval, run kedaluwarsa dan eval wajib diulang.\n")
            val failedVotes = sorted.filter { tq -> vl.first { p -> normalizeQuery(p.query) == normalizeQuery(tq.query) }.let { majorityTrigger(it.votes) != tq.should } }
            if (failedVotes.isNotEmpty()) {
                sb.append("Query yang diprediksi SALAH oleh mayoritas vote (").append(failedVotes.size).append("):\n")
                for (f in failedVotes) {
                    val v = vl.first { p -> normalizeQuery(p.query) == normalizeQuery(f.query) }
                    sb.append("- [harusnya ").append(if (f.should) "TRIGGER" else "TIDAK").append(", mayoritas vote: ").append(if (majorityTrigger(v.votes)) "TRIGGER" else "TIDAK").append("] ").append(f.query.take(100)).append("\n")
                }
            }
        }
        val failed = sorted.filter { predict(it.query).trigger != it.should }
        if (failed.isEmpty()) sb.append("Semua query diprediksi benar oleh sinyal.\n")
        else {
            sb.append("Query yang diprediksi SALAH oleh sinyal (").append(failed.size).append("):\n")
            for (f in failed) {
                val p = predict(f.query)
                sb.append("- [harusnya ").append(if (f.should) "TRIGGER" else "TIDAK").append(", sinyal: ").append(if (p.trigger) "TRIGGER" else "TIDAK").append(", skor ").append(p.netScore).append(" (pos ").append(p.posOverlap).append(", neg ").append(p.negOverlap).append(")] ").append(f.query.take(100)).append("\n")
            }
        }
        val passSignal = testAcc >= 80 && trainAcc >= 80
        val passVote = voteList == null || (voteTest >= 80 && voteTrain >= 80)
        sb.append(if (passSignal && passVote) "Verdict: LOLOS." else "Verdict: PERBAIKI deskripsi (tambah/kurangi kata pemicu, maks 5 iterasi, pilih by TEST — bukan train).")
        sb.append(" Split deterministik (urut String.hashCode query) untuk set query yang SAMA — tapi set query dipilih agen/model sendiri; angka ini sinyal atas pilihan itu, bukan benchmark independen.")
        // VC303-P1-2 (EV-2 opsi b): blok JSON verbatim di AWAL output —
        // sebelum daftar gagal, agar selamat dari cap 9000 char hasil tool
        // (Tools.kt:3080) yang memotong ekor. Hanya bila ada vote (tanpa
        // vote tidak ada angka trigger yang sah untuk disalin).
        if (voteList == null || agentVotes == null) return sb.toString()
        val blockVc303 = JSONObject()
        blockVc303.put("trigger_query_hash", setHash)
        blockVc303.put("trigger_train", voteTrain)
        blockVc303.put("trigger_test", voteTest)
        blockVc303.put("agent_votes", agentVotes)
        return "BLOK JSON EVAL (vc303) — salin blok ini apa adanya ke forge_eval_save, jangan ketik ulang; atau cukup kirim trigger_query_hash saja (run tersimpan):\n" + blockVc303.toString() + "\n" + sb.toString()
    }

    /** vc301 SF-A10 — validasi eval trigger yang hendak disimpan (forge_eval_save).
     *  Mengembalikan pesan tolak, atau null bila boleh disimpan. Aturan:
     *  - tanpa trigger_train/trigger_test -> null (tidak ada klaim trigger;
     *    kompatibel: eval judge/A-B/TDD saja tetap bisa disimpan).
     *  - dengan trigger_* -> wajib agent_votes lengkap (tiap entri: query,
     *    should_trigger, tepat 3 vote) + trigger_query_hash yang cocok dengan
     *    hitung-ulang dari agent_votes + angka TRAIN/TEST yang cocok dengan
     *    hitung-ulang mayoritas vote. */
    fun validateSavedTriggerEval(eval: JSONObject): String? {
        val hasTrain = eval.has("trigger_train")
        val hasTest = eval.has("trigger_test")
        // VC303-P1-7 (EV-6): agent_votes tanpa trigger_* = payload
        // inkonsisten — vote tanpa angka TRAIN/TEST tidak bisa disimpan
        // sebagai benchmark trigger; tolak eksplisit, jangan lolos diam-diam
        // sebagai save judge-only.
        val votesEarlyVc303 = eval.optJSONArray("agent_votes")
        if (!hasTrain && !hasTest && votesEarlyVc303 != null && votesEarlyVc303.length() > 0)
            return "forge_eval_save ditolak: eval memuat agent_votes tanpa trigger_train/trigger_test — payload inkonsisten (vote tanpa angka TRAIN/TEST tidak bisa disimpan sebagai benchmark trigger). Sertakan trigger_train/trigger_test + trigger_query_hash dari hasil forge_trigger_eval, atau hapus agent_votes bila hanya menyimpan judge/A-B/TDD."
        if (!hasTrain && !hasTest) return null
        val votesArr = eval.optJSONArray("agent_votes")
        if (votesArr == null || votesArr.length() == 0)
            return "forge_eval_save ditolak: eval memuat trigger_train/trigger_test tanpa agent_votes — angka trigger adalah klaim agen yang belum tercatat. Jalankan forge_trigger_eval dengan agent_votes (3 vote TRIGGER/TIDAK per query), lalu simpan trigger_train/trigger_test + agent_votes + trigger_query_hash bersama-sama."
        val parsed = ArrayList<TrigVote>()
        for (i in 0 until votesArr.length()) {
            val o = votesArr.optJSONObject(i)
                ?: return "forge_eval_save ditolak: agent_votes[$i] bukan objek {query, should_trigger, votes}."
            val q = o.optString("query").trim()
            if (q.isEmpty()) return "forge_eval_save ditolak: agent_votes[$i] tanpa query."
            if (!o.has("should_trigger")) return "forge_eval_save ditolak: vote untuk query \"$q\" tanpa should_trigger."
            val arr = o.optJSONArray("votes")
            if (arr == null || arr.length() != 3) return "forge_eval_save ditolak: vote untuk query \"$q\" harus tepat 3 suara (dapat ${arr?.length() ?: 0})."
            val vs = ArrayList<Boolean>()
            for (j in 0 until 3) {
                val pv = parseVote(arr.opt(j))
                    ?: return "forge_eval_save ditolak: suara ke-${j + 1} untuk query \"$q\" tidak bisa dibaca (pakai true/false atau \"TRIGGER\"/\"TIDAK\")."
                vs.add(pv)
            }
            parsed.add(TrigVote(q, o.optBoolean("should_trigger", false), vs))
        }
        val dupVotes = parsed.groupingBy { normalizeQuery(it.query) }.eachCount().filter { it.value > 1 }.keys
        if (dupVotes.isNotEmpty())
            return "forge_eval_save ditolak: agent_votes duplikat untuk query: " + dupVotes.joinToString("; ") { "\"$it\"" } + "."
        val nShould = parsed.count { it.should }
        if (parsed.size < 16 || nShould < 8 || parsed.size - nShould < 8)
            return "forge_eval_save ditolak: agent_votes hanya ${parsed.size} query ($nShould should-trigger, ${parsed.size - nShould} should-not-trigger) — minimal 8 + 8 (total >=16) seperti forge_trigger_eval."
        val hashClaim = eval.optString("trigger_query_hash", "")
        if (hashClaim.isBlank())
            return "forge_eval_save ditolak: eval memuat trigger_* tanpa trigger_query_hash — salin hash dari hasil forge_trigger_eval (baris \"Query set hash\")."
        val hashReal = querySetHashFromVotes(parsed)
        if (!hashClaim.equals(hashReal, ignoreCase = true))
            return "forge_eval_save ditolak: trigger_query_hash tidak cocok dengan hitung-ulang dari agent_votes (klaim $hashClaim, hitung-ulang $hashReal) — vote dan hash berasal dari set query yang berbeda."
        val (vt, vs2) = voteAccuracies(parsed)
        if (hasTrain && eval.optInt("trigger_train", -1) != vt)
            return "forge_eval_save ditolak: trigger_train di eval (${eval.optInt("trigger_train", -1)}%) tidak cocok dengan hitung-ulang mayoritas vote ($vt%) — simpan angka dari hasil forge_trigger_eval."
        if (hasTest && eval.optInt("trigger_test", -1) != vs2)
            return "forge_eval_save ditolak: trigger_test di eval (${eval.optInt("trigger_test", -1)}%) tidak cocok dengan hitung-ulang mayoritas vote ($vs2%) — simpan angka dari hasil forge_trigger_eval."
        return null
    }

    /** vc299 N6-m8 — benchmark hangus HANYA bila isi benar-benar berubah.
     *  Penelepon menyusun "isi" dari field yang ia ubah: forgeUpsert
     *  (instruction+descr), updateSkillMeta (name+descr). Simpan-ulang
     *  identik = false (eval dipertahankan). */
    fun evalWipeNeededVc299(oldContent: List<String>, newContent: List<String>): Boolean = oldContent != newContent

    // VC303-P3-21 (EV-9): eval yang masuk lewat restore/impor TIDAK
    // dibuang bila gagal validasi save (restore tidak boleh kehilangan
    // data) — sebagai gantinya eval itu DITANDAI di dalam JSON-nya sendiri
    // (flag + alasan), supaya evalSummary bisa memberi label
    // impor/tidak-tervalidasi. Eval yang lolos validasi (null) atau yang
    // judge-only (tanpa trigger_*, validasi dilewati — lihat EV-6)
    // dikembalikan APA ADANYA tanpa flag. JSON rusak dikembalikan apa adanya.
    // Dipakai Tools.kt (sync-restore) + MemoryDb.kt (impor arsip +
    // restoreSkillVersion). Varian yang dibangun: validasi + label impor
    // (bukan tolak/buang).
    fun markImportEvalVc303(evalJson: String): String {
        if (evalJson.isBlank()) return evalJson
        return try {
            val o = JSONObject(evalJson)
            if (o.optBoolean("import_unvalidated", false)) return evalJson
            val err = validateSavedTriggerEval(o) ?: return evalJson
            o.put("import_unvalidated", true)
            o.put("import_unvalidated_reason", err.take(200))
            o.toString()
        } catch (e: Exception) { evalJson }
    }

    // ---------- Ringkasan benchmark (list_skills / forge_eval_save) ----------
    fun evalSummary(evalJson: String): String {
        if (evalJson.isBlank()) return ""
        return try {
            val o = JSONObject(evalJson)
            val parts = ArrayList<String>()
            // vc301 SF-A10 — pisahkan "terverifikasi" (HANYA judge, yang
            // dihitung-ulang tool) dari "klaim agen" (trigger/A-B/TDD).
            // Vote 3x yang tercatat pun tetap "klaim agen" di tingkat input —
            // kode hanya memverifikasi aritmetika mayoritas + kelengkapan,
            // bukan kejujuran suara — tapi keberadaan catatannya ditampilkan.
            if (o.has("judge_total")) parts.add("judge " + o.optInt("judge_total") + "/120 (" + o.optString("grade", "?") + ") [terverifikasi]")
            if (o.has("ab_with") && o.has("ab_prompts")) parts.add("A/B " + o.optInt("ab_with") + "/" + o.optInt("ab_prompts") + " [klaim agen]")
            if (o.has("tdd")) parts.add("TDD " + o.optString("tdd") + " [klaim agen]")
            if (o.has("trigger_test")) {
                val voteNote = if (o.has("agent_votes")) ", vote 3x tercatat" else ""
                parts.add("trigger TEST " + o.optInt("trigger_test") + "% [klaim agen" + voteNote + "]")
            }
            // VC303-P3-21 (EV-9): label impor/tidak-tervalidasi — eval ini
            // masuk lewat restore/impor dan TIDAK lolos validateSavedTriggerEval;
            // datanya dipertahankan, tapi jangan dibaca sebagai hasil save resmi.
            // EV-8 TIDAK DIUBAH: [terverifikasi] tetap HANYA untuk judge,
            // A/B/TDD/trigger tetap [klaim agen] (baris di atas).
            if (o.optBoolean("import_unvalidated", false)) parts.add("[impor — tidak tervalidasi]")
            if (parts.isEmpty()) " \u00b7 eval tersimpan" else " \u00b7 eval vc293: " + parts.joinToString(" \u00b7 ")
        } catch (e: Exception) { " \u00b7 eval tersimpan" }
    }
}

// ---------- vc303 P1 — pembunuh loop eval-save (EV-1/2/5, LP-1, LP-7) ----------
// Helper + penghitung di file ini (bukan Tools.kt) supaya logika murni bisa
// diuji JVM tanpa Android: SkillJudge.kt hanya bergantung pada org.json +
// java.security. Tools.kt memanggil helper ini dari cabang forge_*.
// Batasan jujur: penghitung EvalLoopGuardVc303 IN-MEMORY per proses app —
// reset bila proses app mati/restart. Itu cukup untuk membunuh loop di
// dalam satu sesi (skenario v11: loop terjadi dalam hitungan detik di
// giliran yang sama), tapi bukan penghitung lintas-restart; bila butuh
// persistensi, pindahkan map-nya ke prefs/MemoryDb tanpa mengubah API.
object EvalLoopGuardVc303 {
    const val MAX_EVAL_ITERATIONS_VC303 = 5
    const val MAX_EVAL_RUNS_PER_SKILL_VC303 = 5
    const val EVAL_RUN_TTL_DAYS_VC303 = 7L

    // LP-1: penolakan trigger_query_hash beruntun, kunci = (sesi, slug) +
    // JENIS penolakan (bukan args penuh).
    private val hashReject = HashMap<String, Pair<String, Int>>()
    fun noteHashRejection(sessionId: Long, slug: String, kind: String): Int {
        val k = sessionId.toString() + "|" + slug
        val cur = hashReject[k]
        val n = if (cur != null && cur.first == kind) cur.second + 1 else 1
        hashReject[k] = Pair(kind, n)
        return n
    }
    fun resetHashRejections(sessionId: Long, slug: String) {
        hashReject.remove(sessionId.toString() + "|" + slug)
    }

    // LP-7: iterasi eval per skill-slug (naik tiap forge_trigger_eval tanpa
    // eval_save sukses; reset saat eval_save sukses / draf ditulis ulang).
    private val evalIter = HashMap<String, Int>()
    fun noteEvalIteration(slug: String): Int {
        val n = (evalIter[slug] ?: 0) + 1
        evalIter[slug] = n
        return n
    }
    fun evalIterationCount(slug: String): Int = evalIter[slug] ?: 0
    fun resetEvalIterations(slug: String) {
        evalIter.remove(slug)
        evalBest.remove(slug)
    }

    // LP-7: snapshot deskripsi + TEST terbaik sejauh ini per skill.
    private val evalBest = HashMap<String, Pair<String, Int>>()
    fun noteEvalBest(slug: String, descrSnapshot: String, test: Int) {
        val cur = evalBest[slug]
        if (cur == null || test > cur.second) evalBest[slug] = Pair(descrSnapshot.take(500), test)
    }
    fun bestEval(slug: String): Pair<String, Int>? = evalBest[slug]

    // LP-7: create_skill yang ditolak D1 per skill per giliran.
    private val d1Reject = HashMap<String, Int>()
    fun noteD1Rejection(slug: String, turnNo: Int): Int {
        val k = slug + "|" + turnNo
        val n = (d1Reject[k] ?: 0) + 1
        d1Reject[k] = n
        return n
    }
}

// VC303-P1-1: hash kanonik isi draf (descr + instruction) — sidik jari run
// eval; run kedaluwarsa bila hash ini berubah (draf diubah sesudah eval).
fun descrHashVc303(descr: String, instruction: String): String {
    return try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((descr.trim() + "\n" + instruction.trim()).toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { (descr.trim() + "\n" + instruction.trim()).hashCode().toString() }
}

// VC303-P1-1/3: hitung (trigger_query_hash, trigger_train, trigger_test)
// dari agent_votes — dipakai Tools.kt untuk (a) menyimpan run sesudah eval
// sukses, (b) menyusun eval terkoreksi pada penolakan hash ke-2 (LP-1).
// Return null bila votes malformed (bukan objek / bukan tepat 3 suara /
// suara tak terbaca) — pemanggil memakai jalur tolak biasa.
fun triggerEvalFromVotesVc303(agentVotes: JSONArray?): Triple<String, Int, Int>? {
    if (agentVotes == null || agentVotes.length() == 0) return null
    return try {
        val pairs = ArrayList<Pair<String, Boolean>>()
        val votes = ArrayList<Triple<String, Boolean, List<Boolean>>>()
        for (i in 0 until agentVotes.length()) {
            val o = agentVotes.optJSONObject(i) ?: return null
            val q = o.optString("query").trim()
            if (q.isEmpty() || !o.has("should_trigger")) return null
            val arr = o.optJSONArray("votes") ?: return null
            if (arr.length() != 3) return null
            val vs = ArrayList<Boolean>()
            for (j in 0 until 3) {
                val pv = when (val v = arr.opt(j)) {
                    is Boolean -> v
                    is String -> when (v.trim().lowercase()) {
                        "trigger", "true" -> true
                        "tidak", "false" -> false
                        else -> return null
                    }
                    else -> return null
                }
                vs.add(pv)
            }
            pairs.add(Pair(q, o.optBoolean("should_trigger", false)))
            votes.add(Triple(q, o.optBoolean("should_trigger", false), vs))
        }
        // Hash kanonik + split ternormalisasi — rumus SAMA dengan
        // SkillJudge.triggerQuerySetHash / voteAccuracies (EV-4): normalisasi
        // lowercase+trim, "query|should" terurut; split sort by hashCode
        // query ternormalisasi.
        val canon = pairs.map { (q, s) -> q.trim().lowercase() + "|" + s }.sorted().joinToString("\n")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hash = md.digest(canon.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val sorted = votes.sortedBy { it.first.trim().lowercase().hashCode() }
        val nTrain = (sorted.size * 60) / 100
        fun acc(rows: List<Triple<String, Boolean, List<Boolean>>>): Int {
            if (rows.isEmpty()) return 0
            var ok = 0
            for (r in rows) if ((r.third.count { it } >= 2) == r.second) ok++
            return ok * 100 / rows.size
        }
        Triple(hash, acc(sorted.subList(0, nTrain)), acc(sorted.subList(nTrain, sorted.size)))
    } catch (e: Exception) { null }
}

// VC303-P1-3 (LP-1): jenis penolakan = prefix pesan sebelum angka/hash —
// kunci stabil untuk counter beruntun (angka klaim vs hitung-ulang selalu
// beda, jadi pesan penuh tidak pernah sama).
fun evalRejectionKindVc303(msg: String): String {
    val beforeParen = msg.substringBefore(" (").trim()
    return beforeParen.replace(Regex("[0-9a-f]{8,}"), "#").replace(Regex("[0-9]+"), "#").trim()
}
