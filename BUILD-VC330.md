# BUILD-VC330 — Fix Bocor Sesi: Delta Verifikasi + Delta Ekstraksi + Katup Pengaman Giliran Senyap (vc330, 4.48.9.164)

Dokumen kerja integrator fix SATU bocor sesi (perintah langsung Sendy).
Pola dokumen mengikuti BUILD-VC327.md (fix sesi terakhir).

## §1. Keputusan versi

- **vc330, VERSION_CODE 330, VERSION_NAME 4.48.9.164**, branch `vc317-rust`
  (remote main tetap vc309 — JANGAN disentuh).
- `RUST_CORE_VERSION` **tetap 10** (TIDAK ADA perubahan Rust — tidak ada
  JNI baru, tidak ada sentuhan ke `jni_glue.rs` / `RustCore.kt`).
- Perilaku user-visible yang berubah HANYA yang diperintahkan:
  verifikasi diri + ekstraksi fakta kini mengirim delta (bukan prompt
  penuh), dan giliran senyap yang akan menembak guard reset DILEWATI
  tanpa POST (bukan membakar wadah baru). Verdict LULUS/KOREKSI tetap
  muncul seperti biasa; bila wadah penuh, UI menampilkan "dilewati",
  BUKAN "lolos verifikasi".
- Keystore VM lama dipertahankan; gerbang sidik jari vc316 berlaku:
  Main `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`,
  Pure `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`
  — bila beda, build GAGAL.

## §2. Ukur dulu + desain fix (SEBELUM patch)

### §2.1 Masalah (bukti live Sendy 17 Nov 09:07, vc329)

- Chat "Utama" lane 1-1 slot Browser 2 (akun Eneng Sunarsih), vc329
  terinstall (base.apk MD5 `06aa0b0d…`).
- "say hello bro" AMAN; "cek skill lists" (pakai tool) → notifikasi
  "sesi browser 2 membuat wadah baru" padahal sesi Harness tetap "Utama".

### §2.2 Mekanisme (terverifikasi dari kode vc329 — re-verifikasi sebelum patch)

- (1) `MainActivity.kt` ~12569-12576: giliran VERIFIKASI DIRI
  (`turnUsedTools==true` atau klaim faktual) memanggil
  `sendToAgent(teks verifikasi)` TANPA deltaPrompt (default null) —
  ctxHist 20.000 char ikut dikirim ulang.
- (2) `MainActivity.kt` ~12591-12594: TUGAS LATAR (ekstraksi) juga
  tanpa deltaPrompt — hist 40 pesan / 40.000 char dikirim ulang.
  Komentar vc241 O7 di atasnya menyebut niat jendela 13 pesan
  (10 sejak ekstraksi terakhir + overlap 3) — kode memakai 40/40.000.
- (3) `sendToAgent(prompt, deltaPrompt=null)` di `MainActivity.kt:11504`;
  komentar ~11494-11503 secara eksplisit menyebut verifikasi/tugas
  latar/rescue sebagai pemanggil prompt-penuh.
- (4) `SlotClient.kt` runChat ~221-270 + `DsApiClient.kt` runChat
  ~200-280: `useDeltaVc322=false` karena deltaPrompt null → kirim
  prompt PENUH → guard `TurnMetrics.shouldResetServerSessionVc321
  (accum + prompt > 200.000)` menembak → clearServerSession +
  createServerSession = wadah BARU.
- (5) Fix vc327 H3 (delta auto-save, `MainActivity` ~11807-11817 +
  `AgentPrompt.deltaTurnPromptVc327` :1284) HANYA mencakup giliran
  auto-save "ingat:".
- Basis terverifikasi sebelum patch: 5 file Kotlin live == branch-stage
  (byte-identik); `build.py` live vs branch-stage hanya beda CI-adapted
  vs live hard-fail yang DIKENAL; remote HEAD `0a42b609` (saat mulai);
  `RustCore.VERSION = 10`.

### §2.3 Ukuran prompt (string nyata, input representatif)

Input representatif: trail 2.000 char, jawaban final 500 char,
wsLine 23 char, ctxHist 20.000 char (cap), hist40 36.024 char,
jendela-13 7.224 char. Template penuh disalin verbatim dari
`MainActivity.kt` (replika di `TestSessFixVc330.kt` §1/§2; §5.3/5.4
mengunci literal produksi tetap sama).

| Prompt | PENUH (sebelum) | DELTA vc330 | Rasio |
|---|---|---|---|
| Verifikasi diri | 23.417 | 4.009 | 17% |
| Tugas latar (ekstraksi) | 36.578 | 8.434 | 23% |

- Delta verifikasi = jangkar vc316 (503) + instruksi verifikasi
  (533 + 392) + header jejak/jawaban (26 + 32) + trail 2.000 +
  wsLine 23 + jawaban 500 = **4.009**. Yang dihapus: ctxHist 20.000.
- Delta ekstraksi = jangkar (503) + instruksi tugas latar (671) +
  header jendela (36) + jendela-13 7.224 = **8.434**. Yang dihapus:
  27 pesan lama + ~28.800 char (40/40.000 → 13/8.000).
- Prompt PENUH tetap dikirim sebagai argumen pertama `sendToAgent`
  (fallback bila wadah belum primed — klien yang memutuskan).
- Ambang katup (terukur §4): accum 196.000 + delta verifikasi 4.009
  = 200.009 > cap → delta DITOLAK `shouldSendDeltaVc322`, guard
  menembak → katup LEWATI. Accum 180.000 + delta 4.009 = 184.009
  → delta JALAN.

### §2.4 Angka SEBELUM fix (rezim vc329 — dikunci P16 §4, simulasi mesin runChat + fungsi asli)

- V1: verify penuh 25k di accum 180k → **1 sesi baru** (reset).
- E1: extract penuh 36.578 di accum 170k → **1 sesi baru** (reset).
- REPRO (17 Nov 09:07): penuh 150k + delta + verify penuh 60k → **2 sesi**.

### §2.5 Angka SESUDAH fix (desain target — dikunci P16 §4)

- V1: verify DELTA 4.009 di accum 180k → **0 sesi baru** (accum 184.009).
- V2 (katup): verify delta 4.009 TAPI accum 196k + delta > cap →
  **DILEWATI, 0 sesi**, accum tetap 196.000.
- E1: extract DELTA 8.434 di accum 170k → **0 sesi baru** (accum 178.434).
- E2 (katup): extract delta 8.434 TAPI accum 196k + delta > cap →
  **DILEWATI, 0 sesi**.
- User-visible di kondisi katup yang sama → **RESET 1 sesi** (tidak dilewati).
- Silent + accum 0 → **JALAN** (post penuh pertama, 0 sesi baru).
- REPRO SESUDAH: penuh 150k + delta + verify DELTA 22.009 (trail 20k)
  → **1 sesi**. REPRO KATUP: accum 184.993 + delta 22.009 > cap →
  **DILEWATI, tetap 1 sesi**.
- REGRESI: delta normal 30 giliran → 1 sesi; guard IXL tetap
  (accum 195k + delta lalu + penuh → 1 reset).

### §2.6 Keputusan fix (dari ukuran, bukan asumsi)

1. **VERIFIKASI DELTA** — `AgentPrompt.deltaVerifyPromptVc330
   (userCommand, trailTxt, wsLine, finalAnswer)`: jangkar
   `continuationAnchorVc316(currentTurnUser)` + instruksi VERIFIKASI
   DIRI yang sama (aturan output ketat verbatim supaya `cleanVerdict`
   tetap bekerja) + trail + wsLine + jawaban final. Riwayat dinyatakan
   eksplisit SUDAH ada di konteks sesi server — jangan minta dikirim
   ulang. ctxHist 20.000 char TIDAK ikut.
2. **EKSTRAKSI DELTA** — `AgentPrompt.deltaExtractPromptVc330
   (userCommand, recentHist)`: jangkar + instruksi TUGAS LATAR yang
   sama + jendela RIWAYAT BARU sejak ekstraksi terakhir SAJA
   (`historyTranscriptVc230(13, 8000, turnSessionId)` — konstanta
   `EXTRACT_WINDOW_N_VC330 = 13`, `EXTRACT_WINDOW_CHARS_VC330 = 8000`).
   Gate sinyal fakta tetap memakai hist penuh 40/40.000 (tidak berubah).
3. **KATUP PENGAMAN** — parameter baru `silentKindVc330: String? = null`
   (`"verify"` | `"extract"` | null) di `SlotClient.chat→runChat` DAN
   `DsApiClient.chat→runChat`, diteruskan di SEMUA situs runChat di
   dalam `chat()` (SlotClient 6, DsApiClient 8 — termasuk retry
   stream-putus, rate-limit/upstream, timeout, forceFresh, dan failover
   DsApiClient). `chatSync` TeamRun dibiarkan default null.
   `MainActivity.sendToAgent` menghitungnya dari flag: `verifyTurn` →
   `"verify"`, `internalTurn` → `"extract"`, else null; teruskan ke
   KEDUA situs klien (slot + apiClient). Di `runChat`, TEPAT di guard
   reset yang sudah ada: bila
   `TurnMetrics.shouldSkipSilentVc330(silentKind, useDelta, stored,
   shouldReset(accum, prompt.length), freshEphemeral)` true →
   `logSessEvent` JEJAK ("⏭️ Verifikasi/Ekstraksi DILEWATI vc330
   (wadah penuh: akumulasi X char + prompt Y char) — giliran senyap
   tidak boleh membuat wadah baru") lalu RETURN hasil netral TANPA POST:
   verify → sentinel `VERIFY_SKIPPED_VC330` (`"VERIFIKASI-DILEWATI-VC330"`),
   extract → persis `"TIDAK ADA"`. Keputusan murni di
   `TurnMetrics.shouldSkipSilentVc330` (tanpa Context) — diuji §3 P16.
   Guard reset untuk giliran user-visible TIDAK BERUBAH.
4. **SENTINEL SEBELUM cleanVerdict** — cabang `verifyTurn` di
   `handleAgentResponse` mengecek `visible.trim() ==
   AgentPrompt.VERIFY_SKIPPED_VC330` SEBELUM `cleanVerdict`: sentinel
   TIDAK diawali "LULUS" dan TIDAK mengandung "koreksi", tapi
   `cleanVerdict` akan mengembalikannya utuh sebagai "koreksi" bila
   lolos ke sana (cabang else, panjang < 350). UI menampilkan
   "⏭️ Verifikasi diri dilewati vc330 (wadah server penuh — giliran
   senyap tidak boleh membuat wadah baru)", BUKAN "lolos verifikasi".
   Extract "TIDAK ADA" → cabang `internalTurn` menutup giliran tanpa
   proposal (alur yang sama seperti jawaban model sendiri bila tak ada
   fakta baru — diverifikasi dari kode, tidak diubah).

### §2.7 Trade-off / ambiguitas (dipilih yang PALING AMAN buat akun)

- **Akurasi verdict via delta:** verifikasi via delta hanya membawa
  JEJAK TOOL + jawaban final (bukan riwayat 20k). Bila klaim jawaban
  merujuk riwayat lama yang TIDAK ada di jejak tool, model bisa salah
  menilai — TAPI itu tetap lebih aman daripada membakar wadah (yang
  dulu bikin akun menumpuk sesi + risiko suspend). Bila wadah penuh,
  verifikasi DILEWATI saja (bukan ditebak LULUS). Prinsip: lewati
  verifikasi daripada bakar wadah.
- **Ekstraksi jendela 13:** fakta dari pesan >13 ke belakang yang
  belum terekstrak bisa terlewat SATU putaran — tapi ekstraksi
  berikutnya mengambil jendela 13 TERBARU (overlap 3); fakta lama yang
  penting biasanya muncul ulang / sudah ada di memori. Yang dihapus
  hanya pengiriman ulang 40/40.000, bukan cakupan ekstraksi per
  putaran (tetap 13 pesan seperti niat vc241 O7).
- **Residu jujur — forceFresh sesudah stream putus 2x/3x untuk
  giliran senyap TIDAK diubah:** bila stream verifikasi/ekstraksi
  putus berulang, `chat()` tetap memanggil `runChat(forceFresh=true)`
  seperti biasa — cooldown vc327 (maks 1/10 mnt) sudah membatasi
  ≤1 wadah baru per 10 menit per lane. Katup hanya menahan guard
  reset proaktif, bukan forceFresh darurat. Ini residu yang diterima
  (bukan celah baru — perilaku vc327 yang sudah ada).

## §3. Basis verifikasi (langkah 0 — semua LULUS sebelum patch)

- Live vs branch-stage: 5 file Kotlin (`AgentPrompt`, `TurnMetrics`,
  `MainActivity`, `SlotClient`, `DsApiClient`) byte-identik; `build.py`
  hanya beda CI-adapted vs live hard-fail yang DIKENAL.
- Remote HEAD saat mulai: `0a42b609` (tree_sha via `gh-ci.py tree`).
- `RustCore.VERSION = 10`, `RUST_CORE_VERSION: jint = 10`.
- `DEFAULT_CAP = 200000` (`TurnMetrics.kt:34`).
- Komentar vc241 O7 (jendela 13) vs kode 40/40.000 — DIVERIFIKASI
  (bukan asumsi): `MainActivity.kt` ~12580-12594.

## §4. Isi fix (file yang berubah)

HANYA 5 file Kotlin + 1 file tes + 2 runner + `build.py` (versi).
TIDAK ADA perubahan Rust, `Flavor.kt`, `MemoryDb.kt`.

### AgentPrompt.kt — sesudah `deltaTurnPromptVc327`

- `const val EXTRACT_WINDOW_N_VC330 = 13`,
  `const val EXTRACT_WINDOW_CHARS_VC330 = 8000`.
- `const val VERIFY_SKIPPED_VC330 = "VERIFIKASI-DILEWATI-VC330"`.
- `fun silentSkippedResponseVc330(kind: String?)`: `"extract"` →
  `"TIDAK ADA"`, else sentinel.
- `fun deltaVerifyPromptVc330(userCommand, trailTxt, wsLine, finalAnswer)`.
- `fun deltaExtractPromptVc330(userCommand, recentHist)`.
  Semua murni, tanpa Context (pola `deltaTurnPromptVc327`).

### TurnMetrics.kt — sesudah `shouldResetServerSessionVc321`

- `fun shouldSkipSilentVc330(silentKind, useDelta, hasStoredSession,
  wouldReset, freshEphemeral)`: `silentKind != null && !useDelta &&
  hasStoredSession && wouldReset && !freshEphemeral`. Murni.
  `DEFAULT_CAP` tidak berubah.

### SlotClient.kt

- `chat(...)`: param baru `silentKindVc330: String? = null` (ujung,
  default null). 6 situs `runChat` di dalam `chat()` meneruskannya
  (percobaan pertama + retry stream-putus + forceFresh + retry
  rate-limit + retry upstream + retry timeout).
- `runChat(...)`: param baru yang sama. Katup TEPAT sebelum guard
  reset vc321 (guard di bawahnya byte-identik). `chatSync` tidak berubah.

### DsApiClient.kt

- `chat(...)`: param baru yang sama. 8 situs `runChat` di dalam
  `chat()` meneruskannya (percobaan pertama + retry stream-putus +
  forceFresh + failover suspend + retry rate-limit (+failover) +
  retry upstream + retry timeout).
- `runChat(...)`: param baru yang sama. Katup TEPAT sebelum guard
  reset vc321 (DsApiClient tidak punya freshEphemeral — diteruskan
  `false` ke `shouldSkipSilentVc330`). Guard di bawahnya byte-identik.
  2 situs `runChat` upload (`:927`, `:978`) TIDAK disentuh (default null).

### MainActivity.kt

- Komentar `sendToAgent` diperbarui (vc330): verifikasi/ekstraksi kini
  delta; rescue tetap penuh.
- `sendToAgent`: `silentKindVc330` dari flag (`verifyTurn` → `"verify"`,
  `internalTurn` → `"extract"`, else null); diteruskan ke
  `slotClient.chat` DAN `apiClient.chat`.
- Situs verifikasi: `fullVerifyVc330` (teks lama lengkap, byte-identik)
  + `deltaVerifyVc330 = AgentPrompt.deltaVerifyPromptVc330
  (currentTurnUser, trailTxt, wsLineVc228, visible)` →
  `sendToAgent(fullVerifyVc330, deltaVerifyVc330)`.
- Situs ekstraksi: `histRecentVc330 = historyTranscriptVc230(13, 8000,
  turnSessionId)` + `fullExtractVc330` (teks lama lengkap, byte-identik)
  → `sendToAgent(fullExtractVc330, AgentPrompt.deltaExtractPromptVc330
  (currentTurnUser, histRecentVc330))`.
- Cabang `verifyTurn` di `handleAgentResponse`: cek sentinel SEBELUM
  `cleanVerdict` → bubble meta "dilewati vc330".
- `sendViaWebView` TIDAK disentuh (tanpa delta/valve, seperti sekarang).

### Yang sengaja TIDAK diubah

- `DEFAULT_CAP` 200.000, logika primed/migrasi vc327, forceFresh
  cooldown 10 mnt, perilaku delta giliran 1-1 biasa, Governor (tetap
  hitung prompt penuh — `weightFor(prompt.length)` di kedua klien),
  jalur WebView, `RUST_CORE_VERSION` 10, `Flavor.kt`, `MemoryDb.kt`,
  `GroupChat.kt`, `BgBrain.kt`, file Rust apa pun.

## §5. Tes paritas baru — P16 TestSessFixVc330 (64 cek)

File: `branch-stage/tests/TestSessFixVc330.kt` (model
`TestSessFixVc327.kt` + wiring P13 di `run-suite-ci.sh`).
`SessSim330` memodelkan alur keputusan `runChat` memakai fungsi ASLI
(`shouldResetServerSessionVc321`, `shouldSendDeltaVc322`,
`migratePrimedOnceVc327`, `shouldSkipSilentVc330`, accum/primed prefs
via `FakeCtxVc322`); rezim vc329 = giliran senyap tanpa delta + tanpa
katup, rezim vc330 = delta + katup. Satu-satunya yang dimodelkan adalah
alur keputusan itu sendiri (preseden P8/P13).

- §1 Delta verifikasi (12): jangkar + jejak + wsLine + jawaban final
  ada; marker ctxHist 0x; header `RIWAYAT:` tidak ada; aturan output
  ketat verbatim; panjang eksak 4.009; penuh 23.417; rasio <25%;
  byte-identik; tanpa jejak/ws/jawaban tetap terbentuk.
- §2 Delta ekstraksi (10): jangkar + `TUGAS LATAR — vc330 delta`;
  marker baru ada / lama tidak ada; jendela 13 pesan; konstanta 13/8000;
  panjang eksak 8.434; penuh 36.578; rasio <30%; byte-identik.
- §3 Katup murni (10): matriks keputusan (silent+!delta+stored+
  wouldReset→LEWATI; silent+delta→JALAN; user-visible→JALAN;
  tanpa wadah→JALAN; guard tidak menembak→JALAN; ephemeral→JALAN;
  accum 0→JALAN); guard vc321 tidak berubah; respons netral
  (verify→sentinel, extract→"TIDAK ADA", sentinel bukan LULUS/koreksi).
- §4 Simulasi sebelum→sesudah (13): V1/E1/repro/katup/regresi
  (lihat §2.4/§2.5).
- §5 Struktur (15): marker di 5 file fix; `silentKind` 6 situs
  SlotClient + 8 situs DsApiClient; sentinel sebelum cleanVerdict;
  `chatSync` tanpa silentKind; hsurr 0; batas file (Flavor/MemoryDb/
  GroupChat/BgBrain/Rust tanpa Vc330); VERSION 10; Governor penuh;
  forceFresh vc327 + delta vc322/vc327 dipertahankan.
- §6 Jalur lain (4): `historyBudgetVc321`, `sendToAgent(text)`,
  `sendViaWebView`, `parent_message_id` NULL.

## §6. Angka tes

### R1 (log `work-impl/log-suite-vc330-r1.txt`) — 5 gagal, semuanya assert
string persis usang (R2-4.1 + P8 5.6/5.7 + P14 6.8, lihat §7); BUKAN
regresi produksi. Total suite 2.965 cek; r1 lulus 2.960.

| Section | Hasil r1 |
|---|---|
| Step 0 tree compile | LULUS (jar 8.878.279 byte, kotlinc exit 0) |
| Step 0a host .so Rust | LULUS (59.315.632 byte) |
| P1 TestDsFailureVc316 | 103/103 |
| P2 TestPowSolverVc318 | 36/36 |
| P3 TestRedactSecretsVc318 | 94/94 |
| P4 TestSmallModulesVc318 | 235/235 |
| N9 TestN9Vc316 | 46/46 |
| BR TestBrowserVc316 | 45/45 |
| IXL TestInputLimitVc316 | 72/72 |
| R1 TestConcatApiVc314 | 64/64 |
| R2 TestConcatVc314Merge | 68/69 — gagal 4.1 (usang vc315, DITOLERANSI) |
| P5 TestConcatApiVc319 | kotlin 55/55, rust 102/102 (157) |
| P6 TestToolRegistryAgentPromptVc320 | kotlin 75/75, rust 80/80 (155) |
| P7 TestRoomIxlFixVc321 | 52/52 |
| P8 TestSessFixVc322 | **71/73** — gagal 5.6 + 5.7 (cek usang, diperbaiki §7) |
| P9 TestRusqliteVc323 | rust 107/107, kotlin 27/27 (134) |
| P10 TestHttpVc324 | rust 88/88, kotlin 50/50 (138) |
| P11 TestServer4198Vc325 | rust 151/151, kotlin 151/151 (302) |
| P12 TestToolsLeavesVc326 | rust 86/86, kotlin 86/86 (172) |
| P13 TestSessFixVc327 | 66/66 |
| P14 TestDsApiSplitVc328 | **139/140** rust + **139/140** kotlin — gagal 6.8 (cek usang, diperbaiki §7) |
| P15 TestBgRoomVc329 | rust 312/312, kotlin 296/296 (608) |
| P16 TestSessFixVc330 | **64/64** |
| C (union diff) | LULUS |
| D (sapu staging) | 20 LULUS, 0 GAGAL (termasuk D20 baru) |
| OVERALL r1 | **FAIL=1** (P8 2 + P14 2 + R2-4.1 1) |

### Cargo (Rust, `harness-core`, `cargo test --offline`)

- **397 passed, 0 failed** (log `work-vc330/cargo-test-vc330.log`).
- `cargo build --offline`: `.so` host 59.315.632 byte (02:22 WIB).

### R2 (log `work-impl/log-suite-vc330-r2.txt`) — FINAL, sesudah perbaikan
cek usang P8 5.6/5.7 + P14 6.8

- **2.964/2.965 lulus. SATU-SATUNYA gagal = R2-4.1 (68/69, usang vc315,
  DITOLERANSI — sama seperti vc329).** P8 73/73, P14 140/140 rust +
  140/140 kotlin, P16 64/64. Semua section lain identik r1.
- `SUITE-R2-EXIT=1` karena FAIL=1 dari R2-4.1 saja (runner koordinator
  memang exit 1 bila ada gagal — sama seperti vc329).

## §7. Deviasi / temuan jujur

- **Angka probe dikoreksi 2x dari draf awal:** draf memakai wsLine
  22 char (salah hitung — "Baterai 78% • 09:07 WIB" = 23 char) →
  delta verifikasi 4.007→**4.009**, delta ekstraksi 8.433→**8.434**,
  delta repro (trail 20k) 22.007→**22.009**. Semua angka di dokumen
  ini + tes adalah angka probe Kotlin (`/tmp/probe330/Probe330.kt`),
  bukan tebak. Penuh verifikasi 23.417 + penuh ekstraksi 36.578
  terkonfirmasi via ekstraksi literal Python dari source.
- **Residu forceFresh senyap** (lihat §2.7): katup tidak menahan
  forceFresh sesudah stream putus berulang — cooldown vc327 yang
  membatasi. Dicatat, bukan disembunyikan.
- **Cek 5.12 P16 sempat gagal 1x di iterasi pertama** (resolusi path
  `jni_glue.rs` tidak sampai repo root dari tree lokal) — diperbaiki
  dengan pencarian ancestor (`findUp`), BUKAN dengan melonggarkan
  cek. P16 akhir 64/64.
- **Runner koordinator vc329 berakhir OVERALL FAIL=1** — SATU-SATUNYA
  gagal = R2-4.1 (68/69, assert STRING PERSIS usang warisan vc315,
  ditoleransi eksplisit di CI). Bukan regresi vc329; pola yang sama
  berlaku untuk vc330 (lihat §6).
- **Cek usang P8/P14 diperbarui (bukan dilonggarkan) karena tanda tangan
  berubah secara sah:** penambahan param `silentKindVc330` di ujung
  `chat`/`runChat` mematahkan 3 assert STRING PERSIS di tes lama —
  P8 5.6/5.7 (`runChat(..., deltaPrompt: String? = null)` — kini
  diakhiri `, silentKindVc330: String? = null)`) dan P14 6.8
  (`runChat(..., deltaPrompt = deltaPrompt)` — kini `, silentKindVc330
  = silentKindVc330)`). Perilaku deltaPrompt TIDAK berubah (threading
  identik; param baru default null). Ketiga cek diperbarui ke string
  baru dengan catatan `CATATAN vc330` di file tes (preseden: CATATAN
  vc323/vc324 memperbarui cek 5.18 P8 karena VERSION naik). R1:
  P8 71/73, P14 139/140 per mode — setelah perbaikan, r2 jalan ulang
  penuh (lihat §6). Bukan regresi produksi.

## §8. Push + CI + APK

- Push utama ronde 1: commit `ee9155e9` (513 blob, §8.1); CI run 1 hijau
  TAPI P16 belum ter-wire di workflow (§8.2). Ronde 2 (wiring P16):
  commit `2f3646cb`, run 2 merah instan (YAML). Ronde 3 (kutip nama step
  P16): commit `a9220690`, run 3 merah di P16 5.12 (63/64 — path relatif).
  Ronde 4 (absolutkan tree di tes): commit `141e593d`, **run 4 HIJAU,
  P16 64/64 di CI** (§8.2). Doc-sync (§8.5) dilakukan sesudah run 4.

### §8.1 Push branch `vc317-rust`

- Remote HEAD sebelum push: `0a42b6093ab6fd650d953dd351359e290b2b121d`
  (doc-sync vc329 — cocok ekspektasi; main TIDAK disentuh,
  tetap `2e6245e3`).
- Verifikasi pra-push: HEAD dicek ulang SAAT push (endpoint branches;
  tree segar 512 blob identik `work-impl/remote-tree-vc330.json`);
  staging == remote di luar delta (0 file hanya-di-remote; 9 berubah +
  1 baru, semua sesuai delta vc330; `build.py` diff HANYA
  2 baris VERSION). Skrip: `work-impl/check-push-vc330.py` (pra-push +
  bangun staging + verifikasi SHA staging 513/513);
  `work-impl/verify-push-vc330.py` (pasca-push).
- Push via `gh.py push-tree` (513 blob): commit
  **`ee9155e918879b1b66be1a2e7aa559683031ea93`**,
  parent `0a42b609`, pesan "vc330 fix bocor sesi: delta verifikasi +
  delta ekstraksi + katup senyap (RUST_CORE_VERSION 10, suite
  2964/2965, P16 64/64)".
- Verifikasi pasca-push: HEAD remote = `ee9155e9`;
  **513/513 file lokal SHA-nya cocok dengan tree remote**
  (0 mismatch); 10/10 file delta hadir di remote (9 berubah +
  1 baru). Dokumen BUILD-VC330.md + TES-VC330-SIAP-TEMPEL.txt TIDAK
  ikut push utama (doc-sync terpisah, §8.5). Staging
  `work-impl/push-vc330/` DIHAPUS sesudah push terverifikasi.
- Ronde 2 (wiring P16 di workflow): `gh.py put-file` ke
  `.github/workflows/android.yml` + paragraf vc330 di header workflow
  → commit `2f3646cbf5caf86b97e03c91fe92af173767daa6`
  (parent `ee9155e9`).
- Ronde 3 (kutip nama step P16): `gh.py put-file` ke
  `.github/workflows/android.yml` (hanya baris 195; YAML parse bersih,
  36 steps) → status 200, blob sha
  `08c8c6919696c8e7f53df28ee1d75a63226ac344` == `git hash-object` lokal;
  commit **`a9220690d9cd82d2e8e65a6daa6906ecdb339691`**
  (parent `2f3646cb`).
- Ronde 4 (fix path relatif P16): `gh.py put-file` ke
  `tests/TestSessFixVc330.kt` — SATU file, `main()`:
  `val tree = File(...).absoluteFile` + komentar penjelas (diff remote
  vs lokal pra-push HANYA blok itu); status 200, blob sha
  `f6d85259332d254b15c0c14e3f5726b6c6361ff4` == `git hash-object`
  lokal; commit **`141e593d71128f4e129fc460ae4789a8d700fe2f`**
  (parent `a9220690`), pesan "vc330 ronde 4: absolutkan tree di
  TestSessFixVc330 (findUp tahan path relatif CI, cek 5.12)".
  Main TIDAK tersentuh di semua ronde (tetap `2e6245e3` = vc309
  4.48.9.143, 2026-11-13).
- Repro + bukti lokal ronde 4 (log `work-vc330/`): sebelum fix, P16
  TREE relatif → 63/64, GAGAL 5.12 (identik CI run 3,
  `p16-repro-rel-r4.log`); sesudah fix, P16 TREE relatif → 64/64
  (`p16-fix-rel-r4.log`) dan TREE absolut → 64/64
  (`p16-fix-abs-r4.log`). Suite penuh lokal (run-suite-ci.sh ALL):
  **2964/2965** (satu-satunya gagal = R2-4.1 usang, ditoleransi;
  section C gagal lokal karena branch-stage bukan checkout git —
  artefak lokal, C lulus di CI); D1–D20 semua LULUS; cargo test
  397/397 (`cargo-test-vc330-r4.log`, `suite-r4.log`).

### §8.2 CI

- Run 1: **44008128853** —
  https://github.com/Sendysyah27/deepseek-harness/actions/runs/44008128853
  (workflow `android.yml`, branch `vc317-rust`, head `ee9155e9`,
  event push, dibuat 2026-11-17T03:09:08Z).
- **Status: SUCCESS** (completed, conclusion `success`,
  ~9,7 menit, selesai 03:18:48Z). Satu job `build` — semua
  step hijau, build senz+pure, Verify APKs.
- **TEMUAN (jujur, penting): run 1 TIDAK menjalankan P16** — log job
  (`work-impl/ci-vc330-job-log.zip`, 452.825 byte) tidak mengandung
  "P16"/"TestSessFixVc330" sama sekali. Penyebab: `android.yml`
  memanggil section satu-per-satu dan langkah P16 belum ditambahkan
  (wiring P16 hanya di `run-suite-ci.sh`). D20 LULUS di CI (fix vc330
  hadir + RUST_CORE_VERSION 10 + Flavor/MemoryDb/GroupChat/BgBrain/
  Rust tanpa vc330), tapi 64 cek P16 belum dieksekusi CI.
  → Perbaikan wiring: step P16 ditambahkan ke `android.yml`
  (model step P15) + paragraf vc330 di header workflow — push ke-2
  commit `2f3646cbf5caf86b97e03c91fe92af173767daa6`, run CI ke-2
  44013397588.
- Ronde 1 langsung hijau (0 ronde perbaikan kode).
- **Run 2: 44013397588 — FAILURE (merah ke-1, head `2f3646cb`,
  dibuat 2026-11-17T03:54:03Z).** Run gagal INSTAN (created ==
  updated, 0 job) — workflow tidak sempat jalan. Akar (terverifikasi
  dari file remote): nama step P16 yang saya tulis mengandung `:`
  tanpa kutip — `Suite P16 SessFix vc330 (64 cek: delta verifikasi
  + delta ekstraksi + katup senyap)` — YAML menolaknya
  (`ScannerError: mapping values are not allowed here`, baris 195,
  kolom 46; diverifikasi dengan `yaml.safe_load` pada file remote
  maupun branch-stage). BUKAN kegagalan kode/suite — workflow-nya
  sendiri tidak valid. Perbaikan yang diusulkan (MENUNGGU PERINTAH
  KOORDINATOR, tidak diterapkan sepihak): beri kutip pada nama step
  (`"Suite P16 SessFix vc330 (64 cek: ...)"`) atau hapus `:` dari
  nama, lalu push ke-3 + CI ke-3. Bila CI ke-3 merah lagi di step
  yang sama → BERHENTI (aturan 2x merah).
- **Run 3: 44013789415 — FAILURE (merah ke-1 di step P16, head
  `a9220690`, dibuat 2026-11-17T03:57:31Z, selesai 04:02:08Z,
  ~4,5 menit).** Job `build` (133806746372) berjalan; P0–P15 SEMUA
  lulus; step P16 BENAR-BENAR dieksekusi (log baris 4706) —
  satu-satunya gagal: P16 cek 5.12 "RUST_CORE_VERSION tetap 10" →
  **63/64**. Step sesudah P16 (N9..D, Build APK) tidak sempat jalan;
  artifact run 3 = KOSONG. Log:
  `work-vc330/ci-vc330-run3-job-log.txt` (411.952 byte, 4815 baris).
  Akar (terkonfirmasi dari kode, BUKAN regresi Rust/kode vc330):
  `run-suite-ci.sh:18` TREE relatif
  (`app/src/main/java/com/senzwh/deepharness`); `findUp` di
  `TestSessFixVc330.kt:115` menelusuri `parentFile` — dari path
  relatif rantai berhenti di `app` dan tidak pernah mencapai repo
  root → `jniSrc = ""` → cek 5.12 gagal. Lokal lulus 64/64 karena
  dijalankan dengan tree ABSOLUT. Rust TIDAK berubah:
  `harness-core/src/jni_glue.rs:68` tetap
  `pub const RUST_CORE_VERSION: jint = 10;`. P13/P15 tidak pakai
  `findUp` (0 kemunculan) — itu sebabnya mereka lulus di CI.
- **Run 4: 44017178012 — SUCCESS (HIJAU, head
  `141e593d`, dibuat 2026-11-17T04:26:18Z, selesai ~04:38:21Z,
  ~12 menit).** https://github.com/Sendysyah27/deepseek-harness/actions/runs/44017178012
  Satu job `build` (133817781858) — semua step hijau. Fix ronde 4
  (absolutkan tree di tes, §8.1) terbukti di CI: **P16 64/64**
  (log baris 4785 `LULUS 5.12 RUST_CORE_VERSION tetap 10`, baris
  4795 `HASIL: 64 lulus, 0 gagal (total 64)`); **0 baris `GAGAL`
  di seluruh log** (5366 baris). Tally CI run 4: P1 103, P2 36,
  P3 94, P4 235, P5 55+102=157, P6 75+80=155, P7 52, P8 73,
  P9 107+27=134, P10 88+58=146, P11 151+151=302, P12 86+86=172,
  P13 66, P14 140+140=280, P15 312+296=608, P16 64, N9 46, BR 45,
  IXL 72, R1 64, R2 68/69 → **2972/2973, satu-satunya gagal =
  R2-4.1 usang (ditoleransi, TOLERANSI tercatat di log)**. Cargo
  test CI (`cargo test --locked`): **397 passed, 0 failed**.
  C LULUS di CI; D1–D20 SEMUA LULUS (D20 fix vc330 ada +
  RUST_CORE_VERSION 10). Build APK jalan (senz + pure, §8.3).
  Log: `work-vc330/ci-vc330-run4-job-log.txt` (462.107 byte,
  5366 baris).
- CATATAN JUJUR (selisih hitungan P10, sudah ada sejak run 3,
  BUKAN efek ronde 4 — 0 gagal di kedua sisi): mode kotlin P10
  di CI = 58 cek, lokal = 50 cek. Selisih 8 bersih: CI punya
  9 cek §3/§4 ekstra (3.1, 3.3, 3.5, 3.7, 3.9, 3.11, 3.14, 4.3,
  4.4), lokal punya 1 cek ekstra (3.16). File P10 tidak disentuh
  ronde 3–4. Total CI 2973 vs lokal 2965 berasal dari selisih ini.
- Doc-sync (§8.5) DITAHAN sampai ada CI hijau pasca-perbaikan —
  push dokumen di atas commit workflow-rusak hanya akan memicu
  run merah lagi. **Run 4 HIJAU → doc-sync DILAKUKAN ronde 4
  (§8.5).**

### §8.3 Artifact CI (`vc317-work/apk-vc330-ci/`)

- Run 4 (FINAL — run 1 sudah digantikan; artifact run 3 KOSONG karena
  job berhenti di P16): `harness-vc317-apks` (artifact id 14300170845,
  29.356.417 byte, zip SHA256
  `6a1ef21aae6b18738ac7a64327cab4e668f679d85f7172916f0432e0c39421fb`):
  `DeepSeekHarness-v4.48.9.164.apk` (14.802.028 byte) +
  `Harness-v4.48.9.164-pure.apk` (14.752.876 byte).
  Zip diunduh + diekstrak ke `vc317-work/apk-vc330-ci/` (subfolder
  `extracted/`); hash APK hasil ekstrak COCOK dengan yang tercatat
  di log CI.
- Main: MD5 `4267bf08f032f8caf812d48ea966c349`,
  SHA256 `652757c9cdba9f8802658848f0cc261ea3d2653da12d3774d6a7643da0e51d3e`;
  Pure: MD5 `d063ee018605ce000c51cb3b52f749da`,
  SHA256 `8914d17b284d769a2419b1c250bea7dd8372386c67ee6d83dbb084c4ca1f3417`.
- aapt2: keduanya 330/4.48.9.164; apksigner Verifies, v2+v3 true,
  v1/v3.1/v4 false, 1 signer masing-masing;
  `libharness_core.so` 4.329.280 byte di `lib/arm64-v8a`
  (profil release — SAMA PERSIS dengan vc329 CI, konsisten: tidak
  ada perubahan Rust); `hsurr:` 0 (di dex; .so tidak diperiksa
  ulang — sama dengan run 1).
- Sidik jari CI (EKSPEKTASI beda — kunci runner, bukan kunci VM):
  Main SHA-256 `d53a9ea1626bd3b7a29467895d3f9915eb494cfb2a65986ab5e4b29f45b85a5d`,
  Pure SHA-256 `784332be0087ecfe2b5d4535f5bb3d0d0600a53377340b3900a8ac88f1dd8132`.
- (Arsip run 1: artifact id 14296625009, 29.356.408 byte; Main MD5
  `5400551671563e0d8dacdaa2e5742114`, SHA256
  `93e41b40919443a6c68da842d04f58645e68ade90acb28d3c6c7469180709472`;
  Pure MD5 `9b2af693915731c30e0937fd6fba1623`, SHA256
  `31f42d634527d54358fdcc2fc197ec43272b58a2cd97c3ef29f604d3213ccce1`;
  sidik jari Main
  `ecdabb3c533a1158bbd227c034dbd541104ea67f8fdc334ad76f6708c9b81109`,
  Pure
  `bded46459a2e5052a53b6df512c893cbda0a2c9da905dc77ee9e2b038335d31d`.)

### §8.4 Build VM (`vc317-work/apk-vc330-vm/`, JH `~/jdk-21-sys`,
langkah Rust rust-lld + CC 0r; clang NDK hidup 21.0.0 —
TIDAK perlu restore dari zip cache)

- Main `DeepSeekHarness-v4.48.9.164.apk` 14.986.348 byte,
  MD5 `a74c6e1802c39232f73c7df99a50bc41`,
  SHA256 `8d784f9a74657d69b551af96e5371902713de00329b295ea5ed536e1e91e09fe`,
  **sidik jari `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`
  — GERBANG COCOK (sama vc316, bisa menimpa)**.
- Pure `Harness-v4.48.9.164-pure.apk` 14.937.196 byte,
  MD5 `72886b47c35f69d46a5f435a22d0a052`,
  SHA256 `6acfb645d7ada641af342f99414b03da6cc62358d97bfc5e748e1e1a633e8e79`,
  **sidik jari `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`
  — GERBANG COCOK (sama vc316, bisa menimpa)**.
- Keduanya: apksigner Verifies, v2+v3 true, 1 signer,
  aapt2 330/4.48.9.164, `libharness_core.so` 4.911.312 byte
  di `lib/arm64-v8a` (SAMA PERSIS dengan vc329 VM — tidak ada
  perubahan Rust), `hsurr:` 0 (.so maupun dex),
  arm64-v8a saja, 33 simbol JNI (dihitung dari host .so —
  .so APK ter-strip, `nm`/`strings` 0; set identik vc329,
  termasuk 4 bg_room `bgBrainJson`/`groupChatJson`/`dsAgentJson`/
  `ocCoordJson`, TIDAK ada simbol baru).
- Log build: `build-senz.log`, `build-pure.log` (di folder yang sama).

### §8.5 Dokumen akhir

**SELESAI (ronde 4, sesudah CI run 4 hijau):** doc-sync push
(BUILD-VC330.md + TES-VC330-SIAP-TEMPEL.txt ke branch `vc317-rust`,
pola vc329 — masing-masing `gh.py put-file` ke root repo) dilakukan
sesudah run 4 hijau 2026-11-17 ~04:40 WIB. §8.2 diisi fakta run 3
(merah P16 5.12, 63/64) + run 4 (hijau, P16 64/64 di CI); §8.3
diisi artifact run 4. Dokumen di `vc317-work/BUILD-VC330.md` (+
salinan di `branch-stage/BUILD-VC330.md`) dan
`vc317-work/TES-VC330-SIAP-TEMPEL.txt` (+ salinan di
`branch-stage/TES-VC330-SIAP-TEMPEL.txt`); keduanya DIKECUALIKAN
dari push utama ronde 1–4 (terverifikasi: tidak ada di remote
sebelum doc-sync ini — 404 di root repo).

## §9. Tes perangkat (TES-VC330-SIAP-TEMPEL)

- File: `vc317-work/TES-VC330-SIAP-TEMPEL.txt` (bahasa bayi).
- Ulangan persis kejadian 17 Nov 09:07: "say hello bro" → "cek skill
  lists" → satu perintah tool lain, di "Utama" slot Browser 2.
- LULUS bila: 0 notifikasi "wadah baru" + JEJAK SESI SERVER tidak
  menunjukkan reset + tabel skill tetap tampil + verdict verifikasi
  tetap muncul (atau catatan "dilewati" bila wadah penuh).
