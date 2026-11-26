package com.senzwh.deepharness

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
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

/** Hasil routing engine → jalur LLM (murni, untuk tes regresi D1/D2).
 *  K1 (Fase 6): hanya MODELS_API (engine openai) dan LOCAL_OFFLINE
 *  (engine local) — tidak ada lagi jalur web. */
enum class LlmRoute { MODELS_API, LOCAL_OFFLINE }

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

    /** K1 (Fase 6): TIDAK ADA engine yang menyentuh WebView lagi —
     *  selalu false (D1: semua jalur LLM lewat Models API / offline). */
    fun requiresWebView(engine: String): Boolean = false


    /**
     * Routing engine → jalur. Engine openai → helper Models API untuk
     * SEMUA kind. Engine local → LOCAL_OFFLINE. Nilai lain (legacy/tak
     * dikenal) → MODELS_API: satu-satunya mesin online sesudah K1.
     */
    fun routeFor(engine: String, kind: LlmCallKind): LlmRoute = when (engine) {
        "local" -> LlmRoute.LOCAL_OFFLINE
        else -> LlmRoute.MODELS_API
    }

    /** Anggaran [RIWAYAT] prompt awal dari jumlah pesan user sesi ini
     *  sebelum giliran ini. Murni, tanpa Context — bisa diuji di JVM.
     *  Sesi pendek/normal: (60, 60000) persis seperti vc230; sesi
     *  panjang (>=40 pesan): (30, 30000) — hemat 30.000 char per panggilan.
     *  (Dulu TurnMetrics.historyBudgetVc321 — pindah ke sini K1 Fase 6,
     *  karena TurnMetrics tidak lagi mengenal sesi server.) */
    fun historyBudget(priorMsgCount: Int): Pair<Int, Int> =
        if (priorMsgCount >= HISTORY_LONG_THRESHOLD) Pair(HISTORY_LONG_N, HISTORY_LONG_CHARS)
        else Pair(HISTORY_NORMAL_N, HISTORY_NORMAL_CHARS)

    /** Ambang sesi panjang: jumlah pesan user sesi ini SEBELUM giliran ini. */
    const val HISTORY_LONG_THRESHOLD = 40
    /** Anggaran riwayat awal sesi panjang (vs normal 60 pesan/60.000 char). */
    const val HISTORY_LONG_N = 30
    const val HISTORY_LONG_CHARS = 30000
    /** Anggaran riwayat awal sesi normal/pendek — TIDAK berubah dari vc230. */
    const val HISTORY_NORMAL_N = 60
    const val HISTORY_NORMAL_CHARS = 60000

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
     * mengizinkan (thinkingParam terisi, §2.5).
     */
    fun thinkingAllowed(provider: LlmProvider, thinking: Boolean): Boolean =
        thinking && provider.thinkingParam.isNotBlank()

    /**
     * Native tools dipakai kecuali provider ditandai "text_fallback"
     * (§2.5, Fase 5a): provider itu menolak function calling native,
     * pemanggil memakai jalur teks. Nilai lain (native / unknown /
     * unsupported) → true; "unsupported" ditangani di error path
     * (400 → bubble + tanpa retry, bukan di sini).
     */
    fun shouldUseNativeTools(toolsSupport: String): Boolean =
        toolsSupport.trim().lowercase() != "text_fallback"

    /**
     * Konversi List<ToolDef> → JSONArray skema OpenAI `tools` (§2.2).
     * Delegasi satu baris ke ToolRegistry.toolsJson() (Fase 5a) —
     * termasuk penyaringan NATIVE_TOOLS_EXCLUDED. Null/empty → null
     * (tanpa tools); hasil kosong sesudah saring → null juga.
     */
    fun toolsToJson(tools: List<ToolRegistry.ToolDef>?): JSONArray? {
        if (tools.isNullOrEmpty()) return null
        val arr = ToolRegistry.toolsJson(tools)
        return if (arr.length() == 0) null else arr
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
        thinking: Boolean = false, timeoutMs: Int = 120_000,
        tools: List<ToolRegistry.ToolDef>? = null
    ): String {
        return chatMessagesForEngine(
            ctx, listOf(ChatMessage("user", prompt)), kind,
            tools = tools, thinking = thinking, timeoutMs = timeoutMs
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
        onToolCalls: (List<NativeToolCall>) -> Unit = {},
        toolChoice: Any? = null
    ): String {
        // Engine dibaca SEKALI (current() sudah hydrate — D7).
        val engine = AgentEngines.current(ctx)
        if (engine != "openai") {
            when (engine) {
                "local" -> throw IllegalStateException(
                    "Mode offline lokal — hanya reminder/fakta/buka-app, tanpa LLM."
                )
                else -> throw IllegalStateException(
                    "Engine '$engine' tidak dikenal — atur provider Models API di Setup > 73. Provider LLM."
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
            onError = { e -> errRef.set(e); latch.countDown() },
            toolChoice = toolChoice
        )
        val done = try {
            latch.await(timeoutMs.toLong() + 30_000L, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!done) throw RuntimeException("Provider '${provider.id}' timeout (${timeoutMs} ms) — tidak ada jawaban.")
        errRef.get()?.let { rawErr ->
            val clean = RedactSecrets.redact(rawErr)
            // §2.5 (Fase 5a): 400 penolakan tools dicatat di cfg_<id>,
            // bukan diam-diam — hanya bila request ini memang mengirim
            // tools. TOOLS_UNSUPPORTED → bubble §2.5 1(b), tanpa retry.
            // ROLE_TOOL_FALLBACK → tandai; fallback format teks sekali
            // dilakukan pemanggil (sendContinuation), bukan di sini.
            val rejection = OpenAiCompatClient.classifyToolsRejection(clean)
            if (rejection != null && tools != null) {
                when (rejection) {
                    ToolsRejection.TOOLS_UNSUPPORTED -> {
                        LlmProviderStore.markToolsSupport(ctx, provider.id, "unsupported")
                        throw RuntimeException(
                            OpenAiCompatClient.toolsUnsupportedMessage(provider.id))
                    }
                    ToolsRejection.ROLE_TOOL_FALLBACK -> {
                        LlmProviderStore.markToolRoleFallback(ctx, provider.id, true)
                    }
                }
            }
            throw RuntimeException(clean)
        }
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

    // ---- Vision: kirim gambar/dokumen (Fase 4a, §4.2) ----

    /**
     * Kirim gambar/dokumen ke provider Models API aktif (§4.2).
     *
     * Alur mengikat:
     *  a. resolveProvider (throw NO_PROVIDER / belum-siap, tanpa fallback);
     *  b. gate vision bila ada file gambar (IMAGE atau PDF-jadi-gambar):
     *     UNSUPPORTED → VisionUnsupportedException, UNKNOWN/SUPPORTED lanjut;
     *  c. pilah file — IMAGE disiapkan, TEXT_DOC/DOCX diekstrak jadi teks
     *     (cap provider.maxPromptChars ?: TurnMetrics.DEFAULT_CAP),
     *     PDF dirender halaman 1 jadi gambar, UNSUPPORTED → gagal jujur;
     *  d. maks 4 gambar (termasuk PDF-jadi-gambar);
     *  e. tiap gambar disiapkan (pola analyzePhotoAndContinue: skala maks
     *     1280, JPEG q80; ulang q60 + skala 0.5 bila masih >1 MB);
     *  f. SATU ChatMessage user per gambar (ChatMessage hanya punya satu
     *     imageUrl) — gambar pertama membawa prompt lengkap, sisanya
     *     membawa teks singkat "Gambar ke-N: <nama file>";
     *  g. panggil chatMessagesForEngine (Governor + D4 sudah di sana);
     *  h. sesudah sukses, Governor bump +1 per gambar;
     *  i. HTTP 400 penolakan vision → tandai model UNSUPPORTED di cache
     *     (fetchedAt dijaga), lalu throw pesan §4.4, TANPA retry.
     */
    @Throws(Exception::class)
    fun askWithFiles(
        ctx: Context, files: List<VisionFile>, prompt: String,
        kind: LlmCallKind, thinking: Boolean = false
    ): String {
        // a. Provider aktif — throw seperti helper lain, tanpa fallback.
        val provider = resolveProvider(ctx)

        // Klasifikasi dulu (murni, tanpa efek samping) — dipakai gate + pilah.
        val kinds = files.map { VisionFiles.classify(it.mime, it.fileName) }

        // b. Gate vision: ada file gambar (IMAGE atau PDF-jadi-gambar)?
        val hasImage = kinds.any { it == VisionFileKind.IMAGE || it == VisionFileKind.PDF }
        if (hasImage) {
            val catalog = ModelCaps.catalogMap(LlmProviderStore.loadModels(ctx, provider.id))
            if (ModelCaps.supportsVision(provider, provider.model, catalog) == VisionSupport.UNSUPPORTED) {
                throw VisionUnsupportedException(
                    VisionFiles.modelUnsupportedMessage(provider.model, provider.label)
                )
            }
        }

        // c. Pilah file.
        val docCap = provider.maxPromptChars ?: TurnMetrics.DEFAULT_CAP
        val docBlocks = StringBuilder()
        val pdfNotes = StringBuilder()
        val images = ArrayList<VisionFile>()
        for ((idx, file) in files.withIndex()) {
            when (kinds[idx]) {
                VisionFileKind.IMAGE -> images.add(file)
                VisionFileKind.TEXT_DOC -> {
                    val text = VisionFiles.truncateDocText(
                        String(file.bytes, Charsets.UTF_8), docCap
                    )
                    docBlocks.append("\n\n[Dokumen ${file.fileName}]\n").append(text)
                }
                VisionFileKind.DOCX -> {
                    val text = VisionFiles.truncateDocText(extractDocxText(file), docCap)
                    docBlocks.append("\n\n[Dokumen ${file.fileName}]\n").append(text)
                }
                VisionFileKind.PDF -> {
                    val rendered = renderPdfFirstPage(ctx, file)
                    images.add(
                        VisionFile(rendered.jpegBytes, file.fileName, "image/jpeg")
                    )
                    pdfNotes.append("\n\n(Ini halaman 1 dari ${rendered.pageCount} halaman ")
                        .append("PDF '${file.fileName}' — hanya halaman 1 yang dianalisis.)")
                }
                VisionFileKind.UNSUPPORTED -> throw IllegalArgumentException(
                    VisionFiles.unsupportedMessage(file)
                )
            }
        }

        // d. Batas gambar (termasuk PDF-jadi-gambar).
        if (images.size > VisionFiles.MAX_IMAGES) {
            throw IllegalArgumentException(
                "Maks ${VisionFiles.MAX_IMAGES} gambar per panggilan — kirim sisanya terpisah."
            )
        }

        // e. Siapkan tiap gambar → data URI JPEG base64.
        val imageUris = images.map { prepareVisionImage(it) }

        // f. Bangun messages: SATU ChatMessage user per gambar.
        val promptLengkap = StringBuilder(prompt)
            .append(docBlocks).append(pdfNotes).toString()
        val messages = ArrayList<ChatMessage>()
        if (imageUris.isEmpty()) {
            messages.add(ChatMessage("user", promptLengkap))
        } else {
            messages.add(ChatMessage("user", promptLengkap, imageUrl = imageUris[0]))
            for (i in 1 until imageUris.size) {
                messages.add(
                    ChatMessage(
                        "user", "Gambar ke-${i + 1}: ${images[i].fileName}",
                        imageUrl = imageUris[i]
                    )
                )
            }
        }

        // g. Panggil helper utama (Governor + D4 sudah ditangani di sana).
        val answer = try {
            chatMessagesForEngine(ctx, messages, kind, tools = null, thinking = thinking)
        } catch (e: RuntimeException) {
            // i. 400 penolakan vision → tandai model UNSUPPORTED, tanpa retry.
            if (VisionFiles.isVisionRejection400(e.message)) {
                try {
                    val cached = LlmProviderStore.loadModels(ctx, provider.id)
                    LlmProviderStore.saveModels(
                        ctx, provider.id,
                        VisionFiles.markUnsupported(cached, provider.model),
                        LlmProviderStore.loadModelsFetchedAt(ctx, provider.id)
                    )
                } catch (markErr: Exception) {
                    Log.w("LlmEngineChat", "askWithFiles: gagal menandai model unsupported: ${markErr.message}")
                }
                throw RuntimeException(
                    VisionFiles.serverRejectedMessage(
                        provider.label, provider.model,
                        RedactSecrets.redact(e.message ?: "")
                    )
                )
            }
            throw e
        }

        // h. Governor gambar: +1 per gambar, hanya bila sukses.
        if (images.isNotEmpty()) {
            Governor.bumpModels(ctx, provider.id, bucketFor(kind), images.size)
        }
        return answer
    }

    /** Ekstrak teks word/document.xml dari bytes DOCX (pola fallbackDocVc212). */
    private fun extractDocxText(file: VisionFile): String {
        ZipInputStream(ByteArrayInputStream(file.bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    return VisionFiles.stripDocxXml(
                        zin.readBytes().toString(Charsets.UTF_8)
                    )
                }
                entry = zin.nextEntry
            }
        }
        throw IllegalArgumentException(
            "File '${file.fileName}' gagal diekstrak (word/document.xml tidak ditemukan)."
        )
    }

    /** Hasil render halaman 1 PDF: bytes JPEG + jumlah halaman. */
    private class RenderedPdfPage(val jpegBytes: ByteArray, val pageCount: Int)

    /**
     * Render halaman 1 PDF → JPEG (pola MainActivity :4590/:8202:
     * PdfRenderer + RENDER_MODE_FOR_DISPLAY, JPEG q85, file sementara
     * di ctx.cacheDir). Gagal render → RuntimeException jujur.
     */
    private fun renderPdfFirstPage(ctx: Context, file: VisionFile): RenderedPdfPage {
        val tmp = File(ctx.cacheDir, "vision-pdf-" + System.currentTimeMillis() + ".pdf")
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            tmp.writeBytes(file.bytes)
            pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount
            if (pageCount <= 0) throw IllegalStateException("PDF tanpa halaman")
            val page = renderer.openPage(0)
            val bmp = Bitmap.createBitmap(
                page.width, page.height, Bitmap.Config.ARGB_8888
            )
            try {
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                return RenderedPdfPage(bos.toByteArray(), pageCount)
            } finally {
                bmp.recycle()
                page.close()
            }
        } catch (e: Exception) {
            throw RuntimeException(
                "PDF '${file.fileName}' gagal dirender (halaman 1): ${e.message}", e
            )
        } finally {
            try { renderer?.close() } catch (ignored: Exception) { }
            try { pfd?.close() } catch (ignored: Exception) { }
            try { tmp.delete() } catch (ignored: Exception) { }
        }
    }

    /** True bila bytes diawali magic JPEG (FF D8 FF). */
    private fun isJpegBytes(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    /** Kompres satu bitmap → bytes JPEG kualitas q. */
    private fun jpegBytes(bmp: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    /**
     * Siapkan satu gambar → data URI "data:image/jpeg;base64,..."
     * (pola analyzePhotoAndContinue: skala maks 1280, JPEG q80).
     * JPEG yang dimensinya ≤1280 dan ukurannya ≤1 MB dipakai apa adanya
     * (hindari kompres ganda). Bila hasil kompres masih >1 MB → ulang
     * q60 + skala 0.5; bila tetap >1 MB → IllegalArgumentException §4.4.
     */
    private fun prepareVisionImage(file: VisionFile): String {
        val bytes = file.bytes
        // Jalur cepat: sudah JPEG, dimensi ≤1280, ukuran ≤1 MB.
        if (isJpegBytes(bytes) && bytes.size <= VisionFiles.MAX_IMAGE_BYTES_AFTER_COMPRESS) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0 &&
                maxOf(opts.outWidth, opts.outHeight) <= 1280
            ) {
                return "data:image/jpeg;base64," +
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException(
                "📷 ${file.fileName} tidak bisa dianalisis: file gambar tidak terbaca. " +
                    "Coba gambar JPG/PNG yang lebih kecil."
            )
        try {
            val scale = 1280.0 / maxOf(bmp.width, bmp.height)
            val scaled = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt(),
                    (bmp.height * scale).toInt(), true
                )
            } else {
                bmp
            }
            try {
                var jpeg = jpegBytes(scaled, 80)
                if (jpeg.size > VisionFiles.MAX_IMAGE_BYTES_AFTER_COMPRESS) {
                    val half = Bitmap.createScaledBitmap(
                        scaled, maxOf(1, scaled.width / 2),
                        maxOf(1, scaled.height / 2), true
                    )
                    try {
                        jpeg = jpegBytes(half, 60)
                    } finally {
                        if (half != scaled) half.recycle()
                    }
                }
                if (jpeg.size > VisionFiles.MAX_IMAGE_BYTES_AFTER_COMPRESS) {
                    throw IllegalArgumentException(VisionFiles.tooLargeMessage(file))
                }
                return "data:image/jpeg;base64," +
                    Base64.encodeToString(jpeg, Base64.NO_WRAP)
            } finally {
                if (scaled != bmp) scaled.recycle()
            }
        } finally {
            bmp.recycle()
        }
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
