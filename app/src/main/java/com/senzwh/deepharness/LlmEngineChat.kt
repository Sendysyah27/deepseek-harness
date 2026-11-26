package com.senzwh.deepharness

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * §0.1 (Fase 0b) — SATU helper semua jalur LLM (Models API).
 *
 * Semua jalur LLM (chat 1-1, latar, reviewer, room, tim, graph) lewat
 * helper ini — TIDAK ADA call site baru yang memanggil LlmProviders.chat /
 * OpenAiCompatClient langsung kecuali helper ini sendiri.
 *
 * Kontrak yang ditegakkan helper (bukan oleh tiap call site):
 *  - LlmProviderStore.hydrate(ctx) SELALU dulu;
 *  - engine dibaca SEKALI via AgentEngines.current() yang sudah diperbaiki;
 *  - engine openai tapi provider belum siap → throw pesan kanonik
 *    "Provider '<id>' belum siap ... Atur via Setup > 73. Provider LLM.";
 *  - TIDAK ADA provider aktif yang configured (state NO_PROVIDER) → throw
 *    "Otak belum diatur — atur provider dulu di Setup > 73. Provider LLM."
 *    (JANGAN fallback ke DeepSeek);
 *  - Governor + TurnMetrics dipanggil DI SINI, sekali per panggilan;
 *  - jawaban kosong → throw (D4);
 *  - thinking diteruskan ke client HANYA bila record provider
 *    mengizinkan (thinkingParam terisi).
 *
 * Titik sambung Fase 4 (askWithFiles, §4.2): fungsi itu nanti HANYA
 * membangun List<ChatMessage> (pesan user dengan imageUrl base64) lalu
 * memanggil chatMessagesForEngine() — tanpa mengubah signature yang ada
 * di file ini.
 *
 * CATATAN ANTARMUKA (koordinasi F0a — OpenAiCompatClient.kt milik F0a,
 * diverifikasi terhadap file F0a yang sudah mendarat di staging):
 * tipe ChatMessage / NativeToolCall / ChatResult / ModelInfo /
 * ModelsResult / ModelsErrorKind / VisionSupport mengikuti plan §0.3/
 * §5.10.1 dan DIDEFINISIKAN di file client; helper ini hanya memakai.
 * chatMessages client menerima `tools` sebagai JSONArray skema OpenAI —
 * helper mengonversi List<ToolDef> lewat toolsToJson() (§2.2) sampai
 * ToolRegistry.toolsJson() mendarat (Fase 5).
 * chatMessages dipanggil dengan onDeltaToolCall berupa lambda yang
 * mengabaikan fragmen (daftar tool call final disampaikan via onDone /
 * onToolCalls) — streaming parsial tool call ditangani UI di Fase 5.
 */
enum class LlmCallKind { INTERACTIVE, BACKGROUND, REVIEWER, TEAM, ROOM, GRAPH }

/** Hasil routing engine → jalur LLM (murni, untuk tes regresi D1/D2). */
enum class LlmRoute { MODELS_API, DEEPSEEK_WEB, LOCAL_OFFLINE }

/** State setup provider first-run (§5.7). */
enum class ProviderSetupState { NO_PROVIDER, CONFIGURED, SKIPPED }

object LlmEngineChat {

    const val NO_PROVIDER_MESSAGE =
        "Otak belum diatur — atur provider dulu di Setup > 73. Provider LLM."

    fun providerNotReadyMessage(id: String): String =
        "Provider '$id' belum siap (butuh OpenAI-compatible + API key). Atur via Setup > 73. Provider LLM."

    fun emptyResultError(providerId: String): String =
        "Provider '$providerId' mengembalikan jawaban kosong (HTTP 200 tapi tanpa isi — " +
            "kemungkinan format respons tidak dikenali / model reasoning-only). Coba lagi atau ganti model."

    // ---- Fungsi pure (bisa diuji JVM) ----

    /** "openai" → false, "deepseek" → true (D1: openai tidak menyentuh WebView). */
    fun requiresWebView(engine: String): Boolean = when (engine) {
        "openai", "local" -> false
        else -> true
    }

    /**
     * Routing engine → jalur. Engine openai → helper Models API untuk
     * SEMUA kind (tidak ada kind yang jatuh ke DeepSeek).
     */
    fun routeFor(engine: String, kind: LlmCallKind): LlmRoute = when (engine) {
        "openai" -> LlmRoute.MODELS_API
        "local" -> LlmRoute.LOCAL_OFFLINE
        else -> LlmRoute.DEEPSEEK_WEB
    }

    /** Kind → bucket Governor (§3.2): ROOM/GRAPH masuk bucket bg. */
    fun bucketFor(kind: LlmCallKind): String = when (kind) {
        LlmCallKind.INTERACTIVE -> Governor.MODELS_BUCKET_INTERACTIVE
        LlmCallKind.BACKGROUND -> Governor.MODELS_BUCKET_BG
        LlmCallKind.REVIEWER -> Governor.MODELS_BUCKET_REVIEWER
        LlmCallKind.TEAM -> Governor.MODELS_BUCKET_TEAM
        LlmCallKind.ROOM -> Governor.MODELS_BUCKET_BG
        LlmCallKind.GRAPH -> Governor.MODELS_BUCKET_BG
    }

    /** State setup provider (§5.7): skip hanya berarti SKIPPED bila belum ada provider configured. */
    fun setupState(providerConfigured: Boolean, skipped: Boolean): ProviderSetupState =
        when {
            providerConfigured -> ProviderSetupState.CONFIGURED
            skipped -> ProviderSetupState.SKIPPED
            else -> ProviderSetupState.NO_PROVIDER
        }

    /** True bila tidak ada provider aktif yang configured (state NO_PROVIDER). */
    fun needsProviderSetup(activeProvider: LlmProvider?): Boolean =
        activeProvider == null || activeProvider.kind != LlmKind.OPENAI_COMPAT || !activeProvider.configured

    /** Simpan tanpa tes koneksi sukses → butuh dialog konfirmasi (bukan blokir mentah, §5.6). */
    fun saveNeedsConfirmation(testSucceeded: Boolean): Boolean = !testSucceeded

    /** Banner "🧠 Otak belum diatur" tampil selama skip dan belum ada provider configured. */
    fun skippedBannerVisible(flowProviderSkipped: Boolean, providerConfigured: Boolean): Boolean =
        flowProviderSkipped && !providerConfigured

    /**
     * thinking diteruskan ke client HANYA bila record provider
     * mengizinkan (thinkingParam terisi, §2.5). Pref DeepSeek
     * dsModelVariant TIDAK dipakai di jalur openai.
     */
    fun thinkingAllowed(provider: LlmProvider, thinking: Boolean): Boolean =
        thinking && provider.thinkingParam.isNotBlank()

    /**
     * Konversi List<ToolDef> → JSONArray skema OpenAI `tools` (§2.2).
     * SEMENTARA sampai ToolRegistry.toolsJson() mendarat (Fase 5, K2) —
     * saat itu fungsi ini diganti satu baris memanggil toolsJson().
     * Aturan: tiap tool → {"type":"function","function":{name, description
     * (cap 2000 char), parameters:{type:object, properties, required}}};
     * ArgSpec.type dipetakan string/int/bool/array/object/number;
     * enumValues terisi → "enum". Null/empty → null (tanpa tools).
     */
    fun toolsToJson(tools: List<ToolRegistry.ToolDef>?): JSONArray? {
        if (tools.isNullOrEmpty()) return null
        val arr = JSONArray()
        for (def in tools) {
            val props = JSONObject()
            val required = JSONArray()
            for (arg in def.args) {
                val schema = JSONObject()
                schema.put("type", when (arg.type.lowercase()) {
                    "int", "integer" -> "integer"
                    "bool", "boolean" -> "boolean"
                    "array" -> "array"
                    "object" -> "object"
                    "number" -> "number"
                    else -> "string"
                })
                if (arg.enumValues.isNotEmpty()) {
                    schema.put("enum", JSONArray(arg.enumValues))
                }
                props.put(arg.name, schema)
                if (arg.required) required.put(arg.name)
            }
            val params = JSONObject()
            params.put("type", "object")
            params.put("properties", props)
            params.put("required", required)
            val fn = JSONObject()
            fn.put("name", def.name)
            fn.put("description", def.desc.take(2000))
            fn.put("parameters", params)
            val tool = JSONObject()
            tool.put("type", "function")
            tool.put("function", fn)
            arr.put(tool)
        }
        return arr
    }

    // ---- Resolusi provider (Android) ----

    /**
     * Hydrate + kembalikan provider aktif. Throw bila tidak ada provider
     * OpenAI-compatible yang configured (NO_PROVIDER) atau provider aktif
     * belum siap (pesan kanonik). TIDAK PERNAH fallback ke DeepSeek.
     */
    @Throws(Exception::class)
    fun resolveProvider(ctx: Context): LlmProvider {
        LlmProviderStore.hydrate(ctx)
        val candidates = LlmProviders.all().filter { it.kind == LlmKind.OPENAI_COMPAT && it.configured }
        if (candidates.isEmpty()) throw IllegalStateException(NO_PROVIDER_MESSAGE)
        val activeId = LlmProviderStore.activeId(ctx)
        val active = LlmProviders.find(activeId)
        if (active == null || active.kind != LlmKind.OPENAI_COMPAT || !active.configured) {
            throw IllegalStateException(providerNotReadyMessage(activeId))
        }
        return active
    }

    // ---- Helper utama (Android) ----

    /**
     * Sinkron (blokir thread pemanggil, maks timeoutMs). Dipakai semua
     * jalur sekunder (B-group). Mengembalikan teks jawaban FINAL.
     * Melempar Exception dengan pesan user-friendly bila gagal/kosong.
     */
    @Throws(Exception::class)
    fun chatSyncForEngine(
        ctx: Context, prompt: String, kind: LlmCallKind,
        thinking: Boolean = false, timeoutMs: Int = 120_000
    ): String {
        return chatMessagesForEngine(
            ctx, listOf(ChatMessage("user", prompt)), kind,
            tools = null, thinking = thinking, timeoutMs = timeoutMs
        )
    }

    /**
     * Varian messages (system + riwayat + user). Dipakai A1/chat 1-1.
     * Bila respons hanya berisi tool_calls (tanpa content), mengembalikan
     * "" dan menyampaikan daftar call via onToolCalls — itu sukses,
     * bukan jawaban kosong (D4, pengecualian finish_reason tool_calls).
     */
    @Throws(Exception::class)
    fun chatMessagesForEngine(
        ctx: Context, messages: List<ChatMessage>,
        kind: LlmCallKind, tools: List<ToolRegistry.ToolDef>? = null,
        thinking: Boolean = false, timeoutMs: Int = 120_000,
        onPartial: (String) -> Unit = {},
        onToolCalls: (List<NativeToolCall>) -> Unit = {}
    ): String {
        // Engine dibaca SEKALI (current() sudah hydrate — D7).
        val engine = AgentEngines.current(ctx)
        if (engine != "openai") {
            when (engine) {
                "local" -> throw IllegalStateException(
                    "Mode offline lokal — hanya reminder/fakta/buka-app, tanpa LLM."
                )
                else -> throw IllegalStateException(
                    "Otak DeepSeek (web/API internal) tidak lagi dipakai helper ini — " +
                        "atur provider Models API di Setup > 73. Provider LLM."
                )
            }
        }
        val provider = resolveProvider(ctx)
        val bucket = bucketFor(kind)

        // Governor: gerbang SEBELUM eksekusi (interaktif tidak pernah digerbang).
        Governor.refusalModels(ctx, provider, bucket)?.let { throw IllegalStateException(it) }

        // TurnMetrics: sekali per panggilan. beginTurn hanya bila belum ada
        // giliran aktif (pemilik giliran — MainActivity/BgBrain — yang
        // memanggil finish); helper tidak pernah memanggil finish().
        if (!TurnMetrics.isBegun()) TurnMetrics.beginTurn(ctx)
        val promptChars = messages.sumOf { it.content.length }
        TurnMetrics.recordPromptTotal(promptChars)
        TurnMetrics.recordProvider(provider.id, engine)
        Governor.bumpModels(ctx, provider.id, bucket, Governor.weightFor(promptChars))

        val apiKey = LlmProviderStore.apiKey(ctx, provider.id) ?: ""
        val thinkingEnabled = thinkingAllowed(provider, thinking)

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<ChatResult?>(null)
        val errRef = AtomicReference<String?>(null)
        OpenAiCompatClient.chatMessages(
            provider.baseUrl, apiKey, provider.model, messages, toolsToJson(tools), thinkingEnabled,
            onPartial = onPartial,
            // Fragmen tool call streaming diabaikan di helper sinkron —
            // daftar final disampaikan via onToolCalls sesudah onDone.
            onDeltaToolCall = { },
            onDone = { r -> resultRef.set(r); latch.countDown() },
            onError = { e -> errRef.set(e); latch.countDown() }
        )
        val done = try {
            latch.await(timeoutMs.toLong() + 30_000L, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!done) throw RuntimeException("Provider '${provider.id}' timeout (${timeoutMs} ms) — tidak ada jawaban.")
        errRef.get()?.let { throw RuntimeException(RedactSecrets.redact(it)) }
        val result = resultRef.get()
            ?: throw RuntimeException("Provider '${provider.id}' timeout / tanpa hasil.")

        if (result.toolCalls.isNotEmpty()) {
            val known = ToolRegistry.ALL.map { it.name }.toSet()
            for (tc in result.toolCalls) {
                TurnMetrics.recordStep(tc.name, tc.name !in known)
            }
            onToolCalls(result.toolCalls)
        }
        // D4: jawaban kosong = error — kecuali ada tool_calls.
        if (result.content.isBlank() && result.thinking.isBlank() && result.toolCalls.isEmpty()) {
            throw RuntimeException(emptyResultError(provider.id))
        }
        return result.content
    }

    /** Async callback (untuk UI). Wrapper di atas chatMessagesForEngine. */
    fun chatAsync(
        ctx: Context, prompt: String, kind: LlmCallKind,
        thinking: Boolean = false, timeoutMs: Int = 120_000,
        onDone: (String) -> Unit, onError: (String) -> Unit
    ) {
        Thread {
            try {
                onDone(chatSyncForEngine(ctx, prompt, kind, thinking, timeoutMs))
            } catch (e: Exception) {
                onError(e.message ?: e.toString())
            }
        }.apply { isDaemon = true }.start()
    }

    /** Async varian messages. Wrapper di atas chatMessagesForEngine. */
    fun chatAsyncMessages(
        ctx: Context, messages: List<ChatMessage>,
        kind: LlmCallKind, tools: List<ToolRegistry.ToolDef>? = null,
        thinking: Boolean = false, timeoutMs: Int = 120_000,
        onPartial: (String) -> Unit = {},
        onToolCalls: (List<NativeToolCall>) -> Unit = {},
        onDone: (String) -> Unit, onError: (String) -> Unit
    ) {
        Thread {
            try {
                onDone(
                    chatMessagesForEngine(
                        ctx, messages, kind, tools, thinking, timeoutMs, onPartial, onToolCalls
                    )
                )
            } catch (e: Exception) {
                onError(e.message ?: e.toString())
            }
        }.apply { isDaemon = true }.start()
    }
}
