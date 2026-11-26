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

    println(if (failed == 0) "SEMUA LULUS (" + all.size + " tool)" else "GAGAL: $failed cek")
    if (failed != 0) kotlin.system.exitProcess(1)
}
