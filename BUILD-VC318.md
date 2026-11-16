# BUILD-VC318 — Slice 2: PowSolver.kt → Rust (vc318 / 4.48.9.152)

**Tanggal:** 16 Nov 2026 · **Integrator:** subagent slice 2 (koordinator: Jhon)
· **Status:** INTEGRASI LOKAL + PUSH BRANCH SELESAI (pra-CI — angka CI/VM di §7
menyusul setelah langkah 7–9; dokumen ini diperbarui lagi pasca-verifikasi).

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
- Commit baru: _(diisi setelah push)_.
- Verifikasi: remote tree == staging (0 selisih SHA pada file yang diubah).

## 7. CI + APK (diisi setelah langkah 7–9)

- CI run: _(URL + nomor run + hasil)_.
- APK CI: _(path + ukuran + MD5 + SHA256; sidik jari CI beda dari VM —
  kunci runner, BUKAN kegagalan)_.
- APK VM: _(path `vc317-work/apk-vc318-vm/` + ukuran + MD5 + SHA256 +
  sidik jari; gerbang: Main `e738145b…`, Pure `f7c336ae…` — HARUS cocok
  vc316, bila beda = BUILD GAGAL)_.
- Tes perangkat: `vc317-work/TES-VC318-SIAP-TEMPEL.txt`.
