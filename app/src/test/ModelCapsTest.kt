package com.senzwh.deepharness

// Test JVM murni (tanpa Android) untuk deteksi kapabilitas vision (§4.3, T7).
// Dijalankan manual dengan kotlinc — JANGAN edit tools/run_tests.sh.
//
// Menjaga invarian: heuristik HANYA menjawab SUPPORTED atau UNKNOWN
// (tidak pernah UNSUPPORTED); record provider menang atas katalog;
// katalog menang atas heuristik.

private var failedCaps = 0

private fun checkCaps(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedCaps++; println("  FAIL $name" + detailSuffix)
    }
}

private fun providerWith(vision: String): LlmProvider =
    LlmProvider("p1", "P", LlmKind.OPENAI_COMPAT, "https://a.b/v1", "k", "m", vision = vision)

fun main() {
    println("== ModelCapsTest ==")

    // ModelCaps.parseVisionSupport
    checkCaps("parse supported", ModelCaps.parseVisionSupport("supported") == VisionSupport.SUPPORTED)
    checkCaps("parse unsupported", ModelCaps.parseVisionSupport("unsupported") == VisionSupport.UNSUPPORTED)
    checkCaps("parse unknown", ModelCaps.parseVisionSupport("unknown") == VisionSupport.UNKNOWN)
    checkCaps("parse kosong → UNKNOWN", ModelCaps.parseVisionSupport("") == VisionSupport.UNKNOWN)
    checkCaps("parse null → UNKNOWN", ModelCaps.parseVisionSupport(null) == VisionSupport.UNKNOWN)
    checkCaps("parse nilai aneh → UNKNOWN", ModelCaps.parseVisionSupport("maybe") == VisionSupport.UNKNOWN)

    // Heuristik id-model saja (T7)
    checkCaps("heuristik gpt-4o → SUPPORTED",
        ModelCaps.visionFromModelId("gpt-4o") == VisionSupport.SUPPORTED)
    checkCaps("heuristik gpt-4o-mini → SUPPORTED",
        ModelCaps.visionFromModelId("gpt-4o-mini") == VisionSupport.SUPPORTED)
    checkCaps("heuristik qwen-vl → SUPPORTED",
        ModelCaps.visionFromModelId("qwen-vl-max") == VisionSupport.SUPPORTED)
    checkCaps("heuristik claude-3 → SUPPORTED",
        ModelCaps.visionFromModelId("claude-3-5-sonnet") == VisionSupport.SUPPORTED)
    checkCaps("heuristik gemini-2 → SUPPORTED",
        ModelCaps.visionFromModelId("gemini-2.0-flash") == VisionSupport.SUPPORTED)
    checkCaps("heuristik llama-3.2-vision → SUPPORTED",
        ModelCaps.visionFromModelId("llama-3.2-11b-vision-instruct") == VisionSupport.SUPPORTED)
    checkCaps("heuristik id tak dikenal → UNKNOWN (BUKAN UNSUPPORTED)",
        ModelCaps.visionFromModelId("model-tak-dikenal-123") == VisionSupport.UNKNOWN,
        "dapat=" + ModelCaps.visionFromModelId("model-tak-dikenal-123"))
    checkCaps("heuristik id kosong → UNKNOWN",
        ModelCaps.visionFromModelId("") == VisionSupport.UNKNOWN)
    checkCaps("heuristik tidak pernah UNSUPPORTED (sapuan)",
        listOf("a", "gpt-3.5-turbo", "llama3.1", "mistral-7b", "deepseek-chat").none {
            ModelCaps.visionFromModelId(it) == VisionSupport.UNSUPPORTED
        })

    // Sumber 1: record provider eksplisit menang atas segalanya
    val pUnknown = providerWith("unknown")
    val pSupported = providerWith("supported")
    val pUnsupported = providerWith("unsupported")
    checkCaps("OpenAI standar tanpa info → UNKNOWN",
        ModelCaps.supportsVision(pUnknown, "gpt-3.5-turbo") == VisionSupport.UNKNOWN)
    checkCaps("record supported menang (id tak dikenal)",
        ModelCaps.supportsVision(pSupported, "model-acak-9") == VisionSupport.SUPPORTED)
    checkCaps("record unsupported menang (id vision sekalipun)",
        ModelCaps.supportsVision(pUnsupported, "gpt-4o") == VisionSupport.UNSUPPORTED)

    // Sumber 2: katalog hasil fetch
    val catalog = mapOf(
        "openrouter-model-a" to VisionSupport.SUPPORTED, // modality text+image->text
        "openrouter-model-b" to VisionSupport.UNSUPPORTED, // modality text->text eksplisit
        "litellm-model" to VisionSupport.SUPPORTED, // supports_vision true
        "litellm-model-off" to VisionSupport.UNSUPPORTED // supports_vision false
    )
    checkCaps("katalog OpenRouter modality image → SUPPORTED",
        ModelCaps.supportsVision(pUnknown, "openrouter-model-a", catalog) == VisionSupport.SUPPORTED)
    checkCaps("katalog supports_vision true → SUPPORTED",
        ModelCaps.supportsVision(pUnknown, "litellm-model", catalog) == VisionSupport.SUPPORTED)
    checkCaps("katalog supports_vision false → UNSUPPORTED",
        ModelCaps.supportsVision(pUnknown, "litellm-model-off", catalog) == VisionSupport.UNSUPPORTED)
    checkCaps("katalog UNKNOWN → jatuh ke heuristik (SUPPORTED)",
        ModelCaps.supportsVision(
            pUnknown, "gpt-4o",
            mapOf("gpt-4o" to VisionSupport.UNKNOWN)
        ) == VisionSupport.SUPPORTED)
    checkCaps("katalog tak ada → jatuh ke heuristik (UNKNOWN)",
        ModelCaps.supportsVision(pUnknown, "model-tak-dikenal-123", catalog) == VisionSupport.UNKNOWN)
    checkCaps("record provider menang atas katalog",
        ModelCaps.supportsVision(pUnsupported, "openrouter-model-a", catalog) == VisionSupport.UNSUPPORTED)

    // catalogMap dari entri cache store
    val entries = listOf(
        ModelCaps.CachedModel("m1", "supported"),
        ModelCaps.CachedModel("m2", "unsupported"),
        ModelCaps.CachedModel("m3", "tidak-jelas"),
        ModelCaps.CachedModel("", "supported")
    )
    val built = ModelCaps.catalogMap(entries)
    checkCaps("catalogMap supported", built["m1"] == VisionSupport.SUPPORTED)
    checkCaps("catalogMap unsupported", built["m2"] == VisionSupport.UNSUPPORTED)
    checkCaps("catalogMap nilai aneh → UNKNOWN", built["m3"] == VisionSupport.UNKNOWN)
    checkCaps("catalogMap id kosong dilewati", !built.containsKey(""))

    println(if (failedCaps == 0) "SEMUA LULUS" else "GAGAL: $failedCaps cek")
    if (failedCaps != 0) kotlin.system.exitProcess(1)
}
