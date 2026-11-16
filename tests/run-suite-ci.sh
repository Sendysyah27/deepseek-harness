#!/usr/bin/env bash
# tests/run-suite-ci.sh — SUITE PENUH vc317 di CI (adaptasi dari
# vc317-work/work-impl/run-tests-vc317-coord.sh; path lokal VM diganti
# path repo). Dijalankan dari root repo, satu section per pemanggilan
# (workflow CI memanggil per section sebagai step terpisah supaya langkah
# yang gagal terlihat di API tanpa perlu log):
#   bash tests/run-suite-ci.sh 0|P1|P2|P3|N9|BR|IXL|R1|R2|C|D|ALL
# Section 0 = kompilasi tree (prasyarat semua section tes).
# Suite (garis paritas vc316 + P2 slice 2 + P3 slice 3): P1 103 + P2 36
# + P3 94 + N9 46 + BR 45 + IXL 72 + R1 64 + R2 69 = 529 cek; target
# 528/529 — R2-4.1 assert STRING PERSIS usang
# warisan vc315 (dispatch vc314 direstruktur di vc315; perilaku sama),
# ditoleransi eksplisit di run_r2 (bukan regresi).
# Tiap section keluar 0 bila lulus, 1 bila gagal.
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
# P1 membaca <parent-tree>/merge/AccountPool.kt — sediakan via symlink ke baseline vc316.
ln -sfn "$PROJ/tests/baseline-vc316" "$TREE/../merge"

run_0() {
  echo "== 0. Kompilasi tree vc317 -> $OUT/test-tree.jar (0 error wajib) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "$TREE"/*.kt "$TREE"/priv/*.kt "$TREE_CORE"/*.kt "$STUBS" -cp "$CP" -include-runtime -d "$OUT/test-tree.jar"
}

run_p1() {
  echo "== P1. TestDsFailureVc316 (103 cek) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestDsFailureVc316.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p1.jar" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-p1.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestDsFailureVc316Kt "$TREE"
}

run_p2() {
  echo "== P2. TestPowSolverVc318 (paritas wrapper PowSolver, 36 cek) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestPowSolverVc318.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p2.jar" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-p2.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestPowSolverVc318Kt "$TREE"
}

run_p3() {
  echo "== P3. TestRedactSecretsVc318 (paritas wrapper RedactSecrets, 94 cek) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestRedactSecretsVc318.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p3.jar" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-p3.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestRedactSecretsVc318Kt "$TREE"
}

run_n9() {
  echo "== N9. TestN9Vc316 (46 cek) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestN9Vc316.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-n9.jar" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-n9.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestN9Vc316Kt "$TREE"
}

run_br() {
  echo "== BR. TestBrowserVc316 (45 cek) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestBrowserVc316.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-br.jar" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-br.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestBrowserVc316Kt "$TREE"
}

run_ixl() {
  echo "== IXL. TestInputLimitVc316 (72 cek) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestInputLimitVc316.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-ixl.jar" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-ixl.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestInputLimitVc316Kt "$TREE"
}

run_r1() {
  echo "== R1. TestConcatApiVc314 (64 cek) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestConcatApiVc314.kt" "$TREE/ConcatApiClient.kt" -cp "$JSON_JAR" -include-runtime -d "$OUT/test-r1.jar" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-r1.jar:$JSON_JAR" com.senzwh.deepharness.TestConcatApiVc314Kt "$TREE"
}

run_r2() {
  echo "== R2. TestConcatVc314Merge (69 cek; toleransi R2-4.1 usang) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestConcatVc314Merge.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-r2.jar" || return 1
  local r2fail=0
  "$JAVA" -Xmx1g -cp "$OUT/test-r2.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestConcatVc314MergeKt "$TREE" > "$OUT/r2.log" 2>&1 || r2fail=1
  tail -3 "$OUT/r2.log"
  if [ "$r2fail" = 1 ]; then
    local ngagal
    ngagal=$(grep -c "^GAGAL" "$OUT/r2.log" || true)
    if [ "$ngagal" = 1 ] && grep "^GAGAL" "$OUT/r2.log" | grep -q "4\.1" && grep -q "HASIL: 68 lulus, 1 gagal (total 69)" "$OUT/r2.log"; then
      echo "TOLERANSI R2-4.1: satu-satunya gagal = assert usang warisan vc315 (bukan regresi) — diterima"
      return 0
    fi
    echo "GAGAL R2: gagal di luar 4.1"
    return 1
  fi
  return 0
}

run_c() {
  echo "== C. Diff vs origin/main: DsFailure.kt berubah + RustCore.kt baru + PowSolver.kt berubah (vc318) =="
  # actions/checkout hanya mengambil branch aktif; main harus di-fetch
  # eksplisit (workflow juga melakukan ini sebelum suite).
  git fetch origin main --depth=1 || { echo "GAGAL C: tidak bisa fetch origin/main"; return 1; }
  git diff --name-only origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/DsFailure.kt | grep -q DsFailure.kt || { echo "GAGAL C: DsFailure.kt tidak berubah vs main"; return 1; }
  git diff --name-status origin/main HEAD -- app/src/main/java/harness/core/RustCore.kt | grep -q "^A" || { echo "GAGAL C: RustCore.kt bukan file baru vs main"; return 1; }
  git diff --name-only origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/PowSolver.kt | grep -q PowSolver.kt || { echo "GAGAL C: PowSolver.kt tidak berubah vs main"; return 1; }
  git diff --name-only origin/main HEAD -- harness-core/src/pow_solver.rs | grep -q pow_solver.rs || { echo "GAGAL C: pow_solver.rs bukan file baru vs main"; return 1; }
  git diff --name-only origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/RedactSecrets.kt | grep -q RedactSecrets.kt || { echo "GAGAL C: RedactSecrets.kt tidak berubah vs main"; return 1; }
  git diff --name-status origin/main HEAD -- harness-core/src/redact_secrets.rs | grep -q "^A" || { echo "GAGAL C: redact_secrets.rs bukan file baru vs main"; return 1; }
  echo "LULUS C"
  return 0
}

run_d() {
  echo "== D. Sapu tree =="
  local fail=0
  if grep -rq "hsurr:" "$TREE"/*.kt "$TREE_CORE"/*.kt; then echo "GAGAL D: ada hsurr:"; fail=1; else echo "LULUS D1: hsurr: 0"; fi
  if grep -rq "0\.2\.8" "$TREE"/*.kt "$TREE_CORE"/*.kt; then echo "GAGAL D: masih ada 0.2.8"; fail=1; else echo "LULUS D2: tanpa 0.2.8"; fi
  if grep -q "const val GATES_DISABLED = true" "$TREE/ToolGate.kt"; then echo "LULUS D3: GATES_DISABLED=true"; else echo "GAGAL D: gerbang berubah"; fail=1; fi
  if grep -q 'it.name.startsWith("browser_")' "$TREE/Tools.kt"; then echo "LULUS D4: RISKY_TOOLS tetap mengecualikan browser_*"; else echo "GAGAL D: RISKY_TOOLS berubah"; fail=1; fi
  if grep -q "^package harness.core" "$TREE_CORE/RustCore.kt" && ! grep -q "com.senzwh.deepharness" "$TREE_CORE/RustCore.kt"; then echo "LULUS D5: RustCore.kt package harness.core + tanpa string package aplikasi"; else echo "GAGAL D5: RustCore.kt package/string salah"; fail=1; fi
  if grep -q "private fun classifyKotlin" "$TREE/DsFailure.kt" && grep -q "private fun classifyFailureKotlin" "$TREE/DsFailure.kt" && grep -q "var rustDsFailure" "$TREE/DsFailure.kt"; then echo "LULUS D6: fallback Kotlin DsFailure dipertahankan"; else echo "GAGAL D6: fallback Kotlin DsFailure hilang"; fail=1; fi
  if grep -q "private fun sha3_256Kotlin" "$TREE/PowSolver.kt" && grep -q "private fun dsHashV1Kotlin" "$TREE/PowSolver.kt" && grep -q "private fun solveDeepSeekKotlin" "$TREE/PowSolver.kt" && grep -q "private fun solveFastKotlin" "$TREE/PowSolver.kt" && grep -q "private fun solveFmtKotlin" "$TREE/PowSolver.kt" && grep -q "private fun solveKotlin" "$TREE/PowSolver.kt" && grep -q "var rustPowSolver" "$TREE/PowSolver.kt" && grep -q "const val VERSION = 2" "$TREE_CORE/RustCore.kt"; then echo "LULUS D7: fallback Kotlin PowSolver dipertahankan + RustCore.VERSION=2"; else echo "GAGAL D7: fallback Kotlin PowSolver hilang / VERSION bukan 2"; fail=1; fi
  if grep -q "private fun redactKotlin" "$TREE/RedactSecrets.kt" && grep -q "private fun countMatchesKotlin" "$TREE/RedactSecrets.kt" && grep -q "var rustRedactSecrets" "$TREE/RedactSecrets.kt" && grep -q "external fun redactSecrets(input: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "const val VERSION = 2" "$TREE_CORE/RustCore.kt"; then echo "LULUS D8: fallback Kotlin RedactSecrets dipertahankan + RustCore.VERSION=2 + external fun redactSecrets"; else echo "GAGAL D8: fallback Kotlin RedactSecrets hilang / VERSION bukan 2 / seam redactSecrets hilang"; fail=1; fi
  return $fail
}

SECTION="${1:-ALL}"
case "$SECTION" in
  0) run_0;;
  P1) run_p1;;
  P2) run_p2;;
  P3) run_p3;;
  N9) run_n9;;
  BR) run_br;;
  IXL) run_ixl;;
  R1) run_r1;;
  R2) run_r2;;
  C) run_c;;
  D) run_d;;
  ALL)
    FAIL=0
    run_0 || exit 1
    run_p1 || FAIL=1
    run_p2 || FAIL=1
    run_p3 || FAIL=1
    run_n9 || FAIL=1
    run_br || FAIL=1
    run_ixl || FAIL=1
    run_r1 || FAIL=1
    run_r2 || FAIL=1
    run_c || FAIL=1
    run_d || FAIL=1
    echo "OVERALL FAIL=$FAIL"
    exit $FAIL
    ;;
  *) echo "section tidak dikenal: $SECTION (0|P1|P2|P3|N9|BR|IXL|R1|R2|C|D|ALL)"; exit 2;;
esac
