// TestRedactSecretsVc318.kt — tes paritas JVM wrapper RedactSecrets slice 3 (vc318).
//
// Yang diuji (94 cek):
//  1. Self-test 32 kasus (label, input, expect changed) — port 1:1 dari
//     RedactSecrets.selfTest() + redact_secrets.rs SELF_TEST.
//  2. Idempotence sampel self-test: once == twice + once persis
//     "password=[REDAKSI: password] bearer token]" (hash FNV-1a
//     2ac96929f627f5c0 di model Rust — titik-tetap KEYVAL).
//  3. Ekstra 38 kasus (E1–E38) output PERSIS — port dari
//     redact_secrets.rs EXTRA (idempotence, teks ber-marker [REDAKSI],
//     campuran Bearer+password, kapital, CRLF, NBSP, PEM RSA, semua
//     prefiks API key, kata nilai multi-kata).
//  4. Diskriminator lookahead F1–F5 — satu-satunya bentuk di mana guard
//     Kotlin (?!\[REDAKSI\]) benar-benar memblokir (nilai "[REDAKSI]"
//     polos). Varian separator greedy GAGAL F1; lazy (B2) lolos 5/5.
//  5. API containsSecret / countMatches (port count_matches_api +
//     contains_secret_api Rust).
//  6. Seam + fallback dipertahankan: rustRedactSecrets default true,
//     RustCore.VERSION == 8, external fun redactSecrets ada,
//     redactKotlin/countMatchesKotlin dipertahankan di sumber,
//     .so absen di JVM -> RustCore.available false (jalur fallback
//     teruji), selfTest() 33/33 PASS.
//
// Di JVM .so TIDAK ada, jadi yang diuji di sini = jalur fallback Kotlin
// wrapper == perilaku Kotlin lama. Jalur Rust diuji cargo test (149/149)
// + di perangkat (TES-VC318: rustCoreVersion()==2, redact jalan).
// Perilaku ASLI Kotlin yang direproduksi 1:1 (JANGAN "diperbaiki"):
// guard lookahead praktis mati + BEARER (?i) me-match "bearer" DI DALAM
// marker -> redact(redact(x)) != redact(x) untuk teks ter-redaksi
// (E3/E4/E6/E17, dikunci tes Rust
// kotlin_original_is_not_idempotent_on_pre_redacted_text).
//
// Jalankan (dari vc317-work): kotlinc TestRedactSecretsVc318.kt -cp
// work-impl/test-vc318-tree-integrated.jar -include-runtime -d
// work-impl/test-p3-vc318-coord.jar && java -cp ... TestRedactSecretsVc318Kt
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

fun main(args: Array<String>) {
    val workDir = if (args.isNotEmpty()) args[0] else "."
    val redactSrc = File(workDir, "RedactSecrets.kt").readText()
    // RustCore.kt: tree FLAT lokal (work-impl/integrated/harness/...) vs
    // tree REPO di CI (app/src/main/java/harness/... = naik 3 level
    // dari .../java/com/senzwh/deepharness). Pakai yang ada.
    val rustCoreFile = listOf(
        File(workDir, "harness/core/RustCore.kt"),
        File(workDir, "../../../harness/core/RustCore.kt")
    ).firstOrNull { it.exists() } ?: File(workDir, "harness/core/RustCore.kt")
    val rustCoreSrc = rustCoreFile.readText()

    // ================= 1. Self-test 32 kasus =================
    val selfTest = listOf(
        Triple("PEM block", "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg==\n-----END PRIVATE KEY-----", true),
        Triple("Bearer", "Authorization: Bearer abcdef123456", true),
        Triple("password=", "password=rahasia123", true),
        Triple("token:", "token: abcdef123456", true),
        Triple("app password berlabel", "App Password: abcd efgh ijkl mnop", true),
        Triple("app password satu baris", "abcd efgh ijkl mnop", true),
        Triple("api key sk-", "key sk-live-abcdefghij1234567890", true),
        Triple("api key ghp_", "ghp_abcdefghij1234567890abcdefghij1234", true),
        Triple("MD5 utuh", "md5 d41d8cd98f00b204e9800998ecf8427e", false),
        Triple("SHA-256 utuh", "sha256 e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177", false),
        Triple("prose token listrik", "token listrik bulan ini sudah dibayar", false),
        Triple("kalimat 4 kata biasa", "kemarin saya suka baca buku di rumah", false),
        Triple("kata password saja", "jangan lupa passwordnya ya", false),
        Triple("passwd=", "passwd=palsu123", true),
        Triple("pwd=", "pwd=palsu123", true),
        Triple("pass=", "pass=palsu123", true),
        Triple("secret=", "secret=palsu123", true),
        Triple("api-key=", "api-key=palsu123", true),
        Triple("apikey=", "apikey=palsu123", true),
        Triple("client-secret=", "client-secret=palsu123", true),
        Triple("api key gho_", "gho_abcdefghij1234567890abcdefghij1234", true),
        Triple("api key github_pat_", "github_pat_abcdefghij1234567890abcd", true),
        Triple("api key xoxb-", "xoxb-1234567890ab", true),
        Triple("api key xoxp-", "xoxp-1234567890ab", true),
        Triple("api key xoxa-", "xoxa-1234567890ab", true),
        Triple("api key xoxr-", "xoxr-1234567890ab", true),
        Triple("api key xoxs-", "xoxs-1234567890ab", true),
        Triple("api key AIza", "AIza0123456789abcdefghijklmnopqrstuvwxy", true),
        Triple("api key AKIA", "AKIAABCDEFGHIJKLMNOP", true),
        Triple("api key sk-test-", "sk-test-abcdefghij1234567890", true),
        Triple("api key sk-proj-", "sk-proj-abcdefghij1234567890", true),
        Triple("api key sk-ant-", "sk-ant-abcdefghij1234567890", true)
    )
    for ((label, input, expect) in selfTest) {
        val got = RedactSecrets.redact(input) != input
        check("1. self-test [$label]", got == expect, "input=$input got=$got expect=$expect")
    }

    // ================= 2. Idempotence sampel =================
    val sample = "password=rahasia123 dan Bearer abcdef123456"
    val once = RedactSecrets.redact(sample)
    val twice = RedactSecrets.redact(once)
    check("2.1 idempotence sampel: once == twice", once == twice, "once=[$once] twice=[$twice]")
    check(
        "2.2 idempotence sampel: once persis titik-tetap KEYVAL",
        once == "password=[REDAKSI: password] bearer token]",
        "once=[$once]"
    )

    // ================= 3. Ekstra 38 kasus (output persis) =================
    val nbsp = "\u00A0"
    val extra = listOf(
        Triple("E1", "password=\"[REDAKSI: password]\"", "password=[REDAKSI: password]"),
        Triple("E2", "password= \"[REDAKSI: password]\"", "password=[REDAKSI: password]"),
        Triple("E3", "token: [REDAKSI: bearer token]", "token=[REDAKSI: token] token]"),
        Triple("E4", "Bearer [REDAKSI: bearer token]", "Bearer [REDAKSI: bearer token] Bearer [REDAKSI: bearer token]"),
        Triple("E5", "password=[REDAKSI: password]", "password=[REDAKSI: password]"),
        Triple("E6", "password=[REDAKSI: bearer token]", "password=[REDAKSI: password] token]"),
        Triple("E7", "Authorization: Bearer abcdef123456 dan password=rahasia123", "Authorization: Bearer [REDAKSI: bearer token] dan password=[REDAKSI: password]"),
        Triple("E8", "BEARER ABCDEF123", "Bearer [REDAKSI: bearer token]"),
        Triple("E9", "PASSWORD=Secret123", "PASSWORD=[REDAKSI: password]"),
        Triple("E10", "App Password: ABCD EFGH IJKL MNOP", "[REDAKSI: app-password, 16 karakter]"),
        Triple("E11", "abcd efgh ijkl mnop\r\nbaris lain", "[REDAKSI: app-password, 16 karakter]\r\nbaris lain"),
        Triple("E12", "kata sandi aplikasi abcd efgh ijkl mnop", "[REDAKSI: app-password, 16 karakter]"),
        Triple("E13", "api-key=rahasia saya yang panjang sekali kata", "api-key=[REDAKSI: api-key] sekali kata"),
        Triple("E14", "secret=abc, def", "secret=[REDAKSI: secret]"),
        Triple("E15", "", ""),
        Triple("E16", "token listrik bulan ini sudah dibayar", "token listrik bulan ini sudah dibayar"),
        Triple("E17", "Bearer abcdef [REDAKSI: bearer token]", "Bearer [REDAKSI: bearer token] [REDAKSI: Bearer [REDAKSI: bearer token]"),
        Triple("E18", "password${nbsp}=secret", "password${nbsp}=secret"),
        Triple("E19", "password=rahasia${nbsp}dan", "password=[REDAKSI: password]"),
        Triple("E20", "Password: Secret123", "Password=[REDAKSI: password]"),
        Triple("E21", "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg==\n-----END PRIVATE KEY-----", "[REDAKSI: kunci privat PEM]"),
        Triple("E22", "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----", "[REDAKSI: kunci privat PEM]"),
        Triple("E23", "sk-ant-abcdefghij1234567890", "[REDAKSI: api-key berprefiks]"),
        Triple("E24", "xoxb-1234567890ab", "[REDAKSI: api-key berprefiks]"),
        Triple("E25", "github_pat_abcdefghij1234567890abcd", "[REDAKSI: api-key berprefiks]"),
        Triple("E26", "AIza0123456789abcdefghijklmnopqrstuvwxy", "[REDAKSI: api-key berprefiks]"),
        Triple("E27", "AKIAABCDEFGHIJKLMNOP", "[REDAKSI: api-key berprefiks]"),
        Triple("E28", "password = rahasia123", "password=[REDAKSI: password]"),
        Triple("E29", "token:abc def ghi jkl mno pqr", "token=[REDAKSI: token] mno pqr"),
        Triple("E30", "[REDAKSI: app-password, 16 karakter]", "[REDAKSI: app-password, 16 karakter]"),
        Triple("E31", "laporan [REDAKSI] selesai", "laporan [REDAKSI] selesai"),
        Triple("E32", "Bearer", "Bearer"),
        Triple("E33", "Bearer ", "Bearer "),
        Triple("E34", "pass=ok", "pass=[REDAKSI: pass]"),
        Triple("E35", "client_secret=abc", "client_secret=[REDAKSI: client_secret]"),
        Triple("E36", "API-KEY=abc", "API-KEY=[REDAKSI: api-key]"),
        Triple("E37", "token: [REDAKSI: password]", "token=[REDAKSI: token]"),
        Triple("E38", "sandi aplikasi abcd efgh ijkl mnop", "[REDAKSI: app-password, 16 karakter]")
    )
    for ((label, input, expect) in extra) {
        val got = RedactSecrets.redact(input)
        check("3. ekstra [$label]", got == expect, "input=[$input] got=[$got] expect=[$expect]")
    }

    // ================= 4. Diskriminator lookahead F1–F5 =================
    val disc = listOf(
        Triple("F1", "password=\"[REDAKSI]\"", "password=[REDAKSI: password]"),
        Triple("F2", "Bearer [REDAKSI]", "Bearer [REDAKSI]"),
        Triple("F3", "password=[REDAKSI]", "password=[REDAKSI]"),
        Triple("F4", "token: \"[REDAKSI]\"", "token=[REDAKSI: token]"),
        Triple("F5", "password=\"[REDAKSI: x]\"", "password=[REDAKSI: password]")
    )
    for ((label, input, expect) in disc) {
        val got = RedactSecrets.redact(input)
        check("4. diskriminator [$label]", got == expect, "input=[$input] got=[$got] expect=[$expect]")
    }

    // ================= 5. API containsSecret / countMatches =================
    check("5.1 countMatches(password=...) == 1", RedactSecrets.countMatches("password=rahasia123") == 1)
    check(
        "5.2 countMatches(password+Bearer) == 1 (KEYVAL menelan marker Bearer)",
        RedactSecrets.countMatches("password=rahasia123 dan Bearer abcdef123456") == 1,
        "dapat=${RedactSecrets.countMatches("password=rahasia123 dan Bearer abcdef123456")}"
    )
    check(
        "5.3 countMatches(Bearer+password) == 2",
        RedactSecrets.countMatches("Bearer abcdef123456 dan password=rahasia123") == 2,
        "dapat=${RedactSecrets.countMatches("Bearer abcdef123456 dan password=rahasia123")}"
    )
    check("5.4 countMatches(tidak ada) == 0", RedactSecrets.countMatches("tidak ada apa-apa") == 0)
    check("5.5 countMatches(marker lama saja) == 0", RedactSecrets.countMatches("[REDAKSI: password]") == 0)
    check(
        "5.6 countMatches(marker lama + password baru) == 1",
        RedactSecrets.countMatches("laporan [REDAKSI: password] selesai password=rahasia123") == 1
    )
    check("5.7 containsSecret(token:) true", RedactSecrets.containsSecret("token: abcdef123456"))
    check("5.8 containsSecret(MD5) false", !RedactSecrets.containsSecret("md5 d41d8cd98f00b204e9800998ecf8427e"))
    check("5.9 containsSecret(\"\") false", !RedactSecrets.containsSecret(""))
    check("5.10 redact(\"\") == \"\"", RedactSecrets.redact("") == "")
    check(
        "5.11 redact(teks biasa) utuh",
        RedactSecrets.redact("halo dunia, tidak ada rahasia") == "halo dunia, tidak ada rahasia"
    )

    // ================= 6. Seam + fallback dipertahankan =================
    check("6.1 rustRedactSecrets default true", RedactSecrets.rustRedactSecrets)
    check("6.2 RustCore.VERSION == 8", RustCore.VERSION == 8, "VERSION=${RustCore.VERSION}")
    check(
        "6.3 fallback Kotlin dipertahankan (redactKotlin + countMatchesKotlin + saklar)",
        redactSrc.contains("private fun redactKotlin") && redactSrc.contains("private fun countMatchesKotlin") &&
            redactSrc.contains("var rustRedactSecrets")
    )
    check(
        "6.4 RustCore.kt: external fun redactSecrets(String): String?",
        rustCoreSrc.contains("external fun redactSecrets(input: String): String?")
    )
    check(
        "6.5 .so absen di JVM -> RustCore.available false (jalur fallback teruji)",
        !RustCore.available
    )
    val st = RedactSecrets.selfTest()
    check(
        "6.6 selfTest() 33/33 PASS (32 kasus + idempotence)",
        st.size == 33 && st.all { it.endsWith("PASS") },
        "dapat=$st"
    )

    println("\nTOTAL: $passed lulus, $failed gagal")
    if (failedNames.isNotEmpty()) println("GAGAL: " + failedNames.joinToString(", "))
    exitProcess(if (failed == 0) 0 else 1)
}
