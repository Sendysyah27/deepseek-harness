package com.senzwh.deepharness

import harness.core.RustCore
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * vc316 P1 — Pola 1 (taksonomi error, acuan §3.1 laporan
 * harness-ccc-integration-maturity-20261113-2330): SATU-SATUNYA tempat
 * klasifikasi kegagalan DeepSeek hidup. Dulu klasifikasi tersebar sebagai
 * substring-matching di DsApiClient.chat(), SlotClient.chat(), dan
 * BgBrain.handleError() — tiga salinan daftar sinyal yang sama, tanpa kode
 * HTTP, tanpa Retry-After, dan 5xx tidak pernah di-retry.
 *
 * Aturan (CCC FailureKind — RATE_LIMIT / OVERLOADED / TIMEOUT / UPSTREAM /
 * UNAVAILABLE / AUTH / CONTEXT_WINDOW):
 *  - 429 -> RATE_LIMIT (retryable; tangga 10/20/35 dtk, atau Retry-After
 *    sebagai jeda pertama bila header ada).
 *  - 5xx -> UPSTREAM (retryable, maks 2x, jeda pendek 2/5 dtk); 503 yang
 *    badannya menyebut overload -> OVERLOADED (retryable juga).
 *  - 401/403 -> AUTH (non-retryable).
 *  - 400/402/413 -> non-retryable (kind UPSTREAM, retryable=false — tidak
 *    ada kind CLIENT di enum CCC; retryable ditentukan pasangan
 *    (kind, code), bukan kind saja).
 *  - context-window-exceeded / input_exceeds_limit -> CONTEXT_WINDOW
 *    (non-retryable; sinyal memangkas prompt). "input_exceeds_limit"
 *    TERAMATI di lapangan 15 Nov 2026 sebagai finish_reason asli DeepSeek.
 *  - timeout/connect-error -> TIMEOUT / UNAVAILABLE (retryable 1x, jeda
 *    1,5 dtk — perilaku lama dipertahankan).
 *  - Suspend akun ("suspended"/"violation"/"40003"/"invalid_token"/
 *    "account_banned") -> AUTH (non-retryable); pemutus arus lokal
 *    (AccountPool.isDead pre-check di runChat) tetap gerbang pertama dan
 *    TIDAK diubah klasifikasi ini menjadi retryable.
 *
 * PARSIAL (jujur): daftar kode error SERVER DeepSeek yang lengkap BELUM
 * terkumpul (hanya yang teramati di kode/lapangan: "SERVER <code>" +
 * "input_exceeds_limit"). Pemetaan di bawah memakai kode HTTP + substring
 * yang SUDAH ADA di kode; jangan karang kode server lain. Bila
 * recordDiag (Mode Jhon) / log lapangan menemukan kode SERVER baru,
 * tambahkan di sini — bukan di pemanggil.
 *
 * Failover NONAKTIF (AccountPool.FAILOVER_ENABLED=false): klasifikasi ini
 * TIDAK berasumsi failover hidup; cabang failover di chat() tetap
 * melempar dengan suffix yang sama seperti sebelumnya.
 *
 * File ini murni Kotlin (tanpa Android) supaya bisa dites di JVM.
 */
enum class FailureKind {
    RATE_LIMIT,
    OVERLOADED,
    TIMEOUT,
    UPSTREAM,
    UNAVAILABLE,
    AUTH,
    CONTEXT_WINDOW
}

/** Hasil klasifikasi terstruktur (padanan CCC ExecutionFailure). */
data class ExecutionFailure(
    val kind: FailureKind,
    val statusCode: Int,
    val retryable: Boolean
)

object DsFailure {

    /**
     * Saklar A/B slice 1 (vc317): bila core Rust tersedia (.so ke-load
     * + versi seam cocok), klasifikasi jalan di Rust; bila tidak,
     * fallback ke implementasi Kotlin lama di bawah (classifyKotlin /
     * classifyFailureKotlin — JANGAN dihapus). Rust TIDAK dipanggil
     * di main thread (pemanggil sudah di worker thread).
     */
    var rustDsFailure: Boolean = true

    /** Tangga lama rate-limit (dipertahankan): 10 -> 20 -> 35 dtk. */
    val RATE_LIMIT_WAITS_MS = longArrayOf(10_000L, 20_000L, 35_000L)

    /** Jeda pendek retry 5xx (baru vc316): maks 2x. */
    val UPSTREAM_WAITS_MS = longArrayOf(2_000L, 5_000L)

    /** Jeda retry transien jaringan (lama, dipertahankan): sekali 1,5 dtk. */
    const val TRANSIENT_WAIT_MS = 1_500L

    /** Batas clamp Retry-After sebagai jeda pertama tangga (detik). */
    const val RETRY_AFTER_MIN_SEC = 1L
    const val RETRY_AFTER_MAX_SEC = 120L

    // ---- Sinyal substring (dulu tersebar di 3 file; kini hanya di sini) ----

    /** Sinyal rate-limit dari body/pesan (sudah ada di kode lama). */
    fun isRateLimitSignal(msg: String?): Boolean {
        val m = (msg ?: "").lowercase(Locale.US)
        return m.contains("rate_limit") || m.contains("too frequent")
    }

    /** Sinyal suspend/blokir akun (sudah ada di kode lama). */
    fun isSuspendSignal(msg: String?): Boolean {
        val m = (msg ?: "").lowercase(Locale.US)
        return m.contains("suspended") || m.contains("violation") || m.contains("40003") ||
            m.contains("invalid_token") || m.contains("account_banned")
    }

    /** Sinyal EKSPLISIT server bahwa sesi chat tidak berlaku lagi (lama). */
    fun isSessionInvalidSignal(msg: String?): Boolean {
        val m = (msg ?: "").lowercase(Locale.US)
        if (m.contains("session_not_found") || m.contains("invalid_session") || m.contains("session not found")) return true
        return m.contains("session") && (m.contains("not found") || m.contains("invalid") ||
            m.contains("not exist") || m.contains("does not exist") || m.contains("deleted") || m.contains("no such"))
    }

    /** Stream putus / selesai tanpa jawaban — penanda internal postCompletion. */
    fun isStreamCut(msg: String?): Boolean {
        val m = (msg ?: "").lowercase(Locale.US)
        return m.contains("stream_terputus") || m.contains("jawaban_kosong")
    }

    /** Sinyal timeout (sudah ada di kode lama). */
    fun isTimeoutSignal(msg: String?): Boolean {
        val m = (msg ?: "").lowercase(Locale.US)
        return m.contains("timeout") || m.contains("timed out")
    }

    /** Sinyal transien jaringan: gagal konek / DNS (sudah ada di kode lama). */
    fun isNetworkSignal(msg: String?): Boolean {
        val m = (msg ?: "").lowercase(Locale.US)
        return m.contains("failed to connect") || m.contains("resolve host") ||
            m.contains("no address associated") || m.contains("unknownhost")
    }

    /** Sinyal context-window (TERAMATI di lapangan + acuan CCC). PARSIAL:
     *  hanya dua bentuk ini yang terverifikasi; jangan tambah tebakan. */
    fun isContextWindowSignal(msg: String?): Boolean {
        val m = (msg ?: "").lowercase(Locale.US)
        return m.contains("context-window-exceeded") || m.contains("context_window_exceeded") ||
            m.contains("input_exceeds_limit")
    }

    /** Sinyal overload eksplisit di body (untuk 503 / pesan server). */
    fun isOverloadSignal(msg: String?): Boolean {
        val m = (msg ?: "").lowercase(Locale.US)
        return m.contains("overload")
    }

    // ---- Parsing kode HTTP + Retry-After dari pesan / header ----

    private val httpCodeRe = Regex("HTTP\\s+(\\d{3})")
    private val retryAfterRe = Regex("retry_after=(\\d+)")

    /** Ambil kode HTTP dari pesan "HTTP <code> di ..." (postJson/postCompletion
     *  menempel kode ke string sejak dulu). 0 = tidak ada kode di pesan. */
    fun parseHttpCode(message: String?): Int {
        if (message.isNullOrBlank()) return 0
        return httpCodeRe.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** Ambil detik Retry-After dari suffix " [retry_after=<n>]" yang ditempel
     *  postJson/postCompletion ke pesan error. null = tidak ada / tak terbaca. */
    fun parseRetryAfterSeconds(message: String?): Long? {
        if (message.isNullOrBlank()) return null
        val v = retryAfterRe.find(message)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return v.coerceIn(RETRY_AFTER_MIN_SEC, RETRY_AFTER_MAX_SEC)
    }

    /** Parse nilai mentah header Retry-After: detik ("120") atau tanggal
     *  HTTP-date (dihitung selisihnya ke sekarang). null bila tak terbaca.
     *  Hasil di-clamp 1..120 detik — server tidak boleh menahan giliran
     *  lebih lama dari itu dalam satu jeda tangga. */
    fun parseRetryAfterHeader(value: String?): Long? {
        val v = value?.trim() ?: return null
        if (v.isEmpty()) return null
        v.toLongOrNull()?.let { return it.coerceIn(RETRY_AFTER_MIN_SEC, RETRY_AFTER_MAX_SEC) }
        // Bentuk tanggal: "Wed, 21 Oct 2015 07:28:00 GMT".
        return try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            val date = fmt.parse(v) ?: return null
            val secs = (date.time - System.currentTimeMillis()) / 1000
            if (secs < 0) null else secs.coerceIn(RETRY_AFTER_MIN_SEC, RETRY_AFTER_MAX_SEC)
        } catch (e: Exception) {
            null
        }
    }

    /** Suffix yang ditempel postJson/postCompletion ke pesan error bila
     *  header Retry-After ada — supaya classify() di chat() bisa membacanya
     *  tanpa state bersama antar-thread. "" bila tidak ada. */
    fun retryAfterSuffix(retryAfterSec: Long?): String =
        if (retryAfterSec != null) " [retry_after=$retryAfterSec]" else ""

    /** Tangga rate-limit: Retry-After (bila ada) jadi jeda PERTAMA,
     *  diikuti 20/35 dtk; tanpa header = tangga lama 10/20/35 dtk. */
    fun rateLimitWaits(retryAfterSec: Long?): LongArray {
        if (retryAfterSec == null) return RATE_LIMIT_WAITS_MS.copyOf()
        val first = retryAfterSec.coerceIn(RETRY_AFTER_MIN_SEC, RETRY_AFTER_MAX_SEC) * 1000L
        return longArrayOf(first, 20_000L, 35_000L)
    }

    // ---- Seam Rust (vc317) ----

    private val rustKindRe = Regex("\"kind\":\"([A-Z_]+)\"")
    private val rustStatusRe = Regex("\"status_code\":(-?\\d+)")

    /** Parse JSON dari Rust `classifyFailureJson`. null = bukan hasil
     *  Rust yang valid (ok:false / bentuk tak dikenal) -> fallback. */
    private fun parseRustFailureJson(json: String): ExecutionFailure? {
        if (!json.contains("\"ok\":true")) return null
        val kindName = rustKindRe.find(json)?.groupValues?.getOrNull(1) ?: return null
        val kind = try {
            FailureKind.valueOf(kindName)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val code = rustStatusRe.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val retryable = json.contains("\"retryable\":true")
        return ExecutionFailure(kind, code, retryable)
    }

    /** Klasifikasi via Rust bila tersedia; null -> pakai fallback Kotlin.
     *  Exception Kotlin -> `exception.message ?: ""` saat menyeberang seam. */
    private fun rustClassifyFailure(code: Int, body: String, msg: String): ExecutionFailure? {
        if (!rustDsFailure || !RustCore.available) return null
        return try {
            parseRustFailureJson(RustCore.classifyFailureJson(code, body, msg))
        } catch (e: Throwable) {
            rustDsFailure = false
            null
        }
    }

    // ---- Klasifikasi inti ----

    /** Apakah (kind, code) ini boleh diulang? UPSTREAM hanya retryable
     *  bila code 5xx — 400/402/413 dipetakan ke UPSTREAM tapi TIDAK
     *  retryable (lihat classify). */
    fun isRetryable(kind: FailureKind, code: Int): Boolean = when (kind) {
        FailureKind.RATE_LIMIT -> true
        FailureKind.OVERLOADED -> true
        FailureKind.TIMEOUT -> true
        FailureKind.UNAVAILABLE -> true
        FailureKind.UPSTREAM -> code in 500..599
        FailureKind.AUTH -> false
        FailureKind.CONTEXT_WINDOW -> false
    }

    /**
     * Klasifikasi satu kegagalan. [code] = kode HTTP bila pemanggil
     * mengetahuinya (0 = tidak tahu — kode lalu dicari di pesan exception,
     * pola "HTTP <code>"). [body] = cuplikan body/pesan server bila ada
     * (boleh "" — exception.message dipakai sebagai fallback).
     * Urutan: context-window dulu (bisa datang dengan HTTP 200 di SSE),
     * lalu suspend, lalu kode HTTP, lalu sinyal substring di pesan.
     */
    fun classify(code: Int, body: String, exception: Exception): FailureKind {
        rustClassifyFailure(code, body, exception.message ?: "")?.let { return it.kind }
        return classifyKotlin(code, body, exception)
    }

    /** Implementasi Kotlin lama (fallback bila core Rust tidak tersedia).
     *  DIPERTAHANKAN — jangan dihapus. */
    private fun classifyKotlin(code: Int, body: String, exception: Exception): FailureKind {
        val msg = exception.message ?: ""
        val text = (body + " " + msg).lowercase(Locale.US)
        // 1. Context-window: bisa datang sebagai finish_reason di stream
        //    HTTP 200 (bukan error HTTP), jadi dicek sebelum kode.
        if (isContextWindowSignal(text)) return FailureKind.CONTEXT_WINDOW
        // 2. Suspend akun: non-retryable; runChat sudah menandai akun mati.
        if (isSuspendSignal(text)) return FailureKind.AUTH
        // 3. Kode HTTP (argumen, atau ter-parse dari pesan "HTTP <code>").
        val effCode = if (code != 0) code else parseHttpCode(msg)
        when (effCode) {
            429 -> return FailureKind.RATE_LIMIT
            401, 403 -> return FailureKind.AUTH
            400, 402, 413 -> return FailureKind.UPSTREAM // non-retryable (isRetryable=false untuk 4xx)
            in 500..599 -> return if (isOverloadSignal(text)) FailureKind.OVERLOADED else FailureKind.UPSTREAM
        }
        // 4. Sinyal substring di pesan (jalur lama, kini terpusat).
        if (isRateLimitSignal(text)) return FailureKind.RATE_LIMIT
        if (isOverloadSignal(text)) return FailureKind.OVERLOADED
        if (isTimeoutSignal(text)) return FailureKind.TIMEOUT
        if (isNetworkSignal(text)) return FailureKind.UNAVAILABLE
        // 5. Tidak dikenal: jangan retry membabi-buta.
        return FailureKind.UPSTREAM
    }

    /** classify() + statusCode efektif + retryable dalam satu paket. */
    fun classifyFailure(code: Int, body: String, exception: Exception): ExecutionFailure {
        rustClassifyFailure(code, body, exception.message ?: "")?.let { return it }
        return classifyFailureKotlin(code, body, exception)
    }

    /** Implementasi Kotlin lama (fallback) — DIPERTAHANKAN. */
    private fun classifyFailureKotlin(code: Int, body: String, exception: Exception): ExecutionFailure {
        val kind = classifyKotlin(code, body, exception)
        val effCode = if (code != 0) code else parseHttpCode(exception.message)
        return ExecutionFailure(kind, effCode, isRetryable(kind, effCode))
    }

    // ---- Keputusan BgBrain.handleError (murni, bisa dites JVM) ----

    /** Hasil keputusan handleError: COOLDOWN = rate-limit murni (tahan
     *  15 mnt, JANGAN tandai sesi mati); SESSION_DEAD = suspend/token
     *  (bgSessionDead=true + notif 7702 sekali per episode); GENERIC =
     *  gagal biasa (notif gagal per origin bila origin terjadwal). */
    enum class BgErrorAction { COOLDOWN, SESSION_DEAD, GENERIC }

    fun bgActionFor(e: Exception): BgErrorAction {
        val msg = e.message ?: ""
        val failure = classifyFailure(0, "", e)
        if (failure.kind == FailureKind.RATE_LIMIT && !isSuspendSignal(msg)) return BgErrorAction.COOLDOWN
        if (msg.contains("token", ignoreCase = true) || isSuspendSignal(msg)) return BgErrorAction.SESSION_DEAD
        return BgErrorAction.GENERIC
    }
}
