// TestPowSolverVc318.kt — tes paritas JVM wrapper PowSolver slice 2 (vc318).
//
// Yang diuji (36 cek):
//  1. Hash: sha3_256 / sha3Hex vs vektor FIPS 202 + referensi Keccak
//     independen (sama seperti tes Rust pow_solver) — wrapper di JVM
//     (.so absen -> RustCore.available=false) HARUS jatuh ke fallback
//     Kotlin dan hasilnya byte-identik dengan implementasi Kotlin lama.
//  2. dsHashV1: beda dari SHA3 (bukti skip round 0) + vektor independen.
//  3. hexToBytes: roundtrip + quirk (ganjil abaikan akhir, "zz" -> 0xEF).
//  4. Solve: tanam MainActivity (nonce 4242), solveFmt A difficulty 1000
//     (nonce 2357), semua format B-H, format tak dikenal -> A,
//     maxTries habis -> null, difficulty 0 -> null.
//  5. QUIRK solveFast: maxTries=10 TETAP ketemu nonce 2357 (batas
//     efektif 3 jt — direplikasi Rust, jangan "diperbaiki" satu sisi).
//  6. solve == solveFmt A (nonce + hash sama).
//  7. Seam: rustPowSolver default true, RustCore.VERSION == 7, fallback
//     *Kotlin dipertahankan di sumber, 8 external fun PoW ada di RustCore.
//
// Di JVM .so TIDAK ada, jadi yang diuji di sini = jalur fallback Kotlin
// wrapper == perilaku Kotlin lama. Jalur Rust diuji cargo test (139/139)
// + di perangkat (TES-VC318: rustCoreVersion()==2, solve jalan).
//
// Jalankan (dari vc317-work): kotlinc TestPowSolverVc318.kt -cp
// work-impl/test-vc318-tree-integrated.jar -include-runtime -d
// work-impl/test-p2-vc318-coord.jar && java -cp ... TestPowSolverVc318Kt
// work-impl/integrated
// Keluar 0 = semua lulus; selain itu daftar GAGAL + exit 1.
package com.senzwh.deepharness

import harness.core.RustCore
import java.io.File
import kotlin.system.exitProcess

var passed = 0
var failed = 0
val failedNames = ArrayList<String>()

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
        println("LULUS  $name")
    } else {
        failed++
        failedNames.add(name)
        println("GAGAL  $name  $detail")
    }
}

fun bytesToHex(b: ByteArray): String {
    val sb = StringBuilder()
    for (x in b) sb.append(String.format("%02x", x))
    return sb.toString()
}

fun main(args: Array<String>) {
    val workDir = if (args.isNotEmpty()) args[0] else "."
    val powSrc = File(workDir, "PowSolver.kt").readText()
    // RustCore.kt: tree FLAT lokal (work-impl/integrated/harness/...) vs
    // tree REPO di CI (app/src/main/java/harness/... = naik 3 level
    // dari .../java/com/senzwh/deepharness). Pakai yang ada.
    val rustCoreFile = listOf(
        File(workDir, "harness/core/RustCore.kt"),
        File(workDir, "../../../harness/core/RustCore.kt")
    ).firstOrNull { it.exists() } ?: File(workDir, "harness/core/RustCore.kt")
    val rustCoreSrc = rustCoreFile.readText()

    // ================= 1. SHA3-256 (vektor FIPS 202) =================
    check(
        "1.1 sha3Hex(\"\") = vektor FIPS 202",
        PowSolver.sha3Hex("") == "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
        "dapat=${PowSolver.sha3Hex("")}"
    )
    check(
        "1.2 sha3Hex(\"abc\") = vektor FIPS 202",
        PowSolver.sha3Hex("abc") == "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532"
    )
    check(
        "1.3 sha3_256(\"abc\") byte-identik sha3Hex(\"abc\")",
        bytesToHex(PowSolver.sha3_256("abc".toByteArray(Charsets.UTF_8))) == PowSolver.sha3Hex("abc")
    )

    // ================= 2. DeepSeekHashV1 (skip round 0) =================
    check(
        "2.1 dsHashV1Hex(\"\") = vektor referensi independen",
        PowSolver.dsHashV1Hex("") == "e594808bc5b7151ac160c6d39a02e0a8e261ed588578403099e3561dc40c26b3",
        "dapat=${PowSolver.dsHashV1Hex("")}"
    )
    check(
        "2.2 dsHashV1Hex(\"abc\") = vektor referensi independen",
        PowSolver.dsHashV1Hex("abc") == "f841106c601ce9be9bc38525e90d4178d47f21dd8eb9f238fc55ffaa4ca94506",
        "dapat=${PowSolver.dsHashV1Hex("abc")}"
    )
    check(
        "2.3 dsHashV1(\"abc\") BEDA dari sha3_256(\"abc\") (bukti skip round 0)",
        !PowSolver.dsHashV1("abc".toByteArray(Charsets.UTF_8))
            .contentEquals(PowSolver.sha3_256("abc".toByteArray(Charsets.UTF_8)))
    )
    check(
        "2.4 dsHashV1Hex tanam MainActivity",
        PowSolver.dsHashV1Hex("harness-test_1792611672443_4242") ==
            "006e9152b954a1f141c28e00f64b88f32a3ddc79fa8cdab70671dfc1aca8d655",
        "dapat=${PowSolver.dsHashV1Hex("harness-test_1792611672443_4242")}"
    )

    // ================= 3. hexToBytes (quirk Kotlin) =================
    check(
        "3.1 hexToBytes(\"00ff10ab\") roundtrip",
        PowSolver.hexToBytes("00ff10ab").contentEquals(byteArrayOf(0x00, 0xff.toByte(), 0x10, 0xab.toByte()))
    )
    check(
        "3.2 hexToBytes(\"zz\") -> 0xEF (semantik Character.digit -1)",
        PowSolver.hexToBytes("zz").contentEquals(byteArrayOf(0xef.toByte())),
        "dapat=${bytesToHex(PowSolver.hexToBytes("zz"))}"
    )
    check(
        "3.3 hexToBytes(\"abc\") -> 0xAB (panjang ganjil abaikan akhir)",
        PowSolver.hexToBytes("abc").contentEquals(byteArrayOf(0xab.toByte()))
    )

    // ================= 4. solveDeepSeek (tanam MainActivity) =================
    val planted = PowSolver.solveDeepSeek(
        "006e9152b954a1f141c28e00f64b88f32a3ddc79fa8cdab70671dfc1aca8d655",
        "harness-test", 1792611672443L, 100000L
    )
    check(
        "4.1 solveDeepSeek tanam -> nonce 4242, tries 4243",
        planted != null && planted.nonce == 4242L && planted.tries == 4243L,
        "dapat=$planted"
    )
    check(
        "4.2 solveDeepSeek tanam -> hashHex == challenge",
        planted != null && planted.hashHex == "006e9152b954a1f141c28e00f64b88f32a3ddc79fa8cdab70671dfc1aca8d655"
    )
    check(
        "4.3 solveDeepSeek challenge nol + difficulty 100 -> null",
        PowSolver.solveDeepSeek(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "salt-tidak-ada", 1L, 100L
        ) == null
    )
    check(
        "4.4 solveDeepSeek difficulty 0 -> null",
        PowSolver.solveDeepSeek(
            "006e9152b954a1f141c28e00f64b88f32a3ddc79fa8cdab70671dfc1aca8d655",
            "harness-test", 1792611672443L, 0L
        ) == null
    )

    // ================= 5. solveFmt =================
    val fmtA = PowSolver.solveFmt("testchallenge", "testsalt", 1000L, "A", 0L, 3_000_000L)
    check(
        "5.1 solveFmt A difficulty 1000 -> nonce 2357, tries 2358",
        fmtA != null && fmtA.nonce == 2357L && fmtA.tries == 2358L,
        "dapat=$fmtA"
    )
    check(
        "5.2 solveFmt A difficulty 1000 -> hashHex 000c0bd6...",
        fmtA != null && fmtA.hashHex == "000c0bd6d07267df7eff06a1a3eab29dd10300a9357c43c0925682dfb3ab9551"
    )
    check(
        "5.3 solveFmt A difficulty 1 -> nonce 1",
        PowSolver.solveFmt("testchallenge", "testsalt", 1L, "A", 0L, 3_000_000L)?.nonce == 1L
    )
    val fmtWant = mapOf("B" to 65L, "C" to 895L, "D" to 345L, "E" to 145L, "F" to 1679L, "G" to 307L, "H" to 6L)
    for ((fmt, want) in fmtWant) {
        val r = PowSolver.solveFmt("c", "s", 1000L, fmt, 12345L, 3_000_000L)
        check("5.4 solveFmt format $fmt difficulty 1000 -> nonce $want", r != null && r.nonce == want, "dapat=$r")
    }
    val fmtZ = PowSolver.solveFmt("testchallenge", "testsalt", 1000L, "ZZZ", 0L, 3_000_000L)
    check(
        "5.5 solveFmt format tak dikenal (ZZZ) == A (nonce+hash+tries)",
        fmtA != null && fmtZ != null && fmtZ.nonce == fmtA.nonce && fmtZ.hashHex == fmtA.hashHex && fmtZ.tries == fmtA.tries,
        "A=$fmtA ZZZ=$fmtZ"
    )
    check(
        "5.6 solveFmt maxTries 10 habis -> null",
        PowSolver.solveFmt("testchallenge", "testsalt", 1000L, "A", 0L, 10L) == null
    )
    check(
        "5.7 solveFmt difficulty 0 -> null",
        PowSolver.solveFmt("testchallenge", "testsalt", 0L, "A", 0L, 1000L) == null
    )

    // ================= 6. solveFast (QUIRK maxTries) =================
    val fast = PowSolver.solveFast("testchallenge", "testsalt", 1000L, 3_000_000L)
    check(
        "6.1 solveFast == solveFmt A (nonce 2357 + hash sama)",
        fast != null && fmtA != null && fast.nonce == 2357L && fast.nonce == fmtA.nonce && fast.hashHex == fmtA.hashHex,
        "fast=$fast A=$fmtA"
    )
    val fastQuirk = PowSolver.solveFast("testchallenge", "testsalt", 1000L, 10L)
    check(
        "6.2 QUIRK solveFast maxTries=10 TETAP ketemu nonce 2357",
        fastQuirk != null && fastQuirk.nonce == 2357L,
        "dapat=$fastQuirk"
    )

    // ================= 7. solve =================
    val s = PowSolver.solve("testchallenge", "testsalt", 1000L, 3_000_000L)
    check(
        "7.1 solve == solveFmt A (nonce 2357 + hash 000c0bd6...)",
        s != null && s.nonce == 2357L &&
            s.hashHex == "000c0bd6d07267df7eff06a1a3eab29dd10300a9357c43c0925682dfb3ab9551",
        "dapat=$s"
    )
    check(
        "7.2 solve maxTries 10 habis -> null",
        PowSolver.solve("testchallenge", "testsalt", 1000L, 10L) == null
    )

    // ================= 8. Seam + fallback dipertahankan =================
    check("8.1 rustPowSolver default true", PowSolver.rustPowSolver)
    check("8.2 RustCore.VERSION == 7", RustCore.VERSION == 7, "VERSION=${RustCore.VERSION}")
    check(
        "8.3 fallback Kotlin dipertahankan (*Kotlin + saklar)",
        powSrc.contains("private fun sha3_256Kotlin") && powSrc.contains("private fun dsHashV1Kotlin") &&
            powSrc.contains("private fun solveDeepSeekKotlin") && powSrc.contains("private fun solveFastKotlin") &&
            powSrc.contains("private fun solveFmtKotlin") && powSrc.contains("private fun solveKotlin") &&
            powSrc.contains("var rustPowSolver")
    )
    check(
        "8.4 RustCore.kt: 8 external fun PoW",
        listOf(
            "powSha3Hex", "powDsHashV1Hex", "powSha3Bytes", "powDsHashV1Bytes",
            "powSolveDeepSeekJson", "powSolveFastJson", "powSolveFmtJson", "powSolveJson"
        ).all { rustCoreSrc.contains("external fun $it") }
    )
    check(
        "8.5 .so absen di JVM -> RustCore.available false (jalur fallback teruji)",
        !RustCore.available
    )

    println("\nTOTAL: $passed lulus, $failed gagal")
    if (failedNames.isNotEmpty()) println("GAGAL: " + failedNames.joinToString(", "))
    exitProcess(if (failed == 0) 0 else 1)
}
