package com.senzwh.deepharness

/** vc302 Fix D — Verify-before-write (Worker C).
 *
 *  Gerbang deterministik: sebelum tool agen `create_skill` menimpa skill
 *  yang sudah ada, agen WAJIB sudah membaca live state skill itu di
 *  giliran yang SAMA (bukti: `export_skill` / `list_skills` /
 *  `read_skill_version` yang dieksekusi di giliran itu). Tanpa bukti itu
 *  tool MENOLAK menulis — tidak ada snapshot, tidak ada perubahan DB.
 *
 *  "Giliran yang sama" dilacak lewat epoch = `TurnMetrics.currentTurnNo()`
 *  yang diteruskan pemanggil (lihat RENCANA-C-VERIFYSTUCK.md §2.5):
 *  `beginTurn` hanya dipanggil di jalur 1-1 (`MainActivity.kt:3898`),
 *  jadi di lane 1-1 gate eksak; di lane room/latar/tim `turnNo` membeku
 *  sehingga bacaan tetap berlaku sampai giliran 1-1 berikut dimulai —
 *  gate tetap memaksa urutan baca-dulu-tulis, dengan epoch yang lebih
 *  kasar. Keterbatasan ini disengaja dan didokumentasikan, bukan
 *  disembunyikan.
 *
 *  Objek ini PURE (tanpa import Android, tanpa `TurnMetrics` langsung —
 *  `turnNo` diteruskan sebagai parameter) supaya bisa diuji di JVM.
 *  Satu-satunya pemakai produksi: cabang `create_skill` + 3 handler baca
 *  di `Tools.kt`. `forgeUpsert` sendiri TIDAK digate — jalur
 *  restore/backup/import/sync memanggilnya langsung dan tidak boleh
 *  kena gate ini.
 *
 *  Definisi IDENTIK untuk Fix E TIDAK di sini — lihat kunci LOOP di
 *  `AgentPrompt.StuckWatch.observe` / `MainActivity.stuckSuffix`
 *  (tool + hash args penuh ternormalisasi `normalizeArgsVc277`).
 */
object LiveReadGateVc302 {

    // Bukti baca yang diakui (sesuai brief): export_skill (isi live),
    // list_skills (katalog — berlaku untuk semua slug), read_skill_version
    // (segmen isi versi tersimpan). TIDAK dihitung: export_skill_version
    // (arsip file) dan list_skill_versions (hanya nomor versi).
    private var epoch = -1
    private val readSlugs = HashSet<String>()
    private var listSeen = false

    private fun normSlug(slug: String): String = slug.lowercase().trim()

    private fun syncEpoch(turnNo: Int) {
        if (turnNo != epoch) {
            epoch = turnNo
            readSlugs.clear()
            listSeen = false
        }
    }

    /** Catat: live state satu skill berhasil dibaca di giliran `turnNo`. */
    fun noteRead(slug: String, turnNo: Int) {
        if (slug.isBlank()) return
        syncEpoch(turnNo)
        readSlugs.add(normSlug(slug))
    }

    /** Catat: katalog `list_skills` berhasil dibaca di giliran `turnNo`
     *  (berlaku untuk semua slug — katalog memuat seluruh live state
     *  nama+deskripsi). */
    fun noteListRead(turnNo: Int) {
        syncEpoch(turnNo)
        listSeen = true
    }

    /** Bolehkan overwrite skill `slug`? Hanya bila ada bukti baca live
     *  untuk slug itu (atau katalog) di giliran `turnNo` yang SAMA. */
    fun mayOverwrite(slug: String, turnNo: Int): Boolean {
        syncEpoch(turnNo)
        return listSeen || readSlugs.contains(normSlug(slug))
    }

    /** Hanya untuk tes JVM — mengosongkan jejak. */
    fun resetForTest() {
        epoch = -1
        readSlugs.clear()
        listSeen = false
    }
}
