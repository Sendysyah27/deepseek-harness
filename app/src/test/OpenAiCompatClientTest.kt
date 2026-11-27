package com.senzwh.deepharness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.BufferedReader
import java.io.StringReader
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

// Test JVM murni (tanpa Android) untuk OpenAiCompatClient:
//   T1 parse SSE tool_calls, T2 delta streaming, T3 jawaban kosong,
//   T4 error HTTP. Dijalankan oleh tools/run_tests.sh.
// Tes HTTP memakai server lokal (com.sun.net.httpserver, JDK) —
// TIDAK ada jaringan luar, TIDAK ada key asli (key tes: sk-test-FAKEKEY123456).
// CATATAN SANDBOX: server mock di-bind ke ::1 (IPv6 loopback) — koneksi TCP
// IPv4 127.0.0.1 dari proses Java diblokir filter jaringan sandbox ini,
// sementara ::1 lolos (dibuktikan repro 26 Nov 2026).

private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failed++; println("  FAIL $name" + detailSuffix)
    }
}

private const val FAKE_KEY = "sk-test-FAKEKEY123456"

private fun startServer(handler: (HttpExchange) -> Unit): HttpServer {
    val server = HttpServer.create(InetSocketAddress("::1", 0), 0)
    server.createContext("/") { ex ->
        try {
            handler(ex)
        } catch (e: Exception) {
            try { ex.sendResponseHeaders(500, -1) } catch (_: Exception) {}
        } finally {
            try { ex.responseBody.close() } catch (_: Exception) {}
        }
    }
    server.executor = null
    server.start()
    return server
}

private fun baseUrl(server: HttpServer): String =
    "http://[::1]:${server.address.port}"

private fun respond(ex: HttpExchange, code: Int, body: String, contentType: String = "application/json") {
    val bytes = body.toByteArray(Charsets.UTF_8)
    ex.responseHeaders.set("Content-Type", contentType)
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

private fun respondSse(ex: HttpExchange, chunks: List<String>) {
    ex.responseHeaders.set("Content-Type", "text/event-stream")
    ex.sendResponseHeaders(200, 0) // chunked
    ex.responseBody.use { out ->
        for (c in chunks) {
            out.write(c.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }
}

private fun requestBody(ex: HttpExchange): String =
    ex.requestBody.bufferedReader(Charsets.UTF_8).readText()

private fun testProvider(server: HttpServer): LlmProvider =
    LlmProvider("t1", "Tes", LlmKind.OPENAI_COMPAT, baseUrl(server), FAKE_KEY, "m1")

fun main() {
    println("== OpenAiCompatClientTest ==")

    // ---------------------------------------------------------- T1: tool_calls SSE
    run {
        val sse = """
            data: {"model":"m1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","type":"function","function":{"name":"run_terminal","arguments":""}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{\"cmd\":\"ls\"}"}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"/sdcard\"}"}}]}}]}
            data: {"choices":[{"finish_reason":"tool_calls","delta":{}}]}
            data: [DONE]
        """.trimIndent()
        var deltaCount = 0
        val outcome = OpenAiCompatClient.parseSse(
            BufferedReader(StringReader(sse)), {}, { deltaCount++ })
        val calls = outcome.result.toolCalls
        check("T1 parallel 2 index jadi 2 NativeToolCall terpisah", calls.size == 2, "dapat=${calls.size}")
        check("T1 urut index + id+name dari delta pertama",
            calls.getOrNull(0)?.id == "call_1" && calls.getOrNull(0)?.name == "read_file" &&
                calls.getOrNull(1)?.id == "call_2" && calls.getOrNull(1)?.name == "run_terminal",
            "dapat=$calls")
        check("T1 arguments digabung dari delta berikut",
            calls.getOrNull(0)?.argumentsJson == """{"path":"/sdcard"}""" &&
                calls.getOrNull(1)?.argumentsJson == """{"cmd":"ls"}""",
            "dapat=${calls.map { it.argumentsJson }}")
        check("T1 arguments gabungan JSON valid (tidak broken)",
            calls.all { !it.argumentsBroken })
        check("T1 finish_reason tool_calls diteruskan",
            outcome.result.finishReason == "tool_calls", "dapat=${outcome.result.finishReason}")
        check("T1 onDeltaToolCall per fragmen", deltaCount == 5, "dapat=$deltaCount")
        check("T1 model dari chunk pertama", outcome.result.model == "m1", "dapat=${outcome.result.model}")
    }

    // T1: arguments rusak -> broken, bukan crash; mentah disimpan.
    run {
        val sse = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"x","arguments":"{bukan json"}}]}}]}
            data: {"choices":[{"finish_reason":"tool_calls","delta":{}}]}
            data: [DONE]
        """.trimIndent()
        val outcome = OpenAiCompatClient.parseSse(BufferedReader(StringReader(sse)), {}, {})
        val call = outcome.result.toolCalls.singleOrNull()
        check("T1 arguments rusak tidak crash", call != null)
        check("T1 arguments rusak ditandai broken + mentah disimpan",
            call?.argumentsBroken == true && call?.argumentsJson == "{bukan json",
            "dapat=$call")
    }

    // T1: maks 4 per respons — 5 index -> 4 pertama.
    run {
        val sb = StringBuilder()
        for (i in 0..4) {
            sb.appendLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":$i,"id":"c$i","type":"function","function":{"name":"t$i","arguments":"{}"}}]}}]}""")
        }
        sb.appendLine("""data: {"choices":[{"finish_reason":"tool_calls","delta":{}}]}""")
        sb.appendLine("data: [DONE]")
        val outcome = OpenAiCompatClient.parseSse(BufferedReader(StringReader(sb.toString())), {}, {})
        val calls = outcome.result.toolCalls
        check("T1 maks 4 tool call per respons", calls.size == 4, "dapat=${calls.size}")
        check("T1 4 pertama urut index", calls.map { it.index } == listOf(0, 1, 2, 3),
            "dapat=${calls.map { it.index }}")
    }

    // ------------------------------------------- T1: content + tool_calls bersamaan
    run {
        val sse = """
            data: {"choices":[{"delta":{"content":"Saya "}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_7","type":"function","function":{"name":"read_file","arguments":""}}]}}]}
            data: {"choices":[{"delta":{"content":"cek dulu"}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"/x\"}"}}]}}]}
            data: {"choices":[{"finish_reason":"tool_calls","delta":{}}]}
            data: [DONE]
        """.trimIndent()
        val partials = mutableListOf<String>()
        val outcome = OpenAiCompatClient.parseSse(BufferedReader(StringReader(sse)), { partials.add(it) }, {})
        check("T1 content + tool_calls: content gabung", outcome.result.content == "Saya cek dulu", "dapat=${outcome.result.content}")
        val call = outcome.result.toolCalls.singleOrNull()
        check("T1 content + tool_calls: tool call ikut ada",
            call?.id == "call_7" && call?.name == "read_file" &&
                call?.argumentsJson == """{"path":"/x"}""" && !call.argumentsBroken,
            "dapat=$call")
        check("T1 content + tool_calls: finish_reason tool_calls", outcome.result.finishReason == "tool_calls")
        check("T1 content + tool_calls: onPartial tetap per fragmen content",
            partials == listOf("Saya ", "cek dulu"), "dapat=$partials")
    }
    run {
        // Non-stream: message.content + message.tool_calls bersamaan.
        val server = startServer { ex ->
            val body = requestBody(ex)
            if (body.contains("\"stream\":true")) {
                ex.sendResponseHeaders(500, -1)
                return@startServer
            }
            respond(ex, 200, """{"model":"m1","choices":[{"message":{"role":"assistant","content":"Saya cek dulu","tool_calls":[{"id":"call_8","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"/y\"}"}}]},"finish_reason":"tool_calls"}]}""")
        }
        try {
            var done: ChatResult? = null
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "baca file")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = it }, onError = { err = it })
            check("T1 non-stream content + tool_calls: keduanya ada",
                done?.content == "Saya cek dulu" && done?.toolCalls?.size == 1,
                "dapat=$done err=$err")
            val call = done?.toolCalls?.singleOrNull()
            check("T1 non-stream content + tool_calls: isi tool call benar",
                call?.id == "call_8" && call?.name == "read_file" &&
                    call?.argumentsJson == """{"path":"/y"}""" && call?.argumentsBroken == false,
                "dapat=$call")
            check("T1 non-stream content + tool_calls: finish_reason tool_calls",
                done?.finishReason == "tool_calls", "dapat=${done?.finishReason}")
        } finally {
            server.stop(0)
        }
    }

    // ---------------------------------------------------------- T2: streaming
    run {
        val sse = """
            data: {"choices":[{"delta":{"content":"Hel"}}]}
            data: {"choices":[{"delta":{"content":"lo","reasoning_content":"pikir"}}]}
            data: {"choices":[{"delta":{"reasoning_content":"-lanjut"}}]}
            data: {"choices":[{"finish_reason":"stop","delta":{}}]}
            data: [DONE]
        """.trimIndent()
        val partials = mutableListOf<String>()
        val outcome = OpenAiCompatClient.parseSse(BufferedReader(StringReader(sse)), { partials.add(it) }, {})
        check("T2 onPartial dipanggil PER baris data:", partials == listOf("Hel", "lo"), "dapat=$partials")
        check("T2 content gabung", outcome.result.content == "Hello", "dapat=${outcome.result.content}")
        check("T2 reasoning_content terkumpul ke thinking",
            outcome.result.thinking == "pikir-lanjut", "dapat=${outcome.result.thinking}")
        check("T2 finish_reason stop diteruskan", outcome.result.finishReason == "stop")
    }

    // T2 end-to-end HTTP: streaming beneran lewat socket.
    run {
        val server = startServer { ex ->
            respondSse(ex, listOf(
                "data: {\"model\":\"m1\",\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"lo\",\"reasoning_content\":\"pikir\"}}]}\n",
                "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            val partials = mutableListOf<String>()
            var done: ChatResult? = null
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = { partials.add(it) }, onDeltaToolCall = {},
                onDone = { done = it }, onError = { err = it })
            check("T2 HTTP onPartial per chunk", partials == listOf("Hel", "lo"), "dapat=$partials err=$err")
            check("T2 HTTP onDone content+thinking+finish",
                done?.content == "Hello" && done?.thinking == "pikir" && done?.finishReason == "stop",
                "dapat=$done err=$err")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------- T1/T2 end-to-end: tool_calls HTTP
    run {
        val server = startServer { ex ->
            respondSse(ex, listOf(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]}}]}\n",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\\\"/x\\\"}\"}}]}}]}\n",
                "data: {\"choices\":[{\"finish_reason\":\"tool_calls\",\"delta\":{}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            var done: ChatResult? = null
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "baca file")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = it }, onError = { err = it })
            val call = done?.toolCalls?.singleOrNull()
            check("T1 HTTP tool_call utuh di onDone",
                call?.id == "call_9" && call?.name == "read_file" &&
                    call?.argumentsJson == """{"path":"/x"}""" && call?.argumentsBroken == false,
                "dapat=$done err=$err")
            check("T1 HTTP finish_reason tool_calls", done?.finishReason == "tool_calls")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------- request body: tools / image / thinking
    run {
        var captured = ""
        val server = startServer { ex ->
            captured = requestBody(ex)
            respondSse(ex, listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n",
                "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            val tools = JSONArray().put(
                JSONObject().put("type", "function").put("function",
                    JSONObject().put("name", "read_file")
                        .put("description", "baca file")
                        .put("parameters", JSONObject().put("type", "object")))
            )
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "jelaskan gambar ini", "https://example.invalid/x.png")),
                tools, true,
                onPartial = {}, onDeltaToolCall = {},
                onDone = {}, onError = { err = it })
            val body = JSONObject(captured)
            check("request tools terkirim", body.optJSONArray("tools")?.length() == 1, "body=$captured err=$err")
            check("request tool_choice auto bila tools tidak kosong",
                body.optString("tool_choice") == "auto", "body=$captured")
            check("request TIDAK kirim parallel_tool_calls", !body.has("parallel_tool_calls"), "body=$captured")
            check("request thinking_enabled terkirim bila true",
                body.optBoolean("thinking_enabled", false), "body=$captured")
            val content = body.optJSONArray("messages")?.optJSONObject(0)?.opt("content")
            val partsOk = content is JSONArray &&
                content.length() == 2 &&
                content.optJSONObject(0)?.optString("type") == "text" &&
                content.optJSONObject(1)?.optString("type") == "image_url" &&
                content.optJSONObject(1)?.optJSONObject("image_url")?.optString("url") == "https://example.invalid/x.png"
            check("request imageUrl diserialisasi content parts text+image_url", partsOk, "body=$captured")
        } finally {
            server.stop(0)
        }
    }
    run {
        var captured = ""
        val server = startServer { ex ->
            captured = requestBody(ex)
            respondSse(ex, listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n",
                "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = {}, onError = {},
                toolChoice = "none")
            val body = JSONObject(captured)
            // vc335 R3 (D7 — DIPUTUSKAN, membatalkan ekspektasi lama di
            // sini): call penutup pakai tools=null, BUKAN
            // tool_choice:"none" — pemanggil yang masih mengoper "none"
            // tanpa tools tetap menghasilkan body TANPA tool_choice.
            check("D7: tool_choice none TANPA tools TIDAK dikirim",
                !body.has("tool_choice"), "body=$captured")
            check("request thinking_enabled TIDAK dikirim bila false",
                !body.has("thinking_enabled"), "body=$captured")
            check("request tanpa tools tidak kirim kunci tools", !body.has("tools"), "body=$captured")
        } finally {
            server.stop(0)
        }
    }

    // ---------------------------------------------------------- T3: jawaban kosong
    run {
        // SSE semua baris non-JSON -> onError (BUKAN onDone ""), TANPA fallback.
        val requests = AtomicInteger(0)
        val server = startServer { ex ->
            requests.incrementAndGet()
            respondSse(ex, listOf(
                "data: bukan json\n",
                "data: {invalid\n",
                "data: [DONE]\n"
            ))
        }
        try {
            var done = false
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = true }, onError = { err = it })
            check("T3 SSE non-JSON -> onError, bukan onDone", !done && err != null, "err=$err")
            check("T3 SSE non-JSON pesan sebut jumlah baris dilewati",
                err?.contains("2 baris respons dilewati") == true, "err=$err")
            check("T3 format tak dikenal TIDAK fallback ke non-stream",
                requests.get() == 1, "request=${requests.get()}")
        } finally {
            server.stop(0)
        }
    }
    run {
        // choices:[] di semua baris -> onError.
        val server = startServer { ex ->
            respondSse(ex, listOf(
                "data: {\"choices\":[]}\n",
                "data: {\"choices\":[]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            var done = false
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = true }, onError = { err = it })
            check("T3 choices:[] -> onError", !done && err?.contains("Jawaban kosong") == true, "err=$err")
        } finally {
            server.stop(0)
        }
    }
    run {
        // content:null -> onError.
        val server = startServer { ex ->
            respondSse(ex, listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            var done = false
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = true }, onError = { err = it })
            check("T3 content:null -> onError", !done && err?.contains("Jawaban kosong") == true, "err=$err")
        } finally {
            server.stop(0)
        }
    }
    run {
        // finish_reason length + kosong -> onError menyebut length.
        val server = startServer { ex ->
            respondSse(ex, listOf(
                "data: {\"choices\":[{\"finish_reason\":\"length\",\"delta\":{}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = {}, onError = { err = it })
            check("T3 kosong + finish_reason length disebut di pesan",
                err?.contains("length") == true, "err=$err")
        } finally {
            server.stop(0)
        }
    }
    run {
        // Non-stream content kosong -> onError (stream digagalkan 500 agar fallback jalan).
        val server = startServer { ex ->
            val body = requestBody(ex)
            if (body.contains("\"stream\":true")) {
                ex.sendResponseHeaders(500, -1)
                return@startServer
            }
            respond(ex, 200, """{"choices":[{"message":{"content":"","finish_reason":"stop"},"finish_reason":"stop"}]}""")
        }
        try {
            var done = false
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = true }, onError = { err = it })
            check("T3 non-stream kosong -> onError", !done && err?.contains("Jawaban kosong") == true, "err=$err")
        } finally {
            server.stop(0)
        }
    }
    run {
        // ask() jawaban kosong -> throw.
        val server = startServer { ex ->
            respond(ex, 200, """{"choices":[{"message":{"content":""},"finish_reason":"stop"}]}""")
        }
        try {
            var thrown: String? = null
            try {
                OpenAiCompatClient.ask(testProvider(server), FAKE_KEY, "hai")
            } catch (e: Exception) {
                thrown = e.message
            }
            check("T3 ask() kosong -> throw menyebut provider",
                thrown?.contains("Jawaban kosong dari t1") == true, "thrown=$thrown")
        } finally {
            server.stop(0)
        }
    }
    run {
        // ask() sukses tetap mengembalikan isi + thinking.
        val server = startServer { ex ->
            respond(ex, 200, """{"model":"m1","choices":[{"message":{"content":"halo","reasoning_content":"pikir"}}]}""")
        }
        try {
            val res = OpenAiCompatClient.ask(testProvider(server), FAKE_KEY, "hai")
            check("ask() sukses kembalikan content+thinking",
                res.content == "halo" && res.thinking == "pikir", "res=$res")
        } finally {
            server.stop(0)
        }
    }
    run {
        // Pengecualian D4: finish_reason tool_calls + toolCalls terisi = sukses.
        val server = startServer { ex ->
            respondSse(ex, listOf(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"x\",\"arguments\":\"{}\"}}]}}]}\n",
                "data: {\"choices\":[{\"finish_reason\":\"tool_calls\",\"delta\":{}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            var done: ChatResult? = null
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = it }, onError = { err = it })
            val d = done
            check("T3 tool_calls tanpa content = sukses (bukan kosong)",
                d != null && d.toolCalls.size == 1 && err == null, "done=$done err=$err")
        } finally {
            server.stop(0)
        }
    }

    // ---------------------------------------------------------- T4: error HTTP
    for ((code, serverMsg) in listOf(
        401 to "Incorrect API key provided",
        404 to "model tidak ditemukan",
        429 to "rate limit terlampaui",
        500 to "kesalahan server internal"
    )) {
        run {
            val server = startServer { ex ->
                respond(ex, code, """{"error":{"message":"$serverMsg: Bearer $FAKE_KEY"}}""")
            }
            try {
                var err: String? = null
                OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                    listOf(ChatMessage("user", "hai")), null, false,
                    onPartial = {}, onDeltaToolCall = {},
                    onDone = {}, onError = { err = it })
                check("T4 HTTP $code menyebut kode + pesan server",
                    err?.contains("$code") == true && err?.contains(serverMsg) == true, "err=$err")
                check("T4 HTTP $code key TIDAK bocor ke pesan",
                    err?.contains(FAKE_KEY) != true, "err=$err")
            } finally {
                server.stop(0)
            }
        }
    }
    run {
        // Respons 200 bukan JSON (HTML) di stream -> onError jelas.
        val server = startServer { ex ->
            respond(ex, 200, "<html><body>blokir</body></html>", "text/html")
        }
        try {
            var done = false
            var err: String? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = true }, onError = { err = it })
            check("T4 stream 200 HTML -> onError jelas", !done && err != null, "err=$err")
            check("T4 stream 200 HTML isi HTML tidak ditampilkan mentah",
                err?.contains("<html>") != true, "err=$err")
        } finally {
            server.stop(0)
        }
    }
    run {
        // Respons bukan JSON di non-stream (ask) -> throw jelas.
        val server = startServer { ex ->
            respond(ex, 200, "<html>login dulu</html>", "text/html")
        }
        try {
            var thrown: String? = null
            try {
                OpenAiCompatClient.ask(testProvider(server), FAKE_KEY, "hai")
            } catch (e: Exception) {
                thrown = e.message
            }
            check("T4 ask() non-JSON -> throw menyebut bukan JSON",
                thrown?.contains("bukan JSON") == true, "thrown=$thrown")
        } finally {
            server.stop(0)
        }
    }
    run {
        // chat() lama DIHAPUS R6 (P-2: jalur pintas tanpa Governor/D4) —
        // pemanggil harus lewat chatMessages (dipakai helper LlmEngineChat).
        // Satu pesan user via chatMessages tetap jalan sama.
        val server = startServer { ex ->
            respondSse(ex, listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok-lama\"}}]}\n",
                "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n",
                "data: [DONE]\n"
            ))
        }
        try {
            var done: ChatResult? = null
            OpenAiCompatClient.chatMessages(baseUrl(server), FAKE_KEY, "m1",
                listOf(ChatMessage("user", "hai")), null, false,
                onPartial = {}, onDeltaToolCall = {},
                onDone = { done = it }, onError = {})
            check("chatMessages satu pesan user tetap jalan (pengganti chat() lama)",
                done?.content == "ok-lama", "done=$done")
        } finally {
            server.stop(0)
        }
    }

    println(if (failed == 0) "SEMUA LULUS" else "GAGAL: $failed cek")
    if (failed != 0) kotlin.system.exitProcess(1)
}
