package com.senzwh.deepharness

// Test JVM murni (tanpa Android) untuk registry provider LLM kustom.
// Dijalankan oleh tools/run_tests.sh — TIDAK ikut masuk APK.
//
// Menjaga invarian: id valid, id "local" tak tergantikan, duplikat
// di-upsert, dan resolusi provider aktif jatuh ke KOSONG (belum ada
// provider aktif, K1 Fase 6) bila provider aktif hilang — bukan ke
// provider bawaan (provider bawaan DeepSeek internal sudah dihapus).

private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("  ok   $name")
        else {
            val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
            failed++; println("  FAIL $name" + detailSuffix)
        }
}

private inline fun checkRejects(name: String, block: () -> Unit) {
    try {
        block()
        check(name, false, "tidak melempar IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
        check(name, true)
    } catch (e: Throwable) {
        check(name, false, "tipe salah: $e")
    }
}

fun main() {
    println("== LlmProvidersTest ==")

    check("tidak ada provider bawaan 'deepseek' (K1)",
        LlmProviders.find("deepseek") == null)
    check("all awal kosong (tanpa provider bawaan)", LlmProviders.all().isEmpty(),
        "all=" + LlmProviders.all().map { it.id })
    check("default aktif kosong (belum ada provider)", LlmProviders.activeId() == "",
        "aktif=" + LlmProviders.activeId())
    check("activeProvider null bila belum ada", LlmProviders.activeProvider() == null)

    val added = LlmProviders.addCustom(
        "openai", "OpenAI", "https://api.openai.com/v1", "sk-test", "gpt-4o-mini", true
    )
    check("addCustom kembalikan provider benar",
        added.id == "openai" && added.baseUrl == "https://api.openai.com/v1" && added.model == "gpt-4o-mini",
        "dapat=$added")
    check("custom masuk all", LlmProviders.all().any { it.id == "openai" })
    check("hasKey true bila key ada", LlmProviders.find("openai")!!.hasKey)
    check("configured true bila lengkap", LlmProviders.find("openai")!!.configured)

    LlmProviders.addCustom("nokey", "TanpaKey", "https://example.invalid/v1", null, "m", true)
    check("hasKey false bila key kosong", !LlmProviders.find("nokey")!!.hasKey)
    check("configured false bila key kosong", !LlmProviders.find("nokey")!!.configured)

    checkRejects("baseUrl kosong ditolak") {
        LlmProviders.addCustom("x1", "X", "", "k", "m", true)
    }
    checkRejects("model kosong ditolak") {
        LlmProviders.addCustom("x2", "X", "https://a.b/v1", "k", "", true)
    }
    checkRejects("label kosong ditolak") {
        LlmProviders.addCustom("x3", "", "https://a.b/v1", "k", "m", true)
    }
    checkRejects("id format tidak valid ditolak") {
        LlmProviders.addCustom("Bad-ID!", "X", "https://a.b/v1", "k", "m", true)
    }
    // K1 (Fase 6): id "deepseek" TIDAK dicadangkan lagi — provider bawaan
    // internal sudah dihapus, jadi id itu bebas dipakai provider kustom.
    val freed = LlmProviders.addCustom("deepseek", "DS Kustom", "https://a.b/v1", "k", "m", true)
    check("id deepseek bebas dipakai provider kustom", freed.id == "deepseek" && !freed.builtIn)
    check("removeCustom deepseek kustom sukses", LlmProviders.removeCustom("deepseek"))
    checkRejects("id reserved local ditolak") {
        LlmProviders.addCustom("local", "X", "https://a.b/v1", "k", "m", true)
    }
    checkRejects("id kosong ditolak") {
        LlmProviders.addCustom("  ", "X", "https://a.b/v1", "k", "m", true)
    }

    LlmProviders.addCustom("openai", "OpenAI v2", "https://api.openai.com/v1", "sk-test-2", "gpt-4o", true)
    check("duplikat di-upsert jadi satu entri",
        LlmProviders.all().count { it.id == "openai" } == 1,
        "jumlah=" + LlmProviders.all().count { it.id == "openai" })
    check("upsert memperbarui field", LlmProviders.find("openai")!!.label == "OpenAI v2",
        "label=" + LlmProviders.find("openai")!!.label)

    check("removeCustom custom sukses", LlmProviders.removeCustom("openai"))
    check("custom hilang sesudah remove", LlmProviders.find("openai") == null)
    check("removeCustom id tak dikenal ditolak", !LlmProviders.removeCustom("tidak-ada"))

    LlmProviders.addCustom("groq", "Groq", "https://api.groq.com/openai/v1", "gsk_1", "llama-3.1-70b", true)
    LlmProviders.setActive("groq")
    check("setActive pindah ke groq", LlmProviders.activeId() == "groq", "aktif=" + LlmProviders.activeId())
    check("activeProvider mengikuti", LlmProviders.activeProvider()?.id == "groq")
    LlmProviders.removeCustom("groq")
    check("aktif kosong setelah provider aktif dihapus",
        LlmProviders.activeId() == "", "aktif=" + LlmProviders.activeId())
    checkRejects("setActive id tak dikenal ditolak") {
        LlmProviders.setActive("tidak-ada")
    }
    LlmProviders.setActive("nokey")

    // chat() — validasi gagal tanpa jaringan (onError dipanggil)
    var err: String? = null
    LlmProviders.chat(null, null, "x", false, { }, { _, _ -> }, { err = it })
    check("chat provider null → error", err?.contains("belum dikonfigurasi", ignoreCase = true) == true, "err=$err")

    err = null
    // Preset deepseek-platform (Models API, §8.2-1) tanpa key → error key.
    LlmProviders.chat(null, LlmProviders.preset("deepseek-platform"), "x", false, { }, { _, _ -> }, { err = it })
    check("chat preset deepseek-platform tanpa key → error", err?.contains("belum ada", ignoreCase = true) == true, "err=$err")

    err = null
    LlmProviders.chat(null, LlmProviders.addCustom("nokey2", "X", "https://a.b/v1", null, "m", true), "x", false, { }, { _, _ -> }, { err = it })
    check("chat tanpa key → error", err?.contains("belum ada", ignoreCase = true) == true, "err=$err")

    // ---- T11 (Fase 0b, §3.1/§0.5/C3) ----
    // "openai" TIDAK dicadangkan + field guard baru tersimpan di registry.
    val openaiAgain = LlmProviders.addCustom("openai", "OpenAI", "https://api.openai.com/v1", "sk-x", "gpt-4o-mini", true,
        dailyCapUnits = 123, maxPromptChars = 120000, thinkingParam = "", toolsSupport = "native")
    check("T11 id openai TIDAK dicadangkan", openaiAgain.id == "openai")
    check("T11 dailyCapUnits tersimpan", LlmProviders.find("openai")!!.dailyCapUnits == 123)
    check("T11 maxPromptChars tersimpan", LlmProviders.find("openai")!!.maxPromptChars == 120000)
    check("T11 toolsSupport tersimpan", LlmProviders.find("openai")!!.toolsSupport == "native")
    LlmProviders.removeCustom("openai")

    // PRESETS (§3.1): 5 preset incl. deepseek-platform (keputusan §8.2(1)).
    check("T11 PRESETS berisi 5 preset", LlmProviders.PRESETS.size == 5,
        "jumlah=" + LlmProviders.PRESETS.size)
    check("T11 preset deepseek-platform ada",
        LlmProviders.preset("deepseek-platform")?.baseUrl == "https://api.deepseek.com/v1")
    check("T11 preset deepseek-platform thinking_enabled",
        LlmProviders.preset("deepseek-platform")?.thinkingParam == "thinking_enabled")
    check("T11 preset deepseek-platform model deepseek-chat",
        LlmProviders.preset("deepseek-platform")?.model == "deepseek-chat")
    check("T11 preset openai model gpt-4o-mini",
        LlmProviders.preset("openai")?.model == "gpt-4o-mini")
    check("T11 preset groq maxPromptChars 60.000",
        LlmProviders.preset("groq")?.maxPromptChars == 60000)
    check("T11 preset tak dikenal → null", LlmProviders.preset("tidak-ada") == null)

    // isLocalBaseUrl + configured tanpa key untuk provider lokal.
    check("T11 localhost = lokal", LlmProviders.isLocalBaseUrl("http://localhost:11434/v1"))
    check("T11 127.0.0.1 = lokal", LlmProviders.isLocalBaseUrl("http://127.0.0.1:11434/v1"))
    check("T11 api.openai.com bukan lokal", !LlmProviders.isLocalBaseUrl("https://api.openai.com/v1"))
    check("T11 ollama tanpa key tetap configured",
        LlmProviders.preset("ollama")!!.configured)

    // removeCustom provider aktif → aktif pindah (C3).
    LlmProviders.addCustom("cap_a", "A", "https://a.b/v1", "k", "m", true)
    LlmProviders.addCustom("cap_b", "B", "https://a.b/v1", "k", "m", true)
    LlmProviders.setActive("cap_a")
    LlmProviders.removeCustom("cap_a")
    check("T11 removeCustom aktif → pindah ke configured lain",
        LlmProviders.activeId() == "cap_b", "aktif=" + LlmProviders.activeId())
    LlmProviders.removeCustom("cap_b")
    check("T11 removeCustom aktif terakhir → aktif kosong",
        LlmProviders.activeId() == "", "aktif=" + LlmProviders.activeId())

    // parseConfigEntry: skema baru terbaca, skema lama kompatibel mundur.
    val parsedNew = LlmProviderStore.parseConfigEntry("pnew",
        """{"id":"pnew","label":"N","baseUrl":"https://a.b/v1","model":"m","enabled":true,"dailyCapUnits":77,"maxPromptChars":30000,"maxOutputTokens":4000,"thinkingParam":"thinking_enabled","toolsSupport":"native","vision":"supported","toolRoleFallback":true}""",
        "kunci")
    check("T11 parse field guard baru",
        parsedNew != null && parsedNew.dailyCapUnits == 77 && parsedNew.maxPromptChars == 30000 &&
            parsedNew.maxOutputTokens == 4000 && parsedNew.thinkingParam == "thinking_enabled" &&
            parsedNew.toolsSupport == "native" && parsedNew.vision == "supported" &&
            parsedNew.toolRoleFallback && parsedNew.apiKey == "kunci",
        "dapat=$parsedNew")
    val parsedOld = LlmProviderStore.parseConfigEntry("pold",
        """{"id":"pold","label":"O","baseUrl":"https://a.b/v1","model":"m","enabled":true}""", null)
    check("T11 parse skema lama → null/default",
        parsedOld != null && parsedOld.dailyCapUnits == null && parsedOld.maxPromptChars == null &&
            parsedOld.maxOutputTokens == null && parsedOld.thinkingParam == "" &&
            parsedOld.toolsSupport == "unknown" && parsedOld.vision == "unknown" &&
            !parsedOld.toolRoleFallback,
        "dapat=$parsedOld")
    check("T11 parse JSON rusak → null",
        LlmProviderStore.parseConfigEntry("rusak", "{bukan json", null) == null)
    check("T11 parse id kosong → null",
        LlmProviderStore.parseConfigEntry("", """{"label":"X"}""", null) == null)

    // parseConfigs: provider rusak dilewati + id-nya dilaporkan.
    val (parsedList, skippedList) = LlmProviderStore.parseConfigs(
        mapOf(
            "cfg_ok" to """{"id":"ok","label":"O","baseUrl":"https://a.b/v1","model":"m"}""",
            "cfg_rusak" to "{bukan json",
            "cfg_kosong" to "",
            "other_key" to """{"id":"no"}"""
        )
    ) { null }
    check("T11 parseConfigs loloskan yang baik",
        parsedList.map { it.id } == listOf("ok"), "dapat=" + parsedList.map { it.id })
    check("T11 parseConfigs laporkan id rusak",
        skippedList.sorted() == listOf("kosong", "rusak"), "dapat=$skippedList")

    // Store dengan FakeContext: save gagal → false; hydrate lewati rusak.
    val throwingCtx = FakeContext().also { it.throwOnPrefs = true }
    val saveTarget = LlmProviders.addCustom("saveme", "S", "https://a.b/v1", "k", "m", true)
    check("T11 save gagal → false", !LlmProviderStore.save(throwingCtx, saveTarget))
    LlmProviders.removeCustom("saveme")

    val storeCtx = FakeContext()
    val toSave = LlmProviders.addCustom("store1", "S1", "https://a.b/v1", null, "m", true,
        dailyCapUnits = 42, maxPromptChars = 30000)
    check("T11 save sukses → true", LlmProviderStore.save(storeCtx, toSave))
    val loaded = LlmProviderStore.loadCustom(storeCtx)
    check("T11 loadCustom membaca yang disimpan",
        loaded.any { it.id == "store1" && it.dailyCapUnits == 42 && it.maxPromptChars == 30000 },
        "dapat=$loaded")
    LlmProviders.removeCustom("store1")

    // Cache model: saveModels/loadModels round-trip + delete membersihkannya.
    val models = listOf(
        ModelCaps.CachedModel("gpt-4o-mini", "supported"),
        ModelCaps.CachedModel("llama3.1", "unknown")
    )
    check("T11 saveModels → true", LlmProviderStore.saveModels(storeCtx, "store1", models, 123456789L))
    val loadedModels = LlmProviderStore.loadModels(storeCtx, "store1")
    check("T11 loadModels round-trip",
        loadedModels.map { it.id } == listOf("gpt-4o-mini", "llama3.1") &&
            loadedModels[0].vision == "supported",
        "dapat=$loadedModels")
    check("T11 loadModelsFetchedAt",
        LlmProviderStore.loadModelsFetchedAt(storeCtx, "store1") == 123456789L)
    check("T11 loadModels id tak dikenal → kosong",
        LlmProviderStore.loadModels(storeCtx, "tidak-ada").isEmpty())

    // hydrate: provider rusak dilewati + dilaporkan; yang baik dimuat.
    val hydCtx = FakeContext()
    hydCtx.getSharedPreferences("llm_provider_cfg", 0).edit()
        .putString("cfg_ok1", """{"id":"ok1","label":"OK","baseUrl":"https://a.b/v1","model":"m","enabled":true}""")
        .putString("cfg_rusak", "{bukan json")
        .apply()
    val skippedHyd = LlmProviderStore.hydrate(hydCtx)
    check("T11 hydrate laporkan id rusak",
        skippedHyd.contains("rusak"), "dapat=$skippedHyd")
    check("T11 hydrate memuat provider baik", LlmProviders.find("ok1") != null)
    LlmProviders.removeCustom("ok1")

    // delete: hapus cfg + cache model.
    LlmProviderStore.delete(storeCtx, "store1")
    check("T11 delete hapus cfg",
        LlmProviderStore.loadCustom(storeCtx).none { it.id == "store1" })
    check("T11 delete hapus cache model",
        LlmProviderStore.loadModels(storeCtx, "store1").isEmpty())

    println(if (failed == 0) "SEMUA LULUS" else "GAGAL: $failed cek")
    if (failed != 0) kotlin.system.exitProcess(1)
}
