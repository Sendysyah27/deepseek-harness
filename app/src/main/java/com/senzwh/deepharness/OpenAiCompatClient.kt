package com.senzwh.deepharness

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.TreeMap

/**
 * Klien LLM OpenAI-compatible: OpenAI, OpenRouter, Groq, Ollama, vLLM,
 * LiteLLM, dan endpoint lain yang mengekspos POST /chat/completions dan
 * GET /v1/models.
 *
 * KEY API dikirim sebagai header Authorization Bearer dan TIDAK PERNAH
 * ditulis ke string error / output tool (semua error dipangkas + disaring
 * via RedactSecrets).
 *
 * SATU-SATUNYA tempat HTTP Models API di app ini: [chatMessages]/[chat]/
 * [ask] (POST {base}/chat/completions) dan [listModels] (GET {base}/models,
 * base sudah dinormalisasi ke .../v1 oleh [normalizeBaseUrl]).
 */
/** Hasil sinkron satu-tembakan (dipakai [ask]). */
data class Result(val content: String, val thinking: String = "", val model: String = "")

/** Satu pesan chat. `imageUrl` hanya untuk role user (K5); `toolCallId`
 *  hanya untuk role "tool" (hasil tool native, §2.5.3); `toolCalls`
 *  hanya untuk role "assistant" (tool call yang dibuat pesan itu, §2.4 —
 *  dibawa saat lanjutan supaya round-trip role:tool valid OpenAI). */
data class ChatMessage(
    val role: String,
    val content: String,
    val imageUrl: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<NativeToolCall> = emptyList()
)

/** Satu function call native (OpenAI `tool_calls`). `argumentsBroken`
 *  = arguments bukan JSON valid (disimpan mentah, repair di pemanggil,
 *  bukan di client — §2.3). `index` = indeks delta SSE asal (dipakai
 *  pengurutan hasil streaming; default 0 untuk non-stream). */
data class NativeToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val argumentsBroken: Boolean = false,
    val index: Int = 0
)

/** Penolakan tools oleh provider (§2.5, Fase 5a): 400 yang menyebut
 *  tools → provider tidak mendukung function calling; 400 yang menyebut
 *  role/tool → pesan hasil tool `role:tool` ditolak server (fallback
 *  teks SEKALI, §2.5.3). */
enum class ToolsRejection { TOOLS_UNSUPPORTED, ROLE_TOOL_FALLBACK }

/** Hasil satu giliran chat (streaming maupun non-stream). */
data class ChatResult(
    val content: String,
    val thinking: String = "",
    val model: String = "",
    val toolCalls: List<NativeToolCall> = emptyList(),
    val finishReason: String? = null
)

/** Dukungan vision per model (§5.10.5). Tanpa info kapabilitas apa pun
 *  = UNKNOWN, BUKAN unsupported. */
enum class VisionSupport { SUPPORTED, UNKNOWN, UNSUPPORTED }

data class ModelInfo(val id: String, val vision: VisionSupport = VisionSupport.UNKNOWN)

enum class ModelsErrorKind {
    AUTH_NEEDED,        // 401/403 tanpa key — user harus isi key
    AUTH_REJECTED,      // 401/403 dengan key — key ditolak
    NOT_FOUND,          // 404 — endpoint /v1/models tidak ada
    NOT_JSON,           // respons bukan JSON (halaman blokir/login)
    NOT_JSON_STRUCTURE, // JSON tapi tanpa kunci data/models
    TIMEOUT,            // connect/read timeout
    UNREACHABLE,        // host tidak terjangkau / URL tidak valid
    HTTP_ERROR,         // kode HTTP lain
    UNKNOWN
}

sealed class ModelsResult {
    /** `models` = maks 200 pertama (urut alfabet, dedupe); `totalCount` =
     *  jumlah model dikenali sebelum cap; `skippedCount` = entri dilewati. */
    data class Ok(
        val models: List<ModelInfo>,
        val fetchedAtMs: Long,
        val totalCount: Int = models.size,
        val skippedCount: Int = 0
    ) : ModelsResult()

    data class Err(val kind: ModelsErrorKind, val message: String) : ModelsResult()
}

/** Hasil [parseModelsList]: daftar tampil + total + jumlah dilewati. */
data class ParsedModels(val models: List<ModelInfo>, val totalCount: Int, val skippedCount: Int)

class ModelsParseException(val kind: ModelsErrorKind, message: String) : Exception(message)

/** Hasil parse satu aliran SSE: hasil akhir + jumlah baris `data:` yang
 *  dilewati (bukan JSON / tanpa choices[0]) + jumlah baris `data:` total. */
data class SseParseOutcome(val result: ChatResult, val skippedLines: Int, val dataLines: Int)

/** Dibatalkan user (tombol ■) — BUKAN error jaringan. Pemanggil WAJIB
 *  membedakannya: tanpa retry/fallback non-stream, tanpa bubble ⚠️,
 *  masuk jalur akhir-stop ([MainActivity.endTurnStopped]). */
class UserCancelledException(message: String = "Dibatalkan user") : Exception(message)

object OpenAiCompatClient {

    /** Penanda onError untuk pembatalan user — dibawa sebagai String
     *  karena chatMessages berbasis callback; [LlmEngineChat] mengubahnya
     *  kembali menjadi [UserCancelledException]. */
    const val USER_CANCELLED_MARKER = "USER_CANCELLED: dibatalkan user"

    // vc334 R1 (A1/A4): handle pembatalan request chat yang sedang
    // berjalan. SATU-SATUNYA klien LLM adalah HttpURLConnection (bukan
    // OkHttp) — batal = flag + disconnect() + tutup InputStream.
    // PROBE 27 Nov 2026 (/tmp/cancel-probe, Temurin JDK 21): disconnect()
    // — bahkan tutup InputStream dari thread lain — TIDAK menginterupsi
    // readLine() yang sedang blokir pada respons chunked; klien baru
    // kembali saat server mengirim [DONE]/selesai (t=5154/5176 dari
    // server tahan 5 dtk). Karena itu promptness R1 TIDAK bergantung
    // pada interupsi socket: giliran ditutup segera di UI
    // (MainActivity.requestStopTurnVc306 → endTurnStopped) dan hasil
    // telat dibuang lewat token generasi; disconnect/close di sini
    // adalah best-effort (efektif bila read sedang di antara baris /
    // koneksi idle, dan mencegah fallback). Di Android, disconnect()
    // HttpURLConnection (berbasis OkHttp) umumnya menginterupsi read
    // lebih cepat — perilaku itu bonus, bukan tumpuan.
    @Volatile var currentChatConn: HttpURLConnection? = null
        private set
    @Volatile private var currentChatStream: InputStream? = null
    @Volatile private var cancelRequested = false

    /** Minta batal request chat yang sedang berjalan (atau yang akan
     *  dibaca berikutnya di parseSse): set flag + tutup stream + putus
     *  socket. */
    fun cancelCurrentChat() {
        cancelRequested = true
        try { currentChatStream?.close() } catch (e: Exception) {}
        try { currentChatConn?.disconnect() } catch (e: Exception) {}
    }

    fun isChatCancelled(): Boolean = cancelRequested

    /** Reset flag cancel (dipakai tes + awal tiap chatInternal). */
    fun resetCancelFlag() {
        cancelRequested = false
    }



    // Konstanta timeout/limit (di object langsung — object tidak boleh punya companion).
    const val MAX_TOOL_CALLS = 4
    const val MAX_MODELS_SHOWN = 200
    const val CHAT_CONNECT_TIMEOUT_MS = 20_000
    const val CHAT_READ_TIMEOUT_MS = 120_000
    const val MODELS_CONNECT_TIMEOUT_MS = 10_000
    const val MODELS_READ_TIMEOUT_MS = 15_000

    /** Gagal yang TIDAK boleh memicu fallback non-stream: format/isi respons
     *  tidak dikenal atau jawaban kosong — bukan masalah jaringan (§0.3). */
    private open class NoFallbackException(message: String) : Exception(message)
    private class EmptyAnswerException(message: String) : NoFallbackException(message)
    private class UnknownFormatException(message: String) : NoFallbackException(message)
    private class HttpStatusException(val code: Int, message: String) : Exception(message)

    // ------------------------------------------- penolakan tools (§2.5)

    /** Klasifikasi murni pesan error HTTP (§2.5): null bila bukan 400
     *  yang menyebut tools/role. Urutan: role dulu — "role 'tool' is not
     *  supported" menyebut keduanya, dan itu kasus fallback (§2.5.3),
     *  bukan unsupported. "function calling" tanpa kata tool tetap
     *  dihitung penolakan tools. Pencatatan (tandai provider di
     *  cfg_<id>) dilakukan pemanggil (LlmEngineChat), bukan di sini —
     *  client tidak tahu id provider / Context. */
    fun classifyToolsRejection(errorMessage: String?): ToolsRejection? {
        val m = errorMessage ?: return null
        if (!m.contains("400")) return null
        val lower = m.lowercase()
        return when {
            lower.contains("role") -> ToolsRejection.ROLE_TOOL_FALLBACK
            lower.contains("tool") || lower.contains("function") -> ToolsRejection.TOOLS_UNSUPPORTED
            else -> null
        }
    }

    /** Bubble penolakan tools (§2.5 butir 1(b)) — sediakan di sini,
     *  JANGAN hardcode di MainActivity. */
    fun toolsUnsupportedMessage(providerId: String): String =
        "⚠️ Provider '$providerId' menolak function calling (model ini tidak mendukung tools). Ganti model atau provider di Setup > 73."

    // ---------------------------------------------------------------- ask

    @Throws(Exception::class)
    fun ask(provider: LlmProvider, apiKey: String?, prompt: String, timeoutMs: Int = 120000): Result {
        if (provider.kind != LlmKind.OPENAI_COMPAT) {
            throw IllegalArgumentException("provider '${provider.id}' bukan OpenAI-compatible")
        }
        if (provider.baseUrl.isBlank()) throw IllegalArgumentException("baseUrl provider kosong")
        if (provider.model.isBlank()) throw IllegalArgumentException("model provider kosong")
        if (apiKey.isNullOrBlank()) throw IllegalArgumentException("API key provider '${provider.id}' belum ada")

        val endpoint = normalizeBaseUrl(provider.baseUrl) + "/chat/completions"
        try {
            val r = chatInternal(endpoint = endpoint, apiKey = apiKey!!, model = provider.model,
                messages = listOf(ChatMessage("user", prompt)), tools = null, thinking = true,
                toolChoice = null, stream = false, onPartial = {}, onDeltaToolCall = {},
                timeoutMs = timeoutMs)
            return Result(r.content, r.thinking, r.model)
        } catch (e: EmptyAnswerException) {
            // §0.4: Result kosong dari ask() = throw (bukan Result("") yang lolos).
            throw RuntimeException("Jawaban kosong dari ${provider.id} — ${e.message}")
        }
    }

    // ---------------------------------------------------------------- chat

    /** Chat streaming (OpenAI SSE) + fallback non-streaming bila gagal stream.
     *  Wrapper tipis atas [chatMessages]: satu pesan user, tanpa tools.
     *  `onDone(content, thinking)` — dipertahankan untuk pemanggil lama
     *  (A1/A2 via `LlmProviders.chat`). */
    fun chat(baseUrl: String,
             apiKey: String,
             model: String,
             prompt: String,
             thinkingEnabled: Boolean,
             onPartial: (String) -> Unit,
             onDone: (String, String) -> Unit,
             onError: (String) -> Unit) {
        chatMessages(baseUrl, apiKey, model, listOf(ChatMessage("user", prompt)), null,
            thinkingEnabled, onPartial = onPartial, onDeltaToolCall = {},
            onDone = { r -> onDone(r.content, r.thinking) }, onError = onError)
    }

    /** Overload chat beneran (§0.3 + §2.3): messages (system/riwayat/user),
     *  `tools` native (JSONArray skema OpenAI `{"type":"function",...}` —
     *  lihat `ToolRegistry.toolsJson`), `toolChoice` opsional ("none" untuk
     *  jawaban final tanpa tool; String "auto"/"none" atau JSONObject
     *  `{"type":"function","function":{"name":...}}` untuk forced tool).
     *  `thinkingEnabled` diteruskan apa adanya — gating per-provider ada di
     *  helper pemanggil (client terima boolean saja, §2.5).
     *  JANGAN kirim `parallel_tool_calls` (default server sudah benar). */
    fun chatMessages(baseUrl: String,
                     apiKey: String,
                     model: String,
                     messages: List<ChatMessage>,
                     tools: JSONArray? = null,
                     thinkingEnabled: Boolean,
                     onPartial: (String) -> Unit,
                     onDeltaToolCall: (NativeToolCall) -> Unit,
                     onDone: (ChatResult) -> Unit,
                     onError: (String) -> Unit,
                     toolChoice: Any? = null,
                     // vc334 R3 (P-4): timeout socket — diteruskan ke
                     // chatInternal (dulu: selalu CHAT_READ_TIMEOUT_MS
                     // 120 dtk, apa pun timeoutMs pemanggil).
                     timeoutMs: Int = CHAT_READ_TIMEOUT_MS) {
        val endpoint = normalizeBaseUrl(baseUrl) + "/chat/completions"
        try {
            val result = chatInternal(endpoint, apiKey, model, messages, tools, thinkingEnabled,
                toolChoice, stream = true, onPartial, onDeltaToolCall, timeoutMs = timeoutMs)
            onDone(result)
        } catch (e: UserCancelledException) {
            // vc334 R1 (A1): dibatalkan user — JANGAN fallback non-stream
            // (itu akan menembak request penuh kedua) dan JANGAN bubble ⚠️.
            onError(USER_CANCELLED_MARKER)
        } catch (e: NoFallbackException) {
            // Format tak dikenal / jawaban kosong: langsung onError, jangan
            // fallback buta ke non-stream (§0.3 butir 3, §0.4).
            onError(sanitize(e.message ?: "format respons tidak dikenal"))
        } catch (e: Exception) {
            // vc334 R1 (A1): batal yang tiba lewat IOException sesudah
            // disconnect() (bukan lewat parseSse) tetap user-cancel —
            // jangan fallback non-stream.
            if (cancelRequested) {
                onError(USER_CANCELLED_MARKER)
                return
            }
            // vc334 R3 (P-1): HTTP 4xx deterministik (auth ditolak /
            // model tak ada / kuota 429) — mengulang request yang sama
            // akan gagal sama; langsung error, tanpa POST ulang penuh
            // non-stream. (Versi TERBATAS L6 — penghapusan total
            // fallback = NEEDS-DECISION D3, jangan lakukan: 5xx +
            // gagal transport tetap fallback sekali di bawah.)
            val statusCode = (e as? HttpStatusException)?.code
            if (statusCode != null && statusCode in 400..499) {
                onError(sanitize(e.message ?: "HTTP $statusCode"))
                return
            }
            // Fallback non-streaming sekali (stream gagal karena jaringan/HTTP).
            try {
                val result = chatInternal(endpoint, apiKey, model, messages, tools, thinkingEnabled,
                    toolChoice, stream = false, onPartial = {}, onDeltaToolCall = {},
                    timeoutMs = timeoutMs)
                onDone(result)
            } catch (e2: Exception) {
                onError(sanitize(e2.message ?: e.message ?: "gagal terhubung ke provider"))
            }
        }
    }

    // ------------------------------------------------------------ chat core

    private fun chatInternal(endpoint: String,
                             apiKey: String,
                             model: String,
                             messages: List<ChatMessage>,
                             tools: JSONArray?,
                             thinking: Boolean,
                             toolChoice: Any?,
                             stream: Boolean,
                             onPartial: (String) -> Unit,
                             onDeltaToolCall: (NativeToolCall) -> Unit,
                             timeoutMs: Int): ChatResult {
        val body = buildChatBody(model, messages, tools, thinking, toolChoice, stream)
        // vc334 R1 (A1): tiap panggilan mulai dengan flag cancel bersih;
        // conn disimpan agar cancelCurrentChat() bisa disconnect().
        cancelRequested = false
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        currentChatConn = conn
        conn.requestMethod = "POST"
        conn.connectTimeout = CHAT_CONNECT_TIMEOUT_MS
        conn.readTimeout = timeoutMs
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val raw = readLimited(if (code in 200..299) conn.inputStream else conn.errorStream, 8192)
                throw HttpStatusException(code, httpErrorMessage(code, raw))
            }
            if (cancelRequested) throw UserCancelledException()
            if (stream) {
                // Streaming beneran: baca baris demi baris, panggil callback
                // per baris data: — BUKAN readText() dulu (§0.3 butir 3).
                val stream = conn.inputStream
                currentChatStream = stream
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val outcome = parseSse(reader, onPartial, onDeltaToolCall)
                    val r = outcome.result
                    if (cancelRequested) throw UserCancelledException()
                    if (r.content.isBlank() && r.thinking.isBlank() && r.toolCalls.isEmpty()) {
                        throw emptyAnswerError(r.finishReason, outcome.skippedLines, outcome.dataLines)
                    }
                    r
                }
            } else {
                val stream = conn.inputStream
                currentChatStream = stream
                val raw = readLimited(stream, 1 shl 20)
                if (cancelRequested) throw UserCancelledException()
                parseNonStream(raw, model)
            }
        } catch (e: IOException) {
            // vc334 R1 (A1): IOException sesudah disconnect() = batal user,
            // bukan error jaringan — angkat sebagai UserCancelledException
            // supaya ask() dan pemanggil lain tidak salah mengira timeout.
            if (cancelRequested) throw UserCancelledException() else throw e
        } finally {
            currentChatStream = null
            if (currentChatConn === conn) currentChatConn = null
            try { conn.disconnect() } catch (e: Exception) {}
        }
    }

    private fun buildChatBody(model: String,
                              messages: List<ChatMessage>,
                              tools: JSONArray?,
                              thinking: Boolean,
                              toolChoice: Any?,
                              stream: Boolean): String {
        val body = JSONObject()
        body.putIfNotBlank("model", model) // model opsional di sisi ini; dipakai oleh beberapa endpoint
        val msgs = JSONArray()
        for (m in messages) {
            val o = JSONObject().put("role", m.role)
            if (m.role == "user" && m.imageUrl != null) {
                val parts = JSONArray()
                parts.put(JSONObject().put("type", "text").put("text", m.content))
                parts.put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", m.imageUrl)))
                o.put("content", parts)
            } else {
                o.put("content", m.content)
            }
            if (m.role == "tool" && m.toolCallId != null) o.put("tool_call_id", m.toolCallId)
            if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
                val tcs = JSONArray()
                for (tc in m.toolCalls) {
                    tcs.put(JSONObject().put("id", tc.id).put("type", "function")
                        .put("function", JSONObject().put("name", tc.name).put("arguments", tc.argumentsJson)))
                }
                o.put("tool_calls", tcs)
            }
            msgs.put(o)
        }
        body.put("messages", msgs)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            when (toolChoice) {
                is String -> body.put("tool_choice", toolChoice)
                is JSONObject -> body.put("tool_choice", toolChoice)
                else -> body.put("tool_choice", "auto")
            }
        } else if (toolChoice == "none") {
            body.put("tool_choice", "none")
        }
        body.put("stream", stream)
        if (thinking) body.put("thinking_enabled", thinking) // beberapa kompatibel melewati field tak dikenal
        return body.toString()
    }

    /** Parse respons non-stream `{"choices":[{"message":{...}}]}`. */
    private fun parseNonStream(raw: String, modelFallback: String): ChatResult {
        val obj = try { JSONObject(raw) } catch (e: Exception) {
            throw UnknownFormatException("Respons bukan JSON: ${sanitize(raw.take(200))}")
        }
        if (obj.has("error")) {
            val msg = extractErrorMessage(raw) ?: "Error dari provider"
            throw Exception("Error dari provider: ${sanitize(msg)}")
        }
        val arr = obj.optJSONArray("choices")
        val first = if (arr != null && arr.length() > 0) arr.optJSONObject(0) else null
        val finishReason = first?.optString("finish_reason", "")?.takeIf { it.isNotEmpty() }
        val msg = first?.optJSONObject("message")
        val content = messageContent(msg)
        val think = msg?.optString("reasoning_content", "") ?: ""
        val toolCalls = messageToolCalls(msg)
        val modelOut = obj.optString("model", modelFallback)
        val result = ChatResult(RedactSecrets.redact(content), RedactSecrets.redact(think.trim()),
            modelOut, toolCalls, finishReason)
        if (result.content.isBlank() && result.thinking.isBlank() && result.toolCalls.isEmpty()) {
            throw emptyAnswerError(finishReason, skippedLines = 0, dataLines = 0)
        }
        return result
    }

    /** Isi `message.content`: string, atau array part (`{"type":"text",
     *  "text":...}` digabung; part lain diabaikan), atau null → "". */
    private fun messageContent(msg: JSONObject?): String {
        if (msg == null) return ""
        return when (val v = msg.opt("content")) {
            is String -> v
            is JSONArray -> (0 until v.length()).joinToString("") { i ->
                when (val part = v.opt(i)) {
                    is String -> part
                    is JSONObject -> part.optString("text", "")
                    else -> ""
                }
            }
            else -> ""
        }
    }

    /** `message.tool_calls[]` → maks [MAX_TOOL_CALLS], urut kemunculan. */
    private fun messageToolCalls(msg: JSONObject?): List<NativeToolCall> {
        if (msg == null) return emptyList()
        val arr = msg.optJSONArray("tool_calls") ?: return emptyList()
        val out = ArrayList<NativeToolCall>()
        for (i in 0 until minOf(arr.length(), MAX_TOOL_CALLS)) {
            val tc = arr.optJSONObject(i) ?: continue
            val fn = tc.optJSONObject("function")
            val id = tc.optString("id", "")
            val name = fn?.optString("name", "") ?: ""
            val args = fn?.optString("arguments", "") ?: ""
            out.add(NativeToolCall(id, name, args, index = tc.optInt("index", i),
                argumentsBroken = isArgumentsBroken(args)))
        }
        return out
    }

    // ------------------------------------------------------------------ SSE

    /** Parse SSE OpenAI-compatible dari reader, baris demi baris:
     *  baris "data: {...}", akhir "data: [DONE]".
     *  - `onPartial` dipanggil PER fragmen content (satu baris data:).
     *  - `reasoning_content` terkumpul ke thinking.
     *  - `tool_calls` delta diakumulasi per index (id+name hanya dari delta
     *    pertama, arguments di-append dari delta berikut); `onDeltaToolCall`
     *    dipanggil per fragmen; di akhir digabung per index, urut index,
     *    parallel calls (index berbeda) TIDAK digabung, maks [MAX_TOOL_CALLS].
     *  - Baris `data:` yang dilewat (bukan JSON / tanpa choices[0]) dihitung
     *    di `skippedLines` — dipakai pesan error jawaban kosong (§0.4).
     *  - `finish_reason` diteruskan di [ChatResult.finishReason].
     *  TIDAK memanggil onDone/onError — pemanggil yang memutuskan (kosong =
     *  error, §0.4). */
    fun parseSse(reader: BufferedReader,
                 onPartial: (String) -> Unit,
                 onDeltaToolCall: (NativeToolCall) -> Unit): SseParseOutcome {
        val contentBuf = StringBuilder()
        val thinkBuf = StringBuilder()
        var modelOut = ""
        var finishReason: String? = null
        var skipped = 0
        var dataLines = 0
        val accByIndex = TreeMap<Int, ToolCallAcc>()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val trimmed = line!!.trim()
            if (!trimmed.startsWith("data:")) continue
            dataLines++
            // vc334 R1 (A4): cek kooperatif per baris data: — abort tanpa
            // menunggu baris berikut / read-timeout, walau koneksi hidup.
            if (cancelRequested) throw UserCancelledException()
            val payload = trimmed.substring(5).trim()
            if (payload.isBlank()) continue
            if (payload == "[DONE]") break
            val obj = try { JSONObject(payload) } catch (e: Exception) { skipped++; continue }
            if (modelOut.isBlank()) modelOut = obj.optString("model", "")
            val choices = obj.optJSONArray("choices")
            if (choices == null || choices.length() == 0) { skipped++; continue }
            val choice = choices.optJSONObject(0)
            if (choice == null) { skipped++; continue }
            val fr = choice.optString("finish_reason", "")
            if (fr.isNotEmpty()) finishReason = fr
            val delta = choice.optJSONObject("delta") ?: continue
            val c = delta.optString("content", "")
            if (c.isNotEmpty()) {
                // vc334 R3 (L9): redact SEKALI per fragmen — hasil redact
                // dipakai baik untuk onPartial maupun akumulasi; konten
                // penuh TIDAK di-redact ulang (dulu: 6 regex per fragmen
                // + ulang atas konten penuh — pindai ganda atas teks yang
                // sama). Keterbatasan jujur: rahasia yang TERBELAH di
                // antara dua fragmen lolos dari redact per-fragmen
                // (pola butuh teks utuh); redact ulang penuh tidak
                // dipakai karena redact() tidak idempotent (lihat catatan
                // idempotence di atas — redact atas teks yang sudah
                // ter-redact mengotori marker [REDAKSI: ...]).
                val redacted = RedactSecrets.redact(c)
                contentBuf.append(redacted)
                onPartial(redacted)
            }
            val t = delta.optString("reasoning_content", "")
            if (t.isNotEmpty()) thinkBuf.append(t)
            val toolArr = delta.optJSONArray("tool_calls")
            if (toolArr != null) {
                for (i in 0 until toolArr.length()) {
                    val tc = toolArr.optJSONObject(i) ?: continue
                    val idx = tc.optInt("index", 0)
                    val fn = tc.optJSONObject("function")
                    val fragId = tc.optString("id", "")
                    val fragName = fn?.optString("name", "") ?: ""
                    val fragArgs = fn?.optString("arguments", "") ?: ""
                    val acc = accByIndex.getOrPut(idx) { ToolCallAcc() }
                    if (acc.id.isEmpty() && fragId.isNotEmpty()) acc.id = fragId
                    if (acc.name.isEmpty() && fragName.isNotEmpty()) acc.name = fragName
                    acc.args.append(fragArgs)
                    onDeltaToolCall(NativeToolCall(fragId, fragName, fragArgs, index = idx))
                }
            }
        }
        val toolCalls = ArrayList<NativeToolCall>()
        for ((idx, acc) in accByIndex) {
            if (toolCalls.size >= MAX_TOOL_CALLS) break
            val args = acc.args.toString()
            toolCalls.add(NativeToolCall(acc.id, acc.name, args, index = idx,
                argumentsBroken = isArgumentsBroken(args)))
        }
        val result = ChatResult(
            content = contentBuf.toString(),
            // vc334 R3 (L9): thinking di-redact SEKALI di sini (tidak
            // pernah di-redact per fragmen) — dan tidak dipanggil bila
            // kosong, supaya hitungan redact = jumlah fragmen content.
            thinking = if (thinkBuf.isEmpty()) "" else RedactSecrets.redact(thinkBuf.toString().trim()),
            model = modelOut,
            toolCalls = toolCalls,
            finishReason = finishReason
        )
        return SseParseOutcome(result, skipped, dataLines)
    }

    private class ToolCallAcc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    /** Arguments kosong = valid (tool tanpa argumen). Selain itu harus objek
     *  JSON — bila bukan, simpan mentah + tandai broken, jangan crash. */
    private fun isArgumentsBroken(args: String): Boolean {
        if (args.isBlank()) return false
        return try { JSONObject(args); false } catch (e: Exception) { true }
    }

    /** Pesan error jawaban kosong (§0.4): menyebut finish_reason bila
     *  length/content_filter, dan jumlah baris dilewati bila ada. */
    private fun emptyAnswerError(finishReason: String?, skippedLines: Int, dataLines: Int): EmptyAnswerException {
        val sb = StringBuilder("Jawaban kosong (HTTP 200 tapi tanpa isi — kemungkinan format respons tidak dikenali / model reasoning-only)")
        when (finishReason) {
            "length" -> sb.append(" (finish_reason: length — output kemungkinan terpotong karena batas token)")
            "content_filter" -> sb.append(" (finish_reason: content_filter — jawaban diblokir filter konten)")
            null -> {}
            else -> sb.append(" (finish_reason: $finishReason)")
        }
        if (dataLines == 0) sb.append(" — respons tidak mengandung baris data: SSE")
        else if (skippedLines > 0) sb.append(" — $skippedLines baris respons dilewati karena format tidak dikenal")
        sb.append(". Coba lagi atau ganti model.")
        return EmptyAnswerException(sb.toString())
    }

    // ------------------------------------------------------------ /v1/models

    /** Normalisasi baseUrl — SATU fungsi dipakai chat + models (§5.10.6):
     *  trim → buang trailing "/" → bila berakhir "/v1/models" atau
     *  "/chat/completions" potong jadi base → bila tanpa "/v1" TAMBAH "/v1"
     *  sekali (idempotent). Hasil dipakai sebagai `{base}/chat/completions`
     *  dan `{base}/models` (= `{baseUrl}/v1/models` bagi pemanggil). */
    fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim()
        while (base.endsWith("/")) base = base.dropLast(1)
        if (base.endsWith("/v1/models")) base = base.dropLast("/v1/models".length)
        else if (base.endsWith("/chat/completions")) base = base.dropLast("/chat/completions".length)
        while (base.endsWith("/")) base = base.dropLast(1)
        if (!base.endsWith("/v1")) base += "/v1"
        return base
    }

    /** GET {base}/v1/models (K6, §5.10). Key dikirim bila ada; tanpa key pun
     *  dicoba dulu (Ollama/vLLM lokal mengizinkan tanpa auth). Key TIDAK
     *  PERNAH masuk log/pesan error. */
    fun listModels(baseUrl: String, apiKey: String?): ModelsResult {
        if (baseUrl.isBlank()) {
            return ModelsResult.Err(ModelsErrorKind.UNREACHABLE, "Base URL kosong — isi base URL dulu.")
        }
        val endpoint = try {
            normalizeBaseUrl(baseUrl) + "/models"
        } catch (e: Exception) {
            return ModelsResult.Err(ModelsErrorKind.UNREACHABLE, "Base URL tidak valid — periksa alamat.")
        }
        val conn = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (e: MalformedURLException) {
            return ModelsResult.Err(ModelsErrorKind.UNREACHABLE, "Base URL tidak valid — periksa alamat.")
        } catch (e: Exception) {
            return ModelsResult.Err(ModelsErrorKind.UNREACHABLE, "Base URL tidak terjangkau — periksa alamat / internet.")
        }
        conn.requestMethod = "GET"
        conn.connectTimeout = MODELS_CONNECT_TIMEOUT_MS
        conn.readTimeout = MODELS_READ_TIMEOUT_MS
        if (!apiKey.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            val code = try {
                conn.responseCode
            } catch (e: SocketTimeoutException) {
                return ModelsResult.Err(ModelsErrorKind.TIMEOUT, "Waktu habis (15 dtk) — server lambat / tidak terjangkau.")
            } catch (e: UnknownHostException) {
                return ModelsResult.Err(ModelsErrorKind.UNREACHABLE, "Base URL tidak terjangkau — periksa alamat / internet.")
            } catch (e: ConnectException) {
                return ModelsResult.Err(ModelsErrorKind.UNREACHABLE, "Base URL tidak terjangkau — periksa alamat / internet.")
            } catch (e: IOException) {
                return ModelsResult.Err(ModelsErrorKind.UNREACHABLE, "Base URL tidak terjangkau — periksa alamat / internet.")
            }
            val raw = readLimited(if (code in 200..299) conn.inputStream else conn.errorStream, 1 shl 20)
            when (code) {
                in 200..299 -> {
                    try {
                        val parsed = parseModelsList(raw)
                        ModelsResult.Ok(parsed.models, System.currentTimeMillis(), parsed.totalCount, parsed.skippedCount)
                    } catch (e: ModelsParseException) {
                        ModelsResult.Err(e.kind, e.message ?: "Format daftar model tidak dikenal")
                    }
                }
                401, 403 -> if (apiKey.isNullOrBlank()) {
                    ModelsResult.Err(ModelsErrorKind.AUTH_NEEDED,
                        "Provider ini butuh API key untuk melihat daftar model — isi API key di bawah.")
                } else {
                    // §5.10.4: tampilkan kode saja, JANGAN body error provider
                    // mentah (bisa memuat potongan key / id akun).
                    ModelsResult.Err(ModelsErrorKind.AUTH_REJECTED,
                        "API key ditolak ($code) — periksa key, lalu coba lagi.")
                }
                404 -> ModelsResult.Err(ModelsErrorKind.NOT_FOUND,
                    "Alamat models tidak ditemukan (404) — periksa base URL (biasanya berakhiran /v1).")
                else -> ModelsResult.Err(ModelsErrorKind.HTTP_ERROR,
                    "HTTP $code: ${sanitize(raw.take(200)).ifBlank { "server mengembalikan error" }}")
            }
        } catch (e: SocketTimeoutException) {
            ModelsResult.Err(ModelsErrorKind.TIMEOUT, "Waktu habis (15 dtk) — server lambat / tidak terjangkau.")
        } catch (e: UnknownHostException) {
            ModelsResult.Err(ModelsErrorKind.UNREACHABLE, "Base URL tidak terjangkau — periksa alamat / internet.")
        } catch (e: ConnectException) {
            ModelsResult.Err(ModelsErrorKind.UNREACHABLE, "Base URL tidak terjangkau — periksa alamat / internet.")
        } catch (e: IOException) {
            ModelsResult.Err(ModelsErrorKind.UNREACHABLE, "Base URL tidak terjangkau — periksa alamat / internet.")
        } catch (e: Exception) {
            ModelsResult.Err(ModelsErrorKind.UNKNOWN, sanitize(e.message ?: "gagal mengambil daftar model"))
        } finally {
            try { conn.disconnect() } catch (e: Exception) {}
        }
    }

    /** Parser toleran daftar model (§5.10.6). Terima `data` (array objek
     *  ber-id ATAU array string) → fallback `models` (array objek ber-id ATAU
     *  ber-name → pakai name sebagai id) → fallback array tingkat atas.
     *  Abaikan entri tanpa id/nama kosong (dihitung `skippedCount`); dedupe
     *  (pertahankan pertama); urut alfabet case-insensitive; tampil maks
     *  [MAX_MODELS_SHOWN] pertama (total disimpan).
     *  @throws ModelsParseException NOT_JSON (bukan JSON) atau
     *  NOT_JSON_STRUCTURE (tidak ada data/models). */
    @Throws(ModelsParseException::class)
    fun parseModelsList(raw: String): ParsedModels {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
            throw ModelsParseException(ModelsErrorKind.NOT_JSON,
                "Server mengembalikan jawaban bukan JSON (kemungkinan halaman blokir/login) — periksa base URL.")
        }
        val rootObj: JSONObject? = if (trimmed.startsWith("[")) {
            null
        } else {
            try { JSONObject(trimmed) } catch (e: Exception) {
                throw ModelsParseException(ModelsErrorKind.NOT_JSON,
                    "Server mengembalikan jawaban bukan JSON (kemungkinan halaman blokir/login) — periksa base URL.")
            }
        }
        val fromModelsKey: Boolean
        val arr: JSONArray? = when {
            trimmed.startsWith("[") -> {
                fromModelsKey = false
                try { JSONArray(trimmed) } catch (e: Exception) {
                    throw ModelsParseException(ModelsErrorKind.NOT_JSON,
                        "Server mengembalikan jawaban bukan JSON (kemungkinan halaman blokir/login) — periksa base URL.")
                }
            }
            rootObj!!.opt("data") is JSONArray -> {
                fromModelsKey = false
                rootObj.optJSONArray("data")
            }
            rootObj.opt("models") is JSONArray -> {
                fromModelsKey = true
                rootObj.optJSONArray("models")
            }
            else -> {
                fromModelsKey = false
                null
            }
        }
        if (arr == null) {
            throw ModelsParseException(ModelsErrorKind.NOT_JSON_STRUCTURE,
                "Format daftar model tidak dikenal (tidak ada kunci data/models).")
        }
        val seen = LinkedHashSet<String>()
        val models = ArrayList<ModelInfo>()
        var skipped = 0
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is String -> {
                    if (v.isBlank()) { skipped++; continue }
                    if (!seen.add(v)) continue // duplikat: bukan rusak, tidak dihitung skipped
                    models.add(ModelInfo(v, VisionSupport.UNKNOWN))
                }
                is JSONObject -> {
                    val id = v.optString("id", "").trim().ifEmpty { null }
                        ?: if (fromModelsKey) v.optString("name", "").trim().ifEmpty { null } else null
                    if (id.isNullOrBlank()) { skipped++; continue }
                    if (!seen.add(id)) continue
                    models.add(ModelInfo(id, visionOf(v)))
                }
                else -> skipped++
            }
        }
        models.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.id })
        val total = models.size
        val shown = if (total > MAX_MODELS_SHOWN) models.subList(0, MAX_MODELS_SHOWN).toList() else models
        return ParsedModels(shown, total, skipped)
    }

    /** Deteksi kapabilitas vision per entri model (§5.10.5, urutan cek plan):
     *  sinyal positif mana pun → SUPPORTED; bila tidak ada positif tapi ada
     *  sinyal negatif eksplisit (modality tanpa image / supports_vision
     *  false / capabilities.vision false) → UNSUPPORTED; bila tidak ada info
     *  kapabilitas apa pun → UNKNOWN. */
    private fun visionOf(obj: JSONObject): VisionSupport {
        var explicitNegative = false
        val arch = obj.optJSONObject("architecture")
        if (arch != null) {
            val modality = arch.optString("modality", "")
            if (modality.isNotEmpty()) {
                if (modality.contains("image", ignoreCase = true)) return VisionSupport.SUPPORTED
                explicitNegative = true
            }
        }
        val params = obj.optJSONArray("supported_parameters")
        if (params != null) {
            for (i in 0 until params.length()) {
                if (params.optString(i, "").equals("image", ignoreCase = true)) return VisionSupport.SUPPORTED
            }
        }
        if (obj.has("supports_vision")) {
            if (obj.optBoolean("supports_vision", false)) return VisionSupport.SUPPORTED
            explicitNegative = true
        }
        val caps = obj.optJSONObject("capabilities")
        if (caps != null && caps.has("vision")) {
            if (caps.optBoolean("vision", false)) return VisionSupport.SUPPORTED
            explicitNegative = true
        }
        val modalities = obj.optJSONArray("modalities")
        if (modalities != null) {
            for (i in 0 until modalities.length()) {
                val m = modalities.optString(i, "")
                if (m.equals("image", ignoreCase = true) || m.equals("vision", ignoreCase = true)) {
                    return VisionSupport.SUPPORTED
                }
            }
        }
        return if (explicitNegative) VisionSupport.UNSUPPORTED else VisionSupport.UNKNOWN
    }

    // ---------------------------------------------------------------- util

    private fun JSONObject.putIfNotBlank(key: String, value: String?) {
        if (value?.isNotBlank() == true) put(key, value)
    }

    /** Ambil `error.message` (atau `error` string / `message` tingkat atas)
     *  dari body JSON error; null bila tidak ada. */
    private fun extractErrorMessage(raw: String): String? {
        return try {
            val obj = JSONObject(raw)
            val errObj = obj.optJSONObject("error")
            if (errObj != null) {
                val m = errObj.optString("message", "")
                if (m.isNotEmpty()) return m
            }
            when (val err = obj.opt("error")) {
                is String -> if (err.isNotEmpty()) return err
                else -> {}
            }
            val m = obj.optString("message", "")
            if (m.isNotEmpty()) m else null
        } catch (e: Exception) {
            null
        }
    }

    /** Pesan error HTTP: kode + pesan server yang disaring (tanpa Bearer/key). */
    private fun httpErrorMessage(code: Int, raw: String): String {
        val serverMsg = extractErrorMessage(raw)?.let { sanitize(it) }
        val detail = serverMsg ?: sanitize(raw.take(200)).ifBlank { null }
        return if (detail != null) "HTTP $code: $detail" else "HTTP $code"
    }

    /** Baca stream sampai habis dengan batas karakter (hindari body raksasa). */
    private fun readLimited(stream: InputStream?, maxChars: Int): String {
        if (stream == null) return ""
        return try {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(2048)
                while (sb.length < maxChars) {
                    val n = reader.read(buf)
                    if (n == -1) break
                    sb.append(buf, 0, minOf(n, maxChars - sb.length))
                }
                sb.toString()
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun sanitize(text: String): String =
        RedactSecrets.redact(text.replace(Regex("(?i)Bearer\\s+\\S+"), "Bearer <redacted>"))
}
