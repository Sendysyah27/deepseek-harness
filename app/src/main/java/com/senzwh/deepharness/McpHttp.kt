package com.senzwh.deepharness

import android.content.Context
import harness.core.RustCore
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * vc178: klien MCP Streamable HTTP (tanpa dependensi luar, HttpURLConnection).
 * Urutan per panggilan: initialize -> notifications/initialized (best-effort)
 * -> tools/list atau tools/call. Header Mcp-Session-Id dari initialize
 * diteruskan bila server memberikannya (perilaku protokol yang benar).
 * Respons bisa JSON langsung atau SSE (baris "data: {...}") — keduanya diparse.
 * Timeout: connect 30 detik, baca 60 detik (cermin helper stdio).
 * Token TIDAK pernah ditulis ke log/hasil — hanya dikirim sebagai header.
 */
object McpHttp {
    /** Slice 8 (vc324): saklar seam Rust — default ikut `RustCore.available`. */
    var rustMcpHttp = RustCore.available

    private const val PROTO = "2024-11-05"

    private fun headersFor(headerKind: String, token: String?): Map<String, String> {
        val h = HashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json, text/event-stream"
        if (!token.isNullOrBlank()) {
            when (headerKind) {
                "goog-api-key" -> h["X-Goog-Api-Key"] = token
                "bearer" -> h["Authorization"] = "Bearer $token"
            }
        }
        return h
    }

    private fun post(url: String, headers: Map<String, String>, body: String, sessionId: String?): Pair<String, String?> {
        if (rustMcpHttp) {
            val h = headers.toMutableMap()
            if (!sessionId.isNullOrBlank()) h["Mcp-Session-Id"] = sessionId
            val r = HttpSeam.request(
                "POST", url, h, body, 30000, 60000,
                wantHeaders = listOf("Mcp-Session-Id")
            )
            if (r != null) {
                if (!HttpSeam.rustHttp) rustMcpHttp = false
                val code = r.code
                val text = r.body
                val sid = r.headers["Mcp-Session-Id"]
                if (code !in 200..299) throw Exception("HTTP $code: ${text.take(300)}")
                return text to sid
            }
            if (!HttpSeam.rustHttp) rustMcpHttp = false
            // null = transport gagal -> fallback Kotlin (saklar hidup).
        }
        return postKotlin(url, headers, body, sessionId)
    }

    private fun postKotlin(url: String, headers: Map<String, String>, body: String, sessionId: String?): Pair<String, String?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.doOutput = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (!sessionId.isNullOrBlank()) conn.setRequestProperty("Mcp-Session-Id", sessionId)
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val sid = conn.getHeaderField("Mcp-Session-Id")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            if (code !in 200..299) throw Exception("HTTP $code: ${text.take(300)}")
            return text to sid
        } finally {
            conn.disconnect()
        }
    }

    private fun extractResult(raw: String): JSONObject? {
        HttpSeam.mcpExtract(raw)?.let { return it }
        // HttpSeam.mcpExtract mengembalikan null HANYA bila seam gagal
        // (MCP error dari server dilempar, bukan null) — fallback Kotlin.
        // Bila seam mati, matikan juga saklar file ini.
        if (!HttpSeam.rustHttp) rustMcpHttp = false
        return extractResultKotlin(raw)
    }

    private fun extractResultKotlin(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            try {
                val o = JSONObject(trimmed)
                if (o.has("result")) return o.optJSONObject("result")
                if (o.has("error")) throw Exception("MCP error: ${o.optJSONObject("error").toString().take(300)}")
            } catch (e: Exception) {
                if (e.message?.startsWith("MCP error") == true) throw e
            }
        }
        var last: JSONObject? = null
        for (line in raw.lines()) {
            if (!line.startsWith("data:")) continue
            val d = line.removePrefix("data:").trim()
            if (!d.startsWith("{")) continue
            try {
                val o = JSONObject(d)
                if (o.has("result")) last = o.optJSONObject("result")
                if (o.has("error")) throw Exception("MCP error: ${o.optJSONObject("error").toString().take(300)}")
            } catch (e: Exception) {
                if (e.message?.startsWith("MCP error") == true) throw e
            }
        }
        return last
    }

    private fun handshake(url: String, headers: Map<String, String>): String? {
        val init = JSONObject()
            .put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
            .put(
                "params", JSONObject()
                    .put("protocolVersion", PROTO)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "deepharness").put("version", "2.2"))
            )
        val (raw, sid) = post(url, headers, init.toString(), null)
        if (extractResult(raw) == null) throw Exception("MCP initialize tanpa result: ${raw.take(200)}")
        try {
            post(url, headers, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString(), sid)
        } catch (e: Exception) {
        }
        return sid
    }

    fun listTools(url: String, headerKind: String, token: String?): List<Pair<String, String>> {
        val headers = headersFor(headerKind, token)
        val sid = handshake(url, headers)
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list")
            .put("params", JSONObject()).toString()
        val (raw, _) = post(url, headers, body, sid)
        val res = extractResult(raw) ?: throw Exception("tools/list tanpa result: ${raw.take(200)}")
        val arr = res.optJSONArray("tools") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            out.add(t.optString("name") to t.optString("description"))
        }
        return out
    }

    fun callTool(url: String, headerKind: String, token: String?, tool: String, args: JSONObject): String {
        val headers = headersFor(headerKind, token)
        val sid = handshake(url, headers)
        val params = JSONObject().put("name", tool).put("arguments", args)
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
            .put("params", params).toString()
        val (raw, _) = post(url, headers, body, sid)
        val res = extractResult(raw) ?: throw Exception("tools/call tanpa result: ${raw.take(200)}")
        val content = res.optJSONArray("content")
        if (content != null) {
            val texts = ArrayList<String>()
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                if (c.optString("type") == "text") texts.add(c.optString("text"))
            }
            if (texts.isNotEmpty()) {
                val joined = texts.joinToString("\n").take(6000)
                return if (res.optBoolean("isError")) "[tool mengembalikan isError]\n$joined" else joined
            }
        }
        return res.toString().take(6000)
    }

    fun listToolsFor(ctx: Context, def: McpServerDef): List<Pair<String, String>> {
        val url = def.url ?: throw Exception("URL server kosong.")
        return try {
            listTools(url, def.headerKind, McpServers.tokenFor(ctx, def.id))
        } catch (e: Exception) {
            if (e.message?.contains("HTTP 401") == true) {
                val nt = McpOAuth.refreshAccess(ctx, def)
                if (nt != null) return listTools(url, def.headerKind, nt)
            }
            throw e
        }
    }

    fun callToolFor(ctx: Context, def: McpServerDef, tool: String, args: JSONObject): String {
        val url = def.url ?: throw Exception("URL server kosong.")
        return try {
            callTool(url, def.headerKind, McpServers.tokenFor(ctx, def.id), tool, args)
        } catch (e: Exception) {
            if (e.message?.contains("HTTP 401") == true) {
                val nt = McpOAuth.refreshAccess(ctx, def)
                if (nt != null) return callTool(url, def.headerKind, nt, tool, args)
            }
            throw e
        }
    }
}
