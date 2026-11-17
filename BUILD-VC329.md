# BUILD-VC329 — Slice 12 (TERAKHIR): Otak Latar/Room → Rust (vc329, 4.48.9.163)

Dokumen kerja integrator slice 12 — slice TERAKHIR migrasi hybrid
Kotlin→Rust (keputusan koordinator 17 Nov 05:14 WIB — logika
BgBrain/GroupChat/DsAgent/OcCoord → Rust; WebView/notifikasi tetap
Kotlin). Sesudah slice ini mendarat, **migrasi dinyatakan PENUH**
(lihat §9). Pola dokumen mengikuti BUILD-VC328.md.
Probe: `/tmp/probe329/` — probe JVM `Probe329.kt` + `Probe329b.kt`
(kotlinc + `char-tests/json-20240303.jar`, JDK `~/jdk-21-sys`).

## §1. Keputusan versi

- **vc329, VERSION_CODE 329, VERSION_NAME 4.48.9.163**, branch `vc317-rust`
  (remote main tetap vc309, commit `2e6245e3` — JANGAN disentuh).
- `RUST_CORE_VERSION` **9 → 10** (`jni_glue.rs`, `RustCore.VERSION` = 10)
  — empat fungsi JNI baru `bgBrainJson` / `groupChatJson` /
  `dsAgentJson` / `ocCoordJson` (modul `bg_brain`, `bg_room_common`,
  `group_chat`, `ds_agent`, `ds_agent_js`, `oc_coord`); **33 simbol JNI**
  (was 29).
- ARM64-only (Rust `aarch64-linux-android` saja; APK `arm64-v8a` saja).
  `build.py` bump 328 → 329 / 4.48.9.162 → 4.48.9.163 di live DAN
  branch-stage (beda CI-adapted vs live hard-fail dipertahankan).
- Perilaku user-visible TIDAK berubah: ini port logika murni, bukan
  fix perilaku. Semua perilaku vc321/vc322/vc327/vc328 dipertahankan
  (dikunci P7/P8/P13/P14 + P15 §7). GroupChat tetap memakai
  `catalogForTask` + backstop 30k DI KOTLIN (P7) — Rust TIDAK
  pernah menerima/mengirim katalog penuh.
- Keystore VM lama dipertahankan; gerbang sidik jari vc316 berlaku:
  Main `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`,
  Pure `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`
  — bila beda, build GAGAL.

## §2. Ukur dulu + desain (probe JVM — SEBELUM patch)

### §2.1 Temuan kunci (baca penuh keempat file: BgBrain.kt 1.461 baris + GroupChat.kt 915 + DsAgent.kt 225 + OcCoord.kt 1.089 = 3.690)

- Slice 12 port **logika murni saja**; orkestrasi (thread, WebView eval,
  notifikasi, SlotClient, listener callback, DB/network) tetap Kotlin
  (peta §3.4 + preseden slice 9: tanpa tokio, tanpa Rust listener;
  preseden slice 11: streaming tetap Kotlin).
- **Satu seam file** `BgRoomSeam.kt` dengan 4 objek (`BgBrainSeam`,
  `GroupChatSeam`, `DsAgentSeam`, `OcCoordSeam`), masing-masing saklar
  sendiri default `RustCore.available`, pola `DsApiSeam.opValue`
  (null/exception mematikan saklar; `ok:false` = fallback tanpa
  mematikan; envelope `"value":null` membedakan null-legit dari
  gagal-seam). Op ambigu-null (`slotResolve`, `spinningPattern`,
  `parsePermEvent`) — caller SELALU fallback ke `*Kotlin` bila null
  (hitung ulang, sedikit boros, benar).
- **Pola wiring**: tiap fun yang di-port menyimpan nama/signature;
  body = seam call + fallback `*Kotlin` (body asli dipindah verbatim,
  pola slice 11). Blok inline (tool-route `when` BgBrain, select
  queue/chair hop/slot GroupChat, watchdog per-key, deliver
  title/text) diekstrak dulu jadi fun bernama verbatim, baru
  di-wrap — efek samping tidak pernah digandakan.
- **Op**: BgBrain 11 + GroupChat 15 + DsAgent 6 + OcCoord 26 = **58 op**
  (`perm_detail` digabung ke `parse_perm_event`; `probe_cached`
  dipecah read/write; `spin_observe`/`spin_flush` dua op;
  `incremental_text`/`collect_result` dua op).
- Batasan tes lama yang membentuk desain (merah = regresi, bukan
  "penyesuaian tes"): P7 menuntut `catalogForTask` + backstop 30k
  tetap di Kotlin + `buildPrompt` menerima katalog yang SUDAH
  dipotong; P1 melarang kode baru sesudah `handleError` (baris 1264)
  mengandung `msg.contains(`/`m.contains(` — semua wrapper BgBrain
  diletakkan sebelum 1264; IXL menuntut wrapper + fallback
  `continuationPrompt` berdampingan sebelum `private fun loop(`;
  P8/P13/P14 melarang string `Vc322`/`Vc327`/`Vc328` di
  GroupChat.kt/BgBrain.kt — tambahan hanya boleh bermarker `Vc329`.

### §2.2 Probe JVM (sebelum patch; `/tmp/probe329/Probe329.kt`, `Probe329b.kt`)

- `lastIndexOf('\n', max)`: startIndex di-clamp ke len−1, inklusif.
- `JSONObject.quote`: `/` TIDAK di-escape; kontrol → `\u0001` (hex
  kecil 4 digit); `\b \f \n \r \t` bentuk pendek; `"`/`\` di-escape;
  non-ASCII mentah; DEL (0x7F) mentah; kosong → `""`.
- `trim().toIntOrNull`: `"+20"`→20, `"20.0"`→null, huge→null,
  `"0x10"`→null, `""`→null, `"2147483648"`→null.
- `optLong`: 1.5→1, `"12.5"`→12, `"abc"`→0, missing/null→0.
  `optString` koersi angka/bool, null/missing→`""`, object→JSON kompak.
  `optInt("12.5")`→12. `optBoolean("True")`→true.
- `lowercase()`: `İSTANBUL`→`i̇stanbul` (i + U+0307);
  `contains(x, ignoreCase=true)`: `"İ"` vs `"i"` true, `"I"` vs `"ı"`
  true (Java longgar) — Rust butuh helper contains-ignore-case gaya
  Java per-char untuk `permDecision`.
- `take`/`takeLast` membelah surrogate pair — Rust memakai
  `utf16_take` / `utf16_take_last` (buang surrogate gantung).
- `lines()`: `""` akhir ikut; `\r\n` dan `\r` membelah.
- `(40000L + 3000000000L).toInt()` = `-1294927296` (wrap; Rust `as i32` sama).
- Regex Java `\s+`: NBSP TIDAK cocok; tab/newline cocok.
- **`JSONObject.toString()` urutan key berbasis HASH, bukan
  insertion/sorted**: `{"b":1,"a":2}`→`{"a":2,"b":1}` tapi
  `{"k","v"}`→`{"v":"y","k":"x"}`; `{"title","body","ts"}`→ urutan
  insertion. Konsekuensi: JSON serde Rust (sorted key) BERBEDA dari
  output org.json — P15 membandingkan JSON **secara semantik**
  (di-parse) untuk op yang me-return JSON re-serialisasi
  (`queueBgFactProposal`, `saveLastRoundAsPlan`, object/array
  `parseEvalResult`); konsumen prefs me-re-parse jadi urutan tak
  relevan. Rust meng-emit manual urutan terverifikasi-probe untuk
  bentuk produksi (`{"v","k"}` dan `{"title","body","ts"}`).
- `substringBefore`, trim menghapus NBSP dan U+001C, `replace`
  literal, diff waktu negatif → blokir cooldown,
  `(0.881*100).toInt()`=88, contains `"❌"`, `"".split("-")` → `[""]`,
  `take(0)`=`""`, normalisasi/indexOf `parseMentions` terkonfirmasi,
  `opt` nested terkonfirmasi. NBSP `isBlank()` = true;
  `"".split(",")` size 1; `substringAfter("=")` pada `"tool="` → `""`;
  `JSONObject().opt("args")` → `null`.

### §2.3 Desain (yang diputuskan + diimplementasikan)

- 4 JNI baru, envelope `{"op",...}` → `{"ok":true,"value":...}` /
  `{"ok":false,...}`, boundary nullable seperti slice 8–11.
- `buildPrompt` (GroupChat): SEMUA input dihitung di Kotlin (pack,
  persona, pinned ter-redact, memori filter-vc241 + TER-REDACT —
  Rust TIDAK me-redact baris memori, `memoriesVc241.map {
  RedactSecrets.redact(it) }` — catSlim via `catalogForTask` +
  backstop 30k HANYA bila toolsEnabled, skills ter-redact,
  transcript per-role ter-redact); Rust hanya merakit.
- `submitDecision` / `bgRoundGate`: string keputusan = alasan blok
  Kotlin verbatim (`"proceed" | "cooldown" | "sesi mati" | "kuota" |
  "gerbang" | "job dimatikan" | "baterai"`; `"busy" | "blocked" |
  "proceed"`), BUKAN kode pendek.
- Keputusan route 3-nilai bila Kotlin butuh efek samping berbeda:
  `group_tool_route` gates → `"execute_gates"` (Kotlin tulis
  DecisionLedger dulu) vs shortcut `"execute"`; `perm_decision` →
  `"auto_gate"` (log gate vc247 nonaktif) vs `"auto_pattern"` (log
  pola aturan) vs `"park"`. `bg_tool_route` gates TETAP `"execute"`
  polos — BgBrain TIDAK menulis ledger di cabang itu (terverifikasi
  di sumber; beda dari GroupChat).
- `DsAgent` template JS diekstrak BYTE-IDENTIK dari `DsAgent.kt`
  via skrip (`/tmp/probe329/extract_ds_js.py`, bukan diketik manual):
  ATTACH 492 char, SEND 1552, PROBE 406, PAGE_TEXT 99, LAST_STATE 1988.
  Bug extractor (baris replace-arg ikut tersedot) ditemukan +
  diperbaiki sebelum dipakai.
- `OcSpinWatchVc303.observe`/`flush`: state JSON
  `{counts, first_seen, pending}` pulang-pergi lewat Kotlin;
  observe berhenti di hit pertama; flush membersihkan pending
  bahkan bila norm<20.
- `OcTaskBudgetVc303.overSteps`: tanpa fun fallback `*Kotlin` —
  fallback inline `(assistantMessages > maxSteps)` di call site
  (D19 mengecek `OcCoordSeam.budgetOver`, bukan nama `*Kotlin`).

## §3. Basis verifikasi (langkah 0 — semua LULUS sebelum patch)

- Remote HEAD saat spawn: `c1ee1a4a` (push vc328). **Doc-sync vc328
  mendarat saat kerja berjalan** — HEAD dicek ulang:
  `6f90a5005425ad3a7601f55aaf43fa7667d6c083` ("vc328 docs:
  BUILD-VC328.md + TES-VC328-SIAP-TEMPEL.txt (doc-sync)"),
  tree akar `717e7b9dab0ef6ce3c2bc915f62e973bbbff9378`.
  Remote `main` TIDAK disentuh: `2e6245e30d30c3ea26fa50c5acc43b33f2dd1e3b`.
  CATATAN: `gh-ci.py tree` mencetak `tree_sha` = SHA COMMIT
  (6f90a500…), bukan SHA tree akar — labelnya menyesatkan; daftar
  blob-nya benar (502 blob, 559 entri, truncated=false).
- Live tree == branch-stage app tree (diff exit 0); live crate ==
  branch-stage crate (diff exit 0, excl. `target/`).
  `build.py` diff = beda CI-adapted vs live hard-fail yang DIKENAL.
- `cargo test --offline` di live `harness-core`: **352/352 passed**.
- NDK clang hidup: Android clang 21.0.0.
- `gh.py` TIDAK punya perintah `repos` — perintah benar: `me`,
  `create-repo`, `remote-url`, `put-file`, `delete-file`,
  `push-tree`. `gh-ci.py` dipanggil via `python3` (bukan langsung —
  tidak executable).
- Cek C baseline vc328: `diff -rq merge/ work-impl/integrated/` =
  **32 berubah + 5 baru**. Untuk vc329: **35 berubah + 6 baru**
  (BgBrain.kt, DsAgent.kt, OcCoord.kt baru berubah; GroupChat.kt
  sudah berubah; + `BgRoomSeam.kt`).

## §4. Isi patch (file yang berubah)

### Rust — baru + wiring

- `harness-core/src/bg_room_common.rs` — helper bersama: `utf16_take_last`,
  `java_parse_int_strict`, `java_lower_char`/`java_upper_char` (U+0130→'i',
  U+0131→'I'), `java_equals_ignore_case`, `java_contains_ignore_case`
  (unit UTF-16, urutan regionMatches Java), `java_is_space`/
  `java_collapse_ws` (`\s` ASCII saja), `spin_normalize`, `json_quote`,
  `opt_raw_string`, `opt_string/opt_int/opt_long/opt_bool`,
  `opt_obj/opt_arr`, `parse_json`, `is_blank`, `substring_after_eq`,
  `java_number_string`, `java_double`, envelope `ok_json`/`err_json`,
  `req_op`, `get_str/get_str_d/get_bool_d/get_i64/get_i32`.
- `harness-core/src/bg_brain.rs` — 11 op (`cut_boundary`,
  `trim_transcript`, `bg_quiet_hour`, `battery_formula`,
  `submit_decision`, `continuation_prompt_bg`, `honest_cap_stop_bg`,
  `bg_tool_route`, `watchdog_decision`, `queue_fact_proposal`,
  `deliver_title`).
- `harness-core/src/group_chat.rs` — 15 op (`parse_mentions`,
  `member_info`, `select_queue`, `chair_hop`, `slot_resolve`,
  `build_prompt`, `continuation_prompt_room`, `honest_cap_stop_group`,
  `retrieve_memories_filter`, `quiet_hour_group`, `bg_round_gate`,
  `group_tool_route`, `save_last_round_plan`, `parse_plans`,
  `render_notif_3lines`).
- `harness-core/src/ds_agent.rs` — 6 op (`poll_step`,
  `parse_eval_result`, `json_quote`, `build_attach_js`,
  `build_send_js`, `ds_js`).
- `harness-core/src/ds_agent_js.rs` — template JS byte-identik
  (lihat §2.3).
- `harness-core/src/oc_coord.rs` — 26 op (`result_notif_id`,
  `rule_defaults`, `sanitize_rule`, `rule_int`, `spinning_pattern`,
  `spin_observe`/`spin_flush`, `budget_over`, `looks_failed`,
  `quiet_now`, `incremental_text`, `collect_result`, `scan_messages`,
  `await_body`, `honest_status`, `runloop_gate`, `session_rotate`,
  `serve_select`, `probe_cache_read`/`probe_cache_write`,
  `status_text`, `task_result_text`, `peek_text`, `parse_perm_event`,
  `perm_stale`, `perm_decision`, `battery_pct`).
- `harness-core/src/lib.rs` — 6 modul baru. `harness-core/src/jni_glue.rs`
  — `RUST_CORE_VERSION` 9→10; capabilities + 4 JNI baru (pola
  `guarded_string_nullable`, seperti `dsApiJson`); 29→33 simbol.
- `Cargo.toml`/`Cargo.lock` TIDAK berubah (tanpa dep baru).
- Perbaikan kecil pasca-tulis (17 Nov): arm dispatch `"budget_over"`
  tadinya meng-inline `asst > max` (duplikat logika; fn
  `budget_over` jadi dead-code di build lib) — diubah memanggil
  `budget_over(asst, max)`; cargo test tetap 396/396; warning
  build 4→3.

### Kotlin — baru + wiring

- `app/src/main/java/com/senzwh/deepharness/BgRoomSeam.kt` (BARU) —
  4 objek + 4 saklar + semua 58 op (lihat §2.3). KDoc `selectQueue`
  sempat basi (menyebut catatan "...tidak aktif — dilewati") —
  diperbaiki ke catatan verbatim benar (doc-only).
- `app/src/main/java/harness/core/RustCore.kt` — `VERSION` 9→10 +
  4 `external fun` (`bgBrainJson`/`groupChatJson`/`dsAgentJson`/
  `ocCoordJson`).
- `BgBrain.kt` (1.461 → 1.659 baris) — fully wired: `bgAllowed`
  (quiet-hour via seam), `batteryOk`, `submit` (gate via seam,
  `clearSessionDead` dibersihkan SEBELUM keputusan diterapkan —
  urutan sama seperti asli), `cutAtMessageBoundary`,
  `continuationPrompt` (wrapper + fallback berdampingan sebelum
  `loop(`), `honestCapStopBg` (local fun jadi alias 1 baris),
  tool-route `when` → route-driven (prop `factProposalForMemoryTool`
  + `autoAllowed` dibaca eager — keduanya pure read, terverifikasi),
  `trimTranscriptVc263` (StringBuilder diganti isinya, identitas
  dipertahankan), `watchdogTick` (per-key decision), `queueBgFactProposal`
  (count<0 → −1 tanpa tulis), `deliver` (title+text diekstrak;
  efek samping queue-drop/logJob/report tetap sekali, sesudah
  `routeReason` dihitung). 0 string terlarang; 26 marker Vc329;
  `handleError` di baris 1462; tanpa `msg.contains(`/`m.contains(`
  sesudahnya.
- `GroupChat.kt` (915 → 1.135 baris) — fully wired: `personaNameFor`/
  `accentFor` (via `memberInfo` — persona by ID, accent by NAME),
  `parseMentions` (substring-based — `"@senzwh"` COCOK `"@senz"`,
  quirk dipertahankan), `selectQueue`, `resolveSlot`, `honestCapStopGroup`,
  `chairHop`, `retrieveMemories` (retrieval tetap Kotlin; filter via
  seam), `continuationPromptVc321`, `buildPrompt` (semua input dihitung
  Kotlin, lihat §2.3), `runGroupTool` (`"execute_gates"` menulis
  DecisionLedger dulu), `isQuietHour`, `runBackgroundRound` (gate
  via seam; `prefs` dideklarasi ulang lokal pasca-startRound —
  perbaikan dari penghapusan `val prefs` atas yang sempat salah),
  `notifyGroupDone`, `groupPlans`/`saveGroupPlans`/`saveLastRoundAsPlan`
  (Rust append tanpa re-sort — sama seperti asli; `groupPlans`
  me-re-sort saat baca). P7 dicek ulang sesudah wiring: semua
  4.1–4.8 LULUS.
- `DsAgent.kt` (225 baris) — fully wired: `eval` (parse via seam,
  fallback `evalParseKotlin`), `pageText`/`probe`/`lastState`
  (template via `dsJs`), `attachFile` (`buildAttachJs`),
  `sendAndAwait` (`jsonQuote` + `buildSendJs`), `pollAnswer`
  (decision via `pollStep`; firePartial di continue DAN done;
  unchanged rekursi tries+1/stable 0).
- `OcCoord.kt` (1.089 → 1.423 baris) — fully wired: `resultNotifIdFor`,
  `rule`/`ruleInt` (sanitize), `runLoop` gate, `runTask` rotate,
  `OcSpinWatchVc303.observe`/`flush` (state-JSON round-trip),
  `OcTaskBudgetVc303.overSteps` (fallback inline),
  `spinningPatternVc277`, `looksFailedVc262`, `finishTask`
  (honest status — prefix spinning DULU baru `looksFailed`),
  `awaitCompletion` (scan + awaitBody), `incrementalAssistantTextVc303`/
  `collectResult` (msgs di-fetch SEKALI lalu seam/`*Kotlin`),
  perm watcher (`parsePermEvent` + `handlePermission` 3-nilai),
  `resolvePermission`/`sweepStalePending` (`permStale` inline),
  `taskResultText` (peek lazy hanya bila status bukan done/failed/
  cancelled DAN sessionId non-blank), `peekSessionText`,
  `ensureServe` (two-phase via `serveSelect`; phase 1 sleep 6000
  tetap Kotlin), `probeCached` (read via seam — miss mengembalikan
  `ProbeCacheRead(hit=false, probes=null)`, BUKAN null; write via
  seam), `statusText` (hanya stored rules — key hilang → `"null"`),
  `batteryPct`, `isQuietNow`. 0 string terlarang; 43 marker Vc329.

### Yang sengaja TIDAK diubah

- WebView latar BgBrain, notifikasi Android, dialog Views, thread/
  serve/Termux (`startServeViaTermux`, `Thread.sleep`), SlotClient,
  listener callback, DB/network — tetap Kotlin (dicek D19).
- `Flavor.kt` TIDAK berubah. `MemoryDb.kt` TIDAK berubah (sisa kerja
  — BUKAN bagian rilis ini; D19 + C mengeceknya).
- `build.py` live vs branch-stage: beda CI-adapted vs hard-fail
  dipertahankan; hanya 2 baris VERSION yang berubah di keduanya.

## §5. Tes paritas baru — P15 TestBgRoomVc329

- File: `vc317-work/branch-stage/tests/TestBgRoomVc329.kt`
  (mode `TestBgRoomVc329Kt <rust|kotlin> <treeDir> <coreDir>`,
  pola P14: helper `cek/src/srcCore/funBody/sameFunBodyNorm/
  bodyContainsLines`, banding JSON semantik `jsonSame`/`jsonEq`,
  `Unsafe.allocateInstance` + refleksi; `INSTANCE` asli untuk
  GroupChat — wrapper butuh MEMBERS terinisialisasi; BgBrain/
  OcCoord/DsAgent via `Unsafe.allocateInstance` — fun yang dites
  tidak menyentuh instance state).
- Cakupan §1–§10: §1 struktur/VERSION/JNI/saklar (1.1–1.12);
  §2 BgBrain diferensial + seam-direct (2.1–2.71); §3 GroupChat
  (3.1–3.58); §4 DsAgent incl. template JS byte-identik (4.1–4.24);
  §5 OcCoord (5.1–5.71); §6 kelas spin-watch + round-trip
  state-JSON (6.1–6.11); §7 guard regresi vc321/vc322/vc327/vc328
  incl. IXL §6 + ekor P1 (7.1–7.15); §8 `*Kotlin` == merge/ vc328
  (8.1–8.26); §9 batas slice (9.1–9.11); §10 saklar-mati fallback
  — keempat saklar seam dimatikan, wrapper HARUS mengembalikan nilai
  SAMA, lalu saklar dipulihkan (10.1–10.16).
- Total: **396 pemanggilan `cek(`** di file (per-mode: 312 rust / 296 kotlin).
  Angka per-mode + total suite di §6.
- Ekspektasi kunci (semua diturunkan dari body terverifikasi +
  tes unit Rust, bukan tebakan): matriks `cutAtMessageBoundary`
  (`"a\nb\nc",4`→`"a\nb"`; `"ab\ncdefghij",4`→`"ab\nc"` cut==max/2);
  `trimTranscript` 70.022 char→dropped 10132 + marker, kasus
  newline-snap→dropped 65001; gate `submit_decision` + batas 900.000;
  matriks `poll_step` incl. teks-kosong continue; matriks
  `parse_eval_result` incl. trailing garbage; assembly `build_prompt`
  + marker cap 60k; `save_last_round_plan` semantik; spin-watch
  first-seen/hit/flush/reset lewat kelas `OcSpinWatchVc303` ASLI di
  KEDUA mode; quirk substring `parseMentions` (`"@senzwh"` cocok
  `"@senz"`).
- Perbaikan file tes SEBELUM run (bukan penurunan ekspektasi):
  (a) cek 4.17 + 8.20 — KOREKSI ARAH (catatan ronde-1 di bawah ini
  sempat terbalik): pencarian teks-SUMBER (`readText`) butuh string
  runtime 2 backslash + `n` (isi file di disk memang 2 backslash),
  jadi sumber tes HARUS 4 backslash; sempat diubah ke 2 backslash
  (salah — membuat 4.17/8.20 gagal di ronde 2) lalu dikembalikan ke
  4 backslash di baris 873, 1312, 1315 (terverifikasi numerik:
  `re.search` hitung backslash). Baris 865 (cek 4.13, banding
  NILAI runtime) benar tetap 2 backslash. (b) cek 9.11:
  `opValue("cut_boundary")` → contains polos nama op (seam memakai
  `opStr` untuk op itu). (c) Ronde 1 P15 standalone GAGAL KOMPILASI
  — 5 situs `Triple(emptyList(), listOf(...), "senz")` tanpa
  inferensi tipe (error `no method 'equals(Any?)'` + `not enough
  information to infer type variable`): diperbaiki dengan type-arg
  eksplisit `Triple<List<String>, List<String>, String>(...)` di
  5 situs (622, 625, 629, 630, 1389); nilai ekspektasi TIDAK berubah.
  (d) Ronde 2 P15 standalone (rust 301/312, kotlin 284/296 —
  11 + 12 gagal; SEMUA terdiagnosis, tidak ada yang ditutup
  diam-diam): 4.3 ekspektasi salah DUA KALI — `JSONTokener("bukan
  json")` BUKAN gagal parse dan BUKAN `"bukan"`: probe Java
  langsung (`/tmp/probe329/eval/ProbeEval.java`, json-20240303)
  membuktikan nextValue mengembalikan string UTUH `"bukan json"`
  (spasi ikut — spasi bukan delimiter JSONTokener); ekspektasi
  akhir `"bukan json"` (ronde 3: 311/312 + 295/296, hanya 4.3
  tersisa; ronde 4: LULUS 312/312 rust + 296/296 kotlin = 608); 5.51 konjungsi ke-2
  salah — gate sesi ada di WRAPPER (peek dihitung lazy hanya bila
  sesi tak-kosong; merge asli OcCoord.kt:807-810), fallback
  menempel peek apa pun sesi, ekspektasi diperbaiki ke bentuk
  menempel; 5.71 salah target — merge/ TIDAK punya fun
  `runloopGate` (gerbang inline di `runLoop`), cek diganti ke
  `bodyContainsLines` kedua sisi; 6.9 (rust) PERBAIKAN KODE RUST
  (bukan tes) — `spin_observe`/`spin_flush` error pada state JSON
  rusak → seam null: state rusak kini dianggap segar
  (`spin_state_fresh_or_parse`, `oc_coord.rs`) + unit test baru
  `spin_watch_corrupt_state_is_fresh` (cargo 396→397); 7.10
  terlalu ketat — cek menuntut `"Vc329"` kapital, padahal
  GroupChat/DsAgent/OcCoord hanya punya marker lowercase `vc329`:
  cek dilonggarkan ke case-insensitive (konvensi D19:
  `Vc329|vc329`); 8.5/8.7/8.16/8.17/8.26-batt — helper `funBody`
  tak bisa mengekstrak fun expression-body `= ...` (mengambil
  body fun BERIKUTNYA): helper baru `funSegment` (segmen sampai
  deklarasi fun berikut), kode produksi TIDAK diubah; 8.13 —
  merge/GroupChat.kt PRA-vc321 (tanpa `continuationPromptVc321` /
  `catalogForTask`, hanya `toolCatalog`): banding norm penuh
  mustahil, cek diganti (cont via `funSegment` anchor + suffix;
  build via `funSegment` bagian vc321 + kerangka vc316; merge
  terbukti masih `toolCatalog`; save tetap norm-compare, lulus);
  8.26-probe — fallback `probeCacheReadKotlin` menerima `ts`/`raw`
  sebagai parameter dan TIDAK menyebut `ocProbeCache` (yang membaca
  cache adalah wrapper): ekspektasi `"ocProbeCache"` dihapus.
- Bump pemeliharaan versi di file tes LAMA (standar — perilaku yang
  dites TIDAK berubah, hanya nilai versi yang memang naik):
  `RustCore.VERSION == 9` → `== 10`, `const val VERSION = 9` → `10`,
  `RUST_CORE_VERSION: jint = 9` → `10`, `nSym`/`jniCount == 29` →
  `== 33`, di 15 file: TestConcatApiVc319, TestDsApiSplitVc328,
  TestHttpVc324, TestPowSolverVc318 (+ salinan work-s2),
  TestRedactSecretsVc318 (+ work-s3), TestRusqliteVc323,
  TestServer4198Vc325, TestSessFixVc322 (5.18), TestSessFixVc327
  (5.14), TestSmallModulesVc318 (+ work-s4),
  TestToolRegistryAgentPromptVc320, TestToolsLeavesVc326.
  TIDAK disentuh: `deltaPosts == 29` (TestSessFixVc327:295 —
  hitungan sesi, bukan simbol JNI) dan komentar historis
  ("RUST_CORE_VERSION tetap 8" di TestSessFixVc327:16).

## §6. Angka tes

- cargo test --offline (live `harness-core`):
  **397/397 passed, 0 failed** (352 baseline vc328 + 45 fn tes baru
  slice 12 — hitung sendiri, bukan asumsi; termasuk
  `spin_watch_corrupt_state_is_fresh` di `oc_coord.rs`).
- Suite lokal penuh — RONDE 1 (`work-impl/
  log-suite-vc329-r1.txt`, 23:59:55 UTC, lebih baru dari runner
  23:47:15): **2.895 lulus, 6 gagal (total 2.901)** — 1 ditoleransi
  (R2-4.1) + 5 SATU akar penyebab (bump versi terlewat, §7):
  P6 rust 79/80 (7.3), P10 rust 87/88 (1.3), P11 rust 150/151
  (1.3), P12 rust 85/86 (1.3), P14 rust GAGAL 1 dari 140 (1.6).
  Yang sudah hijau di ronde 1: P15 312/312 rust + 296/296 kotlin;
  P7 52/52, P8 73/73, P13 66/66; C LULUS; D1–D19 SEMUA LULUS
  (0 GAGAL D).
- Suite lokal penuh — RONDE 2 (`work-impl/
  log-suite-vc329-r2.txt`, 17 Nov 00:16:46 UTC, lebih baru dari
  runner 23:47:15): **2.900 lulus, 1 gagal (total 2.901)** —
  aritmetik dari log: P1 103 + P2 36 + P3 94 + P4 235 + N9 46
  + BR 45 + IXL 72 + R1 64 + R2 68/69 + P5 157 (102+55) +
  P6 155 (80+75) + P7 52 + P8 73 + P9 134 (107+27) +
  P10 138 (88+50) + P11 302 (151+151) + P12 172 (86+86) +
  P13 66 + P14 280 (140+140) + **P15 608 (312+296)** = 2.900.
  Satu-satunya gagal = R2-4.1 (assert STRING usang warisan vc315
  — ditoleransi, preseden vc316–vc328). C LULUS (tepat 35 berubah
  + 6 baru; MemoryDb/Flavor/DsFailure tidak berubah).
  D1–D19 SEMUA LULUS (0 GAGAL D; 19 baris D final + 5 baris D1–D5
  awal dari seksi tree). OVERALL FAIL=1 hanya karena exit code
  R2-4.1 (runner lokal tidak punya cabang toleransi seperti CI —
  sama seperti log vc326/vc327/vc328 yang juga berakhir FAIL=1).
- P15 standalone (ronde 4, sebelum suite): **312/312 mode rust +
  296/296 mode kotlin** (lihat §5d untuk riwayat ronde 1–4).

## §7. Deviasi / temuan jujur

- **Urutan key org.json vs serde (probe §2.2):** `JSONObject.toString()`
  berbasis HASH — P15 membandingkan JSON secara semantik untuk
  `queue_fact_proposal` / `save_last_round_plan` / object-array
  `parse_eval_result`. Bentuk produksi di-emit manual Rust dengan
  urutan terverifikasi-probe (`{"v","k"}`, `{"title","body","ts"}`).
- **Surrogate gantung di `take`/`takeLast`:** Kotlin `take(n)` bisa
  menyimpan lone surrogate — tak terwakili di Rust String;
  `utf16_take`/`utf16_take_last` membuangnya (kasus tepi saja;
  P15 memakai teks normal). `trim_transcript` merakit via
  `from_utf16_lossy` (surrogate gantung → U+FFFD) — perilaku
  terverifikasi P15 §2, bukan klaim.
- **Keputusan route hanya keputusan:** `bg_tool_route` /
  `group_tool_route` / `perm_decision` mengembalikan string
  keputusan saja — park/execute/dialog/ledger tetap Kotlin.
- **`perm_detail` digabung** ke `parse_perm_event` (bukan op sendiri).
  `probe_cached` dipecah read/write. `overSteps` tanpa fun fallback
  `*Kotlin` (fallback inline di call site).
- **Op ambigu-null** (`slotResolve`, `spinningPattern`,
  `parsePermEvent`): null = hasil legit ATAU gagal seam — caller
  selalu fallback ke `*Kotlin` (didokumentasikan di header seam).
- **`probeCacheRead` miss mengembalikan `ProbeCacheRead(hit=false,
  probes=null)`, BUKAN null** — seam hanya null bila op-nya sendiri
  gagal. `parse_plans` selalu `ok:true` + array (kosong bila JSON
  buruk) — seam mengembalikan `emptyList`, tidak pernah null.
- **KDoc `selectQueue` sempat basi** — diperbaiki (doc-only, §4).
- **Warning build lib Rust: 3** (`get_i32`, `utf16_take_last`,
  `opt_bool` di `bg_room_common.rs` — helper teruji unit-test tapi
  tanpa pemakai produksi; sengaja dipertahankan sebagai helper
  teruji, bukan dihapus). Sempat 4 — arm `budget_over` diperbaiki
  memanggil fn (§4).
- **Bump versi di tes lama** — lihat §5 (15 file; standar).
- **P15 ronde 1 gagal kompilasi** (inferensi `Triple`, §5c) —
  diperbaiki tanpa mengubah ekspektasi.
- **P15 ronde 2 (11 + 12 gagal) — diagnosis + perbaikan (§5d):**
  semua terdiagnosis sebagai (i) ekspektasi tes salah (4.3, 5.51,
  8.26-probe), (ii) target banding salah (5.71, 8.13 — merge/
  pra-vc321 untuk GroupChat), (iii) helper tes tak mampu
  expression-body (funBody → funSegment baru), (iv) ketat-kasus
  (7.10), (v) arah backslash terbalik (4.17/8.20 — dikoreksi),
  (vi) SATU perbaikan kode Rust sungguhan: state spin rusak →
  segar (6.9). Tidak ada perilaku produksi yang diubah untuk
  meluluskan tes kecuali (vi).
- **Divergensi `parse_eval_result` kata telanjang:** Rust `""`
  vs Kotlin `"bukan json"` untuk input seperti `"bukan json"`
  (probe: JSONTokener mengembalikan string utuh) —
  TAK TERJANGKAU produksi (hasil `eval` WebView selalu nilai
  JSON valid: string ber-quotes / angka / bool / objek); kasus
  terjangkau dibuktikan identik oleh P15 §4.1/4.2/4.4.
- **merge/ = baseline pra-vc321 untuk GroupChat** (tanpa
  `continuationPromptVc321`/`catalogForTask`/`catSlimVc321`) —
  §8.13 disesuaikan (bukan banding norm penuh); merge/BgBrain,
  merge/OcCoord, merge/DsAgent cocok untuk fun yang dibandingkan
  di §8 lainnya.
- **Helper `funSegment` baru di P15** — `funBody` (pola P12/P14)
  gagal untuk fun expression-body `= ...` (mengembalikan body fun
  berikutnya); `funSegment` mengambil segmen sampai deklarasi fun
  berikut (indent 4 spasi; fun lokal ber-indent lebih dalam
  sehingga aman).
- **`gh-ci.py tree` salah label** — field `tree_sha` berisi SHA
  commit HEAD (6f90a500…), bukan SHA tree akar (717e7b9d…).
  Daftar blob-nya benar; verifikasi pra-push memakai daftar blob
  + commit HEAD dari endpoint branches (§8.1).
- **Runner lokal vs CI:** D15 CI memeriksa Flavor tanpa vc327
  (`Vc325|vc325` saja) sementara runner lokal memeriksa keduanya
  — perbedaan pra-ada, bukan dari slice ini; keduanya LULUS.
  D14 header CI sempat menyebut "DsApiClient tanpa Rust" (klausa
  yang sudah DIHAPUS sejak vc328) — diperbaiki di header runner
  lokal vc329 (body D14 sudah benar sejak vc328).
- **Tidak ada penurunan standar:** tidak ada ekspektasi tes yang
  diubah diam-diam; satu-satunya gagal yang ditoleransi tetap
  R2-4.1 (assert STRING usang warisan vc315).
- **RONDE 1 SUITE GAGAL — bump versi terlewat (5 situs, 1 akar
  penyebab; §6):** sesudah `RUST_CORE_VERSION` naik 9→10, lima
  tes lama masih memeriksa `capabilities()` Rust dengan ekspektasi
  version 9, padahal Rust kini benar melaporkan 10 — perilaku
  yang dites TIDAK berubah, hanya angka versi ekspektasi:
  1. `TestToolRegistryAgentPromptVc320.kt:225` — `check("7.3
     capabilities version 9", caps.getInt("version") == 9, ...)`
  2. `TestHttpVc324.kt:311` — `cek("1.3 capabilities: version 9 +
     httpJson + modul http/sse", caps.contains("\"version\":9") ...)`
  3. `TestServer4198Vc325.kt:159` — `cek("1.3 capabilities:
     version 9 + workerEndpointJson + modul worker_endpoint", ...)`
  4. `TestToolsLeavesVc326.kt:222` — `cek("1.3 capabilities:
     version 9 + toolsLeavesJson + modul tools_leaves", ...)`
  5. `TestDsApiSplitVc328.kt:370` — `cek("1.6 capabilities:
     version 9 + dsApiJson + modul ds_api", ...)`
  Perbaikan ronde 2: bump 9→10 di ke-5 situs (nama cek +
  ekspektasi); kode produk TIDAK disentuh; ekspektasi lain TIDAK
  diubah. Verifikasi sebelum ubah: grep menyeluruh `tests/`
  memastikan TIDAK ada situs version-9 lain — sisa hit `9`
  bukan versi (requestCode 9101 di baseline-vc315/vc316,
  "9 delta / 10 giliran" hitungan sesi di TestSessFixVc322:176,
  `hashCode("a") == 97`); salinan runner work-s2/s3/s4 juga
  bersih. Kelas pemeliharaan yang sama dengan bump vc328
  (11 file 8→9/28→29) dan bump-bump sebelumnya — bukan regresi.
  Pekerja ronde 1 BERHENTI sesudah ronde 1 gagal dan melapor
  (aturan: lapor dulu sebelum ronde perbaikan); ronde 2
  dikerjakan integrator lanjutan dari titik itu.

## §8. Push + CI + APK

### §8.1 Push branch `vc317-rust`

- Remote HEAD sebelum push: `6f90a5005425ad3a7601f55aaf43fa7667d6c083`
  (doc-sync vc328 — cocok ekspektasi; main TIDAK disentuh,
  tetap `2e6245e3`).
- Verifikasi pra-push: HEAD dicek ulang SAAT push (bukan hanya
  saat spawn — `gh-ci.py tree` + endpoint branches; tree segar
  502 blob identik `work-impl/remote-tree-vc329.json`); staging
  == remote di luar delta (0 file hanya-di-remote; 22 berubah +
  8 baru, semua sesuai delta slice 12; `build.py` diff HANYA
  2 baris VERSION).
  TEMUAN PRA-PUSH (penting): `branch-stage/` mengandung
  `harness-core/target/` (1.141 file artefak cargo, 541 MB)
  karena runner lokal menjalankan `cargo build` — push-tree
  langsung dari branch-stage akan meng-commit artefak itu.
  Staging push DIBANGUN TERPISAH (`work-impl/push-vc329/`,
  510 file — dari DAFTAR FILE tree remote + 8 file baru,
  tanpa `target/`) dan diverifikasi SHA-per-blob lawan tree
  remote sebelum push (480 tak berubah cocok SHA; 22 berubah +
  8 baru = delta 30 file). Staging di /tmp GAGAL — tmpfs
  512 MB penuh (preseden vc328); staging harus di workspace.
  Skrip: `work-impl/check-push-vc329.py` (pra-push +
  bangun staging + verifikasi SHA staging 510/510);
  `work-impl/verify-push-vc329.py` (pasca-push).
- Push via `gh.py push-tree` (510 blob): commit
  **`b3b84350b94c82e72a27017422ab007ed647ca35`**,
  parent `6f90a500`, pesan "vc329 slice 12: otak latar/room ->
  Rust (RUST_CORE_VERSION 10, suite 2900/2901, P15 312/312 +
  296/296)".
- Verifikasi pasca-push: HEAD remote = `b3b84350`;
  **510/510 file lokal SHA-nya cocok dengan tree remote**
  (0 mismatch); 30/30 file delta hadir di remote (22 berubah
  + 8 baru). Staging `work-impl/push-vc329/` DIHAPUS sesudah
  push terverifikasi.

### §8.2 CI

- Run: **43987881713** —
  https://github.com/Sendysyah27/deepseek-harness/actions/runs/43987881713
  (workflow `android.yml`, branch `vc317-rust`, head `b3b84350`,
  event push, dibuat 2026-11-17T00:28:19Z).
- **Status: SUCCESS** (completed, conclusion `success`,
  ~9,5 menit, selesai 00:37:52Z). Satu job `build` — semua
  step hijau termasuk suite penuh (2.900/2.901 + P15 608 cek),
  C, D (D19 baru hidup), build senz+pure, Verify APKs.
  Ronde 1 langsung hijau (0 ronde perbaikan).

### §8.3 Artifact CI (`vc317-work/apk-vc329-ci/`)

- `harness-vc317-apks` (artifact id 14287530000, 29.352.842 byte):
  `DeepSeekHarness-v4.48.9.163.apk` (14.797.932 byte) +
  `Harness-v4.48.9.163-pure.apk` (14.752.876 byte).
- Main: MD5 `a92bf5eada96c963a71d6470afed1a1f`,
  SHA256 `ed54f36192d59db09f8f449eacd986a7c67afde9de1150f7b86f57c4616c9b10`;
  Pure: MD5 `ccfd0ed782e78e6f2915ddf9f4bbe5d9`,
  SHA256 `73c3fed753550e1443f58e63dba4549f7d1861964b33901b9bf00807d256c227`.
- aapt2: keduanya 329/4.48.9.163; apksigner v2+v3 true, 1 signer
  masing-masing; `libharness_core.so` 4.329.280 byte di
  `lib/arm64-v8a` (profil release — lebih kecil dari VM, konsisten
  vc328: CI 4.220.840 vs VM 4.783.464); `hsurr:` 0 (di .so
  maupun dex).
- Sidik jari CI (EKSPEKTASI beda — kunci runner, bukan kunci VM):
  Main SHA-256 `ab1741ef06c0e54f6756d7a79aae92aee4ba43928110e04a56a37e34f8563fdf`,
  Pure SHA-256 `15d4eaa8d3c4b75811490d746c2b8c8b56f1b1f7918a66d773a322ed914f9219`.

### §8.4 Build VM (`vc317-work/apk-vc329-vm/`, JH `~/jdk-21-sys`,
langkah Rust rust-lld + CC 0r; clang NDK hidup 21.0.0 —
TIDAK perlu restore dari zip cache)

- Main `DeepSeekHarness-v4.48.9.163.apk` 14.982.252 byte,
  MD5 `24ccfa343c6f67e5e6cea835cc345033`,
  SHA256 `80da3725887c233d34ea149322b6011a790a845968e09235c1f1d086de04bb97`,
  **sidik jari `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`
  — GERBANG COCOK (sama vc316, bisa menimpa)**.
- Pure `Harness-v4.48.9.163-pure.apk` 14.937.196 byte,
  MD5 `06aa0b0d127ce3cbf53b4f07f198c0ea`,
  SHA256 `3108db4fdb25f19ec1dfd71a5279f23368ca82156bc267b4fc633bb3d554a03c`,
  **sidik jari `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`
  — GERBANG COCOK (sama vc316, bisa menimpa)**.
- Keduanya: apksigner Verifies, v2+v3 true, 1 signer,
  aapt2 329/4.48.9.163, `libharness_core.so` 4.911.312 byte
  di `lib/arm64-v8a` (naik dari 4.797.888 vc328 — 6 modul
  bg_room masuk, +113.424 byte), `hsurr:` 0 (.so maupun dex),
  arm64-v8a saja, 4 simbol JNI baru ada
  (`bgBrainJson`/`groupChatJson`/`dsAgentJson`/`ocCoordJson`),
  total 33 simbol JNI.
- Log build: `build-senz.log`, `build-pure.log` (di folder yang sama).

### §8.5 Dokumen akhir

- `BUILD-VC329.md` ini difinalisasi (§6 + §7 + §8 + §9) sesudah
  CI SUCCESS + build VM lolos gerbang sidik jari.
- `TES-VC329-SIAP-TEMPEL.txt`: perilaku user-visible slice 12
  = IDENTIK, jadi tes asap (TES 1–4: 2 perintah 1-1 + room/grup
  + tool di room + tugas latar) + T1–T6 vc322 + TES-VC327 +
  TES-VC328 tetap berlaku (ditulis eksplisit di file tes).
- **TERBUKA — doc-sync push:** salinan `BUILD-VC329.md` +
  `TES-VC329-SIAP-TEMPEL.txt` sudah disinkronkan ke
  `branch-stage/` (siap push), TAPI belum di-push ke
  `vc317-rust` — HEAD remote masih `b3b84350` (commit push
  vc329; push utama sengaja TIDAK memuat dokumen, pola
  vc325/vc326/vc327/vc328). Koordinator yang memutuskan
  doc-sync (pola vc328: `6f90a500`).

## §9. PENUTUP MIGRASI — migrasi hybrid Kotlin→Rust PENUH

Slice 12 (vc329) adalah slice TERAKHIR dari peta
`HYBRID-MIGRATION-MAP.md` §4 (12 slice + scaffolding).
Sesudah vc329 mendarat (push `b3b84350` + CI SUCCESS + APK VM
lolos gerbang sidik jari), **migrasi dinyatakan PENUH**.

### §9.1 Rekap slice 0–12

| Slice | vc | Isi | RUST_CORE_VERSION | Suite sesudah |
|---|---|---|---|---|
| 0 — Scaffolding | 317 | Crate `harness-core` + `RustCore` (loadLibrary + version + echo) + langkah `build.py` 0r + `cargo test` | 1 | 398/399 (paritas vc316, P1 di belakang Rust) |
| 1 — DsFailure | 317 | Klasifikasi error + Retry-After + tangga retry | 1 | 398/399 |
| 2 — PowSolver | 318 | Solver PoW DeepSeekHashV1 (SHA3-256) | 2 | 763/764 |
| 3 — RedactSecrets | 318 | Redaksi kredensial (6 regex, idempoten) | 2 | 763/764 |
| 4 — Modul kecil | 318 | NotifIds, SystemBarSpec, DsSafetyStrip, McpHelper, SessionBackground, Behaviors, ChatTheme (+PrivPairNotif) | 2 | 763/764 |
| 5 — ConcatApiClient | 319 | Klien TCP JSON-RPC Concat, 21 method | 3 | 920/921 |
| 6 — ToolRegistry + AgentPrompt | 320 | Definisi tool + selectCatalog + stripper DSML + scoring trigger | 4 | 1075/1076 |
| — fix room IXL | 321 | selectCatalog room + backstop 30k + anchor/delta room + guard reset + riwayat adaptif (bukan slice) | 4 | 1127/1128 |
| — fix sesi | 322 | Delta 1-1 + counter/primed + pengganti kirim ulang penuh (bukan slice) | 4 | 1200/1201 |
| 7 — SQLite via rusqlite | 323 | EventDb → ArtifactStore → ParkedApprovalStore → GraphDb (MemoryDb DIKECUALIKAN — sisa kerja) | 5 | 1334/1335 |
| 8 — HTTP clients | 324 | OcClient + ApiClients + McpHttp + SlotClient + Gmail + McpOAuth | 6 | 1472/1473 |
| 9 — Server :4198 | 325 | WorkerEndpoint (route/auth/HMAC/preview) | 7 | 1774/1775 |
| 10 — Daun Tools | 326 | Parse DuckDuckGo + YouTube + DSML (dispatcher tetap Kotlin) | 8 | 1946/1947 |
| — fix residual sesi | 327 | Migrasi primed + cooldown forceFresh + reset accum + delta auto-save/continuation (bukan slice) | 8 | 2012/2013 |
| 11 — DsApiClient split | 328 | HTTP + parse SSE + PoW → Rust; panen cookie tetap Kotlin | 9 | 2292/2293 |
| **12 — Otak latar/room** | **329** | **BgBrain 11 op + GroupChat 15 op + DsAgent 6 op + OcCoord 26 op = 58 op via BgRoomSeam (4 JNI baru)** | **10** | **2900/2901** |

Catatan: angka suite = lulus/total; satu-satunya gagal di
semua baris = R2-4.1 (assert STRING usang warisan vc315 —
ditoleransi sejak vc316). Simbol JNI: 21 (vc320) → 24 (vc321)
→ 29 (vc328) → **33 (vc329)**.

### §9.2 Yang tetap Kotlin

- **22 file Kotlin-TETAP selamanya** (peta §2.6/§4): MainActivity
  (shell) + Activity/Service/Receiver/komponen sistem +
  CameraVision + Sensors + SlotBrowser + BrowserDownloadManager
  + TermuxBridge + Connectors + CrashGuard.
- **Bagian yang mentok di file HYBRID** (tetap Kotlin di dalam
  file yang sebagian Rust): panen cookie WebView (`DsApiClient`),
  WebView latar (`BgBrain`), notifikasi Android, dialog Views,
  custody kunci Keystore (`SecureStore`), lampiran WebView CDP
  (`BrowserCdpClient`); thread/serve/Termux
  (`startServeViaTermux`, `Thread.sleep`), SlotClient, listener
  callback, DB/network di luar seam; `catalogForTask` + backstop
  30k GroupChat tetap Kotlin (Rust tidak pernah terima katalog
  penuh).
- **`MemoryDb.kt` TIDAK berubah — sisa kerja, BUKAN bagian
  rilis ini** (slice 7 sengaja mengecualikannya; D19 + C
  mengeceknya tiap ronde). Bila suatu hari di-port, itu
  pekerjaan lanjutan di luar migrasi ini.
- **`Flavor.kt` TIDAK berubah** (deferred sejak slice 4).

### §9.3 Total akhir

- **cargo test --offline: 397/397** (352 vc328 + 45 tes baru slice 12).
- **Suite JVM penuh: 2.900/2.901** (baseline vc316 398/399 → 2.900/2.901;
  +2.502 cek paritas selama migrasi; satu-satunya gagal R2-4.1).
- **RUST_CORE_VERSION 10; 33 simbol JNI; 6 modul Rust baru
  slice 12** (`bg_room_common`, `bg_brain`, `group_chat`,
  `ds_agent`, `ds_agent_js`, `oc_coord`).
- **APK kanonis vc329 (VM):** Main 14.982.252 byte MD5
  `24ccfa343c6f67e5e6cea835cc345033`, Pure 14.937.196 byte MD5
  `06aa0b0d127ce3cbf53b4f07f198c0ea`; sidik jari COCOK vc316
  (bisa menimpa); `libharness_core.so` 4.911.312 byte.
- **Perilaku user-visible TIDAK berubah** di semua 12 slice:
  ini port logika murni + 3 fix (vc321/vc322/vc327) yang
  masing-masing dikunci tes paritasnya; fallback Kotlin
  dipertahankan per slice (saklar A/B) sehingga `.so` absen /
  versi tak cocok = jalur Kotlin lama, tanpa rilis baru.
- **Yang belum / terbuka:** doc-sync vc329 (keputusan
  koordinator); tes perangkat TES-VC329 di HP Sendy
  (tugas Jhon — install APK VM + TES 1–4 + T1–T6 vc322 +
  TES-VC327 + TES-VC328); `MemoryDb.kt` sisa kerja (di luar
  migrasi ini).

**MIGRASI PENUH.** — vc329, 17 Nov 2026.
