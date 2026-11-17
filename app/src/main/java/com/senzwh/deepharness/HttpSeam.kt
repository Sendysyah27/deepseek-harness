package com.senzwh.deepharness

import harness.core.RustCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Slice 8 (vc324) — helper seam HTTP ke core Rust (`httpJson`).
 *
 * Pola seperti `SqliteSeam.kt` slice 7: SATU tempat membangun request
 * JSON + mem-parse respons. Perbedaan sadar dari pola slice 7
 * (BUILD-VC324.md §2.3): untuk op `request`, `{"ok":false}` = gagal
 * TRANSPORT (server mati/timeout — bukan cacat Rust) -> kembalikan
 * `null` TANPA mematikan saklar, supaya transport Kotlin lama yang
 * berjalan dan menghasilkan pesan error pemanggil yang benar. Saklar
 * hanya dimatikan bila seam mengembalikan `null`, JSON tak-terparse,
 * atau exception di seam. Untuk op murni, `ok:false`/null -> matikan
 * saklar seperti pola lama.
 *
 * `Resp` = hasil transport yang diterima (apa pun codenya, termasuk
 * non-2xx — pemanggil yang memutuskan throw/return, sama seperti
 * membaca `errorStream` di Kotlin lama).
 */
object HttpSeam {
    data class Resp(val code: Int, val body: String, val headers: Map<String, String>)

    /** Saklar slice 8 — default ikut `RustCore.available`. */
    var rustHttp = RustCore.available

    private fun opRaw(request: JSONObject): JSONObject? {
        if (!rustHttp) return null
        return try {
            val raw = RustCore.httpJson(request.toString()) ?: run { rustHttp = false; return null }
            JSONObject(raw)
        } catch (e: Exception) {
            rustHttp = false
            null
        }
    }

    /**
     * Request HTTP blocking via Rust (ureq). `body` null = tanpa body
     * (GET). `capChars` 0 = tanpa cap. `wantHeaders` = nama header
     * respons yang ingin dikembalikan (mis. "Retry-After").
     * `null` = Rust tak-tersedia/gagal ATAU transport gagal -> pemanggil
     * WAJIB fallback ke transport Kotlin lama (saklar tetap hidup untuk
     * gagal transport — lihat header file ini).
     */
    fun request(
        method: String, url: String, headers: Map<String, String> = emptyMap(),
        body: String? = null, connectMs: Int = 4000, readMs: Int = 15000,
        capChars: Int = 0, wantHeaders: List<String> = emptyList()
    ): Resp? {
        if (!rustHttp) return null
        return try {
            val req = JSONObject()
                .put("op", "request")
                .put("method", method)
                .put("url", url)
                .put("connect_ms", connectMs)
                .put("read_ms", readMs)
                .put("cap_chars", capChars)
            val h = JSONObject()
            for ((k, v) in headers) h.put(k, v)
            req.put("headers", h)
            if (body != null) req.put("body", body)
            val w = JSONArray()
            for (name in wantHeaders) w.put(name)
            req.put("want_headers", w)
            val raw = RustCore.httpJson(req.toString()) ?: run { rustHttp = false; return null }
            val o = JSONObject(raw)
            if (!o.optBoolean("ok")) return null // gagal transport -> fallback, saklar hidup
            val hdrs = mutableMapOf<String, String>()
            val ho = o.optJSONObject("headers")
            if (ho != null) {
                val keys = ho.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    hdrs[k] = ho.optString(k)
                }
            }
            Resp(o.optInt("code", -1), o.optString("body"), hdrs)
        } catch (e: Exception) {
            rustHttp = false
            null
        }
    }

    /** Op murni generik: `{"ok":true,...}` atau null (fallback).
     *  `ok:false` = operasi gagal (input tak-valid) -> null TANPA
     *  mematikan saklar (bukan cacat seam). */
    private fun opValue(op: String, build: JSONObject.() -> Unit): JSONObject? {
        val req = JSONObject().put("op", op)
        req.build()
        val o = opRaw(req) ?: return null
        if (!o.optBoolean("ok")) return null
        return o
    }

    /**
     * `McpHttp.extractResult`: JSONObject? hasil, atau throw "MCP error: ...".
     * CATATAN: `{"ok":false}` di sini BUKAN gagal seam — itu MCP error
     * dari server (harus dilempar seperti Kotlin lama), jadi JANGAN
     * fallback dan JANGAN matikan saklar.
     */
    fun mcpExtract(raw: String): JSONObject? {
        val req = JSONObject().put("op", "mcp_extract").put("raw", raw)
        val o = opRaw(req) ?: return null
        if (!o.optBoolean("ok")) throw Exception(o.optString("error"))
        return o.optJSONObject("result")
    }

    /** `ApiClients.shapeFingerprint`. */
    fun shapeFingerprint(body: String): String? {
        val o = opValue("shape_fingerprint") { put("body", body) } ?: return null
        return o.optString("value")
    }

    /** `Gmail.decodeWord`. */
    fun decodeWord(text: String): String? {
        val o = opValue("decode_word") { put("text", text) } ?: return null
        return o.optString("value")
    }

    /** `Gmail.fetchHeaders` untuk SATU id (baris mentah FETCH -> satu entri). */
    fun gmailHeaders(lines: List<String>): String? {
        val o = opValue("gmail_headers") {
            val arr = JSONArray()
            for (l in lines) arr.put(l)
            put("lines", arr)
        } ?: return null
        return o.optString("value")
    }

    /** `McpOAuth.challenge` (PKCE S256). */
    fun pkceChallenge(verifier: String): String? {
        val o = opValue("pkce_challenge") { put("verifier", verifier) } ?: return null
        return o.optString("value")
    }

    /** `URLEncoder.encode(s, "UTF-8")`. */
    fun formEncode(text: String): String? {
        val o = opValue("form_encode") { put("text", text) } ?: return null
        return o.optString("value")
    }

    /** Gabung pasangan `[k, v]` jadi body form `k=v&k=v` (urutan dipertahankan). */
    fun formBody(params: List<Pair<String, String>>): String? {
        val o = opValue("form_body") {
            val arr = JSONArray()
            for ((k, v) in params) arr.put(JSONArray().put(k).put(v))
            put("params", arr)
        } ?: return null
        return o.optString("value")
    }

    /** Rakit URL auth loopback OAuth (param ter-encode). */
    fun oauthAuthUrl(
        authUrl: String, clientId: String, redirectUri: String,
        scopes: String, state: String, challenge: String, offline: Boolean
    ): String? {
        val o = opValue("oauth_auth_url") {
            put("auth_url", authUrl)
            put("client_id", clientId)
            put("redirect_uri", redirectUri)
            put("scopes", scopes)
            put("state", state)
            put("challenge", challenge)
            put("offline", offline)
        } ?: return null
        return o.optString("value")
    }

    /** Parse query callback OAuth -> map (null bila gagal decode / seam). */
    fun oauthParseCallback(query: String): Map<String, String>? {
        if (!rustHttp) return null
        return try {
            val req = JSONObject().put("op", "oauth_parse_callback").put("query", query)
            val raw = RustCore.httpJson(req.toString()) ?: run { rustHttp = false; return null }
            val o = JSONObject(raw)
            if (!o.optBoolean("ok")) return null // gagal decode -> fallback Kotlin (saklar hidup)
            val out = mutableMapOf<String, String>()
            val po = o.optJSONObject("params")
            if (po != null) {
                val keys = po.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    out[k] = po.optString(k)
                }
            }
            out
        } catch (e: Exception) {
            rustHttp = false
            null
        }
    }

    /** State awal mesin SSE (JSON) — sama dengan default Rust. */
    fun sseInitState(): String =
        """{"last_path":"","saw_finish":false,"fragment_thinking":{},"fragment_count":0,"current_thinking":false}"""

    /** Hasil satu langkah mesin SSE. */
    data class SseStep(
        val state: String,
        val contentDelta: String,
        val thinkingDelta: String,
        val sawFinish: Boolean,
        val skipped: Boolean
    )

    /**
     * Satu baris payload SSE -> delta konten/thinking + state baru.
     * `null` = Rust gagal (bukan baris dilewati — itu `skipped=true`) ->
     * pemanggil WAJIB fallback ke mesin Kotlin lama untuk stream ini.
     */
    fun sseApply(stateJson: String, payload: String): SseStep? {
        if (!rustHttp) return null
        return try {
            val req = JSONObject()
                .put("op", "sse_apply")
                .put("state", JSONObject(stateJson))
                .put("payload", payload)
            val raw = RustCore.httpJson(req.toString()) ?: run { rustHttp = false; return null }
            val o = JSONObject(raw)
            if (!o.optBoolean("ok")) return null // operasi gagal -> fallback (saklar hidup)
            SseStep(
                o.optJSONObject("state")?.toString() ?: stateJson,
                o.optString("content_delta"),
                o.optString("thinking_delta"),
                o.optBoolean("saw_finish"),
                o.optBoolean("skipped")
            )
        } catch (e: Exception) {
            rustHttp = false
            null
        }
    }
}
