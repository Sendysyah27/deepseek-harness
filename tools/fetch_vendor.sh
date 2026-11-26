#!/usr/bin/env bash
# fetch_vendor.sh — unduh semua dependensi biner yang dibutuhkan build.py
# ke ./vendor/ sesuai path yang diharapkan (lihat build.py baris 229-238).
#
# vendor/ TIDAK disertakan di repo (lihat README "Persyaratan build").
# Skrip ini mengisi:
#   vendor/ort/classes.jar + vendor/ort/jni/arm64-v8a/*.so   (ONNX Runtime)
#   vendor/bc/bcprov.jar                                      (Bouncy Castle)
#   vendor/conscrypt/classes.jar + jni/arm64-v8a/*.so         (Conscrypt)
#   vendor/xz/xz.jar                                          (XZ)
#   vendor/spake2/*.jar                                       (SPAKE2 + curve/ed25519)
#
# Yang TIDAK diunduh otomatis (binari kustom, sediakan manual):
#   vendor/proot/jni/arm64-v8a/{libproot,libprootloader,libtalloc,libandroid-shmem}.so
#
# Pemakaian:
#   bash tools/fetch_vendor.sh            # unduh semua
#   bash tools/fetch_vendor.sh --jars-only  # hanya .jar (cukup untuk kotlinc type-check)
#
# Lisensi: setiap paket mengikuti lisensi upstream masing-masing.
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENDOR="$PROJ/vendor"
MC="https://repo1.maven.org/maven2"
JITPACK="https://jitpack.io"

# Pin versi (samakan dengan README bila diubah).
ORT_VER="1.22.0"
BC_VER="1.80"
CONSCRYPT_VER="2.5.3"
XZ_VER="1.10"
SPAKE_VER="1.1.1"
CURVE_VER="0.1.0"
ED_VER="0.1.0"

JARS_ONLY=0
[ "${1:-}" = "--jars-only" ] && JARS_ONLY=1

FETCH=""
if command -v curl >/dev/null 2>&1; then FETCH="curl -fsSL"
elif command -v wget >/dev/null 2>&1; then FETCH="wget -qO-"
else echo "Butuh curl atau wget." >&2; exit 1; fi

get() { # get <url> <dest>
  local url="$1" dest="$2"
  mkdir -p "$(dirname "$dest")"
  if [ -f "$dest" ] && [ -s "$dest" ]; then echo "  ada: ${dest#$PROJ/}"; return 0; fi
  echo "  unduh: $url"
  $FETCH "$url" > "$dest" || { echo "GAGAL unduh $url" >&2; rm -f "$dest"; exit 1; }
}

unzip_to() { # unzip_to <zip> <member-glob> <destdir>
  local z="$1" pat="$2" out="$3"
  mkdir -p "$out"
  unzip -o -j "$z" "$pat" -d "$out" >/dev/null 2>&1 || true
}

echo "== vendor dir: $VENDOR"
mkdir -p "$VENDOR"

echo "== ONNX Runtime ($ORT_VER)"
ORT_AAR="$VENDOR/ort/.tmp/onnxruntime-android.aar"
get "$MC/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VER/onnxruntime-android-$ORT_VER.aar" "$ORT_AAR"
unzip_to "$ORT_AAR" "classes.jar" "$VENDOR/ort"
mv -f "$VENDOR/ort/classes.jar" "$VENDOR/ort/classes.jar" 2>/dev/null || true
mkdir -p "$VENDOR/ort/jni/arm64-v8a"
( cd "$VENDOR/ort/.tmp" && unzip -o -j "$ORT_AAR" "jni/arm64-v8a/*" -d "$VENDOR/ort/jni/arm64-v8a" >/dev/null 2>&1 || true )
rm -rf "$VENDOR/ort/.tmp"

echo "== Bouncy Castle ($BC_VER)"
get "$MC/org/bouncycastle/bcprov-jdk18on/$BC_VER/bcprov-jdk18on-$BC_VER.jar" "$VENDOR/bc/bcprov.jar"

echo "== Conscrypt ($CONSCRYPT_VER)"
CON_AAR="$VENDOR/conscrypt/.tmp/conscrypt-android.aar"
get "$MC/org/conscrypt/conscrypt-android/$CONSCRYPT_VER/conscrypt-android-$CONSCRYPT_VER.aar" "$CON_AAR"
unzip_to "$CON_AAR" "classes.jar" "$VENDOR/conscrypt"
mkdir -p "$VENDOR/conscrypt/jni/arm64-v8a"
( cd "$VENDOR/conscrypt/.tmp" && unzip -o -j "$CON_AAR" "jni/arm64-v8a/*" -d "$VENDOR/conscrypt/jni/arm64-v8a" >/dev/null 2>&1 || true )
rm -rf "$VENDOR/conscrypt/.tmp"

echo "== XZ ($XZ_VER)"
get "$MC/org/tukaani/xz/$XZ_VER/xz-$XZ_VER.jar" "$VENDOR/xz/xz.jar"

echo "== SPAKE2 ($SPAKE_VER) + curve25519/ed25519 elisabeth"
get "$JITPACK/com/github/Flyfish233/spake2-java/$SPAKE_VER/spake2-java-$SPAKE_VER.jar" "$VENDOR/spake2/spake2-java-$SPAKE_VER.jar"
get "$MC/cafe/cryptography/curve25519-elisabeth/$CURVE_VER/curve25519-elisabeth-$CURVE_VER.jar" "$VENDOR/spake2/curve25519-elisabeth-$CURVE_VER.jar"
get "$MC/cafe/cryptography/ed25519-elisabeth/$ED_VER/ed25519-elisabeth-$ED_VER.jar" "$VENDOR/spake2/ed25519-elisabeth-$ED_VER.jar"

if [ "$JARS_ONLY" = "0" ]; then
  echo "== proot: MANUAL"
  echo "   Letakkan di vendor/proot/jni/arm64-v8a/:"
  echo "     libproot.so libprootloader.so libtalloc.so libandroid-shmem.so"
  echo "   (binari kustom dari sumber proot; tidak diunduh skrip ini)"
fi

echo "== selesai."
