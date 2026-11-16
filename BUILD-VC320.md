# BUILD-VC320 — Slice 6: ToolRegistry + AgentPrompt (fungsi murni) → Rust (vc320, 4.48.9.154)

Dokumen kerja integrator slice 6 migrasi hybrid Kotlin→Rust.
Pola dokumen mengikuti BUILD-VC319.md (slice 5).

## §1. Keputusan versi

- **vc320, VERSION_CODE 320, VERSION_NAME 4.48.9.154**, branch `vc317-rust`
  (remote main tetap vc309 — JANGAN disentuh).
- `RUST_CORE_VERSION` 3→4, `RustCore.VERSION` 3→4, capabilities +=
  `toolRegistryJson` + `agentPromptJson` + `skillTriggerJson` + modul
  `tool_registry` + `agent_prompt` + `skill_trigger`.
- Crate `harness-core`: 14 modul Rust, 24 simbol JNI (21 lama + 3 baru).
- ARM64-only (Rust aarch64-linux-android; APK arm64-v8a saja).
- `Flavor.kt` TIDAK disentuh (deferred). Porting MURNI — tanpa perubahan
  perilaku / fitur baru.
- Keystore VM lama dipertahankan; gerbang sidik jari vc316 berlaku:
  Main `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`,
  Pure `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`.

## §2. Desain seam (tiga fungsi JNI bulk JSON — pola slice 4)

Satu fungsi JNI bulk JSON per modul (pola slice 4), BUKAN satu JNI per
fungsi Kotlin: tiga modul Rust baru, tiga simbol JNI baru, masing-masing
`{"op", ...}` in / `{"ok":true,"value":...}` out (pola slice 5).
Alasan: fungsi-fungsi ini kecil dan berkerabat (satu modul = satu
tanggung jawab), dan bulk JSON menjaga jumlah simbol JNI tetap kecil
sambil tiap op tetap bisa dites terpisah di `cargo test` maupun P6.

```
RustCore.toolRegistryJson(request: String): String
  request  : {"op":"catalogSection"} | {"op":"browserSkillDetail"}
           | {"op":"names"} | {"op":"namesByKind","kind":"READ"|"WRITE"|"MEMORY"}
           | {"op":"sectionCounts"} | {"op":"nearestNames","name":"...","count":3}
  respons  : {"ok":true,"value":<string|array|object>}
  null/JSON-error : Rust gagal -> fallback Kotlin

RustCore.agentPromptJson(request: String): String
  request  : {"op":"selectCatalog","full":"...","task":"..."}
           | {"op":"continuationAnchor","command":"..."}
           | {"op":"stripRawToolMarkup","text":"..."}
           | {"op":"normalizeDsmlDelimiters","text":"..."}
           | {"op":"applyDoublePass","answer":"...","claims":[...],"unsupported":[...]}
  respons  : {"ok":true,"value":"..."}

RustCore.skillTriggerJson(request: String): String
  request  : {"op":"splitDescrSections","descr":"..."}
           | {"op":"negWordsOf","descr":"..."}
           | {"op":"triggerScore","descr":"...","query":"..."}
  respons  : {"ok":true,"value":{...}}   // sections / array kata / {trigger,pos,neg,net}
```

- **Port PENUH di Rust**:
  - `tool_registry.rs` (+ `tool_registry_data.rs` GENERATED — data
    188 ToolDef + SEGMENTS + 21 PROSE + BROWSER_SKILL_DETAIL, dibuat
    mekanis dari `ToolRegistry.kt` oleh skrip generator, BUKAN
    disalin tangan — mitigasi utama risiko typo prose slice ini):
    `catalogSection`, `browserSkillDetail`, `names`, `namesByKind`,
    `sectionCounts`, `nearestNames`/`editDistance`.
  - `agent_prompt.rs`: `selectCatalog` (+ `TASK_STOPWORDS_VC241`,
    anggaran 6000/6000, `TOOL_LINE_RE_VC316`), `continuationAnchorVc316`,
    `normalizeDsmlDelimiters`, `stripRawToolMarkupForDisplay`
    (+ `stripTagCallSpans`), `applyDoublePass`.
  - `skill_trigger.rs`: `splitDescrSections`, `negWordsOf`,
    `triggerScore` (+ `contentWords`, `STOPWORDS`, `NEG_WEIGHT_VC312=1`).
    CATATAN: fungsi trigger scoring ini tinggal di `SkillJudge.kt`
    (bukan ToolRegistry/AgentPrompt) — diport ke sini karena tugas
    slice 6 menyebutnya eksplisit; sisa SkillJudge (judge 120 poin,
    eval forge, dsb.) TETAP Kotlin.
- **Tidak ada state persisten Rust** (larangan peta §3.3); `Context`
  tidak pernah menyeberang JNI: `catalogForTask(ctx, task)` TETAP
  Kotlin — ia membangun `toolCatalog(ctx)` (prefs + packageName,
  tetap Kotlin) lalu memanggil `selectCatalog` (kini Rust-first).
  `toolCatalog` sendiri TIDAK diport (butuh Context).
- **Wrapper Kotlin**: API publik TETAP PERSIS. Tiap fungsi publik
  Rust-first lewat seam JSON + `RustFallback` internal; implementasi
  lama → privat `*Kotlin` (isi TIDAK diubah). `ToolRegistry.ALL` /
  `BY_NAME` (val data) TETAP Kotlin — dipakai pemakai lain
  (`ApiToolLayer`, `Tools.validateArgs`, `SkillJudge` D1) + jalur
  fallback; data Rust diverifikasi byte-identik lewat tes hash (§6).
  Saklar: `rustToolRegistry` / `rustAgentPrompt` / `rustSkillTrigger`
  (pola `rustConcatApiClient`); sekali Rust gagal, saklar dimatikan.
- **Quirk Kotlin DIREPLIKASI, bukan diperbaiki** (dikunci tes Rust + P6):
  - `String.trim()` = trim `Character.isWhitespace` (NBSP/EM SPACE
    tepi DIBUANG — pelajaran slice 4, probe JVM): pakai `kotlin_trim`
    dari `ds_safety_strip.rs` yang sudah diperbaiki itu.
  - `String.length` = unit UTF-16 (bukan byte/char): anggaran
    `pick()` + `take(500)` dihitung unit UTF-16 — pakai
    `utf16_len`/`utf16_take` dari `concat_api_client.rs`.
  - `String.lines()` Kotlin: pisah `\r\n`/`\n`/`\r`, dan string yang
    berakhir terminator memberi elemen kosong terakhir — direplika
    `kotlin_lines` (Rust `str::lines` membuang elemen akhir itu;
    berpengaruh di `splitDescrSections` saat join balik).
  - `lowercase()` Unicode (bukan locale): Rust `to_lowercase` sama.
  - `String.replace(a, b)` literal (bukan regex): Rust `str::replace`
    sama; `substringAfter(delim, default)` kembali string asal bila
    delim tak ada — direplika.
  - `pick()`: item PERTAMA boleh melampaui anggaran (`used == 0`
    lolos cek); `list_tools` dipaksa masuk BILA ADA (ambil kandidat
    pertama urutan ASLI, bukan urutan skor) — direplika.
  - `stripRawToolMarkupForDisplay`: early-return bila tak ada
    "DSML"/`<tool=`; regex DSML lazy `.*?` + `DOT_MATCHES_ALL`;
    blok tanpa penutup hanya dipotong bila ekornya mengandung `{`;
    `stripTagCallSpans` lacak kurung + string + escape — direplika.
  - `applyDoublePass`: `String.replace` SEMUA kemunculan klaim
    (bukan hanya pertama); indeks 1-based, di luar jangkauan di-skip.
  - Trigger vc312: HANYA marker PERTAMA dikenali; kata marker
    sendiri bukan kata larangan; bagian larangan = sisa baris marker
    + baris berikut sampai baris kosong (baris kosong pembatas TIDAK
    masuk positif); `negWords = contentWords(negatif) −
    contentWords(positif)` (aturan MINUS-POSITIF); skor dijepit min 0,
    TRIGGER bila net ≥ 2; tanpa marker = rumus lama.
  - Regex `regex` crate: tanpa lookahead — tidak ada pola slice ini
    yang memakai lookahead (dicek: semua pola `(?=`/`(?!` NOL).
  - Locale perangkat: tidak ada `String.format` di fungsi slice ini —
    tidak ada deviasi locale.
- **Tanpa dependensi Cargo baru** (regex + serde_json sudah ada) →
  Cargo.toml/Cargo.lock TIDAK BERUBAH.
- `#![forbid(unsafe_code)]` di modul baru; tanpa `unwrap()`/`expect()`
  di jalur input luar; `catch_unwind` di SETIAP boundary JNI (lewat
  `guarded_string` yang sudah ada; panic -> JSON error).

### Fungsi yang DIPORT (slice 6)

| Modul Rust | Fungsi Kotlin asal | File asal |
|---|---|---|
| tool_registry | catalogSection, browserSkillDetail, names, namesByKind, sectionCounts, nearestNames (+editDistance privat) | ToolRegistry.kt |
| agent_prompt | selectCatalog, continuationAnchorVc316, normalizeDsmlDelimiters, stripRawToolMarkupForDisplay, applyDoublePass | AgentPrompt.kt |
| skill_trigger | splitDescrSections, negWordsOf, triggerScore | SkillJudge.kt |

### Fungsi yang SENGAJA TIDAK diport (dan kenapa)

- `ToolRegistry.ALL` / `BY_NAME` sebagai val Kotlin — tetap jadi
  sumber data Kotlin untuk pemakai lain + fallback; data Rust adalah
  salinan byte-identik yang diverifikasi tes hash, bukan pengganti val.
- `AgentPrompt.toolCatalog(ctx)` — butuh `Context` (prefs
  `maxToolSteps` + `packageName`); tetap Kotlin, memanggil
  `ToolRegistry.catalogSection()` (kini Rust-first) seperti biasa.
- `AgentPrompt.catalogForTask(ctx, task)` — pembungkus tipis
  `selectCatalog(toolCatalog(ctx), task)`; tetap Kotlin (Context),
  seleksinya jalan lewat seam Rust.
- `AgentPrompt.validToolNames/toolGroupCounts/validToolListText/
  toolGroupSummary` — pembungkus tipis atas `ToolRegistry.names()` /
  `sectionCounts()`; tetap Kotlin, otomatis ikut Rust lewat fungsi
  yang diport itu.
- `AgentPrompt.extractToolCall/extractToolCalls/extractDsmlCall/
  extractTagCall/findToolSpans/repairToolJson/balancedEnd` — parser
  tool-call berbasis `org.json` (bukan fungsi murni string sederhana;
  port-nya = slice 10/11, butuh pemetaan `org.json` → `serde_json`
  yang berisiko); jalur eksekusi tool-call TIDAK disentuh slice ini.
- `AgentPrompt.splitClaims/zeroCtxReviewPrompt/parseZeroCtxReview/
  claimSupportedByMemory/filterUnsupportedByMemory/
  claimWarningForCorpus/reviewerNoteText/unknownFooterVc288/
  contentSnippet/...` — filter claim-check yang diport slice ini
  HANYA `applyDoublePass` (penghapus `[KLAIM DIHAPUS]`); mesin
  pendukung claim-check (memory/aritmetika/reviewer) tetap Kotlin
  untuk saat ini — di luar ruang lingkup yang disebut tugas.
- `AgentPrompt.buildSystemPrompt/personaBlockText/modeBebas*/...` —
  butuh `Context`/prefs; tetap Kotlin.
- `AgentPrompt.stripCtxLabels/workNotes*/compressSessionBrief/
  hash64Vc277/normalizeArgsVc277/...` — fungsi murni lain yang tidak
  disebut tugas; tetap Kotlin (calon slice lanjut).
- `AgentPrompt.trimLongFencedBlocks/batchDependencyReason/
  writtenPathOf/isRefusalAnswer` — tidak disebut tugas; tetap Kotlin.
- Sisa `SkillJudge.kt` (judge 120 poin, `evaluateTrigger`,
  `forge_eval_save`, dsb.) — hanya tiga fungsi scoring trigger yang
  diport; sisanya tetap Kotlin.
- Sisa `SkillJudge.kt` (judge 120 poin, `evaluateTrigger`,
  `forge_eval_save`, dsb.) — hanya tiga fungsi scoring trigger yang
  diport; sisanya tetap Kotlin.
- `AgentPrompt.extractDsmlCall` — TIDAK diport walau DSML: ia
  mengembalikan `JSONObject` (org.json) untuk jalur eksekusi; hanya
  stripper tampilan (`stripRawToolMarkupForDisplay`) yang diport.

## §3. Basis verifikasi (langkah 0 — semua LULUS sebelum patch)

- (a) Live `ToolRegistry.kt` (583 baris) + `AgentPrompt.kt`
  (1.932 baris) vs `vc317-work/merge/` IDENTIK.
- (b) `diff -rq --exclude=target harness-core/
  vc317-work/branch-stage/harness-core/` IDENTIK.
- (c) Kedua file target dibaca penuh + `UKURAN-INPUT-VC316.md`
  (`vc316-work/work-IXL/UKURAN-INPUT-VC316.md`).
- Keadaan awal terverifikasi koordinator: vc319 pasca-slice-5
  (build.py 319/4.48.9.153, `RUST_CORE_VERSION` 3, crate 11 modul,
  21 simbol JNI, suite lokal 920/921, cargo test 248/248).
- VM: JDK sistem `~/jdk-21-sys` (OpenJDK 21.0.13); Temurin
  21.0.12.1+1 RUSAK — hard-fail tanpa fallback. cargo di
  `~/.cargo/bin` (tidak di PATH default). `free -m` sebelum suite:
  934 MB tersedia (lebih rendah dari ~1.496 MB di awal slice 5;
  penyebab = daemon + cache, bukan proses liar — tidak ada kotlinc
  sisa; suite tetap jalan, kotlinc sendirian).

## §4. Probe JVM (bukti perilaku Kotlin — 16 Nov 2026)

Probe dikompilasi atas jar tree vc319
(`work-impl/test-vc319-tree-integrated.jar`); sumber
`/tmp/probe6/Probe6.kt`, `/tmp/probe6/Probe6b.kt`; log
`/tmp/probe6/probe6.log`, `/tmp/probe6/probe6b.log`.

- `catalogSection`: 86.710 unit UTF-16, sha256
  `c3357c572acdcd593e23ca15b40169dca431ca6cc1f30f250db1e4c5d3fe3668`.
- `browserSkillDetail`: 2.866, sha256
  `bac16af6ef7d01e5ee99394790f3a36fd66afe0f1054b23c9874a101780c31d6`.
- `names`: 188; sha256 gabungan koma
  `38ee24a6c668b3171ed0b017456f9a1cf15525aa5b8a12a70a0fdee63912a912`.
- `sectionCounts`: utama=53, lanjutan=135; `namesByKind` READ=84,
  WRITE=82, MEMORY=22.
- `nearestNames`: `get_batery`→[get_battery, get_screen, set_alarm];
  `concat_versio`→[concat_version, concat_edit_redo,
  concat_edit_undo]; `xyz`→[tap, swipe, go_back].
- Katalog penuh gaya IXL (head+note+section+trailer, `lineAfter`
  menyimpan escape `\"` apa adanya): 94.818 unit, sha256
  `6fbcf99907d5dcfe4085ca868a88f6eb998c4e8a66f882b079962834376d8a65`.
  Fixture disalin ke
  `harness-core/tests/fixtures/catalog-full-vc319.txt` (95.885 byte).
- `selectCatalog` (pakai ANGKA INI di P6, bukan angka doc):
  ""→94.818; " "→94.818; "kita tes concat"→22.809 (sha
  `1f8c04b50d81155df8ec23976e0290a9b0dcba541feb6082252bdfea182dfed7`);
  "cek concat"→21.122 (sha
  `1045016dd1a45048ea7fe388cee0fcbc591fdb2a68ce5c61ed4f83612f1dce40`);
  "concat_version"→18.417 (sha
  `483d664d0fb7dde952a639202b39d04f93a3005364ecccd234ba07be93ed3638`);
  "bro?"→13.747 (sha
  `0bd180fdf69986bf6d964e632df938b6ac9f96e827b205168049505c452e3ca8`);
  "halo"→11.459 (sha
  `9ddfeaf9ebff97f62ff17f0eeb1c83efce1be413ce192a65710131cf8a77a82e`);
  "cek baterai"→18.479 (sha
  `529cd14f79d09ab222e67ceef5c8484a9765bb1d271a3ec455e7d6180734f5b1`);
  "buatkan dokumen laporan"→21.963 (sha
  `651c18bdbc7a213aa99e73a88b49be3107493825c99aa8fbe73e3d5156ba1798`).
  **+21 vs UKURAN-INPUT-VC316.md §6** (21.101 dst.) karena fixture
  gaya IXL menyimpan `\"` mentah (2 karakter vs 1 hasil decode).
- `continuationAnchorVc316`: "halo"→492 (sha
  `c3d82b60144c1aea051145b0b257d45135ca5cd5da21e468f7e73456e6f0dc33`);
  600 x→988 (sha
  `2aa63be848d9303fd9b52807d07c2e6a1d0e5b8a23ca131537ae4fdf0c1b0af9`,
  cap 500).
- Stripper DSML: 13 vektor di log probe (output eksak tercatat di
  sana); tepi kunci: tag tak tertutup TANPA `{` sesudahnya → tag
  dibuang, prosa dipertahankan; DENGAN `{` → dipotong sampai akhir;
  `<tool=abc>` kurung tak seimbang → tag dibuang, sisa JSON
  dipertahankan; fullwidth ｜＜＞ dinormalisasi dulu.
- `applyDoublePass`: ADP1 (idx 1,2) mengganti SEMUA kemunculan;
  ADP2 (5,0,-1) tidak berubah; ADP3 (1,1,3) hanya klaim 1.
- Trigger scoring: marker HANYA dikenali di awal baris (marker
  tengah baris TIDAK dikenali); T4
  `Skill pengingat jadwal.\nJANGAN PICU: Discord, Slack\n` → positif
  `Skill pengingat jadwal.`, negatif `Discord, Slack`, negWords
  [discord, slack]; skor: "buka discord" (false,0,1,0), "pengingat
  jadwal discord" (false,2,1,1), "pengingat jadwal harian"
  (true,2,0,2), "jadwal slack" (false,1,1,0). T5: bagian negatif
  multi-baris + marker case-insensitive toleran spasi. T3 aturan
  MINUS-POSITIF: "telegram" di kedua bagian → negWords kosong. T7:
  HANYA marker pertama dikenali; baris marker kedua jadi isi negatif
  dan "picu" jadi negWord. T6 tanpa marker = rumus lama.
- Tepi `selectCatalog`: stopwords saja "yang dan atau"→11.459;
  NBSP tepi " cek concat "→21.122; "CEK CONCAT"→21.122; prefix
  "conc"→16.857.
- **Koreksi temuan probe**: baris `forge_trigger_eval` = **1.490**
  unit UTF-16 saat runtime (doc §3a bilang 1.490; replika Python §7
  bilang 1.491 — replika yang meleset satu; Rust mengikuti runtime).

## §5. Isi slice (file yang berubah / baru)

Kotlin (live tree + staging, wrapper Rust-first + fallback `*Kotlin`):
- `app/src/main/java/com/senzwh/deepharness/ToolRegistry.kt`
  (wrappers `names`, `namesByKind`, `sectionCounts`,
  `browserSkillDetail`, `catalogSection`, `nearestNames`;
  `rustToolRegistry` + helper `rustValue`/`rustStringValue`/
  `rustStringListValue`).
- `app/src/main/java/com/senzwh/deepharness/AgentPrompt.kt`
  (wrappers `selectCatalog`, `continuationAnchorVc316`,
  `applyDoublePass`, `normalizeDsmlDelimiters`,
  `stripRawToolMarkupForDisplay`; `rustAgentPrompt` +
  `rustAgentValue`).
- `app/src/main/java/com/senzwh/deepharness/SkillJudge.kt`
  (wrappers `splitDescrSections`, `negWordsOf`, `triggerScore`;
  `rustSkillTrigger` + `rustTriggerValue`).
- `app/src/main/java/harness/core/RustCore.kt` (`VERSION` 3→4 +
  `external fun toolRegistryJson` / `agentPromptJson` /
  `skillTriggerJson`, semua `(String): String`).
- `build.py` (live + staging): 319/4.48.9.153 → 320/4.48.9.154
  (hanya baris VERSION; isi keduanya memang berbeda, tidak saling
  ditimpa).

Rust (`harness-core/src/`, tanpa dependensi baru — Cargo.toml/
Cargo.lock TIDAK BERUBAH):
- BARU `tool_registry.rs` (logika + tes, incl. impl SHA-256 kecil
  untuk tes hash; panjang pakai `utf16_len`).
- BARU `tool_registry_data.rs` (GENERATED 143.504 byte: 188 ToolDef,
  22 konstanta mentah incl. `BROWSER_SKILL_DETAIL_VC313`, 21 entri
  PROSE, 210 SEGMENTS) — generator
  `vc317-work/work-impl/gen-tool-registry.py` mem-parse
  `ToolRegistry.kt`; jalankan ulang generator bila ToolRegistry.kt
  berubah, lalu `cargo test`.
- BARU `agent_prompt.rs` (`selectCatalog`,
  `continuationAnchorVc316`, `normalizeDsmlDelimiters`,
  `stripRawToolMarkupForDisplay` + `stripTagCallSpans`,
  `applyDoublePass`, helper `kotlin_lines`; regex `\s` diganti kelas
  eksplisit `[ \t\n\x0B\f\r]` karena `\s` Kotlin/Java ASCII-only
  sementara `\s` regex-crate Unicode; `utf16_take` membuang high
  surrogate yang menggantung di titik potong — batasan yang sama
  seperti slice 5, hanya relevan bila karakter ke-500 perintah user
  tepat high surrogate).
- BARU `skill_trigger.rs` (`splitDescrSections`, `negWordsOf`,
  `triggerScore`, `contentWords`, `STOPWORDS`,
  `NEG_WEIGHT_VC312=1`).
- BARU `tests/fixtures/catalog-full-vc319.txt` (fixture katalog
  penuh gaya IXL).
- `lib.rs`: `pub mod agent_prompt; skill_trigger; tool_registry;
  tool_registry_data;` (crate kini 14 modul).
- `jni_glue.rs`: `RUST_CORE_VERSION` 3→4, capabilities += 3 fungsi
  + 3 modul, 3 fungsi JNI baru + helper `json_string_array`/
  `json_i64_array`; total 24 `#[no_mangle]`.

Tes (baru):
- `vc317-work/branch-stage/tests/TestToolRegistryAgentPromptVc320.kt`
  (P6, dua mode `rust`/`kotlin`, ~155 cek — lihat §6).
- `vc317-work/branch-stage/tests/TestInputLimitVc316.kt` §7.1
  diubah (lihat §7 deviasi IXL).
- Assert VERSION usang 3→4 di: `work-s2/TestPowSolverVc318.kt`,
  `work-s3/TestRedactSecretsVc318.kt`,
  `work-s4/TestSmallModulesVc318.kt`, salinan branch-stage P2/P3/P4/
  P5, `run-suite-ci.sh` D7/D8/D10, runner lokal D7/D8/D10.
- Runner lokal baru `vc317-work/work-impl/run-tests-vc320-coord.sh`
  (adaptasi `run-tests-vc319-coord.sh`; +P6, +D11, C 12→15).
- CI: `branch-stage/tests/run-suite-ci.sh` (+`run_p6`, D7/D8/D10
  VERSION 4, D11 baru) + `branch-stage/.github/workflows/android.yml`
  (+step P6; YAML divalidasi `yaml.safe_load` SEBELUM push).

## §6. Angka tes

- **cargo test: 271/271 lulus, 0 warning**
  (`cargo test --offline --manifest-path harness-core/Cargo.toml`;
  248 lama + 23 baru: tool_registry 8, agent_prompt 8,
  skill_trigger 7). Perintah sama dipakai ulang sesudah perbaikan
  import (§7) — tetap 271/271, 0 warning.
- **Suite lokal penuh: 1075/1076** — satu-satunya gagal R2-4.1
  (assert STRING usang warisan vc315, DITOLERANSI seperti slice
  sebelumnya; bukan regresi).
  - P1 103/103, P2 36/36, P3 94/94, P4 235/235, N9 46/46,
    BR 45/45, IXL 72/72 (sesudah perbaikan §7), R1 64/64,
    R2 68/69 (4.1), P5 157/157 (kotlin 55 + rust 102),
    **P6 155/155 (kotlin 75 + rust 80)**.
  - C LULUS: tepat 15 file .kt berubah vs baseline vc316 + 0 baru
    (12 slice 2–5 + ToolRegistry.kt + AgentPrompt.kt + SkillJudge.kt).
  - D1–D11 LULUS (D11 baru: fallback slice 6 + 3 saklar + 3 external
    fun + VERSION=4).
  - Log: `vc317-work/work-impl/run-tests-vc320-coord.log`.
  - Total = 921 (vc319) + 155 (P6) = 1076 cek; 1075 lulus
    (satu-satunya gagal R2-4.1, ditoleransi).
- P6 pertama kali jalan SUDAH 155/155 (mode rust 80/80 +
  kotlin 75/75) — paritas slice 6 bersih sejak awal; dua gagal
  suite putaran pertama (IXL 7.1 + D11) keduanya cacat cek, bukan
  cacat kode (lihat §7).

## §7. Deviasi / temuan jujur (termasuk yang diperbaiki di tengah jalan)

1. **IXL 7.1 — assert usang, bukan regresi (perbaikan ronde 1).**
   `TestInputLimitVc316.kt` 7.1 mengunci `ToolRegistry.kt`
   byte-identik baseline — benar di vc316–vc319, tapi slice 6
   memang mengubah file itu (wrapper Rust + fallback `*Kotlin`).
   Yang dikunci sejak vc320: DEFINISI tool tidak berubah — hash
   188 nama + hash `catalogSection` sama persis dengan probe JVM
   vc319 (nilai yang sama dipakai P6 1.2/1.7). File tes diubah di
   `branch-stage/tests/` (helper `sha256Hex` baru + komentar
   pengecualian slice 6); IXL jalan ulang 72/72. Governor/Gate/
   Tools tetap byte-identik.
2. **D11 — pola grep salah tulis, bukan kode hilang
   (perbaikan ronde 1).** Pola D11 di kedua runner menulis
   `external fun toolRegistryJson(request: String): String?` —
   padahal deklarasi aslinya `(String): String` (tanpa `?`,
   konsisten dengan seam JSON slice 4 yang mengembalikan string
   JSON non-null; `redactSecrets`/`concatApiJson` yang `String?`
   memang benar). Pola diperbaiki ke `String` di
   `run-tests-vc320-coord.sh` + `run-suite-ci.sh`; D11 jalan ulang
   LULUS. Verifikasi awal pola D11 sempat menipu karena grep
   tanpa tipe return cocok — pelajaran: grep verifikasi harus
   memakai pola LENGKAP yang sama dengan ceknya.
3. **Warning `unused_imports` di `skill_trigger.rs` (perbaikan
   ronde 1).** `cargo build` pertama memberi 1 warning: import
   `crate::ds_safety_strip::kotlin_trim` tak terpakai di kode lib.
   Menghapusnya begitu saja MEMATAHKAN `cargo test` — import itu
   dipakai satu tes (`content_words_vectors`, baris ~281, sengaja
   menjaga import hidup). Perbaikan benar: import dipindah ke
   dalam `mod tests`. Sesudahnya `cargo build` 0 warning +
   `cargo test` tetap 271/271; .so host dibangun ulang dan P6
   jalan ulang 155/155 atas .so baru.
4. **Angka `selectCatalog` +21 vs UKURAN-INPUT-VC316.md §6.**
   Bukan regresi: fixture gaya IXL menyimpan escape `\"` di
   `API_LAYER_NOTE` apa adanya (2 karakter), sementara angka doc
   memakai string hasil decode (1 karakter). Kedua mode P6 memakai
   `full` yang sama sehingga paritas tetap eksak; angka probe yang
   dipakai (§4).
5. **`forge_trigger_eval` 1.490, bukan 1.491.** Replika Python di
   doc §7 meleset satu; runtime JVM membuktikan 1.490. Tes Rust
   `catalog_section_shape` sempat mengharap 1.491 lalu diperbaiki
   ke 1.490 (probe yang benar, bukan kode yang salah).
6. **Generator `tool_registry_data.rs`: tiga perbaikan mekanis
   tengah jalan** — syntax error docstring triple-quote, satu edit
   yang menggabungkan dua baris, pemetaan varian Kind READ→Read /
   WRITE→Write / MEMORY→Memory. Fakta generator: tidak ada `$` di
   ToolRegistry.kt; escape `\u2014` ditangani; `"#` muncul sekali
   (aman dengan `json.dumps`). File hasil generator 143.504 byte.
7. **Dua detail teknis Rust:** `str::split` tidak menerima
   `regex::Regex` — pakai `Regex::split`; satu perbaikan borrow
   (`low_ln` let binding).
8. **Nama runner lokal sebenarnya `run-tests-vc319-coord.sh`**
   (bukan `run-tests-vc319-coord2.sh` seperti tertulis di ringkasan
   tugas — yang ada `coord2` hanyalah file `.log`). Runner vc320
   diadaptasi dari file `.sh` yang benar.
9. **MEMORY.md berubah di tengah slice ini oleh agen lain**
   (update SSR Content Studio: episode Gelas Terbalik Kertas +
   koreksi TaskFilms) — tidak terkait slice 6, tidak disentuh.
10. **RAM sebelum suite 934 MB tersedia** (lebih rendah dari
    1.496 MB di awal slice 5) — daemon + cache; suite tetap
    selesai tanpa OOM; kotlinc jalan sendirian sesuai pola.
11. **Perilaku TIDAK berubah**: tidak ada fungsi baru yang
    terlihat user; semua wrapper API publik persis; `ToolRegistry.ALL`
    / `BY_NAME` tetap Kotlin.

## §8. Push + CI + APK

- **Push (16 Nov 2026 ~06:44–07:00 WIB):** branch `vc317-rust`,
  commit **`f77939bc4cd9ed8c036e530f94055919bc861f05`**
  ("vc320 (4.48.9.154): slice 6 ..."), 463 file via
  `gh.py push-tree`. Remote HEAD sebelumnya `75911f63` (sesuai
  tugas, belum bergerak). Verifikasi pasca-push: remote tree 463
  blob == staging 463 file, **0 beda / 0 hilang / 0 ekstra**;
  ke-15 file delta dicek SHA-nya satu per satu — semua COCOK.
  Main TIDAK disentuh.
- **CI run #43820363565 — SUCCESS percobaan pertama**
  (in_progress 06:57 → completed success ~07:04 WIB):
  https://github.com/Sendysyah27/deepseek-harness/actions/runs/43820363565
  — cargo test + suite JVM (incl. P6 155 cek + D11) + build APK
  senz+pure semua hijau. Tidak ada ronde perbaikan CI.
- **Artifact CI** (`vc317-work/apk-vc320-ci/`,
  `harness-vc320-ci.zip` 26.402.215 B, artifact id 14204411915):
  - Main `DeepSeekHarness-v4.48.9.154.apk` 13.319.276 B,
    MD5 `beb510164fbccc70e175d2658b17417a`,
    SHA256 `9f4e994423e4f429f1c0066ec59296e971b12ffc672a608715d009da84c0c6b1`.
  - Pure `Harness-v4.48.9.154-pure.apk` 13.270.124 B,
    MD5 `ad8861e64a698d8a5671a2f9324e2bd6`,
    SHA256 `53240a9d5c18066e7ade371940b3d607bec80332449b4dd058e658a27142d05c`.
  - Keduanya: aapt2 320/4.48.9.154, apksigner v2+v3 true,
    1 signer, `libharness_core.so` 1.854.424 B di lib/arm64-v8a,
    3 simbol JNI baru ADA, hsurr: 0, arm64-only.
  - Sidik jari CI BERBEDA (kunci runner) — EKSPEKTASI, bukan
    gagal: Main `dee49af94bc04e3c6b6aa437e3b9464dfd3226fea92dee10cb9af6b0854e11b3`,
    Pure `cc38e485e1d280a5cedf1061c1916ab62458ea5c7387964cd01f4ab1cc70b019`.
- **Build VM** (`vc317-work/apk-vc320-vm/`,
  `python3 build.py --flavor senz` lalu `pure`, JH `~/jdk-21-sys`,
  Rust rust-lld; JANGAN cargo + kotlinc bersamaan):
  - Main `DeepSeekHarness-v4.48.9.154.apk` 13.397.100 B (13.083 KB),
    MD5 `9855cfc85a4c57aef028fb2440d4e2d0`,
    SHA256 `7b63da8c52eca072600c0241f497394fe913f6d7525e3568c539446b94da3876`.
  - Pure `Harness-v4.48.9.154-pure.apk` 13.352.044 B (13.039 KB),
    MD5 `343d2390d5ca1586d0f203b321ea8dd0`,
    SHA256 `14407f87c4529b6714f195e9a17c1dd954640d0c7d80c4de938d049239e9d133`.
  - Keduanya: aapt2 320/4.48.9.154, apksigner v2+v3 true,
    1 signer, `libharness_core.so` 2.119.656 B (release) di
    lib/arm64-v8a, 3 simbol JNI baru ADA, hsurr: 0, arm64-only.
  - **GERBANG SIDIK JARI LULUS — BISA MENIMPA:**
    Main `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`
    ✓, Pure `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f` ✓.
  - Insiden build: service restart VM membunuh build senz pertama
    di tengah jalan (log kosong, proses hilang; .so release sudah
    jadi 07:05). Build diulang dengan `python3 -u` (unbuffered)
    dan selesai normal. Pelajaran: pakai `-u` untuk build
    berdurasi panjang supaya log tidak hilang bila runtime restart.
  - Catatan verifikasi: `apksigner` butuh `java` di PATH —
    verifikasi dijalankan dengan `PATH=$HOME/jdk-21-sys/bin:$PATH`.
- **Dokumen ini diperbarui pasca-CI/APK via `gh.py put-file`**
  (satu file, branch `vc317-rust`) — isi §8 ini + §9.

## §9. Tes perangkat (TES-VC320-SIAP-TEMPEL)

Perilaku user-visible IDENTIK (porting murni fungsi internal;
tidak ada UI/fitur baru). Tidak ada T-baru yang dibutuhkan.
T yang tetap berlaku bila Sendy/Jhon mau verifikasi di perangkat:
T-API-08a/b/c + T-EXP-01 (Concat, dari vc315) dan pemeriksaan umum
bahwa agen tetap memanggil tool dengan benar (daftar tool tidak
berubah — 188 nama, hash terverifikasi §6).
