#!/usr/bin/env bash
# run_tests.sh — test cepat tanpa Android/Gradle.
#
#   1. ToolRegistryTest (JVM murni): konsistensi katalog tool.
#   2. test_flavor.py: logika flavor Pure di build.py.
#
# Pemakaian:
#   bash tools/run_tests.sh
#   HARNESS_KOTLINC=/path/to/kotlinc bash tools/run_tests.sh
#
# Butuh: kotlinc (+ kotlin-stdlib.jar), python3, curl/wget (unduh org.json).
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Prioritas: HARNESS_KOTLINC -> instalasi CI (~/kotlin-dist) -> PATH.
# (`kotlinc` di PATH CI adalah wrapper /usr/bin yang tak punya stdlib.)
if [ -n "${HARNESS_KOTLINC:-}" ]; then
  KOTLINC="$HARNESS_KOTLINC"
elif [ -x "$HOME/kotlin-dist/kotlinc/bin/kotlinc" ]; then
  KOTLINC="$HOME/kotlin-dist/kotlinc/bin/kotlinc"
else
  KOTLINC="$(command -v kotlinc || true)"
fi
if [ -z "$KOTLINC" ] || [ ! -x "$KOTLINC" ]; then
  echo "kotlinc tidak ditemukan (set HARNESS_KOTLINC)" >&2
  exit 1
fi
KOTLIN_HOME="$(cd "$(dirname "$KOTLINC")/.." && pwd)"
STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
if [ ! -f "$STDLIB" ] && [ -f "$HOME/kotlin-dist/kotlinc/lib/kotlin-stdlib.jar" ]; then
  STDLIB="$HOME/kotlin-dist/kotlinc/lib/kotlin-stdlib.jar"
fi
if [ ! -f "$STDLIB" ]; then
  echo "kotlin-stdlib.jar tidak ada (cek KOTLINC=$KOTLINC)" >&2
  exit 1
fi

JSON_JAR="$PROJ/vendor/test/json.jar"
if [ ! -s "$JSON_JAR" ]; then
  mkdir -p "$(dirname "$JSON_JAR")"
  URL="https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"
  echo "== unduh org.json =="
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$URL" -o "$JSON_JAR"
  else wget -qO "$JSON_JAR" "$URL"; fi
fi

OUT="$PROJ/build-test"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "== kompilasi test Kotlin =="
"$KOTLINC" -nowarn -cp "$JSON_JAR" -d "$OUT" \
  "$PROJ/app/src/test/AndroidStubs.kt" \
  "$PROJ/app/src/test/AndroidStubs2.kt" \
  "$PROJ/app/src/test/AndroidStubs3.kt" \
  "$PROJ/app/src/test/AndroidStubs4.kt" \
  "$PROJ/app/src/test/AndroidStubs5.kt" \
  "$PROJ/app/src/test/AndroidStubs6.kt" \
  "$PROJ/app/src/test/AndroidStubs7.kt" \
  "$PROJ/app/src/test/AndroidStubs8.kt" \
  "$PROJ/app/src/test/AgentPromptStubs.kt" \
  "$PROJ/app/src/test/FakeAndroid.kt" \
  "$PROJ/app/src/main/java/harness/core/RustCore.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/SqliteSeam.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/ToolRegistry.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/RedactSecrets.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/StreamAccumVc334.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/OpenAiCompatClient.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/LlmProviders.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/Governor.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/TurnMetrics.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/EventDb.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/SecureStore.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/LlmProviderStore.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/AgentEngine.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/Behaviors.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/AgentPrompt.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/ModelCaps.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/VisionFiles.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/Vc334R4Pure.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/LlmEngineChat.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/FirstRunState.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/ModelPickerController.kt" \
  "$PROJ/app/src/test/ToolRegistryTest.kt" \
  "$PROJ/app/src/test/LlmProvidersTest.kt" \
  "$PROJ/app/src/test/OpenAiCompatClientTest.kt" \
  "$PROJ/app/src/test/ModelCatalogTest.kt" \
  "$PROJ/app/src/test/FirstRunStateTest.kt" \
  "$PROJ/app/src/test/ModelCapsTest.kt" \
  "$PROJ/app/src/test/VisionFilesTest.kt" \
  "$PROJ/app/src/test/GovernorModelsTest.kt" \
  "$PROJ/app/src/test/EngineRouterTest.kt" \
  "$PROJ/app/src/test/ReviewerMigrationTest.kt" \
  "$PROJ/app/src/test/RoomTeamGraphMigrationTest.kt" \
  "$PROJ/app/src/test/ModelPickerControllerTest.kt" \
  "$PROJ/app/src/test/ToolFallbackTest.kt" \
  "$PROJ/app/src/test/VerificationRemovedTest.kt" \
  "$PROJ/app/src/test/StopButtonVc334Test.kt" \
  "$PROJ/app/src/test/StopButtonVc334R2Test.kt" \
  "$PROJ/app/src/test/PerfMechanicsVc334R3Test.kt" \
  "$PROJ/app/src/test/VisionPathsVc334R4Test.kt" \
  "$PROJ/app/src/main/java/com/senzwh/deepharness/PrefsSafe.kt" \
  "$PROJ/app/src/test/BrainBackupG7Test.kt"

echo "== jalankan ToolRegistryTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.ToolRegistryTestKt

echo "== jalankan LlmProvidersTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.LlmProvidersTestKt

echo "== jalankan OpenAiCompatClientTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.OpenAiCompatClientTestKt

echo "== jalankan ModelCatalogTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.ModelCatalogTestKt

echo "== jalankan FirstRunStateTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.FirstRunStateTestKt

echo "== jalankan ModelCapsTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.ModelCapsTestKt

echo "== jalankan VisionFilesTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.VisionFilesTestKt

echo "== jalankan GovernorModelsTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.GovernorModelsTestKt

echo "== jalankan EngineRouterTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.EngineRouterTestKt

echo "== jalankan ReviewerMigrationTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.ReviewerMigrationTestKt

echo "== jalankan RoomTeamGraphMigrationTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.RoomTeamGraphMigrationTestKt

echo "== jalankan ModelPickerControllerTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.ModelPickerControllerTestKt

echo "== jalankan ToolFallbackTest =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.ToolFallbackTestKt

echo "== jalankan VerificationRemovedTest =="
java -Dproj.dir="$PROJ" -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.VerificationRemovedTestKt

echo "== jalankan StopButtonVc334Test =="
java -Dproj.dir="$PROJ" -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.StopButtonVc334TestKt

echo "== jalankan StopButtonVc334R2Test =="
java -Dproj.dir="$PROJ" -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.StopButtonVc334R2TestKt

echo "== jalankan PerfMechanicsVc334R3Test =="
java -Dproj.dir="$PROJ" -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.PerfMechanicsVc334R3TestKt

echo "== jalankan VisionPathsVc334R4Test =="
java -Dproj.dir="$PROJ" -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.VisionPathsVc334R4TestKt

echo "== jalankan BrainBackupG7Test =="
java -cp "$OUT:$STDLIB:$JSON_JAR" com.senzwh.deepharness.BrainBackupG7TestKt

echo "== jalankan test_flavor.py =="
python3 "$PROJ/tools/test_flavor.py"

echo "SEMUA TEST LULUS."
