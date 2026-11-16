# BUILD-VC319 — Slice 5: ConcatApiClient.kt → Rust (vc319, 4.48.9.153)

Dokumen kerja integrator slice 5 migrasi hybrid Kotlin→Rust.
Pola dokumen mengikuti BUILD-VC318.md (slice 2/3/4).

## §1. Keputusan versi

- **vc319, VERSION_CODE 319, VERSION_NAME 4.48.9.153**, branch `vc317-rust`
  (remote main tetap vc309 — JANGAN disentuh).
- `RUST_CORE_VERSION` 2→3, `RustCore.VERSION` 2→3, capabilities +=
  `concatApiJson` + modul `concat_api_client`.
- Crate `harness-core`: 11 modul Rust, 21 simbol JNI (20 lama + 1 baru).
- ARM64-only (Rust aarch64-linux-android; APK arm64-v8a saja).
- `Flavor.kt` TIDAK disentuh (deferred). Porting MURNI — tanpa perubahan
  perilaku / fitur baru.
- Keystore VM lama dipertahankan; gerbang sidik jari vc316 berlaku:
  Main `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`,
  Pure `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`.

## §2. Desain seam (JNI stateless per panggilan)

Klien Kotlin sudah koneksi-per-panggilan (connect → auth id 1 → call id 2 →
close), jadi seam yang dipakai = **SATU fungsi JNI stateless**:

```
RustCore.concatApiJson(request: String): String?
  request  : {"op","token","args":{...},"previewDir":"..."}
  respons  : {"ok":true,"value":...}        // String untuk 20 op,
             // array [{name,path}] untuk listProjects
             {"ok":false,"kind":"auth"|"down"|"api","message":...}
  null     : Rust gagal (panic/error di boundary) -> fallback Kotlin
```

- **Port PENUH di Rust** (`harness-core/src/concat_api_client.rs`):
  21 op + summarizer + validasi lokal + resolvePath + tulis file
  previewFrame, di atas trait `Connector`/`Conn` (line-oriented).
  `TcpConnector`/`TcpConn` = produksi (BufReader/BufWriter di atas TcpStream,
  read timeout 15 dtk, TANPA write timeout — sama seperti Kotlin).
  `MockConn`/`MockConnector` = seam tes dalam-memori (cermin FakeConcat R1).
- **Tidak ada state persisten Rust** (larangan peta §3.3 atas pointer mentah
  jlong); `connectionOpener`/`previewDir` tetap di Kotlin untuk jalur fallback.
- **Wrapper Kotlin** (`ConcatApiClient.kt`): API publik TETAP PERSIS.
  Tiap fungsi publik Rust-first lewat `rustCall`/`rustString` +
  `RustFallback` internal; implementasi lama → privat `*Kotlin`
  (21 fungsi). Error protokol ASLI server (auth/down/api) DITERUSKAN apa
  adanya — BUKAN fallback. Sekali Rust gagal, saklar
  `rustConcatApiClient` dimatikan (pola `rustPowSolver`/`rustDsFailure`).
- **Quirk Kotlin DIREPLIKASI, bukan diperbaiki** (dikunci tes Rust + P5):
  - `String.format("%.2f")` membulatkan HALF_UP di atas desimal terpendek
    (`0.125 -> "0.13"`, `2.675 -> "2.68"`); Rust `{:.2}` half-even MENYIMPANG
    → `java_format_2f` manual. (Probe JVM 16 Nov 2026.)
  - `Double.toString` ilmiah ≥1e7 / <1e-3 (`1e16 -> "1.0E16"`, tanpa `+`
    di eksponen) → `java_double_string`. (Probe JVM.)
  - org.json 20240303 mem-parse desimal sebagai **BigDecimal**:
    `optString` memberi `"1E+16"` dan `"12345678.0"` (skala literal
    dipertahankan). f64 Rust tak menyimpan skala literal → `json_number_string`
    memakai pendekatan (bulat -> polos, pecahan -> gaya Double.toString).
    Hanya terjangkau bila server mengirim angka di tempat string — tidak
    pernah di lapangan. Deviasi tercatat, bukan disembunyikan.
  - Base64 `Base64.getDecoder()`: tanpa padding OK, whitespace/karakter
    invalid ditolak, `len%4==1` ditolak, padding berlebih ditolak,
    trailing bit TIDAK dicek (`"AB" -> [0]`) → `java_base64_decode`
    hand-rolled. (Probe JVM.)
  - `optInt`/`optLong`/`optDouble`/`optString`/`optBoolean` + `has()` TRUE
    untuk nilai NULL + sentinel `JSONObject.NULL` → koersi di
    `concat_api_client.rs`, dikunci tes. (Probe JVM.)
  - `JSONObject(String)` LENIENT (kutip tunggal, trailing comma, literal
    `NaN` jadi STRING, duplikat key DITOLAK) — serde_json strict pada
    keempatnya. Server `json.rs` hanya mengirim JSON strict, jadi divergensi
    ini tak terjangkau di praktik. Tercatat.
  - `read_line_capped`: hitung byte per unit UTF-16 Kotlin; Rust memakai
    byte mentah (= sama untuk semua UTF-8 valid; hanya UTF-8 invalid yang
    menyimpang — tercatat).
  - `summarizeJobStatus` state: hilang/null -> "tidak diketahui",
    hadir-kosong `""` -> `""` (else-branch memanggil ulang optString).
  - `instantiateTemplate` validasi fill pakai `has()` saja (NULL mediaId
    LOLOS — beda dengan applyEdit yang menolak NULL eksplisit).
  - `setVideo` cek pasangan rate SEBELUM resolvePath (tanpa panggilan server
    bila gagal); `applyEdit` validasi SEBELUM resolvePath; `createProject`
    location kosong -> listProjects -> induk entri pertama.
  - Locale perangkat: `String.format("%.2f")` Kotlin memakai locale default
    (perangkat id_ID memberi "23,98 fps"); Rust selalu ".". Suite JVM/CI
    (en_US) tidak bisa membedakan — tercatat.
- **Tanpa dependensi Cargo baru** (base64 hand-rolled, tanpa regex) →
  Cargo.toml/Cargo.lock TIDAK BERUBAH.
- `#![forbid(unsafe_code)]` di modul baru; tanpa `unwrap()`/`expect()` di
  jalur input luar; `catch_unwind` di SETIAP boundary JNI (lewat
  `guarded_string` yang sudah ada; panic -> JSON error/null).
- `java_is_whitespace` / `kotlin_trim` di `ds_safety_strip.rs` dijadikan
  `pub(crate)` untuk dipakai ulang (satu-satunya perubahan file slice 4;
  tanpa perubahan perilaku).

## §3. Verifikasi basis (Step 0) — SEMUA LULUS (16 Nov 2026)

- (a) Live `ConcatApiClient.kt` vs `vc317-work/merge/ConcatApiClient.kt`
  IDENTIK, 1.209 baris.
- (b) `diff -rq --exclude=target harness-core/
  vc317-work/branch-stage/harness-core/` IDENTIK.
- (c) `ConcatApiClient.kt` dibaca SELURUHNYA (1–1209) + `ConcatTools.kt`
  (hanya memakai `ConcatApi.*` publik; `CATALOGUE_KINDS` miliknya sendiri).
  Tidak ada pemakai luar atas `summarize*`/`EDIT_OP_REQUIRED`/`Connection`
  selain tes.
- `cargo test --offline` baseline: **174/174 lulus**.
- Versi terkonfirmasi: build.py (live + branch-stage) 318/4.48.9.152;
  `RUST_CORE_VERSION` 2; `RustCore.VERSION` 2.

## §4. Probe JVM (16 Nov 2026, /tmp/probe5/, /tmp/tcprobe/, /tmp/jniexp/)

- Format angka: `"%.2f"`: 24000/1001 -> "23.98", **0.125 -> "0.13"
  (HALF_UP)**, 2.675 -> "2.68", 29.97 -> "29.97". `Double.toString`:
  12.5 -> "12.5", 0.3 -> "0.3", 59.9 -> "59.9", **1e16 -> "1.0E16"**,
  0.0001 -> "1.0E-4", 1e7 -> "1.0E7", 1e-3 -> "0.001" (bukan ilmiah),
  12345678.0 -> "1.2345678E7". `Math.round(2.5)` = 3,
  `Math.round(12.45*10)` = 125. `take(80)` pada 79 x + emoji MEMOTONG
  pasangan surrogate (panjang 80 dengan high surrogate tunggal).
  trim NBSP ✓, trimEnd('/') ✓, isBlank NBSP ✓.
- Base64: padded OK; **tanpa padding OK**; whitespace/karakter invalid
  ditolak; len%4==1 ditolak; padding berlebih ditolak; "YQ=" ditolak,
  "YQ==" OK, "YQ" OK; "YWJjZA=" ditolak; **trailing bit TIDAK dicek**.
- org.json: `optInt` "12" -> 12, "12.5" -> 12, "abc" -> default,
  true -> default, 3000000000L -> -1294967296 (wrap i32), -3.7 -> -3.
  `optLong` 12.9 -> 12, 3000000000 -> 3000000000. `optString`: 12.9 ->
  "12.9", true -> "true", NULL/hilang -> "". `optBoolean`: "True" -> true,
  1 -> false. `has(n)` = true untuk NULL. `JSONObject(String)` LENIENT +
  duplikat key DITOLAK. **Desimal diparse sebagai BigDecimal**
  (probe lanjutan NUM.kt): 1e16 -> "1E+16", 12345678.0 -> optString
  "12345678.0" tapi JSONObject.toString "12345678", 1e-3 -> "0.001".
- TCP/JNI (penentu desain P5): biner Rust native — loopback TCP jalan
  (port acak DAN 7420; connect ke 7420 tanpa server -> ConnectionRefused
  polos). JVM `Socket` ke ServerSocket Java di loopback DICEGAT guard
  ("muse: Other TCP connections is turned off..."). **Eksperimen JNI
  (mini cdylib `jniexp`): Rust `TcpStream::connect` dari DALAM proses JVM
  via JNI ke ServerSocket Java di 7420 -> JALAN** (server terima, "ping" /
  "pong"). -> P5 mode rust BISA menguji jalur Rust asli end-to-end lewat
  ServerSocket Java sungguhan di 7420 + `.so` host.

## §5. Isi slice

### Rust baru
- `harness-core/src/concat_api_client.rs` (~3.500 baris): konstanta,
  `ConcatError` (Auth/Down/Api), helper org.json, `java_double_string`,
  `java_format_2f`, `is_blank`, `utf16_len`/`utf16_take`,
  `java_base64_decode`, `LineError`, trait `Conn`/`Connector`,
  `TcpConn`/`TcpConnector`, `MockConn`/`MockConnector`,
  `read_line_capped`, `plain_action`, `format_timeout`,
  `timeout_message`, `read_matching`, `auth_line`, `call_line`,
  `map_auth_error`, `map_call_error`, `call`/`call_on`, `format_rate`,
  `format_duration`, `cap_summary`, `summarize_view/media/packages/
  templates/template/started/job_status`, `EDIT_OP_REQUIRED` (58 op),
  `CATALOGUE_KINDS`, `parent_folder`, `require_done`, `fetch_view`,
  `resolve_path`, `reject_match_clip_deep`, 21 fungsi op + `dispatch`.
- `harness-core/src/lib.rs`: `pub mod concat_api_client;`.
- `harness-core/src/jni_glue.rs`: `RUST_CORE_VERSION` 2->3; capabilities
  += `concatApiJson` + modul `concat_api_client`; fungsi JNI
  `Java_harness_core_RustCore_concatApiJson` (guarded_string).
- `harness-core/src/ds_safety_strip.rs`: `java_is_whitespace` dan
  `kotlin_trim` -> `pub(crate)` (satu-satunya perubahan file slice 4).

### Kotlin
- `app/src/main/java/harness/core/RustCore.kt`: VERSION 2->3 +
  `external fun concatApiJson(request: String): String?`.
- `app/src/main/java/com/senzwh/deepharness/ConcatApiClient.kt`: 21 fungsi
  publik jadi wrapper Rust-first (`rustCall`/`rustString` + `RustFallback`
  + saklar `rustConcatApiClient`); implementasi lama -> privat `*Kotlin`
  (isi TIDAK diubah); pemanggil internal (`resolvePath`,
  `createProjectKotlin`) memakai `listProjectsKotlin`.

### Tes
- `TestConcatApiVc319.kt` (baru, P5): 157 cek — mode rust 102
  (jalur Rust asli via TCP 7420 + `.so` host) + mode kotlin 55
  (jalur `*Kotlin` via transport memori; string byte-identik).
- `cargo test`: 74 tes Rust baru (framing, auth, timeout, MAX_LINE,
  event-skip, error mapping, 21 method, format, base64, koersi).

## §6. Angka tes

- `cargo test --offline` (16 Nov 2026): **248/248 lulus**
  (174 lama + 74 baru). Perjalanan: ronde pertama 243/248 — 5 gagal,
  semuanya bug TES saya + 2 perilaku nyata yang probe lanjutan
  (NUM.kt) luruskan:
  - `java_double_string`: eksponen Java TANPA `+` ("1.0E16", bukan
    "1.0E+16") — kode diperbaiki, tes lulus.
  - `json_number_string`: double bulat di posisi string -> polos
    ("12345678"); org.json literal "12345678.0" memberi "12345678.0"
    (skala literal BigDecimal tak bisa dipulihkan dari f64) — deviasi
    tercatat di §2.
  - Tes timeout: mock kosong habis di fase auth, bukan call — skrip
    diperbaiki (auth dijawab dulu, lalu diam).
  - 2 tes pakai path "/p/A" padahal fixture view ber-path "/p/Untitled".
  - Vektor base64 "ABCDEF==" bukan "ABC" (hasil benar [0,16,131,16]).
  - Wart `int_len` tak terpakai di `java_format_2f` dibersihkan.
- Suite lokal penuh (run-tests-vc319-coord.sh, log kedua
  `vc317-work/work-impl/run-tests-vc319-coord2.log`, 16 Nov 2026):
  **920/921 lulus — satu-satunya gagal R2-4.1 (DITOLERANSI, assert usang
  warisan vc315)**. Rincian: P1 103/103, P2 36/36, P3 94/94, P4 235/235,
  N9 46/46, BR 45/45, IXL 72/72, R1 64/64, R2 68/69, P5 157/157
  (rust 102/102 + kotlin 55/55). C: tepat 12 file .kt berubah + 0 baru.
  D1–D10 semua LULUS (termasuk D10 baru: fallback ConcatApiClient).
  OVERALL FAIL=1 hanya karena exit code R2 (sama seperti vc318).
  - Ronde perbaikan 1 (satu-satunya yang dipakai): P2/P3/P4 gagal di
    assert usang `RustCore.VERSION == 2` (3 cek: P2-6.2, P3-8.2, P4-10)
    karena slice 5 menaikkan VERSION ke 3. Perbaikan: assert di-3-kan
    di 6 file (work-s2/s3/s4 + branch-stage/tests copies — keduanya
    identik sebelum perbaikan). Terverifikasi P2 36/36, P3 94/94,
    P4 235/235 sebelum suite penuh kedua.
  - Ronde perbaikan P5 (sebelum suite penuh): R23 gagal — elapsed
    timeout ~20 dtk karena `withTcp` join menunggu tidur handler 20 dtk
    sesudah timeout 15 dtk. Perbaikan: tidur handler 16 dtk + jendela
    14–20 dtk. Rerun: rust 102/102.
- CI: (diisi sesudah jalan)
- APK VM: (diisi sesudah jalan)

## §7. Deviasi jujur / temuan tengah jalan

- (diisi selama kerja berjalan)

## §8. Push + CI + APK

- (diisi sesudah push)

## §9. Tes perangkat

- Perilaku user-visible IDENTIK (porting murni) — tidak ada T-baru.
  T Concat yang relevan tetap berlaku (T-API-06/07 jalur TCP produksi,
  dst.). Bila perangkat menunjukkan perbedaan, itu regresi — laporkan.
