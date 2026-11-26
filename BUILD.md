# Build Guide — Harness

Build memakai `build.py` (Python 3, **tanpa Gradle**): `aapt2` → `kotlinc` →
`d8` → `zipalign` → `apksigner`.

## Prasyarat

| Komponen | Versi / path | Catatan |
|---|---|---|
| Python 3 | 3.10+ | jalankan `python3 build.py` |
| JDK | 21 | `JAVA_HOME`; dipakai `keytool`, `d8`, `apksigner` (via java) |
| Android SDK | `ANDROID_HOME` atau `~/android-sdk` | butuh `platforms/android-34/android.jar` |
| Android build-tools | `34.0.0` (atau terbaru) | `~/android-sdk/build-tools/34.0.0` — `d8`, `zipalign`, `apksigner` |
| Kotlin compiler | 2.0.21 (disarankan) | default dicari di `~/kotlin-dist/kotlinc/bin/kotlinc`; override `HARNESS_KOTLINC` |
| Dependensi biner | `vendor/` | **tidak ada di repo** — jalankan `tools/fetch_vendor.sh` |

`build.py` juga menerima `HARNESS_BT` (override folder build-tools) dan
`HARNESS_PROJ` (override root proyek).

## 1. Ambil dependensi biner

```bash
bash tools/fetch_vendor.sh
```

Mengisi `vendor/`:

| Path | Isi |
|---|---|
| `vendor/ort/classes.jar` + `vendor/ort/jni/arm64-v8a/*.so` | ONNX Runtime (Maven) |
| `vendor/bc/bcprov.jar` | Bouncy Castle (Maven) |
| `vendor/conscrypt/classes.jar` + `jni/arm64-v8a/libconscrypt_jni.so` | Conscrypt (Maven) |
| `vendor/xz/xz.jar` | XZ (Maven) |
| `vendor/spake2/*.jar` | SPAKE2 + curve25519/ed25519 elisabeth (JitPack + Maven) |

**Manual** (binari kustom, tidak diunduh): `vendor/proot/jni/arm64-v8a/`
berisi `libproot.so libprootloader.so libtalloc.so libandroid-shmem.so`.
Tanpa folder ini `build.py` mencetak `WARN 6` dan tetap membangun APK
(fitur proot mati).

## 2. Build

```bash
export ANDROID_HOME="$HOME/android-sdk"
export HARNESS_KOTLINC="$HOME/kotlin-dist/kotlinc/bin/kotlinc"

python3 build.py                # flavor senz  → DeepSeekHarness-v<ver>.apk
python3 build.py --flavor pure  # flavor pure  → Harness-v<ver>-pure.apk
```

Keystore debug dibuat otomatis bila belum ada (`debug.keystore` /
`pure.keystore`).

## Catatan Termux (aarch64)

Build-tools Google berisi biner **x86_64** (`aapt2`, `zipalign`) yang tidak
jalan di Termux. Yang dipakai:

- `aapt2` → paket Termux `aapt` (`pkg install aapt`), symlink ke
  `$ANDROID_HOME/build-tools/34.0.0/aapt2`.
- `zipalign` → tidak ada di Termux; untuk build lokal cukup shim salin
  (`cp in out`). Alignment penuh hanya terjadi di CI (x86_64).
- `d8`/`apksigner` → skrip Java, jalan di aarch64 setelah
  `termux-fix-shebang` pada file di build-tools.

## CI (GitHub Actions)

`.github/workflows/build.yml` membangun **senz + pure** di `ubuntu-latest`
(JDK 21 + Android SDK + kotlinc 2.0.21 + `fetch_vendor.sh`), lalu
mengunggah APK sebagai artifact. Trigger: push ke `main`, PR, manual
(`workflow_dispatch`).

> Workflow butuh token dengan scope `workflow` untuk di-push:
> `gh auth refresh -h github.com -s workflow`.

## Pemecahan masalah

| Gejala | Sebab / solusi |
|---|---|
| `d8` NPE `Cannot invoke "String.length()"` | atribut `MethodParameters` (`name_index=0`) di jar dependensi. `build.py` sudah men-strip semua jar di langkah 4. Bila muncul dari jar baru, tambahkan ke `strip_jar(...)`. |
| `Exec format error` pada `aapt2`/`zipalign` | biner x86_64 di host ARM. Lihat "Catatan Termux". |
| `bad interpreter: /usr/bin/env` | jalankan `termux-fix-shebang <file>` (kotlinc, build-tools). |
| `vendor/proot belum lengkap` | sediakan `vendor/proot/jni/arm64-v8a/` (lihat atas) atau abaikan. |
| `cargo tidak ada` | langkah Rust dilewati; APK tetap jalan via fallback Kotlin di `DsFailure`. |
