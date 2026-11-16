package com.senzwh.deepharness

import harness.core.RustCore
import org.json.JSONObject

/**
 * Slice 10 (vc326) — helper seam daun murni Tools.kt ke core Rust
 * (`toolsLeavesJson`).
 *
 * Pola seperti `WorkerEndpointSeam.kt` slice 9: SATU tempat membangun
 * request JSON + mem-parse respons. `null` dari fun di bawah = Rust
 * tak-tersedia/gagal ATAU operasi gagal -> pemanggil WAJIB fallback
 * ke implementasi Kotlin lama (`*Kotlin`). Saklar dimatikan hanya
 * bila seam mengembalikan `null`, JSON tak-terparse, atau exception
 * di seam; `{"ok":false}` (input tak-valid) mengembalikan `null`
 * TANPA mematikan saklar (bukan cacat seam).
 *
 * Yang TIDAK lewat seam ini (tetap Kotlin, BUILD-VC326.md §2.3):
 * dispatcher `Tools.execute`, SEMUA daun Android (SMS, kalender,
 * call log, lokasi, baterai, clipboard, UsageStats, AlarmClock,
 * deviceInfo), daun ctx/DB/file, dan bagian JARINGAN
 * (`httpGet`/`httpGetStatus`/`httpRequest` + GET di `webFetch`/
 * `webSearch`/`searchYoutube` — HTTP tetap Kotlin; yang pindah
 * hanya PARSE + format + `stripHtml` + `truncateMarkedVc230`).
 *
 * CATATAN AMBIGUITAS `null`: beberapa op mengembalikan `null`
 * sebagai HASIL SAH Kotlin (`extractBalancedJson` tak-ketemu,
 * `ytParseVideoRenderer` tanpa videoId). Seam di sini menyamakan
 * "hasil null" dengan "fallback" — pemanggil SELALU fallback ke
 * `*Kotlin` bila seam mengembalikan null, dan `*Kotlin` menghitung
 * ulang hasil yang sama (null bila memang tak-ketemu). Benar,
 * hanya sedikit boros bila seam sebenarnya sukses-tapi-null.
 */
object ToolsLeavesSeam {
    /** Saklar slice 10 — default ikut `RustCore.available`. */
    var rustToolsLeaves = RustCore.available

    private fun opRaw(request: JSONObject): JSONObject? {
        if (!rustToolsLeaves) return null
        return try {
            val raw = RustCore.toolsLeavesJson(request.toString()) ?: run { rustToolsLeaves = false; return null }
            JSONObject(raw)
        } catch (e: Exception) {
            rustToolsLeaves = false
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

    /** `Tools.stripHtml`. */
    fun stripHtml(html: String): String? {
        val o = opValue("strip_html") { put("html", html) } ?: return null
        return o.optString("value")
    }

    /** Parse hasil DuckDuckGo — string hasil LENGKAP `webSearch`. */
    fun parseDdg(html: String, query: String): String? {
        val o = opValue("parse_ddg") {
            put("html", html)
            put("query", query)
        } ?: return null
        return o.optString("value")
    }

    /** `Tools.truncateMarkedVc230` — null = fallback (lihat catatan di atas). */
    fun truncateMarked(text: String, cap: Int, resultId: String): String? {
        val o = opValue("truncate_marked") {
            put("text", text)
            put("cap", cap)
            put("result_id", resultId)
        } ?: return null
        return o.optString("value")
    }

    /** `Tools.ytRunsText` — `json` = teks `JSONObject` (selalu valid). */
    fun ytRunsText(json: String): String? {
        val o = opValue("yt_runs_text") { put("json", json) } ?: return null
        return o.optString("value")
    }

    /** `Tools.ytParseVideoRenderer` — hit JSON atau null (fallback, lihat catatan). */
    fun ytParseVideoRenderer(json: String, forceMeta: String?): String? {
        val o = opValue("yt_parse_video_renderer") {
            put("json", json)
            if (forceMeta != null) put("force_meta", forceMeta) else put("force_meta", JSONObject.NULL)
        } ?: return null
        if (o.isNull("hit")) return null
        return o.optJSONObject("hit")?.toString()
    }

    /** `Tools.extractBalancedJson` — null = tak-ketemu ATAU fallback. */
    fun extractBalancedJson(text: String, startBrace: Int): String? {
        val o = opValue("extract_balanced_json") {
            put("text", text)
            put("start", startBrace)
        } ?: return null
        if (o.isNull("json")) return null
        return o.optString("json")
    }

    /** `Tools.ytWalkResults` — array hit JSON. */
    fun ytWalkResults(json: String): String? {
        val o = opValue("yt_walk_results") { put("json", json) } ?: return null
        return o.optJSONArray("hits")?.toString()
    }

    /** `Tools.ytScanRenderers` — array hit JSON. */
    fun ytScanRenderers(html: String): String? {
        val o = opValue("yt_scan_renderers") { put("html", html) } ?: return null
        return o.optJSONArray("hits")?.toString()
    }

    /** `Tools.parseYtSearch` — array hit JSON. */
    fun parseYtSearch(html: String): String? {
        val o = opValue("parse_yt_search") { put("html", html) } ?: return null
        return o.optJSONArray("hits")?.toString()
    }

    /** Format hasil `searchYoutube` — `hitsJson` = array hit JSON. */
    fun formatYtResults(hitsJson: String, maxResults: Int): String? {
        val o = opValue("format_yt_results") {
            put("hits", org.json.JSONArray(hitsJson))
            put("limit", maxResults)
        } ?: return null
        return o.optString("value")
    }
}
