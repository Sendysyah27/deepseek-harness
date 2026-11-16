// TestDsFailureVc316.kt — tes JVM Worker P1 vc316 untuk Pola 1 (FailureKind).
//
// Yang diuji:
//  1. DsFailure.classify / classifyFailure: tiap kind (429, 5xx, 401/403,
//     400/402/413, CONTEXT_WINDOW incl. input_exceeds_limit, TIMEOUT,
//     UNAVAILABLE, suspend -> AUTH), termasuk parse kode HTTP dari pesan
//     "HTTP <code>" dan precedence context-window atas 400.
//  2. Retry-After: parse header (detik + HTTP-date + clamp + tak terbaca),
//     suffix "[retry_after=<n>]" di pesan, rateLimitWaits (fallback
//     10/20/35 dtk vs Retry-After sebagai jeda pertama).
//  3. BgBrain cooldown vs sesi-mati: DsFailure.bgActionFor + cek sumber
//     handleError (COOLDOWN tidak menyentuh bgSessionDead / notif 7702).
//  4. SlotClient memakai classify yang sama (cek sumber: tanpa
//     m.contains duplikat, memanggil DsFailure).
//  5. Regresi tangga lama: backoff 10/20/35, retry transien 1x (1,5 dtk),
//     stream-putus 2x + 1 sesi baru, Governor bump sekali per giliran,
//     isDead pre-check tetap gerbang pertama, failover tetap NONAKTIF.
//
// DsFailure.kt murni Kotlin (tanpa Android) — dites langsung di JVM.
// Perilaku chat()/handleError() yang butuh WebView/Context HANYA bisa
// dibuktikan di perangkat; di sini yang diuji adalah keputusan murni
// (DsFailure) + struktur sumber pemanggilnya (cek teks file kerja).
//
// Jalankan: ./run-tests-vc316-P1.sh
// Keluar 0 = semua lulus; selain itu daftar GAGAL + exit 1.
package com.senzwh.deepharness

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

fun ex(msg: String) = Exception(msg)

fun main(args: Array<String>) {
    val workDir = if (args.isNotEmpty()) args[0] else "."
    val dsApi = File(workDir, "DsApiClient.kt").readText()
    val slot = File(workDir, "SlotClient.kt").readText()
    val bg = File(workDir, "BgBrain.kt").readText()
    val dsFailureSrc = File(workDir, "DsFailure.kt").readText()
    val accountPool = File(File(workDir).parentFile, "merge/AccountPool.kt").readText()

    // ================= 1. Klasifikasi tiap kind =================
    println("== 1. classify / classifyFailure ==")
    // 1.1 429 -> RATE_LIMIT, retryable
    run {
        val f = DsFailure.classifyFailure(429, "", ex("HTTP 429 di completion: too many requests"))
        check("1.1 429 -> RATE_LIMIT retryable", f.kind == FailureKind.RATE_LIMIT && f.retryable && f.statusCode == 429, "$f")
    }
    // 1.2 429 via pesan saja (code arg 0, parse "HTTP <code>")
    run {
        val f = DsFailure.classifyFailure(0, "", ex("HTTP 429 di /api/v0/chat/completion: Messages too frequent"))
        check("1.2 HTTP 429 di pesan -> RATE_LIMIT", f.kind == FailureKind.RATE_LIMIT && f.retryable && f.statusCode == 429, "$f")
    }
    // 1.3 Sinyal body SSE rate_limit tanpa kode HTTP
    run {
        val f = DsFailure.classifyFailure(0, "", ex("rate_limit_reached — server DeepSeek membatasi laju permintaan (Messages too frequent); stream berhenti tanpa jawaban final."))
        check("1.3 rate_limit body SSE -> RATE_LIMIT", f.kind == FailureKind.RATE_LIMIT && f.retryable, "$f")
    }
    // 1.4 5xx -> UPSTREAM, retryable
    for (code in listOf(500, 502, 503, 504)) {
        val f = DsFailure.classifyFailure(code, "", ex("HTTP $code di completion: boom"))
        check("1.4 HTTP $code -> UPSTREAM retryable", f.kind == FailureKind.UPSTREAM && f.retryable && f.statusCode == code, "$f")
    }
    // 1.5 503 + overload -> OVERLOADED, retryable
    run {
        val f = DsFailure.classifyFailure(503, "the server is overloaded, try again later", ex("HTTP 503 di completion: overloaded"))
        check("1.5 503 overload -> OVERLOADED retryable", f.kind == FailureKind.OVERLOADED && f.retryable, "$f")
    }
    // 1.6 401/403 -> AUTH, non-retryable
    for (code in listOf(401, 403)) {
        val f = DsFailure.classifyFailure(code, "", ex("HTTP $code di completion: unauthorized"))
        check("1.6 HTTP $code -> AUTH non-retryable", f.kind == FailureKind.AUTH && !f.retryable, "$f")
    }
    // 1.7 Suspend -> AUTH (bukan retryable), semua varian sinyal lama
    for (sig in listOf("suspended", "violation", "40003", "invalid_token", "account_banned")) {
        val f = DsFailure.classifyFailure(0, "", ex("SERVER $sig: akun bermasalah"))
        check("1.7 suspend '$sig' -> AUTH non-retryable", f.kind == FailureKind.AUTH && !f.retryable, "$f")
    }
    run {
        val f = DsFailure.classifyFailure(0, "", ex("SERVER 40003: account suspended"))
        check("1.7b SERVER 40003 -> AUTH", f.kind == FailureKind.AUTH && !f.retryable, "$f")
    }
    // 1.8 400/402/413 -> non-retryable
    for (code in listOf(400, 402, 413)) {
        val f = DsFailure.classifyFailure(code, "", ex("HTTP $code di completion: bad request"))
        check("1.8 HTTP $code -> non-retryable", !f.retryable, "$f")
    }
    // 1.9 CONTEXT_WINDOW: input_exceeds_limit (TERAMATI lapangan 15 Nov 2026)
    run {
        val f = DsFailure.classifyFailure(0, "{\"finish_reason\":\"input_exceeds_limit\"}", ex("jawaban_kosong: stream selesai resmi tapi tanpa fragmen jawaban"))
        check("1.9 input_exceeds_limit -> CONTEXT_WINDOW non-retryable", f.kind == FailureKind.CONTEXT_WINDOW && !f.retryable, "$f")
    }
    run {
        val f = DsFailure.classifyFailure(0, "", ex("context-window-exceeded: prompt too long"))
        check("1.9b context-window-exceeded -> CONTEXT_WINDOW", f.kind == FailureKind.CONTEXT_WINDOW && !f.retryable, "$f")
    }
    run {
        val f = DsFailure.classifyFailure(0, "", ex("context_window_exceeded"))
        check("1.9c context_window_exceeded -> CONTEXT_WINDOW", f.kind == FailureKind.CONTEXT_WINDOW && !f.retryable, "$f")
    }
    // 1.10 CONTEXT_WINDOW menang atas 400 (precedence)
    run {
        val f = DsFailure.classifyFailure(400, "input_exceeds_limit", ex("HTTP 400 di completion: input_exceeds_limit"))
        check("1.10 400 + input_exceeds_limit -> CONTEXT_WINDOW", f.kind == FailureKind.CONTEXT_WINDOW && !f.retryable, "$f")
    }
    // 1.11 TIMEOUT
    for (msg in listOf("Read timed out", "timeout after 20000ms", "SocketTimeoutException: timeout")) {
        val f = DsFailure.classifyFailure(0, "", ex(msg))
        check("1.11 timeout '$msg' -> TIMEOUT retryable", f.kind == FailureKind.TIMEOUT && f.retryable, "$f")
    }
    // 1.12 UNAVAILABLE (transien jaringan — sinyal lama)
    for (msg in listOf(
        "failed to connect to chat.deepseek.com",
        "Unable to resolve host \"chat.deepseek.com\"",
        "UnknownHostException: chat.deepseek.com",
        "No address associated with hostname"
    )) {
        val f = DsFailure.classifyFailure(0, "", ex(msg))
        check("1.12 jaringan '$msg' -> UNAVAILABLE retryable", f.kind == FailureKind.UNAVAILABLE && f.retryable, "$f")
    }
    // 1.13 Stream-cut BUKAN kind retryable (ditangani cabang sendiri di chat)
    run {
        val cut = DsFailure.isStreamCut("stream_terputus: koneksi putus") && DsFailure.isStreamCut("jawaban_kosong: stream selesai resmi")
        check("1.13 isStreamCut kenal kedua penanda", cut)
        val f = DsFailure.classifyFailure(0, "", ex("stream_terputus: koneksi putus"))
        check("1.13b stream_terputus tidak dikira RATE_LIMIT", f.kind != FailureKind.RATE_LIMIT, "$f")
    }
    // 1.14 Tidak dikenal -> tidak retry membabi-buta
    run {
        val f = DsFailure.classifyFailure(0, "", ex("token sesi tidak ditemukan di WebView (userToken kosong)."))
        check("1.14 error tak dikenal -> non-retryable", !f.retryable, "$f")
    }
    // 1.15 parseHttpCode
    check("1.15 parseHttpCode 429", DsFailure.parseHttpCode("HTTP 429 di completion: x") == 429)
    check("1.15b parseHttpCode tanpa kode = 0", DsFailure.parseHttpCode("rate_limit_reached") == 0)
    check("1.15c parseHttpCode null = 0", DsFailure.parseHttpCode(null) == 0)
    // 1.16 Semua kind enum ada (7, sesuai CCC)
    run {
        val kinds = FailureKind.values().map { it.name }.toSet()
        check("1.16 enum 7 kind CCC", kinds == setOf("RATE_LIMIT", "OVERLOADED", "TIMEOUT", "UPSTREAM", "UNAVAILABLE", "AUTH", "CONTEXT_WINDOW"), "$kinds")
    }

    // ================= 2. Retry-After =================
    println("== 2. Retry-After ==")
    check("2.1 header detik 30", DsFailure.parseRetryAfterHeader("30") == 30L)
    check("2.2 header 0 -> clamp 1", DsFailure.parseRetryAfterHeader("0") == 1L)
    check("2.3 header 600 -> clamp 120", DsFailure.parseRetryAfterHeader("600") == 120L)
    check("2.4 header null -> null", DsFailure.parseRetryAfterHeader(null) == null)
    check("2.5 header sampah -> null", DsFailure.parseRetryAfterHeader("nanti dulu") == null)
    run {
        // HTTP-date 60 detik ke depan -> ~60 (toleransi 55..60)
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        val future = fmt.format(Date(System.currentTimeMillis() + 60_000))
        val v = DsFailure.parseRetryAfterHeader(future)
        check("2.6 header HTTP-date masa depan ~60", v != null && v in 55..60, "dapat $v dari $future")
    }
    run {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        val past = fmt.format(Date(System.currentTimeMillis() - 60_000))
        check("2.7 header HTTP-date masa lalu -> null", DsFailure.parseRetryAfterHeader(past) == null)
    }
    check("2.8 parseRetryAfterSeconds dari suffix", DsFailure.parseRetryAfterSeconds("HTTP 429 di completion: x [retry_after=45]") == 45L)
    check("2.9 parseRetryAfterSeconds tanpa suffix -> null", DsFailure.parseRetryAfterSeconds("HTTP 429 di completion: x") == null)
    check("2.10 retryAfterSuffix(30)", DsFailure.retryAfterSuffix(30L) == " [retry_after=30]")
    check("2.11 retryAfterSuffix(null) kosong", DsFailure.retryAfterSuffix(null) == "")
    run {
        val w = DsFailure.rateLimitWaits(null)
        check("2.12 rateLimitWaits(null) = tangga lama 10/20/35", w.contentEquals(longArrayOf(10_000, 20_000, 35_000)), w.joinToString())
    }
    run {
        val w = DsFailure.rateLimitWaits(45L)
        check("2.13 rateLimitWaits(45) = [45,20,35] dtk", w.contentEquals(longArrayOf(45_000, 20_000, 35_000)), w.joinToString())
    }
    run {
        val w = DsFailure.rateLimitWaits(300L)
        check("2.14 rateLimitWaits(300) clamp jeda pertama 120 dtk", w[0] == 120_000L && w[1] == 20_000L && w[2] == 35_000L, w.joinToString())
    }
    // 2.15 Pemanggil membaca header Retry-After (cek sumber)
    check("2.15 DsApiClient baca Retry-After", dsApi.contains("getHeaderField(\"Retry-After\")") && dsApi.contains("parseRetryAfterHeader"))
    check("2.16 SlotClient baca Retry-After", slot.contains("getHeaderField(\"Retry-After\")") && slot.contains("parseRetryAfterHeader"))
    check("2.17 DsApiClient tempel suffix retry_after", dsApi.contains("retryAfterSuffix(retryAfterSec)"))
    check("2.18 SlotClient tempel suffix retry_after", slot.contains("retryAfterSuffix(retryAfterSec)"))
    check("2.19 DsApiClient tangga pakai rateLimitWaits", dsApi.contains("DsFailure.rateLimitWaits(DsFailure.parseRetryAfterSeconds(m))"))
    check("2.20 SlotClient tangga pakai rateLimitWaits", slot.contains("DsFailure.rateLimitWaits(DsFailure.parseRetryAfterSeconds(m))"))

    // ================= 3. BgBrain cooldown vs sesi-mati =================
    println("== 3. BgBrain cooldown vs sesi-mati ==")
    check("3.1 rate_limit murni -> COOLDOWN", DsFailure.bgActionFor(ex("rate_limit_reached — server membatasi laju")) == DsFailure.BgErrorAction.COOLDOWN)
    check("3.2 HTTP 429 -> COOLDOWN", DsFailure.bgActionFor(ex("HTTP 429 di completion: too frequent [retry_after=30]")) == DsFailure.BgErrorAction.COOLDOWN)
    check("3.3 too frequent -> COOLDOWN", DsFailure.bgActionFor(ex("Messages too frequent")) == DsFailure.BgErrorAction.COOLDOWN)
    check("3.4 suspended -> SESSION_DEAD", DsFailure.bgActionFor(ex("account suspended")) == DsFailure.BgErrorAction.SESSION_DEAD)
    check("3.5 SERVER 40003 -> SESSION_DEAD", DsFailure.bgActionFor(ex("SERVER 40003: account suspended")) == DsFailure.BgErrorAction.SESSION_DEAD)
    check("3.6 token hilang -> SESSION_DEAD", DsFailure.bgActionFor(ex("token sesi tidak ditemukan di WebView")) == DsFailure.BgErrorAction.SESSION_DEAD)
    check("3.7 HTTP 500 -> GENERIC (bukan cooldown/dead)", DsFailure.bgActionFor(ex("HTTP 500 di completion: boom")) == DsFailure.BgErrorAction.GENERIC)
    check("3.8 timeout -> GENERIC", DsFailure.bgActionFor(ex("Read timed out")) == DsFailure.BgErrorAction.GENERIC)
    check("3.9 rate_limit + suspended -> SESSION_DEAD (suspend menang)", DsFailure.bgActionFor(ex("rate_limit + account suspended")) == DsFailure.BgErrorAction.SESSION_DEAD)
    check("3.10 CONTEXT_WINDOW -> GENERIC", DsFailure.bgActionFor(ex("input_exceeds_limit")) == DsFailure.BgErrorAction.GENERIC)
    // 3.11 Struktur handleError (cek sumber)
    run {
        val h = bg.substring(bg.indexOf("private fun handleError"))
        check("3.11 handleError pakai bgActionFor", h.contains("DsFailure.bgActionFor(e)"))
        check("3.12 handleError tanpa m.contains deteksi", !h.contains("msg.contains(") && !h.contains("m.contains("))
        val coolBlock = h.substring(h.indexOf("BgErrorAction.COOLDOWN"), h.indexOf("BgErrorAction.SESSION_DEAD"))
        check("3.13 COOLDOWN catat bgLastRateLimitTs", coolBlock.contains("bgLastRateLimitTs"))
        check("3.14 COOLDOWN tanpa bgSessionDead", !coolBlock.contains("bgSessionDead"))
        check("3.15 COOLDOWN tanpa notif 7702", !coolBlock.contains("7702"))
        check("3.16 COOLDOWN tetap antrekan tugas", coolBlock.contains("enqueuePrompt"))
        val deadBlock = h.substring(h.indexOf("BgErrorAction.SESSION_DEAD"))
        check("3.17 SESSION_DEAD set bgSessionDead=true", deadBlock.contains("putBoolean(\"bgSessionDead\", true)"))
        check("3.18 SESSION_DEAD notif 7702 sekali per episode", deadBlock.contains("notify(ctx, 7702") && deadBlock.contains("firstTime"))
    }

    // ================= 4. SlotClient memakai classify yang sama =================
    println("== 4. SlotClient = classify yang sama ==")
    check("4.1 SlotClient panggil classifyFailure", slot.contains("DsFailure.classifyFailure(0, \"\", e)"))
    check("4.2 SlotClient tanpa m.contains duplikat", !slot.contains("m.contains(") && !slot.contains("m2.contains("))
    check("4.3 SlotClient tanpa val rateLimited/transient lokal", !slot.contains("val rateLimited") && !slot.contains("val transient"))
    check("4.4 SlotClient isSuspendSignal delegasi", slot.contains("private fun isSuspendSignal(msg: String?): Boolean = DsFailure.isSuspendSignal(msg)"))
    check("4.5 SlotClient isSessionInvalidSignal delegasi", slot.contains("private fun isSessionInvalidSignal(msg: String?): Boolean = DsFailure.isSessionInvalidSignal(msg)"))
    check("4.6 SlotClient cabang UPSTREAM 2x jeda pendek", slot.contains("DsFailure.UPSTREAM_WAITS_MS") && slot.contains("FailureKind.UPSTREAM, FailureKind.OVERLOADED"))
    check("4.7 SlotClient tanpa failover (pilihan slot user)", !slot.contains("AccountPool.failover") && slot.contains("TIDAK ADA failover"))
    check("4.8 SlotClient tetap tanpa Governor bump ganda (1x di chat)", slot.count("Governor.bump") >= 1 && slot.contains("Governor.weightFor(prompt.length)"))

    // ================= 5. Regresi tangga lama =================
    println("== 5. Regresi tangga lama ==")
    check("5.1 RATE_LIMIT_WAITS = 10/20/35 dtk", DsFailure.RATE_LIMIT_WAITS_MS.contentEquals(longArrayOf(10_000, 20_000, 35_000)))
    check("5.2 UPSTREAM_WAITS = 2/5 dtk", DsFailure.UPSTREAM_WAITS_MS.contentEquals(longArrayOf(2_000, 5_000)))
    check("5.3 TRANSIENT_WAIT = 1500 ms", DsFailure.TRANSIENT_WAIT_MS == 1_500L)
    // DsApiClient
    check("5.4 DsApiClient tanpa m.contains di chat", !dsApi.contains("m.contains(") && !dsApi.contains("m2.contains("))
    check("5.5 DsApiClient tanpa val rateLimited/transient lokal", !dsApi.contains("val rateLimited") && !dsApi.contains("val transient"))
    check("5.6 DsApiClient panggil classifyFailure", dsApi.contains("val failure = DsFailure.classifyFailure(0, \"\", e)"))
    check("5.7 DsApiClient stream-putus jeda 2/5 dtk (reviewer) + 2/5/15 dtk (non-reviewer, vc327)", dsApi.contains("longArrayOf(2_000, 5_000)") && dsApi.contains("longArrayOf(2_000, 5_000, 15_000)"))
    check("5.8 DsApiClient 1 sesi baru sesudah putus berulang (vc327: maks 1 per 10 mnt)", dsApi.contains("forceFresh = true, bgMode = bgMode") && dsApi.contains("coba SATU sesi server baru"))
    check("5.9 DsApiClient cabang UPSTREAM 2x jeda pendek", dsApi.contains("DsFailure.UPSTREAM_WAITS_MS") && dsApi.contains("FailureKind.UPSTREAM, FailureKind.OVERLOADED"))
    check("5.10 DsApiClient transien 1x jeda 1,5 dtk", dsApi.contains("Thread.sleep(DsFailure.TRANSIENT_WAIT_MS)"))
    check("5.11 DsApiClient suspend -> failover sekali + suffix", dsApi.contains("AccountPool.failover(webView.context)") && dsApi.contains("[failover antar slot dinonaktifkan"))
    check("5.12 DsApiClient AUTH/CONTEXT_WINDOW dilempar (else -> throw e)", dsApi.contains("else -> throw e // AUTH / CONTEXT_WINDOW: non-retryable."))
    check("5.13 DsApiClient isDead pre-check tetap gerbang pertama", dsApi.contains("if (AccountPool.isDead(ctx, fp)) throw Exception(\"AKUN DITANDAI TIDAK AKTIF"))
    check("5.14 DsApiClient isSuspendSignal delegasi", dsApi.contains("private fun isSuspendSignal(msg: String?): Boolean = DsFailure.isSuspendSignal(msg)"))
    check("5.15 DsApiClient isSessionInvalidSignal delegasi", dsApi.contains("private fun isSessionInvalidSignal(msg: String?): Boolean = DsFailure.isSessionInvalidSignal(msg)"))
    // Governor tetap bump sekali per giliran (jangan ubah)
    run {
        // Satu BARIS bump (bump/bumpBg/bumpReviewer = satu panggilan
        // bercabang, bukan tiga bump) + satu weightFor per chat().
        val chatRegion = dsApi.substring(dsApi.indexOf("fun chat("), dsApi.indexOf("private fun runChat("))
        val bumpLines = chatRegion.lines().count { it.contains("Governor.bump") }
        val weights = chatRegion.split("Governor.weightFor(prompt.length)").size - 1
        check("5.16 Governor bump tetap 1x di chat() DsApiClient", bumpLines == 1 && weights == 1, "bumpLines=$bumpLines weights=$weights")
    }
    run {
        val chatRegion = slot.substring(slot.indexOf("fun chat("), slot.indexOf("fun chatSync("))
        val bumpLines = chatRegion.lines().count { it.contains("Governor.bump") }
        val weights = chatRegion.split("Governor.weightFor(prompt.length)").size - 1
        check("5.17 Governor bump tetap 1x di chat() SlotClient", bumpLines == 1 && weights == 1, "bumpLines=$bumpLines weights=$weights")
    }
    // Failover tetap NONAKTIF
    check("5.18 FAILOVER_ENABLED=false (AccountPool baseline)", accountPool.contains("FAILOVER_ENABLED = false"))
    // DsFailure.kt sendiri: satu tempat sinyal
    check("5.19 DsFailure.kt ada + enum + classify", dsFailureSrc.contains("enum class FailureKind") && dsFailureSrc.contains("fun classify(") && dsFailureSrc.contains("data class ExecutionFailure"))
    check("5.20 DsFailure.kt catat PARSIAL", dsFailureSrc.contains("PARSIAL"))

    println("\nTOTAL: $passed lulus, $failed gagal")
    if (failedNames.isNotEmpty()) println("GAGAL: " + failedNames.joinToString(", "))
    exitProcess(if (failed == 0) 0 else 1)
}

// Helper hitung kemunculan substring (dipakai cek 4.8).
fun String.count(sub: String): Int = this.split(sub).size - 1
