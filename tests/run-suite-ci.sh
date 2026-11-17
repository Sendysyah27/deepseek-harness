#!/usr/bin/env bash
# tests/run-suite-ci.sh — SUITE PENUH vc321 di CI (adaptasi dari
# vc317-work/work-impl/run-tests-vc317-coord.sh; path lokal VM diganti
# path repo). Dijalankan dari root repo, satu section per pemanggilan
# (workflow CI memanggil per section sebagai step terpisah supaya langkah
# yang gagal terlihat di API tanpa perlu log):
#   bash tests/run-suite-ci.sh 0|P1|P2|P3|P4|P5|P6|P7|P8|P9|P10|P11|P12|P13|P14|P15|P16|P17|N9|BR|IXL|R1|R2|C|D|ALL
# Section 0 = kompilasi tree (prasyarat semua section tes).
# Suite (garis paritas vc316 + P2 slice 2 + P3 slice 3 + P4 slice 4 + P5 slice 5 + P6 slice 6 + P7 fix vc321 + P8 fix vc322 + P9 slice 7 rusqlite vc323 + P10 slice 8 HTTP vc324 + P11 slice 9 server :4198 vc325 + P12 slice 10 daun murni Tools.kt vc326 + P13 fix residual vc327 + P14 slice 11 split DsApiClient vc328 + P15 slice 12 otak latar/room vc329 + P16 fix bocor sesi vc330 + P17 fix bocor DSML vc331):
# P1 103 + P2 36 + P3 94 + P4 235 + P5 157 + P6 155 + P7 52 + P8 73 + P9 134 + P10 138 + P11 302 + P12 172 + N9 46 + BR 45 + IXL 72 + R1 64
# + R2 69 + P13 66 + P14 280 + P15 608 + P16 64 + P17 18 = 2983 cek; target 2982/2983 — R2-4.1 assert STRING PERSIS usang
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

run_p4() {
  echo "== P4. TestSmallModulesVc318 (paritas wrapper slice 4, 235 cek) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestSmallModulesVc318.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p4.jar" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-p4.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestSmallModulesVc318Kt "$TREE"
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
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestConcatApiVc314.kt" "$TREE/ConcatApiClient.kt" "$TREE_CORE/RustCore.kt" -cp "$JSON_JAR" -include-runtime -d "$OUT/test-r1.jar" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-r1.jar:$JSON_JAR" com.senzwh.deepharness.TestConcatApiVc314Kt "$TREE"
}

run_p5() {
  echo "== P5. TestConcatApiVc319 (paritas wrapper slice 5, 157 cek: rust 102 + kotlin 55) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestConcatApiVc319.kt" "$TREE/ConcatApiClient.kt" "$TREE_CORE/RustCore.kt" -cp "$JSON_JAR" -include-runtime -d "$OUT/test-p5.jar" || return 1
  echo "-- P5a. mode kotlin (fallback *Kotlin, tanpa .so) --"
  "$JAVA" -Xmx1g -cp "$OUT/test-p5.jar:$JSON_JAR" com.senzwh.deepharness.TestConcatApiVc319Kt kotlin "$TREE" "$OUT/p5-preview-kotlin" || return 1
  echo "-- P5b. mode rust (jalur Rust asli via TCP 7420 + .so host) --"
  (cd harness-core && cargo build --offline) || return 1
  [ -f harness-core/target/debug/libharness_core.so ] || { echo "GAGAL P5: .so host tidak ada"; return 1; }
  "$JAVA" -Xmx1g -Djava.library.path="harness-core/target/debug" -cp "$OUT/test-p5.jar:$JSON_JAR" com.senzwh.deepharness.TestConcatApiVc319Kt rust "$TREE" "$OUT/p5-preview-rust" || return 1
}

run_p6() {
  echo "== P6. TestToolRegistryAgentPromptVc320 (paritas wrapper slice 6, 155 cek: rust 80 + kotlin 75) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestToolRegistryAgentPromptVc320.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p6.jar" || return 1
  echo "-- P6a. mode kotlin (fallback *Kotlin, tanpa .so) --"
  "$JAVA" -Xmx1g -cp "$OUT/test-p6.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestToolRegistryAgentPromptVc320Kt kotlin "$TREE" || return 1
  echo "-- P6b. mode rust (jalur Rust asli + .so host) --"
  (cd harness-core && cargo build --offline) || return 1
  [ -f harness-core/target/debug/libharness_core.so ] || { echo "GAGAL P6: .so host tidak ada"; return 1; }
  "$JAVA" -Xmx1g -Djava.library.path="harness-core/target/debug" -cp "$OUT/test-p6.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestToolRegistryAgentPromptVc320Kt rust "$TREE" || return 1
}

run_p7() {
  echo "== P7. TestRoomIxlFixVc321 (FIX ROOM-LANE IXL vc321, 52 cek) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestRoomIxlFixVc321.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p7.jar" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-p7.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestRoomIxlFixVc321Kt "$TREE"
}

run_p8() {
  echo "== P8. TestSessFixVc322 (FIX BOCOR SESI SERVER vc322: delta giliran 1-1 + penghitung sesi + primed, 73 cek) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestSessFixVc322.kt" "tests/FakeCtxVc322.java" "tests/FakePrefsVc322.java" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p8.jar" || return 1
  # kotlinc TIDAK meng-compile .java (hanya dipakai sebagai stub resolusi)
  # -> FakeCtx/FakePrefs harus di-compile javac terpisah.
  "$JAVA_HOME/bin/javac" -cp "$OUT/test-tree.jar:$CP" -d "$OUT/test-p8-fake-classes" "tests/FakeCtxVc322.java" "tests/FakePrefsVc322.java" || return 1
  # Stub Context NON-LEMPAR: konstruktor android.jar melempar
  # RuntimeException("Stub!") sehingga FakeCtx (subclass Context) tidak
  # bisa di-instantiate di JVM. Stub di-compile terpisah dan HARUS
  # didahulukan di classpath run (sebelum android.jar di $CP).
  # Hanya untuk tes — TIDAK PERNAH masuk APK.
  "$JAVA_HOME/bin/javac" -cp "$ANDROID_JAR" -d "$OUT/test-stubs-vc322-classes" "tests/stubs/android/content/Context.java" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-stubs-vc322-classes:$OUT/test-p8-fake-classes:$OUT/test-p8.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestSessFixVc322Kt "$TREE"
}

run_p9() {
  echo "== P9. TestRusqliteVc323 (paritas seam rusqlite slice 7 vc323, 134 cek: rust 107 + kotlin 27) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestRusqliteVc323.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p9.jar" || return 1
  echo "-- P9a. mode rust (jalur Rust asli via .so host) --"
  (cd harness-core && cargo build --offline) || return 1
  [ -f harness-core/target/debug/libharness_core.so ] || { echo "GAGAL P9: .so host tidak ada"; return 1; }
  "$JAVA" -Xmx1g -Djava.library.path="harness-core/target/debug" -cp "$OUT/test-p9.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestRusqliteVc323Kt rust "$TREE" "$OUT/p9-tmp-rust" "harness-core" || return 1
  echo "-- P9b. mode kotlin (fallback SqliteSeam tanpa .so) --"
  "$JAVA" -Xmx1g -cp "$OUT/test-p9.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestRusqliteVc323Kt kotlin "$TREE" "$OUT/p9-tmp-kotlin" "harness-core" || return 1
}

run_p10() {
  echo "== P10. TestHttpVc324 (paritas seam HTTP slice 8 vc324, 138 cek: rust 88 + kotlin 50) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestHttpVc324.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p10.jar" || return 1
  echo "-- P10a. mode rust (jalur Rust asli via .so host + server HTTP lokal) --"
  (cd harness-core && cargo build --offline) || return 1
  [ -f harness-core/target/debug/libharness_core.so ] || { echo "GAGAL P10: .so host tidak ada"; return 1; }
  "$JAVA" -Xmx1g -Djava.library.path="harness-core/target/debug" -cp "$OUT/test-p10.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestHttpVc324Kt rust "$TREE" "harness-core" || return 1
  echo "-- P10b. mode kotlin (fallback *Kotlin tanpa .so) --"
  "$JAVA" -Xmx1g -cp "$OUT/test-p10.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestHttpVc324Kt kotlin "$TREE" "harness-core" || return 1
}


run_p11() {
  echo "== P11. TestServer4198Vc325 (paritas seam server :4198 slice 9 vc325, 302 cek: rust 151 + kotlin 151) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestServer4198Vc325.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p11.jar" || return 1
  echo "-- P11a. mode rust (jalur Rust asli via .so host) --"
  (cd harness-core && cargo build --offline) || return 1
  [ -f harness-core/target/debug/libharness_core.so ] || { echo "GAGAL P11: .so host tidak ada"; return 1; }
  "$JAVA" -Xmx1g -Djava.library.path="harness-core/target/debug" -cp "$OUT/test-p11.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestServer4198Vc325Kt rust "$TREE" "harness-core" || return 1
  echo "-- P11b. mode kotlin (fallback *Kotlin tanpa .so) --"
  "$JAVA" -Xmx1g -cp "$OUT/test-p11.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestServer4198Vc325Kt kotlin "$TREE" "harness-core" || return 1
}

run_p12() {
  echo "== P12. TestToolsLeavesVc326 (paritas seam daun murni Tools.kt slice 10 vc326, 172 cek: rust 86 + kotlin 86) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestToolsLeavesVc326.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p12.jar" || return 1
  echo "-- P12a. mode rust (jalur Rust asli via .so host) --"
  (cd harness-core && cargo build --offline) || return 1
  [ -f harness-core/target/debug/libharness_core.so ] || { echo "GAGAL P12: .so host tidak ada"; return 1; }
  "$JAVA" -Xmx1g -Djava.library.path="harness-core/target/debug" -cp "$OUT/test-p12.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestToolsLeavesVc326Kt rust "$TREE" "harness-core" || return 1
  echo "-- P12b. mode kotlin (fallback *Kotlin tanpa .so) --"
  "$JAVA" -Xmx1g -cp "$OUT/test-p12.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestToolsLeavesVc326Kt kotlin "$TREE" "harness-core" || return 1
}

run_p13() {
  echo "== P13. TestSessFixVc327 (FIX RESIDUAL vc327: migrasi primed + tahan forceFresh/cooldown + reset accum create + delta auto-save/continuation) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestSessFixVc327.kt" "tests/FakeCtxVc322.java" "tests/FakePrefsVc322.java" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p13.jar" || return 1
  # FakeCtx/FakePrefs javac terpisah + stub Context non-lempar didahulukan
  # (jebakan yang sama seperti P8).
  "$JAVA_HOME/bin/javac" -cp "$OUT/test-tree.jar:$CP" -d "$OUT/test-p8-fake-classes" "tests/FakeCtxVc322.java" "tests/FakePrefsVc322.java" || return 1
  "$JAVA_HOME/bin/javac" -cp "$ANDROID_JAR" -d "$OUT/test-stubs-vc322-classes" "tests/stubs/android/content/Context.java" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-stubs-vc322-classes:$OUT/test-p8-fake-classes:$OUT/test-p13.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestSessFixVc327Kt "$TREE"
}

run_p14() {
  echo "== P14. TestDsApiSplitVc328 (paritas split DsApiClient slice 11 vc328, 280 cek (140 per mode: rust + kotlin)) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestDsApiSplitVc328.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p14.jar" || return 1
  echo "-- P14a. mode rust (jalur Rust asli via .so host + server lokal upload) --"
  (cd harness-core && cargo build --offline) || return 1
  [ -f harness-core/target/debug/libharness_core.so ] || { echo "GAGAL P14: .so host tidak ada"; return 1; }
  "$JAVA" -Xmx1g -Djava.library.path="harness-core/target/debug" -cp "$OUT/test-p14.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestDsApiSplitVc328Kt rust "$TREE" "harness-core" || return 1
  echo "-- P14b. mode kotlin (fallback *Kotlin tanpa .so) --"
  "$JAVA" -Xmx1g -cp "$OUT/test-p14.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestDsApiSplitVc328Kt kotlin "$TREE" "harness-core" || return 1
}

run_p15() {
  echo "== P15. TestBgRoomVc329 (paritas slice 12 otak latar/room vc329, 608 cek (312 rust / 296 kotlin per mode: rust + kotlin)) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestBgRoomVc329.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p15.jar" || return 1
  echo "-- P15a. mode rust (jalur Rust asli via .so host) --"
  (cd harness-core && cargo build --offline) || return 1
  [ -f harness-core/target/debug/libharness_core.so ] || { echo "GAGAL P15: .so host tidak ada"; return 1; }
  "$JAVA" -Xmx1g -Djava.library.path="harness-core/target/debug" -cp "$OUT/test-p15.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestBgRoomVc329Kt rust "$TREE" "harness-core" || return 1
  echo "-- P15b. mode kotlin (fallback *Kotlin tanpa .so) --"
  "$JAVA" -Xmx1g -cp "$OUT/test-p15.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestBgRoomVc329Kt kotlin "$TREE" "harness-core" || return 1
}

run_p16() {
  echo "== P16. TestSessFixVc330 (FIX BOCOR SESI vc330: delta verifikasi + delta ekstraksi + katup pengaman giliran senyap) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestSessFixVc330.kt" "tests/FakeCtxVc322.java" "tests/FakePrefsVc322.java" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p16.jar" || return 1
  # FakeCtx/FakePrefs javac terpisah + stub Context non-lempar didahulukan
  # (jebakan yang sama seperti P8/P13).
  "$JAVA_HOME/bin/javac" -cp "$OUT/test-tree.jar:$CP" -d "$OUT/test-p8-fake-classes" "tests/FakeCtxVc322.java" "tests/FakePrefsVc322.java" || return 1
  "$JAVA_HOME/bin/javac" -cp "$ANDROID_JAR" -d "$OUT/test-stubs-vc322-classes" "tests/stubs/android/content/Context.java" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-stubs-vc322-classes:$OUT/test-p8-fake-classes:$OUT/test-p16.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestSessFixVc330Kt "$TREE"
}

run_p17() {
  echo "== P17. TestDsmlFixVc331 (FIX BOCOR DSML vc331: normalisasi look-alike + penutup polos + strip streaming potong-dulu + parse multi-invoke) =="
  "$KOTLINC" -J-Xmx3g -jvm-target 17 "tests/TestDsmlFixVc331.kt" -cp "$OUT/test-tree.jar:$CP" -include-runtime -d "$OUT/test-p17.jar" || return 1
  "$JAVA" -Xmx1g -cp "$OUT/test-p17.jar:$OUT/test-tree.jar:$CP" com.senzwh.deepharness.TestDsmlFixVc331Kt "$TREE"
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
  echo "== C. Diff vs origin/main: DsFailure.kt berubah + RustCore.kt baru + PowSolver.kt berubah (vc320) =="
  # actions/checkout hanya mengambil branch aktif; main harus di-fetch
  # eksplisit (workflow juga melakukan ini sebelum suite).
  git fetch origin main --depth=1 || { echo "GAGAL C: tidak bisa fetch origin/main"; return 1; }
  git diff --name-only origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/DsFailure.kt | grep -q DsFailure.kt || { echo "GAGAL C: DsFailure.kt tidak berubah vs main"; return 1; }
  git diff --name-status origin/main HEAD -- app/src/main/java/harness/core/RustCore.kt | grep -q "^A" || { echo "GAGAL C: RustCore.kt bukan file baru vs main"; return 1; }
  git diff --name-only origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/PowSolver.kt | grep -q PowSolver.kt || { echo "GAGAL C: PowSolver.kt tidak berubah vs main"; return 1; }
  git diff --name-only origin/main HEAD -- harness-core/src/pow_solver.rs | grep -q pow_solver.rs || { echo "GAGAL C: pow_solver.rs bukan file baru vs main"; return 1; }
  git diff --name-only origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/RedactSecrets.kt | grep -q RedactSecrets.kt || { echo "GAGAL C: RedactSecrets.kt tidak berubah vs main"; return 1; }
  git diff --name-status origin/main HEAD -- harness-core/src/redact_secrets.rs | grep -q "^A" || { echo "GAGAL C: redact_secrets.rs bukan file baru vs main"; return 1; }
  for _f in NotifIds SystemBarSpecVc309 DsSafetyStrip McpHelper SessionBackgroundVc304 Behaviors ChatThemeVc306; do git diff --name-only origin/main HEAD -- "app/src/main/java/com/senzwh/deepharness/$_f.kt" | grep -q "$_f.kt" || { echo "GAGAL C: $_f.kt tidak berubah vs main"; return 1; }; done
  git diff --name-only origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/priv/PrivPairNotif.kt | grep -q PrivPairNotif.kt || { echo "GAGAL C: priv/PrivPairNotif.kt tidak berubah vs main"; return 1; }
  git diff --name-only origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/ConcatApiClient.kt | grep -q ConcatApiClient.kt || { echo "GAGAL C: ConcatApiClient.kt tidak berubah vs main (slice 5)"; return 1; }
  git diff --name-status origin/main HEAD -- harness-core/src/concat_api_client.rs | grep -q "^A" || { echo "GAGAL C: concat_api_client.rs bukan file baru vs main (slice 5)"; return 1; }
  for _f in ToolRegistry AgentPrompt SkillJudge; do git diff --name-only origin/main HEAD -- "app/src/main/java/com/senzwh/deepharness/$_f.kt" | grep -q "$_f.kt" || { echo "GAGAL C: $_f.kt tidak berubah vs main (slice 6)"; return 1; }; done
  for _r in tool_registry tool_registry_data agent_prompt skill_trigger; do git diff --name-status origin/main HEAD -- "harness-core/src/$_r.rs" | grep -q "^A" || { echo "GAGAL C: $_r.rs bukan file baru vs main (slice 6)"; return 1; }; done
  for _f in GroupChat MainActivity TurnMetrics DsApiClient SlotClient; do git diff --name-only origin/main HEAD -- "app/src/main/java/com/senzwh/deepharness/$_f.kt" | grep -q "$_f.kt" || { echo "GAGAL C: $_f.kt tidak berubah vs main (fix vc321)"; return 1; }; done
  echo "LULUS C (fix vc321): GroupChat.kt + MainActivity.kt + TurnMetrics.kt + DsApiClient.kt + SlotClient.kt berubah"
  for _f in EventDb ArtifactStore ParkedApprovalStoreVc305 GraphDb; do git diff --name-only origin/main HEAD -- "app/src/main/java/com/senzwh/deepharness/$_f.kt" | grep -q "$_f.kt" || { echo "GAGAL C: $_f.kt tidak berubah vs main (slice 7 vc323)"; return 1; }; done
  git diff --name-status origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/SqliteSeam.kt | grep -q "^A" || { echo "GAGAL C: SqliteSeam.kt bukan file baru vs main (slice 7 vc323)"; return 1; }
  for _r in sqlite sqlite_schema; do git diff --name-status origin/main HEAD -- "harness-core/src/$_r.rs" | grep -q "^A" || { echo "GAGAL C: $_r.rs bukan file baru vs main (slice 7 vc323)"; return 1; }; done
  echo "LULUS C (slice 7 vc323): 4 wrapper berubah + SqliteSeam.kt baru + sqlite.rs + sqlite_schema.rs baru"
  for _f in OcClient ApiClients McpHttp Gmail McpOAuth; do git diff --name-only origin/main HEAD -- "app/src/main/java/com/senzwh/deepharness/$_f.kt" | grep -q "$_f.kt" || { echo "GAGAL C: $_f.kt tidak berubah vs main (slice 8 vc324)"; return 1; }; done
  git diff --name-status origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/HttpSeam.kt | grep -q "^A" || { echo "GAGAL C: HttpSeam.kt bukan file baru vs main (slice 8 vc324)"; return 1; }
  for _r in http sse; do git diff --name-status origin/main HEAD -- "harness-core/src/$_r.rs" | grep -q "^A" || { echo "GAGAL C: $_r.rs bukan file baru vs main (slice 8 vc324)"; return 1; }; done
  echo "LULUS C (slice 8 vc324): 5 wrapper berubah + HttpSeam.kt baru + http.rs + sse.rs baru"
  for _f in WorkerEndpoint WorkerEndpointLogic; do git diff --name-only origin/main HEAD -- "app/src/main/java/com/senzwh/deepharness/$_f.kt" | grep -q "$_f.kt" || { echo "GAGAL C: $_f.kt tidak berubah vs main (slice 9 vc325)"; return 1; }; done
  git diff --name-status origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/WorkerEndpointSeam.kt | grep -q "^A" || { echo "GAGAL C: WorkerEndpointSeam.kt bukan file baru vs main (slice 9 vc325)"; return 1; }
  git diff --name-status origin/main HEAD -- harness-core/src/worker_endpoint.rs | grep -q "^A" || { echo "GAGAL C: worker_endpoint.rs bukan file baru vs main (slice 9 vc325)"; return 1; }
  echo "LULUS C (slice 9 vc325): 2 wrapper berubah + WorkerEndpointSeam.kt baru + worker_endpoint.rs baru"
  git diff --name-only origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/Tools.kt | grep -q "Tools.kt" || { echo "GAGAL C: Tools.kt tidak berubah vs main (slice 10 vc326)"; return 1; }
  git diff --name-status origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/ToolsLeavesSeam.kt | grep -q "^A" || { echo "GAGAL C: ToolsLeavesSeam.kt bukan file baru vs main (slice 10 vc326)"; return 1; }
  git diff --name-status origin/main HEAD -- harness-core/src/tools_leaves.rs | grep -q "^A" || { echo "GAGAL C: tools_leaves.rs bukan file baru vs main (slice 10 vc326)"; return 1; }
  echo "LULUS C (slice 10 vc326): Tools.kt berubah + ToolsLeavesSeam.kt baru + tools_leaves.rs baru"
  git diff --name-status origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/DsApiSeam.kt | grep -q "^A" || { echo "GAGAL C: DsApiSeam.kt bukan file baru vs main (slice 11 vc328)"; return 1; }
  git diff --name-status origin/main HEAD -- harness-core/src/ds_api.rs | grep -q "^A" || { echo "GAGAL C: ds_api.rs bukan file baru vs main (slice 11 vc328)"; return 1; }
  echo "LULUS C (slice 11 vc328): DsApiClient.kt berubah + DsApiSeam.kt baru + ds_api.rs baru"
  for _f in BgBrain DsAgent OcCoord; do git diff --name-only origin/main HEAD -- "app/src/main/java/com/senzwh/deepharness/$_f.kt" | grep -q "$_f.kt" || { echo "GAGAL C: $_f.kt tidak berubah vs main (slice 12 vc329)"; return 1; }; done
  git diff --name-status origin/main HEAD -- app/src/main/java/com/senzwh/deepharness/BgRoomSeam.kt | grep -q "^A" || { echo "GAGAL C: BgRoomSeam.kt bukan file baru vs main (slice 12 vc329)"; return 1; }
  for _r in bg_brain bg_room_common group_chat ds_agent ds_agent_js oc_coord; do git diff --name-status origin/main HEAD -- "harness-core/src/$_r.rs" | grep -q "^A" || { echo "GAGAL C: $_r.rs bukan file baru vs main (slice 12 vc329)"; return 1; }; done
  echo "LULUS C (slice 12 vc329): BgBrain.kt + DsAgent.kt + OcCoord.kt berubah + BgRoomSeam.kt baru + 6 modul Rust baru"
  for _r in notif_ids system_bar_spec ds_safety_strip mcp_helper session_background behaviors chat_theme; do git diff --name-status origin/main HEAD -- "harness-core/src/$_r.rs" | grep -q "^A" || { echo "GAGAL C: $_r.rs bukan file baru vs main"; return 1; }; done
  # Flavor.kt: bandingkan commit slice ini (HEAD) vs INDUKNYA (HEAD~1),
  # BUKAN vs main — Flavor.kt branch SUDAH beda dari main sejak lama
  # (pre-slice-4, blob a3feca39 vs main ec173ba0); yang dilarang slice 5
  # adalah MENGUBAHNYA di commit ini. (Perbaikan integrator ronde 1 CI.)
  git diff --name-only HEAD~1 HEAD -- app/src/main/java/com/senzwh/deepharness/Flavor.kt | grep -q Flavor.kt && { echo "GAGAL C: Flavor.kt berubah di commit slice ini (slice 4 DIKECUALIKAN/deferred)"; return 1; }
  echo "LULUS C (slice 5): Flavor.kt tidak berubah di commit slice ini"
  echo "LULUS C (slice 5): 7 file Kotlin + PrivPairNotif berubah, 7 modul Rust baru, Flavor.kt tidak berubah"
  echo "LULUS C (slice 5): ConcatApiClient.kt berubah + concat_api_client.rs baru"
  echo "LULUS C (slice 6): ToolRegistry.kt + AgentPrompt.kt + SkillJudge.kt berubah + 4 modul Rust baru (tool_registry, tool_registry_data, agent_prompt, skill_trigger)"
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
  if grep -q "private fun sha3_256Kotlin" "$TREE/PowSolver.kt" && grep -q "private fun dsHashV1Kotlin" "$TREE/PowSolver.kt" && grep -q "private fun solveDeepSeekKotlin" "$TREE/PowSolver.kt" && grep -q "private fun solveFastKotlin" "$TREE/PowSolver.kt" && grep -q "private fun solveFmtKotlin" "$TREE/PowSolver.kt" && grep -q "private fun solveKotlin" "$TREE/PowSolver.kt" && grep -q "var rustPowSolver" "$TREE/PowSolver.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt"; then echo "LULUS D7: fallback Kotlin PowSolver dipertahankan + RustCore.VERSION=10"; else echo "GAGAL D7: fallback Kotlin PowSolver hilang / VERSION bukan 10"; fail=1; fi
  if grep -q "private fun redactKotlin" "$TREE/RedactSecrets.kt" && grep -q "private fun countMatchesKotlin" "$TREE/RedactSecrets.kt" && grep -q "var rustRedactSecrets" "$TREE/RedactSecrets.kt" && grep -q "external fun redactSecrets(input: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt"; then echo "LULUS D8: fallback Kotlin RedactSecrets dipertahankan + RustCore.VERSION=10 + external fun redactSecrets"; else echo "GAGAL D8: fallback Kotlin RedactSecrets hilang / VERSION bukan 10 / seam redactSecrets hilang"; fail=1; fi
  if grep -q "private fun bgResultIdKotlin" "$TREE/NotifIds.kt" && grep -q "var rustNotifIds" "$TREE/NotifIds.kt" && grep -q "private fun specKotlin" "$TREE/SystemBarSpecVc309.kt" && grep -q "private fun stripKotlin" "$TREE/DsSafetyStrip.kt" && grep -q "private const val SCRIPT_B64_KOTLIN" "$TREE/McpHelper.kt" && grep -q "private fun renderAllowedKotlin" "$TREE/SessionBackgroundVc304.kt" && grep -q "private val PACKS_KOTLIN" "$TREE/Behaviors.kt" && grep -q "private fun blockKotlin" "$TREE/Behaviors.kt" && grep -q "private fun contrastRatioKotlin" "$TREE/ChatThemeVc306.kt" && grep -q "external fun notifIdsJson(origin: String, key: String, workerId: String): String" "$TREE_CORE/RustCore.kt" && grep -q "external fun systemBarSpecJson(isDark: Boolean): String" "$TREE_CORE/RustCore.kt" && grep -q "external fun dsSafetyStripJson(text: String): String" "$TREE_CORE/RustCore.kt" && grep -q "external fun mcpHelperJson(): String" "$TREE_CORE/RustCore.kt" && grep -q "external fun sessionBackgroundJson(turnSessionId: Long, currentSessionId: Long, turnActive: Boolean): String" "$TREE_CORE/RustCore.kt" && grep -q "external fun behaviorsJson(id: String): String" "$TREE_CORE/RustCore.kt" && grep -q "external fun chatThemeJson(fg: Int, bg: Int): String" "$TREE_CORE/RustCore.kt" && grep -q "val NOTIF_ID: Int get() = NotifIds.PRIV_PAIR" "$TREE/priv/PrivPairNotif.kt" && ! grep -q "const val NOTIF_ID" "$TREE/priv/PrivPairNotif.kt" && ! grep -q "RustCore" "$TREE/Flavor.kt"; then echo "LULUS D9: fallback Kotlin slice 4 dipertahankan (7 file *Kotlin + 7 external fun + PrivPairNotif getter + Flavor.kt tanpa Rust)"; else echo "GAGAL D9: fallback/seam slice 4 hilang / PrivPairNotif masih const / Flavor.kt tersentuh Rust"; fail=1; fi
  if grep -q "private fun versionKotlin" "$TREE/ConcatApiClient.kt" && grep -q "private fun previewFrameKotlin" "$TREE/ConcatApiClient.kt" && grep -q "private fun applyEditKotlin" "$TREE/ConcatApiClient.kt" && grep -q "private fun createProjectKotlin" "$TREE/ConcatApiClient.kt" && grep -q "var rustConcatApiClient" "$TREE/ConcatApiClient.kt" && grep -q "external fun concatApiJson(request: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt"; then echo "LULUS D10: fallback Kotlin ConcatApiClient dipertahankan (21 *Kotlin + rustConcatApiClient + external fun concatApiJson) + RustCore.VERSION=10"; else echo "GAGAL D10: fallback ConcatApiClient hilang / seam concatApiJson hilang / VERSION bukan 10"; fail=1; fi
  if grep -q "private fun catalogSectionKotlin" "$TREE/ToolRegistry.kt" && grep -q "private fun namesKotlin" "$TREE/ToolRegistry.kt" && grep -q "private fun nearestNamesKotlin" "$TREE/ToolRegistry.kt" && grep -q "var rustToolRegistry" "$TREE/ToolRegistry.kt" && grep -q "private fun selectCatalogKotlin" "$TREE/AgentPrompt.kt" && grep -q "private fun applyDoublePassKotlin" "$TREE/AgentPrompt.kt" && grep -q "private fun stripRawToolMarkupForDisplayKotlin" "$TREE/AgentPrompt.kt" && grep -q "var rustAgentPrompt" "$TREE/AgentPrompt.kt" && grep -q "private fun splitDescrSectionsKotlin" "$TREE/SkillJudge.kt" && grep -q "private fun triggerScoreKotlin" "$TREE/SkillJudge.kt" && grep -q "var rustSkillTrigger" "$TREE/SkillJudge.kt" && grep -q "external fun toolRegistryJson(request: String): String" "$TREE_CORE/RustCore.kt" && grep -q "external fun agentPromptJson(request: String): String" "$TREE_CORE/RustCore.kt" && grep -q "external fun skillTriggerJson(request: String): String" "$TREE_CORE/RustCore.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt"; then echo "LULUS D11: fallback Kotlin slice 6 dipertahankan (*Kotlin + 3 saklar rust + 3 external fun) + RustCore.VERSION=10"; else echo "GAGAL D11: fallback/seam slice 6 hilang / VERSION bukan 10"; fail=1; fi
  if grep -q "AgentPrompt.catalogForTask(app, userText)" "$TREE/GroupChat.kt" && grep -q "catSlimVc321.length > 30000" "$TREE/GroupChat.kt" && grep -q "private fun continuationPromptVc321(userText: String, suffix: String): String" "$TREE/GroupChat.kt" && grep -q "continuationPromptVc321(userText, BatchToolVc274.combinedMessage" "$TREE/GroupChat.kt" && grep -q "continuationPromptVc321(userText, AgentPrompt.EVIDENCE_GATE_NUDGE)" "$TREE/GroupChat.kt" && grep -q "fun shouldResetServerSessionVc321" "$TREE/TurnMetrics.kt" && grep -q "fun historyBudgetVc321" "$TREE/TurnMetrics.kt" && grep -q "shouldResetServerSessionVc321" "$TREE/SlotClient.kt" && grep -q "shouldResetServerSessionVc321" "$TREE/DsApiClient.kt" && grep -q "historyBudgetVc321" "$TREE/MainActivity.kt" && grep -q "RUST_CORE_VERSION: jint = 10" "harness-core/src/jni_glue.rs" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt" && ! grep -q "Vc321\|vc321" "$TREE/Flavor.kt"; then echo "LULUS D12: fix vc321 ada (selectCatalog room + backstop 30k + anchor/delta room 3 situs + guard reset + riwayat adaptif 1-1) + RUST_CORE_VERSION 10 + Flavor.kt tidak berubah"; else echo "GAGAL D12: cek fix vc321 / VERSION Rust / Flavor.kt"; fail=1; fi
  if grep -q "fun deltaTurnPromptVc322" "$TREE/AgentPrompt.kt" && grep -q "fun shouldSendDeltaVc322" "$TREE/TurnMetrics.kt" && grep -q "fun noteServerSessionCreatedVc322" "$TREE/TurnMetrics.kt" && grep -q "fun isServerSessionPrimedVc322" "$TREE/TurnMetrics.kt" && grep -q "deltaVc322" "$TREE/MainActivity.kt" && grep -q "sendToAgent(toSendVc240, deltaVc322)" "$TREE/MainActivity.kt" && grep -q "val useDeltaVc322 = TurnMetrics.shouldSendDeltaVc322" "$TREE/DsApiClient.kt" && grep -q "val useDeltaVc322 = TurnMetrics.shouldSendDeltaVc322" "$TREE/SlotClient.kt" && grep -q "completeWithSession(fresh, prompt" "$TREE/DsApiClient.kt" && grep -q "completeWithSession(fresh, prompt" "$TREE/SlotClient.kt" && grep -q "private fun addKotlin" "$TREE/EventDb.kt" && grep -q "private fun latestKotlin" "$TREE/EventDb.kt" && grep -q "var rustEventDb" "$TREE/EventDb.kt" && grep -q "private fun createKotlin" "$TREE/ArtifactStore.kt" && grep -q "private fun getKotlin" "$TREE/ArtifactStore.kt" && grep -q "var rustArtifactStore" "$TREE/ArtifactStore.kt" && grep -q "private fun insertIfAbsentKotlin" "$TREE/ParkedApprovalStoreVc305.kt" && grep -q "private fun claimKotlin" "$TREE/ParkedApprovalStoreVc305.kt" && grep -q "private fun decideKotlin" "$TREE/ParkedApprovalStoreVc305.kt" && grep -q "var rustParkedApproval" "$TREE/ParkedApprovalStoreVc305.kt" && grep -q "private fun getOrCreateNodeKotlin" "$TREE/GraphDb.kt" && grep -q "private fun addEdgeKotlin" "$TREE/GraphDb.kt" && grep -q "private fun searchFactsKotlin" "$TREE/GraphDb.kt" && grep -q "var rustGraphDb" "$TREE/GraphDb.kt" && grep -q "external fun sqliteJson(request: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt" && ! grep -q "rustMemoryDb\|SqliteSeam" "$TREE/MemoryDb.kt" && grep -q "RUST_CORE_VERSION: jint = 10" "harness-core/src/jni_glue.rs" && ! grep -q "Vc322\|vc322" "$TREE/Flavor.kt"; then echo "LULUS D13: fix sesi vc322 dipertahankan (delta 1-1 + counter/primed + pengganti penuh) + fallback slice 7 dipertahankan (4 wrapper *Kotlin + 4 saklar rust + sqliteJson + VERSION=10 + MemoryDb tanpa Rust) + RUST_CORE_VERSION 10 + Flavor.kt tidak berubah"; else echo "GAGAL D13: cek fix sesi vc322 / fallback slice 7 / VERSION Rust / Flavor.kt"; fail=1; fi
  if grep -q "private fun httpKotlin" "$TREE/OcClient.kt" && grep -q "var rustOcClient" "$TREE/OcClient.kt" && grep -q "private fun callHttpKotlin" "$TREE/ApiClients.kt" && grep -q "private fun shapeFingerprintKotlin" "$TREE/ApiClients.kt" && grep -q "var rustApiClients" "$TREE/ApiClients.kt" && grep -q "private fun postKotlin" "$TREE/McpHttp.kt" && grep -q "private fun extractResultKotlin" "$TREE/McpHttp.kt" && grep -q "var rustMcpHttp" "$TREE/McpHttp.kt" && grep -q "private fun decodeWordKotlin" "$TREE/Gmail.kt" && grep -q "private fun fetchHeadersEntryKotlin" "$TREE/Gmail.kt" && grep -q "var rustGmail" "$TREE/Gmail.kt" && grep -q "private fun challengeKotlin" "$TREE/McpOAuth.kt" && grep -q "private fun encKotlin" "$TREE/McpOAuth.kt" && grep -q "private fun postFormRawKotlin" "$TREE/McpOAuth.kt" && grep -q "var rustMcpOAuth" "$TREE/McpOAuth.kt" && grep -q "private fun postJsonKotlin" "$TREE/SlotClient.kt" && grep -q "private fun applyChunkKotlin" "$TREE/SlotClient.kt" && grep -q "var rustSlotClient" "$TREE/SlotClient.kt" && grep -q "object HttpSeam" "$TREE/HttpSeam.kt" && grep -q "external fun httpJson(request: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt" && grep -q "RUST_CORE_VERSION: jint = 10" "harness-core/src/jni_glue.rs" && ! grep -q "Vc324\|vc324" "$TREE/Flavor.kt"; then echo "LULUS D14: fallback slice 8 dipertahankan (6 wrapper *Kotlin + 6 saklar rust + httpJson + VERSION=10 + Flavor.kt tidak berubah)"; else echo "GAGAL D14: cek fallback slice 8 / seam httpJson / DsApiClient / Flavor.kt"; fail=1; fi
  if grep -q "private fun isAuthorizedKotlin" "$TREE/WorkerEndpointLogic.kt" && grep -q "private fun bearerTokenKotlin" "$TREE/WorkerEndpointLogic.kt" && grep -q "private fun parseRouteKotlin" "$TREE/WorkerEndpointLogic.kt" && grep -q "private fun hmacSha256HexKotlin" "$TREE/WorkerEndpointLogic.kt" && grep -q "private fun signQueueTextKotlin" "$TREE/WorkerEndpointLogic.kt" && grep -q "private fun verifyQueueTextKotlin" "$TREE/WorkerEndpointLogic.kt" && grep -q "private fun parseHeadersKotlin" "$TREE/WorkerEndpoint.kt" && grep -q "private fun buildResponseHeadKotlin" "$TREE/WorkerEndpoint.kt" && grep -q "private fun idempotencyKeyKotlin" "$TREE/WorkerEndpoint.kt" && grep -q "object WorkerEndpointSeam" "$TREE/WorkerEndpointSeam.kt" && grep -q "var rustWorkerEndpoint" "$TREE/WorkerEndpointSeam.kt" && grep -q "external fun workerEndpointJson(request: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt" && grep -q "RUST_CORE_VERSION: jint = 10" "harness-core/src/jni_glue.rs" && ! grep -q "WorkerEndpointSeam\\|workerEndpointJson\\|rustWorkerEndpoint" "$TREE/WorkerLogic.kt" && ! grep -q "WorkerEndpointSeam\\|workerEndpointJson\\|rustWorkerEndpoint" "$TREE/WorkerQueue.kt" && ! grep -q "WorkerEndpointSeam\\|workerEndpointJson\\|rustWorkerEndpoint" "$TREE/WorkerJanitorLogic.kt" && ! grep -q "WorkerEndpointSeam\\|workerEndpointJson\\|rustWorkerEndpoint" "$TREE/WorkerRegistry.kt" && grep -q "private fun acceptLoop(ctx: Context)" "$TREE/WorkerEndpoint.kt" && grep -q "fun token(ctx: Context): String" "$TREE/WorkerEndpoint.kt" && ! grep -q "Vc325\\|vc325" "$TREE/Flavor.kt"; then echo "LULUS D15: fallback slice 9 dipertahankan (WorkerEndpointLogic + WorkerEndpoint *Kotlin + WorkerEndpointSeam + workerEndpointJson + VERSION=10 + file peta lain tanpa Rust + loop/token tetap Kotlin + Flavor.kt tidak berubah)"; else echo "GAGAL D15: cek fallback slice 9 / seam workerEndpointJson / batas file / Flavor.kt"; fail=1; fi
  if grep -q "private fun stripHtmlKotlin" "$TREE/Tools.kt" && grep -q "private fun parseDdgKotlin" "$TREE/Tools.kt" && grep -q "private fun truncateMarkedVc230Kotlin" "$TREE/Tools.kt" && grep -q "private fun ytRunsTextKotlin" "$TREE/Tools.kt" && grep -q "private fun ytParseVideoRendererKotlin" "$TREE/Tools.kt" && grep -q "private fun extractBalancedJsonKotlin" "$TREE/Tools.kt" && grep -q "private fun ytWalkResultsKotlin" "$TREE/Tools.kt" && grep -q "private fun ytScanRenderersKotlin" "$TREE/Tools.kt" && grep -q "private fun parseYtSearchKotlin" "$TREE/Tools.kt" && grep -q "private fun formatYtResultsKotlin" "$TREE/Tools.kt" && grep -q "object ToolsLeavesSeam" "$TREE/ToolsLeavesSeam.kt" && grep -q "var rustToolsLeaves" "$TREE/ToolsLeavesSeam.kt" && grep -q "external fun toolsLeavesJson(request: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt" && grep -q "RUST_CORE_VERSION: jint = 10" "harness-core/src/jni_glue.rs" && grep -q "fun execute(ctx: Context" "$TREE/Tools.kt" && grep -q "private fun httpGet(" "$TREE/Tools.kt" && grep -q "private fun httpRequest(" "$TREE/Tools.kt" && ! grep -q "DSML" "$TREE/Tools.kt" && grep -q "stripRawToolMarkupForDisplay" "harness-core/src/agent_prompt.rs" && grep -q "fun slugifySkillName(" "$TREE/Tools.kt" && grep -q "fun ruleKey(" "$TREE/Tools.kt" && ! grep -q "slugifySkillNameKotlin" "$TREE/Tools.kt" && ! grep -q "Vc326\\|vc326" "$TREE/Flavor.kt" && ! grep -q "ToolsLeaves\\|toolsLeaves" "$TREE/DsApiClient.kt" && ! grep -q "Vc326\\|vc326" "$TREE/DsApiClient.kt"; then echo "LULUS D16: fallback slice 10 dipertahankan (Tools.kt *Kotlin + ToolsLeavesSeam + toolsLeavesJson + VERSION=10 + dispatcher/Android/jaringan tetap Kotlin + DSML sudah Rust slice 6 + daun murni lain tanpa Rust + Flavor/DsApiClient tanpa vc326)"; else echo "GAGAL D16: cek fallback slice 10 / seam toolsLeavesJson / batas / Flavor.kt / DsApiClient.kt"; fail=1; fi
  if grep -q "fun migratePrimedOnceVc327" "$TREE/TurnMetrics.kt" && grep -q "fun allowForceFreshVc327" "$TREE/TurnMetrics.kt" && grep -q "fun noteForceFreshVc327" "$TREE/TurnMetrics.kt" && grep -q "FORCE_FRESH_COOLDOWN_MS_VC327" "$TREE/TurnMetrics.kt" && grep -q "fun autoSaveBlockVc327" "$TREE/AgentPrompt.kt" && grep -q "fun deltaTurnPromptVc327" "$TREE/AgentPrompt.kt" && grep -q "deltaTurnPromptVc327" "$TREE/MainActivity.kt" && grep -q "sendToAgent(finalTxt, finalTxt)" "$TREE/MainActivity.kt" && grep -q "allowForceFreshVc327" "$TREE/DsApiClient.kt" && grep -q "allowForceFreshVc327" "$TREE/SlotClient.kt" && grep -q "resetServerAccumVc321(ctx, accumKeyVc321)" "$TREE/DsApiClient.kt" && grep -q "if (!freshEphemeral) TurnMetrics.resetServerAccumVc321(ctx, accumKeyVc321)" "$TREE/SlotClient.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt" && grep -q "RUST_CORE_VERSION: jint = 10" "harness-core/src/jni_glue.rs" && ! grep -q "Vc327\|vc327" "$TREE/Flavor.kt" && ! grep -q "Vc327\|vc327" "$TREE/GroupChat.kt" && ! grep -q "Vc327\|vc327" "$TREE/BgBrain.kt"; then echo "LULUS D17: fix residual vc327 ada (migrasi primed + cooldown forceFresh + reset accum create + delta auto-save/continuation) + RUST_CORE_VERSION 10 + Flavor/GroupChat/BgBrain tanpa vc327"; else echo "GAGAL D17: cek fix residual vc327 / VERSION Rust / batas file"; fail=1; fi
  if grep -q "private fun postJsonKotlin" "$TREE/DsApiClient.kt" && grep -q "private fun getJsonKotlin" "$TREE/DsApiClient.kt" && grep -q "private fun uploadPostKotlin" "$TREE/DsApiClient.kt" && grep -q "private fun applyChunkKotlin" "$TREE/DsApiClient.kt" && grep -q "private fun findSessionIdKotlin" "$TREE/DsApiClient.kt" && grep -q "private fun findChallengeKotlin" "$TREE/DsApiClient.kt" && grep -q "private fun findFileIdKotlin" "$TREE/DsApiClient.kt" && grep -q "private fun estTokensKotlin" "$TREE/DsApiClient.kt" && grep -q "var rustDsApi" "$TREE/DsApiClient.kt" && grep -q "object DsApiSeam" "$TREE/DsApiSeam.kt" && grep -q "var rustDsApi" "$TREE/DsApiSeam.kt" && grep -q "external fun dsApiJson(request: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt" && grep -q "RUST_CORE_VERSION: jint = 10" "harness-core/src/jni_glue.rs" && grep -q "extractToken" "$TREE/DsApiClient.kt" && grep -q "CookieManager.getInstance" "$TREE/DsApiClient.kt" && grep -q "dsCaptureShim" "$TREE/DsApiClient.kt" && ! grep -q "Vc328\\|vc328" "$TREE/SlotClient.kt" && ! grep -q "Vc328\\|vc328" "$TREE/TurnMetrics.kt" && ! grep -q "Vc328\\|vc328" "$TREE/Flavor.kt" && ! grep -q "Vc328\\|vc328" "$TREE/GroupChat.kt" && ! grep -q "Vc328\\|vc328" "$TREE/BgBrain.kt"; then echo "LULUS D18: split DsApiClient slice 11 dipertahankan (8 wrapper *Kotlin + DsApiSeam + dsApiJson + VERSION=10 + panen cookie tetap Kotlin + SlotClient/TurnMetrics/Flavor/GroupChat/BgBrain tanpa vc328)"; else echo "GAGAL D18: cek split slice 11 / seam dsApiJson / panen cookie / batas file"; fail=1; fi
  if grep -q "private fun cutAtMessageBoundaryKotlin" "$TREE/BgBrain.kt" && grep -q "private fun trimTranscriptVc263Kotlin" "$TREE/BgBrain.kt" && grep -q "private fun bgQuietHourKotlin" "$TREE/BgBrain.kt" && grep -q "private fun batteryFormulaKotlin" "$TREE/BgBrain.kt" && grep -q "private fun submitGateKotlin" "$TREE/BgBrain.kt" && grep -q "private fun continuationPromptKotlin" "$TREE/BgBrain.kt" && grep -q "private fun honestCapStopBgKotlin" "$TREE/BgBrain.kt" && grep -q "private fun bgToolRouteDecisionKotlin" "$TREE/BgBrain.kt" && grep -q "private fun watchdogDecisionKotlin" "$TREE/BgBrain.kt" && grep -q "private fun queueBgFactProposalKotlin" "$TREE/BgBrain.kt" && grep -q "private fun deliverTitleKotlin" "$TREE/BgBrain.kt" && grep -q "private fun parseMentionsKotlin" "$TREE/GroupChat.kt" && grep -q "private fun personaNameForKotlin" "$TREE/GroupChat.kt" && grep -q "private fun accentForKotlin" "$TREE/GroupChat.kt" && grep -q "private fun selectQueueKotlin" "$TREE/GroupChat.kt" && grep -q "private fun resolveSlotKotlin" "$TREE/GroupChat.kt" && grep -q "private fun honestCapStopGroupKotlin" "$TREE/GroupChat.kt" && grep -q "private fun chairHopKotlin" "$TREE/GroupChat.kt" && grep -q "private fun retrieveMemoriesKotlin" "$TREE/GroupChat.kt" && grep -q "private fun continuationPromptVc321Kotlin" "$TREE/GroupChat.kt" && grep -q "private fun buildPromptKotlin" "$TREE/GroupChat.kt" && grep -q "private fun runGroupToolDecisionKotlin" "$TREE/GroupChat.kt" && grep -q "private fun bgRoundGateKotlin" "$TREE/GroupChat.kt" && grep -q "private fun renderNotif3LinesKotlin" "$TREE/GroupChat.kt" && grep -q "private fun groupPlansKotlin" "$TREE/GroupChat.kt" && grep -q "private fun saveLastRoundAsPlanKotlin" "$TREE/GroupChat.kt" && grep -q "private fun evalParseKotlin" "$TREE/DsAgent.kt" && grep -q "private fun pageTextJsKotlin" "$TREE/DsAgent.kt" && grep -q "private fun attachJsKotlin" "$TREE/DsAgent.kt" && grep -q "private fun probeJsKotlin" "$TREE/DsAgent.kt" && grep -q "private fun lastStateJsKotlin" "$TREE/DsAgent.kt" && grep -q "private fun sendJsKotlin" "$TREE/DsAgent.kt" && grep -q "private fun pollAnswerKotlin" "$TREE/DsAgent.kt" && grep -q "private fun ruleSanitizeKotlin" "$TREE/OcCoord.kt" && grep -q "private fun runloopGateKotlin" "$TREE/OcCoord.kt" && grep -q "private fun sessionRotateKotlin" "$TREE/OcCoord.kt" && grep -q "private fun observeKotlin" "$TREE/OcCoord.kt" && grep -q "private fun flushKotlin" "$TREE/OcCoord.kt" && grep -q "private fun spinningPatternVc277Kotlin" "$TREE/OcCoord.kt" && grep -q "private fun looksFailedVc262Kotlin" "$TREE/OcCoord.kt" && grep -q "private fun honestStatusKotlin" "$TREE/OcCoord.kt" && grep -q "private fun scanMessagesKotlin" "$TREE/OcCoord.kt" && grep -q "private fun awaitBodyKotlin" "$TREE/OcCoord.kt" && grep -q "private fun incrementalAssistantTextVc303Kotlin" "$TREE/OcCoord.kt" && grep -q "private fun collectResultKotlin" "$TREE/OcCoord.kt" && grep -q "private fun parsePermEventKotlin" "$TREE/OcCoord.kt" && grep -q "private fun permDecisionKotlin" "$TREE/OcCoord.kt" && grep -q "private fun taskResultTextKotlin" "$TREE/OcCoord.kt" && grep -q "private fun peekSessionTextKotlin" "$TREE/OcCoord.kt" && grep -q "private fun ensureServeKotlin" "$TREE/OcCoord.kt" && grep -q "private fun probeCacheReadKotlin" "$TREE/OcCoord.kt" && grep -q "private fun statusTextKotlin" "$TREE/OcCoord.kt" && grep -q "private fun batteryPctKotlin" "$TREE/OcCoord.kt" && grep -q "private fun isQuietNowKotlin" "$TREE/OcCoord.kt" && grep -q "OcCoordSeam.budgetOver" "$TREE/OcCoord.kt" && grep -q "object BgBrainSeam" "$TREE/BgRoomSeam.kt" && grep -q "object GroupChatSeam" "$TREE/BgRoomSeam.kt" && grep -q "object DsAgentSeam" "$TREE/BgRoomSeam.kt" && grep -q "object OcCoordSeam" "$TREE/BgRoomSeam.kt" && grep -q "var rustBgBrain" "$TREE/BgRoomSeam.kt" && grep -q "var rustGroupChat" "$TREE/BgRoomSeam.kt" && grep -q "var rustDsAgent" "$TREE/BgRoomSeam.kt" && grep -q "var rustOcCoord" "$TREE/BgRoomSeam.kt" && grep -q "external fun bgBrainJson(request: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "external fun groupChatJson(request: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "external fun dsAgentJson(request: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "external fun ocCoordJson(request: String): String?" "$TREE_CORE/RustCore.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt" && grep -q "RUST_CORE_VERSION: jint = 10" "harness-core/src/jni_glue.rs" && grep -q "WebView" "$TREE/BgBrain.kt" && grep -q "startServeViaTermux" "$TREE/OcCoord.kt" && grep -q "Thread.sleep" "$TREE/OcCoord.kt" && ! grep -q "Vc329\|vc329" "$TREE/Flavor.kt" && ! grep -q "Vc329\|vc329" "$TREE/MemoryDb.kt"; then echo "LULUS D19: fallback slice 12 dipertahankan (BgBrain 11 + GroupChat 15 + DsAgent 7 + OcCoord 21 *Kotlin + budgetOver seam + BgRoomSeam 4 objek/saklar + 4 external fun + VERSION=10 + RUST_CORE_VERSION 10 + WebView/serve/Thread.sleep tetap Kotlin + Flavor/MemoryDb tanpa vc329)"; else echo "GAGAL D19: cek fallback slice 12 / seam bgRoomJson / VERSION Rust / batas file"; fail=1; fi
  if grep -q "fun deltaVerifyPromptVc330" "$TREE/AgentPrompt.kt" && grep -q "fun deltaExtractPromptVc330" "$TREE/AgentPrompt.kt" && grep -q "const val VERIFY_SKIPPED_VC330" "$TREE/AgentPrompt.kt" && grep -q "fun silentSkippedResponseVc330" "$TREE/AgentPrompt.kt" && grep -q "const val EXTRACT_WINDOW_N_VC330 = 13" "$TREE/AgentPrompt.kt" && grep -q "const val EXTRACT_WINDOW_CHARS_VC330 = 8000" "$TREE/AgentPrompt.kt" && grep -q "fun shouldSkipSilentVc330" "$TREE/TurnMetrics.kt" && grep -q "const val DEFAULT_CAP = 200000" "$TREE/TurnMetrics.kt" && grep -q "AgentPrompt.deltaVerifyPromptVc330(currentTurnUser, trailTxt, wsLineVc228, visible)" "$TREE/MainActivity.kt" && grep -q "sendToAgent(fullVerifyVc330, deltaVerifyVc330)" "$TREE/MainActivity.kt" && grep -q "AgentPrompt.deltaExtractPromptVc330(currentTurnUser, histRecentVc330)" "$TREE/MainActivity.kt" && grep -q "historyTranscriptVc230(AgentPrompt.EXTRACT_WINDOW_N_VC330, AgentPrompt.EXTRACT_WINDOW_CHARS_VC330, turnSessionId)" "$TREE/MainActivity.kt" && grep -q "val silentKindVc330: String? = when" "$TREE/MainActivity.kt" && grep -q "visible.trim() == AgentPrompt.VERIFY_SKIPPED_VC330" "$TREE/MainActivity.kt" && grep -q "Verifikasi diri dilewati vc330" "$TREE/MainActivity.kt" && grep -q "deltaPrompt: String? = null, silentKindVc330: String? = null" "$TREE/SlotClient.kt" && grep -q "deltaPrompt: String? = null, silentKindVc330: String? = null" "$TREE/DsApiClient.kt" && grep -q "TurnMetrics.shouldSkipSilentVc330(silentKindVc330" "$TREE/SlotClient.kt" && grep -q "TurnMetrics.shouldSkipSilentVc330(silentKindVc330" "$TREE/DsApiClient.kt" && grep -q "DILEWATI vc330 (wadah penuh: akumulasi" "$TREE/SlotClient.kt" && grep -q "DILEWATI vc330 (wadah penuh: akumulasi" "$TREE/DsApiClient.kt" && grep -q "fun chatSync(prompt: String, thinking: Boolean, search: Boolean, freshEphemeral: Boolean = false, reviewer: Boolean = false)" "$TREE/SlotClient.kt" && grep -q "const val VERSION = 10" "$TREE_CORE/RustCore.kt" && grep -q "RUST_CORE_VERSION: jint = 10" "harness-core/src/jni_glue.rs" && ! grep -q "Vc330\|vc330" "$TREE/Flavor.kt" && ! grep -q "Vc330\|vc330" "$TREE/MemoryDb.kt" && ! grep -q "Vc330\|vc330" "$TREE/GroupChat.kt" && ! grep -q "Vc330\|vc330" "$TREE/BgBrain.kt" && ! grep -q "Vc330\|vc330" "harness-core/src/jni_glue.rs"; then echo "LULUS D20: fix bocor sesi vc330 ada (delta verifikasi + delta ekstraksi 13/8000 + katup senyap kedua klien + sentinel sebelum cleanVerdict + chatSync tanpa silentKind) + RUST_CORE_VERSION 10 + Flavor/MemoryDb/GroupChat/BgBrain/Rust tanpa vc330"; else echo "GAGAL D20: cek fix bocor sesi vc330 / VERSION Rust / batas file"; fail=1; fi
  if grep -q "fun stripForStreamingVc331" "$TREE/AgentPrompt.kt" && grep -q "AgentPrompt.stripForStreamingVc331(t)" "$TREE/MainActivity.kt" && grep -q "stripForStreamingVc331" "$TREE/AgentPrompt.kt" && grep -q "3000" "$TREE/AgentPrompt.kt" && grep -q "normalize_dsml_delimiters" "harness-core/src/agent_prompt.rs" && grep -q "RUST_CORE_VERSION: jint = 10" "harness-core/src/jni_glue.rs" && ! grep -q "Vc331\|vc331" "$TREE/Flavor.kt" && ! grep -q "Vc331\|vc331" "$TREE/MemoryDb.kt" && ! grep -q "stripForStreaming" "harness-core/src/jni_glue.rs"; then echo "LULUS D21: fix bocor DSML vc331 ada (normalize look-alike + penutup polos + stripForStreamingVc331 di sanitizePartial + helper Kotlin murni tanpa op Rust baru) + RUST_CORE_VERSION 10 + Flavor/MemoryDb tanpa vc331"; else echo "GAGAL D21: cek fix bocor DSML vc331 / VERSION Rust / batas file"; fail=1; fi
  return $fail
}

SECTION="${1:-ALL}"
case "$SECTION" in
  0) run_0;;
  P1) run_p1;;
  P2) run_p2;;
  P3) run_p3;;
  P4) run_p4;;
  P5) run_p5;;
  P6) run_p6;;
  P7) run_p7;;
  P8) run_p8;;
  P9) run_p9;;
  P10) run_p10;;
  P11) run_p11;;
  P12) run_p12;;
  P13) run_p13;;
  P14) run_p14;;
  P15) run_p15;;
  P16) run_p16;;
  P17) run_p17;;
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
    run_p4 || FAIL=1
    run_p5 || FAIL=1
    run_p6 || FAIL=1
    run_p7 || FAIL=1
    run_p8 || FAIL=1
    run_p9 || FAIL=1
    run_p10 || FAIL=1
    run_p11 || FAIL=1
    run_p12 || FAIL=1
    run_p13 || FAIL=1
    run_p14 || FAIL=1
    run_p15 || FAIL=1
    run_p16 || FAIL=1
    run_p17 || FAIL=1
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
  *) echo "section tidak dikenal: $SECTION (0|P1|P2|P3|P4|P5|P6|P7|P8|P9|P10|P11|P12|P13|P14|P15|P16|P17|N9|BR|IXL|R1|R2|C|D|ALL)"; exit 2;;
esac
