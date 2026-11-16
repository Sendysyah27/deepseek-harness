package com.senzwh.deepharness

import android.content.Context
import android.content.Intent
import android.net.Uri
import harness.core.RustCore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import org.json.JSONObject

/**
 * vc179: mesin OAuth public-client untuk panel Konektor MCP (61).
 * Dua jalur, keduanya TANPA client secret (RFC 8252, public client):
 * - loopback + PKCE (Google, Linear): browser sistem -> redirect
 *   http://127.0.0.1:8931/ (port TETAP agar bisa didaftarkan persis
 *   di konsol provider) -> tukar code + code_verifier.
 * - device flow (GitHub): tanpa redirect; user memasukkan user_code
 *   di github.com/login/device; app polling sampai disetujui.
 * Token akses disimpan lewat McpServers.setToken (SecureStore,
 * kunci mcpToken_<id>); refresh token di mcpRefresh_<id>; Client ID
 * (PUBLIK, bukan secret) di prefs mcpServers. WebView TIDAK PERNAH
 * dipakai untuk login OAuth — selalu browser sistem (Intent VIEW).
 */
object McpOAuth {
    /** Slice 8 (vc324): saklar seam Rust — default ikut `RustCore.available`. */
    var rustMcpOAuth = RustCore.available

    const val REDIRECT_URI = "http://127.0.0.1:8931/"
    private const val REDIRECT_PORT = 8931

    private fun b64url(b: ByteArray): String =
        android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)

    private fun randVerifier(): String {
        val buf = ByteArray(64)
        SecureRandom().nextBytes(buf)
        return b64url(buf)
    }

    private fun challenge(verifier: String): String {
        HttpSeam.pkceChallenge(verifier)?.let { return it }
        if (!HttpSeam.rustHttp) rustMcpOAuth = false
        return challengeKotlin(verifier)
    }

    private fun challengeKotlin(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun enc(s: String): String {
        HttpSeam.formEncode(s)?.let { return it }
        if (!HttpSeam.rustHttp) rustMcpOAuth = false
        return encKotlin(s)
    }

    private fun encKotlin(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun postFormRaw(url: String, params: Map<String, String>): Pair<Int, String> {
        if (rustMcpOAuth) {
            val body = HttpSeam.formBody(params.entries.map { it.key to it.value })
            if (body != null) {
                val r = HttpSeam.request(
                    "POST", url,
                    mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Accept" to "application/json"
                    ),
                    body, 30000, 60000
                )
                if (r != null) {
                    if (!HttpSeam.rustHttp) rustMcpOAuth = false
                    return r.code to r.body
                }
                if (!HttpSeam.rustHttp) rustMcpOAuth = false
                // null = transport gagal -> fallback Kotlin (saklar hidup).
            } else if (!HttpSeam.rustHttp) rustMcpOAuth = false
        }
        return postFormRawKotlin(url, params)
    }

    private fun postFormRawKotlin(url: String, params: Map<String, String>): Pair<Int, String> {
        val body = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun postForm(url: String, params: Map<String, String>): JSONObject {
        val (code, text) = postFormRaw(url, params)
        if (code !in 200..299) throw Exception("HTTP $code: ${text.take(1000)}")
        return JSONObject(text)
    }

    fun refreshAccess(ctx: Context, def: McpServerDef): String? {
        val rt = McpServers.refreshTokenFor(ctx, def.id) ?: return null
        val cid = McpServers.clientIdFor(ctx, def.id) ?: return null
        val tu = def.tokenUrl ?: return null
        return try {
            val rfParams = mutableMapOf("grant_type" to "refresh_token", "refresh_token" to rt, "client_id" to cid)
            val sec = McpServers.clientSecretFor(ctx, def.id)
            if (!sec.isNullOrBlank()) rfParams["client_secret"] = sec
            val tok = postForm(tu, rfParams)
            val access = tok.optString("access_token")
            if (access.isBlank()) null
            else {
                McpServers.setToken(ctx, def.id, access)
                val nr = tok.optString("refresh_token")
                if (nr.isNotBlank()) McpServers.setRefreshToken(ctx, def.id, nr)
                access
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Rakit URL auth loopback — Rust dulu, fallback Kotlin (bangun String yang sama). */
    private fun buildAuthUrl(
        authUrl: String, clientId: String, scopes: String, state: String,
        ch: String, offline: Boolean
    ): String {
        HttpSeam.oauthAuthUrl(authUrl, clientId, REDIRECT_URI, scopes, state, ch, offline)
            ?.let { return it }
        if (!HttpSeam.rustHttp) rustMcpOAuth = false
        val q = StringBuilder(authUrl)
            .append("?response_type=code&client_id=").append(encKotlin(clientId))
            .append("&redirect_uri=").append(encKotlin(REDIRECT_URI))
            .append("&scope=").append(encKotlin(scopes))
            .append("&state=").append(encKotlin(state))
            .append("&code_challenge=").append(encKotlin(ch))
            .append("&code_challenge_method=S256")
        if (offline) q.append("&access_type=offline&prompt=consent")
        return q.toString()
    }

    /** Parse query callback — Rust dulu, fallback Kotlin (map yang sama). */
    private fun parseCallbackQuery(query: String): Map<String, String> {
        HttpSeam.oauthParseCallback(query)?.let { return it }
        if (!HttpSeam.rustHttp) rustMcpOAuth = false
        val params = HashMap<String, String>()
        for (part in query.split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) params[kv[0]] = URLDecoder.decode(kv[1], "UTF-8")
        }
        return params
    }

    fun startLoopback(ctx: Context, def: McpServerDef, clientId: String, clientSecret: String? = null, onDone: (String) -> Unit) {
        Thread {
            try {
                val verifier = randVerifier()
                val ch = challenge(verifier)
                val state = UUID.randomUUID().toString()
                val authUrl = def.authUrl ?: throw Exception("Server ini tidak punya auth URL.")
                val tokenUrl = def.tokenUrl ?: throw Exception("Server ini tidak punya token URL.")
                val server = try {
                    ServerSocket(REDIRECT_PORT, 0, InetAddress.getByName("127.0.0.1"))
                } catch (e: Exception) {
                    throw Exception("Port $REDIRECT_PORT dipakai — tutup app yang memakainya lalu ulangi.")
                }
                val q = buildAuthUrl(authUrl, clientId, def.scopes, state, ch, def.offline)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(q)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                server.soTimeout = 300000
                val sock = try {
                    server.accept()
                } finally {
                    try { server.close() } catch (_: Exception) {}
                }
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val reqLine = reader.readLine() ?: throw Exception("Callback kosong.")
                var ln: String?
                do { ln = reader.readLine() } while (ln != null && ln.isNotEmpty())
                val path = reqLine.split(" ").getOrNull(1) ?: ""
                val query = path.substringAfter("?", "")
                val params = parseCallbackQuery(query)
                val body = "<html><body><h2>Berhasil — kembali ke aplikasi S&Harness.</h2></body></html>"
                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                try {
                    val os = sock.getOutputStream()
                    os.write(resp.toByteArray(Charsets.UTF_8))
                    os.write(bodyBytes)
                    os.flush()
                } catch (_: Exception) {}
                try { sock.close() } catch (_: Exception) {}
                if (params["state"] != state) throw Exception("State callback tidak cocok — dibatalkan.")
                val err = params["error"]
                if (err != null) throw Exception("Provider menolak: $err")
                val code = params["code"] ?: throw Exception("Callback tanpa code.")
                val exParams = mutableMapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "client_id" to clientId,
                    "redirect_uri" to REDIRECT_URI,
                    "code_verifier" to verifier
                )
                if (!clientSecret.isNullOrBlank()) exParams["client_secret"] = clientSecret
                val tok = postForm(tokenUrl, exParams)
                val access = tok.optString("access_token")
                if (access.isBlank()) throw Exception("Token endpoint tidak mengembalikan access_token: ${tok.toString().take(300)}")
                McpServers.setToken(ctx, def.id, access)
                val refresh = tok.optString("refresh_token")
                if (refresh.isNotBlank()) McpServers.setRefreshToken(ctx, def.id, refresh)
                onDone("Hubungkan ${def.name} BERHASIL — token tersimpan. Tekan Tes untuk verifikasi.")
            } catch (e: Exception) {
                onDone("Hubungkan ${def.name} gagal: ${e.message}")
            }
        }.start()
    }

    fun startDevice(ctx: Context, def: McpServerDef, clientId: String, onCode: (String, String) -> Unit, onDone: (String) -> Unit) {
        Thread {
            try {
                val dcUrl = def.deviceCodeUrl ?: throw Exception("Server ini tidak punya device-code URL.")
                val tokenUrl = def.tokenUrl ?: throw Exception("Server ini tidak punya token URL.")
                val dc = postForm(dcUrl, mapOf("client_id" to clientId, "scope" to def.scopes))
                val deviceCode = dc.optString("device_code")
                val userCode = dc.optString("user_code")
                val verifyUri = dc.optString("verification_uri")
                val expiresIn = dc.optInt("expires_in", 900)
                var interval = dc.optInt("interval", 5).coerceAtLeast(5)
                if (deviceCode.isBlank() || userCode.isBlank()) throw Exception("Device flow tidak mengembalikan code: ${dc.toString().take(300)}")
                onCode(userCode, verifyUri)
                val deadline = System.currentTimeMillis() + expiresIn * 1000L
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval * 1000L)
                    val (code, text) = postFormRaw(tokenUrl, mapOf(
                        "client_id" to clientId,
                        "device_code" to deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                    ))
                    val o = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                    val access = o.optString("access_token")
                    if (code in 200..299 && access.isNotBlank()) {
                        McpServers.setToken(ctx, def.id, access)
                        val refresh = o.optString("refresh_token")
                        if (refresh.isNotBlank()) McpServers.setRefreshToken(ctx, def.id, refresh)
                        onDone("Hubungkan ${def.name} BERHASIL — token tersimpan. Tekan Tes untuk verifikasi.")
                        return@Thread
                    }
                    when (o.optString("error")) {
                        "authorization_pending" -> {}
                        "slow_down" -> interval += 5
                        "expired_token" -> throw Exception("Kode device kedaluwarsa — ulangi Hubungkan.")
                        "access_denied" -> throw Exception("Akses ditolak user — Hubungkan dibatalkan.")
                        else -> throw Exception("Device poll gagal: HTTP $code: ${text.take(300)}")
                    }
                }
                throw Exception("Waktu device flow habis — ulangi Hubungkan.")
            } catch (e: Exception) {
                onDone("Hubungkan ${def.name} gagal: ${e.message}")
            }
        }.start()
    }
}
