package com.senzwh.deepharness

import harness.core.RustCore

/** Skrip helper MCP (python, jalan di Termux) dalam base64 — dipasang oleh tool mcp_setup. */
object McpHelper {
    /**
     * Saklar A/B slice 4 (vc317): bila core Rust tersedia, skrip dibaca
     * dari Rust (sekali, di-cache); bila tidak, fallback ke literal
     * Kotlin lama di bawah (SCRIPT_B64_KOTLIN — JANGAN dihapus).
     *
     * PERUBAHAN BENTUK (bukan perilaku): `const val` menjadi `val`
     * dengan getter — tidak ada pemakai posisi `const` untuk nilai ini
     * di tree (dipakai hanya konkatenasi string runtime di Tools.kt).
     */
    var rustMcpHelper: Boolean = true

    /** Skrip helper MCP dalam base64 (persis `McpHelper.SCRIPT_B64`). */
    val SCRIPT_B64: String get() = rustScriptB64Cached ?: SCRIPT_B64_KOTLIN

    /** Nilai Rust (sekali baca, di-cache); null = fallback Kotlin. */
    private val rustScriptB64Cached: String? by lazy {
        if (!rustMcpHelper || !RustCore.available) null
        else try {
            val json = RustCore.mcpHelperJson()
            if (!json.contains("\"ok\":true")) null
            else Regex("\"scriptB64\":\"([^\"]*)\"").find(json)?.groupValues?.getOrNull(1)
        } catch (e: Throwable) {
            rustMcpHelper = false
            null
        }
    }

    /** Implementasi Kotlin lama (fallback) — DIPERTAHANKAN. */
    private const val SCRIPT_B64_KOTLIN = "aW1wb3J0IHN1YnByb2Nlc3MsIHN5cywganNvbiwgYmFzZTY0LCBzaGxleCwgdGhyZWFkaW5nLCBxdWV1ZSwgdGltZQoKZGVmIGI2NGQoeCk6CiAgICByZXR1cm4gYmFzZTY0LmI2NGRlY29kZSh4KS5kZWNvZGUoInV0Zi04IiwgInJlcGxhY2UiKQoKY21kID0gYjY0ZChzeXMuYXJndlsxXSk7IHRvb2wgPSBiNjRkKHN5cy5hcmd2WzJdKTsgYXJncyA9IGpzb24ubG9hZHMoYjY0ZChzeXMuYXJndlszXSkgb3IgInt9IikKcCA9IHN1YnByb2Nlc3MuUG9wZW4oc2hsZXguc3BsaXQoY21kKSwgc3RkaW49c3VicHJvY2Vzcy5QSVBFLCBzdGRvdXQ9c3VicHJvY2Vzcy5QSVBFLCB0ZXh0PVRydWUsIGJ1ZnNpemU9MSkKcSA9IHF1ZXVlLlF1ZXVlKCkKZGVmIHJlYWRlcigpOgogICAgZm9yIGxpbmUgaW4gcC5zdGRvdXQ6CiAgICAgICAgcS5wdXQobGluZSkKdGhyZWFkaW5nLlRocmVhZCh0YXJnZXQ9cmVhZGVyLCBkYWVtb249VHJ1ZSkuc3RhcnQoKQoKZGVmIHNlbmQob2JqKToKICAgIHAuc3RkaW4ud3JpdGUoanNvbi5kdW1wcyhvYmopICsgIlxuIik7IHAuc3RkaW4uZmx1c2goKQoKZGVmIHdhaXRfaWQod2FudCwgdGltZW91dD0zMCk6CiAgICBlbmQgPSB0aW1lLnRpbWUoKSArIHRpbWVvdXQKICAgIHdoaWxlIHRpbWUudGltZSgpIDwgZW5kOgogICAgICAgIHRyeTogbGluZSA9IHEuZ2V0KHRpbWVvdXQ9bWF4KDAuMSwgZW5kIC0gdGltZS50aW1lKCkpKQogICAgICAgIGV4Y2VwdCBxdWV1ZS5FbXB0eTogYnJlYWsKICAgICAgICB0cnk6IG9iaiA9IGpzb24ubG9hZHMobGluZSkKICAgICAgICBleGNlcHQgRXhjZXB0aW9uOiBjb250aW51ZQogICAgICAgIGlmIG9iai5nZXQoImlkIikgPT0gd2FudDogcmV0dXJuIG9iagogICAgcmV0dXJuIE5vbmUKCnNlbmQoeyJqc29ucnBjIjogIjIuMCIsICJpZCI6IDEsICJtZXRob2QiOiAiaW5pdGlhbGl6ZSIsICJwYXJhbXMiOiB7InByb3RvY29sVmVyc2lvbiI6ICIyMDI0LTExLTA1IiwgImNhcGFiaWxpdGllcyI6IHt9LCAiY2xpZW50SW5mbyI6IHsibmFtZSI6ICJkZWVwaGFybmVzcyIsICJ2ZXJzaW9uIjogIjIuMiJ9fX0pCmluaXQgPSB3YWl0X2lkKDEpCmlmIG5vdCBpbml0OiBwcmludCgiTUNQOiBzZXJ2ZXIgdGlkYWsgbWVuamF3YWIgaW5pdGlhbGl6ZS4iKTsgcC50ZXJtaW5hdGUoKTsgc3lzLmV4aXQoMCkKc2VuZCh7Impzb25ycGMiOiAiMi4wIiwgIm1ldGhvZCI6ICJub3RpZmljYXRpb25zL2luaXRpYWxpemVkIn0pCnNlbmQoeyJqc29ucnBjIjogIjIuMCIsICJpZCI6IDIsICJtZXRob2QiOiAidG9vbHMvY2FsbCIsICJwYXJhbXMiOiB7Im5hbWUiOiB0b29sLCAiYXJndW1lbnRzIjogYXJnc319KQpyZXMgPSB3YWl0X2lkKDIsIDYwKQpwLnRlcm1pbmF0ZSgpCmlmIG5vdCByZXM6IHByaW50KCJNQ1A6IHRpZGFrIGFkYSBqYXdhYmFuIHRvb2xzL2NhbGwgKHRpbWVvdXQpLiIpOyBzeXMuZXhpdCgwKQppZiAiZXJyb3IiIGluIHJlczogcHJpbnQoIk1DUCBlcnJvcjogIiArIGpzb24uZHVtcHMocmVzWyJlcnJvciJdKVs6MTUwMF0pOyBzeXMuZXhpdCgwKQpjb250ZW50ID0gcmVzLmdldCgicmVzdWx0Iiwge30pLmdldCgiY29udGVudCIsIFtdKQp0ZXh0cyA9IFtjLmdldCgidGV4dCIsICIiKSBmb3IgYyBpbiBjb250ZW50IGlmIGlzaW5zdGFuY2UoYywgZGljdCkgYW5kIGMuZ2V0KCJ0eXBlIikgPT0gInRleHQiXQpwcmludCgiXG4iLmpvaW4odGV4dHMpWzo2MDAwXSBpZiB0ZXh0cyBlbHNlIGpzb24uZHVtcHMocmVzLmdldCgicmVzdWx0Iiwge30pKVs6NjAwMF0pCg=="
}
