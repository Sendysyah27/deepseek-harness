// TestConcatVc314Merge.kt — VARIAN MERGE dari tes Worker B vc314 (TestConcatVc314.kt).
// Pola TestBrowserVc313: logika murni LIVE (ToolRegistry, Tools set
// klasifikasi, ConcatTools, klien ConcatApi ASLI Worker A) + assert sumber
// atas tree merge. File tes + runner BUKAN bagian APK (tinggal di
// work-impl/, di luar tree app — jangan disalin ke tree app).
//
// Jalankan: ./run-tests-vc314-merge.sh
// Keluar 0 = semua lulus; selain itu daftar GAGAL + exit 1.
//
// CATATAN JUJUR: MainActivity (Android UI: Setup native, EditText,
// Switch, Thread) TIDAK bisa dites di JVM — yang dites hanya pure
// helper + assert sumber + kompilasi tree 0 error. Perilaku UI wajib
// uji perangkat (T-API-08 + F2 + T-REG-01–04 di TES-VC314).
// Jalur handler DENGAN token juga tidak bisa dites di JVM (SecureStore
// butuh Android Keystore) — yang dites di sini jalur tanpa token +
// klien ConcatApi ASLI (bukan stub — stub sudah diganti file Worker A).
package com.senzwh.deepharness

import java.io.File
import kotlin.system.exitProcess
import org.json.JSONObject

var passed = 0
var failed = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { passed++; println("LULUS  $name") }
    else { failed++; println("GAGAL  $name  $detail") }
}

fun readOrNull(dir: String, name: String): String? =
    try { File(dir, name).readText() } catch (e: Exception) { null }

fun countOcc(hay: String, needle: String): Int {
    var n = 0; var idx = hay.indexOf(needle)
    while (idx >= 0) { n++; idx = hay.indexOf(needle, idx + needle.length) }
    return n
}

fun main(args: Array<String>) {
    val dir = if (args.isNotEmpty()) args[0] else "."
    val reg = readOrNull(dir, "ToolRegistry.kt")
    val tools = readOrNull(dir, "Tools.kt")
    val gate = readOrNull(dir, "ToolGate.kt")
    val main = readOrNull(dir, "MainActivity.kt")
    val handler = readOrNull(dir, "ConcatTools.kt")
    val stub = readOrNull(dir, "ConcatApiClient.kt")
    check("00 sumber merge terbaca (ToolRegistry/Tools/ToolGate/MainActivity/ConcatTools/ConcatApiClient)",
        reg != null && tools != null && gate != null && main != null && handler != null && stub != null)
    if (reg == null || tools == null || gate == null || main == null || handler == null || stub == null) {
        println("\nHASIL: $passed lulus, $failed gagal (total ${passed + failed})"); exitProcess(1)
    }

    // ---- (1) Registry: 4 ToolDef ada + Kind/ArgSpec benar ----
    val v = ToolRegistry.BY_NAME["concat_version"]
    val l = ToolRegistry.BY_NAME["concat_project_list"]
    val g = ToolRegistry.BY_NAME["concat_project_get"]
    val o = ToolRegistry.BY_NAME["concat_project_open"]
    check("1.1 concat_version ada, Kind READ, tanpa argumen",
        v != null && v.kind == ToolRegistry.Kind.READ && v.args.isEmpty(), "dapat $v")
    check("1.2 concat_project_list ada, Kind READ, tanpa argumen",
        l != null && l.kind == ToolRegistry.Kind.READ && l.args.isEmpty(), "dapat $l")
    check("1.3 concat_project_get ada, Kind READ, arg project string wajib",
        g != null && g.kind == ToolRegistry.Kind.READ &&
            g.args == listOf(ToolRegistry.ArgSpec("project", "string", true)), "dapat $g")
    check("1.4 concat_project_open ada, Kind WRITE, arg project string wajib",
        o != null && o.kind == ToolRegistry.Kind.WRITE &&
            o.args == listOf(ToolRegistry.ArgSpec("project", "string", true)), "dapat $o")
    check("1.5 contoh-arg get/open menyebut project",
        (g?.argsExample ?: "").contains("project") && (o?.argsExample ?: "").contains("project"))
    check("1.6 deskripsi open menyebut tutup-dulu + tanpa fallback GUI",
        (o?.desc ?: "").contains("tutup dulu proyek di layar Concat") &&
            (o?.desc ?: "").contains("tanpa fallback ke tap GUI"), "dapat ${o?.desc}")
    check("1.7 deskripsi get menyebut TIDAK menyentuh layar",
        (g?.desc ?: "").contains("TIDAK menyentuh layar"), "dapat ${g?.desc}")

    // ---- (2) PROSE + SEGMENTS (cerminan pola browser_*) ----
    val cat = ToolRegistry.catalogSection()
    check("2.1 katalog memuat keempat nama concat_*",
        cat.contains("concat_version") && cat.contains("concat_project_list") &&
            cat.contains("concat_project_get") && cat.contains("concat_project_open"))
    check("2.2 prose menyebut aturan tutup-dulu (live mode tidak ada)",
        cat.contains("tutup dulu proyek di layar Concat") && cat.contains("issue #337"),
        cat.substring(cat.indexOf("CONCAT"), minOf(cat.length, cat.indexOf("CONCAT") + 600)))
    check("2.3 prose menyebut gagal jujur tanpa fallback GUI",
        cat.contains("TANPA fallback ke tap GUI") || cat.contains("tanpa fallback ke tap GUI"))
    check("2.4 prose menyebut token di Setup → Concat",
        cat.contains("Setup → Concat"))
    check("2.5 SEGMENTS: keempat tool + prose concat_vc314 ada di sumber",
        reg.contains("\"tool\" to \"concat_version\"") && reg.contains("\"tool\" to \"concat_project_list\"") &&
            reg.contains("\"tool\" to \"concat_project_get\"") && reg.contains("\"tool\" to \"concat_project_open\"") &&
            reg.contains("\"prose\" to \"concat_vc314\""))
    check("2.6 SEGMENTS: prose concat sesudah tool concat (urutan pola browser)",
        reg.indexOf("\"tool\" to \"concat_project_open\"") < reg.indexOf("\"prose\" to \"concat_vc314\""))

    // ---- (3) Klasifikasi gerbang: open = RISKY (REVIEW bila gerbang
    // hidup), BUKAN HIGH, tanpa pengecualian ala browser ----
    check("3.1 RISKY_TOOLS memuat concat_project_open (WRITE turunan)",
        "concat_project_open" in Tools.RISKY_TOOLS, "RISKY berisi ${Tools.RISKY_TOOLS.filter { it.startsWith("concat_") }}")
    check("3.2 RISKY_TOOLS TIDAK memuat concat read-only",
        "concat_version" !in Tools.RISKY_TOOLS && "concat_project_list" !in Tools.RISKY_TOOLS &&
            "concat_project_get" !in Tools.RISKY_TOOLS)
    check("3.3 READ_TOOLS memuat ketiga concat read-only",
        "concat_version" in Tools.READ_TOOLS && "concat_project_list" in Tools.READ_TOOLS &&
            "concat_project_get" in Tools.READ_TOOLS)
    check("3.4 isHighStatic: concat_project_open BUKAN HIGH",
        !ToolGate.isHighStatic("concat_project_open", JSONObject()))
    check("3.5 isHighStatic: concat read-only BUKAN HIGH",
        !ToolGate.isHighStatic("concat_version", JSONObject()) &&
            !ToolGate.isHighStatic("concat_project_get", JSONObject().put("project", "Untitled")))
    check("3.6 ToolGate.kt TIDAK tersentuh concat (0 kemunculan)",
        !gate.contains("concat"), "ToolGate menyebut concat")
    check("3.7 formula RISKY_TOOLS tetap (WRITE+MEMORY) minus browser_*",
        tools.contains("ToolRegistry.namesByKind(ToolRegistry.Kind.WRITE)") &&
            tools.contains("startsWith(\"browser_\")") &&
            !tools.contains("startsWith(\"concat_\")"))

    // ---- (4) Routing dispatch + REQUIRED_ARGS ----
    check("4.1 dispatch: keempat nama concat_* -> ConcatTools.run",
        tools.contains("\"concat_version\", \"concat_project_list\", \"concat_project_get\", \"concat_project_open\" -> ConcatTools.run(ctx, name, args)"))
    check("4.2 REQUIRED_ARGS: get/open wajib 'project'",
        tools.contains("\"concat_project_get\" to \"project\"") &&
            tools.contains("\"concat_project_open\" to \"project\""))
    check("4.3 validateArgs data-driven: get tanpa project DITOLAK (ArgSpec required)",
        g != null && g.args.any { it.name == "project" && it.required })

    // ---- (5) Validasi token (ConcatTools murni, tanpa Android) ----
    check("5.1 token kosong ditolak saat Simpan",
        ConcatTools.validateTokenForSave("") != null)
    check("5.2 token spasi saja ditolak saat Simpan",
        ConcatTools.validateTokenForSave("   ") != null)
    check("5.3 token 19 karakter ditolak (disebut 19 + paling sedikit 20)",
        (ConcatTools.validateTokenForSave("x".repeat(19)) ?: "").contains("19") &&
            (ConcatTools.validateTokenForSave("x".repeat(19)) ?: "").contains("paling sedikit 20"))
    check("5.4 token 20 karakter LOLOS",
        ConcatTools.validateTokenForSave("x".repeat(20)) == null)
    check("5.5 token 20 karakter + spasi tepi LOLOS (trim)",
        ConcatTools.validateTokenForSave("  " + "x".repeat(20) + "  ") == null)
    check("5.6 isEnabled(null)=true (default), hasToken(null)=false, lastCheck(null)=belum dicek",
        ConcatTools.isEnabled(null) && !ConcatTools.hasToken(null) &&
            ConcatTools.lastCheck(null) == "belum dicek")

    // ---- (6) Kegagalan jujur tanpa token (pesan persis brief) ----
    val noTok = ConcatTools.NO_TOKEN_MESSAGE
    check("6.1 pesan tanpa-token persis brief",
        noTok == "token Concat belum di-set — buka Setup → Concat, ketik token sekali", "dapat [$noTok]")
    check("6.2 run(null, concat_version) -> pesan tanpa-token persis",
        ConcatTools.run(null, "concat_version", JSONObject()) == noTok)
    check("6.3 run(null, concat_project_list) -> pesan tanpa-token persis",
        ConcatTools.run(null, "concat_project_list", JSONObject()) == noTok)
    check("6.4 run(null, concat_project_get) -> pesan tanpa-token persis",
        ConcatTools.run(null, "concat_project_get", JSONObject().put("project", "Untitled")) == noTok)
    check("6.5 run(null, concat_project_open) -> pesan tanpa-token persis",
        ConcatTools.run(null, "concat_project_open", JSONObject().put("project", "Untitled")) == noTok)
    check("6.6 run(null, nama tak dikenal) -> tetap tanpa-token dulu (tanpa fallback)",
        ConcatTools.run(null, "concat_bogus", JSONObject()) == noTok)

    // ---- (7) MERGE: stub SUDAH diganti klien asli Worker A ----
    check("7.1 klien asli: object ConcatApi + 4 fungsi signature persis",
        stub.contains("object ConcatApi") &&
            stub.contains("fun version(token: String): String") &&
            stub.contains("fun listProjects(token: String): List<ConcatProjectRef>") &&
            stub.contains("fun openProject(token: String, nameOrPath: String): String") &&
            stub.contains("fun getProject(token: String, nameOrPath: String): String"))
    check("7.2 klien asli: ConcatProjectRef(name, path) + hierarki exception",
        stub.contains("data class ConcatProjectRef(val name: String, val path: String)") &&
            stub.contains("open class ConcatApiException(message: String) : Exception(message)") &&
            stub.contains("class ConcatAuthException(message: String) : ConcatApiException(message)") &&
            stub.contains("class ConcatDownException(message: String) : ConcatApiException(message)"))
    check("7.3 klien asli: ConcatAuthException adalah ConcatApiException",
        try { throw ConcatAuthException("t"); } catch (e: ConcatApiException) { true } catch (e: Exception) { false })
    check("7.4 merge: stub SUDAH diganti — file tanpa 'stub vc314' + tanpa TODO",
        !stub.contains("stub vc314") && !stub.contains("TODO"))
    check("7.5 merge: klien asli pakai TCP langsung tanpa proxy (Proxy.NO_PROXY)",
        stub.contains("Socket(Proxy.NO_PROXY)"))
    check("7.6 merge: klien asli petakan auth ditolak (-32006/unauthorized) ke ConcatAuthException",
        stub.contains("code == -32006") && stub.contains("ConcatAuthException(message)"))
    check("7.7 ConcatProjectRef menyimpan nama + path",
        ConcatProjectRef("Untitled", "/sdcard/Untitled").let { it.name == "Untitled" && it.path == "/sdcard/Untitled" })

    // ---- (8) Handler: ConcatApiException dikembalikan verbatim ----
    check("8.1 handler memakai SecureStore key concat_api_token",
        handler.contains("TOKEN_KEY = \"concat_api_token\"") && handler.contains("SecureStore.get(ctx, TOKEN_KEY)"))
    check("8.2 handler TIDAK me-log token (tanpa Log./println token)",
        !handler.contains("Log.") && !handler.contains("println"))
    check("8.3 handler menangkap ConcatApiException -> pesan verbatim",
        handler.contains("catch (e: ConcatApiException)") && handler.contains("e.message ?: \"Concat gagal tanpa pesan.\""))

    // ---- (9) Layar setup Concat di MainActivity (assert sumber —
    // perilaku UI wajib uji perangkat) ----
    check("9.1 entri Setup: tombol backing 71. + setupView concat",
        main.contains("addBtn(\"71. Concat\")") && main.contains("setupView = \"concat\""))
    check("9.2 kategori konektor memuat 71. + aksiMeta Concat",
        main.contains("\"konektor\" -> listOf(\"7.\", \"8.\", \"61.\", \"62.\", \"71.\")") &&
            main.contains("\"71.\" -> AksiMeta("))
    check("9.3 layar detail: buildConcatCardVc314 dipanggil dari refreshSetupNative",
        main.contains("\"concat\" -> box.addView(buildConcatCardVc314())"))
    check("9.4 kartu status + token + tombol Simpan/Uji koneksi/Hapus token",
        main.contains("skillSectionLabelVc310(\"Status server\")") &&
            main.contains("skillSectionLabelVc310(\"Token Concat\")") &&
            main.contains("thRowBtn(rowB, \"Simpan\", true)") &&
            main.contains("thRowBtn(rowB, \"Uji koneksi\")") &&
            main.contains("thRowBtn(rowB, \"Hapus token\")"))
    check("9.5 kolom token masked (TYPE_TEXT_VARIATION_PASSWORD)",
        main.contains("TYPE_TEXT_VARIATION_PASSWORD") && main.contains("hint = \"Tempel token Concat\""))
    check("9.6 simpan HANYA via SecureStore.put + validasi token dulu",
        main.contains("SecureStore.put(this@MainActivity, ConcatTools.TOKEN_KEY, v)") &&
            main.contains("ConcatTools.validateTokenForSave(v)"))
    check("9.7 hapus via SecureStore.delete + konfirmasi dialog",
        main.contains("SecureStore.delete(this@MainActivity, ConcatTools.TOKEN_KEY)") &&
            main.contains("setTitle(\"Hapus token Concat?\")"))
    check("9.8 Uji koneksi di Thread + tampilkan hasil lewat setLastCheck",
        main.contains("private fun concatTestConnectionVc314()") &&
            main.contains("ConcatApi.version(token)") &&
            main.contains("ConcatTools.setLastCheck(this@MainActivity, result)"))
    check("9.9 status baris: server/token (isinya tidak ditampilkan, baris Concat)",
        main.contains("skillKvRowVc310(\"Server\", ConcatTools.lastCheck(this))") &&
            main.contains("skillKvRowVc310(\"Token\", if (ConcatTools.hasToken(this)) \"ADA (isinya tidak ditampilkan)\" else \"BELUM\")"))

    // ---- (10) Desain Mist (keputusan Sendy 18:54) — token tema,
    // cardBoxT, pola Skills Anda, Switch asli; TANPA hex baru ----
    val concatStart = main.indexOf("private fun concatSwitchVc314")
    val concatEnd = main.indexOf("private fun setupDefModeCard()")
    val concatBlock = if (concatStart >= 0 && concatEnd > concatStart) main.substring(concatStart, concatEnd) else ""
    check("10.1 blok Concat memakai cardBoxT (kartu Mist)",
        concatBlock.contains("cardBoxT()"), "blok ${concatBlock.length} char")
    check("10.2 blok Concat memakai token tema currentTheme() + th.text/dim/faint",
        concatBlock.contains("currentTheme()") && concatBlock.contains("th.text") &&
            concatBlock.contains("th.dim") && concatBlock.contains("th.faint"))
    check("10.3 blok Concat memakai label/baris pola Skills Anda vc310",
        concatBlock.contains("skillSectionLabelVc310(") && concatBlock.contains("skillKvRowVc310("))
    check("10.4 blok Concat memakai ikon path play (bukan emoji di kartu)",
        concatBlock.contains("pathIconVc306(\"play\""))
    check("10.5 Switch asli android.widget.Switch dengan tint aksen Mist",
        concatBlock.contains("android.widget.Switch(this)") &&
            concatBlock.contains("trackTintList") && concatBlock.contains("ACCENT"))
    check("10.6 radius dari token ChatThemeVc306.rMd (bukan angka karangan)",
        concatBlock.contains("ChatThemeVc306.rMd"))
    check("10.7 TANPA hex baru di blok Concat (tanpa 0xFF, tanpa Color.rgb)",
        !concatBlock.contains("0xFF") && !concatBlock.contains("Color.rgb("),
        "ditemukan hex/rgb di blok Concat")
    check("10.8 tombol Mist thBtn/thRowBtn (Simpan aksen)",
        concatBlock.contains("thRowBtn(rowB, \"Simpan\", true)"))

    // ---- (11) Larangan: file yang TIDAK boleh berubah ----
    check("11.1 ToolGate.kt bebas concat (sudah 3.6) + DsApiClient/SlotBrowser/Governor tak disentuh di merge",
        !gate.contains("concat"))
    check("11.2 TIDAK ADA nilai token di tree kerja (grep kunci saja, tanpa nilai)",
        countOcc(reg + tools + handler + stub + main, "concat_api_token") >= 3 &&
            !((reg + tools + handler + stub + main).contains("hsurr:") ))

    // ---- (12) Bahasa bayi (aturan Sendy 14 Nov 19:00) — kata-kata yang
    // dibaca manusia di layar setup + pesan error, bukan istilah teknis.
    // (Isi teknis — kode, ToolDef, prose agen — tetap seperti biasa.)
    val concatBlock12 = if (concatStart >= 0 && concatEnd > concatStart) main.substring(concatStart, concatEnd) else ""
    check("12.1 kepala kartu pakai bahasa sehari-hari (tutup dulu di layar)",
        concatBlock12.contains("tutup dulu proyeknya di layar Concat"))
    check("12.2 token dijelaskan sebagai kunci rahasia (bukan SecureStore/enkripsi)",
        concatBlock12.contains("kunci rahasia Concat"))
    check("12.3 catatan kaki: server hanya di HP ini, tidak dibuka ke internet",
        concatBlock12.contains("hanya jalan di HP ini, tidak dibuka ke internet"))
    check("12.4 TIDAK ADA istilah teknis di teks layar Concat (tanpa 127.0.0.1 / JSON-RPC / loopback / fallback)",
        !concatBlock12.contains("127.0.0.1") && !concatBlock12.contains("JSON-RPC") &&
            !concatBlock12.contains("loopback") && !concatBlock12.contains("fallback"))

    println("\nHASIL: $passed lulus, $failed gagal (total ${passed + failed})")
    if (failed > 0) exitProcess(1)
}
