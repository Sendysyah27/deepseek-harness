package com.senzwh.deepharness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * vc178: Konektor MCP — katalog server MCP resmi (Google/Meta/GitHub/tim)
 * + server kustom bikinan user. Token TIDAK pernah ditulis di file ini:
 * token disimpan di SecureStore (AES-256/GCM, Keystore) dengan kunci
 * "mcpToken_<id>", dan hanya dibaca saat panggilan.
 * Secret client Desktop Google disimpan di SecureStore kunci "mcpSecret_<id>"
 * — tidak pernah ditampilkan di UI.
 *
 * Fakta katalog (riset 2026-10-26, dari dokumentasi vendor):
 * - GitHub remote: https://api.githubcopilot.com/mcp/ (OAuth/PAT).
 * - Google Developer Knowledge: https://developerknowledge.googleapis.com/mcp
 *   (header X-Goog-Api-Key).
 * - Google Workspace remote: gmail/calendar/drive/chat/people *.googleapis.com
 *   /mcp/v1 — status Developer Preview saat katalog ditulis.
 * - Google Maps Code Assist: stdio via npx @googlemaps/code-assist-mcp.
 * - Meta Ads: https://mcp.facebook.com/ads (resmi, hosted, default read-only).
 * - Meta WhatsApp Business Tools: https://mcp.facebook.com/whatsapp_business_tools
 *   (resmi, beta) — untuk setup bisnis, BUKAN kirim massal produksi.
 * - Slack: https://mcp.slack.com/mcp (resmi, Streamable HTTP, OAuth).
 * - Notion: https://mcp.notion.com/mcp (resmi, hosted, OAuth).
 * - Linear: https://mcp.linear.app/mcp (resmi, Streamable HTTP, OAuth).
 * - Meta TIDAK punya server MCP resmi untuk Instagram/Threads/WhatsApp
 *   personal (terkonfirmasi dari riset) — yang ada hanya server komunitas;
 *   server komunitas bisa ditambah lewat "server kustom", BUKAN dilabeli resmi.
 */
data class McpServerDef(
    val id: String,
    val name: String,
    val vendor: String,
    val transport: String, // "http" | "stdio"
    val url: String?,
    val command: String?,
    val headerKind: String, // "bearer" | "goog-api-key" | "none"
    val official: Boolean,
    val note: String,
    val oauthKind: String = "none", // "none" | "loopback" | "device"
    val authUrl: String? = null,
    val tokenUrl: String? = null,
    val scopes: String = "",
    val deviceCodeUrl: String? = null,
    val offline: Boolean = false
)

object McpServers {
    private const val PREFS = "mcpServers"

    val BUILTIN: List<McpServerDef> = listOf(
        McpServerDef("github", "GitHub", "GitHub", "http",
            "https://api.githubcopilot.com/mcp/", null, "bearer", true,
            "Server resmi GitHub (remote). Token: Personal Access Token atau token OAuth. Variasi read-only: /mcp/readonly (tambah sebagai server kustom). Hubungkan: device flow (tanpa redirect, tanpa secret).",
            "device", null, "https://github.com/login/oauth/access_token", "repo read:user", "https://github.com/login/device/code", false),
        McpServerDef("google-dev-knowledge", "Google Developer Knowledge", "Google", "http",
            "https://developerknowledge.googleapis.com/mcp", null, "goog-api-key", true,
            "Dokumentasi resmi Google (Firebase, Cloud, Android, Maps). Auth: API key Google Cloud (Developer Knowledge API). Tool: search_documents, get_documents, answer_query. Hubungkan tidak berlaku (pakai API key via Token)."),
        McpServerDef("gmail", "Gmail", "Google", "http",
            "https://gmailmcp.googleapis.com/mcp/v1", null, "bearer", true,
            "Google Workspace remote (Developer Preview). Auth: token OAuth Google. Hubungkan: login Google (Desktop app, PKCE, loopback).",
            "loopback", "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", "https://www.googleapis.com/auth/gmail.readonly", null, true),
        McpServerDef("google-calendar", "Google Calendar", "Google", "http",
            "https://calendarmcp.googleapis.com/mcp/v1", null, "bearer", true,
            "Google Workspace remote (Developer Preview). Auth: token OAuth Google. Hubungkan: login Google (Desktop app, PKCE, loopback).",
            "loopback", "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", "https://www.googleapis.com/auth/calendar.readonly", null, true),
        McpServerDef("google-drive", "Google Drive", "Google", "http",
            "https://drivemcp.googleapis.com/mcp/v1", null, "bearer", true,
            "Google Workspace remote (Developer Preview). Auth: token OAuth Google. Hubungkan: login Google (Desktop app, PKCE, loopback).",
            "loopback", "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", "https://www.googleapis.com/auth/drive.readonly", null, true),
        McpServerDef("google-chat", "Google Chat", "Google", "http",
            "https://chatmcp.googleapis.com/mcp/v1", null, "bearer", true,
            "Google Workspace remote (Developer Preview). Auth: token OAuth Google. Hubungkan: login Google (Desktop app, PKCE, loopback).",
            "loopback", "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", "https://www.googleapis.com/auth/chat.messages.readonly https://www.googleapis.com/auth/chat.spaces.readonly", null, true),
        McpServerDef("google-people", "Google Contacts", "Google", "http",
            "https://people.googleapis.com/mcp/v1", null, "bearer", true,
            "Google Workspace remote (Developer Preview). Auth: token OAuth Google. Hubungkan: login Google (Desktop app, PKCE, loopback).",
            "loopback", "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", "https://www.googleapis.com/auth/contacts.readonly", null, true),
        McpServerDef("google-maps-code-assist", "Google Maps Code Assist", "Google", "stdio",
            null, "npx -y @googlemaps/code-assist-mcp@latest", "none", true,
            "Jalan lokal via Termux (butuh node + npx). Tool yang diketahui: retrieve-instructions, retrieve-google-maps-platform-docs. Untuk dokumentasi & code assist Maps, bukan data Maps live."),
        McpServerDef("meta-ads", "Meta Ads", "Meta", "http",
            "https://mcp.facebook.com/ads", null, "bearer", true,
            "Server resmi Meta (hosted, gratis, default read-only). Auth: token akses dari aplikasi developer Meta." + " OAuth login belum didukung v1 (butuh client secret) — pakai tombol Token."),
        McpServerDef("meta-wa-business", "WhatsApp Business Tools", "Meta", "http",
            "https://mcp.facebook.com/whatsapp_business_tools", null, "bearer", true,
            "Server resmi Meta (beta, rollout bertahap). Untuk setup/onboarding WhatsApp Business Platform, template, webhook, tes pesan — BUKAN kirim massal produksi. Butuh admin bisnis + aplikasi WA." + " OAuth login belum didukung v1 (butuh client secret) — pakai tombol Token."),
        McpServerDef("slack", "Slack", "Slack", "http",
            "https://mcp.slack.com/mcp", null, "bearer", true,
            "Server resmi Slack (Streamable HTTP). Auth: token user (xoxp-) dari aplikasi Slack; admin workspace harus menyetujui." + " OAuth login belum didukung v1 (butuh client secret) — pakai tombol Token."),
        McpServerDef("notion", "Notion", "Notion", "http",
            "https://mcp.notion.com/mcp", null, "bearer", true,
            "Server resmi Notion (hosted). Auth: token OAuth/integrasi; akses mengikuti halaman yang diizinkan." + " OAuth login belum didukung v1 (butuh client secret) — pakai tombol Token."),
        McpServerDef("linear", "Linear", "Linear", "http",
            "https://mcp.linear.app/mcp", null, "bearer", true,
            "Server resmi Linear (Streamable HTTP). Auth: token OAuth. Hubungkan: login Linear (PKCE, loopback).",
            "loopback", "https://linear.app/oauth/authorize", "https://api.linear.app/oauth/token", "read", null, false)
    )

    fun customServers(ctx: Context): List<McpServerDef> {
        val out = ArrayList<McpServerDef>()
        try {
            val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("custom", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    McpServerDef(
                        o.optString("id"), o.optString("name"), o.optString("vendor", "Kustom"),
                        o.optString("transport", "http"),
                        o.optString("url").ifBlank { null },
                        o.optString("command").ifBlank { null },
                        o.optString("headerKind", "bearer"), false, o.optString("note")
                    )
                )
            }
        } catch (e: Exception) {}
        return out
    }

    fun allServers(ctx: Context): List<McpServerDef> = BUILTIN + customServers(ctx)

    fun resolve(ctx: Context, query: String): McpServerDef? {
        val q = query.trim()
        return allServers(ctx).firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: allServers(ctx).firstOrNull { it.name.equals(q, ignoreCase = true) }
    }

    private fun enabledSet(ctx: Context): MutableSet<String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HashSet(p.getStringSet("enabled", emptySet()) ?: emptySet())
    }

    fun isEnabled(ctx: Context, id: String): Boolean = enabledSet(ctx).contains(id)

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        val s = enabledSet(ctx)
        if (on) s.add(id) else s.remove(id)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("enabled", s).apply()
    }

    fun tokenFor(ctx: Context, id: String): String? = SecureStore.get(ctx, "mcpToken_$id")

    fun hasToken(ctx: Context, id: String): Boolean = SecureStore.has(ctx, "mcpToken_$id")

    fun setToken(ctx: Context, id: String, value: String) {
        if (value.isBlank()) {
            SecureStore.delete(ctx, "mcpToken_$id")
            SecureStore.delete(ctx, "mcpRefresh_$id")
        } else SecureStore.put(ctx, "mcpToken_$id", value)
    }

    fun clientIdFor(ctx: Context, id: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("clientId_$id", null)?.ifBlank { null }

    fun setClientId(ctx: Context, id: String, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().let {
            if (value.isBlank()) it.remove("clientId_$id") else it.putString("clientId_$id", value)
            it.apply()
        }
    }

    fun refreshTokenFor(ctx: Context, id: String): String? = SecureStore.get(ctx, "mcpRefresh_$id")

    fun setRefreshToken(ctx: Context, id: String, value: String) {
        if (value.isBlank()) SecureStore.delete(ctx, "mcpRefresh_$id")
        else SecureStore.put(ctx, "mcpRefresh_$id", value)
    }

    fun clientSecretFor(ctx: Context, id: String): String? = SecureStore.get(ctx, "mcpSecret_$id")

    fun setClientSecret(ctx: Context, id: String, value: String) {
        if (value.isBlank()) SecureStore.delete(ctx, "mcpSecret_$id")
        else SecureStore.put(ctx, "mcpSecret_$id", value)
    }

    fun addCustom(ctx: Context, def: McpServerDef): Boolean {
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(p.getString("custom", "[]"))
            arr.put(
                JSONObject().put("id", def.id).put("name", def.name).put("vendor", def.vendor)
                    .put("transport", def.transport).put("url", def.url ?: "")
                    .put("command", def.command ?: "").put("headerKind", def.headerKind)
                    .put("note", def.note)
            )
            p.edit().putString("custom", arr.toString()).apply()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun removeCustom(ctx: Context, id: String) {
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(p.getString("custom", "[]"))
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") != id) kept.put(o)
            }
            p.edit().putString("custom", kept.toString()).apply()
            SecureStore.delete(ctx, "mcpToken_$id")
            SecureStore.delete(ctx, "mcpRefresh_$id")
            SecureStore.delete(ctx, "mcpSecret_$id")
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("clientId_$id").apply()
            setEnabled(ctx, id, false)
        } catch (e: Exception) {}
    }

    // vc246 GELOMBANG 5 (FT-M9): pengingat scope di baris status
    // GitHub — token ditempel user, app tidak bisa membatasi scope-nya
    // dari sisi klien; yang bisa dilakukan UI adalah mengingatkan.
    fun statusLine(ctx: Context, d: McpServerDef): String {
        val on = if (isEnabled(ctx, d.id)) "Aktif" else "Nonaktif"
        val tok = if (d.headerKind == "none") "tanpa token" else if (hasToken(ctx, d.id)) "token ada" else "token BELUM"
        val tag = if (d.official) "RESMI" else "KUSTOM"
        return "• ${d.name} (${d.vendor}, $tag) — ${d.transport} — $on — $tok"
    }
}
