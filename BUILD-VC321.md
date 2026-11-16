# BUILD-VC321 — Fix Room-Lane IXL (vc321, 4.48.9.155)

Dokumen kerja integrator fix IXL (keputusan Sendy 13:28). Ini BUKAN slice 7
(rusqlite ditunda sesudah fix ini mendarat — slice 7 jadi vc322).
Pola dokumen mengikuti BUILD-VC320.md.

## §1. Keputusan versi

- **vc321, VERSION_CODE 321, VERSION_NAME 4.48.9.155**, branch `vc317-rust`
  (remote main tetap vc309 — JANGAN disentuh).
- `RUST_CORE_VERSION` TETAP 4, `RustCore.VERSION` tetap 4 — fix ini tidak
  menambah fungsi JNI baru (semua logika baru Kotlin murni).
- ARM64-only. `Flavor.kt` TIDAK disentuh.
- Perubahan perilaku HANYA yang diperlukan fix (prompt lebih kecil /
  delta / reset sesi): room pakai selectCatalog + lanjutan delta; 1-1
  sesi panjang pakai riwayat adaptif + reset sesi server proaktif.
  Sesi pendek/normal TIDAK berubah.
- Keystore VM lama dipertahankan; gerbang sidik jari vc316 berlaku:
  Main `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`,
  Pure `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`.

## §2. Ukur dulu (probe JVM — SEBELUM fix, atas tree vc320)

Probe `/tmp/probe321/Probe321.kt` (log `/tmp/probe321/probe321-before.log`),
runtime JVM atas kode vc320, fungsi murni + aritmetika komponen berlabel.
Kalibrasi token dari kode (`DsApiClient.recordTokens`): **3,4 char/token**.
Buffer server ~64.000 token ≈ 217.600 char (UKURAN-INPUT-VC316.md §2).

### §2.1 Kunci sesi server — kenapa keluar-masuk app TIDAK me-reset

- 1-1 API Internal lewat `SlotClient` (slot browser) bila slot terisi,
  bila tidak lewat `DsApiClient` (token WebView). Room lewat `SlotClient`.
  `BgBrain` lewat `DsApiClient` (bgMode, harnessNo=-100).
- Kunci sesi server tersimpan di SharedPreferences `"harness"`:
  `dsSrvSessId_<tokenFp>_<harnessNo>` (DsApiClient.kt:343) dan
  `dsSlotSrv_<slot>_<harnessNo>` (SlotClient.kt:64). Prefs BERTAHAN
  melewati tutup-buka app — tutup app lalu buka lagi membaca kunci yang
  sama, sesi server yang sama, akumulasi yang sama.
- `parent_message_id` SELALU NULL di kedua klien (DsApiClient.kt:417,
  SlotClient.kt:341) — tiap prompt penuh disimpan server sebagai pesan
  baru di sesi itu.
- Pengganti sesi HANYA bila server menolak eksplisit
  (`isSessionInvalidSignal`) atau SEKALI via forceFresh sesudah stream
  putus 2x. Tidak ada jalan reset proaktif sebelum vc321.
- "Utama" = lane 1-1 (BUKAN room): MemoryDb.kt:93 sesi default id 1
  berjudul 'Utama'; MainActivity.kt:365/378/422 fallback judul "Utama".

### §2.2 Angka SEBELUM fix (terukur runtime)

| Pengukuran | Char | ~Token | Keterangan |
|---|---:|---:|---|
| Katalog PENUH | 94.809 | 27.885 | Sama seperti IXL |
| Room katalog LAMA (take 30.000) | 30.000 | 8.823 | Cap lama GroupChat |
| Room selectCatalog "cek skill lists" | 22.429 | 6.596 | Hemat 7.571 vs cap lama |
| Room selectCatalog "bro?" | 13.726 | 4.037 | Hemat 16.274 |
| Room selectCatalog "kita tes concat" | 22.788 | 6.702 | Hemat 7.212 |
| Room selectCatalog "cek baterai" | 18.458 | 5.428 | Hemat 11.542 |
| Room selectCatalog "halo semuanya" | 11.831 | 3.479 | Hemat 18.169 |
| Jangkar delta `continuationAnchorVc316` | 503 | 147 | Sama seperti vc316 |
| Room ronde-1 LAMA (model sintetis*) | 66.641 | 19.600 | base-rest 21.612 + katalog 30.000 + transkrip 15.029 |
| Room ronde-1 BARU (selectCatalog) | 59.070 | 17.373 | Hemat 7.571 |
| Room lanjutan LAMA (rebuild penuh + hasil tool 2.000) | 70.642 | 20.777 | Tiap ronde rebuild prompt penuh |
| Room lanjutan BARU (anchor + hasil tool 2.000) | 4.804 | 1.412 | Hemat 65.838 (-93%) |
| Prompt awal 1-1 sesi panjang LAMA (riwayat 60k) | 90.526 | 26.625 | System slim 26.651 + label/paging/digest 3.375 + riwayat 60.000 + 500 |
| Prompt awal 1-1 sesi panjang BARU (riwayat 30k) | 60.526 | 17.801 | Hemat 30.000 |
| Kumulatif server sesudah 1 giliran (lama) | 93.526 | 27.507 | Di bawah buffer |
| Kumulatif server sesudah 2 giliran (lama) | 187.052 | 55.015 | Di bawah buffer |
| Kumulatif server sesudah 3 giliran (lama) | **280.578** | **82.522** | **LEWAT buffer 217.600 — kartu merah** |
| Kumulatif server sesudah 5 giliran (lama) | 467.630 | 137.538 | Jauh lewat |

\* Model room sintetis: transkrip 30 pesan × 500 char = 15.029 char;
base-rest 21.612 char (persona 1.000 + Behaviors 0 + CTX_LABEL 752 +
PAGING 623 + pinned 500 + WorldState 250 + memori 5.000 + fakta 5.000 +
goals 2.000 + work-notes 2.000 + skills 3.000 + instruksi 1.200 +
BATCH_TERMINAL 287). Komponen katalog + jangkar + konstanta diukur
runtime; sisanya sintetis berlabel (bukan angka perangkat).

**Kesimpulan ukur:** dengan DB default/seed saja, sesi panjang "Utama"
menembus buffer server di giliran ke-3 — bahkan untuk tool sepele
("cek skill lists"). Dengan isi DB nyata Sendy (skills/fakta/goals/
pinned lebih besar dari seed), titik tembusnya LEBIH AWAL. Ini cocok
dengan screenshot Sendy 13:27. Riwayat 60.000 char + system prompt
yang dikirim ulang penuh tiap giliran adalah pengalinya; lanjutan
delta vc316 (~3.000 char/giliran) sudah kecil — yang besar adalah
prompt AWAL tiap giliran.

### §2.3 Keputusan fix (dari ukuran, bukan asumsi)

1. **Room:** selectCatalog (hemat 7,5–18k/ronde pertama) + lanjutan
   delta (hemat ~65,8k/ronde lanjutan). Ronde pertama tetap kirim
   prompt penuh seperti sekarang.
2. **1-1 sesi panjang:** riwayat awal adaptif (hemat 30.000 char/POST
   bila sesi ≥40 pesan) + guard reset sesi server proaktif — karena
   pemangkasan saja TIDAK menghentikan akumulasi: tanpa reset, 10
   giliran × 63.526 char tetap = 635.260 char server-side. Guard
   membatasi konteks server ≤ 200.000 char (~58.800 token) selamanya.
   Simulasi guard 10 giliran (prompt awal baru 60.526): reset 3 kali,
   akumulasi maks sebelum reset 190.578 char.
3. **Yang TIDAK dipilih:** menurunkan DEFAULT_CAP di bawah 200.000 —
   cap per-prompt bukan penyebabnya (prompt awal 90.526 sudah di bawah
   cap); menurunkan cap tidak menghentikan akumulasi antar-POST.
   Juga TIDAK dipilih: sesi server baru per POST — sejarah membuktikan
   itu menumpuk ratusan sesi di akun → suspend otomatis (DsApiClient
   :157-158; suspend 12 Nov 2026). Reset vc321 TERBATAS: hanya bila
   accum + prompt berikut > 200.000, tipikal ~1× per 3–5 giliran sesi
   panjang, dan prompt awal selalu membawa ulang konteks penuh.

## §3. Basis verifikasi (langkah 0 — semua LULUS sebelum patch)

- (a) Live tree = vc320 pasca-slice-6 (build.py 320/4.48.9.154,
  `RUST_CORE_VERSION` 4, 24 simbol JNI, suite 1075/1076 hanya R2-4.1,
  cargo 271/271).
- (b) `diff -rq merge/ work-impl/integrated/` = tepat 15 file .kt
  berubah (slice 2–6) sebelum patch vc321.
- (c) Probe §2 di atas selesai SEBELUM satu baris kode diubah.
- (d) File target dibaca penuh: GroupChat.kt (886 baris),
  MainActivity.kt (prompt awal ~5787 + sendContinuation 11245),
  DsApiClient.kt (runChat ~168), SlotClient.kt (runChat ~198),
  TurnMetrics.kt, AgentPrompt.kt (selectCatalog/anchor).

## §4. Isi fix (file yang berubah)

Kotlin saja (live tree + staging + branch-stage) — TIDAK ADA Rust baru,
TIDAK ADA JNI baru, `RUST_CORE_VERSION` tetap 4.

- `TurnMetrics.kt` — helper baru (murni + prefs):
  - `historyBudgetVc321(priorMsgCount)`: (60, 60000) bila <40 pesan,
    (30, 30000) bila ≥40 pesan. Konstanta `LONG_SESSION_MSG_THRESHOLD_VC321
    = 40`, `HISTORY_LONG_N/CHARS_VC321 = 30/30000`,
    `HISTORY_NORMAL_N/CHARS_VC321 = 60/60000`.
  - `shouldResetServerSessionVc321(accum, next)`: true bila accum > 0
    DAN accum + next > DEFAULT_CAP (200.000). accum==0 tidak pernah
    reset (satu POST besar di sesi baru tidak membakar wadah).
  - `serverAccumVc321(ctx, key)` / `addServerAccumVc321(ctx, key, chars)`
    / `resetServerAccumVc321(ctx, key)`: estimasi akumulasi per sesi
    server di prefs "turnMetrics" (kunci `srvAccumVc321_<kunci-sesi>`,
    jepit 10 juta).
- `GroupChat.kt` — room:
  - `buildPrompt`: katalog lewat `AgentPrompt.catalogForTask(app,
    userText)` (selectCatalog); backstop cap 30.000 dipertahankan
    (tugas kosong → katalog penuh, kontrak lama).
  - BARU `continuationPromptVc321(userText, suffix)` = anchor vc316 +
    suffix. Dipakai di 3 situs lanjutan (batch, nudge gate bukti,
    sesudah tool tunggal — isi suffix byte-identik versi lama).
    Hanya ronde pertama yang `buildPrompt` penuh (1 situs tersisa).
- `MainActivity.kt` — prompt awal 1-1: anggaran [RIWAYAT] adaptif
  (`historyBudgetVc321(msgCount_<sesi> sebelum giliran ini)` +
  `historyTranscriptVc230(histN, histCap, ...)`). Sesi normal tetap
  (60, 60000). Situs lain (20/20000, 40/40000 — jalur reviewer/
  ringkas) tidak disentuh.
- `SlotClient.kt` — `runChat`: guard reset proaktif (kunci
  `dsSlotSrv_<slot>_<no>` / reviewer key; dilewati bila
  `freshEphemeral` — wadah ephemeral memang dibuang tiap panggilan)
  + catat akumulasi sesudah POST sukses (jalur normal + jalur wadah
  pengganti sesudah sesi ditolak).
- `DsApiClient.kt` — `runChat`: guard yang sama (kunci
  `dsSrvSessId_<fp-pemilik-wadah>_<no>`; bila wadah pengganti lahir,
  akumulasi dicatat di bawah fp token saat ini) + catat akumulasi
  sesudah POST sukses. `forceFresh` tetap dihormati; `storedPair`
  di-null-kan saat guard me-reset supaya cabang migrasi/sesi-ditolak
  tidak memakai pasangan basi.
- `build.py` (live + staging): 320/4.48.9.154 → 321/4.48.9.155
  (hanya baris VERSION; isi keduanya memang berbeda, tidak saling
  ditimpa).

### Yang sengaja TIDAK diubah

- `ToolRegistry.kt` (definisi tool utuh), `AgentPrompt.kt`
  (selectCatalog/anchor dipakai apa adanya — tidak diubah),
  `BgBrain.kt` (lantai latar ikut guard otomatis lewat DsApiClient;
  `continuationPrompt` latar tidak disentuh), `Governor.kt`,
  `ToolGate.kt`, `Tools.kt`, `DsFailure.kt` (tangga retry utuh),
  cap langkah 50, `DEFAULT_CAP` 200.000, `Flavor.kt`.
- Transkrip room (30 pesan) tidak dipangkas — penghematan room
  datang dari katalog + delta, bukan dari memotong transkrip.
- `parent_message_id` tetap NULL di kedua klien (protokol tidak
  berubah — reset sesi adalah cara yang dipilih, bukan parent id).

## §5. Tes paritas baru — P7 TestRoomIxlFixVc321 (52 cek)

File: `branch-stage/tests/TestRoomIxlFixVc321.kt` (juga dipakai runner
lokal; CI: step P7 di `android.yml` + `run_p7` di `run-suite-ci.sh`).

- §1 katalog room (runtime): penuh 94.809; slim "cek skill lists"
  22.429, "kita tes concat" 22.788, "bro?" 13.726, "cek baterai"
  18.458, "halo semuanya" 11.831; tiap slim memuat list_tools +
  fallback; pesan kosong → penuh.
- §2 guard reset (murni): 0 + 200.000 → tidak reset; 150.000 +
  60.526 → reset; batas persis 200.000 → tidak reset, 200.001 →
  reset; delta 3.000 di accum 190.000 → tidak reset; accum negatif
  → tidak reset; DEFAULT_CAP tetap 200.000.
- §3 riwayat adaptif: 0/5/39 → (60, 60000); 40/100 → (30, 30000).
- §4 struktur room (source): catalogForTask dipakai, toolCatalog
  penuh hilang, backstop 30.000 ada, continuationPromptVc321 ada +
  3 situs delta, hanya 1 situs buildPrompt tersisa, tidak ada lagi
  rebuild `buildPrompt(...) +`.
- §5 struktur 1-1 (source): MainActivity historyBudgetVc321 +
  historyTranscriptVc230 adaptif, tidak ada lagi (60, 60000) mentah;
  SlotClient + DsApiClient guard + catat akumulasi + kunci utuh;
  freshEphemeral dilewati; forceFresh dihormati; parent NULL tetap.
- §6 angka sebelum→sesudah: ronde-1 room hemat 7.571; lanjutan
  room hemat 65.838 (>90%); prompt awal 1-1 hemat 30.000 (90.526 →
  60.526); kumulatif lama 3 giliran 280.578 > buffer; simulasi guard
  10 giliran reset 3×, maks 190.578 char.
- §7 sesi pendek tidak berubah: riwayat (60, 60000), tidak ada reset
  di accum 0, jangkar 503, katalog penuh pesan kosong, 188 tool.
- §8 regresi: tidak ada `hsurr:` di 5 file fix; Flavor.kt tanpa
  referensi vc321.

IXL lama diperbarui (preseden 7.1 slice 6): `TestInputLimitVc316.kt`
7.5/7.6 tidak lagi menuntut DsApiClient/SlotClient byte-identik P1 —
fix ini memang menyentuh keduanya; yang dikunci sejak vc321 adalah
protokol tidak berubah (parent NULL, kunci sesi, forceFresh/
freshEphemeral) + guard vc321 ada. 7.9 diperluas: 4 file IXL + 3 file
vc321 (GroupChat/DsApiClient/SlotClient) berubah vs baseline.

## §6. Angka tes

- **cargo test: 271/271 lulus, 0 warning** (tidak ada Rust baru —
  sama seperti vc320; dijalankan ulang di suite lokal).
- **Suite lokal penuh: 1127/1128** — satu-satunya gagal R2-4.1
  (assert STRING usang warisan vc315, DITOLERANSI seperti slice
  sebelumnya; bukan regresi).
  - P1 103/103, P2 36/36, P3 94/94, P4 235/235, N9 46/46,
    BR 45/45, IXL 72/72 (sesudah perbaikan 7.5/7.6/7.9 §5), R1 64/64,
    R2 68/69 (4.1), P5 157/157 (kotlin 55 + rust 102),
    P6 155/155 (kotlin 75 + rust 80),
    **P7 52/52 (baru vc321)**.
  - C LULUS: tepat 20 file .kt berubah vs baseline vc316 + 0 baru
    (15 slice 2–6 + 5 vc321).
  - D1–D12 LULUS (D12 baru: fix vc321 + RUST_CORE_VERSION tetap 4
    + Flavor.kt tidak berubah).
  - Log: `vc317-work/work-impl/run-tests-vc321-fix-coord.log`.
  - Total = 1076 (vc320) + 52 (P7) = 1128 cek; 1127 lulus.
- P7 pertama kali jalan: kompilasi tes gagal 1× — bug PENULIS tes
  (bukan kode): `slimSkillLists in 21429..23429` (String, bukan
  Int) — diperbaiki ke `.length in ...` di 5 cek; lalu **52/52**.

## §7. Deviasi / temuan jujur

1. **IXL 7.5/7.6 — cek usang, bukan regresi (diperbaiki sebelum
   suite).** Seperti 7.1 di slice 6, cek byte-identik P1 tidak lagi
   berlaku karena fix memang menyentuh DsApiClient/SlotClient.
   Diganti kunci semantik (protokol + guard). IXL jalan 72/72.
2. **P7 — bug penulisan tes ronde 1 (diperbaiki).** Lihat §6.
3. **Angka "sebelum" 1-1 memakai DB default/seed.** Isi DB + file
   otak di HP Sendy tidak ada di VM — system prompt nyata bisa
   LEBIH BESAR dari 26.651 char (skills/fakta/goals/pinned aktual),
   jadi titik tembus di perangkat bisa lebih awal dari giliran ke-3
   model ini. Guard vc321 tidak bergantung pada angka itu: reset
   dipicu akumulasi TERUKUR (char prompt sukses yang benar-benar
   dikirim), bukan estimasi model.
4. **Reset sesi = wadah server baru.** Tiap reset membuat SATU sesi
   server baru (create). Laju dibatasi guard (hanya bila accum +
   berikut > 200.000). Risiko suspend ala desain lama (sesi baru per
   POST) tidak berlaku di sini — tapi bila prompt awal secara
   persisten ≥200.000 char (DB raksasa), reset bisa terjadi tiap
   giliran. Bila itu terlihat di perangkat, tindak lanjutnya adalah
   memangkas blok memori prompt awal, bukan mematikan guard.
5. **Trade-off room sama seperti vc316 1-1.** Tool di luar seleksi
   slim harus lewat `list_tools {}` dulu (jangkar + catatan fallback
   mengingatkan ini; P7 1.7 menguncinya).
6. **Delta room bergantung pada sesi server yang menyimpan prompt
   ronde pertama.** Bila guard me-reset wadah DI TENGAH ronde room
   (akumulasi sudah >190k saat lanjutan kecil datang — praktis hanya
   di sesi room yang sangat panjang), lanjutan delta kehilangan
   konteks penuh; jangkar membawa jalan keluar list_tools dan hasil
   tool tetap verbatim di delta, jadi ronde tetap bisa jalan
   (degradasi, bukan gagal total).

## §8. Push + CI + APK

- **Pra-cek drift (sebelum push):** remote HEAD 1eaaf4f9 terverifikasi
  (cocok brief); selisih remote tree vs staging tepat 9 berubah + 1 baru,
  0 remote-only: 5 file fix + build.py + TestInputLimitVc316.kt +
  run-suite-ci.sh + android.yml + TestRoomIxlFixVc321.kt (baru).
- **Push:** 16 Nov 2026 ~08:10 WIB, branch `vc317-rust`, commit
  `a21b3abce0cd4a9fd006f699d968da9d596c541b`, 466 file via `gh.py
  push-tree`. Pesan: "vc321 (4.48.9.155): fix room-lane IXL —
  selectCatalog+delta room, riwayat adaptif + guard reset sesi server
  1-1 (P7 52 cek, suite 1127/1128)".
- **Verifikasi pasca-push:** remote HEAD = a21b3abc; remote tree 466
  blob == staging 466 file, 0 beda; 12/12 file delta SHA cocok
  (termasuk BUILD-VC321.md + TES-VC321-SIAP-TEMPEL.txt yang ikut
  ter-push). Main tidak disentuh (tetap 2e6245e3 = vc309).
- **CI:** run [#43829092826](https://github.com/Sendysyah27/deepseek-harness/actions/runs/43829092826)
  (push, head a21b3abc, dibuat 08:10:13Z) — **SUCCESS**, job `build`
  completed/success. Artifact `harness-vc317-apks` (nama belum diganti
  dari vc320) 26.404.791 byte.
- **APK CI** (`vc317-work/apk-vc321-ci/`): zip MD5
  85e3127043f1c9e743e7c8ca046a8859.
  - Main `DeepSeekHarness-v4.48.9.155.apk` 13.319.276 B, MD5
    dba22c901a7c4c405a6cae25f302afad, SHA256
    cead80a68d42290f13debba1c29128918d341b9c1377abc40ce1428513f5f312;
    v2+v3 true, 1 signer (kunci runner
    16ebf9932e89e57730d56bf9208ea7a4a3326e42d14736035a80b6c0066e4b60 —
    beda dari VM, EKSPEKTASI).
  - Pure `Harness-v4.48.9.155-pure.apk` 13.270.124 B, MD5
    f166b3b711634f8b3773e3e6b5bd6875, SHA256
    3dee07228bbb21d4ca5651b7c632ce0de7f4d9befeb81955d57925ac42c26f9d;
    v2+v3 true, 1 signer (kunci runner
    9081a38e034a9e2790ee7c39b7774e6fb1afef37e9c1420a7e4f3290d7ae7dee).
  - Keduanya 321/4.48.9.155, libharness_core.so di lib/arm64-v8a
    (1.854.424 B), hsurr: 0, hanya arm64.
- **APK VM** (`vc317-work/apk-vc321-vm/`, build `python3 -u build.py`
  senz lalu pure; log `/tmp/build-vc321-senz.log`,
  `/tmp/build-vc321-pure.log`):
  - Main `DeepSeekHarness-v4.48.9.155.apk` 13.401.196 B, MD5
    b69be4e70786c94a6c0431e2b6fdeecb, SHA256
    1af379ced32c2cd795841cdcbde0473998221f6323966bd4b9c99bd69e959696;
    v2+v3 true, 1 signer, **sidik jari
    e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177 —
    COCOK gerbang vc316** (bisa menimpa).
  - Pure `Harness-v4.48.9.155-pure.apk` 13.352.044 B, MD5
    fa75a7af41648a496d004bad79d8ef60, SHA256
    e6c3a023ee8dd8b4d10d7fb5455783fd1e09dc968139cbf9e947a62c87421318;
    v2+v3 true, 1 signer, **sidik jari
    f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f —
    COCOK gerbang vc316** (bisa menimpa).
  - Keduanya 321/4.48.9.155, libharness_core.so di lib/arm64-v8a
    (2.119.656 B), hsurr: 0, hanya arm64.
- **Doc-sync:** BUILD-VC321.md diperbarui (§8 ini) via `gh.py
  put-file` sesudah CI/APK (commit terpisah, isi §8 saja yang berubah
  vs versi ter-push).

## §9. Tes perangkat (TES-VC321-SIAP-TEMPEL)

Lihat `vc317-work/TES-VC321-SIAP-TEMPEL.txt` — T-baru: sesi panjang
di chat "Utama" ("cek skill lists" sesudah percakapan panjang — harus
jalan, tidak kartu merah) + tes ronde room (tool di ronde lanjutan
tetap jalan tanpa kartu merah).
