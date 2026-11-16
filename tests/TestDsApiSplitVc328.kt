// TestDsApiSplitVc328 — P14 (slice 11, vc328): paritas split DsApiClient -> Rust.
//
// Mode: TestDsApiSplitVc328Kt <rust|kotlin> <treeDir> <coreDir>
//   rust   = jalur Rust ASLI via .so host (-Djava.library.path=.../target/debug)
//   kotlin = fallback *Kotlin, tanpa .so (RustCore.available=false)
//   treeDir = direktori .kt terpadu (integrated / app/.../deepharness)
//   coreDir = harness-core (untuk baca jni_glue.rs + Cargo.toml)
//
// Yang diuji (diferensial: ekspektasi SAMA di kedua mode — hasil Rust
// dan hasil Kotlin lama harus identik):
//   (a) SEAM LANGSUNG via DsApiSeam (mode rust) + fallback (mode kotlin):
//       findSessionId, findChallenge, findFileId, estTokens, safeFileName,
//       uploadFile (vs ServerSocket lokal — mode rust; mode kotlin ganti
//       identitas sumber karena sandbox VM mencegat TCP loopback JVM,
//       preseden P10).
//   (b) WRAPPER PRIVAT `DsApiClient.*` — diuji via REFLEKSI pada instance
//       yang dialokasikan `sun.misc.Unsafe.allocateInstance` (konstruktor
//       butuh WebView hidup, jadi TIDAK bisa `DsApiClient(webView)` di JVM;
//       probe /tmp/probe328 membuktikan allocateInstance + set field
//       `rustDsApi`/`fragIdxRe` via refleksi/Unsafe bekerja; `recordDiag`
//       aman di instance ini karena `jhonMode()` NPE-safe + try/catch).
//       `StreamState` (kelas privat bersarang) dialokasikan sama +
//       field `lastPath`/`fragmentThinking` diisi manual (allocateInstance
//       melewatkan inisialisasi field).
//   (c) HTTP: postJsonResult/getJsonResult (mapping + Retry-After) via
//       refleksi dengan (code, body, retryAfter) terskrip — postJson/
//       getJson TIDAK bisa menembak server lokal karena URL-nya hardcode
//       chat.deepseek.com; yang dibuktikan: wrapper memanggil
//       HttpSeam.request dengan URL/timeout/wantHeaders benar + mapping
//       hasil transport identik.
//   (d) SSE: aliran payload JSON-Patch terskrip -> HttpSeam.sseApply
//       (rust) vs applyChunkKotlin (kotlin) — content/thinking/
//       sawFinish harus identik.
//   (e) SUMBER: *Kotlin identik merge/ vc327 (dinormalisasi panggilan
//       internal `*Kotlin(` -> `(`; postJsonKotlin/getJsonKotlin/
//       uploadPostKotlin dibandingkan per-baris transport karena badan
//       lamanya terbelah ke *Result), solvePowHeader + completeWithSession
//       + perilaku vc321/vc322/vc327 utuh, panen cookie tetap Kotlin.
//   (f) STRUKTUR: RUST_CORE_VERSION 9 + 29 simbol JNI + dsApiJson +
//       modul ds_api, TANPA dependensi Cargo baru, TANPA sentuhan
//       SlotClient/TurnMetrics/Flavor/GroupChat/BgBrain/MainActivity.
//
// CATATAN JUJUR: jaringan DeepSeek asli TIDAK diuji di JVM (butuh token +
// cookie WebView hidup + jaringan keluar). Yang dibuktikan P14: seluruh
// logika PARSE + hitung + mapping + transport multipart yang menentukan
// hasil postJson/getJson/uploadFile/postCompletion. End-to-end (server
// DeepSeek nyata) menjadi TES perangkat (TES-VC328).
// CATATAN `safeFileName`: seam `DsApiSeam.safeFileName` TIDAK dipanggil
// produksi Kotlin — jalur Rust memakainya di DALAM ds_api.rs saat merakit
// multipart, jalur Kotlin memakai ekspresi inline yang sama di
// uploadPostKotlin. P14 §2 membandingkan keduanya langsung (diferensial).

package com.senzwh.deepharness

import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket

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
 *  dengan scanner yang melewati string "..." (escape-aware), literal
 *  char 'x' (escape-aware), dan komentar `//`. "" = signature tidak
 *  ketemu / tak-berakhir. (Disalin dari P12 — pola yang sama.) */
private fun funBody(srcText: String, sig: String): String {
    val i = srcText.indexOf(sig)
    if (i < 0) return ""
    val open = srcText.indexOf('{', i)
    if (open < 0) return ""
    var depth = 0
    var j = open
    var inStr = false
    var inChar = false
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
        if (inChar) {
            if (c == '\\') { j += 2; continue }
            if (c == '\'') inChar = false
            j++
            continue
        }
        if (c == '/' && j + 1 < srcText.length && srcText[j + 1] == '/') { inLineComment = true; j += 2; continue }
        if (c == '"') { inStr = true; j++; continue }
        if (c == '\'') { inChar = true; j++; continue }
        if (c == '{') depth++
        if (c == '}') {
            depth--
            if (depth == 0) return srcText.substring(open, j + 1)
        }
        j++
    }
    return ""
}

private fun sameFunBodyNorm(aText: String, aSig: String, bText: String, bSig: String): Boolean {
    val a = funBody(aText, aSig)
    val b = funBody(bText, bSig)
    if (a.isEmpty() || b.isEmpty()) return false
    fun norm(s: String) = s.replace("Kotlin(", "(").split(Regex("\\s+")).joinToString(" ")
    return norm(a) == norm(b)
}

/** Badan [sig] di [srcText] mengandung SEMUA baris [lines] (per-baris,
 *  dinormalisasi spasi) — untuk fun yang terbelah (postJsonKotlin dll:
 *  badan lamanya terpotong ke *Result, jadi yang dibandingkan adalah
 *  blok transportnya, bukan badan utuh). */
private fun bodyContainsLines(srcText: String, sig: String, lines: List<String>): Boolean {
    val b = funBody(srcText, sig)
    if (b.isEmpty()) return false
    fun norm(s: String) = s.split(Regex("\\s+")).joinToString(" ").trim()
    val nb = norm(b)
    return lines.all { norm(it).isNotEmpty() && nb.contains(norm(it)) }
}

// ---- Unsafe allocateInstance + refleksi DsApiClient ----
private fun getUnsafe(): sun.misc.Unsafe {
    val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
    f.isAccessible = true
    return f.get(null) as sun.misc.Unsafe
}

private val dsApiClass: Class<*> by lazy { Class.forName("com.senzwh.deepharness.DsApiClient") }
private val streamStateClass: Class<*> by lazy { Class.forName("com.senzwh.deepharness.DsApiClient\$StreamState") }

private fun allocDsApi(rust: Boolean): Any {
    val u = getUnsafe()
    val inst = u.allocateInstance(dsApiClass)
    val rf = dsApiClass.getDeclaredField("rustDsApi")
    rf.isAccessible = true
    rf.setBoolean(inst, rust)
    val ff = dsApiClass.getDeclaredField("fragIdxRe")
    ff.isAccessible = true
    u.putObject(inst, u.objectFieldOffset(ff), Regex("fragments/(-?\\d+)"))
    return inst
}

private fun allocState(): Any {
    val u = getUnsafe()
    val st = u.allocateInstance(streamStateClass)
    val lf = streamStateClass.getDeclaredField("lastPath")
    lf.isAccessible = true
    u.putObject(st, u.objectFieldOffset(lf), "")
    val hf = streamStateClass.getDeclaredField("fragmentThinking")
    hf.isAccessible = true
    u.putObject(st, u.objectFieldOffset(hf), HashMap<Int, Boolean>())
    return st
}

private fun stateSawFinish(st: Any): Boolean {
    val f = streamStateClass.getDeclaredField("sawFinish")
    f.isAccessible = true
    return f.getBoolean(st)
}

private fun callDs(inst: Any, name: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? {
    val m = dsApiClass.getDeclaredMethod(name, *paramTypes)
    m.isAccessible = true
    return m.invoke(inst, *args)
}

/** Panggil fun privat yang melempar Exception: kembalikan pesan
 *  exception-nya (cause InvocationTargetException dibuka). */
private fun callDsErr(inst: Any, name: String, paramTypes: Array<Class<*>>, vararg args: Any?): String {
    return try {
        callDs(inst, name, paramTypes, *args)
        "<<TIDAK-MELEMPAR>>"
    } catch (e: Exception) {
        (e.cause ?: e).message ?: "<<TANPA-PESAN>>"
    }
}

private fun wFindSessionId(inst: Any, root: JSONObject): String? =
    callDs(inst, "findSessionId", arrayOf(JSONObject::class.java), root) as String?

private fun wFindChallenge(inst: Any, root: JSONObject): JSONObject? =
    callDs(inst, "findChallenge", arrayOf(JSONObject::class.java), root) as JSONObject?

private fun wFindFileId(inst: Any, root: JSONObject): String? =
    callDs(inst, "findFileId", arrayOf(JSONObject::class.java), root) as String?

private fun wEstTokens(inst: Any, chars: Int, perChar: Double): Long =
    callDs(inst, "estTokens", arrayOf(Int::class.java, Double::class.java), chars, perChar) as Long

// ---- SSE diferensial ----
private val ssePayloads = listOf(
    // Deklarasi fragmen THINK + isi berpikir.
    """{"p":"response/fragments/0","o":"APPEND","v":{"type":"THINK","content":"hmm "}}""",
    """{"p":"response/fragments/0/content","v":"halo "}""",
    // FINISHED fragmen berpikir -> flip ke jawaban.
    """{"p":"response/fragments/0/status","v":"FINISHED"}""",
    // Deklarasi fragmen RESPONSE + isi jawaban.
    """{"p":"response/fragments/1","o":"APPEND","v":{"type":"RESPONSE","content":"jawab"}}""",
    """{"p":"response/fragments/1/content","v":"an"}""",
    // Label status fragmen BUKAN isi (bug v3.1: "FINISHEDFINISHED").
    """{"p":"response/fragments/1/status","v":"FINISHED"}""",
    // BATCH: dua sub-chunk sekaligus + status resmi selesai.
    """{"o":"BATCH","v":[{"p":"response/fragments/1/content","v":"!"},{"p":"response/status","v":"FINISHED"}]}"""
)

private fun runSseRust(): Triple<String, String, Boolean> {
    var st = HttpSeam.sseInitState()
    val c = StringBuilder()
    val t = StringBuilder()
    var fin = false
    for (p in ssePayloads) {
        val step = HttpSeam.sseApply(st, p) ?: return Triple("<<SEAM-NULL>>", "", false)
        st = step.state
        c.append(step.contentDelta)
        t.append(step.thinkingDelta)
        fin = step.sawFinish
    }
    return Triple(c.toString(), t.toString(), fin)
}

private fun runSseKotlin(inst: Any): Triple<String, String, Boolean> {
    val st = allocState()
    val c = StringBuilder()
    val t = StringBuilder()
    for (p in ssePayloads) {
        callDs(inst, "applyChunkKotlin",
            arrayOf(JSONObject::class.java, StringBuilder::class.java, StringBuilder::class.java, streamStateClass),
            JSONObject(p), c, t, st)
    }
    return Triple(c.toString(), t.toString(), stateSawFinish(st))
}

// ---- Server lokal untuk upload (preseden P10) ----
private fun loopbackBlocked(port: Int): Boolean {
    return try {
        Socket("127.0.0.1", port).use { s -> s.getInputStream().read() == -1 }
        false
    } catch (e: Exception) {
        true
    }
}

private class CapturedReq(
    var head: String = "",
    var body: ByteArray = ByteArray(0)
)

/** Server sekali pakai: tangani [nConn] koneksi berurutan, tiap koneksi
 *  dijawab dengan [respond] (status + body). Kembalikan daftar request
 *  yang tertangkap (head mentah + body mentah). */
private fun serveLocal(nConn: Int, respond: (Int) -> Pair<Int, String>): Pair<Int, List<CapturedReq>> {
    val server = ServerSocket(0)
    val port = server.localPort
    val captured = mutableListOf<CapturedReq>()
    val t = Thread {
        try {
            repeat(nConn) { idx ->
                val sock = server.accept()
                sock.use { s ->
                    val inp = s.getInputStream()
                    val headBytes = ByteArrayOutputStream2()
                    // Baca sampai \r\n\r\n.
                    var prev = -1
                    var cur = -1
                    var b: Int
                    while (true) {
                        b = inp.read()
                        if (b < 0) break
                        headBytes.write(b)
                        prev = cur; cur = b
                        val arr = headBytes.toByteArray()
                        if (arr.size >= 4 && arr[arr.size - 4] == 13.toByte() && arr[arr.size - 3] == 10.toByte() && arr[arr.size - 2] == 13.toByte() && arr[arr.size - 1] == 10.toByte()) break
                    }
                    val head = String(headBytes.toByteArray(), Charsets.UTF_8)
                    val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val body = ByteArray(len)
                    var off = 0
                    while (off < len) {
                        val r = inp.read(body, off, len - off)
                        if (r < 0) break
                        off += r
                    }
                    synchronized(captured) { captured.add(CapturedReq(head, body.copyOf(off))) }
                    val (code, respBody) = respond(idx)
                    val resp = "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${respBody.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$respBody"
                    s.getOutputStream().write(resp.toByteArray(Charsets.UTF_8))
                    s.getOutputStream().flush()
                }
            }
        } catch (e: Exception) {
            // Server tes: abaikan (test akan gagal di assert bila request tak sampai).
        } finally {
            try { server.close() } catch (e: Exception) {}
        }
    }
    t.isDaemon = true
    t.start()
    return Pair(port, captured)
}

private class ByteArrayOutputStream2 : java.io.ByteArrayOutputStream()

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "kotlin"
    val treeDir = args.getOrNull(1) ?: "."
    val coreDir = args.getOrNull(2) ?: "harness-core"
    val rust = mode == "rust"
    println("== TestDsApiSplitVc328 mode=$mode treeDir=$treeDir coreDir=$coreDir ==")
    val mergeDir = java.io.File(treeDir, "../merge")
    val dsSrc = src(treeDir, "DsApiClient.kt")
    val seamSrc = src(treeDir, "DsApiSeam.kt")
    val slotSrc = src(treeDir, "SlotClient.kt")
    val mergeDs = src(mergeDir.path, "DsApiClient.kt")
    val dsApiRs = srcCore(coreDir, "src/ds_api.rs")
    val glue = srcCore(coreDir, "src/jni_glue.rs")
    val libRs = srcCore(coreDir, "src/lib.rs")
    val cargoToml = srcCore(coreDir, "Cargo.toml")
    val cargoLock = srcCore(coreDir, "Cargo.lock")

    val rcSrc = listOf(
        "harness/core/RustCore.kt",
        "../harness/core/RustCore.kt",
        "../../harness/core/RustCore.kt",
        "../../../harness/core/RustCore.kt"
    ).mapNotNull { rel ->
        val f = java.io.File(treeDir, rel)
        if (f.exists()) f.readText() else null
    }.firstOrNull() ?: ""

    val inst = allocDsApi(rust)

    // ---- §1. Seam + VERSION ----
    cek("1.1 RustCore.kt VERSION 9 + dsApiJson", rcSrc.contains("const val VERSION = 9") && rcSrc.contains("external fun dsApiJson(request: String): String?"))
    cek("1.2 jni_glue.rs RUST_CORE_VERSION 9 + fn dsApiJson", glue.contains("pub const RUST_CORE_VERSION: jint = 9") && glue.contains("Java_harness_core_RustCore_dsApiJson"))
    cek("1.3 lib.rs modul ds_api + jni_glue use ds_api", libRs.contains("pub mod ds_api;") && glue.contains("use crate::ds_api;"))
    val nSym = glue.split("pub extern \"C\" fn Java_harness_core_RustCore_").size - 1
    cek("1.4 29 simbol JNI (28 lama + dsApiJson)", nSym == 29)
    if (rust) {
        cek("1.5 RustCore.available true (mode rust, .so host)", harness.core.RustCore.available)
        val caps = harness.core.RustCore.rustCoreCapabilities()
        cek("1.6 capabilities: version 9 + dsApiJson + modul ds_api", caps.contains("\"version\":9") && caps.contains("dsApiJson") && caps.contains("\"ds_api\""))
        cek("1.7 DsApiSeam.rustDsApi true (mode rust)", DsApiSeam.rustDsApi)
        cek("1.8 seam findSessionId non-null (mode rust)", DsApiSeam.findSessionId("""{"data":{"biz_data":{"chat_session":{"id":"sess-123"}}}}""") == "sess-123")
        cek("1.9 seam estTokens 340/3.4 = 100 (mode rust)", DsApiSeam.estTokens(340, 3.4) == 100L)
        cek("1.10 DsApiClient.rustDsApi true di instance (mode rust)", (dsApiClass.getDeclaredField("rustDsApi").apply { isAccessible = true }.getBoolean(inst)))
    } else {
        cek("1.5 RustCore.available false (mode kotlin, tanpa .so)", !harness.core.RustCore.available)
        cek("1.6 DsApiSeam.rustDsApi false (mode kotlin)", !DsApiSeam.rustDsApi)
        cek("1.7 seam findSessionId null tanpa .so (kontrak fallback)", DsApiSeam.findSessionId("""{"data":{"biz_data":{"id":"x"}}}""") == null)
        cek("1.8 seam estTokens null tanpa .so", DsApiSeam.estTokens(340, 3.4) == null)
        cek("1.9 seam uploadFile null tanpa .so", DsApiSeam.uploadFile("http://127.0.0.1:1/x", emptyMap(), "aGk=", "f.txt", "text/plain") == null)
        cek("1.10 DsApiClient.rustDsApi false di instance (mode kotlin)", !(dsApiClass.getDeclaredField("rustDsApi").apply { isAccessible = true }.getBoolean(inst)))
    }

    // ---- §2. Pure ops diferensial via wrapper (ekspektasi literal, sama di kedua mode) ----
    cek("2.1 findSessionId chat_session.id", wFindSessionId(inst, JSONObject("""{"data":{"biz_data":{"chat_session":{"id":"sess-123"}}}}""")) == "sess-123")
    cek("2.2 findSessionId fallback biz_data.id", wFindSessionId(inst, JSONObject("""{"data":{"biz_data":{"id":"biz-9"}}}""")) == "biz-9")
    cek("2.3 findSessionId data langsung (tanpa biz_data)", wFindSessionId(inst, JSONObject("""{"data":{"id":"d-1"}}""")) == "d-1")
    cek("2.4 findSessionId id numerik -> string", wFindSessionId(inst, JSONObject("""{"data":{"biz_data":{"id":123}}}""")) == "123")
    cek("2.5 findSessionId chat_session blank -> biz id", wFindSessionId(inst, JSONObject("""{"data":{"biz_data":{"chat_session":{"id":""},"id":"biz-2"}}}""")) == "biz-2")
    cek("2.6 findSessionId tidak ada -> null", wFindSessionId(inst, JSONObject("""{"data":{"biz_data":{}}}""")) == null)
    cek("2.7 findSessionId tanpa data -> null", wFindSessionId(inst, JSONObject("""{"x":1}""")) == null)
    cek("2.8 findChallenge objek -> dirinya", wFindChallenge(inst, JSONObject("""{"data":{"biz_data":{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abc"}}}}"""))?.optString("challenge") == "abc")
    cek("2.9 findChallenge non-objek -> seluruh biz", wFindChallenge(inst, JSONObject("""{"data":{"biz_data":{"challenge":"str","id":"b1"}}}"""))?.optString("id") == "b1")
    cek("2.10 findChallenge tidak ada -> null", wFindChallenge(inst, JSONObject("""{"data":{"biz_data":{}}}""")) == null)
    cek("2.11 findChallenge tanpa data -> null", wFindChallenge(inst, JSONObject("""{}""")) == null)
    cek("2.12 findFileId biz.id", wFindFileId(inst, JSONObject("""{"data":{"biz_data":{"id":"f-1"}}}""")) == "f-1")
    cek("2.13 findFileId file_id", wFindFileId(inst, JSONObject("""{"data":{"biz_data":{"file_id":"f-2"}}}""")) == "f-2")
    cek("2.14 findFileId file.id", wFindFileId(inst, JSONObject("""{"data":{"biz_data":{"file":{"id":"f-3"}}}}""")) == "f-3")
    cek("2.15 findFileId uploaded_file.id", wFindFileId(inst, JSONObject("""{"data":{"biz_data":{"uploaded_file":{"id":"f-4"}}}}""")) == "f-4")
    cek("2.16 findFileId file objek id blank -> null (TAK coba uploaded_file)", wFindFileId(inst, JSONObject("""{"data":{"biz_data":{"file":{"id":""},"uploaded_file":{"id":"f-5"}}}}""")) == null)
    cek("2.17 findFileId tidak ada -> null", wFindFileId(inst, JSONObject("""{"data":{"biz_data":{}}}""")) == null)
    cek("2.18 findFileId data langsung", wFindFileId(inst, JSONObject("""{"data":{"id":"f-6"}}""")) == "f-6")
    cek("2.19 estTokens 340/3.4 = 100", wEstTokens(inst, 340, 3.4) == 100L)
    cek("2.20 estTokens 0 -> 0", wEstTokens(inst, 0, 3.4) == 0L)
    cek("2.21 estTokens negatif -> 0", wEstTokens(inst, -5, 3.4) == 0L)
    cek("2.22 estTokens 1/3.4 -> 1 (min 1)", wEstTokens(inst, 1, 3.4) == 1L)
    cek("2.23 estTokens 5/2.0 = 3 (2.5 dibulatkan ke atas, Java)", wEstTokens(inst, 5, 2.0) == 3L)
    cek("2.24 estTokens 3/2.0 = 2 (1.5 -> 2)", wEstTokens(inst, 3, 2.0) == 2L)
    cek("2.25 estTokens 7/2.0 = 4 (3.5 -> 4)", wEstTokens(inst, 7, 2.0) == 4L)
    cek("2.26 estTokens 6/3.4 = 2", wEstTokens(inst, 6, 3.4) == 2L)
    cek("2.27 estTokens perChar 0 -> Long.MAX_VALUE", wEstTokens(inst, 10, 0.0) == Long.MAX_VALUE)
    cek("2.28 estTokens perChar negatif -> min 1", wEstTokens(inst, 5, -1.0) == 1L)
    if (rust) {
        cek("2.29 seam safeFileName: hanya \" yang diganti _", DsApiSeam.safeFileName("a/b\"c<d>.txt") == "a/b_c<d>.txt")
        cek("2.30 seam safeFileName: spasi dipertahankan", DsApiSeam.safeFileName("laporan 2026.pdf") == "laporan 2026.pdf")
        cek("2.31 seam safeFileName: emoji -> __", DsApiSeam.safeFileName("😀.png") == "__.png")
        cek("2.32 seam safeFileName: kosong -> file.bin", DsApiSeam.safeFileName("") == "file.bin")
        cek("2.33 seam safeFileName: spasi saja -> file.bin", DsApiSeam.safeFileName("   ") == "file.bin")
        // Diferensial seam vs ekspresi inline Kotlin (uploadPostKotlin).
        val kotlinSafe = "a/b\"c<d>.txt".map { c -> if (c.code in 32..126 && c != '"') c else '_' }.joinToString("").ifBlank { "file.bin" }
        cek("2.34 safeFileName Rust == ekspresi inline Kotlin", DsApiSeam.safeFileName("a/b\"c<d>.txt") == kotlinSafe)
        cek("2.35 seam findChallenge non-objek -> biz (langsung)", DsApiSeam.findChallenge("""{"data":{"biz_data":{"challenge":5,"id":"b2"}}}""")?.optString("id") == "b2")
        cek("2.36 seam findFileId file.id (langsung)", DsApiSeam.findFileId("""{"data":{"biz_data":{"file":{"id":"fx"}}}}""") == "fx")
    } else {
        cek("2.29 seam safeFileName null tanpa .so", DsApiSeam.safeFileName("a.txt") == null)
        cek("2.30 seam findChallenge null tanpa .so", DsApiSeam.findChallenge("""{"data":{}}""") == null)
        cek("2.31 seam findFileId null tanpa .so", DsApiSeam.findFileId("""{"data":{}}""") == null)
        // Ekspresi inline Kotlin (yang dipakai uploadPostKotlin) tetap benar sendiri.
        cek("2.32 inline Kotlin safeFileName: hanya \" yang diganti _", "a/b\"c<d>.txt".map { c -> if (c.code in 32..126 && c != '"') c else '_' }.joinToString("").ifBlank { "file.bin" } == "a/b_c<d>.txt")
        cek("2.33 inline Kotlin safeFileName: emoji -> __", "😀.png".map { c -> if (c.code in 32..126 && c != '"') c else '_' }.joinToString("").ifBlank { "file.bin" } == "__.png")
        cek("2.34 inline Kotlin safeFileName: kosong -> file.bin", "".map { c -> if (c.code in 32..126 && c != '"') c else '_' }.joinToString("").ifBlank { "file.bin" } == "file.bin")
        cek("2.35 wrapper findChallenge non-objek -> biz (fallback Kotlin)", wFindChallenge(inst, JSONObject("""{"data":{"biz_data":{"challenge":5,"id":"b2"}}}"""))?.optString("id") == "b2")
        cek("2.36 wrapper findFileId file.id (fallback Kotlin)", wFindFileId(inst, JSONObject("""{"data":{"biz_data":{"file":{"id":"fx"}}}}""")) == "fx")
    }

    // ---- §3. HTTP: mapping hasil transport + upload wire ----
    // postJsonResult(path, code, text, retryAfter, token, powHeader)
    val prTypes: Array<Class<*>> = arrayOf(String::class.java, Int::class.java, String::class.java, String::class.java, String::class.java, String::class.java)
    val okJson = callDs(inst, "postJsonResult", prTypes, "/api/v0/chat/create_session", 200, """{"data":{"biz_data":{"id":"s1"}}}""", null, "tok", null) as JSONObject
    cek("3.1 postJsonResult 200 + JSON -> objek", okJson.optJSONObject("data")?.optJSONObject("biz_data")?.optString("id") == "s1")
    val err429 = callDsErr(inst, "postJsonResult", prTypes, "/api/v0/chat/create_session", 429, "rate limited", "7", "tok", null)
    cek("3.2 postJsonResult 429 + Retry-After 7 -> suffix [retry_after=7]", err429.contains("HTTP 429") && err429.contains("[retry_after=7]"))
    val err429b = callDsErr(inst, "postJsonResult", prTypes, "/api/v0/chat/create_session", 429, "rate limited", null, "tok", null)
    cek("3.3 postJsonResult 429 tanpa Retry-After -> tanpa suffix", err429b.contains("HTTP 429") && !err429b.contains("[retry_after="))
    val errJson = callDsErr(inst, "postJsonResult", prTypes, "/api/v0/chat/create_session", 200, "bukan json", null, "tok", null)
    cek("3.4 postJsonResult 200 bukan JSON -> 'respon bukan JSON'", errJson.contains("respon bukan JSON"))
    val err500 = callDsErr(inst, "postJsonResult", prTypes, "/api/v0/chat/create_session", 500, "boom", null, "tok", "pow")
    cek("3.5 postJsonResult 500 -> 'HTTP 500 di ...'", err500.contains("HTTP 500 di /api/v0/chat/create_session"))
    val grTypes: Array<Class<*>> = arrayOf(String::class.java, Int::class.java, String::class.java)
    val gOk = callDs(inst, "getJsonResult", grTypes, "/api/v0/file/status", 200, """{"ok":true}""") as JSONObject
    cek("3.6 getJsonResult 200 + JSON -> objek", gOk.optBoolean("ok"))
    val gErr = callDsErr(inst, "getJsonResult", grTypes, "/api/v0/file/status", 404, "not found")
    cek("3.7 getJsonResult 404 -> 'HTTP 404 di ...'", gErr.contains("HTTP 404 di /api/v0/file/status"))
    val gErrJson = callDsErr(inst, "getJsonResult", grTypes, "/api/v0/file/status", 200, "xx")
    cek("3.8 getJsonResult 200 bukan JSON -> 'respon bukan JSON'", gErrJson.contains("respon bukan JSON"))
    // Wrapper memanggil HttpSeam.request dengan parameter benar (cek sumber).
    val postBody = funBody(dsSrc, "private fun postJson(")
    cek("3.9 postJson wrapper: HttpSeam.request POST + URL + 20000/30000 + Retry-After",
        postBody.contains("HttpSeam.request") && postBody.contains("\"POST\"") && postBody.contains("https://chat.deepseek.com\$path") &&
        postBody.contains("20000, 30000") && postBody.contains("wantHeaders = listOf(\"Retry-After\")") && postBody.contains("postJsonKotlin("))
    val getBody = funBody(dsSrc, "private fun getJson(")
    cek("3.10 getJson wrapper: HttpSeam.request GET + URL + 20000/30000 + getJsonKotlin",
        getBody.contains("HttpSeam.request") && getBody.contains("\"GET\"") && getBody.contains("https://chat.deepseek.com\$path") &&
        getBody.contains("20000, 30000") && getBody.contains("getJsonKotlin("))
    val upBody = funBody(dsSrc, "private fun uploadPost(")
    cek("3.11 uploadPost wrapper: DsApiSeam.uploadFile + Base64 NO_WRAP + fallback uploadPostKotlin",
        upBody.contains("DsApiSeam.uploadFile") && upBody.contains("Base64.encodeToString(bytes, Base64.NO_WRAP)") &&
        upBody.contains("https://chat.deepseek.com/api/v0/file/upload_file") && upBody.contains("uploadPostKotlin(") &&
        upBody.contains("20000, 120000"))
    if (rust) {
        // Live: ServerSocket lokal (loopback NATIVE Rust.so bekerja — preseden P10).
        val fileBytes = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xFE.toByte(), 'H'.code.toByte(), 'i'.code.toByte())
        // Base64 standar (dengan padding) seperti Android NO_WRAP produksi.
        val b64std = java.util.Base64.getEncoder().encodeToString(fileBytes)
        val (port, captured) = serveLocal(2) { idx ->
            if (idx == 0) Pair(200, """{"code":0,"data":{"biz_data":{"id":"f-1"}}}""")
            else Pair(500, """{"code":500,"msg":"gagal"}""")
        }
        val headers = mapOf(
            "Authorization" to "Bearer tok123",
            "Cookie" to "c=1",
            "User-Agent" to "UA-tes",
            "Content-Type" to "application/json"
        )
        val r1 = DsApiSeam.uploadFile("http://127.0.0.1:$port/api/v0/file/upload_file", headers, b64std, "tes 😀.bin", "application/octet-stream")
        cek("3.12 uploadFile rust: 200 + body JSON", r1 != null && r1.code == 200 && r1.body.contains("f-1"))
        Thread.sleep(300)
        val c0 = synchronized(captured) { if (captured.isNotEmpty()) captured[0] else null }
        cek("3.13 uploadFile rust: POST + path benar", c0 != null && c0.head.startsWith("POST /api/v0/file/upload_file "))
        cek("3.14 uploadFile rust: multipart + boundary tetap", c0 != null && c0.head.lowercase().contains("content-type: multipart/form-data; boundary=----webkitformboundarytb0pxoqr2rl219hu"))
        cek("3.15 uploadFile rust: x-file-size = 6", c0 != null && c0.head.lowercase().contains("x-file-size: 6"))
        cek("3.16 uploadFile rust: Authorization + Cookie + UA diteruskan", c0 != null && c0.head.contains("Authorization: Bearer tok123") && c0.head.contains("Cookie: c=1") && c0.head.contains("User-Agent: UA-tes"))
        cek("3.17 uploadFile rust: Content-Type application/json DITIMPA multipart", c0 != null && !c0.head.lowercase().contains("content-type: application/json"))
        val bodyStr = if (c0 != null) String(c0.body, Charsets.UTF_8) else ""
        cek("3.18 uploadFile rust: body berisi filename aman + mime", bodyStr.contains("filename=\"tes __.bin\"") && bodyStr.contains("Content-Type: application/octet-stream"))
        cek("3.19 uploadFile rust: byte biner 0x00/0xFF utuh di body", c0 != null && c0.body.contains(0x00.toByte()) && c0.body.contains(0xFF.toByte()) && c0.body.contains("Hi".toByteArray(Charsets.UTF_8)[0]))
        val r2 = DsApiSeam.uploadFile("http://127.0.0.1:$port/api/v0/file/upload_file", headers, b64std, "x.bin", "application/octet-stream")
        cek("3.20 uploadFile rust: 500 -> Resp code 500 (bukan null)", r2 != null && r2.code == 500)
        val r3 = DsApiSeam.uploadFile("http://127.0.0.1:1/x", headers, b64std, "x.bin", "application/octet-stream")
        cek("3.21 uploadFile rust: transport gagal -> null (fallback)", r3 == null)
        cek("3.22 uploadFile rust: saklar tetap hidup sesudah gagal transport", DsApiSeam.rustDsApi)
    } else {
        // Mode kotlin: loopback JVM diblokir sandbox — ganti identitas sumber.
        cek("3.12 uploadPostKotlin: blok transport == merge/ (per-baris)", bodyContainsLines(dsSrc, "private fun uploadPostKotlin(", listOf(
            "val boundary = \"----WebKitFormBoundaryTB0pXOQR2RL219Hu\"",
            "conn.requestMethod = \"POST\"",
            "conn.connectTimeout = 20000",
            "conn.readTimeout = 120000",
            "conn.setRequestProperty(\"Content-Type\", \"multipart/form-data; boundary=\" + boundary)",
            "conn.setRequestProperty(\"x-file-size\", bytes.size.toString())",
            "out.write(bytes)"
        )))
        cek("3.13 postJsonKotlin: blok transport == merge/ (per-baris)", bodyContainsLines(dsSrc, "private fun postJsonKotlin(", listOf(
            "conn.requestMethod = \"POST\"",
            "conn.connectTimeout = 20000",
            "conn.readTimeout = 30000",
            "conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }",
            "return postJsonResult(path, code, text, conn.getHeaderField(\"Retry-After\"), token, powHeader)"
        )))
        cek("3.14 getJsonKotlin: blok transport == merge/ (per-baris)", bodyContainsLines(dsSrc, "private fun getJsonKotlin(", listOf(
            "conn.requestMethod = \"GET\"",
            "conn.connectTimeout = 20000",
            "conn.readTimeout = 30000",
            "return getJsonResult(path, code, text)"
        )))
        cek("3.15 postJsonResult: mapping error == merge/ postJson (Retry-After + recordDiag)", funBody(dsSrc, "private fun postJsonResult(").contains("DsFailure.parseRetryAfterHeader") && funBody(dsSrc, "private fun postJsonResult(").contains("DsFailure.retryAfterSuffix") && funBody(dsSrc, "private fun postJsonResult(").contains("recordDiag(path, code, 0, false, token"))
        cek("3.16 getJsonResult: throw 404 == merge/ getJson", funBody(dsSrc, "private fun getJsonResult(").contains("throw Exception(\"HTTP \$code di \$path: \${text.take(250)}\")"))
        cek("3.17 headersMap: set header SAMA dengan headers() lama", funBody(dsSrc, "private fun headersMap(").contains("h[\"Authorization\"] = \"Bearer \$token\"") && funBody(dsSrc, "private fun headersMap(").contains("h[\"x-client-platform\"] = \"web\"") && funBody(dsSrc, "private fun headersMap(").contains("h[\"x-ds-pow-response\"] = powHeader"))
        cek("3.18 headers(conn): delegasi ke headersMap", funBody(dsSrc, "private fun headers(").contains("for ((k, v) in headersMap(token, cookie, ua, powHeader)) conn.setRequestProperty(k, v)"))
        cek("3.19 uploadFile: poll loop fetch_files + deadline 120000 utuh", dsSrc.contains("getJson(\"/api/v0/file/fetch_files?file_ids=\" + fileId") && dsSrc.contains("System.currentTimeMillis() + 120000"))
        cek("3.20 uploadFile: findFileId + SERVER check utuh", dsSrc.contains("val fileId = findFileId(root) ?: return null") && dsSrc.contains("throw Exception(\"SERVER \${root.optInt(\"code\")}: \${root.optString(\"msg\")}\")"))
        cek("3.21 ds_api.rs upload: boundary + base64 + x-file-size (cek sumber Rust)", dsApiRs.contains("----WebKitFormBoundaryTB0pXOQR2RL219Hu") && dsApiRs.contains("STANDARD as B64") && dsApiRs.contains(".decode(") && dsApiRs.contains("x-file-size"))
        cek("3.22 ds_api.rs upload: Content-Type multipart menimpa application/json", dsApiRs.contains("multipart/form-data; boundary="))
    }

    // ---- §4. SSE diferensial ----
    val (rc, rt, rf) = if (rust) runSseRust() else Triple("<<SKIP>>", "", false)
    val (kc, kt, kf) = runSseKotlin(inst)
    cek("4.1 SSE kotlin: content 'jawaban!'", kc == "jawaban!")
    cek("4.2 SSE kotlin: thinking 'hmm halo '", kt == "hmm halo ")
    cek("4.3 SSE kotlin: sawFinish true (status FINISHED resmi)", kf)
    cek("4.4 SSE kotlin: label status fragmen TIDAK bocor ke content", !kc.contains("FINISHED"))
    if (rust) {
        cek("4.5 SSE rust: content 'jawaban!'", rc == "jawaban!")
        cek("4.6 SSE rust: thinking 'hmm halo '", rt == "hmm halo ")
        cek("4.7 SSE rust: sawFinish true", rf)
        cek("4.8 SSE rust == kotlin: content identik", rc == kc)
        cek("4.9 SSE rust == kotlin: thinking identik", rt == kt)
        cek("4.10 SSE rust == kotlin: sawFinish identik", rf == kf)
        cek("4.11 postCompletion: pakai sseApply + sawFinish var (cek sumber)", dsSrc.contains("HttpSeam.sseApply(sseStateJson, payload)") && dsSrc.contains("var sawFinish = false") && dsSrc.contains("sawFinish = step.sawFinish"))
        cek("4.12 postCompletion: onPartial throttle 250ms + recordDiag utuh", dsSrc.contains("now - lastPartialAt > 250") && dsSrc.contains("recordDiag(\"completion\", code, dataLines, sawFinish, token"))
    } else {
        cek("4.5 postCompletion kotlin: jalur applyChunkKotlin + state.sawFinish", dsSrc.contains("applyChunkKotlin(obj, content, thinkingBuf, state)") && dsSrc.contains("sawFinish = state.sawFinish"))
        cek("4.6 postCompletion: onPartial throttle 250ms + recordDiag utuh", dsSrc.contains("now - lastPartialAt > 250") && dsSrc.contains("recordDiag(\"completion\", code, dataLines, sawFinish, token"))
        cek("4.7 sseApply rust TIDAK dipakai di mode kotlin (saklar mati)", !rust)
        cek("4.8 HttpSeam.sseInitState dipakai postCompletion", dsSrc.contains("HttpSeam.sseInitState()"))
        cek("4.9 applyChunkKotlin: BATCH rekursif utuh", funBody(dsSrc, "private fun applyChunkKotlin(").contains("applyChunkKotlin(sub, content, thinking, state)"))
        cek("4.10 applyChunkKotlin: flip FINISHED fragmen berpikir utuh", funBody(dsSrc, "private fun applyChunkKotlin(").contains("if (v == \"FINISHED\")"))
        cek("4.11 applyValueObjectKotlin: deklarasi fragmen + content utuh", funBody(dsSrc, "private fun applyValueObjectKotlin(").contains("declareFragmentKotlin(state, idx, isThink)"))
        cek("4.12 routeIsThinkingKotlin: fragmen -1 = berjalan utuh", funBody(dsSrc, "private fun routeIsThinkingKotlin(").contains("if (raw == -1) return state.currentThinking"))
    }

    // ---- §5. Stream/PoW wiring (cek sumber) ----
    val solveBody = funBody(dsSrc, "private fun solvePowHeader(")
    cek("5.1 solvePowHeader: postJson create_pow_challenge", solveBody.contains("postJson(\"/api/v0/chat/create_pow_challenge\""))
    cek("5.2 solvePowHeader: findChallenge + PowSolver.solveDeepSeek", solveBody.contains("findChallenge(powResp)") && solveBody.contains("PowSolver.solveDeepSeek(challenge, salt, expireAt, difficulty)"))
    cek("5.3 solvePowHeader: Base64 NO_WRAP", solveBody.contains("Base64.encodeToString(powJson.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)"))
    cek("5.4 solvePowHeader: error 'challenge tidak ditemukan' utuh", solveBody.contains("create_pow_challenge: objek challenge tidak ditemukan"))
    cek("5.5 solvePowHeader: dipanggil postCompletion + uploadFile", dsSrc.contains("solvePowHeader(\"/api/v0/chat/completion\"") && dsSrc.contains("solvePowHeader(\"/api/v0/file/upload_file\""))
    val completeBody = funBody(dsSrc, "private fun completeWithSession(")
    cek("5.6 completeWithSession: parent_message_id NULL", completeBody.contains(".put(\"parent_message_id\", JSONObject.NULL)"))
    cek("5.7 completeWithSession: solvePowHeader completion + postCompletion", completeBody.contains("solvePowHeader(\"/api/v0/chat/completion\"") && completeBody.contains("postCompletion(body, token, cookie, ua, powHeader, onPartial)"))
    cek("5.8 postCompletion: koneksi POST completion + timeout 20000/240000", funBody(dsSrc, "private fun postCompletion(").contains("\"https://chat.deepseek.com/api/v0/chat/completion\"") && funBody(dsSrc, "private fun postCompletion(").contains("conn.readTimeout = 240000"))
    cek("5.9 postCompletion: blank -> rate_limit/stream_terputus/jawaban_kosong utuh", dsSrc.contains("rawStr.contains(\"rate_limit\")") && dsSrc.contains("stream_terputus") && dsSrc.contains("jawaban_kosong"))
    cek("5.10 postCompletion: SERVER code -> throw SERVER utuh", funBody(dsSrc, "private fun postCompletion(").contains("throw Exception(\"SERVER \${obj.optInt(\"code\")}: \${obj.optString(\"msg\")}\")"))

    // ---- §6. Perilaku vc321/vc322/vc327 dipertahankan ----
    cek("6.1 vc327: allowForceFreshVc327 + noteForceFreshVc327 dipanggil", dsSrc.contains("TurnMetrics.allowForceFreshVc327(webView.context, ffKeyVc327") && dsSrc.contains("TurnMetrics.noteForceFreshVc327(webView.context, ffKeyVc327"))
    cek("6.2 vc327: waits non-reviewer [2s,5s,15s], reviewer [2s,5s]", dsSrc.contains("val waits2 = if (reviewer) longArrayOf(2_000, 5_000) else longArrayOf(2_000, 5_000, 15_000)"))
    cek("6.3 vc327: migratePrimedOnceVc327 dipanggil", dsSrc.contains("TurnMetrics.migratePrimedOnceVc327(ctx, accumKeyVc321"))
    cek("6.4 vc327: reset accum di cabang create utuh", dsSrc.contains("TurnMetrics.resetServerAccumVc321(ctx, accumKeyVc321)"))
    val agentSrc = src(treeDir, "AgentPrompt.kt")
    cek("6.5 vc327: deltaTurnPromptVc327 ada di AgentPrompt + dipakai MainActivity", agentSrc.contains("fun deltaTurnPromptVc327(") && src(treeDir, "MainActivity.kt").contains("AgentPrompt.deltaTurnPromptVc327("))
    cek("6.6 vc321/vc322: isSessionInvalidSignal delegasi DsFailure", dsSrc.contains("private fun isSessionInvalidSignal(msg: String?): Boolean = DsFailure.isSessionInvalidSignal(msg)"))
    cek("6.7 vc321: createServerSession tepat 2 call site", dsSrc.split("createServerSession(token, cookie, ua)").size - 1 == 2)
    cek("6.8 vc322: deltaPrompt diteruskan chat -> runChat", dsSrc.contains("runChat(prompt, thinking, search, onPartial, forceFresh = forceFresh, bgMode = bgMode, deltaPrompt = deltaPrompt)"))
    cek("6.9 vc327: pesan 'sesi server baru ditahan vc327' utuh", dsSrc.contains("[sesi server baru ditahan vc327: stream putus berulang"))
    cek("6.10 vc321: guard reset proaktif utuh (shouldResetServerSessionVc321)", dsSrc.contains("TurnMetrics.shouldResetServerSessionVc321(accumVc321, prompt.length)") && dsSrc.contains("Wadah server di-reset proaktif vc321"))

    // ---- §7. Panen cookie tetap Kotlin ----
    cek("7.1 extractToken: evaluateJavascript + localStorage userToken", dsSrc.contains("webView.evaluateJavascript(") && dsSrc.contains("localStorage.getItem('userToken')"))
    cek("7.2 CookieManager.getInstance dipakai (runChat + upload path)", dsSrc.contains("CookieManager.getInstance().getCookie(\"https://chat.deepseek.com\")"))
    cek("7.3 snapshotUa + currentCookie ada", dsSrc.contains("private fun snapshotUa()") && dsSrc.contains("fun currentCookie()"))
    cek("7.4 dsCaptureShim + inject fetch/XHR utuh", dsSrc.contains("private val dsCaptureShim") && dsSrc.contains("window.fetch=function"))
    // Kode Rust (bukan komentar //! di kepala file): tanpa CookieManager/WebView/host.
    val rsCode = dsApiRs.lines().filter { val t = it.trim(); !t.startsWith("//") && !t.startsWith("///") && !t.startsWith("//!") && !t.startsWith("*") }.joinToString("\n")
    cek("7.5 ds_api.rs: TANPA CookieManager/WebView (kode)", !rsCode.contains("CookieManager") && !rsCode.contains("WebView") && !rsCode.contains("evaluateJavascript"))
    cek("7.6 ds_api.rs: TANPA host chat.deepseek.com hardcode", !dsApiRs.contains("chat.deepseek.com"))
    cek("7.7 DsApiClient: panen token via WebView (bukan Rust)", dsSrc.contains("extractToken()") && !rsCode.contains("userToken"))
    cek("7.8 HttpSeam: TANPA panen cookie (bukan bagiannya)", !src(treeDir, "HttpSeam.kt").contains("CookieManager"))

    // ---- §8. *Kotlin identik merge/ vc327 ----
    cek("8.1 applyChunkKotlin == merge applyChunk (norm)", sameFunBodyNorm(dsSrc, "private fun applyChunkKotlin(", mergeDs, "private fun applyChunk("))
    cek("8.2 applyValueObjectKotlin == merge applyValueObject (norm)", sameFunBodyNorm(dsSrc, "private fun applyValueObjectKotlin(", mergeDs, "private fun applyValueObject("))
    cek("8.3 routeIsThinkingKotlin == merge routeIsThinking (norm)", sameFunBodyNorm(dsSrc, "private fun routeIsThinkingKotlin(", mergeDs, "private fun routeIsThinking("))
    cek("8.4 declareFragmentKotlin == merge declareFragment (norm)", sameFunBodyNorm(dsSrc, "private fun declareFragmentKotlin(", mergeDs, "private fun declareFragment("))
    cek("8.5 findSessionIdKotlin == merge findSessionId (norm)", sameFunBodyNorm(dsSrc, "private fun findSessionIdKotlin(", mergeDs, "private fun findSessionId("))
    cek("8.6 findChallengeKotlin == merge findChallenge (norm)", sameFunBodyNorm(dsSrc, "private fun findChallengeKotlin(", mergeDs, "private fun findChallenge("))
    cek("8.7 findFileIdKotlin == merge findFileId (norm)", sameFunBodyNorm(dsSrc, "private fun findFileIdKotlin(", mergeDs, "private fun findFileId("))
    cek("8.8 estTokensKotlin == merge estTokens (norm)", sameFunBodyNorm(dsSrc, "private fun estTokensKotlin(", mergeDs, "private fun estTokens("))
    cek("8.9 postJsonKotlin: blok transport == merge postJson (per-baris)", bodyContainsLines(dsSrc, "private fun postJsonKotlin(", listOf(
        "val conn = URL(\"https://chat.deepseek.com\$path\").openConnection() as HttpURLConnection",
        "conn.requestMethod = \"POST\"",
        "conn.connectTimeout = 20000",
        "conn.readTimeout = 30000",
        "headers(conn, token, cookie, ua, powHeader)",
        "conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }",
        "val code = conn.responseCode"
    )))
    cek("8.10 getJsonKotlin: blok transport == merge getJson (per-baris)", bodyContainsLines(dsSrc, "private fun getJsonKotlin(", listOf(
        "val conn = URL(\"https://chat.deepseek.com\$path\").openConnection() as HttpURLConnection",
        "conn.requestMethod = \"GET\"",
        "conn.connectTimeout = 20000",
        "conn.readTimeout = 30000",
        "headers(conn, token, cookie, ua, null)",
        "val code = conn.responseCode"
    )))
    cek("8.11 uploadPostKotlin: blok transport == merge uploadFile (per-baris)", bodyContainsLines(dsSrc, "private fun uploadPostKotlin(", listOf(
        "val boundary = \"----WebKitFormBoundaryTB0pXOQR2RL219Hu\"",
        "conn.requestMethod = \"POST\"",
        "conn.connectTimeout = 20000",
        "conn.readTimeout = 120000",
        "headers(conn, token, cookie, ua, powHeader)",
        "conn.setRequestProperty(\"Content-Type\", \"multipart/form-data; boundary=\" + boundary)",
        "conn.setRequestProperty(\"x-file-size\", bytes.size.toString())",
        "out.write(bytes)"
    )))
    cek("8.12 DsApiClient: TIDAK ada lagi fun applyChunk/applyValueObject/routeIsThinking/declareFragment tanpa Kotlin", !dsSrc.contains("fun applyChunk(") && !dsSrc.contains("fun applyValueObject(") && !dsSrc.contains("fun routeIsThinking(") && !dsSrc.contains("fun declareFragment("))

    // ---- §9. Batas slice ----
    cek("9.1 SlotClient TANPA Vc328", !slotSrc.contains("Vc328"))
    cek("9.2 TurnMetrics TANPA Vc328", !src(treeDir, "TurnMetrics.kt").contains("Vc328"))
    cek("9.3 Flavor TANPA Vc328", !src(treeDir, "Flavor.kt").contains("Vc328"))
    cek("9.4 GroupChat + BgBrain + MainActivity TANPA Vc328", !src(treeDir, "GroupChat.kt").contains("Vc328") && !src(treeDir, "BgBrain.kt").contains("Vc328") && !src(treeDir, "MainActivity.kt").contains("Vc328"))
    cek("9.5 ds_api.rs forbid(unsafe_code)", dsApiRs.contains("#![forbid(unsafe_code)]"))
    val rsProd = dsApiRs.substringBefore("mod tests")
    cek("9.6 ds_api.rs produksi: TANPA unwrap()/expect()", !rsProd.contains("unwrap()") && !rsProd.contains("expect("))
    val tomlDeps = cargoToml.lines().dropWhile { it.trim() != "[dependencies]" }.drop(1)
        .takeWhile { !it.trim().startsWith("[") }
        .mapNotNull { Regex("^([A-Za-z0-9_-]+)[ ]*=").find(it)?.groupValues?.get(1) }.toSet()
    cek("9.7 Cargo.toml: dependensi TETAP 8 crate (tanpa dep baru)", tomlDeps == setOf("jni", "serde_json", "httpdate", "regex", "rusqlite", "base64", "ureq", "sha2"))
    val lockPkgs = cargoLock.lines().map { it.trim() }.filter { it.startsWith("name = \"") }
        .map { it.removePrefix("name = \"").removeSuffix("\"") }.toSet()
    cek("9.8 Cargo.lock: paket TETAP 106 paket (tanpa paket baru)", lockPkgs == setOf("ahash", "aho-corasick", "base64", "bitflags", "block-buffer", "bytes", "cc", "cesu8", "cfg-if", "combine", "cpufeatures", "crypto-common", "digest", "displaydoc", "fallible-iterator", "fallible-streaming-iterator", "find-msvc-tools", "form_urlencoded", "generic-array", "getrandom", "harness-core", "hashbrown", "hashlink", "httpdate", "icu_collections", "icu_locale_core", "icu_normalizer", "icu_normalizer_data", "icu_properties", "icu_properties_data", "icu_provider", "idna", "idna_adapter", "itoa", "jni", "jni-sys", "jni-sys-macros", "libc", "libsqlite3-sys", "litemap", "log", "memchr", "once_cell", "percent-encoding", "pkg-config", "potential_utf", "proc-macro2", "quote", "regex", "regex-automata", "regex-syntax", "ring", "rusqlite", "rustls", "rustls-pki-types", "rustls-webpki", "same-file", "serde", "serde_core", "serde_derive", "serde_json", "sha2", "shlex", "smallvec", "stable_deref_trait", "subtle", "syn", "synstructure", "thiserror", "thiserror-impl", "tinystr", "typenum", "unicode-ident", "untrusted", "ureq", "url", "utf8_iter", "vcpkg", "version_check", "walkdir", "wasi", "webpki-roots", "winapi-util", "windows-link", "windows-sys", "windows-targets", "windows_aarch64_gnullvm", "windows_aarch64_msvc", "windows_i686_gnu", "windows_i686_gnullvm", "windows_i686_msvc", "windows_x86_64_gnu", "windows_x86_64_gnullvm", "windows_x86_64_msvc", "writeable", "yoke", "yoke-derive", "zerocopy", "zerocopy-derive", "zerofrom", "zerofrom-derive", "zeroize", "zerotrie", "zerovec", "zerovec-derive", "zmij"))
    cek("9.9 DsApiSeam.kt ada + pola HttpSeam (opRaw + saklar)", seamSrc.contains("object DsApiSeam") && seamSrc.contains("var rustDsApi = RustCore.available") && seamSrc.contains("fun uploadFile("))
    cek("9.10 ds_api.rs: 6 op dikenal (find_session_id..upload_file)", dsApiRs.contains("\"find_session_id\"") && dsApiRs.contains("\"find_challenge\"") && dsApiRs.contains("\"find_file_id\"") && dsApiRs.contains("\"est_tokens\"") && dsApiRs.contains("\"safe_file_name\"") && dsApiRs.contains("\"upload_file\""))

    // ---- §10. Saklar fallback ----
    // Matikan seam, lalu semua wrapper harus kembali ke *Kotlin dengan nilai SAMA.
    val seamWas = DsApiSeam.rustDsApi
    DsApiSeam.rustDsApi = false
    val instOff = allocDsApi(false)
    cek("10.1 seam mati: findSessionId null", DsApiSeam.findSessionId("""{"data":{"biz_data":{"id":"x"}}}""") == null)
    cek("10.2 seam mati: findChallenge null", DsApiSeam.findChallenge("""{"data":{"biz_data":{"challenge":{"a":1}}}}""") == null)
    cek("10.3 seam mati: findFileId null", DsApiSeam.findFileId("""{"data":{"biz_data":{"id":"x"}}}""") == null)
    cek("10.4 seam mati: estTokens null", DsApiSeam.estTokens(10, 2.0) == null)
    cek("10.5 seam mati: safeFileName null", DsApiSeam.safeFileName("a.txt") == null)
    cek("10.6 seam mati: uploadFile null", DsApiSeam.uploadFile("http://127.0.0.1:1/x", emptyMap(), "aGk=", "f.txt", "text/plain") == null)
    cek("10.7 wrapper saklar mati: findSessionId tetap benar (fallback Kotlin)", wFindSessionId(instOff, JSONObject("""{"data":{"biz_data":{"id":"biz-9"}}}""")) == "biz-9")
    cek("10.8 wrapper saklar mati: estTokens tetap benar (fallback Kotlin)", wEstTokens(instOff, 340, 3.4) == 100L)
    cek("10.9 wrapper saklar mati: findFileId tetap benar (fallback Kotlin)", wFindFileId(instOff, JSONObject("""{"data":{"biz_data":{"file":{"id":"f-3"}}}}""")) == "f-3")
    cek("10.10 wrapper saklar mati: SSE kotlin tetap 'jawaban!'/'hmm halo '/finish", runSseKotlin(instOff) == Triple("jawaban!", "hmm halo ", true))
    DsApiSeam.rustDsApi = seamWas

    println("== TestDsApiSplitVc328 mode=$mode SELESAI: $lulus/$total lulus, $gagal gagal ==")
    if (gagal > 0) {
        println("HASIL: GAGAL ($gagal dari $total)")
        kotlin.system.exitProcess(1)
    } else {
        println("HASIL: LULUS ($total/$total)")
    }
}
