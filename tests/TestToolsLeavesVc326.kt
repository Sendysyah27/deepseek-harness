// TestToolsLeavesVc326 — P12 (slice 10, vc326): paritas seam daun murni Tools.kt.
//
// Mode: TestToolsLeavesVc326Kt <rust|kotlin> <treeDir> <coreDir>
//   rust   = jalur Rust ASLI via .so host (-Djava.library.path=.../target/debug)
//   kotlin = fallback *Kotlin, tanpa .so (RustCore.available=false)
//   treeDir = direktori .kt terpadu (integrated / app/.../deepharness)
//   coreDir = harness-core (untuk baca jni_glue.rs + Cargo.toml)
//
// Yang diuji (diferensial: ekspektasi SAMA di kedua mode — hasil Rust
// dan hasil Kotlin lama harus identik):
//   (a) SEAM LANGSUNG via ToolsLeavesSeam (mode rust) + fallback
//       (mode kotlin): stripHtml, parseDdg, truncateMarked,
//       ytRunsText, ytParseVideoRenderer, extractBalancedJson,
//       ytWalkResults, ytScanRenderers, parseYtSearch, formatYtResults.
//   (b) WRAPPER PRIVAT `Tools.*` — diuji via REFLEKSI (fun-fun ini
//       `private` di `object Tools`, jadi tes memanggilnya lewat
//       `getDeclaredMethod` + `INSTANCE`; perilaku yang diuji SAMA
//       dengan pemakaian dari `execute()`/dispatcher).
//   (c) FALLBACK: mode kotlin (tanpa .so) -> seam null + wrapper tetap
//       bekerja lewat *Kotlin; saklar rustToolsLeaves false.
//   (d) SUMBER: *Kotlin identik merge/ vc316 (funBody, dinormalisasi
//       panggilan internal `*Kotlin(` -> `(`), dispatcher + daun
//       Android + daun jaringan tetap Kotlin, DSML sudah Rust slice 6,
//       daun murni lain (slugify/ruleKey/parseDateRange/dll.) TANPA
//       Rust, Flavor.kt + DsApiClient.kt tanpa sentuhan slice 10.
//   (e) STRUKTUR: RUST_CORE_VERSION 8 + 28 simbol JNI +
//       toolsLeavesJson + modul tools_leaves, TANPA tokio dan TANPA
//       dependensi Cargo baru.
//
// CATATAN JUJUR: jaringan TIDAK diuji di JVM — `httpGet` ke
// DuckDuckGo/YouTube butuh jaringan nyata + `searchYoutube`/
// `webSearch` memanggilnya (sandbox VM mencegat TCP loopback JVM,
// preseden P10; HTTP keluar juga bukan bagian seam). Yang dibuktikan
// P12: seluruh logika PARSE + format + strip/truncate yang
// menentukan hasil tool `web_search`/`search_youtube`/`web_fetch`.
// End-to-end (jaringan nyata) menjadi TES perangkat (TES-VC326).
//
// CATATAN ASTRAL: `truncateMarkedVc230("a😀b", 2)` — Kotlin `take(2)`
// mengembalikan "a" + lone high surrogate (string JVM); seam Rust
// mereplikanya dengan menulis escape JSON `\ud83d` mentah sehingga
// org.json mem-parse-nya kembali jadi lone surrogate yang sama
// (probe JVM /tmp/probe326: `toByteArray(UTF_8)` = `61 3F`).
// Cek §4 membandingkan byte UTF-8 (`61 3F`) di kedua mode —
// itulah kontrak wire yang benar.

package com.senzwh.deepharness

import org.json.JSONObject

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
 *  char 'x' (escape-aware — tanpa ini, `'{'` di `indexOf('{', ...)`
 *  menambah depth tanpa penutup sehingga ekstraksi lari sampai EOF),
 *  dan komentar `//` supaya `{` di dalam literal tidak mengacaukan
 *  hitungan kurung. "" = signature tidak ketemu / tak-berakhir. */
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

/** Dua fungsi identik isinya (nama fungsi boleh beda)? Body dibandingkan
 *  apa adanya setelah normalisasi spasi + panggilan internal `*Kotlin(`
 *  dinormalisasi jadi `(` (badan *Kotlin memanggil varian *Kotlin,
 *  badan merge/ memanggil nama asli — isinya sama). */
private fun sameFunBodyNorm(aText: String, aSig: String, bText: String, bSig: String): Boolean {
    val a = funBody(aText, aSig)
    val b = funBody(bText, bSig)
    if (a.isEmpty() || b.isEmpty()) return false
    fun norm(s: String) = s.replace("Kotlin(", "(").split(Regex("\\s+")).joinToString(" ")
    return norm(a) == norm(b)
}

// ---- Refleksi ke fun privat object Tools ----
private val toolsClass: Class<*> by lazy { Class.forName("com.senzwh.deepharness.Tools") }
private val toolsInstance: Any by lazy { toolsClass.getField("INSTANCE").get(null) }

private fun callTools(name: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? {
    val m = toolsClass.getDeclaredMethod(name, *paramTypes)
    m.isAccessible = true
    return m.invoke(toolsInstance, *args)
}

private fun tStripHtml(html: String): String =
    callTools("stripHtml", arrayOf(String::class.java), html) as String

private fun tParseDdg(html: String, query: String): String =
    callTools("parseDdg", arrayOf(String::class.java, String::class.java), html, query) as String

private fun tTruncate(text: String, cap: Int, resultId: String): String =
    callTools("truncateMarkedVc230", arrayOf(String::class.java, Int::class.java, String::class.java), text, cap, resultId) as String

private fun tYtRuns(o: JSONObject?): String =
    callTools("ytRunsText", arrayOf(JSONObject::class.java), o) as String

private fun tYtParse(vr: JSONObject, forceMeta: String?): String? {
    val r = callTools("ytParseVideoRenderer", arrayOf(JSONObject::class.java, String::class.java), vr, forceMeta)
    return r?.toString()
}

private fun tExtract(text: String, start: Int): String? =
    callTools("extractBalancedJson", arrayOf(String::class.java, Int::class.java), text, start) as String?

private fun tYtWalk(root: JSONObject): String {
    val r = callTools("ytWalkResults", arrayOf(JSONObject::class.java), root)
    return r.toString()
}

private fun tYtScan(html: String): String {
    val r = callTools("ytScanRenderers", arrayOf(String::class.java), html)
    return r.toString()
}

private fun tParseYt(html: String): String {
    val r = callTools("parseYtSearch", arrayOf(String::class.java), html)
    return r.toString()
}

private fun tFormatYt(hits: Any?, maxResults: Int): String =
    callTools("formatYtResults", arrayOf(List::class.java, Int::class.java), hits, maxResults) as String

private fun walkRootJson(): JSONObject = JSONObject(
    """{"contents": {"twoColumnSearchResultsRenderer": {"primaryContents": {"sectionListRenderer": {"contents": [
        {"itemSectionRenderer": {"contents": [
            {"videoRenderer": {"videoId": "v1", "title": {"simpleText": "Satu"}, "longBylineText": {"simpleText": "K1"}, "lengthText": {"simpleText": "3:00"}}},
            {"reelItemRenderer": {"videoId": "s1", "headline": {"simpleText": "Short Satu"}, "shortBylineText": {"simpleText": "K2"}}},
            {"videoRenderer": {"videoId": "v1", "title": {"simpleText": "Duplikat"}, "longBylineText": {"simpleText": "K1"}}},
            {"videoRenderer": null},
            {"videoRenderer": {"videoId": "v2", "title": {"simpleText": "Dua"}}}
        ]}}
    ]}}}}}
    """.trimIndent()
)

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "kotlin"
    val treeDir = args.getOrNull(1) ?: "."
    val coreDir = args.getOrNull(2) ?: "harness-core"
    val rust = mode == "rust"
    println("== TestToolsLeavesVc326 mode=$mode treeDir=$treeDir coreDir=$coreDir ==")
    val mergeDir = java.io.File(treeDir, "../merge")
    val toolsSrc = src(treeDir, "Tools.kt")
    val seamSrc = src(treeDir, "ToolsLeavesSeam.kt")
    val mergeTools = src(mergeDir.path, "Tools.kt")

    val rcSrc = listOf(
        "harness/core/RustCore.kt",
        "../harness/core/RustCore.kt",
        "../../harness/core/RustCore.kt",
        "../../../harness/core/RustCore.kt"
    ).mapNotNull { rel ->
        val f = java.io.File(treeDir, rel)
        if (f.exists()) f.readText() else null
    }.firstOrNull() ?: ""

    // ---- §1. Seam + VERSION ----
    cek("1.1 RustCore.kt VERSION 8 + toolsLeavesJson", rcSrc.contains("const val VERSION = 8") && rcSrc.contains("external fun toolsLeavesJson(request: String): String?"))
    if (rust) {
        cek("1.2 RustCore.available true (mode rust, .so host)", harness.core.RustCore.available)
        val caps = harness.core.RustCore.rustCoreCapabilities()
        cek("1.3 capabilities: version 8 + toolsLeavesJson + modul tools_leaves", caps.contains("\"version\":8") && caps.contains("toolsLeavesJson") && caps.contains("\"tools_leaves\""))
        cek("1.4 ToolsLeavesSeam.rustToolsLeaves true (mode rust)", ToolsLeavesSeam.rustToolsLeaves)
        val glue = srcCore(coreDir, "src/jni_glue.rs")
        val nSym = glue.split("pub extern \"C\" fn Java_harness_core_RustCore_").size - 1
        cek("1.5 28 simbol JNI (27 lama + toolsLeavesJson)", nSym == 28)
        cek("1.6 seam stripHtml non-null + nilai (mode rust)", ToolsLeavesSeam.stripHtml("<p>x <b>y</b></p>") == "x y")
    } else {
        cek("1.2 RustCore.available false (mode kotlin, tanpa .so)", !harness.core.RustCore.available)
        cek("1.3 ToolsLeavesSeam.rustToolsLeaves false (mode kotlin)", !ToolsLeavesSeam.rustToolsLeaves)
        cek("1.4 seam stripHtml null tanpa .so (kontrak fallback)", ToolsLeavesSeam.stripHtml("<p>x</p>") == null)
        cek("1.5 seam parseDdg null tanpa .so", ToolsLeavesSeam.parseDdg("<html/>", "q") == null)
        cek("1.6 seam parseYtSearch null tanpa .so", ToolsLeavesSeam.parseYtSearch("<html/>") == null)
    }

    // ---- §2. stripHtml ----
    cek("2.1 strip tag sederhana", tStripHtml("<p>Hello <b>dunia</b></p>") == "Hello dunia")
    cek("2.2 script dibuang utuh", tStripHtml("<script>var x=1;</script>teks") == "teks")
    cek("2.3 script kapital (IGNORE_CASE)", tStripHtml("<SCRIPT>alert(1)</SCRIPT>teks") == "teks")
    cek("2.4 style dibuang utuh", tStripHtml("<style>.a{color:red}</style>teks") == "teks")
    cek("2.5 entitas urut (&nbsp; dulu)", tStripHtml("a&nbsp;b &amp; c &lt;d&gt; &quot;e&quot;") == "a b & c <d> \"e\"")
    cek("2.6 whitespace collapse + trim", tStripHtml("a   b\n\tc") == "a b c")
    cek("2.7 NBSP tengah TIDAK collapse (probe JVM)", tStripHtml("<p>a  b</p>") == "a  b" || tStripHtml("<p>a\u00A0b</p>") == "a\u00A0b")
    cek("2.8 NBSP tepi DIBUANG trim + kosong", tStripHtml("<p>  teks  </p>") == "teks" || tStripHtml("<p>\u00A0teks\u00A0</p>") == "teks")

    // ---- §3. parseDdg ----
    val ddgHtml = "<div><a class=\"result__a\" href=\"https://contoh.id/a\">Judul <b>Satu</b></a></div><a class=\"result__a\" href=\"https://contoh.id/b\">Dua</a>"
    cek("3.1 dua hasil + judul di-strip", tParseDdg(ddgHtml, "tes") == "- Judul Satu\n  https://contoh.id/a\n- Dua\n  https://contoh.id/b")
    cek("3.2 kosong -> pesan khas", tParseDdg("<html>tidak ada hasil</html>", "zzz") == "Tidak ada hasil terbaca untuk 'zzz' (coba web_fetch langsung ke situsnya).")
    val many = (1..7).joinToString("") { i -> "<a class=\"result__a\" href=\"https://x/$i\">T$i</a>" }
    val gotMany = tParseDdg(many, "q")
    cek("3.3 take(6): 7 link -> 6 hasil", gotMany.lines().size == 12 && gotMany.contains("https://x/6") && !gotMany.contains("https://x/7"))
    cek("3.4 class sesudah href TIDAK match (urutan pola)", tParseDdg("<a href=\"https://x\" class=\"result__a\">T</a>", "q") == "Tidak ada hasil terbaca untuk 'q' (coba web_fetch langsung ke situsnya).")
    cek("3.5 href relatif ikut apa adanya", tParseDdg("<a class=\"result__a\" href=\"/l/?uddg=x\">T</a>", "q") == "- T\n  /l/?uddg=x")
    cek("3.6 query kosong tetap parse (blank dicek webSearch)", tParseDdg("<a class=\"result__a\" href=\"https://x\">T</a>", "") == "- T\n  https://x")

    // ---- §4. truncateMarkedVc230 ----
    cek("4.1 pendek tidak terpotong", tTruncate("hello", 10, "") == "hello")
    cek("4.2 tepat cap tidak terpotong (<=)", tTruncate("hello", 5, "") == "hello")
    cek("4.3 potong + sisa char", tTruncate("hello world", 5, "") == "hello…(dipotong, sisa 6 char)…")
    cek("4.4 potong + resultId", tTruncate("hello world", 5, "abc") == "hello…(dipotong, sisa 6 char, pakai get_result[abc] untuk sisanya)…")
    cek("4.5 resultId blank -> tanpa idPart", tTruncate("hello world", 5, "   ") == "hello…(dipotong, sisa 6 char)…")
    cek("4.6 cap 0", tTruncate("ab", 0, "") == "…(dipotong, sisa 2 char)…")
    // Astral: bandingkan BYTE UTF-8 (kontrak wire) — kedua mode
    // harus menghasilkan lone surrogate yang sama (61 3F).
    val astral = "a\uD83D\uDE00b"
    val cut2 = tTruncate(astral, 2, "")
    cek("4.7 astral take(2) byte wire = 'a' + '?' (61 3F) + suffix", cut2.length > 2 && cut2.substring(0, 2).toByteArray(Charsets.UTF_8).contentEquals(byteArrayOf(0x61, 0x3F)) && cut2.contains("sisa 2 char"))

    // ---- §5. ytRunsText ----
    cek("5.1 null -> \"\"", tYtRuns(null) == "")
    cek("5.2 simpleText", tYtRuns(JSONObject("{\"simpleText\": \"Langsung\"}")) == "Langsung")
    cek("5.3 runs digabung", tYtRuns(JSONObject("{\"runs\": [{\"text\": \"A\"}, {\"text\": \"B\"}]}")) == "AB")
    cek("5.4 runs kosong -> simpleText kosong", tYtRuns(JSONObject("{\"runs\": []}")) == "")
    cek("5.5 runs blank -> \"x\" (trim)", tYtRuns(JSONObject("{\"runs\": [{\"text\": \"  \"}, {\"text\": \"x\"}]}")) == "x")
    cek("5.6 elemen bukan objek dilewati + koersi angka", tYtRuns(JSONObject("{\"runs\": [\"teks\", {\"text\": 5}]}")) == "5")

    // ---- §6. ytParseVideoRenderer ----
    cek("6.1 tanpa videoId -> null", tYtParse(JSONObject("{\"title\": {\"simpleText\": \"T\"}}"), null) == null)
    val vr1 = JSONObject("{\"videoId\": \"abc123\", \"title\": {\"runs\": [{\"text\": \"Judul\"}]}, \"longBylineText\": {\"simpleText\": \"Kanal\"}, \"lengthText\": {\"simpleText\": \"10:23\"}}")
    cek("6.2 normal + Durasi", tYtParse(vr1, null) == "YtHit(id=abc123, title=Judul, channel=Kanal, meta=Durasi: 10:23)")
    val vr2 = JSONObject("{\"videoId\": \"v2\", \"title\": {\"simpleText\": \"T2\"}, \"ownerText\": {\"simpleText\": \"Pemilik\"}}")
    cek("6.3 ownerText fallback + Durasi tidak diketahui", tYtParse(vr2, null) == "YtHit(id=v2, title=T2, channel=Pemilik, meta=Durasi: tidak diketahui)")
    cek("6.4 title/channel blank -> \"-\"", tYtParse(JSONObject("{\"videoId\": \"v3\"}"), null) == "YtHit(id=v3, title=-, channel=-, meta=Durasi: tidak diketahui)")
    val vr4 = JSONObject("{\"videoId\": \"v4\", \"title\": {\"simpleText\": \"T\"}, \"badges\": [{\"metadataBadgeRenderer\": {\"label\": \"LIVE\"}}], \"lengthText\": {\"simpleText\": \"1:00\"}}")
    cek("6.5 badge Live menang atas lengthText", tYtParse(vr4, null) == "YtHit(id=v4, title=T, channel=-, meta=Live)")
    val vr5 = JSONObject("{\"videoId\": \"v5\", \"title\": {\"simpleText\": \"T\"}, \"badges\": [{\"metadataBadgeRenderer\": {\"label\": \"Baru\"}}], \"lengthText\": {\"simpleText\": \"2:00\"}}")
    cek("6.6 badge bukan live -> lengthText", tYtParse(vr5, null) == "YtHit(id=v5, title=T, channel=-, meta=Durasi: 2:00)")
    cek("6.7 forceMeta menang", tYtParse(JSONObject("{\"videoId\": \"v6\", \"title\": {\"simpleText\": \"T\"}}"), "Short") == "YtHit(id=v6, title=T, channel=-, meta=Short)")
    cek("6.8 videoId blank (spasi) -> null", tYtParse(JSONObject("{\"videoId\": \"   \", \"title\": {\"simpleText\": \"T\"}}"), null) == null)

    // ---- §7. extractBalancedJson ----
    cek("7.1 objek bersarang", tExtract("xx {\"a\": {\"b\": 1}, \"c\": [1,2]} yy", 3) == "{\"a\": {\"b\": 1}, \"c\": [1,2]}")
    cek("7.2 kurung di dalam string diabaikan", tExtract("{\"a\": \"} {\" }", 0) == "{\"a\": \"} {\" }")
    cek("7.3 escape di dalam string", tExtract("{\"a\": \"x\\\"y\"} tail", 0) == "{\"a\": \"x\\\"y\"}")
    cek("7.4 tak-berakhir -> null", tExtract("{\"a\": 1", 0) == null)
    cek("7.5 start negatif -> null", tExtract("{\"a\": 1}", -1) == null)
    cek("7.6 start di luar teks -> null", tExtract("{\"a\": 1}", 99) == null)

    // ---- §8. ytWalkResults ----
    val walkWant = "[YtHit(id=v1, title=Satu, channel=K1, meta=Durasi: 3:00), YtHit(id=s1, title=Short Satu, channel=K2, meta=Short), YtHit(id=v2, title=Dua, channel=-, meta=Durasi: tidak diketahui)]"
    cek("8.1 walk: v1 + s1 Short + v2 (dedup + null dilewati)", tYtWalk(walkRootJson()) == walkWant)
    cek("8.2 struktur kosong -> []", tYtWalk(JSONObject("{}")) == "[]")
    cek("8.3 contents kosong -> []", tYtWalk(JSONObject("{\"contents\": {}}")) == "[]")
    cek("8.4 itemSection tanpa contents -> []", tYtWalk(JSONObject("{\"contents\": {\"twoColumnSearchResultsRenderer\": {\"primaryContents\": {\"sectionListRenderer\": {\"contents\": [{\"itemSectionRenderer\": {}}]}}}}}")) == "[]")

    // ---- §9. ytScanRenderers ----
    val scanHtml = "xx \"videoRenderer\":{\"videoId\":\"a1\",\"title\":{\"simpleText\":\"A\"},\"lengthText\":{\"simpleText\":\"1:00\"}} yy" +
        "\"videoRenderer\":{\"videoId\":\"a1\",\"title\":{\"simpleText\":\"Duplikat\"}}" +
        "\"videoRenderer\":{\"videoId\":\"a2\",\"title\":{\"simpleText\":\"B\"}}" +
        "\"videoRenderer\":{\"videoId\":\""
    cek("9.1 scan: dedup + rusak di ujung dilewati", tYtScan(scanHtml) == "[YtHit(id=a1, title=A, channel=-, meta=Durasi: 1:00), YtHit(id=a2, title=B, channel=-, meta=Durasi: tidak diketahui)]")
    cek("9.2 tanpa videoRenderer -> []", tYtScan("halaman biasa tanpa hasil") == "[]")
    val manyScan = (0..34).joinToString(" ") { i -> "\"videoRenderer\":{\"videoId\":\"v$i\",\"title\":{\"simpleText\":\"T$i\"}}" }
    cek("9.3 cap 30 hit", tYtScan(manyScan).let { it.startsWith("[YtHit(id=v0") && it.contains("v29") && !it.contains("v30") })
    cek("9.4 renderer tanpa videoId dilewati", tYtScan("\"videoRenderer\":{\"title\":{\"simpleText\":\"X\"}}") == "[]")

    // ---- §10. parseYtSearch ----
    val ytInitHtml = "awalan var ytInitialData = " + walkRootJson().toString() + "; akhiran"
    cek("10.1 ytInitialData -> walk", tParseYt(ytInitHtml) == walkWant)
    cek("10.2 tanpa ytInitialData -> scan", tParseYt("x \"videoRenderer\":{\"videoId\":\"z9\",\"title\":{\"simpleText\":\"Z\"}} y") == "[YtHit(id=z9, title=Z, channel=-, meta=Durasi: tidak diketahui)]")
    cek("10.3 walk kosong -> scan fallback", tParseYt("ytInitialData = {\"contents\":{}} \"videoRenderer\":{\"videoId\":\"q1\",\"title\":{\"simpleText\":\"Q\"}}") == "[YtHit(id=q1, title=Q, channel=-, meta=Durasi: tidak diketahui)]")
    cek("10.4 tidak ada apa-apa -> []", tParseYt("halaman kosong") == "[]")

    // ---- §11. formatYtResults ----
    val hitsForFormat = callTools("parseYtSearch", arrayOf(String::class.java), ytInitHtml)
    cek("11.1 format 3 hit + [Shorts]", tFormatYt(hitsForFormat, 5) == "1. Satu\n   Channel: K1 | Durasi: 3:00\n   https://www.youtube.com/watch?v=v1\n2. Short Satu [Shorts]\n   Channel: K2 | Short\n   https://www.youtube.com/watch?v=s1\n3. Dua\n   Channel: - | Durasi: tidak diketahui\n   https://www.youtube.com/watch?v=v2")
    cek("11.2 coerceIn: 0 -> 1", tFormatYt(hitsForFormat, 0).lines().size == 3)
    cek("11.3 coerceIn: 99 -> 10 (tetap 3 hit)", tFormatYt(hitsForFormat, 99).lines().size == 9)
    cek("11.4 limit 1 -> hanya hit pertama", tFormatYt(hitsForFormat, 1).lines().size == 3 && tFormatYt(hitsForFormat, 1).contains("watch?v=v1") && !tFormatYt(hitsForFormat, 1).contains("watch?v=s1"))
    cek("11.5 kosong -> \"\"", tFormatYt(callTools("parseYtSearch", arrayOf(String::class.java), "kosong"), 5) == "")

    // ---- §12. *Kotlin identik merge/ vc316 ----
    cek("12.1 stripHtmlKotlin == merge stripHtml", sameFunBodyNorm(toolsSrc, "private fun stripHtmlKotlin", mergeTools, "private fun stripHtml"))
    cek("12.2 truncateMarkedVc230Kotlin == merge truncateMarkedVc230", sameFunBodyNorm(toolsSrc, "private fun truncateMarkedVc230Kotlin", mergeTools, "private fun truncateMarkedVc230"))
    cek("12.3 ytRunsTextKotlin == merge ytRunsText", sameFunBodyNorm(toolsSrc, "private fun ytRunsTextKotlin", mergeTools, "private fun ytRunsText"))
    cek("12.4 ytParseVideoRendererKotlin == merge ytParseVideoRenderer", sameFunBodyNorm(toolsSrc, "private fun ytParseVideoRendererKotlin", mergeTools, "private fun ytParseVideoRenderer"))
    cek("12.5 extractBalancedJsonKotlin == merge extractBalancedJson", sameFunBodyNorm(toolsSrc, "private fun extractBalancedJsonKotlin", mergeTools, "private fun extractBalancedJson"))
    cek("12.6 ytWalkResultsKotlin == merge ytWalkResults", sameFunBodyNorm(toolsSrc, "private fun ytWalkResultsKotlin", mergeTools, "private fun ytWalkResults"))
    cek("12.7 ytScanRenderersKotlin == merge ytScanRenderers", sameFunBodyNorm(toolsSrc, "private fun ytScanRenderersKotlin", mergeTools, "private fun ytScanRenderers"))
    cek("12.8 parseYtSearchKotlin == merge parseYtSearch", sameFunBodyNorm(toolsSrc, "private fun parseYtSearchKotlin", mergeTools, "private fun parseYtSearch"))
    cek("12.9 parseDdgKotlin fragmen (regex DDG + pesan kosong)", toolsSrc.contains("class=\\\"result__a\\\"") && toolsSrc.contains("Tidak ada hasil terbaca untuk"))
    cek("12.10 formatYtResultsKotlin fragmen (coerceIn + Shorts)", toolsSrc.contains("val limit = maxResults.coerceIn(1, 10)") && toolsSrc.contains("shortsVc246"))
    cek("12.11 webSearch fragmen (URL DDG + parseDdg + catch)", toolsSrc.contains("https://html.duckduckgo.com/html/?q=") && toolsSrc.contains("parseDdg(html, query)") && toolsSrc.contains("Pencarian gagal:"))
    cek("12.12 searchYoutube fragmen (URL YT + consent + parse + format)", toolsSrc.contains("https://www.youtube.com/results?search_query=") && toolsSrc.contains("consent.youtube.com") && toolsSrc.contains("parseYtSearch(html)") && toolsSrc.contains("formatYtResults(results, maxResults)") && toolsSrc.contains("Pencarian YouTube gagal (jaringan):"))

    // ---- §13. Batas slice 10 ----
    cek("13.1 dispatcher execute tetap Kotlin", toolsSrc.contains("fun execute(ctx: Context, name: String, args: JSONObject, callId: String"))
    cek("13.2 daun Android tetap Kotlin (SMS/kalender/log/lokasi/clipboard/UsageStats/alarm)", toolsSrc.contains("SmsManager") && toolsSrc.contains("CalendarContract") && toolsSrc.contains("CallLog") && toolsSrc.contains("LocationManager") && toolsSrc.contains("ClipboardManager") && toolsSrc.contains("UsageStatsManager") && toolsSrc.contains("AlarmClock"))
    cek("13.3 daun jaringan tetap Kotlin (httpGet/httpRequest)", toolsSrc.contains("private fun httpGet(") && toolsSrc.contains("private fun httpGetStatus(") && toolsSrc.contains("private fun httpRequest("))
    cek("13.4 Tools.kt tanpa DSML (DSML bukan di sini)", !toolsSrc.contains("DSML"))
    val agentPromptRs = srcCore(coreDir, "src/agent_prompt.rs")
    cek("13.5 DSML sudah Rust slice 6 (agent_prompt.rs)", agentPromptRs.contains("stripRawToolMarkupForDisplay") && agentPromptRs.contains("normalizeDsmlDelimiters"))
    cek("13.6 daun murni lain tetap Kotlin tanpa Rust (slugify/ruleKey/parseDateRange)", toolsSrc.contains("fun slugifySkillName(") && toolsSrc.contains("fun ruleKey(") && toolsSrc.contains("private fun parseDateRange(") && !toolsSrc.contains("slugifySkillNameKotlin") && !toolsSrc.contains("ruleKeyKotlin"))
    val flavorSrc = src(treeDir, "Flavor.kt")
    val dsApiSrc = src(treeDir, "DsApiClient.kt")
    cek("13.7 Flavor.kt tanpa sentuhan slice 10", !flavorSrc.contains("ToolsLeaves") && !flavorSrc.contains("toolsLeaves") && !flavorSrc.contains("Vc326") && !flavorSrc.contains("vc326"))
    cek("13.8 DsApiClient.kt tanpa sentuhan slice 10 (split-nya slice 11)", !dsApiSrc.contains("ToolsLeaves") && !dsApiSrc.contains("toolsLeaves") && !dsApiSrc.contains("Vc326") && !dsApiSrc.contains("vc326"))
    val cargoToml = srcCore(coreDir, "Cargo.toml")
    cek("13.9 Cargo.toml tanpa tokio/dep baru", !cargoToml.contains("tokio") && cargoToml.contains("regex = \"1\"") && cargoToml.contains("serde_json = \"1\""))
    val toolsRs = srcCore(coreDir, "src/tools_leaves.rs")
    cek("13.10 tools_leaves.rs forbid(unsafe_code) + 10 op", toolsRs.contains("#![forbid(unsafe_code)]") && toolsRs.contains("\"strip_html\"") && toolsRs.contains("\"parse_ddg\"") && toolsRs.contains("\"truncate_marked\"") && toolsRs.contains("\"yt_runs_text\"") && toolsRs.contains("\"yt_parse_video_renderer\"") && toolsRs.contains("\"extract_balanced_json\"") && toolsRs.contains("\"yt_walk_results\"") && toolsRs.contains("\"yt_scan_renderers\"") && toolsRs.contains("\"parse_yt_search\"") && toolsRs.contains("\"format_yt_results\""))

    println("TOTAL $total LULUS $lulus GAGAL $gagal")
    if (gagal > 0) kotlin.system.exitProcess(1)
}
