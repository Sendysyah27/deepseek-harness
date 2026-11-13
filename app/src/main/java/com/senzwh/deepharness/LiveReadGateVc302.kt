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
 *
 *  vc304 B3 (get_result sebagai bukti baca — jebakan verify-before-write
 *  vs StuckDetector pada skill besar, laporan user 04:57 13 Nov 2026):
 *  `get_result` atas R-id dari live read yang memenuhi syarat DIHITUNG
 *  sebagai bukti baca — HANYA bila R-id itu dibuat di giliran yang SAMA
 *  (epoch `TurnMetrics` sama) dan tool asalnya memang qualifying read
 *  (`export_skill` / `list_skills` / `read_skill_version`). R-id lintas
 *  giliran (bisa basi — skill sudah berubah) dan R-id dari tool
 *  non-qualifying tetap DITOLAK. Bukti R-id dicatat di `ResultStore`
 *  (field `turn` + `slug` per entri, ditulis di titik perekaman hasil
 *  `Tools.execute`); gate ini sendiri TIDAK menyimpan peta R-id —
 *  `noteRidReadVc304` di bawah adalah satu-satunya titik masuk murni
 *  yang dipakai handler `get_result` di `Tools.kt`. `grep_result`
 *  TIDAK dihitung (hanya mengembalikan cuplikan cocok, bukan isi live).
 */
object LiveReadGateVc302 {

    // Bukti baca yang diakui (sesuai brief): export_skill (isi live),
    // list_skills (katalog — berlaku untuk semua slug), read_skill_version
    // (segmen isi versi tersimpan). TIDAK dihitung: export_skill_version
    // (arsip file) dan list_skill_versions (hanya nomor versi).
    private var epoch = -1
    private val readSlugs = HashSet<String>()
    private var listSeen = false

    /** vc304 B3: tool yang baca-nya boleh dibuktikan ulang lewat R-id
     *  `get_result` — SAMA dengan daftar bukti baca di atas. Tool lain
     *  (`judge_skill`, `read_file`, `export_skill_version`,
     *  `list_skill_versions`, `create_skill`, …) TIDAK PERNAH menjadi
     *  bukti R-id, walau R-id-nya dibuat di giliran yang sama. */
    val QUALIFYING_RID_TOOLS_VC304: Set<String> =
        setOf("export_skill", "list_skills", "read_skill_version")

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

    /** vc304 B3 — proksi sukses untuk hook simpan `Tools.execute`:
     *  "apakah handler BARU SAJA mencatat bukti baca untuk `slug` ini
     *  di giliran `turnNo`?" `slug == null` = bukti katalog
     *  (`list_skills`). TIDAK memanggil `syncEpoch` bila epoch berbeda
     *  (kuesioner murni — tidak boleh menghapus jejak sebagai efek
     *  samping). Dipakai hook simpan untuk memutuskan apakah R-id yang
     *  baru dibuat layak dicatat beserta epoch + slug di `ResultStore`:
     *  hanya bila handler sudah membuktikan sukses baca — tanpa menebak
     *  dari teks hasil. Konsekuensi yang disengaja (fail-closed): bukti
     *  R-id tidak pernah MELAMPAUI bukti langsung — ia hanya membuat
     *  bukti itu bisa diturunkan ulang lewat `get_result` tanpa
     *  mengulang live read identik. */
    fun hasProofForVc304(slug: String?, turnNo: Int): Boolean {
        if (turnNo != epoch) return false
        return if (slug == null) listSeen
        else listSeen || readSlugs.contains(normSlug(slug))
    }

    /** vc304 B3 — terima `get_result` atas R-id sebagai bukti baca.
     *  Satu-satunya titik masuk murni; dipakai handler `get_result` di
     *  `Tools.kt` SETELAH `ResultStore.get` sukses mengembalikan satu
     *  potongan tersimpan (`tool` = tool asal, `ridTurn` = epoch saat
     *  R-id dibuat, `slug` = slug yang dicatat di titik perekaman).
     *  Mengembalikan true + menerapkan `noteRead`/`noteListRead` HANYA
     *  bila SEMUA syarat terpenuhi:
     *  (1) `tool` qualifying (`QUALIFYING_RID_TOOLS_VC304`);
     *  (2) `ridTurn >= 0` (entri lama tanpa epoch TIDAK dihitung);
     *  (3) `ridTurn == turnNo` (R-id segiliran — lintas giliran bisa
     *  basi, skill sudah berubah);
     *  (4) untuk tool per-skill, `slug` tidak kosong (untuk
     *  `list_skills`, slug diabaikan — katalog berlaku untuk semua).
     *  Penolakan = false TANPA efek samping (tidak ada pencatatan,
     *  tidak ada penghapusan epoch). `grep_result` tidak pernah sampai
     *  ke sini (handler-nya tidak memanggil fungsi ini). */
    fun noteRidReadVc304(tool: String, ridTurn: Int, slug: String, turnNo: Int): Boolean {
        if (tool !in QUALIFYING_RID_TOOLS_VC304) return false
        if (ridTurn < 0 || ridTurn != turnNo) return false
        if (tool == "list_skills") {
            noteListRead(turnNo)
            return true
        }
        if (slug.isBlank()) return false
        noteRead(slug, turnNo)
        return true
    }

    /** Hanya untuk tes JVM — mengosongkan jejak. */
    fun resetForTest() {
        epoch = -1
        readSlugs.clear()
        listSeen = false
    }
}
