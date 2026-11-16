// TestServer4198Vc325 — P11 (slice 9, vc325): paritas seam endpoint :4198.
//
// Mode: TestServer4198Vc325Kt <rust|kotlin> <treeDir> <coreDir>
//   rust   = jalur Rust ASLI via .so host (-Djava.library.path=.../target/debug)
//   kotlin = fallback *Kotlin, tanpa .so (RustCore.available=false)
//   treeDir = direktori .kt terpadu (integrated / app/.../deepharness)
//   coreDir = harness-core (untuk baca jni_glue.rs + Cargo.toml)
//
// Yang diuji (diferensial: ekspektasi SAMA di kedua mode — hasil Rust
// dan hasil Kotlin lama harus identik):
//   (a) SEAM LANGSUNG via WorkerEndpointSeam (mode rust) + fallback
//       (mode kotlin): parseRoute (matriks semua aksi + 405/404 +
//       quirk URL-decode), parseQuery, bearerToken, isAuthorized,
//       isSafeName, slicePreview (satuan UTF-16 + astral),
//       previewChars, HMAC antrean (vektor RFC 4231 + round-trip),
//       workerScoped, parseHeaders, buildResponse, idempotencyKey.
//   (b) WRAPPER PUBLIK `WorkerEndpointLogic.*` (dipakai WorkerEndpoint
//       + WorkerQueue) — hasil identik di kedua mode.
//   (c) FALLBACK: mode kotlin (tanpa .so) -> seam null + wrapper tetap
//       bekerja lewat *Kotlin; saklar rustWorkerEndpoint false.
//   (d) SUMBER: *Kotlin identik merge/ vc316 (funBody), loop server
//       hidup + dispatch + token/SecureStore tetap Kotlin, file peta
//       lain (WorkerLogic/WorkerQueue/WorkerJanitorLogic/
//       WorkerRegistry) tanpa Rust, Flavor.kt + DsApiClient.kt tanpa
//       sentuhan slice 9.
//   (e) STRUKTUR: RUST_CORE_VERSION 7 + 27 simbol JNI +
//       workerEndpointJson + modul worker_endpoint, TANPA tokio dan
//       TANPA dependensi Cargo baru.
//
// CATATAN JUJUR: server hidup :4198 TIDAK diuji end-to-end di JVM —
// `WorkerEndpoint.start(ctx)` butuh Context/SecureStore Android +
// socket loopback JVM dicegat sandbox VM (pelajaran P10 slice 8:
// `Other TCP connections is turned off`). Server hidup (worker hidup
// + token + kuota + respons error) menjadi bagian TES perangkat
// (TES-VC325). Yang dibuktikan P11: seluruh logika murni + framing
// wire yang menentukan kontrak byte-identik server itu. P11 juga
// TIDAK memakai ServerSocket lokal (tidak ada yang bisa diuji
// melaluinya tanpa server Kotlin yang hidup).
//
// CATATAN ASTRAL: `slicePreview("a😀b", 2)` — Kotlin `take(2)`
// mengembalikan "a" + lone high surrogate (string JVM); seam Rust
// mereplikanya dengan menulis escape JSON `\ud83d` mentah sehingga
// org.json mem-parse-nya kembali jadi lone surrogate yang sama
// (bukan U+FFFD — probe JVM membuktikan `toByteArray(UTF_8)` =
// `61 3F` dan `JSONObject.toString()` = `{"content":"a?"}`).
// Cek §7 membandingkan byte UTF-8 (`61 3F`) di kedua mode —
// itulah kontrak wire yang benar.

package com.senzwh.deepharness

import org.json.JSONObject

private var total = 0
private var lulus = 0
private var gagal = 0

private fun cek(nama: String, ok: Boolean) {
    total++
    if (ok) {
        lulus++
        println("LULUS $nama")
    } else {
        gagal++
        println("GAGAL $nama")
    }
}

private fun src(treeDir: String, name: String): String {
    val f = java.io.File(treeDir, name)
    return if (f.exists()) f.readText() else ""
}

private fun srcCore(coreDir: String, rel: String): String {
    val f = java.io.File(coreDir, rel)
    return if (f.exists()) f.readText() else ""
}

/** Ambil body fungsi (dari `{` pertama sesudah [sig] sampai `}` penutup),
 *  dengan scanner yang melewati string "..." (escape-aware) dan komentar
 *  `//` supaya `{` di dalam literal tidak mengacaukan hitungan kurung.
 *  "" = signature tidak ketemu / tak-berakhir. */
private fun funBody(srcText: String, sig: String): String {
    val i = srcText.indexOf(sig)
    if (i < 0) return ""
    val open = srcText.indexOf('{', i)
    if (open < 0) return ""
    var depth = 0
    var j = open
    var inStr = false
    var inLineComment = false
    while (j < srcText.length) {
        val c = srcText[j]
        if (inLineComment) {
            if (c == '\n') inLineComment = false
            j++
            continue
        }
        if (inStr) {
            if (c == '\\') { j += 2; continue }
            if (c == '"') inStr = false
            j++
            continue
        }
        if (c == '/' && j + 1 < srcText.length && srcText[j + 1] == '/') { inLineComment = true; j += 2; continue }
        if (c == '"') { inStr = true; j++; continue }
        if (c == '{') depth++
        if (c == '}') {
            depth--
            if (depth == 0) return srcText.substring(open, j + 1)
        }
        j++
    }
    return ""
}

/** Dua fungsi identik isinya (nama fungsi boleh beda)? Body dibandingkan
 *  apa adanya setelah normalisasi spasi. */
private fun sameFunBody(aText: String, aSig: String, bText: String, bSig: String): Boolean {
    val a = funBody(aText, aSig)
    val b = funBody(bText, bSig)
    if (a.isEmpty() || b.isEmpty()) return false
    fun norm(s: String) = s.split(Regex("\\s+")).joinToString(" ")
    return norm(a) == norm(b)
}

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "kotlin"
    val treeDir = args.getOrNull(1) ?: "."
    val coreDir = args.getOrNull(2) ?: "harness-core"
    val rust = mode == "rust"
    println("== TestServer4198Vc325 mode=$mode treeDir=$treeDir coreDir=$coreDir ==")
    // merge/ vc316 (baseline) — lokal ada via work-impl/merge (symlink);
    // di CI ada via symlink run-suite-ci.sh; bila tidak ada, cek
    // identitas dilewati (SKIP, bukan lulus).
    val mergeDir = java.io.File(treeDir, "../merge")
    val logicSrc = src(treeDir, "WorkerEndpointLogic.kt")
    val epSrc = src(treeDir, "WorkerEndpoint.kt")
    val seamSrc = src(treeDir, "WorkerEndpointSeam.kt")

    // ---- §1. Seam + VERSION ----
    // RustCore.kt TIDAK di bawah treeDir di semua layout: lokal
    // (integrated) = treeDir/harness/core/RustCore.kt; CI =
    // treeDir naik-3 (deepharness -> senzwh -> com -> java) lalu
    // harness/core/RustCore.kt. Coba 4 tingkat (akar yang sama
    // dengan P8 5.18 vc322 dan P9 12.7 vc323, P10 1.1 vc324).
    val rcSrc = listOf(
        "harness/core/RustCore.kt",
        "../harness/core/RustCore.kt",
        "../../harness/core/RustCore.kt",
        "../../../harness/core/RustCore.kt"
    ).mapNotNull { rel ->
        val f = java.io.File(treeDir, rel)
        if (f.exists()) f.readText() else null
    }.firstOrNull() ?: ""
    cek("1.1 RustCore.kt VERSION 7 + workerEndpointJson", rcSrc.contains("const val VERSION = 7") && rcSrc.contains("external fun workerEndpointJson(request: String): String?"))
    if (rust) {
        cek("1.2 RustCore.available true (mode rust, .so host)", harness.core.RustCore.available)
        val caps = harness.core.RustCore.rustCoreCapabilities()
        cek("1.3 capabilities: version 7 + workerEndpointJson + modul worker_endpoint", caps.contains("\"version\":7") && caps.contains("workerEndpointJson") && caps.contains("\"worker_endpoint\""))
        cek("1.4 WorkerEndpointSeam.rustWorkerEndpoint true (mode rust)", WorkerEndpointSeam.rustWorkerEndpoint)
        val glue = srcCore(coreDir, "src/jni_glue.rs")
        val nSym = glue.split("pub extern \"C\" fn Java_harness_core_RustCore_").size - 1
        cek("1.5 27 simbol JNI (26 lama + workerEndpointJson)", nSym == 27)
    } else {
        cek("1.2 RustCore.available false (mode kotlin, tanpa .so)", !harness.core.RustCore.available)
        cek("1.3 WorkerEndpointSeam.rustWorkerEndpoint false (mode kotlin)", !WorkerEndpointSeam.rustWorkerEndpoint)
        cek("1.4 seam parseRoute null tanpa .so (kontrak fallback)", WorkerEndpointSeam.parseRoute("GET", "/health") == null)
        cek("1.5 seam buildResponseHead null tanpa .so", WorkerEndpointSeam.buildResponseHead(200, 0, null) == null)
    }

    // ---- §2. parseRoute — matriks semua aksi ----
    data class RCase(val m: String, val p: String, val a: String?, val w: String = "", val n: String = "")
    val routes = listOf(
        RCase("GET", "/health", "health"),
        RCase("GET", "/health?x=1", "health"),
        RCase("POST", "/health", null),
        RCase("GET", "/workers", "listWorkers"),
        RCase("get", "/workers", "listWorkers"),
        RCase("POST", "/workers/spawn", "spawnWorker"),
        RCase("GET", "/workers/w_1", "workerDetail", "w_1"),
        RCase("GET", "/workers/spawn", "workerDetail", "spawn"),
        RCase("POST", "/workers/w_1/stop", "stopWorker", "w_1"),
        RCase("POST", "/workers/w_1/restart", "restartWorker", "w_1"),
        RCase("POST", "/workers/w_1/quota", "setQuota", "w_1"),
        RCase("POST", "/workers/w_1/reset", "resetWorker", "w_1"),
        RCase("POST", "/workers/w_1/verify-task", "verifyTask", "w_1"),
        RCase("GET", "/workers/w_1/log", "workerLog", "w_1"),
        RCase("GET", "/workers/w_1/tasks", "listTasks", "w_1"),
        RCase("POST", "/workers/w_1/tasks", "enqueueTask", "w_1"),
        RCase("GET", "/workers/w_1/results", "listResults", "w_1"),
        RCase("GET", "/workers/w_1/tasks/task-1.md", "readTask", "w_1", "task-1.md"),
        RCase("GET", "/workers/w_1/results/task-1.md.result.md", "readResult", "w_1", "task-1.md.result.md"),
        RCase("POST", "/workers/w_1/tasks/task-1.md/result", "writeTaskResult", "w_1", "task-1.md"),
        RCase("GET", "/queue/jhon/tasks", "jhonListTasks"),
        RCase("POST", "/queue/jhon/tasks", "jhonEnqueue"),
        RCase("GET", "/queue/jhon/results", "jhonListResults"),
        RCase("GET", "/queue/jhon/tasks/task-9.md", "jhonReadTask", "", "task-9.md"),
        RCase("POST", "/queue/jhon/tasks/task-9.md/result", "jhonWriteResult", "", "task-9.md"),
        RCase("DELETE", "/workers", null),
        RCase("GET", "/workers/w_1/stop", null),
        RCase("POST", "/workers/w_1/log", null),
        RCase("GET", "/tidak-ada", null),
        RCase("GET", "/", null),
        RCase("GET", "", null),
        RCase("DELETE", "/queue/jhon/tasks", null),
        RCase("GET", "/queue/bukan/tasks", null)
    )
    for ((i, c) in routes.withIndex()) {
        val r = WorkerEndpointLogic.parseRoute(c.m, c.p)
        val ok = if (c.a == null) r == null else r != null && r.action == c.a && r.workerId == c.w && r.name == c.n
        cek("2.${i + 1} route ${c.m} ${c.p} -> ${c.a ?: "null"}", ok)
    }

    // ---- §3. parseRoute — quirk URL-decode + pathMatchesOtherMethod ----
    val rd1 = WorkerEndpointLogic.parseRoute("GET", "/workers/w+1/tasks/task%2D1.md")
    cek("3.1 '+' di path -> spasi, %2D -> '-'", rd1 != null && rd1.action == "readTask" && rd1.workerId == "w 1" && rd1.name == "task-1.md")
    val rd2 = WorkerEndpointLogic.parseRoute("GET", "/workers/w%zz/tasks")
    cek("3.2 '%' tak-valid -> segmen mentah seluruhnya", rd2 != null && rd2.action == "listTasks" && rd2.workerId == "w%zz")
    val rd3 = WorkerEndpointLogic.parseRoute("GET", "  /health  ")
    cek("3.3 path di-trim sebelum parse", rd3 != null && rd3.action == "health")
    cek("3.4 DELETE /workers -> 405 (path cocok, method salah)", WorkerEndpointLogic.pathMatchesOtherMethod("DELETE", "/workers"))
    cek("3.5 GET /workers/w_1/stop -> 405", WorkerEndpointLogic.pathMatchesOtherMethod("GET", "/workers/w_1/stop"))
    cek("3.6 GET /health -> bukan 405 (rute cocok)", !WorkerEndpointLogic.pathMatchesOtherMethod("GET", "/health"))
    cek("3.7 DELETE /tidak-ada -> bukan 405", !WorkerEndpointLogic.pathMatchesOtherMethod("DELETE", "/tidak-ada"))

    // ---- §4. parseQuery ----
    val q1 = WorkerEndpointLogic.parseQuery("/workers/w_1/log?lines=50&foo=bar+baz")
    cek("4.1 query lines + '+' -> spasi", q1["lines"] == "50" && q1["foo"] == "bar baz")
    val q2 = WorkerEndpointLogic.parseQuery("/x?k=a%26b&empty=&noeq&k2=%zz")
    cek("4.2 query %26 + kosong + tanpa '=' dilewati + % tak-valid mentah", q2["k"] == "a&b" && q2["empty"] == "" && !q2.containsKey("noeq") && q2["k2"] == "%zz")
    cek("4.3 tanpa query -> map kosong", WorkerEndpointLogic.parseQuery("/x").isEmpty())
    cek("4.4 '?' kosong -> map kosong", WorkerEndpointLogic.parseQuery("/x?").isEmpty())
    val q3 = WorkerEndpointLogic.parseQuery("/x?a=1&a=2")
    cek("4.5 kunci duplikat -> terakhir menang", q3["a"] == "2")
    val q4 = WorkerEndpointLogic.parseQuery("/x?k=v=w")
    cek("4.6 '=' kedua masuk nilai (limit 2)", q4["k"] == "v=w")
    val q5 = WorkerEndpointLogic.parseQuery("/x?%6B=v")
    cek("4.7 kunci TIDAK di-decode", q5.containsKey("%6B") && q5["%6B"] == "v")

    // ---- §5. Auth: bearerToken + isAuthorized ----
    cek("5.1 bearer ok", WorkerEndpointLogic.bearerToken("Bearer abc123") == "abc123")
    cek("5.2 bearer spasi di-trim", WorkerEndpointLogic.bearerToken("  Bearer   abc123  ") == "abc123")
    cek("5.3 'Bearer ' kosong -> null", WorkerEndpointLogic.bearerToken("Bearer ") == null)
    cek("5.4 'bearer' kecil -> null", WorkerEndpointLogic.bearerToken("bearer abc") == null)
    cek("5.5 skema lain -> null", WorkerEndpointLogic.bearerToken("Basic abc") == null)
    cek("5.6 header kosong/null -> null", WorkerEndpointLogic.bearerToken("") == null && WorkerEndpointLogic.bearerToken(null) == null)
    cek("5.7 auth cocok", WorkerEndpointLogic.isAuthorized("tok123", "tok123"))
    cek("5.8 auth beda isi -> false", !WorkerEndpointLogic.isAuthorized("tok123", "tok124"))
    cek("5.9 auth beda panjang -> false", !WorkerEndpointLogic.isAuthorized("tok12", "tok123"))
    cek("5.10 auth kosong -> false", !WorkerEndpointLogic.isAuthorized("", "tok123") && !WorkerEndpointLogic.isAuthorized("   ", "tok123") && !WorkerEndpointLogic.isAuthorized("tok123", "") && !WorkerEndpointLogic.isAuthorized(null, "tok123"))
    cek("5.11 provided di-trim", WorkerEndpointLogic.isAuthorized("  tok123 ", "tok123"))
    cek("5.12 expected TIDAK di-trim", !WorkerEndpointLogic.isAuthorized("tok123", " tok123"))
    cek("5.13 token unicode cocok", WorkerEndpointLogic.isAuthorized("tökén-ü", "tökén-ü"))
    cek("5.14 token unicode beda -> false", !WorkerEndpointLogic.isAuthorized("tökén-ü", "tökén-u"))

    // ---- §6. HMAC antrean ----
    // Vektor RFC 4231 kasus 1 (kunci 0x0B x20) + kasus 2 (Jefe) —
// lewat wrapper str (UTF-8, seperti Kotlin).
    cek("6.1 hmac RFC 4231 kasus 1", WorkerEndpointLogic.hmacSha256Hex("\u000B".repeat(20), "Hi There") == "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
    cek("6.2 hmac RFC 4231 kasus 2", WorkerEndpointLogic.hmacSha256Hex("Jefe", "what do ya want for nothing?") == "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
    val signed = WorkerEndpointLogic.signQueueText("kunci-rahasia", "kerjakan laporan")
    cek("6.3 sign format HMAC-SHA256:<hex>\\n<isi>", signed.startsWith("HMAC-SHA256:") && signed.endsWith("\nkerjakan laporan") && signed.length == "HMAC-SHA256:".length + 64 + 1 + "kerjakan laporan".length)
    cek("6.4 verify round-trip", WorkerEndpointLogic.verifyQueueText("kunci-rahasia", signed) == "kerjakan laporan")
    cek("6.5 verify kunci salah -> null", WorkerEndpointLogic.verifyQueueText("kunci-lain", signed) == null)
    cek("6.6 verify isi diubah -> null", WorkerEndpointLogic.verifyQueueText("kunci-rahasia", signed.replace("laporan", "LAPORAN")) == null)
    cek("6.7 verify tanpa tanda tangan -> null", WorkerEndpointLogic.verifyQueueText("kunci-rahasia", "kerjakan laporan") == null)
    cek("6.8 hasQueueSignature", WorkerEndpointLogic.hasQueueSignature(signed) && !WorkerEndpointLogic.hasQueueSignature("kerjakan laporan"))
    cek("6.9 verify tanda tangan kosong -> null", WorkerEndpointLogic.verifyQueueText("k", "HMAC-SHA256:\nisi") == null)
    cek("6.10 verify tanpa newline -> null", WorkerEndpointLogic.verifyQueueText("k", "HMAC-SHA256:abc") == null)
    cek("6.11 sign/verify teks kosong", WorkerEndpointLogic.verifyQueueText("k", WorkerEndpointLogic.signQueueText("k", "")) == "")
    cek("6.12 sign/verify unicode + newline isi", WorkerEndpointLogic.verifyQueueText("künci", WorkerEndpointLogic.signQueueText("künci", "baris1\nbaris2 😀")) == "baris1\nbaris2 😀")
    cek("6.13 hmac kunci panjang >64 byte (hash dulu) konsisten", WorkerEndpointLogic.hmacSha256Hex("a".repeat(200), "x") == WorkerEndpointLogic.hmacSha256Hex("a".repeat(200), "x") && WorkerEndpointLogic.hmacSha256Hex("a".repeat(200), "x") != WorkerEndpointLogic.hmacSha256Hex("aa", "x"))

    // ---- §7. isSafeName + slicePreview + previewChars ----
    val safeCases = listOf(
        "task-1.md" to true, "task-1.md.result.md" to true, "a b.md" to true,
        "" to false, "   " to false, "../task.md" to false, "a/b.md" to false,
        "a\\b.md" to false, ".." to false, "a..b" to false
    )
    for ((i, c) in safeCases.withIndex()) {
        cek("7.${i + 1} isSafeName '${c.first}' -> ${c.second}", WorkerEndpointLogic.isSafeName(c.first) == c.second)
    }
    cek("7.11 slicePreview pendek tidak terpotong", WorkerEndpointLogic.slicePreview("hello", 10) == Pair("hello", false))
    cek("7.12 slicePreview tepat maks tidak terpotong (<=)", WorkerEndpointLogic.slicePreview("hello", 5) == Pair("hello", false))
    cek("7.13 slicePreview potong + flag", WorkerEndpointLogic.slicePreview("hello world", 5) == Pair("hello", true))
    cek("7.14 slicePreview teks kosong", WorkerEndpointLogic.slicePreview("", 5) == Pair("", false))
    // Astral: bandingkan BYTE UTF-8 (kontrak wire) — kedua mode
    // harus menghasilkan lone surrogate yang sama (61 3F).
    val astral = "a\uD83D\uDE00b"
    val cut2 = WorkerEndpointLogic.slicePreview(astral, 2)
    cek("7.15 slicePreview astral take(2) byte wire = 'a' + '?' (61 3F)", cut2.second && cut2.first.length == 2 && cut2.first.toByteArray(Charsets.UTF_8).contentEquals(byteArrayOf(0x61, 0x3F)))
    val cut3 = WorkerEndpointLogic.slicePreview(astral, 3)
    cek("7.16 slicePreview astral take(3) = 'a' + emoji utuh", cut3 == Pair("a\uD83D\uDE00", true))
    val cut4 = WorkerEndpointLogic.slicePreview(astral, 4)
    cek("7.17 slicePreview astral take(4) utuh tidak terpotong", cut4 == Pair(astral, false))
    for ((i, c) in listOf(0 to 2000, -5 to 2000, 1 to 1, 2000 to 2000, 8000 to 8000, 8001 to 8000, 99999 to 8000).withIndex()) {
        cek("7.${18 + i} previewChars(${c.first}) == ${c.second}", WorkerEndpointLogic.previewChars(c.first) == c.second)
    }

    // ---- §8. parseHeaders + buildResponse (framing HTTP) ----
    if (rust) {
        val ph = WorkerEndpointSeam.parseHeaders("GET /health HTTP/1.1\r\nAuthorization: Bearer tok123\r\nContent-Length: 42\r\nX-Dup: satu\r\nX-Dup: dua\r\nBarisTanpaColon\r\n:tanpa-kunci\r\n\r\n")
        cek("8.1 parseHeaders req_line", ph != null && ph.reqLine == "GET /health HTTP/1.1")
        cek("8.2 parseHeaders kunci lowercase + nilai trim", ph != null && ph.headers["authorization"] == "Bearer tok123" && ph.headers["content-length"] == "42")
        cek("8.3 parseHeaders duplikat -> terakhir menang", ph != null && ph.headers["x-dup"] == "dua")
        cek("8.4 parseHeaders baris tanpa ':' dilewati", ph != null && !ph.headers.containsKey("baristanpacolon") && !ph.headers.containsKey(""))
        val ph2 = WorkerEndpointSeam.parseHeaders("POST /x HTTP/1.1\r\n  X-Custom :   v v  \r\n\r\n")
        cek("8.5 parseHeaders kunci trim + lowercase, nilai trim", ph2 != null && ph2.headers["x-custom"] == "v v")
        cek("8.6 buildResponse 200 eksak", WorkerEndpointSeam.buildResponseHead(200, 13, null) == "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: 13\r\nConnection: close\r\n\r\n")
        cek("8.7 buildResponse 503 + Retry-After eksak", WorkerEndpointSeam.buildResponseHead(503, 100, 5) == "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: 100\r\nConnection: close\r\nRetry-After: 5\r\n\r\n")
        cek("8.8 buildResponse 431 reason eksak", (WorkerEndpointSeam.buildResponseHead(431, 0, null) ?: "").startsWith("HTTP/1.1 431 Request Header Fields Too Large\r\n"))
        cek("8.9 buildResponse kode tak dikenal -> reason OK", (WorkerEndpointSeam.buildResponseHead(418, 1, null) ?: "").startsWith("HTTP/1.1 418 OK\r\n"))
    } else {
        cek("8.1 seam parseHeaders null tanpa .so", WorkerEndpointSeam.parseHeaders("GET /x HTTP/1.1\r\n\r\n") == null)
        cek("8.2 seam buildResponseHead null tanpa .so", WorkerEndpointSeam.buildResponseHead(200, 0, null) == null)
        cek("8.3 parseHeadersKotlin ada (fallback)", epSrc.contains("private fun parseHeadersKotlin(text: String)"))
        cek("8.4 parseHeadersKotlin: split + lowercase", epSrc.contains("text.split(\"\\r\\n\")") && epSrc.contains(".trim().lowercase()"))
        cek("8.5 buildResponseHeadKotlin ada (fallback)", epSrc.contains("private fun buildResponseHeadKotlin("))
        cek("8.6 buildResponseHeadKotlin: template head + Retry-After", epSrc.contains("\"HTTP/1.1 \$code \${reason(code)}\\r\\nContent-Type: application/json; charset=utf-8\\r\\nContent-Length: \$bodyBytes\\r\\nConnection: close\\r\\n\$retryHead\\r\\n\""))
        cek("8.7 sendJson memanggil seam buildResponseHead", epSrc.contains("WorkerEndpointSeam.buildResponseHead(code, body.size, retryAfterSec)"))
        cek("8.8 readHeaders memanggil seam parseHeaders", epSrc.contains("WorkerEndpointSeam.parseHeaders(text)"))
        cek("8.9 reason() tetap ada (dipakai fallback)", epSrc.contains("private fun reason(code: Int): String"))
    }

    // ---- §9. idempotencyKey ----
    if (rust) {
        cek("9.1 idem spawn body kosong -> null", WorkerEndpointSeam.idempotencyKey(WorkerEndpointLogic.Route("spawnWorker"), emptyMap(), "{}")?.key == null)
        val idemH = WorkerEndpointSeam.idempotencyKey(WorkerEndpointLogic.Route("spawnWorker"), mapOf("idempotency-key" to "klien-123"), "{}")
        cek("9.2 idem header -> hdr:klien-123", idemH != null && idemH.key == "hdr:klien-123")
        val idemS = WorkerEndpointSeam.idempotencyKey(WorkerEndpointLogic.Route("spawnWorker"), emptyMap(), "{\"name\":\"Pekerja\",\"kind\":\"proot\",\"session\":\"worker1\",\"command\":\"echo hi\"}")
        cek("9.3 idem spawn proot eksak", idemS != null && idemS.key == "spawn:pekerja|proot|worker1|56a79f3b11544807")
        val idemD = WorkerEndpointSeam.idempotencyKey(WorkerEndpointLogic.Route("spawnWorker"), emptyMap(), "{\"name\":\"  PEKERJA \",\"command\":\"echo hi\"}")
        cek("9.4 idem spawn default kind/session + nama trim/lower", idemD != null && idemD.key == "spawn:pekerja|proot|worker1|56a79f3b11544807")
        val idemP = WorkerEndpointSeam.idempotencyKey(WorkerEndpointLogic.Route("spawnWorker"), emptyMap(), "{\"name\":\"py\",\"kind\":\"python\",\"session\":\"worker2\",\"code\":\"print(1)\"}")
        cek("9.5 idem spawn python memakai code", idemP != null && (idemP.key ?: "").startsWith("spawn:py|python|worker2|"))
        cek("9.6 idem spawn nama kosong -> null", WorkerEndpointSeam.idempotencyKey(WorkerEndpointLogic.Route("spawnWorker"), emptyMap(), "{\"name\":\"\"}")?.key == null)
        val idemE = WorkerEndpointSeam.idempotencyKey(WorkerEndpointLogic.Route("enqueueTask", "w_7"), emptyMap(), "{\"text\":\"kerjakan laporan\"}")
        cek("9.7 idem enqueue eksak", idemE != null && idemE.key == "enqueue:w_7|aee2a04a144e10dc")
        val idemJ = WorkerEndpointSeam.idempotencyKey(WorkerEndpointLogic.Route("jhonEnqueue"), emptyMap(), "{\"text\":\"kerjakan laporan\"}")
        cek("9.8 idem jhonEnqueue eksak", idemJ != null && idemJ.key == "enqueue:jhon|aee2a04a144e10dc")
        cek("9.9 idem aksi lain -> null", WorkerEndpointSeam.idempotencyKey(WorkerEndpointLogic.Route("listWorkers"), emptyMap(), "{}")?.key == null)
    } else {
        cek("9.1 seam idempotencyKey null tanpa .so", WorkerEndpointSeam.idempotencyKey(WorkerEndpointLogic.Route("spawnWorker"), emptyMap(), "{}") == null)
        val mergeEp = java.io.File(mergeDir, "WorkerEndpoint.kt")
        if (mergeEp.exists()) {
            cek("9.2 idempotencyKeyKotlin identik merge/ idempotencyKey vc316", sameFunBody(epSrc, "private fun idempotencyKeyKotlin(", mergeEp.readText(), "private fun idempotencyKey("))
        } else {
            println("SKIP 9.2 merge/ tidak ada")
        }
        cek("9.3 idempotencyKeyKotlin: header hdr: + take(128)", epSrc.contains("\"hdr:\" + hdr.take(128)"))
        cek("9.4 idempotencyKeyKotlin: spawn: + sha256Hex16", epSrc.contains("\"spawn:\$name|\$kind|\$session|\" + sha256Hex16(content)"))
        cek("9.5 idempotencyKeyKotlin: enqueue: + sha256Hex16", epSrc.contains("\"enqueue:\${route.workerId}|\" + sha256Hex16(text)"))
        cek("9.6 idempotencyKeyKotlin: jhonEnqueue", epSrc.contains("\"enqueue:jhon|\" + sha256Hex16(text)"))
        cek("9.7 sha256Hex16 masih ada (dipakai fallback)", epSrc.contains("private fun sha256Hex16(text: String): String"))
        cek("9.8 wrapper idempotencyKey memanggil seam", epSrc.contains("WorkerEndpointSeam.idempotencyKey(route, headers, body)"))
        cek("9.9 idempotencyKey wrapper ada di WorkerEndpoint", epSrc.contains("private fun idempotencyKey(route: WorkerEndpointLogic.Route, headers: Map<String, String>, body: String): String?"))
    }

    // ---- §10. workerScoped ----
    val scoped = WorkerEndpointLogic.WORKER_SCOPED_ACTIONS
    cek("10.1 scoped: verifyTask + workerDetail", scoped.contains("verifyTask") && scoped.contains("workerDetail"))
    cek("10.2 scoped: stop/restart/log/tasks/enqueue/read/write/list", scoped.contains("stopWorker") && scoped.contains("restartWorker") && scoped.contains("workerLog") && scoped.contains("listTasks") && scoped.contains("enqueueTask") && scoped.contains("readTask") && scoped.contains("writeTaskResult") && scoped.contains("listResults") && scoped.contains("readResult"))
    cek("10.3 bukan scoped: health/listWorkers/spawnWorker", !scoped.contains("health") && !scoped.contains("listWorkers") && !scoped.contains("spawnWorker"))
    cek("10.4 bukan scoped: setQuota/resetWorker (master saja)", !scoped.contains("setQuota") && !scoped.contains("resetWorker"))
    cek("10.5 bukan scoped: jhon* (master saja)", !scoped.contains("jhonListTasks") && !scoped.contains("jhonEnqueue") && !scoped.contains("jhonReadTask") && !scoped.contains("jhonWriteResult") && !scoped.contains("jhonListResults"))
    if (rust) {
        cek("10.6 seam workerScoped verifyTask true", WorkerEndpointSeam.workerScoped("verifyTask") == true)
    } else {
        cek("10.6 seam workerScoped null tanpa .so", WorkerEndpointSeam.workerScoped("verifyTask") == null)
    }

    // ---- §11. *Kotlin identik merge/ vc316 ----
    // Fun ber-kurung -> bandingkan body (sameFunBody). Fun SATU
    // BARIS (tanpa '{', mis. `= WorkerLogic.previewChars(...)`)
    // TIDAK BISA diambil funBody-nya (return "") -> bandingkan
    // baris/fragmen kunci di kedua sumber (ditemukan ronde 1:
    // 11.3/11.4/11.5/11.10/11.11 gagal karena funBody "").
    val mergeLogic = java.io.File(mergeDir, "WorkerEndpointLogic.kt")
    if (mergeLogic.exists()) {
        val mLogic = mergeLogic.readText()
        val bracePairs = listOf(
            1 to ("isAuthorizedKotlin(" to "isAuthorized("),
            2 to ("bearerTokenKotlin(" to "bearerToken("),
            6 to ("parseQueryKotlin(" to "parseQuery("),
            7 to ("parseRouteKotlin(" to "parseRoute("),
            8 to ("pathMatchesOtherMethodKotlin(" to "pathMatchesOtherMethod("),
            9 to ("hmacSha256HexKotlin(" to "hmacSha256Hex("),
            12 to ("verifyQueueTextKotlin(" to "verifyQueueText(")
        )
        for ((num, p) in bracePairs) {
            cek("11.$num ${p.first} identik merge/ ${p.second} vc316", sameFunBody(logicSrc, "private fun ${p.first}", mLogic, "fun ${p.second}"))
        }
        cek("11.3 previewCharsKotlin satu baris identik merge/", logicSrc.contains("private fun previewCharsKotlin(requested: Int): Int = WorkerLogic.previewChars(requested)") && mLogic.contains("fun previewChars(requested: Int): Int = WorkerLogic.previewChars(requested)"))
        cek("11.4 slicePreviewKotlin satu baris identik merge/", logicSrc.contains("private fun slicePreviewKotlin(text: String, max: Int): Pair<String, Boolean>") && logicSrc.contains("WorkerLogic.slicePreview(text, max)") && mLogic.contains("fun slicePreview(text: String, max: Int): Pair<String, Boolean>"))
        cek("11.5 isSafeNameKotlin satu baris identik merge/", logicSrc.contains("private fun isSafeNameKotlin(name: String): Boolean") && logicSrc.contains("name.isNotBlank() && !name.contains(\"/\") && !name.contains(\"..\") && !name.contains(\"\\\\\")") && mLogic.contains("fun isSafeName(name: String): Boolean"))
        cek("11.10 signQueueTextKotlin satu baris identik merge/", logicSrc.contains("private fun signQueueTextKotlin(key: String, text: String): String") && logicSrc.contains("\"HMAC-SHA256:\" + hmacSha256Hex(key, text) + \"\\n\" + text") && mLogic.contains("fun signQueueText(key: String, text: String): String"))
        cek("11.11 hasQueueSignatureKotlin satu baris identik merge/", logicSrc.contains("private fun hasQueueSignatureKotlin(text: String): Boolean = text.startsWith(\"HMAC-SHA256:\")") && mLogic.contains("fun hasQueueSignature(text: String): Boolean = text.startsWith(\"HMAC-SHA256:\")"))
    } else {
        println("SKIP §11 merge/WorkerEndpointLogic.kt tidak ada")
    }

    // ---- §12. Batas slice 9 (file lain + infra tetap) ----
    for (f in listOf("WorkerLogic.kt", "WorkerQueue.kt", "WorkerJanitorLogic.kt", "WorkerRegistry.kt")) {
        val s = src(treeDir, f)
        cek("12.$f tanpa Rust (bukan slice 9)", !s.contains("WorkerEndpointSeam") && !s.contains("workerEndpointJson") && !s.contains("rustWorkerEndpoint"))
    }
    cek("12.5 acceptLoop + dispatch + token tetap Kotlin", epSrc.contains("private fun acceptLoop(ctx: Context)") && epSrc.contains("private fun dispatch(ctx: Context, route: WorkerEndpointLogic.Route, query: Map<String, String>, body: String)") && epSrc.contains("fun token(ctx: Context): String"))
    cek("12.6 start/stop + SecureStore tetap Kotlin", epSrc.contains("fun start(ctx: Context): String") && epSrc.contains("fun stop(): String") && epSrc.contains("SecureStore.get(ctx, TOKEN_KEY)"))
    cek("12.7 Flavor.kt tanpa sentuhan slice 9", !src(treeDir, "Flavor.kt").contains("Vc325") && !src(treeDir, "Flavor.kt").contains("vc325"))
    cek("12.8 DsApiClient.kt tanpa sentuhan slice 9", !src(treeDir, "DsApiClient.kt").contains("WorkerEndpointSeam") && !src(treeDir, "DsApiClient.kt").contains("workerEndpointJson"))
    val cargoToml = srcCore(coreDir, "Cargo.toml")
    cek("12.9 Cargo.toml tanpa tokio + tanpa dep baru", !cargoToml.contains("tokio") && !cargoToml.contains("hmac") && cargoToml.contains("sha2 = \"0.10\""))
    cek("12.10 WorkerQueue tetap panggil WorkerEndpointLogic.signQueueText", src(treeDir, "WorkerQueue.kt").contains("WorkerEndpointLogic.signQueueText(signKey, rawBody)") && src(treeDir, "WorkerQueue.kt").contains("WorkerEndpointLogic.verifyQueueText(verifyKey, content)"))
    cek("12.11 PORT 4198 + MAX_BODY_BYTES 1 MB tidak berubah", logicSrc.contains("const val PORT = 4198") && logicSrc.contains("const val MAX_BODY_BYTES = 1024 * 1024"))
    cek("12.12 RUST_CORE_VERSION 7 di jni_glue.rs", srcCore(coreDir, "src/jni_glue.rs").contains("RUST_CORE_VERSION: jint = 7"))

    println("TOTAL $total LULUS $lulus GAGAL $gagal")
    if (gagal > 0) kotlin.system.exitProcess(1)
}
