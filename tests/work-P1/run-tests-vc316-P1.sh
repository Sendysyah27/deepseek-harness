#!/usr/bin/env bash
# run-tests-vc316-P1.sh — SUITE WORKER P1 vc316 (Pola 1 FailureKind).
# Dijalankan di ~/workspace/deepseek-harness/vc316-work/work-P1.
# JDK sistem (Temurin ~/jdk-21.0.12.1+1 RUSAK — lib/modules hilang).
#
# Suite:
#   1. TestDsFailureVc316 (unit DsFailure + cek sumber 3 file kerja):
#      klasifikasi tiap kind (incl. input_exceeds_limit -> CONTEXT_WINDOW),
#      Retry-After dipakai, BgBrain cooldown vs sesi-mati, SlotClient
#      memakai classify yang sama, regresi tangga lama (backoff 10/20/35,
#      retry transien 1x, stream-putus 2x + 1 sesi baru).
#      DsFailure.kt murni Kotlin -> dikompilasi standalone (tanpa Android).
#   T. Kompilasi tree PENUH (staging = merge/ + overlay 4 file kerja):
#      0 error wajib — memastikan DsApiClient/SlotClient/BgBrain/DsFailure
#      kompak dalam konteks tree (bukan cuma standalone).
#   C. Diff work-P1 vs merge/ = TEPAT 3 file .kt berubah + 1 file baru
#      (DsFailure.kt). File lain JANGAN tersentuh.
#   D. Sapu: hsurr: 0 di work-P1; tanpa m.contains sisa di 3 file kerja.
# File tes + runner BUKAN bagian APK.
# Keluar 0 = semua lulus; selain itu OVERALL FAIL=1.
set -u
cd ~/workspace/deepseek-harness/vc316-work/work-P1
JDK_HOME=/usr/lib/jvm/java-21-openjdk-amd64
if [ ! -x "$JDK_HOME/bin/java" ]; then echo "JDK sistem tidak ada: $JDK_HOME"; exit 1; fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JDK_HOME/bin:$HOME/kotlin-dist/kotlinc/bin:$PATH"
export KOTLINC_OPTS="-Xmx3g" JAVA_OPTS="-Xmx3g"
KOTLINC="$HOME/kotlin-dist/kotlinc/bin/kotlinc"
JAVA="$JDK_HOME/bin/java"
ANDROID_JAR="$HOME/android-sdk/platforms/android-34/android.jar"
JSON_JAR="$HOME/workspace/deepseek-harness/char-tests/json-20240303.jar"
VENDOR="$HOME/workspace/deepseek-harness/vendor"
STUBS="$HOME/workspace/deepseek-harness/vc305-work/work-impl/test-stubs/R.java"
CP="$JSON_JAR:$ANDROID_JAR:$VENDOR/ort/classes.jar:$VENDOR/bc/bcprov.jar:$VENDOR/conscrypt/classes.jar:$VENDOR/xz/xz.jar:$VENDOR/spake2/spake2-java-1.1.1.jar:$VENDOR/spake2/curve25519-elisabeth-0.1.0.jar:$VENDOR/spake2/ed25519-elisabeth-0.1.0.jar"
MERGE="../merge"
FAIL=0

echo "== 1. TestDsFailureVc316 (unit DsFailure + cek sumber) =="
"$KOTLINC" -J-Xmx3g -jvm-target 17 DsFailure.kt TestDsFailureVc316.kt -include-runtime -d test-dsfailure-vc316.jar || FAIL=1
"$JAVA" -Xmx1g -cp test-dsfailure-vc316.jar com.senzwh.deepharness.TestDsFailureVc316Kt "$PWD" || FAIL=1

echo "== T. Kompilasi tree penuh (staging merge + overlay 4 file kerja, 0 error wajib) =="
rm -rf tree-staging
cp -r "$MERGE" tree-staging
cp DsFailure.kt DsApiClient.kt SlotClient.kt BgBrain.kt tree-staging/
"$KOTLINC" -J-Xmx3g -jvm-target 17 tree-staging/*.kt tree-staging/priv/*.kt "$STUBS" -cp "$CP" -include-runtime -d test-vc316-tree-P1.jar
if [ $? -ne 0 ]; then echo "GAGAL  T: kompilasi tree staging"; FAIL=1; else echo "LULUS  T: tree staging kompilasi 0 error"; fi

echo "== C. Diff work-P1 vs merge/ = tepat 3 berubah + 1 baru =="
DIFFOUT=$(diff -rq "$MERGE" . --exclude=TestDsFailureVc316.kt --exclude=run-tests-vc316-P1.sh --exclude=test-dsfailure-vc316.jar --exclude=test-vc316-tree-P1.jar --exclude=tree-staging --exclude=run-tests-vc316-P1.log 2>/dev/null)
echo "$DIFFOUT"
N=$(echo "$DIFFOUT" | grep -c "Files.*differ" || true)
ONLY=$(echo "$DIFFOUT" | grep -c "Only in .: DsFailure.kt" || true)
if [ "$N" -ne 3 ] || [ "$ONLY" -ne 1 ]; then echo "GAGAL  C: $N file berubah + $ONLY baru (harus 3 + 1)"; FAIL=1; else echo "LULUS  C: tepat 3 file .kt berubah + DsFailure.kt baru"; fi
for f in DsApiClient.kt SlotClient.kt BgBrain.kt; do
  echo "$DIFFOUT" | grep -q "$f" || { echo "GAGAL  C: $f tidak ada di diff"; FAIL=1; }
done

echo "== D. Sapu work-P1/ =="
if grep -rq "hsurr:" DsFailure.kt DsApiClient.kt SlotClient.kt BgBrain.kt TestDsFailureVc316.kt; then echo "GAGAL  D: ada hsurr:"; FAIL=1; else echo "LULUS  D1: hsurr: 0"; fi
if grep -q "m\.contains(\|m2\.contains(\|msg\.contains(" DsApiClient.kt SlotClient.kt BgBrain.kt; then echo "GAGAL  D: masih ada contains(...) manual di file kerja"; grep -n "m\.contains(\|m2\.contains(\|msg\.contains(" DsApiClient.kt SlotClient.kt BgBrain.kt; FAIL=1; else echo "LULUS  D2: tanpa contains(...) manual di 3 file kerja"; fi

echo "OVERALL FAIL=$FAIL"
exit $FAIL
