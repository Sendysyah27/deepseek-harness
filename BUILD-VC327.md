# BUILD-VC327 — Fix Residual Bocor Sesi Server + input_exceeds_limit (vc327, 4.48.9.161)

Dokumen kerja integrator fix residual (keputusan koordinator 17 Nov
01:37 WIB — residual vc325: kartu merah `input_exceeds_limit` di lane
slot/Browser 2 + sesi server baru tetap lahir di vc325 padahal sesi
Harness sama). Ini BUKAN slice 11 (split DsApiClient = vc328) dan
BUKAN slice 12 (otak latar/room = vc329) — keduanya sesudah fix ini
mendarat. Pola dokumen mengikuti BUILD-VC322.md.
Probe: `vc317-work/work-sess327/probe/ProbeSess327.kt` (log
`probe-sess327.log`, + 6 snapshot sumber di direktori yang sama).

## §1. Keputusan versi

- **vc327, VERSION_CODE 327, VERSION_NAME 4.48.9.161**, branch `vc317-rust`
  (remote main tetap vc309 — JANGAN disentuh).
- `RUST_CORE_VERSION` TETAP 8, `RustCore.VERSION` tetap 8 — fix ini tidak
  menambah fungsi JNI baru (semua logika baru Kotlin murni; Rust tidak
  berubah — cargo test tetap 344/344).
- ARM64-only. `Flavor.kt` TIDAK disentuh (juga `GroupChat.kt`,
  `BgBrain.kt` — 0 marker vc327 di ketiganya).
- Perubahan perilaku HANYA yang diperlukan fix residual:
  migrasi primed satu kali + tahan forceFresh (cooldown 10 mnt) +
  retry stream 3x + reset accum di cabang create + delta auto-save +
  delta continuation. Reviewer (`BgBrain`) + H4 (session-invalid) +
  Governor + `parent_message_id` NULL TIDAK berubah.
- Keystore VM lama dipertahankan; gerbang sidik jari vc316 berlaku:
  Main `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`,
  Pure `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`.

## §2. Ukur dulu + desain fix (probe JVM — SEBELUM patch)

Probe `vc317-work/work-sess327/probe/ProbeSess327.kt` (log
`probe-sess327.log`), verifikasi §0 dari snapshot vc322 + ukuran
runtime dari template sumber. Ringkasnya:

### §2.1 Masalah (bukti live Sendy, vc325/4.48.9.159, chat "Utama" 1-1)

- 00:13 WIB: agen jawab normal (panjang, trigger skill #6 + #8),
  lalu kartu merah `Browser 2 gagal: stream_terputus ...
  {type:error, content:Content is too long. Please shorten it and
  try again., clear_response:true, finish_reason:input_exceeds_limit}`.
  Jawaban utama lolos; langkah lanjutan jalur slot gagal IXL +
  stream putus.
- 00:15 WIB: "sesi baru terbuat lagi di akun deepseek nya" padahal
  "sesi harness masih sama" — sesi server baru TETAP lahir di vc325.
  Fix vc322 tidak menahan kasus live ini. Laporan JEJAK SESI SERVER
  dari Sendy (untuk membedakan H2 vs H3) masih DITUNGGU saat fix
  ini dikerjakan — fix menutup KEEMPAT hipotesis sekaligus, bukan
  menunggu laporan.

### §2.2 Mekanisme (terverifikasi ulang dari kode vc325 — re-verifikasi sebelum patch)

- Routing: "Utama" 1-1 → DsApiClient (`sendToAgent`
  MainActivity.kt:11520, slotEff null → `apiClient.chat`); lane
  slot/Browser N → SlotClient. Kedua klien cerminan logika sama.
- **H1 PRIMED PASCA-UPGRADE — TERBUKTI, TERBATAS 1 sesi per (akun,
  sesi harness):** `isServerSessionPrimedVc322` default false bila
  kunci tak ada (TurnMetrics.kt:271). Sesi dari vc321 punya stored +
  accum tapi TANPA kunci primed → giliran pertama vc322 kirim PENUH;
  bila accum lama + penuh > 200.000 char → reset proaktif → 1 sesi
  baru; sesudah POST penuh sukses primed=true.
- **H2 forceFresh SESUDAH STREAM PUTUS — TERBUKTI, TAK TERBATAS,
  TERIKAT SINYAL (kandidat utama bocor berulang):** `chat()` cut →
  retry 2x di sesi sama (2 dtk, 5 dtk) → gagal lagi →
  `runChat(forceFresh=true)` → sesi baru (DsApiClient.kt:58-84,
  SlotClient.kt:112-129; forceFresh lewati stored sepenuhnya,
  DsApiClient.kt:186-187). Sinyal 4G lemah → 1 sesi baru per episode
  putus.
- **H2-CASCADE (TEMUAN BARU):** cabang sesi-baru/forceFresh TIDAK
  me-reset accum — `resetServerAccumVc321` hanya ada di 2 tempat per
  klien: jalur guard-reset (DsApiClient.kt:223, SlotClient.kt:234) +
  cabang pengganti invalid (DsApiClient.kt:305, SlotClient.kt:307).
  Sesudah forceFresh: accum lama (~150k) + prompt penuh sesi baru
  (~90k) menumpuk di kunci sama → giliran PENUH berikutnya hampir
  pasti kena guard reset → sesi baru KEDUA.
- **H3 GUARD RESET DI GILIRAN PENUH — TERBUKTI, TAK TERBATAS:**
  dispatchTurn auto-save ("ingat:") memaksa delta=null
  (MainActivity.kt:11802-11803, P8 §5.4 mengunci) → penuh ~90k →
  ~2 auto-save = reset → sesi baru. `sendContinuation` kirim
  deltaPrompt=null → penuh di KEDUA lane; verifikasi/rescue/latar
  juga null.
- **H4 SESSION-INVALID — TERBUKTI tapi SEMPIT + SUDAH BENAR, TIDAK
  DIUBAH:** `isSessionInvalidSignal` (DsFailure.kt:105-110) hanya
  cocok session_not_found/invalid_session/not found/invalid/not
  exist/deleted/no such. `input_exceeds_limit` & `stream_terputus`
  TIDAK cocok.
- **UNDERCOUNT (penguat kartu merah, bukan penyebab bocor):**
  `addServerAccum` hanya hitung prompt terkirim, bukan panjang
  respons — guard optimistis → server bisa IXL duluan.

### §2.3 Ukuran prompt (runtime dari template sumber)

| Potongan | Char |
|---|---:|
| Anchor "cek skill lists" | 503 |
| Delta normal (vc322) | 557 |
| Blok auto-save tipikal (ekstraksi 669; probe 671 incl. `\n\n` akhir) | 669 |
| Blok auto-save panjang (dicap) | 2.028 |
| **Delta auto-save tipikal (557 + 2 + 669)** | **1.228** |
| Delta auto-save terburuk | 2.587 |
| Prompt PENUH tipikal | 90.500 |
| Lanjutan (continuation) tipikal | ~12.003 |
| Lanjutan terburuk | ~39.503 |

Delta auto-save tipikal menghemat ~89.270 char/giliran vs penuh;
terburuk ~87.913 char/giliran.

### §2.4 Angka SEBELUM fix (rezim vc322 — dikunci P13 §4, simulasi mesin runChat + fungsi asli)

| Skenario | Giliran | Sesi baru |
|---|---:|---:|
| Auto-save PENUH tiap giliran, P=90.500 (K1) | 10 | 5 |
| Auto-save PENUH tiap giliran, P=90.500 (K1b) | 30 | 15 |
| Upgrade primed=false + accum 150k (K2) | 1 | 1 |
| 4 episode forceFresh, giliran 3/5/7/9 (K3) | 10 | **7** (4 ff + 2 reset cascade) |
| CASCADE: 1 ff + auto-save penuh (K3b) | 6 | 3 (2 sesi baru/episode) |
| Delta + 2 lanjutan 12,5k/giliran (K4) | 10 | 2 (1 reset, accum 127.228) |

### §2.5 Angka SESUDAH fix (desain target — dikunci P13 §4)

| Skenario | Giliran | Sesi baru |
|---|---:|---:|
| Auto-save DELTA, P=90.500 (L1) | 10 | **1** |
| Auto-save DELTA, P=90.500 (L1b) | 30 | **1** |
| Auto-save DELTA terburuk (L1c) | 30 | **1** |
| Upgrade + migrasi primed (L2) | 1 | **0** |
| 4 episode forceFresh + cooldown (L3) | 10 | **3** (2 ff + 2 ditahan + 2 gagal) |
| ANTI-CASCADE ff + auto-save DELTA (L4) | 6 | **2**, 0 reset |
| Lanjutan guard (L4b) | 10 | 2, 1 reset, accum 127.228 — IDENTIK K4 |
| Delta normal 30 giliran (regresi) | 30 | 1 |
| Guard IXL tetap (regresi) | — | 1 reset |
| Invalid 1x (regresi) | — | 2 sesi (pengganti tidak diubah) |

### §2.6 Keputusan fix (dari ukuran, bukan asumsi)

1. **Migrasi primed satu kali** (`TurnMetrics.migratePrimedOnceVc327`):
   stored != null + accum > 0 → primed=true. Aman: accum > 0 hanya
   mungkin sesudah POST penuh sukses ke wadah ini (`addServerAccum`
   hanya sesudah POST sukses; POST pertama ke wadah selalu penuh
   karena delta butuh primed). Stored + accum==0 → TIDAK migrasi
   (wadah dibuat tapi POST pertama belum pernah sukses → giliran
   berikut tetap penuh). Dicatat sekali ke JEJAK SESI SERVER per
   wadah saat flip.
2. **Tahan forceFresh — cooldown 10 menit** per (lane, sesi harness):
   `FORCE_FRESH_COOLDOWN_MS_VC327 = 600.000`; kunci DsApiClient
   `ffVc327_ds_<harnessNo>` (bgMode → -100), SlotClient
   `ffVc327_slot<slot>_<harnessNo>`. Maks 1 forceFresh per 10 mnt;
   episode dalam jendela GAGAL dengan pesan jelas (tidak diam-diam
   membakar sesi). Timestamp dicatat SEBELUM percobaan forceFresh
   (sesi terbakar begitu `createServerSession` jalan, walau
   completion sesudahnya gagal). Jam mundur (`nowMs < last`) →
   allow=true (jangan kunci pengguna karena jam bergerak).
   Gate HANYA di fallback stream-cut `chat()` kedua klien — BUKAN
   di `runChat` — supaya reviewer (yang memanggil dengan
   `forceFresh=true` langsung dari `BgBrain.kt:186`) tidak tersentuh.
   Gate dilewati bila `reviewer==true`; `noteForceFresh` juga
   dilewati untuk reviewer (reviewer berbagi kunci bgMode -100;
   mencatatnya akan meracuni cooldown job latar).
3. **Tangga retry stream: 2 → 3x untuk non-reviewer** (tunggu
   [2 dtk, 5 dtk, 15 dtk]); reviewer tetap [2 dtk, 5 dtk] (retry
   reviewer memakai forceFresh=true sehingga tiap retry membakar
   wadah baru — jalur reviewer harus tetap byte-identik perilakunya;
   reviewer di luar lingkup, preseden BUILD-VC322 §7.6).
4. **Reset accum di cabang create** (anti-cascade, samakan dengan
   cabang invalid): DsApiClient selalu; SlotClient hanya
   `if (!freshEphemeral)` (ephemeral berbagi kunci accum agen —
   me-reset akan menghapus accum agen). Reviewer SlotClient memakai
   reviewerSessKey sendiri, jadi reset di sana benar.
5. **Delta untuk auto-save** (`AgentPrompt.autoSaveBlockVc327` +
   `deltaTurnPromptVc327`): blok diekstrak dari promptNoUser lewat
   `]\n\n` pertama sesudah `[SISTEM — AUTO-SAVE vc298` (baris memakai
   `[STATUS:...]` + `\n` tunggal, jadi tidak terpotong dini), dicap
   2.000 char + penanda `…(dipotong, sisa N char)…`. Prompt penuh
   tetap dikirim bila belum primed, jadi konfirmasi simpanan ke user
   tidak pernah hilang. Bila blok null, delta vc327 == delta vc322
   persis.
6. **Delta untuk continuation:** `sendToAgent(finalTxt, finalTxt)` —
   no-op jujur hari ini (lihat §7: guard-neutral, K4==L4b),
   diimplementasikan sesuai arahan + dikunci tes (P13 §5.4).
7. **H4 TIDAK DIUBAH. Undercount TIDAK DIUBAH** (menghitung respons
   akan membuat guard lebih konservatif → lebih banyak reset →
   lebih banyak sesi — berlawanan dengan tujuan anti-bocor; rezim
   delta sudah mengecilkan akumulasi per-POST di server).

### §2.7 Trade-off

- **Cooldown menahan sesi baru dengan harga: giliran yang ditahan
  GAGAL** (bukan diam-diam sukses di sesi lama). Pengguna melihat
  pesan jelas `[sesi server baru ditahan vc327: stream putus
  berulang, sesi baru terakhir <10 mnt lalu — coba lagi sesudah
  jeda / periksa sinyal]` dan bisa coba lagi sesudah jeda. Ini
  trade-off yang disengaja: membakar sesi tiap episode putus adalah
  pola yang dulu bikin akun suspend; gagal-terang lebih aman
  daripada bakar-diam-diam.
- **Delta auto-save membawa blok simpanan verbatim (dicap 2k).**
  Bila blok sangat panjang, sisanya dipotong dengan penanda —
  konfirmasi ke user tetap ada (prompt penuh saat belum primed +
  blok terpotong tetap memuat instruksi + awal isi).
- **Migrasi primed mengandalkan accum > 0 sebagai bukti POST penuh
  sukses.** Bukti ini kuat karena satu-satunya penulis accum adalah
  `addServerAccum` sesudah POST sukses; tidak ada jalur lain yang
  menaikkan accum.
- **Governor tetap dihitung dari prompt penuh** (konservatif — kuota
  tidak berubah). Guard reset vc321 + `DEFAULT_CAP` 200.000 +
  riwayat adaptif TIDAK berubah. `parent_message_id` tetap NULL.
- **Sesi yatim lama tidak bisa dihapus** (tidak ada endpoint) —
  yang diperbaiki adalah LAJU kelahiran sesi baru, bukan membersihkan
  yang sudah menumpuk.

## §3. Basis verifikasi (langkah 0 — semua LULUS sebelum patch)

- Live app tree == branch-stage app tree (diff exit 0); live crate
  == branch-stage crate (diff exit 0, excl. target/).
- `cargo test --offline` di live `harness-core`: **344/344 passed,
  0 failed**.
- NDK clang hidup: Android clang 21.0.0. RAM ~1.401 MB saat cek
  (ketat; cargo dan kotlinc TIDAK dijalankan bersamaan).
- Remote HEAD saat spawn: `vc317-rust` = `25b53acb` (vc326); remote
  `main` = `2e6245e3` (vc309, tidak disentuh). Doc-sync vc326
  mendarat sesudah spawn → HEAD saat push HARUS dicek ulang
  (lihat §8).

## §4. Isi fix (file yang berubah)

Patch Kotlin saja — 5 file (semuanya sudah ada di set berubah
vc326, jadi C lokal tetap 32 berubah + 4 baru):

### TurnMetrics.kt — blok baru vc327 sesudah `setServerSessionPrimedVc322`
- `migratePrimedOnceVc327(ctx, key, hasStoredSession, accumChars): Boolean`
  (kembalikan primed efektif; migrasi hanya bila !primed && stored
  && accum>0).
- `FORCE_FRESH_COOLDOWN_MS_VC327 = 10*60*1000L`;
  `KEY_LAST_FF_VC327 = "lastForceFreshVc327_"`;
  `lastForceFreshAtVc327`, `noteForceFreshVc327`,
  `allowForceFreshVc327` (true bila belum pernah / sudah lewat
  cooldown / jam mundur).

### AgentPrompt.kt — sesudah `deltaTurnPromptVc322`
- `autoSaveBlockVc327(promptNoUser): String?` (ekstrak blok, cap
  2000 + penanda sisa).
- `deltaTurnPromptVc327(userCommand, workNotes, autoSaveBlock): String`
  (delta vc322 + blok bila ada; identik dengan delta vc322 bila
  blok null).

### DsApiClient.kt
- `chat()` cabang stream-cut: tunggu `if (reviewer) [2s,5s] else
  [2s,5s,15s]`; fallback hitung `cutNvc327` (2 reviewer / 3
  non-reviewer), `ffKeyVc327`; bila `!reviewer &&
  !allowForceFreshVc327` → logSessEvent "Wadah PENGGANTI DITAHAN
  vc327 …" + lempar error terakhir + suffix pesan ditahan;
  bila lolos → `noteForceFreshVc327` (non-reviewer saja) + onPartial
  "Stream putus ${cutNvc327}x …" + `runChat(... forceFresh = true,
  ...)` (string panggilan persis dipertahankan untuk P8 6.5).
- `runChat`: blok migrasi sesudah baca `accumVc321` (cek wasPrimed
  + `migratePrimedOnceVc327` + log JEJAK saat flip);
  `shouldSendDeltaVc322` kini memakai `primedVc327` (bukan baca
  langsung `isServerSessionPrimedVc322`).
- Cabang create: `TurnMetrics.resetServerAccumVc321(ctx,
  accumKeyVc321)` tepat sesudah `storeServerSession` (anti-cascade).
- Penamaan "stream putus 2x" → "stream putus berulang" di komentar
  fallback, komentar runChat, dan log JEJAK forceFresh. Tidak ada
  lagi "putus 2x" di kedua klien.

### SlotClient.kt — cermin DsApiClient
- Baris JEJAK ber-prefix slot, `ffVc327_slot<slot>_<harnessNo>`;
  reset di cabang create dijaga `if (!freshEphemeral)`; class-doc
  diperbarui (cap 10 mnt).

### MainActivity.kt
- `dispatchTurn`: cabang auto-save yang memaksa null diganti
  `val deltaVc322 = AgentPrompt.deltaTurnPromptVc327(rawUser,
  AgentPrompt.workNotesBlockVc302(this, currentSessionId),
  AgentPrompt.autoSaveBlockVc327(promptNoUser))` (nama variabel
  dipertahankan `deltaVc322` supaya P8 5.3 tetap lulus) + komentar
  vc327.
- `sendContinuation` akhir: `sendToAgent(finalTxt)` →
  `sendToAgent(finalTxt, finalTxt)` + komentar yang menjelaskan
  guard-neutrality (probe K4==L4b).
- Komentar doc `sendToAgent` diperbarui (dispatchTurn +
  sendContinuation kini memberi delta; verifikasi/background/rescue
  tetap default null = penuh; WebView selalu penuh).

Verifikasi sesudah patch: 57 marker `Vc327`/`vc327` di 5 file;
reset-accum ada di cabang create kedua klien (DsApiClient create:269,
SlotClient create:280 dengan guard `!freshEphemeral`); reset
guard + cabang invalid tidak berubah (3 situs
`resetServerAccumVc321(ctx, accumKeyVc321)` per klien — catatan:
di DsApiClient, situs ke-3 cabang invalid memakai
`accumKeyFreshVc321` (kunci sidik token saat ini), lihat §7 ronde 1).

### Yang sengaja TIDAK diubah
- `DsFailure.kt` (H4), `Flavor.kt`, `GroupChat.kt`, `BgBrain.kt`
  (0 marker vc327), `TurnMetrics` selain blok vc327, guard/reset
  vc321, `DEFAULT_CAP`, riwayat adaptif, Governor,
  `parent_message_id`, jalur reviewer, `RUST_CORE_VERSION` (8),
  crate Rust (tidak ada perubahan).

## §5. Tes paritas baru — P13 TestSessFixVc327 (66 cek)

File baru `vc317-work/branch-stage/tests/TestSessFixVc327.kt`
(memakai ulang `FakeCtxVc322`/`FakePrefsVc322` +
`tests/stubs/android/content/Context.java`):

- §1 builder delta auto-save runtime (12 cek): ekstraksi, panjang
  eksak 669 / 2028-cap / delta 1228 tipikal / 2587 terburuk,
  tanpa-blok == delta vc322.
- §2 migrasi primed via FakeCtx (7 cek).
- §3 cooldown 10 mnt via FakeCtx (10 cek, incl. batas eksak +599 dtk
  false / +600 dtk true, jam mundur, independensi kunci).
- §4 simulasi dengan fungsi TurnMetrics ASLI + FakeCtx prefs,
  `SessSim327` dengan flag `regime327` yang memodelkan alur runChat
  vc322 dan vc327 (18 cek — mereproduksi angka probe K1/K1b/K2/K3/
  K3b/K4 dan L1/L1b/L1c/L2/L3/L4/L4b/L5/L5b/L5c + batas cooldown
  4.17/4.18).
- §5 struktur sumber (15 cek): helper ada, kedua klien memakai
  migrasi + gate + reset create, reviewer tidak digate, H4 tidak
  berubah (scoped ke badan fungsi), pengganti invalid tetap penuh,
  guard/cap tidak berubah, situs kelahiran tetap 2/klien, hsurr: 0,
  Flavor/GroupChat/BgBrain bersih, RUST_CORE_VERSION tetap 8.
- §6 regresi (4 cek).

P8 `TestSessFixVc322.kt` diperbarui (perubahan perilaku yang
disengaja, preseden IXL 7.5/7.6 vc321): 5.4 ditulis ulang mengunci
perilaku delta auto-save baru; 6.2 ditulis ulang mengunci
`sendToAgent(finalTxt, finalTxt)` (non-API `sendToAgent(text)`
tidak berubah); nama cek 6.5 diperbarui jadi "stream putus berulang
… digate cooldown" (assert tidak berubah).
`TestDsFailureVc316.kt` diperbarui: 5.7 diperkuat mewajibkan
`longArrayOf(2_000, 5_000)` (lengan reviewer) DAN
`longArrayOf(2_000, 5_000, 15_000)` (lengan non-reviewer); nama 5.8
diperbarui jadi "putus berulang (vc327: maks 1 per 10 mnt)" (assert
tidak berubah — string `forceFresh = true, bgMode = bgMode` +
"coba SATU sesi server baru" dipertahankan di kode).

## §6. Angka tes

- cargo test --offline (live `harness-core`, Rust tidak berubah):
  **344/344 passed, 0 failed**.
- Suite lokal penuh — RONDE 1 (`work-impl/suite-vc327.log`,
  19:23 WIB; log diarsipkan `suite-vc327-r1.log`):
  **2012 lulus, 1 gagal (total 2013)** — aritmetik dari log:
  P1 103 + P2 36 + P3 94 + P4 235 + N9 46 + BR 45 + IXL 72 + R1 64
  + R2 68/69 + P5 157 (102+55) + P6 155 (80+75) + P7 52 + P8 73
  + P9 134 (107+27) + P10 138 (88+50) + P11 302 (151+151) + P12 172
  (86+86) + **P13 66/66** = 2012. Satu-satunya gagal = R2-4.1
  (assert STRING usang warisan vc315 — ditoleransi, preseden vc326).
  C LULUS (tepat 32 berubah + 4 baru; Flavor/DsFailure tidak
  berubah). D1–D15 + D17 LULUS; **D16 GAGAL di ronde 1** — cacat
  runner (bukan kode produk), lihat §7. OVERALL FAIL=1 (hanya
  karena R2-4.1 + D16).
- Suite lokal penuh — RONDE 2 (`work-impl/suite-vc327.log`,
  19:36 WIB; log lebih baru dari runner 19:23):
  **2012 lulus, 1 gagal (total 2013)** — aritmetik sama seperti
  ronde 1 (P13 66/66 in-suite; D1–D17 SEMUA LULUS incl. D16 yang
  diperbaiki). Satu-satunya gagal = R2-4.1 (ditoleransi).
  OVERALL FAIL=1 hanya karena exit code R2-4.1 (runner lokal tidak
  punya cabang toleransi seperti CI — sama seperti log vc326 yang
  juga berakhir FAIL=1).

## §7. Deviasi / temuan jujur

- **Continuation guard-neutral (BUKAN penghematan byte):** delta
  untuk continuation menghasilkan string yang SAMA dengan prompt
  penuh yang dikirim sebelumnya (K4==L4b: 2 sesi, 1 reset, accum
  127.228 — identik di vc322 dan vc327). Nilainya adalah
  klasifikasi eksplisit + kunci P13, bukan perubahan byte/guard.
  Dicatat jujur, bukan diklaim sebagai penghematan.
- **Undercount respons TIDAK diubah** (residual terukur): accum
  tetap hanya menghitung prompt terkirim. Menghitung respons akan
  membuat guard lebih konservatif → lebih banyak reset → lebih
  banyak sesi — berlawanan dengan tujuan anti-bocor.
- **Cooldown = gagal-terang, bukan antre-diam-diam:** episode
  stream-putus dalam jendela 10 mnt membuat giliran itu GAGAL
  dengan pesan jelas (bukan sukses tertunda). Trade-off disengaja
  (§2.7).
- **Reviewer TIDAK tersentuh:** jalur reviewer (`BgBrain`,
  `forceFresh=true` langsung) tetap membakar wadah baru per retry
  seperti sebelumnya — di luar lingkup fix ini; perilaku
  byte-identik, dikunci P13 §5.7 + §6.3.
- **Ronde 1 P13 5.5 — cacat FILE TES, bukan kode produk:** asumsi
  awal "3x `resetServerAccumVc321(ctx, accumKeyVc321)` di
  DsApiClient" SALAH — cabang pengganti invalid DsApiClient memakai
  `accumKeyFreshVc321` (kunci sidik token SAAT INI; wadah pengganti
  disimpan di bawah fp baru, bukan sidik pemilik wadah lama —
  perilaku vc321/vc322 yang benar, komentar DsApiClient.kt:334-336).
  Hitung benar: 2x accumKeyVc321 (guard-reset + create vc327) +
  1x accumKeyFreshVc321 (invalid). Cek diperbaiki; SlotClient memang
  3x accumKeyVc321 (satu kunci per slot). P13 standalone sesudah
  perbaikan: 66/66.
- **CI run_d D15/D16 adalah kode mati (bug lama, diperbaiki di
  sini):** di `run-suite-ci.sh`, `return $fail` berdiri SEBELUM cek
  D15/D16 — keduanya tidak pernah dieksekusi di CI sejak vc325/
  vc326 (runner lokal tidak punya bug ini). vc327 memindahkan
  `return` ke sesudah D17 sehingga D15/D16/D17 semuanya hidup.
  Perbaikan ini dicatat di sini, bukan disembunyikan.
- **Ronde 1 suite D16 GAGAL — cacat RUNNER, bukan kode produk:**
  saat menyalin runner vc326→vc327, cek D16 "DsApiClient tanpa
  vc326" ikut berubah jadi "tanpa vc326/vc327" (sed global) — padahal
  DsApiClient SENGAJA disentuh vc327 (10 marker Vc327). Cek Flavor
  (tanpa vc326/vc327) sudah benar; cek DsApiClient dikembalikan ke
  vc326-only + teks echo diperbaiki. D16 terverifikasi standalone
  sesudah perbaikan; suite ronde 2 dijalankan ulang penuh agar log
  lebih baru dari runner yang diperbaiki.
- **Typo angka di komentar header runner (diperbaiki sesudah ronde
  2, komentar saja):** header `run-tests-vc327-coord.sh` menulis
  "Target suite 2011/2012" — salah hitung (benar: 1946+66=2012
  lulus dari 1947+66=2013 total). Diperbaiki jadi "2012/2013"
  sesudah ronde 2 selesai; diff runner sebelum/sesudah = TEPAT 1
  baris komentar, nol perubahan perilaku — jadi log ronde 2 tetap
  berlaku sebagai bukti suite (angka benar sudah ada di log +
  §6 ini).
- **Doc-sync vc326 mendarat saat fix berjalan:** remote HEAD saat
  spawn 25b53acb → doc-sync commit
  `a2828dd98e623f0caf1af3b485eb9f064cd260dc` (02:00 WIB); HEAD saat
  push vc327 HARUS dicek ulang (lihat §8).
- **Suite lokal di-relaunch sesudah restart runtime:** run pertama
  terbunuh restart di tengah langkah 0 (kompilasi tree); tidak ada
  efek parsial (runner idempotent — rebuild jar + rerun tes).
  Log final = `work-impl/suite-vc327.log` (run kedua).

## §8. Push + CI + APK

(DIISI sesudah push — commit + CI run + APK VM + sidik jari.)

## §9. Tes perangkat (TES-VC327-SIAP-TEMPEL)

Lihat `vc317-work/TES-VC327-SIAP-TEMPEL.txt` (bahasa bayi).
Skenario Sendy: ulangi skenario screenshot 00:13 — perintah panjang
di "Utama" + langkah slot Browser 2 — target: tidak ada kartu merah
+ 0 sesi baru di web DeepSeek untuk N perintah dalam 1 sesi Harness.
