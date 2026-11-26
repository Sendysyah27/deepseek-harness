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
 * KEY API dikirim sebagai header Authorization Bearer dan TIDAK PERNAH
 * ditulis ke string error / output tool (semua error dipangkas + disaring
 * via RedactSecrets).
 */
object OpenAiCompatClient {

    data class Result(val content: String, val thinking: String = "", val model: String = "")

    @Throws(Exception::class)
    fun ask(provider: LlmProvider, apiKey: String?, prompt: String, timeoutMs: Int = 120000): Result {
        if (provider.kind != LlmKind.OPENAI_COMPAT) {
            throw IllegalArgumentException("provider '${provider.id}' bukan OpenAI-compatible")
        }
        if (provider.baseUrl.isBlank()) throw IllegalArgumentException("baseUrl provider kosong")
        if (provider.model.isBlank()) throw IllegalArgumentException("model provider kosong")
        if (apiKey.isNullOrBlank()) throw IllegalArgumentException("API key provider '${provider.id}' belum ada")

        var out: Result? = null
        var err: Exception? = null
        chatInternal(endpoint = provider.baseUrl.trimEnd('/') + "/chat/completions",
            apiKey = apiKey!!, model = provider.model, prompt = prompt, stream = false, thinking = true,
            onPartial = {}, onDone = { c, t, m -> out = Result(c, t, m) }, onError = { err = RuntimeException(it) },
            timeoutMs = timeoutMs)
        err?.let { throw it }
        return out ?: throw RuntimeException("Jawaban kosong dari ${provider.id}")
    }

    /** Chat streaming (OpenAI SSE) + fallback non-streaming bila gagal stream. */
    fun chat(baseUrl: String,
             apiKey: String,
             model: String,
             prompt: String,
             thinkingEnabled: Boolean,
             onPartial: (String) -> Unit,
             onDone: (String, String) -> Unit,
             onError: (String) -> Unit) {
        try {
            chatInternal(endpoint = baseUrl.trimEnd('/') + "/chat/completions",
                apiKey = apiKey, model = model, prompt = prompt, stream = true, thinking = thinkingEnabled,
                onPartial = onPartial, onDone = { content, think, _ -> onDone(content, think) },
                onError = { throw RuntimeException(it) }, timeoutMs = 120000)
        } catch (e: Exception) {
            // Fallback non-streaming.
            try {
                chatInternal(endpoint = baseUrl.trimEnd('/') + "/chat/completions",
                    apiKey = apiKey, model = model, prompt = prompt, stream = false, thinking = thinkingEnabled,
                    onPartial = {}, onDone = { c, t, _ -> onDone(c, t) }, onError = { throw RuntimeException(it) },
                    timeoutMs = 120000)
            } catch (e2: Exception) {
                onError(sanitize(e2.message ?: e.message ?: "gagal terhubung ke provider"))
            }
        }
    }

    private fun chatInternal(endpoint: String,
                             apiKey: String,
                             model: String,
                             prompt: String,
                             stream: Boolean,
                             thinking: Boolean,
                             onPartial: (String) -> Unit,
                             onDone: (String, String, String) -> Unit,
                             onError: (String) -> Unit,
                             timeoutMs: Int): Result {
        val body = JSONObject().apply {
            putIfNotBlank("model", model) // model opsional di sisi ini; dipakai oleh beberapa endpoint
            put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            put("stream", stream)
            if (thinking) put("thinking_enabled", thinking) // beberapa kompatibel melewati field tak dikenal
        }.toString()

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = timeoutMs
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (e: Exception) { "" }
            if (code !in 200..299) {
                throw Exception("HTTP $code: ${sanitize(raw.take(200))}")
            }
            if (stream) {
                val r = parseSse(raw, onPartial, onDone)
                return r
            } else {
                val obj = try { JSONObject(raw) } catch (e: Exception) {
                    throw Exception("Respons bukan JSON: ${sanitize(raw.take(200))}")
                }
                if (obj.has("error")) {
                    throw Exception("Error dari provider: ${sanitize(obj.optJSONObject("error")?.optString("message", "") ?: obj.optString("error"))}")
                }
                val arr = obj.optJSONArray("choices")
                val first = if (arr != null && arr.length() > 0) arr.optJSONObject(0) else null
                val msg = first?.optJSONObject("message")
                val content = when (val v = msg?.opt("content")) {
                    is String -> v
                    is org.json.JSONArray -> (0 until v.length()).joinToString("") { v.optString(it, "") }
                    else -> ""
                }
                val think = msg?.optString("reasoning_content") ?: ""
                val modelOut = obj.optString("model", model)
                val finalContent = RedactSecrets.redact(content)
                val finalThink = RedactSecrets.redact(think.trim())
                onDone(finalContent, finalThink, modelOut)
                Result(finalContent, finalThink, modelOut)
            }
        } finally {
            try { conn.disconnect() } catch (e: Exception) {}
        }
    }

    private fun JSONObject.putIfNotBlank(key: String, value: String?) {
        if (value?.isNotBlank() == true) put(key, value)
    }

    /** Parse SSE OpenAI-compatible: baris "data: {...}", akhir "data: [DONE]". */
    private fun parseSse(raw: String, onPartial: (String) -> Unit, onDone: (String, String, String) -> Unit): Result {
        val contentBuf = StringBuilder()
        val thinkBuf = StringBuilder()
        var modelOut = ""
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.substring(5).trim()
            if (payload.isBlank() || payload == "[DONE]") continue
            val obj = try { JSONObject(payload) } catch (e: Exception) { continue }
            if (modelOut.isBlank()) modelOut = obj.optString("model", "")
            val choices = obj.optJSONArray("choices")
            if (choices == null || choices.length() == 0) continue
            val delta = choices.optJSONObject(0).optJSONObject("delta")
            if (delta == null) continue
            val c = delta.optString("content", "")
            if (c.isNotEmpty()) {
                contentBuf.append(c)
                onPartial(RedactSecrets.redact(c))
            }
            val t = delta.optString("reasoning_content", "")
            if (t.isNotEmpty()) thinkBuf.append(t)
        }
        val finalContent = RedactSecrets.redact(contentBuf.toString())
        val finalThink = RedactSecrets.redact(thinkBuf.toString().trim())
        if (finalContent.isBlank() && finalThink.isNotBlank()) {
            onDone(finalThink, "", modelOut)
            return Result(finalThink, "", modelOut)
        } else {
            onDone(finalContent, finalThink, modelOut)
            return Result(finalContent, finalThink, modelOut)
        }
    }

    private fun sanitize(text: String): String =
        RedactSecrets.redact(text.replace(Regex("(?i)Bearer\\s+\\S+"), "Bearer <redacted>"))
}
