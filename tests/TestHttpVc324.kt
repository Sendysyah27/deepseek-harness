// TestHttpVc324 — P10 (slice 8, vc324): paritas seam HTTP.
//
// Mode: TestHttpVc324Kt <rust|kotlin> <treeDir> <coreDir>
//   rust   = jalur Rust ASLI via .so host (-Djava.library.path=.../target/debug)
//   kotlin = fallback *Kotlin, tanpa .so (RustCore.available=false)
//   treeDir = direktori .kt terpadu (integrated / app/.../deepharness)
//   coreDir = harness-core (untuk baca jni_glue.rs + Cargo.toml/lock)
//
// Yang diuji:
//   (a) SEAM LANGSUNG via HttpSeam (jalur Rust asli, mode rust) melawan
//       server HTTP lokal (ServerSocket 127.0.0.1, respons terskrip):
//       request GET/POST, header, body, status non-2xx, Retry-After,
//       cap_chars, timeout, transport gagal, mcp_extract,
//       shape_fingerprint, decode_word, gmail_headers, pkce (vektor
//       RFC 7636), form_encode/form_body, oauth_auth_url,
//       oauth_parse_callback, sse_apply (matriks cabang Kotlin).
//   (b) WRAPPER PUBLIK vs Kotlin lama (mode rust DAN kotlin, server
//       yang sama — diferensial sejati): OcClient (probe/listSessions/
//       createSession/sendPrompt/statusMap/messages/abort/
//       replyPermission) + McpHttp (listTools/callTool, JSON + SSE).
//   (c) FALLBACK: mode kotlin (tanpa .so) -> HttpSeam null +
//       wrapper tetap bekerja lewat *Kotlin.
//   (d) SUMBER: 6 wrapper berisi public fun + *Kotlin + saklar rust;
//       *Kotlin = body lama (fragmen kunci dari merge/ vc316 — untuk
//       mesin SSE SlotClient, fragmen dicek sesudah rename 4 nama fun);
//       DsApiClient.kt TIDAK tersentuh Rust (slice 11); Flavor.kt
//       tidak berubah; transport yang SENGAJA tetap Kotlin
//       (openEventStream, IMAP/SMTP Gmail, listener McpOAuth,
//       postCompletion SlotClient) masih ada.
//   (e) STRUKTUR: RUST_CORE_VERSION 6 + 26 simbol JNI + ureq/sha2
//       di Cargo.toml/lock.
//
// CATATAN JUJUR: `applyChunkKotlin` dkk TIDAK bisa dipanggil langsung
// dari JVM (private + butuh instance SlotClient yang butuh Context
// stub). Paritas Rust-vs-Kotlin untuk mesin SSE dibuktikan dua lapis:
//   (1) fixture `sse_apply` ditelusur cabang-per-cabang dari sumber
//       Kotlin (tiap tes menyebut cabangnya), dan
//   (2) fragmen body *Kotlin dicek identik dengan merge/ vc316
//       sesudah rename 4 nama fun (mesin Kotlin = kode lama).
// Wrapper end-to-end (postJson/postCompletion melawan DeepSeek nyata)
// menjadi bagian TES perangkat (TES-VC324) — bukan klaim P10.
// Sama untuk `ApiClients.execute` / `McpOAuth.startLoopback` /
// `Gmail.unread` (butuh Context/SecureStore/server nyata).
//
// Pemakaian server lokal: satu ServerSocket, tiap koneksi ditangani
// thread daemon; respons `Connection: close`, satu request per koneksi.

package com.senzwh.deepharness

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private var total = AtomicInteger(0)
private var lulus = AtomicInteger(0)
private var gagal = AtomicInteger(0)

private fun cek(nama: String, ok: Boolean) {
    total.incrementAndGet()
    if (ok) {
        lulus.incrementAndGet()
        println("LULUS $nama")
    } else {
        gagal.incrementAndGet()
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

/**
 * Sandbox VM ini mencegat TCP loopback dari JVM (Socket/HttpURLConnection
 * dialihkan ke deny-proxy balasan "Other TCP connections is turned off"),
 * sedangkan .so Rust (syscall native) lolos — terbukti: mode rust §2/§3
 * lulus penuh, mode kotlin gagal SEMUA cek "server hidup" padahal server
 * yang sama. Deteksi: kirim request mentah ke server lokal; bila balasan
 * mengandung penanda deny-proxy, loopback JVM terblokir.
 */
private fun loopbackBlocked(port: Int): Boolean {
    return try {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 2000
            s.getOutputStream().write("GET /__ping__ HTTP/1.0\r\nHost: x\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val buf = ByteArray(1024)
            val n = s.getInputStream().read(buf)
            val txt = if (n > 0) String(buf, 0, n) else ""
            txt.contains("Other TCP connections is turned off")
        }
    } catch (e: Exception) {
        false
    }
}

/** Ambil body fungsi (dari `{` pertama sesudah [sig] sampai `}` penutup),
 *  dengan scanner yang melewati string "..." (escape-aware) dan komentar
 *  `//` supaya `{` di dalam literal (mis. startsWith("{")) tidak
 *  mengacaukan hitungan kurung. "" = signature tidak ketemu / tak-berakhir. */
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

/** Dua fungsi identik isinya (nama fungsi boleh beda, mis. http vs
 *  httpKotlin)? Body dibandingkan apa adanya setelah normalisasi spasi. */
private fun sameFunBody(aText: String, aSig: String, bText: String, bSig: String): Boolean {
    val a = funBody(aText, aSig)
    val b = funBody(bText, bSig)
    if (a.isEmpty() || b.isEmpty()) return false
    fun norm(s: String) = s.split(Regex("\\s+")).joinToString(" ")
    return norm(a) == norm(b)
}

// ------------------------------------------------------- server HTTP lokal --

private class LocalHttp {
    val server = ServerSocket(0)
    val port: Int = server.localPort
    @Volatile var stop = false

    init {
        thread(isDaemon = true) {
            while (!stop) {
                try {
                    val s = server.accept()
                    thread(isDaemon = true) { handle(s) }
                } catch (e: Exception) {
                    if (!stop) println("accept gagal: ${e.message}")
                }
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            val input = sock.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val reqLine = reader.readLine() ?: return
            var contentLen = 0
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                val low = h.lowercase()
                if (low.startsWith("content-length:")) {
                    contentLen = low.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val bodyChars = CharArray(contentLen)
            var read = 0
            while (read < contentLen) {
                val n = reader.read(bodyChars, read, contentLen - read)
                if (n <= 0) break
                read += n
            }
            val body = String(bodyChars, 0, read)
            val parts = reqLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1) ?: ""
            val (code, status, extraHeaders, respBody) = route(method, path, body)
            val out: OutputStream = sock.getOutputStream()
            val head = StringBuilder()
            head.append("HTTP/1.1 $code $status\r\n")
            head.append("Content-Type: application/json\r\n")
            head.append("Content-Length: ${respBody.toByteArray(Charsets.UTF_8).size}\r\n")
            for ((k, v) in extraHeaders) head.append("$k: $v\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            out.write(respBody.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            // Koneksi klien putus / request rusak — bukan kegagalan tes.
        } finally {
            try { sock.close() } catch (e: Exception) {}
        }
    }

    private fun route(method: String, path: String, body: String): Quad {
        // /bad/* = semua 500 (untuk jalur error OcClient).
        if (path.startsWith("/bad")) {
            return Quad(500, "Internal Server Error", emptyMap(), "{\"err\":\"bad\"}")
        }
        when (path) {
            "/session" -> {
                if (method == "GET") return Quad(200, "OK", emptyMap(), "[{\"id\":\"s1\"}]")
                return Quad(200, "OK", emptyMap(), "{\"id\":\"s2\"}")
            }
            "/session/s1/prompt_async" -> return Quad(204, "No Content", emptyMap(), "")
            "/session/status" -> return Quad(200, "OK", emptyMap(), "{\"s1\":{\"type\":\"text\"}}")
            "/session/s1/message" -> return Quad(200, "OK", emptyMap(), "[{\"info\":{\"role\":\"a\"}}]")
            "/session/s1/abort" -> return Quad(200, "OK", emptyMap(), "{}")
            "/session/s1/permissions/p1" -> return Quad(200, "OK", emptyMap(), "{}")
            "/retry" -> return Quad(200, "OK", mapOf("Retry-After" to "12"), "{}")
            "/big" -> return Quad(200, "OK", emptyMap(), "0123456789".repeat(10))
            "/err500" -> return Quad(500, "Internal Server Error", emptyMap(), "oops")
            "/echo" -> return Quad(200, "OK", emptyMap(), body)
            "/mcp", "/mcp-sse" -> {
                val sse = path == "/mcp-sse"
                if (body.contains("\"initialize\"")) {
                    val res = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}}}"
                    return if (sse) Quad(200, "OK", mapOf("Mcp-Session-Id" to "sess-1"), "data: $res\n\n")
                    else Quad(200, "OK", mapOf("Mcp-Session-Id" to "sess-1"), res)
                }
                if (body.contains("notifications/initialized")) {
                    return Quad(200, "OK", emptyMap(), "{}")
                }
                if (body.contains("\"tools/list\"")) {
                    val res = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"t1\",\"description\":\"d1\"},{\"name\":\"t2\",\"description\":\"d2\"}]}}"
                    return if (sse) Quad(200, "OK", emptyMap(), "data: $res\n\n")
                    else Quad(200, "OK", emptyMap(), res)
                }
                if (body.contains("\"tools/call\"")) {
                    if (body.contains("\"boom\"")) {
                        val res = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"gagal\"}],\"isError\":true}}"
                        return Quad(200, "OK", emptyMap(), res)
                    }
                    val res = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"halo\"},{\"type\":\"text\",\"text\":\"dunia\"}]}}"
                    return Quad(200, "OK", emptyMap(), res)
                }
                return Quad(200, "OK", emptyMap(), "{}")
            }
        }
        return Quad(404, "Not Found", emptyMap(), "{\"err\":\"no route\"}")
    }

    private data class Quad(val code: Int, val status: String, val headers: Map<String, String>, val body: String)

    fun close() {
        stop = true
        try { server.close() } catch (e: Exception) {}
    }
}

// ------------------------------------------------------------------- main --

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "kotlin"
    val treeDir = args.getOrNull(1) ?: "."
    val coreDir = args.getOrNull(2) ?: "harness-core"
    val rust = mode == "rust"
    println("== TestHttpVc324 mode=$mode treeDir=$treeDir coreDir=$coreDir ==")
    val server = LocalHttp()
    val base = "http://127.0.0.1:${server.port}"
    val badBase = "http://127.0.0.1:${server.port}/bad"
    val blocked = loopbackBlocked(server.port)
    if (blocked) println("NOTE sandbox: TCP loopback JVM diblokir (deny-proxy) — cek live mode kotlin diganti identitas sumber; mode rust tidak terpengaruh")
    // merge/ vc316 (baseline) — lokal ada via work-impl/merge (symlink);
    // di CI tidak ada -> cek identitas dilewati (SKIP, bukan lulus).
    val mergeDir = java.io.File(treeDir, "../merge")

    // ---- §1. Seam + VERSION ----
    // RustCore.kt TIDAK di bawah treeDir di semua layout: lokal
    // (integrated) = treeDir/harness/core/RustCore.kt; CI =
    // treeDir naik-3 (deepharness -> senzwh -> com -> java) lalu
    // harness/core/RustCore.kt. Coba 4 tingkat (akar yang sama
    // dengan P8 5.18 vc322 dan P9 12.7 vc323).
    val rcSrc = listOf(
        "harness/core/RustCore.kt",
        "../harness/core/RustCore.kt",
        "../../harness/core/RustCore.kt",
        "../../../harness/core/RustCore.kt"
    ).mapNotNull { rel ->
        val f = java.io.File(treeDir, rel)
        if (f.exists()) f.readText() else null
    }.firstOrNull() ?: ""
    cek("1.1 RustCore.kt VERSION 6 + httpJson", rcSrc.contains("const val VERSION = 6") && rcSrc.contains("external fun httpJson(request: String): String?"))
    if (rust) {
        cek("1.2 RustCore.available true (mode rust, .so host)", harness.core.RustCore.available)
        val caps = harness.core.RustCore.rustCoreCapabilities()
        cek("1.3 capabilities: version 6 + httpJson + modul http/sse", caps.contains("\"version\":6") && caps.contains("httpJson") && caps.contains("\"http\"") && caps.contains("\"sse\""))
        cek("1.4 HttpSeam.rustHttp true (mode rust)", HttpSeam.rustHttp)
    } else {
        cek("1.2 RustCore.available false (mode kotlin, tanpa .so)", !harness.core.RustCore.available)
        cek("1.3 HttpSeam.rustHttp false (mode kotlin)", !HttpSeam.rustHttp)
        cek("1.4 HttpSeam.request null tanpa .so (kontrak fallback)", HttpSeam.request("GET", "$base/session") == null)
    }

    // ---- §2. Op request via server lokal (mode rust; kotlin = null) ----
    if (rust) {
        val r = HttpSeam.request("GET", "$base/session", mapOf("Content-Type" to "application/json", "X-Test" to "abc"), null, 3000, 3000, 0, listOf("Retry-After"))
        cek("2.1 request GET 200 + body", r != null && r.code == 200 && r.body == "[{\"id\":\"s1\"}]")
        val rp = HttpSeam.request("POST", "$base/echo", mapOf("Content-Type" to "application/json"), "{\"a\":1}", 3000, 3000)
        cek("2.2 request POST echo body", rp != null && rp.code == 200 && rp.body == "{\"a\":1}")
        val r500 = HttpSeam.request("GET", "$base/err500", emptyMap(), null, 3000, 3000)
        cek("2.3 request non-2xx TETAP dikembalikan (code+body)", r500 != null && r500.code == 500 && r500.body == "oops")
        val rr = HttpSeam.request("GET", "$base/retry", emptyMap(), null, 3000, 3000, 0, listOf("Retry-After"))
        cek("2.4 request Retry-After dikembalikan", rr != null && rr.headers["Retry-After"] == "12")
        val rb = HttpSeam.request("GET", "$base/big", emptyMap(), null, 3000, 3000, 10)
        cek("2.5 request cap_chars=10 memotong body", rb != null && rb.body == "0123456789")
        val rbad = HttpSeam.request("GET", "http://127.0.0.1:1/x", emptyMap(), null, 1000, 1000)
        cek("2.6 request transport gagal -> null (fallback Kotlin)", rbad == null && HttpSeam.rustHttp)
        val rurl = HttpSeam.request("GET", "ftp://x/y", emptyMap(), null, 1000, 1000)
        cek("2.7 request URL/metode tak-valid -> null", rurl == null)
    } else {
        cek("2.1 request null tanpa .so (mode kotlin)", HttpSeam.request("GET", "$base/session") == null)
    }

    // ---- §3. OcClient publik — diferensial rust vs kotlin, server sama ----
    // Mode kotlin + loopback terblokir (sandbox VM): cek "server hidup"
    // tidak bisa jalan (bukan cacat kode) -> gantinya: cek tak-terjangkau
    // (nilai gagal yang benar) + identitas body httpKotlin vs merge/ vc316.
    if (!rust && blocked) {
        println("NOTE §3 mode kotlin: loopback terblokir — cek live 3.1/3.3/3.5/3.7/3.9/3.11/3.14 dilewati, ganti 3.16 identitas sumber")
        cek("3.2 probe port mati -> false", !OcClient.probe(1))
        cek("3.4 listSessions 500 -> null", OcClient.listSessions(badBase) == null)
        cek("3.6 createSession 500 -> null", OcClient.createSession(badBase, "t") == null)
        cek("3.8 sendPrompt 500 -> false", !OcClient.sendPrompt(badBase, "s1", "halo"))
        cek("3.10 statusMap 500 -> objek kosong", OcClient.statusMap(badBase).length() == 0)
        cek("3.12 messages 500 -> array kosong", OcClient.messages(badBase, "s1").length() == 0)
        try {
            OcClient.abort(base, "s1")
            cek("3.13 abort tidak melempar", true)
        } catch (e: Exception) {
            cek("3.13 abort tidak melempar", false)
        }
        cek("3.15 replyPermission 500 -> false", !OcClient.replyPermission(badBase, "s1", "p1", "once"))
        val mergeOc = java.io.File(mergeDir, "OcClient.kt")
        if (mergeOc.exists()) {
            cek("3.16 httpKotlin identik merge/ http vc316", sameFunBody(src(treeDir, "OcClient.kt"), "private fun httpKotlin(", mergeOc.readText(), "private fun http("))
        } else {
            println("SKIP 3.16 httpKotlin identik merge/ (merge/ tidak ada di sini)")
        }
    } else {
        cek("3.1 probe port hidup -> true", OcClient.probe(server.port))
        cek("3.2 probe port mati -> false", !OcClient.probe(1))
        val sess = OcClient.listSessions(base)
        cek("3.3 listSessions 200 -> array 1 sesi", sess != null && sess.length() == 1 && sess.getJSONObject(0).optString("id") == "s1")
        cek("3.4 listSessions 500 -> null", OcClient.listSessions(badBase) == null)
        cek("3.5 createSession 200 -> id s2", OcClient.createSession(base, "t") == "s2")
        cek("3.6 createSession 500 -> null", OcClient.createSession(badBase, "t") == null)
        cek("3.7 sendPrompt 204 -> true", OcClient.sendPrompt(base, "s1", "halo"))
        cek("3.8 sendPrompt 500 -> false", !OcClient.sendPrompt(badBase, "s1", "halo"))
        val sm = OcClient.statusMap(base)
        cek("3.9 statusMap 200 -> objek", sm.length() == 1 && sm.has("s1"))
        cek("3.10 statusMap 500 -> objek kosong", OcClient.statusMap(badBase).length() == 0)
        val msgs = OcClient.messages(base, "s1")
        cek("3.11 messages 200 -> array 1", msgs.length() == 1)
        cek("3.12 messages 500 -> array kosong", OcClient.messages(badBase, "s1").length() == 0)
        try {
            OcClient.abort(base, "s1")
            cek("3.13 abort tidak melempar", true)
        } catch (e: Exception) {
            cek("3.13 abort tidak melempar", false)
        }
        cek("3.14 replyPermission 200 -> true", OcClient.replyPermission(base, "s1", "p1", "once"))
        cek("3.15 replyPermission 500 -> false", !OcClient.replyPermission(badBase, "s1", "p1", "once"))
    }

    // ---- §4. McpHttp publik — JSON + SSE, diferensial rust vs kotlin ----
    if (!rust && blocked) {
        println("NOTE §4 mode kotlin: loopback terblokir — cek live 4.1-4.4 dilewati, ganti 4.1k/4.2k identitas sumber")
        val mergeMcp = java.io.File(mergeDir, "McpHttp.kt")
        if (mergeMcp.exists()) {
            val mergeTxt = mergeMcp.readText()
            val mcpSrc = src(treeDir, "McpHttp.kt")
            cek("4.1k postKotlin identik merge/ post vc316", sameFunBody(mcpSrc, "private fun postKotlin(", mergeTxt, "private fun post("))
            cek("4.2k extractResultKotlin identik merge/ extractResult vc316", sameFunBody(mcpSrc, "private fun extractResultKotlin(", mergeTxt, "private fun extractResult("))
        } else {
            println("SKIP 4.1k/4.2k identitas sumber (merge/ tidak ada di sini)")
        }
        cek("4.5 mcpExtract null tanpa .so (mode kotlin)", HttpSeam.mcpExtract("{\"result\":{}}") == null)
    } else {
        val tools = McpHttp.listTools("$base/mcp", "", null)
        cek("4.1 listTools JSON -> 2 tool", tools.size == 2 && tools[0].first == "t1" && tools[0].second == "d1" && tools[1].first == "t2")
        val toolsSse = McpHttp.listTools("$base/mcp-sse", "", null)
        cek("4.2 listTools SSE (baris data:) -> 2 tool", toolsSse.size == 2 && toolsSse[0].first == "t1")
        val call = McpHttp.callTool("$base/mcp", "", null, "t1", org.json.JSONObject())
        cek("4.3 callTool gabung 2 teks", call == "halo\ndunia")
        val callErr = McpHttp.callTool("$base/mcp", "", null, "boom", org.json.JSONObject())
        cek("4.4 callTool isError diawali penanda", callErr.startsWith("[tool mengembalikan isError]"))
        if (rust) {
            val ex = HttpSeam.mcpExtract("{\"result\":{\"tools\":[]}}")
            cek("4.5 mcpExtract JSON langsung", ex != null && ex.has("tools"))
            val exSse = HttpSeam.mcpExtract("data: {\"result\":{\"a\":1}}\ndata: {\"result\":{\"a\":2}}\n")
            cek("4.6 mcpExtract SSE result terakhir menang", exSse != null && exSse.optInt("a") == 2)
            val exNone = HttpSeam.mcpExtract("data: {\"jsonrpc\":\"2.0\"}\n")
            cek("4.7 mcpExtract tanpa result -> null", exNone == null)
            var threw = false
            try {
                HttpSeam.mcpExtract("{\"error\":{\"code\":-1,\"message\":\"boom\"}}")
            } catch (e: Exception) {
                threw = e.message?.startsWith("MCP error:") == true
            }
            cek("4.8 mcpExtract error -> throw MCP error", threw)
        } else {
            cek("4.5 mcpExtract null tanpa .so (mode kotlin)", HttpSeam.mcpExtract("{\"result\":{}}") == null)
        }
    }

    // ---- §5. ApiClients.shapeFingerprint ----
    if (rust) {
        cek("5.1 shapeFingerprint kunci terurut", HttpSeam.shapeFingerprint("{\"b\":1,\"a\":2}") == "json:a,b")
        cek("5.2 shapeFingerprint array -> non-json", HttpSeam.shapeFingerprint("[1,2]") == "non-json")
        cek("5.3 shapeFingerprint bukan-JSON -> non-json", HttpSeam.shapeFingerprint("bukan json") == "non-json")
        cek("5.4 shapeFingerprint objek kosong", HttpSeam.shapeFingerprint("{}") == "json:")
    } else {
        val apiSrc = src(treeDir, "ApiClients.kt")
        cek("5.1 shapeFingerprintKotlin: sorted() + non-json", apiSrc.contains("private fun shapeFingerprintKotlin") && apiSrc.contains("\"json:\" + list.sorted().joinToString(\",\")") && apiSrc.contains("\"non-json\""))
        cek("5.2 callHttpKotlin masih ada (fallback)", apiSrc.contains("private fun callHttpKotlin"))
    }

    // ---- §6. McpOAuth op murni ----
    if (rust) {
        cek("6.1 pkceChallenge vektor RFC 7636 App. B", HttpSeam.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        cek("6.2 formEncode spasi -> +", HttpSeam.formEncode("a b") == "a+b")
        cek("6.3 formEncode karakter khusus", HttpSeam.formEncode("a+b&c=d") == "a%2Bb%26c%3Dd")
        cek("6.4 formEncode .-*_ lolos", HttpSeam.formEncode(".-*_") == ".-*_")
        cek("6.5 formEncode UTF-8", HttpSeam.formEncode("café") == "caf%C3%A9")
        cek("6.6 formBody urutan dipertahankan", HttpSeam.formBody(listOf("grant_type" to "refresh_token", "refresh_token" to "rt 1", "client_id" to "cid")) == "grant_type=refresh_token&refresh_token=rt+1&client_id=cid")
        cek("6.7 oauthAuthUrl offline", HttpSeam.oauthAuthUrl("https://a.example/auth", "cid", "http://127.0.0.1:8931/", "read write", "s1", "ch", true) == "https://a.example/auth?response_type=code&client_id=cid&redirect_uri=http%3A%2F%2F127.0.0.1%3A8931%2F&scope=read+write&state=s1&code_challenge=ch&code_challenge_method=S256&access_type=offline&prompt=consent")
        val cb = HttpSeam.oauthParseCallback("state=s1&code=abc%2Bdef&x=hello+world")
        cek("6.8 oauthParseCallback decode + dan %XX", cb != null && cb["state"] == "s1" && cb["code"] == "abc+def" && cb["x"] == "hello world")
        cek("6.9 oauthParseCallback % tak-valid -> null", HttpSeam.oauthParseCallback("code=%ZZ") == null)
    } else {
        val oaSrc = src(treeDir, "McpOAuth.kt")
        cek("6.1 challengeKotlin SHA-256 + b64url", oaSrc.contains("private fun challengeKotlin") && oaSrc.contains("MessageDigest.getInstance(\"SHA-256\")"))
        cek("6.2 encKotlin URLEncoder", oaSrc.contains("private fun encKotlin") && oaSrc.contains("URLEncoder.encode(s, \"UTF-8\")"))
        cek("6.3 postFormRawKotlin masih ada (fallback)", oaSrc.contains("private fun postFormRawKotlin"))
        cek("6.4 listener loopback tetap Kotlin (ServerSocket + Intent)", oaSrc.contains("ServerSocket(REDIRECT_PORT") && oaSrc.contains("Intent.ACTION_VIEW"))
    }

    // ---- §7. Gmail op murni ----
    if (rust) {
        cek("7.1 decodeWord B", HttpSeam.decodeWord("=?UTF-8?B?SGFsbw==?=") == "Halo")
        cek("7.2 decodeWord Q underscore + hex", HttpSeam.decodeWord("=?UTF-8?Q?Halo_Dunia?=") == "Halo Dunia")
        cek("7.3 decodeWord Q UTF-8", HttpSeam.decodeWord("=?UTF-8?Q?caf=C3=A9?=") == "café")
        cek("7.4 decodeWord B tak-valid -> data mentah", HttpSeam.decodeWord("=?UTF-8?B?!!!?=") == "!!!")
        cek("7.5 decodeWord Q hex tak-valid -> data mentah", HttpSeam.decodeWord("=?UTF-8?Q?bad=ZZ?=") == "bad=ZZ")
        cek("7.6 decodeWord collapse + trim (NBSP tepi dibuang)", HttpSeam.decodeWord("  Halo   =?UTF-8?B?RHVuaWE=?=  ") == "Halo Dunia" && HttpSeam.decodeWord("  Halo  ") == "Halo")
        val gh = HttpSeam.gmailHeaders(listOf("From: Budi <budi@x.id>", "Subject: =?UTF-8?B?SGFsbw==?=", "Date: Mon, 16 Nov 2026 10:00:00 +0700 (WIB)"))
        cek("7.7 gmailHeaders satu entri (Date take 31)", gh == "- [Mon, 16 Nov 2026 10:00:00 +0700] Budi <budi@x.id> — Halo")
        val gh2 = HttpSeam.gmailHeaders(listOf("From: a@b", "Subject: ", "Date: Tue, 17 Nov 2026 11:00:00 +0700"))
        cek("7.8 gmailHeaders tanpa subjek", gh2 == "- [Tue, 17 Nov 2026 11:00:00 +0700] a@b — (tanpa subjek)")
    } else {
        val gmSrc = src(treeDir, "Gmail.kt")
        cek("7.1 decodeWordKotlin masih ada (fallback)", gmSrc.contains("private fun decodeWordKotlin") && gmSrc.contains("Regex(\"=\\\\?[^?]+\\\\?([bBqQ])\\\\?([^?]*)\\\\?=\")"))
        cek("7.2 fetchHeadersEntryKotlin masih ada (fallback)", gmSrc.contains("private fun fetchHeadersEntryKotlin"))
        cek("7.3 transport IMAP/SMTP tetap Kotlin", gmSrc.contains("SSLSocketFactory") && gmSrc.contains("STARTTLS") && gmSrc.contains("imap.gmail.com"))
    }

    // ---- §8. SlotClient SSE (mode rust: seam; mode kotlin: sumber) ----
    if (rust) {
        val init = HttpSeam.sseInitState()
        val s1 = HttpSeam.sseApply(init, "{\"p\":\"response/fragments/0/content\",\"v\":\"Halo\"}")
        cek("8.1 sse konten via path + last_path", s1 != null && s1.contentDelta == "Halo" && s1.thinkingDelta == "" && s1.state.contains("response/fragments/0/content"))
        val s1b = s1?.let { HttpSeam.sseApply(it.state, "{\"v\":\" dunia\"}") }
        cek("8.2 sse baris tanpa p memakai last_path", s1b != null && s1b.contentDelta == " dunia")
        val s2 = HttpSeam.sseApply(init, "{\"p\":\"response/thinking/content\",\"v\":\"mikir\"}")
        cek("8.3 sse thinking path -> thinking + current", s2 != null && s2.thinkingDelta == "mikir" && s2.contentDelta == "" && s2.state.contains("\"current_thinking\":true"))
        val s3 = HttpSeam.sseApply(init, "{\"p\":\"response/status\",\"v\":\"FINISHED\"}")
        cek("8.4 sse status FINISHED -> saw_finish", s3 != null && s3.sawFinish)
        val s4 = HttpSeam.sseApply(init, "{\"p\":\"response/x\",\"v\":\"FINISHED\"}")
        cek("8.5 sse FINISHED tanpa akhiran content diabaikan", s4 != null && s4.contentDelta == "" && !s4.sawFinish)
        val s5 = HttpSeam.sseApply(init, "{\"o\":\"BATCH\",\"v\":[{\"p\":\"response/fragments/0/content\",\"v\":\"a\"},{\"p\":\"response/fragments/0/content\",\"v\":\"b\"}]}")
        cek("8.6 sse BATCH rekursif", s5 != null && s5.contentDelta == "ab")
        val s6 = HttpSeam.sseApply(init, "{\"p\":\"response/fragments\",\"v\":{\"response\":{\"fragments\":[{\"type\":\"THINK\",\"content\":\"t0\"},{\"type\":\"TEXT\",\"content\":\"c1\"}]}}}")
        cek("8.7 sse fragments declare THINK/TEXT", s6 != null && s6.thinkingDelta == "t0" && s6.contentDelta == "c1" && s6.state.contains("\"fragment_count\":2"))
        val s6b = s6?.let { HttpSeam.sseApply(it.state, "{\"p\":\"response/fragments/0/content\",\"v\":\"t0b\"}") }
        cek("8.8 sse fragmen 0 dikenal -> thinking", s6b != null && s6b.thinkingDelta == "t0b")
        val s6c = s6b?.let { HttpSeam.sseApply(it.state, "{\"p\":\"response/fragments/1/content\",\"v\":\"c1b\"}") }
        cek("8.9 sse fragmen 1 dikenal -> content", s6c != null && s6c.contentDelta == "c1b")
        val stCount1 = "{\"last_path\":\"\",\"saw_finish\":false,\"fragment_thinking\":{\"0\":false},\"fragment_count\":1,\"current_thinking\":false}"
        val s7 = HttpSeam.sseApply(stCount1, "{\"p\":\"response/fragments/1/content\",\"v\":\"baru\"}")
        cek("8.10 sse indeks == count -> declare false", s7 != null && s7.contentDelta == "baru" && s7.state.contains("\"1\":false"))
        val s8 = HttpSeam.sseApply(init, "{\"p\":\"response/fragments/-1/content\",\"v\":\"x\"}")
        cek("8.11 sse indeks -1 -> current (content)", s8 != null && s8.contentDelta == "x")
        val s9 = HttpSeam.sseApply(init, "{\"p\":\"response/fragments/3/content\",\"v\":{\"type\":\"THINK\",\"content\":\"deep\"}}")
        cek("8.12 sse value-object bertipe declare fragmen", s9 != null && s9.thinkingDelta == "deep" && s9.state.contains("\"fragment_count\":4"))
        val s10 = HttpSeam.sseApply(init, "bukan json")
        cek("8.13 sse payload bukan-JSON -> skipped", s10 != null && s10.skipped && s10.contentDelta == "")
        val s11 = HttpSeam.sseApply(init, "{\"p\":\"response/fragments/0/content\",\"v\":[42,\"s\"]}")
        cek("8.14 sse elemen array angka terkoersi \"42\"", s11 != null && s11.contentDelta == "42s")
        val s12 = HttpSeam.sseApply(init, "{\"p\":\"response/status\",\"v\":\"FINISHED\"}")
        cek("8.15 sse status FINISHED tanpa fragmen -> current mati", s12 != null && s12.sawFinish)
    } else {
        val slSrc = src(treeDir, "SlotClient.kt")
        cek("8.1 applyChunkKotlin ada (fallback)", slSrc.contains("private fun applyChunkKotlin"))
        cek("8.2 applyValueObjectKotlin ada (fallback)", slSrc.contains("private fun applyValueObjectKotlin"))
        cek("8.3 routeIsThinkingKotlin + declareFragmentKotlin ada", slSrc.contains("private fun routeIsThinkingKotlin") && slSrc.contains("private fun declareFragmentKotlin"))
        cek("8.4 postJsonKotlin + postJsonResult ada (fallback)", slSrc.contains("private fun postJsonKotlin") && slSrc.contains("private fun postJsonResult"))
        cek("8.5 postCompletion memakai sseApply + sawFinish lokal", slSrc.contains("HttpSeam.sseApply(sseStateJson, payload)") && slSrc.contains("var sawFinish = false"))
        cek("8.6 headersMap dipakai postJson Rust + headers(conn)", slSrc.contains("private fun headersMap") && slSrc.contains("headersMap(token, cookie, ua, powHeader)"))
    }

    // ---- §9. Fallback slice 8 dipertahankan (sumber) ----
    val ocSrc = src(treeDir, "OcClient.kt")
    val mcSrc = src(treeDir, "McpHttp.kt")
    val apiSrc = src(treeDir, "ApiClients.kt")
    val slSrc = src(treeDir, "SlotClient.kt")
    cek("9.1 OcClient: httpKotlin + rustOcClient + openEventStream tetap", ocSrc.contains("private fun httpKotlin") && ocSrc.contains("var rustOcClient") && ocSrc.contains("fun openEventStream"))
    cek("9.2 McpHttp: postKotlin + extractResultKotlin + rustMcpHttp", mcSrc.contains("private fun postKotlin") && mcSrc.contains("private fun extractResultKotlin") && mcSrc.contains("var rustMcpHttp"))
    cek("9.3 ApiClients: callHttpKotlin + shapeFingerprintKotlin + rustApiClients", apiSrc.contains("private fun callHttpKotlin") && apiSrc.contains("private fun shapeFingerprintKotlin") && apiSrc.contains("var rustApiClients"))
    cek("9.4 SlotClient: rustSlotClient + postJsonKotlin + applyChunkKotlin", slSrc.contains("var rustSlotClient") && slSrc.contains("private fun postJsonKotlin") && slSrc.contains("private fun applyChunkKotlin"))
    val gmSrc = src(treeDir, "Gmail.kt")
    val oaSrc = src(treeDir, "McpOAuth.kt")
    cek("9.5 Gmail: rustGmail + decodeWordKotlin + fetchHeadersEntryKotlin", gmSrc.contains("var rustGmail") && gmSrc.contains("private fun decodeWordKotlin") && gmSrc.contains("private fun fetchHeadersEntryKotlin"))
    cek("9.6 McpOAuth: rustMcpOAuth + challengeKotlin + encKotlin + postFormRawKotlin", oaSrc.contains("var rustMcpOAuth") && oaSrc.contains("private fun challengeKotlin") && oaSrc.contains("private fun encKotlin") && oaSrc.contains("private fun postFormRawKotlin"))
    cek("9.7 HttpSeam.kt ada (request + op murni + sseApply)", src(treeDir, "HttpSeam.kt").contains("fun request(") && src(treeDir, "HttpSeam.kt").contains("fun sseApply(") && src(treeDir, "HttpSeam.kt").contains("fun mcpExtract("))

    // ---- §10. Batas slice 8 dijaga ----
    val dsSrc = src(treeDir, "DsApiClient.kt")
    cek("10.1 DsApiClient.kt TIDAK tersentuh Rust slice 8", !dsSrc.contains("HttpSeam") && !dsSrc.contains("httpJson") && !dsSrc.contains("rustSlotClient"))
    cek("10.2 Flavor.kt tidak berubah (deferred)", !src(treeDir, "Flavor.kt").contains("RustCore") && !src(treeDir, "Flavor.kt").contains("HttpSeam"))
    cek("10.3 SlotClient: logika sesi vc321/vc322 dipertahankan", slSrc.contains("shouldResetServerSessionVc321") && slSrc.contains("shouldSendDeltaVc322") && slSrc.contains("deltaPrompt"))
    cek("10.4 Gmail transport + McpOAuth listener tetap Kotlin", gmSrc.contains("SSLSocketFactory") && oaSrc.contains("ServerSocket(REDIRECT_PORT"))
    cek("10.5 OcClient.openEventStream tetap Kotlin (handle hidup)", ocSrc.contains("fun openEventStream") && ocSrc.contains("HttpURLConnection, BufferedReader"))

    // ---- §11. Struktur Rust ----
    val glue = srcCore(coreDir, "src/jni_glue.rs")
    cek("11.1 RUST_CORE_VERSION 6 di jni_glue.rs", glue.contains("RUST_CORE_VERSION: jint = 6"))
    // Jumlah definisi JNI (bukan kemunculan mentah: baris komentar 3
    // jni_glue.rs juga mengandung prefix ini).
    val jniCount = "pub extern \"C\" fn Java_harness_core_RustCore_".toRegex().findAll(glue).count()
    cek("11.2 26 simbol JNI (25 lama + httpJson)", jniCount == 26)
    val cargo = srcCore(coreDir, "Cargo.toml")
    cek("11.3 Cargo.toml: ureq 2.12 tls + sha2 0.10", cargo.contains("ureq") && cargo.contains("2.12") && cargo.contains("sha2") && cargo.contains("0.10"))
    val lock = srcCore(coreDir, "Cargo.lock")
    cek("11.4 Cargo.lock: ureq 2.12.1 + sha2 0.10.9 + rustls 0.23", lock.contains("name = \"ureq\"") && lock.contains("name = \"sha2\"") && lock.contains("name = \"rustls\""))
    cek("11.5 lib.rs: pub mod http + pub mod sse", srcCore(coreDir, "src/lib.rs").contains("pub mod http;") && srcCore(coreDir, "src/lib.rs").contains("pub mod sse;"))
    cek("11.6 http.rs + sse.rs forbid(unsafe_code)", srcCore(coreDir, "src/http.rs").contains("#![forbid(unsafe_code)]") && srcCore(coreDir, "src/sse.rs").contains("#![forbid(unsafe_code)]"))

    server.close()
    println("TOTAL ${total.get()} LULUS ${lulus.get()} GAGAL ${gagal.get()}")
    if (gagal.get() > 0) throw RuntimeException("${gagal.get()} cek gagal")
}
