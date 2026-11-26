package com.senzwh.deepharness

// Test JVM murni untuk routing fallback native tools (Fase 5).
// Dijalankan oleh tools/run_tests.sh.
//
// Kontrak F5a: LlmEngineChat.shouldUseNativeTools(toolsSupport: String):
// Boolean — false HANYA bila "text_fallback". Di staging ini fungsi itu
// BELUM ADA (F5a bekerja paralel) — file ini menunggu merge.
// CATATAN: "unsupported" masih true di sini — penandaan ke text_fallback
// dilakukan di lapisan loop (F5b), bukan di fungsi ini.

private var failedFallback = 0

private fun checkFallback(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedFallback++; println("  FAIL $name" + detailSuffix)
    }
}

fun main() {
    println("== ToolFallbackTest ==")
    checkFallback("shouldUseNativeTools text_fallback → false",
        !LlmEngineChat.shouldUseNativeTools("text_fallback"))
    checkFallback("shouldUseNativeTools unknown → true",
        LlmEngineChat.shouldUseNativeTools("unknown"))
    checkFallback("shouldUseNativeTools native → true",
        LlmEngineChat.shouldUseNativeTools("native"))
    checkFallback("shouldUseNativeTools unsupported → true (fallback ditandai di loop F5b, bukan di sini)",
        LlmEngineChat.shouldUseNativeTools("unsupported"))

    println(if (failedFallback == 0) "SEMUA LULUS" else "GAGAL: $failedFallback cek")
    if (failedFallback != 0) kotlin.system.exitProcess(1)
}
