package com.senzwh.deepharness

/**
 * vc294 — Loop memori gbrain: fungsi MURNI (tanpa Android, tanpa DB),
 * bisa unit-test standalone seperti SkillJudge vc293.
 *
 * Yang diadopsi POLA + wording + workflow-nya, diterjemahkan ke tool
 * Harness — bukan impor mentah:
 * - agentmemory `remember`: 2-5 concept tag SPESIFIK (lowercase,
 *   spesifik > generik, mis. jwt-refresh-rotation > auth) + file path.
 * - agentmemory `lesson`: koreksi/aturan hasil perjuangan sebagai
 *   lesson BERBOBOT confidence (mis. 0.7); koreksi berulang yang sama =
 *   MEMPERKUAT, bukan duplikat; hapus lesson via jalur terpisah.
 * - agentmemory `recall`: hasil ringkas dulu, expand yang dipilih
 *   (expandIds); "kosong yang terpotong != tidak ada" + "jangan
 *   fabrikasi skor" sebagai aturan claim-check jawaban berbasis memori.
 * - swarmvault: dashboard contradiction + graph blast (reverse-impact)
 *   untuk dampak supersession; review queue candidate/promote/archive.
 * - git-context-controller: riwayat bernomor [C004] + log + metadata
 *   sebagai struktur panel riwayat fakta (dipakai factBlast).
 *
 * CATATAN KEJUJURAN SUMBER: SKILL.md agentmemory TIDAK ADA di clone
 * lokal (~/workspace/study/claude-code-templates/... hanya source.json
 * pin-commit 0da38f1d); pola di atas dipakai dari ringkasan terverifikasi
 * TEMUAN-SKILL-UNTUK-HARNESS.md Tier 1 no. 1. swarmvault +
 * git-context-controller SKILL.md dibaca langsung.
 */
object MemoryLoopVc294 {

    data class ConflictVc294(val kind: String, val otherKey: String, val detail: String)

    // Tag generik yang TIDAK membedakan apa pun — dibuang saat normalisasi
    // (bukan ditolak mentah: tag lain yang valid tetap disimpan).
    private val GENERIC_TAGS = setOf(
        "auth", "data", "info", "umum", "general", "hal", "cara", "tips",
        "app", "user", "kerja", "sistem", "halaman", "bagian", "lainnya"
    )

    private val NEG_WORDS = listOf("tidak", "bukan", "jangan", "dilarang", "belum", "batal", "larang")

    /** Normalisasi tag: lowercase, spasi -> -, hanya [a-z0-9-], panjang
     *  >=3, buang generik, unik, maks 5 (spesifik > generik). */
    fun normalizeTags(raw: String): List<String> {
        val out = ArrayList<String>()
        for (part in raw.split(",", ";")) {
            var t = part.trim().lowercase()
            t = t.replace(Regex("[^a-z0-9\\s\\-_/]"), "")
            t = t.trim().replace(Regex("\\s+"), "-").replace("_", "-").replace("/", "-")
            t = t.trim('-')
            if (t.length < 3) continue
            if (t in GENERIC_TAGS) continue
            if (t !in out) out.add(t)
        }
        return out.take(5)
    }

    fun tagsCanonical(raw: String): String = normalizeTags(raw).joinToString(",")

    /** Kunci dedup lesson: teks dinormalisasi (lowercase, spasi diciutkan,
     *  titik akhir kalimat dibuang — vc298: "jangan X." dan "jangan X"
     *  adalah lesson yang sama; tanpa ini pengulangan dengan/tanpa titik
     *  jadi duplikat, bukan penguatan). */
    fun lessonKey(text: String): String {
        return text.trim().trimEnd('.', ' ').trim().lowercase().replace(Regex("\\s+"), " ")
    }

    /** Penguatan lesson: koreksi berulang yang sama = confidence naik
     *  +0.1, cap 0.95 — BUKAN duplikat baris baru. */
    fun strengthenConfidence(conf: Double): Double {
        // Dibulatkan 2 desimal: 0.7+0.1+0.1 tanpa ini = 0.8999999999999999.
        val n = kotlin.math.round((conf + 0.1) * 100.0) / 100.0
        return if (n > 0.95) 0.95 else n
    }

    /** vc299 N6-m5 — deteksi negasi yang TAHAN tanda baca akhir kata:
     *  nilai berakhiran "tidak." / "bukan," / "belum:" tetap terdeteksi
     *  (tanda baca . , : ; ! ? di ujung kata diabaikan sebelum pencocokan
     *  batas kata). Satu pola, dipakai detectConflicts di sini +
     *  scanDreamFlagsVc289 di MemoryDb (akar N5-m10 yang sama). */
    fun hasNegation(s: String): Boolean {
        val cleaned = s.replace(Regex("[.,:;!?]+"), " ")
        return NEG_WORDS.any { (" " + cleaned + " ").contains(" " + it + " ") }
    }

    /** Deteksi konflik fakta baru vs fakta aktif (cerminan aturan
     *  scanDreamFlagsVc289, dibatasi pasangan yang melibatkan fakta baru).
     *  "duplikat": key berbeda, nilai sama persis. "konflik": satu key
     *  hierarki dari yang lain + nilai berbeda + tepat satu mengandung
     *  negasi. HANYA flag kemungkinan — bukan verdict; user yang
     *  memutuskan lewat remember_fact / forget_fact. */
    fun detectConflicts(newKey: String, newValue: String, active: List<Pair<String, String>>): List<ConflictVc294> {
        val nk = newKey.trim().lowercase()
        val nv = newValue.trim().lowercase()
        if (nk.isBlank() || nv.isBlank()) return emptyList()
        val hasNeg = { s: String -> hasNegation(s) }
        val seps = listOf(".", "/", " ", "_", "-")
        val out = ArrayList<ConflictVc294>()
        for ((k, v) in active) {
            val ok = k.trim().lowercase()
            val ov = v.trim().lowercase()
            if (ok.isBlank() || ok == nk) continue
            if (ov.length >= 3 && ov == nv) {
                out.add(ConflictVc294("duplikat", ok, "nilai sama: '" + v.take(80) + "'"))
                continue
            }
            val hier = seps.any { ok.startsWith(nk + it) || nk.startsWith(ok + it) }
            if (hier && ov != nv && hasNeg(ov) != hasNeg(nv)) {
                out.add(ConflictVc294("konflik", ok, "nilai bertentangan (satu mengandung negasi): '" + v.take(60) + "' vs '" + newValue.take(60) + "'"))
            }
        }
        return out.sortedWith(compareBy({ it.kind }, { it.otherKey })).take(10)
    }

    /** vc299 N6-m1 — laporan hasil promote_fact yang JUJUR (fungsi murni,
     *  dipakai handler promote_fact di Tools.kt). Kandidat yang sudah
     *  diputuskan (promoted/archived) dilaporkan apa adanya TANPA tag
     *  "[STATUS: ok]" — tidak ada yang dipromosikan. Jalur promote yang
     *  sah (status putFact: inserted/duplicate/superseded) tetap membawa
     *  tag sukses + info penggantian nilai + konflik seperti semula. */
    fun promoteResultTextVc299(id: Long, status: String, oldValue: String, conflicts: List<String>): String {
        if (status == "promoted") return "Kandidat #$id sudah dipromosikan sebelumnya [promoted] — tidak ada yang dipromosikan (tidak ada perubahan)."
        if (status == "archived") return "Kandidat #$id sudah diputuskan (diarsipkan/ditolak) [archived] — tidak ada yang dipromosikan ke store aktif."
        return "[STATUS: ok] Kandidat #$id DIPROMOSIKAN ke store aktif [$status]" + (if (oldValue.isNotBlank()) " — menggantikan nilai lama '" + oldValue.take(120) + "'" else "") + (if (conflicts.isNotEmpty()) " — KONFLIK terdeteksi vs " + conflicts.joinToString(", ") + " (lihat dream_flags / Setup 65b)" else "") + "."
    }
}
