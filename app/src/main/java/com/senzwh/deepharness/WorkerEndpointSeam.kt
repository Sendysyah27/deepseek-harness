package com.senzwh.deepharness

import harness.core.RustCore
import org.json.JSONObject

/**
 * Slice 9 (vc325) — helper seam endpoint worker :4200 ke core Rust
 * (`workerEndpointJson`).
 *
 * Pola seperti `HttpSeam.kt` slice 8: SATU tempat membangun request
 * JSON + mem-parse respons. `null` dari fun di bawah = Rust
 * tak-tersedia/gagal ATAU operasi gagal -> pemanggil WAJIB fallback
 * ke implementasi Kotlin lama (`*Kotlin`). Saklar dimatikan hanya
 * bila seam mengembalikan `null`, JSON tak-terparse, atau exception
 * di seam; `{"ok":false}` (input tak-valid) mengembalikan `null`
 * TANPA mematikan saklar (bukan cacat seam).
 *
 * Yang TIDAK lewat seam ini (tetap Kotlin, BUILD-VC325.md §2.3):
 * loop server hidup, dispatch (WorkerRegistry/WorkerQueue/
 * WorkerJanitor/ProotSession/SecureStore/EventDb), token/SecureStore,
 * baca body dari stream, peta idempotensi stateful.
 */
object WorkerEndpointSeam {
    /** Hasil `parseRoute`: `route` null = rute tidak dikenal. */
    data class ParsedRoute(val route: WorkerEndpointLogic.Route?)
    /** Hasil `parseHeaders`: baris request + peta header. */
    data class ParsedHeaders(val reqLine: String, val headers: Map<String, String>)
    /** Hasil `idempotencyKey`: `key` null = aksi ini tidak butuh idempotensi. */
    data class IdemKey(val key: String?)
    /** Hasil `slicePreview`: potongan + apakah terpotong. */
    data class Slice(val slice: String, val truncated: Boolean)

    /** Saklar slice 9 — default ikut `RustCore.available`. */
    var rustWorkerEndpoint = RustCore.available

    private fun opRaw(request: JSONObject): JSONObject? {
        if (!rustWorkerEndpoint) return null
        return try {
            val raw = RustCore.workerEndpointJson(request.toString()) ?: run { rustWorkerEndpoint = false; return null }
            JSONObject(raw)
        } catch (e: Exception) {
            rustWorkerEndpoint = false
            null
        }
    }

    /** Op murni generik: `{"ok":true,...}` atau null (fallback). */
    private fun opValue(op: String, build: JSONObject.() -> Unit): JSONObject? {
        val req = JSONObject().put("op", op)
        req.build()
        val o = opRaw(req) ?: return null
        if (!o.optBoolean("ok")) return null
        return o
    }

    private fun jsonToMap(o: JSONObject?): Map<String, String> {
        val out = mutableMapOf<String, String>()
        if (o != null) {
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.optString(k)
            }
        }
        return out
    }

    /** `WorkerEndpointLogic.parseRoute`. */
    fun parseRoute(method: String, rawPath: String): ParsedRoute? {
        val o = opValue("parse_route") {
            put("method", method)
            put("raw_path", rawPath)
        } ?: return null
        if (o.isNull("route")) return ParsedRoute(null)
        val r = o.optJSONObject("route") ?: return null
        return ParsedRoute(
            WorkerEndpointLogic.Route(
                r.optString("action"),
                r.optString("worker_id"),
                r.optString("name")
            )
        )
    }

    /** `WorkerEndpointLogic.pathMatchesOtherMethod`. */
    fun pathMatchesOtherMethod(method: String, rawPath: String): Boolean? {
        val o = opValue("path_matches_other_method") {
            put("method", method)
            put("raw_path", rawPath)
        } ?: return null
        return o.optBoolean("value")
    }

    /** `WorkerEndpointLogic.parseQuery`. */
    fun parseQuery(rawPath: String): Map<String, String>? {
        val o = opValue("parse_query") { put("raw_path", rawPath) } ?: return null
        return jsonToMap(o.optJSONObject("query"))
    }

    /** `WorkerEndpointLogic.bearerToken` — null = tidak ada token ATAU fallback. */
    fun bearerToken(authHeader: String?): String? {
        val o = opValue("bearer_token") {
            if (authHeader != null) put("header", authHeader)
        } ?: return null
        if (o.isNull("token")) return null
        return o.optString("token")
    }

    /** `WorkerEndpointLogic.isAuthorized`. */
    fun isAuthorized(provided: String?, expected: String): Boolean? {
        val o = opValue("is_authorized") {
            if (provided != null) put("provided", provided)
            put("expected", expected)
        } ?: return null
        return o.optBoolean("value")
    }

    /** `WorkerEndpointLogic.isSafeName`. */
    fun isSafeName(name: String): Boolean? {
        val o = opValue("is_safe_name") { put("name", name) } ?: return null
        return o.optBoolean("value")
    }

    /** `WorkerEndpointLogic.slicePreview`. */
    fun slicePreview(text: String, max: Int): Slice? {
        val o = opValue("slice_preview") {
            put("text", text)
            put("max", max)
        } ?: return null
        return Slice(o.optString("slice"), o.optBoolean("truncated"))
    }

    /** `WorkerLogic.previewChars` (dipakai endpoint). */
    fun previewChars(requested: Int): Int? {
        val o = opValue("preview_chars") { put("requested", requested) } ?: return null
        return o.optInt("value")
    }

    /** `WorkerEndpointLogic.hmacSha256Hex`. */
    fun hmacSha256Hex(key: String, text: String): String? {
        val o = opValue("hmac_sha256_hex") {
            put("key", key)
            put("text", text)
        } ?: return null
        return o.optString("value")
    }

    /** `WorkerEndpointLogic.signQueueText`. */
    fun signQueueText(key: String, text: String): String? {
        val o = opValue("sign_queue_text") {
            put("key", key)
            put("text", text)
        } ?: return null
        return o.optString("value")
    }

    /** `WorkerEndpointLogic.hasQueueSignature`. */
    fun hasQueueSignature(text: String): Boolean? {
        val o = opValue("has_queue_signature") { put("text", text) } ?: return null
        return o.optBoolean("value")
    }

    /** `WorkerEndpointLogic.verifyQueueText` — null = tidak valid ATAU fallback. */
    fun verifyQueueText(key: String, signed: String): String? {
        val o = opValue("verify_queue_text") {
            put("key", key)
            put("signed", signed)
        } ?: return null
        if (o.isNull("value")) return null
        return o.optString("value")
    }

    /** `WORKER_SCOPED_ACTIONS.contains(action)`. */
    fun workerScoped(action: String): Boolean? {
        val o = opValue("worker_scoped") { put("action", action) } ?: return null
        return o.optBoolean("value")
    }

    /** `WorkerEndpoint.parseHeaders` — parse teks header yang sudah diakumulasi. */
    fun parseHeaders(text: String): ParsedHeaders? {
        val o = opValue("parse_headers") { put("text", text) } ?: return null
        return ParsedHeaders(o.optString("req_line"), jsonToMap(o.optJSONObject("headers")))
    }

    /** `WorkerEndpoint.buildResponse` — head respons HTTP. */
    fun buildResponseHead(code: Int, bodyBytes: Int, retryAfterSec: Int?): String? {
        val o = opValue("build_response") {
            put("code", code)
            put("body_bytes", bodyBytes)
            if (retryAfterSec != null) put("retry_after", retryAfterSec) else put("retry_after", JSONObject.NULL)
        } ?: return null
        return o.optString("head")
    }

    /** `WorkerEndpoint.idempotencyKey`. */
    fun idempotencyKey(route: WorkerEndpointLogic.Route, headers: Map<String, String>, body: String): IdemKey? {
        val o = opValue("idempotency_key") {
            put("action", route.action)
            put("worker_id", route.workerId)
            val hdr = headers["idempotency-key"]
            if (hdr != null) put("idem_header", hdr)
            put("body", body)
        } ?: return null
        if (o.isNull("key")) return IdemKey(null)
        return IdemKey(o.optString("key"))
    }
}
