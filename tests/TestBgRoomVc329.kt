// TestBgRoomVc329 — P15 (slice 12, vc329): paritas otak latar/room -> Rust.
//
// Mode: TestBgRoomVc329Kt <rust|kotlin> <treeDir> <coreDir>
//   rust   = jalur Rust ASLI via .so host (-Djava.library.path=.../target/debug)
//   kotlin = fallback *Kotlin, tanpa .so (RustCore.available=false)
//   treeDir = direktori .kt terpadu (integrated / app/.../deepharness)
//   coreDir = harness-core (untuk baca jni_glue.rs + Cargo.toml)
//
// Yang diuji (diferensial: ekspektasi SAMA di kedua mode — hasil Rust
// dan hasil Kotlin lama harus identik):
//   (a) BgBrain: cutAtMessageBoundary, trimTranscriptVc263, jam hening,
//       rumus baterai, gerbang submit (seam), lanjutan delta, pesan
//       cap-jujur, tabel rute tool, watchdog per-kunci, antrean proposal
//       fakta (seam), judul+teks deliver.
//   (b) GroupChat: persona/aksen, parseMentions, selectQueue, resolveSlot,
//       cap-jujur grup, chair-hop, saring memori, lanjutan delta vc321,
//       buildPrompt penuh (seam, katalog lewat selectCatalog + backstop
//       30k tetap Kotlin), keputusan runGroupTool, jam hening grup,
//       gerbang ronde latar, render notif 3 baris, parse plan, simpan
//       ronde terakhir sebagai plan (seam).
//   (c) DsAgent: parse hasil eval, template JS (byte-identik seam vs
//       literal Kotlin: probe/page_text/last_state/attach/send),
//       jsonQuote org.json, satu langkah pollAnswer (seam).
//   (d) OcCoord: ID notif hasil (wrap int), RULE_DEFAULTS, sanitasi
//       aturan + ruleInt, rotasi sesi, spin-watch (kelas asli +
//       round-trip state JSON), anggaran langkah, spinningPattern,
//       looksFailed, status jujur, pindaian pesan, badan akhir await,
//       teks asisten inkremental + gabung hasil, parse event izin SSE,
//       keputusan izin, basi izin, teks hasil tugas, intip sesi, pohon
//       serve dua fase, cache probe, teks status, rumus baterai,
//       jam hening.
//   (e) SUMBER: *Kotlin identik merge/ vc328 (dinormalisasi panggilan
//       internal `*Kotlin(` -> `(`; fun yang diekstrak dari blok inline
//       dibandingkan per-baris), perilaku vc321/vc322/vc327/vc328 utuh.
//   (f) STRUKTUR: RUST_CORE_VERSION 10 + 33 simbol JNI + 4 JNI baru +
//       BgRoomSeam (4 objek/saklar), TANPA dependensi Cargo baru, TANPA
//       sentuhan Flavor/MemoryDb/MainActivity/SlotClient/TurnMetrics,
//       WebView/notifikasi/dialog tetap Kotlin.
//
// CATATAN JUJUR:
//  - JSON yang di-serialisasi ulang org.json dibandingkan SEMANTIK
//    (urutan key org.json berbasis hash, bukan insersi — dibuktikan
//    probe JVM /tmp/probe329: {"k","v"} -> {"v":"y","k":"x"}).
//    Pengecualian: `queueFactProposal` + `saveLastRoundPlan` + objek
//    `parseEvalResult` — ketiganya dibandingkan semantik di P15.
//  - `take(n)` Kotlin yang menyimpan lone surrogate tidak bisa
//    diwakili Rust String (utf16_take membuangnya) — P15 memakai teks
//    normal untuk kasus itu.
//  - Jaringan DeepSeek/serve OpenCode asli TIDAK diuji di JVM (butuh
//    WebView hidup + serve + token). Yang dibuktikan P15: seluruh
//    logika PARSE + hitung + keputusan + perakitan string. End-to-end
//    menjadi TES perangkat (TES-VC329).

package com.senzwh.deepharness

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Calendar

private var total = 0
private var lulus = 0
private var gagal = 0

private fun cek(nama: String, ok: Boolean) {
    total++
    if (ok) {
        lulus++
        println("LULUS $nama")
    } else {
        gagal++
        println("GAGAL $nama")
    }
}

private fun src(treeDir: String, name: String): String {
    val f = java.io.File(treeDir, name)
    return if (f.exists()) f.readText() else ""
}

private fun srcCore(coreDir: String, rel: String): String {
    val f = java.io.File(coreDir, rel)
    return if (f.exists()) f.readText() else ""
}

/** Ambil body fungsi (dari `{` pertama sesudah [sig] sampai `}` penutup),
 *  dengan scanner yang melewati string "..." (escape-aware), literal
 *  char 'x' (escape-aware), dan komentar `//`. "" = signature tidak
 *  ketemu / tak-berakhir. (Disalin dari P12/P14 — pola yang sama.) */
private fun funBody(srcText: String, sig: String): String {
    val i = srcText.indexOf(sig)
    if (i < 0) return ""
    val open = srcText.indexOf('{', i)
    if (open < 0) return ""
    var depth = 0
    var j = open
    var inStr = false
    var inChar = false
    var inLineComment = false
    while (j < srcText.length) {
        val c = srcText[j]
        if (inLineComment) {
            if (c == '\n') inLineComment = false
            j++
            continue
        }
        if (inStr) {
            if (c == '\\') { j += 2; continue }
            if (c == '"') inStr = false
            j++
            continue
        }
        if (inChar) {
            if (c == '\\') { j += 2; continue }
            if (c == '\'') inChar = false
            j++
            continue
        }
        if (c == '/' && j + 1 < srcText.length && srcText[j + 1] == '/') { inLineComment = true; j += 2; continue }
        if (c == '"') { inStr = true; j++; continue }
        if (c == '\'') { inChar = true; j++; continue }
        if (c == '{') depth++
        if (c == '}') {
            depth--
            if (depth == 0) return srcText.substring(open, j + 1)
        }
        j++
    }
    return ""
}

private fun sameFunBodyNorm(aText: String, aSig: String, bText: String, bSig: String): Boolean {
    val a = funBody(aText, aSig)
    val b = funBody(bText, bSig)
    if (a.isEmpty() || b.isEmpty()) return false
    fun norm(s: String) = s.replace("Kotlin(", "(").split(Regex("\\s+")).joinToString(" ")
    return norm(a) == norm(b)
}

/** Badan [sig] di [srcText] mengandung SEMUA baris [lines] (per-baris,
 *  dinormalisasi spasi) — untuk fun yang diekstrak dari blok inline. */
private fun bodyContainsLines(srcText: String, sig: String, lines: List<String>): Boolean {
    val b = funBody(srcText, sig)
    if (b.isEmpty()) return false
    fun norm(s: String) = s.split(Regex("\\s+")).joinToString(" ").trim()
    val nb = norm(b)
    return lines.all { norm(it).isNotEmpty() && nb.contains(norm(it)) }
}

/** Segmen teks sebuah fun dari [sig] sampai deklarasi fun berikutnya
 *  (dipakai untuk fun expression-body `= ...` yang tak punya `{` —
 *  `funBody` di atas tak bisa mengekstraknya). */
private fun funSegment(srcText: String, sig: String): String {
    val i = srcText.indexOf(sig)
    if (i < 0) return ""
    val from = i + sig.length
    val ends = listOf("\n    private fun ", "\n    fun ", "\n    override fun ", "\n    internal fun ")
        .mapNotNull { m -> srcText.indexOf(m, from).takeIf { it >= 0 } }
    return srcText.substring(i, ends.minOrNull() ?: srcText.length)
}

/** Bandingkan dua nilai JSON secara SEMANTIK (urutan key tidak penting). */
private fun jsonSame(a: Any?, b: Any?): Boolean {
    if (a is JSONObject && b is JSONObject) {
        if (a.length() != b.length()) return false
        val keys = a.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!b.has(k)) return false
            if (!jsonSame(a.opt(k), b.opt(k))) return false
        }
        return true
    }
    if (a is JSONArray && b is JSONArray) {
        if (a.length() != b.length()) return false
        for (i in 0 until a.length()) if (!jsonSame(a.opt(i), b.opt(i))) return false
        return true
    }
    if (a is Number && b is Number) return a.toDouble() == b.toDouble()
    return a == b
}

private fun jsonEq(aStr: String, bStr: String): Boolean {
    return try {
        jsonSame(JSONTokener(aStr).nextValue(), JSONTokener(bStr).nextValue())
    } catch (e: Exception) { false }
}

// ---- Unsafe allocateInstance + refleksi ----
private fun getUnsafe(): sun.misc.Unsafe {
    val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
    f.isAccessible = true
    return f.get(null) as sun.misc.Unsafe
}

private val bgBrainClass: Class<*> by lazy { Class.forName("com.senzwh.deepharness.BgBrain") }
private val groupChatClass: Class<*> by lazy { Class.forName("com.senzwh.deepharness.GroupChat") }
private val ocCoordClass: Class<*> by lazy { Class.forName("com.senzwh.deepharness.OcCoord") }
private val dsAgentClass: Class<*> by lazy { Class.forName("com.senzwh.deepharness.DsAgent") }
private val memberClass: Class<*> by lazy { Class.forName("com.senzwh.deepharness.GroupChat\$Member") }
private val spinWatchClass: Class<*> by lazy { Class.forName("com.senzwh.deepharness.OcCoord\$OcSpinWatchVc303") }
private val budgetClass: Class<*> by lazy { Class.forName("com.senzwh.deepharness.OcCoord\$OcTaskBudgetVc303") }

// BgBrain/OcCoord/DsAgent: allocateInstance (lewatkan <clinit>/konstruktor —
// fun yang diuji tidak menyentuh state instance). GroupChat: INSTANCE asli
// (wrapper memakai MEMBERS yang diinisialisasi <clinit> — listOf murni).
private val bgBrainObj: Any by lazy { getUnsafe().allocateInstance(bgBrainClass) }
private val ocCoordObj: Any by lazy { getUnsafe().allocateInstance(ocCoordClass) }
private val groupChatObj: Any by lazy { groupChatClass.getField("INSTANCE").get(null)!! }
private val dsAgentObj: Any by lazy { getUnsafe().allocateInstance(dsAgentClass) }

private fun callObj(obj: Any, name: String, types: Array<Class<*>>, vararg args: Any?): Any? {
    val m = obj.javaClass.getDeclaredMethod(name, *types)
    m.isAccessible = true
    return m.invoke(obj, *args)
}

private val T_STR = String::class.java
private val T_BOOL = Boolean::class.javaPrimitiveType!!
private val T_INT = Int::class.javaPrimitiveType!!
private val T_LONG = Long::class.javaPrimitiveType!!
private val T_INT_OBJ = Int::class.javaObjectType
private val T_LIST = List::class.java
private val T_SET = Set::class.java
private val T_MAP = Map::class.java
private val T_PAIR = Pair::class.java
private val T_SB = StringBuilder::class.java
private val T_JSONOBJ = JSONObject::class.java
private val T_JSONARR = JSONArray::class.java

@Suppress("UNCHECKED_CAST")
private fun members(): List<Any> {
    val f = groupChatClass.getDeclaredField("MEMBERS")
    f.isAccessible = true
    return f.get(groupChatObj) as List<Any>
}

private fun memberId(m: Any): String {
    val f = memberClass.getDeclaredField("id")
    f.isAccessible = true
    return f.get(m) as String
}

private fun memberIds(list: List<*>): List<String> = list.map { memberId(it!!) }

private fun newSpinWatch(): Any {
    val c = spinWatchClass.getDeclaredConstructor()
    c.isAccessible = true
    return c.newInstance()
}

private fun spinObserve(w: Any, chunk: String): String? {
    val m = spinWatchClass.getDeclaredMethod("observe", String::class.java)
    m.isAccessible = true
    return m.invoke(w, chunk) as String?
}

private fun spinFlush(w: Any): String? {
    val m = spinWatchClass.getDeclaredMethod("flush")
    m.isAccessible = true
    return m.invoke(w) as String?
}

private fun newBudget(maxSteps: Int): Any {
    val c = budgetClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
    c.isAccessible = true
    return c.newInstance(maxSteps)
}

private fun budgetOver(b: Any, n: Int): Boolean {
    val m = budgetClass.getDeclaredMethod("overSteps", Int::class.javaPrimitiveType)
    m.isAccessible = true
    return m.invoke(b, n) as Boolean
}

/** Pesan asisten OpenCode (info.role + parts text) untuk pindaian. */
private fun ocMsg(role: String, text: String?, finish: String? = null, errName: String? = null, errMsg: String? = null, errCode: Int = 0, tokInput: Long? = null, tokCache: Long? = null): JSONObject {
    val info = JSONObject().put("role", role)
    if (finish != null) info.put("finish", finish)
    if (errName != null || errMsg != null) {
        val err = JSONObject()
        if (errName != null) err.put("name", errName)
        if (errMsg != null) {
            val d = JSONObject().put("message", errMsg)
            if (errCode > 0) d.put("statusCode", errCode)
            err.put("data", d)
        }
        info.put("error", err)
    }
    if (tokInput != null) {
        val tok = JSONObject().put("input", tokInput)
        if (tokCache != null) tok.put("cache", JSONObject().put("read", tokCache))
        info.put("tokens", tok)
    }
    val m = JSONObject().put("info", info)
    if (text != null) m.put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
    return m
}

private fun msgsSample(): JSONArray {
    return JSONArray()
        .put(ocMsg("user", "halo"))
        .put(ocMsg("assistant", "Jawaban satu", tokInput = 100, tokCache = 50))
        .put(ocMsg("assistant", "## Objective ringkasan", tokInput = 150))
        .put(ocMsg("assistant", "Jawaban dua", tokInput = 200, tokCache = 0))
}

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "kotlin"
    val treeDir = args.getOrNull(1) ?: "."
    val coreDir = args.getOrNull(2) ?: "harness-core"
    val rust = mode == "rust"
    println("== TestBgRoomVc329 mode=$mode treeDir=$treeDir coreDir=$coreDir ==")
    val mergeDir = java.io.File(treeDir, "../merge")
    val bgSrc = src(treeDir, "BgBrain.kt")
    val grpSrc = src(treeDir, "GroupChat.kt")
    val dsSrc = src(treeDir, "DsAgent.kt")
    val ocSrc = src(treeDir, "OcCoord.kt")
    val seamSrc = src(treeDir, "BgRoomSeam.kt")
    val mergeBg = src(mergeDir.path, "BgBrain.kt")
    val mergeGrp = src(mergeDir.path, "GroupChat.kt")
    val mergeDs = src(mergeDir.path, "DsAgent.kt")
    val mergeOc = src(mergeDir.path, "OcCoord.kt")
    val bgRs = srcCore(coreDir, "src/bg_brain.rs")
    val grpRs = srcCore(coreDir, "src/group_chat.rs")
    val dsRs = srcCore(coreDir, "src/ds_agent.rs")
    val ocRs = srcCore(coreDir, "src/oc_coord.rs")
    val commonRs = srcCore(coreDir, "src/bg_room_common.rs")
    val glue = srcCore(coreDir, "src/jni_glue.rs")
    val libRs = srcCore(coreDir, "src/lib.rs")
    val cargoToml = srcCore(coreDir, "Cargo.toml")
    val cargoLock = srcCore(coreDir, "Cargo.lock")

    val rcSrc = listOf(
        "harness/core/RustCore.kt",
        "../harness/core/RustCore.kt",
        "../../harness/core/RustCore.kt",
        "../../../harness/core/RustCore.kt"
    ).mapNotNull { rel ->
        val f = java.io.File(treeDir, rel)
        if (f.exists()) f.readText() else null
    }.firstOrNull() ?: ""

    val memb = members()
    val senz = memb.first { memberId(it) == "senz" }
    val mandor = memb.first { memberId(it) == "mandor" }
    val mentor = memb.first { memberId(it) == "mentor" }
    val agen4 = memb.first { memberId(it) == "agen4" }

    // ---- §1. Struktur + VERSION + JNI + saklar ----
    cek("1.1 RustCore.kt VERSION 10 + 4 external fun bgRoom", rcSrc.contains("const val VERSION = 10") &&
        rcSrc.contains("external fun bgBrainJson(request: String): String?") &&
        rcSrc.contains("external fun groupChatJson(request: String): String?") &&
        rcSrc.contains("external fun dsAgentJson(request: String): String?") &&
        rcSrc.contains("external fun ocCoordJson(request: String): String?"))
    cek("1.2 jni_glue.rs RUST_CORE_VERSION 10 + 4 JNI bgRoom", glue.contains("pub const RUST_CORE_VERSION: jint = 10") &&
        glue.contains("Java_harness_core_RustCore_bgBrainJson") &&
        glue.contains("Java_harness_core_RustCore_groupChatJson") &&
        glue.contains("Java_harness_core_RustCore_dsAgentJson") &&
        glue.contains("Java_harness_core_RustCore_ocCoordJson"))
    val nSym = glue.split("pub extern \"C\" fn Java_harness_core_RustCore_").size - 1
    cek("1.3 33 simbol JNI (29 lama + 4 bgRoom)", nSym == 33)
    cek("1.4 lib.rs 4 modul bgRoom + jni_glue use", libRs.contains("pub mod bg_brain;") && libRs.contains("pub mod group_chat;") &&
        libRs.contains("pub mod ds_agent;") && libRs.contains("pub mod oc_coord;") &&
        glue.contains("use crate::bg_brain;") && glue.contains("use crate::group_chat;") &&
        glue.contains("use crate::ds_agent;") && glue.contains("use crate::oc_coord;"))
    cek("1.5 BgRoomSeam.kt: 4 objek + 4 saklar default RustCore.available", seamSrc.contains("object BgBrainSeam") &&
        seamSrc.contains("object GroupChatSeam") && seamSrc.contains("object DsAgentSeam") &&
        seamSrc.contains("object OcCoordSeam") && seamSrc.contains("var rustBgBrain = RustCore.available") &&
        seamSrc.contains("var rustGroupChat = RustCore.available") && seamSrc.contains("var rustDsAgent = RustCore.available") &&
        seamSrc.contains("var rustOcCoord = RustCore.available"))
    if (rust) {
        cek("1.6 RustCore.available true (mode rust, .so host)", harness.core.RustCore.available)
        val caps = harness.core.RustCore.rustCoreCapabilities()
        cek("1.7 capabilities: version 10 + 4 JNI bgRoom + 4 modul", caps.contains("\"version\":10") &&
            caps.contains("bgBrainJson") && caps.contains("groupChatJson") && caps.contains("dsAgentJson") && caps.contains("ocCoordJson") &&
            caps.contains("\"bg_brain\"") && caps.contains("\"group_chat\"") && caps.contains("\"ds_agent\"") && caps.contains("\"oc_coord\""))
        cek("1.8 saklar 4 seam true (mode rust)", BgBrainSeam.rustBgBrain && GroupChatSeam.rustGroupChat && DsAgentSeam.rustDsAgent && OcCoordSeam.rustOcCoord)
        cek("1.9 seam BgBrain.cutBoundary non-null (mode rust)", BgBrainSeam.cutBoundary("a\nb", 1) != null)
        cek("1.10 seam GroupChat.parseMentions non-null (mode rust)", GroupChatSeam.parseMentions("@senz", memb as List<GroupChat.Member>) != null)
        cek("1.11 seam DsAgent.dsJs non-null (mode rust)", DsAgentSeam.dsJs("probe") != null)
        cek("1.12 seam OcCoord.resultNotifIdFor non-null (mode rust)", OcCoordSeam.resultNotifIdFor(1) == 40001)
    } else {
        cek("1.6 RustCore.available false (mode kotlin, tanpa .so)", !harness.core.RustCore.available)
        cek("1.7 saklar 4 seam false (mode kotlin)", !BgBrainSeam.rustBgBrain && !GroupChatSeam.rustGroupChat && !DsAgentSeam.rustDsAgent && !OcCoordSeam.rustOcCoord)
        cek("1.8 seam BgBrain.cutBoundary null tanpa .so (kontrak fallback)", BgBrainSeam.cutBoundary("a\nb", 1) == null)
        cek("1.9 seam GroupChat.parseMentions null tanpa .so", GroupChatSeam.parseMentions("@senz", memb as List<GroupChat.Member>) == null)
        cek("1.10 seam DsAgent.dsJs null tanpa .so", DsAgentSeam.dsJs("probe") == null)
        cek("1.11 seam OcCoord.resultNotifIdFor null tanpa .so", OcCoordSeam.resultNotifIdFor(1) == null)
        cek("1.12 seam OcCoord.ruleDefaults null tanpa .so", OcCoordSeam.ruleDefaults() == null)
    }

    // ---- §2. BgBrain (diferensial wrapper + seam langsung) ----
    fun wCut(text: String, max: Int): String =
        callObj(bgBrainObj, "cutAtMessageBoundary", arrayOf(T_STR, T_INT), text, max) as String
    fun wCutKotlin(text: String, max: Int): String =
        callObj(bgBrainObj, "cutAtMessageBoundaryKotlin", arrayOf(T_STR, T_INT), text, max) as String
    cek("2.1 cutAtMessageBoundary: teks pendek utuh", wCut("abc", 10) == "abc")
    cek("2.2 cutAtMessageBoundary: potong di \\n (cut > max/2)", wCut("a\nb\nc", 4) == "a\nb")
    cek("2.3 cutAtMessageBoundary: tanpa \\n -> take(max)", wCut("abcdefghij", 4) == "abcd")
    cek("2.4 cutAtMessageBoundary: cut == max/2 -> take(max)", wCut("ab\ncdefghij", 4) == "ab\nc")
    cek("2.5 cutAtMessageBoundary: cut == max/2 (6) -> take(max)", wCut("abc\ndefgh", 6) == "abc\nde")
    cek("2.6 cutAtMessageBoundary: cut > max/2 -> substring", wCut("abcde\nfghij", 7) == "abcde")
    cek("2.7 cutAtMessageBoundary: max 0 -> kosong", wCut("ab", 0) == "")
    cek("2.8 cutAtMessageBoundaryKotlin == wrapper (semua kasus di atas)", wCutKotlin("a\nb\nc", 4) == "a\nb" && wCutKotlin("abcdefghij", 4) == "abcd" && wCutKotlin("abcde\nfghij", 7) == "abcde")
    if (rust) {
        cek("2.9 seam cutBoundary == wrapper", BgBrainSeam.cutBoundary("a\nb\nc", 4) == "a\nb" && BgBrainSeam.cutBoundary("abcdefghij", 4) == "abcd")
    } else {
        cek("2.9 seam cutBoundary null tanpa .so", BgBrainSeam.cutBoundary("a\nb\nc", 4) == null)
    }

    fun wTrim(sb: StringBuilder, maxChars: Int): Int =
        callObj(bgBrainObj, "trimTranscriptVc263", arrayOf(T_SB, T_INT), sb, maxChars) as Int
    val trimShort = StringBuilder("Tugas (jadwal): kerja\nisi pendek")
    cek("2.10 trimTranscript: pendek -> 0 + utuh", wTrim(trimShort, 60000) == 0 && trimShort.toString() == "Tugas (jadwal): kerja\nisi pendek")
    val trimOneLine = StringBuilder("x".repeat(70000))
    cek("2.11 trimTranscript: satu baris (verbatim) -> 0 + utuh", wTrim(trimOneLine, 60000) == 0 && trimOneLine.length == 70000)
    val trimLong = StringBuilder("Tugas (jadwal): kerja\n" + "x".repeat(70000))
    val d1 = wTrim(trimLong, 60000)
    cek("2.12 trimTranscript: 70022 -> buang 10132", d1 == 10132)
    cek("2.13 trimTranscript: header utuh + penanda + ekor utuh + < 60000", trimLong.toString().startsWith("Tugas (jadwal): kerja\n") &&
        trimLong.toString().contains("…(transkrip latar dipotong: 10132 char awal dibuang — hanya langkah terakhir dipertahankan)…") &&
        trimLong.toString().endsWith("x".repeat(100)) && trimLong.length < 60000)
    val trimSnap = StringBuilder("Tugas (jadwal): kerja\n" + "x".repeat(65000) + "\n" + "z".repeat(5000))
    val d2 = wTrim(trimSnap, 60000)
    cek("2.14 trimTranscript: snap ke \\n -> buang 65001", d2 == 65001)
    cek("2.15 trimTranscript: sesudah snap ekor 'z' utuh", trimSnap.toString().endsWith("z".repeat(5000)) && trimSnap.toString().startsWith("Tugas (jadwal): kerja\n"))
    if (rust) {
        val rTrim = BgBrainSeam.trimTranscript("Tugas (jadwal): kerja\n" + "x".repeat(70000), 60000)
        cek("2.16 seam trimTranscript: dropped 10132 + penanda sama", rTrim != null && rTrim.second == 10132 && rTrim.first.contains("…(transkrip latar dipotong: 10132 char awal dibuang"))
    } else {
        cek("2.16 seam trimTranscript null tanpa .so", BgBrainSeam.trimTranscript("a\nb", 10) == null)
    }

    fun wQuietKotlin(qs: Int, qe: Int, h: Int): Boolean =
        callObj(bgBrainObj, "bgQuietHourKotlin", arrayOf(T_INT, T_INT, T_INT), qs, qe, h) as Boolean
    cek("2.17 bgQuietHour: -1/-1 -> false", !wQuietKotlin(-1, -1, 23))
    cek("2.18 bgQuietHour: 23-5 jam 23/4 true, 5/12 false", wQuietKotlin(23, 5, 23) && wQuietKotlin(23, 5, 4) && !wQuietKotlin(23, 5, 5) && !wQuietKotlin(23, 5, 12))
    cek("2.19 bgQuietHour: 8-17 jam 12 true, 17/7 false", wQuietKotlin(8, 17, 12) && !wQuietKotlin(8, 17, 17) && !wQuietKotlin(8, 17, 7))
    cek("2.20 bgQuietHour: qs==qe -> false", !wQuietKotlin(5, 5, 5))
    if (rust) {
        cek("2.21 seam bgQuietHour == Kotlin", BgBrainSeam.bgQuietHour(23, 5, 23) == true && BgBrainSeam.bgQuietHour(23, 5, 5) == false && BgBrainSeam.bgQuietHour(8, 17, 12) == true && BgBrainSeam.bgQuietHour(-1, -1, 23) == false)
    } else {
        cek("2.21 seam bgQuietHour null tanpa .so", BgBrainSeam.bgQuietHour(23, 5, 23) == null)
    }

    fun wBattKotlin(level: Int, scale: Int, plugged: Int): Boolean =
        callObj(bgBrainObj, "batteryFormulaKotlin", arrayOf(T_INT, T_INT, T_INT), level, scale, plugged) as Boolean
    cek("2.22 batteryFormula: level<0 true, 50% true, 19% false, 20% true", wBattKotlin(-1, 100, 0) && wBattKotlin(50, 100, 0) && !wBattKotlin(19, 100, 0) && wBattKotlin(20, 100, 0))
    cek("2.23 batteryFormula: dicas true, 0% false, scale 0 -> true", wBattKotlin(10, 100, 1) && !wBattKotlin(0, 100, 0) && wBattKotlin(5, 0, 0))
    if (rust) {
        cek("2.24 seam batteryFormula == Kotlin", BgBrainSeam.batteryFormula(-1, 100, 0) == true && BgBrainSeam.batteryFormula(19, 100, 0) == false && BgBrainSeam.batteryFormula(20, 100, 0) == true && BgBrainSeam.batteryFormula(10, 100, 1) == true)
    } else {
        cek("2.24 seam batteryFormula null tanpa .so", BgBrainSeam.batteryFormula(50, 100, 0) == null)
    }

    fun wContKotlin(transcript: StringBuilder, wb: String): String =
        callObj(bgBrainObj, "continuationPromptKotlin", arrayOf(T_SB, T_STR), transcript, wb) as String
    val cont0 = wContKotlin(StringBuilder("Tugas (jadwal): kerja\nlangkah 1"), "")
    cek("2.25 continuationPromptKotlin: jangkar vc316 + tugas verbatim + [RIWAYAT]", cont0.contains("[LANJUTAN SISTEM — vc316 delta") &&
        cont0.contains("TUGAS (verbatim baris pertama transkrip giliran ini — jangan diubah, jangan ditambah):\nTugas (jadwal): kerja") &&
        cont0.contains("[RIWAYAT]\nTugas (jadwal): kerja\nlangkah 1\n[/RIWAYAT]") &&
        cont0.contains("Lanjutkan tugasnya:") && cont0.contains("SESI SERVER YANG SAMA"))
    cek("2.26 continuationPromptKotlin: tanpa work-notes, tanpa blok catatan", !cont0.contains("CATATAN KERJA"))
    val cont1 = wContKotlin(StringBuilder("Tugas (jadwal): kerja\nlangkah 1"), "CATATAN KERJA X")
    cek("2.27 continuationPromptKotlin: work-notes ditempel", cont1.contains("CATATAN KERJA X"))
    if (rust) {
        val sCont = BgBrainSeam.continuationPromptBg("Tugas (jadwal): kerja\nlangkah 1", "")
        cek("2.28 seam continuationPromptBg == Kotlin", sCont == cont0)
        cek("2.29 seam continuationPromptBg + work-notes == Kotlin", BgBrainSeam.continuationPromptBg("Tugas (jadwal): kerja\nlangkah 1", "CATATAN KERJA X") == cont1)
    } else {
        cek("2.28 seam continuationPromptBg null tanpa .so", BgBrainSeam.continuationPromptBg("a\nb", "") == null)
        cek("2.29 (kotlin) continuationPromptKotlin deterministik", wContKotlin(StringBuilder("Tugas (jadwal): kerja\nlangkah 1"), "") == cont0)
    }

    fun wHonestBg(task: String, steps: Int, tools: List<String>, tr: StringBuilder): String =
        callObj(bgBrainObj, "honestCapStopBg", arrayOf(T_STR, T_INT, T_LIST, T_SB), task, steps, tools, tr) as String
    val honestBg = wHonestBg("kerjakan laporan", 5, listOf("web_search", "read_file", "web_search"), StringBuilder("Tugas (jadwal): kerja\nTAIL"))
    cek("2.30 honestCapStopBg: langkah + tugas + tool unik + ekor", honestBg.contains("langkah 5") && honestBg.contains("kerjakan laporan") &&
        honestBg.contains("web_search, read_file") && honestBg.contains("Transkrip terakhir: ") && honestBg.endsWith("TAIL"))
    cek("2.31 honestCapStopBg: teks persis vc277", honestBg == "Tugas latar berhenti di tengah pada langkah 5 dari tugas: kerjakan laporan — ini yang sudah selesai: web_search, read_file; ini yang belum: tugas belum selesai — sisa langkah tidak dikerjakan. Transkrip terakhir: Tugas (jadwal): kerja\nTAIL")
    cek("2.32 honestCapStopBg: tanpa tool -> 'belum ada tool yang dijalankan'", wHonestBg("t", 1, emptyList(), StringBuilder("x")).contains("belum ada tool yang dijalankan"))
    cek("2.33 honestCapStopBg: tugas dicap 300", run {
        val longTask = "t".repeat(400)
        val m = wHonestBg(longTask, 1, emptyList(), StringBuilder("x"))
        m.contains("t".repeat(300)) && !m.contains("t".repeat(301))
    })
    if (rust) {
        cek("2.34 seam honestCapStopBg == Kotlin", BgBrainSeam.honestCapStopBg("web_search, read_file", 5, "kerjakan laporan", "Tugas (jadwal): kerja\nTAIL") == honestBg)
    } else {
        cek("2.34 seam honestCapStopBg null tanpa .so", BgBrainSeam.honestCapStopBg("a", 1, "t", "x") == null)
    }

    fun wRoute(tool: String, gates: Boolean, mem: Boolean, jobTools: Set<String>?, risky: Boolean, auto: Boolean, prop: Pair<String, String>?): Pair<String, String?> =
        @Suppress("UNCHECKED_CAST")
        (callObj(bgBrainObj, "bgToolRouteDecision", arrayOf(T_STR, T_BOOL, T_BOOL, T_SET, T_BOOL, T_BOOL, T_PAIR), tool, gates, mem, jobTools, risky, auto, prop) as Pair<String, String?>)
    cek("2.35 bgToolRoute: make_plan/update_plan -> execute", wRoute("make_plan", false, false, null, false, false, null) == ("execute" to null) && wRoute("update_plan", false, false, null, false, false, null) == ("execute" to null))
    cek("2.36 bgToolRoute: analyze_photo -> pesan tetap", wRoute("analyze_photo", false, false, null, false, false, null) == ("msg_analyze_photo" to "Tool analyze_photo hanya tersedia dari chat 1-1 — di mode latar tidak dijalankan. Selesaikan tugas tanpa tool itu."))
    cek("2.37 bgToolRoute: delete_session -> pesan tetap", wRoute("delete_session", false, false, null, false, false, null) == ("msg_delete_session" to "Tool delete_session hanya tersedia dari UI chat aktif — di mode latar tidak dijalankan. Selesaikan tugas tanpa tool itu."))
    cek("2.38 bgToolRoute: gatesDisabled -> execute polos (tanpa ledger)", wRoute("read_file", true, false, null, false, false, null) == ("execute" to null) && wRoute("hapus_fakta", true, true, setOf("x"), true, false, null) == ("execute" to null))
    cek("2.39 bgToolRoute: memori + jobTools membatasi -> park_memory_jobtools", wRoute("write_memory", false, true, setOf("read_file"), false, false, null) == ("park_memory_jobtools" to null))
    cek("2.40 bgToolRoute: memori + proposal -> memory_write + pesan proposal", wRoute("write_memory", false, true, null, false, false, "k" to "v") == ("memory_write" to "Proposal memori latar DITAMPUNG di antrean persetujuan batch (Setup 65) — BELUM tersimpan dan BELUM dieksekusi, menunggu persetujuan user: k = v. Lanjutkan tugas tanpa menganggap fakta ini sudah tersimpan."))
    cek("2.41 bgToolRoute: memori tanpa proposal -> park_memory_noprop", wRoute("write_memory", false, true, null, false, false, null) == ("park_memory_noprop" to null))
    cek("2.42 bgToolRoute: berisiko tanpa autoAllow -> park_risky", wRoute("delete_file", false, false, null, true, false, null) == ("park_risky" to null))
    cek("2.43 bgToolRoute: berisiko + autoAllow + jobTools null -> execute", wRoute("delete_file", false, false, null, true, true, null) == ("execute" to null))
    cek("2.44 bgToolRoute: berisiko + autoAllow + tool di jobTools -> execute", wRoute("delete_file", false, false, setOf("delete_file"), true, true, null) == ("execute" to null))
    cek("2.45 bgToolRoute: berisiko + autoAllow + tool di luar jobTools -> park_risky", wRoute("delete_file", false, false, setOf("read_file"), true, true, null) == ("park_risky" to null))
    cek("2.46 bgToolRoute: biasa -> execute", wRoute("read_file", false, false, null, false, false, null) == ("execute" to null))
    if (rust) {
        cek("2.47 seam bgToolRoute == wrapper (4 kasus)", BgBrainSeam.bgToolRoute("make_plan", false, false, true, false, false, false, false, "", "") == ("execute" to null) &&
            BgBrainSeam.bgToolRoute("analyze_photo", false, false, true, false, false, false, false, "", "")?.first == "msg_analyze_photo" &&
            BgBrainSeam.bgToolRoute("write_memory", false, true, true, false, false, false, true, "k", "v")?.first == "memory_write" &&
            BgBrainSeam.bgToolRoute("delete_file", false, false, true, false, true, false, false, "", "") == ("park_risky" to null))
    } else {
        cek("2.47 seam bgToolRoute null tanpa .so", BgBrainSeam.bgToolRoute("read_file", false, false, true, false, false, false, false, "", "") == null)
    }

    fun wWatch(start: Long, hb: Long, lastOk: Long, now: Long, notified: Boolean): Pair<String, String?> =
        @Suppress("UNCHECKED_CAST")
        (callObj(bgBrainObj, "watchdogDecisionKey", arrayOf(T_LONG, T_LONG, T_LONG, T_LONG, T_BOOL), start, hb, lastOk, now, notified) as Pair<String, String?>)
    cek("2.48 watchdog: start<=0 -> skip", wWatch(0, 0, 0, 1000, false) == ("skip" to null))
    cek("2.49 watchdog: >2 jam -> cleanup", wWatch(1000, 1000, 1000, 1000 + 7_200_001L, false) == ("cleanup" to null))
    cek("2.50 watchdog: tepat 2 jam + heartbeat segar -> skip (bukan cleanup)", wWatch(1000, 1000 + 7_200_000L, 1000 + 7_200_000L, 1000 + 7_200_000L, false) == ("skip" to null))
    cek("2.51 watchdog: sudah dinotifikasi -> skip", wWatch(1000, 1000, 1000, 2000, true) == ("skip" to null))
    cek("2.52 watchdog: macet -> notify + alasan giliran macet", wWatch(1000, 1000, 1000 + 601000L, 1000 + 601000L, false) == ("notify" to "tidak ada kemajuan selama 10 menit (giliran macet)"))
    cek("2.53 watchdog: berputar saja -> notify + alasan tanpa progres", wWatch(1000, 1000 + 601000L, 1000, 1000 + 601000L, false) == ("notify" to "tidak ada tool sukses selama 10 menit (berputar tanpa progres)"))
    cek("2.54 watchdog: segar -> skip", wWatch(1000, 1000 + 601000L, 1000 + 601000L, 1000 + 601000L, false) == ("skip" to null))
    if (rust) {
        cek("2.55 seam watchdogDecision == wrapper (3 kasus)", BgBrainSeam.watchdogDecision(1000, 1000, 1000, 1000 + 7_200_001L, false) == ("cleanup" to null) &&
            BgBrainSeam.watchdogDecision(1000, 1000, 1000 + 601000L, 1000 + 601000L, false) == ("notify" to "tidak ada kemajuan selama 10 menit (giliran macet)") &&
            BgBrainSeam.watchdogDecision(1000, 1000 + 601000L, 1000 + 601000L, 1000 + 601000L, false) == ("skip" to null))
    } else {
        cek("2.55 seam watchdogDecision null tanpa .so", BgBrainSeam.watchdogDecision(1000, 1000, 1000, 2000, false) == null)
    }

    fun wDeliver(title: String?, origin: String, failed: Boolean, answer: String): Pair<String, String> =
        @Suppress("UNCHECKED_CAST")
        (callObj(bgBrainObj, "deliverTitleText", arrayOf(T_STR, T_STR, T_BOOL, T_STR), title, origin, failed, answer) as Pair<String, String>)
    cek("2.56 deliver: jadwal/aturan/tujuan/briefing/rekap/konsolidasi", wDeliver(null, "jadwal", false, "x") == ("🤖 Tugas terjadwal selesai" to "x") &&
        wDeliver(null, "aturan", false, "x") == ("⚡ Aturan notifikasi dijalankan" to "x") &&
        wDeliver(null, "tujuan", false, "x") == ("🎯 Langkah tujuan dikerjakan" to "x") &&
        wDeliver(null, "briefing", false, "x") == ("☀️ Briefing pagi" to "x") &&
        wDeliver(null, "rekap", false, "x") == ("🌙 Rekap malam" to "x") &&
        wDeliver(null, "konsolidasi", false, "x") == ("🌙 Konsolidasi memori" to "x"))
    cek("2.57 deliver: origin lain + judul eksplisit", wDeliver(null, "xyz", false, "x") == ("🤖 Otak Latar — xyz" to "x") && wDeliver("Judul Saya", "jadwal", false, "x") == ("Judul Saya" to "x"))
    cek("2.58 deliver: route gagal -> prefix ⚠️", wDeliver(null, "jadwal", true, "x") == ("⚠️ Laporan gagal tersimpan — 🤖 Tugas terjadwal selesai" to "x"))
    cek("2.59 deliver: jawaban kosong -> fallback + trim", wDeliver(null, "jadwal", false, "   ") == ("🤖 Tugas terjadwal selesai" to "(tugas selesai tanpa teks jawaban)") && wDeliver(null, "jadwal", false, "  hasil  ") == ("🤖 Tugas terjadwal selesai" to "hasil"))
    if (rust) {
        cek("2.60 seam deliverTitle == wrapper", BgBrainSeam.deliverTitle(null, "jadwal", false, "hasil kerja") == ("🤖 Tugas terjadwal selesai" to "hasil kerja") &&
            BgBrainSeam.deliverTitle(null, "xyz", true, "   ") == ("⚠️ Laporan gagal tersimpan — 🤖 Otak Latar — xyz" to "(tugas selesai tanpa teks jawaban)"))
    } else {
        cek("2.60 seam deliverTitle null tanpa .so", BgBrainSeam.deliverTitle(null, "jadwal", false, "x") == null)
    }

    if (rust) {
        cek("2.61 seam submitDecision: cooldown menang (diff 1000)", BgBrainSeam.submitDecision(999_000L, 1_000_000L, false, 0, false, true, false, "jadwal", true) == BgBrainSeam.SubmitDecision("cooldown", false, null))
        cek("2.62 seam submitDecision: diff tepat 900000 -> proceed", BgBrainSeam.submitDecision(100_000L, 1_000_000L, false, 0, false, true, false, "jadwal", true)?.decision == "proceed")
        cek("2.63 seam submitDecision: sesi mati dalam 15 mnt -> blok", BgBrainSeam.submitDecision(0, 2_000_000L, true, 1_900_000L, false, true, false, "jadwal", true)?.decision == "sesi mati")
        cek("2.64 seam submitDecision: sesi mati lewat 15 mnt -> proceed + clear", run {
            val d = BgBrainSeam.submitDecision(0, 2_000_000L, true, 1_000_000L, false, true, false, "jadwal", true)
            d?.decision == "proceed" && d.clearSessionDead
        })
        cek("2.65 seam submitDecision: kuota -> gerbang -> job -> baterai", BgBrainSeam.submitDecision(0, 0, false, 0, true, true, false, "jadwal", true)?.decision == "kuota" &&
            BgBrainSeam.submitDecision(0, 0, false, 0, false, false, false, "jadwal", true)?.decision == "gerbang" &&
            BgBrainSeam.submitDecision(0, 0, false, 0, false, true, true, "rutinitas", true)?.decision == "job dimatikan" &&
            BgBrainSeam.submitDecision(0, 0, false, 0, false, true, false, "jadwal", false)?.decision == "baterai")
        cek("2.66 seam submitDecision: jobsOff hanya jadwal/rutinitas", BgBrainSeam.submitDecision(0, 0, false, 0, false, true, true, "manual", true)?.decision == "proceed")
        cek("2.67 seam submitDecision: governorNotify hanya kuota/gerbang", BgBrainSeam.submitDecision(0, 0, false, 0, true, true, false, "jadwal", true)?.governorNotify == "kuota harian Governor habis" &&
            BgBrainSeam.submitDecision(0, 0, false, 0, false, false, false, "jadwal", true)?.governorNotify == "gerbang jam hening / WiFi-only" &&
            BgBrainSeam.submitDecision(0, 0, false, 0, false, true, false, "jadwal", true)?.governorNotify == null)
        cek("2.68 seam queueFactProposal: baru -> [{\"v\",\"k\"}] count 1", run {
            val r = BgBrainSeam.queueFactProposal("", "kota", "Jakarta")
            r?.second == 1 && r.first == "[{\"v\":\"Jakarta\",\"k\":\"kota\"}]"
        })
        cek("2.69 seam queueFactProposal: ganti key sama (urutan dipertahankan)", run {
            val r = BgBrainSeam.queueFactProposal("[{\"v\":\"Lama\",\"k\":\"kota\"},{\"v\":\"x\",\"k\":\"lain\"}]", "kota", "Baru")
            r?.second == 2 && r.first == "[{\"v\":\"Baru\",\"k\":\"kota\"},{\"v\":\"x\",\"k\":\"lain\"}]"
        })
        cek("2.70 seam queueFactProposal: JSON rusak -> (-1, null)", BgBrainSeam.queueFactProposal("{bukan json", "k", "v") == (null to -1))
        cek("2.71 seam queueFactProposal: elemen non-objek dilewati", run {
            val r = BgBrainSeam.queueFactProposal("[1,\"s\",{\"v\":\"a\",\"k\":\"k\"}]", "k2", "v2")
            r?.second == 2 && r.first != null && jsonEq(r.first!!, "[{\"v\":\"a\",\"k\":\"k\"},{\"v\":\"v2\",\"k\":\"k2\"}]")
        })
    } else {
        cek("2.61 seam submitDecision null tanpa .so", BgBrainSeam.submitDecision(0, 0, false, 0, false, true, false, "jadwal", true) == null)
        cek("2.62 seam queueFactProposal null tanpa .so", BgBrainSeam.queueFactProposal("", "k", "v") == null)
        cek("2.63 submitGateKotlin: urutan gerbang (cooldown -> sesi mati -> kuota -> gerbang -> job -> baterai)", run {
            val b = funBody(bgSrc, "private fun submitGateKotlin(")
            b.contains("cooldown") && b.contains("\"sesi mati\"") && b.contains("\"kuota\"") && b.contains("\"gerbang\"") &&
            b.contains("\"job dimatikan\"") && b.contains("\"baterai\"") &&
            b.indexOf("cooldown") < b.indexOf("\"sesi mati\"") && b.indexOf("\"sesi mati\"") < b.indexOf("\"kuota\"") &&
            b.indexOf("\"kuota\"") < b.indexOf("\"gerbang\"") && b.indexOf("\"gerbang\"") < b.indexOf("\"job dimatikan\"")
        })
        cek("2.64 queueBgFactProposalKotlin: replace-or-append {k,v} + -1 bila JSON rusak", bodyContainsLines(bgSrc, "private fun queueBgFactProposalKotlin(", listOf(
            "val old = if (raw.isBlank()) JSONArray() else JSONArray(raw)",
            "if (o.optString(\"k\") == prop.first)",
            "out.put(JSONObject().put(\"k\", prop.first).put(\"v\", prop.second))",
            "} catch (e: Exception) { -1 }"
        )))
    }

    // ---- §3. GroupChat (diferensial wrapper + seam langsung) ----
    cek("3.1 personaNameFor: 4 anggota + '—' bila tak dikenal", GroupChat.personaNameFor("senz") == "Senz" && GroupChat.personaNameFor("mandor") == "Mandor" &&
        GroupChat.personaNameFor("mentor") == "Mentor" && GroupChat.personaNameFor("agen4") == "Agen 4" && GroupChat.personaNameFor("x") == "—")
    cek("3.2 accentFor: 4 aksen + default #4d8cff (case-insensitive)", GroupChat.accentFor("Senz") == "#4d8cff" && GroupChat.accentFor("mandor") == "#e8a33d" &&
        GroupChat.accentFor("Mentor") == "#2fbf9a" && GroupChat.accentFor("Agen 4") == "#9a6bff" && GroupChat.accentFor("x") == "#4d8cff")
    if (rust) {
        cek("3.3 seam memberInfo: nama dari id + aksen dari nama", GroupChatSeam.memberInfo(memb as List<GroupChat.Member>, "mandor", "Mandor") == ("Mandor" to "#e8a33d") &&
            GroupChatSeam.memberInfo(memb as List<GroupChat.Member>, "mandor", "x") == ("Mandor" to "#4d8cff") &&
            GroupChatSeam.memberInfo(memb as List<GroupChat.Member>, "x", "x") == ("—" to "#4d8cff"))
    } else {
        cek("3.3 seam memberInfo null tanpa .so", GroupChatSeam.memberInfo(memb as List<GroupChat.Member>, "senz", "Senz") == null)
    }

    fun wMentions(text: String): List<String> = memberIds(GroupChat.parseMentions(text))
    cek("3.4 parseMentions: urut posisi kemunculan", wMentions("halo @senz dan @mandor, lalu @mentor") == listOf("senz", "mandor", "mentor"))
    cek("3.5 parseMentions: @Agen 4 / @agen4 (case-insensitive)", wMentions("@Agen 4 tolong") == listOf("agen4") && wMentions("tanya @agen4") == listOf("agen4") && wMentions("@agen 4 dan @agen4") == listOf("agen4"))
    cek("3.6 parseMentions: dedup + urut posisi (bukan urut anggota)", wMentions("@mentor @senz @mentor") == listOf("mentor", "senz"))
    cek("3.7 parseMentions: tanpa mention -> kosong", wMentions("tidak ada mention") == emptyList<String>())
    cek("3.8 parseMentions: '@senzwh' ikut cocok '@senz' (substring, perilaku Kotlin asli)", wMentions("halo @senzwh") == listOf("senz"))
    if (rust) {
        cek("3.9 seam parseMentions == wrapper", GroupChatSeam.parseMentions("@mentor @senz @mentor", memb as List<GroupChat.Member>) == listOf("mentor", "senz") &&
            GroupChatSeam.parseMentions("halo @senzwh", memb as List<GroupChat.Member>) == listOf("senz"))
    } else {
        cek("3.9 seam parseMentions null tanpa .so (lihat §1.9)", true)
    }

    fun wQueue(mentioned: List<Any>, allOn: Boolean, active: List<Any>): Triple<List<String>, List<String>, String> {
        @Suppress("UNCHECKED_CAST")
        val r = callObj(groupChatObj, "selectQueue", arrayOf(T_LIST, T_BOOL, T_LIST), mentioned, allOn, active) as Triple<List<Any>, List<String>, String>
        return Triple(memberIds(r.first), r.second, r.third)
    }
    cek("3.10 selectQueue: tanpa mention -> anggota aktif pertama", wQueue(emptyList(), false, listOf(senz)) == Triple(listOf("senz"), emptyList<String>(), "senz"))
    cek("3.11 selectQueue: nol aktif -> catatan + antrean kosong + chair senz", wQueue(emptyList(), false, emptyList()) == Triple<List<String>, List<String>, String>(emptyList(), listOf("Tidak ada anggota grup yang aktif — aktifkan lewat ⚙️ Setting Grup."), "senz"))
    cek("3.12 selectQueue: allOn -> semua aktif", wQueue(emptyList(), true, listOf(senz, mandor)) == Triple(listOf("senz", "mandor"), emptyList<String>(), "senz"))
    cek("3.13 selectQueue: mention aktif -> antrean itu", wQueue(listOf(mandor), false, listOf(senz, mandor)) == Triple(listOf("mandor"), emptyList<String>(), "senz"))
    cek("3.14 selectQueue: mention nonaktif -> catatan 💤 + antrean kosong", wQueue(listOf(mandor), false, listOf(senz)) == Triple<List<String>, List<String>, String>(emptyList(), listOf("💤 Mandor nonaktif di grup — aktifkan lewat ⚙️ Setting Grup."), "senz"))
    cek("3.15 selectQueue: chair = anggota aktif pertama", wQueue(emptyList(), false, listOf(mandor, mentor)).third == "mandor")
    if (rust) {
        cek("3.16 seam selectQueue == wrapper (3 kasus)", GroupChatSeam.selectQueue(emptyList(), false, listOf("senz"), memb as List<GroupChat.Member>) == Triple(listOf("senz"), emptyList<String>(), "senz") &&
            GroupChatSeam.selectQueue(emptyList(), false, emptyList(), memb as List<GroupChat.Member>) == Triple<List<String>, List<String>, String>(emptyList(), listOf("Tidak ada anggota grup yang aktif — aktifkan lewat ⚙️ Setting Grup."), "senz") &&
            GroupChatSeam.selectQueue(listOf("mandor"), false, listOf("senz"), memb as List<GroupChat.Member>) == Triple<List<String>, List<String>, String>(emptyList(), listOf("💤 Mandor nonaktif di grup — aktifkan lewat ⚙️ Setting Grup."), "senz"))
    } else {
        cek("3.16 seam selectQueue null tanpa .so", GroupChatSeam.selectQueue(emptyList(), false, listOf("senz"), memb as List<GroupChat.Member>) == null)
    }

    fun wSlotKotlin(bound: Int?, used: Set<Int>, filled: List<Int>, persona: Map<Int, String>): Int? =
        callObj(groupChatObj, "resolveSlotKotlin", arrayOf(T_INT_OBJ, T_SET, T_LIST, T_MAP), bound, used, filled, persona) as Int?
    cek("3.17 resolveSlotKotlin: terikat -> slot itu", wSlotKotlin(2, setOf(1), listOf(1, 2, 3), mapOf(1 to "", 2 to "", 3 to "")) == 2)
    cek("3.18 resolveSlotKotlin: terikat tapi dipakai -> null", wSlotKotlin(2, setOf(2), listOf(1, 2, 3), mapOf(1 to "", 2 to "", 3 to "")) == null)
    cek("3.19 resolveSlotKotlin: tanpa ikatan -> slot bebas pertama", wSlotKotlin(null, setOf(1), listOf(1, 2, 3), mapOf(1 to "", 2 to "", 3 to "")) == 2)
    cek("3.20 resolveSlotKotlin: slot terikat agen lain dilewati", wSlotKotlin(null, emptySet(), listOf(1, 2), mapOf(1 to "mandor", 2 to "")) == 2)
    cek("3.21 resolveSlotKotlin: tidak ada slot -> null", wSlotKotlin(null, emptySet(), emptyList(), emptyMap()) == null && wSlotKotlin(null, setOf(1), listOf(1), mapOf(1 to "")) == null)
    if (rust) {
        cek("3.22 seam slotResolve == Kotlin", GroupChatSeam.slotResolve(2, listOf(1), listOf(1, 2, 3), mapOf(1 to "", 2 to "", 3 to "")) == 2 &&
            GroupChatSeam.slotResolve(null, listOf(1), listOf(1, 2, 3), mapOf(1 to "", 2 to "", 3 to "")) == 2 &&
            GroupChatSeam.slotResolve(null, emptyList(), listOf(1, 2), mapOf(1 to "mandor", 2 to "")) == 2)
    } else {
        cek("3.22 seam slotResolve null = gagal ATAU tidak ada slot (kontrak ambigu)", GroupChatSeam.slotResolve(2, listOf(1), listOf(1, 2), mapOf(1 to "", 2 to "")) == null)
    }

    fun wHonestGrp(tools: List<String>, userText: String): String =
        callObj(groupChatObj, "honestCapStopGroup", arrayOf(T_LIST, T_STR), tools, userText) as String
    cek("3.23 honestCapStopGroup: teks persis vc277", wHonestGrp(listOf("a", "b", "a"), "tugas x") == "berhenti di tengah pada langkah 3 dari tugas: tugas x — tiap anggota maks 3 langkah tool per ronde. Ini yang sudah selesai: a, b; ini yang belum: sisa tugas belum dikerjakan dalam ronde ini — lanjutkan di ronde berikutnya atau minta anggota lain.")
    cek("3.24 honestCapStopGroup: tanpa tool -> 'belum ada tool yang dijalankan'", wHonestGrp(emptyList(), "t").contains("belum ada tool yang dijalankan"))
    if (rust) {
        cek("3.25 seam honestCapStopGroup == Kotlin", GroupChatSeam.honestCapStopGroup("a, b", "tugas x") == wHonestGrp(listOf("a", "b"), "tugas x"))
    } else {
        cek("3.25 seam honestCapStopGroup null tanpa .so", GroupChatSeam.honestCapStopGroup("a", "t") == null)
    }

    fun wHop(clean: String, allOn: Boolean, mentionedEmpty: Boolean, isChair: Boolean, bubbles: Int, responded: Set<String>, queue: List<Any>): List<String> =
        @Suppress("UNCHECKED_CAST")
        (callObj(groupChatObj, "chairHop", arrayOf(T_STR, T_BOOL, T_BOOL, T_BOOL, T_INT, T_SET, T_LIST), clean, allOn, mentionedEmpty, isChair, bubbles, responded, queue) as List<String>)
    cek("3.26 chairHop: ketua + tanpa mention -> yang disebut di balasan diundang", wHop("@mandor bantu", false, true, true, 0, emptySet(), listOf(senz)) == listOf("mandor"))
    cek("3.27 chairHop: allOn / ada mention awal / bukan ketua / bubble penuh -> kosong", wHop("@mandor", true, true, true, 0, emptySet(), listOf(senz)).isEmpty() &&
        wHop("@mandor", false, false, true, 0, emptySet(), listOf(senz)).isEmpty() &&
        wHop("@mandor", false, true, false, 0, emptySet(), listOf(senz)).isEmpty() &&
        wHop("@mandor", false, true, true, 6, emptySet(), listOf(senz)).isEmpty())
    cek("3.28 chairHop: sudah menjawab / sudah di antrean -> tidak diundang lagi", wHop("@mandor", false, true, true, 0, setOf("mandor"), listOf(senz)).isEmpty() &&
        wHop("@mandor", false, true, true, 0, emptySet(), listOf(senz, mandor)).isEmpty())
    cek("3.29 chairHop: tanpa mention di balasan -> kosong", wHop("tidak ada mention", false, true, true, 0, emptySet(), listOf(senz)).isEmpty())
    if (rust) {
        cek("3.30 seam chairHop == wrapper", GroupChatSeam.chairHop("@mandor bantu", false, true, true, 0, 6, emptyList(), listOf("senz"), memb as List<GroupChat.Member>) == listOf("mandor") &&
            GroupChatSeam.chairHop("@mandor", true, true, true, 0, 6, emptyList(), listOf("senz"), memb as List<GroupChat.Member>) == emptyList<String>())
    } else {
        cek("3.30 seam chairHop null tanpa .so", GroupChatSeam.chairHop("@mandor", false, true, true, 0, 6, emptyList(), listOf("senz"), memb as List<GroupChat.Member>) == null)
    }

    fun wMemFilter(raw: String): List<String> =
        @Suppress("UNCHECKED_CAST")
        (callObj(groupChatObj, "retrieveMemoriesKotlin", arrayOf(T_STR), raw) as List<String>)
    cek("3.31 retrieveMemoriesKotlin: buang kosong + 'Tidak ada memori' + 'Kata kunci'", wMemFilter("Tidak ada memori untuk ini\nbaris satu\n\nKata kunci: x\nbaris dua") == listOf("baris satu", "baris dua"))
    cek("3.32 retrieveMemoriesKotlin: maks 10 baris x 1000 char", run {
        val raw = (1..12).joinToString("\n") { "baris $it" }
        val out = wMemFilter(raw)
        out.size == 10 && out.first() == "baris 1" && out.last() == "baris 10"
    } && wMemFilter("x".repeat(1500)).single().length == 1000)
    if (rust) {
        cek("3.33 seam retrieveMemoriesFilter == Kotlin", GroupChatSeam.retrieveMemoriesFilter("Tidak ada memori\nbaris satu\nKata kunci: x\nbaris dua") == listOf("baris satu", "baris dua"))
    } else {
        cek("3.33 seam retrieveMemoriesFilter null tanpa .so", GroupChatSeam.retrieveMemoriesFilter("baris") == null)
    }

    fun wContRoom(userText: String, suffix: String): String =
        callObj(groupChatObj, "continuationPromptVc321", arrayOf(T_STR, T_STR), userText, suffix) as String
    val contRoom = wContRoom("kerjakan X", "hasil tool")
    cek("3.34 continuationPromptVc321: jangkar + '\\n\\n' + suffix", contRoom.contains("vc316 delta") && contRoom.endsWith("\n\n" + "hasil tool"))
    cek("3.35 continuationPromptVc321: jangkar membawa jalan keluar list_tools", contRoom.contains("list_tools"))
    if (rust) {
        cek("3.36 seam continuationPromptRoom == wrapper", GroupChatSeam.continuationPromptRoom("kerjakan X", "hasil tool") == contRoom)
    } else {
        cek("3.36 seam continuationPromptRoom null tanpa .so", GroupChatSeam.continuationPromptRoom("kerjakan X", "hasil tool") == null)
    }

    fun wQuietGrp(): Boolean = callObj(groupChatObj, "isQuietHour", arrayOf(), ) as Boolean
    cek("3.37 isQuietHour: h>=23 || h<5 (jam sekarang)", wQuietGrp() == run { val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); h >= 23 || h < 5 })
    if (rust) {
        cek("3.38 seam quietHourGroup: 23/4 true, 5/12 false", GroupChatSeam.quietHourGroup(23) == true && GroupChatSeam.quietHourGroup(4) == true && GroupChatSeam.quietHourGroup(5) == false && GroupChatSeam.quietHourGroup(12) == false)
    } else {
        cek("3.38 seam quietHourGroup null tanpa .so", GroupChatSeam.quietHourGroup(23) == null)
    }

    fun wRender(body: String): String =
        callObj(groupChatObj, "renderNotif3LinesKotlin", arrayOf(T_STR), body) as String
    cek("3.39 renderNotif3LinesKotlin: <=3 baris utuh, >3 dipotong + penanda", wRender("satu\ndua") == "satu\ndua" && wRender("a\nb\nc") == "a\nb\nc" &&
        wRender("1\n2\n3\n4\n5") == "1\n2\n3\n…(2 baris lagi)…")
    if (rust) {
        cek("3.40 seam renderNotif3Lines == Kotlin", GroupChatSeam.renderNotif3Lines("1\n2\n3\n4\n5") == "1\n2\n3\n…(2 baris lagi)…" && GroupChatSeam.renderNotif3Lines("a\nb") == "a\nb")
    } else {
        cek("3.40 seam renderNotif3Lines null tanpa .so", GroupChatSeam.renderNotif3Lines("a\nb") == null)
    }

    fun wPlans(raw: String): List<GroupChat.GroupPlan> =
        @Suppress("UNCHECKED_CAST")
        (callObj(groupChatObj, "groupPlansKotlin", arrayOf(T_STR), raw) as List<GroupChat.GroupPlan>)
    cek("3.41 groupPlansKotlin: sort desc ts, stabil bila ts sama", run {
        val p = wPlans("[{\"title\":\"b\",\"body\":\"2\",\"ts\":5},{\"title\":\"a\",\"body\":\"1\",\"ts\":9},{\"title\":\"c\",\"body\":\"3\",\"ts\":5}]")
        p.size == 3 && p[0].title == "a" && p[1].title == "b" && p[2].title == "c"
    })
    cek("3.42 groupPlansKotlin: array kosong -> kosong", wPlans("[]").isEmpty())
    if (rust) {
        cek("3.43 seam parsePlans == Kotlin (sort stabil)", run {
            val p = GroupChatSeam.parsePlans("[{\"title\":\"b\",\"body\":\"2\",\"ts\":5},{\"title\":\"a\",\"body\":\"1\",\"ts\":9},{\"title\":\"c\",\"body\":\"3\",\"ts\":5}]")
            p != null && p.size == 3 && p[0].title == "a" && p[1].title == "b" && p[2].title == "c"
        })
        cek("3.44 seam parsePlans: JSON rusak -> list kosong (bukan null)", GroupChatSeam.parsePlans("bukan json") == emptyList<GroupChat.GroupPlan>() && GroupChatSeam.parsePlans("") == emptyList<GroupChat.GroupPlan>())
    } else {
        cek("3.43 seam parsePlans null tanpa .so", GroupChatSeam.parsePlans("[]") == null)
        cek("3.44 (kotlin) groupPlansKotlin lempar bila JSON rusak (wrapper menangkap -> kosong)", try { wPlans("bukan json"); false } catch (e: Exception) { true })
    }

    if (rust) {
        val bp = GroupChatSeam.buildPrompt("Senz", "", "[PERILAKU]\n", "[OTAK]\n", "CTX", "PAGE", "", "WS", listOf("m1"), "", "", "", true, "KATALOG", "", "[BEBAS]", "[BATCH]", listOf("User: halo", "Senz: hai"), "halo")
        cek("3.45 seam buildPrompt: kepala persona + urutan blok", bp != null && bp.startsWith("Kamu adalah Senz di grup chat") && bp.contains("[PERILAKU]\n[OTAK]\nCTX\n\nPAGE\n\nWS\n\n"))
        cek("3.46 seam buildPrompt: memori + katalog + [BATCH] sesudah cap", bp != null && bp.contains("Memori relevan (otak kedua)") && bp.contains("- m1\n\n") && bp.contains("KATALOG TOOL (sama seperti chat 1-1") && bp.contains("KATALOG\n\n") && bp.contains("[BATCH]\n\n[BEBAS]"))
        cek("3.47 seam buildPrompt: [RIWAYAT] + [USER — PESAN PEMICU VERBATIM]", bp != null && bp.contains("[RIWAYAT]\nTranskrip grup:\nUser: halo\nSenz: hai\n[/RIWAYAT]\n") && bp.contains("[USER — PESAN PEMICU VERBATIM]:\nhalo\n[/USER]\n"))
        val bpOff = GroupChatSeam.buildPrompt("Senz", "", "[PERILAKU]\n", "[OTAK]\n", "CTX", "PAGE", "", "WS", emptyList(), "", "", "", false, "", "", "[BEBAS]", "[BATCH]", listOf("User: halo"), "halo")
        cek("3.48 seam buildPrompt toolsEnabled=false: teks biasa, tanpa katalog", bpOff != null && bpOff.contains("Di mode ini jawablah dengan teks biasa saja (tanpa blok json tool).") && !bpOff.contains("KATALOG TOOL"))
        val bpCap = GroupChatSeam.buildPrompt("Senz", "", "", "", "", "", "", "", emptyList(), "", "", "", true, "C".repeat(70000), "", "[BEBAS]", "[BATCH]", listOf("User: halo"), "halo")
        cek("3.49 seam buildPrompt: base >60k -> penanda cap + [BATCH] tetap ada", bpCap != null && bpCap.contains("…(base grup dipotong ke 60000 char; cap total 200000)…") && bpCap.contains("[BATCH]") && bpCap.contains("[USER — PESAN PEMICU VERBATIM]"))
        cek("3.50 seam groupToolRoute: analyze_photo -> pesan tetap", GroupChatSeam.groupToolRoute("analyze_photo", false, false, false, false, false, false) == ("msg_analyze_photo" to "analyze_photo tidak tersedia di grup/latar — analisis foto hanya dari chat 1-1. Bila foto belum dianalisis, tulis persis 'foto belum berhasil dianalisis', jangan mendeskripsikan tebakan isi foto."))
        cek("3.51 seam groupToolRoute: gatesDisabled -> execute_gates (ledger di Kotlin)", GroupChatSeam.groupToolRoute("read_file", true, false, false, false, false, false) == ("execute_gates" to null))
        cek("3.52 seam groupToolRoute: memori / create_subroom / diblokir -> dialog", GroupChatSeam.groupToolRoute("write_memory", false, true, false, false, false, false) == ("dialog" to null) &&
            GroupChatSeam.groupToolRoute("create_subroom", false, false, false, false, false, false) == ("dialog" to null) &&
            GroupChatSeam.groupToolRoute("read_file", false, false, true, true, false, false) == ("dialog" to null))
        cek("3.53 seam groupToolRoute: shortcut auto-approve / non-risiko / autoAllow -> execute", GroupChatSeam.groupToolRoute("read_file", false, false, false, true, false, false) == ("execute" to null) &&
            GroupChatSeam.groupToolRoute("read_file", false, false, false, false, false, false) == ("execute" to null) &&
            GroupChatSeam.groupToolRoute("delete_file", false, false, false, false, true, true) == ("execute" to null))
        cek("3.54 seam groupToolRoute: berisiko tanpa autoAllow -> dialog", GroupChatSeam.groupToolRoute("delete_file", false, false, false, false, true, false) == ("dialog" to null))
        cek("3.55 seam bgRoundGate: busy -> blocked (f1/jobs/jam/baterai) -> proceed", GroupChatSeam.bgRoundGate(true, false, false, 12, true) == Triple("busy", "grup sibuk — ronde foreground sedang berjalan", "sibuk") &&
            GroupChatSeam.bgRoundGate(false, true, false, 12, true) == Triple("blocked", "F1 dimatikan (kill switch)", "diblokir") &&
            GroupChatSeam.bgRoundGate(false, false, true, 12, true) == Triple("blocked", "job dimatikan", "diblokir") &&
            GroupChatSeam.bgRoundGate(false, false, false, 23, true) == Triple("blocked", "jam hening 23:00-05:00", "diblokir") &&
            GroupChatSeam.bgRoundGate(false, false, false, 12, false) == Triple("blocked", "baterai di bawah 20% dan tidak dicas", "diblokir") &&
            GroupChatSeam.bgRoundGate(false, false, false, 12, true) == Triple("proceed", null, null))
        val rowsPlan = JSONArray()
            .put(JSONObject().put("role", "user").put("name", "User").put("text", "rencana liburan"))
            .put(JSONObject().put("role", "agen").put("name", "Senz").put("text", "jawaban satu"))
            .put(JSONObject().put("role", "agen").put("name", "Mandor").put("text", "jawaban dua"))
        val save1 = GroupChatSeam.saveLastRoundPlan(rowsPlan, JSONArray(), 1_700_000_000_000L)
        cek("3.56 seam saveLastRoundPlan: tersimpan + isi plan semantik", run {
            if (save1 == null || save1.second == null) false
            else {
                val pj = JSONArray(save1.second!!)
                save1.first.contains("Rencana grup tersimpan") && pj.length() == 1 &&
                pj.optJSONObject(0)?.optString("title") == "rencana liburan" &&
                pj.optJSONObject(0)?.optString("body") == "— Senz: jawaban satu\n\n— Mandor: jawaban dua" &&
                pj.optJSONObject(0)?.optLong("ts") == 1_700_000_000_000L
            }
        })
        cek("3.57 seam saveLastRoundPlan: dedup -> pesan + plans null", run {
            val pj = JSONArray(save1!!.second!!)
            val save2 = GroupChatSeam.saveLastRoundPlan(rowsPlan, pj, 1_700_000_000_001L)
            save2 != null && save2.first.contains("sudah tersimpan sebelumnya") && save2.second == null
        })
        cek("3.58 seam saveLastRoundPlan: tanpa user / tanpa agen -> pesan", GroupChatSeam.saveLastRoundPlan(JSONArray(), JSONArray(), 1)?.first?.contains("Belum ada pesan user") == true &&
            GroupChatSeam.saveLastRoundPlan(JSONArray().put(JSONObject().put("role", "user").put("name", "User").put("text", "halo")), JSONArray(), 1)?.first?.contains("belum ada jawaban agen") == true)
    } else {
        cek("3.45 seam buildPrompt null tanpa .so", GroupChatSeam.buildPrompt("Senz", "", "", "", "", "", "", "", emptyList(), "", "", "", false, "", "", "", "", emptyList(), "x") == null)
        cek("3.46 seam groupToolRoute null tanpa .so", GroupChatSeam.groupToolRoute("read_file", false, false, false, false, false, false) == null)
        cek("3.47 seam bgRoundGate null tanpa .so", GroupChatSeam.bgRoundGate(false, false, false, 12, true) == null)
        cek("3.48 seam saveLastRoundPlan null tanpa .so", GroupChatSeam.saveLastRoundPlan(JSONArray(), JSONArray(), 1) == null)
        cek("3.49 runGroupToolDecisionKotlin: intersepsi + gate + shortcut + dialog", bodyContainsLines(grpSrc, "private fun runGroupToolDecisionKotlin(", listOf(
            "if (tool == \"analyze_photo\") return \"msg_analyze_photo\"",
            "if (ToolGate.GATES_DISABLED) return \"execute_gates\"",
            "if (!Tools.isMemoryWrite(tool) && tool != \"create_subroom\" && !ApprovalPanelStore.isAutoAllowBlocked(tool))",
            "if (isGroupAutoApproveOn(app)) return \"execute\"",
            "return \"dialog\" to null"
        )))
        cek("3.50 bgRoundGateKotlin: urutan busy -> f1 -> jobs -> jam -> baterai", bodyContainsLines(grpSrc, "private fun bgRoundGateKotlin(", listOf(
            "if (running) return Triple(\"busy\", \"grup sibuk — ronde foreground sedang berjalan\", \"sibuk\")",
            "prefs.getBoolean(\"f1Off\", false) -> \"F1 dimatikan (kill switch)\"",
            "BgBrain.jobsOff(app) -> \"job dimatikan\"",
            "isQuietHour() -> \"jam hening 23:00-05:00\"",
            "!BgBrain.batteryOk(app) -> \"baterai di bawah 20% dan tidak dicas\""
        )))
        cek("3.51 saveLastRoundAsPlanKotlin: dedup + cap 4000 + judul take(60)", bodyContainsLines(grpSrc, "private fun saveLastRoundAsPlanKotlin(", listOf(
            "val lastUser = rows.indexOfLast { it.role == \"user\" }",
            "if (plans.any { it.title == title && it.body == body.toString() }) return \"Ronde terakhir sudah tersimpan sebelumnya.\"",
            "if (body.length + part.length > 4000) break",
            "val title = userText.take(60)"
        )))
    }

    // ---- §4. DsAgent (diferensial + template JS byte-identik) ----
    fun wEvalParse(raw: String?): String =
        callObj(dsAgentObj, "evalParseKotlin", arrayOf(T_STR), raw) as String
    cek("4.1 evalParseKotlin: string -> dirinya, null -> \"null\"", wEvalParse("\"READY\"") == "READY" && wEvalParse("null") == "null")
    cek("4.2 evalParseKotlin: angka/bool -> toString", wEvalParse("123") == "123" && wEvalParse("1.5") == "1.5" && wEvalParse("1.0") == "1.0" && wEvalParse("true") == "true")
    cek("4.3 evalParseKotlin: kosong -> \"\"; kata telanjang -> literal", wEvalParse("") == "" && wEvalParse("bukan json") == "bukan json" && wEvalParse(null) == "null")
    cek("4.4 evalParseKotlin: spasi + trailing diabaikan", wEvalParse("  \"x\"  trailing") == "x")
    cek("4.5 evalParseKotlin: objek/array semantik (urutan key bebas)", jsonEq(wEvalParse("{\"c\":1,\"t\":\"hi\"}"), "{\"c\":1,\"t\":\"hi\"}") && jsonEq(wEvalParse("[1, \"a\"]"), "[1, \"a\"]"))
    if (rust) {
        cek("4.6 seam parseEvalResult == Kotlin (matriks)", DsAgentSeam.parseEvalResult("\"READY\"") == "READY" && DsAgentSeam.parseEvalResult("null") == "null" &&
            DsAgentSeam.parseEvalResult("123") == "123" && DsAgentSeam.parseEvalResult("1.0") == "1.0" &&
            DsAgentSeam.parseEvalResult("") == "" && DsAgentSeam.parseEvalResult("bukan json") == "" &&
            DsAgentSeam.parseEvalResult("  \"x\"  trailing") == "x" &&
            jsonEq(DsAgentSeam.parseEvalResult("{\"c\":1,\"t\":\"hi\"}") ?: "", "{\"t\":\"hi\",\"c\":1}") &&
            jsonEq(DsAgentSeam.parseEvalResult("[1, \"a\"]") ?: "", "[1, \"a\"]"))
    } else {
        cek("4.6 seam parseEvalResult null tanpa .so", DsAgentSeam.parseEvalResult("\"x\"") == null)
    }

    if (rust) {
        cek("4.7 seam jsonQuote == JSONObject.quote (org.json)", DsAgentSeam.jsonQuote("a/b\"c\\d\te\u0001f\u007Fg") == JSONObject.quote("a/b\"c\\d\te\u0001f\u007Fg"))
        cek("4.8 seam jsonQuote: '/' TIDAK di-escape, kontrol -> \\u0001, emoji mentah", DsAgentSeam.jsonQuote("a/b") == "\"a/b\"" && DsAgentSeam.jsonQuote("\u0001") == "\"\\u0001\"" && DsAgentSeam.jsonQuote("😀") == "\"😀\"")
    } else {
        cek("4.7 seam jsonQuote null tanpa .so", DsAgentSeam.jsonQuote("a") == null)
        cek("4.8 JSONObject.quote (fallback Kotlin): '/' mentah", JSONObject.quote("a/b") == "\"a/b\"" && JSONObject.quote("\u0001") == "\"\\u0001\"")
    }

    fun wAttachJs(b64: String, name: String, mime: String): String =
        callObj(dsAgentObj, "attachJsKotlin", arrayOf(T_STR, T_STR, T_STR), b64, name, mime) as String
    val attachK = wAttachJs("QUJD", "foto.jpg", "image/jpeg")
    cek("4.9 attachJsKotlin: placeholder terganti + isi benar", attachK.contains("atob('QUJD')") && attachK.contains("new File([arr],'foto.jpg',{type:'image/jpeg'})") &&
        !attachK.contains("B64HERE") && !attachK.contains("NAMEHERE") && !attachK.contains("MIMEHERE"))
    if (rust) {
        cek("4.10 seam buildAttachJs byte-identik literal Kotlin", DsAgentSeam.buildAttachJs("QUJD", "foto.jpg", "image/jpeg") == attachK)
    } else {
        cek("4.10 seam buildAttachJs null tanpa .so", DsAgentSeam.buildAttachJs("QUJD", "f", "m") == null)
    }

    fun wSendJs(thinking: Boolean, search: Boolean, quoted: String): String =
        callObj(dsAgentObj, "sendJsKotlin", arrayOf(T_BOOL, T_BOOL, T_STR), thinking, search, quoted) as String
    val sendK = wSendJs(true, false, "\"halo\"")
    cek("4.11 sendJsKotlin: toggle DeepThink true / Search false", sendK.contains("setToggle('DeepThink', true);") && sendK.contains("setToggle('Search', false);") && sendK.contains("setter.call(ta, \"halo\");"))
    if (rust) {
        cek("4.12 seam buildSendJs byte-identik literal Kotlin", DsAgentSeam.buildSendJs(true, false, "\"halo\"") == sendK && DsAgentSeam.buildSendJs(false, true, "\"x\"") == wSendJs(false, true, "\"x\""))
    } else {
        cek("4.12 seam buildSendJs null tanpa .so", DsAgentSeam.buildSendJs(true, false, "\"x\"") == null)
    }

    fun wProbeJs(): String = callObj(dsAgentObj, "probeJsKotlin", arrayOf(), ) as String
    fun wPageJs(): String = callObj(dsAgentObj, "pageTextJsKotlin", arrayOf(), ) as String
    fun wLastJs(): String = callObj(dsAgentObj, "lastStateJsKotlin", arrayOf(), ) as String
    cek("4.13 pageTextJsKotlin: literal persis", wPageJs() == "document.title + '\\n' + ((document.body && document.body.innerText) ? document.body.innerText : '')")
    cek("4.14 probeJsKotlin: READY/LOGIN/LOADING", wProbeJs().contains("return 'READY'") && wProbeJs().contains("return 'LOGIN'") && wProbeJs().contains("return 'LOADING'"))
    cek("4.15 lastStateJsKotlin: walker + JSON.stringify", wLastJs().contains("JSON.stringify({c: n.length, t: t})") && wLastJs().contains("var BLOCKS = {P:1,DIV:1,LI:1"))
    if (rust) {
        cek("4.16 seam dsJs byte-identik 3 template", DsAgentSeam.dsJs("probe") == wProbeJs() && DsAgentSeam.dsJs("page_text") == wPageJs() && DsAgentSeam.dsJs("last_state") == wLastJs())
        cek("4.17 seam dsJs: nama tak dikenal -> null (fallback Kotlin)", DsAgentSeam.dsJs("tidak_ada") == null)
    } else {
        cek("4.16 seam dsJs null tanpa .so (lihat §1.10)", true)
        cek("4.17 (kotlin) template literal == merge/ DsAgent (cek sumber)", mergeDs.contains("document.title + '\\\\n' + ((document.body && document.body.innerText)") && dsSrc.contains("document.title + '\\\\n' + ((document.body && document.body.innerText)"))
    }

    if (rust) {
        cek("4.18 seam pollStep: timeout di tries>150, bukan di 150", DsAgentSeam.pollStep(151, 0, "", "", 0, 0, "")?.outcome == "timeout" && DsAgentSeam.pollStep(150, 0, "", "", 0, 0, "")?.outcome == "unchanged")
        cek("4.19 seam pollStep: belum berubah -> unchanged + stable 0 + lastText lama", DsAgentSeam.pollStep(0, 2, "base", "last", 2, 2, "base") == DsAgentSeam.PollStep("unchanged", false, 0, "last"))
        cek("4.20 seam pollStep: berubah + teks baru -> continue + partial", DsAgentSeam.pollStep(0, 2, "base", "last", 0, 3, "baru") == DsAgentSeam.PollStep("continue", true, 0, "baru"))
        cek("4.21 seam pollStep: stabil 3x -> done (tanpa partial)", DsAgentSeam.pollStep(1, 2, "base", "sama", 2, 3, "sama") == DsAgentSeam.PollStep("done", false, 3, "sama"))
        cek("4.22 seam pollStep: stabil 2x -> continue", DsAgentSeam.pollStep(1, 2, "base", "sama", 1, 3, "sama") == DsAgentSeam.PollStep("continue", false, 2, "sama"))
        cek("4.23 seam pollStep: teks kosong tidak picu partial/stabil walau count naik", DsAgentSeam.pollStep(0, 2, "base", "", 0, 3, "") == DsAgentSeam.PollStep("continue", false, 0, ""))
        cek("4.24 seam pollStep: count naik saja sudah 'berubah' (stabil naik)", DsAgentSeam.pollStep(0, 2, "base", "base", 0, 3, "base") == DsAgentSeam.PollStep("continue", false, 1, "base"))
    } else {
        cek("4.18 seam pollStep null tanpa .so", DsAgentSeam.pollStep(0, 2, "base", "last", 0, 3, "baru") == null)
        cek("4.19 pollAnswerKotlin: logika changed/stable/onPartial utuh", bodyContainsLines(dsSrc, "private fun pollAnswerKotlin(", listOf(
            "val changed = (count > baseCount) || (text != baseText && text.isNotBlank())",
            "if (text != lastText && text.isNotBlank()) onPartial(text)",
            "val newStable = if (text == lastText && text.isNotBlank()) stable + 1 else 0",
            "if (newStable >= 3)"
        )))
    }

    // ---- §5. OcCoord (diferensial wrapper + seam langsung) ----
    cek("5.1 resultNotifIdFor: 1 -> 40001, 0 -> 40000", OcCoord.resultNotifIdFor(1) == 40001 && OcCoord.resultNotifIdFor(0) == 40000)
    cek("5.2 resultNotifIdFor: wrap int (40000+3000000000)", OcCoord.resultNotifIdFor(3_000_000_000L) == -1294927296)
    if (rust) {
        cek("5.3 seam resultNotifIdFor == wrapper", OcCoordSeam.resultNotifIdFor(1) == 40001 && OcCoordSeam.resultNotifIdFor(3_000_000_000L) == -1294927296)
        cek("5.4 seam ruleDefaults == RULE_DEFAULTS (10 entri, urutan sumber)", OcCoordSeam.ruleDefaults() == OcCoord.RULE_DEFAULTS.toList())
    } else {
        cek("5.3 seam resultNotifIdFor null tanpa .so (lihat §1.11)", true)
        cek("5.4 RULE_DEFAULTS: 10 entri (cek sumber)", OcCoord.RULE_DEFAULTS.size == 10 && OcCoord.RULE_DEFAULTS["path_mode"] == "resmi" && OcCoord.RULE_DEFAULTS["session_ctx_limit"] == "350000" && OcCoord.RULE_DEFAULTS["oc_task_max_steps_vc303"] == "200")
    }

    fun wSanitize(key: String, raw: String): String =
        callObj(ocCoordObj, "ruleSanitizeKotlin", arrayOf(T_STR, T_STR), key, raw) as String
    cek("5.5 ruleSanitizeKotlin: path_mode resmi/mandiri (trim+lower)", wSanitize("path_mode", "mandiri") == "mandiri" && wSanitize("path_mode", " MANDIRI ") == "mandiri" && wSanitize("path_mode", "x") == "resmi")
    cek("5.6 ruleSanitizeKotlin: auto_approve on/true/yes/aktif -> \"\"", wSanitize("auto_approve", "on") == "" && wSanitize("auto_approve", "AKTIF") == "" && wSanitize("auto_approve", "yes") == "" && wSanitize("auto_approve", "true") == "")
    cek("5.7 ruleSanitizeKotlin: auto_approve lain -> raw TANPA trim", wSanitize("auto_approve", "off") == "off" && wSanitize("auto_approve", "  off  ") == "  off  ")
    cek("5.8 ruleSanitizeKotlin: kunci lain -> raw apa adanya", wSanitize("other_key", "  v  ") == "  v  ")
    if (rust) {
        cek("5.9 seam sanitizeRule == Kotlin", OcCoordSeam.sanitizeRule("path_mode", " MANDIRI ") == "mandiri" && OcCoordSeam.sanitizeRule("auto_approve", "AKTIF") == "" && OcCoordSeam.sanitizeRule("auto_approve", "  off  ") == "  off  ")
        cek("5.10 seam ruleInt: sanitasi + trim + toIntOrNull + default", OcCoordSeam.ruleInt("session_ctx_limit", "350000", 20) == 350000 && OcCoordSeam.ruleInt("x", "+20", 0) == 20 &&
            OcCoordSeam.ruleInt("x", "20.0", 7) == 7 && OcCoordSeam.ruleInt("x", "", 7) == 7 && OcCoordSeam.ruleInt("x", "  42  ", 0) == 42 && OcCoordSeam.ruleInt("x", "abc", 9) == 9)
    } else {
        cek("5.9 seam sanitizeRule null tanpa .so", OcCoordSeam.sanitizeRule("path_mode", "resmi") == null)
        cek("5.10 seam ruleInt null tanpa .so", OcCoordSeam.ruleInt("x", "1", 0) == null)
        cek("5.11 ruleInt fallback: rule().trim().toIntOrNull() ?: def (cek sumber)", funBody(ocSrc, "private fun ruleInt(").contains("rule(ctx, key).trim().toIntOrNull() ?: def"))
    }

    fun wRotate(sid: String, prevCtx: Long, ctxLimit: Long, taskId: Long, taskText: String): OcCoordSeam.SessionRotate =
        callObj(ocCoordObj, "sessionRotateKotlin", arrayOf(T_STR, T_LONG, T_LONG, T_LONG, T_STR), sid, prevCtx, ctxLimit, taskId, taskText) as OcCoordSeam.SessionRotate
    cek("5.12 sessionRotateKotlin: sid kosong -> buat sesi + judul", run {
        val r = wRotate("", 0, 350000, 7, "kerjakan laporan keuangan bulanan dengan teliti dan lengkap sekali")
        r.needCreate && r.rotateNote == "" && r.title!!.startsWith("🤖 Harness #7 kerjakan laporan keuangan bulanan dengan")
    })
    cek("5.13 sessionRotateKotlin: konteks lewat batas -> rotasi + catatan", run {
        val r = wRotate("s1", 400000, 350000, 7, "x")
        r.needCreate && r.rotateNote.contains("Sesi dirotasi") && r.title == "🤖 Harness #7 x"
    })
    cek("5.14 sessionRotateKotlin: dalam batas -> tidak rotasi", wRotate("s1", 100, 350000, 7, "x") == OcCoordSeam.SessionRotate(false, "", null))
    cek("5.15 sessionRotateKotlin: tepat di batas -> tidak rotasi (>)", wRotate("s1", 350000, 350000, 7, "x").needCreate == false)
    cek("5.16 sessionRotateKotlin: judul take(42)", wRotate("", 0, 350000, 7, "a".repeat(60)).title == "🤖 Harness #7 " + "a".repeat(42))
    if (rust) {
        cek("5.17 seam sessionRotate == Kotlin", OcCoordSeam.sessionRotate("", 0, 350000, 7, "x") == wRotate("", 0, 350000, 7, "x") &&
            OcCoordSeam.sessionRotate("s1", 400000, 350000, 7, "x")?.rotateNote?.contains("Sesi dirotasi") == true &&
            OcCoordSeam.sessionRotate("s1", 100, 350000, 7, "x") == OcCoordSeam.SessionRotate(false, "", null))
    } else {
        cek("5.17 seam sessionRotate null tanpa .so", OcCoordSeam.sessionRotate("", 0, 350000, 7, "x") == null)
    }

    cek("5.18 OcTaskBudgetVc303: 200 tidak lewat, 201 lewat", !budgetOver(newBudget(200), 200) && budgetOver(newBudget(200), 201))
    if (rust) {
        cek("5.19 seam budgetOver == Kotlin", OcCoordSeam.budgetOver(200, 200) == false && OcCoordSeam.budgetOver(201, 200) == true)
    } else {
        cek("5.19 seam budgetOver null tanpa .so", OcCoordSeam.budgetOver(201, 200) == null)
    }

    fun wSpin(text: String): String? =
        callObj(ocCoordObj, "spinningPatternVc277", arrayOf(T_STR), text) as String?
    val spinLine = { n: Int -> "mengerjakan langkah $n dari rencana panjang" }
    val spinText3 = (1..3).joinToString("\n") { spinLine(it) }
    cek("5.20 spinningPattern: pola sama 3x -> cuplikan baris pertama", wSpin(spinText3) == "mengerjakan langkah 1 dari rencana panjang")
    cek("5.21 spinningPattern: 2x -> null, baris pendek 5x -> null", wSpin((1..2).joinToString("\n") { spinLine(it) }) == null && wSpin((1..5).joinToString("\n") { "pendek $it" }) == null)
    if (rust) {
        cek("5.22 seam spinningPattern == Kotlin", OcCoordSeam.spinningPattern(spinText3) == "mengerjakan langkah 1 dari rencana panjang" && OcCoordSeam.spinningPattern("a\nb") == null)
    } else {
        cek("5.22 seam spinningPattern null = tidak ada pola ATAU seam gagal (kontrak ambigu)", OcCoordSeam.spinningPattern("a\nb") == null)
    }

    fun wFailed(text: String): Boolean =
        callObj(ocCoordObj, "looksFailedVc262", arrayOf(T_STR), text) as Boolean
    cek("5.23 looksFailed: 5 penanda (case-insensitive)", wFailed("gagal total") && wFailed("ada ❌ di sini") && wFailed("tidak berhasil") && wFailed("error koneksi") && wFailed("tidak selesai") && wFailed("GAGAL"))
    cek("5.24 looksFailed: bersih -> false", !wFailed("semua beres") && !wFailed("tugas selesai dengan baik"))
    if (rust) {
        cek("5.25 seam looksFailed == Kotlin", OcCoordSeam.looksFailed("tugas gagal ❌") == true && OcCoordSeam.looksFailed("semua beres") == false)
    } else {
        cek("5.25 seam looksFailed null tanpa .so", OcCoordSeam.looksFailed("gagal") == null)
    }

    fun wHonest(status: String, spinning: String?, resultFull: String): Pair<String, String> =
        @Suppress("UNCHECKED_CAST")
        (callObj(ocCoordObj, "honestStatusKotlin", arrayOf(T_STR, T_STR, T_STR), status, spinning, resultFull) as Pair<String, String>)
    cek("5.26 honestStatusKotlin: done bersih -> done utuh", wHonest("done", null, "hasil ok") == ("done" to "hasil ok"))
    cek("5.27 honestStatusKotlin: spinning -> partial + prefix ⛔", wHonest("done", "pola x", "hasil") == ("partial" to "⛔ Hasil koordinator terdeteksi berputar: pola yang sama berulang 3x atau lebih dalam teks hasil (contoh: \"pola x\") — status SEBAGIAN, periksa hasilnya sebelum dipakai.\n\nhasil"))
    cek("5.28 honestStatusKotlin: failed tetap failed, isi gagal -> partial", wHonest("failed", null, "gagal ❌") == ("failed" to "gagal ❌") && wHonest("done", null, "tugas gagal") == ("partial" to "tugas gagal"))
    if (rust) {
        cek("5.29 seam honestStatus == Kotlin", OcCoordSeam.honestStatus("done", "pola x", "hasil") == wHonest("done", "pola x", "hasil") && OcCoordSeam.honestStatus("done", null, "tugas gagal") == ("partial" to "tugas gagal"))
    } else {
        cek("5.29 seam honestStatus null tanpa .so", OcCoordSeam.honestStatus("done", null, "x") == null)
    }

    fun wScan(msgs: JSONArray, baseline: Int): Triple<Int, Boolean, String?> =
        @Suppress("UNCHECKED_CAST")
        (callObj(ocCoordObj, "scanMessages", arrayOf(T_JSONARR, T_INT), msgs, baseline) as Triple<Int, Boolean, String?>)
    val scanMsgs = JSONArray()
        .put(ocMsg("user", "halo"))
        .put(ocMsg("assistant", "jawab 1", finish = "stop"))
        .put(ocMsg("assistant", "jawab 2", errName = "EndpointError", errMsg = "Endpoint is unavailable (HTTP 402)", errCode = 402))
        .put(ocMsg("assistant", "jawab 3"))
    cek("5.30 scanMessages: 3 asisten + finished + error terakhir (402)", wScan(scanMsgs, 0) == Triple(3, true, "Endpoint is unavailable (HTTP 402) (HTTP 402)"))
    cek("5.31 scanMessages: baseline 2 -> 2 asisten + tidak finished", wScan(scanMsgs, 2) == Triple(2, false, "Endpoint is unavailable (HTTP 402) (HTTP 402)"))
    cek("5.32 scanMessages: error tanpa data -> nama saja", wScan(JSONArray().put(ocMsg("assistant", "x", errName = "Boom")), 0) == Triple(1, false, "Boom"))
    cek("5.33 scanMessages: kosong -> (0, false, null)", wScan(JSONArray(), 0) == Triple(0, false, null))
    if (rust) {
        cek("5.34 seam scanMessages == wrapper", OcCoordSeam.scanMessages(scanMsgs, 0) == Triple(3, true, "Endpoint is unavailable (HTTP 402) (HTTP 402)") && OcCoordSeam.scanMessages(scanMsgs, 2) == Triple(2, false, "Endpoint is unavailable (HTTP 402) (HTTP 402)"))
    } else {
        cek("5.34 seam scanMessages null tanpa .so", OcCoordSeam.scanMessages(scanMsgs, 0) == null)
    }

    fun wAwait(txt: String, timedOut: Boolean, sid: String): Pair<String, String> =
        @Suppress("UNCHECKED_CAST")
        (callObj(ocCoordObj, "awaitBody", arrayOf(T_STR, T_BOOL, T_STR), txt, timedOut, sid) as Pair<String, String>)
    cek("5.35 awaitBody: kosong+timeout / kosong / parsial+timeout / hasil", wAwait("", true, "s1") == ("failed" to "⏱ Timeout tanpa jawaban teks dari agen (sesi s1). Cek sesi langsung di serve/Termux.") &&
        wAwait("", false, "s1") == ("done" to "(Agen selesai tanpa teks jawaban — kemungkinan semua hasil berupa aksi tool. Cek sesi s1 di Panel/serve.)") &&
        wAwait("parsial", true, "s1") == ("failed" to "⏱ Timeout — agen dihentikan; hasil parsial:\nparsial") &&
        wAwait("hasil", false, "s1") == ("done" to "hasil"))
    if (rust) {
        cek("5.36 seam awaitBody == wrapper", OcCoordSeam.awaitBody("", true, "s1") == ("failed" to "⏱ Timeout tanpa jawaban teks dari agen (sesi s1). Cek sesi langsung di serve/Termux.") && OcCoordSeam.awaitBody("hasil", false, "s1") == ("done" to "hasil"))
    } else {
        cek("5.36 seam awaitBody null tanpa .so", OcCoordSeam.awaitBody("x", false, "s1") == null)
    }

    fun wIncr(msgs: JSONArray, baseline: Int): String =
        callObj(ocCoordObj, "incrementalAssistantTextVc303Kotlin", arrayOf(T_JSONARR, T_INT), msgs, baseline) as String
    fun wCollect(msgs: JSONArray, baseline: Int): Pair<String, Long> =
        @Suppress("UNCHECKED_CAST")
        (callObj(ocCoordObj, "collectResultKotlin", arrayOf(T_JSONARR, T_INT), msgs, baseline) as Pair<String, Long>)
    val ms = msgsSample()
    cek("5.37 incrementalAssistantText: gabung + lewati '## Objective'", wIncr(ms, 0) == "Jawaban satu\n\nJawaban dua")
    cek("5.38 collectResult: teks + ctxSize last-wins (input+cache)", wCollect(ms, 0) == ("Jawaban satu\n\nJawaban dua" to 200L))
    cek("5.39 incremental/collect baseline 2/3/4", wIncr(ms, 2) == "Jawaban dua" && wCollect(ms, 2) == ("Jawaban dua" to 200L) && wIncr(ms, 3) == "Jawaban dua" && wCollect(ms, 4) == ("" to 0L))
    if (rust) {
        cek("5.40 seam incrementalText/collectResult == Kotlin", OcCoordSeam.incrementalText(ms, 0) == "Jawaban satu\n\nJawaban dua" && OcCoordSeam.collectResult(ms, 0) == ("Jawaban satu\n\nJawaban dua" to 200L) && OcCoordSeam.collectResult(ms, 2) == ("Jawaban dua" to 200L))
    } else {
        cek("5.40 seam incrementalText/collectResult null tanpa .so", OcCoordSeam.incrementalText(ms, 0) == null && OcCoordSeam.collectResult(ms, 0) == null)
    }

    fun wPerm(line: String, sid: String): Triple<String, String, String>? =
        @Suppress("UNCHECKED_CAST")
        (callObj(ocCoordObj, "parsePermEvent", arrayOf(T_STR, T_STR), line, sid) as Triple<String, String, String>?)
    val permLine = "data: {\"type\":\"permission_request\",\"properties\":{\"sessionID\":\"s1\",\"id\":\"p1\",\"title\":\"Izin hapus\",\"tool\":\"hapus\",\"pattern\":\"\",\"command\":\"rm -rf\",\"args\":\"x\",\"permission\":{\"a\":1}}}"
    cek("5.41 parsePermEvent: id + judul + detail (field kosong dibuang)", run {
        val e = wPerm(permLine, "s1")
        e != null && e.first == "p1" && e.second == "Izin hapus" && e.third.contains("tool=hapus") && e.third.contains("command=rm -rf") && e.third.contains("args=x") && e.third.contains("permission=") && !e.third.contains("pattern=")
    })
    cek("5.42 parsePermEvent: judul fallback pattern -> default", wPerm("data: {\"type\":\"permission\",\"properties\":{\"sessionID\":\"s1\",\"id\":\"p2\",\"pattern\":\"ls\"}}", "s1")?.second == "ls" &&
        wPerm("data: {\"type\":\"permission\",\"properties\":{\"sessionID\":\"s1\",\"id\":\"p3\"}}", "s1")?.second == "izin tool OpenCode")
    cek("5.43 parsePermEvent: bukan data / tipe lain / sid beda / id kosong / JSON rusak -> null", wPerm("event: x", "s1") == null &&
        wPerm("data: {\"type\":\"other\",\"properties\":{}}", "s1") == null &&
        wPerm("data: {\"type\":\"permission\",\"properties\":{\"sessionID\":\"s2\",\"id\":\"p1\"}}", "s1") == null &&
        wPerm("data: {\"type\":\"permission\",\"properties\":{\"sessionID\":\"s1\",\"id\":\"  \"}}", "s1") == null &&
        wPerm("data: {bukan json", "s1") == null)
    cek("5.44 parsePermEvent: judul dicap 300", wPerm("data: {\"type\":\"permission\",\"properties\":{\"sessionID\":\"s1\",\"id\":\"p1\",\"title\":\"" + "t".repeat(400) + "\"}}", "s1")?.second?.length == 300)
    if (rust) {
        cek("5.45 seam parsePermEvent == wrapper (isi + null)", run {
            val e = OcCoordSeam.parsePermEvent(permLine, "s1")
            e != null && e.first == "p1" && e.second == "Izin hapus" && e.third.contains("tool=hapus") && !e.third.contains("pattern=")
        } && OcCoordSeam.parsePermEvent("event: x", "s1") == null)
    } else {
        cek("5.45 seam parsePermEvent null = bukan event ATAU seam gagal (kontrak ambigu)", OcCoordSeam.parsePermEvent("event: x", "s1") == null)
    }

    fun wStale(ts: Long, nowMs: Long, ttlMin: Int): Boolean =
        callObj(ocCoordObj, "permStale", arrayOf(T_LONG, T_LONG, T_INT), ts, nowMs, ttlMin) as Boolean
    cek("5.46 permStale: lewat TTL -> true, tepat TTL -> false, ts 0 -> false", wStale(1000, 1000 + 30 * 60_000L + 1, 30) && !wStale(1000, 1000 + 30 * 60_000L, 30) && !wStale(0, 5000, 30) && !wStale(1000, 2000, 30))
    if (rust) {
        cek("5.47 seam permStale == Kotlin", OcCoordSeam.permStale(1000, 1000 + 30 * 60_000L + 1, 30) == true && OcCoordSeam.permStale(1000, 1000 + 30 * 60_000L, 30) == false)
        cek("5.48 seam permDecision: auto_gate / auto_pattern / park", OcCoordSeam.permDecision(true, "", "x") == "auto_gate" &&
            OcCoordSeam.permDecision(false, "bash,ls", "jalankan bash rm") == "auto_pattern" &&
            OcCoordSeam.permDecision(false, "*", "apa saja") == "auto_pattern" &&
            OcCoordSeam.permDecision(false, "bash", "jalankan ls") == "park" &&
            OcCoordSeam.permDecision(false, "", "x") == "park" &&
            OcCoordSeam.permDecision(false, "bash", "JALANKAN BASH") == "auto_pattern")
    } else {
        cek("5.47 seam permStale null tanpa .so", OcCoordSeam.permStale(1000, 2000, 30) == null)
        cek("5.48 seam permDecision null tanpa .so", OcCoordSeam.permDecision(true, "", "x") == null)
        cek("5.49 permDecisionKotlin: gate -> pola -> park (cek sumber)", bodyContainsLines(ocSrc, "private fun permDecisionKotlin(", listOf(
            "if (ToolGate.GATES_DISABLED) return \"auto_gate\"",
            "if (patterns.any { it == \"*\" || title.contains(it, ignoreCase = true) }) return \"auto_pattern\"",
            "return \"park\""
        )))
    }

    fun wTaskKotlin(t: MemoryDb.OcTask, peek: String): String =
        callObj(ocCoordObj, "taskResultTextKotlin", arrayOf(MemoryDb.OcTask::class.java, T_STR), t, peek) as String
    cek("5.50 taskResultTextKotlin: done/failed/cancelled + running+peek", wTaskKotlin(MemoryDb.OcTask(3, "c1", "kerjakan x", "", "done", "beres", 0, 0), "") == "Tugas #3 [done] — kerjakan x\nHASIL:\nberes" &&
        wTaskKotlin(MemoryDb.OcTask(3, "c1", "kerjakan x", "", "failed", "boom", 0, 0), "") == "Tugas #3 [failed] — kerjakan x\nGAGAL: boom" &&
        wTaskKotlin(MemoryDb.OcTask(3, "c1", "kerjakan x", "", "cancelled", "cat", 0, 0), "") == "Tugas #3 [cancelled] — kerjakan x\nDibatalkan. Catatan: cat" &&
        wTaskKotlin(MemoryDb.OcTask(3, "c1", "kerjakan x", "s9", "running", "", 0, 0), "progres 50%") == "Tugas #3 [running] — kerjakan x\nMasih running.\nProgres terakhir agen: progres 50%")
    cek("5.51 taskResultTextKotlin: peek kosong -> tanpa baris; peek isi -> baris progres (gate sesi ada di wrapper)", wTaskKotlin(MemoryDb.OcTask(3, "c1", "kerjakan x", "s9", "running", "", 0, 0), "") == "Tugas #3 [running] — kerjakan x\nMasih running." &&
        wTaskKotlin(MemoryDb.OcTask(3, "c1", "kerjakan x", "", "running", "", 0, 0), "x") == "Tugas #3 [running] — kerjakan x\nMasih running.\nProgres terakhir agen: x")
    if (rust) {
        val tDone = JSONObject().put("status", "done").put("text", "kerjakan x").put("result", "beres").put("sessionId", "")
        val tRun = JSONObject().put("status", "running").put("text", "kerjakan x").put("result", "").put("sessionId", "s9")
        cek("5.52 seam taskResultText: null -> tidak ditemukan; done/failed/cancelled", OcCoordSeam.taskResultText(3, null, "") == "Tugas #3 tidak ditemukan." &&
            OcCoordSeam.taskResultText(3, tDone, "") == "Tugas #3 [done] — kerjakan x\nHASIL:\nberes" &&
            OcCoordSeam.taskResultText(3, JSONObject().put("status", "failed").put("text", "kerjakan x").put("result", "boom").put("sessionId", ""), "") == "Tugas #3 [failed] — kerjakan x\nGAGAL: boom" &&
            OcCoordSeam.taskResultText(3, JSONObject().put("status", "cancelled").put("text", "kerjakan x").put("result", "cat").put("sessionId", ""), "") == "Tugas #3 [cancelled] — kerjakan x\nDibatalkan. Catatan: cat")
        cek("5.53 seam taskResultText: running + peek (sesi kosong / peek kosong -> tanpa baris)", OcCoordSeam.taskResultText(3, tRun, "progres 50%") == "Tugas #3 [running] — kerjakan x\nMasih running.\nProgres terakhir agen: progres 50%" &&
            OcCoordSeam.taskResultText(3, JSONObject().put("status", "running").put("text", "kerjakan x").put("result", "").put("sessionId", ""), "x") == "Tugas #3 [running] — kerjakan x\nMasih running." &&
            OcCoordSeam.taskResultText(3, tRun, "") == "Tugas #3 [running] — kerjakan x\nMasih running.")
    } else {
        cek("5.52 seam taskResultText null tanpa .so", OcCoordSeam.taskResultText(3, JSONObject().put("status", "done"), "") == null)
        cek("5.53 (kotlin) taskResultTextKotlin == seam (cek via §5.50-5.51)", true)
    }

    fun wPeek(msgs: JSONArray): String =
        callObj(ocCoordObj, "peekSessionTextKotlin", arrayOf(T_JSONARR), msgs) as String
    cek("5.54 peekSessionTextKotlin: teks asisten terakhir, take(700)", wPeek(ms) == "Jawaban dua" && wPeek(JSONArray()).isEmpty())
    if (rust) {
        cek("5.55 seam peekText == Kotlin", OcCoordSeam.peekText(ms) == "Jawaban dua" && OcCoordSeam.peekText(JSONArray()) == "")
    } else {
        cek("5.55 seam peekText null tanpa .so", OcCoordSeam.peekText(ms) == null)
    }

    if (rust) {
        cek("5.56 seam serveSelect fase 0 resmi: 4097 hidup -> return; mati -> start_4097", OcCoordSeam.serveSelect(0, "resmi", false, true, false) == OcCoordSeam.ServeSelect(4097, "", "return") &&
            OcCoordSeam.serveSelect(0, "resmi", false, false, false) == OcCoordSeam.ServeSelect(null, "", "start_4097") &&
            OcCoordSeam.serveSelect(0, "resmi", false, false, true) == OcCoordSeam.ServeSelect(4096, "", "return"))
        cek("5.57 seam serveSelect fase 1 resmi: nyala -> return + catatan; mati -> fail", OcCoordSeam.serveSelect(1, "resmi", false, true, false) == OcCoordSeam.ServeSelect(4097, "(Serve 4097 dinyalakan harness via Termux.)", "return") &&
            OcCoordSeam.serveSelect(1, "resmi", false, false, true) == OcCoordSeam.ServeSelect(4096, "(Serve 4096 dinyalakan harness via Termux.)", "return") &&
            OcCoordSeam.serveSelect(1, "resmi", false, false, false) == OcCoordSeam.ServeSelect(null, "tidak ada opencode serve yang hidup (4097/4096 mati; upaya menyalakan via Termux gagal). Nyalakan dari Setup tombol 34 atau buka Termux.", "fail"))
        cek("5.58 seam serveSelect mandiri: 4099 -> start_4099 -> fallback resmi", OcCoordSeam.serveSelect(0, "mandiri", true, false, false) == OcCoordSeam.ServeSelect(4099, "", "return") &&
            OcCoordSeam.serveSelect(0, "mandiri", false, false, false) == OcCoordSeam.ServeSelect(null, "", "start_4099") &&
            OcCoordSeam.serveSelect(1, "mandiri", true, false, false) == OcCoordSeam.ServeSelect(4099, "(Serve mandiri 4099 dinyalakan harness.)", "return") &&
            OcCoordSeam.serveSelect(1, "mandiri", false, true, false) == OcCoordSeam.ServeSelect(4097, "(Serve mandiri gagal — fallback jalur resmi 4097.)", "return") &&
            OcCoordSeam.serveSelect(1, "mandiri", false, false, false) == OcCoordSeam.ServeSelect(null, "serve mandiri 4099 gagal dinyalakan dan jalur resmi (4097/4096) mati semua.", "fail"))
    } else {
        cek("5.56 seam serveSelect null tanpa .so", OcCoordSeam.serveSelect(0, "resmi", false, true, false) == null)
        cek("5.57 ensureServeKotlin == merge ensureServe (norm)", sameFunBodyNorm(ocSrc, "private fun ensureServeKotlin(", mergeOc, "fun ensureServe("))
        cek("5.58 (kotlin) pohon serve dua fase ada di ensureServeKotlin", funBody(ocSrc, "private fun ensureServeKotlin(").contains("startServeViaTermux(ctx, 4099)") && funBody(ocSrc, "private fun ensureServeKotlin(").contains("startServeViaTermux(ctx, 4097)"))
    }

    fun wProbeRead(force: Boolean, now: Long, ts: Long, raw: String): Map<Int, Boolean>? =
        @Suppress("UNCHECKED_CAST")
        (callObj(ocCoordObj, "probeCacheReadKotlin", arrayOf(T_BOOL, T_LONG, T_LONG, T_STR), force, now, ts, raw) as Map<Int, Boolean>?)
    cek("5.59 probeCacheReadKotlin: segar -> hit + map", wProbeRead(false, 15000, 0, "4097=1,4096=0,4099=1") == mapOf(4097 to true, 4096 to false, 4099 to true))
    cek("5.60 probeCacheReadKotlin: kedaluwarsa / force / kosong -> null", wProbeRead(false, 25000, 0, "4097=1") == null && wProbeRead(true, 1000, 0, "4097=1") == null && wProbeRead(false, 1000, 0, "") == null)
    cek("5.61 probeCacheReadKotlin: entri rusak dilewati", wProbeRead(false, 1000, 0, "4097=1,rusak,4096=x") == mapOf(4097 to true, 4096 to false))
    if (rust) {
        cek("5.62 seam probeCacheRead == Kotlin", OcCoordSeam.probeCacheRead(false, 15000, 0, "4097=1,4096=0,4099=1") == OcCoordSeam.ProbeCacheRead(true, mapOf(4097 to true, 4096 to false, 4099 to true)) &&
            OcCoordSeam.probeCacheRead(false, 25000, 0, "4097=1")?.hit == false &&
            OcCoordSeam.probeCacheRead(false, 1000, 0, "4097=1,rusak,4096=x")?.probes == mapOf(4097 to true, 4096 to false))
        cek("5.63 seam probeCacheWrite: format '4097=..,4096=..,4099=..'", OcCoordSeam.probeCacheWrite(true, false, true) == "4097=1,4096=0,4099=1" && OcCoordSeam.probeCacheWrite(false, false, false) == "4097=0,4096=0,4099=0")
    } else {
        cek("5.62 seam probeCacheRead null tanpa .so", OcCoordSeam.probeCacheRead(false, 1000, 0, "4097=1") == null)
        cek("5.63 seam probeCacheWrite null tanpa .so", OcCoordSeam.probeCacheWrite(true, true, true) == null)
    }

    if (rust) {
        val rules = JSONObject().put("session_ctx_limit", "350000").put("chain_max", "3").put("chain_batt_min", "20").put("quiet_hours", "22-5").put("auto_approve", "")
        val recent = JSONArray().put(JSONObject().put("id", 9).put("status", "done").put("text", "tugas penting sekali").put("result", "hasil\nbaris dua"))
        val st = OcCoordSeam.statusText("resmi", true, false, false, 5, 2, 1, "", rules, true, recent)
        cek("5.64 seam statusText: kepala jalur + antrean + aturan + gate + tugas terakhir", st != null &&
            st.startsWith("Jalur mode: resmi • 4097 (resmi): HIDUP • 4096 (cadangan): MATI • 4099 (mandiri): MATI") &&
            st.contains("Antrean: 2 menunggu • 1 berjalan (tugas #5)") &&
            st.contains("batas konteks sesi 350000 token • rantai maks 3 tugas • baterai min rantai 20% • jam hening '22-5' • auto-approve ''") &&
            st.contains("Izin koordinator disetujui otomatis (gate nonaktif)") &&
            st.contains("- #9 [done] tugas penting sekali → hasil baris dua"))
        val st2 = OcCoordSeam.statusText("resmi", false, false, false, 0, 0, 0, "Izin hapus", JSONObject(), false, JSONArray())
        cek("5.65 seam statusText: aturan hilang -> 'null' + izin menunggu", st2 != null && st2.contains("batas konteks sesi null token") && st2.contains("⏳ IZIN MENUNGGU KEPUTUSAN: Izin hapus"))
    } else {
        cek("5.64 seam statusText null tanpa .so", OcCoordSeam.statusText("resmi", true, false, false, 0, 0, 0, "", JSONObject(), false, JSONArray()) == null)
        cek("5.65 statusTextKotlin == merge statusText (norm)", sameFunBodyNorm(ocSrc, "private fun statusTextKotlin(", mergeOc, "fun statusText("))
    }

    fun wBattPct(level: Int, scale: Int): Int =
        callObj(ocCoordObj, "batteryPctKotlin", arrayOf(T_INT, T_INT), level, scale) as Int
    cek("5.66 batteryPctKotlin: 80/0/-1/scale 0/1-3/99", wBattPct(80, 100) == 80 && wBattPct(0, 100) == 0 && wBattPct(-1, 100) == 100 && wBattPct(50, 0) == 100 && wBattPct(1, 3) == 33 && wBattPct(99, 100) == 99)
    if (rust) {
        cek("5.67 seam batteryPct == Kotlin", OcCoordSeam.batteryPct(80, 100) == 80 && OcCoordSeam.batteryPct(-1, 100) == 100 && OcCoordSeam.batteryPct(50, 0) == 100 && OcCoordSeam.batteryPct(1, 3) == 33)
    } else {
        cek("5.67 seam batteryPct null tanpa .so", OcCoordSeam.batteryPct(80, 100) == null)
    }

    fun wQuietNow(spec: String, hour: Int): Boolean =
        callObj(ocCoordObj, "isQuietNowKotlin", arrayOf(T_STR, T_INT), spec, hour) as Boolean
    cek("5.68 isQuietNowKotlin: 23-5 / 8-17 / kosong / tanpa dash / bukan angka / start==end", wQuietNow("23-5", 23) && wQuietNow("23-5", 4) && !wQuietNow("23-5", 5) && !wQuietNow("23-5", 12) &&
        wQuietNow("8-17", 12) && !wQuietNow("8-17", 17) && !wQuietNow("", 12) && !wQuietNow("23", 23) && !wQuietNow("x-y", 12) && !wQuietNow("5-5", 5) && wQuietNow(" 23 - 5 ", 23))
    if (rust) {
        cek("5.69 seam quietNow == Kotlin", OcCoordSeam.quietNow("23-5", 23) == true && OcCoordSeam.quietNow("23-5", 5) == false && OcCoordSeam.quietNow("5-5", 5) == false && OcCoordSeam.quietNow(" 23 - 5 ", 23) == true)
        cek("5.70 seam runloopGate: baterai -> jam -> run (delay di-echo)", OcCoordSeam.runloopGate(true, 10, 20, "", 12, 10) == Triple("delay_battery", "Rantai ditunda: baterai di bawah 20%", 10L) &&
            OcCoordSeam.runloopGate(true, 90, 20, "22-5", 23, 10) == Triple("delay_quiet", "Rantai ditunda: jam hening aktif", 10L) &&
            OcCoordSeam.runloopGate(true, 90, 20, "22-5", 12, 10) == Triple("run", null, 10L) &&
            OcCoordSeam.runloopGate(false, 10, 20, "", 12, 7) == Triple("run", null, 7L))
    } else {
        cek("5.69 seam quietNow null tanpa .so", OcCoordSeam.quietNow("23-5", 23) == null)
        cek("5.70 seam runloopGate null tanpa .so", OcCoordSeam.runloopGate(true, 10, 20, "", 12, 10) == null)
        cek("5.71 runloopGateKotlin == gerbang inline merge runLoop", bodyContainsLines(ocSrc, "private fun runloopGateKotlin(", listOf(
            "Rantai ditunda: baterai di bawah", "Rantai ditunda: jam hening aktif", "chain_delay_sec", "Thread.sleep(delay * 1000L)"
        )) && bodyContainsLines(mergeOc, "private fun runLoop(", listOf(
            "Rantai ditunda: baterai di bawah", "Rantai ditunda: jam hening aktif", "chain_delay_sec", "Thread.sleep(delay * 1000L)"
        )))
    }

    // ---- §6. Spin-watch: kelas asli + round-trip state JSON ----
    val spinHit = "mengerjakan langkah 1 dari rencana panjang"
    val w1 = newSpinWatch()
    cek("6.1 OcSpinWatch: baris pendek diabaikan", spinObserve(w1, "baris pendek\n") == null)
    cek("6.2 OcSpinWatch: hit di kemunculan ke-3 (first seen)", spinObserve(w1, spinLine(1) + "\n") == null && spinObserve(w1, spinLine(2) + "\n") == null && spinObserve(w1, spinLine(3) + "\n") == spinHit)
    val w2 = newSpinWatch()
    cek("6.3 OcSpinWatch: baris terpotong ditahan sampai newline tiba", spinObserve(w2, "mengerjakan langkah 1") == null && spinObserve(w2, " dari rencana panjang\n") == null && spinObserve(w2, spinLine(2) + "\n") == null && spinObserve(w2, spinLine(3) + "\n") == spinHit)
    val w3 = newSpinWatch()
    cek("6.4 OcSpinWatch: flush menghitung pending tanpa newline", spinObserve(w3, spinLine(1) + "\n") == null && spinObserve(w3, spinLine(2) + "\n") == null && spinObserve(w3, spinLine(3)) == null && spinFlush(w3) == spinHit)
    cek("6.5 OcSpinWatch: flush pending kosong -> null", spinFlush(newSpinWatch()) == null)
    val w4 = newSpinWatch()
    cek("6.6 OcSpinWatch: flush membersihkan pending pendek (tidak ikut baris berikut)", spinObserve(w4, "pendek") == null && spinFlush(w4) == null && spinObserve(w4, spinLine(1) + "\n") == null && spinObserve(w4, spinLine(2) + "\n") == null && spinObserve(w4, spinLine(3) + "\n") == spinHit)
    val w5 = newSpinWatch()
    cek("6.7 OcSpinWatch: reset menghapus hitungan + first seen", spinObserve(w5, spinLine(1) + "\n") == null && spinObserve(w5, spinLine(2) + "\n") == null &&
        run { val m = spinWatchClass.getDeclaredMethod("reset"); m.isAccessible = true; m.invoke(w5); true } &&
        spinObserve(w5, spinLine(1) + "\n") == null && spinObserve(w5, spinLine(2) + "\n") == null && spinObserve(w5, spinLine(3) + "\n") == spinHit)
    if (rust) {
        val emptyState = "{\"counts\":{},\"first_seen\":{},\"pending\":\"\"}"
        val chunk3 = (1..3).joinToString("\n") { spinLine(it) } + "\n"
        cek("6.8 seam spinObserve: 3x dalam satu chunk -> hit + state baru", run {
            val r = OcCoordSeam.spinObserve(emptyState, chunk3)
            r != null && r.first == spinHit && r.second.contains("\"pending\":\"\"")
        })
        cek("6.9 seam spinObserve: state rusak -> dianggap segar (tetap hit)", OcCoordSeam.spinObserve("bukan json", chunk3)?.first == spinHit)
        cek("6.10 seam spinFlush: pending pendek dibersihkan (hit null)", run {
            val r = OcCoordSeam.spinFlush("{\"counts\":{},\"first_seen\":{},\"pending\":\"pendek\"}")
            r != null && r.first == null && JSONObject(r.second).optString("pending") == ""
        })
        cek("6.11 seam spinFlush: pending hitungan ke-3 -> hit", run {
            val st = JSONObject().put("counts", JSONObject().put("mengerjakan langkah # dari rencana panjang", 2)).put("first_seen", JSONObject().put("mengerjakan langkah # dari rencana panjang", spinHit)).put("pending", spinLine(3)).toString()
            OcCoordSeam.spinFlush(st)?.first == spinHit
        })
    } else {
        cek("6.8 seam spinObserve null tanpa .so", OcCoordSeam.spinObserve("{}", "x\n") == null)
        cek("6.9 seam spinFlush null tanpa .so", OcCoordSeam.spinFlush("{}") == null)
        cek("6.10 (kotlin) spin-watch kelas asli sudah teruji §6.1-6.7", true)
        cek("6.11 (kotlin) observeKotlin/flushKotlin == merge (norm)", sameFunBodyNorm(ocSrc, "private fun observeKotlin(", mergeOc, "fun observe(") && sameFunBodyNorm(ocSrc, "private fun flushKotlin(", mergeOc, "fun flush("))
    }

    // ---- §7. Perilaku vc321/vc322/vc327/vc328 dipertahankan (regresi = gagal) ----
    cek("7.1 vc321: GroupChat pakai catalogForTask, BUKAN toolCatalog penuh", grpSrc.contains("AgentPrompt.catalogForTask(app, userText)") && !grpSrc.contains("AgentPrompt.toolCatalog(app)"))
    cek("7.2 vc321: backstop 30k katalog grup utuh", grpSrc.contains("catSlimVc321.length > 30000") && grpSrc.contains("katalog grup dipotong, sisa"))
    cek("7.3 vc321: continuationPromptVc321 + anchor vc316 utuh", grpSrc.contains("private fun continuationPromptVc321(userText: String, suffix: String): String") && grpSrc.contains("AgentPrompt.continuationAnchorVc316(userText)"))
    cek("7.4 vc321: call site lanjutan 4.4/4.5/4.6 utuh", grpSrc.contains("BatchToolVc274.combinedMessage(\"grup\", reportGrpVc274)") && grpSrc.contains("EVIDENCE_GATE_NUDGE") && grpSrc.contains("\"Hasil tool \$tool\""))
    cek("7.5 vc321: buildPrompt(app, m, userText, memories, execMode) tepat 1x", grpSrc.split("buildPrompt(app, m, userText, memories, execMode)").size - 1 == 1 && grpSrc.contains("var prompt = buildPrompt(app, m, userText, memories, execMode)"))
    cek("7.6 vc321: TIDAK ada buildPrompt(...) + (tanpa cap ganda)", !grpSrc.contains("buildPrompt(app, m, userText, memories, execMode) +"))
    cek("7.7 vc322/vc327/vc328: TANPA marker lama di BgBrain + GroupChat", !bgSrc.contains("Vc322") && !bgSrc.contains("vc322") && !bgSrc.contains("Vc327") && !bgSrc.contains("vc327") && !bgSrc.contains("Vc328") && !bgSrc.contains("vc328") &&
        !grpSrc.contains("Vc322") && !grpSrc.contains("vc322") && !grpSrc.contains("Vc327") && !grpSrc.contains("vc327") && !grpSrc.contains("Vc328") && !grpSrc.contains("vc328"))
    cek("7.8 vc328: DsApiClient tetap split (DsApiSeam) + tanpa Vc329", src(treeDir, "DsApiClient.kt").contains("DsApiSeam") && !src(treeDir, "DsApiClient.kt").contains("Vc329"))
    cek("7.9 vc329: Flavor/MemoryDb/MainActivity/SlotClient/TurnMetrics TANPA Vc329", !src(treeDir, "Flavor.kt").contains("Vc329") && !src(treeDir, "MemoryDb.kt").contains("Vc329") &&
        !src(treeDir, "MainActivity.kt").contains("Vc329") && !src(treeDir, "SlotClient.kt").contains("Vc329") && !src(treeDir, "TurnMetrics.kt").contains("Vc329"))
    cek("7.10 vc329: keempat file target mengandung marker Vc329/vc329", bgSrc.lowercase().contains("vc329") && grpSrc.lowercase().contains("vc329") && dsSrc.lowercase().contains("vc329") && ocSrc.lowercase().contains("vc329"))
    cek("7.11 vc321/vc327: AgentPrompt.continuationAnchorVc316 + deltaTurnPromptVc327 ada", src(treeDir, "AgentPrompt.kt").contains("fun continuationAnchorVc316(") && src(treeDir, "AgentPrompt.kt").contains("fun deltaTurnPromptVc327("))
    cek("7.12 vc328: DsApiSeam + HttpSeam utuh", src(treeDir, "DsApiSeam.kt").contains("object DsApiSeam") && src(treeDir, "HttpSeam.kt").contains("object HttpSeam"))
    // IXL §6 (vc316): badan continuationPrompt..loop( tanpa buildSystemPrompt/pin/fakta/goals.
    val ixlBody = run {
        val a = bgSrc.indexOf("private fun continuationPrompt")
        val b = bgSrc.indexOf(" private fun loop(", a)
        if (a < 0 || b < 0) "" else bgSrc.substring(a, b)
    }
    cek("7.13 vc316 IXL: lanjutan latar TANPA buildSystemPrompt/pinned/fakta/goals", ixlBody.isNotEmpty() && !ixlBody.contains("buildSystemPrompt") && !ixlBody.contains("pinnedFactsText") && !ixlBody.contains("factsText(") && !ixlBody.contains("goalsText("))
    cek("7.14 vc316 IXL: lanjutan latar MEMBAWA work-notes + [RIWAYAT] + tugas verbatim", ixlBody.contains("workNotesBlockVc302") && ixlBody.contains("[RIWAYAT]") && ixlBody.contains("transcript") && ixlBody.contains("TUGAS (verbatim") && ixlBody.contains("Lanjutkan tugasnya:") && ixlBody.contains("vc316 delta") && ixlBody.contains("SESI SERVER YANG SAMA"))
    val p1Tail = bgSrc.substring(bgSrc.indexOf("private fun handleError"))
    cek("7.15 vc316 P1: ekor sesudah handleError TANPA msg.contains(/m.contains(", !p1Tail.contains("msg.contains(") && !p1Tail.contains("m.contains("))

    // ---- §8. *Kotlin identik merge/ vc328 ----
    cek("8.1 cutAtMessageBoundaryKotlin == merge (norm)", sameFunBodyNorm(bgSrc, "private fun cutAtMessageBoundaryKotlin(", mergeBg, "fun cutAtMessageBoundary("))
    cek("8.2 trimTranscriptVc263Kotlin == merge (norm)", sameFunBodyNorm(bgSrc, "private fun trimTranscriptVc263Kotlin(", mergeBg, "private fun trimTranscriptVc263("))
    cek("8.3 queueBgFactProposalKotlin == merge (norm)", sameFunBodyNorm(bgSrc, "private fun queueBgFactProposalKotlin(", mergeBg, "private fun queueBgFactProposal("))
    cek("8.4 bgQuietHourKotlin: rumus jam hening sama dengan merge bgAllowed", funBody(bgSrc, "private fun bgQuietHourKotlin(").contains("(h >= qs && h < qe)") && funBody(bgSrc, "private fun bgQuietHourKotlin(").contains("(h >= qs || h < qe)") &&
        funBody(mergeBg, "fun bgAllowed(").contains("(h >= qs && h < qe)") && funBody(mergeBg, "fun bgAllowed(").contains("(h >= qs || h < qe)"))
    cek("8.5 batteryFormulaKotlin: rumus daya sama dengan merge batteryOk", funSegment(bgSrc, "private fun batteryFormulaKotlin(").contains("level * 100 / scale.coerceAtLeast(1) >= 20 || plugged != 0") &&
        funBody(mergeBg, "fun batteryOk(").contains("level * 100 / scale.coerceAtLeast(1) >= 20 || plugged != 0"))
    cek("8.6 continuationPromptKotlin: jangkar + [RIWAYAT] sama dengan merge", bodyContainsLines(bgSrc, "private fun continuationPromptKotlin(", listOf(
        "[LANJUTAN SISTEM — vc316 delta", "TUGAS (verbatim baris pertama", "[RIWAYAT]", "Lanjutkan tugasnya:"
    )) && bodyContainsLines(mergeBg, "private fun continuationPrompt(", listOf(
        "[LANJUTAN SISTEM — vc316 delta", "TUGAS (verbatim baris pertama", "[RIWAYAT]", "Lanjutkan tugasnya:"
    )))
    cek("8.7 honestCapStopBgKotlin: template pesan sama dengan merge loopInner", funSegment(bgSrc, "private fun honestCapStopBgKotlin(").contains("Tugas latar berhenti di tengah pada langkah ") &&
        funBody(mergeBg, "private fun loopInnerVc263(").contains("Tugas latar berhenti di tengah pada langkah ") &&
        funBody(mergeBg, "private fun loopInnerVc263(").contains("— ini yang sudah selesai: ") &&
        funBody(mergeBg, "private fun loopInnerVc263(").contains("sisa langkah tidak dikerjakan"))
    cek("8.8 bgToolRouteDecisionKotlin: tabel rute sama dengan merge loopInner", bodyContainsLines(bgSrc, "private fun bgToolRouteDecisionKotlin(", listOf(
        "if (tool == \"make_plan\" || tool == \"update_plan\") return \"execute\"",
        "Tool analyze_photo hanya tersedia dari chat 1-1",
        "Tool delete_session hanya tersedia dari UI chat aktif",
        "park_memory_jobtools", "Proposal memori latar DITAMPUNG", "park_memory_noprop", "park_risky"
    )) && funBody(mergeBg, "private fun loopInnerVc263(").contains("Proposal memori latar DITAMPUNG") && funBody(mergeBg, "private fun loopInnerVc263(").contains("Tool analyze_photo hanya tersedia dari chat 1-1"))
    cek("8.9 watchdogDecisionKotlin: kondisi sama dengan merge watchdogTick", bodyContainsLines(bgSrc, "private fun watchdogDecisionKotlin(", listOf(
        "if (start <= 0) return \"skip\"",
        "if (now - start > 2 * 3600_000L) return \"cleanup\"",
        "tidak ada kemajuan selama 10 menit (giliran macet)",
        "tidak ada tool sukses selama 10 menit (berputar tanpa progres)"
    )) && funBody(mergeBg, "fun watchdogTick(").contains("tidak ada kemajuan selama 10 menit (giliran macet)") && funBody(mergeBg, "fun watchdogTick(").contains("tidak ada tool sukses selama 10 menit (berputar tanpa progres)"))
    cek("8.10 deliverTitleKotlin: peta judul sama dengan merge deliver", bodyContainsLines(bgSrc, "private fun deliverTitleKotlin(", listOf(
        "🤖 Tugas terjadwal selesai", "⚡ Aturan notifikasi dijalankan", "🎯 Langkah tujuan dikerjakan", "☀️ Briefing pagi", "🌙 Rekap malam", "🌙 Konsolidasi memori",
        "(tugas selesai tanpa teks jawaban)", "⚠️ Laporan gagal tersimpan — "
    )) && funBody(mergeBg, "private fun deliver(").contains("🤖 Tugas terjadwal selesai") && funBody(mergeBg, "private fun deliver(").contains("(tugas selesai tanpa teks jawaban)"))
    cek("8.11 submitGateKotlin: blok gerbang sama dengan merge submit", bodyContainsLines(bgSrc, "private fun submitGateKotlin(", listOf(
        "return block(ctx, \"cooldown\")", "return block(ctx, \"sesi mati\")", "return block(ctx, \"kuota\")", "return block(ctx, \"gerbang\")", "return block(ctx, \"job dimatikan\")", "return block(ctx, \"baterai\")"
    )) && funBody(mergeBg, "fun submit(").contains("return block(ctx, \"cooldown\")") && funBody(mergeBg, "fun submit(").contains("return block(ctx, \"baterai\")"))
    cek("8.12 personaNameForKotlin/accentForKotlin/parseMentionsKotlin == merge (norm)", sameFunBodyNorm(grpSrc, "private fun personaNameForKotlin(", mergeGrp, "fun personaNameFor(") &&
        sameFunBodyNorm(grpSrc, "private fun accentForKotlin(", mergeGrp, "fun accentFor(") &&
        sameFunBodyNorm(grpSrc, "private fun parseMentionsKotlin(", mergeGrp, "fun parseMentions("))
cek("8.13 continuationPromptVc321Kotlin/buildPromptKotlin/saveLastRoundAsPlanKotlin: vc321 + kerangka vc316 == merge", funSegment(grpSrc, "private fun continuationPromptVc321Kotlin(").contains("AgentPrompt.continuationAnchorVc316(userText)") &&
        funSegment(grpSrc, "private fun continuationPromptVc321Kotlin(").contains("+ suffix") &&
        funSegment(grpSrc, "private fun buildPromptKotlin(").contains("AgentPrompt.catalogForTask(app, userText)") &&
        funSegment(grpSrc, "private fun buildPromptKotlin(").contains("catSlimVc321.length > 30000") &&
        funSegment(grpSrc, "private fun buildPromptKotlin(").contains("…(base grup dipotong ke 60000 char; cap total 200000)…") &&
        funSegment(grpSrc, "private fun buildPromptKotlin(").contains("[RIWAYAT]") &&
        funSegment(grpSrc, "private fun buildPromptKotlin(").contains("[USER — PESAN PEMICU VERBATIM]:") &&
        funSegment(grpSrc, "private fun buildPromptKotlin(").contains("transcript.size - 5") &&
        funBody(mergeGrp, "private fun buildPrompt(").contains("AgentPrompt.toolCatalog(app)") &&
        sameFunBodyNorm(grpSrc, "private fun saveLastRoundAsPlanKotlin(", mergeGrp, "fun saveLastRoundAsPlan("))
    cek("8.14 selectQueueKotlin: cabang + catatan sama dengan merge startRound", bodyContainsLines(grpSrc, "private fun selectQueueKotlin(", listOf(
        "Tidak ada anggota grup yang aktif — aktifkan lewat ⚙️ Setting Grup.",
        "💤 ", "nonaktif di grup — aktifkan lewat ⚙️ Setting Grup."
    )) && funBody(mergeGrp, "private fun startRound(").contains("Tidak ada anggota grup yang aktif") && funBody(mergeGrp, "private fun startRound(").contains("nonaktif di grup"))
    cek("8.15 resolveSlotKotlin: terikat + fallback sama dengan merge startRound", bodyContainsLines(grpSrc, "private fun resolveSlotKotlin(", listOf(
        "if (used.contains(bound)) null else bound",
        "filled.firstOrNull { !used.contains(it) && (slotPersona[it] ?: \"\").isBlank() }"
    )) && funBody(mergeGrp, "private fun startRound(").contains("slotPersona"))
    cek("8.16 honestCapStopGroupKotlin/chairHopKotlin: template + guard sama dengan merge", funSegment(grpSrc, "private fun honestCapStopGroupKotlin(").contains("berhenti di tengah pada langkah 3 dari tugas: ") &&
        funBody(mergeGrp, "private fun startRound(").contains("berhenti di tengah pada langkah 3 dari tugas: ") &&
        bodyContainsLines(grpSrc, "private fun chairHopKotlin(", listOf("parseMentions(clean)", "!responded.contains(it.id)", "!queue.contains(it)")) &&
        funBody(mergeGrp, "private fun startRound(").contains("parseMentions(clean)"))
cek("8.17 retrieveMemoriesKotlin: saring sama dengan merge retrieveMemories", funSegment(grpSrc, "private fun retrieveMemoriesKotlin(").contains("Tidak ada memori") &&
        funSegment(grpSrc, "private fun retrieveMemoriesKotlin(").contains("Kata kunci") &&
        funSegment(grpSrc, "private fun retrieveMemoriesKotlin(").contains(".take(10)") &&
        funSegment(grpSrc, "private fun retrieveMemoriesKotlin(").contains(".take(1000)") &&
        funBody(mergeGrp, "fun retrieveMemories(").contains("Tidak ada memori") && funBody(mergeGrp, "fun retrieveMemories(").contains(".take(10)"))
    cek("8.18 renderNotif3LinesKotlin/groupPlansKotlin: render + sort sama dengan merge", bodyContainsLines(grpSrc, "private fun renderNotif3LinesKotlin(", listOf("baris lagi")) &&
        funBody(mergeGrp, "private fun notifyGroupDone(").contains("baris lagi") &&
        bodyContainsLines(grpSrc, "private fun groupPlansKotlin(", listOf("JSONArray(raw)", "sortedByDescending { it.ts }")) &&
        funBody(mergeGrp, "fun groupPlans(").contains("sortedByDescending { it.ts }"))
    cek("8.19 evalParseKotlin: JSONTokener.nextValue sama dengan merge eval", bodyContainsLines(dsSrc, "private fun evalParseKotlin(", listOf(
        "JSONTokener(raw ?: \"null\").nextValue()", "if (parsed is String) parsed else (parsed?.toString() ?: \"\")"
    )) && funBody(mergeDs, "private fun eval(").contains("JSONTokener") && funBody(mergeDs, "private fun eval(").contains("nextValue()"))
    cek("8.20 template JS Kotlin: literal sama dengan merge DsAgent", dsSrc.contains("document.title + '\\\\n' + ((document.body && document.body.innerText)") &&
        dsSrc.contains("return 'READY'") && dsSrc.contains("JSON.stringify({c: n.length, t: t})") &&
        dsSrc.contains("\"B64HERE\"") && dsSrc.contains("setToggle('DeepThink', \$thinking)") &&
        mergeDs.contains("document.title + '\\\\n' + ((document.body && document.body.innerText)") &&
        mergeDs.contains("setToggle('DeepThink', \$thinking)"))
    cek("8.21 ruleSanitizeKotlin: sanitasi sama dengan merge rule", bodyContainsLines(ocSrc, "private fun ruleSanitizeKotlin(", listOf(
        "\"path_mode\" -> if (raw.trim().lowercase() in listOf(\"resmi\", \"mandiri\"))",
        "\"auto_approve\" -> if (raw.trim().lowercase() in listOf(\"on\", \"true\", \"yes\", \"aktif\")) \"\" else raw"
    )) && funBody(mergeOc, "private fun rule(").contains("listOf(\"resmi\", \"mandiri\")") && funBody(mergeOc, "private fun rule(").contains("listOf(\"on\", \"true\", \"yes\", \"aktif\")"))
    cek("8.22 spinningPatternVc277Kotlin/looksFailedVc262Kotlin/incrementalAssistantTextVc303Kotlin == merge (norm)", sameFunBodyNorm(ocSrc, "private fun spinningPatternVc277Kotlin(", mergeOc, "private fun spinningPatternVc277(") &&
        sameFunBodyNorm(ocSrc, "private fun looksFailedVc262Kotlin(", mergeOc, "private fun looksFailedVc262(") &&
        sameFunBodyNorm(ocSrc, "private fun incrementalAssistantTextVc303Kotlin(", mergeOc, "private fun incrementalAssistantTextVc303("))
    cek("8.23 sessionRotateKotlin: judul + catatan rotasi sama dengan merge runTask", bodyContainsLines(ocSrc, "private fun sessionRotateKotlin(", listOf(
        "🤖 Harness #", "Sesi dirotasi: konteks sesi sebelumnya"
    )) && funBody(mergeOc, "private fun runTask(").contains("Sesi dirotasi: konteks sesi sebelumnya") && funBody(mergeOc, "private fun runTask(").contains("🤖 Harness #"))
    cek("8.24 honestStatusKotlin/scanMessagesKotlin/awaitBodyKotlin: prefix + pindai + badan sama dengan merge", bodyContainsLines(ocSrc, "private fun honestStatusKotlin(", listOf(
        "⛔ Hasil koordinator terdeteksi berputar", "status SEBAGIAN"
    )) && funBody(mergeOc, "private fun finishTask(").contains("⛔ Hasil koordinator terdeteksi berputar") &&
        bodyContainsLines(ocSrc, "private fun scanMessagesKotlin(", listOf("optString(\"finish\") == \"stop\"", "agentError")) &&
        bodyContainsLines(ocSrc, "private fun awaitBodyKotlin(", listOf("⏱ Timeout tanpa jawaban teks dari agen", "Agen selesai tanpa teks jawaban", "⏱ Timeout — agen dihentikan; hasil parsial:"))
        && funBody(mergeOc, "private fun awaitCompletion(").contains("⏱ Timeout tanpa jawaban teks dari agen"))
    cek("8.25 collectResultKotlin/parsePermEventKotlin/permDecisionKotlin: gabung + SSE + izin sama dengan merge", bodyContainsLines(ocSrc, "private fun collectResultKotlin(", listOf("## Objective", ".take(3000)", "optLong(\"input\")")) &&
        bodyContainsLines(ocSrc, "private fun parsePermEventKotlin(", listOf("data:", "permission", "sessionID", "izin tool OpenCode", "\"tool=\" +", "\"args=\" +")) &&
        bodyContainsLines(ocSrc, "private fun permDecisionKotlin(", listOf("if (ToolGate.GATES_DISABLED) return \"auto_gate\"", "return \"park\"")) &&
        funBody(mergeOc, "private fun handlePermission(").contains("GATES_DISABLED"))
    cek("8.26 taskResultTextKotlin/peekSessionTextKotlin/probeCacheReadKotlin/batteryPctKotlin/isQuietNowKotlin: sama dengan merge", bodyContainsLines(ocSrc, "private fun taskResultTextKotlin(", listOf("HASIL:", "GAGAL:", "Dibatalkan. Catatan:", "Progres terakhir agen:")) &&
        bodyContainsLines(ocSrc, "private fun peekSessionTextKotlin(", listOf(".take(700)")) &&
        bodyContainsLines(ocSrc, "private fun probeCacheReadKotlin(", listOf("20_000")) &&
        funSegment(ocSrc, "private fun batteryPctKotlin(").contains("level * 100 / scale") &&
        bodyContainsLines(ocSrc, "private fun isQuietNowKotlin(", listOf("hour in start until end", "hour >= start || hour < end")))

    // ---- §9. Batas slice ----
    fun rsCodeNoComments(rs: String): String =
        rs.lines().filter { val t = it.trim(); !t.startsWith("//") && !t.startsWith("///") && !t.startsWith("//!") && !t.startsWith("*") }.joinToString("\n")
    val prodBg = bgRs.substringBefore("mod tests")
    val prodGrp = grpRs.substringBefore("mod tests")
    val prodDs = dsRs.substringBefore("mod tests")
    val prodOc = ocRs.substringBefore("mod tests")
    val prodCommon = commonRs.substringBefore("mod tests")
    cek("9.1 Rust slice 12: TANPA WebView/CookieManager/NotificationManager/evaluateJavascript", !rsCodeNoComments(bgRs + grpRs + dsRs + ocRs + commonRs).contains("WebView") &&
        !rsCodeNoComments(bgRs + grpRs + dsRs + ocRs + commonRs).contains("CookieManager") &&
        !rsCodeNoComments(bgRs + grpRs + dsRs + ocRs + commonRs).contains("NotificationManager") &&
        !rsCodeNoComments(bgRs + grpRs + dsRs + ocRs + commonRs).contains("evaluateJavascript"))
    cek("9.2 Rust slice 12: TANPA host chat.deepseek.com hardcode", !(bgRs + grpRs + dsRs + ocRs + commonRs).contains("chat.deepseek.com"))
    cek("9.3 Rust slice 12: forbid(unsafe_code) di 5 file", bgRs.contains("#![forbid(unsafe_code)]") && grpRs.contains("#![forbid(unsafe_code)]") && dsRs.contains("#![forbid(unsafe_code)]") &&
        ocRs.contains("#![forbid(unsafe_code)]") && commonRs.contains("#![forbid(unsafe_code)]"))
    cek("9.4 Rust slice 12 produksi: TANPA unwrap()/expect()", !prodBg.contains("unwrap()") && !prodBg.contains("expect(") && !prodGrp.contains("unwrap()") && !prodGrp.contains("expect(") &&
        !prodDs.contains("unwrap()") && !prodDs.contains("expect(") && !prodOc.contains("unwrap()") && !prodOc.contains("expect(") && !prodCommon.contains("unwrap()") && !prodCommon.contains("expect("))
    val tomlDeps = cargoToml.lines().dropWhile { it.trim() != "[dependencies]" }.drop(1)
        .takeWhile { !it.trim().startsWith("[") }
        .mapNotNull { Regex("^([A-Za-z0-9_-]+)[ ]*=").find(it)?.groupValues?.get(1) }.toSet()
    cek("9.5 Cargo.toml: dependensi TETAP 8 crate (tanpa dep baru)", tomlDeps == setOf("jni", "serde_json", "httpdate", "regex", "rusqlite", "base64", "ureq", "sha2"))
    val lockPkgs = cargoLock.lines().map { it.trim() }.filter { it.startsWith("name = \"") }
        .map { it.removePrefix("name = \"").removeSuffix("\"") }.toSet()
    cek("9.6 Cargo.lock: paket TETAP 106 paket (tanpa paket baru)", lockPkgs == setOf("ahash", "aho-corasick", "base64", "bitflags", "block-buffer", "bytes", "cc", "cesu8", "cfg-if", "combine", "cpufeatures", "crypto-common", "digest", "displaydoc", "fallible-iterator", "fallible-streaming-iterator", "find-msvc-tools", "form_urlencoded", "generic-array", "getrandom", "harness-core", "hashbrown", "hashlink", "httpdate", "icu_collections", "icu_locale_core", "icu_normalizer", "icu_normalizer_data", "icu_properties", "icu_properties_data", "icu_provider", "idna", "idna_adapter", "itoa", "jni", "jni-sys", "jni-sys-macros", "libc", "libsqlite3-sys", "litemap", "log", "memchr", "once_cell", "percent-encoding", "pkg-config", "potential_utf", "proc-macro2", "quote", "regex", "regex-automata", "regex-syntax", "ring", "rusqlite", "rustls", "rustls-pki-types", "rustls-webpki", "same-file", "serde", "serde_core", "serde_derive", "serde_json", "sha2", "shlex", "smallvec", "stable_deref_trait", "subtle", "syn", "synstructure", "thiserror", "thiserror-impl", "tinystr", "typenum", "unicode-ident", "untrusted", "ureq", "url", "utf8_iter", "vcpkg", "version_check", "walkdir", "wasi", "webpki-roots", "winapi-util", "windows-link", "windows-sys", "windows-targets", "windows_aarch64_gnullvm", "windows_aarch64_msvc", "windows_i686_gnu", "windows_i686_gnullvm", "windows_i686_msvc", "windows_x86_64_gnu", "windows_x86_64_gnullvm", "windows_x86_64_msvc", "writeable", "yoke", "yoke-derive", "zerocopy", "zerocopy-derive", "zerofrom", "zerofrom-derive", "zeroize", "zerotrie", "zerovec", "zerovec-derive", "zmij"))
    cek("9.7 BgBrain: WebView latar (ensureClient) + notifikasi tetap Kotlin", bgSrc.contains("ensureClient(ctx)") && bgSrc.contains("notify("))
    cek("9.8 OcCoord: notifikasi hasil + izin tetap Kotlin", ocSrc.contains("notifyTask(") && ocSrc.contains("notifyApproval("))
    cek("9.9 GroupChat: dialog persetujuan listener tetap Kotlin", grpSrc.contains("listener.onToolApproval"))
    cek("9.10 DsAgent: evaluateJavascript tetap Kotlin", dsSrc.contains("webView.evaluateJavascript("))
    cek("9.11 BgRoomSeam: 58 op dikenal (14 bgBrain + 15 groupChat + 6 dsAgent + 26 ocCoord - 3 gabung)", seamSrc.contains("\"cut_boundary\"") && seamSrc.contains("\"parse_mentions\"") && seamSrc.contains("\"poll_step\"") && seamSrc.contains("\"serve_select\"") &&
        bgRs.contains("\"cut_boundary\"") && grpRs.contains("\"parse_mentions\"") && dsRs.contains("\"poll_step\"") && ocRs.contains("\"serve_select\""))

    // ---- §10. Saklar fallback: matikan 4 seam -> wrapper kembali ke *Kotlin, nilai SAMA ----
    val swBg = BgBrainSeam.rustBgBrain
    val swGrp = GroupChatSeam.rustGroupChat
    val swDs = DsAgentSeam.rustDsAgent
    val swOc = OcCoordSeam.rustOcCoord
    BgBrainSeam.rustBgBrain = false
    GroupChatSeam.rustGroupChat = false
    DsAgentSeam.rustDsAgent = false
    OcCoordSeam.rustOcCoord = false
    cek("10.1 saklar mati: cutAtMessageBoundary tetap 'a\\nb'", wCut("a\nb\nc", 4) == "a\nb")
    cek("10.2 saklar mati: honestCapStopBg tetap", wHonestBg("kerjakan laporan", 5, listOf("web_search", "read_file"), StringBuilder("Tugas (jadwal): kerja\nTAIL")).contains("langkah 5"))
    cek("10.3 saklar mati: bgToolRoute tetap execute/park", wRoute("make_plan", false, false, null, false, false, null) == ("execute" to null) && wRoute("delete_file", false, false, null, true, false, null) == ("park_risky" to null))
    cek("10.4 saklar mati: watchdog tetap cleanup/notify", wWatch(1000, 1000, 1000, 1000 + 7_200_001L, false) == ("cleanup" to null) && wWatch(1000, 1000, 1000 + 601000L, 1000 + 601000L, false).first == "notify")
    cek("10.5 saklar mati: deliverTitle tetap", wDeliver(null, "jadwal", false, "hasil kerja") == ("🤖 Tugas terjadwal selesai" to "hasil kerja"))
    cek("10.6 saklar mati: parseMentions tetap", wMentions("@mentor @senz") == listOf("mentor", "senz"))
    cek("10.7 saklar mati: selectQueue tetap", wQueue(listOf(mandor), false, listOf(senz)) == Triple<List<String>, List<String>, String>(emptyList(), listOf("💤 Mandor nonaktif di grup — aktifkan lewat ⚙️ Setting Grup."), "senz"))
    cek("10.8 saklar mati: honestCapStopGroup + chairHop tetap", wHonestGrp(listOf("a"), "t").contains("langkah 3") && wHop("@mandor", false, true, true, 0, emptySet(), listOf(senz)) == listOf("mandor"))
    cek("10.9 saklar mati: continuationPromptVc321 tetap", wContRoom("kerjakan X", "hasil tool") == contRoom)
    cek("10.10 saklar mati: renderNotif + groupPlans tetap", wRender("1\n2\n3\n4\n5") == "1\n2\n3\n…(2 baris lagi)…" && wPlans("[{\"title\":\"a\",\"body\":\"1\",\"ts\":9}]").single().title == "a")
    cek("10.11 saklar mati: spinningPattern + looksFailed + scan + await tetap", wSpin(spinText3) == spinHit && wFailed("gagal") && wScan(scanMsgs, 0).first == 3 && wAwait("hasil", false, "s1") == ("done" to "hasil"))
    cek("10.12 saklar mati: parsePermEvent + permStale + resultNotifId tetap", wPerm(permLine, "s1")?.first == "p1" && wStale(1000, 1000 + 30 * 60_000L + 1, 30) && OcCoord.resultNotifIdFor(1) == 40001)
    cek("10.13 saklar mati: evalParse + attach/send JS tetap", wEvalParse("\"READY\"") == "READY" && wAttachJs("QUJD", "foto.jpg", "image/jpeg") == attachK && wSendJs(true, false, "\"halo\"") == sendK)
    cek("10.14 saklar mati: template JS tetap", wProbeJs().contains("return 'READY'") && wPageJs().contains("document.title"))
    cek("10.15 saklar mati: seam mengembalikan null (bukan crash)", BgBrainSeam.cutBoundary("a", 1) == null && GroupChatSeam.renderNotif3Lines("a") == null && DsAgentSeam.dsJs("probe") == null && OcCoordSeam.resultNotifIdFor(1) == null)
    BgBrainSeam.rustBgBrain = swBg
    GroupChatSeam.rustGroupChat = swGrp
    DsAgentSeam.rustDsAgent = swDs
    OcCoordSeam.rustOcCoord = swOc
    cek("10.16 saklar dipulihkan ke nilai awal", BgBrainSeam.rustBgBrain == swBg && GroupChatSeam.rustGroupChat == swGrp && DsAgentSeam.rustDsAgent == swDs && OcCoordSeam.rustOcCoord == swOc)

    println("== TestBgRoomVc329 mode=$mode SELESAI: $lulus/$total lulus, $gagal gagal ==")
    if (gagal > 0) {
        println("HASIL: GAGAL ($gagal dari $total)")
        kotlin.system.exitProcess(1)
    } else {
        println("HASIL: LULUS ($total/$total)")
    }
}
