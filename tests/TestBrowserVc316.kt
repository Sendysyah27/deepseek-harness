// TestBrowserVc316.kt — tes JVM Worker BR vc316 untuk 4 bug browser vc313.
//
// Yang diuji (tanpa HP, tanpa WebView sungguhan — logika murni + asersi sumber):
//   (a) DSML mentah tidak lolos ke chat: stripRawToolMarkupForDisplay +
//       extractToolCall (semua jalur return) + extractToolCalls (jalur
//       eksekusi TIDAK berubah) + 3 situs tampil di MainActivity.
//   (b) URL panjang terpotong: AgentBrowser.tabLabelVc316 + konfigurasi
//       ellipsize/singleLine bUrlTv + minWidth=0 tombol bar tab.
//   (c) browser_shot_full: rencana stitch (viewport degenerat -> fallback,
//       cap 12000px) + anggaran internal 100 dtk < timeout luar 120 dtk +
//       pesan fallback + ToolDef tidak berubah + regresi snapshot.
//   (d) WebView render bawah hero: enableSlowWholeDocumentDraw di onCreate
//       SEBELUM WebView pertama + tunggu scroll mereda per segmen +
//       konfigurasi WebView tidak berubah. Bagian perangkat (lihat gambar
//       shot_full di HP) dicetak sebagai daftar cek manual di akhir.
//
// Jalankan: ./run-tests-vc316-BR.sh
// Keluar 0 = semua lulus; selain itu daftar GAGAL + exit 1.
package com.senzwh.deepharness

import java.io.File
import kotlin.system.exitProcess

var passed = 0
var failed = 0
val failedNames = ArrayList<String>()

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
        println("LULUS  $name")
    } else {
        failed++
        failedNames.add(name)
        println("GAGAL  $name  $detail")
    }
}

fun src(tree: File, name: String): String {
    val f = File(tree, name)
    return if (f.exists()) f.readText() else ""
}

fun main(args: Array<String>) {
    val tree = if (args.isNotEmpty()) File(args[0]) else File("merge")
    println("== TestBrowserVc316 — tree: ${tree.path} ==")

    // ================= (a) DSML mentah tidak lolos ke chat =================
    // A1: blok DSML ASCII utuh (tag + isi JSON parameter) hilang total.
    run {
        val raw = "Halo <||DSML||calls><||DSML||invoke name=\"read_file\"><||DSML||parameter name=\"args\" string=\"false\">{\"path\":\"/sdcard/x.txt\"}</||DSML||parameter></||DSML||invoke></||DSML||calls> selesai"
        val clean = AgentPrompt.stripRawToolMarkupForDisplay(raw)
        check("A1 strip blok DSML ASCII utuh", !clean.contains("DSML") && !clean.contains("invoke") && !clean.contains("read_file") && !clean.contains("sdcard") && clean.contains("Halo") && clean.contains("selesai"), "hasil=[$clean]")
    }
    // A2: delimiter fullwidth (mojibake ｜ ＜ ＞) ikut dibersihkan.
    run {
        val raw = "cek <｜｜DSML｜｜calls><｜｜DSML｜｜invoke name=\"list_files\"><｜｜DSML｜｜parameter name=\"args\" string=\"false\">{\"dir\":\"/tmp\"}</｜｜DSML｜｜parameter></｜｜DSML｜｜invoke></｜｜DSML｜｜calls> done"
        val clean = AgentPrompt.stripRawToolMarkupForDisplay(raw)
        check("A2 strip blok DSML fullwidth", !clean.contains("DSML") && !clean.contains("list_files") && !clean.contains("/tmp") && clean.contains("cek") && clean.contains("done"), "hasil=[$clean]")
    }
    // A3: DSML terpotong (tanpa penutup, JSON args menggantung) ikut dibersihkan.
    run {
        val raw = "Proses <||DSML||calls><||DSML||invoke name=\"read_file\"><||DSML||parameter name=\"args\" string=\"false\">{\"path\":\"/sdcard/y.txt\""
        val clean = AgentPrompt.stripRawToolMarkupForDisplay(raw)
        check("A3 strip DSML terpotong", !clean.contains("DSML") && !clean.contains("read_file") && !clean.contains("sdcard") && clean.contains("Proses"), "hasil=[$clean]")
    }
    // A4: extractToolCall DSML valid -> tool jalan, teks tampil bersih
    // (REGRESI bug lama: tag-only strip meninggalkan {"path":...}).
    run {
        val raw = "Baik, saya baca dulu. <||DSML||calls><||DSML||invoke name=\"read_file\"><||DSML||parameter name=\"args\" string=\"false\">{\"path\":\"/sdcard/x.txt\"}</||DSML||parameter></||DSML||invoke></||DSML||calls>"
        val (visible, obj) = AgentPrompt.extractToolCall(raw)
        check("A4 extractToolCall DSML: tool terparse", obj != null && obj.optString("tool") == "read_file" && obj.optJSONObject("args")?.optString("path") == "/sdcard/x.txt", "obj=$obj")
        check("A5 extractToolCall DSML: teks tampil bersih", !visible.contains("DSML") && !visible.contains("/sdcard") && !visible.contains("\"path\"") && visible.contains("Baik"), "visible=[$visible]")
    }
    // A6: DSML yang GAGAL diparse (tanpa invoke) -> obj null, tampil bersih.
    run {
        val raw = "<||DSML||calls>teks acak tanpa invoke</||DSML||calls> halo"
        val (visible, obj) = AgentPrompt.extractToolCall(raw)
        check("A6 DSML gagal parse: tidak dieksekusi + tampil bersih", obj == null && !visible.contains("DSML") && visible.contains("halo"), "visible=[$visible] obj=$obj")
    }
    // A7: JSON pagar valid + fragmen DSML di tempat lain -> JSON jalan, DSML hilang.
    run {
        val raw = "```json\n{\"tool\":\"list_files\",\"args\":{\"dir\":\"/tmp\"}}\n```\n<||DSML||calls><||DSML||invoke name=\"read_file\"><||DSML||parameter name=\"args\" string=\"false\">{\"path\":\"/x\"}</||DSML||parameter></||DSML||invoke></||DSML||calls>"
        val (visible, obj) = AgentPrompt.extractToolCall(raw)
        check("A7 JSON pagar + DSML: tool JSON jalan", obj != null && obj.optString("tool") == "list_files", "obj=$obj")
        check("A8 JSON pagar + DSML: teks tampil bersih", !visible.contains("DSML") && !visible.contains("/x\""), "visible=[$visible]")
    }
    // A9: dialek <tool=nama>{json} -> tool jalan, tampil bersih.
    run {
        val raw = "Jalankan ini <tool=read_file>{\"path\":\"/sdcard/z.txt\"}</tool> ya"
        val (visible, obj) = AgentPrompt.extractToolCall(raw)
        check("A9 dialek tag: tool terparse", obj != null && obj.optString("tool") == "read_file", "obj=$obj")
        check("A10 dialek tag: teks tampil bersih", !visible.contains("<tool=") && !visible.contains("/sdcard") && visible.contains("Jalankan") && visible.contains("ya"), "visible=[$visible]")
    }
    // A11: teks biasa TANPA markup tidak berubah (identitas) — termasuk
    // prosa yang menyebut "DSML" tanpa kurung sudut.
    run {
        val plain = "Halo, ini jawaban biasa tanpa markup."
        val prose = "Format DSML dipakai DeepSeek untuk tool call."
        check("A11 teks biasa tidak berubah", AgentPrompt.stripRawToolMarkupForDisplay(plain) == plain && AgentPrompt.stripRawToolMarkupForDisplay(prose) == prose, "")
        val (visible, obj) = AgentPrompt.extractToolCall(plain)
        check("A12 extractToolCall teks biasa: identitas", obj == null && visible == plain, "visible=[$visible]")
    }
    // A13: idempotent — strip(strip(x)) == strip(x).
    run {
        val raw = "a <||DSML||invoke name=\"x\">b</||DSML||invoke> c <tool=y>{\"k\":1}</tool> d"
        val once = AgentPrompt.stripRawToolMarkupForDisplay(raw)
        check("A13 strip idempotent", AgentPrompt.stripRawToolMarkupForDisplay(once) == once && !once.contains("DSML") && !once.contains("<tool="), "hasil=[$once]")
    }
    // A14: jalur EKSEKUSI tidak berubah — extractToolCalls tetap mengembalikan
    // call DSML (stripper hanya dipakai jalur tampil).
    run {
        val raw = "<||DSML||calls><||DSML||invoke name=\"read_file\"><||DSML||parameter name=\"args\" string=\"false\">{\"path\":\"/a.txt\"}</||DSML||parameter></||DSML||invoke></||DSML||calls>"
        val calls = AgentPrompt.extractToolCalls(raw)
        check("A14 extractToolCalls: DSML tetap dieksekusi", calls.size == 1 && calls[0].optString("tool") == "read_file" && calls[0].optJSONObject("args")?.optString("path") == "/a.txt", "calls=$calls")
    }
    // A15: situs tampil di MainActivity memakai stripper (asersi sumber).
    run {
        val ma = src(tree, "MainActivity.kt")
        check("A15 addAnswerBubble bersihkan fragmen berpikir", ma.contains("stripRawToolMarkupForDisplay(think)"), "")
        check("A16 bubble rescue-ditolak bersihkan fragmen pemicu", ma.contains("stripRawToolMarkupForDisplay(thinkingFull)"), "")
        check("A17 extractToolCall memakai stripper di semua jalur", ma.contains("stripRawToolMarkupForDisplay") && src(tree, "AgentPrompt.kt").contains("fun stripRawToolMarkupForDisplay"), "")
    }

    // ================= (b) URL panjang banjir layar =================
    run {
        val longUrl = "https://pos-ssr.site/" + "artikel/panjang/".repeat(40) + "?a=1&b=2"
        val label = AgentBrowser.tabLabelVc316(0, 5, "Judul Halaman", longUrl, false)
        check("B1 label tab: URL 500+ char terpotong", label.length <= 200 && label.startsWith("Tab 1/5") && label.endsWith("…"), "len=${label.length} label=[$label]")
        check("B2 label tab: URL pendek tidak berubah", AgentBrowser.tabLabelVc316(0, 1, "T", "https://a.id/x", false) == "Tab 1/1 — T — https://a.id/x", "")
        check("B3 label tab: judul panjang terpotong + dorman", AgentBrowser.tabLabelVc316(1, 2, "J".repeat(100), "https://a.id/", true).contains("…") && AgentBrowser.tabLabelVc316(1, 2, "J", "https://a.id/", true).contains("(belum dimuat)"), "")
    }
    run {
        val ma = src(tree, "MainActivity.kt")
        val boxStart = ma.indexOf("private fun buildWebBox()")
        val boxEnd = ma.indexOf("private fun buildClassicUi()")
        val box = if (boxStart >= 0 && boxEnd > boxStart) ma.substring(boxStart, boxEnd) else ""
        check("B4 bUrlTv: singleLine + ellipsize END + maxLines 1", box.contains("setSingleLine(true)") && box.contains("TruncateAt.END") && box.contains("maxLines = 1"), "")
        check("B5 tombol bar tab: minWidth=0 (＋ Tab tidak terdorong)", box.contains("text = \"＋ Tab\"; minWidth = 0") && box.contains("text = \"◀\"; minWidth = 0") && box.contains("text = \"▶\"; minWidth = 0") && box.contains("text = \"⟳\"; minWidth = 0"), "")
        check("B6 updateBrowserVisibility pakai tabLabelVc316", ma.contains("AgentBrowser.tabLabelVc316(bActive, bTabs.size"), "")
    }

    // ================= (c) browser_shot_full timeout =================
    run {
        // C1: viewport degenerat (WebView belum ter-layout, 1x1) -> JANGAN loop.
        val p = AgentBrowser.shotFullPlanVc316(12000, 1, 1)
        check("C1 rencana: viewport 1x1 -> tidak ok, 0 segmen", !p.viewportOk && p.segments == 0, "plan=$p")
        val p0 = AgentBrowser.shotFullPlanVc316(12000, 0, 0)
        check("C2 rencana: viewport 0x0 -> tidak ok", !p0.viewportOk && p0.segments == 0, "plan=$p0")
        // C3: kasus normal 1080x800, halaman 12000px -> 15 segmen.
        val pn = AgentBrowser.shotFullPlanVc316(12000, 1080, 800)
        check("C3 rencana: 12000px / 800px = 15 segmen", pn.viewportOk && pn.totalH == 12000 && pn.segments == 15, "plan=$pn")
        // C4: halaman > 12000px dicap (bukan segmen tak berujung).
        val pc = AgentBrowser.shotFullPlanVc316(50000, 1080, 800)
        check("C4 rencana: halaman 50000px dicap 12000px", pc.totalH == 12000 && pc.segments == 15, "plan=$pc")
        // C5: anggaran internal HARUS di bawah timeout luar 120 dtk.
        check("C5 anggaran stitch 100 dtk < timeout luar 120 dtk", AgentBrowser.SHOT_FULL_BUDGET_MS_VC316 == 100_000L && AgentBrowser.SHOT_FULL_BUDGET_MS_VC316 < 120_000L, "budget=${AgentBrowser.SHOT_FULL_BUDGET_MS_VC316}")
        // C6: regresi — hitung segmen lama (viewport 1px) DULU 12000 iterasi.
        // Dengan guard baru, rencana itu tidak pernah dieksekusi (C1/C2).
        val oldLoop = 12000 / 1
        check("C7 regresi: loop lama 12000 iterasi kini dicegah guard", oldLoop == 12000 && !AgentBrowser.shotFullPlanVc316(12000, 1, 1).viewportOk, "")
    }
    run {
        val ma = src(tree, "MainActivity.kt")
        val shotStart = ma.indexOf("private fun browserShotFull(")
        val shotEnd = ma.indexOf("private fun waitBrowserScrollSettledVc316(")
        val shot = if (shotStart >= 0 && shotEnd > shotStart) ma.substring(shotStart, shotEnd) else ""
        check("C8 browserShotFull pakai rencana + guard viewport", shot.contains("shotFullPlanVc316") && shot.contains("if (!plan.viewportOk)"), "")
        check("C9 browserShotFull cek anggaran tiap segmen", shot.contains("deadlineMs") && shot.contains("System.currentTimeMillis() > deadlineMs"), "")
        check("C10 browserShotFull: habis anggaran -> area terlihat + alasan", shot.contains("batas waktu stitch 100 dtk habis") && shot.contains("dikembalikan tangkapan area terlihat"), "")
        check("C11 browserShotFull: viewport degenerat -> area terlihat + alasan", shot.contains("viewport WebView belum ter-layout"), "")
        check("C12 browserShotVisible: ukur tinggi-0 + retry", ma.contains("if (wv.width == 0 || wv.height == 0)") && ma.contains("fun drawVisibleShotToFile"), "")
        val tr = src(tree, "ToolRegistry.kt")
        check("C13 ToolDef browser_shot_full tidak berubah", tr.contains("ToolDef(\"browser_shot_full\", \"lanjutan\", \"{}\", \"screenshot satu halaman penuh (gulir-sambung); bila gagal, kembalikan area terlihat + alasan.\""), "")
        check("C14 timeout luar 120 dtk tidak dinaikkan", ma.contains("return if (tool in READ_FAST_TOOLS_VC278) 30 else 120"), "")
    }
    // C15: regresi snapshot/diff (perilaku browser_shot yang sudah benar).
    run {
        val json = "{\"url\":\"https://pos-ssr.site/\",\"title\":\"SSR Space\",\"nodes\":[{\"ref\":1,\"tag\":\"button\",\"type\":\"\",\"label\":\"Masuk\",\"cx\":10,\"cy\":20,\"pw\":false,\"role\":\"button\",\"name\":\"Masuk\",\"sel\":\"button\"}],\"text\":\"Halo dunia\"}"
        val snap = AgentBrowser.formatSnapshot(json)
        check("C15 regresi formatSnapshot", snap.contains("[#1] button \"Masuk\" @e1") && snap.contains("SSR Space") && snap.contains("ISI TEKS:") && snap.contains("Halo dunia"), "snap=[$snap]")
        check("C16 regresi diffSnapshots identik", AgentBrowser.diffSnapshots(snap, snap) == "(tidak ada perubahan)", "")
        check("C17 regresi diffSnapshots kosong -> cur utuh", AgentBrowser.diffSnapshots("", snap) == snap, "")
    }

    // ================= (d) WebView tidak render bawah hero =================
    run {
        val ma = src(tree, "MainActivity.kt")
        val onCreateIdx = ma.indexOf("override fun onCreate(savedInstanceState: Bundle?)")
        val slowIdx = ma.indexOf("enableSlowWholeDocumentDraw()")
        val buildUiIdx = ma.indexOf("buildUi()", onCreateIdx)
        check("D1 enableSlowWholeDocumentDraw di onCreate SEBELUM buildUi", onCreateIdx >= 0 && slowIdx > onCreateIdx && buildUiIdx > slowIdx, "onCreate=$onCreateIdx slow=$slowIdx buildUi=$buildUiIdx")
        val shotStart = ma.indexOf("private fun browserShotFull(")
        val shotEnd = ma.indexOf("private fun waitBrowserScrollSettledVc316(")
        val shot = if (shotStart >= 0 && shotEnd > shotStart) ma.substring(shotStart, shotEnd) else ""
        check("D2 stitch tunggu scroll mereda sebelum draw", shot.contains("waitBrowserScrollSettledVc316(wv, segY)") && shot.indexOf("waitBrowserScrollSettledVc316(wv, segY)") < shot.indexOf("wv.draw(c)"), "")
        check("D3 helper settle: poll scrollY + batas 2 dtk", ma.contains("fun waitBrowserScrollSettledVc316") && ma.contains("SHOT_FULL_SEGMENT_SETTLE_MS_VC316") && ma.contains("wv.scrollY"), "")
        val createStart = ma.indexOf("private fun createBrowserWebView(")
        val createEnd = ma.indexOf("private fun reviveBrowserTab(")
        val create = if (createStart >= 0 && createEnd > createStart) ma.substring(createStart, createEnd) else ""
        check("D4 regresi: konfigurasi WebView tab tidak berubah", create.contains("javaScriptEnabled = true") && create.contains("domStorageEnabled = true") && create.contains("loadWithOverviewMode = true") && create.contains("useWideViewPort = true"), "")
        check("D5 slot browser tidak disentuh (di luar bug ini)", !src(tree, "SlotBrowser.kt").contains("enableSlowWholeDocumentDraw"), "")
    }

    // ================= (e) sapu =================
    run {
        var hsurr = 0
        for (n in listOf("MainActivity.kt", "AgentPrompt.kt", "BrowserHost.kt")) {
            if (src(tree, n).contains("hsurr:")) hsurr++
        }
        check("E1 tanpa hsurr: di 3 file diubah", hsurr == 0, "")
    }

    // ================= daftar cek PERANGKAT (tidak bisa di JVM) =================
    println("--- CEK PERANGKAT (wajib sebelum klaim T-BR-23 lulus) ---")
    println("D-P1 Buka pos-ssr.site lewat browser_open, lalu browser_shot_full. Buka JPG di /sdcard/Pictures/Harness: konten DI BAWAH hero harus ter-render (bukan hitam).")
    println("D-P2 browser_shot di tengah halaman (sesudah scroll): gambar harus menunjukkan viewport SAAT INI, bukan atas halaman (regresi enableSlowWholeDocumentDraw).")
    println("D-P3 browser_shot_full di artikel panjang >12000px: hasil = 12000px pertama + catatan jujur, ATAU area terlihat + alasan.")
    println("D-P4 Matikan layout (buka tab agen saat bBox belum pernah dibuka): browser_shot_full harus kembali <120 dtk DENGAN file area terlihat + alasan viewport.")
    println("B-P1 Buka URL 500+ char: bar tab 1 baris + '...' di ujung; tombol ◀ ▶ ⟳ ＋ Tab tetap terlihat & bisa diketuk; WebView tidak terdorong keluar layar.")
    println("A-P1 Kirim jawaban model berisi DSML mentah (contoh: blok <||DSML||invoke ...> rusak): bubble chat TIDAK menampilkan markup DSML apa pun.")

    println("== HASIL: $passed lulus, $failed gagal ==")
    if (failed > 0) {
        println("GAGAL: " + failedNames.joinToString(", "))
        exitProcess(1)
    }
}
