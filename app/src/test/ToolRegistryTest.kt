package com.senzwh.deepharness

import harness.core.RustCore

// Test JVM murni (tanpa Android) untuk katalog tool.
// Dijalankan oleh tools/run_tests.sh — TIDAK ikut masuk APK
// (build.py hanya mengompilasi app/src/main/java).
//
// Tujuan: menangkap drift katalog (nama ganda, ArgSpec rusak, tool hilang
// dari catalogSection) sebelum sampai ke prompt model.

private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else { failed++; println("  FAIL $name" + (if (detail.isNotBlank()) " — $detail" else "")) }
}

fun main() {
    println("== ToolRegistryTest ==")
    val all = ToolRegistry.ALL
    check("katalog tidak kosong", all.isNotEmpty(), "size=" + all.size)

    val names = all.map { it.name }
    check("nama unik", names.size == names.toSet().size,
        "duplikat=" + names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys)
    check("nama tidak kosong", names.all { it.isNotBlank() })
    val bad = names.filter { !Regex("[a-z0-9_]+").matches(it) }
    check("nama sesuai [a-z0-9_]+", bad.isEmpty(), "buruk=" + bad)
    check("deskripsi tidak kosong", all.all { it.desc.isNotBlank() })
    check("section tidak kosong", all.all { it.section.isNotBlank() })
    check("argsExample tidak kosong", all.all { it.argsExample.isNotBlank() })

    val badArg = all.flatMap { d -> d.args.map { d.name to it } }
        .filter { (_, a) -> a.name.isBlank() || a.type.isBlank() || (a.enumValues.isNotEmpty() && a.type != "string") }
    check("ArgSpec valid (nama/tipe; enum hanya string)", badArg.isEmpty(),
        "buruk=" + badArg.take(5))

    val kinds = all.groupingBy { it.kind }.eachCount()
    check("ada tool READ", (kinds[ToolRegistry.Kind.READ] ?: 0) > 0)
    check("ada tool WRITE", (kinds[ToolRegistry.Kind.WRITE] ?: 0) > 0)

    val cat = try { ToolRegistry.catalogSection() } catch (t: Throwable) { "" }
    val missing = names.filter { !cat.contains(it) }
    check("catalogSection memuat semua nama", missing.isEmpty(), "hilang=" + missing.take(5))

    // RustCore di JVM (tanpa .so) harus aman: init menangkap Throwable.
    try { RustCore.available; check("RustCore aman di JVM", true) }
    catch (t: Throwable) { check("RustCore aman di JVM", false, t.toString()) }

    // ---------------------------------------------------------- T5: toolsJson() native (Fase 5)
    // Kontrak F5a: ToolRegistry.toolsJson(): JSONArray (format OpenAI
    // {"type":"function","function":{name, description, parameters}}) +
    // ToolRegistry.NATIVE_TOOLS_EXCLUDED berisi "list_tools",
    // "llm_provider_set", "llm_provider_remove". Di staging ini kedua nama
    // itu BELUM ADA (F5a bekerja paralel) — blok ini menunggu merge.
    run {
        val toolsJson = ToolRegistry.toolsJson()
        val excluded = ToolRegistry.NATIVE_TOOLS_EXCLUDED
        check("T5 NATIVE_TOOLS_EXCLUDED berisi list_tools + llm_provider_set + llm_provider_remove",
            excluded.containsAll(listOf("list_tools", "llm_provider_set", "llm_provider_remove")),
            "dapat=$excluded")
        val namesJson = (0 until toolsJson.length()).map {
            toolsJson.optJSONObject(it)?.optJSONObject("function")?.optString("name", "") ?: ""
        }
        check("T5 llm_provider_set / llm_provider_remove / list_tools TIDAK ada di toolsJson",
            namesJson.none { it == "llm_provider_set" || it == "llm_provider_remove" || it == "list_tools" },
            "dapat=$namesJson")
        val expectedNames = all.filter { it.name !in excluded }.map { it.name }.toSet()
        check("T5 toolsJson memuat semua tool lain (lengkap, tanpa duplikat)",
            namesJson.toSet() == expectedNames && namesJson.size == namesJson.toSet().size,
            "json=${namesJson.size} harap=${expectedNames.size}")
        val nameRe = Regex("[a-zA-Z0-9_-]{1,64}")
        check("T5 nama tool di toolsJson memenuhi [a-zA-Z0-9_-]{1,64}",
            namesJson.all { nameRe.matches(it) },
            "buruk=" + namesJson.filter { !nameRe.matches(it) })
        var schemaOk = true
        var schemaDetail = ""
        for (i in 0 until toolsJson.length()) {
            val tool = toolsJson.optJSONObject(i)
            val fn = tool?.optJSONObject("function")
            val def = all.firstOrNull { it.name == fn?.optString("name") }
            if (tool?.optString("type") != "function" || fn == null || def == null) {
                schemaOk = false; schemaDetail = "indeks $i: $tool"; break
            }
            val params = fn.optJSONObject("parameters")
            val requiredJson = params?.optJSONArray("required")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList()
            val requiredSpec = def.args.filter { it.required }.map { it.name }
            if (requiredJson.toSet() != requiredSpec.toSet()) {
                schemaOk = false
                schemaDetail = "tool ${def.name}: required json=$requiredJson spec=$requiredSpec"
                break
            }
        }
        check("T5 tiap tool type:function + parameters.required cocok ArgSpec(required=true)",
            schemaOk, schemaDetail)
    }

    println(if (failed == 0) "SEMUA LULUS (" + all.size + " tool)" else "GAGAL: $failed cek")
    if (failed != 0) kotlin.system.exitProcess(1)
}
