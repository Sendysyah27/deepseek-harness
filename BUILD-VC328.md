# BUILD-VC328 — Slice 11: Split DsApiClient → Rust (vc328, 4.48.9.162)

Dokumen kerja integrator slice 11 migrasi hybrid Kotlin→Rust
(keputusan koordinator 17 Nov 03:26 WIB — DsApiClient split: HTTP +
parse stream SSE + PoW → Rust; panen cookie tetap Kotlin).
Ini BUKAN slice 12 (otak latar/room = vc329) — sesudah slice ini.
Pola dokumen mengikuti BUILD-VC326.md (pola batch slice).
Probe: `vc317-work/work-impl/` — probe JVM `DsApiClient` via
`sun.misc.Unsafe.allocateInstance` (log di §2).

## §1. Keputusan versi

- **vc328, VERSION_CODE 328, VERSION_NAME 4.48.9.162**, branch `vc317-rust`
  (remote main tetap vc309, commit `2e6245e3` — JANGAN disentuh).
- `RUST_CORE_VERSION` **8 → 9** (`jni_glue.rs:59`,
  `RustCore.VERSION` = 9) — satu fungsi JNI baru `dsApiJson`
  (modul `ds_api`); 29 simbol JNI (was 28).
- ARM64-only. `SlotClient.kt` TIDAK disentuh (cermin slice 8 —
  bukan target slice ini). `TurnMetrics.kt`, `Flavor.kt`,
  `GroupChat.kt`, `BgBrain.kt`, `MainActivity.kt` TIDAK disentuh
  (0 marker vc328 di kelimanya).
- Perilaku user-visible TIDAK berubah: ini split transport+parse,
  bukan fix perilaku. Semua perilaku vc321/vc322/vc327
  dipertahankan byte-identik (dikunci P7/P8/P13 + P14 §6).
- Keystore VM lama dipertahankan; gerbang sidik jari vc316 berlaku:
  Main `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`,
  Pure `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`.

## §2. Ukur dulu + desain split (probe JVM — SEBELUM patch)

### §2.1 Temuan kunci (baca penuh DsApiClient.kt 1.124 baris + SlotClient.kt + http.rs + sse.rs + HttpSeam.kt)

- Fungsi SSE DsApiClient (`applyChunk`/`applyValueObject`/
  `routeIsThinking`/`declareFragment`) **identik kode** dengan
  versi `*Kotlin` SlotClient modulo komentar saja (diff
  ternormalisasi) — jadi `harness-core/src/sse.rs` (slice 8, port
  dari SlotClient) sudah mem-port logika parse DsApiClient 1:1.
  Tidak ada logika parse baru yang perlu ditulis.
- SlotClient (slice 8) adalah template eksak: `postJson` via
  `HttpSeam.request` (Rust ureq) + `postJsonKotlin` fallback +
  `postJsonResult` bersama; `postCompletion` transport tetap
  Kotlin `HttpURLConnection` dengan per-baris `HttpSeam.sseApply`
  + `*Kotlin` fallback (mid-stream seam death → sisa stream
  gugur → `stream_terputus` → `chat()` retry di Kotlin);
  `headersMap` diekstrak sebagai map untuk seam.
- Spesifik DsApiClient vs SlotClient: `postJson`/`postCompletion`
  memanggil `recordDiag` (Mode Jhon; `jhonMode()` NPE-safe via
  try/catch, aman di instans tes); `getJson` tanpa recordDiag;
  `uploadFile` multipart + poll loop via `getJson` (poll memakai
  endpoint `fetch_files`, deadline 120.000,
  `findFileId(root) ?: return null`); rumus `estTokens`
  `if (chars<=0) 0L else maxOf(1L, Math.round(chars/perChar))`
  **berbeda dari versi truncating SlotClient** — di-port rumus
  DsApiClient yang eksak.
- `solvePowHeader` orkestrasi tetap Kotlin (`postJson` → Rust
  lewat slice ini, `PowSolver.solveDeepSeek` → Rust sejak slice 2,
  Base64 di Kotlin). Tidak ada op Rust `pow_header` baru.

### §2.2 Probe JVM (sebelum patch)

`sun.misc.Unsafe.allocateInstance(DsApiClient)` **berfungsi di
tes JVM**: `findSessionId` mengembalikan `sess-123`,
`estTokens(340, 3.4) = 100`, `estTokens(0, 3.4) = 0`
(`PROBE-OK`). Caveat: `allocateInstance` melewati inisialisasi
field, jadi tes harus menyetel `rustDsApi` via refleksi dan
`fragIdxRe` sebelum memanggil `applyChunkKotlin`; `StreamState`
(kelas nested privat) bisa diinstansiasi via refleksi, tapi
`lastPath` (null) dan `fragmentThinking` (HashMap null) harus
disetel via `Unsafe.putObject` atau `applyChunkKotlin` NPE.

### §2.3 Desain split (yang diputuskan + diimplementasikan)

**Rust — modul baru `harness-core/src/ds_api.rs`** (686 baris,
`#![forbid(unsafe_code)]`, satu fn JNI baru `dsApiJson`,
konvensi envelope `http_json`):
1. `find_session_id` — replika org.json `optJSONObject`/
   `optString` + `ifBlank` (`data.biz_data.chat_session.id |
   data.biz_data.id`; id numerik di-coerce ke string).
2. `find_challenge` — objek → dirinya sendiri; kunci ada tapi
   bukan objek → seluruh `biz` (semantik `has("challenge")`).
3. `find_file_id` — `biz.id | biz.file_id | biz.file.id |
   biz.uploaded_file.id`; bila `biz.file` objek dengan id kosong
   → null (TIDAK mencoba `uploaded_file`). Cocok dengan Kotlin
   asli (bentuk `?.let` dengan
   `val f = biz.optJSONObject("file") ?: biz.optJSONObject("uploaded_file")`).
4. `est_tokens` — `Math.round` Java = `floor(x+0.5)` (BUKAN
   `round` Rust: -1.5 → -1 Java vs -2 Rust); NaN→0, ±inf→±MAX;
   `chars<=0 → 0`, selain itu `max(1, round)`.
5. `safe_file_name` — per unit UTF-16: hanya 32..126 kecuali
   `"` yang dipertahankan, selain itu `_`; satu emoji
   (2 surrogate) → `"__"`; kosong → `"file.bin"`. CATATAN: `/`,
   `<`, `>` semuanya di 32..126 dan DIPERTAHANKAN (hanya `"`
   jadi `_`) — lihat §7 (cek P14 awal salah ekspektasi).
6. `upload_file` — multipart POST via ureq `send_bytes`
   (terverifikasi ada di ureq 2.12.1), boundary tetap
   `----WebKitFormBoundaryTB0pXOQR2RL219Hu`, byte file base64 in,
   header `x-file-size`, Content-Type multipart menimpa
   `application/json`; non-2xx → `ok:true` + code + body;
   gagal transport → `ok:false`.

**Kotlin:**
- Baru `DsApiSeam.kt` (130 baris, objek, pola `HttpSeam`,
  `var rustDsApi = RustCore.available`):
  `findSessionId/findChallenge/findFileId/estTokens/safeFileName/
  uploadFile` (upload mengembalikan `HttpSeam.Resp`). Null =
  fallback; saklar mati hanya bila `DsApiSeam.rustDsApi` false.
  Ambiguitas (hasil null legit vs gagal seam) ditangani seperti
  `ToolsLeavesSeam`: selalu fallback ke `*Kotlin` bila null.
- `DsApiClient.kt` (1.124 → 1.289 baris): `var rustDsApi =
  RustCore.available` + `import harness.core.RustCore`;
  `headersMap` + delegasi `headers` (pola SlotClient); `postJson`
  → cabang Rust via `HttpSeam.request` (kedua pemanggilan
  `recordDiag` direplikasi eksak) + `postJsonKotlin` (transport
  verbatim) + `postJsonResult(path, code, text, retryAfter,
  token, powHeader)` bersama (recordDiag + mapping); `getJson`
  → pola sama dengan `getJsonResult` (tanpa recordDiag, tanpa
  suffix Retry-After); `uploadFile` → ekstrak `uploadPost`
  (Rust via `DsApiSeam.uploadFile` + `uploadPostKotlin` blok POST
  verbatim), sisanya (parse root, cek SERVER, `findFileId`,
  poll loop via `getJson`) tidak berubah; `postCompletion` →
  transport tetap `HttpURLConnection` (aturan no-callback peta
  §3.4 + preseden slice 8 SlotClient; `onPartial` live
  dipertahankan), parse per baris via `HttpSeam.sseApply`,
  ganti nama `applyChunk→applyChunkKotlin` dst. (badan verbatim),
  `sawFinish` var menggantikan `state.sawFinish` di tail (3
  situs; baris 666 `sawFinish = state.sawFinish` dan baris 751
  setter `state.sawFinish = true` dipertahankan benar);
  `findSessionId/findChallenge/findFileId/estTokens` → wrapper
  seam + fallback `*Kotlin` verbatim. Timeout `postCompletion`:
  connect 20000 / read **240000** (terverifikasi, bukan 120000);
  throttle `now - lastPartialAt > 250`.
- **Tetap Kotlin, tidak disentuh:** panen token/cookie
  (`extractToken`/`evaluateJavascript`/`CookieManager`/
  `snapshotUa`/`currentCookie`, dsCaptureShim + inject/
  readDeepSeekCapture), mesin state sesi (`runChat`/`chat`
  tangga retry, guard/primed/cooldown/accum via TurnMetrics),
  antrean, Governor, prefs/diag/sess-events, WebView, poll loop
  upload, orkestrasi `solvePowHeader`. `SlotClient.kt` TIDAK
  disentuh. `TurnMetrics.kt`, `Flavor.kt`, `GroupChat.kt`,
  `BgBrain.kt`, `DsFailure.kt`, `MemoryDb.kt` TIDAK disentuh.
  Tanpa dep Cargo baru (ureq/base64/serde_json/regex sudah ada).
  `Cargo.toml`/`Cargo.lock` tidak berubah. `deltaTurnPromptVc327`
  ada di `AgentPrompt.kt` (dipanggil dari `MainActivity.kt`),
  BUKAN di `DsApiClient.kt`.

## §3. Basis verifikasi (langkah 0 — semua LULUS sebelum patch)

- Live app tree == branch-stage app tree (diff exit 0); live
  crate == branch-stage crate (diff exit 0, excl. target/).
- `cargo test --offline` di live `harness-core`: **344/344
  passed, 0 failed** (baseline vc327; Rust tidak berubah di vc327).
- NDK clang hidup: Android clang 21.0.0. RAM tersedia hanya
  ~978 MB saat cek — cargo + kotlinc TIDAK dijalankan bersamaan
  (risiko OOM). JDK `~/jdk-21-sys` (OpenJDK 21.0.13; Temurin
  mati; `/usr/lib/jvm` hilang). build.py langkah 0r set
  CC/CXX/AR — tidak diregresi.
- Remote HEAD saat spawn: `vc317-rust` = `0b25fccf`
  (commit push vc327); remote `main` = `2e6245e3` (vc309, tidak
  disentuh). **Doc-sync vc327 mendarat sesudah spawn** → HEAD
  saat push HARUS dicek ulang (lihat §8: ternyata `48dd36be`).

## §4. Isi patch (file yang berubah)

Delta slice 11 vs branch-stage (vc327) = **tepat 7 file**
(287 baris diff di DsApiClient.kt):

### Rust — baru + wiring
- `harness-core/src/ds_api.rs` (BARU, 686 baris, 26.613 byte):
  6 op di atas + `#[cfg(test)]` (find_session_id 10 kasus,
  find_challenge 6, find_file_id 9, est_tokens incl. .5/NaN/
  ±inf/per_char 0 → MAX, safe_file_name incl. emoji → `__`,
  upload wire-exact vs TcpListener lokal incl. byte biner
  0x00/0xFF, non-2xx + gagal-transport + base64 buruk,
  op buruk → ok:false). Memakai ulang
  `crate::http::{opt_string_coerce, valid_header_name,
  valid_header_value}` (dijadikan `pub(crate)` di http.rs) dan
  `crate::ds_safety_strip::java_is_whitespace` (`pub(crate)`).
  `unwrap()`/`expect()` hanya di dalam `mod tests` (mulai
  baris 367).
- `harness-core/src/lib.rs`: `pub mod ds_api;`.
- `harness-core/src/jni_glue.rs`: VERSION 8→9, capabilities +
  `"dsApiJson"`/`"ds_api"`, fn JNI
  `Java_harness_core_RustCore_dsApiJson` via
  `guarded_string_nullable`.
- `harness-core/src/http.rs`: 2× `pub(crate)` (bukan perubahan
  perilaku).
- `app/src/main/java/harness/core/RustCore.kt`: VERSION 9 +
  `external fun dsApiJson(request: String): String?`.

### Kotlin — baru + wiring
- `app/src/main/java/com/senzwh/deepharness/DsApiSeam.kt`
  (BARU, 130 baris) — lihat §2.3.
- `app/src/main/java/com/senzwh/deepharness/DsApiClient.kt`
  (1.124 → 1.289 baris) — lihat §2.3. Tidak ada lagi nama SSE
  lama (`applyChunk(` dst. semua sudah diganti nama).
- `build.py` (live + branch-stage): 328 / 4.48.9.162.

### Yang sengaja TIDAK diubah
- `SlotClient.kt`, `TurnMetrics.kt`, `Flavor.kt`,
  `GroupChat.kt`, `BgBrain.kt`, `DsFailure.kt`, `MemoryDb.kt`,
  `MainActivity.kt`, `AgentPrompt.kt`; `Cargo.toml`/
  `Cargo.lock` (tanpa dep baru); tanpa file lain.

## §5. Tes paritas baru — P14 TestDsApiSplitVc328 (280 cek)

File baru `vc317-work/branch-stage/tests/
TestDsApiSplitVc328.kt` (140 cek per mode, 280 total di suite;
173 situs `cek(` di sumber karena cabang bersama + per-mode):

- §1 seam+VERSION (10/mode): `VERSION==9` runtime, `dsApiJson`
  di RustCore.kt, capabilities berisi `dsApiJson` + modul
  `ds_api`, **29 simbol JNI** (was 28), `DsApiSeam.rustDsApi`
  default, saklar-mati → null.
- §2 op murni diferensial via refleksi pada DsApiClient hasil
  Unsafe-allocate (set field `rustDsApi` per mode):
  matriks findSessionId/findChallenge/findFileId/estTokens
  dengan ekspektasi literal (36/mode).
- §3 HTTP (22/mode): mode rust — `DsApiSeam.uploadFile` vs
  ServerSocket lokal (assert wire); `postJson`/`getJson` tidak
  bisa menembak server lokal (URL hardcode chat.deepseek.com)
  → tes `postJsonResult`/`getJsonResult` mapping via refleksi
  dengan (code, body, retryAfter) terskrip + cek sumber bahwa
  wrapper memanggil `HttpSeam.request` dengan URL/timeout
  (20000/30000)/`wantHeaders=[Retry-After]` yang benar; mode
  kotlin — substitusi per preseden P10 (loopback JVM diblokir
  sandbox deny-proxy; loopback Rust.so native berfungsi,
  terbukti P10 mode rust).
- §4 SSE diferensial (12/mode): stream payload terskrip →
  `HttpSeam.sseApply` (rust) vs `applyChunkKotlin` (kotlin,
  refleksi + StreamState teralokasi + `fragIdxRe` disetel) —
  bandingkan content/thinking/`sawFinish` (ekspektasi content
  `jawaban!`, thinking `hmm halo `, sawFinish true); plus cek
  sumber badan `applyChunkKotlin` == `merge/DsApiClient.kt`
  `applyChunk` (rename ternormalisasi).
- §5 stream/PoW wiring (cek sumber): `solvePowHeader`
  memanggil postJson + `PowSolver.solveDeepSeek` + Base64
  NO_WRAP, string error dipertahankan; badan
  `completeWithSession`; loop `postCompletion` (`useRust`,
  `sseApply`, onPartial throttle 250ms, situs recordDiag,
  string blank → rate_limit/stream_terputus/jawaban_kosong).
- §6 vc321/vc322/vc327 dipertahankan: `allowForceFreshVc327` +
  `migratePrimedOnceVc327` + reset-accum-di-create
  (`TurnMetrics.resetServerAccumVc321(ctx, accumKeyVc321)`) +
  `deltaTurnPromptVc327` (di AgentPrompt.kt/MainActivity.kt);
  tunggu `[2s,5s,15s]` non-reviewer; H4 `isSessionInvalidSignal`
  tidak berubah (delegasi DsFailure); `parent_message_id` NULL;
  situs panggil `createServerSession` tetap 2.
- §7 panen cookie tetap Kotlin: `extractToken`
  (evaluateJavascript + localStorage userToken),
  `CookieManager.getInstance`, `snapshotUa`, `currentCookie`,
  shim hadir; tanpa `chat.deepseek.com`/CookieManager/WebView
  di `ds_api.rs` (kecuali komentar doc `//!` baris 27 yang
  menyebutnya dalam prosa).
- §8 identitas `*Kotlin`: kesamaan badan penuh untuk fun
  `*Kotlin` murni vs `merge/`; cek fragmen untuk
  `postJsonKotlin`/`getJsonKotlin`/`uploadPostKotlin` (dipecah
  dari badan lama).
- §9 batas: tanpa marker Vc328 di SlotClient/TurnMetrics/
  Flavor/GroupChat/BgBrain/MainActivity; `forbid(unsafe_code)`
  di ds_api.rs; tanpa `unwrap()`/`expect()` di luar
  `#[cfg(test)]`; file Cargo tidak berubah (cek himpunan
  eksak: 8 crate di Cargo.toml, 106 paket di Cargo.lock).
- §10 saklar fallback: `DsApiSeam` dengan `rustDsApi=false`
  mengembalikan null untuk semua op; wrapper dengan saklar
  mati memakai `*Kotlin` (nilai sama).
- P7 (52), P8 (73), P13 (66) HARUS tetap hijau penuh — bila
  satu pun merah, itu regresi, bukan "penyesuaian tes".

## §6. Angka tes

- cargo test --offline (live `harness-core`):
  **352/352 passed, 0 failed** (344 baseline + 8 fn tes baru).
- Suite lokal penuh — RONDE 1 (`work-impl/
  log-suite-vc328-r1.txt`, 21:07 WIB): **terbunuh restart
  runtime di tengah P11**; 1.466 LULUS saat terbunuh. Temuan:
  P2/P3/P4 assert VERSION==8 usang (salinan work-s2/s3/s4 —
  diperbaiki sesudahnya); R2-4.1 (ditoleransi); P10 "1.3
  capabilities version 7" (assert `"version":8`, kini 9) +
  "10.1 DsApiClient.kt TIDAK tersentuh Rust slice 8" ×2
  (premis digantikan slice 11); P11 "1.3 capabilities
  version 7" pola sama (belum jalan saat terbunuh).
- Suite lokal penuh — RONDE 2 (`work-impl/
  log-suite-vc328-r2.txt`, 21:27 WIB): EXIT=1, OVERALL FAIL=1.
  **Satu-satunya baris GAGAL = R2-4.1 (ditoleransi).**
  P7 52/52, P8 73/73, P13 66/66 hijau; D1–D18 LULUS (23
  baris D). **TAPI P14 tidak jalan: kotlinc gagal** —
  `TestDsApiSplitVc328.kt:441-456: type mismatch: inferred
  type is Array<Class<out {Comparable<*> & java.io.Serializable}>>
  but Array<Class<*>> was expected` di situs pakai
  `prTypes`/`grTypes` (`arrayOf(String::class.java,
  Int::class.java, ...)` inferensi tipe campuran). Java lalu
  gagal `Could not find or load main class...
  TestDsApiSplitVc328Kt` di kedua mode.
- Suite lokal penuh — RONDE 3 (`work-impl/
  log-suite-vc328-r3.txt`, 21:44 WIB; log lebih baru dari
  runner 21:28): **2.292 lulus, 1 gagal (total 2.293)** —
  aritmetik dari log: P1 103 + P2 36 + P3 94 + P4 235 + N9 46
  + BR 45 + IXL 72 + R1 64 + R2 68/69 + P5 157 (102+55) +
  P6 155 (80+75) + P7 52 + P8 73 + P9 134 (107+27) +
  P10 138 (88+50) + P11 302 (151+151) + P12 172 (86+86) +
  P13 66 + **P14 280 (140+140)** = 2.292. Satu-satunya gagal
  = R2-4.1 (assert STRING usang warisan vc315 — ditoleransi,
  preseden vc326/vc327). C LULUS (tepat 32 berubah + 5 baru;
  DsApiSeam.kt baru; DsApiClient.kt + RustCore.kt sudah di 32).
  D1–D18 SEMUA LULUS incl. D18 baru. OVERALL FAIL=1 hanya
  karena exit code R2-4.1 (runner lokal tidak punya cabang
  toleransi seperti CI — sama seperti log vc326/vc327 yang
  juga berakhir FAIL=1).
- P14 standalone (sesudah perbaikan §7, sebelum ronde 3):
  **140/140 mode rust + 140/140 mode kotlin** (kotlinc exit 0;
  hanya warning deprecation `Unsafe.objectFieldOffset` +
  var `prev`/`cur` tak terpakai di `serveLocal`).

## §7. Deviasi / temuan jujur

- **`DsApiSeam.safeFileName` TIDAK dipanggil kode produksi
  Kotlin** — jalur upload Rust menerapkannya di dalam
  `ds_api.rs`; jalur Kotlin memakai ekspresi inline yang sama
  di `uploadPostKotlin`. P14 §2 membandingkan keduanya
  langsung (diferensial). Deviasi jujur, bukan disembunyikan.
- **Ronde 2 P14 gagal kompilasi — cacat FILE TES, bukan kode
  produk:** `arrayOf` dengan elemen `Class<String>`/
  `Class<Int>` campuran tidak menginfer `Array<Class<*>>`
  — anotasi eksplisit `Array<Class<*>>` pada `prTypes`/
  `grTypes` memperbaikinya. Pelajaran: anotasi tipe eksplisit
  untuk `arrayOf` kelas campuran.
- **6 cek P14 awal salah ekspektasi/cek — semuanya cacat TES,
  bukan kode produk (diperbaiki, lalu P14 standalone
  140/140 ×2):**
  1. 2.29/2.32 safeFileName `"a/b\"c<d>.txt"`: ekspektasi
     `"a_b_c_d_.txt"` SALAH — sanitizer mempertahankan
     32..126 kecuali `"`; `/` (47), `<` (60), `>` (62)
     dipertahankan, hanya `"` → `_`. Ekspektasi benar:
     `"a/b_c<d>.txt"`. Rust dan Kotlin sepakat (cek 2.34
     Rust == inline Kotlin lulus).
  2. 4.6/4.12 throttle postCompletion: string cek
     `System.currentTimeMillis() - lastPartialAt >= 250`
     SALAH — kode asli `now - lastPartialAt > 250` (baris 669).
  3. 5.7 completeWithSession: cek `postJson("/api/v0/chat/
     completion"` SALAH — fungsi itu memanggil
     `solvePowHeader("/api/v0/chat/completion", ...)` lalu
     `postCompletion(body, token, cookie, ua, powHeader,
     onPartial)` (baris 525).
  4. 9.7/9.8 Cargo: path tes `File(treeDir,
     "../branch-stage/harness-core/Cargo.toml")` SALAH —
     dari `work-impl/integrated` itu mengarah ke
     `work-impl/branch-stage/...` (tidak ada), dan di CI
     `vc317-work` tidak ada sama sekali. Diganti cek
     mandiri yang jalan di mana saja: himpunan eksak
     dependensi Cargo.toml (8 crate) + himpunan eksak paket
     Cargo.lock (106 paket). Diff langsung live vs
     branch-stage membuktikan file Cargo memang tidak berubah.
  5. 3.21 (mode kotlin saja): string cek
     `general_purpose::STANDARD.decode` SALAH — kode Rust
     menulis `STANDARD as B64` + `B64\n.decode(...)`
     (terbelah baris). Cek diperbaiki jadi `STANDARD as B64`
     + `.decode(`. Cek live upload mode rust (3.12–3.22)
     semuanya lulus — kode Rust benar sejak awal.
  6. Pertanyaan terbuka yang dibawa: `File(treeDir,
     "../merge")` P12 mengarah dengan cara yang sama
     "salah" tapi P12 lulus ronde 2 — TIDAK "diperbaiki";
     ternyata `work-impl/merge` memang ada (symlink ke
     `../merge`). Dibiarkan apa adanya.
- **Pemeliharaan assert tes lama (bukan perubahan kode):**
  assert VERSION/symbol 8→9 / 28→29 di branch-stage/tests
  (26 penggantian di 11 file) + salinan runner lokal
  work-s2/s3/s4 (6 penggantian — salinan inilah yang
  dikompilasi runner lokal untuk P2/P3/P4); TestHttpVc324
  §10.1 **di-scope ulang**: "DsApiClient.kt TIDAK tersentuh
  Rust slice 8" → "DsApiClient.kt Rust HANYA slice 11
  (HttpSeam reuse + DsApiSeam; tanpa rustSlotClient)" —
  premis lama digantikan slice 11, bukan dilonggarkan diam-
  diam; TestHttpVc324:310 + TestServer4198Vc325:159
  capabilities 8→9.
- **Pelajaran runner (global replace):** replace `vc327`→
  `vc328` sed-style pada badan runner juga merusak echo P13
  ("FIX RESIDUAL vc328"), pola Flavor-exclusion D15/D16/D17
  (`Vc327\|vc328` bukan `Vc327\|vc327` — melemahkan cek),
  echo D17, dan nama jar `test-concatapi-vc314-vc328.jar`.
  Semua dipulihkan dan diverifikasi dengan diff D10–D17
  LULUS vs runner vc327 (hanya diff yang dimaksud: VERSION
  8→9, label VERSION=7 usang → 9, D14 tanpa klausa
  DsApiClient, + D18). Satu perbaikan lagi sesudah diff itu:
  echo D18 "tanpa vc327" → "tanpa vc328". **Pelajaran
  backslash:** backslash di heredoc `muse.exec` berlipat
  tak terduga — bangun pola `\\|` dengan `chr(92)`;
  jangan pernah global-replace string versi secara buta
  pada runner (verifikasi dengan diff sesudahnya).
- **CI run_d D18 hidup:** `return $fail` berdiri sesudah
  D17 (D18 ditambah sebelumnya) — terverifikasi BUKAN kode
  mati. Pola D15/D16/D17 CI terverifikasi utuh
  (`Vc327\|vc327`).
- **Doc-sync vc327 mendarat saat slice berjalan:** remote
  HEAD saat spawn `0b25fccf` → doc-sync commit
  `48dd36be27bbae887717bfa2cefa2dce4f5764d3` (20:38 WIB);
  HEAD saat push dicek ulang (lihat §8).
- **Suite lokal di-relaunch sesudah restart runtime:**
  ronde 1 terbunuh restart di tengah P11 (21:07); tidak ada
  efek parsial (runner idempotent — rebuild jar + rerun tes).
  Log final = `work-impl/log-suite-vc328-r3.txt` (ronde 3).

## §8. Push + CI + APK

### §8.1 Push branch `vc317-rust`

- Remote HEAD sebelum push: `48dd36be27bbae887717bfa2cefa2dce4f5764d3`
  (doc-sync vc327 — cocok ekspektasi; main TIDAK disentuh,
  tetap `2e6245e3`).
- Verifikasi pra-push: staging == remote di luar delta (0 file
  hanya-di-remote; 3 file baru + 19 berubah, semua sesuai delta
  slice 11; `build.py` diff HANYA 2 baris VERSION).
  TEMUAN PRA-PUSH (penting): `branch-stage/` mengandung
  `harness-core/target/` (1.141 file artefak cargo, 541 MB)
  karena runner lokal menjalankan `cargo build` dengan CWD
  branch-stage — push-tree langsung dari branch-stage akan
  meng-commit artefak itu. Staging push DIBANGUN TERPISAH
  (`work-impl/push-vc328/`, rsync exclude
  `harness-core/target/`, 500 file — dari DAFTAR FILE tree
  remote) dan diverifikasi SHA-per-blob lawan tree remote
  sebelum push (478/497 tak berubah cocok SHA; 19 berubah +
  3 baru = delta). Staging di /tmp GAGAL — tmpfs 512 MB penuh;
  staging harus di workspace.
- Push via `gh.py push-tree` (500 blob): commit
  **`c1ee1a4ab9359b6c553d7dc1b9eadd7c2430328c`**,
  parent `48dd36be`, pesan "vc328 slice 11: DsApiClient split
  -> Rust (RUST_CORE_VERSION 9, suite 2292/2293, P14 140/140
  x2)".
- Verifikasi pasca-push: HEAD remote = `c1ee1a4a`;
  **500/500 file lokal SHA-nya cocok dengan tree remote**
  (0 mismatch); 22/22 file delta hadir di remote (19 berubah
  + 3 baru). Staging `work-impl/push-vc328/` DIHAPUS sesudah
  push terverifikasi.

### §8.2 CI

- Run: **43964955275** —
  https://github.com/Sendysyah27/deepseek-harness/actions/runs/43964955275
  (workflow `android.yml`, branch `vc317-rust`, head `c1ee1a4a`,
  event push, dibuat 2026-11-16T21:55:35Z).
- **Status: SUCCESS** (completed, conclusion `success`, ~9 menit).
  Semua step hijau termasuk P14 (280 cek), C, D (D18 baru
  hidup), build senz+pure, Verify APKs. Ronde 1 langsung hijau
  (0 ronde perbaikan).

### §8.3 Artifact CI (`vc317-work/apk-vc328-ci/`)

- `harness-vc317-apks` (artifact id 14277030717, 29.163.702 byte):
  `DeepSeekHarness-v4.48.9.162.apk` (14.703.724 byte) +
  `Harness-v4.48.9.162-pure.apk` (14.654.572 byte).
- Main: MD5 `bf6fc2645c7bccc6dd821fb42d42203f`,
  SHA256 `557c614212d3483ac4674935fe6abec96a4bf1ea71d1efa2cfbe563f3e8dc02c`;
  Pure: MD5 `c54e2f7d097c8d81c079b212178b17f7`,
  SHA256 `d787963c1f9841c57d04cc5af59bec8eba3264d7dac3564adb6eabb2bd79c3b1`.
- aapt2: keduanya 328/4.48.9.162; apksigner v2+v3 true, 1 signer
  masing-masing; `libharness_core.so` 4.220.840 byte di
  `lib/arm64-v8a` (profil release — lebih kecil dari VM, konsisten
  vc327: CI 4.201.592 vs VM 4.783.464); `hsurr:` 0 (di .so
  maupun dex).
- Sidik jari CI (EKSPEKTASI beda — kunci runner, bukan kunci VM):
  Main SHA-256 `c0c3f7aebbccc78277c1f4b9f7b4fccaf0ce3996b6494392b6d4335fa48550a8`,
  Pure SHA-256 `b2ea49429ca00cba0162c289c98c9770c3b10e6f71e03a021b8009026442abfb`.

### §8.4 Build VM (`vc317-work/apk-vc328-vm/`, JH `~/jdk-21-sys`,
langkah Rust rust-lld + CC 0r; clang NDK hidup 21.0.0 —
TIDAK perlu restore dari zip cache)

- Main `DeepSeekHarness-v4.48.9.162.apk` 14.879.852 byte,
  MD5 `65bc0cee9546b70256118a58e1629d53`,
  SHA256 `e1cb89014a4a7790b8aeed534a734697c7e712c9a08cd2e4b9289c357a586cf0`,
  **sidik jari `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`
  — GERBANG COCOK (sama vc316, bisa menimpa)**.
- Pure `Harness-v4.48.9.162-pure.apk` 14.830.700 byte,
  MD5 `9ea27ba00b386880175b0572ccb72756`,
  SHA256 `02663475359a4a9906ad07cb113299b3d9513d7d7fa92013dcaac4528b21749a`,
  **sidik jari `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`
  — GERBANG COCOK (sama vc316, bisa menimpa)**.
- Keduanya: apksigner Verifies, v2+v3 true, 1 signer,
  aapt2 328/4.48.9.162, `libharness_core.so` 4.797.888 byte
  di `lib/arm64-v8a` (naik dari 4.783.464 vc327 — ds_api.rs
  masuk, +14.424 byte), `hsurr:` 0, arm64-v8a saja.
- Log build: `build-senz.log`, `build-pure.log` (di folder yang sama).

### §8.5 Dokumen akhir

- `BUILD-VC328.md` ini difinalisasi (§6 + §7 + §8) sesudah CI
  SUCCESS + build VM lolos gerbang sidik jari.
- `TES-VC328-SIAP-TEMPEL.txt`: perilaku user-visible slice 11
  = IDENTIK, jadi tes asap saja + T1–T6 vc322 + TES-VC327
  tetap berlaku (ditulis eksplisit di file tes).
- **TERBUKA — doc-sync push:** salinan `BUILD-VC328.md` +
  `TES-VC328-SIAP-TEMPEL.txt` sudah disinkronkan ke
  `branch-stage/` (siap push), TAPI belum di-push ke
  `vc317-rust` — HEAD remote masih `c1ee1a4a` (commit push
  vc328). Alasan: tugas ini hanya memerintahkan SATU push
  (langkah 7); doc-sync akan memicu satu run CI penuh lagi
  hanya untuk perubahan dokumen. Koordinator yang memutuskan
  (pola vc325: doc-sync `f1cac218`, vc326: `a2828dd9`,
  vc327: `48dd36be`).

## §9. Tes perangkat (TES-VC328-SIAP-TEMPEL)

Lihat `vc317-work/TES-VC328-SIAP-TEMPEL.txt` (bahasa bayi).
Perilaku user-visible slice 11 = identik dengan vc327, jadi
tes perangkat = tes asap (install + kirim 2 perintah + 1 upload
file) + T1–T6 vc322 + TES-VC327 tetap berlaku.
