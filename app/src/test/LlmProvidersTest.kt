package com.senzwh.deepharness

// Test JVM murni (tanpa Android) untuk registry provider LLM kustom.
// Dijalankan oleh tools/run_tests.sh — TIDAK ikut masuk APK.
//
// Menjaga invarian: id valid, id bawaan tak tergantikan, duplikat di-upsert,
// dan resolusi provider aktif jatuh balik ke deepseek bila provider aktif
// hilang.

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

    check("deepseek bawaan ada", LlmProviders.find(LlmProviders.ID_DEEPSEEK) != null)
    check("deepseek builtIn", LlmProviders.find(LlmProviders.ID_DEEPSEEK)!!.builtIn)
    check("default aktif = deepseek", LlmProviders.activeId() == LlmProviders.ID_DEEPSEEK,
        "aktif=" + LlmProviders.activeId())
    check("activeProvider = deepseek", LlmProviders.activeProvider().id == LlmProviders.ID_DEEPSEEK)
    check("all awal hanya deepseek", LlmProviders.all().map { it.id } == listOf(LlmProviders.ID_DEEPSEEK),
        "all=" + LlmProviders.all().map { it.id })

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
    checkRejects("id reserved deepseek ditolak") {
        LlmProviders.addCustom(LlmProviders.ID_DEEPSEEK, "X", "https://a.b/v1", "k", "m", true)
    }
    checkRejects("id reserved local ditolak") {
        LlmProviders.addCustom(LlmProviders.ID_LOCAL, "X", "https://a.b/v1", "k", "m", true)
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
    check("removeCustom bawaan ditolak", !LlmProviders.removeCustom(LlmProviders.ID_DEEPSEEK))
    check("removeCustom id tak dikenal ditolak", !LlmProviders.removeCustom("tidak-ada"))

    LlmProviders.addCustom("groq", "Groq", "https://api.groq.com/openai/v1", "gsk_1", "llama-3.1-70b", true)
    LlmProviders.setActive("groq")
    check("setActive pindah ke groq", LlmProviders.activeId() == "groq", "aktif=" + LlmProviders.activeId())
    check("activeProvider mengikuti", LlmProviders.activeProvider().id == "groq")
    LlmProviders.removeCustom("groq")
    check("aktif jatuh ke deepseek setelah provider aktif dihapus",
        LlmProviders.activeId() == LlmProviders.ID_DEEPSEEK, "aktif=" + LlmProviders.activeId())
    checkRejects("setActive id tak dikenal ditolak") {
        LlmProviders.setActive("tidak-ada")
    }
    LlmProviders.setActive(LlmProviders.ID_DEEPSEEK)

    // chat() — validasi gagal tanpa jaringan (onError dipanggil)
    var err: String? = null
    LlmProviders.chat(null, null, "x", false, { }, { _, _ -> }, { err = it })
    check("chat provider null → error", err?.contains("belum dikonfigurasi", ignoreCase = true) == true, "err=$err")

    err = null
    LlmProviders.chat(null, LlmProviders.find(LlmProviders.ID_DEEPSEEK), "x", false, { }, { _, _ -> }, { err = it })
    check("chat provider deepseek → error", err?.contains("bukan OpenAI-compatible", ignoreCase = true) == true, "err=$err")

    err = null
    LlmProviders.chat(null, LlmProviders.addCustom("nokey2", "X", "https://a.b/v1", null, "m", true), "x", false, { }, { _, _ -> }, { err = it })
    check("chat tanpa key → error", err?.contains("belum ada", ignoreCase = true) == true, "err=$err")

    println(if (failed == 0) "SEMUA LULUS" else "GAGAL: $failed cek")
    if (failed != 0) kotlin.system.exitProcess(1)
}
