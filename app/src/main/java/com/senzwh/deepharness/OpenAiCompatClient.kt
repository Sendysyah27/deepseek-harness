package com.senzwh.deepharness

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Klien LLM OpenAI-compatible: OpenAI, OpenRouter, Groq, Ollama, vLLM,
 * LiteLLM, dan endpoint lain yang mengekspos POST /chat/completions.
 *
 * TANPA streaming — satu POST, satu jawaban penuh. Ini disengaja agar
 * pemanggilan via tool agen tetap sederhana dan deterministik; DeepSeek
 * memakai jalur SSE/native sendiri dan TIDAK disentuh di sini.
 *
 * Key dikirim sebagai header Authorization Bearer dan TIDAK PERNAH
 * ditulis ke string error (semua error dipangkas + disaring).
 */
object OpenAiCompatClient {

    data class Result(val content: String, val model: String)

    @Throws(Exception::class)
    fun ask(provider: LlmProvider, apiKey: String?, prompt: String, timeoutMs: Int = 120000): Result {
        if (provider.kind != LlmKind.OPENAI_COMPAT) {
            throw IllegalArgumentException("provider '${provider.id}' bukan OpenAI-compatible")
        }
        if (provider.baseUrl.isBlank()) throw IllegalArgumentException("baseUrl provider kosong")
        if (provider.model.isBlank()) throw IllegalArgumentException("model provider kosong")
        if (apiKey.isNullOrBlank()) throw IllegalArgumentException("API key provider '${provider.id}' belum ada")

        val endpoint = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject().apply {
            put("model", provider.model)
            put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            put("stream", false)
        }.toString()

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = timeoutMs
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (e: Exception) { "" }
            if (code !in 200..299) {
                throw Exception("HTTP $code dari ${provider.id}: ${sanitize(raw.take(200))}")
            }
            val obj = try { JSONObject(raw) } catch (e: Exception) {
                throw Exception("Respons bukan JSON dari ${provider.id}: ${sanitize(raw.take(200))}")
            }
            if (obj.has("error")) {
                throw Exception("Error dari ${provider.id}: ${sanitize(obj.optJSONObject("error")?.optString("message", "") ?: obj.optString("error"))}")
            }
            val arr = obj.optJSONArray("choices")
            val first = if (arr != null && arr.length() > 0) arr.optJSONObject(0) else null
            val msg = first?.optJSONObject("message")
            val content = when (val v = msg?.opt("content")) {
                is String -> v
                is org.json.JSONArray -> (0 until v.length()).joinToString("") { v.optString(it, "") }
                else -> ""
            }
            if (content.isBlank()) {
                throw Exception("Jawaban kosong dari ${provider.id} (finish=${first?.optString("finish_reason", "?")})")
            }
            Result(RedactSecrets.redact(content), obj.optString("model", provider.model))
        } finally {
            try { conn.disconnect() } catch (e: Exception) {}
        }
    }

    /** Key tidak pernah muncul — pola Bearer/sk-/gsk_ diganti sebelum tampil. */
    private fun sanitize(text: String): String =
        RedactSecrets.redact(text.replace(Regex("(?i)Bearer\\s+\\S+"), "Bearer <redacted>"))
}
