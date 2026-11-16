# BUILD-VC323 — Slice 7: SQLite via `rusqlite` (vc323, 4.48.9.157)

Dokumen kerja integrator slice 7 (keputusan Sendy 13:28 — fix room-lane
dikerjakan dulu sebagai vc321; slice 7 rusqlite direncanakan vc322,
DI-PAUSE 16 Nov 16:05 untuk fix bocor sesi vc322, lanjut sebagai vc323).
Pola dokumen mengikuti BUILD-VC321.md.

## §1. Keputusan versi

- **vc323, VERSION_CODE 323, VERSION_NAME 4.48.9.157**, branch `vc317-rust`
  (remote main tetap vc309 — JANGAN disentuh).
- `RUST_CORE_VERSION` 4→**5**, `RustCore.VERSION` 4→**5** — slice ini
  menambah SATU fungsi JNI baru (`sqliteJson`).
- ARM64-only. `Flavor.kt` TIDAK disentuh.
- Porting MURNI: skema + SQL + migrasi 1:1 (SQL yang sama persis);
  quirk DIREPLIKASI (dikunci tes), bukan diperbaiki.
- Keystore VM lama dipertahankan; gerbang sidik jari vc316 berlaku:
  Main `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`,
  Pure `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`.

## §2. Desain seam + inventaris skema (diukur dulu, sebelum patch)

### §2.1 Keputusan desain seam

Diputuskan SETELAH membaca ke-5 file target penuh (bukan asumsi):

- **Satu modul Rust generik `sqlite` + satu modul `sqlite_schema`**,
  BUKAN satu modul per DB. Alasan: ke-5 DB memakai pola akses yang
  sama (open file → exec/query/tx ber-parameter); yang berbeda hanya
  DDL skema + langkah migrasi. Satu engine generik + tabel skema per
  DB = satu tempat untuk membuktikan rusqlite sekali (EventDb) lalu
  dipakai ulang 4 DB lain tanpa menduplikasi mesin. Per-DB yang
  spesifik (mis. backfill graph v2→v3, rebuild facts vc285) hidup
  sebagai fungsi kecil di `sqlite_schema.rs`, bukan modul sendiri.
- **Seam: SATU fungsi JNI `sqliteJson(request: String): String`**,
  JSON in/out (pola slice 5/6). Request:
  `{"op":"ensure"|"query"|"exec"|"tx","db":"<path absolut .db>","key":"events"|"artifacts"|"parked"|"graph"|"memory",...}`.
  Respons `{"ok":true,...}` atau `{"ok":false,"error":"..."}`.
  Alasan: satu boundary JNI (satu `catch_unwind`) untuk semua operasi
  DB; SQL tetap ditulis di sisi Kotlin (string yang sama persis dengan
  Kotlin lama) sehingga porting tiap metode = ganti primitif Cursor
  dengan panggilan seam, bukan tulis ulang logika. Nilai baris:
  Integer→JSON number, Real→JSON number, Text→string, Blob→
  `{"$b64":"..."}` (base64), NULL→null. Parameter: number/string/
  null/bool(→1/0)/`{"$b64":...}`.
- **Path `.db` dilewat sebagai String dari Kotlin** (lewat
  `ctx.getDatabasePath(nama).absolutePath`); Rust TIDAK PERNAH
  menerima `Context`.
- **Koneksi dibuka-tutup per operasi** (bukan satu koneksi persisten).
  Alasan: disiplin writer yang ada adalah SATU writer dalam satu
  waktu (`@Synchronized` di hampir semua metode tulis + singleton
  per DB); koneksi per panggilan + file lock SQLite + `busy_timeout`
  5000 ms + `PRAGMA journal_mode=WAL` (diset tiap open; persisten di
  file) menjaga disiplin itu antar proses (MainActivity +
  HarnessService + BgBrain bisa bersamaan; SlotBrowser TIDAK menyentuh
  `.db`). Tanpa state Rust jangka panjang → tanpa registry handle,
  tanpa `close()` yang bisa lupa (peta §3.3: jangan pointer mentah).
- **`ensure` mereplikasi `SQLiteOpenHelper`**: baca
  `PRAGMA user_version`; 0 → jalankan DDL `onCreate` (ketat: gagal =
  error); < target → jalankan langkah `onUpgrade` + langkah lazy
  `onOpen` (longgar per-pernyataan, seperti `try{}` Kotlin), lalu
  set `user_version` = target. Wrapper Kotlin memanggil `ensure`
  sekali per instance (flag volatile); bila Rust tidak tersedia,
  `SQLiteOpenHelper` lama yang bekerja seperti biasa (fallback).
- **Fallback + saklar per DB**: tiap wrapper menyimpan
  `var rust<Event|Artifact|Parked|Graph> = RustCore.available`
  (default ikut `available`); public fun mencoba seam Rust dulu,
  bila `null`/error → matikan saklar + jalankan implementasi Kotlin
  lama (`*Kotlin`, privat, body ASLI yang diganti nama). API publik
  TETAP PERSIS (nama + parameter + nilai kembali + perilaku
  try/catch yang menelan error).
- **Kelas wrapper TETAP `extends SQLiteOpenHelper`** (fallback butuh
  `onCreate`/`onUpgrade` asli + pemanggil luar masih memakai
  `.readableDatabase`/`.writableDatabase` langsung: MainActivity:987/
  1030 + FloatingAgentActivity:378/387 + RedactMigration:79-108 atas
  MemoryDb; TriageDigest:82 atas EventDb — TIDAK diubah).
- **Dependensi baru**: `rusqlite 0.32` fitur `bundled` (kompilasi
  amalgamasi SQLite C; FTS5 ikut teruji — lihat §2.3) + `base64
  0.22` (encoding BLOB). `Cargo.lock` di-merge kunci (nama, versi)
  seperti pelajaran slice 3.
- `#![forbid(unsafe_code)]` di `sqlite.rs` + `sqlite_schema.rs`;
  TANPA `unwrap()`/`expect()` di jalur input luar (request JSON,
  path, SQL, parameter dari Kotlin).

### §2.2 Inventaris skema (dari baca penuh file target)

| DB | File | Versi | Tabel | onCreate / onUpgrade |
|---|---|---|---|---|
| events | `EventDb.kt` 98 baris | v1 | `events` + `idx_events_ts` | onCreate: 1 tabel + 1 indeks. onUpgrade: kosong. |
| artifacts | `ArtifactStore.kt` 271 baris | v1 | `artifacts`, `artifact_versions` + `idx_artifacts_owner` | onCreate: 2 tabel + 1 indeks. onUpgrade: `onCreate(db)` ulang. |
| parked | `ParkedApprovalStoreVc305.kt` 395 baris | v1 | `parked_approvals` + `idx_parked_status`, `idx_parked_notif` | onCreate: 1 tabel + 2 indeks. onUpgrade: kosong. |
| graph | `GraphDb.kt` 1003 baris | v3 | `nodes`, `edges`, `episodes`, FTS5 `fts_fact` + 4 indeks | onCreate: 3 tabel + 4 indeks + FTS5 (try{}). onUpgrade: v1→v2 ADD tag/confidence + UPDATE tag; v2→v3 ADD 7 kolom edge + `aliases_json` + episodes + FTS5 + **backfill** fact_text/recorded_at/valid_at per edge dari label ujung + insert fts_fact; lalu `onCreate(db)`. |
| memory | `MemoryDb.kt` 3202 baris | v7 | `facts`, `history`, `reminders` + v2 schedules/rules/skills/geofences/finance + v3 sessions (+kolom `session` di history + seed `sessions(1,'Utama')`) + v4 embeddings (BLOB) + v5 goals/goal_steps + v6 oc_tasks/coord_rules + v7 workers (`WorkerRegistry.CREATE_SQL`) + lazy: deleted_archive, fact_edges, dream_flags, lessons, fact_candidates, joblog, group_msgs, skill_versions, skill_export_ledger, eval_runs + indeks perf | onCreate: facts/history/reminders + createV2..V7 + createPerfIndexes. onUpgrade: bertahap per versi (V2..V7). onOpen (TIAP buka): ensureFactProvenanceVc285 (rebuild facts lama `k TEXT PRIMARY KEY` → `facts_vc285` PK id, transaksional, idempotent via PRAGMA table_info) + ensureVc294Schema + fact_edges/dream_flags + createPerfIndexes + ensureWave2Columns + ensureWorkerTable. Plus lazy per-metode (ensureOcDirColumn, joblog, group_msgs, skill_versions, eval_runs, kolom history.work, …). |

### §2.3 Syarat keras peta §2.3 — bukti

1. **Satu writer**: `@Synchronized` dipertahankan di semua metode
   tulis wrapper (disiplin in-process sama); antar proses:
   SQLite file lock + `busy_timeout(5000)` + `journal_mode=WAL`
   di tiap koneksi Rust. Bukti: tes konkurensi P8 §5 (dua writer
   bergantian via seam, 0 hilang/korup) + tes Rust `concurrent_writers`.
2. **Migrasi diuji pada SALINAN `.db` produksi**: tidak ada `.db`
   produksi di VM. Metode: fixture `.db` realistis dibangun dengan
   skema Kotlin LAMA (v6 memory / v2 graph / v1 lainnya — DDL
   disalin verbatim dari sumber Kotlin) + baris representatif,
   lalu migrasi Rust dijalankan dan hasilnya dibandingkan dengan
   migrasi Kotlin — perbandingan dilakukan sebagai **identitas
   string SQL** (tes P8 §6: tiap pernyataan DDL Kotlin di sumber
   `.kt` harus ada verbatim di `sqlite_schema.rs`) + **hasil
   akhir** (skema + isi sesudah migrasi Rust sama dengan yang
   dijanjikan komentar/migrasi Kotlin: baris utuh, kolom baru ada,
   `user_version` = target). Migrasi Kotlin sendiri tidak bisa
   dijalankan di JVM (kelas `android.database.sqlite` di
   `android.jar` adalah stub yang melempar) — keterbatasan ini
   dicatat jujur, bukan ditutupi.
3. **ContentValues → binding rusqlite**: mekanis — tiap `put`
   menjadi parameter `?` + `params` JSON di seam (bukti: tiap
   metode tulis wrapper memakai `SqliteSeam.exec/tx`, tanpa
   `ContentValues` di jalur Rust).

### §2.4 Keputusan cakupan — MemoryDb

MemoryDb (3202 baris, ~195 fun, 242 situs SQL, migrasi lazy
berlapis vc285/vc289/vc294 + lazy per-metode + BLOB embedding +
`onOpen` tiap buka) TIDAK diport ke wrapper Kotlin dalam ronde
ini. Yang DIKERJAKAN untuk MemoryDb di vc323: DDL + migrasi
v6→v7 + lazy `onOpen` direplikasi di `sqlite_schema.rs`
(kunci `memory`) dan dibuktikan tes Rust + P8 atas fixture v6
— jadi fondasi port wrapper-nya sudah teruji; port 195 metode
menjadi pekerjaan mekanis lanjutan (satu rilis berikut), bukan
riset ulang. Wrapper `MemoryDb.kt` TIDAK berubah di vc323
(selain nol — file tidak masuk diff C). Keputusan ini sesuai
brief koordinator (mendaratkan EventDb→GraphDb sebagai vc323 +
laporkan MemoryDb sebagai sisa adalah hasil jujur yang diterima).

## §3. Basis verifikasi (langkah 0 — semua LULUS sebelum patch)

- (a) Live 5 file target vs `vc317-work/merge/` IDENTIK (asli vc316):
  EventDb, ArtifactStore, ParkedApprovalStoreVc305, GraphDb,
  MemoryDb — semua `diff -q` IDENTIK.
- (b) `diff -rq --exclude=target harness-core/
  vc317-work/branch-stage/harness-core/` IDENTIK (exit 0).
- (c) Live tree = vc321 pasca-fix (build.py 321/4.48.9.155,
  `RUST_CORE_VERSION` 4, 24 simbol JNI, suite 1127/1128 hanya
  R2-4.1, cargo 271/271 — klaim koordinator, diverifikasi ulang
  di §6/§7).
- (d) Ke-5 file dibaca penuh; skema/migrasi dicatat di §2.2.

## §4. Isi patch

Rust (crate `harness-core`, `RUST_CORE_VERSION` 4→5):

- `src/sqlite.rs` (BARU, `#![forbid(unsafe_code)]`): engine generik —
  `sqlite_json(request)`; op `ensure`/`query`/`exec`/`tx`; koneksi
  dibuka-tutup per operasi; `busy_timeout(5000)` + `PRAGMA
  journal_mode=WAL` tiap open; nilai baris Integer/Real/Text/Blob
  (`{"$b64":...}`)/NULL; parameter number/string/null/bool(→1/0)/
  `{"$b64":...}`; TANPA `unwrap()`/`expect()` di jalur input luar.
- `src/sqlite_schema.rs` (BARU, `#![forbid(unsafe_code)]`): DDL +
  migrasi per kunci `events`/`artifacts`/`parked`/`graph`/`memory`
  (identitas string SQL dengan sumber Kotlin — dikunci P9 §6).
- `src/lib.rs`: +`pub mod sqlite;` +`pub mod sqlite_schema;`.
- `src/jni_glue.rs`: `RUST_CORE_VERSION` 4→5; capabilities +`"sqliteJson"`;
  JNI `Java_harness_core_RustCore_sqliteJson` (satu `catch_unwind`).
- `Cargo.toml`: +`rusqlite = { version = "0.32", features = ["bundled"] }`
  +`base64 = "0.22"`. `Cargo.lock`: merge kunci (nama, versi) —
  rusqlite 0.32.1, libsqlite3-sys 0.30.1, base64 0.22.1, 62 paket,
  0 duplikat nama+versi.

Kotlin (5 file + 1 baru; porting murni, API publik TETAP PERSIS):

- `EventDb.kt` — ditulis ulang (seam Rust + 7 `*Kotlin` privat + saklar
  `rustEventDb`).
- `ArtifactStore.kt` — 8 metode → `*Kotlin` + metode Rust baru + saklar
  `rustArtifactStore`.
- `ParkedApprovalStoreVc305.kt` — 6 metode → `*Kotlin` + metode Rust
  baru + saklar `rustParkedApproval`.
- `GraphDb.kt` — 29 metode leaf → `*Kotlin` + metode Rust baru + saklar
  `rustGraphDb`.
- `RustCore.kt` — `VERSION` 4→5 + `external fun sqliteJson(request:
  String): String?`.
- `SqliteSeam.kt` (BARU) — helper seam; memakai `java.util.Base64`
  (bukan `android.util.Base64` — stub JVM, pelajaran ronde 1).
- `MemoryDb.kt` TIDAK berubah (keputusan §2.4). `Flavor.kt` TIDAK
  disentuh. Fix vc322 (delta 1-1 + counter/primed) DIPERTAHANKAN
  utuh — file fix (DsApiClient, SlotClient, MainActivity, TurnMetrics,
  AgentPrompt + TestSessFixVc322.kt) TIDAK disentuh slice 7;
  rebase di atas vc322 TANPA konflik (disjoint, dibuktikan diff
  snapshot-vs-live: hanya delta slice 7).

## §5. Tes paritas baru — P9 TestRusqliteVc323

File: `branch-stage/tests/TestRusqliteVc323.kt` (134 cek: rust 107 +
kotlin 27). Dinamai P9 di vc323 — P8 sudah dipakai fix sesi vc322
(sebelumnya P8 di runner slice 7 saat pause; rename vc322→vc323 +
P8→P9 dicatat di file tes itu sendiri).

- §1 seam + VERSION: `RustCore.VERSION == 5`, `sqliteJson` ada,
  request/response JSON, error → `{"ok":false,...}`.
- §2 `ensure` per DB (events v1, artifacts v1, parked v1, graph v3,
  memory v7) + `user_version` = target.
- §3 CRUD per DB (insert/query/update/delete, parameter binding).
- §4 tx atomik (commit + rollback).
- §5 konkurensi dua writer bergantian via seam (0 hilang/korup) +
  tes Rust `concurrent_writers`.
- §6 identitas SQL/DDL: tiap pernyataan DDL Kotlin di sumber `.kt`
  ada verbatim di `sqlite_schema.rs`; migrasi fixture graph v2→v3 +
  memory v6→v7 (skema + isi akhir sama dengan janji migrasi Kotlin;
  migrasi Kotlin sendiri tidak bisa jalan di JVM — kelas
  `android.database.sqlite` di `android.jar` adalah stub yang
  melempar; dicatat jujur, bukan ditutupi).
- §7 claim atomik/decide set-once H-K1 (ParkedApproval).
- §8 FTS5 (`fts_fact` graph) + BLOB (embedding memory).
- §9 fallback SqliteSeam tanpa `.so` (mode kotlin).
- §10 `java.util.Base64` (bukan android — crash stub JVM ronde 1).
- §11 struktur sumber: 4 wrapper `*Kotlin` + 4 saklar rust +
  `sqliteJson` + VERSION 5 + MemoryDb tanpa Rust.
- §12 `RUST_CORE_VERSION` 5 di `jni_glue.rs` + simbol JNI baru.

CATATAN: wrapper TIDAK diinstansiasi di JVM (Context stub) — P9
menguji seam + identitas sumber; wrapper end-to-end di perangkat
(TES-VC323).

## §6. Angka tes

- **cargo test: 287/287 lulus, 0 failed** (271 lama + 16 baru slice 7;
  dijalankan terpisah sebelum suite + di langkah 0a runner).
- **Suite lokal penuh ronde 2: 1334/1335** — satu-satunya gagal R2-4.1
  (assert STRING usang warisan vc315, DITOLERANSI seperti slice
  sebelumnya; bukan regresi).
  - P1 103/103, P2 36/36, P3 94/94, P4 235/235, N9 46/46,
    BR 45/45, IXL 72/72, R1 64/64, R2 68/69 (4.1),
    P5 157/157 (kotlin 55 + rust 102),
    P6 155/155 (kotlin 75 + rust 80),
    P7 52/52, **P8 73/73 (fix sesi vc322, in-suite)**,
    **P9 134/134 (baru vc323: rust 107 + kotlin 27)**.
  - C LULUS: tepat 24 file .kt berubah vs baseline vc316 + 1 baru
    (20 vc322 + 4 wrapper slice 7 + SqliteSeam.kt; MemoryDb +
    Flavor.kt + DsFailure.kt tidak berubah).
  - D1–D13 LULUS (D13 baru: fix sesi vc322 dipertahankan +
    fallback slice 7 dipertahankan + RUST_CORE_VERSION 5).
  - Log ronde 2: `vc317-work/work-impl/run-tests-vc323-coord.log`
    (16 Nov 11:20 UTC / 18:20 WIB, 116.874 byte; `OVERALL FAIL=1`
    hanya dari R2-4.1). Log LEBIH BARU dari runner
    (10:54 UTC) — segar.
  - Total = 1201 (vc322) + 134 (P9) = 1335 cek; 1334 lulus.
- Ronde 1 (log sama, tertimpa ronde 2): TERHENTI di P5b mode rust —
  proses suite mati diam-diam tanpa jejak di log (bukan kegagalan
  tes; P1–P5a sudah lulus semua: P1 103 + P2 36 + P3 94 + P4 235 +
  N9 46 + BR 45 + IXL 72 + R1 64 + R2 68/69 + P5a 55/55; dmesg
  tanpa OOM). Diduga service restart runtime yang membunuh process
  tree (restart terjadi di tengah ronde 1). Ronde 2 = re-run penuh
  (maks 2 ronde — ronde 2 adalah re-run penuh terakhir); P8 73/73
  lulus IN-SUITE di ronde 2 (tidak perlu standalone seperti vc322).

## §7. Deviasi / temuan jujur

1. **Resume dari snapshot (bukan dari live).** Patch slice 7
   diparkir koordinator ke `vc317-work/work-s7/snapshot-patch/` (16
   file + MANIFEST.md). Restore: 16/16 md5 COCOK; 13 file disalin
   ke live + branch-stage (md5 salinan = MANIFEST); live sebelum
   restore bersih vc322 (marker slice 7 = 0, VERSION 4).
2. **Rebase TANPA konflik.** Fix vc322 menyentuh DsApiClient,
   SlotClient, MainActivity, TurnMetrics, AgentPrompt +
   TestSessFixVc322.kt; slice 7 menyentuh EventDb, ArtifactStore,
   ParkedApprovalStoreVc305, GraphDb, RustCore, SqliteSeam + crate.
   DISJOINT — dibuktikan diff snapshot-vs-live (hanya delta slice 7:
   RustCore VERSION 4→5 + sqliteJson; jni_glue RUST_CORE_VERSION
   4→5 + sqliteJson; lib.rs +2 mod; Cargo.toml +rusqlite/base64;
   Cargo.lock +paket baru). MemoryDb.kt IDENTIK dengan merge/.
3. **Renumbering vc322→vc323.** Bump 323/4.48.9.157 di live build.py
   DAN branch-stage build.py (hanya baris VERSION; isi keduanya
   memang berbeda, tidak saling ditimpa). Rename:
   BUILD-VC322-slice7-rusqlite-DRAFT.md → BUILD-VC323.md;
   TestRusqliteVc322.kt → TestRusqliteVc323.kt (+ P8→P9, karena P8
   sudah dipakai fix sesi vc322); runner run-tests-vc322-coord.sh →
   run-tests-vc323-coord.sh (+ seksi P9); CI run-suite-ci.sh +
   android.yml (step P9). TestSessFixVc322.kt TIDAK di-rename.
4. **Assert VERSION 4→5 diterapkan kembali di 9 file tes**
   (8 warisan slice 7 + 1 baru): work-s2/TestPowSolverVc318.kt,
   work-s3/TestRedactSecretsVc318.kt,
   work-s4/TestSmallModulesVc318.kt, branch-stage/tests/
   TestPowSolverVc318.kt, TestRedactSecretsVc318.kt,
   TestSmallModulesVc318.kt, TestConcatApiVc319.kt (R1),
   TestToolRegistryAgentPromptVc320.kt (7.1 + 7.3 capabilities
   version 5) — masing-masing diverifikasi per baris, bukan ganti
   global. Tambahan di luar daftar 8: TestSessFixVc322.kt cek 5.18
   (`"const val VERSION = 4" in rcSrc`) — SATU-SATUNYA perubahan di
   file itu; premis "tidak ada JNI baru" sudah tidak berlaku di
   vc323 (sqliteJson baru), jadi ekspektasi diubah ke 5 + label
   diperbarui. Perilaku fix sesi TIDAK berubah.
5. **C dihitung dari diff AKTUAL** (bukan asumsi): 24 berubah + 1
   baru (20 vc322 + 4 wrapper slice 7 + SqliteSeam.kt baru).
   Fix vc322 tidak menambah file baru vs merge/ (5 file fix sudah
   berbeda sejak vc321/slice 6).
6. **D13 ditulis ulang** (bukan ditambah): fix sesi vc322
   dipertahankan + fallback slice 7 dipertahankan + RUST_CORE_VERSION
   5. D12 diperbarui ke 5. D7/D8/D10/D11 diperbarui ke 5 (lokal +
   CI). CI run_c ditambah cek slice 7 (4 wrapper berubah +
   SqliteSeam.kt/sqlite.rs/sqlite_schema.rs baru vs main).
7. **Ronde 1 suite mati diam-diam di P5b** (lihat §6) — bukan
   kegagalan tes; ronde 2 penuh lulus target. `work-impl/integrated/`
   di-sync ulang dari live tree saat ini (4 wrapper + RustCore +
   SqliteSeam.kt baru); sesudahnya diff integrated vs live kosong
   kecuali build.py/harness ekstra yang memang bukan tree .kt.
8. **Keputusan §2.4 TIDAK berubah:** `MemoryDb.kt` tetap tidak
   di-port wrapper-nya; DDL + migrasi v6→v7 + lazy onOpen sudah
   direplikasi di `sqlite_schema.rs` dan dibuktikan tes Rust + P9
   atas fixture v6.
9. **CI ronde 2 gagal di BUILD APK, bukan di tes — dan VM ikut
   terblokir (lihat §8).** Akar: `libsqlite3-sys` (rusqlite
   `bundled`) mengkompilasi C SQLite dan butuh
   `aarch64-linux-android-clang`; build.py langkah 0r tidak pernah
   men-set `CC_aarch64_linux_android` / PATH NDK bin untuk cc-rs
   (slice lama Rust murni, tanpa C — masalah ini baru muncul di
   slice 7). VM: biner clang NDK HILANG (`bin/clang -> clang-21`
   dangling; semua wrapper `*-clang` memanggilnya; tidak ada
   clang sistem, hanya gcc). Perbaikan CI yang jelas: set
   `CC_aarch64_linux_android` ke wrapper berversi
   `aarch64-linux-android26-clang` di build.py 0r. Perbaikan VM:
   butuh keputusan koordinator (unduh ulang NDK / sumber clang
   lain) — integrator BERHENTI di sini, tidak push ronde 3.

## §8. Push + CI + APK

- **Pra-cek drift (sebelum push 1):** remote HEAD 2920e3d5
  terverifikasi (cocok basis vc322); selisih remote tree vs staging
  tepat 6 baru + 18 berubah, 0 remote-only:
  baru = BUILD-VC323.md, TES-VC323-SIAP-TEMPEL.txt, SqliteSeam.kt,
  sqlite.rs, sqlite_schema.rs, TestRusqliteVc323.kt;
  berubah = android.yml, 4 wrapper, RustCore.kt, build.py,
  Cargo.lock, Cargo.toml, jni_glue.rs, lib.rs, 6 file tes
  (assert VERSION 4→5), run-suite-ci.sh.
- **Push 1:** 16 Nov 2026 ~18:32 WIB, branch `vc317-rust`, commit
  `2afd9ab3e533f010e18b2ab68e54dd3479c57b84`, 478 file via `gh.py
  push-tree`. Verifikasi: remote HEAD = 2afd9ab3 (parent 2920e3d5);
  remote tree 478 blob == staging 478 file, 0 beda; 24/24 file
  delta SHA cocok. Main tidak disentuh (tetap 2e6245e3 = vc309).
- **CI ronde 1:** run [#43858687962](https://github.com/Sendysyah27/deepseek-harness/actions/runs/43858687962)
  (push, head 2afd9ab3) — **FAILURE**, job `build`. Log (trik 302):
  P1–P8 semua HIJAU; satu-satunya gagal = **P9 mode rust 106/107,
  cek 12.7** ("RustCore.kt VERSION 5 + sqliteJson"). Akar: tes
  12.7 hanya mencoba 2 lokasi RustCore.kt (tree + naik-1); di
  layout CI tree = `.../deepharness` sehingga RustCore ada di
  naik-3 (`deepharness → senzwh → com → java`) — akar yang SAMA
  dengan P8 5.18 vc322. Perbaikan: 12.7 mencoba 4 tingkat
  (0/1/2/3). Diverifikasi lokal di KEDUA layout SEBELUM re-push:
  integrated 107/107 + 27/27, layout CI (app dir) 107/107 +
  27/27 (`work-impl/run-p9-ci-layout.log`; 2 run integrated
  pertama di log itu SALAH path relatif dari CWD yang keliru —
  tidak valid, diulang dengan path benar dan lulus).
- **Push 2 (fix CI):** 16 Nov 2026 ~19:00 WIB, branch `vc317-rust`,
  commit `8c8f809dcfec338d5fdf0050568b628a574f4505`, 478 file via
  `gh.py push-tree` (hanya `tests/TestRusqliteVc323.kt` berubah vs
  push 1; SHA remote cocok staging; 478/478, 0 beda; main utuh).
  Pesan: "fix P9 12.7 — RustCore naik 4 tingkat di layout CI".
- **CI ronde 2:** run [#43860941939](https://github.com/Sendysyah27/deepseek-harness/actions/runs/43860941939)
  (push, head 8c8f809d) — **FAILURE**, job `build`. SEMUA langkah
  suite HIJAU (cargo test, P1–P9 termasuk P9 134/134, N9, BR,
  IXL, R1, R2, C, D); gagal di **"Build APK Main (senz)"**:
  `GAGAL 0r: cargo build libharness_core.so` —
  `libsqlite3-sys v0.30.1: failed to find tool
  "aarch64-linux-android-clang"`. Akar (dua lapis):
  (1) build.py langkah 0r tidak men-set `CC_aarch64_linux_android`
  atau PATH NDK bin untuk cc-rs — slice 0–6 Rust murni (tanpa C)
  sehingga tidak pernah dibutuhkan; rusqlite `bundled` adalah
  modul pertama yang mengkompilasi C (amalgamasi SQLite).
  (2) **VM ikut terblokir:** biner clang NDK VM HILANG —
  `toolchains/llvm/prebuilt/linux-x86_64/bin/clang -> clang-21`
  (symlink menggantung, target tidak ada); semua wrapper
  `aarch64-linux-android*-clang` memanggilnya dan gagal;
  `lib/clang/21/` ada tapi hanya berisi lib; tidak ada clang
  sistem (hanya `/usr/bin/gcc`). Jadi perbaikan build.py saja
  memperbaiki CI (NDK fresh, clang utuh) tapi TIDAK memperbaiki
  build VM (langkah 7) — VM butuh biner clang dipulihkan dulu
  (unduh ulang NDK / sumber lain).
- **STOP — tidak ada push 3.** Integrator berhenti sesuai aturan
  (laporkan kegagalan SEBELUM ronde perbaikan berikutnya).
  Yang belum: perbaikan `CC_aarch64_linux_android` di build.py
  0r (+ android.yml bila perlu), pemulihan clang NDK VM,
  artifact CI, build VM + gerbang sidik jari, doc-sync §8 ini
  ke remote (BELUM di-push — supaya tidak memicu run CI yang
  pasti gagal lagi di langkah yang sama).

## §8.1 UNBLOCK — pemulihan clang + perbaikan build.py 0r (integrator vc323, 16 Nov ~19:00 WIB)

**0. VERIFIKASI BLOKIR (ketiganya terverifikasi langsung, bukan klaim laporan):**
- (a) VM 0r direproduksi persis seperti build.py (`build.build_rust_so()`):
  `error occurred in cc-rs: failed to find tool "aarch64-linux-android-clang":
  No such file or directory (os error 2)` — EXIT 1.
  `CC_aarch64_linux_android = None` di stdout build-script libsqlite3-sys.
- (b) NDK VM: `bin/clang -> clang-21` symlink menggantung; `clang-21`,
  `lld`, `llvm-bolt` TIDAK ADA (8 symlink menggantung: clang, clang++,
  ld, ld.lld, ld64.lld, lld-link, wasm-ld, perf2bolt); wrapper
  `aarch64-linux-android21-clang` gagal "No such file or directory";
  `llvm-ar` ADA (16.473.560 byte); hanya `/usr/bin/gcc` (host).
  `apt-cache policy clang` keluar KOSONG (package lists kosong) — apt
  tidak dipakai.
- (c) Log job CI ronde 2 diunduh via trik 302
  (`work-impl/log-ci-r2-43860941939.txt`, 263.723 byte; job
  133277280418): baris 3020 error PERSIS sama (`failed to find tool
  "aarch64-linux-android-clang"`), step `python3 build.py --flavor senz`,
  `ANDROID_NDK_HOME=/usr/local/lib/android/sdk/ndk/30.0.16248370`;
  D10–D13 LULUS sebelum step build; P9 sudah jalan (baris 2407).
  Jadi CI gagal di NDK-nya yang SEHAT — akar lapis ke-2 (CC tidak
  di-set), bukan NDK CI yang rusak.

**1. PULIHKAN CLANG VM — opsi (a) BERHASIL, tanpa unduh/apt:**
- Zip NDK ter-cache ditemukan di VM: `~/workspace/tmp/android-ndk-r30-linux.zip`
  (738.633.529 byte, MD5 `b773f7838b227639a4bb6195045916d4`, `unzip -t`
  OK, 0 error) — berisi `clang-21` (153.783.400 byte) + `lld`
  (75.955.976 byte) NDK r30 yang sama persis.
- Perbaikan SURGICAL: hanya 2 file yang hilang itu disalin ke
  `.../toolchains/llvm/prebuilt/linux-x86_64/bin/` (tidak ada file
  lama yang ditimpa/dihapus — backup dir NDK tidak diperlukan;
  symlink menggantung kini ter-resolve). MD5 sesudah salin:
  clang-21 `f97adccf61cac55b8fc28dcc23eb40ed`,
  lld `a4d008d38e8cb592d6edaf4f62e0b21b`.
- Verifikasi: `clang --version` = Android clang 21.0.0;
  `aarch64-linux-android26-clang --version` target
  `aarch64-unknown-linux-android26`; hello-world C → objek
  `ELF 64-bit LSB relocatable, ARM aarch64`. Sisa 1 symlink
  menggantung: `perf2bolt -> llvm-bolt` (tool perf tak terpakai —
  dibiarkan).
- Bukti 2 lapis TERPISAH (diagnosa, bukan tebak): sesudah clang
  pulih tapi SEBELUM perbaikan build.py, 0r TETAP gagal dengan
  error yang sama persis (`CC_aarch64_linux_android = None`) —
  jadi pemulihan clang saja TIDAK cukup; perbaikan CC di build.py
  memang dibutuhkan.

**2. PERBAIKAN build.py 0r (live DAN branch-stage, masing-masing
diterapkan setara — isi keduanya memang beda, tidak saling ditimpa;
hanya blok 0r yang berubah):**
- `CC_aarch64_linux_android` = `<NDK>/toolchains/llvm/prebuilt/
  linux-x86_64/bin/aarch64-linux-android26-clang` (path lengkap dari
  lokasi NDK yang sudah dikenal build.py; wrapper API 26 = minSdk 26,
  wrapper sudah membawa `--target=aarch64-linux-android26` + sysroot).
  Alasan: NDK TIDAK PERNAH menyediakan `aarch64-linux-android-clang`
  tanpa versi (hanya wrapper berversi 21..37 — terverifikasi dari
  isi NDK 30.0.16248370 yang SAMA dengan yang diinstal CI oleh
  workflow `ndk;30.0.16248370`), jadi cc-rs tidak akan pernah
  menemukannya sendiri meski NDK sehat.
- `CXX_aarch64_linux_android` = wrapper `...26-clang++` yang sama
  (preventif; libsqlite3-sys murni C, tapi cc-rs C++ memakai var ini).
- `AR_aarch64_linux_android` = `<NDK>/.../bin/llvm-ar` — ditemukan
  ronde perbaikan 1: sesudah CC di-set, 0r gagal berikutnya di
  `failed to find tool "aarch64-linux-android-ar"` (cc-rs butuh
  archiver untuk arsip statis `.a` SQLite; ia TIDAK membaca
  `CARGO_TARGET_*_AR` yang sudah di-set build.py sejak vc317).
- Guard `os.path.isfile(...)` di tiap var (bila wrapper hilang,
  var tidak di-set — cc-rs gagal dengan pesannya sendiri, bukan
  diam-diam). android.yml TIDAK berubah (workflow sudah menginstal
  NDK 30 yang benar; tidak ada langkah setup yang salah).

**3. VERIFIKASI LOKAL 0r (VM, sesudah perbaikan): SUKSES.**
- `libharness_core.so` 3.235.040 byte (vc322: 2.119.656 — membesar
  karena amalgamasi SQLite, wajar), ELF 64-bit aarch64, stripped.
- Simbol: JNI `Java_harness_core_RustCore_sqliteJson` ADA (nm -D);
  total 25 simbol JNI (24 lama + 1 baru); strings memuat header
  `SQLite format 3` + tabel pesan error SQLite lengkap + 74 string
  `sqlite` (rusqlite 0.32.1). Ronde perbaikan loop 0r: 1 dari maks 2.

## §8.2 Push 3 + CI ronde 3 + APK (16 Nov ~19:11–19:35 WIB)

- **Pra-cek drift (sebelum push 3):** remote HEAD 8c8f809d
  terverifikasi; selisih remote tree vs staging tepat 2 berubah,
  0 baru, 0 remote-only: `build.py` + `BUILD-VC323.md` (§8.1).
  android.yml TIDAK berubah (validasi `yaml.safe_load` tetap OK).
- **Push 3:** 16 Nov 2026 ~19:11 WIB, branch `vc317-rust`, commit
  `5af5c91a8702ec8bff46b69f1a877541f13de6d9`, 478 file via `gh.py
  push-tree` (528 dtk). Verifikasi: remote HEAD = 5af5c91a
  (parent 8c8f809d); remote tree 478 blob == staging 478 file,
  0 beda. Main tidak disentuh (tetap 2e6245e3 = vc309).
- **CI ronde 3:** run [#43864275007](https://github.com/Sendysyah27/deepseek-harness/actions/runs/43864275007)
  (push, head 5af5c91a) — **SUCCESS** (12:11:15Z → 12:18:16Z,
  ~7 mnt; job `build` success). Semua langkah hijau: cargo test,
  suite P1–P9 + N9/BR/IXL/R1/R2/C/D, build APK Main + Pure,
  Verify, Upload. Perbaikan CC/CXX/AR TERBUKTI di NDK CI yang
  fresh.
- **APK CI** (`vc317-work/apk-vc323-ci/`): artifact
  `harness-vc317-apks` 27.656.935 byte (zip MD5
  `f05b998e3b99c199bd9f2a024b148cde`, SHA256
  `7bac875bd62d5d96fc1d6f81b1c943f8ccfff65988381eb5b4d38929351c6c12`).
  - Main `DeepSeekHarness-v4.48.9.157.apk` 13.945.964 B, MD5
    `7d3b3cd99aa1a511ddebe0aa7fc98b78`, SHA256
    `7aeb86da8bcb4527ad48beab62b0eb99921158003211d45fda0cc04dbcc3e51c`;
    aapt2 323/4.48.9.157, `com.senzwh.deepharness`, `S&Harness`;
    apksigner Verifies, v2+v3, 1 signer, cert SHA-256
    `aa5324495a12782e40cb37e6b8b8d7431f32a0f162b5d297c5a8142f40c45eec`
    (BEDA dari VM — kunci runner, EKSPEKTASI).
  - Pure `Harness-v4.48.9.157-pure.apk` 13.896.812 B, MD5
    `7038246e69c3523caab3e7a880bc5166`, SHA256
    `97b8b9da2ee866ef1aa6047193fa8fc1b8f934e4ca8eaa1e330459d4eeab695e`;
    aapt2 323/4.48.9.157, `com.harness.app`, `Harness`;
    apksigner Verifies, v2+v3, 1 signer, cert SHA-256
    `1c8d536673a798956e96f4cff0aa8acf8321459d89dc1e7a2e19b8c94a800549`
    (BEDA — kunci runner, EKSPEKTASI).
  - Kedua APK: `libharness_core.so` 2.953.472 B di lib/arm64-v8a
    (CI lebih kecil dari VM — pola yang sama seperti slice
    sebelumnya); `sqliteJson` ADA, 25 simbol JNI; `hsurr:` 0;
    arm64-only (tanpa ABI lain).
- **cargo test (dijalankan ulang integrator, VM): 287/287, 0 failed.**
  Suite lokal TIDAK dijalankan ulang penuh (angka koordinator
  1334/1335 ronde 2 masih berlaku — perubahan push 3 hanya
  infra build.py 0r + doc, tanpa sentuhan kode slice 7 / Kotlin /
  Rust; bukti pengganti: cargo test 287/287 + 0r + CI ronde 3
  SUCCESS yang menjalankan suite penuh di CI).
- **BUILD VM + VERIFIKASI MANDIRI** (`python3 build.py --flavor
  senz` lalu `--flavor pure`, JH `~/jdk-21-sys`, sekuensial):
  - Main `DeepSeekHarness-v4.48.9.157.apk` 14.027.884 B, MD5
    `f84c97ca3bee5c2a81ef34aa35171312`, SHA256
    `3d30e902b1330924871eb31394b01339795a8dffdb4b45f5f9050b933f2d80f5`.
  - Pure `Harness-v4.48.9.157-pure.apk` 13.982.828 B, MD5
    `031203118f0902b5bb11f794f8a2b22a`, SHA256
    `c86cbe89bc6d29a9d17a41d5bae990795deb01617bbd22a9c17b21dedbc0f2cc`.
  - Keduanya di `vc317-work/apk-vc323-vm/`; aapt2 323/4.48.9.157;
    apksigner Verifies, v2+v3, 1 signer;
    `libharness_core.so` 3.235.040 B di lib/arm64-v8a;
    `sqliteJson` ADA, 25 simbol JNI; `hsurr:` 0; arm64-only.
  - **GERBANG SIDIK JARI LULUS — BISA MENIMPA:** Main
    `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`,
    Pure
    `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`
    (sama persis seperti vc316).
- **TES-VC323-SIAP-TEMPEL.txt:** final, tidak berubah sejak push 1
  (sudah di remote; T1–T6 vc322 + T-baru minimal slice 7, lihat §9).
- **SLICE 7 / vc323 SELESAI PENUH** (kode + tes + CI hijau + APK VM
  + APK CI terverifikasi). APK VM kanonis vc323 =
  `vc317-work/apk-vc323-vm/` (upload = tugas Jhon; JANGAN upload
  sendiri ke HP).

## §9. Tes perangkat (TES-VC323-SIAP-TEMPEL)

Lihat `vc317-work/TES-VC323-SIAP-TEMPEL.txt` — perilaku user-visible
IDENTIK (porting murni + fallback): T1–T6 vc322 tetap berlaku
(sesi fix), ditambah T-baru minimal: buka app + kirim 1 perintah +
pastikan data lama (riwayat/skills) tetap ada dan tidak crash.
