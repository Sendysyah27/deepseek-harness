package com.senzwh.deepharness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONObject

// Test JVM murni (tanpa Android) untuk auto-fetch daftar model (§5.10):
//   T8 listModels + parseModelsList + normalizeBaseUrl.
// Dijalankan oleh tools/run_tests.sh. Tes HTTP memakai server lokal
// (com.sun.net.httpserver, JDK) — TIDAK ada jaringan luar, TIDAK ada key asli.
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

fun main() {
    println("== ModelCatalogTest ==")

    // ------------------------------------------- normalizeBaseUrl
    run {
        val cases = listOf(
            "https://api.openai.com" to "https://api.openai.com/v1",
            "https://api.openai.com/" to "https://api.openai.com/v1",
            "https://api.openai.com/v1" to "https://api.openai.com/v1",
            "https://api.openai.com/v1/" to "https://api.openai.com/v1",
            "https://api.openai.com/v1/models" to "https://api.openai.com/v1",
            "https://api.openai.com/v1/models/" to "https://api.openai.com/v1",
            "https://api.openai.com/v1/chat/completions" to "https://api.openai.com/v1",
            "https://api.openai.com/chat/completions" to "https://api.openai.com/v1",
            "https://api.groq.com/openai/v1" to "https://api.groq.com/openai/v1",
            "  https://api.openai.com/v1  " to "https://api.openai.com/v1",
            "http://localhost:11434" to "http://localhost:11434/v1"
        )
        for ((input, expected) in cases) {
            val got = OpenAiCompatClient.normalizeBaseUrl(input)
            check("normalizeBaseUrl '$input'", got == expected, "dapat='$got'")
        }
        val once = OpenAiCompatClient.normalizeBaseUrl("https://api.openai.com")
        val twice = OpenAiCompatClient.normalizeBaseUrl(once)
        check("normalizeBaseUrl idempotent", once == twice, "sekali='$once' dua='$twice'")
    }

    // ------------------------------------------- fetch sukses format OpenAI
    run {
        val server = startServer { ex ->
            check("listModels GET ke /v1/models",
                ex.requestURI.path == "/v1/models", "path=${ex.requestURI.path}")
            respond(ex, 200, """{"object":"list","data":[
                {"id":"gpt-4o-mini","object":"model","owned_by":"openai"},
                {"id":"gpt-4o","object":"model","owned_by":"openai"},
                {"id":"gpt-4o-mini","object":"model","owned_by":"openai"}
            ]}""")
        }
        try {
            val res = OpenAiCompatClient.listModels(baseUrl(server), "sk-test-FAKEKEY123456")
            val ok = res as? ModelsResult.Ok
            check("T8 fetch OpenAI -> Ok", ok != null, "res=$res")
            check("T8 daftar terurut alfabet", ok?.models?.map { it.id } == listOf("gpt-4o", "gpt-4o-mini"),
                "dapat=${ok?.models?.map { it.id }}")
            check("T8 dedupe pertahankan pertama", ok?.totalCount == 2, "total=${ok?.totalCount}")
            check("T8 tanpa info kapabilitas -> UNKNOWN",
                ok?.models?.all { it.vision == VisionSupport.UNKNOWN } == true)
            check("T8 fetchedAtMs terisi", (ok?.fetchedAtMs ?: 0L) > 0L)
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------- format berbeda
    run {
        // Array berisi string.
        val parsed = OpenAiCompatClient.parseModelsList("""{"data":["z-model","a-model","m-model"]}""")
        check("T8 data array string terurut", parsed.models.map { it.id } == listOf("a-model", "m-model", "z-model"),
            "dapat=${parsed.models.map { it.id }}")
    }
    run {
        // Kunci models + name (Ollama-style).
        val parsed = OpenAiCompatClient.parseModelsList(
            """{"models":[{"name":"llama3.1"},{"id":"qwen2"},{"name":"llama3.1"}]}""")
        check("T8 kunci models + name dipakai sebagai id",
            parsed.models.map { it.id } == listOf("llama3.1", "qwen2"),
            "dapat=${parsed.models.map { it.id }}")
    }
    run {
        // has_more + cap 200.
        val ids = (1..250).joinToString(",") { """{"id":"model-${"%03d".format(it)}"}""" }
        val parsed = OpenAiCompatClient.parseModelsList("""{"object":"list","data":[$ids],"has_more":true}""")
        check("T8 cap 200 pertama", parsed.models.size == 200, "dapat=${parsed.models.size}")
        check("T8 total disimpan sebelum cap", parsed.totalCount == 250, "total=${parsed.totalCount}")
        check("T8 200 pertama tetap terurut", parsed.models.first().id == "model-001" &&
            parsed.models.last().id == "model-200",
            "awal=${parsed.models.firstOrNull()?.id} akhir=${parsed.models.lastOrNull()?.id}")
    }
    run {
        // Data kosong -> Ok kosong (state EMPTY di UI), bukan error.
        val server = startServer { ex -> respond(ex, 200, """{"object":"list","data":[]}""") }
        try {
            val res = OpenAiCompatClient.listModels(baseUrl(server), null)
            val ok = res as? ModelsResult.Ok
            check("T8 data kosong -> Ok kosong", ok != null && ok.models.isEmpty() && ok.totalCount == 0,
                "res=$res")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------- entri rusak dilewati
    run {
        val parsed = OpenAiCompatClient.parseModelsList(
            """{"data":[{"id":"a"},{"tanpa-id":true},{"id":""},{"id":"b"},123,"plain-string",{"id":"a"}]}""")
        check("T8 entri rusak dilewati, yang dikenali kembali",
            parsed.models.map { it.id } == listOf("a", "b", "plain-string"),
            "dapat=${parsed.models.map { it.id }}")
        check("T8 jumlah dilewati dicatat", parsed.skippedCount == 3, "skipped=${parsed.skippedCount}")
    }
    run {
        // Tanpa data/models -> NOT_JSON_STRUCTURE.
        var kind: ModelsErrorKind? = null
        try {
            OpenAiCompatClient.parseModelsList("""{"object":"list","punya":[]}""")
        } catch (e: ModelsParseException) {
            kind = e.kind
        }
        check("T8 tanpa data/models -> NOT_JSON_STRUCTURE",
            kind == ModelsErrorKind.NOT_JSON_STRUCTURE, "kind=$kind")
        val server = startServer { ex -> respond(ex, 200, """{"object":"list","punya":[]}""") }
        try {
            val res = OpenAiCompatClient.listModels(baseUrl(server), null)
            val err = res as? ModelsResult.Err
            check("T8 listModels tanpa data/models -> Err NOT_JSON_STRUCTURE",
                err?.kind == ModelsErrorKind.NOT_JSON_STRUCTURE, "res=$res")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------- vision (§5.10.5)
    run {
        val parsed = OpenAiCompatClient.parseModelsList("""{"data":[
            {"id":"m-or","architecture":{"modality":"text+image->text"}},
            {"id":"m-or-text","architecture":{"modality":"text->text"}},
            {"id":"m-sv-yes","supports_vision":true},
            {"id":"m-sv-no","supports_vision":false},
            {"id":"m-cap","capabilities":{"vision":true}},
            {"id":"m-cap-kosong","capabilities":{}},
            {"id":"m-mod","modalities":["text","image"]},
            {"id":"m-mod-vision","modalities":["vision"]},
            {"id":"m-param","supported_parameters":["temperature","image"]},
            {"id":"m-plain"}
        ]}""")
        val byId = parsed.models.associateBy { it.id }
        check("T8 vision modality text+image -> SUPPORTED", byId["m-or"]?.vision == VisionSupport.SUPPORTED)
        check("T8 vision modality text-only -> UNSUPPORTED", byId["m-or-text"]?.vision == VisionSupport.UNSUPPORTED)
        check("T8 vision supports_vision true -> SUPPORTED", byId["m-sv-yes"]?.vision == VisionSupport.SUPPORTED)
        check("T8 vision supports_vision false -> UNSUPPORTED", byId["m-sv-no"]?.vision == VisionSupport.UNSUPPORTED)
        check("T8 vision capabilities.vision -> SUPPORTED", byId["m-cap"]?.vision == VisionSupport.SUPPORTED)
        check("T8 vision capabilities tanpa kunci -> UNKNOWN", byId["m-cap-kosong"]?.vision == VisionSupport.UNKNOWN)
        check("T8 vision modalities image -> SUPPORTED", byId["m-mod"]?.vision == VisionSupport.SUPPORTED)
        check("T8 vision modalities vision -> SUPPORTED", byId["m-mod-vision"]?.vision == VisionSupport.SUPPORTED)
        check("T8 vision supported_parameters image -> SUPPORTED", byId["m-param"]?.vision == VisionSupport.SUPPORTED)
        check("T8 vision tanpa info -> UNKNOWN (bukan unsupported)", byId["m-plain"]?.vision == VisionSupport.UNKNOWN)
    }

    // ------------------------------------------- gagal per kind
    run {
        // 401 tanpa key -> AUTH_NEEDED.
        val server = startServer { ex -> respond(ex, 401, """{"error":{"message":"Unauthorized"}}""") }
        try {
            val res = OpenAiCompatClient.listModels(baseUrl(server), null)
            val err = res as? ModelsResult.Err
            check("T8 401 tanpa key -> AUTH_NEEDED",
                err?.kind == ModelsErrorKind.AUTH_NEEDED, "res=$res")
            check("T8 AUTH_NEEDED pesan minta isi key",
                err?.message?.contains("butuh API key") == true, "msg=${err?.message}")
        } finally {
            server.stop(0)
        }
    }
    run {
        // 401 dengan key -> AUTH_REJECTED, pesan menyebut 401 + key ditolak.
        val server = startServer { ex -> respond(ex, 401, """{"error":{"message":"Incorrect API key"}}""") }
        try {
            val res = OpenAiCompatClient.listModels(baseUrl(server), "sk-test-FAKEKEY123456")
            val err = res as? ModelsResult.Err
            check("T8 401 dengan key -> AUTH_REJECTED",
                err?.kind == ModelsErrorKind.AUTH_REJECTED, "res=$res")
            check("T8 401 dengan key pesan menyebut 401 + ditolak",
                err?.message?.contains("401") == true && err?.message?.contains("ditolak") == true,
                "msg=${err?.message}")
        } finally {
            server.stop(0)
        }
    }
    run {
        // 403 tanpa key -> AUTH_NEEDED juga (§5.10.4).
        val server = startServer { ex -> respond(ex, 403, """{"error":{"message":"Forbidden"}}""") }
        try {
            val res = OpenAiCompatClient.listModels(baseUrl(server), null)
            val err = res as? ModelsResult.Err
            check("T8 403 tanpa key -> AUTH_NEEDED",
                err?.kind == ModelsErrorKind.AUTH_NEEDED, "res=$res")
        } finally {
            server.stop(0)
        }
    }
    run {
        // 404 -> NOT_FOUND.
        val server = startServer { ex -> respond(ex, 404, """{"error":{"message":"not found"}}""") }
        try {
            val res = OpenAiCompatClient.listModels(baseUrl(server), null)
            val err = res as? ModelsResult.Err
            check("T8 404 -> NOT_FOUND",
                err?.kind == ModelsErrorKind.NOT_FOUND, "res=$res")
            check("T8 404 pesan menyebut 404 + base URL",
                err?.message?.contains("404") == true && err?.message?.contains("base URL") == true,
                "msg=${err?.message}")
        } finally {
            server.stop(0)
        }
    }
    run {
        // Non-JSON (HTML) -> NOT_JSON, isi HTML tidak ditampilkan.
        val server = startServer { ex -> respond(ex, 200, "<html><body>Cloudflare blokir</body></html>", "text/html") }
        try {
            val res = OpenAiCompatClient.listModels(baseUrl(server), null)
            val err = res as? ModelsResult.Err
            check("T8 non-JSON -> NOT_JSON",
                err?.kind == ModelsErrorKind.NOT_JSON, "res=$res")
            check("T8 non-JSON isi HTML tidak ditampilkan",
                err?.message?.contains("<html>") != true, "msg=${err?.message}")
        } finally {
            server.stop(0)
        }
    }
    run {
        // URL tidak terjangkau (port tertutup) -> UNREACHABLE.
        val res = OpenAiCompatClient.listModels("http://[::1]:1", null)
        val err = res as? ModelsResult.Err
        check("T8 tidak terjangkau -> UNREACHABLE",
            err?.kind == ModelsErrorKind.UNREACHABLE, "res=$res")
    }
    run {
        // URL salah (malformed) -> UNREACHABLE.
        val res = OpenAiCompatClient.listModels("ht!tp://tidak valid", null)
        val err = res as? ModelsResult.Err
        check("T8 URL salah -> UNREACHABLE",
            err?.kind == ModelsErrorKind.UNREACHABLE, "res=$res")
    }
    run {
        // Base URL kosong -> Err (bukan crash).
        val res = OpenAiCompatClient.listModels("   ", null)
        check("T8 base URL kosong -> Err", res is ModelsResult.Err, "res=$res")
    }
    run {
        // Timeout: server diam 20 dtk > read timeout 15 dtk -> TIMEOUT.
        val server = startServer { ex ->
            Thread.sleep(20_000)
            respond(ex, 200, """{"data":[]}""")
        }
        try {
            val start = System.currentTimeMillis()
            val res = OpenAiCompatClient.listModels(baseUrl(server), null)
            val elapsed = System.currentTimeMillis() - start
            val err = res as? ModelsResult.Err
            check("T8 timeout -> TIMEOUT",
                err?.kind == ModelsErrorKind.TIMEOUT, "res=$res elapsed=${elapsed}ms")
            check("T8 timeout sekitar 15 dtk (bukan gantung)",
                elapsed in 14_000..19_000, "elapsed=${elapsed}ms")
        } finally {
            server.stop(0)
        }
    }
    run {
        // Key TIDAK PERNAH masuk pesan error: body error mengandung key palsu
        // format realistis (sufiks >= 16 char, cocok pola RedactSecrets).
        val longKey = "sk-test-FAKEKEY1234567890ABCD"
        val server = startServer { ex ->
            respond(ex, 500, """{"error":{"message":"gagal, key=$longKey"}}""")
        }
        try {
            val res = OpenAiCompatClient.listModels(baseUrl(server), longKey)
            val err = res as? ModelsResult.Err
            check("T8 key tidak bocor ke pesan error",
                err?.message?.contains(longKey) != true, "msg=${err?.message}")
        } finally {
            server.stop(0)
        }
    }
    run {
        // listModels mengirim Authorization bila key ada, tanpa key bila kosong.
        var authHeader: String? = "BELUM-DIPANGGIL"
        val server = startServer { ex ->
            authHeader = ex.requestHeaders.getFirst("Authorization")
            respond(ex, 200, """{"data":[{"id":"m1"}]}""")
        }
        try {
            OpenAiCompatClient.listModels(baseUrl(server), "sk-test-FAKEKEY123456")
            check("T8 listModels kirim Bearer bila key ada",
                authHeader == "Bearer sk-test-FAKEKEY123456", "header=$authHeader")
            authHeader = "BELUM-DIPANGGIL"
            OpenAiCompatClient.listModels(baseUrl(server), null)
            check("T8 listModels tanpa header auth bila key kosong",
                authHeader == null, "header=$authHeader")
        } finally {
            server.stop(0)
        }
    }

    println(if (failed == 0) "SEMUA LULUS" else "GAGAL: $failed cek")
    if (failed != 0) kotlin.system.exitProcess(1)
}
