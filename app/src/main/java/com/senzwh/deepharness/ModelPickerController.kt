package com.senzwh.deepharness

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * §5.10.1–2 (Fase 0b) — SATU komponen bersama auto-fetch daftar model.
 *
 * Dipakai layar [3] first-run (§5.6) DAN dialog 73 tambah/edit (§5.9).
 * TANPA kode UI: hanya orkestrasi (debounce, single-flight, cache) +
 * callback onStateChanged(ModelPickerState). UI hanya me-render state.
 *
 * Perilaku:
 *  - Debounce 600 ms saat mengetik base URL; fetch segera saat focus loss,
 *    saat layar dibuka, dan saat tombol ⟳ manual ditekan.
 *  - Single-flight: hanya satu fetch berjalan; fetch baru membatalkan
 *    (mengabaikan hasil) fetch lama — hasil terlambat DIBUANG bila
 *    URL-nya sudah berubah.
 *  - Cache via LlmProviderStore (models_<id>): tampilkan cache dulu
 *    sambil fetch baru jalan — picker tidak pernah kosong bila pernah
 *    sukses sekali.
 *  - Bila fetch sebelumnya gagal auth dan user mulai mengisi key → fetch
 *    ulang otomatis (debounce 600 ms) DENGAN key.
 *
 * Callback dipanggil dari thread worker controller (BUKAN main thread) —
 * UI WAJIB mem-post ke main thread sebelum menyentuh View.
 *
 * CATATAN ASUMSI (koordinasi F0a — OpenAiCompatClient.kt milik F0a):
 * OpenAiCompatClient.listModels(baseUrl, apiKey): ModelsResult dengan
 * ModelsResult.Ok(models, fetchedAtMs) / Err(kind, message), dan
 * ModelsErrorKind.AUTH_NEEDED untuk 401/403 tanpa key. Bila F0a menamai
 * kind itu berbeda, satu baris `when` di applyResult() yang disesuaikan.
 */
enum class ModelPickerKind { IDLE, LOADING, LOADED, ERROR, EMPTY, MANUAL, AUTH_NEEDED }

data class ModelPickerState(
    val kind: ModelPickerKind,
    val models: List<ModelInfo> = emptyList(),
    val message: String? = null,
    val selectedModel: String? = null,
    val fetchedAtMs: Long = 0L,
    val totalCount: Int = 0,
    val fromCache: Boolean = false
)

class ModelPickerController(
    private val ctx: Context,
    private val providerId: String,
    private val onStateChanged: (ModelPickerState) -> Unit,
    private val listModelsFn: (baseUrl: String, apiKey: String?) -> ModelsResult =
        { url, key -> OpenAiCompatClient.listModels(url, key) }
) {

    companion object {
        const val DEBOUNCE_MS = 600L

        /**
         * Validasi base URL (murni): harus http(s):// + host (mengandung
         * "." atau "localhost"), tanpa spasi. URL tidak valid → jangan fetch.
         */
        fun isValidBaseUrl(url: String): Boolean {
            val u = url.trim()
            if (u.contains(" ")) return false
            if (!u.startsWith("http://") && !u.startsWith("https://")) return false
            val host = u.substringAfter("://").substringBefore("/").substringBefore(":")
            if (host.isBlank()) return false
            return host.contains(".") || host.equals("localhost", ignoreCase = true)
        }
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private val generation = AtomicLong(0)

    private var currentBaseUrl: String = ""
    private var currentApiKey: String? = null
    private var lastFetchedOkUrl: String? = null
    private var lastAuthFailed: Boolean = false
    private var debounceFuture: ScheduledFuture<*>? = null
    private var lastState: ModelPickerState =
        ModelPickerState(ModelPickerKind.IDLE, message = "Isi base URL dulu — daftar model akan muncul otomatis.")
    private var closed: Boolean = false

    private fun emit(state: ModelPickerState) {
        synchronized(lock) { lastState = state }
        try { onStateChanged(state) } catch (e: Exception) { /* callback UI tidak boleh mematikan controller */ }
    }

    fun currentState(): ModelPickerState = synchronized(lock) { lastState }

    /** Layar dibuka: tampilkan cache dulu, lalu fetch otomatis sekali bila URL valid. */
    fun onOpened(baseUrl: String, apiKey: String?) {
        synchronized(lock) {
            currentBaseUrl = baseUrl.trim()
            currentApiKey = apiKey?.ifBlank { null }
        }
        showCacheFirst()
        if (isValidBaseUrl(currentBaseUrl)) fetchNow()
        else emit(ModelPickerState(ModelPickerKind.IDLE, message = "Isi base URL dulu — daftar model akan muncul otomatis."))
    }

    /** Mengetik di field base URL → debounce 600 ms, lalu fetch bila valid. */
    fun onBaseUrlChanged(url: String) {
        synchronized(lock) {
            currentBaseUrl = url.trim()
            debounceFuture?.cancel(false)
            debounceFuture = null
            if (!isValidBaseUrl(currentBaseUrl)) {
                emit(ModelPickerState(ModelPickerKind.IDLE, message = "Isi base URL dulu — daftar model akan muncul otomatis."))
                return
            }
        }
        synchronized(lock) {
            if (closed) return
            debounceFuture = executor.schedule({ fetchNow() }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
        // F1: pertahankan pilihan lama selama LOADING — bila tidak, applyResult()
        // membaca prevSelected = null dan cabang retensi/catatan "model hilang" mati.
        emit(ModelPickerState(ModelPickerKind.LOADING, models = lastState.models, selectedModel = lastState.selectedModel, message = "Memuat daftar model…"))
    }

    /** Field base URL kehilangan fokus → fetch segera bila belum pernah sukses untuk URL ini. */
    fun onBaseUrlFocusLost(url: String) {
        val u = url.trim()
        synchronized(lock) { currentBaseUrl = u }
        if (!isValidBaseUrl(u)) return
        if (u == lastFetchedOkUrl) return
        fetchNow()
    }

    /**
     * API key berubah: bila fetch sebelumnya gagal auth (401/403 tanpa key)
     * dan key mulai diisi → fetch ulang otomatis (debounce 600 ms) DENGAN key.
     */
    fun onApiKeyChanged(key: String) {
        val k = key.ifBlank { null }
        val shouldRefetch = synchronized(lock) {
            currentApiKey = k
            lastAuthFailed && !k.isNullOrBlank()
        }
        if (!shouldRefetch) return
        synchronized(lock) {
            if (closed) return
            debounceFuture?.cancel(false)
            debounceFuture = executor.schedule({ fetchNow() }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Tombol ⟳ manual — fetch segera (bypass debounce). */
    fun refresh() = fetchNow()

    /** Tombol [Coba lagi] — fetch segera (bypass debounce). */
    fun retry() = fetchNow()

    /** Pilih mode manual: field teks model tampil, picker tetap dengan pesan mode manual. */
    fun useManualModel(modelId: String) {
        synchronized(lock) {
            debounceFuture?.cancel(false)
            debounceFuture = null
            generation.incrementAndGet()
        }
        val prev = lastState
        emit(
            ModelPickerState(
                ModelPickerKind.MANUAL,
                models = prev.models,
                message = "Mode manual — daftar tidak dimuat.",
                selectedModel = modelId.trim().ifBlank { null },
                fetchedAtMs = prev.fetchedAtMs,
                totalCount = prev.totalCount
            )
        )
    }

    /** Pilih model dari daftar (dipertahankan bila masih ada sesudah fetch berikut). */
    fun selectModel(modelId: String) {
        val prev = lastState
        emit(prev.copy(selectedModel = modelId))
    }

    fun close() {
        synchronized(lock) {
            closed = true
            debounceFuture?.cancel(false)
            debounceFuture = null
            generation.incrementAndGet()
        }
        executor.shutdownNow()
    }

    private fun showCacheFirst() {
        val cached: List<ModelInfo> = try {
            LlmProviderStore.loadModels(ctx, providerId).map {
                ModelInfo(it.id, ModelCaps.parseVisionSupport(it.vision))
            }
        } catch (e: Exception) {
            emptyList()
        }
        if (cached.isEmpty()) return
        val fetchedAt = try { LlmProviderStore.loadModelsFetchedAt(ctx, providerId) } catch (e: Exception) { 0L }
        val prevSelected = lastState.selectedModel
        val selected = if (prevSelected != null && cached.any { it.id == prevSelected }) prevSelected
            else cached.firstOrNull()?.id
        emit(
            ModelPickerState(
                ModelPickerKind.LOADED,
                models = cached,
                message = "Daftar dari cache • ${cached.size} model",
                selectedModel = selected,
                fetchedAtMs = fetchedAt,
                totalCount = cached.size,
                fromCache = true
            )
        )
    }

    private fun fetchNow() {
        val url: String
        val key: String?
        val gen: Long
        synchronized(lock) {
            if (closed) return
            debounceFuture?.cancel(false)
            debounceFuture = null
            url = currentBaseUrl
            key = currentApiKey
            gen = generation.incrementAndGet()
        }
        if (!isValidBaseUrl(url)) return
        // F1: pertahankan pilihan lama selama LOADING (lihat onBaseUrlChanged).
        emit(ModelPickerState(ModelPickerKind.LOADING, models = lastState.models, selectedModel = lastState.selectedModel, message = "Memuat daftar model…"))
        executor.execute {
            val result: ModelsResult = try {
                listModelsFn(url, key)
            } catch (e: Exception) {
                ModelsResult.Err(ModelsErrorKind.UNKNOWN, "Gagal memuat daftar model: ${e.message ?: e.toString()}")
            }
            val stale = synchronized(lock) {
                // Single-flight: hasil terlambat DIBUANG bila URL sudah
                // berubah / fetch lebih baru sudah dimulai.
                gen != generation.get() || url != currentBaseUrl || closed
            }
            if (stale) return@execute
            applyResult(url, result)
        }
    }

    private fun applyResult(url: String, result: ModelsResult) {
        when (result) {
            is ModelsResult.Ok -> {
                synchronized(lock) { lastAuthFailed = false }
                if (result.models.isEmpty()) {
                    emit(
                        ModelPickerState(
                            ModelPickerKind.EMPTY,
                            message = "Provider menjawab tapi tidak mengembalikan model apa pun. [Isi model manual] [Coba lagi]"
                        )
                    )
                    return
                }
                try {
                    LlmProviderStore.saveModels(
                        ctx, providerId,
                        result.models.map { ModelCaps.CachedModel(it.id, it.vision.name.lowercase()) },
                        result.fetchedAtMs
                    )
                } catch (e: Exception) { /* cache gagal tidak boleh menggagalkan picker */ }
                synchronized(lock) { lastFetchedOkUrl = url }
                val prevSelected = lastState.selectedModel
                val selected = if (prevSelected != null && result.models.any { it.id == prevSelected }) prevSelected
                    else result.models.firstOrNull()?.id
                val note = if (prevSelected != null && result.models.none { it.id == prevSelected }) {
                    "Model '$prevSelected' tidak ada di daftar ini — dipilih '${selected ?: ""}'."
                } else if (result.totalCount > result.models.size) {
                    "Menampilkan ${result.models.size} dari ${result.totalCount} model — ketik untuk mencari."
                } else null
                emit(
                    ModelPickerState(
                        ModelPickerKind.LOADED,
                        models = result.models,
                        message = note ?: "Diperbarui • ${result.models.size} model • [⟳ Muat ulang]",
                        selectedModel = selected,
                        fetchedAtMs = result.fetchedAtMs,
                        totalCount = result.totalCount,
                        fromCache = false
                    )
                )
            }
            is ModelsResult.Err -> {
                if (result.kind == ModelsErrorKind.AUTH_NEEDED) {
                    synchronized(lock) { lastAuthFailed = true }
                    emit(
                        ModelPickerState(
                            ModelPickerKind.AUTH_NEEDED,
                            message = "Provider ini butuh API key untuk melihat daftar model — isi API key di bawah."
                        )
                    )
                } else {
                    synchronized(lock) { lastAuthFailed = false }
                    emit(
                        ModelPickerState(
                            ModelPickerKind.ERROR,
                            message = result.message
                        )
                    )
                }
            }
        }
    }
}
