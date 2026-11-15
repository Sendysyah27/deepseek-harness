package com.senzwh.deepharness

/**
 * vc298 BAGIAN 3 — Auto-save deterministik "ingat:" + koreksi -> lesson.
 *
 * Kotlin MURNI (tanpa Android, tanpa DB, tanpa jaringan) — bisa unit-test
 * standalone seperti MemoryLoopVc294 / ReviewerVc298. Fungsi di sini
 * HANYA mendeteksi + memetakan; eksekusi simpan dilakukan pemanggil
 * (MainActivity.onUserMessage untuk 1-1, Tools.execute untuk guard
 * panggilan-ulang agen).
 *
 * ALASAN ARSITEKTUR (pilihan iii — kombinasi, deterministik dulu):
 * Prompt saja TERBUKTI tidak cukup. vc294 Tes 1/2/7 (tes perangkat
 * 11 Nov 2026):
 *  - (4a) agen MENERAPKAN koreksi sesaat ("jangan kirim laporan sebagai
 *    video, kirim sebagai dokumen...") tapi TIDAK menyimpan lesson —
 *    padahal PROSE_MEMORY_DISCIPLINE_VC294 poin (3) SUDAH memerintah
 *    "bila user mengoreksi caramu, simpan ... via remember_lesson".
 *  - (4b) simpan lesson IDENTIK kedua kalinya TIDAK menaikkan confidence
 *    (tetap 0.70; agen menjawab "udah kesimpan, nggak perlu dobel") —
 *    padahal saveLessonVc294 + pesan DIPERKUAT di Tools.kt SUDAH benar
 *    (dedup +0.1 cap 0.95). Yang rusak BUKAN aritmetikanya, melainkan
 *    agen TIDAK MEMANGGIL tool kedua kalinya.
 *  - (5) "ingat: tes regresi vc294 jam 22:43" dan "ingat: ... kedua,
 *    jam 22:47, status jalan" TIDAK tersimpan (recall_facts tetap 23),
 *    padahal remember_fact JSON langsung jalan (#51, total 24) — model
 *    tidak memetakan pola "ingat:" ke tool, karena TIDAK ADA satu pun
 *    kalimat di prompt/deskripsi tool yang menyebut pemicu "ingat:".
 * Jadi perbaikan utamanya DETERMINISTIK (hook di jalur pemrosesan pesan
 * mengeksekusi simpan langsung) + penguatan prompt sebagai lapis kedua
 * (deskripsi tool + prose disiplin + trailer katalog diperbaiki supaya
 * agen juga memetakan pola ini bila ia bertindak sendiri). Tanpa hook,
 * ketiga kegagalan di atas akan terulang apa pun isi promptnya.
 *
 * ATURAN DETEKSI (sengaja konservatif — anti-spam lesson):
 *  - "ingat:" HANYA bila pesan DIAWALI "ingat:" / "tolong ingat:" (boleh
 *    kapital, boleh spasi sebelum ":"). "ingat" tanpa ":" (mis. "masih
 *    ingat ...?") BUKAN pemicu — itu pertanyaan, bukan perintah simpan.
 *  - Koreksi HANYA pola eksplisit: "simpan lesson: <teks>", "koreksi:
 *    <teks>", atau SATU kalimat berisi "jangan ..." / "bukan ... , ..."
 *    / "harusnya/seharusnya ..." / penanda waktu ("mulai sekarang",
 *    "ke depannya", "lain kali", "seterusnya") + kata kerja aksi agen
 *    (kirim, pakai, buat, ...) atau "kamu"/akhiran "-mu". Kalimat
 *    tanya ("...?") TIDAK PERNAH jadi lesson. "jangan lupa" /
 *    "jangan sampai" dikecualikan (pengingat umum, bukan koreksi cara
 *    kerja agen). Pesan biasa tanpa pola ini -> null -> prompt TIDAK
 *    berubah sama sekali.
 *  - Lesson = kalimat koreksinya sendiri (apa adanya, maks 500 char) —
 *    dedup eksak via MemoryLoopVc294.lessonKey (lowercase + ciutkan
 *    spasi), sama seperti jalur tool, jadi koreksi yang sama persis
 *    memperkuat, bukan duplikat.
 *  - Key fakta "ingat:" = slug teks fakta (lowercase, non-alnum -> "-",
 *    maks 80 char, cermin batas putFact) dan value = teks fakta lengkap
 *    apa adanya. Dua kalimat tes yang berbeda -> key berbeda -> dua
 *    baris inserted (total +2); kalimat yang sama persis -> key sama +
 *    value sama -> putFact "duplicate" (total tidak naik — benar).
 *    Sufiks ", tag <tag>" / ", ref <path>" (format Tes 3 vc294)
 *    dipisah ke tags/ref_path dan dibuang dari value.
 *
 * GUARD PANGGILAN-ULANG (one-shot, 10 menit): hook menyimpan lesson
 * lalu menandai kuncinya (markAutoSavedLesson). Bila agen di giliran
 * YANG SAMA tetap memanggil remember_lesson untuk teks yang sama,
 * Tools.execute memakai consumeAutoSavedLesson untuk melaporkannya
 * sebagai "sudah tersimpan giliran ini" TANPA memanggil saveLesson lagi
 * — mencegah +0.1 kedua dari SATU koreksi user. Satu kunci hanya bisa
 * dikonsumsi SEKALI; sesudah 10 menit mark kedaluwarsa (pembersihan
 * oportunistik di mark). Trade-off yang dicatat: bila agen di giliran
 * LATER (<=10 menit) menyimpulkan sendiri lesson yang sama TANPA hook
 * yang menyala, satu penguatan sah bisa tertelan — kegagalan ini hanya
 * melewatkan +0.1, bukan merusak data.
 */
object LessonIngatVc298 {

    data class IngatVc298(val raw: String, val key: String, val value: String, val tags: String, val refPath: String)
    data class AutoSaveVc298(val ingat: IngatVc298?, val lessonText: String?)

    /** Kata kerja aksi agen — koreksi harus menyebut salah satunya (atau
     *  "kamu"/akhiran "-mu") supaya kalimat "jangan ..." tentang user
     *  sendiri (mis. "jangan makan pedas") tidak jadi lesson. */
    private val AGENT_VERBS = setOf(
        "kirim", "kirimkan", "pakai", "gunakan", "buat", "buatkan", "bikin",
        "simpan", "simpankan", "tulis", "tuliskan", "baca", "bacakan",
        "hapus", "hapuskan", "ubah", "ganti", "gantikan", "tambah",
        "tambahkan", "tampilkan", "jawab", "balas", "catat", "catatkan",
        "sebut", "panggil", "sapa", "format", "render", "potong", "gabung",
        "pisah", "urutkan", "susun", "rapikan", "cek", "cekkan", "cari",
        "carikan", "hitung", "hitungkan", "jadwalkan", "ingatkan", "buka",
        "bukakan", "tutup", "jalankan", "jalanin", "matikan", "nyalakan",
        "ketik", "unduh", "download", "unggah", "upload", "edit", "sunting",
        "salin", "pindah", "pindahkan", "atur", "setel", "pilih"
    )

    private val TIME_MARKERS = listOf("mulai sekarang", "ke depannya", "kedepannya", "lain kali", "seterusnya", "mulai hari ini", "mulai besok")

    private val INGAT_RE = Regex("^\\s*(tolong\\s+)?ingat\\s*:\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SIMPAN_LESSON_RE = Regex("^\\s*simpan\\s+lesson\\s*:\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val KOREKSI_RE = Regex("^\\s*koreksi\\s*:\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG_SEG_RE = Regex(",\\s*tag\\s+([^,]+)", RegexOption.IGNORE_CASE)
    private val REF_SEG_RE = Regex(",\\s*ref\\s+([^,]+)", RegexOption.IGNORE_CASE)

    /** Satu pesan user -> apa yang harus di-auto-save (null = tidak ada
     *  pemicu; pemanggil TIDAK BOLEH mengubah prompt). "ingat:" diperiksa
     *  dulu: pesan yang diawali "ingat:" tidak pernah dipindai sebagai
     *  koreksi. */
    fun processUserMessage(text: String): AutoSaveVc298? {
        val ingat = parseIngat(text)
        if (ingat != null) return AutoSaveVc298(ingat, null)
        val lesson = detectCorrection(text)
        if (lesson != null) return AutoSaveVc298(null, lesson)
        return null
    }

    /** "ingat: <fakta>" / "tolong ingat: <fakta>" di AWAL pesan -> pemetaan
     *  ke remember_fact (key + value + tags/ref_path opsional). null bila
     *  bukan pemicu / fakta kosong / terlalu pendek (< 3 char). */
    fun parseIngat(text: String): IngatVc298? {
        val m = INGAT_RE.find(text) ?: return null
        var raw = m.groupValues[2].substringBefore("\n").trim()
        if (raw.length < 3) return null
        var tags = ""
        var refPath = ""
        val tagM = TAG_SEG_RE.find(raw)
        if (tagM != null) tags = tagM.groupValues[1].trim()
        val refM = REF_SEG_RE.find(raw)
        if (refM != null) refPath = refM.groupValues[1].trim()
        if (tagM != null) raw = raw.replace(TAG_SEG_RE, "")
        if (refM != null) raw = raw.replace(REF_SEG_RE, "")
        // Titik akhir kalimat dibuang: "status jalan." dan "status jalan"
        // adalah fakta yang sama — tanpa ini pengulangan dengan/tanpa
        // titik jadi superseded, bukan duplicate.
        raw = raw.trim().trimEnd(',', ' ', '.').trim()
        if (raw.length < 3) return null
        val value = raw.take(500)
        return IngatVc298(raw, factKeyFor(value), value, tags.take(200), refPath.take(200))
    }

    /** Key fakta dari teks: slug lowercase, maks 80 char. Kosong (teks
     *  tanpa huruf/angka latin) -> "ingatan-<hex hash>" deterministik. */
    fun factKeyFor(value: String): String {
        var slug = value.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        if (slug.length > 80) slug = slug.take(80).trim('-')
        if (slug.isBlank()) slug = "ingatan-" + value.hashCode().toUInt().toString(16)
        return slug
    }

    /** "simpan lesson: <teks>" eksplisit di AWAL pesan -> teks lesson.
     *  null bila bukan pemicu / teks kosong / terlalu pendek. */
    fun parseSimpanLesson(text: String): String? {
        val m = SIMPAN_LESSON_RE.find(text) ?: return null
        val t = m.groupValues[1].substringBefore("\n").trim().trimEnd('.').trim()
        if (t.length < 5) return null
        return t.take(500)
    }

    /** Deteksi koreksi eksplisit -> teks lesson (kalimat koreksinya), atau
     *  null. Urutan: "simpan lesson:" / "koreksi:" dulu, lalu pindai
     *  kalimat demi kalimat. */
    fun detectCorrection(text: String): String? {
        parseSimpanLesson(text)?.let { return it }
        val k = KOREKSI_RE.find(text)
        if (k != null) {
            val t = k.groupValues[1].substringBefore("\n").trim().trimEnd('.').trim()
            if (t.length >= 5) return t.take(500)
        }
        for (sentence in splitSentences(text)) {
            val lesson = correctionSentenceToLesson(sentence) ?: continue
            return lesson
        }
        return null
    }

    /** Pecah pesan jadi kalimat (baris baru atau [.!?] + spasi). Kalimat
     *  dipertahankan apa adanya (tanpa titik akhir) untuk teks lesson. */
    fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        for (chunk in text.split("\n")) {
            val parts = chunk.split(Regex("(?<=[.!?])\\s+"))
            for (p in parts) {
                val s = p.trim()
                if (s.isNotEmpty()) out.add(s)
            }
        }
        return out
    }

    /** Satu kalimat -> teks lesson, atau null bila bukan koreksi eksplisit.
     *  Aturan (lihat header): kalimat tanya tidak pernah; "jangan lupa" /
     *  "jangan sampai" bukan koreksi; pola "jangan"/"bukan"/"harusnya"/
     *  penanda waktu + (kata kerja aksi agen atau "kamu"/"-mu"). */
    fun correctionSentenceToLesson(sentence: String): String? {
        val t = sentence.trim().trimEnd('.', ' ').trim()
        if (t.length < 10 || t.length > 500) return null
        if (sentence.trim().endsWith("?")) return null
        val low = t.lowercase()
        val words = low.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        val hasAgentRef = AGENT_VERBS.any { it in words } ||
            "kamu" in words || words.any { it.length > 2 && it.endsWith("mu") }
        if (!hasAgentRef) return null
        if (low.contains("jangan lupa") || low.contains("jangan sampai")) return null
        val hasJangan = Regex("\\bjangan\\b").containsMatchIn(low)
        val hasBukan = Regex("\\bbukan\\b").containsMatchIn(low) &&
            (low.contains(",") || low.contains("tapi") || low.contains("melainkan"))
        val hasHarusnya = low.contains("harusnya") || low.contains("seharusnya")
        val hasTimeMarker = TIME_MARKERS.any { low.contains(it) }
        if (!hasJangan && !hasBukan && !hasHarusnya && !hasTimeMarker) return null
        return t
    }

    // ---------- Guard panggilan-ulang (one-shot, 10 menit) ----------
    private val autoSavedLessons = HashMap<String, Long>()
    private const val AUTO_SAVE_GUARD_MS = 10L * 60L * 1000L

    @Synchronized
    fun markAutoSavedLesson(lessonText: String) {
        val now = System.currentTimeMillis()
        val it = autoSavedLessons.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > AUTO_SAVE_GUARD_MS) it.remove()
        }
        autoSavedLessons[MemoryLoopVc294.lessonKey(lessonText)] = now
    }

    /** true (sekali saja) bila lesson ini baru saja di-auto-save hook
     *  giliran ini — pemanggil (Tools remember_lesson) WAJIB melaporkan
     *  "sudah tersimpan" TANPA memanggil saveLesson lagi. */
    @Synchronized
    fun consumeAutoSavedLesson(lessonText: String): Boolean {
        val k = MemoryLoopVc294.lessonKey(lessonText)
        val t = autoSavedLessons[k] ?: return false
        if (System.currentTimeMillis() - t > AUTO_SAVE_GUARD_MS) {
            autoSavedLessons.remove(k)
            return false
        }
        autoSavedLessons.remove(k)
        return true
    }
}
