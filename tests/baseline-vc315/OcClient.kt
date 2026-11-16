package com.senzwh.deepharness

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Klien HTTP tipis ke opencode serve di HP yang sama (Tahap D).
 * Bentuk API diverifikasi langsung di perangkat (opencode 1.18.9,
 * 2026-10-22): GET /session, POST /session, POST /session/{id}/prompt_async
 * (204), GET /session/status (peta sid -> {type}), GET /session/{id}/message
 * (array {info{role,finish,tokens}, parts[{type,text}]}).
 * Semua panggilan blocking — panggil HANYA dari thread latar.
 */
object OcClient {

    fun base(port: Int) = "http://127.0.0.1:$port"

    private fun http(method: String, url: String, body: String? = null,
                     connectMs: Int = 4000, readMs: Int = 15000): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = if (stream != null) {
                val sb = StringBuilder()
                val r = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                val buf = CharArray(8192)
                var total = 0
                while (true) {
                    val n = r.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                    total += n
                    if (total > 2_000_000) break
                }
                r.close()
                sb.toString()
            } else ""
            Pair(code, text)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "koneksi gagal")
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    /** Serve hidup di port ini? (GET /session balas 200) */
    fun probe(port: Int, timeoutMs: Int = 2500): Boolean {
        val (code, _) = http("GET", base(port) + "/session", null, timeoutMs, timeoutMs)
        return code == 200
    }

    fun listSessions(base: String): JSONArray? {
        val (code, txt) = http("GET", "$base/session")
        if (code != 200) return null
        return try { JSONArray(txt) } catch (e: Exception) { null }
    }

    fun createSession(base: String, title: String): String? {
        val body = JSONObject().put("title", title).toString()
        val (code, txt) = http("POST", "$base/session", body)
        if (code !in 200..299) return null
        return try { JSONObject(txt).optString("id").ifBlank { null } } catch (e: Exception) { null }
    }

    /** Kirim prompt tanpa menunggu (204 = diterima). Model dibiarkan default serve. */
    fun sendPrompt(base: String, sid: String, text: String): Boolean {
        val part = JSONObject().put("type", "text").put("text", text)
        val body = JSONObject().put("parts", JSONArray().put(part)).toString()
        val (code, _) = http("POST", "$base/session/$sid/prompt_async", body, 5000, 20000)
        return code in 200..299
    }

    fun statusMap(base: String): JSONObject {
        val (code, txt) = http("GET", "$base/session/status", null, 3000, 6000)
        if (code != 200) return JSONObject()
        return try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
    }

    fun messages(base: String, sid: String): JSONArray {
        val (code, txt) = http("GET", "$base/session/$sid/message", null, 4000, 12000)
        if (code != 200) return JSONArray()
        return try { JSONArray(txt) } catch (e: Exception) { JSONArray() }
    }

    fun abort(base: String, sid: String) {
        try { http("POST", "$base/session/$sid/abort", "{}", 3000, 6000) } catch (e: Exception) {}
    }

    /** Balas permintaan izin tool dari agen serve. response: once|always|reject. */
    fun replyPermission(base: String, sid: String, permId: String, response: String): Boolean {
        val body = JSONObject().put("response", response).toString()
        val (code, _) = http("POST", "$base/session/$sid/permissions/$permId", body, 4000, 10000)
        return code in 200..299
    }

    /**
     * Buka stream event SSE serve (/event). Kembalikan koneksi + reader;
     * pemanggil membaca baris di thread-nya sendiri dan WAJIB disconnect.
     * Null bila gagal tersambung.
     */
    fun openEventStream(base: String): Pair<HttpURLConnection, BufferedReader>? {
        return try {
            val conn = URL("$base/event").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 0 // stream panjang; ditutup manual saat tugas selesai
            conn.setRequestProperty("Accept", "text/event-stream")
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            Pair(conn, BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)))
        } catch (e: Exception) { null }
    }
}
