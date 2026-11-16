# BUILD-VC318 — Slice 2: PowSolver.kt → Rust (vc318 / 4.48.9.152)

**Tanggal:** 16 Nov 2026 · **Integrator:** subagent slice 2 (koordinator: Jhon)
· **Status:** SELESAI PENUH — kode Rust + JNI + CI hijau + APK VM + CI
terverifikasi (sidik jari VM COCOK vc316, bisa menimpa).

## 1. Keputusan versi (koordinator)

- Slice 2+3+4 mendarat sebagai **SATU rilis vc318 (VERSION_CODE 318,
  VERSION_NAME 4.48.9.152)** — bump SEKALI di awal slice ini.
- Branch tetap **`vc317-rust`** (branch migrasi). Main TIDAK disentuh.
- `RUST_CORE_VERSION` naik **1 → 2** (disepakati ketiga slice 2, 3, 4 semua
  ke 2 — JANGAN naikkan lebih jauh di slice 3/4).
- `RustCore.VERSION` ikut **1 → 2**; `capabilities()` kini membawa modul
  `["ds_failure", "pow_solver"]` + 8 nama fungsi PoW.
- Nama workflow CI (`Android vc317`) dan nama artifact (`harness-vc317-apks`)
  TIDAK diubah (kosmetik; run dibedakan dari nomor run/commit).

## 2. Isi slice 2 (porting murni — perilaku TIDAK diubah)

Sumber: patch set cargo-verified `vc317-work/work-s2/` (PATCH-S2.md).
Hanya 5 file berubah/baru vs basis vc317:

| # | File | Sifat |
|---|---|---|
| 1 | `harness-core/src/pow_solver.rs` — BARU, 741 baris | Port Rust modul `pow_solver` + 36 tes; `#![forbid(unsafe_code)]` |
| 2 | `harness-core/src/lib.rs` | +1 baris: `pub mod pow_solver;` |
| 3 | `harness-core/src/jni_glue.rs` (184 → 318 baris) | +8 fungsi JNI PoW, `RUST_CORE_VERSION` 1 → 2, `guarded_bytes` + `jbytes_to_rust` + `pow_result_json` |
| 4 | `app/src/main/java/harness/core/RustCore.kt` (37 → 57 baris) | `VERSION` 1 → 2 + 8 deklarasi `external fun` |
| 5 | `app/src/main/java/com/senzwh/deepharness/PowSolver.kt` (217 → 374 baris) | Wrapper: API publik TETAP, implementasi lama → privat `*Kotlin`, seam Rust + fallback |

Simbol JNI baru (8): `powSha3Hex`, `powDsHashV1Hex`, `powSha3Bytes`,
`powDsHashV1Bytes`, `powSolveDeepSeekJson`, `powSolveFastJson`,
`powSolveFmtJson`, `powSolveJson` — semua `catch_unwind` (panic → JSON
error/null, tidak pernah menyeberang FFI). `Cargo.toml`/`Cargo.lock`
TIDAK berubah (dependensi sama).

Quirk yang DIREPLIKASI (bukan diperbaiki — porting murni, PATCH-S2.md §4.2):
- `solveFast`: argumen `maxTries` masuk ke `expireAt` (batas efektif
  SELALU 3.000.000) — dikunci tes Rust + tes JVM P2 6.2.
- `difficulty == -1` / `Long.MAX_VALUE` di `solveFmt`/`solve`: Rust
  mengembalikan `None` (Kotlin melempar / target negatif). Difficulty
  server DeepSeek puluhan ribu — tak terjangkau di lapangan.
- `hexToBytes` tetap murni Kotlin; `solve` dipertahankan sebagai fungsi
  terpisah (bukan delegasi ke `solve_fmt`) supaya jejak 1:1.

## 3. Verifikasi basis (langkah 0 — semua LULUS)

- (a) `diff -rq --exclude=target harness-core/ vc317-work/branch-stage/harness-core/`
  → IDENTIK (satu-satunya selisih mentah = dir `target/` hasil build, bukan sumber).
- (b) Patch S2 vs basis: TEPAT 5 file di atas yang berbeda;
  `Cargo.toml`/`Cargo.lock` identik.
- (c) Live `PowSolver.kt` vs `vc317-work/merge/PowSolver.kt` → IDENTIK
  (asli vc316, 217 baris) sebelum ditimpa wrapper.

## 4. Angka tes

### cargo test (live tree)
- Perintah: `cargo test --offline --manifest-path harness-core/Cargo.toml`
- **139/139 lulus, 0 gagal** (103 slice 1 + 36 baru `pow_solver`).

### Suite JVM lokal (VM, `vc317-work/work-impl/run-tests-vc318-coord.sh`)
- **434/435** — P1 103/103 + **P2 36/36 (BARU: paritas wrapper PowSolver)**
  + N9 46/46 + BR 45/45 + IXL 72/72 (varian CI-adapt) + R1 64/64
  + R2 68/69 + C + D (D1–D7).
- Satu-satunya gagal = **R2-4.1** (assert STRING PERSIS usang warisan
  vc315 — SAMA seperti vc317, ditoleransi eksplisit; bukan regresi).
- Tes P2 baru: `vc317-work/work-s2/TestPowSolverVc318.kt` (36 cek: vektor
  FIPS 202 + referensi Keccak independen, tanam MainActivity nonce 4242,
  solveFmt A nonce 2357, format B–H, quirk solveFast maxTries=10,
  seam VERSION 2, fallback `*Kotlin` dipertahankan). Juga masuk CI
  (`tests/TestPowSolverVc318.kt` + step P2 di workflow + D7 di
  `run-suite-ci.sh`).
- Log: `vc317-work/work-impl/run-tests-vc318-coord.log`.

## 5. Deviasi / temuan selama integrasi (jujur)

1. **P2 sempat crash (bug tes saya, bukan kode):** `TestPowSolverVc318.kt`
   membaca RustCore.kt lewat `parentFile` — path salah untuk tree flat
   (`work-impl/harness/...` tidak ada). Diperbaiki: `File(workDir,
   "harness/core/RustCore.kt")` + fallback `../../../harness/...` untuk
   layout repo CI. Re-run: **P2 36/36**.
2. **Section C runner vc317 salah ekspektasi untuk vc318:** runner lama
   mengharap diff vs baseline vc316 = 1 berubah + 1 baru. Nyatanya
   `merge/` SUDAH jadi baseline vc317 pasca-rebuild 00:50 (berisi
   DsFailure wrapper + RustCore v1). Ekspektasi vc318 yang benar: 2
   berubah (PowSolver.kt + RustCore.kt v1→v2) + 0 baru; DsFailure.kt
   TIDAK berubah. Runner diperbaiki; C LULUS.
3. **NDK VM rusak (warisan PATCH-S2 §5, belum diperbaiki):** clang NDK
   symlink putus — `.so` Android TIDAK bisa di-link di VM. Build `.so`
   rilis tetap lewat CI (build.py live hard-fail bila Rust gagal — APK
   vc318 WAJIB berisi .so; tidak ada mode diam-diam tanpa .so).
4. **live `build.py` vs `branch-stage/build.py` memang beda** (bukan
   selisih integrasi): live hard-fail (Rust wajib, JH `~/jdk-21-sys`
   tanpa fallback, PROJ hardcoded); branch-stage CI-adapted (env
   fallback, Rust opsional). Bump versi dilakukan per file (sed baris
   VERSION saja) — JANGAN timpa satu dengan yang lain.
5. **BUILD-VC318.md di-push ke repo root** (`BUILD-VC318.md`) — repo
   sebelumnya TIDAK berisi BUILD-VC317.md (koordinator vc317 tidak
   mem-push-nya). Keputusan integrator: tugas langkah 5 menyebut
   BUILD-VC318.md sebagai bagian dari cermin yang di-push; bila
   koordinator tidak menghendakinya di repo, hapus di push berikutnya.

## 6. Push branch vc317-rust

- Remote HEAD sebelum push: `1d8451b4af300b0c9924f9d7384b7d531fd05c80`
  (commit vc317).
- Push: delta via Git Data API (`base_tree` = tree HEAD remote) —
  file yang diubah: crate (3) + Kotlin (2) + `build.py` + `tests/
  TestPowSolverVc318.kt` (baru) + `tests/run-suite-ci.sh` +
  `.github/workflows/android.yml` + `BUILD-VC318.md` (baru).
- Commit baru: `7339c730ef65947e4eef38002cb6db900f431a3b` (tree
  `aebc9937093d284a26015e975dbad0ab2eb4a4cf`).
- Verifikasi: remote tree == staging (10/10 file delta SHA cocok).

## 7. CI + APK (diisi setelah langkah 7–9)

- CI run: **#10 (43784602557, commit 7339c730) — SUCCESS**
  https://github.com/Sendysyah27/deepseek-harness/actions/runs/43784602557
  (cargo test + suite 0/P1 103/P2 36/N9 46/BR 45/IXL 72/R1 64/R2 69
  dengan R2-4.1 ditoleransi/C/D + build APK Main + Pure + Verify + Upload —
  semua langkah hijau).
- APK CI (`vc317-work/apk-vc318-ci/`, zip 25.224.479 B, MD5
  `7e79e8e6e34084828bdaea353f6f584f`, SHA256
  `29b5be136c0d98bc396c8a50143ca0c7aca4849cf52bc701e985bb2b94a873cb`):
  - Main `DeepSeekHarness-v4.48.9.152.apk` — com.senzwh.deepharness
    318/4.48.9.152, 12.717.164 B, MD5
    `ee18669afc45df8c2af284374131066f`, SHA256
    `7e45808b33aae7ba8c5f8932ad4b322a76ee2b914d5b108ee9f94eff62a2a95b`;
    apksigner Verifies, v2=true, v3=true, 1 signer (CN=Android Debug —
    kunci runner CI); libharness_core.so 336.376 B di lib/arm64-v8a;
    hsurr 0.
  - Pure `Harness-v4.48.9.152-pure.apk` — com.harness.app 318/4.48.9.152,
    12.672.108 B, MD5 `3dc23137ec87ed92b1f89525cbd9f55c`, SHA256
    `8700724d73acd6d116a420293ed1d1ecb54d8e966f80c596cecc75944bfdf22a`;
    apksigner Verifies, v2=true, v3=true, 1 signer (CN=Harness — kunci
    runner CI); libharness_core.so 336.376 B; hsurr 0.
  - Sidik jari CI AKAN beda dari VM (kunci runner) — BUKAN kegagalan.
    Gerbang sidik jari hanya berlaku untuk APK VM (§ bawah).
- APK VM (`vc317-work/apk-vc318-vm/`, build `build.py --flavor senz/pure`,
  log `build-senz.log`/`build-pure.log`, .so 423.688 B):
  - Main `DeepSeekHarness-v4.48.9.152.apk` — com.senzwh.deepharness
    318/4.48.9.152, 12.749.932 B, MD5
    `71250543e783bb8306fd2cb286932b32`, SHA256
    `bc6e3f8c9e85af57027ea81bb9ea30191ee2d054db4009c0a77b198c46640ead`;
    apksigner Verifies, v2=true, v3=true, 1 signer; sidik jari
    `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`
    — **COCOK gerbang vc316**; libharness_core.so 423.688 B di
    lib/arm64-v8a (arm64-only); hsurr 0.
  - Pure `Harness-v4.48.9.152-pure.apk` — com.harness.app 318/4.48.9.152,
    12.704.876 B, MD5 `90dfe2d31ee72a4fa9325737bf3a64dd`, SHA256
    `b69031a71a569d5ae073a70efd5c4ccd092409def501f5539c68c81d8d84bebd`;
    apksigner Verifies, v2=true, v3=true, 1 signer; sidik jari
    `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`
    — **COCOK gerbang vc316**; libharness_core.so 423.688 B; hsurr 0.
  - Bukti isi `.so` (strings di `libharness_core.so` APK VM): ke-6 simbol
    JNI PoW baru ADA (`powSha3Hex`, `powDsHashV1Hex`,
    `powSolveDeepSeekJson`, `powSolveFastJson`, `powSolveFmtJson`,
    `powSolveJson`) + `classifyFailureJson` slice 1.
  - Catatan: `.so` VM (423.688 B) lebih besar dari `.so` CI (336.376 B)
    — pola yang sama seperti vc317 (VM 396.920 B vs CI 313.688 B);
    bukan anomali.
- Tes perangkat: `vc317-work/TES-VC318-SIAP-TEMPEL.txt` (T1–T6, bahasa
  bayi; T2/T3 membuktikan PoW Rust jalan di perangkat — tiap chat baru
  harus menyelesaikan PoW sebelum server menjawab).

## 8. Slice 3: RedactSecrets.kt → Rust (vc318 / 4.48.9.152)

**Tanggal:** 16 Nov 2026 · **Integrator:** subagent slice 3 (koordinator: Jhon)
· **Status:** (diisi setelah langkah 7–9).

Versi TETAP 318/4.48.9.152; `RUST_CORE_VERSION` TETAP 2 (slice 2+3+4
semua ke 2 — JANGAN naikkan). Branch tetap `vc317-rust`. Main TIDAK
disentuh. Patch set: `vc317-work/work-s3/` (PATCH-S3.md, cargo-verified
113/113 standalone).

### 8.1 Isi slice 3 (porting murni — perilaku TIDAK diubah)

Merge TIGA ARAH (bukan timpa — file work-s3 diturunkan dari basis slice
0+1, sedangkan live sudah berisi slice 2; menimpa akan menghilangkan
fungsi PoW). Hanya perubahan S3 yang diambil:

| # | File | Sifat |
|---|---|---|
| 1 | `harness-core/src/redact_secrets.rs` — BARU, 448 baris | Port Rust modul `redact_secrets` + 10 tes; `#![forbid(unsafe_code)]`, tanpa `unwrap()`/`expect()` di jalur produksi; 6 regex, `OnceLock` cache |
| 2 | `harness-core/src/lib.rs` | +1 baris: `pub mod redact_secrets;` (`ds_failure`/`pow_solver` dipertahankan) |
| 3 | `harness-core/src/jni_glue.rs` (318 → 356 baris) | +`guarded_string_nullable` + fungsi JNI `redactSecrets`; capabilities += `redactSecrets`/`redact_secrets`; SEMUA fungsi lama dipertahankan (classifyFailureJson + 8 PoW + helpers); VERSION TETAP 2 |
| 4 | `harness-core/Cargo.toml` + `Cargo.lock` | +`regex = "1"` (1.13.1) + deps (`regex-automata` 0.4.18, `regex-syntax` 0.8.11, `aho-corasick` 1.1.5); lock hasil merge == lock s3 byte-identik (43 paket) |
| 5 | `app/src/main/java/harness/core/RustCore.kt` (57 → 65 baris) | +`external fun redactSecrets(input: String): String?`; VERSION TETAP 2 + 8 fn PoW dipertahankan |
| 6 | `app/src/main/java/com/senzwh/deepharness/RedactSecrets.kt` (86 → 151 baris) | Wrapper: API publik TETAP, implementasi lama → privat `redactKotlin()`/`countMatchesKotlin()`, seam Rust + fallback + saklar `rustRedactSecrets`; `selfTest()` TIDAK diubah |

Simbol JNI baru (1): `redactSecrets` — seam String polos in/out
(BUKAN JSON — deviasi desain PATCH-S3.md §5: payload-nya teks
presentasi; envelope JSON memaksa Kotlin unescape). Sukses → teks
ter-redaksi; panic/error di boundary (`guarded_string_nullable`,
`catch_unwind`) → **`null`** → Kotlin fallback + matikan saklar.

Lookahead (PATCH-S3.md §4, keputusan B2 — restrukturisasi, DIPAKAI):
separator KEYVAL LAZY (`["'=:]+?`); guard lookahead diganti cek manual
`starts_with("[REDAKSI]")`. `fancy-regex` DITOLAK (gagal ketahanan
273 KB: `BacktrackLimitExceeded`, ~20–160x lebih lambat).

Quirk yang DIREPLIKASI 1:1 (bukan diperbaiki — PATCH-S3.md §4):
- Guard lookahead praktis mati (memeriksa literal `"[REDAKSI]"`,
  sementara semua marker `"[REDAKSI: ...]"`).
- BEARER `(?i)` me-match "bearer" DI DALAM marker →
  `"Bearer [REDAKSI: bearer token]"` di-redact lagi jadi ganda.
- Idempotence Kotlin HANYA berlaku untuk sampel self-test;
  `redact(redact(x)) != redact(x)` untuk teks ter-redaksi (E3/E4/E6/
  E7/E8/E13/E17/E29) — dikunci tes Rust
  `kotlin_original_is_not_idempotent_on_pre_redacted_text`.
- `\s`/`\S` ditulis eksplisit ASCII (beda NBSP, dibuktikan E18/E19).

### 8.2 Verifikasi basis (langkah 0 — semua LULUS)

- (a) `diff -rq --exclude=target harness-core/ vc317-work/branch-stage/harness-core/`
  → IDENTIK (sebelum patch); live == vc318 pasca-slice-2.
- (b) Live `RedactSecrets.kt` vs `vc317-work/merge/RedactSecrets.kt` →
  IDENTIK (asli vc316, 86 baris) sebelum ditimpa wrapper.
- (c) Diff S3 vs basis slice 0+1: tepat file di §8.1; `Cargo.lock` s3
  vs live hanya 4 paket regex + 1 baris dep `regex`.

### 8.3 Angka tes

#### cargo test (live tree)
- Perintah: `cargo test --offline --manifest-path harness-core/Cargo.toml`
- **149/149 lulus, 0 gagal** (103 slice 1 + 36 slice 2 + 10 baru
  `redact_secrets`).

#### Suite JVM lokal (VM, `vc317-work/work-impl/run-tests-vc318-s3-coord.sh`)
- **528/529** — P1 103/103 + P2 36/36 + **P3 94/94 (BARU: paritas
  wrapper RedactSecrets)** + N9 46/46 + BR 45/45 + IXL 72/72 (varian
  CI-adapt) + R1 64/64 + R2 68/69 + C (tepat 3 file .kt berubah + 0 baru)
  + D1–D8 (D8 BARU: fallback RedactSecrets dipertahankan).
- Satu-satunya gagal = **R2-4.1** (assert STRING PERSIS usang warisan
  vc315 — SAMA seperti vc317/vc318-slice-2, ditoleransi eksplisit;
  bukan regresi).
- Tes P3 baru: `vc317-work/work-s3/TestRedactSecretsVc318.kt` (94 cek:
  32 self-test + idempotence sampel (once==twice + titik-tetap
  `"password=[REDAKSI: password] bearer token]") + 38 ekstra output
  persis + diskriminator F1–F5 + countMatches/containsSecret +
  seam VERSION 2 + fallback `redactKotlin` dipertahankan +
  `selfTest()` 33/33 PASS). Juga masuk CI
  (`tests/TestRedactSecretsVc318.kt` + step P3 di workflow + D8 di
  `run-suite-ci.sh`).
- Log: `vc317-work/work-impl/run-tests-vc318-s3-coord.log`.

### 8.4 Deviasi / temuan selama integrasi (jujur)

1. **Merge `Cargo.lock` sempat rusak (bug skrip saya, bukan kode):**
   skrip Python pertama merge lock dengan dict keyed nama paket saja —
   `Cargo.lock` punya paket NAMA SAMA versi beda (mis. `jni-sys`,
   `syn`, `windows-sys`) sehingga blok duplikat tertimpa/hilang.
   Terdeteksi LANGSUNG dari `diff` vs lock s3 (blok hilang terlihat).
   Perbaikan: lock live direkonstruksi dari lock s3 minus 4 paket
   regex (diff awal 42 baris TERCAPAI LAGI persis), lalu merge ulang
   dengan kunci (nama, versi) — hasil akhir byte-identik lock s3
   (43 paket, 0 duplikat nama+versi) + `cargo test --locked`-setara
   lolos 149/149. Pelajaran: JANGAN merge lock dengan dict per nama;
   kunci (nama, versi) atau salin dari patch set yang diff-nya sudah
   terbukti bersih.
2. **Live tree TIDAK punya `tests/` / `.github/` di root** (keduanya
   hanya ada di `branch-stage/` = cermin push). Wire P3 CI dilakukan
   langsung di `branch-stage/tests/run-suite-ci.sh` +
   `branch-stage/.github/workflows/android.yml` (pola yang sama seperti
   slice 2 — P2 juga hanya ada di branch-stage).
3. **Section C CI diperkuat:** `run-suite-ci.sh` run_c kini juga
   mengecek `RedactSecrets.kt` berubah vs main + `redact_secrets.rs`
   file baru vs main (selain cek slice 1+2 yang sudah ada).

### 8.5 Push branch vc317-rust

- Remote HEAD sebelum push: `fdb741695cce8f2e3be1dba53e44995dfb3ec0d4`
  (tree `c9290d465728a410eb0331c05a7d7f823e5d5326`) — BUKAN
  `7339c730` dari §6 slice 2: remote sudah bergerak sesudah push slice
  2 (staging == remote di luar delta, terverifikasi check — tidak ada
  drift).
- Push: delta via Git Data API (`base_tree` = tree HEAD remote) —
  file yang diubah: crate (5: `redact_secrets.rs` baru + `lib.rs` +
  `jni_glue.rs` + `Cargo.toml` + `Cargo.lock`) + Kotlin (2:
  `RedactSecrets.kt` + `RustCore.kt`) + `tests/
  TestRedactSecretsVc318.kt` (baru) + `tests/run-suite-ci.sh` +
  `.github/workflows/android.yml` + `BUILD-VC318.md` (baru §8).
- Commit baru: `c207878f9d7ffc53b3b545631fff744ae663a687` (tree
  `aa5528d76eca31f33726e5d2fbba18ca124a8d2e`).
- Verifikasi: remote tree == staging (11/11 file delta SHA cocok).

### 8.6 CI + APK slice 3

- CI run: **#14 (43788476990, commit c207878f) — SUCCESS**
  https://github.com/Sendysyah27/deepseek-harness/actions/runs/43788476990
  (cargo test + suite 0/P1 103/P2 36/**P3 94**/N9 46/BR 45/IXL 72/R1 64/R2 69
  dengan R2-4.1 ditoleransi/C/D + build APK Main + Pure + Verify + Upload —
  semua langkah hijau).
- APK CI (`vc317-work/apk-vc318-s3-ci/`, zip 26.019.989 B, MD5
  `8b96bfb77eeaf020a6a8e502dfc5771e`, SHA256
  `ab545586c99c07f516f3e0267793e1c732474d87b9f586c7ef4bee82e34bd8ed`):
  - Main `DeepSeekHarness-v4.48.9.152.apk` — com.senzwh.deepharness
    318/4.48.9.152, 13.126.764 B, MD5
    `4ff5604abb6dad222867b960389b4301`, SHA256
    `003c22c7ac1f6db9afa43c08ca185ba5d4ad5baef04a1a8946c84f781549074b`;
    apksigner Verifies, v2=true, v3=true, 1 signer (CN=Android Debug —
    kunci runner CI); libharness_core.so 1.435.080 B di lib/arm64-v8a;
    simbol `redactSecrets` ADA; hsurr 0.
  - Pure `Harness-v4.48.9.152-pure.apk` — com.harness.app 318/4.48.9.152,
    13.077.612 B, MD5 `815c436e66d4e7e971e9d004ef8a09ab`, SHA256
    `38d555e8109b781f38a644aa7271a1bd4c06c7ce6243795a81be0997b48c36cf`;
    apksigner Verifies, v2=true, v3=true, 1 signer (CN=Harness — kunci
    runner CI); libharness_core.so 1.435.080 B; simbol `redactSecrets`
    ADA; hsurr 0.
  - Sidik jari CI AKAN beda dari VM (kunci runner) — BUKAN kegagalan.
    Gerbang sidik jari hanya berlaku untuk APK VM (§ bawah).
  - CATATAN UKURAN: `.so` CI membesar 336.376 B (slice 2) → 1.435.080 B
    (slice 3) — crate `regex` + tabel Unicode (`(?i)` case folding,
    kelas karakter) ikut ter-link; pola sama di VM (423.688 →
    1.672.504 B). Bukan anomali; APK total hanya +~400 KB vs slice 2.
- APK VM (`vc317-work/apk-vc318-s3-vm/`, build `build.py --flavor senz/pure`,
  log `build-senz.log`/`build-pure.log`, .so 1.672.504 B):
  - Main `DeepSeekHarness-v4.48.9.152.apk` — com.senzwh.deepharness
    318/4.48.9.152, 13.196.396 B, MD5
    `761894d2215a105792607d2b6ae5d249`, SHA256
    `e15a3cbbafcfc84760b6377a344bb547974c387945c5b276a6fc73d77d2dd5ba`;
    apksigner Verifies, v2=true, v3=true, 1 signer; sidik jari
    `e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177`
    — **COCOK gerbang vc316**; libharness_core.so 1.672.504 B di
    lib/arm64-v8a (arm64-only); hsurr 0.
  - Pure `Harness-v4.48.9.152-pure.apk` — com.harness.app 318/4.48.9.152,
    13.151.340 B, MD5 `967d81c8973fa8cb1837d61303add118`, SHA256
    `bd2f0ec8ba112e954315bb74f5079719a33b2a07e0eda2c028bb4a0a6032f4b2`;
    apksigner Verifies, v2=true, v3=true, 1 signer; sidik jari
    `f7c336ae0a887b4725d81bd59f930efde0a7d1d02d0606001e748e25c5b6553f`
    — **COCOK gerbang vc316**; libharness_core.so 1.672.504 B; hsurr 0.
  - Bukti isi `.so` (strings di `libharness_core.so` APK VM + CI):
    **13/13 simbol JNI ADA** (4 basis: rustCoreVersion,
    rustCoreCapabilities, echo, classifyFailureJson; 8 PoW slice 2;
    `redactSecrets` slice 3).
- Tes perangkat: `vc317-work/TES-VC318-SIAP-TEMPEL.txt` — slice 3
  TIDAK membawa perubahan perilaku yang terlihat user (redaksi
  kredensial bekerja sama seperti sebelumnya, hanya mesinnya pindah
  ke Rust); T1–T6 slice 2 tetap berlaku apa adanya, dengan catatan
  slice 3 di kepala file. Tidak ada T-baru.

## 9. Slice 4: modul kecil → Rust (vc318 / 4.48.9.152) — SLICE TERAKHIR batch vc318

**Tanggal:** 16 Nov 2026 · **Integrator:** subagent slice 4 (koordinator: Jhon)
· **Status:** (diisi setelah langkah 7–9).

Versi TETAP 318/4.48.9.152; `RUST_CORE_VERSION` TETAP 2 (slice 2+3+4
semua ke 2 — JANGAN naikkan). Branch tetap `vc317-rust`. Main TIDAK
disentuh. Patch set: `vc317-work/work-s4/` (PATCH-S4.md, cargo-verified
128/128 standalone — basis slice 0+1).

### 9.1 Isi slice 4 (porting murni — perilaku TIDAK diubah)

Merge TIGA ARAH (bukan timpa — file work-s4 diturunkan dari basis slice
0+1, sedangkan live sudah berisi slice 2 + slice 3; menimpa akan
menghilangkan fungsi PoW + redactSecrets). Hanya perubahan S4 yang diambil:

| # | File | Sifat |
|---|---|---|
| 1 | `harness-core/src/notif_ids.rs` — BARU, 200 baris | 17 konstanta + `all()` + 5 alokator + `checkDuplicates()` + replika `String.hashCode()` Java (unit UTF-16, wrapping i32) |
| 2 | `harness-core/src/system_bar_spec.rs` — BARU, 61 baris | `Spec{status_color, nav_color, light_icons}` + `spec(is_dark)` (nilai dari `chat_theme`) |
| 3 | `harness-core/src/ds_safety_strip.rs` — BARU, 193 baris | `strip()` — 4 regex + `trim()` ala Kotlin (lihat koreksi §9.4.2) |
| 4 | `harness-core/src/mcp_helper.rs` — BARU, 55 baris | `SCRIPT_B64` (2.352 karakter, verbatim dari Kotlin) |
| 5 | `harness-core/src/session_background.rs` — BARU, 66 baris | `render_allowed` / `should_park_decision` / `background_notice()` |
| 6 | `harness-core/src/behaviors.rs` — BARU, 192 baris | `Pack` + `PACKS` (6 pak) + `by_id` + `block` |
| 7 | `harness-core/src/chat_theme.rs` — BARU, 177 baris | 27 warna + 11 angka + `contrast_ratio` / `is_aa_ok` (WCAG 2.1) |
| 8 | `harness-core/src/lib.rs` | +7 baris: `pub mod` 7 modul baru (`ds_failure`/`pow_solver`/`redact_secrets` dipertahankan) |
| 9 | `harness-core/src/jni_glue.rs` (356 → 578 baris) | +7 fungsi bulk JSON + `behavior_pack_json`; capabilities += 7 nama + 7 modul; SEMUA fungsi lama dipertahankan (classifyFailureJson + 8 PoW + redactSecrets + helpers `guarded_string_nullable`/`guarded_bytes`/`jbytes_to_rust`/`pow_result_json`); VERSION TETAP 2 |
| 10 | `harness-core/Cargo.toml` + `Cargo.lock` | **TIDAK BERUBAH** — `regex = "1"` sudah ditambahkan slice 3; lock live byte-identik lock s4 (43 paket) |
| 11 | `app/src/main/java/harness/core/RustCore.kt` (65 → 74 baris) | +7 deklarasi `external fun` (bulk JSON); VERSION TETAP 2 + 8 fn PoW + redactSecrets dipertahankan |
| 12 | 7 wrapper Kotlin (lihat §9.1b) | API publik TETAP; implementasi lama → privat `*Kotlin`, seam Rust + fallback + saklar `rust<X>` |
| 13 | `app/src/main/java/com/senzwh/deepharness/priv/PrivPairNotif.kt` | SATU baris konsekuensial: `const val NOTIF_ID = NotifIds.PRIV_PAIR` → `val NOTIF_ID: Int get() = NotifIds.PRIV_PAIR` (nilai hanya dipakai runtime di `nm.notify`) |
| — | `Flavor.kt` | **DIKECUALIKAN (deferred)** — TIDAK diport, TIDAK disentuh (langkah 0d `build.py` me-rewrite `Flavor.kt` saat build Pure) |

### 9.1b Tujuh wrapper Kotlin

| File | Perubahan |
|---|---|
| `NotifIds.kt` | `const val` (17) → `val` getter Rust-backed (di-cache sekali via bulk JSON); 5 alokator via Rust + fallback `*Kotlin` privat; `all()`/`checkDuplicates()` dari getter |
| `SystemBarSpecVc309.kt` | `spec()` via Rust + fallback `specKotlin` privat |
| `DsSafetyStrip.kt` | `strip()` via Rust + fallback `stripKotlin` privat (regex Kotlin dipertahankan) |
| `McpHelper.kt` | `const val SCRIPT_B64` → `val` getter (Rust, di-cache sekali) + `SCRIPT_B64_KOTLIN` privat (literal utuh dipertahankan) |
| `SessionBackgroundVc304.kt` | 3 fungsi via Rust + fallback `*Kotlin` privat |
| `Behaviors.kt` | `PACKS` lazy (Rust, validasi 6 pak, else `PACKS_KOTLIN`); `block()` via Rust + fallback `blockKotlin` privat; `byId` atas `PACKS` |
| `ChatThemeVc306.kt` | 38 `val` → getter Rust-backed (di-cache sekali); `contrastRatio()` via Rust + fallback `contrastRatioKotlin` privat; `isAaOk` tetap memanggil `contrastRatio` |

Simbol JNI baru (7, satu fungsi JSON bulk per modul): `notifIdsJson`,
`systemBarSpecJson`, `dsSafetyStripJson`, `mcpHelperJson`,
`sessionBackgroundJson`, `behaviorsJson`, `chatThemeJson` — semua
`catch_unwind` (pola `guarded_string` slice 1); JSON
`{"ok":true, ...}` atau `{"ok":false,"error":"rust core error"}`.
Total simbol JNI kini 21 (4 basis + 8 PoW + 1 redact + 7 slice 4);
`external fun` di `RustCore.kt` kini 20.

Quirk yang DIREPLIKASI 1:1 (bukan diperbaiki — PATCH-S4.md §4):
- `String.hashCode()` Java: iterasi unit UTF-16 + `wrapping_mul(31)` +
  `wrapping_add` (emoji = surrogate pair: `"\u{1F600}emoji"` →
  `-1233692477`; `"test"` → `3556498`).
- Regex Java → `regex` crate: tanpa flag `(?-u)` (ditolak crate);
  `\s`/`\w` diganti kelas ASCII eksplisit; `\b` sesudah `ds_safety`
  diganti `(?:>|[^A-Za-z0-9_][^>]*>)`. Dampak: `Safety: café` →
  `caf` termakan + `é` tersisa (sama seperti Kotlin);
  `<ds_safetyé>` dikenali sebagai tag; `<ds_safetyfoo>` bukan tag.
- Konstanta di-cache sekali (`by lazy`) — akses pertama bisa di main
  thread (satu panggilan JNI kecil, dicatat).

### 9.2 Verifikasi basis (langkah 0 — semua LULUS)

- (a) `diff -rq --exclude=target harness-core/ vc317-work/branch-stage/harness-core/`
  → IDENTIK (sebelum patch); live == vc318 pasca-slice-3
  (`redact_secrets` ada, VERSION 2).
- (b) Live 7 file target Kotlin + `RustCore.kt` + `PrivPairNotif.kt`
  vs `vc317-work/merge/` → IDENTIK (asli vc316) sebelum ditimpa wrapper.
- (c) Diff S4 vs basis slice 0+1: tepat file di §9.1; `Cargo.lock` s4
  vs live byte-identik; `Cargo.toml` hanya beda komentar (regex sudah
  ada di live) → keduanya TIDAK diubah.
- (d) `grep`: satu-satunya pemakai posisi `const` atas `NotifIds.*` di
  tree = `PrivPairNotif.kt:23`; `McpHelper.SCRIPT_B64` hanya dipakai
  runtime (konkatenasi di `Tools.kt`).

### 9.3 Angka tes

#### cargo test (live tree)
- Perintah: `cargo test --offline --manifest-path harness-core/Cargo.toml`
- **174/174 lulus, 0 gagal** (103 slice 1 + 36 slice 2 + 10 slice 3
  + 25 baru slice 4).

#### Suite JVM lokal (VM, `vc317-work/work-impl/run-tests-vc318-s4-coord.sh`)
- **763/764** — P1 103/103 + P2 36/36 + P3 94/94 + **P4 235/235 (BARU:
  paritas wrapper slice 4)** + N9 46/46 + BR 45/45 + IXL 72/72 (varian
  CI-adapt) + R1 64/64 + R2 68/69 + C (tepat 11 file .kt berubah + 0 baru;
  Flavor.kt TIDAK berubah) + D1–D9 (D9 BARU: fallback slice 4
  dipertahankan).
- Satu-satunya gagal = **R2-4.1** (assert STRING PERSIS usang warisan
  vc315 — SAMA seperti vc317/vc318-slice-2/slice-3, ditoleransi eksplisit;
  bukan regresi).
- Tes P4 baru: `vc317-work/work-s4/TestSmallModulesVc318.kt` (235 cek:
  17 konstanta NotifIds + all()/duplikat + 6 vektor hashCode + 34 cek
  alokator + SystemBarSpec 11 + DsSafetyStrip 29 + McpHelper 5 +
  SessionBackground 11 + Behaviors 32 + ChatTheme 57 (27 warna + 11
  angka + 3 desimal + alias + 7 kontras + 8 isAaOk) + seam/fallback 27).
  Juga masuk CI (`tests/TestSmallModulesVc318.kt` + step P4 di workflow
  + D9 di `run-suite-ci.sh`).
- Log: `vc317-work/work-impl/run-tests-vc318-s4-coord.log`.

### 9.4 Deviasi / temuan selama integrasi (jujur)

1. **Wrapper `ChatThemeVc306.kt` dari patch set TIDAK BISA DIKOMPILASI
   (bug patch set, bukan kode):** 5 baris ikon ditulis
   `val iconXs: Int get() = themeConst("iconXs", 16 // komentar)` —
   komentar `//` menelan kurung tutup `)` sehingga kotlinc gagal
   76 error (`expecting ')'` + `unresolved reference: iconLg` dkk.
   di `MainActivity.kt`). PATCH-S4 §5 mengakui Kotlin tidak dikompilasi
   di slice ini. Perbaikan integrator (ronde 1): komentar dipindah ke
   atas barisnya (teks komentar dipertahankan, nilai tidak berubah),
   di ketiga salinan (live + `work-impl/integrated` + `work-s4/
   integrated`). Re-run suite: kompilasi lolos.
2. **Klaim PATCH-S4 §4.3 tentang `trim()` Kotlin SALAH — dan tes P4
   menangkap divergensi Rust-nya (ronde 2, temuan terpenting slice
   ini):** klaim awal: "`trim()` Kotlin hanya buang char kode ≤ U+0020
   (NBSP TIDAK dibuang)". Probe JVM (kotlinc 1.9.20 + JDK 21,
   `/tmp/nbsp-probe/`, 16 Nov 2026) MEMBUKTIKAN sebaliknya:
   `"\u00A0teks\u00A0".trim()` == `"teks"`; no-arg `trim()` ==
   `trim { it.isWhitespace() }` (Java `Character.isWhitespace`),
   BUKAN `trim { it <= ' ' }` (NBSP + EM SPACE U+2003 dipertahankan
   yang kedua; FS U+001C dibuang keduanya; NBSP tengah dipertahankan).
   Akibatnya `kotlin_trim` Rust lama (`<= 0x20`) MENYIMPANG dari
   Kotlin asli untuk teks ber-NBSP di tepi — begitu pula
   `str::trim()` Rust (Unicode White_Space tidak mencakup U+00A0 /
   U+001C–U+001F). Perbaikan integrator: `kotlin_trim` kini memakai
   himpunan Java `Character.isWhitespace` eksplisit (0x09–0x0D, 0x20,
   0x1C–0x1F, 0x85, 0xA0, 0x1680, 0x2000–0x200A, 0x2028, 0x2029,
   0x202F, 0x205F, 0x3000) + tes Rust `kotlin_trim_keeps_nbsp`
   diganti `kotlin_trim_matches_kotlin_is_whitespace` (NBSP/EM/FS
   tepi dibuang, NBSP tengah dipertahankan, `strip` ujung-ke-ujung).
   Ini BUKAN perubahan perilaku — ini MENGEMBALIKAN perilaku ke
   Kotlin asli (porting murni); yang diperbaiki adalah klaim patch
   set yang salah. Cargo tetap 174/174; P4 235/235 (cek NBSP
   diperbaiki + 1 cek NBSP tengah baru). Perbaikan yang sama
   diterapkan ke salinan `work-s4/harness-core/src/ds_safety_strip.rs`
   supaya patch set tidak tertinggal dalam keadaan salah.
3. **Hitungan PATCH-S4 §1/§4 sedikit meleset (kosmetik):** warna
   ChatTheme = 27 (bukan 26) + angka = 11 (bukan 12); total 38 nama
   di `themeNames` tetap cocok. Tes P4 memakai 27 + 11.
4. **Live tree TIDAK punya `tests/` / `.github/` di root** (keduanya
   hanya ada di `branch-stage/` = cermin push). Wire P4 CI dilakukan
   langsung di `branch-stage/tests/run-suite-ci.sh` +
   `branch-stage/.github/workflows/android.yml` (pola yang sama seperti
   slice 2/3).

### 9.5 Push branch vc317-rust

- Remote HEAD sebelum push: `39e8c1a5164800026cc79c63e54d519eede7921e`
  (tree `44fb64c25e209edd9bffd2c039781847dc665db5`) — BUKAN
  `c207878f` dari §8.5 slice 3: remote sudah bergerak sesudah push slice
  3 (staging == remote di luar delta, terverifikasi check — tidak ada
  drift).
- Push: delta via Git Data API (`base_tree` = tree HEAD remote) —
  file yang diubah: crate (9: 7 modul Rust baru + `lib.rs` +
  `jni_glue.rs`; `Cargo.toml`/`Cargo.lock` TIDAK berubah) + Kotlin (9:
  7 wrapper + `RustCore.kt` + `priv/PrivPairNotif.kt`) + `tests/
  TestSmallModulesVc318.kt` (baru) + `tests/run-suite-ci.sh` +
  `.github/workflows/android.yml` + `BUILD-VC318.md` (baru §9).
- Commit baru: `6f52c3d31ec0d4243616b03aae528d300a26e205` (tree
  `a293b96d5ac9ff0480aa7c915a23ac5c75c2dd6d`).
- Verifikasi: remote tree == staging (22/22 file delta SHA cocok).

### 9.6 CI + APK slice 4

(diisi setelah langkah 7–9)

## PENUTUP BATCH vc318 — (diisi setelah APK VM slice 4 terverifikasi)

APK VM kanonis batch vc318 = `vc317-work/apk-vc318-s4-vm/` (slice
terakhir, berisi semua slice 2+3+4).
