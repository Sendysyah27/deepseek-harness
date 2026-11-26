package com.senzwh.deepharness

/**
 * §4.3 (Fase 0b) — Deteksi kapabilitas vision per (provider, model).
 *
 * JVM-murni (tanpa Context/Android) supaya bisa diuji di run_tests.sh.
 *
 * Tiga sumber, urutan prioritas — pertama yang memberi jawaban menang:
 *  1. Record provider eksplisit — field LlmProvider.vision
 *     ("supported" | "unknown" | "unsupported").
 *  2. Katalog model hasil fetch /v1/models — peta id model → VisionSupport
 *     (dibangun pemanggil dari cache LlmProviderStore.loadModels).
 *  3. Heuristik id-model (fallback terakhir) — daftar eksplisit
 *     VISION_MODEL_IDS + aturan substring.
 *
 * Aturan mengikat: heuristik HANYA boleh menjawab SUPPORTED atau UNKNOWN —
 * TIDAK PERNAH UNSUPPORTED (id tak dikenal ≠ tidak support).
 *
 * CATATAN: enum VisionSupport DIDEFINISIKAN di OpenAiCompatClient.kt
 * (milik F0a, §5.10.1 — ModelInfo.vision memakai tipe itu); file ini
 * TIDAK mendefinisikan ulang — hanya memakai + mem-parse nilainya.
 */
object ModelCaps {

    /** Parse nilai vision mentah ("supported"|"unknown"|"unsupported") — lain → UNKNOWN. */
    fun parseVisionSupport(raw: String?): VisionSupport = when (raw?.trim()?.lowercase()) {
        "supported" -> VisionSupport.SUPPORTED
        "unsupported" -> VisionSupport.UNSUPPORTED
        else -> VisionSupport.UNKNOWN
    }

    /** Satu entri cache daftar model (id + vision mentah) — dipakai store + picker. */
    data class CachedModel(val id: String, val vision: String)

    /** Model yang diketahui mendukung vision (id eksplisit, lowercase). */
    val VISION_MODEL_IDS: Set<String> = setOf(
        "gpt-4o", "gpt-4o-mini", "gpt-4-turbo",
        "claude-3-5-sonnet", "claude-3-opus", "claude-3-sonnet", "claude-3-haiku",
        "gemini-1.5-pro", "gemini-1.5-flash", "gemini-2.0-flash",
        "qwen-vl-max", "qwen-vl-plus",
        "llama-3.2-11b-vision-instruct", "llama-3.2-90b-vision-instruct"
    )

    /** Substring id-model yang menandakan vision (lowercase, §4.3). */
    val VISION_ID_SUBSTRINGS: List<String> = listOf(
        "4o", "vision", "-vl", "multimodal",
        "gemini-1.5", "gemini-2", "claude-3", "llama-3.2-vision", "qwen-vl"
    )

    /**
     * Heuristik id-model saja. HANYA mengembalikan SUPPORTED atau UNKNOWN —
     * tidak pernah UNSUPPORTED.
     */
    fun visionFromModelId(model: String): VisionSupport {
        val id = model.trim().lowercase()
        if (id.isBlank()) return VisionSupport.UNKNOWN
        if (id in VISION_MODEL_IDS) return VisionSupport.SUPPORTED
        if (VISION_ID_SUBSTRINGS.any { id.contains(it) }) return VisionSupport.SUPPORTED
        return VisionSupport.UNKNOWN
    }

    /**
     * Keputusan akhir per (provider, model).
     *
     * @param catalogVision peta id model → VisionSupport dari katalog hasil
     *   fetch /v1/models (boleh null bila belum pernah fetch). Pencocokan
     *   id eksak dulu, lalu lowercase.
     */
    fun supportsVision(
        provider: LlmProvider,
        model: String,
        catalogVision: Map<String, VisionSupport>? = null
    ): VisionSupport {
        // 1. Record provider eksplisit.
        when (ModelCaps.parseVisionSupport(provider.vision)) {
            VisionSupport.SUPPORTED -> return VisionSupport.SUPPORTED
            VisionSupport.UNSUPPORTED -> return VisionSupport.UNSUPPORTED
            VisionSupport.UNKNOWN -> { /* lanjut ke sumber berikut */ }
        }
        // 2. Katalog hasil fetch.
        if (catalogVision != null) {
            val hit = catalogVision[model] ?: catalogVision[model.lowercase()]
            if (hit != null && hit != VisionSupport.UNKNOWN) return hit
        }
        // 3. Heuristik id-model — hanya SUPPORTED atau UNKNOWN.
        return visionFromModelId(model)
    }

    /**
     * Bangun peta katalog dari entri cache store (id → vision).
     * Entri tanpa id dilewati; nilai vision yang tidak dikenal → UNKNOWN.
     */
    fun catalogMap(entries: List<CachedModel>): Map<String, VisionSupport> {
        val out = LinkedHashMap<String, VisionSupport>()
        for (e in entries) {
            if (e.id.isBlank()) continue
            out.putIfAbsent(e.id, ModelCaps.parseVisionSupport(e.vision))
        }
        return out
    }
}
