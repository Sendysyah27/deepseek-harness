package com.senzwh.deepharness

// Test-double minimal HANYA untuk kompilasi JVM AgentPrompt.kt di
// tools/run_tests.sh (vc333). AgentPrompt.buildSystemPrompt memanggil
// AgentBrain.promptBlocks/blockLengths, WorldState.line, dan
// Connectors.catalogLine — ketiganya di produksi memakai API Android
// (Intent/BatteryManager/PrivServer) yang tidak ada di stub JVM, jadi
// di sini diganti pengembalian kosong. VerificationRemovedTest TIDAK
// memanggil buildSystemPrompt; double ini hanya supaya AgentPrompt bisa
// dikompilasi dan fungsi murninya (stripRawToolMarkupForDisplay,
// stripSafetyTags, deltaExtractPromptVc330, contentSnippet) bisa dites.
// JANGAN pakai file ini di luar run_tests.sh.

object AgentBrain {
    fun promptBlocks(ctx: android.content.Context): String = ""
    fun blockLengths(ctx: android.content.Context): Triple<Int, Int, Int> = Triple(0, 0, 0)
}

object WorldState {
    fun line(ctx: android.content.Context, slimVc240: Boolean = false, dropGoalTopVc241: Boolean = false): String = ""
}

object Connectors {
    fun catalogLine(ctx: android.content.Context): String = ""
}
