# Harness — Aplikasi Agen AI Android (DeepSeek-only)

> **English summary:** Harness is an on-phone Android AI-agent app powered by DeepSeek.
> No API key is needed — it drives your own DeepSeek account through an in-app
> WebView session. The agent can chat, run tools (shell via Termux, proot workers,
> ADB), keep long-term memory, ask for approval before sensitive actions, and
> connect MCP servers. Build it with `python3 build.py --flavor pure`.
> Current version: **vc302 / 4.48.9.136**. License: MIT.

## Tentang

Harness adalah aplikasi agen AI yang berjalan sepenuhnya di HP Android. Otaknya
DeepSeek — **tanpa API key**: app memakai sesi login DeepSeek kamu sendiri lewat
WebView resmi (`chat.deepseek.com`) di dalam app. Password tidak pernah disimpan
app; yang dipakai hanya sesi login.

Varian di repo ini adalah **Pure** (bersih/original): tanpa personalisasi pemilik
di source. Nama persona default netral (`Asisten` / `Operator`), label app
`Harness`, dan port serve default `4100` / `4199` / `4200`.

- **Versi terkini:** vc302 — `4.48.9.136` (versionCode 302)
- **Hub / unduhan:** https://pos-ssr.site
- **Lisensi:** MIT

## Fitur utama

- **Agen chat 1-1 & grup** — DeepSeek sebagai otak, dengan konteks layar,
  memori jangka panjang (MemoryDb), dan prompt sistem yang bisa diatur.
- **Tool untuk agen** — perintah shell via Termux, worker proot (Device-as-Server),
  ADB pairing, baca layar (accessibility), kamera, notifikasi, dan lain-lain.
- **Approval sebelum aksi sensitif** — panel persetujuan + PIN untuk tool
  berisiko; aksi ke kolom password selalu ditolak.
- **Multi-slot DeepSeek** — beberapa akun/sesi DeepSeek (slot) dengan panen
  token sesi otomatis dari WebView.
- **Skill Forge** — skill agen berversi (append-only) + riwayat versi.
- **MCP** — konektor MCP (HTTP/SSE) + OAuth loopback, termasuk server bawaan
  (Slack, Notion, Linear, GitHub Copilot, Google, dsb.).
- **Briefing harian** — ringkasan rilis AI + aktivitas kemarin, tiap pagi.
- **Redaksi rahasia** — pola kredensial (token, API key, PEM) disensor otomatis
  sebelum ditampilkan atau disimpan ke jejak (`RedactSecrets.kt`).
- **ONNX Runtime di perangkat** — embedding lokal untuk memori
  (butuh `vendor/`, lihat bagian Build).

## Persyaratan build

Build memakai `build.py` (Python 3, tanpa Gradle — aapt2 + kotlinc + d8 +
apksigner langsung). Yang dibutuhkan:

| Kebutuhan | Keterangan |
|---|---|
| Python 3 | menjalankan `build.py` |
| JDK 21 | `JAVA_HOME` — di `build.py`: `~/jdk-21.0.12.1+1` |
| Android SDK | build-tools **34.0.0** (`aapt2`, `d8`, `zipalign`, `apksigner`) + platform **android-34** (`android.jar`) — di `build.py`: `~/android-sdk` |
| kotlinc | `~/kotlin-dist/kotlinc/bin/kotlinc` + `kotlin-stdlib.jar` |
| `vendor/` | **TIDAK termasuk di repo ini** — dependensi biner yang dipakai `build.py`: ONNX Runtime (`ort/classes.jar` + `libonnxruntime*.so` arm64), Bouncy Castle (`bcprov.jar`), SPAKE2 (3 jar), Conscrypt (`classes.jar` + `.so`), XZ (`xz.jar`), proot (`libproot*.so` arm64). Sediakan sendiri dari upstream masing-masing, lalu letakkan di `vendor/` sesuai path di `build.py`. |
| `strip_methodparams.py` | Termasuk di repo ini (`tools/strip_methodparams.py`) — strip atribut `MethodParameters` dari stdlib/xz sebelum d8. `build.py` memakai salinan repo dulu, fallback ke `~/workspace/senz-browser/tools/`. |

> **Catatan path:** `build.py` mendeteksi lokasi repo otomatis (direktori
> `build.py` sendiri; override lewat env `HARNESS_PROJ`). Path tool lain
> (`SDK`, `KOTLINC`, `JH`) masih absolut di awal `build.py` — sesuaikan dengan
> lokasi Android SDK / kotlinc / JDK di mesinmu bila berbeda.

## Cara build

```bash
# Varian Pure (publik): paket com.harness.app, label "Harness"
python3 build.py --flavor pure
# -> Harness-v4.48.9.136-pure.apk

# Varian mainline (default bila tanpa --flavor): paket com.senzwh.deepharness
python3 build.py --flavor senz
# -> DeepSeekHarness-v4.48.9.136.apk
```

`--flavor` juga bisa lewat environment: `FLAVOR=pure python3 build.py`.

Alur build (`build.py`): salin source → (pure: rename package + terapkan
pengganti + timpa splash/ikon) → `aapt2 compile` → `aapt2 link`
(minSdk 26, targetSdk 34) → `kotlinc` → strip `MethodParameters` →
`d8` → paket + `.so` native → `zipalign` → `apksigner`.

**Signing:** bila keystore belum ada, `build.py` membuatnya otomatis dengan
`keytool` (debug keystore, password `android`). Untuk rilis publik, buat
keystore sendiri dan jangan pernah commit keystore ke repo.

## Struktur repo

```
app/            Source Kotlin + resource + manifest (147 file)
  src/main/
    AndroidManifest.xml
    java/com/senzwh/deepharness/   # package source; --flavor pure me-rename
    res/                          # drawable, layout, mipmap, values, xml
build.py        Skrip build (aapt2 + kotlinc + d8 + apksigner) + flavor pure
flavor/pure/    Splash + ikon launcher varian Pure (dipakai build --flavor pure)
tools/          strip_methodparams.py (helper build, dipakai build.py)
README.md
LICENSE
.gitignore
```

`SCRUB-LOG.md` (bila ada di direktori kerja) adalah catatan internal proses
pembersihan tree ini — **bukan bagian repo** dan tidak ikut commit.

## Catatan keamanan

- App ini meminta izin luas (accessibility, overlay, SMS, lokasi, dsb.) karena
  memang dirancang sebagai agen di HP. Pahami tiap izin sebelum menggunakannya.
- Token sesi DeepSeek dan token MCP disimpan di `SecureStore` (tidak pernah
  ditampilkan di UI) dan tidak pernah di-hardcode di source.
- Jangan pernah commit `*.keystore`, `.env`, atau APK ke repo ini
  (sudah ditutup `.gitignore`).

## Lisensi

MIT — lihat `LICENSE`. Copyright (c) 2026 Sendy Syah Ramadhan.
