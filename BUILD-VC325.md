# BUILD-VC325 — Slice 9: Server :4198 (vc325, 4.48.9.159)

Dokumen kerja integrator slice 9. Pola dokumen mengikuti BUILD-VC324.md.

## §1. Keputusan versi

- **vc325, VERSION_CODE 325, VERSION_NAME 4.48.9.159**, branch `vc317-rust`
  (remote main tetap vc309 — JANGAN disentuh).
- `RUST_CORE_VERSION` 6→**7**, `RustCore.VERSION` 6→**7** — slice ini
  menambah SATU fungsi JNI baru (`workerEndpointJson`).
- ARM64-only. `Flavor.kt` TIDAK disentuh. `DsApiClient.kt` TIDAK disentuh
  (split-nya slice 11).
- Porting MURNI: perilaku 1:1; quirk DIREPLIKASI (dikunci tes), bukan
  diperbaiki. KHUSUSNYA: autentikasi token (master + per-worker),
  kuota worker (Fase 3 vc281), isolasi worker, dan respons error
  endpoint TIDAK BOLEH berubah.

## §2. Desain seam + ruang lingkup (diukur dulu, sebelum patch)

### §2.1 Ruang lingkup slice 9 menurut peta

Peta HYBRID-MIGRATION-MAP.md §6 (urutan slice): **slice 9 = "Server
:4198 — `WorkerEndpoint.kt`", usaha M**. Rincian per file di peta:
- §2.4 `WorkerEndpoint.kt` (959 baris) — RUST/M, "`std::net::
  TcpListener` (cukup; tanpa tokio di tahap awal) atau `tokio` bila
  butuh konkurensi. **Kontrak wire HARUS byte-identik** (token, path,
  kode status) — tes terhadap klien yang sudah ada."
- §2.1 `WorkerEndpointLogic.kt` (166 baris) — RUST/S, "logika endpoint
  worker (HMAC `javax.crypto.Mac` + `MessageDigest`)", crate `hmac` +
  `sha2` (RustCrypto). File ini pendamping murni `WorkerEndpoint.kt`
  (dipakai server + `WorkerQueue.kt` untuk sign/verify antrean) —
  di-port BERSAMA slice 9; tanpa itu server tidak bisa pindah.
- §2.1/§2.2 `WorkerLogic.kt`, `WorkerQueue.kt`,
  `WorkerJanitorLogic.kt`, `WorkerRegistry.kt` — file TERPISAH di peta
  (RUST/S-M, klaster A/B), BUKAN slice 9. `WorkerEndpoint.kt` hanya
  memanggil mereka lewat dispatch Kotlin — dispatch itu tetap Kotlin
  (lihat §2.3). Dua helper `WorkerLogic` yang dipakai endpoint
  (`previewChars`, `slicePreview`) di-port sebagai bagian op endpoint
  (konstanta PREVIEW_DEFAULT 2000 / PREVIEW_MAX 8000 / READ_MAX 8000
  direplika di Rust); `WorkerLogic.kt` sendiri TIDAK diubah.

### §2.2 Keputusan runtime async: TANPA tokio (keputusan peta, dicatat)

Peta §3.1: "Jangan tambah `tokio` sebelum slice 9" — slice 9 adalah
slice pertama yang BOLEH memakai tokio; §2.4 memberi pilihan
"`std::net::TcpListener` (cukup...) atau `tokio` bila butuh
konkurensi". Keputusan slice 9: **TIDAK pakai tokio, TIDAK pakai
`std::net::TcpListener` untuk server hidup** — alasannya:
1. Dispatch endpoint memanggil kolaborator yang HANYA ada di Kotlin/
   Android (`WorkerRegistry`, `WorkerQueue`, `WorkerJanitor`,
   `ProotSession`, `SecureStore`, `EventDb`) — server Rust yang
   hidup butuh callback Rust→Kotlin, dan peta §3.4 melarang itu di
   slice awal ("Rust tidak callback ke Kotlin di slice awal").
2. Loop accept Kotlin SUDAH memakai model yang sama dengan yang
   `std::net` berikan: `accept()` + satu thread daemon per koneksi
   (`worker-ep-conn`) + `dispatchPool` (cached thread pool) untuk
   dispatch 25 dtk. Tidak ada kebutuhan konkurensi baru yang tokio
   selesaikan di sini.
3. Yang BISA pindah sekarang — dan yang menentukan kontrak wire —
   adalah seluruh lapisan murni: routing, auth, HMAC antrean,
   framing HTTP, kunci idempotensi. Itulah yang di-port (§2.3).
Server Rust penuh (listener + dispatch) menjadi pekerjaan lanjutan
bila kolaborator dispatch sudah Rust (paling cepat slice 11+) dan
desain callback JNI sudah ada — BUKAN slice 9.

### §2.3 Keputusan desain seam

- **Satu modul Rust `worker_endpoint` + SATU fungsi JNI
  `workerEndpointJson(request: String): String?`**, JSON in/out
  (pola slice 5/6/7/8). Request `{"op":...}`; respons
  `{"ok":true,...}` atau `{"ok":false,"error":"..."}` (error op →
  JSON, bukan `null` — pola perbaikan `http_json` vc324 §7.1);
  `null` = panic/boundary gagal → fallback Kotlin.
- **Tanpa dependensi Cargo baru.** HMAC-SHA256 diimplementasi di
  atas `sha2` 0.10.9 (sudah ada sejak slice 8; ipad/opad standar,
  dikunci vektor RFC 4231). `Cargo.toml`/`Cargo.lock` TIDAK berubah.
- **Op seam** (semua dipakai langsung oleh wrapper Kotlin):
  - `parse_route` — `WorkerEndpointLogic.parseRoute` 1:1 (semua
    aksi: health/listWorkers/spawnWorker/workerDetail/stopWorker/
    restartWorker/resetWorker/verifyTask/setQuota/workerLog/
    listTasks/enqueueTask/listResults/readTask/readResult/
    writeTaskResult/jhonListTasks/jhonEnqueue/jhonReadTask/
    jhonWriteResult/jhonListResults; rute tak-dikenal →
    `{"ok":true,"route":null}`, Kotlin yang menjawab 404/405).
  - `path_matches_other_method` — 405 vs 404 (pola vc261 N-m2).
  - `parse_query` — `substringAfter("?")`, `&`, `=` limit 2,
    kunci TIDAK di-decode, nilai di-URL-decode.
  - `bearer_token` — `Bearer ` (huruf besar B saja) + trim.
  - `is_authorized` — banding waktu-konstan (akumulasi XOR tanpa
    early-exit isi; panjang beda → false, seperti
    `MessageDigest.isEqual`); kosong → tolak; provided di-trim,
    expected TIDAK.
  - `is_safe_name`, `slice_preview`, `preview_chars` (2000/8000).
  - `hmac_sha256_hex`, `sign_queue_text`, `has_queue_signature`,
    `verify_queue_text` (format `HMAC-SHA256:<hex>\n<isi>`).
  - `worker_scoped` — 11 aksi `WORKER_SCOPED_ACTIONS`.
  - `parse_headers` — teks header → `(req_line, headers)` (kunci
    lowercase, duplikat menimpa, baris tanpa `:` dilewati).
  - `build_response` — head respons HTTP (`HTTP/1.1 <code>
    <reason>\r\nContent-Type...Content-Length: <byte>\r\n
    Connection: close\r\n[Retry-After]\r\n`).
  - `idempotency_key` — header `Idempotency-Key` didahulukan
    (`hdr:` + take(128)); spawn (`spawn:nama|jenis|sesi|sha16`),
    enqueue (`enqueue:<id>|sha16`), jhonEnqueue
    (`enqueue:jhon|sha16`); sha16 = 8 byte pertama SHA-256 hex.
- **Fallback + saklar**: `WorkerEndpointSeam.kt` (BARU, pola
  `HttpSeam.kt`) — satu saklar `rustWorkerEndpoint` (default ikut
  `RustCore.available`). Tiap fun publik `WorkerEndpointLogic` /
  helper `WorkerEndpoint` mencoba seam dulu, bila `null`/error →
  jalankan implementasi Kotlin lama (`*Kotlin`, privat, body ASLI
  yang diganti nama). API publik TETAP PERSIS — `WorkerQueue.kt`
  (pemakai `signQueueText`/`verifyQueueText`) TIDAK DIUBAH sama
  sekali: seam hidup DI DALAM `WorkerEndpointLogic.kt`.
- **Yang TETAP Kotlin di vc325 (sisa jujur, bukan regresi):**
  - Loop server hidup (`ServerSocket`/`acceptLoop`/`handleConn`
    stream + `start`/`stop`/`ensureStarted`) — butuh callback ke
    dispatch Kotlin (§2.2).
  - Dispatch (`dispatch` + `workerJson` + `dirsFor` +
    `findDuplicateRunningWorker`) — memanggil `WorkerRegistry`/
    `WorkerQueue`/`WorkerJanitor`/`ProotSession`/`EventDb`.
  - Token/SecureStore (`token`/`rotateToken`/`workerToken`/
    `queueKey`/`expectedToken`/`authorizeFor`/`syncTokenFile*`/
    `deleteWorkerToken`/`rotateQueueKeyOnceVc284`) — Keystore +
    `SecureRandom` + proot; `authorizeFor` memakai
    `isAuthorized` + `WORKER_SCOPED_ACTIONS` yang SUDAH Rust
    di bawahnya.
  - Baca body dari stream (`readChunkedBody`/`readBody`/
    `readLineRaw` + loop byte `readHeaders`) — I/O stream;
    yang pindah hanya parse teks header (`parse_headers`) dan
    pembangunan head respons (`build_response`).
  - Peta idempotensi (`inFlight`/`idemDone` + `pruneIdemLocked`
    + `idempotentFuture`) — stateful + `synchronized`; kunci
    dihitung Rust (`idempotency_key`), penyimpanan tetap Kotlin
    (state Rust jangka panjang ditolak peta §7).
  - `WorkerLogic.kt` / `WorkerQueue.kt` / `WorkerJanitorLogic.kt`
    / `WorkerRegistry.kt` — file peta lain, bukan slice 9.
- `#![forbid(unsafe_code)]` di `worker_endpoint.rs`; TANPA
  `unwrap()`/`expect()` di jalur input luar (request JSON, path,
  header, body, nama file, token).
- **Quirk yang direplikasi (dikunci tes P11):**
  `URLDecoder.decode` mengubah `+` jadi spasi (path DAN query)
  dan bila SATU `%` tak-valid, SELURUH segmen/nilai dikembalikan
  mentah; `optString` melakukan koersi (angka/bool → teks kompak,
  hilang/null → default/`""`); `String.length`/`take(n)` Kotlin
  = satuan UTF-16 — Rust menghitung unit UTF-16 (astral = 2);
  potongan tepat di tengah pasangan surrogate → lone
  high-surrogate di string JVM (probe JVM §7.1: `toByteArray`
  UTF-8 = `?` 0x3F, `JSONObject.toString()` = `"a?"`); seam
  Rust mereplikanya dengan menulis escape JSON `\uXXXX`
  mentah supaya org.json mem-parse-nya kembali jadi lone
  surrogate yang sama; `lowercase()`/`trim()` Kotlin =
  Unicode-lowercase / Java-`isWhitespace` (pelajaran slice 4);
  `MessageDigest.isEqual` = banding waktu-konstan; HMAC key
  >64 byte di-hash dulu (blok SHA-256); `parseRoute` —
  `GET /workers/spawn` = workerDetail id=`spawn` (spawnWorker
  hanya POST; nama `spawn` dicadangkan `WorkerLogic.validateSpawn`);
  `previewChars(0)` → 2000 (bukan 1); `slicePreview` memakai
  `<=` (teks tepat sepanjang maks TIDAK terpotong).

### §2.4 Inventaris file target (dibaca penuh sebelum patch)

| File | Baris live | vs merge/ (vc316) | Isi yang di-port |
|---|---|---|---|
| `WorkerEndpoint.kt` | 959 | IDENTIK | `parseHeaders` (teks→map) + `buildResponse` (head) + `idempotencyKey` (+ `sha256Hex16` di bawahnya); accept/dispatch/token/start-stop tetap Kotlin |
| `WorkerEndpointLogic.kt` | 166 | IDENTIK | SEMUA fun publik: `isAuthorized`, `bearerToken`, `previewChars`, `slicePreview`, `isSafeName`, `parseQuery`, `parseRoute`, `pathMatchesOtherMethod`, `hmacSha256Hex`, `signQueueText`, `hasQueueSignature`, `verifyQueueText` (+ `workerScoped` baru di seam; `WORKER_SCOPED_ACTIONS`/`PORT`/`MAX_BODY_BYTES` tetap sebagai data) |

## §3. Basis verifikasi (langkah 0 — semua LULUS sebelum patch)

- (a) 2 file target vs `vc317-work/merge/` IDENTIK (WorkerEndpoint,
  WorkerEndpointLogic) — belum tersentuh slice 1–8 / fix vc321/vc322.
- (b) `diff -rq --exclude=target harness-core/
  vc317-work/branch-stage/harness-core/` IDENTIK (exit 0).
- (c) `cargo test --offline` 311/311 (dijalankan ulang).
- (d) clang NDK masih jalan: `bin/clang --version` = Android
  clang 21.0.0 (tidak perlu pulihkan dari zip cache).
- (e) RAM `free -m`: available 2223 MB sebelum kerja.

## §4. Isi patch

Rust (crate `harness-core`, `RUST_CORE_VERSION` 6→7):

- `src/worker_endpoint.rs` (BARU, `#![forbid(unsafe_code)]`):
  `worker_endpoint_json(request)` + 16 op (§2.3): `parse_route`,
  `path_matches_other_method`, `parse_query`, `bearer_token`,
  `is_authorized` (waktu-konstan), `is_safe_name`,
  `slice_preview`, `preview_chars`, `hmac_sha256_hex`
  (HMAC-SHA256 di atas `sha2`, tanpa dep baru),
  `sign_queue_text`, `has_queue_signature`,
  `verify_queue_text`, `worker_scoped`, `parse_headers`,
  `build_response`, `idempotency_key` (+ `sha256_hex16`).
  TANPA `unwrap()`/`expect()` di luar `#[cfg(test)]`.
  18 tes Rust baru (total cargo 329).
- `src/lib.rs`: +`pub mod worker_endpoint;`.
- `src/jni_glue.rs`: `RUST_CORE_VERSION` 6→7; capabilities
  +`"workerEndpointJson"` + modul `"worker_endpoint"`; JNI
  `Java_harness_core_RustCore_workerEndpointJson`
  (`guarded_string_nullable` + `catch_unwind`, pola `httpJson`).
- `Cargo.toml`/`Cargo.lock`: TIDAK berubah (tanpa tokio, tanpa
  dep baru — HMAC di atas `sha2` 0.10.9 yang sudah ada).

Kotlin (2 file berubah + 1 baru + RustCore; porting murni, API publik
TETAP PERSIS):

- `WorkerEndpointLogic.kt` — SEMUA fun publik jadi wrapper seam
  + versi `*Kotlin` (body ASLI vc316 yang diganti nama):
  `isAuthorized`, `bearerToken`, `previewChars`, `slicePreview`,
  `isSafeName`, `parseQuery`, `parseRoute`,
  `pathMatchesOtherMethod`, `hmacSha256Hex`, `signQueueText`,
  `hasQueueSignature`, `verifyQueueText`. `WORKER_SCOPED_ACTIONS`/
  `PORT`/`MAX_BODY_BYTES` tetap sebagai data. `WorkerQueue.kt`
  TIDAK DIUBAH (tetap memanggil `WorkerEndpointLogic.*`).
- `WorkerEndpoint.kt` — 3 helper murni jadi wrapper seam +
  versi `*Kotlin`: `readHeaders` (parse teks via seam;
  loop byte stream tetap Kotlin) + `parseHeadersKotlin`,
  `sendJson` (head via seam; tulis socket tetap Kotlin) +
  `buildResponseHeadKotlin`, `idempotencyKey` +
  `idempotencyKeyKotlin` (+ `sha256Hex16` tetap dipakai fallback).
  `acceptLoop`/`handleConn`/`dispatch`/`token`/`rotateToken`/
  `start`/`stop`/`warmUp`/`authorizeFor`/`audit`/`status`
  TIDAK disentuh.
- `RustCore.kt` — `VERSION` 6→7 + `external fun
  workerEndpointJson(request: String): String?`.
- `WorkerEndpointSeam.kt` (BARU) — helper seam slice 9 (pola
  `HttpSeam.kt`): satu saklar `rustWorkerEndpoint` + 16 fun
  bertipe (`ParsedRoute`/`ParsedHeaders`/`IdemKey`/`Slice`).
- `WorkerLogic.kt` / `WorkerQueue.kt` / `WorkerJanitorLogic.kt` /
  `WorkerRegistry.kt` / `DsApiClient.kt` / `Flavor.kt` TIDAK berubah
  (0 baris — keputusan §2.1/§2.3).

## §5. Tes paritas baru — P11 TestServer4198Vc325

File: `branch-stage/tests/TestServer4198Vc325.kt` (rust 151 +
kotlin 151 = 302 cek). Diferensial: ekspektasi SAMA di kedua
mode (hasil Rust vs hasil Kotlin lama harus identik).

- §1 seam + VERSION: `VERSION` 7 + `workerEndpointJson`,
  `available`, capabilities (version 7 + workerEndpointJson +
  modul worker_endpoint), 27 simbol JNI, saklar
  `rustWorkerEndpoint`, seam null tanpa `.so`.
- §2 parseRoute (33 cek): matriks semua aksi + jhon + method
  salah/unknown/kosong.
- §3 quirk routing (7 cek): `+` → spasi, `%` tak-valid →
  segmen mentah, trim path, 405 vs 404.
- §4 parseQuery (7 cek): `+`/decode, kosong, tanpa `=`,
  duplikat, `=` kedua, kunci TIDAK di-decode.
- §5 auth (14 cek): bearer (6) + isAuthorized (8, termasuk
  trim provided / expected tidak di-trim / unicode).
- §6 HMAC (13 cek): vektor RFC 4231 kasus 1+2, sign/verify
  round-trip + tamper/kunci-salah/tanpa-tanda-tangan/
  tanda-tangan-kosong/tanpa-newline, teks kosong, unicode +
  newline, kunci >64 byte.
- §7 safe/preview (24 cek): `isSafeName` (10),
  `slicePreview` (7, termasuk astral byte-wire),
  `previewChars` (7).
- §8 framing (9 cek/mode): rust = `parse_headers` (5) +
  `build_response` (4, string head eksak); kotlin = seam null
  (2) + fragmen `parseHeadersKotlin`/`buildResponseHeadKotlin`
  /`sendJson`/`readHeaders` memanggil seam (7).
- §9 idempotency (9 cek/mode): rust = header didahulukan +
  spawn proot eksak + default + python + nama kosong +
  enqueue/jhon eksak + aksi lain null; kotlin = seam null +
  identitas `idempotencyKeyKotlin` vs merge/ + 6 fragmen.
- §10 workerScoped (6 cek): isi set + seam.
- §11 *Kotlin identik merge/ vc316 (12 cek): 7 fun ber-kurung
  via `sameFunBody`, 5 fun satu-baris via fragmen baris
  (alasan: `funBody` return `""` untuk fun tanpa `{`).
- §12 batas (12 cek): 4 file peta lain tanpa Rust,
  acceptLoop/dispatch/token tetap Kotlin, start/stop +
  SecureStore tetap Kotlin, Flavor + DsApiClient tanpa
  sentuhan slice 9, Cargo.toml tanpa tokio/dep baru,
  WorkerQueue tetap panggil `signQueueText`/`verifyQueueText`,
  PORT 4198 + MAX_BODY_BYTES 1 MB, RUST_CORE_VERSION 7.

CATATAN: server hidup :4198 TIDAK diuji end-to-end di JVM —
`WorkerEndpoint.start(ctx)` butuh Context/SecureStore Android
+ socket loopback JVM dicegat sandbox VM (pelajaran P10).
Server hidup (worker hidup + token + kuota + respons error)
menjadi TES perangkat (TES-VC325). Yang dibuktikan P11:
seluruh logika murni + framing wire yang menentukan kontrak
byte-identik server itu.

## §6. Angka tes

- **cargo test: 329/329 lulus, 0 failed** (311 lama + 18 baru
  slice 9; dijalankan ulang sesudah perbaikan error-wrapper
  `slice_preview` — lihat §7.3).
- **Suite lokal penuh ronde 2: 1774/1775** — satu-satunya gagal
  R2-4.1 (assert STRING usang warisan vc315, DITOLERANSI seperti
  slice sebelumnya; bukan regresi).
  - P1 103/103, P2 36/36, P3 94/94, P4 235/235, P5 157/157,
    P6 155/155, P7 52/52, P8 73/73, P9 134/134,
    P10 138/138 (rust 88 + kotlin 50),
    **P11 302/302 (baru vc325: rust 151 + kotlin 151)**,
    N9 46/46, BR 45/45, IXL 72/72, R1 64/64,
    R2 68/69 (4.1).
  - C LULUS: tepat 31 file .kt berubah vs baseline vc316 + 3 baru
    (29 vc324 + 2 wrapper slice 9: WorkerEndpoint,
    WorkerEndpointLogic; baru: SqliteSeam + HttpSeam +
    WorkerEndpointSeam).
  - D1–D15 LULUS (D15 baru: fallback slice 9 dipertahankan +
    workerEndpointJson + VERSION 7 + file peta lain tanpa Rust).
  - Log ronde 2: `vc317-work/work-impl/run-tests-vc325-coord.log`
    (16 Nov 15:20 UTC / 22:20 WIB, 141.130 byte; `OVERALL
    FAIL=1` hanya dari R2-4.1). Log LEBIH BARU dari runner
    (14:51 UTC) — segar.
  - Total = 1473 (vc324) + 302 (P11) = 1775 cek; 1774 lulus.
- Ronde 1 (log sama, tertimpa ronde 2): FAIL di 3 tempat —
  P10 11.2 (hitung simbol JNI 26, kini 27), P11 §11 5 cek
  (funBody `""` untuk fun satu-baris), P11 7.15 (astral —
  klaim U+FFFD salah, bukti probe JVM). Semua didiagnosis
  ke akar + diperbaiki (lihat §7); ronde 2 = re-run penuh
  (maks 2 ronde — ronde 2 adalah re-run penuh terakhir)
  dan lulus target.

## §7. Deviasi / temuan jujur

1. **Klaim U+FFFD SALAH — dibuktikan probe JVM (temuan
   terpenting slice 9, pola pelajaran `trim()` slice 4).**
   Asumsi awal: lone surrogate di-encode UTF-8 jadi U+FFFD.
   Probe JVM (`/tmp/Probe715.kt`, kotlinc + JDK 21):
   `"a😀b".take(2)` = `"a"` + lone high surrogate (55357);
   `toByteArray(UTF_8)` = `61 3F` (`?` 0x3F, BUKAN EF BF BD);
   `JSONObject().put("content", cut).toString()` =
   `{"content":"a?"}`. Jadi wire Kotlin untuk potongan
   astral = `?` 0x3F. Perbaikan (bukan workaround): seam Rust
   kini menulis escape JSON `\uXXXX` mentah bila potongan
   membelah pasangan surrogate (`slice_preview` op) — org.json
   mem-parse-nya kembali jadi lone surrogate di string JVM,
   sehingga SEMUA perilaku hilir identik 1:1 (byte `61 3F`,
   JSON `"a?"`). Rust tidak bisa memegang lone surrogate
   di `String`, jadi escape mentah adalah satu-satunya cara
   replikasi eksak; `serde_json` tidak dipakai untuk kasus
   itu (ia menolak lone surrogate). Tes Rust dicek string
   mentah (`"slice": "a\ud83d"`); tes P11 7.15 membandingkan
   byte UTF-8 `61 3F` di KEDUA mode. Dokumen (§2.3) dan
   komentar modul diperbaiki dari klaim U+FFFD yang salah.
2. **P10 11.2 — hitung simbol JNI usang.** Tes P10 menuntut
   26 simbol; slice 9 menambah `workerEndpointJson` jadi 27.
   Diperbaiki: 11.2 → 27 (26 lama + workerEndpointJson) +
   komentar header P10. P10 lulus 138/138 di ronde 2.
3. **P11 §11 — `funBody` tidak bisa ambil fun satu-baris.**
   5 fun `*Kotlin` (`previewCharsKotlin`, `slicePreviewKotlin`,
   `isSafeNameKotlin`, `signQueueTextKotlin`,
   `hasQueueSignatureKotlin`) adalah ekspresi tunggal tanpa
   `{` → `funBody` return `""` → `sameFunBody` false. BUKAN
   bug kode (body-nya identik). Perbaikan tes: 5 cek itu
   diganti cek fragmen baris penuh di kedua sumber
   (logika + merge/). 7 fun ber-kurung tetap `sameFunBody`.
   Ronde 2: §11 12/12 di kedua mode.
4. **Assert VERSION 6→7 diterapkan di 12 file tes** (pola
   vc324 §7.2): branch-stage `TestConcatApiVc319.kt` (R1),
   `TestHttpVc324.kt` (1.1, 1.3, 11.1, 11.2),
   `TestPowSolverVc318.kt` (8.2), `TestRedactSecretsVc318.kt`
   (6.2), `TestRusqliteVc323.kt` (1.1, 1.4, 12.7),
   `TestSessFixVc322.kt` (5.18), `TestSmallModulesVc318.kt`
   (10), `TestToolRegistryAgentPromptVc320.kt` (7.1, 7.3) +
   salinan work-s2/s3/s4 (P2/P3/P4) — masing-masing
   diverifikasi per baris (grep sisa `== 6` = 0).
5. **C dihitung dari diff AKTUAL** (bukan asumsi): 31 berubah
   + 3 baru (29 vc324 + 2 wrapper slice 9 + WorkerEndpointSeam
   baru). `WorkerLogic.kt`/`WorkerQueue.kt`/`WorkerJanitorLogic.kt`
   /`WorkerRegistry.kt` TIDAK menambah hitungan (tidak diubah).
6. **`work-impl/integrated/` di-sync ulang dari live tree**
   (WorkerEndpoint, WorkerEndpointLogic, WorkerEndpointSeam
   baru, RustCore) sebelum suite; sesudahnya diff integrated
   vs live kosong untuk file .kt.
7. **Tokio TIDAK dipakai — keputusan sadar (§2.2).** Bukan
   karena tidak sempat: server Rust hidup butuh callback
   Rust→Kotlin (dispatch memanggil WorkerRegistry dkk.) yang
   dilarang peta §3.4; loop accept Kotlin sudah memakai model
   thread-per-koneksi yang sama. `Cargo.toml` tanpa tokio
   dikunci tes P11 12.9.
8. **Perbedaan wire yang dicatat (bukan cacat):** tidak ada
   perbedaan wire baru slice 9 — semua op pure menghasilkan
   byte yang sama di kedua mode (termasuk astral, §7.1).
   `serde_json` tanpa `preserve_order` mengurutkan kunci
   objek (vs `org.json` urutan sisip) — hanya memengaruhi
   urutan kunci di respons seam internal (tidak pernah
   keluar sebagai wire HTTP; `sendJson` tetap memakai
   `JSONObject.toString()` Kotlin).

## §8. Push + CI + APK

- **Push branch `vc317-rust`: commit
  `938cd51f084ae39056d6dcc0e7dbebc4e8c3814e`** (488 file,
  via `gh.py push-tree`; HEAD remote sebelumnya `4a8cd9e7`
  tidak bergerak sebelum push). Delta vs remote diverifikasi
  blob SHA 20/20 (4 baru + 16 berubah) + 0 file di luar delta.
  Main TIDAK disentuh.
- **CI run 43897736135 — SUCCESS** (completed):
  https://github.com/Sendysyah27/deepseek-harness/actions/runs/43897736135
  (1 job `build`; push 16 Nov 15:34 UTC, selesai ~15:44 UTC).
  Suite CI menjalankan P1–P11 + C + D (termasuk P11 302 cek
  + D15) + build kedua APK.
- **Artifact CI** (`vc317-work/apk-vc325-ci/`,
  `harness-vc317-apks.zip` 29.098.116 byte):
  - Main `DeepSeekHarness-v4.48.9.159.apk` 14.670.956 byte,
    MD5 `202271c62ea094aa8f8fd7b273a81f6c`,
    SHA256 `52c0b044556c251b10194d4ab361a7185488073a9eef8b174432928ca1c9b54a`.
  - Pure `Harness-v4.48.9.159-pure.apk` 14.621.804 byte,
    MD5 `b88dc45423f8694a439a3cf9b5bc8f43`,
    SHA256 `8876281b74dd10110291452e56417cc620833fc8fcfabd0396f26ba74e792c8b`.
  - Keduanya: apksigner Verifies, v2+v3 true, 1 signer,
    aapt2 325/4.48.9.159, `libharness_core.so` 4.180.208 byte
    di `lib/arm64-v8a`, simbol `workerEndpointJson` ADA,
    `hsurr:` 0, arm64-v8a saja.
  - Sidik jari CI (kunci runner, BEDA — ekspektasi, bukan
    gagal): Main `d80cf8ee3aad977ed70df8ee3635b94629e0add287c1e787db02b4ba78b2427d`,
    Pure `ac73930c2200d5130d875f1568bc15273e7f3e282cfa4f8692a918881c32e6d2`.
- **Build VM** (`vc317-work/apk-vc325-vm/`, JH `~/jdk-21-sys`,
  langkah Rust rust-lld + CC 0r; clang NDK hidup 21.0.0 —
  TIDAK perlu restore dari zip cache):
  - Main `DeepSeekHarness-v4.48.9.159.apk` 14.851.180 byte,
    MD5 `ad2087e884dd4a286c01cc08e2576155`,
    SHA256 `d057b34758dc083dc9805024a79782585493f7962ea7c47b57d704dd9549863d`,
    **sidik jari `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`
    — GERBANG COCOK (sama vc316, bisa menimpa)**.
  - Pure `Harness-v4.48.9.159-pure.apk` 14.802.028 byte,
    MD5 `74a9f6262af628c86c45bd708c7b1f47`,
    SHA256 `5a78c1d0e9eeca6efe0a268bc54037d2c6768c1865fd1ed85402fbfaba6f95e8`,
    **sidik jari `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`
    — GERBANG COCOK (sama vc316, bisa menimpa)**.
  - Keduanya: apksigner Verifies, v2+v3 true, 1 signer,
    aapt2 325/4.48.9.159, `libharness_core.so` 4.753.664 byte
    di `lib/arm64-v8a`, simbol `workerEndpointJson` ADA,
    `hsurr:` 0, arm64-v8a saja.
  - Catatan: `.so` VM (4.753.664 byte) lebih besar dari `.so`
    CI (4.180.208 byte) — toolchain runner CI ≠ VM (rustc/
    LLVM berbeda); bukan masalah: sidik jari VM COCOK dengan
    vc316 (gerbang), simbol + perilaku terverifikasi sama.
- **TES perangkat**: `vc317-work/TES-VC325-SIAP-TEMPEL.txt`
  (bahasa bayi; T1–T6 vc322 tetap berlaku + T7–T9 baru
  server :4198 via panel Worker). Tes token/kuota detail
  via curl = tugas Jhon (token tidak terlihat di layar).
- **Doc-sync**: BUILD §8 + TES di-push sebagai commit
  lanjutan (lihat riwayat branch).
