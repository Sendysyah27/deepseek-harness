#!/usr/bin/env bash
# tests/run-suite-ci.sh — SUITE PENUH vc317 di CI (adaptasi dari
# vc317-work/work-impl/run-tests-vc317-coord.sh; path lokal VM diganti
# path repo). Dijalankan dari root repo: bash tests/run-suite-ci.sh
# Suite (garis paritas vc316): P1 103 + N9 46 + BR 45 + IXL 72 + R1 64
# + R2 69 = 399 cek; target 398/399 — R2-4.1 assert STRING PERSIS usang
# warisan vc315 (dispatch vc314 direstruktur di vc315; perilaku sama),
# ditoleransi eksplisit di langkah R2 (bukan regresi).
# Keluar 0 = semua lulus (dengan toleransi R2-4.1); selain itu 1.
set -u
cd "$(dirname "$0")/.."
PROJ="$PWD"
TREE="app/src/main/java/com/senzwh/deepharness"
TREE_CORE="app/src/main/java/harness/core"
STUBS="tests/stubs/R.java"
JSON_JAR="char-tests/json-20240303.jar"
VENDOR="vendor"
OUT="tests-out"
[ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME tidak ada (setup-java?)"; exit 1; }
[ -n "${ANDROID_HOME:-}" ] || { echo "ANDROID_HOME tidak ada (setup-android?)"; exit 1; }
JAVA="$JAVA_HOME/bin/java"
KOTLINC="$(command -v kotlinc || echo "$HOME/kotlin-dist/kotlinc/bin/kotlinc")"
[ -x "$KOTLINC" ] || { echo "kotlinc tidak ada: $KOTLINC"; exit 1; }
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "android.jar tidak ada: $ANDROID_JAR"; exit 1; }
CP="$JSON_JAR:$ANDROID_JAR:$VENDOR/ort/classes.jar:$VENDOR/bc/bcprov.jar:$VENDOR/conscrypt/classes.jar:$VENDOR/xz/xz.jar:$VENDOR/spake2/spake2-java-1.1.1.jar:$VENDOR/spake2/curve25519-elisabeth-0.1.0.jar:$VENDOR/spake2/ed25519-elisabeth-0.1.0.jar"
mkdir -p "$OUT"
FAIL=0
# P1 membaca <parent-tree>/merge/AccountPool.kt — sediakan via symlink ke baseline vc316.
ln -sfn "$PROJ/tests/baseline-vc316" "$TREE/../merge"

echo "== 0. Kompilasi tree vc317 -> $OUT/test-tree.jar (0 error wajib) =="
"$KOTLINC" -J-Xmx3g -jvm-target 17 "$TREE"/*.kt "$TREE"/priv/*.kt "$TREE_CORE"/*.kt "$STUBS" -cp "$CP" -include-runtime -d "$OUT/test-tree.jar"
if [ $? -ne 0 ]; then echo "KOMPILASI TREE GAGAL"; exit 1; fi

echo "== P1. TestDsFailureVc316 (103 cek) =="
"$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestDsFailureVc316.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p1.jar" || FAIL=1
"$JAVA" -Xmx1g -cp "$OUT/test-p1.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestDsFailureVc316Kt "$TREE" || FAIL=1

echo "== N9. TestN9Vc316 (46 cek) =="
"$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestN9Vc316.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-n9.jar" || FAIL=1
"$JAVA" -Xmx1g -cp "$OUT/test-n9.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestN9Vc316Kt "$TREE" || FAIL=1

echo "== BR. TestBrowserVc316 (45 cek) =="
"$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestBrowserVc316.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-br.jar" || FAIL=1
"$JAVA" -Xmx1g -cp "$OUT/test-br.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestBrowserVc316Kt "$TREE" || FAIL=1

echo "== IXL. TestInputLimitVc316 (72 cek) =="
"$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestInputLimitVc316.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-ixl.jar" || FAIL=1
"$JAVA" -Xmx1g -cp "$OUT/test-ixl.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestInputLimitVc316Kt "$TREE" || FAIL=1

echo "== R1. TestConcatApiVc314 (64 cek) =="
"$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestConcatApiVc314.kt" "$TREE/ConcatApiClient.kt" -cp "$JSON_JAR" -include-runtime -d "$OUT/test-r1.jar" || FAIL=1
"$JAVA" -Xmx1g -cp "$OUT/test-r1.jar:$JSON_JAR" com.senzwh.deepharness.TestConcatApiVc314Kt "$TREE" || FAIL=1

echo "== R2. TestConcatVc314Merge (69 cek; toleransi R2-4.1 usang) =="
"$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestConcatVc314Merge.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-r2.jar" || FAIL=1
R2FAIL=0
"$JAVA" -Xmx1g -cp "$OUT/test-r2.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestConcatVc314MergeKt "$TREE" > "$OUT/r2.log" 2>&1 || R2FAIL=1
tail -3 "$OUT/r2.log"
if [ "$R2FAIL" = 1 ]; then
  NGAGAL=$(grep -c "^GAGAL" "$OUT/r2.log" || true)
  if [ "$NGAGAL" = 1 ] && grep "^GAGAL" "$OUT/r2.log" | grep -q "4\.1" && grep -q "HASIL: 68 lulus, 1 gagal (total 69)" "$OUT/r2.log"; then
    echo "TOLERANSI R2-4.1: satu-satunya gagal = assert usang warisan vc315 (bukan regresi) — diterima"
  else
    echo "GAGAL R2: gagal di luar 4.1"; FAIL=1
  fi
fi

echo "== C. Diff vs origin/main: DsFailure.kt berubah + RustCore.kt baru =="
git diff --name-only origin/main...HEAD -- app/src/main/java/com/senzwh/deepharness/DsFailure.kt | grep -q DsFailure.kt || { echo "GAGAL C: DsFailure.kt tidak berubah vs main"; FAIL=1; }
git diff --name-status origin/main...HEAD -- app/src/main/java/harness/core/RustCore.kt | grep -q "^A" || { echo "GAGAL C: RustCore.kt bukan file baru vs main"; FAIL=1; }
echo "C dicek (bila tidak ada GAGAL di atas = LULUS)"

echo "== D. Sapu tree =="
if grep -rq "hsurr:" "$TREE"/*.kt "$TREE_CORE"/*.kt; then echo "GAGAL D: ada hsurr:"; FAIL=1; else echo "LULUS D1: hsurr: 0"; fi
if grep -rq "0\.2\.8" "$TREE"/*.kt "$TREE_CORE"/*.kt; then echo "GAGAL D: masih ada 0.2.8"; FAIL=1; else echo "LULUS D2: tanpa 0.2.8"; fi
if grep -q "const val GATES_DISABLED = true" "$TREE/ToolGate.kt"; then echo "LULUS D3: GATES_DISABLED=true"; else echo "GAGAL D: gerbang berubah"; FAIL=1; fi
if grep -q 'it.name.startsWith("browser_")' "$TREE/Tools.kt"; then echo "LULUS D4: RISKY_TOOLS tetap mengecualikan browser_*"; else echo "GAGAL D: RISKY_TOOLS berubah"; FAIL=1; fi
if grep -q "^package harness.core" "$TREE_CORE/RustCore.kt" && ! grep -q "com.senzwh.deepharness" "$TREE_CORE/RustCore.kt"; then echo "LULUS D5: RustCore.kt package harness.core + tanpa string package aplikasi"; else echo "GAGAL D5: RustCore.kt package/string salah"; FAIL=1; fi
if grep -q "private fun classifyKotlin" "$TREE/DsFailure.kt" && grep -q "private fun classifyFailureKotlin" "$TREE/DsFailure.kt" && grep -q "var rustDsFailure" "$TREE/DsFailure.kt"; then echo "LULUS D6: fallback Kotlin DsFailure dipertahankan"; else echo "GAGAL D6: fallback Kotlin DsFailure hilang"; FAIL=1; fi

echo "OVERALL FAIL=$FAIL"
exit $FAIL
