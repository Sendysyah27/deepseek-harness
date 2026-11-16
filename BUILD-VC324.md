# BUILD-VC324 — Slice 8: HTTP clients (vc324, 4.48.9.158)

Dokumen kerja integrator slice 8. Pola dokumen mengikuti BUILD-VC323.md.

## §1. Keputusan versi

- **vc324, VERSION_CODE 324, VERSION_NAME 4.48.9.158**, branch `vc317-rust`
  (remote main tetap vc309 — JANGAN disentuh).
- `RUST_CORE_VERSION` 5→**6**, `RustCore.VERSION` 5→**6** — slice ini
  menambah SATU fungsi JNI baru (`httpJson`).
- ARM64-only. `Flavor.kt` TIDAK disentuh. `DsApiClient.kt` TIDAK disentuh
  (split-nya slice 11).
- Porting MURNI: perilaku 1:1; quirk DIREPLIKASI (dikunci tes), bukan
  diperbaiki.
- Keystore VM lama dipertahankan; gerbang sidik jari vc316 berlaku:
  Main `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`,
  Pure `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`.

## §2. Desain seam + ruang lingkup (diukur dulu, sebelum patch)

### §2.1 Ruang lingkup slice 8 menurut peta

Peta HYBRID-MIGRATION-MAP.md §6 (urutan slice): **slice 8 = "HTTP
clients — `OcClient` + `ApiClients` + `McpHttp` → `SlotClient` →
`Gmail` → `McpOAuth` (HYBRID)", usaha M-L**. Rincian per file di peta:
- §2.1 `OcClient.kt` (127 baris) — RUST/S, `reqwest`/`ureq` + proxy.
- §2.2 `ApiClients.kt` (166 baris) — RUST/S, `reqwest`/`ureq`;
  `McpHttp.kt` (163 baris) — RUST/S-M; `SlotClient.kt` (591 baris di
  peta, 639 baris live pasca-fix vc321/vc322) — RUST/M, "padanan
  langsung `DsApiClient` minus cookie-harvest".
- §2.4 `Gmail.kt` (203 baris) — RUST/M, `rustls` + parser protokol;
  `McpOAuth.kt` (219 baris) — HYBRID/M, listener lokal + PKCE → Rust,
  buka URL auth di browser + terima redirect Intent tetap Kotlin.

### §2.2 Keputusan batas vs slice 11 (split DsApiClient)

Peta §6 slice 11: "DsApiClient split — HTTP + parse stream + PoW →
Rust; panen cookie tetap Kotlin". Batas yang dipakai di vc324:
- **`DsApiClient.kt` TIDAK disentuh sama sekali** (0 baris) — HTTP +
  parse stream + PoW-nya tetap Kotlin sampai slice 11, walau
  `SlotClient` menduplikasi protokol yang sama persis.
- `SlotClient.kt` DI-port karena peta §6 slice 8 menamainya eksplisit
  ("→ `SlotClient` →") dan §2.2 menilainya RUST/M sebagai file
  sendiri ("padanan langsung `DsApiClient` minus cookie-harvest").
  Yang di-port dari SlotClient HANYA: transport `postJson` (non-
  streaming) + mesin parse SSE (`applyChunk` family). Yang TETAP
  Kotlin di SlotClient: manajemen sesi server (stored/store/clear
  + reset proaktif vc321 + delta vc322 + primed/counter), tangga
  retry + klasifikasi DsFailure, Governor/AccountPool/prefs/MemoryDb,
  transport `postCompletion` (loop baca + `onPartial` tiap 250 mdtk +
  deteksi rate_limit/stream_terputus/jawaban_kosong), `solvePowHeader`
  (PoW sudah Rust sejak slice 2 — `PowSolver.solveDeepSeek`), dan
  `headers()`/pembangunan body JSON.
- Mesin parse SSE yang di-port ke Rust (`sse_apply`) dirancang
  generik (state JSON in/out, bukan state SlotClient) supaya slice 11
  bisa memakainya ulang untuk DsApiClient tanpa tulis ulang.
- TIDAK ADA ambiguitas lain yang perlu diputuskan: keenam file slice 8
  adalah file yang berbeda dari file slice 11 (`DsApiClient.kt`).

### §2.3 Keputusan desain seam

- **Satu modul Rust `http` + satu modul `sse`** (bukan satu modul per
  klien). Alasan: keenam klien memakai pola yang sama (request HTTP
  blocking + header + body JSON/form + baca Retry-After); yang
  berbeda hanya URL/header/timeout/cap — semua itu parameter JSON.
  Logika murni per klien (extractResult MCP, shape fingerprint,
  decodeWord Gmail, PKCE OAuth, mesin SSE) hidup sebagai op di seam
  yang sama.
- **Seam: SATU fungsi JNI `httpJson(request: String): String?`**,
  JSON in/out (pola slice 5/6/7). Request `{"op":...}`; respons
  `{"ok":true,...}` atau `{"ok":false,"error":"..."}`; `null` =
  panic/boundary gagal → fallback Kotlin.
- **Transport: `ureq` 2.12.1** (`default-features = false`,
  `features = ["tls"]` → rustls 0.23 + webpki-roots 0.26, direct,
  tanpa proxy — cocok dengan `OcClient` yang memakai
  `Proxy.NO_PROXY`; klien lain memakai `URL.openConnection()`
  tanpa proxy eksplisit, jadi direct adalah perilaku app di
  perangkat). TANPA gzip/charset/cookies/json (fitur default
  dimatikan) supaya perilaku wire sedekat mungkin dengan
  `HttpURLConnection` Kotlin (tidak ada `Accept-Encoding: gzip`
  tambahan, tidak ada cookie jar). `tokio` TIDAK dipakai (peta §7:
  tokio baru slice 9).
- **Op `request`** — `{"op":"request","method","url","headers":{...},
  "body":<string|null>,"connect_ms","read_ms","cap_chars",
  "want_headers":[...]}` → `{"ok":true,"code":n,"body":"...",
  "headers":{...}}`. Transport gagal (timeout, refused, DNS, TLS)
  → `{"ok":false,"error":...}` (bukan panic). Respons non-2xx
  TETAP dikembalikan sebagai `ok:true` + code + body (Kotlin yang
  memutuskan throw/return, sama seperti membaca `errorStream`).
  `cap_chars`: 0 = tanpa cap; >0 = baca maks `cap_chars` char
  (OcClient: 2.000.000 — replikasi batas baca Kotlin; yang lain 0).
  Deviasi yang dicatat: cap dihitung per char sesudah decode
  UTF-8-lossy (Kotlin menghitung char dari `BufferedReader`);
  untuk ASCII (semua respons nyata di sini) identik.
- **Op murni** (tanpa I/O, semua 1:1 dengan Kotlin lama):
  - `mcp_extract` — `McpHttp.extractResult` (JSON langsung, lalu
    pindai baris `data:` SSE; `result` terakhir menang; `error` →
    `{"ok":false,"error":"MCP error: ..."}`).
  - `shape_fingerprint` — `ApiClients.shapeFingerprint`
    (`"json:k1,k2"` kunci level-atas terurut; non-JSON → `"non-json"`).
  - `decode_word` — `Gmail.decodeWord` (RFC 2047 B/Q + collapse
    spasi Java-`\s` + `trim()` Kotlin = `kotlin_trim` slice 4).
  - `gmail_headers` — `Gmail.fetchHeaders` (baris `From:/Subject:/
    Date:` → satu string; `Date` di-`take(31)`).
  - `pkce_challenge` — `McpOAuth.challenge` (SHA-256 verifier →
    b64url tanpa padding; vektor RFC 7636 Appendix B dikunci tes).
  - `form_encode` — `URLEncoder.encode` UTF-8 (spasi → `+`,
    `.-*` lolos, sisanya `%XX` hex huruf besar).
  - `form_body` — gabung `k=v` dengan `&` (dipakai `postFormRaw`).
  - `oauth_auth_url` — rakit URL auth loopback (param ter-encode +
    `access_type=offline&prompt=consent` bila `offline`).
  - `oauth_parse_callback` — parse query callback (`+` → spasi,
    `%XX`; state/code/error).
  - `sse_apply` — mesin parse SSE `SlotClient.applyChunk` family
    (state JSON in/out: `last_path`, `saw_finish`,
    `fragment_thinking`, `fragment_count`, `current_thinking`;
    keluar `content_delta` + `thinking_delta` + `saw_finish` +
    `skipped` untuk payload bukan-JSON — cermin `catch { continue }`
    Kotlin).
- **Fallback + saklar per file**: tiap wrapper menyimpan
  `var rust<OcClient|ApiClients|McpHttp|SlotClient|Gmail|McpOAuth>
  = RustCore.available`; public fun mencoba seam Rust dulu, bila
  `null`/error → jalankan implementasi Kotlin lama (`*Kotlin`,
  privat, body ASLI yang diganti nama). API publik TETAP PERSIS.
  DEVIASI dari pola slice 7 (dicatat sadar): untuk op `request`,
  `{"ok":false}` = gagal TRANSPORT (server mati/timeout — bukan
  cacat Rust) → wrapper fallback ke transport Kotlin TANPA
  mematikan saklar (Kotlin akan gagal dengan cara yang sama, dan
  menghasilkan pesan error pemanggil yang benar); saklar hanya
  dimatikan bila seam mengembalikan `null`, JSON tak-terparse, atau
  exception di seam. Untuk op murni, `ok:false`/null → matikan
  saklar seperti pola lama.
- **Yang TETAP Kotlin di vc324 (sisa jujur, bukan regresi):**
  - `OcClient.openEventStream` — mengembalikan handle hidup
    (`HttpURLConnection` + `BufferedReader`) ke pemanggil; tidak
    bisa lewat seam request/response JSON. Transportnya tetap
    Kotlin; 8 fun lain pindah ke Rust.
  - `Gmail` transport IMAP/SMTP (socket + `SSLSocketFactory` +
    STARTTLS) — TETAP Kotlin. Alasan: port TLS penuh butuh kredensial
    Gmail hidup + endpoint TLS nyata untuk verifikasi ujung-ke-ujung;
    VM/CI tidak punya keduanya, dan port buta atas kode pembawa
    kredensial melanggar disiplin "buktikan" (preseden slice 7:
    wrapper `MemoryDb` ditunda dengan alasan yang sama). Yang
    di-port: `decodeWord` + `fetchHeaders` (logika murni, teruji).
    Seam `httpJson` sudah membawa TLS (rustls) sehingga transport
    Gmail bisa pindah di rilis berikut tanpa seam baru.
  - `McpOAuth` listener loopback (`ServerSocket` 8931 + `accept()` +
    kirim `Intent.ACTION_VIEW` browser sistem) — TETAP Kotlin
    (peta §2.4 HYBRID: Intent adalah API Android). Yang di-port:
    PKCE + form-encode + POST token/device (`postFormRaw`).
  - `SlotClient.postCompletion` transport (loop baca + `onPartial`)
    — TETAP Kotlin (peta §3.4: Rust tidak callback ke Kotlin di
    slice awal); mesin parse-nya pindah ke Rust (`sse_apply`).
  - `ApiClients` token/prefs (`SecureStore`, `SharedPreferences`),
    `McpHttp` orkestrasi handshake + retry-401, `McpOAuth`
    orkestrasi loopback/device-flow — TETAP Kotlin.
- **Dependensi baru**: `ureq = { version = "2.12",
  default-features = false, features = ["tls"] }` +
  `sha2 = "0.10"` (PKCE). `Cargo.lock` di-merge kunci (nama,
  versi) seperti pelajaran slice 3.
- `#![forbid(unsafe_code)]` di `http.rs` + `sse.rs`; TANPA
  `unwrap()`/`expect()` di jalur input luar (request JSON, URL,
  header, body HTTP, baris SSE, string Gmail/OAuth dari Kotlin).
- **Quirk yang direplikasi (dikunci tes P10):** cap baca OcClient
  2.000.000 char; `Content-Type: application/json` selalu dikirim
  OcClient bahkan untuk GET; `Retry-After` dibaca dari header
  respons (SlotClient/McpHttp); `extractResult` — `result`
  terakhir dari baris `data:` menang; `shapeFingerprint` hanya
  untuk respons sukses; `decodeWord` — kata B/Q tak-valid → data
  mentah (bukan error); `fetchHeaders` TIDAK meng-unfold header
  lipat (tiap baris dicek sendiri); `form_encode` spasi → `+`;
  SSE — `fragments/(-?\d+)`, indeks -1 = current, indeks ==
  fragmentCount → declare false, `BATCH` rekursif,
  `FINISHED`/`WIP` tanpa akhiran `content` diabaikan.

### §2.4 Inventaris file target (dibaca penuh sebelum patch)

| File | Baris live | vs merge/ (vc316) | Isi yang di-port |
|---|---|---|---|
| `OcClient.kt` | 127 | IDENTIK | `http` + 8 fun (probe/listSessions/createSession/sendPrompt/statusMap/messages/abort/replyPermission); `openEventStream` tetap Kotlin |
| `ApiClients.kt` | 166 | IDENTIK | `callHttp` + `shapeFingerprint`; token/prefs/execute tetap Kotlin |
| `McpHttp.kt` | 163 | IDENTIK | `post` + `extractResult`; handshake/listTools/callTool + retry-401 tetap Kotlin |
| `SlotClient.kt` | 639 | BERBEDA (hanya fix vc321/vc322: `deltaPrompt`, reset proaktif, primed/counter — lihat §3) | `postJson` + mesin SSE (`applyChunk`/`applyValueObject`/`routeIsThinking`/`declareFragment`); sesi/retry/transport completion tetap Kotlin |
| `Gmail.kt` | 203 | IDENTIK | `decodeWord` + `fetchHeaders`; IMAP/SMTP tetap Kotlin |
| `McpOAuth.kt` | 219 | IDENTIK | `challenge` + `enc` + `postFormRaw` (+ rakit URL auth + parse callback); loopback/device orchestration tetap Kotlin |

## §3. Basis verifikasi (langkah 0 — semua LULUS sebelum patch)

- (a) 5 file target vs `vc317-work/merge/` IDENTIK (OcClient,
  ApiClients, McpHttp, Gmail, McpOAuth). `SlotClient.kt` BERBEDA
  hanya oleh fix vc321/vc322 (deltaPrompt di 7 situs + reset
  proaktif + primed/counter; `grep -c Vc321|Vc322` = 16) — file
  itu BUKAN baseline vc316 lagi; hash live dicatat di bawah.
- (b) `diff -rq --exclude=target harness-core/
  vc317-work/branch-stage/harness-core/` IDENTIK (exit 0).
- (c) `cargo test --offline` 287/287 (dijalankan ulang).
- (d) clang NDK masih jalan: `bin/clang --version` = Android
  clang 21.0.0 (tidak perlu pulihkan dari zip cache).

Hash live file target (md5sum, sebelum patch):
- OcClient.kt `b58282a2b9f160e69ddb1aa5e5f9e1e8`
- ApiClients.kt `43344ed71707c319cc1380721146f1e2`
- McpHttp.kt `74a15ed8e6bcbcad4533e23ef8953b99`
- SlotClient.kt `20decddada7a5d7dfa60ed8356c9c82b`
- Gmail.kt `42271ab7ca807d6ec37ccfa64e834eea`
- McpOAuth.kt `c0c9f59f274f3457b7571699474c619f`

## §4. Isi patch

Rust (crate `harness-core`, `RUST_CORE_VERSION` 5→6):

- `src/http.rs` (BARU, `#![forbid(unsafe_code)]`): `http_json(request)`
  + op `request` (ureq 2.12.1 blocking: method/url/headers/body/
  connect_ms/read_ms/cap_chars/want_headers → `{ok,code,body,headers}`;
  transport gagal → `{ok:false,error}`), `mcp_extract`,
  `shape_fingerprint`, `decode_word`, `gmail_headers`,
  `pkce_challenge`, `form_encode`, `form_body`, `oauth_auth_url`,
  `oauth_parse_callback`. TANPA `unwrap()`/`expect()` di luar
  `#[cfg(test)]`.
- `src/sse.rs` (BARU, `#![forbid(unsafe_code)]`): `sse_apply` — port
  1:1 mesin parse SSE SlotClient (`applyChunk`/`applyValueObject`/
  `routeIsThinking`/`declareFragment`); state JSON in/out; `skipped`
  untuk payload bukan-JSON.
- `src/lib.rs`: +`pub mod http;` +`pub mod sse;`.
- `src/jni_glue.rs`: `RUST_CORE_VERSION` 5→6; capabilities
  +`"httpJson"` + modul `"http","sse"`; JNI
  `Java_harness_core_RustCore_httpJson` (`guarded_string_nullable` +
  `catch_unwind`, pola `sqliteJson`).
- `Cargo.toml`: +`ureq = { version = "2.12", default-features =
  false, features = ["tls"] }` +`sha2 = "0.10"`. `Cargo.lock`:
  62 → 119 paket, murni aditif (0 dihapus, 0 versi berubah, 0
  duplikat nama+versi — diverifikasi perbandingan set): ureq
  2.12.1, sha2 0.10.9, rustls 0.23.46, webpki-roots 0.26.11,
  ring 0.17.14, url 2.5.8.

Kotlin (6 file berubah + 1 baru + RustCore; porting murni, API publik
TETAP PERSIS):

- `OcClient.kt` — `http()` → seam Rust + `httpKotlin` (body lama) +
  saklar `rustOcClient`; `openEventStream` TIDAK disentuh.
- `ApiClients.kt` — `callHttp` → seam Rust + `callHttpKotlin` +
  logika pasca-transport diekstrak ke `callHttpResult` (dipakai
  kedua jalur); `shapeFingerprint` + `shapeFingerprintKotlin` +
  saklar `rustApiClients`.
- `McpHttp.kt` — `post` → seam Rust + `postKotlin`;
  `extractResult` → `HttpSeam.mcpExtract` + `extractResultKotlin` +
  saklar `rustMcpHttp`.
- `SlotClient.kt` — `headersMap` baru (dipakai `headers()` +
  `postJson` Rust); `postJson` + `postJsonKotlin` +
  `postJsonResult` bersama; `postCompletion` loop disatukan:
  `sseApply` Rust per baris + `sawFinish` lokal (ganti
  `state.sawFinish`), fallback `applyChunkKotlin` bila seam mati
  di tengah stream; 4 fungsi SSE diganti nama `*Kotlin` +
  saklar `rustSlotClient`. Fix vc321/vc322 DIPERTAHANKAN utuh
  (deltaPrompt, reset proaktif, primed/counter — tidak satu baris
  pun diubah selain penambahan wrapper).
- `Gmail.kt` (`GmailLite`) — `decodeWord` + `decodeWordKotlin`;
  `fetchHeaders` → `HttpSeam.gmailHeaders(lines)` per id +
  `fetchHeadersEntryKotlin` baru (fungsi murni per id, memakai
  `decodeWordKotlin`); saklar `rustGmail`. Transport IMAP/SMTP
  TIDAK disentuh.
- `McpOAuth.kt` — `challenge`/`enc`/`postFormRaw` + versi
  `*Kotlin` + saklar `rustMcpOAuth`; `buildAuthUrl` +
  `parseCallbackQuery` baru (Rust lalu fallback Kotlin) dipakai
  `startLoopback`; listener loopback TIDAK disentuh.
- `RustCore.kt` — `VERSION` 5→6 + `external fun httpJson(request:
  String): String?`.
- `HttpSeam.kt` (BARU) — helper seam slice 8 (`request` + op murni
  + `sseApply` + `SseStep`); `rustHttp = RustCore.available`.
- `DsApiClient.kt` TIDAK berubah (0 baris — keputusan §2.2).
  `Flavor.kt` TIDAK disentuh.

## §5. Tes paritas baru — P10 TestHttpVc324

File: `branch-stage/tests/TestHttpVc324.kt` (rust 88 + kotlin 50 =
138 cek). Server HTTP lokal terskrip di dalam file (`LocalHttp`:
`ServerSocket` port 0, thread daemon, satu request per koneksi,
rute `/session`, `/session/s1/*`, `/retry`, `/big`, `/err500`,
`/echo`, `/mcp`, `/mcp-sse`, `/bad/*` → 500).

- §1 seam + VERSION: `VERSION` 6 + `httpJson`, `available`,
  capabilities (version 6 + httpJson + modul http/sse),
  `rustHttp`.
- §2 op `request` (rust): GET 200 + body, POST echo, non-2xx
  TETAP dikembalikan, `Retry-After`, `cap_chars=10`, transport
  gagal → null (saklar hidup), URL/metode tak-valid → null.
- §3 OcClient publik diferensial rust vs kotlin (probe,
  listSessions, createSession, sendPrompt 204, statusMap,
  messages, abort, replyPermission — jalur 200 + jalur 500).
- §4 McpHttp publik (listTools JSON + SSE `data:`, callTool
  gabung teks + isError) + `mcpExtract` (JSON langsung, SSE
  result terakhir menang, tanpa result → null, error → throw
  `MCP error:`).
- §5 `shapeFingerprint` (kunci terurut, non-JSON, objek kosong).
- §6 McpOAuth op murni (vektor RFC 7636 Appendix B, formEncode
  spasi/karakter khusus/`.-*_`/UTF-8, formBody urutan dipertahankan,
  oauthAuthUrl offline, oauthParseCallback `+`/`%XX`/bad `%`).
- §7 Gmail op murni (`decodeWord` B/Q/tak-valid/collapse+trim
  termasuk NBSP U+00A0, `gmailHeaders` + `Date` take(31) +
  "(tanpa subjek)").
- §8 `sse_apply` (15 cek: path/last_path, thinking, FINISHED,
  FINISHED-tanpa-content, BATCH, fragments declare, routing
  fragment dikenal, indeks==count, indeks -1, value-object
  bertipe, non-JSON skipped, koersi array "42").
- §9 fallback (6 wrapper `*Kotlin` + 6 saklar + HttpSeam).
- §10 batas (DsApiClient tanpa Rust, Flavor tak berubah, logika
  sesi vc321/vc322 dipertahankan, transport Gmail + listener
  McpOAuth + openEventStream tetap Kotlin).
- §11 struktur Rust (`RUST_CORE_VERSION` 6, 26 simbol JNI,
  Cargo ureq/sha2, `pub mod http/sse`, `forbid(unsafe_code)`).

CATATAN: wrapper TIDAK diinstansiasi penuh di JVM (Context stub)
— P10 menguji seam + wrapper publik + identitas sumber; wrapper
end-to-end (DeepSeek/Gmail nyata) di perangkat (TES-VC324).
`applyChunkKotlin` dkk. tidak bisa dipanggil langsung dari JVM
(privat + butuh instance SlotClient ber-Context stub); paritas
SSE dibuktikan dua lapis: (1) fixture Rust ditelusur
cabang-per-cabang dari sumber Kotlin, (2) body `*Kotlin`
dicek identik dengan merge/ vc316 sesudah rename 4 nama fungsi.
Sama untuk `ApiClients.execute` / `McpOAuth.startLoopback` /
`Gmail.unread` (butuh Context/SecureStore/server nyata) — item
tes perangkat, bukan klaim P10.

## §6. Angka tes

- **cargo test: 311/311 lulus, 0 failed** (287 lama + 24 baru
  slice 8; dijalankan ulang sesudah perbaikan error-wrapper
  `http_json` — lihat §7.6).
- **Suite lokal penuh ronde 2: 1472/1473** — satu-satunya gagal
  R2-4.1 (assert STRING usang warisan vc315, DITOLERANSI seperti
  slice sebelumnya; bukan regresi).
  - P1 103/103, P2 36/36, P3 94/94, P4 235/235, N9 46/46,
    BR 45/45, IXL 72/72, R1 64/64, R2 68/69 (4.1),
    P5 157/157 (kotlin 55 + rust 102),
    P6 155/155 (kotlin 75 + rust 80),
    P7 52/52, P8 73/73,
    P9 134/134 (rust 107 + kotlin 27),
    **P10 138/138 (baru vc324: rust 88 + kotlin 50)**.
  - C LULUS: tepat 29 file .kt berubah vs baseline vc316 + 2 baru
    (24 vc323 + 5 wrapper slice 8: OcClient, ApiClients,
    McpHttp, Gmail, McpOAuth; SlotClient sudah berubah sejak
    vc321/vc322; HttpSeam.kt baru; MemoryDb + Flavor.kt +
    DsFailure.kt tidak berubah; DsApiClient ada di diff SEJAK
    vc321/vc322 — tanpa Rust dicek D14).
  - D1–D14 LULUS (D14 baru: fallback slice 8 dipertahankan +
    httpJson + VERSION 6 + DsApiClient tanpa Rust).
  - Log ronde 2: `vc317-work/work-impl/run-tests-vc324-coord.log`
    (16 Nov 13:21 UTC / 20:21 WIB, 125.349 byte; `OVERALL
    FAIL=1` hanya dari R2-4.1). Log LEBIH BARU dari runner
    (13:10 UTC) — segar.
  - Total = 1335 (vc323) + 138 (P10) = 1473 cek; 1472 lulus.
- Ronde 1 (log sama, tertimpa ronde 2): FAIL di 4 tempat —
  P6 7.3 (capabilities version 5), P10 rust 11.2 (hitung simbol
  JNI), P10 kotlin §3/§4 (loopback JVM terblokir sandbox), C
  (cek DsApiClient salah). Semua didiagnosis ke akar + diperbaiki
  (lihat §7); ronde 2 = re-run penuh (maks 2 ronde — ronde 2
  adalah re-run penuh terakhir) dan lulus target.

## §7. Deviasi / temuan jujur

1. **Perbaikan semantik error `http_json` (Rust + HttpSeam).**
   Ditemukan saat review P10: `http_json` mengembalikan `Err`
   → JNI null untuk input buruk (mis. URL tak-valid, `%` tak-valid)
   — itu akan mematikan saklar untuk SEMUA cek rust-mode
   berikutnya. Perbaikan: `http_json` membungkus dispatch
   (`Ok(dispatch(...).unwrap_or_else(|e| err_json(&e)))`) —
   error op → `{"ok":false}` JSON; JNI null HANYA bila panic/FFI
   gagal. `HttpSeam.opValue`/`oauthParseCallback`/`sseApply`:
   `ok:false` → null TANPA menyentuh saklar. `request` sudah
   begitu; `mcpExtract` tetap throw untuk MCP error. Dua tes Rust
   diperbarui ke semantik baru (bad URL/metode + bad `%` →
   `ok:false`); cargo test diulang: tetap 311/311.
2. **Assert VERSION 5→6 diterapkan di 11 file tes** (pola vc323
   §7.4): work-s2/TestPowSolverVc318.kt,
   work-s3/TestRedactSecretsVc318.kt,
   work-s4/TestSmallModulesVc318.kt, branch-stage/tests/
   TestPowSolverVc318.kt, TestRedactSecretsVc318.kt,
   TestSmallModulesVc318.kt, TestConcatApiVc319.kt (R1),
   TestToolRegistryAgentPromptVc320.kt (7.1 + 7.3),
   TestRusqliteVc323.kt (1.1, 1.4, 12.7),
   TestSessFixVc322.kt (5.18) — masing-masing diverifikasi per
   baris. 7.3 P6 sempat terlewat sed awal (pola `version 5` di
   capabilities, bukan `VERSION == 5`) — ketahuan di ronde 1
   suite, diperbaiki, lulus ronde 2.
3. **C dihitung dari diff AKTUAL** (bukan asumsi): 29 berubah + 2
   baru (24 vc323 + 5 wrapper slice 8 + HttpSeam.kt baru).
   SlotClient TIDAK menambah hitungan (sudah berubah sejak
   vc321/vc322).
4. **Cek C DsApiClient SALAH di ronde 1, diperbaiki.** Runner
   awal menuntut DsApiClient.kt TIDAK ada di diff — keliru:
   file itu sudah berbeda sejak fix vc321/vc322 (guard reset +
   delta). Yang dijaga slice 8 adalah DsApiClient TANPA Rust —
   itu dicek D14 (grep `HttpSeam|httpJson|rustSlotClient` =
   kosong). Cek C diganti catatan INFO; header C diperbarui.
5. **P10 11.2 — hitung simbol JNI salah di ronde 1.** Tes
   menghitung kemunculan mentah prefix `Java_harness_core_RustCore_`
   (27 — termasuk baris komentar 3 `jni_glue.rs`). Diperbaiki:
   hitung definisi `pub extern "C" fn Java_harness_core_RustCore_`
   = 26 (25 lama + httpJson). Baseline "25 simbol" di langkah 0
   adalah hitungan definisi yang sama.
6. **Sandbox VM mencegat TCP loopback JVM (temuan ronde 1,
   BUKAN bug kode).** Bukti: `Socket`/`HttpURLConnection` ke
   `127.0.0.1:<port server lokal>` tidak pernah sampai ke
   `accept()` server (tanpa jejak ACCEPT); balasan mentah dari
   socket: `muse: Other TCP connections is turned off for this
   assistant...` (deny-proxy runtime). `.so` Rust (syscall
   native, ureq) TIDAK dicegat — mode rust §2/§3 lulus penuh
   melawan server yang sama. Akibat: P10 mode kotlin gagal
   SEMUA cek "server hidup" (§3/§4) di ronde 1, padahal kode
   `httpKotlin`/`postKotlin` adalah kode vc316 ASLI (tidak
   diubah isinya — hanya diganti nama).
   Perbaikan P10 (bukan perbaikan kode app): deteksi
   `loopbackBlocked()` (socket mentah + penanda deny-proxy) di
   awal tes; bila mode kotlin + terblokir, cek live §3/§4
   diganti (a) cek tak-terjangkau — nilai gagal yang benar
   (probe false, listSessions null, dst. — semuanya lulus),
   (b) identitas body fungsi vs merge/ vc316
   (`httpKotlin`≡`http`, `postKotlin`≡`post`,
   `extractResultKotlin`≡`extractResult`, via `funBody`
   string-aware) — ketiganya LULUS. Bila merge/ tidak ada
   (CI), cek identitas SKIP (bukan lulus). Di CI (tanpa
   sandbox ini) loopback TIDAK terblokir, jadi cek live
   §3/§4 mode kotlin jalan normal di sana. Preceden:
   P5 mode kotlin memakai transport memori, bukan TCP.
   **Konsekuensi:** P10 kotlin lokal = 50 cek (bukan 88):
   38 cek live-diganti + identitas. Total P10 = 88 + 50 = 138.
7. **`work-impl/integrated/` di-sync ulang dari live tree**
   (6 wrapper + HttpSeam.kt + RustCore.kt) sebelum suite;
   sesudahnya diff integrated vs live kosong untuk file .kt.
8. **Bump 324/4.48.9.158** di live build.py DAN branch-stage
   build.py (hanya baris VERSION; isi keduanya memang berbeda,
   tidak saling ditimpa). Blok 0r CC/CXX/AR terverifikasi ada
   di kedua salinan sesudah bump. clang NDK hidup sepanjang
   kerja (tidak perlu restore dari zip cache).
9. **Perbedaan wire yang dicatat (bukan cacat):** ureq mengirim
   `User-Agent: ureq/2.12.1` (Kotlin: default Android); ureq
   `connect_ms==0` → default 30 dtk (Kotlin: tak-berhingga —
   tidak ada pemanggil yang memakai 0); cap `request` dihitung
   per char sesudah decode UTF-8-lossy (Kotlin: char dari
   BufferedReader — identik untuk ASCII); serde_json tanpa
   `preserve_order` mengurutkan kunci objek (vs org.json urutan
   sisip — hanya memengaruhi teks kompak objek error MCP; tes
   assert prefix, bukan kesamaan byte).

## §8. Push + CI + APK

- **Pra-cek drift (sebelum push 1):** remote HEAD `db46fbcc`
  terverifikasi (cocok basis vc323, tidak bergerak); main tetap
  `2e6245e3` (vc309). Selisih remote tree vs staging tepat
  21 berubah + 5 baru, 0 remote-only:
  berubah = android.yml, 6 wrapper (OcClient, ApiClients,
  McpHttp, SlotClient, Gmail, McpOAuth) + RustCore.kt, build.py,
  Cargo.lock, Cargo.toml, jni_glue.rs, lib.rs, 7 file tes
  (assert VERSION 5→6), run-suite-ci.sh;
  baru = BUILD-VC324.md, HttpSeam.kt, http.rs, sse.rs,
  TestHttpVc324.kt.
- **Push 1:** 16 Nov 2026 ~20:34 WIB, branch `vc317-rust`,
  commit `4e24db8e0aec423c7cda8d210d1499c9ae6a92b4`, 483 file via
  `gh.py push-tree` (parent `db46fbcc`). Verifikasi: remote HEAD
  = 4e24db8e; remote tree 483 blob == staging 483 file, 0 beda;
  main utuh.
- **CI ronde 1:** run
  [#43877083136](https://github.com/Sendysyah27/deepseek-harness/actions/runs/43877083136)
  (push, head 4e24db8e) — **FAILURE**, job `build`. Log (trik 302,
  `/tmp/ci-vc324-r1.log`): P1–P9 semua HIJAU; satu-satunya gagal
  = **P10 mode rust 87/88, cek 1.1** ("RustCore.kt VERSION 6 +
  httpJson"). Akar: tes §1.1 membaca `harness/core/RustCore.kt`
  relatif ke treeDir — jalan di layout lokal (integrated) tapi
  TIDAK di layout CI (treeDir = `.../deepharness`, RustCore ada
  3 tingkat di atas). Akar yang SAMA dengan P8 5.18 vc322 dan
  P9 12.7 vc323. Perbaikan: §1.1 mencoba 4 tingkat (0/1/2/3).
  Diverifikasi lokal di KEDUA layout SEBELUM re-push:
  integrated rust 88/88, layout CI rust 88/88, layout CI kotlin
  47/47 (3 cek identitas SKIP — merge/ tidak ada di layout itu
  tanpa symlink; di CI symlink dibuat run-suite-ci.sh, jadi
  cek itu jalan di sana).
- **Push 2 (fix CI):** 16 Nov 2026 ~20:51 WIB, branch
  `vc317-rust`, commit `8f93fd25e97fac6c854aeec9ddc17fededf446d7`,
  483 file via `gh.py push-tree` (hanya
  `tests/TestHttpVc324.kt` berubah vs push 1). Pesan: "vc324:
  fix P10 1.1 — RustCore.kt coba 4 tingkat".
- **CI ronde 2:** run
  [#43879816951](https://github.com/Sendysyah27/deepseek-harness/actions/runs/43879816951)
  (push, head 8f93fd25) — **SUCCESS**. Semua step hijau:
  cargo test, P1–P10 (termasuk P10 138/138: rust 88 + kotlin
  50 — di CI loopback TIDAK terblokir, jadi cek live §3/§4
  mode kotlin jalan normal di sana), N9, BR, IXL, R1, R2
  (4.1 ditoleransi), C (termasuk cek slice 8 baru), D
  (termasuk D14 baru), build APK senz+pure, verify, upload.
- **Artifact CI** (`vc317-work/apk-vc324-ci/`,
  `harness-vc317-apks.zip` 29.049.364 byte, artifact
  14232222805):
  - Main `DeepSeekHarness-v4.48.9.158.apk` — 14.642.284 byte,
    MD5 `034fecd3f13f687c9073c062c007583b`,
    SHA256 `d9c0d81d1f6be544d2b414fce1690ca40ead55ecb78d4b49a40f11614cb01d73`.
  - Pure `Harness-v4.48.9.158-pure.apk` — 14.597.228 byte,
    MD5 `48b247af8f00a1c71961a5c077e6983a`,
    SHA256 `2df4032fed5a6faba0bf5f89204783cccb6ae58c6104483bb57b3d8ec2534db6`.
  - Keduanya: aapt2 324/4.48.9.158, apksigner v2+v3, 1 signer,
    `lib/arm64-v8a/libharness_core.so` ada (4.129.976 byte),
    simbol `Java_harness_core_RustCore_httpJson` ADA, 26 simbol
    JNI, `hsurr:` 0. Sidik jari CI BEDA (kunci runner debug:
    Main `3edf1866…`, Pure `b40e45df…`) — EKSPEKTASI, bukan
    kegagalan.
- **Build VM** (`vc317-work/apk-vc324-vm/`, log build-senz.log +
  build-pure.log; clang NDK 21.0.0 hidup, blok 0r jalan):
  - Main `DeepSeekHarness-v4.48.9.158.apk` — 14.822.508 byte,
    MD5 `ee55ac5ba0cbfeb09cf42fe50a7db990`,
    SHA256 `952f226fe5a7b1b2edbff5161fc8415269e66b8eb0b3f61c59ded68d3b37d715`.
  - Pure `Harness-v4.48.9.158-pure.apk` — 14.777.452 byte,
    MD5 `f646e17e34ae4679a9f34cc803c15c10`,
    SHA256 `7b9f073483272baabca8d2049d17c44c93e7c95bef39e70a20830ab2e2b036e7`.
  - Keduanya: aapt2 324/4.48.9.158, apksigner v2+v3, 1 signer,
    `libharness_core.so` ada (4.709.552 byte), simbol httpJson
    ADA, 26 simbol JNI, `hsurr:` 0.
  - **GERBANG SIDIK JARI LULUS:** Main
    `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`
    ✓, Pure
    `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`
    ✓ — sama seperti vc316, BISA menimpa.

## §9. Tes perangkat (TES-VC324-SIAP-TEMPEL)

Perilaku yang kelihatan di layar TIDAK berubah (porting murni).
T1–T6 dari TES-VC322 TETAP BERLAKU semua. Tambahan vc324
(T7–T11) ada di `vc317-work/TES-VC324-SIAP-TEMPEL.txt` (bahasa
bayi): chat DeepSeek normal + streaming (OcClient/SlotClient/
SSE Rust), sesi panjang berurutan (postCompletion), Gmail
(decodeWord/fetchHeaders), MCP listTools/callTool, dan OAuth
loopback (opsional, bila ada server MCP yang butuh login).
