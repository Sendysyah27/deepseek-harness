package com.senzwh.deepharness

import android.content.Context
import harness.core.RustCore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** vc201 T2 API-Buatan — FRAMEWORK saja, tanpa klien app nyata.
 *  Tesis 2 DIBALIK (keputusan Sendy): untuk app yang BELUM punya API, kita
 *  BUAT API-nya — pelajari cara app bicara ke servernya, bangun klien API di
 *  harness, kontrol full app lewat API; layar jadi verifikasi + fallback.
 *  Prinsip yang dikunci di sini:
 *  - Hanya app + akun milik sendiri. App pertama = keputusan user (belum ada).
 *  - Satu-satunya klien = "template" (CONTOH, disabled) — TIDAK PERNAH
 *    dieksekusi, tidak pernah memanggil jaringan. Panggilan apa pun ke
 *    template ditolak dengan pesan jelas.
 *  - Token TIDAK PERNAH lewat chat/log/prefs biasa: hanya SecureStore kunci
 *    "apiToken_<id>", dipasang lewat dialog Setup "Token API-Buatan"
 *    (status ADA/BELUM saja, nilai tidak pernah ditampilkan).
 *  - Detektor perubahan bentuk: fingerprint kunci JSON level-atas terurut
 *    per endpoint di prefs "harness" kunci "apiShape_<client>_<endpoint>";
 *    berubah → hasil diawali "[API-BERUBAH]" + peringatan sekali per
 *    perubahan, data tetap dikembalikan (tidak ditolak).
 *  - Pinning = tembok: bila trafik tidak bisa dibaca, fallback layar (bukan
 *    membobol). Fitur murni lokal tanpa panggilan server tidak bisa via API.
 *  Gating: api_read = REVIEW (RISKY_TOOLS), api_write = HIGH (HIGH_TOOLS,
 *  dialog + PIN). Logika level()/autoAllow TIDAK diubah di sini. */
data class ApiEndpoint(
    val name: String,
    val method: String,
    val path: String,
    val write: Boolean,
    val desc: String
)

data class ApiClientDef(
    val id: String,
    val label: String,
    val baseUrl: String,
    val enabled: Boolean,
    val endpoints: List<ApiEndpoint>
)

object ApiClients {
    /** Slice 8 (vc324): saklar seam Rust — default ikut `RustCore.available`. */
    var rustApiClients = RustCore.available

    val CLIENTS: List<ApiClientDef> = listOf(
        ApiClientDef(
            "template", "CONTOH — bukan app nyata", "https://invalid.example", false,
            listOf(
                ApiEndpoint("contoh_baca", "GET", "/contoh/baca", false, "Endpoint baca contoh — tidak pernah dieksekusi."),
                ApiEndpoint("contoh_tulis", "POST", "/contoh/tulis", true, "Endpoint tulis contoh — tidak pernah dieksekusi.")
            )
        )
    )

    fun byId(id: String): ApiClientDef? = CLIENTS.firstOrNull { it.id == id }

    fun tokenKey(id: String): String = "apiToken_$id"

    fun tokenFor(ctx: Context, id: String): String? = SecureStore.get(ctx, tokenKey(id))

    fun hasToken(ctx: Context, id: String): Boolean = !tokenFor(ctx, id).isNullOrBlank()

    fun setToken(ctx: Context, id: String, value: String) {
        if (value.isBlank()) SecureStore.delete(ctx, tokenKey(id))
        else SecureStore.put(ctx, tokenKey(id), value)
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    private fun shapeKey(client: String, endpoint: String): String =
        "apiShape_${client}_${endpoint}"

    /** Jalur tool: readOnly=true → api_read, false → api_write. */
    fun execute(ctx: Context, readOnly: Boolean, args: JSONObject): String {
        val client = args.optString("client").trim()
        val epName = args.optString("endpoint").trim()
        if (client.isBlank() || epName.isBlank())
            return "api_read/api_write butuh argumen 'client' dan 'endpoint'."
        val def = byId(client)
            ?: return "Klien API '$client' tidak dikenal. Klien tersedia: " +
                CLIENTS.joinToString(", ") { it.id }
        if (!def.enabled || def.id == "template")
            return "Klien template bukan app nyata — tentukan app pertama " +
                "(keputusan user, belum ada). Framework ini tidak memanggil " +
                "jaringan apa pun untuk template."
        val ep = def.endpoints.firstOrNull { it.name == epName }
            ?: return "Endpoint '$epName' tidak dikenal pada klien '${def.label}'."
        if (readOnly && ep.write) return "Endpoint tulis hanya lewat api_write."
        if (!readOnly && !ep.write) return "Endpoint baca hanya lewat api_read."
        val token = tokenFor(ctx, def.id)
        if (token.isNullOrBlank())
            return "Token klien '${def.label}' belum dipasang via dialog Token " +
                "API-Buatan (Setup). Token TIDAK PERNAH lewat chat — pasang di " +
                "dialog Setup."
        return try {
            callHttp(ctx, def, ep, token, args)
        } catch (e: Exception) {
            "API gagal (${def.label}/${ep.name}): ${e.message}"
        }
    }

    private fun callHttp(
        ctx: Context, def: ApiClientDef, ep: ApiEndpoint, token: String, args: JSONObject
    ): String {
        var path = ep.path
        val params = args.optJSONObject("params") ?: JSONObject()
        val keys = params.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            path = path.replace("{$k}", URLEncoder.encode(params.optString(k), "UTF-8"))
        }
        val url = def.baseUrl.trimEnd('/') + path
        if (rustApiClients) {
            val headers = mutableMapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/json"
            )
            var body: String? = null
            if (ep.method == "POST") {
                headers["Content-Type"] = "application/json"
                body = args.optString("body", "")
            }
            val r = HttpSeam.request(
                ep.method, url, headers, body, 15000, 30000
            )
            if (r != null) {
                if (!HttpSeam.rustHttp) rustApiClients = false
                return callHttpResult(ctx, def, ep, r.code, r.body)
            }
            if (!HttpSeam.rustHttp) rustApiClients = false
            // null = transport gagal -> fallback Kotlin (saklar hidup).
        }
        return callHttpKotlin(ctx, def, ep, token, args, path)
    }

    /** Bagian sesudah transport: error mapping + detektor bentuk (sama untuk Rust/Kotlin). */
    private fun callHttpResult(
        ctx: Context, def: ApiClientDef, ep: ApiEndpoint, code: Int, raw: String
    ): String {
        if (code !in 200..299)
            return "API error (HTTP $code) pada ${def.label}/${ep.name}: " +
                ProotSession.truncateOutput(raw)
        // Detektor bentuk: hanya respons sukses yang dilacak.
        val fp = shapeFingerprint(raw)
        val key = shapeKey(def.id, ep.name)
        val prev = prefs(ctx).getString(key, null)
        prefs(ctx).edit().putString(key, fp).apply()
        val body = ProotSession.truncateOutput(raw)
        return if (prev != null && prev != fp)
            "[API-BERUBAH] bentuk respons endpoint ini berubah — " +
                "verifikasi via layar sebelum percaya.\n" + body
        else body
    }

    private fun callHttpKotlin(
        ctx: Context, def: ApiClientDef, ep: ApiEndpoint, token: String, args: JSONObject,
        path: String
    ): String {
        val conn = (URL(def.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            requestMethod = ep.method
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (ep.method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (ep.method == "POST") {
                conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(args.optString("body", "")) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = try {
                stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            } catch (e: Exception) {
                ""
            }
            return callHttpResult(ctx, def, ep, code, raw)
        } finally {
            try { conn.disconnect() } catch (e: Exception) {}
        }
    }

    /** Fingerprint bentuk: kunci JSON level-atas terurut; non-JSON = "non-json". */
    private fun shapeFingerprint(body: String): String {
        HttpSeam.shapeFingerprint(body)?.let { return it }
        return shapeFingerprintKotlin(body)
    }

    private fun shapeFingerprintKotlin(body: String): String {
        return try {
            val o = JSONObject(body)
            val list = mutableListOf<String>()
            val keys = o.keys()
            while (keys.hasNext()) list.add(keys.next())
            "json:" + list.sorted().joinToString(",")
        } catch (e: Exception) {
            "non-json"
        }
    }
}
