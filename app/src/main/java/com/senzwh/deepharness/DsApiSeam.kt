package com.senzwh.deepharness

import harness.core.RustCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Slice 11 (vc328) — helper seam split DsApiClient ke core Rust
 * (`dsApiJson`, modul `ds_api`).
 *
 * Pola seperti `HttpSeam.kt` slice 8: SATU tempat membangun request
 * JSON + mem-parse respons. `{"ok":false}`/null = Rust tak-tersedia /
 * operasi gagal -> pemanggil WAJIB fallback ke implementasi Kotlin
 * lama (`*Kotlin` di `DsApiClient`); saklar hanya dimatikan bila seam
 * mengembalikan `null`, JSON tak-terparse, atau exception di seam.
 *
 * AMBIGUITAS `null` (dicatat jujur, pola `ToolsLeavesSeam.kt` slice 10):
 * `findSessionId`/`findChallenge`/`findFileId` mengembalikan `null`
 * sebagai HASIL SAH Kotlin (tidak ketemu) MAUPUN sebagai sinyal
 * "seam gagal". Pemanggil SELALU fallback ke `*Kotlin` bila seam
 * null, dan `*Kotlin` menghitung ulang hasil yang sama. Benar, hanya
 * sedikit boros. `estTokens`/`safeFileName` tidak ambigu (hasil Kotlin
 * tidak pernah null); `uploadFile` mengikuti konvensi
 * `HttpSeam.request`: null = transport gagal ATAU seam gagal ->
 * fallback, saklar tetap hidup untuk gagal transport.
 *
 * Transport JSON non-streaming (`postJson`/`getJson`) TIDAK lewat sini
 * — memakai `HttpSeam.request` (seam `httpJson` slice 8) langsung dari
 * `DsApiClient`. Parse SSE memakai `HttpSeam.sseApply` slice 8.
 */
object DsApiSeam {
    /** Saklar slice 11 — default ikut `RustCore.available`. */
    var rustDsApi = RustCore.available

    private fun opRaw(request: JSONObject): JSONObject? {
        if (!rustDsApi) return null
        return try {
            val raw = RustCore.dsApiJson(request.toString()) ?: run { rustDsApi = false; return null }
            JSONObject(raw)
        } catch (e: Exception) {
            rustDsApi = false
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

    /** `DsApiClient.findSessionId`: id sesi dari root JSON create-session.
     *  Null = tidak ketemu ATAU seam gagal -> pemanggil fallback. */
    fun findSessionId(rootJson: String): String? {
        val o = opValue("find_session_id") { put("root", rootJson) } ?: return null
        return if (o.isNull("value")) null else o.optString("value")
    }

    /** `DsApiClient.findChallenge`: objek challenge dari root JSON.
     *  Null = tidak ketemu ATAU seam gagal -> pemanggil fallback. */
    fun findChallenge(rootJson: String): JSONObject? {
        val o = opValue("find_challenge") { put("root", rootJson) } ?: return null
        return o.optJSONObject("value")
    }

    /** `DsApiClient.findFileId`: id berkas dari root JSON upload.
     *  Null = tidak ketemu ATAU seam gagal -> pemanggil fallback. */
    fun findFileId(rootJson: String): String? {
        val o = opValue("find_file_id") { put("root", rootJson) } ?: return null
        return if (o.isNull("value")) null else o.optString("value")
    }

    /** `DsApiClient.estTokens`: `maxOf(1, Math.round(chars / perChar))`
     *  (0 bila chars <= 0). Null = seam gagal -> pemanggil fallback. */
    fun estTokens(chars: Int, perChar: Double): Long? {
        val o = opValue("est_tokens") {
            put("chars", chars)
            put("per_char", perChar)
        } ?: return null
        return o.optLong("value")
    }

    /** Sanitasi nama berkas unggahan (satuan UTF-16; blank -> "file.bin").
     *  Null = seam gagal -> pemanggil fallback. */
    fun safeFileName(name: String): String? {
        val o = opValue("safe_file_name") { put("name", name) } ?: return null
        return o.optString("value")
    }

    /**
     * POST multipart `/api/v0/file/upload_file` via Rust (ureq).
     * `headers` = map header DeepSeek (Authorization/Cookie/UA/dll —
     * Content-Type application/json di dalamnya DITIMPA multipart oleh
     * Rust, sama seperti Kotlin). `fileB64` = isi berkas base64 NO_WRAP.
     * `null` = Rust tak-tersedia/gagal ATAU transport gagal -> pemanggil
     * WAJIB fallback ke transport Kotlin lama (saklar tetap hidup untuk
     * gagal transport — lihat header file ini).
     */
    fun uploadFile(
        url: String, headers: Map<String, String>, fileB64: String,
        fileName: String, mime: String, connectMs: Int = 20000, readMs: Int = 120000
    ): HttpSeam.Resp? {
        if (!rustDsApi) return null
        return try {
            val req = JSONObject()
                .put("op", "upload_file")
                .put("url", url)
                .put("file_b64", fileB64)
                .put("file_name", fileName)
                .put("mime", mime)
                .put("connect_ms", connectMs)
                .put("read_ms", readMs)
            val h = JSONObject()
            for ((k, v) in headers) h.put(k, v)
            req.put("headers", h)
            val raw = RustCore.dsApiJson(req.toString()) ?: run { rustDsApi = false; return null }
            val o = JSONObject(raw)
            if (!o.optBoolean("ok")) return null // gagal transport -> fallback, saklar hidup
            HttpSeam.Resp(o.optInt("code", -1), o.optString("body"), emptyMap())
        } catch (e: Exception) {
            rustDsApi = false
            null
        }
    }
}
