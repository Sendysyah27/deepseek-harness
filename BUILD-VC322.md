# BUILD-VC322 — Fix Bocor Sesi Server DeepSeek (vc322, 4.48.9.156)

Dokumen kerja integrator fix bocor sesi (keputusan Sendy — Gas fix sesi,
slice 7 DI-PAUSE, lanjut lagi sebagai vc323 sesudah fix ini mendarat).
Ini BUKAN slice 7 (rusqlite). Pola dokumen mengikuti BUILD-VC321.md.
Dokumen ukur: `vc317-work/UKURAN-SESI-VC322.md` (probe
`vc317-work/work-sess/probe/`, log `probe-sess.log`).

## §1. Keputusan versi

- **vc322, VERSION_CODE 322, VERSION_NAME 4.48.9.156**, branch `vc317-rust`
  (remote main tetap vc309 — JANGAN disentuh).
- `RUST_CORE_VERSION` TETAP 4, `RustCore.VERSION` tetap 4 — fix ini tidak
  menambah fungsi JNI baru (semua logika baru Kotlin murni).
- ARM64-only. `Flavor.kt` TIDAK disentuh.
- Perubahan perilaku HANYA yang diperlukan fix: giliran 1-1 lanjutan
  dikirim sebagai DELTA (bukan prompt penuh), + penghitung sesi per akun
  + primed-flag. Prompt PENUH hanya untuk wadah baru / sesudah reset /
  pengganti / forceFresh. Sesi pendek/normal TIDAK berubah (giliran
  pertama selalu penuh; WebView selalu penuh).
- Keystore VM lama dipertahankan; gerbang sidik jari vc316 berlaku:
  Main `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`,
  Pure `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`.

## §2. Ukur dulu + desain fix (probe JVM — SEBELUM patch)

Probe `vc317-work/work-sess/probe/ProbeSess.kt` (log `probe-sess.log`),
angka lengkap di `UKURAN-SESI-VC322.md`. Ringkasnya:

### §2.1 Masalah (ukuran Sendy, vc321, Browser 2, akun Eneng Sunarsih)

1 sesi Harness, 4 perintah → 4 SESI BARU di web DeepSeek (1 sesi baru
per giliran). Pola yang dulu bikin akun kena suspend (12 Nov 2026) —
BAHAYA.

### §2.2 Mekanisme (terverifikasi ulang dari kode)

1. Reset proaktif vc321 (DsApiClient.kt:207, SlotClient.kt:219)
   menembak tiap giliran bila akumulasi + prompt berikut >
   DEFAULT_CAP 200.000 char. Prompt sesi panjang "Utama" ~90.500 char
   dan tumbuh — begitu >100.001 char/giliran, tiap giliran = reset =
   sesi server baru (ambang persis terukur probe §4).
2. Sesi lama yang ditinggal TIDAK PERNAH dihapus di server:
   `clearServerSession` (DsApiClient.kt:387) cuma hapus catatan lokal
   di prefs; **TIDAK ADA panggilan hapus sesi ke server di seluruh
   kode** (permukaan API app hanya 5 path `/api/v0`: chat/completion,
   chat_session/create, chat/create_pow_challenge, file/upload_file,
   file/fetch_files — TIDAK ADA chat_session/delete/list/update).
   Tiap reset = satu sesi yatim numpuk permanen di sidebar akun.
3. Preseden TERBUKTI (`ReviewerVc298.kt:13-16`): reviewer lama bikin
   sesi ephemeral baru tiap review → puluhan chat "Review klaim"
   menumpuk → akun kena SUSPEND DeepSeek 12 Nov 2026.
4. Jalur lain yang bisa melahirkan sesi: forceFresh (stream putus 2x
   → sesi pengganti) dan pengganti saat server menolak sesi tersimpan
   (session-invalid). Keduanya dibedakan di dsSessEvents (lihat
   UKURAN-SESI-VC322.md §5).

### §2.3 Angka SEBELUM fix (rezim vc321 — prompt PENUH tiap giliran)

| Skenario | Sesi baru / 4 giliran | Sesi baru / 10 giliran |
|---|---:|---:|
| P=60.526 (model seed vc321) | 2 | 4 |
| P=90.500 (brief) | 2 | 5 |
| **P=110.000 (DB nyata > seed)** | **4 — reproduksi persis ukuran Sendy** | **10** |
| P=90.500 + tumbuh 5k/giliran | 3 | 9 |
| P=150.000 (DB raksasa) | 4 | 10 |
| "Pangkas saja" (riwayat 30k→15k, P=75.500) | — | 5 |

**Memangkas riwayat saja TIDAK menolong** (5 sesi, sama seperti
P=90.500). Tanpa delta, target "1 sesi untuk N giliran normal" tidak
tercapai secara matematis untuk N≥5 (butuh P≤20k, mustahil karena
system prompt saja ~26k+). Delta adalah satu-satunya jalan ke ~1 sesi
per 25+ giliran.

### §2.4 Keputusan fix (dari ukuran, bukan asumsi)

1. **Delta giliran 1-1:** giliran lanjutan dikirim sebagai DELTA
   (jangkar vc316 + perintah user verbatim + work-notes segar,
   tipikal ~1-2k char, terburuk ~8k). Prompt PENUH hanya untuk wadah
   baru / sesudah reset / pengganti / forceFresh. Mekanisme "server
   menyimpan konteks antar POST" sudah terbukti di lanjutan tool +
   delta room vc321 — delta 1-1 memakai mekanisme yang sama.
2. **Primed-flag (baru, krusial):** delta HANYA ke wadah yang sudah
   menerima SATU prompt penuh sukses. Tanpa ini, retry sesudah reset
   + stream putus bisa mengirim delta ke wadah kosong yang belum
   punya konteks. Primed disimpan di prefs "turnMetrics"
   (`srvPrimedVc322_<key>`); di-clear saat create/reset/pengganti,
   di-set true sesudah POST penuh sukses.
3. **Penghitung sesi:** `sessCreatedVc322_<accumKey>` di prefs
   "turnMetrics"; dihitung di tiap situs kelahiran (normal +
   pengganti, kedua klien); hitungan ditampilkan di tiap baris
   kelahiran JEJAK SESI SERVER ("[Sesi server ke-N untuk sesi
   harness ini.]"). Ephemeral TIDAK dihitung (bukan wadah sesi
   harness yang dipakai ulang; saat ini tidak ada call site
   freshEphemeral=true). Yang yatim TIDAK dikurangi (server tidak
   memberi jalan menghapusnya).
4. **Pengecualian:** giliran auto-save vc298 ("ingat:"/koreksi)
   memaksa prompt penuh — blok "[SISTEM — AUTO-SAVE vc298" hanya ada
   di prompt penuh dan wajib dibaca model giliran itu (konfirmasi
   simpanan ke user); delta akan menghilangkannya.
5. **Yang TIDAK dipilih:** (a) endpoint hapus sesi — TIDAK ADA
   (bukti §2.2); (b) menurunkan DEFAULT_CAP — cap per-prompt bukan
   penyebabnya; (c) sesi server baru per POST — sejarah membuktikan
   itu menumpuk ratusan sesi → suspend; (d) memangkas riwayat lebih
   keras — terbukti tidak menolong (§2.3).

### §2.5 Angka SESUDAH fix (desain target DELTA — dikunci P8 §4)

| Skenario | Giliran | Sesi baru |
|---|---:|---:|
| Penuh 90.500, delta ~1k | 10 | **1** |
| Penuh 90.500, delta ~1k | 30 | **1** |
| Penuh 150.000, delta ~1k | 30 | **1** |
| Penuh 90.500, delta ~1k + forceFresh 1x | 10 | 2 |
| Penuh 90.500, delta ~1k + invalid 1x | 10 | 2 |
| Delta terburuk 8k, penuh 90.500 | 10 | 1 |
| Delta terburuk 8k, penuh 90.500 | 30 | 3 |
| Delta terburuk 8k, penuh 150.000 | 30 | 5 |

### §2.6 Trade-off

- **Delta bergantung pada sesi server yang menyimpan prompt penuh
  giliran pertama.** Bila server membuang konteks antar POST (tidak
  ada bukti itu terjadi — lanjutan tool + delta room sudah
  membuktikan sebaliknya sejak vc316/vc321), giliran lanjutan akan
  kehilangan konteks. Mitigasi: primed-flag memastikan delta hanya
  ke wadah yang sudah terbukti menerima penuh; bila POST delta
  gagal/stream putus, retry tetap di sesi yang sama; forceFresh
  mengirim ulang PENUH ke wadah baru.
- **Delta TIDAK membawa digest** (ringkasan giliran lalu sudah ada
  verbatim di memori server). Work-notes dibawa segar karena isinya
  ("jangan ulangi") bisa berubah antar giliran.
- **Governor tetap dihitung dari prompt penuh** (konservatif — kuota
  tidak berubah). Guard reset vc321 + `DEFAULT_CAP` 200.000 +
  riwayat adaptif TIDAK berubah. `parent_message_id` tetap NULL.
- **Sesi yatim lama tidak bisa dihapus** (tidak ada endpoint) —
  yang diperbaiki adalah LAJU kelahiran sesi baru, bukan membersihkan
  yang sudah menumpuk. Sesi yatim di akun Sendy tetap ada sampai
  DeepSeek menyediakan hapus sesi / Sendy menghapus manual di web.

## §3. Basis verifikasi (langkah 0 — semua LULUS sebelum patch)

- (a) Live tree = vc321 murni pasca-restore koordinator dari clone
  otoritatif branch `vc317-rust` HEAD 15b96c1 (doc-sync vc321 §8,
  tanpa slice 7): build.py 321/4.48.9.155, `RUST_CORE_VERSION` 4,
  `RustCore.VERSION` 4, cargo test 271/271.
- (b) Marker slice 7 = 0 di live + branch-stage
  (`SqliteSeam.kt` / `sqlite.rs` / `sqlite_schema.rs` /
  `TestRusqliteVc322.kt` tidak ada); `RUST_CORE_VERSION: jint = 4`
  (`harness-core/src/jni_glue.rs:40`).
- (c) Diff live pkg vs `merge/` = tepat 20 file .kt (18 di pkg +
  `priv/PrivPairNotif.kt` + `harness/core/RustCore.kt`) — ekspektasi
  C vc321. File vc321 ada semua: GroupChat, MainActivity,
  TurnMetrics, DsApiClient, SlotClient.
- (d) Live app == branch-stage app (diff kosong); crate live ==
  branch-stage (diff kosong, excl. target).
- (e) `cargo test --offline`: **271 passed, 0 failed**.
- (f) Probe §2 selesai SEBELUM satu baris kode diubah (bagian
  read-only dikerjakan saat live tree masih berisi patch slice 7 —
  snapshot probe terverifikasi IDENTIK dengan live pasca-restore
  untuk TurnMetrics/AgentPrompt/DsApiClient/SlotClient; slice 7
  tidak menyentuh ke-4 file itu).
- (g) File target dibaca penuh: TurnMetrics.kt, AgentPrompt.kt
  (`deltaTurnPrompt`/`continuationAnchorVc316`/`workNotesBlockVc302`),
  DsApiClient.kt (runChat ~168), SlotClient.kt (runChat ~198),
  MainActivity.kt (`dispatchTurn` ~11768, `sendToAgent` ~11486,
  `sendContinuation` ~11253).

## §4. Isi fix (file yang berubah)

Kotlin saja (live tree + staging + branch-stage) — TIDAK ADA Rust baru,
TIDAK ADA JNI baru, `RUST_CORE_VERSION` tetap 4.

- `TurnMetrics.kt` — helper baru (murni + prefs):
  - `shouldSendDeltaVc322(hasStoredSession, hasDeltaPrompt,
    forceFresh, freshEphemeral, reviewer, primed, accumChars,
    deltaLen): Boolean` (murni; delta hanya bila tersimpan + ada
    delta + bukan forceFresh/ephemeral/reviewer + primed +
    deltaLen>0 + accum+delta ≤ DEFAULT_CAP).
  - `serverSessionCountVc322(ctx, key)`,
    `noteServerSessionCreatedVc322(ctx, key): Int` (mengembalikan
    hitungan baru, jepit 10 juta),
    `isServerSessionPrimedVc322` / `setServerSessionPrimedVc322`
    (key prefix `sessCreatedVc322_` / `srvPrimedVc322_`, prefs
    "turnMetrics").
- `AgentPrompt.kt` — `deltaTurnPromptVc322(userCommand, workNotes):
  String` (murni; `continuationAnchorVc316(userCommand)` +
  "\n\n[USER — TUGAS 1-1 VERBATIM]:\n" + perintah verbatim +
  "\n[/USER]" + work-notes bila ada, dicap 5000 + penanda
  "…(dipotong, sisa N char)…").
- `DsApiClient.kt` — `chat(...)` + `runChat(...)` menerima
  `deltaPrompt: String? = null`; ke-8 situs `runChat` di `chat()`
  meneruskan `deltaPrompt = deltaPrompt` (termasuk forceFresh=true
  — aturan seleksi yang menangani). `runChat`: hitung `accumVc321`
  + `useDeltaVc322` + `sendPromptVc322`; guard reset hanya bila
  `!useDeltaVc322` (kondisi guard + panjang prompt penuh tidak
  berubah); reset juga clear primed; situs create: `setPrimed(false)`
  + `noteServerSessionCreatedVc322` + hitungan di kedua baris log
  kelahiran; POST memakai `sendPromptVc322`; akumulasi menambah
  `sendPromptVc322.length`; bila penuh sukses → `setPrimed(true)`.
  Jalur pengganti: log dipindah SESUDAH `storeServerSession` (bila
  create gagal, tidak ada kelahiran untuk dicatat — sebelumnya
  dicatat optimistis sebelum create); `res2` tetap
  `completeWithSession(fresh, prompt,...)` (PENUH) + `setPrimed(true)`.
- `SlotClient.kt` — cermin DsApiClient: `chat(...)` + `runChat(...)`
  menerima `deltaPrompt`; ke-6 situs `runChat` di `chat()`
  diteruskan (sed hanya pada baris `runChat(` agar `chatSync` tidak
  tersentuh — `chatSync` tetap tanda tangan lama tanpa delta).
  Seleksi delta meneruskan `freshEphemeral` + `reviewer` (keduanya
  mengecualikan delta). Create: counter + primed hanya bila
  `!freshEphemeral`; baris log REVIEWER juga membawa hitungan bila
  tersimpan. Pengganti: sama seperti DsApiClient (log sesudah store
  + counter + primed; `res2` penuh + primed true).
- `MainActivity.kt` — `sendToAgent(prompt, deltaPrompt: String? =
  null)` (komentar vc322; teruskan `deltaPrompt = deltaPrompt` ke
  `slotClient.chat(...)` dan ke lambda `chatFn` → `apiClient.chat(...,
  deltaPrompt = deltaPrompt)`; WebView tetap penuh). `dispatchTurn`:
  bangun `deltaVc322` (null bila `promptNoUser` mengandung
  "[SISTEM — AUTO-SAVE vc298", else
  `AgentPrompt.deltaTurnPromptVc322(rawUser,
  AgentPrompt.workNotesBlockVc302(this, currentSessionId))`);
  `sendToAgent(toSendVc240, deltaVc322)`.
- `build.py` (live + staging): 321/4.48.9.155 → 322/4.48.9.156
  (hanya baris VERSION; isi keduanya memang berbeda, tidak saling
  ditimpa).

### Yang sengaja TIDAK diubah

- `GroupChat.kt` (room — round baru room ~59k → reset ~tiap 3 round
  adalah residual, di luar cakupan; lihat §7.5), `BgBrain.kt`
  (lantai latar ikut guard otomatis lewat DsApiClient; tidak
  didelta-kan), `Governor.kt`, `ToolGate.kt`, `Tools.kt`,
  `DsFailure.kt` (tangga retry utuh), cap langkah 50, `DEFAULT_CAP`
  200.000, `Flavor.kt`, `ReviewerVc298.kt` (reviewer tetap sesi
  tetap + prompt penuh).
- `sendContinuation`, verifikasi, tugas latar, rescue:
  `deltaPrompt` default null → perilaku lama.
- Jalur pengganti (session-invalid): kirim ulang prompt PENUH ke
  sesi baru (bukan delta).
- `parent_message_id` tetap NULL di kedua klien (protokol tidak
  berubah).

## §5. Tes paritas baru — P8 TestSessFixVc322 (73 cek)

File: `branch-stage/tests/TestSessFixVc322.kt` (+
`FakeCtxVc322.java`, `FakePrefsVc322.java`,
`tests/stubs/android/content/Context.java` — stub Context
non-lempar, lihat §7.8; juga dipakai runner lokal; CI: step P8 di
`android.yml` + `run_p8` di `run-suite-ci.sh`).

- §1 builder delta (runtime): jangkar 503/492/988 char; delta memuat
  jangkar + perintah verbatim; <1200 char perintah pendek (557 char);
  tanpa [RIWAYAT]/SKILL AKTIF bila work-notes kosong; work-notes
  >5000 dipotong + penanda — rumus panjang eksak.
- §2 aturan kirim delta murni (11 kasus): normal true; tanpa
  wadah/tanpa delta/forceFresh/ephemeral/reviewer/belum primed →
  false; accum+delta>cap → false; ==cap persis → true; accum 0 →
  true; delta 0 → false.
- §3 counter + primed via `FakeCtxVc322` (hitungan 0→1→3, kunci
  independen, primed set/clear, akumulasi vc321 tetap jalan + reset
  tidak mengubah hitungan).
- §4 simulasi sesi per N giliran memakai fungsi ASLI tree
  (`shouldResetServerSessionVc321` + `shouldSendDeltaVc322`) +
  model primed: B 4 giliran → 2 sesi; C reproduksi Sendy → 4 sesi;
  ambang 100.000→2 / 100.001→4; H pangkas saja 10 giliran → 5 sesi;
  B 10 giliran → 5; I1 delta 10 giliran → 1 sesi 0 reset; I2 30
  giliran → 1; I4 P=150.000 30 giliran → 1; J2 delta-8k 30 giliran
  → 3; I5 +forceFresh → 2; I6 +invalid → 2; giliran pertama selalu
  penuh; 9 delta/1 penuh per 10 giliran; rezim delta 2 giliran
  P=150.000 → tetap 1 sesi, primed, accum=150.000+deltaLen; sesudah
  invalid + POST penuh sukses → pengganti primed; guard IXL accum
  190.000+penuh 60.526 → reset; +delta → tidak reset.
- §5 struktur source (18 cek): fungsi baru ada;
  dispatchTurn→sendToAgent(delta); kedua klien terima deltaPrompt;
  POST delta + akumulasi panjang terkirim; pengganti penuh;
  ephemeral/reviewer dikecualikan; guard+cap tidak berubah; counter
  di 4 situs lahir; JEJAK memuat "Sesi server ke-"; tanpa hsurr: di
  5 file; Flavor tanpa vc322; GroupChat+BgBrain tanpa vc322;
  `RustCore.VERSION == 4`.
- §6 sesi pendek/jalur lain tidak berubah: riwayat adaptif tetap;
  sendContinuation tetap kirim promptnya sendiri; chatSync tanpa
  delta; parent_message_id tetap NULL; forceFresh tetap diteruskan
  di kedua klien.

IXL + P7 diperbarui (preseden 7.1 slice 6 / 7.5-7.6 vc321):
`TestInputLimitVc316.kt` 7.5/7.6 dan `TestRoomIxlFixVc321.kt`
5.3/5.4/5.5 tidak lagi menuntut string lama
`addServerAccumVc321(ctx, accumKeyVc321, prompt.length)` dan
`"!freshEphemeral && stored != null"` — fix ini memang mengubah
kedua baris itu (akumulasi kini menghitung panjang prompt YANG
DIKIRIM `sendPromptVc322.length`; guard SlotClient kini
`!freshEphemeral && !useDeltaVc322 && stored != null`). Yang
dikunci adalah semantik baru yang sama maknanya (protokol + guard
untuk prompt penuh TIDAK berubah). IXL 72/72, P7 52/52 sesudah
perbaikan.

P2/P3/P4/P5/P6 diperbarui: ekspektasi usang `RustCore.VERSION == 5`
warisan pekerja slice 7 (versi yang diparkir) dikembalikan ke `== 4`
di 8 file tes (work-s2/s3/s4 + branch-stage/tests, termasuk P6 7.3
"capabilities version 5"). Bukan perubahan perilaku — kode di bawah
tes memang VERSION 4.

## §6. Angka tes

- **cargo test: 271/271 lulus, 0 failed** (tidak ada Rust baru —
  sama seperti vc321; dijalankan terpisah sebelum suite + di langkah
  0a runner).
- **Suite lokal penuh: 1200/1201** — satu-satunya gagal R2-4.1
  (assert STRING usang warisan vc315, DITOLERANSI seperti slice
  sebelumnya; bukan regresi).
  - P1 103/103, P2 36/36, P3 94/94, P4 235/235, N9 46/46,
    BR 45/45, IXL 72/72 (sesudah perbaikan 7.5/7.6 §5), R1 64/64,
    R2 68/69 (4.1), P5 157/157 (kotlin 55 + rust 102),
    P6 155/155 (kotlin 75 + rust 80),
    P7 52/52 (sesudah perbaikan 5.3/5.4/5.5 §5),
    **P8 73/73 (baru vc322)**.
  - C LULUS: tepat 20 file .kt berubah vs baseline vc316 + 0 baru
    (15 slice 2–6 + 5 vc321 — file fix vc322 sudah termasuk di 5
    file vc321 itu; TIDAK ADA file .kt baru vs baseline).
  - D1–D13 LULUS (D13 baru: fix sesi vc322 + fallback slice 7
    TIDAK ADA + RUST_CORE_VERSION tetap 4 + Flavor.kt tidak
    berubah).
  - Log ronde 2: `vc317-work/work-impl/run-tests-vc322-coord2.log`
    (16 Nov 09:47 WIB) + P8 standalone
    `vc317-work/work-impl/run-p8-standalone.log` (73/73, lihat
    §7.7–§7.8 soal kenapa P8 dipisah).
  - Total = 1128 (vc321) + 73 (P8) = 1201 cek; 1200 lulus.
- Ronde 1 (log `run-tests-vc322-coord.log`): FAIL=9 — 4× ekspektasi
  VERSION 5 usang (P2/P3/P4/P6), 2× IXL 7.5/7.6 usang, 2× P7
  5.4/5.5 usang, 1× P8 tidak ter-compile (§4.7–4.9, bug penulis tes
  — trailing lambda, lihat §7.3), 1× R2-4.1 (ditoleransi). Semua
  diperbaiki di ronde 2 (maks 2 ronde — ronde 2 adalah re-run penuh
  terakhir; P8 §3–§6 divalidasi standalone, lihat §7.7).

## §7. Deviasi / temuan jujur

1. **Basis awal brief SALAH — drift slice 7 (koreksi koordinator,
   diakui sebagai kesalahannya).** Brief mengklaim live tree vc321
   bersih; verifikasi saya menemukan patch slice 7 (rusqlite) sudah
   tertulis di live tree (EventDb, ArtifactStore,
   ParkedApprovalStoreVc305, GraphDb, RustCore VERSION 5 +
   sqliteJson, SqliteSeam.kt baru, sqlite.rs + sqlite_schema.rs,
   RUST_CORE_VERSION 5). Koordinator memarkir patch slice 7 ke
   `vc317-work/work-s7/snapshot-patch/` (+ MANIFEST.md, 16 file +
   md5sum) dan me-restore live + branch-stage ke vc321 murni dari
   clone otoritatif branch `vc317-rust` HEAD 15b96c1. Saya verifikasi
   ulang basis (§3) sebelum patch. `work-s7/` TIDAK disentuh.
2. **Drift tambahan yang TIDAK disebutkan koordinator (ditemukan +
   ditangani):** `vc317-work/work-impl/integrated/` (test tree)
   MASIH berisi slice 7 (`SqliteSeam.kt`, `RustCore.VERSION = 5`,
   4 wrapper berbeda). Saya sinkron ulang integrated dari live tree
   (copy 4 wrapper + RustCore.kt, hapus `SqliteSeam.kt`); sesudahnya
   `diff integrated vs live` kosong kecuali `build.py`/`harness`
   ekstra (bukan tree .kt). Alasan: suite harus menguji kode
   live-tree, bukan slice 7 yang sudah diparkir.
3. **P8 — bug penulis tes ronde 1 (diperbaiki).** `simDelta(10)
   { 90500 }`: trailing lambda Kotlin mengikat parameter TERAKHIR
   (`invAt: Set<Int>`), bukan `full` — 3 call site diganti ke
   `simDelta(10, { 90500 })`. Cek 1.11 awalnya tautologis → diganti
   rumus panjang eksak; cek 4.15 awalnya salah memodelkan (mengira
   giliran ke-2 P=150.000 me-reset — di rezim delta tidak reset) →
   diganti assert 1 sesi + primed + accum eksak; ditambah 4.15b.
4. **Tes usang warisan slice 7 (diperbaiki, bukan regresi).**
   8 file tes menuntut `RustCore.VERSION == 5` / "capabilities
   version 5" — ekspektasi pekerja slice 7 atas tree yang sudah
   diparkir. Dikembalikan ke `== 4` (P2/P3/P4/P6). IXL 7.5/7.6 +
   P7 5.3/5.4/5.5 menuntut string lama yang memang diubah fix ini
   (lihat §5). Semua diperbaiki di file TES, bukan di kode produk.
5. **Residual: room round baru.** Round baru room ~59k char → guard
   reset ~tiap 3 round. Fix ini tidak menyentuh GroupChat (di luar
   cakupan brief — lane 1-1). Bila room panjang juga bocor sesi di
   perangkat, tindak lanjutnya adalah delta round-pertama room /
   pemangkasan katalog room, bukan mematikan guard.
6. **BgBrain + reviewer di luar cakupan.** Keduanya punya sesi
   server sendiri dan TIDAK didelta-kan (sengaja). BgBrain jarang
   bergiliran panjang; reviewer per-review memang 1 POST penuh ke
   sesi tetapnya (bukan ephemeral lagi sejak vc298).
7. **P8 §3–§6 divalidasi standalone (bukan di run ronde 2).**
   Ronde 2 penuh: semua section lulus kecuali R2-4.1 (toleransi) —
   TAPI P8 hanya jalan §1–§2 (22/22) lalu crash
   `NoClassDefFoundError: FakeCtxVc322` di §3 (lihat §7.8). P8
   lengkap 73/73 divalidasi lewat run standalone
   (`run-p8-standalone.log`, exit 0) memakai jar + tree yang SAMA
   dengan ronde 2. Runner sudah diperbaiki supaya run penuh
   berikutnya menjalankan P8 lengkap; angka §6 menggabungkan
   ronde-2 (1128 section lain) + P8 standalone (73) = 1201 cek.
8. **Dua jebakan harness tes JVM (temuan baru, didokumentasikan
   untuk integrator berikut):**
   (a) **kotlinc TIDAK meng-compile file .java** yang dilewatkan di
   command line — hanya dipakai sebagai stub resolusi simbol.
   FakeCtx/FakePrefs harus di-compile `javac` terpisah (runner P8
   kini melakukannya; CI `run_p8` juga).
   (b) **Konstruktor `android.content.Context` di android.jar
   melempar `RuntimeException("Stub!")`** — subclass Context apa pun
   (FakeCtx) tidak bisa di-instantiate di JVM. Solusi: stub
   `tests/stubs/android/content/Context.java` (konstruktor kosong +
   103 metode default null/0/false/"", digenerate dari FakeCtx),
   di-compile javac terpisah dan DIDAHULUKAN di classpath run
   (sebelum android.jar). Hanya untuk tes — TIDAK PERNAH masuk APK.
9. **`deltaLen` aktual ≠ estimasi probe.** Probe memakai delta
   konservatif 1.003 char (anchor 503 + blok user 500); runtime
   aktual P8 = 557–558 char (anchor 503 + blok user ~54 char).
   Hasil simulasi tidak berubah (ambang jauh), tapi angka probe
   adalah batas ATAS yang konservatif.
10. **Undercount akumulasi saat retry stream-cut (warisan vc321).**
    Retry yang gagal tidak menambah akumulasi, tapi server mungkin
    sudah menyimpan POST pertama bila stream putus sesudah server
    sukses. Efeknya kecil dan sudah ada sebelum fix ini; tidak
    diperbaiki di sini (di luar cakupan).
11. **P perangkat tidak terukur dari VM** (lihat UKURAN-SESI-VC322
    §6). Ambang 100.001 char adalah hasil simulasi, bukan
    pengukuran perangkat. Ground truth adalah JEJAK SESI SERVER di
    perangkat + hitungan sesi baru di web DeepSeek (TES-VC322).
12. **CI ronde 1 GAGAL hanya di P8 5.18 (jalur RustCore salah
    hitung).** Di layout CI, tree = `app/src/main/java/com/senzwh/
    deepharness` — RustCore ada 3 tingkat di atasnya (deepharness
    → senzwh → com → java), bukan 1 tingkat seperti asumsi awal
    saya. Perbaikan pertama (naik 2 tingkat) juga salah — baru naik
    3 tingkat yang benar. Diverifikasi lokal di KEDUA layout
    (integrated + live app dir) 73/73 sebelum re-push; CI ronde 2
    SUCCESS dengan P8 73/73 di CI. Pelajaran: hitung kedalaman path
    dari struktur direktori nyata (probe `ProbePath.java`), jangan
    dari ingatan.

## §8. Push + CI + APK

- **Pra-cek drift (sebelum push):** remote HEAD 15b96c1 terverifikasi
  (cocok basis restore koordinator); selisih remote tree vs staging
  tepat 10 berubah + 6 baru, 0 remote-only: 5 file fix + build.py +
  TestInputLimitVc316.kt + TestRoomIxlFixVc321.kt + run-suite-ci.sh +
  android.yml (berubah) + BUILD-VC322.md + TES-VC322-SIAP-TEMPEL.txt
  + TestSessFixVc322.kt + FakeCtxVc322.java + FakePrefsVc322.java +
  tests/stubs/android/content/Context.java (baru).
  - Temuan pra-cek: staging `BUILD-VC321.md` masih versi pra-doc-sync
    (§8 placeholder "(BELUM...)") — restore koordinator memakai versi
    lama. Dipulihkan dari remote (isi §8 vc321) SEBELUM push supaya
    push-tree tidak me-regresikannya.
- **Push 1:** 16 Nov 2026 ~10:04 WIB, branch `vc317-rust`, commit
  `26f6cad61d3c4cdb3eebb83a38190d699e3883ec`, 472 file via `gh.py
  push-tree`. Pesan: "vc322 (4.48.9.156): fix bocor sesi server
  DeepSeek — delta 1-1 + penghitung sesi + primed-flag (P8 73 cek,
  suite 1200/1201)".
- **Verifikasi pasca-push 1:** remote HEAD = 26f6cad6; remote tree 472
  blob == staging 472 file, 0 beda; 16/16 file delta SHA cocok
  (termasuk BUILD-VC322.md + TES-VC322-SIAP-TEMPEL.txt yang ikut
  ter-push). Main tidak disentuh (tetap 2e6245e3 = vc309).
- **CI ronde 1:** run [#43845524586](https://github.com/Sendysyah27/deepseek-harness/actions/runs/43845524586)
  (push, head 26f6cad6) — **FAILURE**, job `build`. Log (trik 302):
  satu-satunya gagal = **P8 5.18** ("RUST_CORE_VERSION tetap 4") —
  jalur RustCore di layout CI salah (naik 1 tingkat; parent CI =
  `.../com/senzwh`). Semua section lain lulus (P1–P7, dst. — workflow
  berhenti di step P8 sehingga N9/BR/IXL/R1/R2/C/D/APK tidak jalan).
  Akar: hitungan kedalaman direktori dari ingatan, bukan dari struktur
  nyata. Perbaikan: coba naik 0/1/2/3 tingkat (file
  `tests/TestSessFixVc322.kt` saja); perbaikan pertama (naik 2) juga
  salah — baru naik 3 yang benar (deepharness → senzwh → com → java).
  Diverifikasi lokal di KEDUA layout (integrated + live app dir)
  73/73 sebelum re-push.
- **Push 2 (fix CI):** 16 Nov 2026 ~10:20 WIB, branch `vc317-rust`,
  commit `9cc48f1f1ec2fb3c10a75efa4d893fe8ab77903f`, 472 file via `gh.py
  push-tree` (hanya `tests/TestSessFixVc322.kt` berubah vs push 1;
  SHA remote 136c23faac851c8f2026e52ffa5bc7102299658d cocok staging).
  Pesan: "vc322 (4.48.9.156): fix P8 5.18 — RustCore naik 3 tingkat
  di layout CI (CI ronde 1 gagal di 5.18 saja)".
- **CI ronde 2:** run [#43847898379](https://github.com/Sendysyah27/deepseek-harness/actions/runs/43847898379)
  (push, head 9cc48f1f, dibuat 10:20:07Z) — **SUCCESS**, job `build`
  completed/success. Suite CI: P1 103, P2 36, P3 94, P4 235, P5 157,
  P6 155, P7 52, **P8 73/73**, N9 46, BR 45, IXL 72, R1 64, R2 68/69
  (4.1 ditoleransi), C LULUS, D1–D13 LULUS (D13 di CI). Artifact
  `harness-vc317-apks` (nama belum diganti dari vc320) 26.409.419 byte.
- **APK CI** (`vc317-work/apk-vc322-ci/`): zip MD5
  b20deb0d00dad797a596f5819afe7914.
  - Main `DeepSeekHarness-v4.48.9.156.apk` 13.319.276 B, MD5
    cec7b2287ea70ce61e4ac64f030ab45c, SHA256
    c00986896e902ce4aa25181f64b5a930e6b8020a4ee2670d8d29898ca1665a4f;
    v2+v3 true, 1 signer (kunci runner
    2a38b765b226630e7fc3a32a6276c7eff88e42d625b5cd4c4f6f76759a534786 —
    beda dari VM, EKSPEKTASI).
  - Pure `Harness-v4.48.9.156-pure.apk` 13.274.220 B, MD5
    7ffe36ec64217cae03c64d97ef4e712b, SHA256
    f928f04828fd2b6b36dc35017410f3277d61927264347a0e246ff8dfcc49485c;
    v2+v3 true, 1 signer (kunci runner
    c733f11108cce378157022d1fdcc3a88e6531e9120569c124476ff61ed7b9fd7).
  - Keduanya 322/4.48.9.156, libharness_core.so di lib/arm64-v8a
    (1.854.424 B), hsurr: 0, hanya arm64.
- **APK VM** (`vc317-work/apk-vc322-vm/`, build `python3 -u build.py`
  senz lalu pure; log `/tmp/build-vc322-senz.log`,
  `/tmp/build-vc322-pure.log`):
  - Main `DeepSeekHarness-v4.48.9.156.apk` 13.401.196 B, MD5
    ffcf2b238614ec1d8615cd7f3b0c264b, SHA256
    99d2d9b4a6e85bcde3b2d2fa6eb02c9990a2e6b7b47fc405ba894f7195d88b18;
    v2+v3 true, 1 signer, **sidik jari
    e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177 —
    COCOK gerbang vc316** (bisa menimpa).
  - Pure `Harness-v4.48.9.156-pure.apk` 13.356.140 B, MD5
    1e5a0aeb941ad31e260fcb7003920939, SHA256
    36e07643a5888fabb6d1a0c4590dbd7b53cdd7db29c038b0ed8025e90a45b043;
    v2+v3 true, 1 signer, **sidik jari
    f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f —
    COCOK gerbang vc316** (bisa menimpa).
  - Keduanya 322/4.48.9.156, libharness_core.so di lib/arm64-v8a
    (2.119.656 B), hsurr: 0, hanya arm64.
- **Doc-sync:** BUILD-VC322.md diperbarui (§7.12 + §8 ini) via `gh.py
  put-file` sesudah CI/APK (commit terpisah, isi §7.12+§8 saja yang
  berubah vs versi ter-push).

## §9. Tes perangkat (TES-VC322-SIAP-TEMPEL)

Lihat `vc317-work/TES-VC322-SIAP-TEMPEL.txt` — T-baru: N perintah di
1 sesi Harness ("Utama") → hitung sesi baru di web DeepSeek; target:
tetap 1 sesi (sebelum fix: 4 sesi baru untuk 4 perintah). Sertakan
cara melihat jumlah sesi di web + apa yang dihitung sebagai
LULUS/GAGAL + cara membaca JEJAK SESI SERVER di Profil (penghitung
"Sesi server ke-N" + jalur penyebab reset/forceFresh/invalid).
